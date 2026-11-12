"""The tap encoder on recorded phone taps, against the engine's decoder.

usage:
  taps_real.py train <keyboards.tsv> <dev_cases.tsv> <words.txt> <out dir> [steps=6000 batch=256 d=96 layers=4 real=0.3]
  taps_real.py eval <checkpoint.pt> <keyboards.tsv> <test_cases.tsv> <words.txt>[,<words2.txt>] [beam=16 limit=0]

The cases are `sentence_eval REPLAY=... CASES_OUT=...`: every one-word
buffer of recorded taps (Vertanen & Kristensson's noisy typing set, TSI),
labelled `study|keyboard`, with the word meant, what was typed, the taps in
key widths, the decoder's best reading and what the field got. Training
mixes a simulated finger on those keyboards (TSI's people, spread from
careful to a VelociTap typist's, letters left out, doubled and swapped)
with the dev set's real words; the eval reads the test set's taps through
a dictionary-constrained CTC beam (`taps_eval.decode`) and sets its top
word beside the decoder's on the same taps: slipped buffers fixed, clean
ones kept, and how often either one was right (what a merge could reach).
"""
import math
import os
import sys
import time
from collections import defaultdict

import numpy as np
import torch
import torch.nn.functional as F

import tapsim
from tapmodel import TapCtc
from taps_eval import Trie, decode
from train_taps import greedy, to_dev

LETTERS = tapsim.LETTERS
K = tapsim.K
MAX_TAPS = tapsim.MAX_TAPS
# TSI's 16 people: letter spread across (key widths) and down (key heights),
# and mean offset down (key heights); `sentence_eval`'s PEOPLE.
PEOPLE = [
    (0.200, 0.133, -0.061), (0.226, 0.145, 0.024), (0.232, 0.160, -0.083), (0.236, 0.169, -0.048),
    (0.239, 0.150, -0.027), (0.240, 0.194, 0.027), (0.250, 0.115, -0.121), (0.255, 0.191, -0.082),
    (0.257, 0.193, 0.017), (0.257, 0.214, -0.013), (0.258, 0.158, -0.005), (0.262, 0.185, -0.036),
    (0.274, 0.160, -0.061), (0.280, 0.192, 0.032), (0.338, 0.211, -0.115), (0.385, 0.202, -0.028),
]


def keyboards(path):
    """name -> (26 letter centres [26, 2], key height) in key widths."""
    raw = defaultdict(dict)
    for line in open(path):
        name, key, x, y, w, h = line.rstrip("\n").split("\t")
        raw[name][key] = (float(x), float(y), float(w), float(h))
    out = {}
    for name, keys in raw.items():
        if not all(c in keys for c in LETTERS):
            continue
        centres = np.array([[keys[c][0], keys[c][1]] for c in LETTERS], dtype=np.float32)
        out[name] = (centres, float(np.median([keys[c][3] for c in LETTERS])))
    return out


def cases(path, boards):
    out = []
    for line in open(path):
        f = line.rstrip("\n").split("\t")
        if len(f) < 7:
            continue
        label, kind, want, typed, taps_s, dec_top, field = f[:7]
        board = label.split("|")[-1]
        if board not in boards or not want or not all(c in LETTERS for c in want):
            continue
        pts = [tuple(map(float, p.split(","))) for p in taps_s.split(";") if p]
        if not pts or len(pts) > MAX_TAPS or len(want) > MAX_TAPS:
            continue
        out.append(dict(study=label.split("|")[0], board=board, kind=kind, want=want, typed=typed,
                        taps=np.array(pts, dtype=np.float32), dec=dec_top, field=field))
    return out


