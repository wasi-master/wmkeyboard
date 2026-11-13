# opus-jni: compressed uploads to a transcription server

[libopus](https://opus-codec.org) compresses each dictation clip before it is
sent to a [transcription server](../../docs/src/content/docs/tools/transcription-server.mdx).
Kotlin entry point: `com.wasimaster.wmkeyboard.core.voice.OpusEncoder`, used by
`TranscriptionClient` in `:core:tools`. Nothing else in the app sends audio.

`ogg_opus.c` wraps the encoder in an Ogg Opus file (RFC 7845 framing over
RFC 3533 pages), written by hand rather than with libogg: one stream, written
front to back, is all the keyboard needs. Pages hold a second of 20 ms packets
each (27 + 50 bytes of framing a second). The OpusHead pre-skip is the
encoder's lookahead, the last page's granule position trims the zero padding
off the end, and the stream serial is fixed so the same clip always gives the
same bytes.

Encoder settings: 16 kHz mono in, `OPUS_APPLICATION_VOIP`, `OPUS_SIGNAL_VOICE`,
unconstrained VBR at 20 kbit/s (`OpusEncoder.BITRATE`), complexity 10.

## Building

The built libraries are committed under `core/voice/src/opus/jniLibs/`, a
directory of their own: they are the `opus` gate in `native/gates.json`
(`:feature:opus` on Play). Rebuild after you change anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/opus-jni/build.sh
```

It needs the NDK, CMake and Ninja, and fetches libopus at the pinned commit
unless `OPUS_SRC` points at a checkout. libopus is built as a static float
library with `-ffunction-sections`; only the five JNI functions are exported
(`jni.map`), so `--gc-sections` leaves the decoder out. Sizes: arm64-v8a
295,464 B, armeabi-v7a 228,284 B; every LOAD segment 16 KB
aligned; no NEEDED beyond libc, libm and libdl.

Reproducible: a fresh clone and a local checkout of the same commit give
byte-identical libraries on both ABIs. `-g0` matters here: the NDK adds
`-g` by default, and the debug info in `libopus.a` carries a path that would
otherwise change the build id.

## Provenance

libopus `v1.6.1`, commit `22244de5a79bd1d6d623c32e72bf1954b56235be`,
unmodified, BSD-3-Clause (`LICENSE`, the upstream `COPYING`). Opus is covered
by royalty-free patent licences listed in that file.

## Measured on the host

73 LibriSpeech clips (`hf-internal-testing/librispeech_asr_dummy`, 481 s),
encoded through `ogg_opus.c` on macOS, decoded with ffmpeg's libopus:

| | WAV | Opus 16k | **Opus 20k (shipped)** | Opus 24k |
|---|---:|---:|---:|---:|
| Bytes, all 73 | 15,396,328 | 976,748 | 1,213,481 | 1,452,952 |
| Smaller by | | 15.8x | 12.7x | 10.6x |
| PESQ-WB, mean (min) | | 4.22 (3.76) | 4.38 (4.26) | 4.46 (4.33) |
| STOI, mean | | 0.981 | 0.988 | 0.992 |
| WER, Whisper small.en | 5.65% | 6.52% | 5.74% | |
| WER, Whisper base.en | 9.22% | | 8.96% | |

WER is through an OpenAI-shaped `/v1/audio/transcriptions` server (FastAPI,
faster-whisper int8 with its default decoding: beam 5 and temperature
fallback, the upload decoded by the ffmpeg CLI as LocalAI and whisper.cpp's
`--convert` do), the same multipart request `TranscriptionClient` makes,
1,150 reference words. At 20k small.en got one word more wrong than from WAV;
68 of 73 transcripts were identical. Greedy decoding with no temperature
fallback is noisier: one 24k clip fell into a repetition loop there, which
says more about that decoding than about the codec.

Every decoded file has exactly the input's sample count (pre-skip and end
trim right), and the last granule minus the pre-skip is 3 x the samples.
ffprobe reports 6.5 ms more than the true length (one lookahead) for these
files and for ffmpeg's own libopus files alike. No demuxer warning on any file.

Complexity 5 encodes 2.3x faster with PESQ 4.32, but cost words (small.en
6.00%, base.en 11.83%), so the encoder stays at 10. At 10, the 481 s took
12.2 s to encode on an M2 under load average 40 (about 40x real time), and a
5.9 s clip 60 ms at load 14. Phone timing is not measured; the chunked API
lets the recorder encode while it records if that turns out to matter.

The JNI glue was driven on the host through a hand-made `JNIEnv` under ASan
and UBSan: whole-buffer and 1,600-sample chunks give the same bytes as the
host encoder, a second finish returns the same file, a count past the array
and a 44.1 kHz rate are refused. Empty input makes a valid 140-byte file that
decodes to nothing.

## Contract with Kotlin

- `nativeEncode(samples, count, sampleRate, bitrate) -> ByteArray?`: a whole
  clip in one call. Null on a rate other than 8, 12, 16, 24 or 48 kHz, a
  bitrate outside 6–510 kbit/s, or a count past the array.
- `nativeOpen(sampleRate, bitrate) -> Long`: a stream, 0 on failure.
- `nativeWrite(handle, samples, count) -> Boolean`: adds samples; false once
  the stream has failed or is finished.
- `nativeFinish(handle) -> ByteArray?`: pads, drains the lookahead, writes the
  last page and returns the whole file. A second call returns the same file.
- `nativeFree(handle)`.

Not thread-safe; one caller per stream. Samples are copied out of the Java
array 4,096 at a time, never pinned.
