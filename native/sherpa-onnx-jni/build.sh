#!/usr/bin/env bash
# Builds libwmsherpa.so (sherpa_jni.cpp over a static sherpa-onnx) for every
# ABI and copies it into core/voice/src/onnx/jniLibs/. Fetches sherpa-onnx and
# ONNX Runtime's headers at their pinned commits unless SHERPA_SRC / ORT_SRC
# point at checkouts of them. Needs CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/sherpa-onnx-jni/build.sh
set -euo pipefail

SHERPA_TAG=v2.0.1
SHERPA_COMMIT=af2d8d81dbfa61c4cea4c568da78f3982edca005
# Headers only: the runtime itself is Microsoft's Maven Central build of the
# same version (com.microsoft.onnxruntime:onnxruntime-android), which the
# phone loads before this library. Keep in step with `onnxruntime` in
# gradle/libs.versions.toml.
ORT_TAG=v1.31.1
ORT_COMMIT=6af1a932e2286d5c58166085d24c68783033edee
# libwmsherpa_qnn.so (qnn_jni.cpp), arm64 only, its own gate (`sherpaqnn`,
# never F-Droid's: build-fdroid-libs.sh sets WMKB_SHERPA_QNN=0). It is
# compiled against Qualcomm's QNN API headers: include/QNN of QAIRT
# 2.40.0.251030, the release k2-fsa compiled the context binaries this library
# runs with, read out of Qualcomm's public SDK zip by range requests
# (../executorch-jni/qnn_headers.py) and checked against this digest of the
# tree. Compiled against, never committed. QNN_INCLUDE points at a QAIRT SDK's
# include/QNN instead.
QNN_VERSION=2.40.0.251030
QNN_ZIP_URL=https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QNN_VERSION/v$QNN_VERSION.zip
QNN_HEADERS_DIGEST=dde637c3e8093ef8b987d3696dbeaf406e383fd9e79a3522668ac11b9703524b

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: its own jniLibs directory under :core:voice, apart
# from src/main/jniLibs, because it rides the `onnx` delivery gate (Play's
# :feature:onnx) and not the speech one. F-Droid's recipe scandeletes it.
OUT=${SHERPA_OUT:-$REPO/core/voice/src/onnx/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share GitHub with everyone, and a
# busy host refusing one clone should not fail the build.
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

SRC=${SHERPA_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/sherpa-onnx
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$SHERPA_TAG" https://github.com/k2-fsa/sherpa-onnx
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$SHERPA_COMMIT" ] || { echo "sherpa-onnx is at $got, expected $SHERPA_COMMIT" >&2; exit 1; }

ORT=${ORT_SRC:-}
if [ -z "$ORT" ]; then
    ORT=$WORK/onnxruntime
    retry fresh_clone "$ORT" --quiet --depth 1 --branch "$ORT_TAG" --filter=blob:none --sparse \
        https://github.com/microsoft/onnxruntime
    git -C "$ORT" sparse-checkout set --no-cone /include/onnxruntime/core/session \
        /include/onnxruntime/core/providers/cpu /include/onnxruntime/core/providers/nnapi
fi
got=$(git -C "$ORT" rev-parse HEAD)
[ "$got" = "$ORT_COMMIT" ] || { echo "onnxruntime is at $got, expected $ORT_COMMIT" >&2; exit 1; }
# The flat header directory the AAR's headers/ folder has.
HEADERS=$WORK/ort-headers
mkdir -p "$HEADERS"
cp "$ORT"/include/onnxruntime/core/session/*.h "$ORT"/include/onnxruntime/core/session/*.inc "$HEADERS"/ 2>/dev/null || true
cp "$ORT"/include/onnxruntime/core/providers/cpu/cpu_provider_factory.h \
   "$ORT"/include/onnxruntime/core/providers/nnapi/nnapi_provider_factory.h "$HEADERS"/
[ -f "$HEADERS/onnxruntime_cxx_api.h" ] || { echo "no ONNX Runtime headers in $ORT" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=sherpa-onnx -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

# clang target triple per ABI, for the link stub.
triple() {
    case $1 in
        arm64-v8a) echo aarch64-linux-android24 ;;
        armeabi-v7a) echo armv7a-linux-androideabi24 ;;
    esac
}

# build ABI [qnn]: libwmsherpa.so, or with qnn libwmsherpa_qnn.so from a
# tree of its own configured with WMSHERPA_QNN.
build() {
    local abi=$1 variant=${2:-}
    local out=$WORK/build-$abi${variant:+-$variant}
    local stub=$WORK/stub-$abi
    local target=wmsherpa lib=libwmsherpa.so dest_root=$OUT
    local extra=() map=$MAP
    if [ "$variant" = qnn ]; then
        target=wmsherpa_qnn lib=libwmsherpa_qnn.so dest_root=$QNN_OUT
        extra=(-DWMSHERPA_QNN=ON)
        map="$MAP -ffile-prefix-map=$(cd "$QNN_ROOT/include/QNN" && pwd -P)=qnn"
    fi
    mkdir -p "$stub"
    "$BIN/$(triple "$abi")-clang" -shared -fPIC -O2 -Wl,-soname,libonnxruntime.so \
        -o "$stub/libonnxruntime.so" "$HERE/ort_stub.c"
    # sherpa-onnx's own dependencies (kaldi-native-fbank, kaldi-decoder,
    # simple-sentencepiece, OpenFst, ...) come from its CMake at the
    # versions this tag pins.
    SHERPA_ONNXRUNTIME_INCLUDE_DIR=$HEADERS SHERPA_ONNXRUNTIME_LIB_DIR=$stub QNN_SDK_ROOT=$QNN_ROOT \
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$map" -DCMAKE_CXX_FLAGS="$map" \
        -DSHERPA_SRC="$SRC" ${extra[@]+"${extra[@]}"} > "$out.log" 2>&1 \
        || { tail -40 "$out.log"; exit 1; }
    cmake --build "$out" --target "$target" >> "$out.log" 2>&1 || { tail -60 "$out.log"; exit 1; }
    local dest=$dest_root/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/$lib" "$out/$lib"
    echo "$abi $lib: $(wc -c < "$dest/$lib") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

QNN_ROOT=
QNN_OUT=${SHERPA_QNN_OUT:-$REPO/core/voice/src/sherpaqnn/jniLibs}
if [ "${WMKB_SHERPA_QNN:-1}" = 1 ] && wanted arm64-v8a; then
    QNN_ROOT=$WORK/qnn-sdk
    mkdir -p "$QNN_ROOT/include"
    if [ -n "${QNN_INCLUDE:-}" ]; then
        ln -sfn "$(cd "$QNN_INCLUDE" && pwd -P)" "$QNN_ROOT/include/QNN"
    else
        retry python3 -I "$HERE/../executorch-jni/qnn_headers.py" "$QNN_ZIP_URL" "$QNN_HEADERS_DIGEST" "$QNN_ROOT/include"
    fi
    [ -f "$QNN_ROOT/include/QNN/QnnInterface.h" ] || { echo "no QNN headers under $QNN_ROOT" >&2; exit 1; }
fi

# WMKB_SHERPA_PLAIN=0 builds the QNN library alone, leaving libwmsherpa.so
# as it is.
if [ "${WMKB_SHERPA_PLAIN:-1}" = 1 ]; then
    for abi in arm64-v8a armeabi-v7a; do
        if wanted "$abi"; then build "$abi"; fi
    done
fi
if [ -n "$QNN_ROOT" ]; then
    build arm64-v8a qnn
    # The split's armeabi-v7a stand-in (feature/sherpaqnn/stub/stub.c says
    # why), made with the arm64 library so the two never part:
    # Play's bundle refuses a module that covers other ABIs than the base.
    STUBS=${SHERPA_QNN_STUBS:-$REPO/feature/sherpaqnn/src/main/jniLibs}
    for t in armv7a-linux-androideabi28:armeabi-v7a; do
        mkdir -p "$STUBS/${t##*:}"
        "$BIN/clang" --target="${t%%:*}" -O2 -shared -fPIC -nostdlib++ -Wl,--build-id=sha1 \
            -Wl,-z,max-page-size=16384 -o "$WORK/stub.so" "$REPO/feature/sherpaqnn/stub/stub.c"
        "$BIN/llvm-strip" --strip-unneeded -o "$STUBS/${t##*:}/libwmsherpa_qnn.so" "$WORK/stub.so"
    done
fi
