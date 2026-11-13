#!/usr/bin/env bash
# Builds libwmmozc.so (mozc_jni.cc + mozc_bridge.cc over Mozc's converter)
# for every ABI and copies it into core/input/src/mozc/jniLibs/. Mozc builds
# with Bazel upstream, and so does this: the bridge is copied into a Mozc
# checkout at the pinned release as a package of its own (src/wmkeyboard/)
# and built there with Mozc's own Android configuration, the one its CI
# uses for libmozc.so. Fetches Mozc at the pinned tag, checked against its
# commit, unless MOZC_SRC points at a checkout already.
#
#   ANDROID_NDK=/path/to/ndk-r29 native/mozc-jni/build.sh
#
# Bazel, at the version Mozc's src/.bazeliskrc names, comes from the first
# of these that applies:
#
#   MOZC_BAZEL=/path/to/bazel   that binary, which must report the version;
#   `bazel` on PATH             the same, so a bazelisk install serves
#                               (USE_BAZEL_VERSION is exported for it);
#   Linux                       built from source: Bazel's distribution
#                               archive (bazel-<version>-dist.zip, checked
#                               against SHA-256) bootstrapped with its own
#                               compile.sh, which needs a JDK 21 (MOZC_JDK,
#                               JAVA_HOME or Debian's openjdk-21), zip,
#                               unzip, a C++ compiler and Python 3, and
#                               fetches nothing; the result is kept under
#                               ~/.cache/wmkeyboard (MOZC_BAZEL_CACHE) for
#                               the next run on the same machine. Mozc is
#                               then built with that JDK and the machine's
#                               python3, so no prebuilt toolchain is
#                               downloaded at all.
#                               This is the path F-Droid's builder and the
#                               release workflow's reference build take:
#                               F-Droid takes no prebuilt Bazel (#964).
#   macOS                       bazelisk from its GitHub release, checked,
#                               fetching Bazel's own binary, checked.
#
# With --data DIR it also builds Mozc's system dictionary, mozc.data, into
# DIR (on the host, since its generators run there). That file is the
# Japanese pack the keyboard downloads (CjkDictCatalog, `mozc`), not part of
# the library; its SHA-256 is pinned in the catalog. See README.md.
set -euo pipefail

MOZC_TAG=3.35.6354
MOZC_COMMIT=0136c35782536cb43d710575c311cd4185982738
# Bazel itself, at the version Mozc's src/.bazeliskrc names.
BAZEL_VERSION=9.0.2
# Its source distribution archive, the one Bazel bootstraps from
# (https://bazel.build/install/compile-source), as published with the
# release; the checksum is releases.bazel.build's.
BAZEL_DIST_SHA256=d80b8f708e7a5840fd09b853a4343cace7e4c3504d4e8ac67367d4cb6224436e
BAZELISK_VERSION=1.30.0

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
# The directory :core:input packages off Play and :feature:mozc on Play.
OUT=${MOZC_OUT:-$REPO/core/input/src/mozc/jniLibs}
DATA_OUT=
if [ "${1:-}" = "--data" ]; then
    DATA_OUT=${2:?--data needs a directory}
    mkdir -p "$DATA_OUT"
    DATA_OUT=$(cd "$DATA_OUT" && pwd)
fi
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] || {
    echo "Set ANDROID_NDK to an NDK install (r29)" >&2; exit 1; }
NDK=$(cd "$NDK" && pwd -P)
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin)
WORK=$(cd "$(mktemp -d)" && pwd -P)
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT

retry() {
    local n
    for n in 1 2 3 4 5; do
        "$@" && return 0
        echo "attempt $n failed, retrying in $((n * 30)) s: $*" >&2
        sleep $((n * 30))
    done
    return 1
}
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# A JDK of 21 or newer: what Bazel's bootstrap compiles with and what the
# bootstrapped Bazel, which embeds none, runs on. MOZC_JDK, then JAVA_HOME,
# then Debian's openjdk-21 (the F-Droid recipe installs it).
find_jdk() {
    local cand version
    for cand in "${MOZC_JDK:-}" "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk-*; do
        [ -n "$cand" ] && [ -x "$cand/bin/javac" ] || continue
        version=$("$cand/bin/javac" -version 2>&1 | sed -nE 's/^javac ([0-9]+).*/\1/p')
        [ -n "$version" ] && [ "$version" -ge 21 ] || continue
        echo "$cand"
        return 0
    done
    return 1
}

