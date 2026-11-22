#!/usr/bin/env bash
# Builds libwmnumbat.so for every ABI and copies them into
# core/tools/src/numbat/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/numbat-jni/build.sh
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
# The jniLibs directory of the `numbat` gate (native/gates.json): its own, so
# the gate's Play split carries nothing else. F-Droid's recipe scandeletes it.
OUT=${NUMBAT_OUT:-$REPO/core/tools/src/numbat/jniLibs}
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
# Panic locations and Numbat's embedded module paths carry source paths: the
# crates.io registry, the standard library (rust-src, when installed) and this
# crate. rust-embed's folder path is remapped with the registry.
export CARGO_TARGET_DIR=${NUMBAT_TARGET_DIR:-$HERE/target}
mkdir -p "$CARGO_TARGET_DIR"
CARGO_TARGET_DIR=$(cd "$CARGO_TARGET_DIR" && pwd -P)
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$CARGO_TARGET_DIR=/target"

cd "$HERE"
# WMKB_ABIS narrows the build to some of the two (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a})
# --lib: the crate is also an rlib for its host tests; only the cdylib ships.
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked --lib
for abi in "${ABIS[@]}"; do
    f=$OUT/$abi/libwmnumbat.so
    echo "$abi libwmnumbat.so: $(wc -c < "$f") bytes"
done
