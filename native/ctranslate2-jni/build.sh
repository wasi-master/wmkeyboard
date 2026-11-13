#!/usr/bin/env bash
# Builds libwmct2.so (ct2_jni.cpp over OpenNMT's CTranslate2 and Google's
# SentencePiece) for every ABI and copies it into
# core/intelligence/src/ct2/jniLibs/. Fetches both at their pinned commits,
# with the submodules CTranslate2's CPU build needs and the abseil release
# SentencePiece builds against, unless CT2_SRC and SPM_SRC point at
# checkouts of them (SPM_SRC with abseil already in third_party/abseil-cpp).
# Needs git, CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/ctranslate2-jni/build.sh
set -euo pipefail

CT2_TAG=v4.8.4
CT2_COMMIT=5de572ef60b67a0276c5989ee73d0072808ff4c3
SPM_TAG=v0.2.2
SPM_COMMIT=e0cce7d37b065b5140349dbe12c6bcf6192fdd78
# The tag SentencePiece's own CMake would fetch (SPM_ABSL_PROVIDER=module).
ABSL_TAG=20260526.0
ABSL_COMMIT=5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a

# The submodules a CPU build compiles: ruy for every GEMM (with its cpuinfo),
# spdlog for logging.
CT2_SUBMODULES="third_party/ruy third_party/spdlog"

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: its own jniLibs directory, so the Play split that
# carries it carries nothing else. F-Droid's recipe scandeletes the same path.
OUT=${CT2_OUT:-$REPO/core/intelligence/src/ct2/jniLibs}
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
check_commit() {  # dir, expected commit, name
    local got
    got=$(git -C "$1" rev-parse HEAD)
    [ "$got" = "$2" ] || { echo "$3 is at $got, expected $2" >&2; exit 1; }
}

# The sources, fetched and checked once, the first time an ABI needs the
# engine: F-Droid's armeabi-v7a entry only builds the stub and fetches nothing.
CT2= SPM= MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
prepared=
prepare() {
    [ -n "$prepared" ] && return 0
    prepared=1
    CT2=${CT2_SRC:-}
    if [ -z "$CT2" ]; then
        CT2=$WORK/ctranslate2
        retry fresh_clone "$CT2" --quiet --depth 1 --branch "$CT2_TAG" https://github.com/OpenNMT/CTranslate2
        # shellcheck disable=SC2086
        retry git -C "$CT2" submodule update --init --depth 1 $CT2_SUBMODULES
        retry git -C "$CT2/third_party/ruy" submodule update --init --depth 1 third_party/cpuinfo
    fi
    CT2=$(cd "$CT2" && pwd -P)
    check_commit "$CT2" "$CT2_COMMIT" "CTranslate2"
    # Bionic has no pthread_setaffinity_np. CTranslate2 pins a thread to a
    # core only when asked to (cpu_core_offset, which the bridge leaves at
    # -1), so on Android the pinning takes the branch other systems take:
    # refuse when asked, compile either way.
    pool=$CT2/src/thread_pool.cc
    grep -q '__ANDROID__' "$pool" ||
        sed -i.orig 's/#if !defined(__linux__) || defined(_OPENMP)$/#if !defined(__linux__) || defined(_OPENMP) || defined(__ANDROID__)/' "$pool"
    rm -f "$pool.orig"
    grep -q '__ANDROID__' "$pool" || { echo "the thread_pool.cc edit no longer applies" >&2; exit 1; }

    SPM=${SPM_SRC:-}
    if [ -z "$SPM" ]; then
        SPM=$WORK/sentencepiece
        retry fresh_clone "$SPM" --quiet --depth 1 --branch "$SPM_TAG" https://github.com/google/sentencepiece
        retry fresh_clone "$SPM/third_party/abseil-cpp" --quiet --depth 1 --branch "$ABSL_TAG" https://github.com/abseil/abseil-cpp
    fi
    SPM=$(cd "$SPM" && pwd -P)
    check_commit "$SPM" "$SPM_COMMIT" "SentencePiece"
    check_commit "$SPM/third_party/abseil-cpp" "$ABSL_COMMIT" "abseil"

    # Every path on the build machine is mapped out of the library (__FILE__
    # in assertion messages, the NDK's libc++ headers), so two machines with
    # the same NDK build the same bytes; F-Droid's reproducible build relies
    # on it. The last matching map wins, so the build directory comes first:
    # a fresh clone sits inside it and has to map to the same name as a
    # checkout passed in.
    MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$CT2=ctranslate2 -ffile-prefix-map=$SPM=sentencepiece -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
}

build() {
    local abi=$1
    prepare
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCT2_SRC="$CT2" -DSPM_SRC="$SPM" -DWM_PREFIX_MAP="$MAP" > "$out.log" 2>&1 \
        || { tail -40 "$out.log"; exit 1; }
    cmake --build "$out" --target wmct2 >> "$out.log" 2>&1 || { tail -60 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmct2.so" "$out/libwmct2.so"
    echo "$abi libwmct2.so: $(wc -c < "$dest/libwmct2.so") bytes"
}

# The armeabi-v7a stand-in: stub.c, one function, no dependencies.
stub() {
    local abi=$1
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/clang" --target=armv7a-linux-androideabi24 -mthumb -O2 -shared -fPIC -nostdlib++ \
        -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 $MAP \
        -o "$WORK/libwmct2-$abi.so" "$HERE/stub.c"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmct2.so" "$WORK/libwmct2-$abi.so"
    echo "$abi libwmct2.so (stub): $(wc -c < "$dest/libwmct2.so") bytes"
}

for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        # ruy's NEON kernels, with the dot-product ones picked at run time
        # on the phones that have them (Cortex-A55/A75 and later).
        arm64-v8a) build arm64-v8a ;;
        # No engine on 32-bit ARM: the model alone is 600 MB of int8 weights
        # held in memory, more than a 32-bit process can count on. stub.c
        # only fails to load, so the split and the pins still cover both
        # ABIs; Ct2Native wants a 64-bit process.
        armeabi-v7a) stub armeabi-v7a ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
