# FUTO Swipe on real swipes

The FUTO Swipe glide engine's decoder (`wm-engine`'s `glide::ctc`) measured on
FUTO's MIT-licensed swipe.futo.org strokes, with the model's emissions
computed once in Python so the decoder can be iterated on in Rust.

1. Fetch the data and the models (MIT dataset, FUTO Model Weights License 1.0
   for the weights; nothing here is committed):

   ```bash
   curl -L -o test.jsonl https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/d71bf5fd/test.jsonl
   curl -L -o qwerty.json https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/d71bf5fd/swipe-5/layouts/qwerty.json
   R=18328c3042b066952c0936b3771d492fe2ec289a
   curl -L -o honorable_sturgeon_model_fp32.pte https://huggingface.co/futo-org/futo-swipe/resolve/$R/honorable_sturgeon/model_fp32.pte
   curl -L -o magic_macaw_model_fp32.pte https://huggingface.co/futo-org/futo-swipe/resolve/$R/magic_macaw/model_fp32.pte
   ```

2. A Python with `executorch` 1.5.1 and `torch` (`uv venv && uv pip install executorch torch numpy`).

3. Emissions, as the app computes them (`SwipeFrame.features`, the encoder, the
   decoder): `python -I emit.py test.jsonl qwerty.json <models dir> test.bin`.
   `loopify.py` redraws strokes with a loop on the doubled key (or a stray one
   on a word with none), `truncate.py` cuts strokes of 6+ letters at a share
   of their length; both write jsonl for `emit.py`.

4. `cargo run --release --example ctc_glide_eval -- test.bin ../../app/dictionaries-src/en.txt [mode]`
   from `native/wm-engine`; the example's header lists the modes.

Measured 2026-10-21 (bundled en.txt, 38,049 test swipes whose word it holds):

| | top-1 | top-4 |
|---|---|---|
| bare decoder | .9562 | .9921 |
| loops (weight 2) | .9562 | .9921 |
| look-ahead, Eager margin (cost 0.5) | .9560 | .9921 |
| learned shapes (weight 10) | .9564 | .9921 |

| subset | without | with |
|---|---|---|
| doubled key redrawn with a loop (3,055) | .9437 | .9460 |
| stray loop on a word with no double (4,485) | .8954 | .8939 |
| cut at 70% of the path (4,619), top-1 | .068 | .120 |
| cut at 50% (4,619), top-1 | .0004 | .038 |
| drawn before in the session (8,656) | .9812 | .9841 |
| not drawn before (29,393) | .9488 | .9482 |

Dropped: pause and wiggle marks (charging words that skip a paused key cost
0.3 point of top-1 at weight 1 and 2.3 at weight 3: the model reads timing
itself), the broad loop charge GlideBeam uses (+0.6 on looped doubles but
−1 to −9 points on stray loops), and learned-shape seeding (.9564 to .9552).

## GlideBeam on German strokes (#966)

The built-in decoder on real German swipes, for the per-language tuning
profile. Two public corpora, both with the key geometry they were drawn on:
FUTO's `swipe-5` (MIT; Wikipedia sentences, common words) and HeliBoard's
gesture data (CC BY-SA 4.0; rare words). Word lists from FrequencyWords
(MIT). Nothing here is committed.

```bash
curl -L -o swipe5.jsonl https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/swipe-5/swipe5.jsonl
curl -L -o german.json  https://huggingface.co/datasets/futo-org/swipe.futo.org/resolve/main/swipe-5/layouts/german.json
curl -L -o de_full.txt  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/de/de_full.txt
head -n 150000 de_full.txt > de_150k.txt
# HeliBoard's German records (zstd JSON; each carries its own key boxes):
# https://codeberg.org/eclexic/heliboard-gesture-data, compressed/batch_1/de/
```

Pick the German single-finger strokes out of `swipe5.jsonl` into one jsonl
of the harness's shape, then from `native/wm-engine`:

