# LeanType → WM Keyboard: Feature-Gap Map (first pass, 2026-11-13)

## Context

[LeanType](https://github.com/LeanBitLab/LeanType) is LeanBitLab's fork of HeliBoard
(GPL-3.0, formerly "HeliboardL", renamed at v3.7.2). It keeps HeliBoard's
LatinIME core and adds its own work on top: cloud and self-hosted AI, a text
expander, per-app profiles, a native floating window, clipboard gestures,
hexagonal layouts, sound packs, and a family of out-of-process plugins (voice,
OCR, translation, handwriting, offline AI, GIF, CJK).

This is the first comparison against it. Earlier passes covered HeliBoard
(`2026-11-13-heliboard-gap-map.md`) and FlorisBoard, so every LeanType feature
inherited from HeliBoard is left to the HeliBoard map and only named here.

Compared: LeanType v4.3.2 (released 2026-11-13, latest stable), against
WM Keyboard at `3402a3dd0` (main, 2026-11-13).

LeanType sources read: all 96 GitHub releases (`gh api --paginate
repos/LeanBitLab/LeanType/releases`, v3.6 → v4.3.2, betas included), the
README, and `docs/FEATURES.md` (45 sections plus the keycode reference).

How WM was checked: every "absent" or "partial" below rests on a grep of
`app/src/main`, `core/` and `feature/` with quoted globs, retried with the
codebase's own vocabulary when the first word found nothing (the rules in
`2026-07-20-heliboard-comparison.md`). `FEATURES.md` and the docs site were
used only as leads, and every lead was confirmed in code. WM source paths below
are relative to the repository root.

Legend: ❌ absent · ⚠️ partial or weaker · ✅ at or above parity.

Size: XS under an hour · S a day or less · M a few days · L a week or more.

### Already settled on 2026-10-31

LeanType's plugins were reviewed then. Sound packs are imported
(`LeanTypeSoundPack.kt`, 22 packs in the addon repo); the CJK, OCR, Translation,
Offline AI, Voice and Handwriting plugins duplicate WM's own engines; the Klipy
**memes** category is still an open idea (WM already has Klipy as a GIF and
sticker provider, `app/.../app/ProviderProbes.kt:98`, but no memes tab). Not
redone here.

## Scorecard

| Bucket | Count | IDs |
|---|---|---|
| LeanType-specific features catalogued | 85 | table at the end |
| ✅ WM at or above parity | 63 | table at the end, plus delete-by-word in a mixed row |
| ❌ Absent in WM | 7 | L1–L7 |
| ⚠️ Partial in WM | 12 | L8–L19 |
| Not checked or not found | 2 | AI over plain HTTP; slide from the clipboard key |
| Settled earlier (plugins) | 7 | see above |
| Inherited from HeliBoard | ~60 | left to the HeliBoard map |

The gaps cluster in one place: the text expander. LeanType's expander has
grown fast (images, clipboard modifiers, fill-in prompts, a plain-text editor),
and five of the nineteen items below are there. Most of the rest are small
settings knobs.

## ❌ Absent

