package com.otaku.poet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.otaku.poet.jni.NativePoet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    appVersion: String,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
                SectionCard(title = "电子诗人") {
                    Para(
                        "本项目复刻了刘慈欣 1989 年编写的《电子诗人》（最初为 " +
                            "FoxBase / Visual FoxPro 程序）的实现思路，仅生成现代诗。"
                    )
                    Para(
                        "它不是模型，也不含任何深度学习或概率学习成分，而是一个单纯的" +
                            "“匹配器 + 嵌词器”：从语法库随机抽取句式模板，再从分类词库" +
                            "随机取词填入模板占位符；可选按韵部押韵。"
                    )
                }
            }
            item {
                SectionCard(title = "挂载式语言资源") {
                    Para(
                        "词库、语法库、韵库全部位于外部挂载目录，不编译进程序本体，" +
                            "可随时整体替换或手工编辑。详见“词库管理”页与工程文档。"
                    )
                }
            }
            item {
                SectionCard(title = "版本") {
                    Para("应用版本：$appVersion")
                    Para("核心引擎（Rust）：${NativePoet.version()}")
                }
            }
            item {
                SectionCard(title = "许可证") {
                    Para("本项目代码以 GNU GPL-3.0-or-later 许可发布。")
                }
            }
            item {
                SectionCard(title = "致谢") {
                    Para("· 刘慈欣与原版《电子诗人》")
                    Para("· sheepzh/poetry：华语现代诗歌语料库（MIT）")
                    Para("· jieba-rs：中文分词与词性标注（MIT）")
                    Para("· pinyin crate：汉字拼音（用于离线归韵，MIT）")
                    Para("· viegg.com/poet：在线简化版，提供原版语料参考")
                    Para(
                        "注：随工程提供的“经典词库包”中词目源自流传的原版词库，" +
                            "版权归原作者，仅供个人学习研究，不随 APK 内嵌分发。",
                    )
                }
            }
        }
    }
}

@Composable
private fun Para(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}
