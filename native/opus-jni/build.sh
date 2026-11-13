#!/usr/bin/env bash
# Builds libwmopus.so (opus_jni.c + ogg_opus.c over libopus) for every ABI and
# copies it into core/voice/src/opus/jniLibs/. Fetches libopus at the pinned
# commit unless OPUS_SRC points at a checkout of it. Needs the NDK, CMake and
# Ninja. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/opus-jni/build.sh
set -euo pipefail

OPUS_TAG=v1.6.1
OPUS_COMMIT=22244de5a79bd1d6d623c32e72bf1954b56235be

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# A directory of its own: the library is the `opus` gate (native/gates.json),
# so Play's :feature:opus split carries it and nothing else.
OUT=${OPUS_OUT:-$REPO/core/voice/src/opus/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
command -v cmake >/dev/null && command -v ninja >/dev/null || { echo "Needs cmake and ninja on PATH" >&2; exit 1; }
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
fresh_clone() {  # dir, then git clone arguments
    local dir=$1; shift
    rm -rf "$dir" && git clone "$@" "$dir"
}

SRC=${OPUS_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/opus
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$OPUS_TAG" https://github.com/xiph/opus
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$OPUS_COMMIT" ] || { echo "libopus is at $got, expected $OPUS_COMMIT" >&2; exit 1; }

# Build paths mapped out, so two machines with the same NDK build the same bytes.
MAP="-ffile-prefix-map=$SRC=opus -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
    esac
}

build() {
    local abi=$1
    local dest=$OUT/$abi
    local cmake_dir=$WORK/cmake-$abi
    local tmp=$WORK/libwmopus-$abi.so
    mkdir -p "$dest"
    # Float build: every ABI here has an FPU, and the recorder's samples are
    # floats already. Only the encoder is reached from the JNI side, so
    # --gc-sections drops the decoder.
    cmake -S "$SRC" -B "$cmake_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS_RELEASE="-O2 -DNDEBUG -g0" \
        -DCMAKE_C_FLAGS="$MAP -ffunction-sections -fdata-sections -fvisibility=hidden" \
        -DOPUS_BUILD_SHARED_LIBRARY=OFF -DOPUS_BUILD_TESTING=OFF -DOPUS_BUILD_PROGRAMS=OFF \
        -DOPUS_INSTALL_PKG_CONFIG_MODULE=OFF -DOPUS_INSTALL_CMAKE_CONFIG_MODULE=OFF \
        > "$WORK/cmake-$abi.log" 2>&1 || { cat "$WORK/cmake-$abi.log" >&2; exit 1; }
    cmake --build "$cmake_dir" --target opus >> "$WORK/cmake-$abi.log" 2>&1 || { cat "$WORK/cmake-$abi.log" >&2; exit 1; }
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -std=c99 $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections \
        -I"$SRC/include" \
        "$HERE/opus_jni.c" "$HERE/ogg_opus.c" "$cmake_dir/libopus.a" \
        -lm -Wl,--no-undefined -Wl,--gc-sections -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 \
        -Wl,--version-script="$HERE/jni.map" -Wl,-soname,libwmopus.so \
        -o "$tmp"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmopus.so" "$tmp"
    echo "$abi libwmopus.so: $(wc -c < "$dest/libwmopus.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a; do
    if wanted "$abi"; then build "$abi"; fi
done
