# qnn-context: the upscaler's networks for the Snapdragon NPU

`build.sh` compiles waifu2x's UpConv7 networks, the two the upscaler already
runs on the CPU through MNN, into QNN context binaries for the Hexagon NPU of
each Snapdragon in `NpuSupport.HTP_ARCH` (SM8550, SM8650, SM8750, SM8850). The
phone runs them through `libwmimagegen_qnn.so` (`native/imagegen-qnn`), the
same runner as the sticker generator's Stable Diffusion, as
`UpscaleEngine`'s NPU path. `UpscaleModels.NPU_BUILDS` pins them.

## Why not Qualcomm AI Hub's builds

AI Hub publishes per-chip context binaries only for some of its models (Stable
Diffusion, Whisper, ControlNet, SAM 3 and others; 27 of the 267 models in the
`release-assets.yaml` files of quic/ai-hub-models on 2026-11-26, release
0.67.0). The one super-resolution model among them, NAFSSR, is stereo (two
views in). Every single-picture upscaler it carries (Real-ESRGAN x4plus and
General x4v3, ESRGAN, XLSR, QuickSRNet, SESR, SRGAN), its segmenters (U2Net, MediaPipe
Selfie, SINet, DeepLab) and its OCR models (EasyOCR, TrOCR) come only as
chip-independent TFLite, ONNX and QNN DLC files, which a phone would have to
compile itself with `libQnnHtpPrepare.so` (about 90 MB). Their source licences
differ per model: Real-ESRGAN BSD-3-Clause, U2Net Apache-2.0, XLSR and
QuickSRNet the AIMET model zoo's licence.

Real-ESRGAN General x4v3, the obvious candidate, also measured worse than
UpConv7 on photos (2026-11-26, AI Hub's float ONNX against the app's photo
network, six photos shrunk by 2 and 4 and enlarged back, PSNR against the
original):

| | ×2 | ×4 |
|---|---|---|
| bicubic | 25.7 to 31.3 dB | 22.7 to 26.0 dB |
| waifu2x UpConv7 photo (the app's) | 26.2 to 34.5 dB | 24.5 to 28.4 dB |
| Real-ESRGAN General x4v3 | 25.0 to 29.2 dB | 22.8 to 25.5 dB |

UpConv7 was ahead on every photo at both factors, and Real-ESRGAN was under
plain bicubic on most. So the NPU runs the same networks as the CPU, and an
NPU upscale looks like a CPU one.

## What gets made

`export_upconv7.py` writes each network as ONNX with nagadomi's weights (the
commit and SHA-256 the data repository's `export_upscalers_mnn.py` pins),
fixed at one tile: `[1, 142, 142, 3]` RGB 0 to 1 in, channels last, and
`[1, 256, 256, 3]` out (a 128-pixel tile plus the 7 pixels of context the
network eats on each side). It checks that float16 throughout stays within
half of one 8-bit step of float32 (0.41 of 255 for art, 0.37 for photo), and
with `--check` that the graph matches the app's `.mnn` files (0.24 and 0.20 of
255).

`build.sh` then converts each ONNX graph to a float16 DLC with
`qairt-converter` and compiles it once per chip with
`qnn-context-binary-generator` (O3, 8 MB VTCM), reads every binary back with
`qnn-context-binary-utility`, and writes `pins.txt` with sizes, SHA-256 and the
Kotlin lines for `UpscaleModels.NPU_BUILDS`.

## Running it

QAIRT's converter and HTP compiler run on Linux x86-64 only, and the SDK is
Qualcomm's, under its "AI Stack" licence, which whoever downloads it accepts.
So nothing here fetches it on its own: run the `Upscaler NPU builds` workflow
(`.github/workflows/qnn-context.yml`) by hand, or on a Linux machine

```sh
QAIRT_SDK_ROOT=/path/to/qairt/2.51.0.260929 tools/qnn-context/build.sh out
```

QAIRT must be 2.51, the release of the app's QNN runtime (`qnnRuntime` in
`gradle/libs.versions.toml`). Then upload `out/*.bin` to the data repository's
`models` release as `<sha256>-<name>` and paste the Kotlin lines into
`UpscaleModels.NPU_BUILDS`.

Nothing here runs the NPU. The first run on a phone is the check: a build that
does not load, has the wrong shape or fails a tile is remembered per phone and
system build, and the CPU takes that picture and every one after it until the
next app or system update.
