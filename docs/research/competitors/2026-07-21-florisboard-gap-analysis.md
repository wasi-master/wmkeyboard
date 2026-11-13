# FlorisBoard → WM Keyboard: Feature Gap Analysis

## Context

The user asked for an **exhaustive, false-positive-free list of features FlorisBoard has that WM Keyboard lacks**. Source: `/Users/wasimaster/Downloads/florisboard-releases-combined.txt` (full GitHub release notes, v0.1.0 → v0.6.0-alpha02) cross-checked against the WM Keyboard codebase.

This is a research deliverable — the "plan" is the verified gap list plus how each item was checked. Implementation is out of scope until the user picks items.

**Headline:** WM Keyboard is already broader than FlorisBoard. It matches or exceeds FlorisBoard on glide typing, floating/one-handed/split, clipboard manager, subtypes, theme editor + Material You, inline autofill, emoji `:name`/pin/reorder, haptics+sound, hardware keyboard, per-orientation sizing — plus WM-only extras (AI, grammar, voice, handwriting, OCR, huge tool suite). Notably FlorisBoard 0.4–0.6 has **no** word suggestions/spellcheck/glide (all removed pending rewrite); WM has them, so those are NOT gaps. After filtering, the genuine gaps are below.

## Method
1. Cataloged every user-facing FlorisBoard capability from the whole changelog (subagent).
2. Inventoried WM's actual capabilities two ways: settings/prefs model + IME engine/panels (two subagents).
3. Diffed; dropped every candidate WM already has; grep-verified the uncertain ones to kill false positives.

## Verified gaps (FlorisBoard has, WM lacks)

### Tier 1 — clear, worth-doing
1. **Auto-space after punctuation** — FlorisBoard toggle (#375) inserts a space after `.?!,` etc (skipped in URL/email/password fields). WM has no such setting; only internal auto-space after emoji/GIF/suggestion inserts (`ime/WMKeyboardService.kt:299`), and `handwritingAutoSpace` (handwriting only). → new `typing` toggle in `core/settings/SettingsRepository.kt` + logic in the commit path.
2. **Extensions / Add-ons system + in-app Add-ons store** — FlorisBoard packages layouts, themes, composers, popup mappings and subtype presets as installable extensions, with a browsable store and update checks (v0.4.0 #2473, #2508, #2509). WM only does per-asset JSON import/export (themes, `.wmlayout.json`, symbol sets, dictionaries, settings) — no packaged bundle format and no in-app catalog. (grep: no `addon`/`extension store`/`.flex`.) Large architectural item.
3. **Clipboard sensitive-content handling** — FlorisBoard honors Android 13's sensitive-clip flag and auto-cleans sensitive items (v0.4.2 #2631, v0.4.4 #2659). WM's `ClipboardStore` has only time-expiry + incognito pause; no `isSensitive`/OTP/password redaction (grep: no `sensitive`/`FLAG_SENSITIVE`).
4. **Clipboard phone-number detection** — FlorisBoard detects phone numbers (and emails) in copied text and surfaces them (#1971). WM detects **URLs only** (`ClipboardStore.kt:63`); no phone/email extraction.
5. **Time-based / scheduled theme switching** — FlorisBoard auto-switches themes on a day/night schedule (v0.5.0 #2977). WM only follows the system light/dark flag (`autoTheme`) and Material You; no clock/sunrise scheduling (grep confirmed absent).

### Tier 2 — real but narrower / depth
6. **Interactive on-keyboard resize & move for the docked keyboard** — FlorisBoard v0.6 adds drag move-handle + resize handles/overlay for the fixed keyboard (replaces height/offset prefs). WM's interactive drag-resize is **floating-only** (`ime/ui/KeyboardScreen.kt:1041`); the docked keyboard resizes via sliders + per-orientation overrides (grep: no docked `resizeHandle`/`moveHandle`).
7. **Advanced per-element theme stylesheet editor (Snygg v2)** — FlorisBoard styles every UI element per state, with reusable variables, shadows, borders, shapes (v0.3.14 #1513, v0.5.0 #2855). WM's editor is real but coarser (`ThemeSpec`: board/key colors, gradients, shape, bg image, borders, popup/toolbar colors) — no per-element/per-state stylesheet or variables. Granularity gap, not "no editor."
8. **Clipboard filtering by item type + timestamps in the history UI** — FlorisBoard shows filter chips (text/image) and copy timestamps (v0.5.0 #2938). WM stores `timestamp` (`ClipboardStore.kt:39`) and supports text substring search but no type-filter UI. Partial.
9. **Clipboard history in backup** — FlorisBoard includes clipboard history in its backup (#2458). WM's `SettingsBackup` exports prefs only; clipboard is excluded. (WM does have Android auto-backup + settings JSON — those are NOT gaps.)

### Tier 3 — minor / niche / technical (genuinely absent, low value)
10. **Direct-boot support** — keyboard usable before first unlock (#1021). WM's IME service is not `directBootAware` (manifest confirmed).
11. **On-screen forward-delete gesture / quick action** — FlorisBoard v0.5.0 (#3010). WM only handles the physical `KEYCODE_FORWARD_DEL` key (`WMKeyboardService.kt:6659`); no on-screen forward-delete swipe/action.
12. **In-app debug-log viewer/export screen** — FlorisBoard v0.3.16. WM has no equivalent (grep: none).
13. **Video clips in clipboard history** — FlorisBoard v0.3.15 (#1721). WM `ClipKind` = TEXT/HTML/LINK/IMAGE/FILE/FOLDER — no dedicated video.
14. **Text emoticons / kaomoji section** (`¯\_(ツ)_/¯`) — FlorisBoard emoticons layout. WM has emoji + symbols catalog but no kaomoji set (grep: none).

## False positives explicitly ruled out (WM already has these)
Glide typing (WM ahead — FlorisBoard removed it); word suggestions + autocorrect + next-word + spellcheck (FlorisBoard removed); floating keyboard; one-handed/compact + split; number row **and** hinted digit corner hints; incognito (manual + auto); clipboard pin/search/URL/image/source-app; theme editor + import/export + Material You + system light/dark; inline autofill; `:name` emoji search; emoji history pin+reorder + skin tones; configurable long-press delay + key-repeat; key vibration **and** sound (multiple styles); popup key-preview; per-orientation sizing; subtype (language+layout) system; hardware-keyboard input; **configurable spacebar label** (`spacebarLabel`); **Android auto-backup** (`allowBackup`/`dataExtractionRules`/`fullBackupContent`); **monochrome themed launcher icon**; quick-action editor/overflow (toolbox reorder); backup/restore.

## Verification (how to re-check before building any item)
- Settings truth: `core/settings/SettingsRepository.kt` (`KeyboardSettings`, ~180 fields + nested classes).
- Clipboard: `core/clipboard/ClipboardStore.kt`.
- Themes: `core/theme/ThemeSpec.kt`, `ime/ui/KbTheme.kt`, `app/ThemeScreens.kt`.
- Resize/floating: `ime/ui/KeyboardScreen.kt` (~1030–1114), `ime/WMKeyboardService.kt` (~1005–1053).
- Commit/auto-space path: `ime/WMKeyboardService.kt` (~299, 2333).
- To implement a settings-backed feature, follow the existing field→DataStore-key→setter pattern (mind the 245-field ceiling — nest per memory `settings-class-jvm-ceiling`).

## Note
No implementation is included — this is a comparison list. If the user wants any Tier-1/2 items built, that becomes a separate scoped plan.