# Bazel's bootstrap: the distribution archive's compile.sh, which builds a
# small Bazel with javac and then the real one with that. Everything it
# needs is in the archive (derived/repository_cache); the local JDK flags
# keep its second stage from fetching a JDK of its own. Leaves
# $BAZEL_SRC/output/bazel.
bootstrap_bazel() {
    local dist=$WORK/bazel-$BAZEL_VERSION-dist.zip
    retry curl -fsSL -o "$dist" \
        "https://github.com/bazelbuild/bazel/releases/download/$BAZEL_VERSION/bazel-$BAZEL_VERSION-dist.zip"
    [ "$(sha256 "$dist")" = "$BAZEL_DIST_SHA256" ] || { echo "bazel dist checksum mismatch" >&2; exit 1; }
    BAZEL_SRC=$WORK/bazel-src
    mkdir -p "$BAZEL_SRC"
    unzip -q "$dist" -d "$BAZEL_SRC"
    echo "bootstrapping Bazel $BAZEL_VERSION from source with $JDK (this takes a while)"
    local started=$(date +%s)
    (cd "$BAZEL_SRC" && env JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" \
        EXTRA_BAZEL_ARGS="--tool_java_runtime_version=local_jdk --java_runtime_version=local_jdk" \
        BAZEL_DIR_STARTUP_OPTIONS="--output_user_root=$ROOT" \
        EMBED_LABEL="$BAZEL_VERSION" \
        bash ./compile.sh > "$WORK/bazel-bootstrap.log" 2>&1) \
        || { echo "compile.sh failed; its last lines:" >&2; tail -60 "$WORK/bazel-bootstrap.log" >&2; exit 1; }
    [ -x "$BAZEL_SRC/output/bazel" ] || { echo "compile.sh left no output/bazel" >&2; exit 1; }
    echo "bootstrapped Bazel in $(( $(date +%s) - started )) s"
}

# Bazel runs every action with paths relative to its execution root, so
# nothing of this machine reaches the library; a fresh output root per run
# keeps two builds from sharing anything but the pinned inputs.
ROOT=${MOZC_BAZEL_ROOT:-$WORK/bazel-root}

# bazelisk, on PATH or fetched below, resolves to the pinned Bazel and
# checks it.
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)  host=linux-amd64;  BAZELISK_SHA256=a248ae2b2b62b5baebb748f6fba7203bff1a27e5d01b55399a8406744cb539e8
                   BAZEL_SHA256=422e7a1690b76d7e615c29091d3aca28d0bd3a93fe3c93cbefb8f72d774926d5 ;;
    Darwin-arm64)  host=darwin-arm64; BAZELISK_SHA256=555d2ff912ccd336d0060bdb5251ef2f62629c6f8fbadb59005a45d5625793ec
                   BAZEL_SHA256=525cfcbf9790af7319ea78c9ff2053b8a5634013c41783440b5d82433f14d280 ;;
    *)             host=;             BAZELISK_SHA256=; BAZEL_SHA256= ;;
esac
[ -z "$BAZEL_SHA256" ] || export USE_BAZEL_VERSION=$BAZEL_VERSION BAZELISK_VERIFY_SHA256=$BAZEL_SHA256
export BAZELISK_HOME=${BAZELISK_HOME:-$WORK/bazelisk-home}

# Startup options and build flags that go with the Bazel picked: the
# bootstrapped one runs on the JDK found and must not fetch another, nor a
# Python; the machine's python3 serves Mozc's code generators instead of
# rules_python's downloaded one. (The ${x[@]+...} form is for macOS's
# bash 3.2, where an empty array trips `set -u`.)
STARTUP=()
FLAGS=()
JDK=
if [ -n "${MOZC_BAZEL:-}" ]; then
    BAZEL=$MOZC_BAZEL
elif command -v bazel >/dev/null 2>&1; then
    BAZEL=$(command -v bazel)
elif [ "$(uname -s)" = Linux ]; then
    JDK=$(find_jdk) || { echo "Bazel's bootstrap needs a JDK 21 or newer: set MOZC_JDK or JAVA_HOME, or install openjdk-21-jdk-headless" >&2; exit 1; }
    # The bootstrapped binary is kept under the cache directory, named by
    # the version and the archive's checksum, so a machine that builds
    # several entries in one go (fdroiddata's CI builds every new entry of
    # a merge request in sequence, under a three-hour cap) bootstraps once.
    # F-Droid's buildserver starts each build in a fresh VM and never finds
    # it, which is fine: the binary is self-contained and the same build
    # either way.
    CACHE=${MOZC_BAZEL_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/wmkeyboard/bazel-$BAZEL_VERSION-${BAZEL_DIST_SHA256:0:16}}
    if [ -x "$CACHE/bazel" ]; then
        echo "using the Bazel bootstrapped earlier at $CACHE"
    else
        bootstrap_bazel
        mkdir -p "$CACHE"
        cp "$BAZEL_SRC/output/bazel" "$CACHE/bazel.tmp" && mv "$CACHE/bazel.tmp" "$CACHE/bazel"
    fi
    BAZEL=$CACHE/bazel
    STARTUP+=(--server_javabase="$JDK")
    FLAGS+=(--tool_java_runtime_version=local_jdk --java_runtime_version=local_jdk
            --extra_toolchains=@rules_python//python/runtime_env_toolchains:all)
else
    [ -n "$host" ] || { echo "No pinned bazelisk for $(uname -s)-$(uname -m); set MOZC_BAZEL" >&2; exit 1; }
    BAZEL=$WORK/bazelisk
    retry curl -fsSL -o "$BAZEL" \
        "https://github.com/bazelbuild/bazelisk/releases/download/v$BAZELISK_VERSION/bazelisk-$host"
    [ "$(sha256 "$BAZEL")" = "$BAZELISK_SHA256" ] || { echo "bazelisk checksum mismatch" >&2; exit 1; }
    chmod +x "$BAZEL"
fi
# `--version` alone: the client answers it itself only when it is the
# whole command line; with startup options in front it is an unknown option.
# The bootstrap is given EMBED_LABEL so its binary says "bazel 9.0.2" like a
# release; left alone it says "bazel 9.0.2- (@non-git)", so match the front.
# stdout only: bazelisk 1.30 reports its download and signature check on stderr.
got=$("$BAZEL" --version 2>"$WORK/bazel-version.err") ||
    { echo "$BAZEL --version failed: $got $(cat "$WORK/bazel-version.err")" >&2; exit 1; }
case "$got" in
    "bazel $BAZEL_VERSION"*) echo "using $got from $BAZEL" ;;
    *) echo "$BAZEL is '$got', expected bazel $BAZEL_VERSION" >&2; exit 1 ;;
