package com.otaku.poet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otaku.poet.data.GenParams
import com.otaku.poet.data.PackSummary
import com.otaku.poet.data.PoemResult
import com.otaku.poet.data.PoetViewModel

/** 十三辙 id → 中文名。 */
val RHYME_LABELS = mapOf(
    "fa" to "发花", "suo" to "梭波", "mie" to "乜斜", "yi" to "一七",
    "gu" to "姑苏", "huai" to "怀来", "hui" to "灰堆", "yao" to "遥条",
    "you" to "由求", "yan" to "言前", "ren" to "人辰", "jiang" to "江阳",
    "zhong" to "中东",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComposeScreen(
    onOpenPacks: () -> Unit,
    onOpenAbout: () -> Unit,
    vm: PoetViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("电子诗人", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenPacks) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "词库管理")
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "关于")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.active == null) {
                item { EmptyPackCard(onOpenPacks) }
            }
            item { ActivePackCard(state.active) }
            item { ParamsCard(state.active, state.params, vm) }
            item {
                Button(
                    onClick = vm::generate,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.active != null && !state.busy,
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            "创作中…",
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    } else {
                        Text("创 作", modifier = Modifier.padding(4.dp))
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = state.busy,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    SectionCard {
                        val progress = if (state.generatingTotal > 0)
                            (state.generatingProgress.toFloat() / state.generatingTotal).coerceIn(0f, 1f)
                        else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "已生成 ${state.generatingProgress} / ${state.generatingTotal} 段" +
                                if (state.params.showInUi) "" else "（仅写入文件，不在界面显示）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
            state.poem?.let { poem ->
                item { PoemCard(poem, vm) }
            }
        }
    }
}

@Composable
private fun EmptyPackCard(onOpenPacks: () -> Unit) {
    SectionCard {
        Text(
            "还没有挂载任何词库包。\n" +
                "电子诗人的词库 / 语法均为外部挂载，不内嵌于应用。\n" +
                "请进入“词库管理”导入词库包（zip 或目录）。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenPacks, modifier = Modifier.padding(top = 10.dp)) {
            Text("前往词库管理")
        }
    }
}

@Composable
private fun ActivePackCard(active: PackSummary?) {
    SectionCard(title = "当前词库包") {
        if (active == null) {
            Text("未选择", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                active.name,
                style = MaterialTheme.typography.titleLarge,
            )
            if (active.author.isNotEmpty()) {
                Text(
                    active.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active.description.isNotEmpty()) {
                Text(
                    active.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Text(
                "模板 ${active.templates} 条 · 词目 ${active.lexiconWords} 词 · 韵部 ${active.rhymes.size} 个",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamsCard(
    active: PackSummary?,
    params: GenParams,
    vm: PoetViewModel,
) {
    SectionCard(title = "参数") {
        Stepper("段落数", params.paragraphs, min = 1) { v ->
            vm.updateParams { it.copy(paragraphs = v) }
        }
        Stepper("每段行数", params.linesPerParagraph, min = 1) { v ->
            vm.updateParams { it.copy(linesPerParagraph = v) }
        }

        SwitchRow(
            label = "押韵",
            checked = params.rhyme,
            onChange = { v -> vm.updateParams { it.copy(rhyme = v) } },
        )

        if (params.rhyme) {
            Text(
                "韵部",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = params.rhymeId == null,
                    onClick = { vm.updateParams { it.copy(rhymeId = null) } },
                    label = { Text("自动") },
                )
                active?.rhymes?.forEach { rid ->
                    FilterChip(
                        selected = params.rhymeId == rid,
                        onClick = { vm.updateParams { it.copy(rhymeId = rid) } },
                        label = { Text(RHYME_LABELS[rid] ?: rid) },
                    )
                }
            }
            SwitchRow(
                label = "每段换韵",
                checked = params.perParagraphRhyme,
                onChange = { v -> vm.updateParams { it.copy(perParagraphRhyme = v) } },
            )
            SwitchRow(
                label = "隔行押韵",
                description = "关闭则每行都押韵",
                checked = !params.everyLine,
                onChange = { v -> vm.updateParams { it.copy(everyLine = !v) } },
            )
        }

        SwitchRow(
            label = "生成标题",
            checked = params.makeTitle,
            onChange = { v -> vm.updateParams { it.copy(makeTitle = v) } },
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp),
        ) {
            OutlinedTextField(
                value = params.seed?.toString() ?: "",
                onValueChange = { txt ->
                    val v = txt.filter { it.isDigit() }.toLongOrNull()
                    vm.updateParams { it.copy(seed = v) }
                },
                label = { Text("随机种子（仅支持数字，留空 = 每次随机）") },
                placeholder = { Text("仅支持数字") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { vm.updateParams { it.copy(seed = null) } },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(Icons.Filled.Casino, contentDescription = "随机种子")
            }
        }

        SwitchRow(
            label = "界面显示生成内容",
            description = "关闭时仅写文件、不在界面累积文本，支持超长篇幅不闪退",
            checked = params.showInUi,
            onChange = { v -> vm.updateParams { it.copy(showInUi = v) } },
        )

        Text(
            "内存上限：可用内存的 ${params.memoryLimitPercent}%",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Slider(
            value = params.memoryLimitPercent.toFloat(),
            onValueChange = { v ->
                vm.updateParams { it.copy(memoryLimitPercent = v.toInt().coerceIn(1, 100)) }
            },
            valueRange = 1f..100f,
            steps = 98, // 1% 粒度
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PoemCard(poem: PoemResult, vm: PoetViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    SectionCard {
        poem.title?.let {
            Text(
                "《$it》",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            )
        }
        poem.paragraphs.forEach { stanza ->
            Column(Modifier.padding(bottom = 10.dp)) {
                stanza.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ActionIcon(Icons.Filled.Refresh, "再写") { vm.generate() }
            ActionIcon(Icons.Filled.ContentCopy, "复制") {
                copyToClipboard(context, poem.fullText)
            }
            ActionIcon(Icons.Filled.Share, "分享") { shareText(context, poem.fullText) }
            ActionIcon(Icons.Filled.FavoriteBorder, "收藏") { vm.favorite(poem) }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("电子诗人", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "分享诗作"))
}
