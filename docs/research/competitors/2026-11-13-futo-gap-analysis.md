# FUTO Keyboard → WM Keyboard: Feature-Gap Map (first full pass, 2026-11-13)

## Context

[FUTO Keyboard](https://github.com/futo-org/android-keyboard) is FUTO's offline
keyboard, forked from AOSP LatinIME and grown with a transformer language model,
built-in Whisper voice input, the FUTO Swipe neural decoder, Japanese (Mozc) and
Chinese (RIME) input, and a YAML layout system. Its licence is the FUTO Source
First License 1.1, which is not open source: nothing in this file copies its
code, and features are described from release notes, settings strings and docs.

In July 2026 only FUTO's release notes were collected. Its prediction and swipe
ideas were then studied and partly ported, so this pass builds on that work
instead of redoing it:

- `futo-prediction-ideas` (memory): confidence-gated autocorrect, proximity
  edits, split-word suggestions, importance-weighted learning, seed bigrams. All
  shipped in WM in July.
- `futo-swipe-engine` and `futo-glide-features` (memory): FUTO Swipe measured
  against WM's GlideBeam on 38k real swipes and shipped as an optional English
  QWERTY engine (`GlideEngine.FUTO_SWIPE`), with look-ahead, loop doubling and
  learned shapes added to it.
- `layout-parity-heli-floris-futo` (memory): every FUTO layout matched against
  WM's in September; 108 layouts and 22 languages added, the FUTO YAML importer
  (`core/language/.../layout/FutoLayout.kt:46`) fixed.

Compared: FUTO Keyboard **v0.1.30** (2026-08-04, latest stable) plus the 20
commits on `master` up to `7d61ab41` (2026-11-02), against WM Keyboard at
**`97c534ed5`** (main, 2026-11-13).

FUTO sources read: all 27 GitHub releases (`gh api --paginate
repos/futo-org/android-keyboard/releases`, v0.1.20 → v0.1.30, release
candidates included; earlier versions were tagged on gitlab.futo.org without
notes), the full English settings catalogue (`java/res/values/strings-uix.xml`,
`voiceinput-shared/.../strings-voiceinput.xml`, the `settings/pages/*.kt` page
list including developer settings), and the docs site
(docs.keyboard.futo.org: settings, gestures, actions, roadmap, the September
2026 voice nightly).

How WM was checked: every ❌ or ⚠️ below rests on a grep of `app/src/main`,
`core/*/src/main` and `feature/*/src/main` with quoted globs, retried in WM's
own vocabulary when the first word found nothing, and read at the hit. This
follows the rules in `2026-07-20-heliboard-comparison.md`. `FEATURES.md` was not
used as evidence. Several first-grep "gaps" turned out to exist under WM names
and are listed at parity with the name that found them.

Legend: ❌ absent · ⚠️ partial or weaker · ✅ at or above parity.
Size: XS under an hour · S a day or less · M a few days · L a week or more.

Path shorthand: `settings/` = `core/settings/src/main/java/com/wasimaster/wmkeyboard/core/settings/`,
`ime/` = `feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/`,
`app/` = `app/src/main/java/com/wasimaster/wmkeyboard/app/`,
`prediction/` = `core/prediction/src/main/java/com/wasimaster/wmkeyboard/core/prediction/`.
`SR` = `settings/SettingsRepository.kt`.

---

## Scorecard

| Bucket | Count | IDs |
|---|---|---|
| FUTO user-facing features catalogued | 102 | |
| ✅ At or above parity | 90 | tables below |
| ❌ Absent | 4 | U1–U4 |
| ⚠️ Partial | 8 | U5–U12 |
| Not counted (business, dev-only, website) | 11 | last section |

The gap is small and scattered. No FUTO area is missing wholesale: the closest
thing to a cluster is Japanese (two partials) and FUTO's new advanced theme
format (one partial).

---

## ❌ Absent

### U1 — Backspace removes a whole paste

**What FUTO does.** "Delete pasted/inserted text on backspace" (Typing
preferences). The first backspace after a paste, or after inserting a clip or
an emoticon, deletes the whole inserted run instead of its last character.

**WM now.** ❌ for pastes. WM undoes three other kinds of insertion with one
backspace: an autocorrection (`undoCorrectionChip`, `SR:10761`, plus the
backspace revert from July), a glide word (`backspaceUndoesGlide`, `SR:9602`)
and a voice utterance (`lastVoiceCommit` in the service). A paste is not one of
them; no paste range is tracked (`pasteRange`, `lastPaste*`, `pastedSpan`: zero
hits in `ime/`).

**Size.** S. Record the committed range on paste and clip insert, honour it on
the next backspace if the caret has not moved, behind a setting.

### U2 — Voice: suppress symbols

**What FUTO does.** "Suppress symbols" (Voice input, on by default) keeps the
recogniser from writing characters such as `@ $ %` and limits output to text
and basic punctuation. Spoken words like "comma" are unaffected.

**WM now.** ❌. Neither `VoiceBarSettings` nor `WhisperSettings` (`SR:6695`,
`SR:6917`) has a symbol filter; `suppressSymbols`, `stripSymbols`,
`suppress_tokens`: zero hits. WM has the opposite lever, spoken punctuation, and
an AI tidy pass (`aiTidy`).

**Size.** S. A post-filter on finals (and Whisper's token suppression where the
engine allows it). Low value: few users ask for it.

### U3 — Debug Info action

**What FUTO does.** An action that shows the focused field's EditorInfo
(input type, IME options, package), keyboard state, screen info and memory use,
so a user can attach it to a bug report.

**WM now.** ❌ as a user tool. The same field facts are exposed to plugins only
(`ime/PluginKeyboardServices.kt:211`, `field.info`), memory and models are on
the Resources screen (#1163), and the EditorInfo recipe in memory
`enter-key-routing` uses `dumpsys`, which users cannot run.

**Size.** S. A toolbar tool or a Settings → About row that renders the plugin
JSON. Low value for typing, useful for issue triage.

### U4 — "ie" → "i.e." and "eg" → "e.g."

**What FUTO does.** Since v0.1.28 autocorrect turns "ie" into "i.e." and "eg"
into "e.g.", and undoes the automatic space and capital when the user types
"I. e." by hand.

**WM now.** ❌ for the correction. No shortcut entry for either abbreviation
in `app/src/main/assets/dictionaries` and no rule in the engine; the only
"i.e." handling is selecting it as one word (`ime/WMKeyboardService.kt:3566`,
#679). Typing "i.e." by hand already works because WM's space after
punctuation is off by default (`spaceAfterPunctuation`, `SR:3124`).

**Size.** XS. Two seed shortcuts in the English dictionary. Low value.

---

## ⚠️ Partial

### U5 — Japanese kana pad: toggle input (keitai)

**What FUTO does.** The 12-key kana pad accepts both flicks and repeated taps
(あ → い → う on the same key), with a "Flick only" switch that turns cycling
off. There is also "Use half-width space".

**WM now.** ⚠️ Flick only. `app/src/main/assets/layouts/ja_flick.wmlayout.json`
has flick arms and the 小゛゜ variant key (`JapaneseComposer.cycleVariant`), but
no `multitap` rings, so repeated taps type the same kana again. The generic
`Key.multitap` exists (`core/language/.../layout/KeyboardLayout.kt:754`) and is
used by the Korean 천지인 pad, so the mechanism is there. Full-width space is
per language (`fullWidthSpaceLanguages`, `SR:6504`): at parity.

**Size.** S. Add the ten vowel rings to `ja_flick`, a setting to drop them, and
check that the composer sees a cycled kana as a replacement, not a new one.
Medium value: toggle input is how many Japanese phone users type.

### U6 — FUTO advanced ZIP themes

**What FUTO does.** v0.1.30 added a desktop web editor whose ZIP themes set all
44 colours, per-key bitmap borders (nine-patch) and icons chosen by
"matchrules", and a background shader. A theme gallery lives on
keyboard.futo.tech/themes.

**WM now.** ⚠️ WM already imports FUTO ZIP themes (`core/theme/.../FutoTheme.kt`,
both `theme.txt` and `config.json`, wired at `app/ThemeScreens.kt:1402`), but
the importer lists what it drops: matchrules, nine-patch key borders and icons,
the shader, and wallpaper-derived `system_*` colours (`FutoTheme.kt:40-43`).
WM's own engine has per-key styles and textures (memory `theme-per-key-styles`)
and one picture cut across keys (memory `picture-on-keys`), but no per-key
nine-patch image.

**Size.** M for nine-patch key backgrounds and icon swaps through the existing
per-key style system; L to reproduce matchrules fully. Shader: skip. Medium
value, rising as FUTO's gallery grows.

### U7 — Number row in password fields

**What FUTO does.** The number row appears automatically in password fields.

**WM now.** ⚠️ WM has a built-in Passwords keyboard mode bound to password
fields (`settings/KeyboardModes.kt:672-688`), and a mode can turn the number row
on (`KeyboardMode.numberRow`, `KeyboardModes.kt:404`, applied at `:1072`), but
the shipped mode does not set it. A user can, if they find the mode editor.

**Size.** XS. `numberRow = true` in `mode_password`, plus a docs line. High
value for its cost.

### U8 — Quick period key

**What FUTO does.** "Quick period key" replaces the period's long-press popup
with a flick layout of `! , . ?`.

**WM now.** ⚠️ Possible but not offered. Per-key swipe arms
(`gesture.keySwipes`, `SR:9143`) can put those four on the period key, and
`hintFlick` (`SR:9819`) types a key's corner hint on a downward swipe. Neither
is a one-switch preset for the period key.

**Size.** S. A toggle that writes a default `KeySwipeArms` entry for the period
key (and removes it when turned off). Medium value: fast punctuation is a
common Gboard habit.

### U9 — Enter key hold menu

**What FUTO does.** Holding Enter opens a row of actions: undo, redo, switch
language, text editor, clipboard, emoji. In multi-line fields with an action
button it also offers Shift+Enter for a new line.

**WM now.** ⚠️ Shift+Enter newline is there (`shiftEnterNewline`, `SR:10082`).
Holding Enter can open emoji (`enterLongPressEmoji`, `SR:9766`); the built-in
Enter keys carry no long-press list (`core/language/.../BuiltInLayouts.kt:553`,
`:645`, `:789`). The same actions are one tap away on WM's toolbar.

**Size.** S. Default alternates on the Enter key that run toolbar actions.
Low value.

### U10 — MS-IME and ATOK dictionary import

**What FUTO does.** The personal dictionary menu imports MS-IME and ATOK
dictionary exports for Japanese.

**WM now.** ⚠️ Japanese user words come from imported lists with readings,
which covers Gboard, Mozc and Google Japanese Input exports
(`core/input/.../composer/JapaneseUserWords.kt:6-9`, loaded at
`ime/WMKeyboardService.kt:23596-23605`). MS-IME exports UTF-16 by default and
ATOK often Shift-JIS; no UTF-16 or Shift-JIS decoding was found on the
word-list import path (the only UTF-16 sniffing is for IME tables,
`core/input/.../imported/InputMethodSources.kt:17-20`). Not tested with a real
export, so treat this as a likely gap, not a confirmed one.

**Size.** S. BOM and Shift-JIS sniffing on word-list import, plus the MS-IME
column order. Medium value for people moving from Windows Japanese input.

### U11 — Clipboard: switch off saving images

**What FUTO does.** With clipboard history on, separate toggles decide whether
copied images and recent screenshots are kept; images over 10 MB are skipped
and unpinned images share a 40 MB budget.

**WM now.** ⚠️ Screenshots have their own switch (`clipboard.userScreenshots`,
`SR:8332`) and images have a size cap (`ClipboardStore.HARD_MAX_IMAGE_BYTES`,
32 MB, `core/content/.../clipboard/ClipboardStore.kt:359`), but
`ClipboardSettings` (`SR:8206`) has no switch to keep text only.

**Size.** XS. One boolean checked where image clips are stored. Low to medium
value (privacy and storage).

### U12 — Language-model knobs: own file, strength

**What FUTO does.** Each language can take an imported transformer model file,
with an "Explore models" link, and Advanced Parameters exposes "Transformer LM
strength" beside "Autocorrect threshold".

**WM now.** ⚠️ WM's word LM (`.wmlm`, memory `neural-prediction-stack`) is
downloaded from its catalogue (`app/LanguageDictionaries.kt:51`), switched by
`neuralContext` (`app/TypingSettingsScreens.kt:537`), and its blend weight is a
shipped constant (`LM_BLEND`). No `.wmlm` file import path was found in
`app/FileImport.kt`, and there is no strength slider. The threshold side is at
parity (`autocorrectConfidence`, `prediction/SuggestionEngine.kt:1062`, and
`mistypeTolerance`). FUTO's own LM covers English only; WM ships English and
Bengali romanised.

**Size.** S for file import, XS for a slider. Low value: few users train
models, and the blend was tuned on the typing harness.

---

## ✅ At or above parity

Each row names the FUTO feature, then where WM has it. Grouped by FUTO's own
settings menus.

### Text prediction and autocorrect

| FUTO | WM |
|---|---|
| Transformer LM next-word and autocorrect | Word LM with revision and phrase chip, `neuralContext` `SR:10890`, `contextRevision` |
| Autocorrect threshold | `autocorrectConfidence` `prediction/SuggestionEngine.kt:1062`, `mistypeTolerance` `app/TypingSettingsScreens.kt:638` |
| Smart key-hit detection | Autopilot, `smartHitDetection` `SR:10006`, with strength and visual options |
| Multilingual typing with per-language confidence | `prediction/LanguageMixConfidence.kt:26`, used at `SuggestionEngine.kt:52` |
| Missing-space correction ("thisbis" → "this is") | `autocorrectSplits` `SR:11102`, `splitCandidates` `SuggestionEngine.kt:4809` |
| No autocorrect of words starting with a digit ("10mm", "2nd") | `SuggestionEngine.kt:7565` |
| Word blacklist, long-press a suggestion to blacklist | memory `suggestion-blacklist` |
| Block offensive words | `blockOffensiveWords` `SR:10865` |
| Undo autocorrect on backspace | July revert plus `undoCorrectionChip` `SR:10761` |
| Automatic spaces mode (4 choices) | `autoSpaceAfterSuggestion` `SR:10910`, `spaceAfterPunctuation` `SR:3124`, glide before/after spaces |
| Emoji suggestions while typing, skin tone respected | `core/emoji/.../EmojiSuggester.kt:23`, `defaultSkinTone` `SR:8619` |
| Email fields remember typed addresses, suggest domains | `typedEmails` `SR:3266`, contact emails, `@gmail.com`… keys `core/tools/.../SymbolSets.kt:96` |
| Personal dictionary menu with shortcuts | hand-added words, `expandUserDictShortcuts` |
| Personalised suggestions, next-word toggle | learning settings in `SuggestionStripSettings` |
| Dictionary download prompt | per-language auto download, `app/LanguageRootScreen.kt:191` |
| Import AOSP `.dict` dictionaries | `AospDictionary.looksLikeDictionary` `app/FileImport.kt:519` |
| Alternatives remembered when the caret goes back to a swiped word (v0.1.29.1) | `GlideReadings`, place-anchored, `prediction/GlideReadings.kt:47`, `withGlideReadings` `ime/WMKeyboardService.kt:34369` |
| French spacing before `? !` | `core/language/.../script/SpacedPunctuation.kt` |

### Swipe typing

| FUTO | WM |
|---|---|
| FUTO Swipe neural decoder | Same model as an optional engine, `gesture.engine` `SR:9388`, `app/GlideEngineSettings.kt:64` |
| Legacy algorithm switch | `GlideEngine.BUILT_IN` (WM's own GlideBeam, the default) |
| Suggestion bar mode (3 alternatives vs main word centred) | `suggestionPrimaryCenter` `SR:10851` |
| Swipe sensitivity, cooldown after typing | `postTypeCooldownMs` `SR:9279`, `startThresholdSlop` |
| KASROZ and ClearFlow | both shipped, `rowSpan` layouts (memory `layout-parity-heli-floris-futo`) |
| Swipe learns new words immediately | learned shapes, memory `futo-glide-features` |

### Voice input

| FUTO | WM |
|---|---|
| Built-in offline Whisper, model sizes, multilingual models | `whisper.engine`, `modelByLang`, whisper.cpp runtime |
| Streaming models (asr4all, Parakeet, Moonshine; FUTO Voice Input nightly) | All three on WM's ExecuTorch runtime, memory `asr4all-engine` |
| Long-form, no 30 s limit | continuous dictation (memory `voice-input-system`) |
| Auto-stop on silence | `silenceStopMs` `SR:6739`, VAD |
| Indication sounds | `startSound` `SR:6785` |
| Prefer Bluetooth mic | `mic` `SR:6799`, `core/voice/.../VoiceMics.kt:33` |
| Audio focus (pause media) | `pauseMedia` `SR:6777`, `ime/WMKeyboardService.kt:38516` |
| Personal dictionary as voice context | `biasPersonalWords` `SR:7021`, `biasWords` |
| Use an external voice app | system recognizer routes, `systemService` |
| Language auto-detect among enabled languages | `languages` list on the voice settings, language detection |

### Keyboard and typing

| FUTO | WM |
|---|---|
| Number row, Western-numerals option | `numberRow`, `numeralSystemByLang` `SR:10053` |
| Number row style (compact or keys) | `numberRowHeightDp` per screen variant |
| Arrow key row | `arrowRow` `SR:10121`, orderable |
| Long-press key kinds, ordered and switchable | `settings/AlternateGroups.kt`, `topRowHolds`, `holdSymbols`, `longPressLetterActions` |
| Quick actions on letter long-press (Copy on C) | `longPressLetterActions` `app/KeyPressSettingsScreens.kt:1687` |
| Hints toggle, long-press duration | key popup and hint settings |
| Spacebar swipe and hold: cursor or language | `SpaceSwipeAction`, `spaceHoldKeys`, language picker carousel |
| Hide language name on the spacebar | `spacebarDisplay` `SR:9927` (`LANGUAGE`/`LAYOUT`/`BOTH`/blank) |
| Backspace hold and swipe: characters or words | `BackspaceSwipeUnit`, `backspaceSwipeDelete` |
| Slide from ?123 to type one symbol | layer peek drag (memory `layer-peek-drag`) |
| URL bar and number-only fields | `FieldKind` keypads, `ime/KeyboardState.kt:758` |
| Language switcher lists other keyboards | `showInputMethodPicker`, `KeyAction.SwitchInputMethod` |
| Show or hide with a hardware keyboard | `onEvaluateInputViewShown` `ime/WMKeyboardService.kt:7615`, pinned keyboard, toolbar-only mode |
| Side padding, per-orientation sizes, split, one-handed, floating | `sidePadLeftScale` `SR:10161`, keyboard modes |
| Action key beside the spacebar | Bottom row settings (memory `bottom-row-as-laid-out`), globe and emoji keys |
| Hide the suggestion bar | toolbar `enabled` |
| Inline autofill switch | memory `inline-autofill-398` |
| Vibration strength, sound volume, haptics on cursor drag and long-press | `HapticSettings` `SR:1263` (`onCursorMove`, `onLongPress`) |
| Enter key sound | per-role key sounds, `KeySoundRole.ENTER` |
| Dead keys | `ComposerType.DEAD_KEY` |
| Custom layouts | in-app layout editor plus FUTO YAML import, `FutoLayout.kt:46` |

### Languages

| FUTO | WM |
|---|---|
| Japanese via Mozc, QWERTY and flick | Mozc engine and WM's own composer (`mozcJapanese`), seven `ja_*` layouts |
| Chinese via RIME: pinyin, fuzzy, double pinyin, stroke, simplified/traditional | `CjkSettings` `SR:6409` (`pinyinFuzzy`, `pinyinDoublePinyin`, `traditionalOutput`), RIME, Wubi, Cangjie, stroke |
| Editable preedit box above the keys | `readingInField` `SR:6467`, `readingCaret` in the service |
| Vietnamese Telex and VNI | `ComposerType.VNI`, Telex composer |
| IPA, Toki Pona, Tibetan Wylie, Shavian, Uralic, Unijoy and the rest | `ipa*.wmlayout.json`, `Language.kt:3406`, `WylieTibetan` composer; 3,685 layout files |
| Currency key per region | memory `currency-by-region`, `currencyKeys` `SR:10269` |

### Emoji

| FUTO | WM |
|---|---|
| Unicode 17 emoji on old Android | 🫪 present in `app/src/main/assets/emoji/catalog.tsv`; downloadable emoji fonts (`EmojiFontCatalog`) |
| Emoji search, multilingual | emoji keyword packs per language, `EmojiKeywordPacks` |
| Recent emoji cap, clear recents | `recentsLimit` `SR:8667`, `emojiClearRecentsButton` `SR:4163` |
| Remembered skin tone | per-emoji `preferredVariant`, global `defaultSkinTone` |
| ASCII emoticons category | kaomoji library |

### Clipboard

| FUTO | WM |
|---|---|
| History, pins, hours to keep, count to keep | `ClipboardSettings` `SR:8206` (`expiryHours` `:8226`, `maxItems`) |
| Search | `clipboard.search` `SR:8320` |
| Image clips, screenshots | image clips, `userScreenshots` `SR:8332` |
| Save sensitive clips or not, obscure them | `sensitiveHandling` `SR:8244`, `detectSensitive` |
| List or grid, pinned on top, skip delete confirmation | `view`, `gridColumns`, `pinnedLast`/`pinnedInPlace`, `undoDelete`/`confirmPinnedDelete` |
| Recent copy on the strip, URL and OTP detection | `suggestRecent` `SR:8270`, entity detection, OTP chips |
| Long-press the clipboard action to paste | `holdPaste` `SR:8312` |

### Actions, themes, app

| FUTO | WM |
|---|---|
| Action bar: favourites, pinned, drag editor | toolbar, toolbox and second toolbar with drag reorder |
| Text editor action | `ToolbarTool.TEXT_EDIT`, selection mode |
| Undo, redo, select all, cut, copy, paste, arrows | toolbar tools and letter-hold actions |
| Theme switcher above the keyboard | `ToolbarTool.THEMES` |
| Keyboard modes action | one-handed, split, floating tools and keyboard modes |
| Simple custom theme from a photo: crop, transparency, blur, colours from the image, dark mode, key borders | photo backgrounds, `seedPalette` `settings/PhotoBackgroundSettings.kt:121`, `BackgroundBitmaps.blurStep` |
| Import FUTO ZIP themes | `FutoTheme.kt` (colours; see U6 for what is dropped) |
| High-contrast theme, dynamic colour themes | high-contrast note `app/PhotoSettingsScreens.kt:664`, Material You themes |
| Monochrome launcher icon | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:5` |
| Settings search | `app/SettingsSearch.kt:3585` |
| Export and import everything | `settings/ConfigBackup.kt`, encrypted and cloud destinations |
| Import a FUTO settings backup | `settings/foreign/ForeignPrefs.kt:90` (`futoSharedPrefs`) |
| Direct boot | `settings/DirectBootSettings.kt` |
| No-internet build | offline build flag (`offlineBuild`, `app/LanguageDictionaries.kt:487`) |
| Manual update check | update channels |
| TalkBack | memory `talkback-passthrough` |
| WordStyles (text rendered into styled images) | ⚠️ see note below |

WordStyles needs a note. FUTO renders typed text into an image using one of
seven presets (Scratch, Superhero, Rainbow, Opening Crawl and others) and sends
it as a picture. WM has Unicode fancy text, zalgo, TextArt, and on-device
text-to-image stickers (memory `sticker-generator`), but no preset that draws
the typed words themselves as a styled picture (`WordStyle`, `TextSticker`,
`textToImage`: zero hits). It is counted at parity in the scorecard because
WM's sticker and fancy-text tools cover the use (send something showy), and
because FUTO itself ships only seven fixed presets. If the owner wants the exact
feature it is an M.

---

## Not counted

- Payments and licence screens, "I already paid", reminders.
- Developer settings: touch-typing mode (hides every key label), inline
  keyboard, buggy text-edit variations, swipe and voice debug logging, theme
  reimport over adb, legacy composition toggles, "allow transformer on
  non-QWERTY".
- Transformer fine-tuning: removed by FUTO in v0.1.26.
- Seasonal themes (the Christmas theme moved to their website).
- The swipe.futo.org data collection: a website, not a keyboard setting. WM
  already uses its public dataset to tune and test glide.
- Pontoon translation platform and credits screen.
- Voice "Verbose progress" (FUTO's docs say it does nothing) and the voice
  bubble animation switch (cosmetic; WM's voice UI is a different design).

---

## What WM has that FUTO lacks

FUTO is a focused offline typing keyboard. WM covers that ground and adds a
large tools layer, so this list is long; the main headings:

- AI: cloud and self-hosted providers, on-device LLMs (LiteRT-LM, llama.cpp,
  NPU builds), AI chat, reply, rewrite, tool calling, MCP servers.
- Typing extras FUTO does not have: snippets and text expansion, Harper grammar
  and spell checking, transliteration (Avro and the other phonetic layouts;
  FUTO lists Indic transliteration as a mid-term roadmap item), handwriting,
  flick and key-swipe arms on any key, multi-finger swipes, two-thumb glide,
  number prediction, smart punctuation model, glide ambiguity picker.
- Tools: translation (on-device Bergamot and NLLB), OCR, QR and document
  scanning, calculator, unit and currency conversion, weather, notes, to-dos,
  calendar events, files, gallery, Home Assistant and KDE Connect, media
  control, music ID, GIFs, stickers and sticker generation.
- Voice beyond Whisper: Parakeet, Moonshine, asr4all, Sherpa, Vosk, denoising,
  smart turn-ending, punctuation model, server engines.
- Customisation: a layout editor and importers for Keyman, MSKLC, m17n, RIME,
  `.cin`, FUTO, Floris and HeliBoard layouts; theme engine with frosted glass,
  animated and per-key styles; theme importers for Gboard, Floris, FUTO, Clink.
- Platform: keyboard modes bound to apps and field types, Python plugins,
  automation intents, encrypted and cloud backups, app lock, statistics,
  Android TV.
- Breadth: 3,685 layout files and the Keyman catalogue against FUTO's
  layouts repo.

FUTO's real advantage is not a feature but a posture: one APK with no Internet
permission at all. WM gets there with its offline build flavour, which drops
the network features rather than hiding them.

---

## Gaps ranked by value to users

| Rank | ID | Gap | Size | Why this rank |
|---|---|---|---|---|
| 1 | U7 | Number row on by default in password fields | XS | Everyone types passwords; one line in the built-in mode |
| 2 | U1 | Backspace removes a whole paste | S | A wrong paste is a frequent slip, and WM already does this for corrections, glides and voice |
| 3 | U8 | Quick period key preset | S | Fast `! , . ?` is a habit Gboard users bring; the mechanism exists |
| 4 | U5 | Japanese toggle input (keitai) | S | Many Japanese phone users tap-cycle; WM's pad cannot |
| 5 | U10 | MS-IME and ATOK dictionary import | S | Moving a Japanese user dictionary from Windows (likely, untested) |
| 6 | U6 | Per-key pictures from FUTO advanced themes | M–L | FUTO's theme gallery is new and growing; colours already import |
| 7 | U11 | Switch to keep images out of clipboard history | XS | Privacy and storage, small audience |
| 8 | U9 | Enter key hold menu | S | Actions are already on the toolbar |
| 9 | U4 | "ie" → "i.e.", "eg" → "e.g." | XS | Typing them by hand already works |
| 10 | U12 | LM file import and strength slider | S | Few users train models; blend is harness-tuned |
| 11 | U2 | Voice: suppress symbols | S | Rarely requested |
| 12 | U3 | Debug Info action | S | Helps triage, not typing |

## Re-running this

FUTO's release notes are detailed from v0.1.20; the settings catalogue is
`java/res/values/strings-uix.xml` plus `settings/pages/*.kt` in their repo
(read for names only; the licence forbids reuse of code). Before calling a
FUTO feature missing, try WM's names first: Autopilot (`smartHitDetection`),
`autocorrectSplits`, `LanguageMixConfidence`, `GlideReadings`,
`suggestionPrimaryCenter`, `postTypeCooldownMs`, `startSound`, `pauseMedia`,
`biasPersonalWords`, `readingInField`, `spacebarDisplay`, `holdPaste`,
`typedEmails`, keyboard modes for anything field-specific.
