# AnySoftKeyboard → WM Keyboard: Feature-Gap Map (first pass, 2026-11-13)

## Context

The July 2026 passes collected AnySoftKeyboard's release notes but never compared
them against WM. This is the first full comparison.

Compared at:

- AnySoftKeyboard (ASK): all 47 GitHub releases, `v128` (2015-03) through
  `1.13-r2` (2026-09-08), plus `main` at `1ee43e7f0` (2026-11-09). The GitHub
  release bodies are thin, so the catalogue also comes from the in-app
  changelog (`ime/releaseinfo/.../VersionChangeLogs.java`, which reaches back to
  1.7 and already lists the unreleased milestone 96), every settings screen in
  `ime/app/src/main/res/xml/prefs_*.xml`, `night_mode_prefs.xml`,
  `power_saving_prefs.xml`, the key-code table in `api/.../KeyCodes.java`, the
  top/bottom generic rows in `extension_keyboards.xml`, and the add-on packs in
  `addons/` (63 language packs, 4 theme packs, the BBCode quick-text pack) and
  `ime/addons/` (19 built-in themes, 19 quick-text groups).
- WM Keyboard: `main` at `721f3ebb6` (2026-11-13). Line numbers were read
  from the working tree that day, which carried other sessions' uncommitted
  edits, so a number can be off by a few lines; the symbol named next to it is
  the stable reference.

Every "WM now" line was checked by grepping the code and reading the hit, not
taken from `FEATURES.md`, following the rules in
`2026-07-20-heliboard-comparison.md` (quote globs, try WM's own vocabulary
before calling something absent). Settings live in
`core/settings/src/main/java/com/wasimaster/wmkeyboard/core/settings/SettingsRepository.kt`,
written `SettingsRepository.kt` below unless another file is named.
`WMKeyboardService.kt` is `feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/WMKeyboardService.kt`.

Legend: ❌ absent · ⚠️ partial or weaker · ✅ at or above parity.
Size: XS = under an hour, S = an afternoon, M = a day or two, L = a week or more.

## Scorecard

| Bucket | Count | IDs |
|---|---|---|
| ASK features and options catalogued | ~110 | settings, key codes, gestures, add-ons |
| ✅ WM at or above parity | ~93 | listed under "Already covered" |
| ❌ Absent | 7 | K1–K7 |
| ⚠️ Partial | 10 | P1–P10 |
| Language/layout breadth | WM far ahead | 3,685 layout files vs 63 packs; 4 ASK layouts missing (K7) |

Nothing on the list is large. The biggest item is the gesture-action map (P1),
and most of the rest are single settings.

## ❌ Absent

### K1 — Pinch and stretch on the keyboard
**ASK.** Two gestures on the key area with their own action slots: pinch
(default "merge layout") and stretch (default "split layout"). Both can be bound
to any of the 23 gesture actions (P1).
**WM now.** ❌ on the board. Pinch handling exists only for floating tool boxes
(`feature/ime/.../floating/FloatingApplet.kt:61`), the QR scanner zoom
(`QrScanPanel.kt:464`) and image viewers. Split is a toolbar tool and a setting
(`ToolbarTool.SPLIT`, `splitKeyboard` `SettingsRepository.kt:3758`), not a
gesture.
**Size.** S–M. A two-pointer scale detector in the key-grid gesture layer, gated
so it never fights two-thumb glide; bind it to the existing split toggle.

### K2 — Emoji default gender (and random skin tone or gender)
**ASK.** "Emoji default gender" (person / woman / man / random) next to "Emoji
default skin-tone", which also offers "random".
**WM now.** ❌. A default skin tone exists (`defaultSkinTone`
`SettingsRepository.kt:8619`, enum `EmojiSkinTone` `:1918`: NONE, LIGHT …
DARK, no random), plus per-emoji memory of the last pick
(`toneOverrideByLastUsed`). Gender variants are collapsed under their base emoji
and picked per emoji (`feature/ime/.../ui/EmojiFields.kt:116`); there is no
global gender default. `grep -i gender core/settings` returns nothing.
**Size.** S. Mirror the skin-tone default with a gender enum applied when the
palette grid resolves a base emoji.

