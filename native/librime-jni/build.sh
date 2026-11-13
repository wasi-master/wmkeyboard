#!/usr/bin/env bash
# Builds libwmrime.so (rime_jni.cc + rime_bridge.cc over a static librime,
# yaml-cpp, leveldb, marisa-trie, OpenCC and Boost headers) for every ABI and
# copies it into core/input/src/main/jniLibs/. Fetches librime at the pinned
# release with the submodules it pins, and Boost at the pinned release,
# unless RIME_SRC / BOOST_SRC point at them already. Needs CMake and Ninja on
# PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/librime-jni/build.sh
set -euo pipefail

RIME_TAG=1.17.0
RIME_COMMIT=33e78140250125871856cdc5b42ddc6a5fcd3cd4
# The dependencies are librime's own submodules at the commits RIME_COMMIT
# pins; build.sh checks they came out at these.
DEPS="leveldb:99b3c03b3284f5886f9ef9a4ef703d57373e61be
marisa-trie:3e87d53b78e15f2f43783d5e376561a8c9722051
opencc:556ed22496d650bd0b13b6c163be9814637970ae
yaml-cpp:2f86d13775d119edbb69af52e5f566fd65c6953b"
BOOST_VERSION=1.92.0
BOOST_SHA256=9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: the jniLibs directory of the module that packages it
# for the non-Play builds. F-Droid's recipe scandeletes the same path.
OUT=${RIME_OUT:-$REPO/core/input/src/main/jniLibs}
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
submodules() {  # librime checkout
    git -C "$1" submodule update --init --depth 1 deps/leveldb deps/marisa-trie deps/opencc deps/yaml-cpp
}
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

SRC=${RIME_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/librime
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$RIME_TAG" https://github.com/rime/librime
    retry submodules "$SRC"
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$RIME_COMMIT" ] || { echo "librime is at $got, expected $RIME_COMMIT" >&2; exit 1; }
while IFS=: read -r dep commit; do
    got=$(git -C "$SRC/deps/$dep" rev-parse HEAD)
    [ "$got" = "$commit" ] || { echo "deps/$dep is at $got, expected $commit" >&2; exit 1; }
done <<< "$DEPS"

# Boost's release in its CMake layout (every library under libs/<name>/),
# headers only: nothing of it is compiled.
BOOST=${BOOST_SRC:-}
if [ -z "$BOOST" ]; then
    tarball=$WORK/boost-$BOOST_VERSION-cmake.tar.xz
    retry curl -fsSL -o "$tarball" \
        "https://github.com/boostorg/boost/releases/download/boost-$BOOST_VERSION/boost-$BOOST_VERSION-cmake.tar.xz"
    [ "$(sha256 "$tarball")" = "$BOOST_SHA256" ] || { echo "Boost tarball checksum mismatch" >&2; exit 1; }
    tar -xf "$tarball" -C "$WORK"
    rm "$tarball"
    BOOST=$WORK/boost-$BOOST_VERSION
fi

# Every path on the build machine is mapped out of the library (__FILE__ in
# asserts, the NDK's libc++ headers), so two machines with the same NDK build
# the same bytes. F-Droid's reproducible build relies on that. The work
# directory goes first: librime and Boost are usually fetched inside it, and
# clang takes the last map that matches, so theirs must come after.
MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$SRC=librime -ffile-prefix-map=$BOOST=boost -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" \
        -DRIME_SRC="$SRC" -DBOOST_SRC="$BOOST" -DRIME_VERSION="$RIME_TAG" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmrime >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmrime.so" "$out/libwmrime.so"
    echo "$abi libwmrime.so: $(wc -c < "$dest/libwmrime.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a; do
    if wanted "$abi"; then build "$abi"; fi
done
