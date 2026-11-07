#!/usr/bin/env bash
# Builds libwmllama*.so (llamacpp_jni.cpp over llama.cpp + ggml, with
# llama.cpp's chat-template code and libmtmd for pictures) for every ABI, and
# the arm64 OpenCL variant for Adreno GPUs, and copies them into
# core/intelligence/src/llama/jniLibs/ (the OpenCL one into
# core/intelligence/src/llamagpu/jniLibs/, its own gate). Fetches llama.cpp, and the OpenCL
# headers and loader, at the pinned commits (pin.sh) unless LLAMA_SRC,
# OPENCL_HEADERS_SRC and OPENCL_LOADER_SRC point at checkouts of them. Needs
# CMake, Ninja and Python 3 on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/llamacpp-jni/build.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# shellcheck disable=SC1091
. "$HERE/pin.sh"
# Where the libraries go: a jniLibs directory of their own, so the on-demand
# :feature:llama split carries them alone on Play. F-Droid's recipe
# scandeletes the same path.
OUT=${LLAMA_OUT:-$REPO/core/intelligence/src/llama/jniLibs}
# The OpenCL build is a gate of its own (`llamagpu`): only an Adreno phone
# with Compute on GPU fetches it.
GPU_OUT=${LLAMA_GPU_OUT:-$REPO/core/intelligence/src/llamagpu/jniLibs}
# The Vulkan build is another (`llamavulkan`), for every other phone GPU
# (Mali, Xclipse, PowerVR): its shaders make it several times the size of
# the CPU builds, so only a phone with Compute on GPU fetches it.
VULKAN_OUT=${LLAMA_VULKAN_OUT:-$REPO/core/intelligence/src/llamavulkan/jniLibs}
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

