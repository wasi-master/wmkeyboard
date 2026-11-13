# HeliBoard → WM Keyboard: Feature-Gap Map (re-checked 2026-07-22)

## Context

WM Keyboard is a ground-up Kotlin/Compose keyboard descended from the OpenBoard → HeliBoard lineage, not a line-for-line fork. This document tracks **every HeliBoard option/feature** (mined from the full changelog, `openboard_new_1.4.5` 2023-06 → `v4.0` 2026-07) and marks what WM Keyboard is still missing.

Since the first pass, a large implementation batch landed (see `git log`: clipboard chip, punctuation suggestions, gesture customization, haptic gates, emoji skin-tone, hardware keyboard, hide-on-lock, foldable scale, one-handed per-orientation, wrap/recapitalize selection, and more). **Of the original 66 gaps, ~47 are now closed.** This file has been rewritten to (a) record what closed, and (b) explain the **remaining 14 absent + 5 partial** in enough detail that each is self-explanatory without cross-referencing HeliBoard.

Legend: ❌ absent · ⚠️ partial/weaker.
WM source paths are under `app/src/main/java/com/wasimaster/wmkeyboard/`.

---

## Scorecard

| Bucket | Count | IDs |
|---|---|---|
| ✅ Closed (now at/above parity) | 47 | A1–A8, A10, A12–A22, A24, A25, A27, A29–A33, A35–A37, A40–A42, A46, A48, A49, A51, A52, A54, A56–A61, A65 |
| ❌ Still absent | 14 | A9, A11, A26, A28, A34, A39, A43, A44, A47, A50, A62, A63, A64, A66 |
| ⚠️ Still partial | 5 | A23, A38, A45, A53, A55 |
| Layout/dict breadth | improving | ~20 → 38 layouts / 34 langs; still trails exotic set |

---

## ❌ Still absent — verbose

