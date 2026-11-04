"""Train WordLm on prep.py's arrays.

usage: train.py <data dir> <out dir> [steps=20000 batch=256 lr=1e-3 d=192 layers=4 heads=4 ff=512
                                     seed=0 eval_every=1000 drop_prefix=0.3 ctx=32
                                     distill=soft.npz distill_alpha=0.5 distill_share=0.5
                                     distill_mask=keep.kd.npz distill_weight=masks.npz|1.0
                                     distill_align=align.npz
                                     resume=out/last.pt]

Sentences are packed one per row, padded to the longest in the batch; the
loss ignores padding. `drop_prefix` is the share of rows whose register and
field tokens are replaced by the neutral ones, so the model also serves a
caller that does not know them. Saves out/last.pt and out/best.pt (lowest
held-out loss) with the config inside.

`distill=` points at distill.py's soft targets: `distill_share` of the
batches are drawn from the sentences the teacher has read, and on those the
loss is `(1 - alpha) * cross-entropy + alpha * KL(teacher top-k || student)`
per word position; rows the teacher left empty keep the plain target.
`resume=` continues from a saved checkpoint's weights and step (the
optimiser's moments start afresh; the schedule picks up where it was).

`distill_mask=<file>.keep.kd.npz` adds a per-row KD gate (from soft_mask.py):
`kd` uint8[rows]=1 keeps the position in the KD term; the file may also have
`ce` uint8[rows] which gates the CE term for teacher batches (positions set
to 0 have CE's `tgt` rewritten to PAD on those rows only).
`soft_mask.py build --weight=cand` writes the `distill_weight=<file>` array.
"""
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn.functional as F

from common import FIELDS, REGISTERS, SPECIALS
from model import WordLm

PAD = SPECIALS.index("<pad>")
NEUTRAL_REG = SPECIALS.index(REGISTERS[0])
OTHER_FIELD = SPECIALS.index(FIELDS[0])


def opts(argv):
    o = dict(steps=20000, batch=256, lr=1e-3, d=192, layers=4, heads=4, ff=512, seed=0, eval_every=1000, drop_prefix=0.3, ctx=32,
             distill="", distill_alpha=0.5, distill_share=0.5, resume="",
             distill_mask="", distill_weight="",
             device="auto", distill_align="")
    for a in argv:
        k, v = a.split("=", 1)
        o[k] = type(o[k])(v)
    return o


class Soft:
    """distill.py's teacher targets, looked up by training-sentence index.

    When `align_path` is passed we load the `.align.npz` produced by
    `soft_mask.py build --emit-align`; that file carries `pos` (uint8[rows])
    giving the word-aware logits position for each soft row. `Soft.batch`
    then scatters rows into `[B, T-1, k]` at `pos[k]` instead of `3+k`.
    """

    def __init__(self, path, align_path=None):
        z = np.load(path)
        self.sentences = z["sentences"]
        self.starts = z["starts"]
        self.ids = z["ids"].astype(np.int64)
        self.probs = z["probs"].astype(np.float32)
        self.at = {int(s): i for i, s in enumerate(self.sentences)}
        self.k = self.ids.shape[1]
        self.align = None
        if align_path:
            a = np.load(align_path)
            if "pos" not in a:
                raise ValueError(f"align file {align_path}: missing 'pos'")
            if a["pos"].shape != (self.ids.shape[0],):
                raise ValueError(
                    f"align pos shape {a['pos'].shape} != soft rows {self.ids.shape[0]}"
                )
            self.align = a["pos"].astype(np.uint8)

    def batch(self, idx, T, dev):
        """Teacher ids/probs per logits position for the rows `idx`, zero
        where the teacher said nothing: `[B, T-1, k]` each.

        Default layout (no `align`): soft row r predicts token 4+r, so it
        lands at logits position 3+r (cap at T-2). When `align` is set we
        scatter row k into position `pos[k]` (capped / dropped if >= T-1).
        """
        B = len(idx)
        ids = np.zeros((B, T - 1, self.k), dtype=np.int64)
        probs = np.zeros((B, T - 1, self.k), dtype=np.float32)
        Tp = T - 1
        if self.align is None:
            for b, s in enumerate(idx):
                i = self.at.get(int(s))
                if i is None:
                    continue
                a, e = self.starts[i], self.starts[i + 1]
                n = min(int(e - a), Tp - 3)
                if n > 0:
                    ids[b, 3 : 3 + n] = self.ids[a : a + n]
                    probs[b, 3 : 3 + n] = self.probs[a : a + n]
        else:
            for b, s in enumerate(idx):
                i = self.at.get(int(s))
                if i is None:
                    continue
                a, e = int(self.starts[i]), int(self.starts[i + 1])
                n = e - a
                if n <= 0:
                    continue
                pos_b = self.align[a:e]
                # Keep rows whose pos is in [0, Tp). Drop rows with pos == 255
                # (unavailable) or pos >= Tp. One vectorised store per row
                # beats a per-element Python loop at batch 256 x ctx 40.
                keep = (pos_b < Tp)
                ks = np.flatnonzero(keep)
                if ks.size:
                    j = pos_b[ks].astype(np.int64)
                    ids[b, j] = self.ids[a + ks]
                    probs[b, j] = self.probs[a + ks]
        return torch.from_numpy(ids).to(dev), torch.from_numpy(probs).to(dev)