SRC=${LLAMA_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/llama.cpp
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$LLAMA_TAG" https://github.com/ggml-org/llama.cpp
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$LLAMA_COMMIT" ] || { echo "llama.cpp is at $got, expected $LLAMA_COMMIT" >&2; exit 1; }

# dir, env override, tag, commit, GitHub repository
khronos() {
    local dir=$1 src=$2 tag=$3 commit=$4 repo=$5
    if [ -z "$src" ]; then
        src=$WORK/$dir
        retry fresh_clone "$src" --quiet --depth 1 --branch "$tag" "https://github.com/KhronosGroup/$repo"
    fi
    local at
    at=$(git -C "$src" rev-parse HEAD)
    [ "$at" = "$commit" ] || { echo "$repo is at $at, expected $commit" >&2; exit 1; }
    echo "$src"
}

# KleidiAI's release archive, checked against the pinned hash, unpacked once
# and handed to every arm64 configuration (WM_KLEIDIAI in CMakeLists.txt).
KLEIDIAI_SRC=${KLEIDIAI_SRC:-}
if [ -z "$KLEIDIAI_SRC" ]; then
    retry curl -sSfL -o "$WORK/kleidiai.tar.gz" \
        "https://github.com/ARM-software/kleidiai/releases/download/$KLEIDIAI_TAG/kleidiai-$KLEIDIAI_TAG-src.tar.gz"
    got=$(shasum -a 256 "$WORK/kleidiai.tar.gz" | cut -c1-64)
    [ "$got" = "$KLEIDIAI_SHA256" ] || { echo "KleidiAI archive is $got, expected $KLEIDIAI_SHA256" >&2; exit 1; }
    mkdir -p "$WORK/kleidiai"
    tar xzf "$WORK/kleidiai.tar.gz" -C "$WORK/kleidiai" --strip-components 1
    KLEIDIAI_SRC=$WORK/kleidiai
fi
KLEIDIAI=(-DWM_KLEIDIAI=ON -DFETCHCONTENT_SOURCE_DIR_KLEIDIAI="$KLEIDIAI_SRC")

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=llama.cpp -ffile-prefix-map=$KLEIDIAI_SRC=kleidiai -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

# abi  library-name  extra cmake flags
build() {
    local abi=$1 name=$2; shift 2
    local out=$WORK/build-$name-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_ASM_FLAGS="$MAP" \
        -DLLAMA_SRC="$SRC" -DWM_LIB_NAME="$name" \
        -DWM_LLAMA_BUILD="$LLAMA_BUILD" -DWM_LLAMA_COMMIT="$LLAMA_SHORT" "$@" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    # shellcheck disable=SC2086
    cmake --build "$out" --target wmllama ${EXTRA_TARGETS:-} >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=${DEST_ROOT:-$OUT}/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/lib$name.so" "$out/lib$name.so"
    echo "$abi lib$name.so: $(wc -c < "$dest/lib$name.so") bytes"
    # Libraries built beside it that ship with it (the Hexagon skels), as
    # built: they run on the DSP, which the NDK's strip does not know.
    local extra
    for extra in ${EXTRA_FILES:-}; do
        cp "$out/$extra" "$dest/" && echo "$abi $(basename "$extra"): $(wc -c < "$dest/$(basename "$extra")") bytes"
    done
    # Each configuration's build tree is a few hundred megabytes: gone before
    # the next one starts.
    rm -rf "$out"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

# arm64: a baseline ARMv8.0 build every 64-bit phone runs, one for ARMv8.2
# cores with dot-product and half-precision instructions (2018 on), which
# quantized matrix maths runs several times faster on, and one that adds the
# int8 matrix-multiply instructions (i8mm: Cortex-A510/A710/X2 and later,
# 2022 on), which ggml's repacked Q4_0/Q8_0 kernels and its plain Q4/Q8 dot
# products use when compiled in. LlamaNative picks the newest every core can
# run from /proc/cpuinfo at load, the way WhisperCpp does. All three carry
# KleidiAI, whose kernels are chosen at run time.
# WMKB_LLAMA_CPU=0 skips them, to rebuild only a GPU variant.
if wanted arm64-v8a && [ "${WMKB_LLAMA_CPU:-1}" = 1 ]; then
    build arm64-v8a wmllama      -DGGML_CPU_ARM_ARCH=armv8-a "${KLEIDIAI[@]}"
    build arm64-v8a wmllama_v82  -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 "${KLEIDIAI[@]}"
    build arm64-v8a wmllama_i8mm -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16+i8mm "${KLEIDIAI[@]}"
fi
# arm64 with OpenCL: the ARMv8.2 build plus Qualcomm's Adreno backend.
# LlamaNative loads it only when Compute is GPU on an Adreno phone; it does
# the CPU's work as fast as libwmllama_v82.so where the GPU cannot. It links
# to libOpenCL.so by name, which the loader built here provides at link time
# and the phone's vendor library at run time. Phones whose app namespace
# does not expose that library cannot load it, and run the CPU build.
if wanted arm64-v8a && [ "${WMKB_LLAMA_GPU:-1}" = 1 ]; then
    CL_HEADERS=$(khronos OpenCL-Headers "${OPENCL_HEADERS_SRC:-}" "$OPENCL_HEADERS_TAG" "$OPENCL_HEADERS_COMMIT" OpenCL-Headers)
    CL_LOADER=$(khronos OpenCL-ICD-Loader "${OPENCL_LOADER_SRC:-}" "$OPENCL_LOADER_TAG" "$OPENCL_LOADER_COMMIT" OpenCL-ICD-Loader)
    cmake -S "$CL_LOADER" -B "$WORK/cl-loader" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release \
        -DOPENCL_ICD_LOADER_HEADERS_DIR="$CL_HEADERS" -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON \
        -DBUILD_TESTING=OFF -DENABLE_OPENCL_LAYERS=OFF > "$WORK/cl-loader.log" 2>&1 \
        && cmake --build "$WORK/cl-loader" --target OpenCL >> "$WORK/cl-loader.log" 2>&1 \
        || { tail -30 "$WORK/cl-loader.log"; exit 1; }
    DEST_ROOT=$GPU_OUT build arm64-v8a wmllama_opencl -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DWM_GPU=opencl \
        -DOpenCL_INCLUDE_DIR="$CL_HEADERS" -DOpenCL_LIBRARY="$WORK/cl-loader/libOpenCL.so" "${KLEIDIAI[@]}"
fi
# arm64 with Vulkan: the ARMv8.2 build plus ggml's Vulkan backend, for the
# GPUs Qualcomm's OpenCL backend refuses (Mali, Xclipse, PowerVR).
# LlamaNative loads it only when Compute is GPU on such a phone. ggml compiles
# its shaders with a pinned glslc (native/vulkan.sh) through a host tool
# (vulkan-shaders-gen, built with this machine's compiler) and embeds the
# SPIR-V; it links the NDK's libvulkan.so stub, and the phone's loader at run
# time.
if wanted arm64-v8a && [ "${WMKB_LLAMA_VULKAN:-1}" = 1 ]; then
    # shellcheck disable=SC1091
    . "$HERE/../vulkan.sh"
    vulkan_setup "$WORK" "$BIN"
    DEST_ROOT=$VULKAN_OUT build arm64-v8a wmllama_vulkan -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DWM_GPU=vulkan \
        "${VULKAN_CMAKE[@]}" "${KLEIDIAI[@]}"
fi
# arm64 with Qualcomm's Hexagon NPU backend, and its DSP skels, one per HTP
# generation (v73: Snapdragon 8 Gen 2, through v85). Needs the Hexagon SDK,
# which runs on x86-64 Linux only: built by .github/workflows/llama-hexagon.yml
# in Qualcomm's toolchain container, never by F-Droid (the SDK is proprietary),
# so it is off unless HEXAGON_SDK_ROOT is set.
if wanted arm64-v8a && [ -n "${HEXAGON_SDK_ROOT:-}" ] && [ "${WMKB_LLAMA_HEXAGON:-1}" = 1 ]; then
    HTP_VERSIONS="v73 v75 v79 v81 v85"
    htp_targets=""
    htp_files=""
    for v in $HTP_VERSIONS; do
        htp_targets="$htp_targets htp-$v"
        htp_files="$htp_files llama/ggml/src/ggml-hexagon/libggml-htp-$v.so"
    done
    EXTRA_TARGETS=$htp_targets EXTRA_FILES=$htp_files \
    DEST_ROOT=${LLAMA_NPU_OUT:-$REPO/core/intelligence/src/llamanpu/jniLibs} \
        build arm64-v8a wmllama_hexagon -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 -DWM_GPU=hexagon \
        -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" ${HEXAGON_TOOLS_ROOT:+-DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT"} \
        -DPREBUILT_LIB_DIR=android_aarch64 "${KLEIDIAI[@]}"
fi
# armeabi-v7a: small models only in practice (a 32-bit process cannot map
# much more than a gigabyte), but the phones that run it are the ones a
# 135M-360M model is for.
# llamafile's sgemm needs NEON fp16 loads ARMv7's NEON lacks; ggml's own
# kernels do the same work there.
if wanted armeabi-v7a; then
    build armeabi-v7a wmllama -DGGML_LLAMAFILE=OFF
fi
# x86_64 (Chromebooks, emulators): SSE4.2 is what the Android ABI guarantees.
if wanted x86_64; then
    build x86_64 wmllama -DGGML_SSE42=ON -DGGML_AVX=OFF -DGGML_AVX2=OFF -DGGML_FMA=OFF \
        -DGGML_F16C=OFF -DGGML_AVX512=OFF -DGGML_BMI2=OFF -DGGML_AVX_VNNI=OFF
fi