### K3 — Key-label letter-case override
**ASK.** "Keyboard letter-case override": use theme case, let Android decide,
always lowercase, or always uppercase.
**WM now.** ❌. No setting or theme field controls label case; labels follow the
shift state. Searches for label case, upper/lower label and case override in
`core/settings`, `core/theme` and `feature/ime` found only unrelated hits.
**Size.** S. One enum in layout behaviour, applied where letter labels are drawn.

### K4 — "Pop text" from keys
**ASK.** The typed text floats up out of the key: never, on word correction, on
any word, or on any key.
**WM now.** ❌. WM's key-press effects are particles (`KeyEffectKind` STARS,
HEARTS, SPARKLE, CONFETTI, EMOJI, CUSTOM_IMAGE,
`core/theme/.../ThemeSpec.kt:874`); nothing animates the committed word or
character.
**Size.** S–M. An overlay that draws the committed text at the key and lifts it;
must respect `reduceMotion` and power saving.

### K5 — Homophone search (CJK)
**ASK.** A key (`HOMOPHONE_SEARCH`) that, after you type one Chinese or Japanese
character, lists characters with the same reading.
**WM now.** ❌. No homophone lookup in the Rime, pinyin, Cangjie or Zhuyin paths;
`grep -i homophone` finds nothing.
**Size.** M. Reverse-reading lookup over the installed tables, shown as a
candidate list. Niche.

### K6 — Full-screen editor in portrait
**ASK.** Separate toggles for the full-screen extract editor in portrait and in
landscape.
**WM now.** ❌ for portrait. `landscapeFullscreen` (`SettingsRepository.kt:9786`)
drives `onEvaluateFullscreenMode` (`WMKeyboardService.kt:7492`); there is no
portrait counterpart.
**Size.** XS. Low value.

### K7 — Four layouts ASK ships and WM does not
**ASK.** Bribri (`bzd_qwerty`), French Ergo-L, Russian Rulemak, German AdNW and
KOY.
**WM now.** ❌ for these four. Searched the 3,685 files in
`app/src/main/assets/layouts` and `core/language` by name and language code.
Every other ASK layout family checked was present: Coptic, Old North Arabian,
Songhay and Pilagá (`sam_son_qwerty`, `sam_plg_qwerty`), Kachin, Tifinagh,
Sinhala, Rusyn, Hawaiian, Piedmontese, Sardinian, Scottish Gaelic, Sorani and
Kurmanji, Thai, Urdu, Esperanto, Luxembourgish, Arabic Lulua, Halmak, Workman,
Colemak, Dvorak, Neo 2, Bépo, Svorak, Greek polytonic, Hindi InScript, Belarusian
Łacinka, Cangjie and Zhuyin, and a function-key PC board
(`BuiltInLayouts.kt:541`).
**Size.** S each, as JSON layouts. WM also imports ASK layout XML directly
(`app/.../FileImport.kt:772`), so a user can bring any of these in today.

## ⚠️ Partial

### P1 — Gesture-to-action map
**ASK.** Eleven gesture slots, each bindable to any of 23 actions (next/previous
alphabet or symbols, cycle all keyboards, shift, space, hide, backspace,
back-word, clear entire text, cursor up/down/left/right, split, merge, compact
left/right, utility keyboard, emoji popup). Slots: one-finger swipe up, down,
left and right anywhere on the board; spacebar swipe left, right and up;
two-finger swipe left and right; pinch; stretch. Two thresholds tune it: swipe
velocity and swipe distance, five steps each.
**WM now.** ⚠️ Broader in places, narrower in others.
- Multi-finger swipes: 2, 3 and 4 fingers in four directions, each bound to any
  key action, tool or edit (`core/language/.../layout/FingerSwipes.kt:19`,
  defaults `:48`, setting `multiFingerSwipes` `SettingsRepository.kt:9112`).
  This is more than ASK's two two-finger slots.
