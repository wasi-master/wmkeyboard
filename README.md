<div align="center">

<img src=".github/media/logo.png" width="112" alt="WM Keyboard logo">

# WM Keyboard

**An Android keyboard that keeps its brain on the device.**

843 languages, Avro-style Bangla phonetic typing, a toolbox that goes well past emoji,
and a prediction stack that never phones home.

<a href="https://github.com/wasi-master/wmkeyboard/releases/latest" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/github/v/release/wasi-master/wmkeyboard?style=flat-square&label=Download&color=3DDC84&logo=android&logoColor=white" alt="Download"></a>
<a href="https://wmkeyboard.pages.dev" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Docs-wmkeyboard.pages.dev-F38020?style=flat-square&logo=cloudflarepages&logoColor=white" alt="Docs"></a>
<a href="#building" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android"></a>
<a href="https://kotlinlang.org" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin"></a>
<a href="https://developer.android.com/compose" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose"></a>
<a href="#privacy-is-the-default-not-a-setting" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/Core-fully_offline-6C5CE7?style=flat-square" alt="Offline core"></a>
<a href="LICENSE" target="_blank" rel="noopener noreferrer"><img src="https://img.shields.io/badge/License-MIT-1f6feb?style=flat-square" alt="License"></a>

<br>

<table>
<tr>



https://github.com/user-attachments/assets/3bd063a9-6701-4ccd-82ef-edd40a42b8e0



</tr>
</table>

</div>

---

## Why this exists

Every keyboard I tried made me pick two out of three: types Bangla well, has the features I
actually use, and doesn't ship my keystrokes to somebody's analytics pipeline. So I built one
that does all three.

The whole intelligence layer runs on device. Dictionaries, transliteration, autocorrect, glide
typing, emoji search, grammar checking and dictation are bundled, or downloaded once and then
run locally. Network access exists only where it has to, like translation or GIF search. Each
of those is a tool you open yourself.

It is written in Kotlin with Jetpack Compose, keyboard surface included. The code splits across
22 Gradle modules, which keeps the engines testable and free of Android dependencies.

## What you get

| | |
|---|---|
| **Languages** | 843 languages and 1,274 layouts from a data-driven registry: native scripts, InScript variants, romanized entries, CJK, even constructed ones. A fresh install picks up whatever your phone is already set to. |
| **Bangla, done right** | Avro-compatible phonetic typing for the Bengali script, with a lenient index, so `asi`, `achi` and `achhi` all land on আছি. Probhat layout too, with aspirates on shift, plus conjunct-aware backspace. |
| **Prediction** | Trie-lattice beam decoder shared by tapping and glide, n-gram context ranking, likelihood-gated autocorrect, and on-device learning of your words, bigrams and trigrams. |
| **Emoji and friends** | Emoji 17.0 catalog with semantic search in 125 languages, per-person skin tones, kaomoji, GIFs, stickers, and long-press to send Google's animated emoji. |
| **The toolbox** | Clipboard with history and pinning, snippets, translate, calculator, unit and currency conversion, camera, OCR and QR scanning, media controls, an app launcher, text editing, and more. |
| **Themes** | A real theme editor: palettes, fonts, key shapes, textures, decals, particles, photo backgrounds, plus Material You and AMOLED. Themes export and import as files. |
| **AI, voice, handwriting** | Speech recognition with a continuous mode, offline Whisper dictation, ML Kit handwriting, Harper grammar checking through a Rust JNI bridge, and an optional local LLM. |
| **Extensibility** | Addon repositories serve 12 kinds of addon. Lua plugins run sandboxed. Layouts and themes import from other keyboards, including Florisboard and HeliBoard. |

<details>
<summary><b>The full inventory, if you want numbers</b></summary>

<br>

<a href="FEATURES.md" target="_blank" rel="noopener noreferrer"><code>FEATURES.md</code></a> tracks the surface across 12 areas:

| Area | Families | Features | Capabilities |
|---|---:|---:|---:|
| Typing core: prediction, autocorrect, learning, spell check | 9 | 49 | 172 |
| Input behaviour: glide, gestures, cursor, editing, keys | 11 | 72 | 128 |
| Languages, scripts, layouts, transliteration | 11 | 59 | 186 |
| Themes and appearance | 14 | 73 | 179 |
| Emoji, GIFs, stickers, kaomoji | 16 | 88 | 93 |
| Toolbar and the tool set | 10 | 77 | 282 |
| Clipboard, snippets, text expansion | 7 | 36 | 179 |
| AI, voice, handwriting, scanning | 11 | 70 | 162 |
| Privacy, backup, storage, statistics | 12 | 54 | 136 |
| Accessibility, form factors, platform integration | 12 | 56 | 105 |
| Extensibility: addons, plugins, imports, formats | 5 | 35 | 164 |
| Modes, rows, field adaptation, runtime | 12 | 97 | 200 |
| **Total** | **130** | **766** | **1986** |

Entries marked `RARE` there are things few or no mainstream keyboards ship. There are over 400 of them.

</details>

## Privacy is the default, not a setting

