#!/usr/bin/env bash
# Builds libwmnikud.so (nikud_jni.cpp, Phonikud's Hebrew diacritizer over ONNX
# Runtime) for every ABI and copies it into core/voice/src/onnx/jniLibs/, beside
# libwmsherpa, so it rides the same `onnx` delivery gate. Fetches ONNX Runtime's
# headers at the pinned commit unless ORT_SRC points at a checkout. Plain clang
# from the NDK, no CMake: one source file.
#
#   ANDROID_NDK=/path/to/ndk native/nikud-jni/build.sh
set -euo pipefail

# Headers only, the same release libwmsherpa is built against; the runtime is
# Microsoft's Maven Central build of it. Keep in step with `onnxruntime` in
# gradle/libs.versions.toml and ORT_TAG in native/sherpa-onnx-jni/build.sh.
ORT_TAG=v1.31.1
ORT_COMMIT=6af1a932e2286d5c58166085d24c68783033edee

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${NIKUD_OUT:-$REPO/core/voice/src/onnx/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] || {
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
fresh_clone() {  # dir, then git clone arguments
    local dir=$1; shift
    rm -rf "$dir" && git clone "$@" "$dir"
}

ORT=${ORT_SRC:-}
if [ -z "$ORT" ]; then
    ORT=$WORK/onnxruntime
    retry fresh_clone "$ORT" --quiet --depth 1 --branch "$ORT_TAG" --filter=blob:none --sparse \
        https://github.com/microsoft/onnxruntime
    git -C "$ORT" sparse-checkout set --no-cone /include/onnxruntime/core/session
fi
got=$(git -C "$ORT" rev-parse HEAD)
[ "$got" = "$ORT_COMMIT" ] || { echo "onnxruntime is at $got, expected $ORT_COMMIT" >&2; exit 1; }
HEADERS=$ORT/include/onnxruntime/core/session
[ -f "$HEADERS/onnxruntime_cxx_api.h" ] || { echo "no ONNX Runtime headers in $ORT" >&2; exit 1; }

# Build-machine paths are mapped out of the library, so two machines with the
# same NDK make the same bytes (F-Droid's reproducible build relies on it).
MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk -ffile-prefix-map=$ORT=onnxruntime"

triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
        x86_64) echo x86_64-linux-android24 ;;
    esac
}

build() {
    local abi=$1
    local cc=$BIN/$(triple "$abi")-clang
    local stub=$WORK/stub-$abi
    mkdir -p "$stub"
    # The link stub libwmsherpa uses: one unversioned OrtGetApiBase, soname
    # libonnxruntime.so, never shipped.
    "$cc" -shared -fPIC -O2 -Wl,-soname,libonnxruntime.so \
        -o "$stub/libonnxruntime.so" "$HERE/../sherpa-onnx-jni/ort_stub.c"
    "$cc++" -shared -fPIC -O2 -std=c++17 $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections -static-libstdc++ \
        -I"$HEADERS" \
        -o "$WORK/libwmnikud-$abi.so" "$HERE/nikud_jni.cpp" \
        -L"$stub" -lonnxruntime -llog \
        -Wl,--no-undefined -Wl,--gc-sections -Wl,--exclude-libs,ALL \
        -Wl,--version-script="$HERE/jni.map" -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmnikud.so" "$WORK/libwmnikud-$abi.so"
    echo "$abi libwmnikud.so: $(wc -c < "$dest/libwmnikud.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a x86_64; do
    if wanted "$abi"; then build "$abi"; fi
done
