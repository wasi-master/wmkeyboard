---
title: "Architecture"
description: "Module layering, design decisions, and the seams that hold WM Keyboard together."
sidebar:
  label: "Architecture"
  order: 1
---

## Overview

WM Keyboard is a multi-module Gradle build with strict layering: `:app` on
top, `:feature:*` in the middle, `:core:*` at the bottom. Package names keep
their original `com.wasimaster.wmkeyboard.*` layout, and module boundaries
follow the packages. A class's module is visible from its path, not its
package. Every `:core:*` and `:feature:*` module is an Android module,
so the split that matters is per file rather than per module: the engines
themselves (tries, transliterators, layout compilers, parsers) avoid
`android.*`/`androidx.*` entirely and stay unit-testable on the JVM, while the
stores, the settings DataStore and the whole UI layer sit alongside them and
do not. See [testing](/development/testing/) for what that means in practice.

```
┌─────────────────────────────────────────────┐
│ :app  settings activity, manifest, assets   │
├──────────────┬──────────────────────────────┤
│ :feature:ime │ :feature:addons :feature:tools│
│ IME service +│ addon install   network tool  │
│ Compose UI   │ pipeline        clients       │
├──────────────┴──────────────────────────────┤
│ :core:settings (+ :core:intelligence,       │
│  :core:feedback above it)                   │
├─────────────────────────────────────────────┤
│ :core:*  engines and stores                 │
│  language input prediction emoji theme      │
│  icons tools content addons voice plugins   │
├─────────────────────────────────────────────┤
│ :core:common (+ :core:config build flags)   │
└─────────────────────────────────────────────┘
```

Notable seams:

- **`:core:config`** generates the library-side `BuildConfig` (channel and
  feature flags, API keys). No library module has a product flavor; the one
  build switch that changes code, `wmkb.enableFdroid`, is read by each module
  that has ML Kit code and picks its `src/google` or `src/nogoogle` sources.
- **Split packages are deliberate.** `core.settings.ToolbarTool` lives in
  `:core:common` so icon packs and tool engines can name tools without
  depending on the settings module; the network clients in `:feature:tools`
  share the `core.tools` package with the offline engines in `:core:tools`.
- **The IME never depends on `:app`.** It launches the settings activity
  through `MainActivityContract` (an explicit component name in
  `:core:common`) and owns the permission trampoline activities' classes.

## Key decisions

### Compose inside the IME window

`InputMethodService` predates architecture components, so its window has no
ViewTree owners. `KeyboardViewLifecycleOwner` implements `LifecycleOwner`,
`ViewModelStoreOwner` and `SavedStateRegistryOwner`, is attached to the IME
decor view, and is driven from the service lifecycle callbacks
(`onStartInputView` → resume, `onFinishInputView` → pause). This is the same
approach used by production Compose keyboards (e.g. FlorisBoard).

### Unidirectional data flow

The service owns a single `MutableStateFlow<KeyboardUiState>`. The Compose tree
collects it and renders. Every interaction calls back into the service
(`onKey`, `onSuggestion`, `onEmoji`, …), which mutates state through `copy()`,
so anything the text field or the engines can see lives in that one object. The
view layer keeps its own transient state on top (press, measured panel sizes,
which bar is expanded, a drag in progress), but none of it survives the
composition or reaches the service.

### Composing region as the source of truth

A `Composer`, picked from the active layout's script, decides what a keystroke
does. Latin layouts type into an `InputConnection` composing region purely to
feed suggestions, so the region is dropped in password fields and with the
strip off. A transliterating composer (Avro, Hangul, Vietnamese) has to compose
everywhere, because its buffer *is* the input method: the composing preview is
the live transliteration, so the user watches বাংলা appear as they type
romanized text. Avro is the one that commits through the dictionary, where the
top phonetic-index candidate wins on space or a suggestion tap. Hangul and
Vietnamese commit the composed text straight out, with no dictionary pass. A
cluster-shaping composer (Probhat, Jatiya, fixed Devanagari) types its script
straight into the field and never starts a buffer of its own, though it will
keep composing one the caret handed back to it.

### Bangla phonetics: two engines, one job

- `AvroPhonetic` is a deterministic greedy longest-match transliterator (rules
  → glyphs). It answers "what did the user literally type?" The rules run in
  Rust: every phonetic engine, Khipro, the Bijoy converter, the grapheme
  helpers and the romanizers live in the `native/wm-engine` crate, which the
  desktop app shares, and the Kotlin objects in `core/transliteration` are
  facades over one JNI library (`native/wm-engine-jni`, loaded by
  `EngineNative`). The word-list indexes stay in Kotlin.
