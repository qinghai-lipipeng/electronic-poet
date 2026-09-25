# 从源码构建

## 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8 要求 JDK 17 |
| Android SDK Platform | API 35 | `platforms;android-35` |
| Android Build Tools | 35.0.0 | `build-tools;35.0.0` |
| Android NDK | r27 | 用于交叉编译 Rust |
| Rust | stable | 含三个 Android target |
| cargo-ndk | 最新 | 简化交叉编译 |
| Gradle | 8.11.1 | 或使用其他兼容版本 |

### 安装 Android SDK 组件

使用 `sdkmanager`：

```bash
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;27.3.13750724"
```

### 配置 Rust

```bash
# 添加三个 Android 编译目标
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android

# 安装 cargo-ndk
cargo install cargo-ndk
```

## 构建步骤

### 1. 交叉编译 Rust 动态库

设置 NDK 路径，运行编译脚本：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk   # 例如 ~/Android/Sdk/ndk/27.3.13750724
bash rust/scripts/build_android.sh
```

产物输出到 `app/src/main/jniLibs/`：

```text
app/src/main/jniLibs/
├── arm64-v8a/libpoet_core.so
├── armeabi-v7a/libpoet_core.so
└── x86_64/libpoet_core.so
```

### 2. 配置 SDK 路径

在项目根目录创建 `local.properties`（该文件已被 .gitignore 排除）：

```properties
sdk.dir=/path/to/android-sdk
```

### 3. 构建 APK

```bash
gradle assembleDebug
```

产物路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 4. 安装到设备

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 支持的 ABI

| ABI | 架构 | target |
|---|---|---|
| `arm64-v8a` | 64 位 ARM（主流真机） | `aarch64-linux-android` |
| `armeabi-v7a` | 32 位 ARM（老设备） | `armv7-linux-androideabi` |
| `x86_64` | 64 位 x86（模拟器） | `x86_64-linux-android` |

如需增减 ABI，修改 `rust/scripts/build_android.sh` 中的 `-t` 参数与 `app/build.gradle.kts` 中的 `abiFilters`。

## 运行测试

Rust 核心的端到端测试（在 `rust/` 目录下）：

```bash
cd rust
cargo test -p poet-core
```

测试覆盖：基本段落形状、同种子复现与种子回放、行行押 / 隔行押、句中同名占位符只押句尾、缺失词库回退、词库包列表与 JSON 字段、换包独立性等。

## 命令行试写（无需 Android）

不构建 APK，直接在主机上用示例程序试写：

```bash
cd rust
cargo run -p poet-core --example poem -- ../corpus-packs/liucixin-classic
```

## 仅构建 Rust（主机调试）

```bash
cd rust
cargo build
```

`poet-core` 的 crate-type 包含 `cdylib`，主机上也会生成 `libpoet_core.so`，可配合 JVM 做 JNI 端到端调试。

## 常见问题

- **`Unresolved reference` / JNI 相关编译错误**：确认 NDK 路径正确，且已 `rustup target add` 对应架构；
- **Gradle 找不到 SDK**：检查 `local.properties` 的 `sdk.dir`（注意路径转义）；
- **生成的诗作无韵**：确认词库包的 `rhyme/` 目录存在且模板以占位符结尾，详见 [PACK_FORMAT.md](PACK_FORMAT.md)；
- **APK 体积**：包含三个 ABI 的原生库，如需减小体积可只保留 `arm64-v8a` 或按 ABI 分包（App Bundle）。
