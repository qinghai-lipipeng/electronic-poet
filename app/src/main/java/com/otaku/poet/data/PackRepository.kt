package com.otaku.poet.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.otaku.poet.jni.NativePoet
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 词库包挂载仓库。
 *
 * 挂载根目录为 App 专属外部目录：`Android/data/<pkg>/files/poet-packs/`，
 * 无需任何存储权限。用户可：
 * - 通过 SAF 导入 zip 词库包 / 整个目录；
 * - 用文件管理器或 adb 直接向该目录增删词库包；
 * - 手工编辑其中任意文本文件。
 */
class PackRepository(private val context: Context) {

    val mountRoot: File by lazy {
        File(context.getExternalFilesDir(null), "poet-packs").apply { mkdirs() }
    }

    /** 首次运行：写入挂载说明（不是语言资源，仅指引）。 */
    fun ensureLayout() {
        if (!mountRoot.exists()) mountRoot.mkdirs()
        val readme = File(mountRoot, "README-挂载说明.txt")
        if (!readme.exists()) {
            readme.writeText(
                """
                这里是“电子诗人”的词库包挂载目录。

                每个子目录是一个词库包，结构为：
                  <包>/
                    manifest.conf      元信息（id/name/version/...）
                    grammar/*.txt      句式模板，连续两个大写字母为占位符，如 MM在DD
                    lexicon/<CODE>.txt 词库，每行一个词，CODE 为两个大写字母
                    rhyme/<韵>.txt     （可选）韵部，每行 CODE=词

                词库包可随时增删、替换、手工编辑；应用会在进入词库管理时重新扫描。
                也可以在应用内通过“导入”选择 zip 包或目录。
                """.trimIndent()
            )
        }
    }

    /** 扫描挂载根目录下全部词库包。 */
    fun listPacks(): List<PackSummary> {
        ensureLayout()
        val json = NativePoet.listPacks(mountRoot.absolutePath)
        val arr = JSONArray(json)
        return List(arr.length()) { PackSummary.fromJson(arr.getJSONObject(it)) }
    }

    /** 从 SAF 选取的 zip 文件导入，返回新导入的包；失败抛异常。 */
    fun importZip(uri: Uri): PackSummary {
        val stamp = System.currentTimeMillis()
        val tmp = File(mountRoot, ".tmp-$stamp").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            // 防止 zip slip。
                            val parts = entry.name.split('/', '\\').filter { it.isNotEmpty() }
                            if (parts.any { it == ".." }) {
                                error("压缩包含非法路径：${entry.name}")
                            }
                            val target = File(tmp, parts.joinToString(File.separator))
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: error("无法读取所选文件")

            val sourcePackDir = findPackDir(tmp)
                ?: error("压缩包中未找到包含 manifest.conf 的词库包")
            return installPack(sourcePackDir)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 从 SAF 选取的目录树导入。 */
    fun importTree(treeUri: Uri): PackSummary {
        val stamp = System.currentTimeMillis()
        val tmp = File(mountRoot, ".tmp-tree-$stamp").apply { mkdirs() }
        try {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("无法打开所选目录")
            copyDocumentTree(root, tmp)
            val sourcePackDir = findPackDir(tmp)
                ?: error("所选目录中未找到包含 manifest.conf 的词库包")
            return installPack(sourcePackDir)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 删除词库包（按路径）。 */
    fun deletePack(path: String): Boolean {
        val f = File(path)
        // 仅允许删除挂载根目录内的内容。
        if (!f.absolutePath.startsWith(mountRoot.absolutePath)) return false
        return f.deleteRecursively()
    }

    /** 递归复制 SAF 文档树到本地目录。 */
    private fun copyDocumentTree(node: DocumentFile, dest: File) {
        if (node.isDirectory) {
            dest.mkdirs()
            for (child in node.listFiles()) {
                copyDocumentTree(child, File(dest, child.name ?: "unnamed"))
            }
        } else {
            dest.parentFile?.mkdirs()
            context.contentResolver.openInputStream(node.uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }

    /** 在解压目录中定位含 manifest.conf 的目录（本身或唯一子目录）。 */
    private fun findPackDir(dir: File): File? {
        if (File(dir, "manifest.conf").exists()) return dir
        val children = dir.listFiles()?.filter { it.isDirectory }.orEmpty()
        children.forEach { c ->
            if (File(c, "manifest.conf").exists()) return c
        }
        // 再深一层。
        children.forEach { c ->
            c.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                if (File(d, "manifest.conf").exists()) return d
            }
        }
        return null
    }

    /** 把临时目录中的包安装到挂载根（同名覆盖），并返回摘要。 */
    private fun installPack(source: File): PackSummary {
        // 用 manifest 中的 id 作为目标目录名；缺失则用原目录名。
        val id = parseManifestId(File(source, "manifest.conf"))
            ?: source.name
        val safeId = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { source.name }
        val target = File(mountRoot, safeId)
        if (target.exists()) target.deleteRecursively()
        if (!source.renameTo(target)) {
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
        }
        return listPacks().firstOrNull { File(it.path) == target }
            ?: error("导入后无法读取词库包")
    }

    private fun parseManifestId(manifest: File): String? {
        if (!manifest.exists()) return null
        manifest.useLines { lines ->
            for (line in lines) {
                val t = line.trim()
                if (t.startsWith("id") && t.contains('=')) {
                    return t.substringAfter('=').trim().ifEmpty { null }
                }
            }
        }
        return null
    }
}
