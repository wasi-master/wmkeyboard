# harper-jni: offline grammar engine

Rust cdylib wrapping [Harper](https://github.com/automattic/harper)
(`harper-core`) behind a tiny JNI surface for the keyboard's grammar tool.
Kotlin entry point: `com.wasimaster.wmkeyboard.core.grammar.HarperNative`.

The prebuilt `libharper_jni.so` files are committed under
`core/intelligence/src/main/jniLibs/<abi>/`, so the app builds without a Rust
toolchain. Rebuild them after you change this crate or bump `harper-core`:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/harper-jni/build.sh
```

`build.sh` installs the pinned Rust toolchain and Android targets through
rustup and the pinned `cargo-ndk`, builds arm64-v8a and armeabi-v7a,
and remaps the registry, standard library and repository paths out of the
panic messages, so two machines with the same toolchain build the same bytes.
F-Droid deletes the committed copies and builds them with it (through
`native/build-fdroid-libs.sh`), as does the release workflow for the reference
APK F-Droid compares against.

Host-side tests (`cargo test`) exercise the lint→JSON path without Android.

## Vendored harper-core and harper-brill

`vendor/` holds harper-core and harper-brill 3.0.0, wired in through
`[patch.crates-io]`, with two changes and nothing else (tests and benches left
out):

- Their embedded data (the dictionary word list, the Brill tagger and chunker,
  the neural chunker's model and vocabulary) is stored raw-deflated
  (`*.deflate`) and inflated once on first use with `miniz_oxide`. Lint output
  is byte-for-byte the same as the published crates on harper-core's own test
  sources in all four dialects (`cargo test -- --ignored corpus_snapshot` with
  `HARPER_CORPUS` and `HARPER_CORPUS_OUT` set, against a build of the
  published crates). On 3.0.0 the final build, Brill chunker included, gives
  the same bytes too.
- `Document::parse` uses the Brill chunker instead of the neural one, which
  lets LTO drop the Burn framework and its model. harper-core 3.0.0's own
  unit tests: 6,787 of 6,788 pass, against 6,788 with the neural chunker; the
  one change is still a false "missing to" on "a standard and expected feature
  for any e-commerce site selling visually-driven products". The suite runs
  about ten times as fast (2 s against 21 s). Its `pos_tags` snapshot test
  fails by design: the snapshots record the neural chunker's noun phrases.

Together: arm64 5.5 MB to 2.4 MB, armv7 4.9 to 1.9.

To bump harper-core: copy the new versions of both crates into `vendor/`
(without `tests` and `benches`), deflate the same five files with raw deflate
(`zlib.compressobj(9, zlib.DEFLATED, -15)`), and re-apply the two marked
"WM Keyboard" edits in `harper-brill/src/lib.rs`,
`harper-core/src/spell/dictionary.rs` (since 3.0 the word list is read there,
by both `MutableDictionary` and `WordMap`) and `harper-core/src/document.rs`,
plus the `miniz_oxide` dependency in both `Cargo.toml`s. Then check both
claims above again before rebuilding.

## Contract with Kotlin

- `nativeLint(text, dialect) -> String` returns a JSON array of
  `{start, end, original, kind, message, priority, suggestions:[{kind, text}]}`.
  `start` and `end` are **UTF-16** code-unit indices, which is how Kotlin
  indexes a string. The char→UTF-16 conversion happens on the Rust side.
- The `dialect` ordinal matches `GrammarDialect` in `SettingsRepository.kt`:
  0 American, 1 British, 2 Canadian, 3 Australian. Append-only.
- Harper's `LintGroup` is not `Send`, so the linter cache is thread-local.
  `GrammarChecker` funnels every call through one dedicated thread.

## Vendored blanket and const-random-macro

Two proc macros made the library come out different from one build to the
next, which F-Droid's reproducible build cannot accept. Both are vendored in
`vendor/` through `[patch.crates-io]`, each with one marked "WM Keyboard"
change:

- `blanket` 0.4.0 kept its `derive(...)` list in a `HashSet`, so
  `#[blanket(derive(Arc, Ref))]` on harper-core's `Dictionary` emitted the two
  impls in a random order. It is a `Vec` in the order written now.
- `const-random-macro` 0.1.16 drew a fresh random number on every compile
  (burn-ndarray uses it for an RNG seed the keyboard never runs) and mixed in
  the call site's source position. It returns a fixed value now.

Check a change to this crate the same way: build it twice into different
target directories (`HARPER_TARGET_DIR`, `HARPER_OUT`) and compare the
libraries byte for byte.