- No analytics SDK, no crash reporting, no server of this project's to phone home to. (One exception: Google's ML Kit sits under handwriting and the scanners in the full edition, and it reports its own diagnostics to Google. The <a href="https://wmkeyboard.pages.dev/privacy/policy/" target="_blank" rel="noopener noreferrer">privacy policy</a> spells that out.)
- Learned words, clipboard history and typing stats stay in app storage and can be wiped in one tap.
- Password and secure fields turn off learning, suggestions and clipboard capture on their own.
- Incognito mode does the same on demand.
- Network tools are opt-in per tool, and the offline build never links them at all.
- Backups are AES-GCM encrypted and written wherever you point them through the storage picker.

## Installing

Signed APKs live on the <a href="https://github.com/wasi-master/wmkeyboard/releases/latest" target="_blank" rel="noopener noreferrer">releases page</a>.
Each release carries eight of them: `full` and `lite`, each built for `arm64-v8a`,
`armeabi-v7a`, `x86_64`, and a `universal` APK that covers all three. Almost every phone made
since 2017 wants `arm64-v8a`. Check your download against the `SHA256SUMS.txt` in the same
release. Not on Google Play or F-Droid yet.

## Building

Install JDK 17 or newer to launch Gradle. Gradle then provisions its own JDK 21 toolchain
through Foojay on the first build. Install the Android SDK with compileSdk 36.1 available.
Android Studio's bundled JBR works well as `JAVA_HOME`.

```bash
./gradlew assembleFullDebug
```

```bash
./gradlew testFullDebugUnitTest
```

```bash
./gradlew staticAnalysis
```

Two flavors ship. `full` is everything. `lite` swaps the ML Kit and on-device model paths for
stubs, which matters on storage-constrained devices. The build channel (Play versus direct
download) is a flag in `local.properties`. It decides whether ML Kit models and the LLM runtime
ship inside the APK or download on demand.

Install the APK. Open the app and follow the setup card. Turn on WM Keyboard in system
settings, then select it as your input method.

## Project layout

Modules are layered bottom-up. Every dependency points downward, and each module declares its
own in `build.gradle.kts`.

```
app/                  Settings app, manifest, resources, bundled assets, most unit tests
feature/
├── ime/              WMKeyboardService and the Compose keyboard UI (the keyboard itself)
├── addons/           Addon install, reconcile, download
├── tools/            Network tool clients: AI, GIF and sticker, search, link preview
└── llm/              On-demand LiteRT-LM runtime (Play channel only)
core/
├── settings/         SettingsRepository (DataStore), KeyboardSettings, modes, power saving
├── intelligence/     Grammar (Harper JNI), local LLM, handwriting, spell checker service
├── feedback/         Key sounds and haptics
├── voice/            SpeechRecognizer plus offline Whisper dictation
├── plugins/          Lua plugin sandbox
├── addons/           Addon store and repository data layer
├── content/          Clipboard, snippets, fonts, media, stickers
├── tools/            Offline tool engines: calc, units, currency, symbols, calendars
├── icons/            Icon packs and the SVG parser
├── theme/            ThemeSpec, palettes, rendering
├── emoji/            Catalog loader, semantic search, usage tracking
├── prediction/       Trie, SuggestionEngine, UserLexicon, dictionaries, gestures
├── input/            Composers (CJK, cluster scripts) and the input pipeline
├── language/         Scripts, layouts, transliteration
├── common/           Utilities, direct boot, debug log, shared contracts
└── config/           Build flags and API keys
tools/dictc/          Host-side dictionary compiler, shares :core:prediction sources
native/               Rust sources for the Harper grammar engine
```

## Documentation

The full documentation site is at **<a href="https://wmkeyboard.pages.dev" target="_blank" rel="noopener noreferrer">wmkeyboard.pages.dev</a>**. Its source
lives in <a href="docs/" target="_blank" rel="noopener noreferrer"><code>docs/</code></a> and runs on Astro Starlight. Run `npm run dev` inside that folder for a
local preview.

| | |
|---|---|
| <a href="docs/src/content/docs/development/architecture.md" target="_blank" rel="noopener noreferrer">Architecture</a> | How the pieces fit and why |
| <a href="docs/src/content/docs/development/building.mdx" target="_blank" rel="noopener noreferrer">Building</a> | Toolchain, flavors, channels |
| <a href="docs/src/content/docs/development/dictionaries.mdx" target="_blank" rel="noopener noreferrer">Dictionaries</a> | The `.wmdict` and `.wmng` binary formats |
| <a href="docs/src/content/docs/plugins/api-reference.mdx" target="_blank" rel="noopener noreferrer">Plugin API</a> | Writing a `.wmplugin` |
| <a href="docs/src/content/docs/development/addon-repos/repo-format.mdx" target="_blank" rel="noopener noreferrer">Addon repos</a> | Hosting themes, layouts, packs |
| <a href="docs/src/content/docs/privacy/overview.mdx" target="_blank" rel="noopener noreferrer">Privacy</a> | What touches the network and when |

## Contributing

The engines under `core/` are plain Kotlin with no Android dependencies, so they are easy to
test and easy to extend. Dictionaries and the emoji catalog are plain text assets meant for hand
editing, and a new language mostly comes down to a word list and a layout definition.

Good places to start: a dictionary for a language that has none, emoji keywords in your language,
a theme, or a layout you miss from another keyboard. Open an issue first for anything larger so
we can agree on the shape of it.

## License

MIT. See <a href="LICENSE" target="_blank" rel="noopener noreferrer">LICENSE</a>.
