#!/usr/bin/env bash
# Builds libwmcld3.so (cld3_jni.cpp over Google's CLD3 language identifier and
# a static protobuf-lite) for every ABI and copies it into
# core/intelligence/src/langid/jniLibs/. Fetches CLD3 and protobuf at the
# pinned commits unless CLD3_SRC / PROTOBUF_SRC point at checkouts of them,
# builds protoc from the same protobuf for this machine, and generates CLD3's
# three .proto files with it, so the generated code always matches the
# runtime it links. Needs CMake, Ninja and a host C++ compiler. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/cld3-jni/build.sh
set -euo pipefail

# google/cld3, archived 2022: the model is compiled into the source.
CLD3_COMMIT=b48dc46512566f5a2d41118c8c1116c4f96dc661
# protobuf 3.21.12, the last release whose C++ runtime needs no Abseil.
PROTOBUF_TAG=v21.12
PROTOBUF_COMMIT=f0dc78d7e6e331b8c6bb2d5283e06aa26883ca7c

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# Where the library goes: a jniLibs directory of its own, so the on-demand
# :feature:langid split carries it alone on Play. F-Droid's recipe
# scandeletes the same path.
OUT=${CLD3_OUT:-$REPO/core/intelligence/src/langid/jniLibs}
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
# dir, repository URL, commit: a shallow fetch of exactly that commit.
fetch_commit() {
    local dir=$1 url=$2 commit=$3
    rm -rf "$dir" && git init -q "$dir" &&
        git -C "$dir" fetch -q --depth 1 "$url" "$commit" &&
        git -C "$dir" checkout -q FETCH_HEAD
}

CLD3=${CLD3_SRC:-}
if [ -z "$CLD3" ]; then
    CLD3=$WORK/cld3
    retry fetch_commit "$CLD3" https://github.com/google/cld3 "$CLD3_COMMIT"
fi
[ "$(git -C "$CLD3" rev-parse HEAD)" = "$CLD3_COMMIT" ] || { echo "CLD3 is not at $CLD3_COMMIT" >&2; exit 1; }
CLD3=$(cd "$CLD3" && pwd -P)

PB=${PROTOBUF_SRC:-}
if [ -z "$PB" ]; then
    PB=$WORK/protobuf
    retry fetch_commit "$PB" https://github.com/protocolbuffers/protobuf "$PROTOBUF_COMMIT"
fi
[ "$(git -C "$PB" rev-parse HEAD)" = "$PROTOBUF_COMMIT" ] || { echo "protobuf is not at $PROTOBUF_COMMIT" >&2; exit 1; }
PB=$(cd "$PB" && pwd -P)

# protoc for this machine, from the same source.
cmake -S "$PB/cmake" -B "$WORK/protoc" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -Dprotobuf_BUILD_TESTS=OFF -Dprotobuf_WITH_ZLIB=OFF -Dprotobuf_BUILD_SHARED_LIBS=OFF > "$WORK/protoc.log" 2>&1 \
    && cmake --build "$WORK/protoc" --target protoc >> "$WORK/protoc.log" 2>&1 \
    || { tail -30 "$WORK/protoc.log"; exit 1; }

# CLD3's sources include its protos as cld_3/protos/<name>.pb.h.
GEN=$WORK/gen
mkdir -p "$GEN/cld_3/protos"
(cd "$CLD3/src" && "$WORK/protoc/protoc" --cpp_out="$GEN/cld_3/protos" \
    feature_extractor.proto sentence.proto task_spec.proto)

# Every path on the build machine is mapped out of the library, so two
# machines with the same NDK build the same bytes. F-Droid's reproducible
# build relies on that.
MAP="-ffile-prefix-map=$CLD3=cld3 -ffile-prefix-map=$PB=protobuf -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk"

build() {
    local abi=$1
    local out=$WORK/build-$abi
    cmake -S "$HERE" -B "$out" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_FLAGS="$MAP" -DCMAKE_CXX_FLAGS="$MAP" \
        -DCLD3_SRC="$CLD3" -DPROTOBUF_SRC="$PB" -DCLD3_GEN="$GEN" > "$out.log" 2>&1 \
        || { tail -30 "$out.log"; exit 1; }
    cmake --build "$out" --target wmcld3 >> "$out.log" 2>&1 || { tail -40 "$out.log"; exit 1; }
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmcld3.so" "$out/libwmcld3.so"
    echo "$abi libwmcld3.so: $(wc -c < "$dest/libwmcld3.so") bytes"
    rm -rf "$out"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64}; do
    case $abi in
        arm64-v8a|armeabi-v7a|x86_64) build "$abi" ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
