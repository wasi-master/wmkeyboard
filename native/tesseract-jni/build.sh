#!/usr/bin/env bash
# Builds libwmtess.so for every ABI the app ships and copies it into
# core/intelligence/src/tesseract/jniLibs/<abi>/. Needs NDK r29, CMake 3.22+ and
# Ninja. See README.md.
#
#   ANDROID_NDK=/path/to/ndk/29.0.14206865 native/tesseract-jni/build.sh
set -euo pipefail

# Tesseract4Android 4.9.0 vendors Tesseract 5.5.1 and Leptonica 1.85.0 with
# the Android config headers this build reuses. Pinned by commit, not tag.
T4A_URL=https://github.com/adaptech-cz/Tesseract4Android
T4A_COMMIT=15c534717b1cb58261b58d4e4c1200c7f81f668c
ABIS=(arm64-v8a armeabi-v7a)

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK r29 install" >&2; exit 1; }
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29; output will not match the committed libraries" >&2
STRIP=$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip)

CMAKE=${CMAKE:-cmake}
NINJA=${NINJA:-ninja}

WORK=${WORK:-$HERE/build}
mkdir -p "$WORK"
if [ ! -d "$WORK/t4a" ]; then
    curl -sfL "$T4A_URL/archive/$T4A_COMMIT.tar.gz" -o "$WORK/t4a.tar.gz"
    mkdir -p "$WORK/t4a"
    tar -xzf "$WORK/t4a.tar.gz" -C "$WORK/t4a" --strip-components 1
    rm "$WORK/t4a.tar.gz"
fi
T4A_CPP=$WORK/t4a/tesseract4android/src/main/cpp

for abi in "${ABIS[@]}"; do
    out=$WORK/$abi
    "$CMAKE" -S "$HERE" -B "$out" -G Ninja -DCMAKE_MAKE_PROGRAM="$(command -v "$NINJA")" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DT4A_CPP="$T4A_CPP"
    "$CMAKE" --build "$out"
    dest=$REPO/core/intelligence/src/tesseract/jniLibs/$abi
    mkdir -p "$dest"
    "$STRIP" --strip-unneeded -o "$dest/libwmtess.so" "$out/libwmtess.so"
    echo "$abi: $(wc -c < "$dest/libwmtess.so") bytes"
done
