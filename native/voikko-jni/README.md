# voikko-jni: Finnish spell checking with Voikko

[Voikko](https://voikko.puimula.org/) is the Finnish spell checker,
hyphenator and morphological analyser that LibreOffice, Firefox and most
Linux desktops use. Finnish compounds freely and inflects every noun in
fifteen cases, with possessive suffixes and clitics on top, so most of what a
Finn types is in no word list, and Hunspell dictionaries for Finnish only
approximate it. Voikko's dictionary is a finite-state description of the
morphology itself: it accepts `kahvinkeittimessäkin` and `taloissammekin`
because it knows the stems, the endings and the order they come in.

This directory builds `libwmvoikko.so`: libvoikko, with its VFST backend
only, linked statically with `wm_voikko_jni.cpp`. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.prediction.VoikkoNative`, wrapped by
`VoikkoChecker` and `VoikkoRuntime` in the same package. The engine treats
it as a `WordChecker`, like Hunspell and divvunspell, and for Finnish uses it
in Hunspell's place.

## Licence, and why the library lives here

Everything under this directory is free software outside the app's MIT
licence. `COPYING` is libvoikko's: libvoikko as a whole is GPL 2 or later.
`LICENSE.CORE` says which parts are also offered under MPL 1.1 and LGPL 2.1+,
and the bridge in this directory is offered under the same three.

The built libraries sit in `jniLibs/` here, not in a module's
`src/main/jniLibs`, so that no Gradle module packages them into an APK:

| Build | How the phone gets `libwmvoikko.so` |
| --- | --- |
| Standard (GitHub) | Pinned in `assets/native/pins.tsv` (gate `spell`, with Hunspell and divvunspell) and downloaded from the data repository's `natives` release |
| F-Droid | Built from this source by `native/build-fdroid-libs.sh`, pinned the same way, downloaded after the library opt-in |
| Play | Packaged in the on-demand `:feature:spell` split with the other two spelling libraries |
| Offline | Not at all: Finnish uses the word lists and any imported Hunspell dictionary |

The offline build bundles every other library, because it can never fetch
one. It leaves this one out, which is what the brief for this library asked
for. That is stricter than the licences require, though. The only files in
libvoikko under GPL 2 alone are the lttoolbox backend (`LttoolboxAnalyzer.*`),
and `CMakeLists.txt` does not compile it; every file it does compile carries
the MPL 1.1 / GPL 2+ / LGPL 2.1+ notice, and `utf8/` is under a Boost-style
licence. So `libwmvoikko.so` can be taken under the MPL 1.1, the licence the
app already takes Hunspell under, and packaged in the offline APK the same
way Hunspell is. Doing that takes two lines: add `native/voikko-jni/jniLibs`
to `:core:prediction`'s jniLibs on the offline channel, and drop the
sentence above. The Finnish dictionary is GPL 2+ only, but it is data the
phone downloads, never part of any APK, so it does not affect that choice.

## Building

The built libraries are committed, so the app builds without an NDK. Rebuild
after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/voikko-jni/build.sh
```

It needs CMake and Ninja on `PATH` and fetches corevoikko at tag
`rel-libvoikko-4.3.3` (commit `4b838726d6e11af235b5aba50dcc056bc17c8176`)
unless `VOIKKO_SRC` points at a checkout of it. libvoikko normally builds
with autotools; `CMakeLists.txt` lists the same sources as upstream's
`libvoikko/src/Makefile.am` with VFST on and HFST, lttoolbox and VISLCG3 off,
and defines what `configure` would. `DISABLE_EXTDICTS` keeps the library from
looking in `$HOME/.voikko` or `VOIKKO_DICTIONARY_PATH`, and there is no
built-in dictionary path, so it reads only the folder Kotlin hands it.

Both ABIs, API 24, 16 KB page aligned, about 560 KB on arm64, exporting only
the five `Java_..._VoikkoNative_*` functions. A rebuild takes about 20
seconds. The committed libraries were built with NDK r29
(`29.0.14206865`), the one F-Droid's recipe names. Build paths are mapped out
with `-ffile-prefix-map` and the library is stripped at the link, so the build
id hashes only the bytes that ship: two builds, one from a fresh clone and one
from a checkout elsewhere on disk, came out byte for byte the same.

## Dictionary

Upstream publishes voikko-fi as source only, so the keyboard's download is
built here:

```sh
native/voikko-jni/build-dict.sh <out-dir>
```

The script fetches the signed `voikko-fi-2.6.tar.gz` release from
puimula.org, checks its SHA-256, builds libvoikko's `voikkovfstc` for the
host (`build.sh --host`), runs voikko-fi's own `make vvfst` with a fixed build
date and C-locale sort, and packs `5/mor-standard/{index.txt,mor.vfst,autocorr.vfst}`
into a zip with fixed timestamps. It needs foma (`brew install foma`),
Python 3 and make. Two runs give the same zip:

| File | Size | SHA-256 |
| --- | --- | --- |
| `voikko-fi-2.6.tar.gz` (source) | 709,151 | `5c147c1c92323941e5847b5c46d00b35ff6b62dc3cf348516bc0fbdc87340220` |
| `voikko-fi-2.6-mor-standard.zip` | 1,645,402 | `9a7b8ed3f401ac46e81714775743219175c54164a4eaa3b0286c68db4825b439` |

Both go in the data repository under `spell/voikko/`, the zip beside the
tarball it was built from, as the GPL asks. `VoikkoDictionaryCatalog` pins
the zip; the phone unpacks it into `dict/fi/voikko/` and keeps it only if
libvoikko loads it. A user can also import any zip with a
`5/mor-<variant>/` folder, for example one built with other voikko-fi
options.

## Contract with Kotlin

- `nativeOpen(language, dictionaryDir) -> Long`: a handle, 0 if no
  dictionary under `dictionaryDir/5/` matches `language` (`fi`) or it fails
  to load. Ugly hyphenation is switched off.
- `nativeClose(handle)`.
- `nativeSpell(handle, word: ByteArray) -> Boolean`: UTF-8 in.
- `nativeSuggest(handle, word: ByteArray, limit) -> Array<ByteArray>`: UTF-8
  out, best first.
- `nativeHyphenate(handle, word: ByteArray) -> String?`: one character per
  code point of the word, `' '` no break, `'-'` a break before it, `'='` a
  break that replaces it with the hyphen.

A handle keeps a speller cache between calls and is not safe on two threads
at once; `VoikkoChecker` holds a lock per handle, and the main thread waits
at most 15 ms for it.

## Checking it on a desktop

`build.sh --host <dir>` also builds `libwmvoikko.dylib` (or `.so`), which a
desktop JVM loads with `-Djava.library.path=<dir>`. Run against the real
dictionary, through the real `VoikkoNative`, `VoikkoChecker` and the import
and download code, it accepted `kahvinkeittimessäkin`, `taloissammekin`,
`ylioppilastutkintolautakunta`, `saunoisimmekohan` and `Helsingissä`, refused
`helsingissä` and `suomalainnen`, and suggested `kirajstossakin` →
`kirjastossakin`, `taloisammekin` → `taloissammekin`,
`kahvinkeittimesäkin` → `kahvinkeittimessäkin` and `paiva` → `päivä` in
1 to 14 ms. An uncached check took about 20 µs. Hyphenation gave
`kah-vi-kup-pi` and `yli-op-pi-las-tut-kin-to-lau-ta-kun-ta`.
