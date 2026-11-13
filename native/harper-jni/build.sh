#!/usr/bin/env bash
# Builds libharper_jni.so for every ABI and copies them into
# core/intelligence/src/main/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/harper-jni/build.sh
#
# Needs rustup (the toolchain and Android targets below are installed if
# missing) and cargo-ndk at the pinned version. Paths on the build machine are
# remapped out of the library, so two machines with the same toolchain build
# the same bytes; F-Droid's reproducible build relies on that.
set -euo pipefail

RUST_VERSION=1.99.1
CARGO_NDK_VERSION=4.1.2

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that packages
# them for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${HARPER_OUT:-$REPO/core/intelligence/src/main/jniLibs}
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
# Panic locations carry source paths: the crates.io registry, the standard
# library (rust-src, when installed) and this crate's vendored sources.
export CARGO_TARGET_DIR=${HARPER_TARGET_DIR:-$HERE/target}
mkdir -p "$CARGO_TARGET_DIR"
CARGO_TARGET_DIR=$(cd "$CARGO_TARGET_DIR" && pwd -P)
# Build scripts' generated sources live under the target directory.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$CARGO_TARGET_DIR=/target"

cd "$HERE"
# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a})
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked
for abi in "${ABIS[@]}"; do
    f=$OUT/$abi/libharper_jni.so
    echo "$abi libharper_jni.so: $(wc -c < "$f") bytes"
done