def distill_loss(logits, ids, probs, mask=None, weight=None):
    """-sum_k p_k log q(id_k), averaged over the positions the teacher filled.

    log q(id) = logit[id] - logsumexp(logits): the reduction keeps only a
    [B, T] tensor live, where a materialised log-softmax over the whole
    vocabulary ([B, T, V], a gigabyte at batch 256) sent MPS into swap.

`mask` is an optional uint8 tensor of shape (rows_soft,) — applied per row.
It reshapes to [B, T-1] and zeros the kept-count on rows where mask==0.
Note: when `distill_align` is NOT set, the soft row for logits position j is
r = j - 3 (row r predicts the token at absolute position 4 + r). When
`distill_align` IS set, soft row k has its own logits position in
`align_pos[k]` (uint8), and the mask/weight tensors expand to [B, T-1] via
the same `offsets` tensor that the CE gate and the keep-mask/weight builders
share.
    `weight` is an optional float tensor of shape (rows_soft,).
    Weighted KD is sum(w*loss)/sum(w) over kept positions; sum(w)==0 returns
    a zero tensor (same shape as the existing `filled`-empty guard)."""
    filled = probs.sum(-1) > 0
    if mask is not None:
        # mask arrives as (rows_soft,); reshape to (B, T-1) and combine.
        B, Tm1, K = ids.shape
        m = mask.reshape(B, Tm1)
        filled = filled & (m > 0)
    if not bool(filled.any()):
        return logits.new_zeros(())
    lse = torch.logsumexp(logits, dim=-1)
    picked = torch.gather(logits, -1, ids)
    per_pos = -(probs * (picked - lse.unsqueeze(-1))).sum(-1)
    if weight is None:
        return per_pos[filled].mean()
    # weight is (rows_soft,) -> reshape to (B, T-1).
    B, Tm1, K = ids.shape
    w = weight.reshape(B, Tm1).to(per_pos.dtype)
    wsum = (w * filled.to(w.dtype)).sum()
    # Stay on the device: `.item()` here would sync every weighted step. With
    # an all-zero weight the numerator is zero too, so the tiny floor is inert.
    return ((per_pos * w)[filled].sum()) / wsum.clamp_min(torch.finfo(wsum.dtype).tiny)


class Data:
    def __init__(self, path, dev):
        z = np.load(path)
        self.tokens = torch.from_numpy(z["tokens"].astype(np.int64))
        self.starts = z["starts"]
        self.n = len(self.starts) - 1
        self.dev = dev

    def batch(self, idx, ctx, gen=None, drop_prefix=0.0):
        rows = [self.tokens[self.starts[i]:self.starts[i + 1]] for i in idx]
        T = min(ctx, max(len(r) for r in rows))
        out = torch.full((len(rows), T), PAD, dtype=torch.long)
        for i, r in enumerate(rows):
            r = r[:T]
            out[i, : len(r)] = r
        if drop_prefix > 0:
            mask = torch.rand(len(rows), generator=gen) < drop_prefix
            out[mask, 0] = NEUTRAL_REG
            out[mask, 1] = OTHER_FIELD
        return out.to(self.dev)


def evaluate(model, data, ctx, dev):
    model.eval()
    total, count = 0.0, 0
    with torch.no_grad():
        for a in range(0, min(data.n, 20000), 256):
            idx = range(a, min(data.n, a + 256))
            x = data.batch(idx, ctx)
            logits = model(x[:, :-1])
            tgt = x[:, 1:]
            # The prefix tokens are given, not predicted: score from <s> on.
            tgt = tgt.clone()
            tgt[:, :3] = PAD
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD, reduction="sum")
            total += loss.item()
            count += (tgt != PAD).sum().item()
    model.train()
    return total / max(1, count)


def _pick_device(name):
    """`auto` keeps the historic CUDA/MPS/CPU selection; anything else is a
    torch device name (`cpu`, `cuda`, `cuda:1`, `mps`)."""
    if name == "auto":
        return torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    return torch.device(name)


