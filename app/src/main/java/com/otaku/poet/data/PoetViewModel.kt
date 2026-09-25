package com.otaku.poet.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otaku.poet.jni.NativePoet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

data class UiState(
    val initializing: Boolean = true,
    val packs: List<PackSummary> = emptyList(),
    val active: PackSummary? = null,
    val params: GenParams = GenParams(),
    val poem: PoemResult? = null,
    val busy: Boolean = false,
    val message: String? = null,
    /** 生成进度：已生成的段落数（仅在 showInUi=false 时用于显示进度）。 */
    val generatingProgress: Int = 0,
    /** 生成总段落数（用于进度显示）。 */
    val generatingTotal: Int = 0,
)

class PoetViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PackRepository(app)
    private val settings = SettingsStore(app)
    private val random = SecureRandom()
    private var handle: Long = 0L

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val saved = settings.flow.first()
            refresh(saved.activePackPath?.ifEmpty { null }, saved.params)
        }
    }

    /**
     * 生成过程中禁止切换 / 导入 / 删除词库包。
     * 这些操作会替换或释放引擎句柄，而生成协程仍在向旧句柄取段落。
     */
    private fun ensureIdle(action: String): Boolean {
        if (!_state.value.busy) return true
        _state.update { it.copy(message = "生成中，请等本次创作完成后再$action") }
        return false
    }

    /** 重新扫描挂载目录并打开目标包。生成过程中不切换：旧句柄仍被生成协程使用。 */
    fun refresh(preferPath: String? = null, params: GenParams? = null) {
        if (!ensureIdle("切换词库包")) return
        refreshInternal(preferPath, params)
    }

    /** 重新扫描挂载目录并打开目标包（不做 busy 检查，供内部流程调用）。 */
    private fun refreshInternal(preferPath: String? = null, params: GenParams? = null) {
        val packs = try {
            repo.listPacks()
        } catch (e: Throwable) {
            _state.update { it.copy(initializing = false, message = "扫描词库失败：${e.message}") }
            return
        }
        val target = packs.firstOrNull { it.path == preferPath } ?: packs.firstOrNull()
        openPack(target)
        _state.update {
            it.copy(
                initializing = false,
                packs = packs,
                active = target,
                params = params ?: it.params,
            )
        }
    }

    private fun openPack(p: PackSummary?) {
        if (handle != 0L) {
            runCatching { NativePoet.close(handle) }
            handle = 0L
        }
        if (p != null) {
            handle = try {
                NativePoet.open(p.path)
            } catch (e: Throwable) {
                _state.update { it.copy(message = "打开词库包失败：${e.message}") }
                0L
            }
        }
        viewModelScope.launch { settings.setActivePack(p?.path) }
    }

    fun selectPack(path: String) = refresh(path)

    fun updateParams(transform: (GenParams) -> GenParams) {
        _state.update { it.copy(params = transform(it.params)) }
    }

    /**
     * 生成一首诗（流式：边生成边写入文件）。
     *
     * - showInUi=true：逐段在 UI 显示（内存会随段落增长，适合短诗）；
     * - showInUi=false（默认）：不在 UI 累积文本，仅写文件、显示进度数字，
     *   可支持超长篇幅而不 OOM。
     * 内存占用超过用户设定的可用内存百分比时自动停止，保留已生成部分。
     */
    fun generate() {
        if (handle == 0L) {
            _state.update { it.copy(message = "尚未挂载词库包，请先在“词库管理”中导入") }
            return
        }
        // 连点保护：busy 在启动协程前同步置位，避免两次生成并发操作同一个 native 会话。
        if (_state.value.busy) return
        _state.update {
            it.copy(
                busy = true,
                poem = null,
                generatingProgress = 0,
                generatingTotal = it.params.paragraphs,
            )
        }

        // 生成循环里有 JNI 阻塞调用与文件写入，必须离开主线程，否则界面冻结 / ANR。
        viewModelScope.launch(Dispatchers.IO) {
            val current = _state.value.params
            // 「留空 = 每次随机」：随机种子只用于本次生成，不回写参数、不持久化，
            // 否则之后的每次创作都会复用同一颗种子，生成完全相同的诗。
            val seed = current.seed ?: (random.nextLong() ushr 1)
            val genJson = current.copy(seed = seed).toJson()
            val ctx = getApplication<Application>()
            val showInUi = current.showInUi

            var writer: PoemWriter? = null
            var memoryStopped = false
            try {
                // 1. 开始流式生成，获取标题
                val beginJson = JSONObject(NativePoet.beginGenerate(handle, genJson))
                val title = if (beginJson.isNull("title")) null
                else beginJson.optString("title").ifEmpty { null }
                val actualSeed = beginJson.optLong("seed", seed)

                // 2. 打开流式写入器（边生成边写文件）
                writer = PoemSaver.openStream(ctx, title).getOrThrow()

                // 仅在 showInUi 时才累积段落文本
                val paragraphs = if (showInUi) mutableListOf<List<String>>() else null
                val rhymes = if (showInUi) mutableListOf<String?>() else null
                val activePack = _state.value.active

                // UI 更新间隔：总段数越多间隔越大，目标总共更新约 200 次，
                // 避免千万次级 StateFlow 更新导致主线程阻塞 / OOM。
                val uiUpdateInterval = maxOf(1, current.paragraphs / 200)
                // 内存检查间隔：至少每 MemoryGuard.CHECK_INTERVAL 段，大篇幅时更稀疏。
                val memCheckInterval = maxOf(MemoryGuard.CHECK_INTERVAL, current.paragraphs / 1000)

                // 3. 逐段生成
                var index = 0
                while (true) {
                    val nextJson = JSONObject(NativePoet.nextParagraph(handle))
                    if (nextJson.optBoolean("done", false)) break

                    val linesArr = nextJson.optJSONArray("lines") ?: break
                    val lines = List(linesArr.length()) { linesArr.getString(it) }
                    val rhyme = if (nextJson.isNull("rhyme")) null
                    else nextJson.optString("rhyme").ifEmpty { null }

                    // 立即写入文件（无论是否显示）
                    writer.appendParagraph(lines)

                    index++

                    // 按间隔更新 UI，避免高频重组
                    if (index % uiUpdateInterval == 0 || index == current.paragraphs) {
                        if (showInUi) {
                            paragraphs!!.add(lines)
                            rhymes!!.add(rhyme)
                            val partial = PoemResult(
                                title = title,
                                paragraphs = paragraphs.toList(),
                                packId = activePack?.id ?: "",
                                packName = activePack?.name ?: "",
                                seed = actualSeed,
                                rhymes = rhymes.toList(),
                                warnings = emptyList(),
                            )
                            _state.update { it.copy(poem = partial, generatingProgress = index) }
                        } else {
                            _state.update { it.copy(generatingProgress = index) }
                        }
                    } else if (showInUi) {
                        // 非更新点也要累积数据，下次更新时一并提交
                        paragraphs!!.add(lines)
                        rhymes!!.add(rhyme)
                    }

                    // 内存检查
                    if (index % memCheckInterval == 0 &&
                        MemoryGuard.isOverLimit(ctx, current.memoryLimitPercent)
                    ) {
                        memoryStopped = true
                        break
                    }
                }

                // 4. 结束生成，获取 warnings
                val warningsArr = org.json.JSONArray(NativePoet.endGenerate(handle))
                val warnings = List(warningsArr.length()) { warningsArr.getString(it) }

                // 5. 完成写入（元信息 + 关闭流）
                writer.finish(activePack?.name ?: "", actualSeed)

                // 持久化用户参数（生成期间可能已改动，且本次随机种子不回写）。
                // 放在写完文件之后：此处是挂起点，若被取消不该影响已完成的诗作。
                settings.saveParams(_state.value.params)

                val saveMsg =
                    "已保存：Download/cyberpoet/${writer.fileName}（共 $index 段，种子 $actualSeed）"
                val stopMsg = if (memoryStopped)
                    "内存达到上限（可用内存${current.memoryLimitPercent}%），已停止，已生成 $index 段并保存"
                else null

                if (showInUi) {
                    val finalPoem = PoemResult(
                        title = title,
                        paragraphs = paragraphs!!.toList(),
                        packId = activePack?.id ?: "",
                        packName = activePack?.name ?: "",
                        seed = actualSeed,
                        rhymes = rhymes!!.toList(),
                        warnings = warnings,
                    )
                    _state.update {
                        it.copy(
                            busy = false,
                            poem = finalPoem,
                            generatingProgress = 0,
                            generatingTotal = 0,
                            message = stopMsg ?: warnings.firstOrNull() ?: saveMsg,
                        )
                    }
                } else {
                    // 不显示内容，poem 保持 null，仅通过 message 告知结果
                    _state.update {
                        it.copy(
                            busy = false,
                            poem = null,
                            generatingProgress = 0,
                            generatingTotal = 0,
                            message = stopMsg ?: warnings.firstOrNull() ?: saveMsg,
                        )
                    }
                }
            } catch (e: Throwable) {
                writer?.abort()
                runCatching { NativePoet.endGenerate(handle) }
                _state.update {
                    it.copy(
                        busy = false,
                        generatingProgress = 0,
                        generatingTotal = 0,
                        message = "生成失败：${e.message}",
                    )
                }
            }
        }
    }

    fun importZip(uri: Uri) {
        if (!ensureIdle("导入词库包")) return
        // 同步置位 busy：避免与生成或另一次导入并发争用引擎句柄。
        _state.update { it.copy(busy = true) }
        // 解压与拷贝是阻塞 IO，放到后台线程执行。
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.importZip(uri) }
                .onSuccess { pack ->
                    // 重新挂载完成前保持 busy：避免此刻开始的生成与换包争用引擎句柄。
                    refreshInternal(pack.path)
                    _state.update { it.copy(busy = false, message = "已导入：${pack.name}") }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, message = "导入失败：${e.message}") }
                }
        }
    }

    fun importTree(uri: Uri) {
        if (!ensureIdle("导入词库包")) return
        // 同步置位 busy：避免与生成或另一次导入并发争用引擎句柄。
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repo.importTree(uri) }
                .onSuccess { pack ->
                    // 重新挂载完成前保持 busy：避免此刻开始的生成与换包争用引擎句柄。
                    refreshInternal(pack.path)
                    _state.update { it.copy(busy = false, message = "已导入：${pack.name}") }
                }
                .onFailure { e ->
                    _state.update { it.copy(busy = false, message = "导入失败：${e.message}") }
                }
        }
    }

    fun deletePack(path: String) {
        if (!ensureIdle("删除词库包")) return
        val activePath = _state.value.active?.path
        repo.deletePack(path)
        refresh(if (path != activePath) activePath else null)
    }

    /** 收藏当前诗作到 App 私有目录。 */
    fun favorite(poem: PoemResult) {
        val dir = File(getApplication<Application>().filesDir, "favorites").apply { mkdirs() }
        // 标题由词库模板填出，可能含 / 等文件名非法字符，需过滤后再作文件名。
        val title = (poem.title ?: "无题")
            .replace(Regex("[\\\\/:*?\"<>|\r\n\t]"), "_")
            .take(60)
            .ifBlank { "无题" }
        val name = "$title-${poem.seed}.txt"
        File(dir, name).writeText(poem.fullText)
        _state.update { it.copy(message = "已收藏：$name") }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        super.onCleared()
        // 生成中不释放句柄：后台生成协程可能仍在调用它，交给进程回收以避免悬空指针。
        if (handle != 0L && !_state.value.busy) {
            runCatching { NativePoet.close(handle) }
            handle = 0L
        }
    }
}
