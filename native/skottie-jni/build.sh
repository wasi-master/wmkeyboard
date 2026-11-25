#!/usr/bin/env bash
# Builds libwmskottie.so (wm_skottie_jni.cpp over a static, CPU-only Skia with
# Skottie) for every ABI and copies it into core/content/src/skottie/jniLibs/,
# the `skottie` gate's own directory. Fetches Skia at the pinned milestone
# branch unless SKIA_SRC points at a checkout of it, and only the third-party
# trees this build compiles (not Skia's whole DEPS). Needs CMake, Ninja,
# Python 3 and a host C++ compiler on PATH. See README.md.
#
#   ANDROID_NDK=/path/to/ndk native/skottie-jni/build.sh
#
# `--desktop DIR` builds the same library for this machine instead (a .dylib
# on macOS, a .so on Linux) into DIR, for the render test in README.md. It
# takes jni.h from JAVA_HOME, or from the NDK's sysroot when that has none.
#
# gn is built from source too, at the revision Skia's bin/fetch-gn names, and
# kept in the checkout's out/wm-gn; GN may point at another gn instead. No
# prebuilt binary takes part in the build.
set -euo pipefail

SKIA_BRANCH=chrome/m158
SKIA_COMMIT=37e919d8669ebe4a8912dcef03db14b9b55f70c7
# The third-party trees of Skia's DEPS that this build compiles, at the
# commits that DEPS pins. unicodetools is only libgrapheme's Unicode data.
SKIA_DEPS="expat freetype harfbuzz icu libgrapheme libjpeg-turbo libpng libwebp zlib unicodetools"
# The paths of the big ones that are compiled or read, so only those files are
# fetched: ICU's bidi code, HarfBuzz's sources, Expat's library, and the one
# Unicode version libgrapheme's tables are generated from (UCD_VERSION, read
# from its BUILD.gn below).
sparse_paths() {
    case $1 in
        icu) echo "/source/common/ /source/i18n/" ;;
        harfbuzz) echo "/src/" ;;
        expat) echo "/expat/" ;;
        unicodetools) echo "/unicodetools/data/ucd/$UCD_VERSION/" ;;
    esac
}

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# The gate's own directory in the core module that loads it, so its Play
# split carries nothing else. F-Droid's recipe scandeletes the same path.
OUT=${SKOTTIE_OUT:-$REPO/core/content/src/skottie/jniLibs}
DESKTOP=
if [ "${1:-}" = "--desktop" ]; then
    DESKTOP=${2:?"--desktop needs an output directory"}
    mkdir -p "$DESKTOP"
    DESKTOP=$(cd "$DESKTOP" && pwd -P)
fi
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
if [ -n "$NDK" ]; then NDK=$(cd "$NDK" && pwd -P); fi
if [ -z "$DESKTOP" ]; then
    [ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
        echo "Set ANDROID_NDK to an NDK install (r27 or newer)" >&2; exit 1; }
    grep -q "Pkg.Revision = 29.0." "$NDK/source.properties" ||
        echo "warning: not NDK r29, which F-Droid builds with; output will not match the committed libraries" >&2
    BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
fi
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'rm -rf "$WORK"' EXIT

