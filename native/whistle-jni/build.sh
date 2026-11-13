#!/usr/bin/env bash
# Builds libwmwhistle.so (whistle_jni.cpp linked against Cactus's prebuilt
# libneedle.a) and copies it into core/voice/src/whistle/jniLibs/arm64-v8a/.
# One source file, so it calls the NDK's clang directly: no CMake, no Ninja.
# See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/whistle-jni/build.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)

# Only arm64: Cactus's armv7 archive needs a libc++ symbol no NDK ships
# (README.md). Other ABIs simply have no
# library, and WhistleEngine reports itself unavailable there.
abi=arm64-v8a
dest=$REPO/core/voice/src/whistle/jniLibs/$abi
mkdir -p "$dest"
out=$(mktemp -d)
"$BIN/aarch64-linux-android24-clang++" \
    -std=c++17 -Os -fPIC -shared -fvisibility=hidden \
    -ffile-prefix-map="$HERE"=. \
    -I"$HERE/include" \
    "$HERE/whistle_jni.cpp" \
    "$HERE/prebuilt/$abi/libneedle.a" \
    -static-libstdc++ -llog -lm \
    -Wl,--no-undefined \
    -Wl,--gc-sections \
    -Wl,--version-script="$HERE/jni.map" \
    -Wl,--build-id=sha1 \
    -Wl,-z,max-page-size=16384 \
    -o "$out/libwmwhistle.so"
"$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmwhistle.so" "$out/libwmwhistle.so"
rm -rf "$out"
echo "$abi: $(wc -c < "$dest/libwmwhistle.so") bytes"
"$BIN/llvm-nm" -D --defined-only "$dest/libwmwhistle.so" | grep ' T '