- `BengaliPhoneticIndex` answers "which real words sound like this?" by folding
  both roman input and dictionary words into a lenient canonical key
  (স/শ/ষ/ছ/চ → s, aspiration dropped, inherent vowels dropped). This is what
  turns `asi` into আছি while আসি stays one tap away.

Corrections that need context (আসি vs আছি) are handled at ranking time,
deliberately, rather than inside the transliterator.

Hindi has the same pair, `HindiPhonetic` and `HindiPhoneticIndex`, and the
balance between them is different. Hinglish has no spelling scheme, and spoken
Hindi drops its inherent vowel, so the rules cannot tell a conjunct from a
dropped schwa (`namaste` against `karna`): they guess, and offer their other
readings as `variants()`. The index folds away everything the spelling does not
carry (virama, every inner "a", dental against retroflex) and is what actually
knows the word. It is built over a downloaded list, since Hindi bundles none.

Both languages plug in through one seam. A composer names its language in
`Composer.phoneticLanguage`; `PhoneticSchemes` maps that to the rules, the
index builder and the spelling assets; the engine resolves a `PhoneticBackend`
from it. Nothing in the engine or the service names Hindi.

### Native conversion engines

The CJK composers are Kotlin, and a native engine (RIME for Chinese, Mozc for
Japanese) stands in for one of them rather than beside it.
`LayoutSpec.resolvedComposer()` builds the layout's built-in composer, then
asks `ConversionEngines` (`core/input/.../composer/ConversionEngines.kt`)
whether a registered `ConversionEngine` replaces it for the layout's
`ComposerType` and language. An engine answers from fields alone, because
the service resolves a composer on every layout bind and settings emission;
anything slower happens in the background, and the engine calls
`ConversionEngines.changed()` when its answer moves. The service collects
that signal and resolves its composer again between words.

The replacement keeps the built-in composer as its `base` and delegates
every question about *keys* to it: which characters the buffer takes,
whether digits start it, what the composing region shows, what Enter and
backspace do. The layout is unchanged, so its keys must behave exactly as
before. Only conversion moves: `candidates`, `consumedForIndex` and
`learnChoice`. When the engine cannot answer (still compiling, library
dropped) the replacement converts with `base` instead, so typing never stops.

RIME's half (`core/input/.../rime/`):

- `RimeEngine` is the `ConversionEngine`. The service pushes the user's
  choices into it from the settings collector, the way it fills `CjkConfig`.
- `RimeRuntime` owns librime's one instance: readiness (storage unlocked,
  library loaded, a compile that matches what is installed), compiling with
  progress, and the single session. Composers reach the session through
  `tryLock`, so a compile on a background thread never blocks a keystroke.
- `RimeComposer` gives the session the whole reading, the shape the
  `Composer` contract already has, and keeps it while the service picks its
  way through it: a prefix pick leaves librime one segment further on, which
  is how its user dictionary learns the whole phrase at the last pick. Spans
  are librime's own `Candidate::end()`, mapped back to buffer characters
  through `RimeKeys`, and looked up by index, so prefix commit stays exact.
  Simplified and Traditional are librime filter switches, applied inside its
  menu before any span is measured, never to a finished list.
  On the 9-key pad the syllable column and its picks stay `base`'s
  (`T9PinyinComposer`): `RimeKeys` puts a `'` after a picked syllable, since
  Rime Ice's T9 algebra spells each syllable in letters and digits mixed and
  would read `xi26` as `xiao`, and the column and composing text follow the
  spelling librime hints in its first candidate's comment. 联想 is the
  built-in Mandarin predictor (`PinyinComposer.predictions`), handed in by
  `RimeEngine` for Chinese: librime-predict looks up the exact last commit,
  which RIME's phrase commits rarely match.
- `RimeStore` keeps the unpacked packs, imports and compiled tables under
  `filesDir/rime`, in credential-protected storage beside the other learned
  words. `restrictedToDirectBoot()` empties `rimeLanguages`, so a locked
  phone types on the built-in composers.
- Schema packs are ordinary `CjkDictPack`s (`RimeSchemaCatalog`), so download,
  checksum and offline import are the CJK pack path unchanged.

Mozc's half (`core/input/.../mozc/`):

- `MozcEngine` replaces the composer for `ComposerType.ROMAJI` in Japanese,
  which every Japanese layout (romaji, flick, JIS kana) types through.
