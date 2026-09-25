package com.otaku.poet.data

import org.json.JSONArray
import org.json.JSONObject

/** 词库包摘要（对应 Rust 端 PackSummary）。 */
data class PackSummary(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val path: String,
    val templates: Int,
    val titleTemplates: Int,
    val lexiconWords: Int,
    val lexiconCodes: List<String>,
    val rhymes: List<String>,
) {
    companion object {
        private fun JSONArray.toStringList(): List<String> =
            List(length()) { getString(it) }

        fun fromJson(o: JSONObject): PackSummary = PackSummary(
            id = o.optString("id"),
            name = o.optString("name").ifEmpty { o.optString("id") },
            version = o.optString("version"),
            author = o.optString("author"),
            description = o.optString("description"),
            path = o.optString("path"),
            templates = o.optInt("templates"),
            titleTemplates = o.optInt("titleTemplates"),
            lexiconWords = o.optInt("lexiconWords"),
            lexiconCodes = o.optJSONArray("lexiconCodes")?.toStringList().orEmpty(),
            rhymes = o.optJSONArray("rhymes")?.toStringList().orEmpty(),
        )
    }
}

/** 生成结果（对应 Rust 端 PoemOut）。 */
data class PoemResult(
    val title: String?,
    val paragraphs: List<List<String>>,
    val packId: String,
    val packName: String,
    val seed: Long,
    val rhymes: List<String?>,
    val warnings: List<String>,
) {
    /** 排版后的完整文本，用于复制 / 分享 / 导出。段与段之间以空行分隔。 */
    val fullText: String
        get() = buildString {
            title?.let { append("《").append(it).append("》\n\n") }
            paragraphs.forEachIndexed { idx, stanza ->
                if (idx > 0) append('\n') // 段前空一行，明确划分段落
                stanza.forEach { append(it).append('\n') }
            }
        }

    companion object {
        fun fromJson(o: JSONObject): PoemResult {
            val paragraphsArr = o.optJSONArray("paragraphs") ?: JSONArray()
            val paragraphs = List(paragraphsArr.length()) { i ->
                val stanza = paragraphsArr.getJSONArray(i)
                List(stanza.length()) { j -> stanza.getString(j) }
            }
            val rhymesArr = o.optJSONArray("rhymes")
            val rhymes = if (rhymesArr == null) emptyList() else List(rhymesArr.length()) {
                if (rhymesArr.isNull(it)) null else rhymesArr.getString(it)
            }
            val warningsArr = o.optJSONArray("warnings")
            val warnings = warningsArr?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
            return PoemResult(
                title = if (o.isNull("title")) null else o.optString("title").ifEmpty { null },
                paragraphs = paragraphs,
                packId = o.optString("packId"),
                packName = o.optString("packName"),
                seed = o.optLong("seed"),
                rhymes = rhymes,
                warnings = warnings,
            )
        }
    }
}

/** 生成参数（对应 Rust 端 GenOptions，外加纯客户端设置）。 */
data class GenParams(
    val paragraphs: Int = 3,
    val linesPerParagraph: Int = 4,
    val rhyme: Boolean = false,
    val rhymeId: String? = null,
    val perParagraphRhyme: Boolean = true,
    val everyLine: Boolean = true,
    val makeTitle: Boolean = true,
    val seed: Long? = null,
    /** 纯客户端：是否在 UI 界面逐段显示生成内容。关闭时仅写文件、不累积文本，避免大篇幅 OOM。 */
    val showInUi: Boolean = false,
    /** 纯客户端：内存上限占设备可用内存的百分比（1-100）。 */
    val memoryLimitPercent: Int = 60,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("paragraphs", paragraphs)
        o.put("linesPerParagraph", linesPerParagraph)
        o.put("rhyme", rhyme)
        o.put("rhymeId", rhymeId ?: JSONObject.NULL)
        o.put("perParagraphRhyme", perParagraphRhyme)
        o.put("rhymeScheme", if (everyLine) "every" else "alternate")
        o.put("makeTitle", makeTitle)
        o.put("seed", seed ?: JSONObject.NULL)
        return o.toString()
    }
}
