#!/usr/bin/env bash
# Builds libwmchewing.so (libchewing's Rust crate behind a small JNI bridge)
# for every ABI and copies them into core/input/src/chewing/jniLibs/, the
# directory :core:input packages off Play and :feature:chewing on Play. See
# README.md.
#
#   ANDROID_NDK=/path/to/ndk native/chewing-jni/build.sh
#
# With --data DIR it builds the dictionary instead, on the host: the
# libchewing-data release pinned below, fetched and checked against its
# commit, compiled by this crate's chewing-dictc into
# DIR/chewing-<release>.dat. That file is the Zhuyin pack the keyboard
# downloads (ChewingData in :core:input, `chewing`); its SHA-256 is pinned
# there. It is not part of the library.
#
# Needs rustup (the toolchain and Android targets below are installed if
# missing) and cargo-ndk at the pinned version. Paths on the build machine are
# remapped out of the library, so two machines with the same toolchain build
# the same bytes; F-Droid's reproducible build relies on that.
set -euo pipefail

RUST_VERSION=1.99.1
CARGO_NDK_VERSION=4.1.2
# libchewing-data, the dictionary sources (LGPL-2.1-or-later).
DATA_TAG=v2026.3.22
DATA_COMMIT=c44e81aef24b06f1509f19e1be54c99812d0c43f

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${CHEWING_OUT:-$REPO/core/input/src/chewing/jniLibs}

TARGETS=(aarch64-linux-android armv7-linux-androideabi)
rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    $(printf -- '--target %s ' "${TARGETS[@]}") >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ "${1:-}" = "--data" ]; then
    DATA_OUT=${2:?--data needs a directory}
    mkdir -p "$DATA_OUT"
    DATA_OUT=$(cd "$DATA_OUT" && pwd)
    SRC=${CHEWING_DATA_SRC:-}
    if [ -z "$SRC" ]; then
        SRC=$(mktemp -d)/libchewing-data
        git clone --quiet --depth 1 --branch "$DATA_TAG" https://github.com/chewing/libchewing-data.git "$SRC"
    fi
    got=$(git -C "$SRC" rev-parse HEAD)
    [ "$got" = "$DATA_COMMIT" ] || { echo "libchewing-data $DATA_TAG is $got, expected $DATA_COMMIT" >&2; exit 1; }
    release=${DATA_TAG#v}
    out=$DATA_OUT/chewing-$release.dat
    (cd "$HERE" && cargo run --quiet --release --locked --features dictc --bin chewing-dictc -- \
        "$SRC/dict/chewing/word.csv" "$SRC/dict/chewing/tsi.csv" "$release" "$out")
    echo "chewing-$release.dat: $(wc -c < "$out") bytes, sha256 $(sha256 "$out")"
    exit 0
fi

NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
SYSROOT=$(rustc --print sysroot)
export CARGO_TARGET_DIR=${CHEWING_TARGET_DIR:-$HERE/target}
mkdir -p "$CARGO_TARGET_DIR"
CARGO_TARGET_DIR=$(cd "$CARGO_TARGET_DIR" && pwd -P)
# Panic locations carry source paths: the crates.io registry, the standard
# library and this crate. 16 KB pages, which Android 15 phones may use.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$CARGO_TARGET_DIR=/target -C link-arg=-Wl,-z,max-page-size=16384"

cd "$HERE"
# WMKB_ABIS narrows the build to one of the two (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a})
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked --lib
for abi in "${ABIS[@]}"; do
    f=$OUT/$abi/libwmchewing.so
    echo "$abi libwmchewing.so: $(wc -c < "$f") bytes, sha256 $(sha256 "$f")"
done
