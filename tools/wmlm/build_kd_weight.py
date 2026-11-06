"""Build a per-row KD weight from a soft_mask.py mask file.

usage: build_kd_weight.py <masks.npz> <out.npz> [piece_weight=0.1] [word_weight=1.0]

`soft_mask.py build` writes `flags` (uint8): bit 1 empty, bit 2 piece, bit 4
<=3 candidates. Pass the result as `distill_weight=<out.npz>`: empty rows
get 0, piece rows get a small weight (default 0.1), word rows get 1.0. CE is
not weighted; only the KD term is.
"""
import sys
import numpy as np

from soft_mask import FLAG_EMPTY, FLAG_PIECE


def main():
    src, out = sys.argv[1:3]
    opts = {"piece_weight": 0.1, "word_weight": 1.0}
    for a in sys.argv[3:]:
        k, v = a.split("=", 1)
        if k not in opts:
            raise SystemExit(f"unknown option {k}")
        opts[k] = float(v)
    z = np.load(src)
    if "flags" not in z:
        raise SystemExit(f"{src}: missing 'flags'")
    flags = z["flags"].astype(np.uint8)
    empty = (flags & FLAG_EMPTY) != 0
    piece = (flags & FLAG_PIECE) != 0
    w = np.full(flags.shape, opts["word_weight"], dtype=np.float32)
    w[piece] = opts["piece_weight"]
    w[empty] = 0.0
    np.savez_compressed(out, weight=w)
    print(
        f"rows {len(w)} empty {int(empty.sum())} piece {int(piece.sum())} "
        f"word {int((~empty & ~piece).sum())} "
        f"piece_weight {opts['piece_weight']} mean {float(w.mean()):.4f} -> {out}"
    )


if __name__ == "__main__":
    main()
