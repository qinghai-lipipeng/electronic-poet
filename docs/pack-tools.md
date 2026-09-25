# 离线工具 pack-tools

`pack-tools` 是离线运行的 Rust 命令行工具，用于从华语现代诗语料**自动构建词库包**。它不进入 Android，只在开发机上运行。

包含两个子命令：

- `corpus`：遍历语料，分词并按词性统计，生成词库；
- `rhyme`：按末字拼音把词归入「十三辙」，生成韵部。

## 构建工具

```bash
cd rust
cargo build -p pack-tools
```

或直接用 `cargo run -p pack-tools -- <子命令>`。

---

## corpus：从语料生成词库

### 用法

```bash
cargo run -p pack-tools -- corpus \
  --corpus <语料目录> \
  --out <输出词库包目录> \
  --grammar-from <语法模板来源目录> \
  --id <包id> --name <显示名称>
```

### 参数

| 参数 | 说明 |
|---|---|
| `--corpus` | 语料根目录，递归遍历 `.pt` / `.json` 文件 |
| `--out` | 输出的词库包目录 |
| `--grammar-from` | 复制该目录下的语法模板（grammar/）到新包 |
| `--id` | 词库包 id（写入 manifest.conf） |
| `--name` | 词库包显示名称 |
| `--min-len` | 实词最小字数，默认过滤单字（TT 叹词豁免） |
| `--top` | 每类词保留词频最高的前 N 个 |

### 语料格式

支持 `.pt` 单文件（华语现代诗语料库 sheepzh/poetry 的格式）：

```text
title: 诗名
date: 年代

正文第一行
正文第二行
...
```

也支持 `.json`。工具会递归遍历目录下全部文件。

### 词性映射

使用 jieba-rs 分词并标注 ICTCLAS 词性，再映射到占位符代码：

| 占位符 | 收词词性 | 含义 |
|---|---|---|
| `MM` | 名词系（n / nr / ns / nt / nz 等） | 名词 / 意象 |
| `DD` | 动词（v / vg 等不及物用法） | 自动词 |
| `DJ` | 他动词（v / vd / vn 等及物用法） | 他动词 |
| `XX` | 形容词 a / 状态词 z / 区别词 b | 修饰语 |
| `TT` | 叹词 e / 拟声词 o + 叹词白名单 | 叹词 |

注意：

- **不收语气词**（y 类，如「呢、吗、吧」），避免句子破碎；
- 默认过滤单字实词（`--min-len 2`），TT 叹词豁免；
- 输出权重 = 词频，词频越高被抽中的概率越大。

---

## rhyme：按拼音归韵

### 用法

```bash
cargo run -p pack-tools -- rhyme <词库包目录> [--min <每辙最少词数>]
```

### 原理

1. 遍历词库中每个词，取**末字**；
2. 用 pinyin crate 转拼音，手写逻辑剥离声母，得到韵母；
3. 按韵母归入汉语「十三辙」；
4. 输出到 `rhyme/<辙id>.txt`，每行 `CODE=词`。

处理的特例：

- `j / q / x` 后 `u` 实际读 `ü`（如「句」「学」）；
- `y / w` 整体认读（如「夜」「无」）；
- 无法归韵的生僻 / 笔误词跳过并报告数量。

### 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--min` | 4 | 某辙某类词少于该数则不写入，保证韵部可用 |

### 十三辙

| 辙 id | 名称 | 韵母 |
|---|---|---|
| `fa` | 发花 | a / ia / ua |
| `suo` | 梭波 | o / e / uo |
| `mie` | 乜斜 | ie / üe |
| `yi` | 一七 | i / ü / er |
| `gu` | 姑苏 | u |
| `huai` | 怀来 | ai / uai |
| `hui` | 灰堆 | ei / ui |
| `yao` | 遥条 | ao / iao |
| `you` | 由求 | ou / iu |
| `yan` | 言前 | an / ian / uan / üan |
| `ren` | 人辰 | en / in / un / ün |
| `jiang` | 江阳 | ang / iang / uang |
| `zhong` | 中东 | eng / ing / ong / iong / ueng |

---

## 完整示例：从语料到可导入词库包

以 sheepzh/poetry 语料为例：

```bash
# 1. 生成词库（处理 8.2 万首，约 1 分钟）
cargo run -p pack-tools -- corpus \
  --corpus /path/to/poetry/data \
  --out corpus-packs/my-pack \
  --grammar-from corpus-packs/liucixin-classic/grammar \
  --id my-pack --name 我的词库 \
  --min-len 2 --top 2500

# 2. 归韵
cargo run -p pack-tools -- rhyme corpus-packs/my-pack --min 6

# 3. 打包为 zip（zip 顶层为词库包目录，含 manifest.conf）
cd corpus-packs
zip -r my-pack.zip my-pack
```

之后在应用的「词库管理」页导入 `my-pack.zip` 即可。

## 词库包结构

生成的词库包结构与手工编辑完全一致：

```text
my-pack/
├── manifest.conf
├── grammar/
│   ├── modern.txt
│   └── title.txt
├── lexicon/
│   ├── MM.txt
│   ├── DD.txt
│   ├── DJ.txt
│   ├── XX.txt
│   └── TT.txt
└── rhyme/
    ├── fa.txt
    └── ...（十三辙）
```

格式的完整说明见 [PACK_FORMAT.md](PACK_FORMAT.md)。