def teacher_row_offsets(soft, idx, T):
    """`offsets[b, j]` = soft-row index predicted at logits position `j`, -1 = none.

    With `soft.align`: positions come from the word-aware `pos[k]`. Without it:
    row k maps to position `3 + k` (the historic per-token layout). This is the
    one mapping used by the loss and by the mask/weight gate builders."""
    offsets = np.full((len(idx), T), -1, dtype=np.int64)
    if soft.align is None:
        for b, s in enumerate(idx):
            i = soft.at.get(int(s))
            if i is None:
                continue
            a = int(soft.starts[i])
            e = int(soft.starts[i + 1])
            n = min(e - a, T - 3)
            if n > 0:
                offsets[b, 3 : 3 + n] = np.arange(a, a + n)
    else:
        for b, s in enumerate(idx):
            i = soft.at.get(int(s))
            if i is None:
                continue
            a = int(soft.starts[i])
            e = int(soft.starts[i + 1])
            pos_b = np.asarray(soft.align[a:e], dtype=np.int64)
            valid = pos_b < T  # 255 ("no position") is >= T by construction
            ks = np.flatnonzero(valid)
            if ks.size:
                offsets[b, pos_b[ks]] = (a + ks).astype(np.int64)
    return offsets


def gates_for_batch(offsets, keep_kd=None, keep_ce=None, weight_kd=None):
    """Per-position gates from `offsets`, as numpy arrays of its shape.

    Returns `(blocked_ce, kd_mask, kd_weight)`; `blocked_ce` is a bool array
    (True = drop that position from CE), `kd_mask` is uint8 (1 = keep in KD)
    and `kd_weight` is float32. Any argument that is None yields None."""
    if offsets is None:
        return None, None, None
    sel = offsets >= 0
    rows = offsets
    blocked_ce = None
    if keep_ce is not None:
        ce_vals = np.zeros_like(rows)
        ce_vals[sel] = keep_ce[rows[sel].clip(min=0)]
        blocked_ce = sel & (ce_vals == 0)
    kd_mask = None
    if keep_kd is not None:
        kd_vals = np.ones_like(rows, dtype=np.uint8)
        kd_vals[sel] = keep_kd[rows[sel].clip(min=0)]
        # Unmapped positions keep today's behaviour (weight 1).
        kd_mask = np.where(sel, kd_vals, 1).astype(np.uint8)
    kd_weight = None
    if weight_kd is not None and weight_kd[0] == "per_row":
        wvals = np.ones_like(rows, dtype=np.float32)
        wvals[sel] = weight_kd[1][rows[sel].clip(min=0)]
        kd_weight = np.where(sel, wvals, 1.0).astype(np.float32)
    elif weight_kd is not None and weight_kd[0] == "scale":
        kd_weight = np.full(rows.shape, weight_kd[1], dtype=np.float32)
    return blocked_ce, kd_mask, kd_weight


def load_gates(soft, mask_path, weight_path):
    """Load `distill_mask`/`distill_weight` and validate against the soft rows.

    Returns `(keep_kd, keep_ce, weight_kd)`; `weight_kd` is `("per_row", arr)`
    or `("scale", float)`. Raises on shape mismatch."""
    keep_kd = keep_ce = None
    if mask_path:
        if soft is None:
            raise ValueError("distill_mask= requires distill=")
        z = np.load(mask_path)
        if "kd" not in z:
            raise ValueError(f"distill_mask={mask_path}: missing 'kd' array")
        rows_soft = int(soft.ids.shape[0])
        keep_kd = z["kd"].astype(np.uint8)
        if keep_kd.shape != (rows_soft,):
            raise ValueError(f"distill_mask shape {keep_kd.shape} != soft rows {rows_soft} ({mask_path})")
        if "ce" in z:
            keep_ce = z["ce"].astype(np.uint8)
            if keep_ce.shape != (rows_soft,):
                raise ValueError(f"distill_mask 'ce' shape {keep_ce.shape} != soft rows {rows_soft}")
    weight_kd = None
    if weight_path:
        if soft is None:
            raise ValueError("distill_weight= requires distill=")
        try:
            weight_kd = ("scale", float(weight_path))
        except ValueError:
            w = np.load(weight_path)
            for cand in ("weight", "kd_weight", "w"):
                if cand in w:
                    arr = w[cand]
                    break
            else:
                raise ValueError(f"distill_weight={weight_path}: no 'weight'/'kd_weight'/'w' array")
            arr = arr.astype(np.float32)
            rows_soft = int(soft.ids.shape[0])
            if arr.shape != (rows_soft,):
                raise ValueError(f"distill_weight shape {arr.shape} != soft rows {rows_soft}")
            weight_kd = ("per_row", arr)
    return keep_kd, keep_ce, weight_kd


