# Speech model exports

Converts open speech models that the app's dictation engines cannot read as
they are published. `.github/workflows/speech-exports.yml` runs these by
hand, one job per model, and pushes each result to a Hugging Face repository
of its own (`<owner>/<source name>-sherpa-onnx`). `EtModels.kt` then pins
that repository at the revision the run printed, with the sizes and SHA-256
from the run's `manifest.json`.

| Family | Source | Script | Engine |
|---|---|---|---|
| `addis` | Addis AI's Nemotron 3.5 fine-tunes for Tigrinya, Afaan Oromo and Swahili (`addisai/addis-scribe-*-streaming`, OpenMDW-1.1), published as `.nemo` only | `export_addis.py` | Offline Sherpa |
| `waxal` | Omnilingual ASR CTC 300M fine-tuned on one WAXAL language each (`waxal-benchmarking/omniasr-ctc-300m-waxal-<iso3>`, CC-BY-4.0), fairseq2 checkpoints | `export_waxal.py` | Offline Omnilingual |

Addis AI's Amharic model needs none of this: its repository carries a
sherpa-onnx export already, which the app pins as it is.

## What each script does

`export_addis.py` runs sherpa-onnx's own Nemotron 3.5 exporter, at the commit
the app's bridge is built from, on the fine-tune instead of NVIDIA's base
model. The one change is the prompt dictionary: it holds only `auto`, set to
the prompt the model was trained under (49, the `am-ET` slot, for Tigrinya;
59 for Oromo; 48 for Swahili). sherpa-onnx then uses that prompt for every
stream, so the app tells the model nothing. Addis AI's own Amharic export
does the same.

`export_waxal.py` exports the way sherpa-onnx exports the base Omnilingual
models (raw waveform in, CTC logits out, opset 14, dynamic int8 on MatMul),
after loading the fine-tune with fairseq2's `load_custom_model`. The
tokenizer is the base model's (`omniASR_tokenizer_v1`). It refuses a source
whose licence is not an open one.

`smoke.py` speaks a sentence with Meta's MMS-TTS voice for the language
(used in the run only, never shipped) and decodes it with sherpa-onnx's
Python package, so a model that loads but writes nothing fails its job. The
transcript goes into `manifest.json`.

## Running

Actions → Speech model exports → Run workflow. `family` picks the table row,
`only` narrows it (`tigrinya`, or `tir,amh`), and `hf_owner` is where to
push; leave it empty for artifacts only. The push needs an `HF_TOKEN` secret
with write access.