def simulate(word, centres, height, rng):
    """Taps for `word` from one of TSI's people, scaled careful to sloppy."""
    sx, sy, dy = PEOPLE[rng.integers(len(PEOPLE))]
    scale = rng.uniform(0.8, 2.6)
    letters = list(word)
    out = []
    i = 0
    while i < len(letters):
        roll = rng.random()
        if roll < 0.03 and len(letters) > 3:
            i += 1
            continue
        if roll < 0.04 and i + 1 < len(letters):
            letters[i], letters[i + 1] = letters[i + 1], letters[i]
        cx, cy = centres[LETTERS.index(letters[i])]
        out.append((cx - 0.062 + rng.normal(0, sx * scale), cy + (dy + rng.normal(0, sy * scale)) * height))
        if 0.04 <= roll < 0.05:
            out.append((cx - 0.062 + rng.normal(0, sx * scale), cy + (dy + rng.normal(0, sy * scale)) * height))
        i += 1
    return np.array(out[:MAX_TAPS], dtype=np.float32)


def batch(items, n, rng):
    """`items`: (taps [N, 2], centres [26, 2], word)."""
    taps = np.zeros((n, MAX_TAPS, 4), dtype=np.float32)
    mask = np.zeros((n, MAX_TAPS), dtype=bool)
    keys = np.zeros((n, K, 2), dtype=np.float32)
    kmask = np.zeros((n, K), dtype=bool)
    targets = np.zeros((n, MAX_TAPS), dtype=np.int64)
    lengths = np.zeros(n, dtype=np.int64)
    for b, (pts, centres, word) in enumerate(items):
        m = len(pts)
        taps[b, :m, :2] = pts
        taps[b, 1:m, 2] = math.log1p(0.25)
        taps[b, :m, 3] = 0.09
        mask[b, :m] = True
        perm = rng.permutation(K)
        slot_of = np.argsort(perm)
        for slot in range(K):
            if perm[slot] < 26:
                keys[b, slot] = centres[perm[slot]]
                kmask[b, slot] = True
        label = [slot_of[LETTERS.index(c)] for c in word]
        targets[b, : len(label)] = label
        lengths[b] = len(label)
    return taps, mask, keys, kmask, targets, lengths


def train(argv):
    kb_path, dev_path, words_path, out = argv[:4]
    o = dict(steps=6000, batch=256, lr=1e-3, d=96, layers=4, seed=0, real=0.3)
    for a in argv[4:]:
        k, v = a.split("=")
        o[k] = type(o[k])(v)
    os.makedirs(out, exist_ok=True)
    dev = torch.device("cuda" if torch.cuda.is_available() else "mps" if torch.backends.mps.is_available() else "cpu")
    torch.manual_seed(o["seed"])
    rng = np.random.default_rng(o["seed"])
    boards = keyboards(kb_path)
    names = list(boards)
    real = [c for c in cases(dev_path, boards) if len(c["taps"]) >= 1]
    words, probs = tapsim.load_words(words_path)
    model = TapCtc(d=o["d"], layers=o["layers"], max_taps=MAX_TAPS).to(dev)
    print("params", sum(p.numel() for p in model.parameters()), "real cases", len(real), "boards", names, flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=o["lr"], weight_decay=0.01)
    sched = torch.optim.lr_scheduler.LambdaLR(
        opt, lambda s: min(1, (s + 1) / 300) * 0.5 * (1 + math.cos(math.pi * min(1, s / o["steps"]))))
    t0, run = time.time(), 0.0
    for step in range(1, o["steps"] + 1):
        items = []
        idx = rng.choice(len(words), size=o["batch"], p=probs)
        for b in range(o["batch"]):
            if real and rng.random() < o["real"]:
                c = real[rng.integers(len(real))]
                items.append((c["taps"], boards[c["board"]][0], c["want"]))
            else:
                centres, height = boards[names[rng.integers(len(names))]]
                w = words[idx[b]]
                pts = simulate(w, centres, height, rng)
                if len(pts) == 0:
                    pts = simulate(w, centres, height, rng)
                items.append((pts, centres, w))
        taps, mask, keys, kmask, targets, lengths = batch(items, len(items), rng)
        lp = model(*to_dev([taps, mask, keys, kmask], dev))
        in_len = torch.from_numpy(mask.sum(1) * 2).to(dev)
        loss = F.ctc_loss(lp.transpose(0, 1).float(), torch.from_numpy(targets).to(dev), in_len,
                          torch.from_numpy(lengths).to(dev), blank=K, zero_infinity=True)
        opt.zero_grad(set_to_none=True)
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step()
        sched.step()
        run = 0.98 * run + 0.02 * loss.item() if step > 1 else loss.item()
        if step % 200 == 0:
            print(f"step {step} loss {run:.3f} {time.time() - t0:.0f}s", flush=True)
        if step % 1000 == 0 or step == o["steps"]:
            torch.save({"model": model.state_dict(), "opts": o, "step": step}, f"{out}/last.pt")


