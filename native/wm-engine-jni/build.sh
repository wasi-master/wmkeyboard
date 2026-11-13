#!/usr/bin/env bash
# Builds libwm_engine_jni.so for every ABI and copies them into
# core/language/src/main/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/wm-engine-jni/build.sh
#
# Same recipe as native/harper-jni/build.sh: the pinned Rust toolchain and
# Android targets are installed through rustup if missing, cargo-ndk at the
# pinned version, and the build machine's paths are remapped out of the
# library so two machines with the same toolchain build the same bytes;
# F-Droid's reproducible build relies on that.
set -euo pipefail

RUST_VERSION=1.99.1
CARGO_NDK_VERSION=4.1.2

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that holds the
# Kotlin facades (core/language). F-Droid's recipe scandeletes the same path.
OUT=${WM_ENGINE_OUT:-$REPO/core/language/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)

TARGETS=(aarch64-linux-android armv7-linux-androideabi)
rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    $(printf -- '--target %s ' "${TARGETS[@]}") >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
SYSROOT=$(rustc --print sysroot)
# Relocations packed in Android's APS2 format (bionic reads it from API 23;
# minSdk is 24): the tables' pointers made .rela.dyn 120 KB of 24-byte entries.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard -C link-arg=-Wl,--pack-dyn-relocs=android"
export CARGO_TARGET_DIR=${WM_ENGINE_TARGET_DIR:-$HERE/target}

cd "$HERE"
# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a})
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked
for abi in "${ABIS[@]}"; do
    f=$OUT/$abi/libwm_engine_jni.so
    echo "$abi libwm_engine_jni.so: $(wc -c < "$f") bytes"
done
