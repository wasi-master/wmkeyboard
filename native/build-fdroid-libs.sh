#!/usr/bin/env bash
# Builds, from source, every native library F-Droid's build ships, into the
# jniLibs directories the app's modules package them from. Which libraries,
# which script builds each and where it writes are native/gates.json: every
# library there with an `fdroidBuild` script (the text engines' first, from
# `base`, then each gate's in table order), each script run once.
#
#   python3 native/gates.py fdroid-scripts     lists them in that order
#
# The two prebuilt exceptions, Tesseract (libwmtess.so) and Whistle
# (libwmwhistle.so), have no script there: they come from their own workflows.
# Voikko's script writes into native/voikko-jni/jniLibs, which no APK
# packages: it is pinned for download only.
#
# libwmsherpa and libwmxlit are linked against a stub of ONNX Runtime built from source
# headers, never a prebuilt binary; the runtime itself is Microsoft's, fetched
# on the phone from Maven Central after the F-Droid download opt-in.
#
# F-Droid's recipe deletes the committed copies of these libraries before its
# source scan and runs this script in their place (fdroid/README.md), and the
# release workflow's `fdroid` job builds the reference APK the same way. Each
# script maps the build machine's paths out of its output, so the two builds
# come out byte for byte the same.
#
#   ANDROID_NDK=/path/to/ndk native/build-fdroid-libs.sh
#
# WMKB_ABIS="arm64-v8a" builds one ABI's libraries instead of all three
# (any of arm64-v8a, armeabi-v7a and x86_64, space-separated). F-Droid's
# recipe has one build entry per ABI and each passes its own, so ExecuTorch
# is compiled once per entry rather than three times. Every ABI is built on
# its own either way, so its libraries come out the same bytes.
#
# The text engines are the phonetic and fixed-layout engines every edition
# types Bengali, Hindi, Urdu and the Indic languages through (native/wm-engine).
#
# Needs git, curl, CMake, Ninja, a host C++ compiler and Python 3 with PyYAML
# and typing_extensions. Rust comes from rustup, which is installed at a
# pinned version when the machine has none.
set -euo pipefail

RUSTUP_VERSION=1.29.2
RUSTUP_SHA256=5be191e8c70a5007074254cb74d78dbfdd25cd5a61c27de7b8e5410122ec24f4

HERE=$(cd "$(dirname "$0")" && pwd)

if ! command -v rustup >/dev/null 2>&1; then
    export PATH=${CARGO_HOME:-$HOME/.cargo}/bin:$PATH
fi
if ! command -v rustup >/dev/null 2>&1; then
    case "$(uname -s)-$(uname -m)" in
        Linux-x86_64) host=x86_64-unknown-linux-gnu ;;
        *) echo "Install rustup first: https://rustup.rs" >&2; exit 1 ;;
    esac
    init=$(mktemp -d)/rustup-init
    curl -fsSL --proto '=https' --tlsv1.2 -o "$init" \
        "https://static.rust-lang.org/rustup/archive/$RUSTUP_VERSION/$host/rustup-init"
    echo "$RUSTUP_SHA256  $init" | sha256sum -c -
    chmod +x "$init"
    "$init" -y --no-modify-path --profile minimal --default-toolchain none
    rm -f "$init"
fi

# No Qualcomm code in F-Droid's libraries: ExecuTorch's QNN variant is
# compiled against Qualcomm's proprietary QNN headers.
export WMKB_ET_QNN=0

scripts=$(python3 "$HERE/gates.py" fdroid-scripts)
[ -n "$scripts" ] || { echo "native/gates.py listed no build scripts" >&2; exit 1; }
for script in $scripts; do
    # Called as before the table, $HERE/<dir>/build.sh: each script maps its
    # own directory out of its output, so its path must not change.
    "$HERE/${script#native/}"
done