- Per-key swipe arms (`keySwipes` `:9143`), flick keys, and backspace swipes
  (`backspaceSwipeUnit` `:7382`).
- Spacebar: short and long swipe (`spaceShortSwipe` `:3929`, `spaceLongSwipe`
  `:3931`) limited to `SpaceSwipeAction` NONE, LANGUAGE, CURSOR, NUMPAD,
  KEYBOARDS, SECONDARY_LAYOUT (`:1325`); swipe down hides (`spaceSwipeDownHide`
  `:9772`); swipe up opens the touchpad or numpad (`:7345`, `:7351`).
- Missing: a one-finger whole-board direction map (WM gives letter-area swipes
  to glide, handwriting or select-key via `letterSwipeAction` `:3921`); arbitrary
  actions on spacebar swipes; pinch and stretch (K1); global velocity and
  distance thresholds (only `spaceSwipeDistanceDp` `:7284` and `flickDistanceDp`
  `:9853`).
**Size.** M. Let spacebar swipes take a `KeyAlternate` like finger swipes do, and
add a velocity/distance pair. A whole-board one-finger map only makes sense when
glide typing is off.

### P2 — Swipe up from the spacebar opens a utility keyboard
**ASK.** The default spacebar swipe-up opens a small board with select all, cut,
copy, paste, fine selection, undo, redo, arrows, Home, End, split/merge/compact,
voice and settings.
**WM now.** ⚠️ Every one of those actions exists (the TEXT_EDIT panel,
`ToolbarTool` CURSOR_*, SELECT_MODE, COPY, CUT, PASTE, UNDO, REDO, SPLIT,
ONE_HANDED in `core/common/.../settings/ToolbarTool.kt:36-73`), but spacebar
swipe-up can only open the touchpad or the numpad.
**Size.** XS–S. Add the text-editing panel as a swipe-up target, or fold this
into P1.

### P3 — Common bottom and top rows, picked apart from the layout
**ASK.** "Common bottom generic row" offers 18 presets (Regular, Classic,
iPhone-styled with or without mic, Alternate, Old School, Simple, AOSP, New
Generation, four Minimal variants and others). "Common top generic row" offers 11
(none, small, regular, numbers, alternate numbers, navigation keys in three
forms, simple numbers, terminal, quick text editing). A switch decides whether a
layout may override them.
**WM now.** ⚠️ The top side is covered by separate rows: number row
(`numberRow` `:3898`), arrow row, symbol row (`symbolRowEnabled` `:4387`),
second toolbar, and modifier keys in layouts (`KeyAction.Mod`,
`core/language/.../layout/KeyActions.kt:282`). The bottom row belongs to each
layout and is changed through the layout editor or a few toggles
(`showGlobeKey` `:3985`, `swapCommaAndGlobe`, `commaAsEmoji`,
`bottomRowHeightDp`). There is no preset picker that swaps the bottom row
across every layout at once.
**Size.** S–M. A handful of bottom-row presets applied at layout compile time.

### P4 — Night mode bundle
**ASK.** Night mode (never, follow system, always) darkens the keyboard theme
and the settings app, and can mute key sounds and vibration.
**WM now.** ⚠️ Theme switching is ahead of ASK (`autoTheme` `:3722` with
SYSTEM, SCHEDULE and SUN triggers, `AutoThemeTrigger` `:242`; settings app
follows `themeMode` `:3705`). Muting at night needs a Keyboard Mode with
`timeFrom`/`timeTo` plus `keySound`/`keyHaptics` (`KeyboardModes.kt:394-396`,
`:459-460`); modes cannot trigger on the system's dark mode.
**Size.** S. A dark-mode condition for modes, or two "quiet in dark mode" toggles.

### P5 — Power saving: dark simple theme, suggestions off
**ASK.** Power saving (never, low battery, always) can switch to a dark simple
theme and turn off word suggestions, besides sound, vibration, animations and
gesture typing.
**WM now.** ⚠️ WM's power saving drops far more (haptics, sound, animations,
glide trail, glass blur, gesture typing, emoji prediction, smart chips, network,
on-device models, moving themes and others,
`PowerSavingSettings.kt:78-182`) but has no theme swap and no suggestions-off
switch.
**Size.** S.

