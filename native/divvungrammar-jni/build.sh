#!/usr/bin/env bash
# Builds libwmdivvungrammar.so (divvun-runtime, which runs GiellaLT's grammar
# checker bundles) for every ABI and copies them into
# core/intelligence/src/divvungrammar/jniLibs/, the `divvungrammar` gate.
# GPL-3.0, unlike the rest of the app (Cargo.toml).
#
#   ANDROID_NDK=/path/to/ndk native/divvungrammar-jni/build.sh
#
# Needs rustup (the toolchain and Android targets below are installed if
# missing) and cargo-ndk at the pinned version. divvun-runtime and its
# CG-3 and HFST ports are git dependencies pinned in Cargo.lock.
# Paths on the build machine are mapped out of the library, so two machines
# with the same toolchain build the same bytes; F-Droid's reproducible build
# relies on that.
set -euo pipefail

RUST_VERSION=1.99.1
CARGO_NDK_VERSION=4.1.2

HERE=$(cd "$(dirname "$0")" && pwd -P)
REPO=$(cd "$HERE/../.." && pwd -P)
OUT=${DIVVUN_GRAMMAR_OUT:-$REPO/core/intelligence/src/divvungrammar/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2

TARGETS=(aarch64-linux-android armv7-linux-androideabi x86_64-linux-android)
rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    $(printf -- '--target %s ' "${TARGETS[@]}") >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

# A fresh target directory per run unless one is given, removed on exit
# however the build ends.
if [ -n "${DIVVUN_GRAMMAR_TARGET_DIR:-}" ]; then
    mkdir -p "$DIVVUN_GRAMMAR_TARGET_DIR"
    TARGET=$(cd "$DIVVUN_GRAMMAR_TARGET_DIR" && pwd -P)
else
    TARGET=$(cd "$(mktemp -d)" && pwd -P)
    trap 'rm -rf "$TARGET"' EXIT
fi
export CARGO_TARGET_DIR=$TARGET
# divvun-runtime's build script stamps the build date and git state into the
# library through vergen; idempotent mode writes fixed values instead, so two
# builds of the same sources come out the same.
export VERGEN_IDEMPOTENT=1

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
CARGO_HOME=$(cd "$CARGO_HOME" && pwd -P)
SYSROOT=$(rustc --print sysroot)
# Panic locations carry Rust source paths (the registry, git checkouts, the
# standard library); zstd's and liblzma's C sources, compiled by the cc crate,
# carry theirs through __FILE__. Both are mapped out. --exclude-libs keeps the
# C archives' own symbols (BLAKE3's, zstd's) out of the dynamic table, so the
# library exports its JNI entry points and nothing else.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$TARGET=/target -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--build-id=none -C link-arg=-Wl,--exclude-libs,ALL"
export CFLAGS="-ffile-prefix-map=$CARGO_HOME=/cargo -ffile-prefix-map=$TARGET=/target -ffile-prefix-map=$NDK=/ndk"

cd "$HERE"
# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64})
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked --lib
for abi in "${ABIS[@]}"; do
    # cargo-ndk copies every cdylib the build produced, a dependency's
    # libcffi-*.so among them, which this library does not load.
    find "$OUT/$abi" -name '*.so' ! -name libwmdivvungrammar.so -delete
    f=$OUT/$abi/libwmdivvungrammar.so
    echo "$abi libwmdivvungrammar.so: $(wc -c < "$f") bytes"
done
