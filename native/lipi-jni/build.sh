#!/usr/bin/env bash
# Builds libwmlipi.so (vidyut-lipi, the Brahmic script and romanisation
# transliterator) for every ABI and copies them into
# core/language/src/lipi/jniLibs/, the jniLibs directory of the `lipi` gate
# alone. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/lipi-jni/build.sh
#
# Needs rustup (the toolchain and Android targets below are installed if
# missing) and cargo-ndk at the pinned version. Paths on the build machine are
# mapped out of the library, so two machines with the same toolchain build the
# same bytes; F-Droid's reproducible build relies on that.
set -euo pipefail

RUST_VERSION=1.99.1
CARGO_NDK_VERSION=4.1.2

HERE=$(cd "$(dirname "$0")" && pwd -P)
REPO=$(cd "$HERE/../.." && pwd -P)
# A directory of its own, so the on-demand :feature:lipi split carries this
# library and nothing else. F-Droid's recipe scandeletes the same path.
OUT=${LIPI_OUT:-$REPO/core/language/src/lipi/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2

TARGETS=(aarch64-linux-android armv7-linux-androideabi)
rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update \
    $(printf -- '--target %s ' "${TARGETS[@]}") >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION
if [ "$(cargo ndk --version 2>/dev/null | awk '{print $2}')" != "$CARGO_NDK_VERSION" ]; then
    cargo install cargo-ndk --version "$CARGO_NDK_VERSION" --locked
fi

# A fresh target directory per run unless one is given, removed on exit
# however the build ends.
if [ -n "${LIPI_TARGET_DIR:-}" ]; then
    mkdir -p "$LIPI_TARGET_DIR"
    TARGET=$(cd "$LIPI_TARGET_DIR" && pwd -P)
else
    TARGET=$(cd "$(mktemp -d)" && pwd -P)
    trap 'rm -rf "$TARGET"' EXIT
fi
export CARGO_TARGET_DIR=$TARGET

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
CARGO_HOME=$(cd "$CARGO_HOME" && pwd -P)
SYSROOT=$(rustc --print sysroot)
# Panic locations carry Rust source paths (the registry, the standard
# library, this crate); all are mapped out. 16 KB segment alignment for the
# phones with 16 KB pages, and no build id, which would differ per machine.
export RUSTFLAGS="--remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$TARGET=/target -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,--build-id=none"

cd "$HERE"
# WMKB_ABIS narrows the build to some of the ABIs (native/build-fdroid-libs.sh).
ABIS=(${WMKB_ABIS:-arm64-v8a armeabi-v7a})
ANDROID_NDK_HOME=$NDK cargo ndk $(printf -- '-t %s ' "${ABIS[@]}") --platform 24 \
    -o "$OUT" build --release --locked --lib
for abi in "${ABIS[@]}"; do
    # vidyut-lipi is a cdylib too (for its WebAssembly build), and cargo-ndk
    # copies every cdylib it built: keep only ours.
    rm -f "$OUT/$abi"/libvidyut_lipi-*.so
    f=$OUT/$abi/libwmlipi.so
    echo "$abi libwmlipi.so: $(wc -c < "$f") bytes"
done