### P6 — Numbers on top in password fields by default
**ASK.** Password fields get a number row automatically, and Shift turns that
row into symbols.
**WM now.** ⚠️ The seeded Passwords mode (`KeyboardModes.kt:672-688`) does not
set `numberRow`, though a mode can (`KeyboardModes.kt:404`), and
`numberRowShiftSymbols` exists (`SettingsRepository.kt:9992`). A user who turns
the global number row off gets none in password fields unless they edit the mode.
**Size.** XS. Seed `numberRow = true` on the Passwords mode.

### P7 — Hint label alignment
**ASK.** Override the hint position: left, centre or right, and top or bottom;
plus hint size (none, small, normal, big).
**WM now.** ⚠️ Hint size and nudges exist (`hintFontScale` `:9955`,
`hintOffsetDp` `:9961`, `hintOffsetXDp` `:9967`, `longPressHints` `:4065`), but
not a corner or alignment pick.
**Size.** S.

### P8 — Alt+Space and Shift+Space on hardware keyboards
**ASK.** Separate toggles for Alt+Space and Shift+Space to switch layout.
**WM now.** ⚠️ Ctrl+Space and Ctrl+Shift+Space (`languageSwitchChord` `:2755`)
and a bare Ctrl+Shift (`languageSwitchCtrlShift` `:2763`); no Alt+Space or
Shift+Space.
**Size.** XS. Two more chords in `core/tools/.../HardwareShortcuts.kt` (`:486`).

### P9 — Multi-tap timeout
**ASK.** A seven-step multi-tap timeout (disabled … wait forever) for its
16-key and multi-tap layouts.
**WM now.** ⚠️ Multi-tap works on the 452 layout files named `t9`, but the
cycle window is fixed at 1,000 ms (`core/input/.../MultitapCycle.kt:90`).
**Size.** XS.

### P10 — Next-word prediction controls
**ASK.** Next-word suggestions: off, words only, or words and punctuation; and
an aggressiveness level (minimal, medium, many).
**WM now.** ⚠️ Punctuation chips exist (`punctuation` `:10585`), and word pairs
can be turned off per language (`wordPairsOffLangs` `:11185`), but there is no
single switch and no aggressiveness knob.
**Size.** S. Low value; WM's strip already has its own slot controls.

## Already covered (✅, checked)

