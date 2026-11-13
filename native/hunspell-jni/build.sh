#!/usr/bin/env bash
# Builds libwmhunspell.so (wm_hunspell_jni.cpp over a static Hunspell) for
# every ABI and copies it into core/prediction/src/main/jniLibs/. Fetches
# Hunspell at the pinned commit unless HUNSPELL_SRC points at a checkout of
# it. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/hunspell-jni/build.sh
set -euo pipefail

HUNSPELL_TAG=v1.7.6
HUNSPELL_COMMIT=15faca15532b34db3106d50f162e5edc2503d6d6

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: the jniLibs directory of the module that packages
# it for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${HUNSPELL_OUT:-$REPO/core/prediction/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share GitHub with everyone, and
# a busy host refusing one clone should not fail the build.
retry() {
    local n
    for n in 1 2 3 4 5; do
        "$@" && return 0
        echo "attempt $n failed, retrying in $((n * 30)) s: $*" >&2
        sleep $((n * 30))
    done
    return 1
}
fresh_clone() {  # dir, then git clone arguments
    local dir=$1; shift
    rm -rf "$dir" && git clone "$@" "$dir"
}

SRC=${HUNSPELL_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/hunspell
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$HUNSPELL_TAG" https://github.com/hunspell/hunspell
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$HUNSPELL_COMMIT" ] || { echo "Hunspell is at $got, expected $HUNSPELL_COMMIT" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=hunspell -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP" \
        -DHUNSPELL_SRC="$SRC" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmhunspell >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmhunspell.so" "$out/libwmhunspell.so"
    echo "$abi libwmhunspell.so: $(wc -c < "$dest/libwmhunspell.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a|armeabi-v7a) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
