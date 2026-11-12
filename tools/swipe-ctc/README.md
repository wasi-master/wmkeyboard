# Geometry-conditioned CTC swipe encoder (prototype)

A glide encoder of our own whose input includes the keyboard: the key
centres go in as a tensor beside the stroke, keys are embedded from their
position alone, and every output head scores a key slot by its embedding and
its distance from the frame. Nothing in the network knows a slot's index or
letter, so one set of weights reads QWERTY, AZERTY, QWERTZ, Colemak, Dvorak or
a user's own layout, and the caller decides which letter each slot stands
for. Its emissions feed wm-engine's `glide::ctc` trie beam (`CtcGlideBeam`)
unchanged: given the 26 letters in a..z order, the classes come out a..z then
blank, as FUTO Swipe's do.

Written from scratch on the MIT swipe.futo.org dataset. Nothing here comes
from CleverKeys (GPL) or FUTO's model code; the idea of key centres as an
input is public and is all that is shared. Not wired into the app.

## Files

| | |
|---|---|
| `geo.py` | stroke units, time-uniform resample, the anchor fit and transplant |
| `layouts.py` | the named eval layouts and the random training layouts |
| `prep.py` | dataset jsonl to training arrays |
| `model.py` | `GeoCtc` |
| `train.py` | training with layout augmentation |
| `transplant.py` | test strokes redrawn on the named layouts (jsonl + layout json) |
| `emit.py` | model emissions as FSW1 records for `ctc_glide_eval` |
| `tapmodel.py` | `TapCtc`, the same encoder for taps: two output steps per tap |
| `tapsim.py` | the simulated finger the tap encoder trains on |
| `train_taps.py`, `taps_eval.py` | training, and the comparison against the engine's walk on `typing_eval`'s exported cases |

## Results (2026-10-26)

FUTO's test split, the 38,049 strokes whose word the bundled `en.txt` holds,
bare trie beam (`ctc_glide_eval`, no marks or shapes) at `FW=0.5`. GlideBeam
is wm-engine's `glide_beam_eval` with the shipped tuning on the same strokes;
FUTO is FUTO Swipe's encoder and decoder from `native/bench/futo-swipe`.
Top-1 / top-4:

| layout | GeoCtc run1 | GeoCtc run2 | GlideBeam | FUTO Swipe |
|---|---|---|---|---|
| QWERTY (real strokes) | .9515 / .9925 | .9427 / .9905 | .8734 / .9598 | .9562 / .9921 |
| QWERTZ | .9507 / .9926 | .9459 / .9915 | .8783 / .9628 | QWERTY only |
| AZERTY | .9069 / .9863 | .9100 / .9864 | .8782 / .9653 | |
| Colemak | .9186 / .9896 | .9213 / .9902 | .8375 / .9560 | |
| Dvorak | .9330 / .9918 | .9375 / .9919 | .8639 / .9661 | |
| alphabetical | .9547 / .9940 | .9579 / .9949 | .9154 / .9788 | |

Both runs: 779,736 parameters (d 96, one key layer, one 64-frame block, four
32-step blocks), 24,000 steps of 384 strokes on an M2 Air, about 2.5 hours.
run1 draws 40% of strokes on their recorded QWERTY and 60% on random
layouts; run2 draws 15% on QWERTY. Every layout but QWERTY is unseen in
training.

- On QWERTY the encoder comes within half a point of FUTO Swipe's top-1
  with the same top-4 (FUTO's encoder without its decoder measured .918 on
  5,000 of these strokes). It is 7.8 points ahead of GlideBeam.
- On the unseen layouts it leads GlideBeam by 2.9 (AZERTY) to 8.1
  (Colemak) points of top-1, and its top-4 is .986 or better everywhere.
- The frequency weight wants to be higher than FUTO's 0.166: 0.5 gains 0.3
  points on QWERTY and 1 to 2 points elsewhere. A model of its own would
  carry its own `Scoring`.
- Doubled letters are the weak subset: .889 on QWERTY (3,055 strokes),
  .76 to .83 on the other layouts.