esac

SRC=${MOZC_SRC:-}
if [ -z "$SRC" ]; then
    SRC=$WORK/mozc
    retry git clone --quiet --depth 1 --branch "$MOZC_TAG" https://github.com/google/mozc "$SRC"
fi
got=$(git -C "$SRC" rev-parse HEAD)
[ "$got" = "$MOZC_COMMIT" ] || { echo "mozc is at $got, expected $MOZC_COMMIT" >&2; exit 1; }

# The bridge as a package of the Mozc workspace. Mozc's MODULE.bazel looks
# for the NDK at third_party/ndk/android-ndk-r29 and leaves the API level at
# rules_android_ndk's default, 31; the keyboard runs from Android 7 (24).
rm -rf "$SRC/src/wmkeyboard"
mkdir -p "$SRC/src/wmkeyboard" "$SRC/src/third_party/ndk"
cp "$HERE/BUILD.bazel" "$HERE/mozc_bridge.h" "$HERE/mozc_bridge.cc" "$HERE/mozc_jni.cc" \
    "$HERE/host_test.cc" "$HERE/jni.map" "$SRC/src/wmkeyboard/"
ln -sfn "$NDK" "$SRC/src/third_party/ndk/android-ndk-r29"
module=$SRC/src/MODULE.bazel
if ! grep -q 'api_level = 24' "$module"; then
    python3 - "$module" <<'EOF'
import sys
path = sys.argv[1]
text = open(path).read()
anchor = 'path = "$WORKSPACE_ROOT/third_party/ndk/android-ndk-r29",'
if anchor not in text:
    sys.exit("MODULE.bazel no longer configures the NDK where build.sh expects")
open(path, "w").write(text.replace(anchor, anchor + "\n    api_level = 24,", 1))
EOF
fi

bazel() {
    local command=$1; shift
    (cd "$SRC/src" && env ${JDK:+JAVA_HOME="$JDK"} \
        "$BAZEL" ${STARTUP[@]+"${STARTUP[@]}"} --output_user_root="$ROOT" "$command" ${FLAGS[@]+"${FLAGS[@]}"} "$@")
}

# WMKB_ABIS narrows the build to some of the three (native/build-fdroid-libs.sh).
wanted() { case " ${WMKB_ABIS:-arm64-v8a armeabi-v7a} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }
targets=()
for abi in arm64-v8a armeabi-v7a; do
    if wanted "$abi"; then targets+=("//wmkeyboard:wmmozc.$abi"); fi
done
log=$WORK/bazel.log
bazel build --config oss_android --config release_build "${targets[@]}" > "$log" 2>&1 \
    || { tail -60 "$log"; exit 1; }
for abi in arm64-v8a armeabi-v7a; do
    wanted "$abi" || continue
    lib=$(bazel cquery --config oss_android --config release_build --output=files \
        "//wmkeyboard:wmmozc.$abi" 2>/dev/null | head -1)
    mkdir -p "$OUT/$abi"
    "$BIN/llvm-strip" --strip-unneeded -o "$OUT/$abi/libwmmozc.so" "$SRC/src/$lib"
    echo "$abi libwmmozc.so: $(wc -c < "$OUT/$abi/libwmmozc.so") bytes"
done

if [ -n "$DATA_OUT" ]; then
    # macOS: the generators need std::filesystem, newer than Bazel's
    # default minimum macOS.
    case "$(uname -s)" in
        Darwin) hostcfg=(--config oss_macos --macos_minimum_os=11.0 --host_macos_minimum_os=11.0) ;;
        *) hostcfg=(--config oss_linux) ;;
    esac
    bazel build "${hostcfg[@]}" --config release_build //data_manager/oss:mozc_dataset_for_oss \
        > "$log" 2>&1 || { tail -60 "$log"; exit 1; }
    cp "$SRC/src/bazel-bin/data_manager/oss/mozc.data" "$DATA_OUT/mozc.data"
    chmod 644 "$DATA_OUT/mozc.data"
    echo "mozc.data: $(wc -c < "$DATA_OUT/mozc.data") bytes, sha256 $(sha256 "$DATA_OUT/mozc.data")"
fi
