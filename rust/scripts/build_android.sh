#!/usr/bin/env bash
# 将 poet-core 交叉编译为 Android 动态库，输出到 app/src/main/jniLibs/<abi>/。
#
# 前置：
#   - rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
#   - cargo install cargo-ndk
#   - 安装 Android NDK，并设置 ANDROID_NDK_HOME（或 ANDROID_NDK_ROOT）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PROJECT_DIR="$(cd "$RUST_DIR/.." && pwd)"
JNILIBS="$PROJECT_DIR/app/src/main/jniLibs"

if [[ -z "${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}" ]]; then
  echo "错误：请先设置 ANDROID_NDK_HOME（或 ANDROID_NDK_ROOT）" >&2
  exit 1
fi
if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "错误：未找到 cargo-ndk，请运行 cargo install cargo-ndk" >&2
  exit 1
fi

cd "$RUST_DIR"

cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o "$JNILIBS" \
  build --release -p poet-core

echo "动态库已输出到：$JNILIBS"
find "$JNILIBS" -name "*.so" -print
