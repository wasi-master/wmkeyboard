#!/usr/bin/env bash
# Builds libwmzxing.so (wm_zxing_jni.cpp over a static zxing-cpp, readers
# only) for every ABI and copies it into core/intelligence/src/zxing/jniLibs/.
# Fetches zxing-cpp at the pinned tag unless ZXING_SRC points at a checkout of
# it. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/zxingcpp-jni/build.sh
#
# `--desktop DIR` builds the same library for this machine instead (a .dylib
# on macOS, a .so on Linux) into DIR, for the JNI test in README.md. It takes
# jni.h from JAVA_HOME, or from the NDK's sysroot when that has none.
set -euo pipefail

ZXING_TAG=v3.1.1
ZXING_COMMIT=287c85df6f961c8efbfb5ffd736cd9457b8b890e

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: a jniLibs directory of its own in the module that
# packages it for the non-Play builds, so the on-demand :feature:zxing split
# can carry it alone. F-Droid's recipe scandeletes the same path.
OUT=${ZXING_OUT:-$REPO/core/intelligence/src/zxing/jniLibs}
DESKTOP=
if [ "${1:-}" = "--desktop" ]; then
    DESKTOP=${2:?"--desktop needs an output directory"}
    mkdir -p "$DESKTOP"
    DESKTOP=$(cd "$DESKTOP" && pwd -P)
fi
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
if [ -n "$NDK" ]; then NDK=$(cd "$NDK" && pwd -P); fi
if [ -z "$DESKTOP" ]; then
    [ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
        echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
    grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
        echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
    BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
fi
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

SRC=${ZXING_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/zxing-cpp
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$ZXING_TAG" https://github.com/zxing-cpp/zxing-cpp
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$ZXING_COMMIT" ] || { echo "zxing-cpp is at $got, expected $ZXING_COMMIT ($ZXING_TAG)" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# zxing-cpp's asserts, the NDK's libc++ headers), so two machines with the
# same NDK build the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=zxing-cpp -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=."
[ -n "$NDK" ] && MAP="$MAP -ffile-prefix-map=$NDK=ndk"

if [ -n "$DESKTOP" ]; then
    jni=
    if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/include/jni.h" ]; then
        jni="-I$JAVA_HOME/include -I$(dirname "$(ls "$JAVA_HOME"/include/*/jni_md.h | head -1)")"
    elif [ -n "$NDK" ]; then
        jni="-I$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/include | head -1)"
        # The NDK's sysroot carries the target's libc headers too; only jni.h
        # is wanted, so copy it out rather than putting the sysroot on the path.
        mkdir -p "$WORK/jni" && cp "${jni#-I}/jni.h" "$WORK/jni/" && jni="-I$WORK/jni"
    else
        echo "Set JAVA_HOME to a JDK with include/jni.h, or ANDROID_NDK" >&2; exit 1
    fi
    cmake -S "$HERE" -B "$WORK/desktop" -G Ninja -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP $jni" -DCMAKE_C_FLAGS="$MAP" -DZXING_SRC="$SRC" > "$WORK/desktop.log" 2>&1 \
        || { tail -30 "$WORK/desktop.log"; exit 1; }
    cmake --build "$WORK/desktop" --target wmzxing >> "$WORK/desktop.log" 2>&1 || { tail -40 "$WORK/desktop.log"; exit 1; }
    cp "$WORK"/desktop/libwmzxing.* "$DESKTOP/"
    ls "$DESKTOP"/libwmzxing.*
    exit 0
fi

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_C_FLAGS="$MAP" \
        -DZXING_SRC="$SRC" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmzxing >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmzxing.so" "$out/libwmzxing.so"
    echo "$abi libwmzxing.so: $(wc -c < "$dest/libwmzxing.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a|armeabi-v7a) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
