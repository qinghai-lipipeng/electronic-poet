package com.otaku.poet.data

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.otaku.poet.jni.NativePoet
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

    /** 重新扫描挂载目录并打开目标包。 */
    fun refresh(preferPath: String? = null, params: GenParams? = null) {
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

    /** 生成一首诗。 */
    fun generate() {
        if (handle == 0L) {
            _state.update { it.copy(message = "尚未挂载词库包，请先在“词库管理”中导入") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val current = _state.value.params
            // 保证正数（Rust 端为 u64）。
            val seed = current.seed ?: (random.nextLong() ushr 1)
            val params = current.copy(seed = seed)
            try {
                val json = NativePoet.generate(handle, params.toJson())
                val poem = PoemResult.fromJson(JSONObject(json))
                settings.saveParams(params)
                // 每次生成自动写入 /sdcard/Download/cyberpoet/*.txt
                val saveMsg = PoemSaver.save(getApplication(), poem).fold(
                    onSuccess = { "已保存：Download/cyberpoet/$it" },
                    onFailure = { "保存失败：${it.message}" },
                )
                _state.update {
                    it.copy(
                        busy = false,
                        params = params,
                        poem = poem,
                        message = poem.warnings.firstOrNull() ?: saveMsg,
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false, message = "生成失败：${e.message}") }
            }
        }
    }

    fun importZip(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching { repo.importZip(uri) }
            .onSuccess { pack ->
                _state.update { it.copy(busy = false, message = "已导入：${pack.name}") }
                refresh(pack.path)
            }
            .onFailure { e ->
                _state.update { it.copy(busy = false, message = "导入失败：${e.message}") }
            }
    }

    fun importTree(uri: Uri) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching { repo.importTree(uri) }
            .onSuccess { pack ->
                _state.update { it.copy(busy = false, message = "已导入：${pack.name}") }
                refresh(pack.path)
            }
            .onFailure { e ->
                _state.update { it.copy(busy = false, message = "导入失败：${e.message}") }
            }
    }

    fun deletePack(path: String) {
        val activePath = _state.value.active?.path
        repo.deletePack(path)
        refresh(if (path != activePath) activePath else null)
    }

    /** 收藏当前诗作到 App 私有目录。 */
    fun favorite(poem: PoemResult) {
        val dir = File(getApplication<Application>().filesDir, "favorites").apply { mkdirs() }
        val name = (poem.title ?: "无题") + "-" + poem.seed + ".txt"
        File(dir, name).writeText(poem.fullText)
        _state.update { it.copy(message = "已收藏：$name") }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        super.onCleared()
        if (handle != 0L) runCatching { NativePoet.close(handle) }
    }
}