- Size and speed: 3.1 MB in fp32, 1.6 MB in fp16; 1.8 ms a stroke on one
  M2 core in PyTorch; the trie beam over its emissions takes 3.1 ms p50 on
  the host (about 3.8 ms over FUTO's). Not exported or timed on a phone.

### What the model learned about QWERTY

Without the dictionary (greedy CTC on 3,000 test strokes), run1 reads
QWERTY at .70 but drops to .42 when any one row of QWERTY's letters slides
half a key, which is where random layouts from its own training mix sit
(.42 to .55). Unseen named layouts score the same as trained random ones, so
the gap is not a failure to generalise: exact QWERTY earns a bonus, the path
habits of English on that one geometry, learned from the real strokes. The
trie closes most of the gap (.91 to .96 against .95). Two things follow.
The numbers on the remapped layouts are the model's general ability. And
the QWERTY bonus is English, so a French or German writer on QWERTY may get
none of it or pay for it; no non-English swipe data exists to check. Fewer
real strokes in training (run2) trade 0.9 points of QWERTY for 0.3 to 0.5
elsewhere. AZERTY stays lowest in both runs: its letters on QWERTY's
staggered grid read at .61 greedy, on its own flat home row at .44. Sliding
each row by up to half a key in training (`rowshift=0.5`, `real=0.25`,
stopped at 8,000 steps) moved the first 7,263 strokes by -1.3 points on
QWERTY, +0.7 on AZERTY and 0 on Colemak against run1 at the same step, so
the stagger is not what holds the remapped layouts back.

### Go / no-go

Go, for Latin layouts, as an engine of our own. The code and the weights
are ours and the data is MIT, so it needs no FUTO licence or consent and
could serve every Latin layout, QWERTY included, where today only GlideBeam
does. Before it can ship:

1. `glide::ctc` knows only a..z (`class_of`, `CLASSES = 27`): a word with
   é, ü or ß is unreachable, which rules out French and German dictionaries
   on AZERTY and QWERTZ. It needs a per-alphabet class table that folds
   accents onto their base key unless the layout has a key of their own,
   in the Rust walk and in the Kotlin oracle.
2. Export (the forward pass is standard ops: cdist, scaled dot-product
   attention with a float mask, einsum) and a timing on the Helio G99.
3. Its own `Scoring` weights, fitted the way FUTO's were.

The model takes the stroke and the layout's key centres, both in px, which
the service already has; no frame fit to QWERTY as `SwipeFrame` does.

FUTO Swipe stays ahead on English QWERTY by half a point and can stay the
engine there for users who accept its licence.

No-go for now on other scripts. The model is alphabet-free (any number of
key slots), but there are no real Cyrillic, Greek or Hebrew swipes, and
transplanting cannot make a stroke for a word nobody drew. Training data
would have to come from splicing pieces of real English strokes between key
pairs, or from a synthetic finger, and the decoder needs the class table
above. Untested.

Caveat for every remapped number: the strokes are QWERTY strokes moved onto
the new keys, not strokes drawn on them.

### Box run, 2026-10-26

The same recipe on a rented RTX 5080 (`train.py ... steps=24000`, batch 384,
about ten minutes): `ctc_glide_eval` top-1 QWERTY .946, QWERTZ .946, Dvorak
.922, Colemak .905, AZERTY .889 over the 38,049 test strokes (top-4 .98 to
.99 everywhere). The tap encoder at `d=128 layers=6 steps=20000` decodes the
harness's cases at .973/.913/.831 against the walk's .872/.795/.721 on the
same taps. Checkpoints are kept under `build/corpora/box/{swipe,taps}/`.

## Taps

No public corpus holds phone taps with their coordinates, so the tap
encoder learns from a simulated finger (`tapsim.py`): words from the
keyboard's list, Gaussian scatter from careful to sloppy, a dropped, stray
or swapped letter at literature rates, log-normal gaps. The Rust harness
draws its cases from the same kind of finger, so the two can be compared on
identical taps:

```bash
python train_taps.py ../../app/dictionaries-src/en.txt taps_run steps=12000
cd ../../native/wm-engine && PACK=pack CASES_OUT=tap_cases.tsv SAVINGS=0 MODES=base \
    ./target/release/examples/typing_eval ../../app/dictionaries-src/en.txt heldout.tsv
cd ../../tools/swipe-ctc && python taps_eval.py taps_run/best.pt tap_cases.tsv ../../app/dictionaries-src/en.txt
```

The result is an upper bound: the encoder was trained on the very noise
model it is tested on. It is not wired into the app.

### On recorded taps (2026-11-13)

`taps_real.py` trains TapCtc (d96, 4 layers, 6,000 steps of 256, 12
minutes on an M2) on TSI's people simulated on the real studies' keyboards
(spread up to 2.6 times TSI's, letters left out, doubled and swapped) mixed
30% with the real words of Vertanen & Kristensson's noisy typing dev set,
and reads the test set and TSI (`sentence_eval REPLAY=... CASES_OUT=...`,
19,492 one-word buffers from 117 people) beside wm-engine's whole-phrase
decoder on the same taps. Top reading right, on the 7,264 buffers the
person mistyped:

| | decoder | encoder | either |
|---|---|---|---|
| all | .774 | .785 | .845 |
| TSI | .894 | .862 | .922 |
| Vertanen | .752 | .770 | .831 |

Alone, the encoder is one point ahead and three behind on TSI, the set it
saw no real taps from: what the published comparisons found (Ma et al.,
CHI 2025, 95.4% against 96.3% for a spatial model and language model).
The two are wrong on different words, though. Letting the encoder change
only a word the decoder left as typed, when its best word leads its second
by a margin, on what ends up in the field:

| rule | mistyped fixed | TSI | correct words changed |
|---|---|---|---|
| decoder as shipped | .707 | .840 | 0.14% |
| + encoder, lead 6 | .726 | .842 | 0.14% |
| + encoder, lead 4 | .746 | .850 | 0.20% |
| + encoder, lead 2 | .769 | .863 | 0.39% |

Two to four points of mistyped words at the bar this pilot set for itself
(three points, no more damage) is short of it, and the Vertanen numbers
carry some home advantage from the dev set's studies. A rented-GPU run of
the full size and a merge fitted properly might clear it; shipping would
also need the ExecuTorch export and a reading of Google's US9678664
(neural keyboard decoding, in force to about 2035). Not wired into the app.

## Transplanting a stroke

swipe.futo.org was drawn on QWERTY only. To train on and test other layouts
with real hands, each stroke is cut at the points nearest its letters' QWERTY
keys (a monotone fit over the squeezed word) and every point moves by a blend
of its span's two letters' displacements to the new layout, by arc length:
`p + (1 - s) d[lo] + s d[hi]`. The stroke keeps the writer's timing, wobble,
overshoot and how far each key was missed; only where the keys sit changes.
Training draws a random letter order on a random grid for most strokes
(`layouts.bank`) and never sees the named layouts, so the eval on AZERTY,
QWERTZ, Colemak, Dvorak and an alphabetical layout is on layouts the model has
not met. It is still semi-synthetic: a real writer on an unfamiliar layout
swipes slower and less surely than the QWERTY strokes moved onto it.

## Running it

Needs a Python with torch and numpy (MPS on Apple silicon; no executorch) and
the dataset files from `native/bench/futo-swipe/README.md` step 1, plus the
train split (`train.jsonl` from the same revision). From this folder:

```bash
python prep.py train.jsonl qwerty.json train.npz
python prep.py test.jsonl qwerty.json test.npz
python train.py train.npz test.npz qwerty.json run steps=24000
python transplant.py test.jsonl qwerty.json layouts
python emit.py run/last.pt test.jsonl qwerty.json qwerty.bin
python emit.py run/last.pt layouts/dvorak.jsonl layouts/dvorak.json dvorak.bin
```

Then from `native/wm-engine`:

```bash
cargo run --release --example ctc_glide_eval -- qwerty.bin ../../app/dictionaries-src/en.txt
cargo run --release --example glide_beam_eval -- layouts/dvorak.jsonl layouts/dvorak.json ../../app/dictionaries-src/en.txt < /dev/null
```

The scripts import each other, so run them from this folder; keep the
downloaded files in their own folder outside it.
