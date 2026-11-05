---
license: cc-by-4.0
pipeline_tag: automatic-speech-recognition
language: [en, de, fr, es, it, pt, ru, uk, hr, sl, lv, lt, et, fi, sv, da, nl, pl, cs, sk, hu, ro, bg, el, mt]
tags: [executorch, xnnpack, asr, parakeet, tdt]
base_model: moondream/parakeet-ultra
---

# Parakeet Ultra, ExecuTorch export

[Moondream's Parakeet Ultra](https://huggingface.co/moondream/parakeet-ultra), a post-trained
[parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), exported for
[ExecuTorch](https://github.com/pytorch/executorch) v1.5.1 with the XNNPACK backend. Offline voice
typing in [WM Keyboard](https://github.com/wasi-master/wmkeyboard) downloads it.

| Path | What |
|---|---|
| `xnnpack/int4/model.pte` | 8-bit dynamic activations, 4-bit weights in groups of 32 (8da4w) |
| `xnnpack/int4/tokenizer.model` | SentencePiece tokenizer, unchanged from parakeet-tdt-0.6b-v3 |

The program has the methods `preprocessor`, `encoder`, `decoder_step` and `joint`, the same as
ExecuTorch's own parakeet-tdt-0.6b-v3 export, so its Parakeet runner runs this file unchanged.

## How it was made

Moondream publish Ultra as Hugging Face transformers weights. Their tensors were renamed to
NeMo's names with transformers' own NeMo conversion mapping, run in reverse, and loaded into
parakeet-tdt-0.6b-v3's NeMo model. The mapping was first checked on v3 itself: every NeMo
weight equals the tensor of that name in NVIDIA's transformers conversion. Ultra's
voice-activity head has no place in the NeMo model and was left out. The model was then
quantized and exported with ExecuTorch's `examples/models/parakeet/export_parakeet_tdt.py` at
v1.5.1. `manifest.json` holds the pinned sources, file hashes and test transcripts.
The script is
[`tools/parakeet-ultra/export_ultra.py`](https://github.com/wasi-master/wmkeyboard/blob/main/tools/parakeet-ultra/export_ultra.py).

## License

CC-BY-4.0, as the original. Parakeet Ultra is by Moondream, built on NVIDIA's
parakeet-tdt-0.6b-v3. Changes: converted to NeMo's layout, quantized and exported to ExecuTorch.
