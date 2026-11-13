# HeliBoard → WM Keyboard: Feature-Gap Map (re-checked 2026-11-13)

## Context

This is the third pass. The first two are in this folder:
`2026-07-20-heliboard-comparison.md` (method and the false negatives that
pass made) and `2026-07-22-heliboard-gap-map.md` (14 absent and 5 partial,
measured against HeliBoard v4.0).

Compared at:

- **HeliBoard:** every release after v4.0 (v4.1-beta1, v4.1, v4.2-beta1, v4.2,
  v4.3-beta1 of 2026-10-18), plus `main` up to `3e91b4f1` (2026-11-07). The
  repository moved from `Helium314/HeliBoard` to `HeliBorg/HeliBoard`; the
  `gh api` command in the 07-20 file needs the new owner.
- **WM Keyboard:** `main` at `cd14a50a8` (2026-11-13).

Every "WM now" line below was checked by grepping the code and reading the
hit, not taken from `FEATURES.md`. Paths in the 07-22 map predate the module
split and no longer resolve; the paths here are current. All WM settings live
in `core/settings/src/main/java/com/wasimaster/wmkeyboard/core/settings/SettingsRepository.kt`
(written `SettingsRepository.kt` below) unless another file is named, and
`WMKeyboardService.kt` is
`feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/WMKeyboardService.kt`.

Legend: ❌ absent · ⚠️ partial/weaker · ✅ at or above parity.
Size: S = an afternoon, M = a day or two, L = a week or more.

---

## Scorecard

| Bucket | Count | IDs |
|---|---|---|
| ✅ Closed since July (of the 19 open) | 14 | A11, A26, A28, A38, A39, A43, A44, A45, A47, A50, A55, A62, A63, A64 |
| ✅ Closed, with a caveat | 1 | A66 |
| ⚠️ Still partial (July items) | 3 | A9, A23, A53 (now also covers H2) |
| ❌ Still absent (July items) | 1 | A34 (intentional skip) |
| New HeliBoard features since v4.0 | 18 | H1–H18 |
| ✅ of those, WM already has | 14 | H3, H4, H6–H17 |
| ⚠️ of those, partial | 2 | H1, H2 (H2 folded into A53) |
| ❌ of those, absent | 1 | H18 (part of A34) |
| Not verified | 1 | H5, a HeliBoard bug fix rather than an option |
| Layout breadth | closed | 3,685 layout files in `app/src/main/assets/layouts/`; the July "exotic" list is now present (see below) |

Open list, all told: **1 absent, 4 partial** (A34 ❌; A9, A23, A53, H1 ⚠️).
In July it was 14 absent and 5 partial.

---

## ❌ Still absent

