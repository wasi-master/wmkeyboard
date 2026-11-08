#!/usr/bin/env bash
# Builds libwmdocscan.so (wm_docscan_jni.cpp and docscan.cpp over a static
# OpenCV, core, imgproc and geometry only) for every ABI and copies it into
# core/intelligence/src/docscan/jniLibs/. Fetches OpenCV at the pinned commit
# unless OPENCV_SRC points at a checkout of it. Needs CMake and Ninja on PATH.
# See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/docscan-jni/build.sh
set -euo pipefail

OPENCV_TAG=5.0.0
OPENCV_COMMIT=40738fb16ceddb5fb3fea747585f7ce6abb0605b
# KleidiCV, Arm's image-processing kernels, as OpenCV's HAL on arm64: the
# release OpenCV 5.0.0's hal/kleidicv/kleidicv.cmake names, fetched here and
# checked against this hash so configuring downloads nothing. Bump with OpenCV.
KLEIDICV_TAG=26.03
KLEIDICV_SHA256=1bb4078fd215565f3906c33f76a00e7b843328965230d765afcd0d51141c434f

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: a directory of its own, so the on-demand
# :feature:docscan split carries it alone on Play. F-Droid's recipe
# scandeletes the same path.
OUT=${DOCSCAN_OUT:-$REPO/core/intelligence/src/docscan/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
    echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
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

SRC=${OPENCV_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/opencv
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$OPENCV_TAG" https://github.com/opencv/opencv
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$OPENCV_COMMIT" ] || { echo "OpenCV is at $got, expected $OPENCV_COMMIT" >&2; exit 1; }

# KleidiCV's release archive, checked once, for the arm64 build only, and
# handed to OpenCV as the URL its own download step reads from. Not as
# KLEIDICV_SOURCE_PATH: OpenCV 5.0.0 switches KleidiCV off when that path is
# valid (cmake/OpenCVFindLibsPerf.cmake turns HAVE_KLEIDICV on, then off in
# the else branch), so a source tree passed that way builds without it. The
# archive is the one OpenCV pins by MD5, so its check passes too.
KLEIDICV_DL=
if [[ " ${WMKB_ABIS:-arm64-v8a} " == *" arm64-v8a "* ]]; then
    KLEIDICV_DL=$WORK/kleidicv-dl
    mkdir -p "$KLEIDICV_DL"
    retry curl -sSfL -o "$KLEIDICV_DL/kleidicv-$KLEIDICV_TAG.tar.gz" \
        "https://gitlab.arm.com/kleidi/kleidicv/-/archive/$KLEIDICV_TAG/kleidicv-$KLEIDICV_TAG.tar.gz"
    got=$(shasum -a 256 "$KLEIDICV_DL/kleidicv-$KLEIDICV_TAG.tar.gz" | cut -c1-64)
    [ "$got" = "$KLEIDICV_SHA256" ] || { echo "KleidiCV archive is $got, expected $KLEIDICV_SHA256" >&2; exit 1; }
fi

# Every path on the build machine is mapped out of the library (__FILE__ in
# CV_Error and asserts, the NDK's libc++ headers), so two machines with the
# same NDK build the same bytes. F-Droid's reproducible build relies on that.
MAP="-ffile-prefix-map=$SRC=opencv -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
SECTIONS="-ffunction-sections -fdata-sections"

build() {
    local abi=$1
    local ocv=$WORK/opencv-$abi out=$WORK/build-$abi
    local android=(
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    )
    # KleidiCV on arm64: OpenCV routes the scanner's blur, colour, threshold,
    # morphology and warp calls to it where it has a kernel (NEON, SVE2 or
    # SME, picked at run time), and to its own code otherwise.
    local hal=()
    if [ "$abi" = arm64-v8a ]; then
        # OpenCV unpacks it under its build tree, inside $WORK, so the
        # prefix map above covers its paths too.
        hal=(-DWITH_KLEIDICV=ON -DOPENCV_KLEIDICV_URL="file://$KLEIDICV_DL/"
             -DOPENCV_DOWNLOAD_PATH="$WORK/opencv-downloads")
    fi
    cmake -C "$HERE/opencv.cmake" -S "$SRC" -B "$ocv" -G Ninja "${android[@]}" "${hal[@]}" \
        -DCMAKE_INSTALL_PREFIX="$ocv/install" \
        -DCMAKE_C_FLAGS="$MAP $SECTIONS" -DCMAKE_CXX_FLAGS="$MAP $SECTIONS" > "$ocv.log" 2>&1 \
        || { tail -40 "$ocv.log"; exit 1; }
    # OpenCV writes its configuration, this machine's paths and tools
    # included, into the library as cv::getBuildInformation(), which its
    # startup keeps alive (OPENCV_DUMP_CONFIG prints it). A fixed line
    # instead, or no two machines would build the same bytes.
    printf '"OpenCV %s, core, imgproc and geometry, built by native/docscan-jni\\n"\n' "$OPENCV_TAG" \
        > "$ocv/modules/core/version_string.inc"
    if [ "$abi" = arm64-v8a ]; then
        grep -q "KleidiCV (ver" "$ocv.log" || { echo "OpenCV configured without KleidiCV; see $ocv.log" >&2; exit 1; }
    fi
    cmake --build "$ocv" --target install >> "$ocv.log" 2>&1 || { tail -40 "$ocv.log"; exit 1; }
    cmake -S "$HERE" -B "$out" -G Ninja "${android[@]}" \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_CXX_FLAGS="$MAP" \
        -DOpenCV_DIR="$ocv/install/sdk/native/jni" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmdocscan >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmdocscan.so" "$out/libwmdocscan.so"
    echo "$abi libwmdocscan.so: $(wc -c < "$dest/libwmdocscan.so") bytes"
    # The build tree of one ABI is a few hundred MB; gone before the next.
    rm -rf "$ocv" "$out"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    case $abi in
        arm64-v8a|armeabi-v7a|x86_64) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
