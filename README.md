# 电子诗人 · Electronic Poet

复刻刘慈欣 1989 年编写的《电子诗人》的实现思路，运行于 Android，**仅生成现代诗**。

> “我面对着黑色的艺术家和荆棘丛生的波浪……”

## 它是什么 / 不是什么

**它是一个单纯的「匹配器 + 嵌词器」**：从语法库随机抽取句式模板，再从分类词库随机取词填入占位符，可选按十三辙押韵。

**它不是模型**：没有神经网络、没有训练、没有概率学习，全部是确定的规则匹配与随机抽样；给定相同种子必然生成相同诗作。

**语言资源全部挂载**：词库、语法库、韵库位于外部目录的「词库包」中，不编译进 APK，可随时替换、增删或手工编辑。

## 功能特性

- 现代诗生成：段落数与每段行数无上限，支持直接输入数字；
- 十三辙押韵：行行押 / 隔行押、指定韵部、每段自动换韵；
- 标题生成、随机种子复现；
- 每次生成自动保存 txt 到 `/sdcard/Download/cyberpoet/`；
- 复制、分享、收藏；词库包导入（zip / 目录）、切换、删除；
- Material 3 风格 UI，浅色 / 深色主题；
- 支持 `arm64-v8a`、`armeabi-v7a`、`x86_64`。

## 快速开始

1. 在 [Releases](https://github.com/qinghai-lipipeng/electronic-poet/releases) 下载并安装 APK（minSdk 26）；
2. APK 不含语料，进入「词库」页，点「导入 zip」，选择 Releases 中随附的词库包；
3. 回到「创作」页，设置参数，点「创作」；
4. 生成的 txt 自动保存到 `Download/cyberpoet/`。

## 文档库

| 文档 | 内容 |
|---|---|
| [架构说明](docs/architecture.md) | Kotlin / Rust 分层、JNI 接口、生成流程、随机数 |
| [从源码构建](docs/build.md) | 环境要求、交叉编译、构建 APK、测试 |
| [词库包格式](docs/PACK_FORMAT.md) | 挂载目录结构、模板、词库、韵部、十三辙表 |
| [离线工具](docs/pack-tools.md) | 从语料生成词库、按拼音归韵的完整用法 |

## 随附词库包

| 词库包 | 说明 |
|---|---|
| `liucixin-classic` | 原版词库与句式的挂载复刻，保留原貌；版权归刘慈欣，仅供个人学习研究，不随 APK 内嵌 |
| `modern-zh` | 由 8.2 万首华语现代诗按词性统计，权重为词频；十三辙齐全 |

## 上游与致谢

- **刘慈欣《电子诗人》（1989）** —— 最初用 FoxBase 编写，后移植 Visual FoxPro，本项目的机制与灵感来源；原版词库版权归刘慈欣；
- **viegg.com/poet** —— 在线简化版，经典词库包的直接数据来源；
- **[sheepzh/poetry](https://github.com/sheepzh/poetry)**（MIT）—— 华语现代诗语料库，82204 首；
- **[jieba-rs](https://github.com/messense/jieba-rs)**（MIT）、**[pinyin](https://github.com/mozillazg/rust-pinyin)**（MIT）、**[jni-rs](https://github.com/jni-rs/jni-rs)**（MIT/Apache）。

## 许可证

本项目代码采用 **GPL-3.0-or-later**；经典词库词目版权归刘慈欣，仅供个人学习研究；第三方语料与依赖遵循各自许可证。本项目为非商业个人学习作品，与刘慈欣及上游项目无官方关联。