### A34 — Gesture (glide) data gathering
**What it is.** An opt-in recorder that saves your glide strokes with the
intended word, so the data can train an open gesture model. HeliBoard has a
passive background collector and an active "contribute words" mode, and since
v4.3-beta1 a review screen that can show only the first instance of each word
(#2891).
**WM now.** ❌. No stroke log, dataset export or contribution flow anywhere
(grep for `gestureData|strokeLog|glideLog|swipeDataset` finds nothing). WM
ships its own decoders (`core/prediction/.../core/gesture/`) and does not
depend on a shared community model.
**Verdict.** Skip. It is a data-collection feature serving HeliBoard's own
model work, and it would need a privacy story WM does not have a reason to
write. **Size:** L.

---

## ⚠️ Still partial

### A9 — One-tap timestamp key or tool
**What HeliBoard does.** A `timestamp` keycode and toolbar key insert the
current date/time in one tap, with a configurable format.
**WM now.** ⚠️. The date and time are reachable, but never in one tap:
- Snippet variables `{datetime}` and `{timestamp}`
  (`core/content/src/main/java/com/wasimaster/wmkeyboard/core/snippets/SnippetStore.kt:1436`,
  `:1444`). A snippet with a short trigger is the closest equivalent; note that
  `{timestamp}` is Unix seconds (`:1468`), not a readable stamp.
- The Clock tool inserts in several formats (`InsertFormat`, including
  `DATE_TIME`, in `core/tools/src/main/java/com/wasimaster/wmkeyboard/core/clock/ClockFormats.kt:16`),
  but from inside its panel.
- `ToolbarTool` (`core/common/src/main/java/com/wasimaster/wmkeyboard/core/settings/ToolbarTool.kt:35`)
  has no timestamp entry, and the layout `KeyAction` set
  (`core/language/src/main/java/com/wasimaster/wmkeyboard/core/layout/KeyActions.kt`)
  has no date action. Custom buttons (`CustomButtons.kt:64`) run shortcuts,
  settings, pages or plugins, not snippets.

**To close.** A `TIMESTAMP` toolbar tool (or a `KeyAction`) that commits
`ClockFormats.format(...)` in the Clock tool's chosen `insertFormat`, so the
format setting already exists. **Size:** S.

### A23 — URLs as one word
**What HeliBoard does.** An optional "URL detection" mode treats a whole URL
or address as a single token for suggestions, autocorrect and deletion.
**WM now.** ⚠️, better than in July. The keyboard now knows when it is
inside an address for spacing and correction:
- No auto-space after a mark that continues an address or number
  (`markContinuesAddress`, used at `WMKeyboardService.kt:12971`).
- A domain ending or file extension typed after a dot is joined back onto it
  and kept away from autocorrect ("google. com" → "google.com", "com" is not
  corrected to "come"; `joinDotSuffix`, `WMKeyboardService.kt:15034`).
- Email tokens extend past letters (`emailTokenBeforeCursor`,
  `WMKeyboardService.kt:28171`).
- Learn-from-text skips links, addresses and handles
  (`core/prediction/.../TextWordScan.kt:240`).

What is still missing is the token itself: the suggestion and word-delete
paths still see `example`, `com` and `path` as separate words, so the strip
offers completions for URL fragments and a word-delete stops at each dot.
**To close.** A URL/address token rule (`.`, `/`, `:`, `_`, `-` count as
in-word once the token matches a scheme, `www.` or a known TLD) shared by the
suggestion lookup and `DELETE_WORD`. **Size:** M.

### A53 + H2 — Dedicated theme colours for emoji search and the clipboard chip
**What HeliBoard does.** Separate colour roles for the emoji search bar
(v4.0-beta1, #2442) and, new in v4.1-beta1, for the clipboard suggestion chip.
**WM now.** ⚠️. Both surfaces draw from the shared "Cards & search bar" slot,
`chipBackground`, plus `suggestionText`
(`core/theme/src/main/java/com/wasimaster/wmkeyboard/core/theme/ThemeSpec.kt:401`).
The HeliBoard theme importer reads both new roles but folds them into that
shared slot (`ChipRoles` and `ChipTextRoles`,
`core/theme/src/main/java/com/wasimaster/wmkeyboard/core/theme/HeliTheme.kt:391`),
so a HeliBoard theme that colours them apart loses the difference on import.
**To close.** Optional `emojiSearchBackground/Text` and
`clipboardChipBackground/Text` fields that fall back to `chipBackground`, and
point the importer's roles at them. **Size:** S.

### H1 — D-pad layout and its shortcuts
**What HeliBoard does.** v4.1-beta1 (#2600) added a D-pad text-editing
layout: arrows, select in the middle, copy/cut, paste, undo and redo, with
shift recapitalising the word at the caret. It opens from a key on the symbols
row's bottom row or a space-swipe option, and v4.2-beta1 (#2778) added a
setting to hide that key after users hit it by accident.
**WM now.** ⚠️, the panel is there and the shortcuts are not. The
`TEXT_EDIT` tool opens an editable text-editing grid of `TextEditAction`s
(`core/common/src/main/java/com/wasimaster/wmkeyboard/core/settings/TextEditLayout.kt:16`)
that covers everything the D-pad does and more; cursor tools repeat on hold
(`SettingsRepository.kt:7156`). But `SpaceSwipeAction`
(`SettingsRepository.kt:1325`) has no text-edit entry, and the symbols layer
offers only a numpad key (`symbolsNumpadKey`, `SettingsRepository.kt:9752`).
A user can place a `KeyAction.Tool(TEXT_EDIT)` key with the layout editor,
which is the only route besides the toolbar.
**To close.** Add `TEXT_EDIT` to `SpaceSwipeAction` and an opt-in "Text
editing key on symbols" beside `symbolsNumpadKey`. Off by default, given the
complaint in #2778. **Size:** S.

---

## ✅ Closed since July

| ID | Feature | Where it is now |
|---|---|---|
| A11 | Auto-show / auto-hide toolbar | `ToolbarPlacement.STRIP` (`SettingsRepository.kt:2336`): tools rest on the strip when nothing is suggested and give way to words. `stripOnlyWithWords` (`:2167`) does the reverse for the own-row placement |
| A26 | Auto-space tuning | `autoSpaceAfterSuggestion` with per-language opt-outs (`:10910`), `autoSpaceAfterGlide` / `autoSpaceBeforeGlide` (`:9252`, `:9262`), `spaceAfterPunctuation` with an editable mark list (`:3124`) |
| A28 | Expand user-dictionary shortcuts | `expand_user_dict_shortcuts` (`:12264`); `SystemUserDictionary.kt:227` reads the `SHORTCUT` column |
| A38 | Number row in symbols, its own toggle | `numberRowInSymbols` (`:10101`) |
| A39 | Space swipe → numpad | `SpaceSwipeAction.NUMPAD` (`:1329`) and `spaceSwipeUpNumpad` (`:7351`) |
| A43 | Show all accent variants in popups | `showAllPopupKeys` (`:10212`), merged in `KeyboardScreen.kt:24540` |
| A44 | Customisable currency keys | `currencyKeys` (`:10269`) |
| A45 | Bottom-row height | `bottomRowHeightDp` (`:10149`) |
| A47 | Shift → caps-lock timing | `shiftCapsLockMs` (`:10204`), read at `WMKeyboardService.kt:15818` |
| A50 | Side-padding scale | `sidePadLeftScale` / `sidePadRightScale`, per screen variant (`:10161`) |
| A55 | Close after insert, beyond emoji | Clipboard paste shares `closePanelAfterInsertIfEnabled` (`WMKeyboardService.kt:55675`); symbols has `symbolsReturnToLetters` (`:10349`) |
| A62 | Broadcast-intent keys | `KeyAction.Broadcast` (`KeyActions.kt:368`) |
| A63 | Per-app UI language (Android 13) | `app/src/main/java/com/wasimaster/wmkeyboard/app/AppLanguage.kt`, `generateLocaleConfig = true` (`app/build.gradle.kts:556`) |
| A64 | Datestamp in backup filename | `wmkeyboard-backup-$stamp` (`app/src/main/java/com/wasimaster/wmkeyboard/app/BackupScreens.kt:1257`) |
| A66 | Predictive back | See caveat below |

**A66 caveat.** HeliBoard's #2256 was a one-line manifest change: it set
`android:enableOnBackInvokedCallback="true"` on `<application>`, which lets the
system play its own back preview when the keyboard is dismissed. WM sets the
flag on each activity (`app/src/main/AndroidManifest.xml:484` and 20 more), not on
`<application>` (`:412`). WM targets SDK 36 (`app/build.gradle.kts:274`), where
predictive back is on by default, so on Android 16 the keyboard window gets the
same system animation. On Android 13–15 it would need the application-level
flag. Neither keyboard animates its own panels from `onBackProgressed`.
Adding the attribute to `<application>` is a one-line change if the 13–15 case
matters.

**Layout breadth.** The July list of missing exotic layouts is now covered by
`app/src/main/assets/layouts/`: Toki Pona (`tok*`), HCESAR, Kaitag (`xdq*`),
Dargwa, Mansi (`mns*`), Eastern Mari (`mhr*`), Chuvash (`cv*`), Kabyle (`kab*`),
Piedmontese (`pms*`), Mirandese (`mwl*`), Central Kurdish (`ckb*`), Gujarati,
Kannada, Korean phonetic, Arabic Hija'i (`ar_hijai`), Hebrew SI-1452
(`he_si1452`), Hindi phonetic, and Bengali Unijoy, Baishakhi and Khipro.

---

## New in HeliBoard since v4.0

User-facing changes from v4.1-beta1 through v4.3-beta1 and `main` to
2026-11-07. Bug fixes, refactors, translations and icon redraws are left out.

| ID | HeliBoard feature (release, PR) | WM status | Where |
|---|---|---|---|
| H1 | D-pad text-editing layout, symbols key, space-swipe toggle, hide-key setting (4.1-b1 #2600, 4.2-b1 #2778) | ⚠️ | See H1 above |
| H2 | Separate colour for the clipboard suggestion chip (4.1-b1) | ⚠️ | Folded into A53 |
| H3 | Key repeat as a toolbar-key / popup long-press (4.1-b1 #1776); arrows repeat on hold by default (#2672) | ✅ | `cursorToolsRepeatOnHold` (`SettingsRepository.kt:7156`), `Key.repeatOnHold` for layout keys (`:597`) |
| H4 | Clipboard paste hint redesign; fix it missing in some fields (4.1-b1 #2659) | ✅ | Recently-copied clipboard chip on the strip |
| H5 | Shift state follows arrow-key cursor moves (4.1-b1 #2664) | not verified | A HeliBoard bug fix, not an option. WM's selection handler (`WMKeyboardService.kt:9765`) was not traced for it |
| H6 | Hardware keyboard support as an opt-in toggle (4.2-b1 #2251) | ✅ | WM's is on and much deeper: `HardwareKeyboardSettings` (`:2686`), shortcuts, panel navigation, suggestion hotkeys, auto-show UI |
| H7 | Clear recent emojis: long-press one to remove, long-press the tab to clear all (4.2-b1 #2603) | ✅ | `EmojiUsage.removeFromHistory` / `clearRecents` (`core/emoji/.../EmojiUsage.kt:204`, `:215`), `emojiClearRecentsButton` (`:4163`) |
| H8 | Regional-indicator letters in the flags category; any two indicators count as one emoji (4.2-b1 #2682, `ebaaddea`) | ✅ | Pair handling in `core/common/.../text/EmojiGraphemes.kt:30`; a Regional Indicator fancy-text style types any letters as indicators (`core/language/.../script/FancyStyles.kt:548`) |
| H9 | Emoji picked from suggestions goes to Recent (4.2-b1 #2062) | ✅ | `onEmojiSuggestionTapped` and the inline `:` search both call `recordEmojiUse` (`WMKeyboardService.kt:55566`, `:30448`) |
| H10 | Floating keyboard handles reachable on Android 15+ (4.2-b1 #2692) | ✅ | Bug fix; WM's floating mode reports the system bar band |
| H11 | Arabic punctuation popups with dotted-circle markers (4.2-b1 #2537) | ✅ | Every popup draws `◌` before a combining mark (`visibleAlternate`, `KeyboardScreen.kt:27643`) |
| H12 | Load AOSP `.combined` word lists as dictionaries (4.2, 4.3-b1) | ✅ | `core/prediction/.../DictionaryLoader.kt:14`, `AospCombined.kt`, import at `CustomDictionaries.kt:806` |
| H13 | Delete-swipe selects whole words (4.3-b1) | ✅ | `BackspaceSwipeUnit.WORD` / `CHARACTER` (`:7108`) |
| H14 | `DELETE_WORD` keycode (4.3-b1) | ✅ | `TextEditAction.DELETE_WORD` (`core/common/.../TextEditLayout.kt`) |
| H15 | Higher recent-emoji limit (4.3-b1) | ✅ | `settings.emoji.recentsLimit`, held to 8–96, default 32 (`EmojiUsage.kt:55`, applied at `WMKeyboardService.kt:5608`) |
| H16 | Fewer unwanted words added to typed history (4.3-b1, `c768bf5e`, `3afa6bb1`) | ✅ | WM's deferred learning and learned-word cleanup cover the same ground; internal tuning on both sides |
| H17 | Emoji search in secondary languages; search button only when an index exists (`main`, `b6b62e83`, `db5e834e`) | ✅ | One multilingual index (`core/emoji/.../EmojiSearch.kt`), plus downloadable keyword packs |
| H18 | Gesture-data review, first instance only (4.3-b1 #2891) | ❌ | Part of A34, skipped |

---

## WM Keyboard has, HeliBoard does not (brief)

- Bundled glide typing with several decoders; HeliBoard still needs a
  user-supplied gesture library.
- AI: cloud providers, on-device LLMs (LiteRT-LM, llama.cpp, NPU paths), AI
  reply, AI chat, tool calling, MCP servers.
- Voice: several on-device engines (Whisper, sherpa-onnx, Parakeet), spoken
  punctuation, dictation tidy-up.
- CJK input engines (Rime, Mozc, pinyin, kana flick, Cheonjiin), Avro and
  other phonetic transliteration, handwriting.
- Grammar checking (Harper, LanguageTool), Harper as system spell checker.
- About 100 toolbar tools (cursor and clipboard keys included): OCR, QR, document scanner, translate,
  calculator, currency, clock, notes, to-do, gallery, files, Home Assistant,
  KDE Connect, password manager (WM Pass) and more, with a second toolbar,
  floating tool boxes and custom buttons.
- Snippets with variables, Espanso import, pattern and glide triggers.
- Python plugins with a host API.
- Theme engine with photo and live backgrounds, materials, per-key styles and
  importers for other keyboards' themes, HeliBoard's included.
- Clipboard hardening, privacy shade, metadata stripping, incognito clipboard.
- Imports layouts from Keyman, Multiling, desktop keymaps and others.

---

## Open gaps ranked by value to users

1. **A23 URLs as one word** (⚠️, M). People type links every day, and the
   strip still offers word completions for URL fragments while word-delete
   stops at every dot. The spacing and autocorrect side is already done, so
   only the token rule is left.
2. **A9 one-tap timestamp** (⚠️, S). Note-takers and log-keepers ask for it.
   The Clock tool's format setting can be reused as is.
3. **H1 text-editing shortcuts** (⚠️, S). The panel is there; a space-swipe
   entry and an opt-in symbols-row key would make it as quick to reach as
   HeliBoard's D-pad.
4. **A53 + H2 dedicated theme colours** (⚠️, S). Matters mostly for HeliBoard
   theme imports, which currently lose these two colours.
5. **A66 application-level back flag** (✅ with caveat, S). Only Android 13–15
   devices notice.
6. **A34 gesture data gathering** (❌, L). Skip.
