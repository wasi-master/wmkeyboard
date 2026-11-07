#!/usr/bin/env bash
# Builds libwmfasttext.so (fasttext_jni.cpp over fastText's prediction code,
# with the lid.176.ftz language identification model compiled in) for every
# ABI and copies it into core/intelligence/src/langid/jniLibs/, beside CLD3's
# libwmcld3.so: one gate, langid, carries both. Fetches fastText at the
# pinned commit unless FASTTEXT_SRC points at a checkout of it, and the model
# unless LID_MODEL points at a copy; either way the model's hash is checked.
# Needs CMake and Ninja.
#
#   ANDROID_NDK=/path/to/ndk native/fasttext-jni/build.sh
set -euo pipefail

# facebookresearch/fastText, archived 2024 (MIT).
FASTTEXT_COMMIT=1142dc4c4ecbc19cc16eee5cdd28472e689267e6
# lid.176.ftz, the quantised model: 176 languages, CC BY-SA 3.0.
LID_URL=https://dl.fbaipublicfiles.com/fasttext/supervised-models/lid.176.ftz
LID_SHA256=8f3472cfe8738a7b6099e8e999c3cbfae0dcd15696aac7d7738a8039db603e83

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${FASTTEXT_OUT:-$REPO/core/intelligence/src/langid/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
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

FT=${FASTTEXT_SRC:-}
if [ -z "$FT" ]; then
    FT=$WORK/fastText
    fetch() {
        rm -rf "$FT" && git init -q "$FT" &&
            git -C "$FT" fetch -q --depth 1 https://github.com/facebookresearch/fastText "$FASTTEXT_COMMIT" &&
            git -C "$FT" checkout -q FETCH_HEAD
    }
    retry fetch
fi
[ "$(git -C "$FT" rev-parse HEAD)" = "$FASTTEXT_COMMIT" ] || { echo "fastText is not at $FASTTEXT_COMMIT" >&2; exit 1; }
FT=$(cd "$FT" && pwd -P)

MODEL=${LID_MODEL:-}
if [ -z "$MODEL" ]; then
    MODEL=$WORK/lid.176.ftz
    retry curl -sSfL -o "$MODEL" "$LID_URL"
fi
got=$(shasum -a 256 "$MODEL" | cut -c1-64)
[ "$got" = "$LID_SHA256" ] || { echo "lid.176.ftz is $got, expected $LID_SHA256" >&2; exit 1; }
MODEL=$(cd "$(dirname "$MODEL")" && pwd -P)/$(basename "$MODEL")

MAP="-ffile-prefix-map=$FT=fastText -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_ASM_FLAGS="$MAP" \
        -DFASTTEXT_SRC="$FT" -DLID_MODEL="$MODEL" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmfasttext >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmfasttext.so" "$out/libwmfasttext.so"
    echo "$abi libwmfasttext.so: $(wc -c < "$dest/libwmfasttext.so") bytes"
    rm -rf "$out"
}

for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    case $abi in
        arm64-v8a|armeabi-v7a|x86_64) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