def build_trie(paths):
    root = Trie()
    best = {}
    for path in paths.split(","):
        ws, ps = tapsim.load_words(path, min_len=1)
        for w, p in zip(ws, ps):
            best[w] = max(best.get(w, 0.0), p)
    for w, p in best.items():
        node = root
        for c in w:
            node = node.children.setdefault(c, Trie())
        node.word = w
        node.freq = math.log(p + 1e-12)
    return root


def evaluate(argv):
    ckpt, kb_path, cases_path, words_paths = argv[:4]
    opts = dict(a.split("=") for a in argv[4:])
    beam, limit = int(opts.get("beam", 16)), int(opts.get("limit", 0))
    dump = open(opts["dump"], "w") if "dump" in opts else None
    state = torch.load(ckpt, map_location="cpu")
    o = state["opts"]
    model = TapCtc(d=o["d"], layers=o["layers"], max_taps=MAX_TAPS)
    model.load_state_dict(state["model"])
    model.eval()
    boards = keyboards(kb_path)
    trie = build_trie(words_paths)
    tests = cases(cases_path, boards)
    if limit:
        tests = tests[:limit]
    # group: [n, encoder right, decoder's best reading right, either, field right]
    tally = defaultdict(lambda: [0, 0, 0, 0, 0])
    with torch.no_grad():
        for c in tests:
            centres = boards[c["board"]][0]
            m = len(c["taps"])
            taps = np.zeros((1, m, 4), dtype=np.float32)
            taps[0, :, :2] = c["taps"]
            taps[0, 1:, 2] = math.log1p(0.25)
            taps[0, :, 3] = 0.09
            keys = np.zeros((1, K, 2), dtype=np.float32)
            keys[0, :26] = centres
            kmask = np.zeros((1, K), dtype=bool)
            kmask[0, :26] = True
            lp = model(torch.from_numpy(taps), torch.ones((1, m), dtype=torch.bool), torch.from_numpy(keys),
                       torch.from_numpy(kmask))[0].numpy()
            found = decode(lp, trie, beam)
            enc = found[0][0] if found else c["typed"]
            if dump:
                second = found[1][1] if len(found) > 1 else found[0][1] - 99 if found else 0.0
                lead = (found[0][1] - second) if found else 0.0
                dump.write("\t".join([c["study"], c["kind"], c["want"], c["typed"], c["dec"].lower(),
                                      c["field"].lower(), enc, f"{lead:.3f}"]) + "\n")
            dec = c["dec"].lower()
            group = ("clean" if c["kind"] == "clean" else "slipped", "tsi" if c["study"] == "tsi" else "vertanen")
            for g in (group, (group[0], "all")):
                t = tally[g]
                t[0] += 1
                t[1] += enc == c["want"]
                t[2] += dec == c["want"]
                t[3] += enc == c["want"] or dec == c["want"]
                t[4] += c["field"].lower() == c["want"]
    for g, (n, e, d, either, field) in sorted(tally.items()):
        print(f"{g[0]:>8} {g[1]:>8}: n {n:5d}  encoder {e / n:.4f}  decoder {d / n:.4f}  either {either / n:.4f}  field {field / n:.4f}")


if __name__ == "__main__":
    {"train": train, "eval": evaluate}[sys.argv[1]](sys.argv[2:])