| ASK feature | WM evidence |
|---|---|
| Gesture typing, with user words | `gestureTyping` `:3916`, `GestureSettings` `:9041` |
| Auto-select aggressiveness (5 levels) | autocorrect confidence `AutocorrectSettings` `:2998` |
| Split-word correction | `autocorrectSplits` `:11102` |
| Learn new words: off / after a while / immediately | `learnFromTyping` `:4050`, `learnedWordMinCount` `:10700`, `askBeforeLearning` `:10729` |
| Android vs private user dictionary | `useSystemDictionary` `:10998`, `addWordsToSystemDictionary` `:4056` |
| User-dictionary shortcuts | `expandUserDictShortcuts` `:10988` |
| Abbreviations editor | Snippets (`core/snippets`) |
| Ignored-words editor | suggestion blacklist `:3280` |
| Contacts dictionary | `contacts` `:3249` |
| Several dictionaries per layout | `secondaryLanguages` `:3695`, multilingual typing |
| Swap punctuation and space | `hugPunctuation` `:3145` |
| Double space to period, auto-caps | `doubleSpacePeriod` `:3111`, `capitalize` `:3103` |
| Space after picking a suggestion | `autoSpaceAfterSuggestion` `:10910` |
| Suggestions restart on cursor move | word resume, `WMKeyboardService.kt:1231` |
| Space returns from symbols to letters | `symbolsReturnOnSpace` `:10365` |
| Remember layout per app | `perAppLanguage` `:4028` |
| Layout for URL or email fields | Keyboard Modes bound to field kinds, with `layoutId` (`KeyboardModes.kt:446`, `:716`) |
| Back-word, swipe from backspace to delete word | backspace swipe `:3954`, `DELETE_WORD` edit action |
| Clear entire text | `ToolbarTool.CLEAR_FIELD` |
| Shift+Enter forces a newline | `shiftEnterNewline` `:10082` |
| Shift recases selected text | `recapitalizeSelectionWithShift` `:7219` |
| Wrap selection in quotes or brackets | `wrapSelectionWithPair` `:7198` |
| Fine text selection | `ToolbarTool.SELECT_MODE` |
| Clipboard history, paste list on long-press, OS sync | `ClipboardSettings` `:8206`, `holdPaste` `:8312`, `history` `:8208` |
| Suggest pasting a fresh copy | `suggestRecent` `:8270` |
| Volume keys move the cursor | `volumeCursor` `:3971`, `WMKeyboardService.kt` `volumeCursorDelta` |
| Key repeat, long-press time | `keyRepeat` `:4063`, `longPressDelayMs` `:4061` |
| Hide soft keyboard with a physical one; per-language hardware mapping | `onEvaluateInputViewShown` `WMKeyboardService.kt:7615`, `autoShowUi` `:2783`, `followOnScreenLayout` `:2771` |
| Split, compact left/right, per orientation | `splitKeyboard` `:3758`, `splitOnlyOnLargeScreens` `:10183`, one-handed profiles with `portraitOnly` `:665` |
| Key height per orientation, bottom offset | `sizingOverrides` `:3904`, `bottomPaddingDp` `:3757` |
| Key preview on/off and position | `KeyPopupSettings` `:359` (on-key or floating, with offsets) |
| Keyboard name on the spacebar | `spacebarDisplay` `:9927` |
| Language key: popup vs cycle, hide it | globe tap/hold, `languagePickerStyle`, `showGlobeKey` `:3985` |
| Domain key and custom domain text | web and email symbol sets, `core/tools/.../SymbolSets.kt:96`, custom sets |
| Theme colours follow the app | `appColors` `:3724` (the probe credits ASK's method, `core/theme/.../AppPalette.kt:51`) |
| Draw behind the navigation bar | nav-bar colour handling, `WMKeyboardService.kt:6840` |
| Animation level | `reduceMotion` `:3891`, system motion |
| Sound, custom volume, vibration length, system vibration, long-press vibration | `KeySoundSettings` `:1144` (`volume` `:1150`), `HapticSettings` `:1263` (`onLongPress` `:1279`), `HapticPlayer.kt:138` |
| Emoji: own board, history, `:` tag search, tags on long-press, skin tone, close after pick, kaomoji, text smileys | `inlineEmojiSearch` `:3296`, `emojiLongPressName` `:4165`, `defaultSkinTone` `:8619`, `closeAfterInsert` `:8631`, `KaomojiSettings` `:8860` |
| Clear emoji history | `emojiClearRecentsButton` `:4163` |
| Image and GIF insertion | Gallery and GIF tools, sticker send |
| Incognito, and honouring no-learning fields | `incognito` `:4122`, `autoIncognito` `:4314`, `WMKeyboardService.kt:61238` |
| Inline autofill | `inlineAutofill` `:3304` |
| Direct boot | `DirectBootSettings.kt` |
| Backup and restore, chosen parts, chosen location | `SettingsBackup.kt`, `BackupConfigs.kt`, `BackupLocation.kt` |
| Clear learned data; usage statistics | privacy "clear learned words", typing statistics |
| Status-bar icon | opt-in shade controls, `feature/ime/.../KeyboardControls.kt:16` |
| Hide the launcher icon | `LauncherName.setShown`, `app/.../LauncherName.kt:72` |
| App UI language | in-app language picker |
| Launcher shortcuts, setup wizard, holiday easter eggs | launcher shortcuts, onboarding, easter eggs |
| Crash reports and developer logs | `core/common/.../debug/DebugLog.kt:157` |
| Chinese radical tables from a folder | `.cin` and Rime import, `app/.../FileImport.kt:463` |
| Add-on packs (languages, themes, quick text) | addon repository, theme and layout import, Snippets |
| Top extension keyboard (swipe up above the board) | always-visible symbol row, number row, second toolbar instead |
| Keyboard reorder | enabled-layout order `enabledLayoutIds` `:3670` |

## What WM has that AnySoftKeyboard lacks

- Breadth: 3,685 layout files against ASK's 63 language packs, and import of
  ASK's own layout XML and backups (`app/.../FileImport.kt:772`,
  `ForeignBackupImport.kt:410`) alongside Keyman, FlorisBoard, Multiling, `.cin`
  and Rime.
