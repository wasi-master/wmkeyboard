# npu-speech: Parakeet and Moonshine for the phone NPUs

The app ships two ExecuTorch builds that carry an NPU delegate beside XNNPACK
(`native/executorch-jni/build.sh`):

| Library | Delegate | Chips | Gate |
|---|---|---|---|
| `libwmexecutorch_qnn.so` | Qualcomm QNN | SM8550, SM8650, SM8750, SM8850 | `executorchqnn`, plus the `npu` gate's QNN runtime |
| `libwmexecutorch_neuron.so` | MediaTek NeuroPilot | MT6989 (Dimensity 9300), MT6991 (Dimensity 9400) | `executorchneuron`; the phone's own NeuroPilot adapter |

`export_npu.py` makes the programs that run on them. As of 2026-11-26 nobody
publishes QNN or NeuroPilot `.pte` exports of Parakeet TDT 0.6B v3 or
Moonshine Tiny (Hugging Face searched for parakeet, moonshine, executorch,
qnn, neuron, mediatek; the QNN Parakeets there are sherpa-onnx context
binaries, which the app already offers on Snapdragon through sherpa-onnx).

## What gets made

An NPU wants fixed shapes, so only the encoder goes to it, compiled for one
clip length. The phone pads a shorter clip with silence, and cuts a longer one
at its quietest moments (`QnnSpeech.cuts`), as it does for sherpa-onnx's QNN
builds.

| `--model` | File | Where it runs |
|---|---|---|
| `parakeet-aux` | `parakeet-tdt-0.6b-v3-aux-xnnpack.pte` (+ `tokenizer.model`) | CPU: preprocessor, `decoder_step`, `joint` and the constants, 8da4w as the CPU build. One file for every chip. |
| `parakeet` | `parakeet-tdt-0.6b-v3-encoder-<delegate>-<soc>-<s>s.pte` | NPU: the encoder and the joint's encoder projection, mel `[1, 128, s*100+1]` in, `[1, T, 640]` out. 16-bit activations, 8-bit weights by default (calibrated on four test clips and their concatenation). |
| `moonshine` | `moonshine-tiny-encoder-<delegate>-<soc>-<s>s.pte` | NPU: the encoder, audio `[1, s*16000]` in. fp16 by default. The phone pairs it with Software Mansion's XNNPACK decoder and tokenizer, the files the CPU build already uses. |

Each run writes `manifest-<tag>.json`: bytes and SHA-256, the operators left to
the CPU per method, and test transcripts (eager on the clip as it is, eager on
the padded clip, and with the quantization simulated). For Moonshine it also
checks that the transformers encoder matches Software Mansion's exported one,
since their decoder was exported against it. A program calling an operator
missing from `native/executorch-jni/ops.txt` fails the run: add the operator
and rebuild the libraries, or the phone cannot load it.

The NPU's own output is not checked here (the pip runtime has no QNN or
NeuroPilot kernels for the host). The first run on a phone is the check; the
app remembers a program that fails to load or run, per phone and system
build, and uses the CPU build from then on.

## Running it

Both delegates need **Linux x86_64**. Neither SDK has a macOS or arm64 build of
its compiler.

- QNN: Qualcomm's public QAIRT SDK. ExecuTorch fetches 2.37.0.250724 on its own
  when `QNN_SDK_ROOT` is unset. 2.37 has no Hexagon V81, so SM8850 needs 2.40
  or later (the workflow takes 2.40.0.251030, the release sherpa-onnx built its
  SM8850 binaries with). The app's QNN delegate is compiled against 2.37's
  headers and the phone runs the `npu` gate's 2.51 runtime; a context binary
  from an older SDK loads on a newer runtime.
- NeuroPilot: Python 3.10 and MediaTek's `mtk_converter` and `mtk_neuron`
  wheels (8.13.0 and 8.2.23 for ExecuTorch 1.x), from the NeuroPilot Express
  SDK at neuropilot.mediatek.com. The portal needs a sign-in, so they cannot be
  fetched by script; the workflow reads a zip of them from the
  `NEUROPILOT_WHEELS_URL` secret (with `NEUROPILOT_WHEELS_TOKEN` when the URL
  needs one).

The workflow `.github/workflows/npu-speech.yml` (Actions › NPU speech export)
does all of it: pick the model, delegate, chips and clip lengths, and each
chip and length becomes a job. By hand:

```sh
pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
pip install executorch==1.5.1 "nemo_toolkit[asr]==2.7.3" soundfile scipy huggingface_hub   # Parakeet
pip install executorch==1.5.1 transformers==4.57.6 soundfile scipy huggingface_hub          # Moonshine
curl -fsSLo /tmp/export_parakeet_tdt.py https://raw.githubusercontent.com/pytorch/executorch/3b60683923245cf472b7323426920e15623ba361/examples/models/parakeet/export_parakeet_tdt.py

python tools/npu-speech/export_npu.py --model parakeet-aux --export-script /tmp/export_parakeet_tdt.py
python tools/npu-speech/export_npu.py --model parakeet --delegate neuron --soc MT6989 --seconds 30 --export-script /tmp/export_parakeet_tdt.py
python tools/npu-speech/export_npu.py --model moonshine --delegate qnn --soc SM8750 --seconds 10
```

## Publishing

1. Upload each `.pte` to the data repository's `models` release as
   `<sha256>-<name>` (the name the app builds its URL from; `scripts/mirror-releases-to-r2.sh`
   then copies it to the mirror).
2. Fill its `bytes` and `sha256` in `EtNpuCatalog.EXPORTS`
   (`core/voice/.../executorch/EtNpuModels.kt`). An entry with either missing
   is in `PENDING_EXPORTS` and hidden from the model list; a Parakeet encoder
   is also hidden until the shared aux program is filled.

## Licences

Parakeet TDT 0.6B v3 is NVIDIA's, CC-BY-4.0. Moonshine Tiny is Useful
Sensors', MIT. Both are credited in the app's About screen. The QNN context
binaries inside a `.pte` are compiled by Qualcomm's SDK and run only through
Qualcomm's proprietary runtime, which is why the F-Droid build never offers
them; the NeuroPilot ones run through the phone's own MediaTek libraries.

Asr4All has no NPU export: FUTO publish its ExecuTorch programs and a runner
but not the export code, and its weights are under their own restricted
licence, so rebuilding its streaming programs from `modeling_asr4all.py` was
left alone.
