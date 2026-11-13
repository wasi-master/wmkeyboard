# rnnoise-jni: noise reduction in front of the offline engines

[RNNoise](https://github.com/xiph/rnnoise) takes steady background noise out
of the microphone's audio before an on-device dictation engine hears it
(Settings › Voice typing › Reduce background noise). Kotlin entry point:
`com.wasimaster.wmkeyboard.core.voice.VoiceDenoise`, used by `WhisperRecorder`.

RNNoise works on 10 ms frames at 48 kHz; the keyboard records at 16 kHz.
`denoise_jni.c` raises each 160-sample block to 480 samples with a polyphase
windowed-sinc interpolator (48 taps, Blackman window, 7.2 kHz cut-off), runs
RNNoise, and brings the result back down through the same low-pass filter.
The output trails the input by under 10 ms. Speech models never look above
8 kHz, so nothing they use is lost.

The built libraries (about 2.9 MB each, nearly all of it the model's
weights) are committed under
`core/voice/src/main/jniLibs/`, beside whisper.cpp's, and ride the same
`speech` gate (`:feature:speech` on Play). Rebuild after you change anything
here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/rnnoise-jni/build.sh
```

It needs the NDK (r29, the one F-Droid builds with), git and curl. It
fetches RNNoise at the pinned commit unless `RNNOISE_SRC` points at a
checkout, and the model tarball (22 MB, checked against its SHA-256) unless
`RNNOISE_MODEL_TARBALL` points at a copy.

## Provenance

RNNoise commit `372f7b4b76cde4ca1ec4605353dd17898a99de38`, unmodified,
BSD-3-Clause. That is the 0.2 release (`v0.2`, `904a876d`) plus its only
child, upstream's "Fix compilation errors": the tag itself does not compile
for ARM (`os_support.h` and `OPUS_CLEAR` are missing). The weights are
not in the repository; `model_version` at the pin names `0b50c45`, fetched
from `https://media.xiph.org/rnnoise/models/rnnoise_data-0b50c45.tar.gz`
(SHA-256 `4ac81c5c0884ec4bd5907026aaae16209b7b76cd9d7f71af582094a2f98f4b43`).
The tarball holds two models behind one header; the build compiles in the
little one (`rnnoise_data_little.c`, 2.9 MB of library). The default one would
make the library 5.6 MB. 0.1.1's model was about 90 KB, the library 126 KB.

Checked on the host (2026-10-28) with a 220 Hz + 660 Hz tone, amplitude
modulated at 4 Hz, plus white noise at -31 dBFS, through `denoise_jni.c`'s
resampling:

| | noise-only stretch, RMS (in 0.0279) | tone kept | CPU per 10 ms, M2 |
|---|---|---|---|
| 0.1.1 | 0.00063 | 96% | 0.12 ms |
| 0.2, little model (shipped) | under 0.00001 | 95% | 0.13 ms |
| 0.2, default model | under 0.00001 | 92% | |

Word error rate, rerun 2026-10-28 with the method in
`native/sherpa-onnx-jni/README.md` § Choosing the Strong model (same 25
LibriSpeech clips and DEMAND recordings, a new synthetic fan, so the raw row
is lower than there), both RNNoise builds through `denoise_jni.c`'s
resampling in 100 ms chunks:

| Denoiser | Whisper Tiny (en) | Sherpa zipformer-en-20M |
|---|---|---|
| none | 23.3% | 28.6% |
| RNNoise 0.1.1 | 28.9% | 36.8% |
| RNNoise 0.2, little model (Light now) | 28.8% | 34.7% |

On the 25 clean clips alone: Whisper 8.2%, 9.0%, 8.4%; zipformer 19.6%,
20.2%, 22.0%.

0.2 is no help to Whisper over 0.1.1 and a little to the zipformer on noisy
audio; either still costs words against no denoiser at all.

## Contract with Kotlin

- `nativeCreate() -> Long`: a denoiser, 0 on failure.
- `nativeProcess(handle, samples, count) -> FloatArray`: every whole block
  the samples complete, denoised; up to 159 samples wait for the next call.
- `nativeFree(handle)`.

Not thread-safe; the recorder's reader thread owns it.
