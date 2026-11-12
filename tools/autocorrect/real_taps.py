"""Real phone taps, in the replay format `sentence_eval REPLAY=` reads.

usage: real_taps.py <vertanen noisy_typing dir> <tsi dir> <out dir>

Two corpora with tap coordinates, both CC BY 4.0:

- Vertanen & Kristensson, "A Dataset of Noisy Typing on QWERTY Keyboards"
  (IUI 2023, https://osf.io/5xwng, the JSON release): the phone sentences of
  the VelociTap studies, dev and test sets as the authors split them. Some
  studies asked for sentences without spaces, some on small or tiny
  keyboards, some eyes-free; each row says which (`study` column), so the
  harness can report them apart.
- TSI (Google, UIST 2024): 16 people copy-typing on a Pixel 6 Pro. Taps are
  aligned to the prompt; for each prompt character the first tap made for it
  is kept, so a letter the person typed wrong and then fixed counts as typed
  wrong, the way the keyboard first saw it. A character with no tap at all
  (skipped) is left out. Phrase prompts only.

Out:
  keyboards.tsv  name, key ('<sp>' for the bar), centre x, centre y, width,
                 height, all in letter-key widths
  replay.tsv     source, set, person, keyboard, study, meant text,
                 taps as x,y;x,y... in letter-key widths, touch-down point

Every tap goes in, space taps included: the harness decides what each one
hit on the keyboard's own geometry.
"""
import csv
import json
import os
import sys
from collections import Counter


def letter_width(keys):
    widths = Counter(round(k["width"], 1) for k in keys if len(k["character"]) == 1 and k["character"].isalpha())
    return widths.most_common(1)[0][0]


def vertanen(root, out_kb, out_rows):
    keyboards = {k["keyboard"]: k["keys"] for k in json.load(open(os.path.join(root, "keyboards.json")))}
    units = {}
    for sentence in json.load(open(os.path.join(root, "noisy_typing.json"))):
        if sentence.get("device") != "phone" or sentence.get("set") not in ("dev", "test"):
            continue
        ref = (sentence.get("ref") or "").strip().lower()
        if not ref:
            continue
        name = sentence["keyboard"]
        if name not in units:
            keys = keyboards[name]
            w = letter_width(keys)
            units[name] = w
            for k in keys:
                out_kb.append((name, k["character"], k["x_center"] / w, k["y_center"] / w, k["width"] / w, k["height"] / w))
        w = units[name]
        taps = []
        for chunk in sentence["chunks"]:
            if chunk["deleted"]:
                continue
            for tap in chunk["taps"]:
                if tap["input"]:
                    p = tap["input"][0]
                    taps.append((p["x"] / w, p["y"] / w))
        if not taps:
            continue
        study = sentence["experiment"]
        if sentence.get("condition"):
            study += ":" + str(sentence["condition"])
        person = f"{sentence['experiment']}:{sentence['participant']}"
        out_rows.append(("vertanen", sentence["set"], person, name, study, ref, taps))


def tsi(root, out_kb, out_rows):
    info = json.load(open(os.path.join(root, "keyboard_data.json")))
    keys = info["keys_info"]
    w = info["keyboard_info"]["most_common_key_width"]
    for ch, k in keys.items():
        label = "<sp>" if ch == "SPACE" else ch
        out_kb.append(("tsi", label, k["key_center_x"] / w, k["key_center_y"] / w, k["key_width"] / w, k["key_height"] / w))
    prompts = {}
    with open(os.path.join(root, "prompt_data.csv"), newline="") as f:
        for row in csv.DictReader(f):
            if row["prompt_type"] == "phrase":
                prompts[(row["participant_id"], row["task_id"], row["trial_id"])] = row["prompt"]
    first = {}
    with open(os.path.join(root, "touch_data.csv"), newline="") as f:
        for row in csv.DictReader(f):
            key = (row["participant_id"], row["task_id"], row["trial_id"])
            if key not in prompts:
                continue
            at = int(row["ref_char_index_in_prompt"])
            t = int(row["timestamp_ms"])
            slot = first.setdefault(key, {})
            if at not in slot or t < slot[at][0]:
                slot[at] = (t, float(row["first_frame_touch_x"]) / w, float(row["first_frame_touch_y"]) / w)
    for key, slot in first.items():
        ref = prompts[key].strip().lower()
        taps = [(x, y) for _, (_, x, y) in sorted(slot.items())]
        out_rows.append(("tsi", "all", key[0], "tsi", "tsi", ref, taps))


def main():
    vroot, troot, out = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    kb, rows = [], []
    vertanen(vroot, kb, rows)
    tsi(troot, kb, rows)
    with open(os.path.join(out, "keyboards.tsv"), "w") as f:
        for r in kb:
            f.write("\t".join([r[0], r[1]] + [f"{v:.4f}" for v in r[2:]]) + "\n")
    with open(os.path.join(out, "replay.tsv"), "w") as f:
        for src, split, person, board, study, ref, taps in rows:
            pts = ";".join(f"{x:.3f},{y:.3f}" for x, y in taps)
            f.write("\t".join([src, split, person, board, study, ref, pts]) + "\n")
    print(len(rows), "sentences;", Counter((r[0], r[1]) for r in rows))


if __name__ == "__main__":
    main()
