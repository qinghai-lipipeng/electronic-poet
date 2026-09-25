package com.otaku.poet.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把诗作以 txt 写入公共下载目录的 cyberpoet 子目录：
 *   /sdcard/Download/cyberpoet/poem_yyyyMMdd_HHmmss_SSS.txt
 *
 * 支持两种模式：
 * - [save]：一次性写入整首诗（兼容旧接口）；
 * - [openStream]：返回 [PoemWriter]，边生成边逐段写入（流式，内存占用低）。
 *
 * API 29+ 用 MediaStore（无需存储权限）；
 * API 26-28 用传统 File（需 WRITE_EXTERNAL_STORAGE，已在 Manifest 声明 maxSdkVersion=28）。
 */
object PoemSaver {

    private const val SUB_DIR = "cyberpoet"

    /** 一次性保存整首诗（兼容接口）。 */
    fun save(context: Context, poem: PoemResult): Result<String> {
        val writer = openStream(context, poem.title).getOrElse { return Result.failure(it) }
        poem.paragraphs.forEach { writer.appendParagraph(it) }
        writer.finish(poem.packName, poem.seed)
        return Result.success(writer.fileName)
    }

    /**
     * 打开一个流式写入器，返回 [PoemWriter]。
     * 调用方随后逐段调用 [PoemWriter.appendParagraph]，最后调用 [PoemWriter.finish]。
     */
    fun openStream(context: Context, title: String?): Result<PoemWriter> {
        // 带毫秒：API<29 走 File 写入，同一秒内两次生成会互相覆盖。
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
        val fileName = "poem_$ts.txt"
        return try {
            val out: OutputStream = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openMediaStoreStream(context, fileName, title)
            } else {
                openFileStream(fileName, title)
            }
            Result.success(PoemWriter(out, fileName))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 写入文件头部的标题（若有）。 */
    private fun writeTitle(out: OutputStream, title: String?) {
        title?.let {
            out.write("《".toByteArray(Charsets.UTF_8))
            out.write(it.toByteArray(Charsets.UTF_8))
            out.write("》\n\n".toByteArray(Charsets.UTF_8))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openMediaStoreStream(
        context: Context,
        fileName: String,
        title: String?,
    ): OutputStream {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore 创建文件失败")
        return try {
            val out = context.contentResolver.openOutputStream(uri)
                ?: error("MediaStore 打开输出流失败")
            writeTitle(out, title)
            out
        } catch (e: Exception) {
            // 打开流或写标题失败都要回滚，避免在公共目录留下空文件。
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun openFileStream(fileName: String, title: String?): OutputStream {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR,
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw Exception("无法创建目录：${dir.absolutePath}")
        }
        val file = File(dir, fileName)
        val out = file.outputStream()
        try {
            writeTitle(out, title)
        } catch (e: Exception) {
            runCatching { out.close() }
            file.delete()
            throw e
        }
        return out
    }
}

/**
 * 流式诗作写入器：边生成边写入，每段立即 flush，内存中不累积全诗。
 */
class PoemWriter(
    private val out: OutputStream,
    val fileName: String,
) {
    private var firstParagraph = true
    private var closed = false

    /** 追加一段文本，段与段之间以空行分隔。 */
    fun appendParagraph(lines: List<String>) {
        if (closed) return
        if (!firstParagraph) {
            out.write("\n".toByteArray(Charsets.UTF_8)) // 段前空一行
        }
        firstParagraph = false
        for (line in lines) {
            out.write(line.toByteArray(Charsets.UTF_8))
            out.write("\n".toByteArray(Charsets.UTF_8))
        }
        out.flush()
    }

    /** 写入元信息并关闭流。 */
    fun finish(packName: String, seed: Long) {
        if (closed) return
        closed = true
        out.write("\n————————————————\n".toByteArray(Charsets.UTF_8))
        out.write("词库：$packName\n".toByteArray(Charsets.UTF_8))
        out.write("种子：$seed\n".toByteArray(Charsets.UTF_8))
        out.write(
            "时间：${
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            }\n".toByteArray(Charsets.UTF_8)
        )
        out.flush()
        out.close()
    }

    /** 异常中止时关闭流（不写元信息）。 */
    fun abort() {
        if (closed) return
        closed = true
        runCatching { out.close() }
    }
}
