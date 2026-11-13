# ctranslate2-jni: NLLB-200 translation on the device

OpenNMT's [CTranslate2](https://github.com/OpenNMT/CTranslate2) (MIT) with
Google's [SentencePiece](https://github.com/google/sentencepiece), built for
Android with a small JNI bridge into `libwmct2.so`. It runs Meta's NLLB-200
model (distilled 600M, int8) as the translate tool's third on-device engine,
for the pairs ML Kit and Bergamot have no model for. Kotlin entry point:
`com.wasimaster.wmkeyboard.core.translate.Ct2Native` (in
`core/intelligence/src/main`), driven by `NllbTranslator`.

The built libraries are committed under `core/intelligence/src/ct2/jniLibs/`,
a directory of their own so that the Play split `:feature:ct2` carries
nothing else. GitHub builds pin them for download (native gate `ct2`),
F-Droid deletes the committed copies and builds them with this script (through
`native/build-fdroid-libs.sh`), and Play ships them in the split. Rebuild
after you change anything here:

```sh
ANDROID_NDK=/opt/homebrew/share/android-ndk native/ctranslate2-jni/build.sh
```

It needs git, CMake and Ninja on `PATH`, and fetches the sources at their
pinned commits unless `CT2_SRC` and `SPM_SRC` point at checkouts of them
(`SPM_SRC` with abseil already cloned into `third_party/abseil-cpp`).
`WMKB_ABIS` narrows the build to one of the two ABIs.

## Provenance

| Source | Pin | Licence |
|---|---|---|
| OpenNMT/CTranslate2 | `v4.8.4`, `5de572ef60b67a0276c5989ee73d0072808ff4c3` | MIT |
| its submodules `ruy` (+ `cpuinfo`), `spdlog` (bundles {fmt}) | the commits that tag records | Apache-2.0 (+ BSD-2), MIT |
| google/sentencepiece | `v0.2.2`, `e0cce7d37b065b5140349dbe12c6bcf6192fdd78` (bundles protobuf-lite, darts-clone) | Apache-2.0 (BSD-3, BSD-2) |
| abseil/abseil-cpp | `20260526.0`, `5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a`, the tag SentencePiece's CMake fetches | Apache-2.0 |

One edit is made to the checkout, in `build.sh`: bionic has no
`pthread_setaffinity_np`, so `thread_pool.cc`'s affinity code takes the
branch other systems take (it refuses when asked; the bridge never asks).
SentencePiece's CMake would clone abseil at configure time through a form of
FetchContent that ignores every "disconnected" switch, so `build.sh` clones
the same tag first and `CMakeLists.txt` adds it itself.

The notices of everything linked in are in
`app/src/main/assets/licenses/ct2-third-party.txt`, assembled from the
pinned sources' licence files.

## The model

Not bundled, not in the library. `NllbTranslator.FILES` pins the four files
of `JustFrederik/nllb-200-distilled-600M-ct2-int8` at revision
`302d78f00e6fdb50a1064059df7c392b735e9d05` (a CTranslate2 conversion of
`facebook/nllb-200-distilled-600M` with int8 weights) by size and SHA-256:
`model.bin` (622,595,991 bytes), `config.json`, `shared_vocabulary.txt` and
`sentencepiece.bpe.model`, 630,016,302 bytes in all. The weights are Meta's,
under CC BY-NC 4.0 (`cc-by-nc-4.0.txt` beside the notices); the phone fetches
them from Hugging Face only after the user accepts that licence
(`NativeConsent.NLLB`), so this project never redistributes them.

## Build choices

| Choice | Why |
|---|---|
| ruy for the int8 and the float GEMM, no BLAS, no OpenMP | `backend.cc` picks ruy for both when nothing else is compiled in, and ruy has its own thread pool. ruy chooses its dot-product kernels at run time on the phones that have them |
| API 24 | nothing in either library needs more |
| armeabi-v7a: `stub.c`, no engine | 600 MB of weights held in memory is more than a 32-bit process can count on. Play wants every split to cover the base's two ABIs, so 32-bit ARM gets a library whose `JNI_OnLoad` fails; `Ct2Native.supported` wants a 64-bit process anyway |
| `c++_static`, `--gc-sections`, `--icf=all`, `--exclude-libs,ALL`, a version script | one self-contained library exporting only `JNI_OnLoad` and the three bridge functions; it needs only `liblog`, `libdl`, `libm` and `libc` |
| `-z max-page-size=16384` | 16 KB page devices |
| `-ffile-prefix-map` for both source trees, the build directory and the NDK | the same bytes from any machine with NDK r29, which F-Droid's reproducible build relies on |

Size, stripped, NDK r29: arm64-v8a 2,745,040 bytes, the armeabi-v7a stub
2,772.

## Contract with Kotlin

