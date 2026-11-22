# chewing-jni: the Chewing engine for Zhuyin

A Rust cdylib wrapping [libchewing](https://github.com/chewing/libchewing)
(the `chewing` crate, 0.13.1) behind a small JNI surface for the keyboard's
optional Chewing engine for Zhuyin (Traditional Chinese). Kotlin entry point:
`com.wasimaster.wmkeyboard.core.input.chewing.ChewingNative`.

libchewing is LGPL-2.1-or-later, and so is this library. It ships as its own
`libwmchewing.so`, delivered by its own gate (`chewing` in
`native/gates.json`), so it can be replaced on its own: build it again from
this directory with the script below.

The prebuilt libraries are committed under
`core/input/src/chewing/jniLibs/<abi>/`, so the app builds without a Rust
toolchain. Rebuild them after you change this crate or bump `chewing`:

```sh
ANDROID_NDK=/path/to/ndk native/chewing-jni/build.sh
```

`build.sh` installs the pinned Rust toolchain and Android targets through
rustup and the pinned `cargo-ndk`, builds arm64-v8a and armeabi-v7a with
16 KB pages, and remaps the registry, standard library and repository paths
out of the panic messages, so two machines with the same toolchain build the
same bytes (checked: two target directories, the same SHA-256). F-Droid
deletes the committed copies and builds them with it, through
`native/build-fdroid-libs.sh`.

About 550 KB on arm64, 380 KB on armeabi-v7a.

## The dictionary

The dictionary is not in the library. `build.sh --data DIR` fetches
[libchewing-data](https://github.com/chewing/libchewing-data) at the pinned
release, checks its commit, and compiles its `dict/chewing/word.csv` (single
characters) and `tsi.csv` (phrases) with this crate's `chewing-dictc` into
`DIR/chewing-<release>.dat`. libchewing loads the two as separate layers;
`chewing-dictc` writes them into one trie, which reads the same (a character
keeps `word.csv`'s order, an entry in both keeps the higher frequency), so
the keyboard downloads one file with one checksum. The output depends only on
the sources. The file is published in the data repository under
`cjk/chewing/` and pinned in `ChewingData.kt`; a new release goes out under a
new name.

## Contract with Kotlin

The keyboard keeps the reading (the bopomofo typed, tone marks included) and
asks on every keystroke, so the bridge keeps no state between calls except
libchewing's learning. Each call replays the reading into one `Editor` from
empty, as the keys would arrive on a Dai Chien (大千) keyboard; the conversion
it reads back is libchewing's own.

- A syllable needs no tone: the editor runs libchewing's fuzzy lookup
  (`FuzzyPartialPrefix`, `FuzzyChewingEngine`), so the next initial closes a
  toneless syllable and it matches any tone. A syllable still open at the
  end is closed with a space before converting.
- `nativeConvert(reading, limit, info)` returns the candidate texts and then
  the composing text. `info` gets `[count, tail, consumed₀, sentence₀, …]`:
  `tail` is where the syllable still being typed begins, `consumed` how many
  reading characters a candidate covers from the start, `sentence` which of
  libchewing's whole-sentence conversions it is (0 the best, -1 for a phrase).
  The list is the sentence, its next two conversions (Tab, in libchewing),
  then libchewing's candidate window at the front of the reading: the
  longest phrase first, down to the first character's readings.
- Every bopomofo and tone mark is one UTF-16 unit, so lengths in characters
  are lengths in Kotlin.
- `nativeLearnPhrase` and `nativeLearnSentence` learn a pick. A phrase picked
  from a toneless reading is learned under the tones the system dictionary
  reads it with, so a later reading with the tones typed finds it too. A
  sentence learns its phrases and each run of single characters, the way
  libchewing learns on Enter.
- Learned phrases live in the user dictionary file `nativeStart` was given
  (libchewing's trie format). `nativeSync` writes it out.
- Each entry point catches a panic and answers "nothing", so an assertion
  inside libchewing costs one keystroke's candidates and not the keyboard.

## Tests

`cargo test` runs the engine against a small dictionary built in the test.
With the real one: `CHEWING_DICT=chewing-2026.3.22.dat cargo test --release
-- --ignored --nocapture` converts a few sentences and times an eleven
syllable reading (about 3 ms on an M-series Mac).
