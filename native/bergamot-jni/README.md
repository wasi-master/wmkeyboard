# bergamot-jni: on-device translation without Google

Mozilla's [bergamot-translator](https://github.com/mozilla/translations/tree/main/inference),
the engine Firefox Translations runs, built for Android with a small JNI
bridge into `libwmbergamot.so`. It is the translate tool's second on-device
engine beside ML Kit, and the only one on F-Droid and on phones without Google
Play services. Kotlin entry point: `com.wasimaster.wmkeyboard.core.translate.BergamotNative`
(in `core/intelligence/src/main`), driven by `BergamotTranslator`.

The built libraries are committed under `core/intelligence/src/bergamot/jniLibs/`,
a directory of their own so that the Play split `:feature:bergamot` carries
nothing else. GitHub builds pin them for download (native gate `bergamot`),
F-Droid deletes the committed copies and builds them with this script (through
`native/build-fdroid-libs.sh`), and Play ships them in the split. Rebuild after
you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/bergamot-jni/build.sh
```

It needs git, CMake and Ninja on `PATH`, and fetches the sources at their
pinned commits unless `BERGAMOT_SRC`, `PCRE2_SRC` and `ZSTD_SRC` point at
checkouts of them. `WMKB_ABIS` narrows the build to one of the two ABIs.
Each ABI compiles Marian, about six minutes on an 8-core laptop.

## Provenance

| Source | Pin | Licence |
|---|---|---|
| `mozilla/translations`, `inference/` (bergamot-translator v0.6.0 and its Marian fork) | `1de4a085d3a7afb625c51a60aabb5ad298e4059f`, the revision Firefox's `translations-wasm-v2` record names | MPL-2.0 (Marian: MIT) |
| its submodules `ssplit-cpp`, `ruy` (+ `cpuinfo`), `sentencepiece`, `simd_utils`, `intgemm` | the commits that revision records | Apache-2.0, Apache-2.0, Apache-2.0, BSD-2, MIT |
| PCRE2 (for the sentence splitter) | `pcre2-10.49`, `6f9d7c1373262c541324a16a358785b33ef116cf` | BSD-3 |
| Zstandard (decompressor only) | `v1.5.7`, `f8745da6ff1ad1e7bab384bd1f9d742439278e99` | BSD-3 |

Only `inference/` of `mozilla/translations` is checked out (a blob-less,
sparse clone), and only the five submodules above: Marian's configure would
otherwise clone CUDA's NCCL, FBGEMM, emsdk and a second Marian, so the script
marks every other submodule `update = none`. Two edits are made to the
checkout, both in `build.sh`: SentencePiece's trainer has a `constexpr` enum
cast that clang 21 (NDK r29) refuses (the trainer is linked, never run), and
Marian's generated `build_info.cpp`, which bakes every CMake cache value,
build paths included, into the library, is replaced with empty strings.

The notices of everything linked in are in
`app/src/main/assets/licenses/bergamot-third-party.txt`, assembled from the
pinned sources' licence files; the MPL-2.0 text is `mpl-2.0.txt` beside it.

## Models

The models are not bundled. `catalog.py` reads Mozilla's public Remote
Settings collection `translations-models-v2` (what Firefox's
`TranslationsParent` reads) and writes
`core/intelligence/.../core/translate/BergamotCatalog.kt`: for every language
direction, the files a release build of Firefox for Android would take
(newest released 3.x model, Android or unfiltered records, every file of the
model's own version), each with its address on Mozilla's attachment CDN, its
size and SHA-256 as downloaded (zstd) and as unpacked. The keyboard downloads
a direction's files, checks the zstd file, unpacks it with this library and
checks the result again. The models are Mozilla's, under the MPL-2.0.

As pinned on 2026-10-20: 114 directions, every language to and from English,
13.5 to 36.5 MB a direction to download, 20.8 to 54.8 MB unpacked.

## Build choices

| Choice | Why |
|---|---|
| API 28 (Android 9), not the app's 24 | Marian's path library (pathie-cpp) calls `iconv`, `nl_langinfo` and `glob`, which bionic has from API 28. `BergamotNative.MIN_SDK` keeps older phones from loading it |
| ARM: ruy for both the int8 and the float GEMM, simde in place of SSE | Marian's own ARM path, which Firefox's ARM builds use; ruy picks the dot-product kernels at run time |
| armeabi-v7a: `stub.c`, no engine | simd_utils' sse2neon uses AArch64-only types (`float64x2_t`), so Marian has no 32-bit ARM build. Play wants every split to cover the base's two ABIs, so 32-bit ARM gets a library whose `JNI_OnLoad` fails; `BergamotNative.supported` wants a 64-bit process anyway |
| Vocabulary paths in the config as well as in memory | Marian indexes `vocabs` while it builds the embedding layer. Firefox's WASM reads past an empty list harmlessly; native code crashed on it (found on the desktop test below) |
| Marian's defines copied onto Bergamot's sources and the bridge | Marian sets `ARM`, `SSE` and its GEMM backend for its own directory only; the headers the others include read them too |
| `c++_static`, `--gc-sections`, `--icf=all`, `--exclude-libs,ALL`, a version script | one self-contained library exporting only `JNI_OnLoad` and the bridge |
| `-z max-page-size=16384` | 16 KB page devices |
| `-ffile-prefix-map` for every source tree, the build directory and the NDK | the same bytes from any machine with NDK r29, which F-Droid's reproducible build relies on |

Size, stripped: arm64-v8a 6,732,632 bytes, the armeabi-v7a stub 2,748.

## Tested

Not on a phone. The same CMake project, built for macOS arm64 (the ruy path,
Apple clang, with `-Wno-error=vla-cxx-extension` for Marian and
`fdopen=fdopen` for its old zlib, both macOS-only) and loaded into a JVM
through these exact JNI names, translated with Mozilla's pinned files:
French → English, French → English → German through `nativePivot`,
English → Japanese (separate source and target vocabularies) and Bengali →
English (a `tiny` model); an emoji came through intact, a repeated sentence
came from the cache, and a missing file threw a Java exception.
`nativeUnzstd` produced files matching every pinned unpacked SHA-256 and
refused a truncated download. Two builds of both ABIs, one from fresh
clones and one from existing checkouts in other directories, came out byte for
byte the same (NDK r29, CMake 4.4, macOS host).

## Contract with Kotlin

Text crosses as UTF-8 `byte[]`: JNI's modified UTF-8 would split an emoji
into two surrogates. A service translates on the calling thread
(`BlockingService`), and no handle may be used from two threads at once.
Marian's fatal errors become a `RuntimeException` (`JNI_OnLoad` turns its abort
into a throw), so a bad file costs a translation, not the keyboard.

- `nativeNewService(cacheSize) -> Long`, `nativeFreeService(handle)`.
- `nativeLoadModel(model, lex?, vocabs[1 or 2], int8shiftAll) -> Long`: reads
  the files into aligned memory (256 bytes for the model, 64 for the rest, as
  Firefox does) with Firefox's engine settings; `nativeFreeModel(handle)`.
- `nativeTranslate(service, model, byte[][]) -> byte[][]` and
  `nativePivot(service, first, second, byte[][]) -> byte[][]`, in order.
- `nativeUnzstd(source, target) -> String?`: null on success.