- `MozcRuntime` opens Mozc once both the library (`mozc` gate) and the
  dictionary (`MozcData`, a `CjkDictPack` holding Mozc's own `mozc.data`)
  are on the device and storage is unlocked, on a background thread, and
  guards every call with `tryLock` like RIME's runtime. What Mozc learns
  lives in `filesDir/mozc/user` and is written out a few seconds after a pick.
- `MozcComposer` hands Mozc hiragana, never keys: `Kana.transduce` composes
  the buffer as the built-in composer does, and the spans it keeps map a
  length Mozc reports in kana back to buffer characters. The bridge
  (`native/mozc-jni/mozc_bridge.cc`) sits on Mozc's `ConverterInterface`
  rather than its session, because only the converter's `Segments` say how
  much of the reading a partial suggestion covers
  (`Candidate::consumed_key_size`). Each call starts from the whole reading;
  only Mozc's history segments carry over, which is its context and what
  its learning reads.
- Segments are a second list. `Composer.segmentsReading`,
  `resizeSegment`, `moveSegment`, `focusedSegment` and `committedText` let
  the service's arrow keys split and resize a reading and mark the focused
  segment in the composing region; the composer replays the operations from
  Mozc's own split on each call, and a pick commits the segments up to the
  focused one. `remainderAfterCommit` keeps the rest split.

### Prediction

`Trie` is frequency-weighted and serves prefix completions. `UserLexicon`
overlays the user's learned words, heavily boosted, plus bigrams, trigrams and skip-grams
for next-word prediction. `SuggestionEngine` merges, ranks and case-matches
those sources. Corrections come from `FuzzyBeamSearch`, one best-first walk
down the tries themselves rather than Norvig generate-and-test: only strings
the dictionary can actually reach are ever considered, which is what makes
two-edit corrections affordable and lets substitutions come from a script's
own edge labels instead of an ASCII table. The walk runs in Rust, over a Rust
twin of every trie it covers. Learning is skipped for secure fields and in
incognito mode. Everything is JSON on private storage, with
one-tap clearing.

### The native engine and the move to Rust

Prediction's engines live in `native/wm-engine`, the Rust crate that also
holds the phonetic engines, so the phone and WMK Desktop run one
implementation: the dictionary and n-gram readers, the correction walk, the
learning stores and the glide decoders. `SuggestionEngine`'s orchestration
stays in Kotlin. Each kept its Kotlin copy until a
parity test showed the Rust one gives identical output on the real
dictionaries and the eval sets, bit for bit; then the copy left the app for
the test sources, where it stays as the reference that test compares
against (`reference/` under `app/src/test/.../prediction` and `.../gesture`).

`NativeEngine` (`core/language`, package `core.engine`) loads
libwm_engine_jni once for every class that calls into it, on a device and in
a JVM test alike. `NativeEngine.forceKotlin` pins the SIMD kernels to their
scalar references; `WM_NATIVE=kotlin` sets it for a test run.

On the Rust side, `wm-engine`'s `kernels` module holds the data-parallel
loops. Each has a scalar reference, an aarch64 NEON version on the stable
intrinsics, and a dispatcher that reads the CPU features once at run time
(`dotprod` and `fp16` are optional extensions), with a test comparing every
SIMD version against the reference. Kernels serve more than prediction: the
scanner's PaddleOCR reader shrinks the photo for its detector and picks each
recogniser step's best class through them (`box_average`, `argmax`). Those two
keep their Kotlin loops, which run when the library is missing or
`forceKotlin` is set. The sticker GIF encoder (`wm_engine::gif`, behind
`GifEncoder` when a field takes no WebP) is Rust whole, with NEON for its
per-pixel loops (`kernels/gif.rs`), and has no Kotlin copy left in the app:
like prediction's, its old Kotlin is the reference `GifParityTest` checks the
bytes against (`feature/ime/src/test/.../core/media/reference`). In `wm-engine-jni`, every entry point runs inside `guard::guarded`, so a Rust panic arrives in Kotlin as an
exception instead of killing the keyboard. Data that outlives a call, such as
a mapped dictionary, stays in Rust behind a `handles::Registry` handle.
Calls are batched, one crossing per query or per array.

The dictionary and n-gram readers moved first. `MappedTrie`'s word queries
and all of `MappedNgramPack` are Rust, over a mapping that stays in the
library behind a handle; `NativeHandles` closes it once the Kotlin object
that held it is unreachable, because the IME replaces a dictionary by
dropping the reference rather than closing it.

The correction walk came next. A walk takes its tries by handle: a mapped
dictionary is its own twin, a `PackedTrie` gets a copy of its codec bytes,
the user lexicon's trie is Rust already, and a plain `Trie` (only tests build
one) gets an exact copy, rebuilt whenever its version counter moves. A
`.wmdict` would not do for that last one, because the codec rounds
frequencies.

