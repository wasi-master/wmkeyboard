#!/usr/bin/env bash
# Builds libwmexecutorch.so for every ABI: first a selective, static
# ExecuTorch (only the operators in ops.txt, optimized kernels where they
# exist, the XNNPACK delegate, the Module extension), then executorch_jni.cpp
# over it; copies the results into
# core/voice/src/executorch/jniLibs/. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/executorch-jni/build.sh
#
# Needs CMake, Ninja and a Python 3 with PyYAML and typing_extensions on PATH,
# for the operator registration it generates. The generator is ExecuTorch's
# own, over PyTorch's torchgen, which is fetched from PyTorch's sources at the
# pinned commit rather than taken from an installed torch, so the generated
# code does not depend on what the machine has. Fetches ExecuTorch at the
# pinned commit unless EXECUTORCH_SRC points at a checkout of it (ExecuTorch
# insists the directory be named "executorch").
#
# Paths on the build machine are mapped out of the library, so two machines
# with the same NDK build the same bytes; F-Droid's reproducible build relies
# on that.
set -euo pipefail

# The QNN API headers ExecuTorch's Qualcomm backend compiles against: the
# QAIRT SDK release ExecuTorch pins (backends/qualcomm/scripts/qnn_config.sh),
# include/QNN only, read out of Qualcomm's public 1.35 GB zip by range
# requests (qnn_headers.py) and checked against this digest of the tree.
# geniex-qairt-plugin's copy, which native/imagegen-qnn uses, lacks the
# Saver, GPU, LPAI and IR headers the delegate includes. Compiled against,
# never committed. QNN_INCLUDE points at a QAIRT SDK's include/QNN instead.
QNN_VERSION=2.37.0.250724
QNN_ZIP_URL=https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QNN_VERSION/v$QNN_VERSION.zip
QNN_HEADERS_DIGEST=fa3ceeff00ab4316c974e94e5cb6093ec15782c75ebf558840079518a869d08f

# MediaTek's NeuronAdapter.h for the NeuroPilot variant: AOSP's
# platform/external/executorch at this commit, and the file's SHA-256.
NEURON_ADAPTER_AOSP=28d3bc0b28d9375d346e88e4ddbaaba700638b2d
NEURON_ADAPTER_SHA256=1719d8b33b87ed6aedd860eb4793e013286ec1f5675e401ae4575875f9b80938

ET_TAG=v1.5.1
ET_COMMIT=3b60683923245cf472b7323426920e15623ba361
# The PyTorch release ExecuTorch v1.5.1 pins (torch_pin.py), for torchgen.
TORCH_TAG=v2.14.0

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the libraries go: the jniLibs directory of the module that packages
# them for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${ET_OUT:-$REPO/core/voice/src/executorch/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=${ET_WORK:-$(mktemp -d)}
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd -P)
# A scratch directory of our own is about 4.7 GB after three ABIs; remove it
# on exit like the other build scripts. An ET_WORK the caller named is kept,
# since reusing it is the point of naming one.
[ -n "${ET_WORK:-}" ] || trap 'rm -rf "$WORK"' EXIT
PY=$(command -v python3)

# Network fetches retry: F-Droid's builders share GitHub and GitLab with
# everyone, and a busy host refusing one clone should not fail the build.
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

# PyTorch, from source at the pinned tag, for two things a selective build
# takes from an installed torch: torchgen with the two operator tables it
# reads (laid out as the wheel packages them, torchgen/packaged/ATen/native/),
# and the C++ headers the optimized kernels include (ATen's CPU vector
# classes and the c10 utilities under them). ExecuTorch finds the headers by
# asking Python where the torch package is, so they go in a stub package of
# that name. Nothing of torch is compiled; the headers it would have
# generated are written below with the values a CPU-only build has.
PT=$WORK/pytorch-src
if [ ! -f "$PT/.wm-ready" ]; then
    rm -rf "$PT"
    retry fresh_clone "$PT" --quiet --depth 1 --branch "$TORCH_TAG" --filter=blob:none --no-checkout \
        https://github.com/pytorch/pytorch
    git -C "$PT" sparse-checkout set --no-cone /torchgen/ /aten/src/ATen/ /c10/ /torch/headeronly/
    retry git -C "$PT" checkout --quiet
    mkdir -p "$PT/torchgen/packaged/ATen/native"
    cp "$PT/aten/src/ATen/native/native_functions.yaml" "$PT/aten/src/ATen/native/tags.yaml" \
        "$PT/torchgen/packaged/ATen/native/"
    touch "$PT/.wm-ready"
