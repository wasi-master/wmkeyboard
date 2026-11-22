# lipi-jni: script conversion with vidyut-lipi

[vidyut-lipi](https://github.com/ambuda-org/vidyut) (MIT, Ambuda) converts
text between the Brahmic scripts (Devanagari, Bengali, Tamil, Grantha,
Sharada, Thai, Tibetan and the rest) and the romanisations Indic text is
written in (IAST, ISO 15919, ITRANS, Harvard-Kyoto, SLP1, Velthuis, WX,
Baraha). The keyboard uses it for two things:

- **Convert script**, the selection action: select Indic text (or IAST) and
  pick the script or scheme to rewrite it in.
- **Write in another script** for Sanskrit: the Sanskrit layout goes on typing
  Devanagari, and each word reaches the field in IAST, Grantha, Kannada or
  whichever script was picked, through the same output path the legacy fonts
  use (`LegacyFonts`, `OutputEncoding.LegacyFont`).

Kotlin entry point: `com.wasimaster.wmkeyboard.core.transliteration.LipiNative`,
behind the `Lipi` facade and the `LipiScheme` list in the same package
(`:core:language`).

It is a gate of its own (`lipi` in `native/gates.json`), not part of
`libwm_engine_jni.so`: the engine loads at every keyboard start, and this is
a library few people will use. GitHub builds leave it out of the APK and fetch
it the first time it is asked for, Play builds carry it in the on-demand
`:feature:lipi` split, and F-Droid deletes the committed copies and builds them
with this script through `native/build-fdroid-libs.sh`.

The built libraries are committed under `core/language/src/lipi/jniLibs/`, so
the app builds without a Rust toolchain. Rebuild them after you change this
crate or bump vidyut-lipi:

```sh
ANDROID_NDK=/path/to/ndk/29.0.14206865 native/lipi-jni/build.sh
```

`build.sh` installs the pinned Rust toolchain and Android targets through
rustup and the pinned `cargo-ndk`, builds arm64-v8a and armeabi-v7a with 16 KB
segment alignment, and maps the registry, standard library and repository
paths out of the library. Two builds into different target directories
(`LIPI_TARGET_DIR`, `LIPI_OUT`) come out byte for byte the same.

Sizes, stripped, 0.2.0: arm64-v8a 858 KB, armeabi-v7a 531 KB.

Host tests: `cargo test --release` in this directory.

## Contract with Kotlin

- `nativeTransliterate(text, from, to) -> String?`. `from` and `to` are
  vidyut's `Scheme` variant names exactly (`Devanagari`, `Iast`,
  `HarvardKyoto`, `Iso15919`), which is what `LipiScheme.native` holds. An
  unknown name returns null.
- vidyut-lipi is pinned to `=0.2.0`, because those names are the contract. The
  `kotlin_names_are_schemes` test lists every name `LipiScheme` passes; keep it
  in step with the enum, and run it after a bump.
- One `Lipika` serves the process behind a mutex: it caches the mappings it
  builds, which are most of the cost of a call.
