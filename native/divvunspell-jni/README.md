# divvunspell-jni: GiellaLT spellers

[divvunspell](https://github.com/divvun/divvunspell) is the engine Divvun and
GiellaLT run their spellers on: weighted finite-state transducers, packed in
`.bhfst` archives (or the older zip `.zhfst`), that know every inflected and
compounded form of a language. GiellaLT builds them for about 150 languages,
most of them morphologically rich minority languages (the Sámi languages,
Greenlandic, Faroese, Inuktitut, the Finnic and Uralic languages of Russia), where
a word list cannot hold the forms people actually type. Divvun's own Android
keyboard checks words with it.

This crate wraps the library (`divvun-fst` upstream) in five JNI calls for
`com.wasimaster.wmkeyboard.core.prediction.DivvunNative`, which
`DivvunChecker` turns into the suggestion engine's `WordChecker`, beside
Hunspell's. Speller packs are not part of the app: the phone downloads the
pinned ones (`core/prediction/.../dictionaries/DivvunSpellerCatalog.kt`) or
the user imports one.

The built libraries are committed under `core/prediction/src/main/jniLibs/`,
next to `libwmhunspell.so`, and share its `spell` gate: GitHub builds leave
both out of the APK and pin them for download, Play builds ship both in the
on-demand `:feature:spell` split, and F-Droid deletes the committed copies and
builds them through `native/build-fdroid-libs.sh`. Rebuild after you change
anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/divvunspell-jni/build.sh
```

The script installs Rust 1.99.1 and cargo-ndk 4.1.2 if they are missing,
builds in a temporary target directory it removes on exit (or in
`DIVVUN_TARGET_DIR`), and maps every build-machine path out of the output.
The committed libraries were built with NDK r29 (`29.0.14206865`), the one
F-Droid's recipe names. A cold build takes about 25 minutes for the three
ABIs on a busy laptop, most of it fat LTO.

## Provenance

- divvunspell (`divvun-fst` 1.0.0-beta.13), git commit
  `7c3400754ef3f3e39b1c725f0ea18c1acd749b0d` of github.com/divvun/divvunspell,
  MIT OR Apache-2.0. crates.io has only 1.0.0-beta.3 (2023), which predates
  the archives GiellaLT publishes now and the search budget used below. With
  the `compression` feature, so a `.zhfst` with deflated members opens too.
- box-format (the `.bhfst` reader), git commit
  `3d3818ce6251201916f2ce99a2aedfef321ba3ad` of github.com/bbqsrc/box, the
  one divvunspell's own `Cargo.lock` names.
- `tar` and `zstd` for unpacking GiellaLT's `.pkt.tar.zst` packages; zstd is
  linked in for box-format already.

Every crate, with its licence, is listed in
`app/src/main/assets/licenses/divvunspell-third-party.txt`. All are MIT,
Apache-2.0 or BSD; the C parts are Zstandard (BSD-3-Clause) and liblzma
(0BSD / public domain).

## Contract with Kotlin

- `nativeOpen(path, tempDir) -> Long`: a handle, 0 when the archive does not
  open. The library picks the format by extension (`.bhfst`, `.zhfst`).
  `tempDir` becomes `TMPDIR` when the process has no writable one, because a
  `.zhfst` with compressed members unpacks them there and Android's default
  (`/data/local/tmp`) is not writable by an app.
- `nativeClose(handle)`.
- `nativeIsCorrect(handle, word) -> Boolean`. Words with no letters count as
  correct, so Kotlin asks only about words that have one. Case variants are
  tried (`Sámegiela`, `SÁMEGIELA`).
- `nativeSuggest(handle, word, limit) -> Array<String>?`, best first, never
  the word itself.
- `nativeUnpack(package, member, target) -> String?`: writes `member` of a
  zstd tar to `target` (the first `.bhfst` that is not a `-x-` variant when
  `member` is empty). Null on success, the error otherwise.

Every call catches panics, so a broken archive the user imported is a
refusal, not a dead keyboard (the profile keeps `panic = "unwind"` for that).
A handle may be used from several threads at once; Kotlin holds a read lock
around calls and a write lock around close.

Suggestions run under the archive's bundled configuration with the search
budget capped at 60,000 nodes (`SEARCH_BUDGET` in `src/lib.rs`). The search is
best-first, so the cap trims the dearest tail rather than the best answers;
without it, a word with nothing near it sweeps everything the error model
reaches, which takes seconds on a phone.

## Host check

`examples/probe.rs` runs the same `Checker` on the Mac against real archives:

```sh
cd native/divvunspell-jni
cargo run --release --example probe -- se.bhfst sámegiella čallindarkkisteaddji
```

Results on 2026-10-20 (Apple M-series, machine under load), with
`se.bhfst` from `speller-sme/v4.5.2` and `kl.bhfst` from `speller-kal/v1.0.3`:

| Archive | Word | Accepted | Spellings |
| --- | --- | --- | --- |
| se | sámegiella, giella, Guovdageaidnu, davvisámegiella, čállindárkkisteaddji, boahtit | yes | |
| se | čallindarkkisteaddji | no | čállindárkkisteaddji, čallandárkkisteaddji, … (77 ms) |
| se | boatit | no | boahtit, boađit, boahttit, boaddit, bohtit (10 ms) |
| se | dalki | no | dálki, dolki, dálkii, dálkki, dilkii (7 ms) |
| se | gávppot | no | gávpot, gávppat, gávppát, gávppit, gávpát (12 ms) |
| kl | kalaallisut, Kalaallit, Nunaat, qimmeq, illu, inuit, oqaatsit, Nuuk | yes | |
| kl | kalalisut | no | kalallisut, kalagisut, kalaliisut, kalaliusut, kalalivut (7 ms) |
| kl | qimeq | no | qimmeq, qileq, qineq, qipeq, qiseq (6 ms) |
| kl | oqatsit | no | oqaatsit, oqatit (10 ms) |

The same words through the JVM, on the host build of this library with the
real `DivvunNative`, `DivvunChecker` and `DivvunSpellerDownloads.import`
(fed the downloaded `.pkt.tar.zst` packages, which it recognised by content,
unpacked and opened in 1.9 s for se and 0.7 s for kl), gave the same
answers. Two words came back "no" on their first ask because the walk took
longer than the 15 ms wait, and "yes" on the next ask once it had finished.
A text file offered as a speller was refused and left the installed one in
place.

Opening an archive takes 3 to 30 ms (it is memory-mapped, not read). A check
is well under a millisecond once the pages it walks are in memory and can
take hundreds of milliseconds the first time, which is why `DivvunChecker`
never waits more than 15 ms for one on the main thread. Keyboard mash
(`qwrtzp`, `asdfghjkl`) comes back with no spellings in 2 to 11 ms.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned: 2.8 MB (arm64) and
2.0 MB (armeabi-v7a). The only dynamic symbols
are the five `Java_..._DivvunNative_*` entry points.
