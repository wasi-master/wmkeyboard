# whistle-jni: offline Cactus Whistle dictation

[Cactus Whistle](https://huggingface.co/Cactus-Compute/whistle) is a 16.9 MB
speech recognition model for English, German, French, Spanish, Italian, Dutch
and Polish, run by Cactus's Needle engine. It is the third dictation engine,
beside the system recognizer and offline Whisper. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.voice.whistle.WhistleEngine` (in
`core/voice/src/main`). The integration was contributed by
[@Guillermode20](https://github.com/Guillermode20) in PR #548.

The built `libwmwhistle.so` is committed under
`core/voice/src/whistle/jniLibs/arm64-v8a/`, so the app builds without an NDK.
F-Droid `scandelete`s both that directory and `prebuilt/`, so its build has no
Whistle. Rebuild after you change
anything here:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/whistle-jni/build.sh
```

One source file, so `build.sh` calls the NDK's clang directly: no CMake, no
Ninja. The output is about 0.6 MB, linked with `--no-undefined`, 16 KB pages.

## Provenance

`prebuilt/arm64-v8a/libneedle.a` and `include/needle.h` are the unmodified
Android artifacts Cactus Compute publishes in the Hugging Face
`Cactus-Compute/needle3` repository, revision
`f84005f8992caf37f17b0d64a4b5b31a84ce0d2a` (`android-arm64/`). SHA-256 of the
archive: `16752fb75adea7af89bbc435a97d1cda7e71bc74d04578d551d0d3bbd5f9e633`,
the same as the LFS oid Hugging Face reports for it. Needle and the Whistle
weights are Apache-2.0; `LICENSE` is upstream's.

The model (`whistle.cact`) is not bundled. The keyboard downloads it on request
from `Cactus-Compute/whistle` at revision
`b358ddadd89b7a713b5aa131f23032d3cca1b251` and checks it is 16,919,407 bytes
with SHA-256 `b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb`.

## ABIs

arm64 only. Upstream's armv7 archive references libc++'s private
`std::__ndk1::__hash_memory`, which no NDK runtime exports. On armv7 no library loads, `WhistleEngine.available` is
false, and the engine is not offered.

## Contract with Kotlin

- `nativeLoad(bytes) -> String?`: null on success, else the reason. The bytes
  are kept for the life of the process; the C API has no unload.
- `nativeTranscribe(pcm, language, keywords) -> String`: 16 kHz mono float PCM
  in [-1, 1], at most 30 s. `language` is one of the seven codes, or null to
  detect. `keywords` is null or newline-separated words to bias towards. Returns
  the engine's JSON, `{"text":"...","language":"en",...}`, or
  `{"error":"..."}`.
- Both are serialised behind one mutex: the engine is process-global and not
  thread-safe.