def main():
    data_dir, out = sys.argv[1:3]
    o = opts(sys.argv[3:])
    os.makedirs(out, exist_ok=True)
    dev = _pick_device(o["device"])
    print("device", dev, flush=True)
    torch.manual_seed(o["seed"])
    gen = torch.Generator().manual_seed(o["seed"])
    vocab = sum(1 for _ in open(os.path.join(data_dir, "vocab.txt"), encoding="utf-8"))
    train = Data(os.path.join(data_dir, "train.npz"), dev)
    held = Data(os.path.join(data_dir, "heldout.npz"), dev)
    soft = Soft(o["distill"], align_path=o["distill_align"] or None) if o["distill"] else None
    if soft:
        print("distilling from", o["distill"], "over", len(soft.sentences), "sentences",
              "with" if soft.align is not None else "without", "align", file=sys.stderr, flush=True)
    # Optional per-row gate/weight for the KD term (CE gate for teacher
    # batches). Shapes validated against the soft file row count.
    keep_kd, keep_ce, weight_kd = load_gates(soft, o["distill_mask"], o["distill_weight"])
    model = WordLm(vocab, d=o["d"], layers=o["layers"], heads=o["heads"], ff=o["ff"], ctx=o["ctx"]).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "train sentences", train.n, "vocab", vocab, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.1, betas=(0.9, 0.95))
    warm = 500
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run, best, start = time.time(), 0.0, 1e9, 0
    if o["resume"]:
        saved = torch.load(o["resume"], map_location=dev)
        model.load_state_dict(saved["model"])
        start, best = saved["step"], saved.get("heldout", 1e9)
        for _ in range(start):
            sched.step()
        print("resumed from", o["resume"], "at step", start, flush=True)
    for step in range(start + 1, o["steps"] + 1):
        from_teacher = soft is not None and torch.rand((), generator=gen).item() < o["distill_share"]
        if from_teacher:
            idx = soft.sentences[torch.randint(0, len(soft.sentences), (o["batch"],), generator=gen).numpy()].tolist()
        else:
            idx = torch.randint(0, train.n, (o["batch"],), generator=gen).tolist()
        x = train.batch(idx, o["ctx"], gen, o["drop_prefix"])
        logits = model(x[:, :-1])
        tgt = x[:, 1:].clone()
        tgt[:, :3] = PAD
        if from_teacher:
            ids, probs = soft.batch(idx, x.shape[1], dev)
            B, T_ = ids.shape[0], ids.shape[1]
            # offsets/gates come from the shared helpers so the loss, the CE
            # gate and the mask/weight slices use the same arithmetic.
            use_offsets = keep_ce is not None or keep_kd is not None or weight_kd is not None
            offsets = teacher_row_offsets(soft, idx, T_) if use_offsets else None
            blocked_np, mask_sl, weight_sl = gates_for_batch(offsets, keep_kd, keep_ce, weight_kd)
            if blocked_np is not None:
                blocked = torch.from_numpy(blocked_np).to(dev, non_blocking=True)
                tgt = tgt.masked_fill(blocked, PAD)
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD)
            if mask_sl is not None or weight_sl is not None:
                m_t = torch.from_numpy(mask_sl).to(dev) if mask_sl is not None else None
                w_t = torch.from_numpy(weight_sl).to(dev) if weight_sl is not None else None
                d = distill_loss(logits, ids, probs, mask=m_t, weight=w_t)
                loss = (1 - o["distill_alpha"]) * loss + o["distill_alpha"] * d
            else:
                # Today: no mask/weight; the existing call path.
                loss = (1 - o["distill_alpha"]) * loss + o["distill_alpha"] * distill_loss(logits, ids, probs)
        else:
            loss = F.cross_entropy(logits.reshape(-1, logits.shape[-1]), tgt.reshape(-1), ignore_index=PAD)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > start + 1 else loss.item()
        if step % 100 == 0:
            print(f"step {step} loss {run:.3f} ppl {math.exp(run):.1f} lr {sched.get_last_lr()[0]:.2e} {time.time() - t0:.0f}s", flush=True)
        if step % o["eval_every"] == 0 or step == o["steps"]:
            h = evaluate(model, held, o["ctx"], dev)
            print(f"eval step {step} heldout loss {h:.4f} ppl {math.exp(h):.1f}", flush=True)
            state = {"model": model.state_dict(), "config": model.config(), "opts": o, "step": step, "heldout": h}
            torch.save(state, os.path.join(out, "last.pt"))
            if h < best:
                best = h
                torch.save(state, os.path.join(out, "best.pt"))


if __name__ == "__main__":
    main()
