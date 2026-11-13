#!/usr/bin/env bash
# Builds libwmvosk.so (vosk_jni.c, the bridge to Alpha Cephei's libvosk.so)
# for every ABI and copies it into core/voice/src/vosk/jniLibs/. The bridge
# finds libvosk's C API with dlsym at run time, so it needs nothing but the
# NDK: no Vosk source, no AAR. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/vosk-jni/build.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Its own directory: the library rides the `vosk` gate (Play's :feature:vosk).
OUT=${VOSK_JNI_OUT:-$REPO/core/voice/src/vosk/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Build paths mapped out, so two machines with the same NDK build the same bytes.
MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
    esac
}

build() {
    local abi=$1
    local dest=$OUT/$abi
    local tmp=$WORK/libwmvosk-$abi.so
    mkdir -p "$dest"
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -std=c99 $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections -Wall -Wextra \
        "$HERE/vosk_jni.c" \
        -ldl -Wl,--no-undefined -Wl,--gc-sections -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 \
        -Wl,--version-script="$HERE/jni.map" -Wl,-soname,libwmvosk.so \
        -o "$tmp"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmvosk.so" "$tmp"
    echo "$abi libwmvosk.so: $(wc -c < "$dest/libwmvosk.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a; do
    if wanted "$abi"; then build "$abi"; fi
done
