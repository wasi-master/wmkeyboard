---
title: "WMLM training and evaluation"
description: "Prepare, distil, mask and evaluate the neural context model, then export a .wmlm with a verifiable hash."
sidebar:
  order: 8
---

The context model the keyboard downloads per language is a small word-level
transformer that reads the sentence before the caret and says what comes next.
[Suggestions](/smart/suggestions/) describes what it does in the app; this page
covers the training and evaluation tools in `tools/wmlm/`. That directory's
`README.md` is the command reference; the steps below are the end-to-end path.

## Prepare the arrays

`prep.py` turns a sentence TSV into the `train.npz`, `heldout.npz`,
`heldout.tsv` and `vocab.txt` a run needs. Reserving piece slots lets the model
spell a word the vocabulary does not list instead of emitting `<unk>`:

```sh
python tools/wmlm/prep.py en_sentences.tsv en_lm vocab=24576 pieces=2048 ctx=40 \
    seed_words=app/dictionaries-src/en.txt
```

## Distil a teacher

`distill.py` runs a causal language model over a sample of the training
sentences and maps its top-k next-word distribution onto the student's
vocabulary. The result is a soft-target file that `train.py distill=` reads:

```sh
python tools/wmlm/distill.py en_lm en_soft.npz teacher=Qwen/Qwen2.5-1.5B sentences=100000 topk=24
```

## Add masks and alignment

`soft_mask.py` derives per-row data from the student's tokens and the teacher's
soft targets. `--emit-align` writes the word-aware position map, `--emit-keep`
writes a per-row KD/CE gate, and `--weight cand` writes a candidate-count
weight. `build_kd_weight.py` turns a mask file into a two-level weight:

```sh
python tools/wmlm/soft_mask.py build en_lm en_soft.npz masks \
    --weight cand --emit-keep kd --emit-align
python tools/wmlm/build_kd_weight.py masks.masks.npz masks.w03.npz piece_weight=0.3
```

## Train and evaluate

`train.py` keeps the plain cross-entropy target where the teacher said nothing,
mixes the distillation loss on the rows the teacher filled, and evaluates up to
20,000 held-out sentences:

```sh
python tools/wmlm/train.py en_lm en_run steps=16000 batch=512 d=256 layers=6 ff=768 ctx=40 \
    distill=en_soft.npz distill_alpha=0.3 distill_align=masks.align.npz \
    distill_mask=masks.keep.kd.npz distill_weight=masks.w03.npz
```

For a quick screen, `slice_data.py` cuts a hermetic slice of the data dir and
its soft targets. `average_checkpoints.py` averages saved checkpoints before
export; evaluate the soup on heldout first.

## Compare two runs

The typing harness writes a stable per-item dump with `ITEMS=items.tsv`; two
runs' dumps go into `paired_eval.py`, which reports McNemar, a bootstrap CI and
a Holm correction across the metrics:

```sh
cd native/wm-engine && cargo build --release --example typing_eval
ITEMS=items_a.tsv PACK=pack MODES=base,lm \
    ./target/release/examples/typing_eval ../../app/dictionaries-src/en.txt \
    en_lm/heldout.tsv en_context.wmlm
cd ../.. && python tools/wmlm/paired_eval.py items_a.tsv items_b.tsv --metric lm_strip_top1
```

`LANG=de` selects QWERTZ, the German seed pairs and `english_sources=false`;
pass the German word list, heldout split and `.wmlm` for that run. The glide harness
(`native/wm-engine/examples/glide_lm_blend_eval.rs`) writes the same kind of
per-item dump for a stroke set.

## Export

`export.py` writes the int8 `.wmlm` and a `.sha256` sidecar next to it:

```sh
python tools/wmlm/export.py en_run/best.pt en_lm/vocab.txt en_context.wmlm \
    --langs=en --name=English
sha256sum -c en_context.wmlm.sha256
```

The app-side reader and the catalog entry are described in
[Suggestions](/smart/suggestions/); the Rust reader is
`native/wm-engine/src/lm.rs`.
