#!/usr/bin/env bash
# Builds libwmlouis.so (liblouis's translator and louis_jni.c) for every ABI and
# copies it into core/language/src/main/jniLibs/, beside the text engines. It is
# small enough to ship with every build; the tables it reads are a download.
# Fetches liblouis's release at a pinned checksum unless LOUIS_SRC points at an
# unpacked one. Plain clang from the NDK, no autotools: configure's only output
# this needs is two strings and the widechar type, set below.
#
# Liblouis is LGPL-2.1-or-later, which is why it stays a shared library here,
# never linked into anything else.
#
#   ANDROID_NDK=/path/to/ndk native/louis-jni/build.sh
set -euo pipefail

LOUIS_VERSION=3.39.0
LOUIS_SHA256=629fa8cb0dfd9ad457c5bf47a42f0953b673e62c8ad6b1d03ddc4e2bd20008f1

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${LOUIS_OUT:-$REPO/core/language/src/main/jniLibs}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -d "$NDK/toolchains/llvm/prebuilt" ] || {
    echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

SRC=${LOUIS_SRC:-}
if [ -z "$SRC" ]; then
    curl -fsSL --retry 5 --retry-delay 30 -o "$WORK/louis.tar.gz" \
        "https://github.com/liblouis/liblouis/releases/download/v$LOUIS_VERSION/liblouis-$LOUIS_VERSION.tar.gz"
    if command -v sha256sum >/dev/null 2>&1; then
        echo "$LOUIS_SHA256  $WORK/louis.tar.gz" | sha256sum -c -
    else
        echo "$LOUIS_SHA256  $WORK/louis.tar.gz" | shasum -a 256 -c -
    fi
    tar -xzf "$WORK/louis.tar.gz" -C "$WORK"
    SRC=$WORK/liblouis-$LOUIS_VERSION
fi
SRC=$(cd "$SRC" && pwd -P)

# What configure would write. UTF-16, as Java strings are.
mkdir -p "$WORK/inc"
sed 's/@WIDECHAR_TYPE@/unsigned short int/' "$SRC/liblouis/liblouis.h.in" > "$WORK/inc/liblouis.h"
cat > "$WORK/inc/config.h" <<CONFIG
#define PACKAGE_VERSION "$LOUIS_VERSION"
#define TABLESDIR "/nonexistent"
CONFIG

# Build-machine paths are mapped out of the library, so two machines with the
# same NDK make the same bytes (F-Droid's reproducible build relies on it).
MAP="-ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=. -ffile-prefix-map=$NDK=ndk -ffile-prefix-map=$SRC=liblouis"

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
    # -w: liblouis is not ours to tidy.
    "$cc" -shared -fPIC -Os -w $MAP \
        -fvisibility=hidden -ffunction-sections -fdata-sections \
        -I"$WORK/inc" -I"$SRC/liblouis" \
        -o "$WORK/libwmlouis-$abi.so" "$HERE/louis_jni.c" \
        "$SRC/liblouis/commonTranslationFunctions.c" "$SRC/liblouis/compileTranslationTable.c" \
        "$SRC/liblouis/logging.c" "$SRC/liblouis/lou_backTranslateString.c" \
        "$SRC/liblouis/lou_translateString.c" "$SRC/liblouis/maketable.c" \
        "$SRC/liblouis/metadata.c" "$SRC/liblouis/pattern.c" "$SRC/liblouis/utils.c" \
        -Wl,--no-undefined -Wl,--gc-sections \
        -Wl,--version-script="$HERE/jni.map" -Wl,--build-id=sha1 -Wl,-z,max-page-size=16384
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmlouis.so" "$WORK/libwmlouis-$abi.so"
    echo "$abi libwmlouis.so: $(wc -c < "$dest/libwmlouis.so") bytes"
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a x86_64} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

for abi in arm64-v8a armeabi-v7a x86_64; do
    if wanted "$abi"; then build "$abi"; fi
done