# Network fetches retry: F-Droid's builders share the hosts with everyone, and
# a busy one refusing a fetch should not fail the build.
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
# One commit of one repository into dir, nothing else of its history. Further
# arguments check out only those paths (and fetch only their files).
fetch_commit() {  # dir url commit [sparse paths...]
    local dir=$1 url=$2 commit=$3
    shift 3
    rm -rf "$dir" && mkdir -p "$dir"
    git -C "$dir" init --quiet
    if [ $# -gt 0 ]; then
        git -C "$dir" sparse-checkout set --no-cone "$@"
        git -C "$dir" fetch --quiet --depth 1 --filter=blob:none "$url" "$commit"
    else
        git -C "$dir" fetch --quiet --depth 1 "$url" "$commit"
    fi
    git -C "$dir" -c advice.detachedHead=false checkout --quiet FETCH_HEAD
}

SRC=${SKIA_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/skia
    retry fresh_clone "$SRC" --quiet --depth 1 --branch "$SKIA_BRANCH" https://skia.googlesource.com/skia
fi
SRC=$(cd "$SRC" && pwd -P)
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$SKIA_COMMIT" ] || { echo "Skia is at $got, expected $SKIA_COMMIT ($SKIA_BRANCH)" >&2; exit 1; }

# Fixes to the pinned commit, in patches/, each applied once: a checkout in
# SKIA_SRC that already carries one is left as it is. See README.md.
for patch in "$HERE"/patches/*.patch; do
    [ -f "$patch" ] || continue
    git -C "$SRC" apply --reverse --check "$patch" 2> /dev/null && continue
    git -C "$SRC" apply "$patch" || { echo "$(basename "$patch") does not apply to Skia $SKIA_COMMIT" >&2; exit 1; }
done

UCD_VERSION=$(sed -n 's/^ *UNICODE_VERSION = "\([0-9.]*\)".*/\1/p' "$SRC/third_party/libgrapheme/BUILD.gn")
[ -n "$UCD_VERSION" ] || { echo "no UNICODE_VERSION in third_party/libgrapheme/BUILD.gn" >&2; exit 1; }

# The dependencies, each at the commit Skia's DEPS names; one already there at
# that commit is kept, so a second build fetches nothing.
# DEPS is a Python file of plain literals plus Var(); read, not trusted
# further than that, since it comes from the pinned commit.
python3 - "$SRC/DEPS" $SKIA_DEPS > "$WORK/deps" <<'PY'
import sys
ns = {"Var": lambda k: ns["vars"][k], "Str": str}
exec(open(sys.argv[1]).read(), ns)
for name in sys.argv[2:]:
    url, commit = ns["deps"]["third_party/externals/" + name].rsplit("@", 1)
    print(name, url, commit)
PY
while read -r name url commit; do
    dir=$SRC/third_party/externals/$name
    [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null || true)" = "$commit" ] && continue
    # Word splitting wanted: one argument per sparse path.
    # shellcheck disable=SC2046
    retry fetch_commit "$dir" "$url" "$commit" $(sparse_paths "$name")
done < "$WORK/deps"

# gn from source, at the revision bin/fetch-gn would download a build of.
GN=${GN:-}
if [ -z "$GN" ]; then
    gn_rev=$(sed -n "s/^ *rev = '\([0-9a-f]\{40\}\)'.*/\1/p" "$SRC/bin/fetch-gn")
    [ -n "$gn_rev" ] || { echo "no gn revision in bin/fetch-gn" >&2; exit 1; }
    GN=$SRC/out/wm-gn/out/gn
    if [ ! -x "$GN" ] || [ "$(cat "$SRC/out/wm-gn/.rev" 2>/dev/null)" != "$gn_rev" ]; then
        retry fetch_commit "$SRC/out/wm-gn" https://gn.googlesource.com/gn "$gn_rev"
        (
            cd "$SRC/out/wm-gn"
            # gen.py asks for clang++ unless CXX says otherwise; g++ builds gn too.
            if [ -z "${CXX:-}" ] && ! command -v clang++ > /dev/null; then export CXX=g++ LD=g++; fi
            # The version header gen.py would derive from the full history,
            # which a one-commit fetch does not have.
            python3 build/gen.py --no-last-commit-position > "$WORK/gn-gen.log" 2>&1
            printf '#ifndef OUT_LAST_COMMIT_POSITION_H_\n#define OUT_LAST_COMMIT_POSITION_H_\n#define LAST_COMMIT_POSITION_NUM 0\n#define LAST_COMMIT_POSITION "0 (%s)"\n#endif\n' \
                "${gn_rev:0:12}" > out/last_commit_position.h
            ninja -C out gn > "$WORK/gn-build.log" 2>&1
        ) || { tail -30 "$WORK"/gn-*.log; exit 1; }
        echo "$gn_rev" > "$SRC/out/wm-gn/.rev"
    fi
fi

# CPU raster only: no Ganesh, Graphite, GL, Vulkan or Metal, no PDF, SVG or
# XPS backends, no GIF (Wuffs), RAW, AVIF or JPEG XL decoders, no encoders.
# Bundled third-party code throughout, never the build machine's (an
# official build would otherwise link the system's). Text: HarfBuzz shaping,
# SkUnicode on libgrapheme with ICU's bidi subset (no ICU data file),
# FreeType. Built for size (-Oz).
ARGS='is_official_build=true is_component_build=false is_debug=false
skia_use_system_freetype2=false skia_use_system_harfbuzz=false skia_use_system_libpng=false
skia_use_system_libjpeg_turbo=false skia_use_system_libwebp=false skia_use_system_zlib=false skia_use_system_icu=false
skia_enable_ganesh=false skia_enable_graphite=false skia_use_gl=false skia_use_vulkan=false skia_use_metal=false
skia_use_dawn=false skia_use_angle=false skia_use_egl=false skia_use_x11=false
skia_enable_pdf=false skia_enable_svg=false skia_use_xps=false skia_enable_tools=false skia_use_lua=false
skia_use_perfetto=false skia_use_partition_alloc=false skia_enable_optimize_size=true
skia_use_piex=false skia_use_dng_sdk=false skia_use_wuffs=false skia_use_libjxl_decode=false
skia_use_libavif=false skia_use_crabbyavif=false skia_use_jpeg_gainmaps=false skia_use_ffmpeg=false
skia_use_libpng_decode=true skia_use_libpng_encode=false skia_use_no_png_encode=true
skia_use_libjpeg_turbo_decode=true skia_use_libjpeg_turbo_encode=false skia_use_no_jpeg_encode=true
skia_use_libwebp_decode=true skia_use_libwebp_encode=false skia_use_no_webp_encode=true skia_use_zlib=true
skia_use_freetype=true skia_use_harfbuzz=true skia_pdf_subset_harfbuzz=false
skia_use_icu=false skia_use_libgrapheme=true skia_use_fontconfig=false skia_enable_skottie=true
skia_enable_fontmgr_custom_directory=false skia_enable_fontmgr_custom_embedded=false'
TARGETS="skia modules/skottie modules/skresources modules/sksg modules/skshaper modules/skunicode"

# The paths of this machine are mapped out of every object, so two machines
# with the same NDK build the same bytes. gn compiles with paths relative to
# its output directory, which stays inside the checkout, so Skia's own objects
# need only the checkout and the NDK mapped; leaving the temporary directory
# out of its arguments keeps a second build incremental.
SKIA_MAP="-ffile-prefix-map=$SRC=skia"
[ -n "$NDK" ] && SKIA_MAP="$SKIA_MAP -ffile-prefix-map=$NDK=ndk"
MAP="$SKIA_MAP -ffile-prefix-map=$WORK=build -ffile-prefix-map=$HERE=."
gn_list() { printf '"%s",' $1 | sed 's/,$//'; }

skia_build() {  # out dir (relative to SRC), extra args
    local out=$1 extra=$2
    "$GN" gen "$SRC/$out" --root="$SRC" --args="$(echo "$ARGS $extra" | tr '\n' ' ') extra_cflags=[$(gn_list "$SKIA_MAP")]" \
        > "$WORK/gn.log" 2>&1 || { cat "$WORK/gn.log"; exit 1; }
    ninja -C "$SRC/$out" $TARGETS > "$WORK/ninja.log" 2>&1 || { grep -A20 FAILED "$WORK/ninja.log" | head -60; exit 1; }
    # The defines Skia's public headers were compiled under, for the JNI side.
    local t
    for t in //modules/skottie:skottie //modules/skshaper:skshaper //modules/skunicode:skunicode_core //modules/skresources:skresources; do
        "$GN" desc "$SRC/$out" --root="$SRC" "$t" defines 2>/dev/null
    done | grep -E '^SK_' | grep -vE '^SK(IA|SHAPER|UNICODE)_IMPLEMENTATION|^SK_CODEC_' | sort -u | paste -sd ';' - > "$SRC/$out/wm-defines"
}

jni_build() {  # cmake build dir, skia out dir, then cmake arguments
    local build=$1 skia=$2; shift 2
    cmake -S "$HERE" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DSKIA_SRC="$SRC" -DSKIA_OUT="$SRC/$skia" -DSKIA_DEFINES="$(cat "$SRC/$skia/wm-defines")" "$@" \
        > "$build.log" 2>&1 || { tail -30 "$build.log"; exit 1; }
    cmake --build "$build" --target wmskottie >> "$build.log" 2>&1 || { tail -40 "$build.log"; exit 1; }
}

if [ -n "$DESKTOP" ]; then
    jni=
    if [ -n "${JAVA_HOME:-}" ] && [ -f "$JAVA_HOME/include/jni.h" ]; then
        jni="-I$JAVA_HOME/include -I$(dirname "$(ls "$JAVA_HOME"/include/*/jni_md.h | head -1)")"
    elif [ -n "$NDK" ]; then
        jni="-I$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/sysroot/usr/include | head -1)"
        # The NDK's sysroot carries the target's libc headers too; only jni.h
        # is wanted, so copy it out rather than putting the sysroot on the path.
        mkdir -p "$WORK/jni" && cp "${jni#-I}/jni.h" "$WORK/jni/" && jni="-I$WORK/jni"
    else
        echo "Set JAVA_HOME to a JDK with include/jni.h, or ANDROID_NDK" >&2; exit 1
    fi
    # FreeType for the caller's font files and nothing of the system's: the
    # desktop library is for tests, which pass every font they use.
    skia_build out/wm-desktop "skia_use_fonthost_mac=false skia_use_expat=false
        skia_enable_fontmgr_android=false skia_enable_fontmgr_custom_empty=true"
    jni_build "$WORK/desktop" out/wm-desktop -DCMAKE_CXX_FLAGS="$MAP $jni"
    cp "$WORK"/desktop/libwmskottie.* "$DESKTOP/"
    ls -l "$DESKTOP"/libwmskottie.*
    exit 0
fi

build() {
    local abi=$1 cpu=$2
    # Android's font manager reads /system/etc/fonts.xml with expat.
    skia_build "out/wm-$abi" "target_os=\"android\" target_cpu=\"$cpu\" ndk=\"$NDK\" ndk_api=24
        skia_use_expat=true skia_use_system_expat=false skia_enable_fontmgr_android=true
        skia_enable_fontmgr_android_ndk=false skia_enable_fontmgr_custom_empty=false"
    jni_build "$WORK/build-$abi" "out/wm-$abi" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
        -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_CXX_FLAGS="$MAP"
    local dest=$OUT/$abi
    mkdir -p "$dest"
    "$BIN/llvm-strip" --strip-unneeded -o "$dest/libwmskottie.so" "$WORK/build-$abi/libwmskottie.so"
    echo "$abi libwmskottie.so: $(wc -c < "$dest/libwmskottie.so") bytes"
}

# WMKB_ABIS narrows the build to some of the two (native/build-fdroid-libs.sh).
for abi in ${WMKB_ABIS:-arm64-v8a armeabi-v7a}; do
    case $abi in
        arm64-v8a) build "$abi" arm64 ;;
        armeabi-v7a) build "$abi" arm ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
done