Text crosses as UTF-8 `byte[]`: JNI's modified UTF-8 would split an emoji
into two surrogates. Language tags are FLORES-200 codes (`ben_Beng`) as
strings. No handle may be used from two threads at once. Every C++ exception
becomes a `RuntimeException`, so a bad file costs a translation, not the
keyboard.

- `nativeLoad(modelDir, threads) -> Long`: reads the model into memory
  (int8 weights, float32 activations) and the SentencePiece model; `nativeFree(handle)`.
- `nativeTranslate(handle, byte[][], sourceTag, targetTag, beam) -> byte[][]`:
  one sentence each, in order, decoded together as one batch. The source is
  `[sourceTag] + pieces + "</s>"` and decoding starts from `targetTag`, as
  Hugging Face's NLLB tokenizer does; the result drops that first token.
  Decoding stops at twice the longest source plus ten tokens, at most 256.
  The output layer is cut to the pieces the target's script can produce (see
  "The output layer, cut to the target's script" below); Kotlin passes
  nothing extra for it.

## Tested

Not on a phone. The same CMake project built for macOS arm64 (Apple clang,
`-std=c++17` for abseil's check, JNI headers from a JDK) and loaded into a
JVM through these exact JNI names translated a fixed set of 40 sentences
(five English sentences into Bengali, Hindi, Arabic, Swahili and French, and
three of each back into English) with the pinned model, giving the same text
as CTranslate2's own Python wheel.

Measured on an M2 (8 cores) while other builds kept its load average between
25 and 50, so wall times are noise; instruction counts and memory are not:

| | |
|---|---|
| Load | 2.6 to 4.1 s |
| Memory, model loaded | 720 MB peak footprint |
| Memory, while translating | 1.25 GB peak footprint: ruy packs each weight matrix per call into a buffer it keeps, the 256,206 × 1024 output projection included |
| Work per output token, one thread | about 790 M instructions; about 140 ms of CPU on the moments the machine was quiet (a 15-token sentence in about 2 s, English out in about 1 s) |
| With ruy's dot-product kernels forced on | 17 % fewer instructions, the same cycles. macOS reports no dot-product to ruy, so the desktop run took the older NEON kernel; phones from the Cortex-A55/A75 on get the dot-product one |
| Five sentences in one call | 3.7 s against about 2 s for one alone, both in the machine's quiet minutes: about the cost of two |

A profile shows ruy's int8 kernel and its packing of the weights taking the
time in about equal parts: every decoding step repacks about 430 M int8
weights (the decoder's and the output projection's). So a phone's time per
token is bound by memory traffic as much as by its cores. From these numbers
a recent phone's big cores should take roughly a second for a short
sentence and an older one several; that is an estimate, not a measurement.
`Ct2Native.MIN_TOTAL_RAM` (5.5 GB, a 6 GB phone) follows from the 1.25 GB.

Beside Bergamot, on the pairs both have (the same desktop, Bergamot's own
build of `native/bergamot-jni` with Mozilla's pinned models, load average
about 240): Bergamot took 20 to 108 ms a sentence where NLLB took seconds,
thirty to a hundred times faster. Into English the two read the same
(Bengali, Hindi, Arabic and French each gave identical or equally good
sentences). Out of English Bergamot was as good or better: NLLB's Arabic
turned "this keyboard" into "these keys" and put "cancelled" in the active
voice, both of which Bergamot got right, and its Hindi "I will be late" came
out as "I will delay". NLLB's Bengali was the more natural of the two in one
sentence. Swahili, which Bergamot lacks, came out of NLLB readable with one
wrong word ("cancelled" as "rejected"). Hence the order in the translate
list: Bergamot where it has the pair, NLLB for the rest.

Two builds of both ABIs from fresh clones in different directories
came out byte for byte the same (NDK r29, CMake 4.x, macOS host).

The figures above are for the whole vocabulary, before the cut described
next.

## The output layer, cut to the target's script

Each decoding step multiplies the decoder's state by the output projection,
256,206 rows of 1,024 int8 weights: 262 M of the roughly 430 M weights a step
repacks, and the matrix behind ruy's largest packing buffer. A sentence into
Bengali can only use Bengali pieces, pieces with no letters in them (digits,
punctuation, "▁") and pieces copied from its source, such as a name or a link.
So the bridge decodes against those rows alone.

CTranslate2's own mechanism for this, `use_vmap` with a `vmap.txt` beside
`model.bin` (`VocabularyMap` in `src/vocabulary_map.cc`), cannot express it.
Its rules are keyed on n-grams of the source tokens; the empty key lists
pieces that are always candidates, and the target prefix's ids are added to
every set. The file is read once, when the model loads. Nothing in NLLB's
source names the target (it is `[sourceTag] + pieces + "</s>"`), so a map per
target would need its own model load, another 600 MB each, or a source token
that names the target, which would change what the encoder reads.

The bridge restricts the layer itself, through CTranslate2's public API and
with no patch to it. `Translator::post` runs a function on the translator's
own worker thread (its thread pool and ruy context), and that function does
what `translate_batch` does for this model: the encoder through
`EncoderDecoderReplica::encoder()`, then
`Decoder::update_output_layer(multiple, ids)` and `ctranslate2::decode`.
With the cut turned off, this path gave the same bytes as `translate_batch`
on every sentence below.

The cut comes from the target tag alone. Its script code (`Beng` in
`ben_Beng`) picks Unicode blocks from a table in `ct2_jni.cpp`. A piece is
kept when every character is either in those blocks or in no script
(ASCII digits and punctuation, Latin-1 signs, combining accents, general
punctuation and symbols, CJK and full-width punctuation, emoji). Other
languages' tags are dropped. The source's own ids, the target tag and
`<unk>`, `<s>` and `</s>` are added on every call. Each script's list is
built once per handle, on first use, from `shared_vocabulary.txt` as
loaded. A script missing from the table decodes against the whole
vocabulary. Rows kept:

| Target script | Rows | Share |
|---|---|---|
| Latin | 141,831 | 55.4 % |
| Cyrillic | 26,216 | 10.2 % |
| Hangul (with Han) | 18,864 | 7.4 % |
| Arabic | 17,705 | 6.9 % |
| Japanese (Han and kana) | 17,171 | 6.7 % |
| Devanagari | 12,054 | 4.7 % |
| Simplified or Traditional Han | 10,879 | 4.2 % |
| Bengali | 7,569 | 3.0 % |
| Ethiopic | 7,182 | 2.8 % |
| Tamil | 5,748 | 2.2 % |
| Thai | 5,242 | 2.0 % |

CTranslate2 4.8.4 (like 4.8.3) has a bug here that the bridge works around:
`update_output_layer` builds its reverse map (`_to_output_word_id`) with
`emplace` and clears it only on the way back to the full vocabulary. Two
different cuts in a row keep the first one's entries, so the forced target
tag, and the ids after it, map to the wrong rows. The output turned to
garbage from the third sentence on. The bridge resets to the whole
vocabulary before each cut, which clears the map; with a size multiple of 1
on the CPU the reset copies nothing. `use_vmap` goes through the same code,
so its users hit the same bug whenever their candidate sets differ between
calls. `src/layers/decoder.cc` upstream has not changed since 2023.

### Measured

The 40 sentences have the same layout as the set above: five English
sentences into Bengali, Hindi, Arabic, Swahili and French, three of each back
into English. The set itself was rewritten for this test, because the
earlier one was not kept. The run used an M2, one thread, one sentence per
call, in a JVM through the JNI names. Instruction counts come from
`/usr/bin/time -l`, with the 3.0 to 3.3 G that starting the JVM and loading
the model cost (a run that only loads) taken off. The load average stayed
between 20 and 40, so wall times are left out.

| Targets | Instructions, whole vocabulary | Cut | Change | Peak footprint, whole | Cut |
|---|---|---|---|---|---|
| Bengali, Hindi, Arabic (15 sentences) | 148.9 G | 67.4 G | −55 % | 1,261 MB | 796 MB |
| English, French, Swahili (25 sentences) | 205.9 G | 153.6 G | −25 % | 1,259 MB | 1,182 MB |
| All 40 in one process | 339.7 G | 223.8 G | −34 % | 1,264 MB | 1,217 MB |

All 40 translations came out the same, byte for byte, with and without the
cut. Five-sentence batches matched as well. Loading still peaks at about
720 MB.

Into a non-Latin script, decoding now costs about half the work, and the
peak footprint ends up about 75 MB above the loaded model rather than 540 MB
above it. Into a Latin script the cut keeps 55 % of the rows and peak memory
barely moves: going by the code, `select_weights` copies the kept rows
(145 MB) and ruy then packs that copy, which together come to about what
packing the whole projection took. A process that has translated
into both kinds keeps the larger figure. `Ct2Native.MIN_TOTAL_RAM` stays
where it is for that reason: the translate list offers Latin targets on
every phone.

On Android nothing else changes: no download, no new file, no Kotlin. The
rebuilt library carries the cut, and the restricted path runs for every
target whose script is in the table. A tighter cut for Latin targets would
need a list per language rather than per script. Letters alone do little:
pieces made only of ASCII letters number 112,275, and with French accents
added, 117,754. A list built from text in each language would have to be
shipped and kept in step with the model, and its effect on unusual words
has not been measured.

The libraries committed with this change came out of `build.sh` twice from
the same source with the same bytes, and a build of the bridge before it
reproduced the library it replaced. Not run on a phone yet.
