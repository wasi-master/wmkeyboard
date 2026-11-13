# sherpa-onnx-jni: Offline Sherpa on ONNX Runtime

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) runs Offline Sherpa's
streaming Zipformer transducers, Silero and TEN VAD and the English online
punctuation model. Kotlin entry points: `SherpaNative`, `SherpaEngine` and
`OmnilingualEngine` in
`core/voice/src/main/java/com/wasimaster/wmkeyboard/core/voice/sherpa/`.

Offline Omnilingual's model (Meta's Omnilingual ASR 300M CTC) runs on ONNX
Runtime's C++ API directly (`omnilingual_jni.cpp`), with sherpa-onnx's Silero
cutting the audio into phrases. sherpa-onnx's offline recognizer decodes the
same model, but the model has no language conditioning and spells in
whichever script it thinks it hears (macOS's Hindi voice came back in Urdu
letters), and the recognizer offers no way to steer that. The bridge follows
sherpa-onnx's preprocessing (the raw waveform, normalised to zero mean and
unit variance) and does the greedy CTC pick itself, over the tokens
`OmnilingualScriptMask` flags for the dictation's scripts.

The same library reads text aloud with Meta's MMS-TTS voices
(`vits_jni.cpp`, driven by `core.voice.speech.MmsSpeech`). That part is not
sherpa-onnx: it runs the VITS network through ONNX Runtime's C++ API. An MMS
voice reads characters, so its whole text frontend (lower-casing, one id per
character, the blank between them) is a few lines of Kotlin.

Kokoro voices go through sherpa-onnx's own offline TTS (`kokoro_jni.cpp`,
driven by `core.voice.speech.KokoroSpeech`). Since 2.0 sherpa-onnx phonemises
from the lexicon files a model ships (English and Chinese for Kokoro v1.0)
and no longer links espeak-ng, so turning its TTS on brings no GPL code into
this library. A word missing from the lexicon is skipped; `KokoroText` spells
out English numbers before the text gets here.

