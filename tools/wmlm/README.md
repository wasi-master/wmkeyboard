# Context model (`.wmlm`) training

The neural context model the keyboard downloads per language: a small
word-level transformer (4.5M to 10M parameters, int8, 5 to 11 MB) that reads
the sentence before the caret once per committed word and says what comes
next. `wm-engine/src/lm.rs` runs it on the phone; `ContextModel.kt` is the
Kotlin side; `ContextModelCatalog` lists the published files.

What the model does for the strip, and the numbers behind the defaults, are
in `docs/src/content/docs/smart/suggestions.mdx` under "The context model"
and `smart/autocorrect.mdx` under "The learned gate".

## Files

| | |
|---|---|
| `common.py` | the tokenisation both sides share (lower-case, trim punctuation, `<num>`, the sixteen specials) |
| `corpus.py` | sentences out of Tatoeba and Leipzig archives, labelled with register and field |
| `banglish.py` | romanised Bengali sentences out of a Leipzig Bengali corpus, with chat spellings from the spelling map |
| `prep.py` | vocabulary and token arrays, plus the held-out sentences as text; `pieces=N` reserves N slots for subword pieces so words outside the vocabulary are spelled rather than `<unk>` |
| `model.py`, `train.py` | the model and its training loop (CUDA, MPS or CPU); `distill=` adds a teacher's soft targets to the loss |
| `distill.py` | runs a large causal LM (Qwen2.5-0.5B by default, through `transformers`) over a sample of the training sentences and writes its top-k next-word distribution mapped onto the student's vocabulary |
| `export.py` | a checkpoint as a `.wmlm`, with a `.sha256` sidecar |
| `check.py` | PyTorch's answers for one context, to compare with `cargo run --example lm_probe` |
| `train_confidence.py` | fits the learned autocorrect gate on the typing harness's rows and writes the constants into `confidence.rs` and `Confidence.kt` |
| `soft_mask.py` | per-row keep masks, candidate weights, word-aware alignment and unigram row-0 fill for `distill=`/`train.py` |
| `build_kd_weight.py` | a per-row KD weight from a `soft_mask.py` mask file |
| `paired_eval.py` | paired McNemar/bootstrap/Holm on two binary item dumps |
| `slice_data.py` | a hermetic slice of a data dir and its soft targets |
| `average_checkpoints.py` | averages checkpoints for a weight soup |

## English

Needs a Python with torch and numpy. Corpora go in their own folder (they
are untrusted downloads): Tatoeba's `eng_sentences.tsv.bz2` and Leipzig's
`eng_news_2023_1M.tar.gz`.

```bash
python corpus.py tatoeba:eng_sentences.tsv.bz2 leipzig:eng_news_2023_1M.tar.gz > en_sentences.tsv
python prep.py en_sentences.tsv en_lm vocab=16384 ctx=32 seed_words=../../app/dictionaries-src/en.txt
python train.py en_lm en_run steps=8000 batch=256
python export.py en_run/best.pt en_lm/vocab.txt en_context.wmlm --langs=en --name=English
```

Tatoeba sentences are labelled casual and chat, news formal; a third of
the rows train with the neutral labels so a caller that does not know them
is served too.

The bigger recipe adds OpenSubtitles dialogue (`lines:<path>:1:1`, casual
chat), reserves pieces, and trains a d256 six-layer model (10M parameters,
about 10 MB int8):

```bash
python corpus.py tatoeba:eng_sentences.tsv.bz2 leipzig:eng_news_2023_1M.tar.gz lines:opensub_en.txt:1:1 > en2_sentences.tsv
python prep.py en2_sentences.tsv en2_lm vocab=24576 pieces=2048 ctx=40 seed_words=../../app/dictionaries-src/en.txt
python train.py en2_lm en2_run steps=16000 batch=512 d=256 layers=6 ff=768 ctx=40
```

Distillation (optional, needs a venv with `transformers`): write the
teacher's soft targets for a sample of sentences, then train with them.

```bash
uv venv --python 3.12 .venv-distill && uv pip install --python .venv-distill/bin/python torch transformers
.venv-distill/bin/python distill.py en2_lm en2_soft.npz sentences=100000 topk=24
python train.py en2_lm en2_run_kd steps=16000 batch=512 d=256 layers=6 ff=768 ctx=40 distill=en2_soft.npz distill_alpha=0.5
``` The vocabulary takes every word of the keyboard's own
frequency list the corpus has seen, then the corpus's commonest words.

## English and Banglish in one model

```bash
python banglish.py ben_community_2017.tar.gz ../../app/src/main/assets/dictionaries/bn_rom.tsv bn_rom_sentences.tsv
(awk 'NR%2==0' en_sentences.tsv; cat bn_rom_sentences.tsv) > en_bn_rom_sentences.tsv
python prep.py en_bn_rom_sentences.tsv en_bn_lm vocab=24576 ctx=32 seed_words=../../app/dictionaries-src/en.txt
python train.py en_bn_lm en_bn_run steps=8000 batch=256
python export.py en_bn_run/best.pt en_bn_lm/vocab.txt bn_rom_context.wmlm --langs=en,bn_rom --name="English and Banglish"
```

