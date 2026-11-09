"""PyTorch's answer for one sentence, to compare with
`cargo run --release --example punct_probe -- model.wmpt "how are you"`.

usage: check.py <checkpoint.pt> <data dir> [--lang=xx] "<sentence so far>" ...

`--lang` is the keyboard's language id for a model with language tokens;
an id the model lacks (or none) reads as `<lang:?>`.
"""
import os
import sys

import torch

from punct_common import BOS, LABELS, LANG_UNKNOWN, UNK, Pieces, START, CONT, lang_token, tokens_of
from punct_model import PunctEncoder


def main():
    ckpt, data_dir = sys.argv[1:3]
    state = torch.load(ckpt, map_location="cpu")
    cfg = state["config"]
    vocab = [line.rstrip("\n") for line in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8")]
    ids = {w: i for i, w in enumerate(vocab)}
    starts = {w[len(START):] for w in vocab if w.startswith(START)}
    conts = {w[len(CONT):] for w in vocab if w.startswith(CONT)}
    pieces = Pieces(starts, conts)
    model = PunctEncoder(cfg["vocab"], e=cfg["e"], d=cfg["d"], layers=cfg["layers"], heads=cfg["heads"], ff=cfg["ff"], ctx=cfg["ctx"], classes=cfg["classes"])
    model.load_state_dict(state["model"])
    model.eval()
    lang = None
    sentences = []
    for a in sys.argv[3:]:
        if a.startswith("--lang="):
            lang = a.split("=", 1)[1]
        else:
            sentences.append(a)
    for sentence in sentences:
        seq = [ids[BOS]]
        if LANG_UNKNOWN in ids:
            seq.append(ids.get(lang_token(lang), ids[LANG_UNKNOWN]) if lang else ids[LANG_UNKNOWN])
        for w in tokens_of(sentence):
            if w in ids:
                seq.append(ids[w])
            else:
                enc = pieces.encode(w)
                seq += [ids[p] for p in enc] if enc else [ids[UNK]]
        ctx = cfg["ctx"]
        if len(seq) > ctx:
            seq = seq[: ctx // 3] + seq[-(ctx - ctx // 3):]
        x = torch.tensor([seq])
        with torch.no_grad():
            p = torch.softmax(model(x, torch.ones_like(x, dtype=torch.bool)), -1)[0]
        print(repr(sentence), seq, " ".join(f"{l} {v:.4f}" for l, v in zip(LABELS, p.tolist())))


if __name__ == "__main__":
    main()
