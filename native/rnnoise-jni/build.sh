#!/usr/bin/env bash
# Builds libwmdenoise.so (denoise_jni.c over RNNoise) for every ABI and copies
# it into core/voice/src/main/jniLibs/. Fetches RNNoise at the pinned commit
# unless RNNOISE_SRC points at a checkout of it, and its model tarball from
# xiph.org unless RNNOISE_MODEL_TARBALL points at a copy. Needs nothing but
# the NDK, git and curl. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/rnnoise-jni/build.sh
set -euo pipefail

# v0.2 (904a876d) does not compile for ARM without its only child,
# 372f7b4b "Fix compilation errors", so the pin is that commit.
RNNOISE_COMMIT=372f7b4b76cde4ca1ec4605353dd17898a99de38
# The weights live outside the repository; model_version at the pin names them.
RNNOISE_MODEL=0b50c45
RNNOISE_MODEL_SHA256=4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Beside whisper.cpp's and ExecuTorch's libraries: it rides the speech gate
# (Play's :feature:speech). F-Droid's recipe scandeletes the same path.
OUT=${RNNOISE_OUT:-$REPO/core/voice/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

retry() {
    local n
    for n in 1 2 3 4 5; do
        "$@" && return 0
        echo "attempt $n failed, retrying in $((n * 30)) s: $*" >&2
        sleep $((n * 30))
    done
    return 1
}
fetch_commit() {  # dir, url, commit: no tag points at the pin
    rm -rf "$1" && git init -q "$1" &&
        git -C "$1" fetch --quiet --depth 1 "$2" "$3" && git -C "$1" checkout --quiet FETCH_HEAD
}
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

SRC=${RNNOISE_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/rnnoise
    retry fetch_commit "$SRC" https://github.com/xiph/rnnoise "$RNNOISE_COMMIT"
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$RNNOISE_COMMIT" ] || { echo "rnnoise is at $got, expected $RNNOISE_COMMIT" >&2; exit 1; }
[ "$(cat "$SRC/model_version")" = "$RNNOISE_MODEL" ] ||
    { echo "rnnoise wants model $(cat "$SRC/model_version"), pinned $RNNOISE_MODEL" >&2; exit 1; }

TARBALL=${RNNOISE_MODEL_TARBALL:-}
if [ -z "$TARBALL" ]; then
    TARBALL=$WORK/rnnoise_data-$RNNOISE_MODEL.tar.gz
    retry curl -fsSL -o "$TARBALL" "https://media.xiph.org/rnnoise/models/rnnoise_data-$RNNOISE_MODEL.tar.gz"
fi
[ "$(sha256 "$TARBALL")" = "$RNNOISE_MODEL_SHA256" ] || { echo "rnnoise model checksum mismatch" >&2; exit 1; }
# The tarball holds two models with one header: the default (14.7 MB of
# library) and the little one (2.9 MB), which is the one built in.
MODEL=$WORK/model
mkdir -p "$MODEL"
tar -xzf "$TARBALL" -C "$MODEL" src/rnnoise_data.h src/rnnoise_data_little.c

# Build paths mapped out, so two machines with the same NDK build the same bytes.
MAP="-ffile-prefix-map=$SRC=rnnoise -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
    esac
}

build() {
    local abi=$1
    local dest=$OUT/$abi
    local tmp=$WORK/libwmdenoise-$abi.so
    mkdir -p "$dest"
    # The little model is compiled in; no file to load. Upstream's Makefile
    # sources.
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -std=c99 $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections -Wno-null-dereference \
        -I"$SRC/include" -I"$SRC/src" -I"$MODEL/src" \
        "$HERE/denoise_jni.c" \
        "$SRC/src/denoise.c" "$SRC/src/rnn.c" "$SRC/src/pitch.c" \
        "$SRC/src/kiss_fft.c" "$SRC/src/celt_lpc.c" \
        "$SRC/src/nnet.c" "$SRC/src/nnet_default.c" "$SRC/src/parse_lpcnet_weights.c" \
        "$SRC/src/rnnoise_tables.c" "$MODEL/src/rnnoise_data_little.c" \
        -lm -Wl,--no-undefined -Wl,--gc-sections -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 \
        -Wl,--version-script="$HERE/jni.map" -Wl,-soname,libwmdenoise.so \
        -o "$tmp"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmdenoise.so" "$tmp"
    echo "$abi libwmdenoise.so: $(wc -c < "$dest/libwmdenoise.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a; do
    if wanted "$abi"; then build "$abi"; fi
done
