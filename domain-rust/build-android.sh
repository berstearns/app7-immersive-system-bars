#!/usr/bin/env bash
# build-android.sh — cross-compile the Rust domain staticlib for Android ABIs.
# Usage: build-android.sh [abi ...]   (default: x86_64 arm64-v8a armeabi-v7a x86)
#
# Needs the rustup-managed Android std/core targets:
#   rustup target add aarch64-linux-android armv7-linux-androideabi \
#                     i686-linux-android x86_64-linux-android
# (System-package Rust without rustup cannot cross-compile to Android —
#  install rustup first: https://rustup.rs)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/$(ls "$HOME/Android/Sdk/ndk" 2>/dev/null | sort -V | tail -1)}"
API="${ANDROID_API:-24}"   # matches minSdk
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
[ -d "$TOOLCHAIN" ] || { echo "FATAL: NDK toolchain not found under $NDK" >&2; exit 2; }

abi_to_triple() {
  case "$1" in
    arm64-v8a)   echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    x86)         echo i686-linux-android ;;
    x86_64)      echo x86_64-linux-android ;;
    *) echo "unknown ABI: $1" >&2; return 1 ;;
  esac
}
# clang wrapper prefix differs for armv7
triple_to_clang() {
  case "$1" in
    armv7-linux-androideabi) echo "armv7a-linux-androideabi${API}-clang" ;;
    *)                       echo "${1}${API}-clang" ;;
  esac
}

ABIS=("${@:-x86_64}")
[ $# -eq 0 ] && ABIS=(x86_64 arm64-v8a armeabi-v7a x86)

for abi in "${ABIS[@]}"; do
  triple=$(abi_to_triple "$abi")
  clang="$TOOLCHAIN/$(triple_to_clang "$triple")"
  [ -x "$clang" ] || { echo "FATAL: $clang not found" >&2; exit 2; }
  if ! rustc --print target-libdir --target "$triple" >/dev/null 2>&1 || \
     [ ! -d "$(rustc --print target-libdir --target "$triple" 2>/dev/null)" ]; then
    echo "FATAL: Rust target '$triple' not installed." >&2
    echo "  Fix: install rustup (https://rustup.rs), then:" >&2
    echo "       rustup target add $triple" >&2
    exit 3
  fi
  echo "▸ cargo build --release --target $triple  (ABI $abi)"
  env_triple=$(echo "$triple" | tr 'a-z-' 'A-Z_')
  env "CC_${triple}=$clang" \
      "AR_${triple}=$TOOLCHAIN/llvm-ar" \
      "CARGO_TARGET_${env_triple}_LINKER=$clang" \
      cargo build --release --target "$triple" \
      --manifest-path "$HERE/Cargo.toml"
  ls -la "$HERE/target/$triple/release/libapp7_domain.a"
done
echo "done."
