#!/usr/bin/env bash
# Builds libwmimagegen.so (wm_imagegen_jni.cpp and imagegen.cpp over a static
# MNN, CPU backend only) for every ABI and copies it into
# core/intelligence/src/imagegen/jniLibs/. Fetches MNN at the pinned commit
# unless MNN_SRC points at a checkout of it. Needs CMake and Ninja on PATH.
# See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/imagegen-jni/build.sh
set -euo pipefail

MNN_TAG=3.6.1
MNN_COMMIT=d407447ed56c4121a11ccbd266dc184ca1ead0c2
# KleidiAI, Arm's matrix kernels, in the arm64 build: the GitHub archive of
# the tag MNN 3.6.1's cmake/KleidiAI.cmake names, fetched here and checked
# against this hash, so configuring downloads nothing. Bump with MNN.
KLEIDIAI_TAG=v1.16.0
KLEIDIAI_SHA256=57b0a95c559ec6a4b1460bc742af600bfb6cdff8dc39154b376c25347d7b0891

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# A directory of its own, so the on-demand :feature:imagegen split carries it
# alone on Play. F-Droid's recipe scandeletes the same path.
OUT=${IMAGEGEN_OUT:-$REPO/core/intelligence/src/imagegen/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
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

SRC=${MNN_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/mnn
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$MNN_TAG" https://github.com/alibaba/MNN
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$MNN_COMMIT" ] || { echo "MNN is at $got, expected $MNN_COMMIT" >&2; exit 1; }

# KleidiAI's source, checked against the pinned hash and unpacked once. MNN
# would quietly build without it if it could not download it; handed over as
# KLEIDIAI_SRC_DIR, it never tries.
KLEIDIAI_SRC=${KLEIDIAI_SRC:-}
if [ -z "$KLEIDIAI_SRC" ]; then
    retry curl -sSfL -o "$WORK/kleidiai.tar.gz" \
        "https://github.com/ARM-software/kleidiai/archive/refs/tags/$KLEIDIAI_TAG.tar.gz"
    got=$(shasum -a 256 "$WORK/kleidiai.tar.gz" | cut -c1-64)
    [ "$got" = "$KLEIDIAI_SHA256" ] || { echo "KleidiAI archive is $got, expected $KLEIDIAI_SHA256" >&2; exit 1; }
    mkdir -p "$WORK/kleidiai"
    tar xzf "$WORK/kleidiai.tar.gz" -C "$WORK/kleidiai" --strip-components 1
    KLEIDIAI_SRC=$WORK/kleidiai
fi
[ -d "$KLEIDIAI_SRC/kai" ] || { echo "no KleidiAI source in $KLEIDIAI_SRC" >&2; exit 1; }

# Every path on the build machine is mapped out of the library (__FILE__ in
# MNN's asserts, the NDK's libc++ headers), so two machines with the same NDK
# build the same bytes.
MAP="-ffile-prefix-map=$SRC=mnn -ffile-prefix-map=$KLEIDIAI_SRC=kleidiai -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
SECTIONS="-ffunction-sections -fdata-sections"

# The CPU backend everywhere, and on arm64 MNN's OpenCL and Vulkan backends
# too, float32 compute on all three. KleidiAI on arm64 (below), on by
# default: MNN hands it the convolutions it has kernels for when the CPU has
# them (SME2 for float, dot product and i8mm for int8), and its own kernels
# the rest. MNN's own i8mm and SME2 kernels are picked at run time as well,
# so one arm64 build serves every core. No float16 kernels (never used: see
# imagegen.cpp), no transformer fusion (it changes a one-step model's
# picture), no tools, no converter, no telemetry (MNN_INTERNAL stays off).
MNN_OPTIONS=(
    -DMNN_BUILD_SHARED_LIBS=OFF -DMNN_SEP_BUILD=OFF
    -DMNN_BUILD_TOOLS=OFF -DMNN_BUILD_DEMO=OFF -DMNN_BUILD_CONVERTER=OFF -DMNN_BUILD_TRAIN=OFF
    -DMNN_BUILD_PROTOBUFFER=OFF -DMNN_BUILD_LLM=OFF -DMNN_BUILD_DIFFUSION=OFF -DMNN_BUILD_OPENCV=OFF
    -DMNN_INTERNAL=OFF -DMNN_USE_LOGCAT=ON -DMNN_USE_THREAD_POOL=ON -DMNN_OPENMP=OFF
    -DMNN_ARM82=OFF -DMNN_SUPPORT_FP16_ARMV7=OFF -DMNN_SUPPORT_BF16=OFF
    -DMNN_SUPPORT_TRANSFORMER_FUSE=OFF -DMNN_LOW_MEMORY=ON
    -DMNN_OPENGL=OFF -DMNN_NNAPI=OFF -DMNN_QNN=OFF
)

build() {
    local abi=$1
    local mnn=$WORK/mnn-$abi out=$WORK/build-$abi
    local android=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    )
    local extra=()
    # SME2 and KleidiAI kernels are arm64 only, and so are the GPU backends:
    # the sticker generator's Compute choice is for phones that draw on
    # their GPU, which 32-bit ones do not. Both open the system's
    # libOpenCL.so or libvulkan.so at run time (MNN_USE_SYSTEM_LIB off), so a
    # phone without one keeps the CPU; their kernels and shaders are compiled
    # into MNN's source, so the build needs no shader compiler.
    if [ "$abi" = arm64-v8a ]; then
        extra+=(-DMNN_KLEIDIAI=ON -DMNN_KLEIDIAI_DEFAULT_ON=ON -DKLEIDIAI_SRC_DIR="$KLEIDIAI_SRC"
                -DMNN_OPENCL=ON -DMNN_VULKAN=ON -DMNN_USE_SYSTEM_LIB=OFF)
    else
        extra+=(-DMNN_SME2=OFF -DMNN_KLEIDIAI=OFF -DMNN_OPENCL=OFF -DMNN_VULKAN=OFF)
    fi
    cmake -S "$SRC" -B "$mnn" -G Ninja "${android[@]}" "${MNN_OPTIONS[@]}" "${extra[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP $SECTIONS" -DCMAKE_CXX_FLAGS="$MAP $SECTIONS" > "$mnn.log" 2>&1 \
        || { tail -40 "$mnn.log"; exit 1; }
    cmake --build "$mnn" --target MNN >> "$mnn.log" 2>&1 || { grep -m1 -B2 -A12 "error:\|Stack dump\|PLEASE submit" "$mnn.log"; tail -20 "$mnn.log"; exit 1; }
    cmake -S "$HERE" -B "$out" -G Ninja "${android[@]}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_CXX_FLAGS="$MAP" \
        -DMNN_SRC="$SRC" -DMNN_BUILD="$mnn" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmimagegen >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmimagegen.so" "$out/libwmimagegen.so"
    echo "$abi libwmimagegen.so: $(wc -c < "$dest/libwmimagegen.so") bytes"
    rm -rf "$mnn" "$out"
}

for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a|armeabi-v7a) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
