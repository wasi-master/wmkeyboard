# wm-engine: the text engines, written once

WM Keyboard's phonetic and prediction engines have lived in Kotlin under
`core/language` and `core/prediction`. WMK Desktop, the companion for macOS,
Windows and Linux, needs the same engines, and a second implementation would
drift from the first. So the engines move here, one at a time, and both apps
run this crate: the Android app through a JNI bridge, the desktop app directly.

The move is incremental on purpose. Each engine is ported against the same
conformance fixtures the Kotlin original uses, the Android app switches to the
Rust version once that corpus is green, and only then is the Kotlin copy
deleted. Prediction and glide, which change weekly, stay in Kotlin until the
rest is settled.

## What is here

The whole of the Kotlin `core/transliteration` package, module for module.

| Module | Engine |
|---|---|
| `khipro`, `sexp` | the Khipro m17n interpreter, Classic and Dark, touch and desktop specs |
| `avro`, `avro_desktop` | the keyboard's Avro transliterator; desktop Avro's grammars and lookups from ibus-avro's data |
| `hindi` | Hindi phonetic into Devanagari |
| `arabic_script`, `urdu`, `arabic`, `persian` | the shared roman-to-Arabic-script walk and its three tables |
| `indic`, `indic_script`, `indic_profiles`, one module per language | the Indic engine for Marathi, Gujarati, Punjabi, Odia, Tamil, Telugu, Kannada and Malayalam |
| `assamese` | Assamese on the Avro engine |
| `bengali_graphemes` | cluster-aware backspace and vowel-key forms |
| `bengali_romanizer`, `devanagari_romanizer`, `urdu_romanizer`, `khipro_romanizer` | script back to Latin |
| `bijoy`, `bijoy_tables` | Unicode Bengali to the three Bijoy ANSI encodings |
| `zawgyi`, `zawgyi_rules` | Burmese Zawgyi: google/myanmar-tools' detector and its Zawgyi ↔ Unicode converters |
| `phonetic_index`, `bengali_index`, `hindi_index`, `indic_index`, `urdu_index` | reverse-phonetic lookup over word lists |
| `phonetic_key_map` | the key maps the settings pages draw, tested against the engines |
| `unicode` | the letter and mark classes `std` has no table for |
| `wmdict` | the `.wmdict` binary dictionary: the breadth-first trie builder, the writer (byte for byte the Kotlin codec, proven against the app's compiled lists) and the in-place reader with branch-and-bound completion |
| `wmng` | the `.wmng` n-gram pack: builder, writer and in-place reader |
| `wordlist`, `freq_codec` | the text word lists the dictionaries compile from; the minifloat both files store counts in |

Still Kotlin: Avro's dictionary walk, prediction, glide. The desktop app
reads the phone's dictionaries and packs through `wmdict` and `wmng`, so
the two apps share word lists as files, not just engines.

## Data

The crate does no I/O. Its tables are compiled in with `include_str!`: the
Khipro specs and their upstream conformance tables under `data/khipro/`
(`scripts/update-khipro.sh` refreshes them; `build.rs` deflates the four
specs into the library, 196 KB of text down to about 30 KB, and `khipro`
inflates one on first use), and ibus-avro's grammar files from
the Android app's own `app/src/main/assets/avro/*.json`, which the Kotlin
dictionary walk still reads too, so the two cannot run different revisions.
The Bijoy tables in `src/bijoy_tables.rs` were generated from the Kotlin
`BijoyAnsiTables.kt` at the time of the port and are the only copy now.
The Zawgyi detector's model and upstream's test corpora live under
`data/myanmar/`, and `src/zawgyi_rules.rs` is generated from the Java client's
transliterators; `scripts/update-myanmar-tools.py` refreshes all of it from a
checkout of google/myanmar-tools at the pinned tag (v1.2.0+java).

## Tests

```sh
cargo test
```

Every Kotlin test for the package is mirrored, including the four upstream
Khipro conformance tables and the Bijoy converters' outputs for all three
encodings. A failure against a table is a disagreement with upstream: fix the
interpreter, never the table.

## Bridges

`../wm-engine-jni` is the Android bridge: a `cdylib` with one JNI entry point
per engine function, loaded by `EngineNative` in `core/language`, which the
Kotlin facades (`AvroPhonetic`, `Khipro`, `BijoyAnsi`...) call. Its `build.sh`
cross-compiles both ABIs with `cargo ndk` into
`core/language/src/main/jniLibs`, as `harper-jni/build.sh` does; F-Droid
rebuilds it from source. `build-host.sh` builds it for this machine, which
the JVM unit tests load. The desktop app depends on this crate directly.
