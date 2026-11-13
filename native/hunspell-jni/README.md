# hunspell-jni: spell checking with Hunspell

[Hunspell](https://github.com/hunspell/hunspell) checks words and offers
spellings against a language's own `.aff` and `.dic`, the dictionaries
LibreOffice, Firefox and Chrome use. The keyboard imports those files as word
lists (`core/prediction/.../Hunspell.kt` unfolds them into every word they
spell) and keeps the originals beside the list. With this library present,
the suggestion engine also asks Hunspell itself, which understands what the
unfolded list cannot: compound words (German, Dutch, Hungarian, Finnish),
deeper affix chains, and its own suggestions. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.prediction.HunspellNative`, wrapped by
`HunspellChecker` and `HunspellRuntime` in the same package.

The built libraries are committed under `core/prediction/src/main/jniLibs/`,
so the app builds without an NDK. GitHub builds leave them out of the APK and
pin them (gate `spell`), Play builds ship them in the on-demand
`:feature:spell` split, and F-Droid deletes the committed copies and builds
them with this script through `native/build-fdroid-libs.sh`, as does the
release workflow for the reference APK F-Droid compares against. The script
maps the build machine's paths out of the library, so two builds with the
same NDK come out byte for byte the same. Rebuild after you change anything
here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/hunspell-jni/build.sh
```

It needs CMake and Ninja on `PATH`, and fetches Hunspell at the pinned commit
unless `HUNSPELL_SRC` points at a checkout of it. The committed libraries were
built with NDK r29 (`29.0.14206865`), the one F-Droid's recipe names; r27 also
builds them, into different bytes. A rebuild takes about ten seconds.

Upstream Hunspell rather than nuspell: nuspell needs ICU4C, which the NDK
exposes only from API 31, and the app supports API 24.

## Provenance

Hunspell `v1.7.6`, commit `15faca15532b34db3106d50f162e5edc2503d6d6`,
unmodified: the ten sources of upstream's `libhunspell` built as a static
library, `-Os`, linked into one shared library with `wm_hunspell_jni.cpp`
and libc++ static. Hunspell is tri-licensed MPL 1.1 / GPL 2.0 / LGPL 2.1;
the app takes it under the MPL 1.1 (`app/src/main/assets/licenses/mpl-1.1.txt`).
Every Hunspell source file is unchanged and published at the commit above.

## Contract with Kotlin

- `nativeOpen(affPath, dicPath) -> Long`: a handle, 0 if construction threw.
  Hunspell itself never fails on a bad file; it loads what it can, and a
  missing `.dic` is a checker that accepts nothing.
- `nativeClose(handle)`.
- `nativeEncoding(handle) -> String`: the dictionary's `SET` (`UTF-8`,
  `ISO8859-1`, `KOI8-R`, `microsoft-cp1251` ...), `ISO8859-1` when it has none.
- `nativeSpell(handle, word: ByteArray) -> Boolean`.
- `nativeSuggest(handle, word: ByteArray, limit) -> Array<ByteArray>`.

Words cross as bytes in the dictionary's own encoding, which Kotlin encodes
and decodes (`HunspellEncoding`): JNI's modified UTF-8 would hand an
ISO 8859 dictionary the wrong bytes. A Hunspell object keeps scratch state
between calls and must not be used from two threads at once; Kotlin holds a
lock per handle. Suggestion is slow by design (Hunspell gives itself up to a
quarter of a second), so the engine asks for it only on its background pass.

## ABIs

arm64-v8a and armeabi-v7a, API 24, 16 KB page aligned, about 1.1 MB
(arm64) each, most of it libc++'s streams, which Hunspell reads its files
with.