The learning stores followed. `UserLexicon`, `NgramReranker`,
`CorrectionMemory` and `CorrectionStats` are thin Kotlin classes over Rust
stores that read and write the same JSON files as before. Two things stay in
Kotlin because they lean on the JVM's Unicode tables: folding a word to its
key (`WordKey.of`) and deciding whether a word may be learned at all
(`WordContext.isLearnableWord`). Loading a file is therefore two steps: Rust
parses it, Kotlin judges and folds each word, Rust builds the store. The old
Kotlin stores live on under `app/src/test/.../prediction/reference/` as the
references the parity tests compare against.

The glide decoders came last: `GlideBeam`, the two-thumb `DuetBeam` and the
neural `CtcGlideBeam`'s beam search. The user's learned shapes stay in
Kotlin, so a decode that reads them crosses twice: the walk says which words
the shape channel will score, Kotlin looks their shapes up, and the second
call finishes the decode. Key maps cross once, by handle. The workspace
parameters the decoders still take are kept for their callers and carry
nothing; the state pools are Rust's, one per decoding thread.

### Emoji search

The catalog is a TSV asset (`emoji/catalog.tsv`) with English and Bangla
keywords merged into a single token index, so multilingual search falls out for
free. Query scoring: exact shortcode (1,000) > shortcode prefix (500 minus
rank) > exact token (100) > curated synonym expansion (60) > keyword prefix
(40) > Damerau-Levenshtein distance-1 fuzzy match (30), summed across query
tokens. The search field lives inside the keyboard: while it is active, letter
keys feed the query instead of the app.

### Persistence

- **Settings** use Preferences DataStore, exposed as a
  `Flow<KeyboardSettings>` the service collects, so changes apply live without
  restarting the IME.
- **Learning data, emoji usage and clipboard** are kotlinx-serialization JSON
  files under `filesDir`. Room was deliberately avoided for the MVP: the data
  sets are small and append-mostly, and the KSP/AGP compatibility surface
  during the AGP 9 transition wasn't worth it. Revisit if any store outgrows
  JSON.

### Direct boot

The IME service is `directBootAware`, because the keyboard is what the user
types their PIN on. A keyboard that cannot run before the first unlock is one
the platform silently replaces on the lock screen.

In that window there is no credential-encrypted storage at all: no `filesDir`,
no settings DataStore, no learned words. The keyboard runs on what
device-protected storage can hold, which deliberately excludes anything of the
user's:

- **Settings.** `LockedSettings` keeps a mirror of the DataStore in
  device-protected storage, rewritten on every change while unlocked and read
  (never as a source of truth) while locked. `SettingsBackup.SECRET_KEYS`, the
  API keys and tokens, is filtered out on the way in, since that storage is
  not covered by the user's credential.
- **Personal stores.** The learned lexicon, clipboard, snippets, emoji history
  and sticker packs are constructed with a null file, which every one of them
  already treats as "memory only, never persisted". A locked session learns
  nothing and writes nothing.
- **Dictionaries.** The bundled `.wmdict` lists are inflated into
  device-protected storage (they come out of the APK, so nothing is exposed by
  it) and serve both states from one copy. Downloaded and imported lists stay
  behind the credential, so prediction while locked knows only the words that
  shipped with the app.
- **Everything else.** `KeyboardSettings.restrictedToDirectBoot()` switches
  off, in one place, every feature whose data is unreadable: custom fonts and
  theme images, contact and app-name suggestions, offline dictation, and the
  tools that fail the `isDirectBootSafeTool` test. Downstream code (toolbar,
  toolbox, shortcuts, renderer) needs no direct-boot awareness of its own.

`ACTION_USER_UNLOCKED` arrives while the keyboard is often still on screen, so
the service re-attaches the real stores, rebuilds the suggestion engine around
them and flips the repository back to the DataStore in place, rather than
waiting for the process to be restarted.

### Power saving