Add punctuation and capitals runs here too (`tagger_jni.cpp`, driven by
`core.voice.punctuation.DictationPunctuation`): a generic token classifier on
ONNX Runtime's C++ API, used for Horizon Labs' punctuation-restoration-small
(mmBERT-small, Apache-2.0), which puts full stops, commas and question marks
into the bare words Sherpa and Omnilingual write. See
[Choosing the punctuation model](#choosing-the-punctuation-model).

Reduce background noise at Strong runs here too (`dpdfnet_jni.cpp`, driven by
`core.voice.StrongDenoise`): sherpa-onnx's streaming speech denoiser over Ceva's
DPDFNet baseline model (DeepFilterNet2 with dual-path RNN blocks, Apache-2.0,
16 kHz), with 12 dB of the microphone mixed back under the cleaned audio. See
[Choosing the Strong model](#choosing-the-strong-model) below for why that model
and that mix.

A second library, `libwmsherpa_qnn.so` (`qnn_jni.cpp`, arm64 only, its own
gate `sherpaqnn`), is sherpa-onnx configured again with
`SHERPA_ONNX_ENABLE_QNN`, for the NPU builds of Parakeet TDT 0.6B v3 and
Moonshine Tiny: k2-fsa's QNN context binaries from the `asr-models-qnn-binary-2`
and `-3` releases, one set per Snapdragon chip and fixed clip length, run by
sherpa-onnx's offline recognizer (`provider=qnn`). The C API has no QNN
fields, so this bridge uses the C++ API. It compiles against QAIRT
2.40.0.251030's `include/QNN` (the SDK k2-fsa compiled the binaries with,
fetched by `../executorch-jni/qnn_headers.py`) and opens Qualcomm's
`libQnnHtp.so` and `libQnnSystem.so` by path from the `npu` gate's runtime
directory at run time; `ADSP_LIBRARY_PATH` points the DSP at the chip's skel
there. Kotlin side: `SherpaQnnNative` and `QnnSpeech`. Built by `build.sh`
unless `WMKB_SHERPA_QNN=0`; `WMKB_SHERPA_PLAIN=0 WMKB_ABIS=arm64-v8a` builds
only it.

The bridge is a thin JNI layer over sherpa-onnx's C API, which is linked in
statically with only speech recognition switched on (no TTS, no diarization,
no audio I/O, no servers). ONNX Runtime is not linked in. `libwmsherpa.so`
names `libonnxruntime.so` as a dependency and calls one symbol of it,
`OrtGetApiBase`; everything else goes through the table that function
returns. At build time the linker sees a stub (`ort_stub.c`, built per ABI
with the soname `libonnxruntime.so`) and the runtime's headers, cloned at the
pinned tag. At run time `SherpaNative` loads Microsoft's own
`libonnxruntime.so` first and this library second, and the dynamic linker
binds the dependency by soname to the copy already loaded. So:

- no ONNX Runtime binary is in the repository or the build, and F-Droid can
  build this library from source;
- the runtime on the phone is Microsoft's release from Maven Central
  (`com.microsoft.onnxruntime:onnxruntime-android`, MIT), either packaged by the
  `wmkeyboard.onnxruntime` convention or, with native delivery, read entry by
  entry out of that AAR on the phone;
- the stub's symbol is unversioned, so the reference binds to whichever
  `VERS_<version>` the loaded runtime exports. The headers' API version must
  not be newer than the runtime's: keep `ORT_TAG` in `build.sh` and
  `onnxruntime` in `gradle/libs.versions.toml` on the same release.

The built libraries are committed under `core/voice/src/onnx/jniLibs/`, apart
from `src/main/jniLibs` because they ride their own delivery gate, `onnx`
(`:feature:onnx` on Play). Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/sherpa-onnx-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches sherpa-onnx and the ONNX
Runtime headers at their pinned commits unless `SHERPA_SRC` / `ORT_SRC` point
at checkouts. sherpa-onnx's own CMake fetches its dependencies. One ABI takes
about four minutes.

## Provenance

- sherpa-onnx `v2.0.1`, commit `af2d8d81dbfa61c4cea4c568da78f3982edca005`,
  unmodified, Apache-2.0.
- ONNX Runtime headers `v1.31.1`, commit
  `6af1a932e2286d5c58166085d24c68783033edee`, MIT.

The models are not bundled. `EtCatalog` in `:core:voice` pins each one to a
Hugging Face revision and every file to its SHA-256, the same store and
downloader as the ExecuTorch engines. The denoiser's model is sherpa-onnx's own
conversion (`speech-enhancement-models` release, `dpdfnet_baseline.onnx`,
8,791,035 bytes), pinned by size and SHA-256 in `StrongDenoise`. The phrase
end detectors' models are pinned in `core.voice.vad.VadModels`: FireRedVAD's
streaming export and `cmvn.ark` from FireRedTeam/FireRedVAD's own repository
(commit `c30ec49e8cc69642b0ee65362eba11b9d11c6e54`, Apache-2.0), Smart Turn
v3.2 (`smart-turn-v3.2-cpu.onnx`, pipecat-ai/smart-turn-v3 revision
`f766f81d3cfdf7737ac64aad813d91bbfd56bf93`, BSD-2-Clause) and a Silero copy
of its own for engines whose models carry none. TEN VAD is not offered as a
download: its licence adds conditions to Apache-2.0 (no use that competes
with Agora's offerings), so a user who wants it imports sherpa-onnx's release
of it themselves.

## ABIs

| ABI | Size |
|---|---|
| arm64-v8a | about 2.4 MB |
| armeabi-v7a | about 1.6 MB |

Both are 16 KB aligned (`-z max-page-size=16384`), as is Microsoft's
runtime from 1.30.0.

## Contract with Kotlin

Handles are `Long`s, 0 on failure; text crosses as UTF-8 byte arrays.

- `nativeRecognizer(encoder, decoder, joiner, tokens, threads, trailingSilence)`:
  a streaming transducer, greedy search, 16 kHz, 80 mel bins. Its own
  endpoint fires after `trailingSilence` seconds of quiet following words;
  the no-words rule is pushed out of reach.
- `nativeHotwordRecognizer(..., trailingSilence, modelingUnit, bpeVocab,
  hotwordsScore)`: the same by modified beam search (4 paths), the only search
  that follows hotwords. `modelingUnit` is `bpe` or `cjkchar+bpe` and nothing
  else, since sherpa-onnx exits the process on any other; hotwords are cut into
  pieces over `bpeVocab` (`piece score` a line, written by `SherpaHotwords`
  from the model's `tokens.txt`), each matching piece worth `hotwordsScore`.
- `nativeStream`, `nativeHotwordStream(recognizer, hotwords)` (UTF-8, one
  phrase a line, already spelled for the model by `SherpaHotwords`),
  `nativeFeed` (decodes every frame the samples complete),
  `nativeText`, `nativeEndpoint`, `nativeFinish` (a short silent tail, input
  finished, decode the rest), `nativeStreamFree`.
- `nativeVad(model, threshold, minSilence, minSpeech)`, `nativeVadFeed`
  (returns whether speech is going on; feeds Silero whole 512-sample windows
  and drops finished segments), `nativeVadReset`, `nativeVadFree`.
- `nativeVadOf(kind, model, threshold, minSilence, minSpeech, maxSpeech,
  keep)`: the same for `kind` 0 (Silero, 512-sample windows) or 1 (TEN, 256),
  keeping its segments for `nativeVadSegment` when `keep` is true. Built after
  the bridge above, so Kotlin falls back to `nativeVad` / `nativeSegmenter`
  when an older library throws `UnsatisfiedLinkError`.
- `nativeVadProbe(model)`: 0 when the file is a Silero model sherpa-onnx will
  take (k2-fsa's v4 export, v4 as published, v5), 1 for TEN with sherpa-onnx's
  metadata (`model_type` `ten-vad`, `mean` and `inv_stddev` of 41, `window` of
  768), -1 otherwise. It makes exactly the checks after which sherpa-onnx
  v2.0.1 (unchanged from v1.13.8) would exit the process, on a plain ONNX Runtime session, so an
  imported file is tried here first and never reaches sherpa-onnx unless it
  passes.
- `nativeFireRed(model, mean, scale, threads)`: FireRedVAD's streaming model
  (upstream's `fireredvad_stream_vad_with_cache.onnx`: `feat` (1, T, 80) and
  `caches_in` (8, 1, 128, 19) in, `probs` and `caches_out` out), with the
  CMVN means and inverse standard deviations Kotlin reads from `cmvn.ark`.
  `nativeFireRedFeed(handle, samples, count)` scales to 16 bits, computes
  kaldi-native-fbank filterbanks (80 bands, 25 ms every 10 ms, no dither,
  edges snipped, as upstream's `AudioFeat`), runs every frame the samples
  complete with the caches carried, and returns one speech probability per
  10 ms frame. Fed in pieces it gives the probabilities of the whole clip at
  once to the bit. `nativeFireRedReset`, `nativeFireRedFree`.
- `nativeSmartTurn(model, threads)`: Smart Turn v3, 0 on failure or when the
  input is not (n, 80, 800). `nativeSmartTurnRun(handle, samples, count)`:
  the chance, 0 to 1, that the speaker of the last 8 s of `samples` has
  finished, -1 on failure. The features are computed here, following
  pipecat's `_whisper_features.py` (zeros in front of less than 8 s, waveform
  normalised, centred STFT with a periodic Hann of 400, hop 160, 80 Slaney
  mel filters, log10 clamped 8 decades below the peak, (x + 4) / 4); on
  synthetic and spoken clips they match it within 7e-6, and the model's
  answer to five decimals. `nativeSmartTurnFree`.
- `nativePunctuation(model, vocab)`, `nativePunctuate`, `nativePunctuationFree`.
- `nativeVits(model, threads)`, `nativeVitsMeta(handle, key)` (a custom
  metadata entry such as `sample_rate` or `add_blank`, empty when absent),
  `nativeVitsRun(handle, ids, noiseScale, lengthScale, noiseScaleW)` (one
  sentence's samples, null on failure; inputs matched by name, a `sid` input
  fed 0), `nativeVitsFree`.

- `nativeTagger(model, threads)`: a token classifier, 0 on failure or when the
  model takes an input other than `input_ids` and `attention_mask`.
  `nativeTag(handle, ids, count)` runs the first `count` ids as one (1, n)
  int64 row, an all-ones mask beside it when the model has one, and returns
  the argmax of the first output's (1, n, labels) logits for each id, specials
  included; null when the run fails. `nativeTaggerFree`. Tokenising and
  reading the labels back onto the words is Kotlin's (`PunctuationText`).

- `nativeDenoiser(model, threads, delay, keep)`: a streaming DPDFNet denoiser,
  0 on failure (or a model whose rate is not 16 kHz). `nativeDenoise(handle,
  samples, count)` returns what is ready, `keep` times the input `delay`
  samples back mixed under it; the first `delay` output samples of a clip are
  dropped. `nativeDenoiseFinish` feeds `delay` samples of silence, flushes and
  resets, so over a clip the output is exactly as long as the input.
  `nativeDenoiserFree`. sherpa-onnx exits the process on a model whose
  metadata it does not recognise, so Kotlin hands it only a file whose SHA-256
  it has checked.

`mms_tts_catalog.py` writes the voice catalog, `core/voice/src/main/assets/speech/mms_tts.tsv`:
every voice of one pinned revision of the ONNX conversion, with sizes and SHA-256.

None of it is thread-safe; Kotlin calls the recognizer on one thread, the
voice on another (`MmsSpeech.dispatcher`) and a denoiser on the capture thread
of the clip using it; they share no handle.

## Choosing the Strong model

Measured on a Mac (2026-10-20) before any of it was built: 25 LibriSpeech
clips, each mixed with street and cafeteria noise from DEMAND (channel 1) and
a synthetic desk fan at 0, 5 and 10 dB SNR, plus the clean clips, 250 clips
and 34 minutes in all. Every denoiser was fed 100 ms chunks, as
`WhisperRecorder` does. Word error rate, all 250 clips:

| Denoiser | Whisper Tiny (en) | Sherpa zipformer-en-20M |
|---|---|---|
| none | 27.2% | 33.1% |
| RNNoise v0.1.1 (Light) | 32.8% | 40.0% |
| RNNoise, 12 dB of input kept | 31.0% | 35.7% |
| GTCRN (sherpa-onnx) | 32.6% | 37.9% |
| DPDFNet baseline | 26.4% | 32.4% |
| DPDFNet baseline, 12 dB kept (Strong) | 24.1% | 31.1% |
| DPDFNet2 | 25.9% | 30.7% |
| DPDFNet2, 12 dB kept | 23.7% | |
| DeepFilterNet3 (48 kHz, its own CLI) | 30.3% | |
| DeepFilterNet3, 12 dB kept | 24.7% | |

Blank cells are runs that did not finish. Light moved to RNNoise 0.2's
little model on 2026-10-28; rerun on the same clips and DEMAND recordings
with a new synthetic fan (raw 23.3% and 28.6% there), it scored 28.8% and
34.7% against 0.1.1's 28.9% and 36.8%. Table in `native/rnnoise-jni/README.md`. On clean clips Strong moved Whisper
from 8.2% to 9.0%. CPU per 10 ms of
audio, one thread, on a heavily loaded M2: RNNoise 0.25 ms, GTCRN 1.07,
DPDFNet baseline 1.20, DeepFilterNet3 1.23, DPDFNet2 2.62. DPDFNet2 is within
noise of the baseline on accuracy at twice the work, and DeepFilterNet3 needs
48 kHz audio and its own STFT and feature code (or the Rust runtime) on top,
so the baseline is the one shipped. Peak RSS of the Python process running it
was under 90 MB.

## Choosing the punctuation model

Measured on a Mac (2026-10-21) before anything was built. Held-out text in
eight languages had its punctuation and case stripped to what an engine writes
(lower case, no marks, apostrophes and in-number marks kept), each candidate
restored it, and every word end was scored for the mark after it (full stop
with `!` `;` `।` `。` and the like, comma, question mark): micro F1 over the
three. Two sets: 200 FLORES-200 devtest paragraphs of one to three sentences of
an article, the same articles in every language, and 200 groups of two or
three Tatoeba sentences per language, short and conversational, about one
in seven a question (FLORES has almost none).

FLORES-200, punctuation F1:

| Model | Phone size | en | bn | hi | es | ar | zh | ru | fr |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 1-800-BAD-CODE punct_cap_seg_47 (int8) | 59 MB | 0.667 | 0.606 | 0.575 | 0.634 | 0.606 | 0.627 | 0.805 | 0.646 |
| 1-800-BAD-CODE xlm-roberta pcs (fp32) | 1.1 GB | 0.802 | 0.691 | 0.711 | 0.782 | 0.673 | 0.808 | 0.871 | 0.815 |
| sherpa-onnx CT-Transformer (zh, en) | 294 MB | 0.560 | | | | | 0.559 | | |
| sherpa-onnx English online model | 7.5 MB | 0.659 | | | | | | | |
| **Horizon punctuation-restoration-small, as shipped** | 151 MB | **0.805** | **0.730** | **0.776** | **0.838** | **0.726** | **0.830** | **0.900** | **0.853** |

Tatoeba, punctuation F1 (English question marks alone: 0.95 shipped, 0.98
xlm-roberta, 0.60 the English online model):

| Model | en | bn | hi | es | ar | zh | ru | fr |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| punct_cap_seg_47 (int8) | 0.856 | 0.797 | 0.721 | 0.741 | 0.659 | 0.652 | 0.664 | 0.689 |
| xlm-roberta pcs (fp32) | 0.895 | 0.881 | 0.804 | 0.776 | 0.715 | 0.716 | 0.824 | 0.830 |
| English online model | 0.623 | | | | | | | |
| **Shipped** | **0.935** | 0.858 | **0.857** | **0.800** | **0.741** | 0.657 | **0.860** | **0.853** |

Capitals, FLORES, share of words whose first letter's case is right (en, es,
ru, fr): xlm-roberta 0.977 to 0.981; punct_cap_seg 0.948 to 0.953; the English
model 0.967. The shipped model writes no capitals: sentence starts by rule
give 0.918 to 0.950, plus a capitals lexicon of the kind the keyboard's word
lists hold 0.944 to 0.962, and for English the English model's capitals laid
over its marks 0.970 (punctuation unchanged at 0.805).

Cost, median per paragraph on two threads of a shared M2: CT-Transformer 3 to
4 ms, the English model 5, punct_cap_seg 8 to 10, the shipped model 14 to 37
(Bangla, whose words take more tokens, the slowest), xlm-roberta 92 to 147.
Memory of an open session: the shipped model 87 MB, xlm-roberta 837 MB,
CT-Transformer 980 MB.

So: Horizon's model for every language it knows. It is first on marks in all
eight on FLORES and in six on Tatoeba, where xlm-roberta leads Bangla and
Chinese; its 88 languages against 47 for the other
multilingual ones, and an eighth of xlm-roberta's run time. xlm-roberta's better
truecasing does not pay for a 1.1 GB model that punctuates worse, and the
keyboard has a capitals lexicon already. English keeps the English model as a
second pass for names, and as the whole of it on a phone without the big model.

Quantisation, on Bangla, Hindi, Spanish and Chinese (120 FLORES paragraphs):
Horizon's own `model_quantized.onnx` (int8 token table only) matched fp32
but ONNX Runtime folds its dequantised table into a 393 MB float constant;
dynamic int8 MatMuls lost 0.02 to 0.03 F1 (commas); 4-bit weight-only
MatMulNBits lost as much; 8-bit weight-only MatMulNBits with int8 compute
(`accuracy_level` 4) matched fp32 and was the fastest of all. That is what
ships, with the int8 table dequantised after its Gather and the weights in an
external data file, which ONNX Runtime maps: 319 MB of private memory for
the same weights inline, 87 MB mapped. Checked identical on ONNX Runtime
1.30.0.

The JVM side was run against the same files: `GemmaTokenizer` a word at a
time gives Hugging Face tokenizers' ids on all 3,200 test texts, the bridge's
labels through JNI match the Python session's, and `PunctuationText`
reproduces the scores above.

