#!/usr/bin/env bash
# Builds libwmwebp.so (wm_webp_jni.c over a static libwebp: the encoder,
# libwebpmux and its WebPAnimEncoder) for every ABI and copies it into
# core/content/src/main/jniLibs/, so every build ships it. Fetches libwebp at
# the pinned tag unless WEBP_SRC points at a checkout of it. Needs CMake and
# Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/webp-jni/build.sh
#
# `--desktop DIR` builds the same library for this machine instead (a .dylib
# on macOS, a .so on Linux) into DIR, with libwebp's webpinfo beside it, for
# the JNI test in README.md. It takes jni.h from JAVA_HOME, or from the NDK's
# sysroot when that has none.
set -euo pipefail

WEBP_TAG=v1.6.0
WEBP_COMMIT=4fa21912338357f89e4fd51cf2368325b59e9bd9

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# In the base APK of every channel, beside nothing else of a gate: the
# `base` list of native/gates.json. F-Droid's recipe scandeletes the path.
OUT=${WEBP_OUT:-$REPO/core/content/src/main/jniLibs}
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

SRC=${WEBP_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/libwebp
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$WEBP_TAG" -c advice.detachedHead=false \
        https://github.com/webmproject/libwebp
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$WEBP_COMMIT" ] || { echo "libwebp is at $got, expected $WEBP_COMMIT ($WEBP_TAG)" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# libwebp's asserts, the NDK's headers), so two machines with the same NDK
# build the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=libwebp -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=."
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
        -DCMAKE_C_FLAGS="$MAP $jni" -DWEBP_SRC="$SRC" -DWMWEBP_DESKTOP_TOOLS=ON > "$WORK/desktop.log" 2>&1 \
        || { tail -30 "$WORK/desktop.log"; exit 1; }
    cmake --build "$WORK/desktop" --target wmwebp webpinfo >> "$WORK/desktop.log" 2>&1 || { tail -40 "$WORK/desktop.log"; exit 1; }
    cp "$WORK"/desktop/libwmwebp.* "$WORK"/desktop/webp/webpinfo "$DESKTOP/"
    ls "$DESKTOP"/libwmwebp.* "$DESKTOP"/webpinfo
    exit 0
fi

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_C_FLAGS="$MAP" \
        -DWEBP_SRC="$SRC" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmwebp >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmwebp.so" "$out/libwmwebp.so"
    echo "$abi libwmwebp.so: $(wc -c < "$dest/libwmwebp.so") bytes"
}

# WMKB_ABIS narrows the build to some of the two (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a|armeabi-v7a) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