Power saving reuses direct boot's shape rather than inventing one. A pure
function, `KeyboardSettings.underPowerSaving()`, returns the settings *as they
apply* while it is on. The service combines the DataStore flow with
`core/settings/.../power/PowerSaver`'s device state and applies the view on the
way out, so the renderer, the suggestion engine and the tool handlers all see one
already-reduced settings object, and none of them knows power saving exists.
Nothing is persisted, which is what makes it reversible: the user's own
settings are never rewritten, only hidden, so ending power saving restores them
exactly.

`PowerSaver` deliberately does *not* subscribe to `ACTION_BATTERY_CHANGED`.
That broadcast fires once per percentage point, and waking the process that
often to decide whether to save power defeats the feature. It subscribes to the
coarse broadcasts instead (battery low/okay, charger in/out, the system
battery-saver toggle) and reads the exact level from the sticky battery intent
when the keyboard comes on screen, which is the only moment the answer can
matter.

### Screen readers and the touch stream

While an explore-by-touch service (TalkBack) runs, the accessibility
framework's input filter consumes touches before any window sees them. So every
gesture the keyboard owns is dead: the spacebar cursor slide, the backspace
word swipe, glide typing, handwriting. No app-side workaround reaches the
events. `ScreenReaderMode` therefore offers four behaviours: `OFF`, `LABELS`
(spoken names, direct typing), `EXPLORE` (hand the keys to TalkBack's own
hover-and-activate) and `PASSTHROUGH`.

`PASSTHROUGH` uses the one supported escape hatch,
`AccessibilityService.setTouchExplorationPassthroughRegion` (API 30). Only an
accessibility service may call it, which is why the `core.accessibility`
package in `:core:common` exists:

- `TouchPassthroughService` is an accessibility service that exists solely to
  publish that region. It subscribes to no events, cannot retrieve window
  content, and adds `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` only while some
  *other* enabled service already explores by touch. Requesting it
  unconditionally would switch explore-by-touch on for a user who never asked
  for a screen reader.
