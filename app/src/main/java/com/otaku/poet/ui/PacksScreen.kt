package com.otaku.poet.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.otaku.poet.data.PackSummary
import com.otaku.poet.data.PoetViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacksScreen(
    onBack: () -> Unit,
    vm: PoetViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<PackSummary?>(null) }

    // 导入 zip。
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importZip(uri) }

    // 导入目录。
    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.importTree(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词库管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "挂载目录") {
                    Text(
                        "词库 / 语法 / 韵库均为外部挂载，可随时替换，不内嵌于应用。\n" +
                            "你可以直接用文件管理器或 adb 向下面的目录增删词库包，" +
                            "也可以在此导入 zip 或目录：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        state.packs.firstOrNull()?.path?.substringBeforeLast('/')
                            ?: "(挂载目录尚未就绪)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            zipLauncher.launch(
                                arrayOf("application/zip", "application/x-zip", "*/*")
                            )
                        }) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null)
                            Text(" 导入 zip", modifier = Modifier.padding(start = 4.dp))
                        }
                        OutlinedButton(onClick = { treeLauncher.launch(null) }) {
                            Text("导入目录")
                        }
                    }
                }
            }

            if (state.packs.isEmpty()) {
                item {
                    Text(
                        "挂载目录中还没有词库包。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            items(state.packs, key = { it.path }) { pack ->
                PackRow(
                    pack = pack,
                    active = state.active?.path == pack.path,
                    onSelect = { vm.selectPack(pack.path) },
                    onDelete = { toDelete = pack },
                )
            }
        }
    }

    toDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("删除词库包？") },
            text = { Text("将从挂载目录删除“${pack.name}”，此操作不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    vm.deletePack(pack.path)
                    toDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { toDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PackRow(
    pack: PackSummary,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onSelect) {
                Icon(
                    if (active) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (active) "当前使用" else "设为当前",
                    tint = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(pack.name, style = MaterialTheme.typography.titleMedium)
                if (pack.author.isNotEmpty()) {
                    Text(
                        pack.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "模板 ${pack.templates} · 词 ${pack.lexiconWords} · 韵 ${pack.rhymes.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}