- Its own voice input: Whisper, sherpa-onnx and other on-device engines with
  spoken punctuation. ASK's mic key only hands off to Google's voice IME.
- AI and on-device models: Multi-provider AI tools, on-device LLMs,
  translation, Harper grammar checking and a system spell checker.
- Tools: OCR, QR, document scanning, handwriting, calculator, currency and
  unit conversion, weather, notes, to-dos, files, a floating tool box, and dozens more
  toolbar tools. ASK has none of these.
- Layouts and input: Floating keyboard, spacebar trackpad, flick keys, hint
  flicks, two-thumb glide, an in-app layout editor, number and arrow rows with
  their own height, and transliteration engines (Avro, Khipro, Hindi phonetic).
- Clipboard: Pins, folders, search, sensitive-content detection, expiry,
  entity chips, link cleaning and metadata stripping. ASK keeps a paste list.
- Context: Keyboard Modes that change layout, rows, tools, sound and
  learning per app, field kind, time, orientation or connected device.
- Themes: A theme engine with gradients, photo backgrounds, glass,
  per-key styles and an editor. ASK ships 19 fixed themes plus 4 add-on packs.
- Settings: Settings search, per-setting reset, settings history and cloud
  backup destinations.

## Gap list ranked by value to users

1. **P1 Gesture-to-action map** (M): arbitrary spacebar-swipe actions and swipe
   thresholds are what ASK users rely on most when they switch.
2. **P3 Bottom-row presets** (S–M): an iPhone-style or minimal bottom row
   without editing each layout.
3. **K1 Pinch and stretch to split and merge** (S–M).
4. **P6 Number row in password fields by default** (XS): cheap, and it affects
   every password typed.
5. **P4 Night mode mutes sound and vibration** (S).
6. **P2 Spacebar swipe-up to the text-editing panel** (XS–S).
7. **K2 Emoji default gender, random tone** (S).
8. **P5 Power saving: dark theme and suggestions off** (S).
9. **K3 Label case override** (S).
10. **P7 Hint alignment** (S).
11. **K4 Pop text** (S–M): cosmetic.
12. **P8 Alt+Space and Shift+Space** (XS).
13. **P9 Multi-tap timeout** (XS).
14. **K7 Bribri, Ergo-L, Rulemak, AdNW/KOY layouts** (S each): importable today.
15. **P10 Next-word aggressiveness** (S).
16. **K6 Portrait full-screen editor** (XS).
17. **K5 CJK homophone search** (M): niche.

## Re-running this

```
gh api --paginate 'repos/AnySoftKeyboard/AnySoftKeyboard/releases?per_page=100' \
  --jq '.[] | "### " + .tag_name + " (" + .published_at + ")\n" + (.body // "")'
```

The release bodies alone miss most options. Also read
`ime/releaseinfo/src/main/java/com/anysoftkeyboard/releaseinfo/VersionChangeLogs.java`,
the `prefs_*.xml` files under `ime/app/src/main/res/xml/`, `api/.../KeyCodes.java`,
and `ime/app/src/main/res/values/strings.xml` for the option labels. Note that
`PRE_PREPARED_*_POPUP` key codes are declared in `KeyCodes.java` but no case in
`AnySoftKeyboard.java` handles them, so they are not counted as features.
