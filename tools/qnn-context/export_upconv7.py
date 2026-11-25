#!/usr/bin/env python3
"""Writes waifu2x's UpConv7 networks (the upscaler's, MIT) as fixed-size ONNX
graphs for QAIRT to compile into Hexagon NPU context binaries (build.sh).

    python3 tools/qnn-context/export_upconv7.py <out dir> [--check <dir of .mnn>]

Needs: numpy, onnx; onnxruntime for the checks; MNN for --check.

The weights are nagadomi/waifu2x's own `upconv_7` noise1 x2 models, pinned to
the commit and SHA-256 the data repository's export_upscalers_mnn.py uses, so
the NPU draws with exactly the networks the CPU path (MNN) draws with. The
layers are the same seven: six unpadded 3x3 convolutions with leaky ReLU 0.1,
then a 4x4 transposed convolution, stride 2, padding 3.

Unlike the MNN export, the graph is fixed at one tile, since an NPU graph has
one input shape: [1, 142, 142, 3] RGB from 0 to 1 in, channels last, and
[1, 256, 256, 3] out. 142 is the app's 128-pixel tile plus the 7 pixels of
context the network eats on each side (UpscaleModels.TILE and CONTEXT). The
graph transposes to channels first and back itself, which the QNN converter
folds away, so the context binary's input and output are channels last, the
layout the HTP keeps tensors in.

Checks, when onnxruntime is there: the graph run in float16 throughout against
float32 on random tiles and on a smooth gradient (the HTP runs a float graph in
float16), and with --check against the app's own .mnn files.
"""
import argparse
import hashlib
import json
import os
import sys
import urllib.request

import numpy as np

COMMIT = "cc385f97a9debfe611316aabfd5d8bb30ba2dbeb"
SOURCES = {
    "art": ("models/upconv_7/art/noise1_scale2.0x_model.json",
            "6c0688159ab0023619d1b0e472fead370619df1d36251f14f299edc43b30d1e3"),
    "photo": ("models/upconv_7/photo/noise1_scale2.0x_model.json",
              "adbce4a532d42bc96f17fea8a5aec9245c509ff44058263938ea232244dff0bc"),
}
TILE = 128
CONTEXT = 7
SIDE = TILE + 2 * CONTEXT
CHANNELS = [3, 16, 32, 64, 128, 128, 256]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fetch(kind, work):
    path, want = SOURCES[kind]
    target = os.path.join(work, f"upconv7-{kind}.json")
    if not (os.path.isfile(target) and sha256(target) == want):
        urllib.request.urlretrieve(f"https://raw.githubusercontent.com/nagadomi/waifu2x/{COMMIT}/{path}", target)
    got = sha256(target)
    if got != want:
        sys.exit(f"{path}: sha256 {got}, expected {want}")
    return target


def weights(json_path):
    with open(json_path) as f:
        layers = json.load(f)
    if len(layers) != 7:
        sys.exit(f"{json_path}: {len(layers)} layers, expected 7")
    shapes = [(CHANNELS[i + 1], CHANNELS[i], 3, 3) for i in range(6)] + [(256, 3, 4, 4)]
    out = []
    for layer, shape in zip(layers, shapes):
        w = np.array(layer["weight"], np.float32)
        b = np.array(layer["bias"], np.float32).reshape(-1)
        if w.size != int(np.prod(shape)) or b.size != (shape[1] if len(out) == 6 else shape[0]):
            sys.exit(f"{json_path}: layer shapes differ from upconv_7")
        out.append((w.reshape(shape), b))
    return out


