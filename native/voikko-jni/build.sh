#!/usr/bin/env bash
# Builds libwmvoikko.so (wm_voikko_jni.cpp over a static libvoikko, VFST
# backend only) for every ABI into native/voikko-jni/jniLibs/. Fetches
# corevoikko at the pinned commit unless VOIKKO_SRC points at a checkout of
# it. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/voikko-jni/build.sh
#
# With --host <dir> it builds for this machine instead: libwmvoikko.dylib (or
# .so) for a desktop JVM, and voikkovfstc, which build-dict.sh needs. Host
# builds take jni.h from a JDK (JAVA_HOME) or, failing that, the NDK.
set -euo pipefail

VOIKKO_TAG=rel-libvoikko-4.3.3
VOIKKO_COMMIT=4b838726d6e11af235b5aba50dcc056bc17c8176

HERE=$(cd "$(dirname "$0")" && pwd)
# Where the library goes. Not a module's src/main/jniLibs: no build packages
# this directory into an APK. GitHub and F-Droid builds pin it for download
# and Play's :feature:spell split packages it (README.md, "Licence").
OUT=${VOIKKO_OUT:-$HERE/jniLibs}
HOST_OUT=
if [ "${1:-}" = --host ]; then
    HOST_OUT=${2:?usage: build.sh --host <dir>}
    mkdir -p "$HOST_OUT"
    HOST_OUT=$(cd "$HOST_OUT" && pwd -P)
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

SRC=${VOIKKO_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/corevoikko
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$VOIKKO_TAG" https://github.com/voikko/corevoikko
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$VOIKKO_COMMIT" ] || { echo "corevoikko is at $got, expected $VOIKKO_COMMIT" >&2; exit 1; }

if [ -n "$HOST_OUT" ]; then
    jni=${JAVA_HOME:+$JAVA_HOME/include}
    if [ ! -f "$jni/jni.h" ]; then
        ndk=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
        # Only the header: the rest of the NDK's sysroot is Android's libc.
        mkdir -p "$WORK/jni"
        cp "$(ls "$ndk"/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h 2>/dev/null | head -1)" "$WORK/jni/" || true
        jni=$WORK/jni
    fi
    [ -f "$jni/jni.h" ] || { echo "No jni.h: set JAVA_HOME to a JDK or ANDROID_NDK to an NDK" >&2; exit 1; }
    out=$WORK/build-host
    cmake -S "$HERE" -B "$out" -G Ninja -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DWMVOIKKO_HOST=ON -DVOIKKO_SRC="$SRC" -DJNI_INCLUDE="$jni" > "$out.log" 2>&1 || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    cp "$out"/libwmvoikko.* "$out/voikkovfstc" "$HOST_OUT/"
    ls -l "$HOST_OUT"
    exit 0
fi

NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=corevoikko -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP" \
        -DVOIKKO_SRC="$SRC" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmvoikko >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmvoikko.so" "$out/libwmvoikko.so"
    echo "$abi libwmvoikko.so: $(wc -c < "$dest/libwmvoikko.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a|armeabi-v7a) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
