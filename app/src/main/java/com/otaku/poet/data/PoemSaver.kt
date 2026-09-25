package com.otaku.poet.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每次生成后自动把诗作以 txt 写入公共下载目录的 cyberpoet 子目录：
 *   /sdcard/Download/cyberpoet/poem_yyyyMMdd_HHmmss.txt
 *
 * API 29+ 用 MediaStore（无需存储权限）；
 * API 26-28 用传统 File（需 WRITE_EXTERNAL_STORAGE，已在 Manifest 声明 maxSdkVersion=28）。
 */
object PoemSaver {

    private const val SUB_DIR = "cyberpoet"

    fun save(context: Context, poem: PoemResult): Result<String> {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "poem_$ts.txt"
        // fullText 已保证段与段之间以空行分隔；末尾用分隔线标注元信息。
        val content = buildString {
            append(poem.fullText)
            append("\n————————————————\n")
            append("词库：${poem.packName}\n")
            append("种子：${poem.seed}\n")
            append("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        }
        return try {
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, content)
            } else {
                saveViaFile(fileName, content)
            }
            if (ok) Result.success(fileName) else Result.failure(Exception("写入返回失败"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, fileName: String, content: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
        }
        val uri = context.contentResolver
            .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        context.contentResolver.openOutputStream(uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: return false
        return true
    }

    private fun saveViaFile(fileName: String, content: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR,
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return true
    }
}
