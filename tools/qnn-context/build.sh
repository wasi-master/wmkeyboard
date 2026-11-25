#!/usr/bin/env bash
# Compiles the upscaler's waifu2x UpConv7 networks (art and photo) into QNN
# context binaries for the Hexagon NPU of every Snapdragon the app's NPU
# runtime covers (NpuSupport.HTP_ARCH), for UpscaleModels.NPU_BUILDS.
#
#   QAIRT_SDK_ROOT=/path/to/qairt/2.51.0.260929 tools/qnn-context/build.sh <out dir>
#
# Needs Linux x86-64 (QAIRT's converter and HTP compiler have no macOS or
# Windows-on-ARM build), Python 3.10 with QAIRT's converter dependencies plus
# numpy, onnx and onnxruntime. The QAIRT SDK is Qualcomm's, under Qualcomm's
# "AI Stack" licence, which whoever downloads and runs it accepts; it is not
# fetched here. .github/workflows/qnn-context.yml does all of this on a GitHub
# runner.
#
# QAIRT must be the release the app's runtime is (gradle/libs.versions.toml
# qnnRuntime, 2.51): a context binary needs a runtime at least as new as the
# compiler that made it.
#
# Out: one .bin per network and chip, and pins.txt with their sizes and
# SHA-256, the values UpscaleModels.NPU_BUILDS takes. Upload the .bin files to
# the data repository's `models` release as `<sha256>-<name>`.
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SDK=${QAIRT_SDK_ROOT:?set QAIRT_SDK_ROOT to a QAIRT SDK, e.g. .../qairt/2.51.0.260929}
OUT=${1:?usage: build.sh <out dir>}
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

LIB=$SDK/lib/x86_64-linux-clang
BIN=$SDK/bin/x86_64-linux-clang
[ -f "$LIB/libQnnHtp.so" ] && [ -x "$BIN/qnn-context-binary-generator" ] || {
    echo "no x86_64-linux-clang QNN tools in $SDK" >&2; exit 1; }
export LD_LIBRARY_PATH=$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
export PYTHONPATH=$SDK/lib/python${PYTHONPATH:+:$PYTHONPATH}
export PATH=$BIN:$PATH

# Chip, QNN_SOC_MODEL_* (QnnTypes.h), Hexagon generation; VTCM is 8 MB on all four.
CHIPS="SM8550:43:v73 SM8650:57:v75 SM8750:69:v79 SM8850:87:v81"
VTCM_MB=8

python3 "$HERE/export_upconv7.py" "$WORK"

: > "$OUT/pins.txt"
for kind in art photo; do
    onnx=$WORK/sr-waifu2x-upconv7-$kind-142.onnx
    # The graph takes the DLC's name, and the HTP config names the graph.
    graph=upconv7_$kind
    dlc=$WORK/$graph.dlc
    # Float16 throughout, inputs and outputs included: the HTP runs float
    # graphs in float16, and export_upconv7.py has checked these two hold
    # within half of one 8-bit step of float32 there.
    qairt-converter --input_network "$onnx" --output_path "$dlc" --float_bitwidth 16 > "$WORK/convert-$kind.log" 2>&1 || {
        tail -40 "$WORK/convert-$kind.log"; exit 1; }
    for chip in $CHIPS; do
        IFS=: read -r soc model arch <<< "$chip"
        name=sr-waifu2x-upconv7-$kind-$(echo "$soc" | tr '[:upper:]' '[:lower:]')
        cat > "$WORK/htp-$soc.json" <<EOF
{
  "graphs": [{ "graph_names": ["$graph"], "vtcm_mb": $VTCM_MB, "O": 3 }],
  "devices": [{
    "device_id": 0, "soc_model": $model, "dsp_arch": "$arch",
    "cores": [{ "core_id": 0, "perf_profile": "burst", "rpc_control_latency": 100 }]
  }]
}
EOF
        cat > "$WORK/ext-$soc.json" <<EOF
{ "backend_extensions": { "shared_library_path": "$LIB/libQnnHtpNetRunExtensions.so", "config_file_path": "$WORK/htp-$soc.json" } }
EOF
        qnn-context-binary-generator \
            --backend "$LIB/libQnnHtp.so" \
            --model "$LIB/libQnnModelDlc.so" \
            --dlc_path "$dlc" \
            --config_file "$WORK/ext-$soc.json" \
            --binary_file "$name" \
            --output_dir "$OUT" > "$WORK/gen-$name.log" 2>&1 || { tail -40 "$WORK/gen-$name.log"; exit 1; }
        [ -f "$OUT/$name.bin" ] || { echo "no $name.bin"; tail -20 "$WORK/gen-$name.log"; exit 1; }
        # What the app will read: graph name, inputs and outputs.
        qnn-context-binary-utility --context_binary "$OUT/$name.bin" --json_file "$OUT/$name.json" > /dev/null
        python3 - "$OUT/$name.json" "$graph" <<'EOF'
import json, sys
info = json.load(open(sys.argv[1]))
text = json.dumps(info)
if sys.argv[2] not in text:
    sys.exit(f"{sys.argv[1]}: graph {sys.argv[2]} not in the binary; the HTP config named the wrong graph")
def tensors(o, key):
    for k, v in (o.items() if isinstance(o, dict) else enumerate(o)):
        if k == key and isinstance(v, list):
            yield from v
        elif isinstance(v, (dict, list)):
            yield from tensors(v, key)
for key in ("graphInputs", "graphOutputs"):
    for t in tensors(info, key):
        t = t.get("info", t)
        print(f"  {key[5:-1].lower():6s} {t.get('name')} {t.get('dimensions')} type {t.get('dataType')}")
EOF
        bytes=$(wc -c < "$OUT/$name.bin" | tr -d ' ')
        sum=$(sha256sum "$OUT/$name.bin" | cut -d' ' -f1)
        echo "$soc $kind $bytes $sum $name.bin" | tee -a "$OUT/pins.txt"
    done
done
echo
echo "Kotlin, for UpscaleModels.NPU_BUILDS:"
while read -r soc kind bytes sum file; do
    echo "    npu(UpscaleKind.$(echo "$kind" | tr '[:lower:]' '[:upper:]'), \"$soc\", ${bytes}L, \"$sum\"),"
done < "$OUT/pins.txt"
