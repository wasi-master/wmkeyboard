"""Train PunctEncoder on prep.py's arrays.

usage: train.py <data dir> <out dir> [steps=20000 batch=512 lr=1e-3 e=64 d=192 layers=4 heads=4 ff=512
                                     seed=0 eval_every=1000 label_smoothing=0.05 warm=500
                                     drop_tail=0.1 device=auto resume=out/last.pt]

Examples are packed one per row, padded to the longest in the batch, with a
mask so padding is never attended to. `drop_tail` is the share of rows whose
last one or two words are taken off at training time, so the model also has
an opinion while the last word of a sentence is still being typed (the
keyboard asks on every keystroke, not only at the space).

Saves out/last.pt and out/best.pt, best by held-out accuracy over the three
end marks (the comma class is reported but not what the key is judged on),
with the config inside, and prints per-class precision and recall and
per-language accuracy at each evaluation.
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from punct_common import LABELS, SPECIALS
from punct_model import PunctEncoder

PAD = SPECIALS.index("<pad>")


def opts(argv):
    o = dict(steps=20000, batch=512, lr=1e-3, e=64, d=192, layers=4, heads=4, ff=512, seed=0, eval_every=1000,
             label_smoothing=0.05, warm=500, drop_tail=0.1, device="auto", resume="", weight_decay=0.1)
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v)
    return o


class Data:
    def __init__(self, path):
        z = np.load(path)
        self.tokens = torch.from_numpy(z["tokens"].astype(np.int64))
        self.starts = z["starts"]
        self.labels = torch.from_numpy(z["labels"].astype(np.int64))
        self.langs = z["langs"]
        self.n = len(self.starts) - 1

    def batch(self, idx, dev, gen=None, drop_tail=0.0):
        rows = [self.tokens[self.starts[i]:self.starts[i + 1]] for i in idx]
        if drop_tail > 0:
            dropped = []
            for r in rows:
                if len(r) > 3 and torch.rand((), generator=gen).item() < drop_tail:
                    cut = 1 if torch.rand((), generator=gen).item() < 0.7 else 2
                    r = r[: max(2, len(r) - cut)]
                dropped.append(r)
            rows = dropped
        T = max(len(r) for r in rows)
        out = torch.full((len(rows), T), PAD, dtype=torch.long)
        mask = torch.zeros((len(rows), T), dtype=torch.bool)
        for i, r in enumerate(rows):
            out[i, : len(r)] = r
            mask[i, : len(r)] = True
        return out.to(dev), mask.to(dev), self.labels[idx].to(dev)


def evaluate(model, data, dev, lang_names=None, limit=40000):
    model.eval()
    n = min(data.n, limit)
    preds = np.zeros(n, dtype=np.int64)
    probs = np.zeros((n, len(LABELS)), dtype=np.float32)
    with torch.no_grad():
        for a in range(0, n, 512):
            idx = list(range(a, min(n, a + 512)))
            x, m, _ = data.batch(idx, dev)
            logits = model(x, m)
            p = torch.softmax(logits.float(), dim=-1)
            probs[a:a + len(idx)] = p.cpu().numpy()
            preds[a:a + len(idx)] = p.argmax(-1).cpu().numpy()
    model.train()
    labels = data.labels[:n].numpy()
    report = {}
    acc = float((preds == labels).mean())
    # The key only ever shows . ? !: accuracy over those rows with the comma
    # class folded away (renormalised), which is what a user sees.
    end = labels < 3
    p3 = probs[:, :3].argmax(-1)
    acc3 = float((p3[end] == labels[end]).mean()) if end.any() else 0.0
    report["acc"] = acc
    report["acc3"] = acc3
    for k, name in enumerate(LABELS):
        tp = int(((preds == k) & (labels == k)).sum())
        fp = int(((preds == k) & (labels != k)).sum())
        fn = int(((preds != k) & (labels == k)).sum())
        report[f"p{name}"] = tp / max(1, tp + fp)
        report[f"r{name}"] = tp / max(1, tp + fn)
    by_lang = {}
    langs = data.langs[:n]
    for l in np.unique(langs):
        sel = (langs == l) & end
        if sel.sum() >= 200:
            name = lang_names[l] if lang_names and l < len(lang_names) else str(l)
            by_lang[name] = float((p3[sel] == labels[sel]).mean())
    report["by_lang"] = by_lang
    return report


def _pick_device(name):
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    return torch.device(name)


def main():
    data_dir, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    dev = _pick_device(o["device"])
    print("device", dev, flush=True)
    torch.manual_seed(o["seed"])
    gen = torch.Generator().manual_seed(o["seed"])
    vocab = sum(1 for _ in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8"))
    lang_names = [l.strip() for l in open(os.path.join(data_dir, "langs.txt"), encoding="utf-8")]
    train = Data(os.path.join(data_dir, "train.npz"))
    held = Data(os.path.join(data_dir, "heldout.npz"))
    ctx = int(max(np.diff(train.starts).max(), np.diff(held.starts).max()))
    model = PunctEncoder(vocab, e=o["e"], d=o["d"], layers=o["layers"], heads=o["heads"], ff=o["ff"], ctx=ctx, classes=len(LABELS)).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "train", train.n, "heldout", held.n, "vocab", vocab, "ctx", ctx, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=o["weight_decay"], betas=(0.9, 0.95))
    warm = o["warm"]
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run, best, start = time.time(), 0.0, -1.0, 0
    if o["resume"]:
        saved = torch.load(o["resume"], map_location=dev)
        model.load_state_dict(saved["model"])
        start, best = saved["step"], saved.get("acc3", -1.0)
        for _ in range(start):
            sched.step()
        print("resumed from", o["resume"], "at step", start, flush=True)
    use_amp = dev.type == "cuda"
    for step in range(start + 1, o["steps"] + 1):
        idx = torch.randint(0, train.n, (o["batch"],), generator=gen).tolist()
        x, m, y = train.batch(idx, dev, gen, o["drop_tail"])
        with torch.autocast(device_type="cuda", dtype=torch.bfloat16, enabled=use_amp):
            logits = model(x, m)
        loss = F.cross_entropy(logits.float(), y, label_smoothing=o["label_smoothing"])
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > start + 1 else loss.item()
        if step % 100 == 0:
            print(f"step {step} loss {run:.4f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % o["eval_every"] == 0 or step == o["steps"]:
            r = evaluate(model, held, dev, lang_names)
            cls = " ".join(f"{k}:{r['p' + k]:.3f}/{r['r' + k]:.3f}" for k in LABELS)
            langs = " ".join(f"{l}:{a:.3f}" for l, a in sorted(r["by_lang"].items()))
            print(f"eval step {step} acc {r['acc']:.4f} acc3 {r['acc3']:.4f} p/r {cls}", flush=True)
            print(f"  by language: {langs}", flush=True)
            state = {"model": model.state_dict(), "config": model.config(), "opts": o, "step": step,
                     "acc": r["acc"], "acc3": r["acc3"], "report": r}
            torch.save(state, os.path.join(out, "last.pt"))
            if r["acc3"] > best:
                best = r["acc3"]
                torch.save(state, os.path.join(out, "best.pt"))


if __name__ == "__main__":
    main()