def graph(layers, dtype=np.float32):
    from onnx import TensorProto, helper, numpy_helper
    elem = TensorProto.FLOAT16 if dtype == np.float16 else TensorProto.FLOAT
    out_side = 2 * TILE
    nodes = [helper.make_node("Transpose", ["image"], ["x0"], perm=[0, 3, 1, 2])]
    inits = []
    x = "x0"
    for i, (w, b) in enumerate(layers[:6]):
        inits += [numpy_helper.from_array(w.astype(dtype), f"w{i}"), numpy_helper.from_array(b.astype(dtype), f"b{i}")]
        nodes.append(helper.make_node("Conv", [x, f"w{i}", f"b{i}"], [f"c{i}"], kernel_shape=[3, 3]))
        nodes.append(helper.make_node("LeakyRelu", [f"c{i}"], [f"x{i + 1}"], alpha=0.1))
        x = f"x{i + 1}"
    w, b = layers[6]
    inits += [numpy_helper.from_array(w.astype(dtype), "w6"), numpy_helper.from_array(b.astype(dtype), "b6")]
    nodes.append(helper.make_node("ConvTranspose", [x, "w6", "b6"], ["y"], kernel_shape=[4, 4], strides=[2, 2], pads=[3, 3, 3, 3]))
    nodes.append(helper.make_node("Transpose", ["y"], ["upscaled"], perm=[0, 2, 3, 1]))
    g = helper.make_graph(
        nodes, "upconv7",
        [helper.make_tensor_value_info("image", elem, [1, SIDE, SIDE, 3])],
        [helper.make_tensor_value_info("upscaled", elem, [1, out_side, out_side, 3])],
        inits,
    )
    model = helper.make_model(g, opset_imports=[helper.make_opsetid("", 17)], producer_name="wmkeyboard")
    model.ir_version = 8
    return model


def run(model, x):
    import onnxruntime as ort
    s = ort.InferenceSession(model.SerializeToString(), providers=["CPUExecutionProvider"])
    return s.run(None, {"image": x.astype(np.float16 if "16" in str(s.get_inputs()[0].type) else np.float32)})[0].astype(np.float32)


def check_fp16(layers, name):
    rs = np.random.RandomState(0)
    ramp = np.linspace(0, 1, SIDE, dtype=np.float32)
    tiles = [rs.rand(1, SIDE, SIDE, 3).astype(np.float32),
             np.stack([np.tile(ramp, (SIDE, 1)), np.tile(ramp[:, None], (1, SIDE)), np.full((SIDE, SIDE), 0.5, np.float32)], -1)[None]]
    f32, f16 = graph(layers), graph(layers, np.float16)
    worst = max(float(np.abs(np.clip(run(f16, t), 0, 1) - np.clip(run(f32, t), 0, 1)).max()) for t in tiles)
    print(f"{name}: float16 against float32, largest difference {worst * 255:.2f} of 255")
    return worst


def check_mnn(model, mnn_path, name):
    import MNN
    F = MNN.expr
    mod = MNN.nn.load_module_from_file(mnn_path, ["image"], ["upscaled"])
    x = np.random.RandomState(1).rand(1, SIDE, SIDE, 3).astype(np.float32)
    v = F.const(x.transpose(0, 3, 1, 2).copy(), [1, 3, SIDE, SIDE], F.NCHW)
    y = mod.forward(v)
    ref = np.array(F.convert(y, F.NCHW).read(), np.float32).transpose(0, 2, 3, 1)
    worst = float(np.abs(run(model, x) - ref).max())
    print(f"{name}: against the app's {os.path.basename(mnn_path)}, largest difference {worst * 255:.3f} of 255")
    return worst


def main():
    p = argparse.ArgumentParser()
    p.add_argument("out")
    p.add_argument("--check", help="directory holding sr-waifu2x-upconv7-{art,photo}.mnn")
    a = p.parse_args()
    os.makedirs(a.out, exist_ok=True)
    import onnx
    for kind in SOURCES:
        layers = weights(fetch(kind, a.out))
        model = graph(layers)
        onnx.checker.check_model(model)
        target = os.path.join(a.out, f"sr-waifu2x-upconv7-{kind}-{SIDE}.onnx")
        onnx.save(model, target)
        try:
            import onnxruntime  # noqa: F401
        except ImportError:
            print("onnxruntime missing: float16 check skipped")
        else:
            # Half of one 8-bit step: anything more would show as banding.
            if check_fp16(layers, kind) > 0.5 / 255:
                sys.exit(f"{kind}: float16 drifts from float32; compile this graph without fp16_relaxed_precision")
            if a.check:
                if check_mnn(model, os.path.join(a.check, f"sr-waifu2x-upconv7-{kind}.mnn"), kind) > 1 / 255:
                    sys.exit(f"{kind}: the ONNX graph does not draw what the app's MNN network draws")
        print(f"{os.path.getsize(target):>10}  {sha256(target)}  {os.path.basename(target)}")


if __name__ == "__main__":
    main()