- `KeyboardPassthrough` is the in-process channel between the two (the IME and
  the service share the app's process). `KeyRows` publishes the **key grid** in
  display coordinates, never the whole window, so the suggestion strip, the
  toolbar and every panel stay explorable. The IME clears the region when the
  input view goes away.
- Inside the carve-out TalkBack no longer speaks, so `KeyButton` announces the
  key itself on press. Keys already commit on release, so a key can be heard
  before it types.

Without the service granted, the mode degrades to `EXPLORE`. Picking it and
never granting it can never leave a screen-reader user with keys that neither
announce nor explore.

### Performance

- Dictionaries and the emoji catalog load on `Dispatchers.Default` after
  `onCreate`; the keyboard renders immediately and suggestions attach when
  ready. The bundled word lists are compiled `.wmdict` binaries, so loading one
  is a single `mmap` call rather than tens of thousands of trie inserts, and
  the trie never enters the Java heap.
- Suggestion computation runs off the main thread with job cancellation on
  each keystroke.
- The layout model is immutable data, and the rule everywhere in the key grid
  is that touch state is never read at composition scope. A key's face is
  painted inside a `drawWithCache` lambda, so a press invalidates the draw
  without recomposing or re-measuring the key; a glide stroke keeps its points
  in plain arrays Compose cannot observe, and the head position is read from a
  placement lambda so the floating word pill re-places instead.

## Extension points

- **Layouts.** Add a `LayoutSpec` to `BuiltInLayouts`
  (`core/language/.../layout/BuiltInLayouts.kt`), or ship a `.wmlayout.json`
  under `app/src/main/assets/layouts/`. Nothing in the renderer changes:
  `currentLayout()` picks a layer out of the already-resolved layout set, so
  keys are data and there's no drawing code to write.
- **Languages.** Add a `LanguageDef` to `LanguageRegistry`
  (`core/language/.../script/Language.kt`), which is what a layout, a
  dictionary and a subtype all key off. The old `InputMode` enum is gone;
  `LegacyModes.kt` only survives to translate preferences written before the
  registry existed. For a bundled word list, drop a `<lang>.txt` (word plus
  frequency per line) into `app/dictionaries-src/`, where
  `compileBundledDictionaries` turns it into a `.wmdict` asset. See
  [the dictionary pipeline](/development/dictionaries/).
- **Emoji.** Append lines to `emoji/catalog.tsv`. Add synonym rows to
  `EmojiSearch.SYNONYMS` for concept queries.
- **Transliteration schemes.** Write the engine in Rust in `native/wm-engine`
  alongside `hindi.rs` and the Indic language modules, with its tests there,
  add an entry point in `native/wm-engine-jni` and a Kotlin facade next to
  `HindiPhonetic`, then give it a composer that sets `phoneticLanguage` and a
  `PhoneticScheme` entry: `transliterate()`, `variants()`, an index builder and
  the spelling-map assets. The service loads the backend when a layout that
  types through it is enabled. Rebuild the committed `.so` files with
  `native/wm-engine-jni/build.sh`; see [building](/development/building/).
- **On-device AI accelerators.** Compute choices are `LocalLlmBackend`
  values, and every one goes through the same `LlmRuntime` seam: the bridge
  (`core/intelligence/src/llmbridge/.../LitertLmRuntime.kt`) picks the
  litertlm `Backend` and owns the fallback, so callers never change. The NPU
  is the worked example. To add a chip, list it in `NpuSupport.HTP_ARCH`
  (`core/intelligence/.../localllm/NpuSupport.kt`) with its Hexagon
  generation, add that generation's QNN stub and skel to the
  `wmkeyboard.npu-runtime` convention in `build-logic`, and give the catalog
  models that have a build for it an `NpuBuild` in `LocalLlmCatalog`. The
  runtime ships as assets that `NpuRuntime` unpacks, in the base APK outside
  Play and in the on-demand `:feature:npu` split on Play. A model without a
  build for the chip needs nothing: `NpuRouting` sends it to its standard
  file on the GPU or CPU.
- **Native libraries delivered on demand.** A native library behind an
  optional feature sits behind a `ModuleGate`
  (`core/common/.../modules/ModuleGate.kt`, one per gate in the generated
  `FeatureModules`), and its facade loads it
  through `FeatureModules.loadLibrary` or a `GatedLibrary`. Each channel
  backs the gates differently. On Play they are Play Feature Delivery
  modules. On GitHub and F-Droid builds, `NativeDelivery` backs them: the
  build leaves the library out of the APK and writes its size and SHA-256
  into `assets/native/pins.tsv`, and `DownloadedModuleGate` fetches it the
  first time its feature asks.
  - The project's own libraries come from the data repository's `natives`
    release, named by hash.
  - Libraries the project may not redistribute (ML Kit, LiteRT, Qualcomm's
    QNN) are read straight out of the vendor's own Maven artifact with HTTP
    ranges (`ZipRangeReader`).
  - A dependency that loads its library itself with `System.loadLibrary` is
    served by `NativeDelivery.preload`, a plain `dlopen` from the always-shipped
    `libwm_engine_jni.so` that puts the library in the class loader's
    namespace under its soname.

  To deliver one more library, add it to a gate in `native/gates.json` (see
  below), and call `preload` before its dependency's first class loads when
  the dependency loads it.

### Adding a native gate

Every gate is one entry in `native/gates.json`, and nothing else lists them.
The build reads it to generate `FeatureModules`, to wire Play delivery and
native delivery, to write the pins and the R8 keep rules, and to include the
Play module; `native/gates.py` reads it for the release and F-Droid scripts.
An entry has:

- `id`: the gate's name in `pins.tsv` and its Play module, `:feature:<id>`.
- `property` and `doc`: the `FeatureModules` property and its KDoc. Leave
  them out for a gate whose facade keeps its own gate type (`llm`,
  `translate`).
- `play`: `split` for a Play module that only carries the libraries, `module`
  for one with its own build file, or nothing for no Play module (`scanner`).
- `libraries`: the project's own `.so` files, each with the jniLibs `dir` it
  is committed in, the `fdroidBuild` script that makes it from source,
  `abis` when it is not built for all three, and the JNI classes to `keep`
  when it binds by mangled name.
- `maven`: libraries read out of a vendor's AAR, with its `module`, a
  `catalog` alias when the app does not depend on that AAR, a `licence` when
  the download needs one, and a finer `gate` when a chip needs only some
  of them (`npu-V79`).
- `google`, `enabledBy`, `neverPackaged`: built on a Google SDK (left out of
  F-Droid's build), switched on by a flag, never packaged into an APK.

Then give the library a directory of its own (`src/<id>/jniLibs` in the core
module that loads it) so that its Play split carries nothing else. A
`split` gate also needs `feature/<id>/`, which holds a build file that only
applies `wmkeyboard.native-split` and a manifest copied from another split,
plus a `<id>_module_title` string in `:app`. F-Droid's recipe is the one place
the table does not reach: the next fdroiddata entry's `scandelete` needs the
new directory, which `python3 native/gates.py scandelete` lists.
