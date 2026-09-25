# 架构说明

电子诗人上层使用 **Kotlin（Jetpack Compose）**，下层使用 **Rust**，二者通过 **JNI** 连接。语言资源（词库 / 语法 / 韵库）以「词库包」形式外部挂载，不编译进程序。

## 总体结构

```text
┌─────────────────────────────────────────────┐
│  app (Kotlin / Compose)                     │
│    创作页 / 词库管理 / 关于                  │
│    参数（段落、行数、韵部、种子）            │
│    PoetViewModel                            │
│    jni/NativePoet ─────────────┐            │
└────────────────────────────────┼────────────┘
                                 │ JNI（JSON 字符串编组）
┌────────────────────────────────▼────────────┐
│  libpoet_core.so (Rust)                     │
│    pack.rs       解析挂载包                  │
│    generator.rs  选模板 → 嵌词 → 押韵        │
│    ffi.rs        JNI 接口                    │
└─────────────────────────────────────────────┘
                                 │
                    读取外部挂载目录（可随时更换）
                    manifest.conf / grammar / lexicon / rhyme
```

## Kotlin 上层

单 Activity 架构，Jetpack Compose + Navigation：

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | 入口，持有共享 ViewModel，配置三个页面的导航 |
| `jni/NativePoet.kt` | 声明 native 静态方法，负责加载 `libpoet_core.so` |
| `data/Models.kt` | `GenParams`（生成参数）、`PoemResult`（诗作）、`PackSummary`（词库包摘要）及 JSON 转换 |
| `data/PackRepository.kt` | 挂载目录扫描、zip / 目录导入、删除 |
| `data/SettingsStore.kt` | DataStore 持久化参数与当前词库包 |
| `data/PoemSaver.kt` | 生成后写入 `/sdcard/Download/cyberpoet/*.txt` |
| `data/PoetViewModel.kt` | 核心状态管理：扫描、打开词库包、生成、导入、收藏 |
| `ui/ComposeScreen.kt` | 创作页：参数设置、创作按钮、诗作展示 |
| `ui/PacksScreen.kt` | 词库管理页：导入、切换、删除 |
| `ui/AboutScreen.kt` | 关于页 |
| `ui/Components.kt` | 通用 Material 组件（卡片、步进器、开关行等） |
| `theme/` | Material 3 颜色方案与字体 |

## Rust 下层

### poet-core（核心引擎）

| 文件 | 职责 |
|---|---|
| `src/pack.rs` | 挂载包解析：`manifest.conf`、`grammar/`、`lexicon/`、`rhyme/`；模板扫描为 Literal / Slot token |
| `src/generator.rs` | SplitMix64 随机数、加权抽样、模板填充、押韵行判定、段落 / 标题生成 |
| `src/ffi.rs` | JNI 桥接，五个导出函数，失败时抛出 Java 异常 |
| `tests/engine.rs` | 端到端测试 |
| `examples/poem.rs` | 命令行试写示例 |

### pack-tools（离线工具）

独立的命令行工具，不进入 Android，用于从语料构建词库包，详见 [pack-tools.md](pack-tools.md)。

## JNI 接口

共八个静态方法。复杂参数与结果一律以 **JSON 字符串**传递，保持接口简单稳定：

| Kotlin 方法 | 作用 | 返回 |
|---|---|---|
| `nativeVersion()` | 引擎版本号 | `String` |
| `nativeListPacks(root)` | 扫描挂载根目录，列出所有词库包 | JSON 数组（PackSummary） |
| `nativeOpen(dir)` | 打开一个词库包 | 句柄 `long` |
| `nativeGenerate(handle, optsJson)` | 按参数一次性生成诗作 | JSON 对象（PoemOut） |
| `nativeBeginGenerate(handle, optsJson)` | 开始流式生成 | JSON 对象（`title`、`seed`） |
| `nativeNextParagraph(handle)` | 取下一段 | JSON 对象（`lines`、`rhyme`）或 `{"done":true}` |
| `nativeEndGenerate(handle)` | 结束流式生成 | JSON 数组（warnings） |
| `nativeClose(handle)` | 关闭词库包，释放资源 | — |

生成参数 JSON 字段（对应 `GenOptions`，camelCase，均有默认值）：

```json
{
  "paragraphs": 3,
  "linesPerParagraph": 4,
  "rhyme": false,
  "rhymeId": null,
  "perParagraphRhyme": true,
  "rhymeScheme": "every",
  "makeTitle": true,
  "seed": null
}
```

- `rhymeScheme`：`"every"` 行行押，`"alternate"` 隔行押；
- `seed` 为 null 时引擎随机，实际使用的种子会随结果返回（`PoemOut.seed` / `BeginOut.seed`）；给定数字则结果可复现。

## 生成流程

```text
用户点击「创作」
      │
      ▼
Kotlin 组装 GenOptions JSON
      │
      ▼ JNI
Rust 过滤可用模板（引用的词库非空）
      │
      ├─ 押韵开？→ 过滤可用韵部 → 选定韵部
      │
      ▼
逐段、逐行：
  ├─ 押韵行：从「以占位符结尾」的模板中抽取
  │           句尾占位符从韵库取词，其余从词库取词
  └─ 普通行：从全部模板抽取，占位符从词库取词
      │
      ▼
可选：填充标题模板
      │
      ▼
返回 PoemOut JSON
      │
      ├─ Kotlin 展示诗作
      └─ PoemSaver 写入 Download/cyberpoet/*.txt
```

## 随机数与可复现性

- 引擎自带 SplitMix64 伪随机数实现，无第三方随机依赖；
- 相同种子 + 相同词库包 → 必然生成完全相同的诗作（含标题、韵部选择）；
- 种子留空时由 Kotlin 层用 `SecureRandom` 生成。
