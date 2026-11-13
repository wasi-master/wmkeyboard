#!/usr/bin/env bash
# Builds libwmbergamot.so (bergamot_jni.cpp over Mozilla's bergamot-translator,
# PCRE2 and zstd's decompressor) for every ABI and copies it into
# core/intelligence/src/bergamot/jniLibs/. Fetches the three at their pinned
# commits unless BERGAMOT_SRC, PCRE2_SRC and ZSTD_SRC point at checkouts of
# them. Needs git, CMake and Ninja on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/bergamot-jni/build.sh
set -euo pipefail

# The engine Firefox ships: the `translations-wasm-v2` record names this
# revision of mozilla/translations (inference/, bergamot-translator v0.6.0).
BERGAMOT_COMMIT=1de4a085d3a7afb625c51a60aabb5ad298e4059f
PCRE2_TAG=pcre2-10.49
PCRE2_COMMIT=6f9d7c1373262c541324a16a358785b33ef116cf
ZSTD_TAG=v1.5.7
ZSTD_COMMIT=f8745da6ff1ad1e7bab384bd1f9d742439278e99

# The submodules the CPU library compiles.
MARIAN_3RD=inference/marian-fork/src/3rd_party
SUBMODULES="inference/3rd_party/ssplit-cpp $MARIAN_3RD/ruy $MARIAN_3RD/sentencepiece $MARIAN_3RD/simd_utils $MARIAN_3RD/intgemm"

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: its own jniLibs directory, so the Play split that
# carries it carries nothing else. F-Droid's recipe scandeletes the same path.
OUT=${BERGAMOT_OUT:-$REPO/core/intelligence/src/bergamot/jniLibs}
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
SRC= PCRE2= ZSTD= MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
prepared=
prepare() {
    [ -n "$prepared" ] && return 0
    prepared=1
    SRC=${BERGAMOT_SRC:-}
    if [ -z "$SRC" ]; then
        SRC=$WORK/translations
        # The repository also holds the training pipeline and its data; only
        # inference/ is checked out, and blobs come down as they are needed.
        retry fresh_clone "$SRC" --quiet --filter=blob:none --no-checkout https://github.com/mozilla/translations
        git -C "$SRC" sparse-checkout set --no-cone inference
        git -C "$SRC" checkout --quiet "$BERGAMOT_COMMIT"
        # shellcheck disable=SC2086
        retry git -C "$SRC" submodule update --init --depth 1 $SUBMODULES
        retry git -C "$SRC/$MARIAN_3RD/ruy" submodule update --init --depth 1 third_party/cpuinfo
    fi
    SRC=$(cd "$SRC" && pwd -P)
    check_commit "$SRC" "$BERGAMOT_COMMIT" "mozilla/translations"
    # Marian's configure runs `git submodule update --init --recursive`. Marking
    # every submodule "update = none" (the fetched ones are checked out already)
    # keeps it from cloning the rest: CUDA's NCCL, FBGEMM, emsdk, a second Marian.
    mark_none() {  # repository dir
        git -C "$1" show HEAD:.gitmodules > "$WORK/gitmodules" 2>/dev/null || return 0
        git config -f "$WORK/gitmodules" --get-regexp '^submodule\..*\.path$' | while read -r key _; do
            name=${key#submodule.}
            git -C "$1" config "submodule.${name%.path}.update" none
        done
    }
    mark_none "$SRC"
    mark_none "$SRC/$MARIAN_3RD/ruy"
    # Clang 21 (NDK r29) refuses an out-of-range enum value in a constant
    # expression, which SentencePiece's trainer has. Marian links the trainer
    # but the keyboard never runs it; a plain const says the same thing.
    spm_trainer=$SRC/$MARIAN_3RD/sentencepiece/src/trainer_interface.cc
    sed -i.orig 's/constexpr unicode_script::ScriptType kAnyType/const unicode_script::ScriptType kAnyType/' "$spm_trainer"
    rm -f "$spm_trainer.orig"

    PCRE2=${PCRE2_SRC:-}
    if [ -z "$PCRE2" ]; then
        PCRE2=$WORK/pcre2
        retry fresh_clone "$PCRE2" --quiet --depth 1 --branch "$PCRE2_TAG" https://github.com/PCRE2Project/pcre2
    fi
    PCRE2=$(cd "$PCRE2" && pwd -P)
    check_commit "$PCRE2" "$PCRE2_COMMIT" "PCRE2"

    ZSTD=${ZSTD_SRC:-}
    if [ -z "$ZSTD" ]; then
        ZSTD=$WORK/zstd
        retry fresh_clone "$ZSTD" --quiet --depth 1 --branch "$ZSTD_TAG" https://github.com/facebook/zstd
    fi
    ZSTD=$(cd "$ZSTD" && pwd -P)
    check_commit "$ZSTD" "$ZSTD_COMMIT" "zstd"

    # Every path on the build machine is mapped out of the library (__FILE__ in
    # Marian's error messages, the NDK's libc++ headers), so two machines with
    # the same NDK build the same bytes. F-Droid's reproducible build relies on that.
    # The last matching map wins, so the build directory comes first: a fresh
    # clone sits inside it and has to map to the same name as a checkout passed in.
    MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$SRC=translations -ffile-prefix-map=$PCRE2=pcre2 -ffile-prefix-map=$ZSTD=zstd -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"
}

# API 28, not the app's 24: Marian's path library (pathie-cpp) calls iconv,
# nl_langinfo and glob, which bionic has only from Android 9. BergamotNative
# does not load the library on anything older.
#
# abi  Marian BUILD_ARCH  instruction-set flags  extra cmake flags
build() {
    local abi=$1 arch=$2 flags=$3; shift 3
    prepare
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DBERGAMOT_SRC="$SRC" -DPCRE2_SRC="$PCRE2" -DZSTD_SRC="$ZSTD" \
        -DWM_PREFIX_MAP="$MAP" -DWM_ARCH_FLAGS="$flags" -DBUILD_ARCH="$arch" "$@" > "$out.log" 2>&1 \
        || { tail -40 "$out.log"; exit 1; }
    # Marian bakes every CMake cache value into build_info.cpp for its
    # --build-info flag, build paths included. Nothing here prints it.
    printf '%s\n' '#include "common/build_info.h"' \
        'std::string marian::cmakeBuildOptions() { return ""; }' \
        'std::string marian::cmakeBuildOptionsAdvanced() { return ""; }' \
        > "$out/bergamot/marian-fork/src/common/build_info.cpp"
    cmake --build "$out" --target wmbergamot >> "$out.log" 2>&1 || { tail -60 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmbergamot.so" "$out/libwmbergamot.so"
    echo "$abi libwmbergamot.so: $(wc -c < "$dest/libwmbergamot.so") bytes"
}

# The armeabi-v7a stand-in: stub.c, one function, no dependencies.
stub() {
    local abi=$1
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/clang" --target=armv7a-linux-androideabi28 -mthumb -O2 -shared -fPIC -nostdlib++ \
        -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384 $MAP \
        -o "$WORK/libwmbergamot-$abi.so" "$HERE/stub.c"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmbergamot.so" "$WORK/libwmbergamot-$abi.so"
    echo "$abi libwmbergamot.so (stub): $(wc -c < "$dest/libwmbergamot.so") bytes"
}

# arm64: Marian's integer GEMM runs on Google's ruy, with simde standing in for
# the SSE it was written against (the path Firefox's own arm64 builds take).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a) build arm64-v8a armv8-a "" ;;
        # No engine on 32-bit ARM: Marian's NEON path (sse2neon) needs
        # AArch64. stub.c only fails to load, so the split and the pins
        # still cover both ABIs; BergamotNative wants a 64-bit process.
        armeabi-v7a) stub armeabi-v7a ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