### L1 — Image shortcuts in the text expander
**LeanType.** v4.3.2 (#784). A shortcut can carry a photo instead of text. The
strip shows it as a thumbnail chip; picking it, or typing the trigger, inserts
the image through `commitContent`, falling back to the share sheet when the
field takes no images. Images live in private storage and travel in backups.
**WM now.** ❌. Snippets are text only. The Espanso importer meets image
matches and drops them with a note (`core/content/.../snippets/espanso/EspansoFile.kt:435`,
`EspansoNotes.kt:31`). The insert path itself exists: image clips already go
through `commitContent` with MIME negotiation (`core/content/.../clipboard/ClipboardStore.kt:16`).
**Size.** M. A snippet kind with a stored file, a strip thumbnail, and reuse of
the clipboard's image commit. Backup needs the file alongside the JSON.

### L2 — Clipboard modifiers inside snippets
**LeanType.** v4.1.4 onward. `%clipboard:clean%` strips Wikipedia-style
citations (`[1]`, `[citation needed]`), and chained modifiers reshape the
clipboard as it expands: `singleline`, `title`, `slug`/`kebab`, `snake`,
`camel`, `upper`, `lower`, `trim`, `unquote`, `nourl`, `replace(pattern, with)`.
**WM now.** ❌. `{clip}` inserts the clip as it is
(`core/content/.../snippets/SnippetStore.kt:1445`), with no modifier syntax.
The transforms themselves are already written for the selection macros (case
ladder, camel/snake/kebab, line tools), so this is wiring rather than new text
logic.
**Size.** S.

### L3 — Fill-in prompt when a snippet expands
**LeanType.** `%custom_variable%` opens a small popup asking for the value
before the expansion lands, so one shortcut can produce "Dear ___," with the
name typed at expansion time.
**WM now.** ❌. Espanso forms are flattened: the first `[[field]]` becomes the
caret position and the rest are blanked (`EspansoFile.kt:450`). Pattern
snippets insert a blank template with the caret in the first gap, which covers
one field but not several.
**Size.** M. An in-keyboard prompt row that captures keystrokes the way the
emoji and clipboard search pills already do.

### L4 — Hexagonal honeycomb layouts
**LeanType.** v4.2.7. Two layouts, `hex_typewise` (Typewise's arrangement) and
`hex_qwerty`, drawn as interlocking hexagons with axial hit-testing (nearest
cell centre), half-hex Shift and Delete, two spacebars, and, held sideways,
a split into two thumb clusters.
**WM now.** ❌. Hexagon exists only as a key *shape* in themes
(`app/.../app/ThemeScreens.kt:897`); the grid stays rectangular. No axial or
hex grid code anywhere (`axial`, `HexGrid`, `honey` all return nothing).
**Size.** L. A non-rectangular grid touches drawing, hit-testing, the glide
grid and the layout editor.

### L5 — Per-app "keep symbols in the word" (Tasker variables)
**LeanType.** v4.2.5 App Profiles: "Allow symbol composing" keeps `%` and `_`
inside the composing word for chosen apps, so Tasker's `%output_var` is not
split and rejected by the app's input filter.
**WM now.** ❌. The composing word admits letters, digits and apostrophes only
(`isComposingWordChar`, `feature/ime/.../WMKeyboardService.kt:3575`), and a
Keyboard Mode has no field to widen it (`core/settings/.../KeyboardModes.kt:289`
onward lists every override). A Mode can already turn suggestions off for an
app, which sidesteps the problem by losing suggestions.
**Size.** S–M. A Mode field naming extra word characters, read by the
composing gate.

### L6 — Physical keyboard layout remapping inside the IME
**LeanType.** v4.2.7. A setting maps hardware scan codes to QWERTY, QWERTZ,
AZERTY, Dvorak, Colemak, Workman, or "match the on-screen layout".
**WM now.** ❌. Hardware keys are decoded through the device's own
`KeyCharacterMap` (`event.unicodeChar`), so WM follows the physical layout
chosen in Android's settings; there is no in-keyboard remap (no scan-code
table; `Colemak` hits are on-screen layouts only).
**Size.** M. Value is low: Android already offers per-device physical layouts,
and "match the on-screen layout" is the only part it cannot do.

### L7 — "Restart keyboard" button
**LeanType.** v4.3.2. About → Restart keyboard ends the IME process after a
confirmation, to reload models and plugins.
**WM now.** ❌. Nothing kills or relaunches the keyboard process from settings
(the only `exitProcess` is in the Shizuku shell service).
**Size.** XS. Low value; WM reloads settings live.

## ⚠️ Partial

### L8 — Blocked words: regex patterns and their own import/export
**LeanType.** v3.8.6 regex blacklist; v4.3.0 (#687) standalone import and
export with language tags, comments and de-duplication.
**WM now.** ⚠️. The blacklist is exact words, global or per language,
matched case-insensitively (`core/settings/.../SettingsRepository.kt:3328`,
`core/prediction/.../SuggestionEngine.kt:1347`). It travels in the backup but
has no file of its own.
**Size.** S. Regex needs the same compile-time guards the pattern snippets
already have (step budget, no backreferences).

### L9 — Per-app Enter key override
**LeanType.** App Profiles can force Enter to System default, Newline, Send,
Search, Go, Next or Done, and can ignore `IME_FLAG_NO_ENTER_ACTION`.
**WM now.** ⚠️. A Keyboard Mode can force a newline and nothing else
(`enterNewline`, `KeyboardModes.kt:402`). WM handles the no-enter flag for
multi-line boxes on its own (`WMKeyboardService.kt:19293`) but cannot be told
to send in a given app.
**Size.** S. Widen the Mode field from a boolean to an action.

### L10 — More snippet variables
**LeanType.** `%battery%`, `%language%`, `%greeting%` (good morning/afternoon/
evening by time), `%bullets_N%` and `%list_N%` (N bullet or numbered lines),
`%tab%`, `%week%`, `%tomorrow%`.
**WM now.** ⚠️. 18 tokens plus `{date:pattern}`, date offsets, `{random:…}`,
`{location}`, `{place:…}` and `{ha:…}` (`SnippetStore.kt:1433`–`1450`,
`1214`–`1232`). Tomorrow and the week number are reachable as
`{date+86400}` and `{date:w}`. Battery, keyboard language, greeting and the
list generators are not.
**Size.** S.

### L11 — All snippets as one plain-text document
**LeanType.** v4.3.2 "Edit all as text": every shortcut in one editor as
`=== trigger ===` blocks, with line-numbered validation, a diff of added,
changed and deleted entries before saving, a one-tap undo snapshot, and `.txt`
import and export.
**WM now.** ⚠️. Snippets import and export as `.wmsnippets.json` and Espanso
YAML, and a folder has a bulk dialog, but it edits flags only (case, confirm,
immediate; `app/.../app/SnippetScreens.kt:1468`). There is no edit-everything-
as-text screen.
**Size.** M.

### L12 — Personal dictionary as a plain `.txt` file
**LeanType.** v4.3.2 (#431). Export all words, or one language's, to `.txt`.
**WM now.** ⚠️. Words go to Android's dictionary or into a backup; no screen
writes them to a file (no `CreateDocument` near the personal dictionary).
**Size.** S.

### L13 — Corner radius per key class
**LeanType.** v4.2.1 and v4.2.3: separate radius sliders for normal keys,
functional keys (Shift, Backspace, ?123) and action keys (Enter, Space).
**WM now.** ⚠️. One radius for every key, as a setting
(`keyCornerRadiusDp`, `SettingsRepository.kt:3801`) and in the theme
(`ThemeSpec.kt:472`). Single-key styles override colours, texture, label size
and bold, but not shape (`ThemeSpec.kt:760`–`796`).
**Size.** S–M. A shape or radius field on the per-key override would cover it
and more.

### L14 — Key preview bubble size and corners
**LeanType.** v4.2.9: width scale, height scale up to 200%, and corner radius
for the press preview, proportional to key size.
**WM now.** ⚠️. Linger, ceiling, on-key versus floating, font scale
(`SettingsRepository.kt:360`–`377`) and a remembered height per style. No
width or corner control.
**Size.** S.

### L15 — Delete swipe by visual (wrapped) lines
**LeanType.** v4.3.0 (#731). On Android 14+ the vertical delete swipe asks the
editor for `TextBoundsInfo` and counts the lines as drawn, so a wrapped
paragraph is selected line by line, with a fallback estimate elsewhere.
**WM now.** ⚠️. The delete swipe selects and deletes whole lines
(`feature/ime/.../ui/DeleteSwipeCallbacks.kt:53`), but a "line" is text up to
a newline (`feature/ime/.../DeleteLines.kt:3`), so one long paragraph is one
line. `requestTextBoundsInfo` is used nowhere.
**Size.** S–M.

### L16 — Topic-sorted Unicode symbol palette
**LeanType.** v4.3.2 (#776). A symbols tab in the emoji view with topics:
arrows, math, typographic, geometric, stars, weather, music, technical,
currency.
**WM now.** ⚠️. The symbol catalogue has nine categories: fractions, math,
Greek, arrows, currency, superscript, punctuation, shapes, other
(`core/tools/.../SymbolCatalog.kt:25`). Stars, weather, music and technical
symbols have no tab of their own.
**Size.** S. Data only.

### L17 — Switch for the autocorrect underline
**LeanType.** v4.3.2. A toggle hides the underline drawn under an
auto-corrected word.
**WM now.** ⚠️. Every autocorrection carries a `SuggestionSpan` with
`FLAG_AUTO_CORRECTION` (`WMKeyboardService.kt:2871`), with no setting. The
existing "hide composing underline" setting governs a different span.
**Size.** XS.

### L18 — One-finger swipe across the whole board
**LeanType.** v4.2.9 and v4.3.1. A horizontal swipe anywhere on the keys
switches language, moves the cursor, toggles the numpad, hides the keyboard or
deletes a word; it turns gesture typing off while set.
**WM now.** ⚠️, by design. Two-, three- and four-finger swipes cover the same
need with twelve bindable slots and leave glide alone
(`multiFingerSwipes`, `SettingsRepository.kt:9112`). A one-finger version would
fight glide, which is why LeanType makes them exclusive.
**Size.** S if wanted for people who never glide. Low priority.

### L19 — Tamil V4 layout
**LeanType.** v4.3.0. A "Tamil V4" layout for the Tamil locales.
**WM now.** ⚠️, unidentified. WM ships Tamil99, InScript, Gboard's Tamil,
phonetic, one-row, T9 and several Keyman Tamil layouts
(`app/src/main/assets/layouts/ta_*`, `gb_ta_*`, `kmn_*tam*`), but none is named
V4, and this pass did not establish which standard LeanType means.
**Size.** S once the source is known.

## Catalogue of LeanType-specific features

Features inherited from HeliBoard come from HeliBoard and are checked in the
HeliBoard map. The rest are LeanType's own; their WM status is below, with
the evidence for the ✅ rows.

### AI, voice, translation, OCR, handwriting

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Gemini, Groq, OpenAI-compatible and self-hosted AI providers | 3.6–3.7 | ✅ | OpenAI-compatible chain covers Groq, OpenRouter and others (`SettingsRepository.kt:755`) |
| Model list fetched from the provider | 3.7.8 | ✅ | `AiClient.listModels` (`app/.../ProviderScreens.kt:1387`) |
| Ten custom AI keys, persona hashtags, append mode | 3.7.1–3.8.2 | ✅ | Custom AI actions with append insert mode (`app/.../AiToolScreens.kt:867`) |
| `<think>` stripping, `#showthought` | 3.7.2 | ✅ | Reasoning shown separately in AI chat (`AiChatScreens.kt:1341`) |
| Allow plain HTTP / self-signed AI endpoints | 3.8.6 | not checked | Niche |
| Offline GGUF, translation, Whisper voice, handwriting, camera OCR | 3.8–4.2 | settled | Plugins, see top |
| Cloud speech-to-text (Groq, OpenAI, Gemini) | 4.2.3 | ✅ | `core/tools/.../TranscriptionClient.kt:19` |
| Keep screen on while dictating | 4.2.9 | ✅ | `voiceBar.keepScreenOn` (`PrivacyVoiceClipboardScreens.kt:469`) |
| Voice vocabulary prompt | 4.1.3 | ✅ | `whisper.biasWords` (`PrivacyVoiceClipboardScreens.kt:675`) |
| Screenshot chip with one-tap OCR | 4.1.9 | ✅ | Screenshots join clipboard history; image clips have Extract (`feature/ime/.../ClipImageActions.kt:27`) |

### Text expander

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Shortcuts with prefixes, regex triggers | 3.8.6 | ✅ | Prefix triggers and pattern snippets (FEATURES.md "Pattern (regex) triggers") |
| Expand immediately, backspace undoes | 3.8.4 | ✅ | Immediate snippets; one backspace reverts |
| Suggestion-only mode, shortcuts in the strip | 4.3.2 | ✅ | Per-snippet "ask before it expands", folder-wide via bulk edit |
| `%date:pattern%`, `%cursor%`, `%clipboard%`, `%time12%` | 3.8.4–4.2.9 | ✅ | `SnippetStore.kt:1214`, `1433`–`1450` |
| Battery, language, greeting, bullets, lists, tab | 3.8.4–4.3.2 | ⚠️ | L10 |
| Clipboard modifiers | 4.1.4 | ❌ | L2 |
| Fill-in custom variable | 3.9.4 | ❌ | L3 |
| Image shortcuts | 4.3.2 | ❌ | L1 |
| Edit all as text, diff, undo snapshot | 4.3.2 | ⚠️ | L11 |

### Clipboard and one-time codes

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Clipboard search | 3.7.1 | ✅ | Search pill (FEATURES.md "Search inside the panel") |
| Swipe to delete with undo | 3.7.5 | ✅ | `clipboard.undoDelete` (`SettingsRepository.kt:8414`) |
| Inline clip editing | 4.1.0 | ✅ | `clipEditable` and edit action (`feature/ime/.../ui/ClipboardFields.kt:2407`) |
| Fold pinned items | 3.8.2 | ✅ | `clipboard.pinnedFolders` (`SettingsRepository.kt:8300`) |
| Action pill on long press, multi-select batch pin/delete | 4.2.9 | ✅ | Hold actions and `ClipSelectionBar` (`ClipboardFields.kt:1333`) |
| Sensitive data and OTP filtering | 4.2.9 | ✅ | Three-way sensitive handling, paste-time purge |
| Screenshots saved to history | 3.7.5 | ✅ | MediaStore observer |
| Notification OTP with app selection | 4.1.1 | ✅ | Notification listener OTP chip (FEATURES.md "One-time codes from notifications") |
| Hold the clipboard key and slide to a clip | 4.1 era | not found | Docs and code show no slide-to-paste from the tool; too small to rank |

### Typing, suggestions, learning

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Inline math on `=` | 4.1.9 | ✅ | Calc chip (`core/tools/.../CalcEngine.kt:53`; docs `smart/chips.mdx`) |
| Bracket and quote auto-pairing | 4.3.2 | ✅ | `autoCloseBrackets` (`SettingsRepository.kt:7212`), 18 pairs incl. CJK |
| Learning threshold 1–5 | 4.1.2 | ✅ | `newWordSightings` slider (`SettingsRepository.kt:10719`) |
| Suggestion count 3–7 | 4.2.7 | ✅ | `suggestionStrip.slotCount` (`SettingsRepository.kt:10604`) |
| Autocorrect on space vs punctuation | 3.9.4 | ✅ | `setAutocorrectOnPunctuation` (`SettingsRepository.kt:21619`) |
| Don't re-apply a correction undone by backspace | 4.3.2 | ✅ | The pair is blocked on revert (FEATURES.md "Revert-on-backspace") |
| Clear learned words | 4.3.1 | ✅ | `clearLearnedData` (`SettingsRepository.kt:21300`) |
| Shift cycles the case of the word at the caret | 4.2.9 | ✅ | `shiftRecasesWordAtCaret` (`SettingsRepository.kt:7230`) |
| Hold Delete removes whole words | 4.2.9 | ✅ | `deleteHoldDeletesWords` (`SettingsRepository.kt:7423`) |
| Blocked words with regex, own import/export | 3.8.6, 4.3.0 | ⚠️ | L8 |
| Autocorrect underline toggle | 4.3.2 | ⚠️ | L17 |
| Personal dictionary `.txt` export | 4.3.2 | ⚠️ | L12 |
| Gboard dictionary import | 3.6.9 | ✅ | Gboard `PersonalDictionary.zip` import (docs `smart/personal-dictionary.mdx`) |
| Sort personal dictionary by date added | 4.2.7 | ✅ | `DictionarySortKey.ADDED` (`core/settings/.../DictionarySort.kt:6`) |
| Unicode font styles while typing | 4.3.2 | ✅ | Fancy text bar and layout (`app/.../RowsModesScreens.kt:164`) |

### Gestures, editing, layouts

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Gboard-style text-editing pad, persistent Select | 3.8.9–4.2.7 | ✅ | Text-editing panel, editable `PanelLayoutSpec` |
| Touchpad with two-finger gestures | 3.8.6 | ✅ | Trackpad tool: two-finger word moves, tap = space |
| Delete swipe by word, by line | 4.2.7–4.2.9 | ✅ / ⚠️ | Word and line arms; visual lines L15 |
| Whole-board one-finger swipe | 4.2.9 | ⚠️ | L18 |
| Hexagonal layouts | 4.2.7 | ❌ | L4 |
| Tamil V4 | 4.3.0 | ⚠️ | L19 |
| Automation broadcasts to switch layout, language, mode | 4.3.2 | ✅ | `AutomationSettings` (`core/settings/.../AutomationSettings.kt:95`) |
| Switch straight to another IME | 3.9.6 | ✅ | `switchInputMethod(imeId)` (`WMKeyboardService.kt:20532`) |
| Custom layout slots 1–5 | 3.9.4 | ✅ | Unlimited user layouts, layer and keyboard key actions |
| Localized numeral systems in number-row popups | 4.3.2 | ✅ | `NumeralSystem` in language settings (`LanguageSettingsScreens.kt:109`) |
| Unicode symbol palette by topic | 4.3.2 | ⚠️ | L16 |
| Emoticon sub-categories | 4.2.7 | ✅ | Kaomoji library with tag tabs |

### Window, appearance, toolbar

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Floating keyboard in the IME window, no overlay permission | 4.2.5 | ✅ | Touchable-region insets (FEATURES.md "Floating keyboard") |
| Float automatically in landscape | 4.2.9 | ✅ | `floatingKeyboardLandscape` (`SettingsRepository.kt:3770`) |
| Foldable profile: scale, side padding, split gap | 4.0.8–4.3.0 | ✅ | Per-screen-variant sizing |
| System wallpaper behind keys, blurred | 4.2.7 | ✅ | `WallpaperBackdrop` (`core/theme/.../WallpaperBackdrop.kt:35`), glass themes |
| Separate corner radii per key class | 4.2.1–4.2.3 | ⚠️ | L13 |
| Preview bubble width, height, corners | 4.2.9 | ⚠️ | L14 |
| Shift icons for off, on, caps lock | 4.2.3 | ✅ | `KEY_SHIFT_LOCK` icon slot (`core/icons/.../IconSlots.kt:65`) |
| Key gap slider | 3.7.8 | ✅ | `keyGapScale` (`SettingsRepository.kt:3800`) |
| Toolbar keys spread to fill the width | 4.1.2–4.2.9 | ✅ | `toolbar.greedy` (`SettingsRepository.kt:2084`) |
| Dual toolbar (suggestions and tools on separate rows) | 3.6.9 | ✅ | Second toolbar row (#1217) |
| Haptic amplitude and style | 3.8.1–4.2.9 | ✅ | `haptics.amplitude`, `style` (`KeyPressSettingsScreens.kt:1293`) |
| Settings fine-tuning drawers | 4.3.0 | ✅ | `SettingLevel.ADVANCED` rows |
| Skip the setup wizard | 4.3.2 | ✅ | Skip in the wizard's corner (docs `start/setup.mdx`) |
| Restart keyboard | 4.3.2 | ❌ | L7 |

### Per-app profiles and hardware keyboards

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Auto-incognito and force non-incognito per app | 4.2.5 | ✅ | `incognitoApps` true/false map (`SettingsRepository.kt:4320`) |
| Direct commit per app | 4.2.4 | ✅ | Mode `commitAsTyped` (`KeyboardModes.kt:385`) |
| Autocorrect, auto-space, suggestions per app | 4.2.4–4.2.9 | ✅ | Mode overrides (`KeyboardModes.kt:343`–`364`) |
| Symbol composing for Tasker | 4.2.5 | ❌ | L5 |
| Enter action override, strip no-enter flag | 4.2.4 | ⚠️ | L9 |
| Keyboard on `TYPE_NULL` fields | 4.2.6 | ✅ | Fieldless windows get key events (FEATURES.md "Keep the keyboard on screen") |
| Hardware keyboard suggestions, autocorrect, digit picks | 4.2.4 | ✅ | Typed characters through the IME pipeline, Alt+1…9 |
| D-pad navigation in panels | 4.2.4 | ✅ | Panel focus ring |
| Ctrl+Space language switch | 4.2.4 | ✅ | Ctrl+Space cycling with overlay |
| Physical layout remap | 4.2.7 | ❌ | L6 |
| Mute custom sounds over Bluetooth | 4.2.6 | ✅ | A Mode can key on Bluetooth audio and turn key sound off (`KeyboardModes.kt` `bluetoothAudio`, `keySound`) |

### Backup, storage, updates

| Feature | Since | WM | Evidence / note |
|---|---|---|---|
| Category-selective backup | 3.8.6–4.2.9 | ✅ | Backup sections |
| Encrypted export of API keys | 4.2.9 | ✅ | Passphrase backups with an include-secrets switch (`SettingsBackup.kt:96`) |
| Storage and cache screen | 4.2.7 | ✅ | Storage screen with per-category delete |
| In-app update checker | 4.1.2 | ✅ | Update channels |
| App language independent of the system | 3.9.6 | ✅ | App language picker |

### Inherited from HeliBoard (named only)

Multilingual typing, emoji search and inline emoji search, the glide library
loader, custom JSON and text layouts, popup key order, toolbar customisation
and pinned keys, the timestamp and broadcast keycodes, modifier keys
(Ctrl/Alt/Fn/Meta), number row options, split and one-handed modes,
recapitalising a selection with Shift, the colour editor, the personal
dictionary screen, dead keys, and predictive back. Status for all of these is in
`2026-11-13-heliboard-gap-map.md`.

## WM Keyboard has, LeanType does not (brief)

- **Glide typing that works out of the box**, in any language with a word list:
  WM's own lattice decoder plus the optional FUTO Swipe engine. LeanType still
  downloads or imports Google's `libjni_latinime.so`.
- **Language breadth**: 1,555 languages, Gboard layout parity (650 boards),
  the RIME and Mozc engines, Avro, and phonetic layouts for dozens of Indic and Arabic-script
  languages. LeanType's CJK comes from a plugin without frequencies.
- **Keyboard Modes**: per-app profiles that also trigger on time, orientation,
  field kind, Bluetooth device and field text, and change theme, toolbar,
  layout and languages, not only typing behaviour.
- **Selection macros**: 49 actions on selected text (format, convert,
  find/replace, chat markup per app, Banglish and Hinglish both ways).
- **Typing aids LeanType has no answer to**: words on keys, the mid-stroke
  ambiguity picker, the smart punctuation key, multi-finger swipes, flick keys,
  braille and morse, and Harper grammar as a system spell checker.
- **In-process engines** for voice (several ASR engines), OCR, translation,
  handwriting and on-device LLMs, versus LeanType's separately installed
  plugins.
- **Glass and animated themes, sticker generator, WM Pass, Python plugins,
  MCP-aware AI chat**, and about 30 utility tools (calendar, weather, notes,
  files, Home Assistant, KDE Connect and more).
- **Cloud backup destinations** (S3, WebDAV, SMB, Proton, Mega and others).

## Gap list ranked by value to users

1. **L1 image shortcuts** (❌, M). The most visible new LeanType feature this
   month and a common request in Gboard-style keyboards: a saved meme, a
   signature, a QR code under a short trigger. WM has the insert path already.
2. **L2 clipboard modifiers in snippets** (❌, S). Cheap, because the
   transforms exist in the selection macros, and useful to students and anyone
   pasting from Wikipedia.
3. **L8 regex blacklist and its own file** (⚠️, S). Asked for by people
   filtering slurs or a whole family of unwanted completions; sharing a block
   list between devices is the other half.
4. **L3 fill-in prompt** (❌, M). Turns one snippet into a template; pairs with
   Espanso forms, which WM imports today by flattening.
5. **L9 per-app Enter override** (⚠️, S). Fixes chat apps that send on Enter or
   never show Send, with a one-field change to Modes.
6. **L15 visual-line delete swipe** (⚠️, S–M). Makes the existing line arm
   behave as people expect in long wrapped messages.
7. **L10 extra snippet variables** (⚠️, S). Greeting and list generators are
   the useful ones.
8. **L5 Tasker symbol composing** (❌, S–M). Small audience, but those users
   cannot work around it without losing suggestions.
9. **L11 snippets as one text document** (⚠️, M). Power users with hundreds of
   snippets.
10. **L13 corner radius per key class** and **L14 preview size** (⚠️, S each).
    Theme tinkerers.
11. **L12 dictionary `.txt` export** (⚠️, S).
12. **L16 symbol topics** (⚠️, S, data only).
13. **L17 underline toggle** (⚠️, XS).
14. **L19 Tamil V4** (⚠️, S once identified).
15. **L4 hexagonal layouts** (❌, L). Distinctive, but a large change for a
    niche layout family.
16. **L18 one-finger board swipe** (⚠️, S). Conflicts with glide; WM's
    multi-finger swipes already cover it.
17. **L6 physical layout remap** (❌, M). Android covers most of it.
18. **L7 restart keyboard** (❌, XS). Little reason to exist in WM.

## Licence note

LeanType is GPL-3.0 and WM Keyboard is MIT. Nothing here was copied; every
entry describes behaviour from LeanType's release notes and feature guide.
Any implementation must be written from these descriptions, not from
LeanType's source.