```bash
COLS=11 RESULTS=6 ALL=1 DUMP=/tmp/de_shipped.tsv \
  cargo run --release --example glide_beam_eval -- de_swipes.jsonl german.json de_150k.txt < /dev/null
echo 'freq_weight=0.5 gap_weight=2' | COLS=11 RESULTS=6 ALL=1 DUMP=/tmp/de_profile.tsv \
  cargo run --release --example glide_beam_eval -- de_swipes.jsonl german.json de_150k.txt
```

With `RESULTS=16` and the German profile the dump also answers how close a
stroke's top two have to be before it is worth decoding wider (#1016). On the
2,588 FUTO strokes the word is in the top 8 on 2,190 and at rank 9 to 16 on 18:

| gap under | strokes widened | of the 18 reached |
|---|---|---|
| 0.25 | 5.4% | 4 |
| 0.5 | 10.7% | 7 |
| 1 | 20.6% | 12 |
| 2 | 40.8% | 14 |
| 3 | 59.2% | 17 |
| 5 | 82.1% | 18 |

`ALL=1` counts a stroke whose word the list lacks as a miss (success per
swipe); the two dumps diffed are the regression set, the strokes right
before and wrong after. Measured by the issue's reporter on 2,588 FUTO and
10,908 HeliBoard strokes, success per swipe, shipped against the German
profile (`freq_weight` 0.5, `gap_weight` 2.0): 71.41 to 74.27 and 21.95 to
32.51; the same profile on 2,129 English test strokes 81.49 to 80.41. The
error radii, `sigma` and the result cap were swept too and sit at their
optimum for German as well; `GlideLanguageProfiles` in `:core:prediction`
holds the table.

## GlideBeam on the same strokes

`GlideRealSwipeEval` in `:app`'s tests runs the built-in decoder over the
same files: `WM_FUTO_DIR=<folder with qwerty.json and the jsonl files>
./gradlew :app:testIntlDebugUnitTest --tests "*GlideRealSwipeEval"`. Measured
2026-10-22 on the first 8,000 test strokes over the bundled en.txt, before and
after #886 (the doubling charge cut to 0.05 + 0.1, loops cut out of the shape
channel, the stroke's ends open past the gap band):

| | before | after |
|---|---|---|
| all strokes (6,052) | .7740 | .7791 |
| short words with a doubled letter (81) | .1975 | .6543 |
| doubled key redrawn with a loop (482) | .7552 | .8154 |
| stray loop on a word with no double (4,385) | .8041 | .7977 |

## GlideBeam on whole phrases

A stroke across the spacebar, on real strokes joined into the sentences they
were drawn for: consecutive words by one person on one canvas, four to a phrase,
with walks that curve toward the bar's real extent (`PHRASES=1`; the walks are
a model of a finger, the strokes between them are not). From `native/wm-engine`,
with `test.jsonl` and `qwerty.json` as above and the n-gram pack and context
model from the data repo:

```bash
ONE_LETTER=1 PHRASES=1 RESULTS=8 DUMP=dump.tsv cargo run --release --example glide_beam_eval -- test.jsonl qwerty.json ../../app/dictionaries-src/en.txt < /dev/null
```

```bash
python -I ../bench/futo-swipe/fit_phrase.py dump.tsv bigrams.txt trigrams.txt target/release/examples/phrase_lm_eval en_context.wmlm
```

Measured 2026-10-30 (32,285 words; held-out half of the sessions for the last three rows):

| | words | 2 words | 3 words | 4 words |
|---|---|---|---|---|
| walks read before, one-letter words excluded | .552 | .388 | .166 | .075 |
| `open_run` follows diagonal walks, one-letter words excluded | .786 | .675 | .527 | .429 |
| one-letter words read too | .784 | .667 | .523 | .423 |
| each word's leader, held out | .770 | .646 | .496 | .405 |
| n-gram lattice, held out | .827 | .733 | .605 | .531 |
| lattice and context model, held out | .850 | .765 | .640 | .577 |

`ORACLE=1` hands the decoder the walks' true lengths (.792 without one-letter
words). `WALK=straight` is the column walk `CHAIN=1` uses, which flatters the
decoder: .814 on the old `open_run`, where curved walks read .552.
