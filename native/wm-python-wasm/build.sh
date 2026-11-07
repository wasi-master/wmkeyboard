#!/usr/bin/env bash
# Builds the web plugin editor's wasm (the interpreter and its editor tooling)
# and its wasm-bindgen glue into docs/src/store/wasm/. The output is committed,
# so building the docs site needs no Rust.
#
#   native/wm-python-wasm/build.sh
#
# Needs rustup (the toolchain and the wasm target are installed if missing) and
# wasm-bindgen-cli at the version Cargo.toml pins wasm-bindgen to. Paths on the
# build machine are remapped out of the module, so two machines with the same
# toolchain write the same bytes.
set -euo pipefail

RUST_VERSION=1.99.1
WASM_BINDGEN_VERSION=0.2.130
TARGET=wasm32-unknown-unknown

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
OUT=${WM_PYTHON_WASM_OUT:-$REPO/docs/src/store/wasm}

rustup toolchain install "$RUST_VERSION" --profile minimal --no-self-update --target "$TARGET" >/dev/null
export RUSTUP_TOOLCHAIN=$RUST_VERSION
if [ "$(wasm-bindgen --version 2>/dev/null | awk '{print $2}')" != "$WASM_BINDGEN_VERSION" ]; then
    cargo install wasm-bindgen-cli --version "$WASM_BINDGEN_VERSION" --locked
fi

CARGO_HOME=${CARGO_HOME:-$HOME/.cargo}
SYSROOT=$(rustc --print sysroot)
export CARGO_TARGET_DIR=${WM_PYTHON_WASM_TARGET_DIR:-$HERE/target}
mkdir -p "$CARGO_TARGET_DIR"
CARGO_TARGET_DIR=$(cd "$CARGO_TARGET_DIR" && pwd -P)
# RUSTFLAGS replaces .cargo/config.toml's, so the stack size is repeated here.
export RUSTFLAGS="-C link-arg=-zstack-size=8388608 --remap-path-prefix=$CARGO_HOME=/cargo --remap-path-prefix=$SYSROOT=/rustc --remap-path-prefix=$REPO=/wmkeyboard --remap-path-prefix=$CARGO_TARGET_DIR=/target"

cd "$HERE"
cargo build --release --locked --target "$TARGET"
mkdir -p "$OUT"
wasm-bindgen --target web --remove-name-section --remove-producers-section --out-dir "$OUT" --out-name wm_python "$CARGO_TARGET_DIR/$TARGET/release/wm_python_wasm.wasm"
echo "wm_python_bg.wasm: $(wc -c < "$OUT/wm_python_bg.wasm" | tr -d ' ') bytes"