`<lang:0>` is English and `<lang:1>` Banglish in that model; the catalog
entry for `bn_rom` points at it and the keyboard passes the slot of the
language it is on.

## Masks, alignment and paired evaluation

`soft_mask.py` derives per-row data for the distillation loss from the
student's tokens and the teacher's soft targets. `--emit-align` writes the
word-aware `pos` map that `train.py distill_align=` consumes; `--emit-keep`
writes a `kd`/`ce` gate, and `--weight cand` writes a candidate-count weight.
`build_kd_weight.py` turns a mask file into a two-level weight (empty 0,
piece <1, word 1) for `distill_weight=`.

```bash
python soft_mask.py build en2_lm en2_soft.npz masks --weight cand --emit-keep kd --emit-align
python build_kd_weight.py masks.masks.npz masks.w03.npz piece_weight=0.3
python train.py en2_lm en2_run_kd steps=16000 batch=512 d=256 layers=6 ff=768 ctx=40 \
    distill=en2_soft.npz distill_alpha=0.3 distill_align=masks.align.npz \
    distill_mask=masks.keep.kd.npz distill_weight=masks.w03.npz
python export.py en2_run_kd/best.pt en2_lm/vocab.txt en_context.wmlm --langs=en --name=English
```

`slice_data.py` cuts a small hermetic data dir (train, heldout, vocab and the
soft rows) for a fast screen; `average_checkpoints.py` averages checkpoints
before `export.py`. `paired_eval.py` compares two runs' per-item dumps
(`ITEMS=…`, below) with McNemar, a bootstrap CI and Holm across metrics.

## Renting a GPU for the bigger model

The d256 six-layer model with a 1.5B teacher and 30k steps is a job for a
CUDA card: a Mac with unified memory swaps at this size, and a 6 GB card
cannot hold the teacher. On a rented RTX 3090/4090 the whole of
`remote/run_en2.sh` (teacher pass, training, export) takes under two hours.

```bash
# here: one upload (scripts + prep'd data + the small teacher's targets)
tar -czf en2_bundle.tar.gz -C build/corpora en2_bundle   # what tools/wmlm packs
# there, in the unpacked wmlm/ directory
bash remote/setup.sh
TEACHER=Qwen/Qwen2.5-1.5B STEPS=30000 bash remote/run_en2.sh
# bring back out/en_context.wmlm and out/best.pt
```

`run_en2.sh` reads TEACHER, SENTENCES, STEPS, BATCH, D/LAYERS/FF/CTX, ALPHA
and OUT from the environment; a sweep is the same script with another OUT.
Qwen2.5-3B as the teacher wants a 16 GB card.

## Measuring

The typing harness (`native/wm-engine/examples/typing_eval.rs`) runs the
Rust engine over the held-out sentences with a simulated finger, with and
without the model, and reports strip top-1, autocorrect precision and
recall, new words wrongly rewritten, next-word top-1 and keystroke savings:

```bash
cd native/wm-engine && cargo build --release --example typing_eval
PACK=pack MODES=base,lm,conf,lm+conf ./target/release/examples/typing_eval \
    ../../app/dictionaries-src/en.txt en_lm/heldout.tsv en_context.wmlm
```

`PACK` is a folder holding the data repo's `en_bigrams.txt` and
`en_trigrams.txt` gunzipped, so the base has the n-gram pack the phone has.
`DUMP=rows.csv` writes the gate's feature rows; `train_confidence.py` fits
on them and writes the constants into both source files. Use `SKIP=10000`
on a measuring run so it reads sentences the fit never saw. `LMBLEND`,
`LMBCAP` and `LMBSLOPE` override the score-space blend's weight, cap and
tap-spread slope (the slope measured flat, so it ships at 0).

`LANG=de` selects QWERTZ, the German seed pairs and `english_sources=false`
(`LAYOUT=qwertz` overrides only the keys); `PACK` and `SEEDS` then hold the
language's `de_bigrams.txt`/`de_trigrams.txt` and seed asset. `ITEMS=items.tsv`
writes one stable row per item for `paired_eval.py`.

The glide harness (`native/wm-engine/examples/glide_lm_blend_eval.rs`) runs
the same score-space blend over a stroke dump:

```bash
cd native/wm-engine && cargo build --release --example glide_lm_blend_eval
COLS=11 WEIGHT=0.5 CAP=5 FREQ=0.5 GAP=2.0 \
    ./target/release/examples/glide_lm_blend_eval records.jsonl layout.json list.txt model.wmlm
```

`records.jsonl` is one JSON object per stroke (`word`, `sentence`, `width`,
`height`, `points`), `layout.json` the keyboard's key centres and `list.txt`
the frequency list. `ITEMS=items.tsv` writes the per-item dump for
`paired_eval.py`.
