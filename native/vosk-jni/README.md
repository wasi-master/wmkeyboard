# vosk-jni: the bridge to Vosk

[Vosk](https://alphacephei.com/vosk/) is Alpha Cephei's offline speech
recogniser, built on Kaldi. It sits behind the Vosk voice engine (Settings ›
Voice typing › Recognition engine › Vosk, #1165). Kotlin entry point:
`com.wasimaster.wmkeyboard.core.voice.vosk.VoskNative`.

Two libraries ride the `vosk` gate in `native/gates.json`:

- `libvosk.so` is Alpha Cephei's own build, read out of their
  `com.alphacephei:vosk-android` AAR on Maven Central (version in
  `gradle/libs.versions.toml`). It is never rebuilt or mirrored here: the
  GitHub build pins its SHA-256 and reads it on the phone straight out of the
  AAR, the offline build packages it from the AAR (`wmkeyboard.vosk`
  convention), and Play's `:feature:vosk` split carries it.
- `libwmvosk.so` is this directory's `vosk_jni.c`, about 8 KB, committed under
  `core/voice/src/vosk/jniLibs/`. It finds libvosk's C API (`vosk_api.h`) with
  `dlsym` in the copy the Kotlin side loaded first, so it builds with nothing
  but the NDK and does not link against any particular libvosk. The AAR's
  Java API, which goes through JNA, is not used.

Rebuild after changing `vosk_jni.c`:

```sh
ANDROID_NDK=~/Library/Android/sdk/ndk/27.3.13750724 native/vosk-jni/build.sh
```

Audio goes in as 16 kHz floats in [-1, 1]; the bridge scales them to the
16-bit range `vosk_recognizer_accept_waveform_f` expects (Vosk copies the
floats into the same buffer its `short` entry point fills).

## Not in F-Droid's build

libvosk is a prebuilt binary, and building it from source means Kaldi,
OpenFst and CLAPACK for both ABIs. The gate is marked `google` (left out of
F-Droid's build, like `kiwi` and `webrtc`), and has no `fdroidBuild` script.

## Provenance

- Vosk API: Apache-2.0, Copyright 2019-2025 Alpha Cephei Inc.
  (https://github.com/alphacep/vosk-api). Kaldi and OpenFst inside libvosk
  are Apache-2.0 as well.
- The small models downloaded from https://alphacephei.com/vosk/models are
  Apache-2.0, apart from the Czech and Swedish models repackaged from Rhasspy
  under MIT. Each zip is pinned by SHA-256 in `EtCatalog`.
- Sayboard (https://github.com/ElishaAz/Sayboard, GPL-3.0) uses Vosk through
  its Java API in a keyboard; nothing here is taken from it.
