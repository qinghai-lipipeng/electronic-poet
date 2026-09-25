package com.otaku.poet.jni

/**
 * Rust 核心引擎（libpoet_core.so）的 JNI 桥接。
 *
 * 引擎是一个纯粹的“模板匹配 + 嵌词器”，语言资源（词库 / 语法 / 韵库）
 * 全部来自挂载目录，不内嵌于程序。
 */
object NativePoet {

    init {
        System.loadLibrary("poet_core")
    }

    /** 核心引擎版本。 */
    fun version(): String = nativeVersion()

    /** 扫描挂载根目录，返回 PackSummary 的 JSON 数组字符串。 */
    fun listPacks(rootDir: String): String = nativeListPacks(rootDir)

    /** 加载词库包，返回不透明句柄；失败由 native 抛出异常。 */
    fun open(packDir: String): Long = nativeOpen(packDir)

    /** 按 JSON 参数生成诗歌，返回 PoemOut 的 JSON 字符串。 */
    fun generate(handle: Long, optionsJson: String): String =
        nativeGenerate(handle, optionsJson)

    /** 开始流式生成，返回 `{"title":...,"seed":...}`。 */
    fun beginGenerate(handle: Long, optionsJson: String): String =
        nativeBeginGenerate(handle, optionsJson)

    /** 生成下一段，返回 `{"lines":[...],"rhyme":...}` 或 `{"done":true}`。 */
    fun nextParagraph(handle: Long): String = nativeNextParagraph(handle)

    /** 结束流式生成，返回 warnings 的 JSON 数组。 */
    fun endGenerate(handle: Long): String = nativeEndGenerate(handle)

    /** 释放词库包句柄。 */
    fun close(handle: Long) = nativeClose(handle)

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeListPacks(rootDir: String): String

    @JvmStatic
    private external fun nativeOpen(packDir: String): Long

    @JvmStatic
    private external fun nativeGenerate(handle: Long, optionsJson: String): String

    @JvmStatic
    private external fun nativeBeginGenerate(handle: Long, optionsJson: String): String

    @JvmStatic
    private external fun nativeNextParagraph(handle: Long): String

    @JvmStatic
    private external fun nativeEndGenerate(handle: Long): String

    @JvmStatic
    private external fun nativeClose(handle: Long)
}
