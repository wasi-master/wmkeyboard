"""Average checkpoints saved by train.py.

usage: average_checkpoints.py <out.pt> <ckpt.pt> [<ckpt.pt> ...]

Floating tensors are averaged; integer/bool tensors and metadata come from
the first checkpoint. The output keeps the `{"model", "config", "opts",
"step", "heldout"}` shape that export.py expects. The caller must evaluate
the soup on heldout before exporting; averaging is not a selection rule.
"""
import os
import sys
import torch


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: average_checkpoints.py <out.pt> <ckpt.pt> [...]")
    out = sys.argv[1]
    paths = sys.argv[2:]
    states = [torch.load(p, map_location="cpu", weights_only=True) for p in paths]
    base = states[0]
    keys = list(base["model"].keys())
    for s in states[1:]:
        if list(s["model"].keys()) != keys:
            raise SystemExit("checkpoint parameter names/order differ")
    avg = {}
    for k in keys:
        tensors = [s["model"][k] for s in states]
        if all(t.dtype.is_floating_point for t in tensors):
            acc = tensors[0].float().clone()
            for t in tensors[1:]:
                acc += t.float()
            avg[k] = (acc / len(tensors)).to(tensors[0].dtype)
        else:
            avg[k] = tensors[0].clone()
    cfg = base.get("config")
    for s in states[1:]:
        if s.get("config") != cfg:
            raise SystemExit("checkpoint configs differ")
    soup = {
        "model": avg,
        "config": cfg,
        "opts": base.get("opts", {}),
        "step": base.get("step"),
        "heldout": base.get("heldout"),
        "averaged_from": [os.path.basename(p) for p in paths],
    }
    torch.save(soup, out)
    print(f"averaged {len(paths)} checkpoints -> {out}")


if __name__ == "__main__":
    main()