### A9 — Timestamp as a toolbar tool
**What it is.** A toolbar button that, when tapped, inserts the current date/time directly into the text field (e.g. `2026-07-22 14:03`). HeliBoard exposes it both as a toolbar key and as a `timestamp` key-label / keycode you can place in any layout, with a configurable format string.
**HeliBoard.** v3.0 (#846) added the timestamp keycode; v3.1 added the `timestamp` key label; later releases added an info note on how to surface the button.
**WM now.** ❌ as a toolbar tool. A `{timestamp}` placeholder exists in the **snippets** system (`core/snippets/SnippetStore.kt:172`) — so it's reachable by expanding a snippet, but there is no one-tap toolbar/keyboard key. `ToolbarTool` enum has no `TIMESTAMP` (`SettingsRepository.kt:158-175`).
**To implement.** Add `TIMESTAMP` to `ToolbarTool` + a format-string setting; on tap, commit `SimpleDateFormat(fmt)` output. Could reuse the snippet's existing timestamp formatting.

### A11 — Auto-show / auto-hide toolbar
**What it is.** A setting that makes the toolbar reveal itself automatically in certain contexts and collapse otherwise — e.g. show the toolbar only right after focusing a field, then auto-collapse to reclaim space; or auto-expand when the suggestion strip has nothing to show. Distinct from the manual chevron toggle and from "fully hide" (A6, already done).
**HeliBoard.** v2.0-beta1 (#674) "add options to auto-show/hide toolbar."
**WM now.** ❌. `ToolbarBehavior` supports `enabled`, `swipeDownHide`, `onlyWithHardwareKeyboard`, `reverseForRtl`, `greedy`, `scrollable`, `hideWhenLocked` (`SettingsRepository.kt:410-450`) — no auto-show/auto-hide policy. The only timed auto-hide in the code is the recently-copied clipboard chip, unrelated.
**To implement.** Add an `autoHide` policy enum (never / after-timeout / when-strip-empty) to `ToolbarBehavior` and drive the TOPBAR visibility from it.

### A26 — Auto-space / phantom-space tuning
**What it is.** "Phantom space" (a.k.a. auto-space) is the invisible pending space the keyboard inserts after you pick a suggestion or type certain punctuation, so the next word is spaced correctly — and which it silently swallows if you then type punctuation. HeliBoard added **settings to tune this behaviour**: whether to auto-insert the space, whether/which punctuation cancels or absorbs it, behaviour around quotes/brackets, etc.
**HeliBoard.** v3.0-alpha3 (#876, #1348) "add some settings for tuning auto-space behavior."
**WM now.** ❌ as tunable. Auto-space in WM is fixed policy: a one-shot `pendingAutoSpace` tied to the double-space ". " conversion (`WMKeyboardService.kt:277, 2311`), hardwired auto-space between glide-typed words (`:3299, :3354`), and a handwriting-only `handwritingAutoSpace` toggle (`SettingsRepository.kt:859`). No user knobs for general phantom-space. (Note: shift-cancels-auto-space, A27, *is* done — but that's one behaviour, not the tuning suite.)
**To implement.** Introduce an auto-space settings group: toggle for phantom space after suggestions, set of punctuation that cancels vs. absorbs it, and quote/bracket handling.

### A28 — Expand shortcuts from user-dictionary entries
**What it is.** The Android personal/user dictionary lets each entry carry an optional **shortcut** string. HeliBoard reads that column and auto-expands/autocorrects the shortcut to the full word as you type (e.g. shortcut `omw` → `on my way`). This is the *system-dictionary-backed* expansion, separate from a standalone snippet feature.
**HeliBoard.** openboard v8 "autocorrect/-expand shortcuts defined in user-dictionary"; v3.0 (#1287) "add setting to autocorrect shortcuts."
**WM now.** ❌. `core/prediction/SystemUserDictionary.kt` writes words with a null shortcut (`addWord(..., null, null)` `:45`) and reads only the `WORD` column (`:62`) — it never reads/uses the `SHORTCUT` column. WM's **Snippets** (`core/snippets/`) provide expansion but are a separate store, not backed by the Android user dictionary, and don't participate in the suggestion/autocorrect path the same way.
**To implement.** Read the `SHORTCUT` column in `SystemUserDictionary`, index shortcut→word, and feed matches into the suggestion/autocorrect engine.

### A34 — Gesture (glide) data gathering
**What it is.** An **opt-in** facility that records your glide-typing strokes (anonymised, with the intended word) to build/improve an open gesture-typing model — HeliBoard has both a "background" passive collector and an "active" contribute-a-word mode, with wiki/video guides. It exists because open glide libraries are scarce and need training data.
**HeliBoard.** v3.7-beta1 (#1641), v4.0-alpha1 (#2397) and follow-ups.
**WM now.** ❌. No stroke logging / corpus / training-dump code anywhere. WM ships its own native SHARK²-style decoder (`core/gesture/GestureDecoder.kt`) that doesn't need user-contributed data.
**Verdict.** Likely an intentional skip — it's a data-collection feature serving HeliBoard's shared model, not a user-facing convenience. Low priority.

### A39 — Space-swipe → toggle numpad
**What it is.** A configurable spacebar swipe gesture that opens/closes the numeric keypad (numpad). HeliBoard lets a space swipe be bound to numpad toggle among other actions.
**HeliBoard.** v2.2 (#950) "add space swipe setting to toggle numpad."
**WM now.** ❌. `SpaceSwipeAction` enum is only `NONE, LANGUAGE, CURSOR` (`SettingsRepository.kt:337`). Numpad is reachable via the toolbar tool and (now) hold-?123 (A36), but not via a space swipe. WM does have short- and long-swipe slots on space (`spaceShortSwipe`, `spaceLongSwipe`), so adding a numpad action is a small enum extension.
**To implement.** Add `NUMPAD` to `SpaceSwipeAction` and handle it in the space-swipe dispatch.

### A43 — Show ALL available popup keys (auto accent variants)
**What it is.** For Latin letters, HeliBoard can automatically populate a key's long-press popup with **every** accent/diacritic variant of that letter (e.g. long-press `a` → à á â ä ã å ā ...), rather than only the variants the layout author explicitly listed. It's a setting so power users get the full set without editing layouts.
**HeliBoard.** openboard v8 "allow showing all available keys in popups (latin script only)."
**WM now.** ❌. Long-press alternates come *only* from the authored `key.longPress` list (`KeyboardScreen.kt:5397`, gated on non-empty). There's no auto-expansion to the full accent set. `KeyPopupSettings` governs the preview balloon, not variant population.
**To implement.** Ship a Latin letter→accents table; when the setting is on, merge the full set into each letter key's long-press list.

### A44 — Dedicated customizable currency keys
**What it is.** A first-class setting to choose which currency symbols appear (and in what order) on the currency key's long-press popup (e.g. put `€ £ ¥` before `$`), independent of editing a whole custom layout.
**HeliBoard.** v2.1 (#353) "allow customizing currency keys."
**WM now.** ❌ as a dedicated setting. Currency long-press is hardcoded (`core/layout/BuiltInLayouts.kt:654`: `Key("$", longPress=["৳","€","£","¥","₹","₿"])`), editable only through the generic custom-layout editor's "Long-press alternates" field — so it's *possible* but not a purpose-built control. WM also has a separate smart-currency *conversion* tool, which is unrelated.
**To implement.** Add a currency-symbols setting (ordered list) and inject it into the currency key's long-press at compile time.

### A47 — Tune shift → caps-lock timing (and long-press path)
**What it is.** HeliBoard reaches caps-lock by **long-pressing** shift and lets you shorten that long-press threshold via a setting. It's about how fast/what gesture flips shift into locked caps.
**HeliBoard.** v2.1 (#924, #923) "reduce long-press time for shift → caps lock."
**WM now.** ❌. WM uses a **double-tap** of shift with a hardwired `SHIFT_DOUBLE_TAP_MS = 350L` (`WMKeyboardService.kt:6927`, used `:1897`) — there's no long-press-shift-to-lock path and no setting to tune the timing.
**To implement.** Expose the double-tap window as a setting, and/or add an optional long-press-shift-to-caps-lock with a configurable threshold.

### A50 — Side-padding scale
**What it is.** A slider that adds symmetric horizontal padding on the left/right edges of the keyboard, shrinking key width toward the center **without** narrowing via alignment — useful on wide screens / for thumb reach. Distinct from `keyboardWidthPercent` (which narrows and can dock) and from split-gap.
**HeliBoard.** v3.0 (#1309) "add side padding scale setting."
**WM now.** ❌. `sidePadFor` (`core/layout/KeyboardLayout.kt:109`) is automatic row-centering slack, not a user control. Nearest knobs are `keyboardWidthPercent` (`SettingsRepository.kt:542`) + `keyboardAlignment`. No `sidePadScale` key.
**To implement.** Add a `sidePadScale` setting (per-orientation like other sizing) and apply it as edge insets in the keyboard layout pass.

### A62 — Broadcast-intent keycodes
**What it is.** Special keycodes/keys that, when pressed, fire an Android **broadcast Intent** — letting power users/automation (Tasker, etc.) trigger external actions from a keyboard key.
**HeliBoard.** v3.3-beta1 (#1675) "add keycodes for broadcasting intents."
**WM now.** ❌. No `sendBroadcast` and no broadcast/intent key action (`KeyActions.kt` has none). The only `BroadcastReceiver` in the app listens for ringer-mode changes (`ime/ui/ToolPanels.kt:1473`) — it receives, doesn't emit.
**Verdict.** Niche/power-user. Low priority.

### A63 — Android-13 per-app system UI language
**What it is.** Integration with Android 13's **per-app language** feature so the user can set WM Keyboard's own **settings-UI language** from the system Settings → App → Language screen (via `localeConfig` + `LocaleManager`). This is about the *app's UI locale*, not the typing language.
**HeliBoard.** v3.9-beta1 (#2373) "allow switching app (settings) language in system settings."
**WM now.** ❌. No `android:localeConfig` in the manifest, no `res/xml/locales_config.xml`, no `LocaleManager`/`applicationLocales` usage. (WM's `PerAppLanguageSettings` is a *different* thing — it remembers the keyboard *layout* per target app; see A22, which is done.)
**To implement.** Add `locales_config.xml`, reference it via `android:localeConfig`, and optionally an in-app language picker calling `AppCompatDelegate.setApplicationLocales`.

### A64 — Backup datestamp in export filename
**What it is.** When exporting settings, name the file with a date/time stamp (e.g. `wmkeyboard-settings-20260722.json`) so successive backups don't collide and are self-labelling.
**HeliBoard.** v3.0 (#1364) "add datestamp to backup filename."
**WM now.** ❌. Settings export uses a fixed name `wmkeyboard-settings.wmsettings.json` (`app/MainActivity.kt:3359`); `SettingsBackup.kt:42` has no date logic. (A timestamp formatter exists but only for camera/screenshot gallery saves.)
**To implement.** Insert a `yyyyMMdd` (or full) stamp into the default export filename. Trivial.

### A66 — Predictive-back animation / preview
**What it is.** Android 13+ **predictive back gesture**: as the user starts a back swipe, the UI shows a live *preview* animation of where back will take them (e.g. the panel sliding away under the finger), using `OnBackAnimationCallback` (`onBackStarted` / `onBackProgressed`). WM currently only *dismisses* on back-commit.
**HeliBoard.** v3.8-beta1 (#2256) "enable predictive back gesture."
**WM now.** ❌ (animation). Only a plain `OnBackInvokedCallback` is registered while a panel is open (`WMKeyboardService.kt:6477-6497`) — it closes the panel on commit but implements no `OnBackAnimationCallback` / progress preview.
**To implement.** Register `OnBackAnimationCallback` (API 34+) and drive the open panel's dismiss transform from `onBackProgressed`.

---

## ⚠️ Still partial — verbose

### A23 — URL/email token detection for suggestions
**What it is.** Treating `.`, `@`, `_`, `:` as *in-word* so a whole URL/email is handled as one token for suggestion/deletion (HeliBoard's experimental "URL detection"), plus email-field completions.
**WM now.** ⚠️ Email side is strong: `emailTokenBeforeCursor` extends the token past letters/digits (`WMKeyboardService.kt:2980`), plus forced email-field suggestions and contact-email completion (`:2971, :2994`). **URL/URI token detection is not implemented** — URI only affects double-space suppression and clipboard handling, not suggestion tokenisation.
**Gap.** Add URL-token detection (treat `.:/_` as in-word) for the suggestion/deletion path.

### A38 — Show number row inside symbols layout (toggle)
**What it is.** An independent toggle for whether the digit row appears while on the symbols layer.
**WM now.** ⚠️ Functionally present — the number row *does* show inside symbols, and the symbols layer's leading digit row is swapped for a fill row (`KeyboardScreen.kt:4619-4620, 4625`). But it's driven by the **single global** `numberRow` toggle; there's no separate "number row in symbols" switch, so you can't have it in symbols but not in letters (or vice-versa).
**Gap.** Split into its own setting if that independence is wanted.

### A45 — Bottom-row / general per-row height
**What it is.** Setting the height of the bottom (space/enter) row — or any row — independently of the global key height.
**WM now.** ⚠️ Only the **number row** has an independent height (`numberRowHeightDp`, per-screen-variant, `SettingsRepository.kt:530`). The bottom row (space/enter) and all other rows follow the global `keyHeightDp` (`:529`). No general per-row height system.
**Gap.** HeliBoard v3.8-beta1 (#1199) added a dedicated bottom-row height. Add `bottomRowHeightDp` (per-variant), optionally a general per-row scale.

### A53 — Emoji-search dedicated color
**What it is.** A distinct customizable color for the emoji search bar in the "all colors" theme editor.
**WM now.** ⚠️ The emoji search bar *is* themeable, but only through the shared **"Cards & search bar"** color (`chipBackground`, `ThemeSpec.kt:98`) plus "Suggestion text" — the same slot as clipboard cards. There's no emoji-search-specific field, so you can't color it independently.
**Gap.** HeliBoard v4.0-beta1 (#2442) "add colors for emoji search to all colors." Add a dedicated `emojiSearchBackground`/`emojiSearchText` to `ThemeSpec` if independent control is wanted.

### A55 — Close-after-insert for clipboard / symbol (not just emoji)
**What it is.** A setting controlling whether the keyboard auto-returns to the main alphabet view after you insert an item from a panel — HeliBoard applies it to emoji **and** clipboard **and** symbol inserts.
**WM now.** ⚠️ `closeAfterInsert` exists (`SettingsRepository.kt:1185`, UI `MainActivity.kt:2931`) but is honored **only for emoji-panel** inserts (`WMKeyboardService.kt:6035` gates on `panel == PanelMode.EMOJI`). Clipboard and symbol inserts ignore it.
**Gap.** HeliBoard v2.2 (#546). Extend the close-after-insert check to the clipboard and symbol insert paths.

---

## Layout & dictionary breadth

**Now ~38 layouts across ~34 languages** (up from ~20). Compiled (`core/layout/BuiltInLayouts.kt`, 15): QWERTY/AZERTY/Dvorak (en), Avro/Probhat/Jatiya (bn), French AZERTY, German QWERTZ, Spanish QWERTY+Ñ, Korean 2-beolsik, Russian ЙЦУКЕН, Arabic, Greek, Hebrew, Hindi. JSON assets (`assets/layouts/*.wmlayout.json`, 23, lazy-loaded): pt, uk, it, nl, pl, sv, sr, bg, ka, cs, sk, ro, hu, fi, da, nb, hr, fa, be, et, lt, lv, sl.

**Still trails HeliBoard's exotic catalog:** Toki Pona, HCESAR, Kaitag, Dargwa (Urakhi), Mansi, Eastern Mari, Chuvash, Piedmontese, Mirandese, Central Kurdish, Kabyle, Gujarati, Kannada (extended), Korean Phonetic, Arabic Hija'i, Hebrew 1452-2, Hindi Phonetic, additional Bengali variants (Unijoy, Baishakhi, Khipro), African-language layouts.
**Mitigation:** WM's in-app JSON layout editor + import means any of these are user-addable; they're just not preinstalled. **Bundled dictionaries** remain English + Bengali only; others require user import.

---

## Suggested next steps (if implementing)

**Quick wins (small, isolated):**
- A64 backup datestamp — trivial filename change.
- A55 close-after-insert for clipboard/symbol — extend one existing gate.
- A39 space-swipe → numpad — one enum value + dispatch.
- A38 / A53 — add the dedicated toggle / color slot.
- A50 side-padding scale — one per-variant slider + inset.

**Medium:**
- A9 timestamp toolbar tool (reuse snippet formatting).
- A44 currency-key customization; A43 auto-accent popups (needs an accent table).
- A28 user-dictionary shortcut expansion; A23 URL-token detection.
- A47 shift→caps-lock timing/long-press; A45 bottom-row height; A11 auto-show/hide toolbar.

**Platform / niche:**
- A63 per-app system UI language (localeConfig); A66 predictive-back animation.
- A62 broadcast-intent keycodes; A34 gesture data gathering — likely skip unless specifically wanted.

## Deliverable note

Research/mapping deliverable — no source files modified. Only this plan file updated. Awaiting direction on which remaining items to implement.
