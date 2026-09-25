# 电子诗人 · Electronic Poet

复刻刘慈欣 1989 年编写的《电子诗人》的实现思路，运行于 Android，**仅生成现代诗**。

> “我面对着黑色的艺术家和荆棘丛生的波浪……”

---

## 目录

- [它是什么 / 不是什么](#它是什么--不是什么)
- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [项目结构](#项目结构)
- [架构说明](#架构说明)
- [从源码构建](#从源码构建)
- [挂载词库](#挂载词库)
- [词库包格式](#词库包格式)
- [自制词库包（离线工具）](#自制词库包离线工具)
- [随附词库包](#随附词库包)
- [上游与致谢](#上游与致谢)
- [许可证](#许可证)

---

## 它是什么 / 不是什么

**它是一个单纯的「匹配器 + 嵌词器」：**

1. 从**语法库**随机抽取一条句式模板；
2. 模板中的占位符（如 `MM`、`DD`）从对应的**分类词库**随机取词填入；
3. 可选按汉语「十三辙」对句尾押韵。

**它不是模型：**

- 没有神经网络、没有概率学习、没有训练、没有推理；
- 全部逻辑是确定的规则匹配与可复现的随机抽样；
- 随机数使用自写的 SplitMix64，给定相同种子必然生成相同诗作。

**语言资源全部挂载：**

- 词库、语法库、韵库位于外部目录中的「词库包」内，不编译进 APK；
- 可随时整体替换、增删或用任意文本编辑器手工修改；
- 应用运行时重新扫描，新增词库包立即可用。

## 功能特性

- 现代诗自动生成：可设**段落数**与**每段行数**（无上限，支持直接输入数字）；
- **十三辙押韵**：行行押 / 隔行押、指定韵部、每段自动换韵；
- **标题生成**：独立的标题模板；
- **随机种子**：可留空随机，也可填入数字复现同一首诗；
- 每次生成自动保存 txt 到 `/sdcard/Download/cyberpoet/`；
- 诗作支持复制、分享、收藏（收藏存于应用私有目录）；
- 词库管理：扫描挂载目录、导入 zip / 目录、切换、删除；
- Material 3 风格 UI，支持浅色 / 深色主题；
- 提供三个 ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。

## 快速开始

1. 安装 APK（minSdk 26 / Android 8.0）；
2. APK **不含任何语料**，首次打开没有词库；
3. 进入「词库」页，点「导入 zip」，选择随附的词库包 zip（无需解压）；
4. 选中词库包后回到「创作」页，设置参数，点「创作」即可；
5. 生成的 txt 自动保存到 `Download/cyberpoet/`。

## 项目结构

```text
ElectronicPoet/
├── app/                              # Android 上层（Kotlin / Jetpack Compose）
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/otaku/poet/
│       │   ├── MainActivity.kt       # 单 Activity + Navigation
│       │   ├── PoetApp.kt
│       │   ├── jni/NativePoet.kt     # native 方法声明
│       │   ├── data/                 # 数据模型、词库仓库、设置、ViewModel、导出
│       │   ├── theme/                # Material 3 颜色与字体
│       │   └── ui/                   # 创作页 / 词库页 / 关于页 / 通用组件
│       ├── jniLibs/<abi>/libpoet_core.so   # Rust 交叉编译产物
│       └── res/
├── rust/                             # Rust 工作区
│   ├── Cargo.toml
│   ├── crates/
│   │   ├── poet-core/                # 核心引擎
│   │   │   ├── src/pack.rs           # 挂载包解析
│   │   │   ├── src/generator.rs      # 模板匹配、嵌词、押韵
│   │   │   ├── src/ffi.rs            # JNI 桥接
│   │   │   ├── tests/engine.rs       # 端到端测试
│   │   │   └── examples/poem.rs      # 命令行试写
│   │   └── pack-tools/               # 离线工具：构建词库 / 韵部
│   │       ├── src/corpus.rs
│   │       ├── src/rhyme.rs
│   │       └── src/main.rs
│   └── scripts/build_android.sh      # 交叉编译脚本
├── corpus-packs/                     # 随附词库包（挂载资源，不内嵌 APK）
│   ├── liucixin-classic/             # 原版复刻
│   ├── modern-zh/                    # 语料统计
│   └── *.zip                         # 可直接导入的打包文件
├── docs/PACK_FORMAT.md               # 词库包格式详解
└── README.md
```

## 架构说明

上层 **Kotlin**（Jetpack Compose），下层 **Rust**（通过 JNI）：

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

JNI 接口共五个静态方法，复杂参数与结果一律以 JSON 字符串传递：

| Kotlin 方法 | 作用 |
|---|---|
| `nativeVersion()` | 引擎版本 |
| `nativeListPacks(root)` | 扫描挂载目录，返回词库包摘要列表 |
| `nativeOpen(dir)` | 打开一个词库包，返回句柄 |
| `nativeGenerate(handle, optsJson)` | 按参数生成诗作，返回 JSON |
| `nativeClose(handle)` | 关闭词库包 |

## 从源码构建

### 环境要求

- JDK 17
- Android SDK：`platforms;android-35`、`build-tools;35.0.0`
- Android NDK r27
- Rust（stable）、`cargo-ndk`

### 步骤

1. 添加 Rust Android 目标并安装 `cargo-ndk`：

   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
   cargo install cargo-ndk
   ```

2. 交叉编译 Rust 动态库：

   ```bash
   export ANDROID_NDK_HOME=/path/to/android-ndk
   bash rust/scripts/build_android.sh
   # 产物输出到 app/src/main/jniLibs/<abi>/libpoet_core.so
   ```

3. 在项目根目录创建 `local.properties`，指定 SDK 路径：

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. 构建 APK：

   ```bash
   gradle assembleDebug
   # 产物：app/build/outputs/apk/debug/app-debug.apk
   ```

### 运行 Rust 测试

```bash
cd rust
cargo test -p poet-core
```

命令行直接试写（无需 Android）：

```bash
cargo run -p poet-core --example poem -- corpus-packs/liucixin-classic
```

## 挂载词库

挂载根为应用专属外部目录，**无需存储权限**：

```text
Android/data/com.otaku.poet/files/poet-packs/<pack-id>/
├── manifest.conf          # 包元信息
├── grammar/
│   ├── modern.txt         # 正文句式模板（可多个 .txt）
│   └── title.txt          # 标题模板
├── lexicon/
│   ├── MM.txt             # 词库，文件名 = 占位符代码
│   ├── DD.txt
│   ├── DJ.txt
│   ├── XX.txt
│   └── TT.txt
└── rhyme/                 # 韵部（可选）
    ├── jiang.txt
    └── zhong.txt
```

除了在应用内导入，也可以直接用文件管理器或 adb 增删：

```bash
adb push corpus-packs/liucixin-classic \
  /sdcard/Android/data/com.otaku.poet/files/poet-packs/
```

## 词库包格式

**模板**中连续两个 ASCII 大写字母构成占位符，例如：

```text
MM在DD
XX的MM在DD
我面对着XX的MM和XX的MM
TTXX的MM
```

**词库**每行一个词，重复出现（或行尾加 `<TAB>权重`）权重更高。

占位符代码约定：

| 代码 | 含义 | 示例 |
|---|---|---|
| `MM` | 名词 / 意象 | 月亮、灯塔、荒原 |
| `DD` | 自动词（不及物） | 飞翔、游荡、燃烧 |
| `DJ` | 他动词（及物） | 拥抱、追逐、照亮 |
| `XX` | 修饰语（形容词 / 形状 / 颜色） | 金色、荒凉、圆柱形 |
| `TT` | 叹词 | 啊！、噢！、咦！ |

**韵部**每行 `CODE=词`，押韵时只替换句尾占位符。

完整格式（含权重、注释、十三辙表）见 [`docs/PACK_FORMAT.md`](docs/PACK_FORMAT.md)。

## 自制词库包（离线工具）

`pack-tools` 是离线运行的 Rust 命令行工具，包含两个子命令。

### 1. 从语料生成词库：`corpus`

遍历华语现代诗语料，用 jieba-rs 分词并标注词性，按词性映射到占位符代码，按词频输出权重：

```bash
cargo run -p pack-tools -- corpus \
  --corpus /path/to/poetry/data \
  --out corpus-packs/my-pack \
  --grammar-from corpus-packs/liucixin-classic/grammar \
  --id my-pack --name 我的词库 \
  --min-len 2 --top 2500
```

词性映射：名词系 → `MM`；动词 → `DD` + `DJ`；形容词 / 状态词 / 区别词 → `XX`；叹词 → `TT`（语气词「呢、吗、吧」等被排除）。

### 2. 按拼音归韵：`rhyme`

取词的末字，转拼音、剥离声母，归入十三辙：

```bash
cargo run -p pack-tools -- rhyme corpus-packs/my-pack --min 6
```

处理了 `j/q/x` 后 `u → ü`、`y/w` 整体认读等特例。完成后把词库目录打成 zip，即可在应用内导入。

## 随附词库包

| 词库包 | 模板 | 词量 | 说明 |
|---|---|---|---|
| `liucixin-classic` | 133 | 约 2600 | 原版词库与句式的挂载复刻，保留原貌（含原版笔误）；版权归刘慈欣，仅供个人学习研究，**不随 APK 内嵌分发** |
| `modern-zh` | 133 | 约 10000 | 由 8.2 万首华语现代诗按词性统计，权重为词频，已过滤单字实词；十三辙齐全 |

## 上游与致谢

本项目是对上游作品的再实现，谨此标注：

- **刘慈欣《电子诗人》（1989）** —— 最初用 FoxBase 编写，后移植至 Visual FoxPro。本项目的核心机制（词库 + 语法库 + 模板嵌词）与设计灵感均源于此。原版词库版权归刘慈欣所有。
- **viegg.com/poet** —— 《电子诗人》在线简化版，其 `main.js` 包含流传的原版句式模板与分类词库，是 `liucixin-classic` 词库包的直接数据来源。
- **[sheepzh/poetry](https://github.com/sheepzh/poetry)**（MIT License）—— 华语现代诗歌语料库，收录 82204 首现代诗，是 `modern-zh` 词库包的数据来源。
- **[jieba-rs](https://github.com/messense/jieba-rs)**（MIT License）—— 中文分词与词性标注，`pack-tools corpus` 使用。
- **[pinyin](https://github.com/mozillazg/rust-pinyin)**（MIT License）—— 汉字转拼音，`pack-tools rhyme` 使用。
- **[jni-rs](https://github.com/jni-rs/jni-rs)**（MIT / Apache-2.0）—— Rust 的 JNI 绑定。
- **Android Jetpack Compose / Material 3** —— 上层界面框架。

## 许可证

- **本项目代码：GNU General Public License v3.0 or later（GPL-3.0-or-later）**
- 随附的 `liucixin-classic` 词库包词目版权归刘慈欣所有，仅供个人学习、研究使用，不随应用二进制分发；
- 第三方语料与依赖遵循其各自许可证（主要为 MIT / Apache-2.0）。

本项目为非商业的个人学习作品，与刘慈欣本人及上述上游项目无官方关联。