fi
STUB=$WORK/torch-stub
rm -rf "$STUB"
mkdir -p "$STUB/torch/include/torch"
: > "$STUB/torch/__init__.py"
(cd "$PT/aten/src" && find ATen -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include"
(cd "$PT" && find c10 -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include"
(cd "$PT/torch" && find headeronly -name '*.h' | LC_ALL=C sort | tar -cf - -T -) | tar -xf - -C "$STUB/torch/include/torch"
cat > "$STUB/torch/include/ATen/Config.h" <<'H'
#pragma once
#define AT_MKLDNN_ENABLED() 0
#define AT_MKLDNN_ACL_ENABLED() 0
#define AT_MKL_ENABLED() 0
#define AT_MKL_SEQUENTIAL() 0
#define AT_POCKETFFT_ENABLED() 0
#define AT_NNPACK_ENABLED() 0
#define CAFFE2_STATIC_LINK_CUDA() 0
#define AT_BUILD_WITH_BLAS() 0
#define AT_BUILD_WITH_LAPACK() 0
#define AT_PARALLEL_OPENMP 0
#define AT_PARALLEL_NATIVE 1
#define AT_BLAS_F2C() 0
#define AT_BLAS_USE_CBLAS_DOT() 0
#define AT_KLEIDIAI_ENABLED() 0
#define AT_USE_EIGEN_SPARSE() 0
H
printf '#ifndef C10_MACROS_CMAKE_MACROS_H_\n#define C10_MACROS_CMAKE_MACROS_H_\n#endif\n' \
    > "$STUB/torch/include/torch/headeronly/macros/cmake_macros.h"
v=${TORCH_TAG#v}
printf '#pragma once\n#define TORCH_VERSION_MAJOR %s\n#define TORCH_VERSION_MINOR %s\n#define TORCH_VERSION_PATCH %s\n#define TORCH_VERSION_ABI_TAG 0\n#define TORCH_VERSION "%s"\n' \
    "${v%%.*}" "$(echo "$v" | cut -d. -f2)" "${v##*.}" "$v" > "$STUB/torch/include/torch/headeronly/version.h"
export PYTHONPATH=$PT:$STUB${PYTHONPATH:+:$PYTHONPATH}
"$PY" -c 'import yaml, typing_extensions, torchgen' || {
    echo "python3 needs PyYAML and typing_extensions" >&2; exit 1; }

SRC=${EXECUTORCH_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/executorch
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$ET_TAG" https://github.com/pytorch/executorch
    retry git -C "$SRC" submodule update --init --depth 1 --recursive --quiet \
        backends/xnnpack/third-party third-party/flatbuffers third-party/flatcc \
        third-party/json third-party/gflags third-party/pocketfft third-party/ao
    # Eigen lives on gitlab.com alone, which refused clones under load for
    # hours on the day 0.6.0 shipped. Git fetches the submodule by its pinned
    # commit hash, so a mirror cannot hand over different code: try
    # upstream, then two GitHub mirrors of it.
    eigen=kernels/optimized/third-party/eigen
    eigen_commit=$(git -C "$SRC" ls-tree HEAD "$eigen" | awk '{print $3}')
    fetch_eigen() {  # url
        rm -rf "${SRC:?}/$eigen" && git init --quiet "$SRC/$eigen" &&
            git -C "$SRC/$eigen" fetch --quiet --depth 1 "$1" "$eigen_commit" &&
            git -C "$SRC/$eigen" checkout --quiet FETCH_HEAD
    }
    for url in https://gitlab.com/libeigen/eigen.git https://github.com/eigen-mirror/eigen.git \
               https://github.com/live-clones/eigen.git; do
        retry fetch_eigen "$url" && break
    done
    [ "$(git -C "$SRC/$eigen" rev-parse HEAD 2>/dev/null)" = "$eigen_commit" ] || {
        echo "could not fetch Eigen $eigen_commit from any source" >&2; exit 1; }
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$ET_COMMIT" ] || { echo "ExecuTorch is at $got, expected $ET_COMMIT" >&2; exit 1; }
SRC=$(cd "$SRC" && pwd -P)
OPS=$(grep -v '^\s*$' "$HERE/ops.txt" | paste -sd, -)
MAP="-ffile-prefix-map=$SRC=executorch -ffile-prefix-map=$STUB/torch/include=torch -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

# QNN: the Qualcomm backend beside XNNPACK, in a second arm64 library,
# libwmexecutorch_qnn.so, its own gate (`executorchqnn`, never F-Droid's:
# build-fdroid-libs.sh sets WMKB_ET_QNN=0). EtNative loads it instead of
# libwmexecutorch.so on a Snapdragon with an NPU, and models exported for
# that NPU run their delegated parts there through the QNN libraries the
# `npu` gate delivers. Its build reads only include/QNN of an SDK root.
QNN_ROOT=
if [ "${WMKB_ET_QNN:-1}" = 1 ]; then
    QNN_ROOT=$WORK/qnn-sdk
    if [ -n "${QNN_INCLUDE:-}" ]; then
        mkdir -p "$QNN_ROOT/include" && ln -sfn "$(cd "$QNN_INCLUDE" && pwd -P)" "$QNN_ROOT/include/QNN"
    elif [ ! -f "$QNN_ROOT/include/QNN/Saver/QnnSaver.h" ]; then
        rm -rf "$QNN_ROOT/include" && mkdir -p "$QNN_ROOT/include"
        retry "$PY" -I "$HERE/qnn_headers.py" "$QNN_ZIP_URL" "$QNN_HEADERS_DIGEST" "$QNN_ROOT/include"
    fi
    [ -f "$QNN_ROOT/include/QNN/QnnInterface.h" ] || { echo "no QNN headers under $QNN_ROOT" >&2; exit 1; }
    MAP="$MAP -ffile-prefix-map=$(cd "$QNN_ROOT/include/QNN" && pwd -P)=qnn"
    # ExecuTorch builds the Qualcomm delegate as a shared library with its own
    # static copy of the runtime, whose backend registry ours would never see.
    # Built static instead, and not installed (its static parts are not in the
    # export set); the JNI step links the archives straight out of the build
    # tree, the delegate's own whole so its registration is kept.
    q=$SRC/backends/qualcomm/CMakeLists.txt
    if grep -q 'add_library(qnn_executorch_backend SHARED)' "$q"; then
        "$PY" - "$q" <<'EOF'
import re, sys
p = sys.argv[1]
s = open(p).read()
s = s.replace("add_library(qnn_executorch_backend SHARED)", "add_library(qnn_executorch_backend STATIC)", 1)
s, n = re.subn(r"install\(\n  TARGETS qnn_executorch_backend\n.*?\n\)\n", "", s, count=1, flags=re.S)
assert n == 1, "install block not found"
open(p, "w").write(s)
EOF
    fi
    # Nor in executorch_backends, the installed target that links every
    # delegate built, whose export would then need it.
    top=$SRC/CMakeLists.txt
    if grep -q 'list(APPEND _executorch_backends qnn_executorch_backend)' "$top"; then
        "$PY" - "$top" <<'EOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = "  list(APPEND _executorch_backends qnn_executorch_backend)\n"
assert s.count(old) == 1, "QNN backend list entry not found"
open(p, "w").write(s.replace(old, "", 1))
EOF
    fi
fi

# Vulkan: ExecuTorch's GPU backend beside XNNPACK, in libwmexecutorch_vulkan.so
# (arm64, gate `executorchvulkan`), for programs exported with its Vulkan
# partitioner. It opens the system's libvulkan.so through volk at run time
# and compiles its GLSL shaders into the library at build time, with a
# shaderc glslc newer than the NDK's (native/vulkan.sh builds one, or GLSLC
# names one). Its three headers-only submodules are fetched here.
VULKAN_GLSLC=
if [ "${WMKB_ET_VULKAN:-1}" = 1 ]; then
    retry git -C "$SRC" submodule update --init --depth 1 --quiet \
        backends/vulkan/third-party/Vulkan-Headers backends/vulkan/third-party/volk \
        backends/vulkan/third-party/VulkanMemoryAllocator
    # shellcheck source=../vulkan.sh
    . "$HERE/../vulkan.sh"
    vulkan_setup "$WORK" "$BIN"
fi

# MediaTek: ExecuTorch's NeuroPilot backend beside XNNPACK, in
# libwmexecutorch_neuron.so (arm64, gate `executorchneuron`), for programs
# exported for a Dimensity NPU (the 9300 and 9400 so far) with MediaTek's
# converter. Open source throughout: it opens the phone's own NeuroPilot
# adapter (libneuronusdk_adapter.mtk.so or libneuron_adapter.so) at run time.
# Patched like QNN's: built static and kept out of the export set. Its buffer
# allocator, a library from MediaTek's SDK that apps would have to ship, is
# made optional: ExecuTorch sets it up from a static initialiser that calls a
# null function where that library is missing, which would take the process
# down as soon as ours loads; without it, the backend copies its inputs as
# it already does for memory the allocator did not hand out.
if [ "${WMKB_ET_NEURON:-1}" = 1 ]; then
    "$PY" -I "$HERE/patch_neuron.py" "$SRC"
    # NeuronAdapter.h, MediaTek's NNAPI-like interface the backend calls
    # through: not in ExecuTorch's repository but in AOSP's copy of it, with
    # MediaTek's notice, so it is fetched at a pinned commit, checked, and
    # never committed (nor built by F-Droid).
    na=$SRC/backends/mediatek/runtime/include/api/NeuronAdapter.h
    if [ "$(shasum -a 256 "$na" 2>/dev/null | cut -c1-64)" != "$NEURON_ADAPTER_SHA256" ]; then
        retry curl -sSfL -o "$WORK/NeuronAdapter.b64" \
            "https://android.googlesource.com/platform/external/executorch/+/$NEURON_ADAPTER_AOSP/backends/mediatek/runtime/include/api/NeuronAdapter.h?format=TEXT"
        "$PY" -c 'import base64, sys; open(sys.argv[2], "wb").write(base64.b64decode(open(sys.argv[1], "rb").read()))' \
            "$WORK/NeuronAdapter.b64" "$na"
        [ "$(shasum -a 256 "$na" | cut -c1-64)" = "$NEURON_ADAPTER_SHA256" ] ||
            { echo "NeuronAdapter.h does not match $NEURON_ADAPTER_SHA256" >&2; exit 1; }
    fi
fi

# XNNPACK downloads Arm's KleidiAI from gitlab.arm.com while configuring,
# once per build directory and with no retry, and that host refuses a
# request now and then. Fetched here instead, from the URL and hash XNNPACK
# pins, with retries, and handed over as KLEIDIAI_SOURCE_DIR.
KLEIDIAI_SRC=$WORK/kleidiai-xnnpack
if [ ! -f "$KLEIDIAI_SRC/CMakeLists.txt" ]; then
    dl=$SRC/backends/xnnpack/third-party/XNNPACK/cmake/DownloadKleidiAI.cmake
    url=$(sed -n 's/^ *URL \(https:[^ ]*\)$/\1/p' "$dl")
    sha=$(sed -n 's/^ *URL_HASH SHA256=\([0-9a-f]*\)$/\1/p' "$dl")
    [ -n "$url" ] && [ -n "$sha" ] || { echo "no KleidiAI URL in $dl" >&2; exit 1; }
    retry curl -fsSL -o "$WORK/kleidiai-xnnpack.zip" "$url"
    echo "$sha  $WORK/kleidiai-xnnpack.zip" | shasum -a 256 -c - > /dev/null ||
        { echo "KleidiAI archive does not match $sha" >&2; exit 1; }
    rm -rf "$KLEIDIAI_SRC" "$WORK/kleidiai-unzip"
    "$PY" -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' \
        "$WORK/kleidiai-xnnpack.zip" "$WORK/kleidiai-unzip"
    mv "$WORK/kleidiai-unzip"/kleidiai-* "$KLEIDIAI_SRC"
    rm -rf "$WORK/kleidiai-unzip" "$WORK/kleidiai-xnnpack.zip"
fi

# abi, then "qnn", "vulkan" or "neuron" for a delegate variant, then where to
build_et() {
    local abi=$1 variant=${2:-}
    local et=$WORK/et-$abi$variant
    local prefix=$WORK/et-install-$abi$variant
    local name=wmexecutorch${variant:+_$variant}
    local backend=()
    case $variant in
        qnn) backend=(-DEXECUTORCH_BUILD_QNN=ON -DQNN_SDK_ROOT="$QNN_ROOT") ;;
        vulkan) backend=(-DEXECUTORCH_BUILD_VULKAN=ON -DGLSLC_PATH="$VULKAN_GLSLC") ;;
        neuron) backend=(-DEXECUTORCH_BUILD_NEURON=ON) ;;
    esac
    # MediaTek's adapter calls AHardwareBuffer (Android 8); every phone
    # with a NeuroPilot NPU ExecuTorch supports runs Android 14 or later.
    local platform=24
    [ "$variant" = neuron ] && platform=26
    common=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-$platform -DANDROID_STL=c++_static
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" -DCMAKE_ASM_FLAGS="$MAP"
    )
    cmake -S "$SRC" -B "$et" -G Ninja "${common[@]}" -DCMAKE_INSTALL_PREFIX="$prefix" \
        -DPYTHON_EXECUTABLE="$PY" -DBUILD_TESTING=OFF \
        -DEXECUTORCH_BUILD_XNNPACK=ON -DKLEIDIAI_SOURCE_DIR="$KLEIDIAI_SRC" \
        -DEXECUTORCH_BUILD_EXTENSION_MODULE=ON -DEXECUTORCH_BUILD_EXTENSION_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_DATA_LOADER=ON -DEXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR=ON \
        -DEXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP=ON \
        -DEXECUTORCH_BUILD_KERNELS_OPTIMIZED=ON -DEXECUTORCH_BUILD_KERNELS_QUANTIZED=OFF \
        -DEXECUTORCH_BUILD_DEVTOOLS=OFF -DEXECUTORCH_BUILD_ANDROID_JNI=OFF \
        -DEXECUTORCH_ENABLE_LOGGING=OFF \
        -DEXECUTORCH_SELECT_OPS_LIST="$OPS" "${backend[@]}" > "$et.log" 2>&1 || { tail -30 "$et.log"; exit 1; }
    cmake --build "$et" --target install >> "$et.log" 2>&1 || { tail -40 "$et.log"; exit 1; }

    # A delegate's archives straight out of the build tree: the one that
    # registers the backend whole, the rest as a group (CMakeLists.txt).
    local jni=$WORK/jni-$abi$variant
    local backend_libs="" backend_dir="" backend_whole=""
    case $variant in
        qnn) backend_dir=backends/qualcomm backend_whole=libqnn_executorch_backend.a ;;
        vulkan) backend_dir=backends/vulkan backend_whole=libvulkan_backend.a ;;
        neuron) backend_dir=backends/mediatek backend_whole=libneuron_backend.a ;;
    esac
    if [ -n "$backend_dir" ]; then
        backend_libs=$(find "$et/$backend_dir" -name '*.a' | LC_ALL=C sort | paste -sd';' -)
        [ -n "$backend_libs" ] || { echo "no $variant backend archives in $et" >&2; exit 1; }
    fi
    cmake -S "$HERE" -B "$jni" -G Ninja "${common[@]}" -DET_PREFIX="$prefix" \
        -DWM_LIB_NAME="$name" -DWM_BACKEND="$variant" -DWM_BACKEND_LIBS="$backend_libs" \
        -DWM_BACKEND_WHOLE="$backend_whole" > "$jni.log" 2>&1 \
        || { tail -30 "$jni.log"; exit 1; }
    cmake --build "$jni" >> "$jni.log" 2>&1 || { tail -40 "$jni.log"; exit 1; }
    local dest=${3:-$OUT}/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/lib$name.so" "$jni/lib$name.so"
    echo "$abi lib$name.so: $(wc -c < "$dest/lib$name.so") bytes"
}

for abi in ${ET_ABIS:-${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}}; do
    [ "${WMKB_ET_CPU:-1}" = 1 ] && build_et "$abi"
    if [ "$abi" = arm64-v8a ] && [ -n "$QNN_ROOT" ]; then
        build_et "$abi" qnn "${ET_QNN_OUT:-$REPO/core/voice/src/executorchqnn/jniLibs}"
    fi
    if [ "$abi" = arm64-v8a ] && [ -n "$VULKAN_GLSLC" ]; then
        build_et "$abi" vulkan "${ET_VULKAN_OUT:-$REPO/core/voice/src/executorchvulkan/jniLibs}"
    fi
    if [ "$abi" = arm64-v8a ] && [ "${WMKB_ET_NEURON:-1}" = 1 ]; then
        build_et "$abi" neuron "${ET_NEURON_OUT:-$REPO/core/voice/src/executorchneuron/jniLibs}"
    fi
done
