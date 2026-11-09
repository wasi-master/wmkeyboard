"""A trained PunctEncoder as a `.wmpt` file for `wm-engine/src/punct.rs`.

usage: export.py <checkpoint.pt> <data dir> <out.wmpt> [--name="Smart punctuation"]

The data dir supplies vocab.txt and langs.txt. Layout, little-endian, the
same container as `.wmlm`:
  "WMPT"  u32 version (1)  u32 header_len  header JSON (utf-8, padded to 4)
  vocab: u32 count, then per token u16 length + utf-8 bytes, padded to 4
  tensors, each padded to 4, at the offsets the header gives (relative to
  the first tensor byte)

Weights are int8 with one f32 scale per output row (`i8`: data then scales)
for the embedding, its projection, the attention and feed-forward matrices;
LayerNorm weights and biases, the position table, every bias and the
classifier head are f32. The header names the classes in order
(`labels`) and the languages the model was trained on (`langs`).
"""
import hashlib
import json
import os
import struct
import sys

import numpy as np
import torch

from punct_common import LABELS


def quantize_rows(w):
    w = w.astype(np.float32)
    scale = np.abs(w).max(axis=1) / 127.0
    scale[scale == 0] = 1.0
    q = np.round(w / scale[:, None]).clip(-127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def main():
    ckpt, data_dir, out = sys.argv[1:4]
    name = "Smart punctuation"
    for a in sys.argv[4:]:
        if a.startswith("--name="):
            name = a.split("=", 1)[1]
    state = torch.load(ckpt, map_location="cpu")
    cfg = state["config"]
    sd = {k: v.float().numpy() for k, v in state["model"].items()}
    vocab = [line.rstrip("\n") for line in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8")]
    langs = [line.strip() for line in open(os.path.join(data_dir, "langs.txt"), encoding="utf-8") if line.strip()]
    assert len(vocab) == cfg["vocab"], (len(vocab), cfg["vocab"])
    assert cfg["classes"] == len(LABELS)

    tensors, blobs = [], []
    offset = 0

    def pad4(n):
        return (n + 3) & ~3

    def add(tname, arr, dtype):
        nonlocal offset
        if dtype == "i8":
            q, s = quantize_rows(arr.reshape(arr.shape[0], -1))
            data = q.tobytes() + s.tobytes()
        else:
            data = arr.astype(np.float32).tobytes()
        tensors.append({"name": tname, "shape": list(arr.shape), "dtype": dtype, "offset": offset, "bytes": len(data)})
        blobs.append(data + b"\0" * (pad4(len(data)) - len(data)))
        offset += pad4(len(data))

    add("emb", sd["emb.weight"], "i8")
    add("proj.w", sd["proj.weight"], "i8")
    add("proj.b", sd["proj.bias"], "f32")
    add("pos", sd["pos"], "f32")
    for i in range(cfg["layers"]):
        p = f"blocks.{i}."
        add(f"l{i}.ln1.w", sd[p + "ln1.weight"], "f32")
        add(f"l{i}.ln1.b", sd[p + "ln1.bias"], "f32")
        add(f"l{i}.qkv.w", sd[p + "qkv.weight"], "i8")
        add(f"l{i}.qkv.b", sd[p + "qkv.bias"], "f32")
        add(f"l{i}.o.w", sd[p + "o.weight"], "i8")
        add(f"l{i}.o.b", sd[p + "o.bias"], "f32")
        add(f"l{i}.ln2.w", sd[p + "ln2.weight"], "f32")
        add(f"l{i}.ln2.b", sd[p + "ln2.bias"], "f32")
        add(f"l{i}.w1.w", sd[p + "w1.weight"], "i8")
        add(f"l{i}.w1.b", sd[p + "w1.bias"], "f32")
        add(f"l{i}.w2.w", sd[p + "w2.weight"], "i8")
        add(f"l{i}.w2.b", sd[p + "w2.bias"], "f32")
    add("ln.w", sd["ln.weight"], "f32")
    add("ln.b", sd["ln.bias"], "f32")
    add("head.w", sd["head.weight"], "f32")
    add("head.b", sd["head.bias"], "f32")

    header = dict(cfg)
    header.update(name=name, langs=langs, labels=LABELS, tensors=tensors,
                  acc3=state.get("acc3"), acc=state.get("acc"), step=state.get("step"))
    hjson = json.dumps(header, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    hjson += b"\0" * (pad4(len(hjson)) - len(hjson))
    vocab_blob = struct.pack("<I", len(vocab))
    for w in vocab:
        b = w.encode("utf-8")
        vocab_blob += struct.pack("<H", len(b)) + b
    vocab_blob += b"\0" * (pad4(len(vocab_blob)) - len(vocab_blob))
    with open(out, "wb") as f:
        f.write(b"WMPT")
        f.write(struct.pack("<II", 1, len(hjson)))
        f.write(hjson)
        f.write(vocab_blob)
        for b in blobs:
            f.write(b)
    total = 12 + len(hjson) + len(vocab_blob) + offset
    digest = hashlib.sha256()
    with open(out, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    with open(out + ".sha256", "w", encoding="utf-8", newline="\n") as f:
        f.write(f"{digest.hexdigest()}  {os.path.basename(out)}\n")
    print(f"{out}: {total} bytes, {len(tensors)} tensors, vocab {len(vocab)}, langs {len(langs)}, sha256 {digest.hexdigest()}")


if __name__ == "__main__":
    main()
