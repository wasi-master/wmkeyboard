# Smart punctuation model (`.wmpt`) training

The model behind the smart punctuation key: a small bidirectional
transformer encoder (about 4M parameters, int8, 3 to 5 MB) that reads the
sentence before the caret on every keystroke and says which mark it is
heading for: a full stop, a question mark, an exclamation mark, or a comma
(a clause the writer goes on from). One model covers every language it was
trained on and tells them apart from the words. `wm-engine/src/punct.rs` runs
it on the phone; `PunctuationModel.kt` is the Kotlin side;
`PunctuationModelCatalog` pins the published file.

What the key does with the answer is in
`docs/src/content/docs/smart/autocorrect.mdx` under "The smart punctuation
key".

## Files

| | |
|---|---|
| `punct_common.py` | the classes, the sentence filter (`end_label`, `comma_prefixes`), and the tokenisation shared with the context model (`../wmlm/common.py`: lower-case, trim punctuation, `<num>`, subword pieces) |
| `corpus.py` | examples out of Tatoeba, OPUS OpenSubtitles, Leipzig or plain lines: `lang<TAB>label<TAB>sentence without its mark` |
| `prep.py` | the vocabulary (word slots shared out by language, then pieces) and the token arrays |
| `punct_model.py`, `train.py` | the encoder and its training loop (CUDA, MPS or CPU); best checkpoint by held-out accuracy over the three end marks |
| `export.py` | a checkpoint as a `.wmpt`, with a `.sha256` sidecar |
| `check.py` | PyTorch's answer for a sentence, to compare with `cargo run --example punct_probe` |
| `remote/` | the whole job on a rented CUDA box: `languages.tsv` (which corpora per language), `fetch.sh`, `setup.sh`, `run.sh` |

## Recipe

Needs a Python with torch and numpy, and `../wmlm/common.py` beside this
folder. Corpora go in their own folder (they are untrusted downloads).

```bash
python corpus.py en tatoeba:raw/eng_sentences.tsv.bz2 opus:raw/opus_en.txt > examples.tsv
python corpus.py de tatoeba:raw/deu_sentences.tsv.bz2 opus:raw/opus_de.txt >> examples.tsv
python prep.py examples.tsv data/punct vocab=32768 pieces=8192 ctx=48
python train.py data/punct out steps=40000 batch=512 e=64 d=192 layers=4 ff=512
python export.py out/best.pt data/punct smart_punct.wmpt
python check.py out/best.pt data/punct "how are you" "if you want"
```

`remote/fetch.sh` does the first two lines for every row of
`remote/languages.tsv`: Tatoeba's per-language export whole, and the OPUS
2018 OpenSubtitles mono file streamed and cut at the row's line cap, so the
3.6 GB English file costs a few hundred MB. Greek text has its `;` read as
the question mark it is. Subtitle lines with speaker dashes, bracketed sound
cues, `♪` or shouting in capitals are skipped.

A sentence becomes an example when it ends in one mark (a run of marks
counts as its first; `...` is a full stop), holds no other sentence end,
and is 2 to 40 words. With probability 0.35 a sentence with a comma in it
also yields one of its comma prefixes as a clause-break example. The held-out
set is drawn after a dedupe on the lower-cased text.

`train.py`'s `drop_tail=0.1` takes the last word or two off a tenth of the
rows, so the model also has an opinion while the last word is being typed;
the keyboard asks on every keystroke. Label smoothing 0.05. The model is
judged by `acc3`: accuracy over the three end marks with the comma class
folded away, which is what the key shows by default.

## On a rented GPU

```bash
tar -czf wmpunct_bundle.tar.gz -C tools wmpunct wmlm/common.py
# there, in the unpacked wmpunct/ directory
bash remote/setup.sh
STEPS=40000 bash remote/run.sh
# bring back out/smart_punct.wmpt and out/best.pt
```

`run.sh` reads LANGS, VOCAB, PIECES, CTX, STEPS, BATCH, E/D/LAYERS/HEADS/FF
and OUT from the environment; a sweep is the same script with another OUT.
An RTX 4090-class card does 40k steps at batch 512 in well under an hour
once the corpora are in; fetching them is the longer half.

## Results (2026-11-09, RTX 5080, 41.4M examples, 58 languages)

| run | vocab | steps | acc over `. ? !` | file |
|---|---|---|---|---|
| e64 d192 L4 ff512 | 32,768 | 40k | .869 | 3.7 MB |
| e96 d256 L6 ff768 | 32,768 | 40k | .868 | |
| e64 d192 L4, `exclaim_weight=2` | 32,768 | 40k | .867 (`!` recall .07) | |
| **e64 d192 L4 (v2 Standard)** | 49,152 | 60k | **.872** (en .937) | 5.3 MB |
| **e96 d256 L6 (v2 Large)** | 49,152 | 100k | **.875** (en .939) | 9.5 MB |
| e64 d192 L4 + `lang_token=1` (v3 Standard) | 49,152 + 59 | 60k | .874 (en .937, hu .82, es .88) | 5.3 MB |
| e96 d256 L6 + `lang_token=1` (v3 Large) | 49,152 + 59 | 100k | .874 | 9.5 MB |

Every language, every variant and the per-mark precision and recall where they were measured: [RESULTS.md](RESULTS.md).

Size barely matters and the language token is worth a quarter of a point
(a point or two in Hungarian and Spanish); the misses are exclamations (5.8% of ends, recall
.02) and questions asked in statement order (`?` recall .86 in English,
.55–.66 in French, Spanish, Hungarian, Persian). Short subtitle lines
(≤3 words) score .81 in English against .95 for 8+ words.

## Publishing

Copy `smart_punct.wmpt` to the data repo as `models/punct/smart_punct_v<n>.wmpt`
(a new model is a new name, never a replaced file), and set
`PunctuationModelCatalog`'s `PATH`, `BYTES`, `SHA256` and `languages` from
`export.py`'s output and `data/punct/langs.txt`.
