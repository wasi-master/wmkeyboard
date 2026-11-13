# FlorisBoard → WM Keyboard: Feature Gap Analysis (November update)

## Context

This updates `2026-07-21-florisboard-gap-analysis.md`, which covered FlorisBoard v0.1.0 through v0.6.0-alpha02. The July list is re-checked against WM's code, and everything FlorisBoard has added since is compared the same way.

**Compared:**
- FlorisBoard: latest release is still **v0.6.0-alpha02** (2026-01-23). No release, pre-release or tag has been published since. The new work is all on `main`, compared at **`bac346e8a`** (2026-11-02), and will ship as v0.6.0-alpha03.
- WM Keyboard: `main` at **`cd14a50a8`** (2026-11-13, "build: drop the x86_64 ABI everywhere"). Other sessions had uncommitted edits in the working tree at the time; greps ran against that tree, and none of the cited lines are in files those edits touch.

**Headline:** WM has closed all fourteen July gaps; two of them leave a small remainder, noted under items 6 and 8 below. FlorisBoard's ten months since alpha02 went almost entirely into one rewrite. "Keyboard3 integration" (PRs #3340, #3341) replaces its layout engine with Unicode's CLDR keyboard3 (LDML) format, and #3358 moves the settings app to Material 3 Expressive. Most user-visible results of that rewrite (arbitrary layers, layer peeking, multi-tap keys, per-key flicks, configurable numpad) WM already has. What is left is a handful of narrow items, ranked at the end. FlorisBoard still has no word suggestions, spellcheck or glide typing on `main`. The suggestions work sits in open PR #2970, so those remain WM strengths, not gaps.

## Method

1. Listed every release (`gh api repos/florisboard/florisboard/releases`, all pages): none after v0.6.0-alpha02.
2. Listed every PR merged since 2026-01-23 (24 PRs) and every commit on `main` in the same window. Read the bodies of the feature PRs (#3238, #3340, #3341, #3358, #3208, #3303, #3213, #2762, #3270) and the issues #3341 closes.
3. Diffed FlorisBoard's `AppPrefs.kt` and `values/strings.xml` between `v0.6.0-alpha02` and `main` to separate real new options from strings that were only renamed (most of the changed strings are Title Case → Sentence case, from #3320).
4. For each July gap and each new feature, grepped WM's `core/settings`, `feature/ime`, `app`, `core/*` and the bundled layouts. A gap is listed only where the grep came back empty or showed a narrower feature. FEATURES.md was not used as evidence.

## What FlorisBoard added after v0.6.0-alpha02 (all unreleased, on `main`)

| Area | Change | Source |
|---|---|---|
| Layouts | CLDR keyboard3 (LDML) files as the only layout format; FlorisBoard JSON layouts dropped | #3340 |
| Layouts | Any number of layers, with named `base`, `shift`, `numpad`, `telpad`, `numrow` entry points | #3340 |
| Layouts | keyboard3 display overrides, search-and-replace transforms, `@fl:action/…` and `@fl:icon/…` references | #3340 |
| Layouts | Layer peeking: drag off a layer key to type one key from that layer, or hold to type several, then fall back | #3340 |
| Keys | Multi-tap keys, with a multi-tap timeout ("use system" or custom) and a highlight of the pending output | `2a5a08df1`, `27743b8cf`, `0aa296d4c` |
| Keys | Universal **Fn key** beside the spacebar: one short-press action plus a user list of long-press actions | `d8a04b4f2`, `6551175e7` |
| Keys | Long-press timeout gains "use system"; long-press hint toggle with six placements (top/bottom × start/center/end) | `27743b8cf`, `a2b033905` |
| Keys | Flick hints with their own placement | `a2b033905` |
| Keys | Utility key removed; hinted number row / hinted symbols / merged hint popups prefs removed | #3340, #3341 |
| Gestures | New swipe action "Toggle compact layout" (one-handed) | #3238 |
| Feedback | Audio and haptic reduced to "follow system" or off; vibration duration, strength and mode removed | #3340 |
| Emoji | Experimental emoji search (dev-tools toggle) | `9c1792c24` |
| Themes | Style keys by their output, with a recorder that captures the next key press; Material You light/dark colour values; padding/margin property; "window inner" element | `85521f291`, `bf5c96c04`, `004c46857` |
| Settings app | Material 3 Expressive redesign; one Themes screen for select/import/export/edit; extension detail screen; AMOLED dark settings theme | #3358 |
| Setup | New setup flow with "I have a backup" restore and an opt-in crash-report notification | `8b1650c64`, `31a40944f` |
| System | App info page links to the keyboard's settings | #3208 |
| Fixes | Backspace no longer splits emoji; long clipboard text no longer crashes the panel; raw-input detection for editors such as Acode | `8001802d0`, #3303, #3213 |
| Layouts | Bengali `bengali_bd` renamed Unijoy and added for bn-IN; missing JCUKEN Interslavic letter | #2762, #3270 |

PR #3340 also announced "key release sounds/vibration" toggles, but they are not in `main`'s `AppPrefs.kt`, so they are left out.

Open, unmerged PRs worth watching: word suggestions and spellcheck (#2970), Japanese input (#3376, draft), Vietnamese Telex/VNI (#3372, #3236, #3102), a share-selected-text quick action (#3357), an option to reveal sensitive clipboard items (#3375), and new layouts (Nogai/Kazakh #3317, Enthium #3255, Kaitag #3240, Armenian #3287). WM already has suggestions/spellcheck, Mozc Japanese, Telex/VNI (`AssetLayouts.VI_TELEX_ID`), Share in the selection bar (`core/content/.../selection/SelectionMacros.kt:49`), Nogai (`app/src/main/assets/layouts/nog.wmlayout.json`) and Kaitag (`xdq_kaitag.wmlayout.json`). Enthium is the only one WM lacks; it is not merged.

## Verified gaps (FlorisBoard has, WM lacks)

### Tier 1: worth doing

1. **A universal action key ("Fn key")**: FlorisBoard puts one key next to the spacebar on every layout that supports it, with a chosen tap action and a user-built list of long-press actions (Settings → Fn key and actions). WM covers pieces of this but not the whole: the 🌐 seat can hold one tool (`globeTool`, `core/settings/.../SettingsRepository.kt:10406`), any key can carry flick arms set from Settings (`keySwipes`, `SettingsRepository.kt:9143`), and the spacebar hold can offer chosen *characters* (`spaceHoldKeys`, `SettingsRepository.kt:9917`). No key's long-press popup can be filled with *actions* (tools, edits, layer switches) from Settings across all layouts; that needs the layout editor, per layout. Size: S–M. A `List<KeyAlternate>` hold list on the 🌐 or comma key, reusing the `KeyAction` picker the finger-swipe and chord settings already use.
2. **Import of CLDR keyboard3 (LDML) layouts**: FlorisBoard's layouts are now LDML XML (`keyboard3` elements, layers by form, flicks, transforms, display overrides), and #3340 says all non-baseline layouts will come from its add-ons store in that format. WM imports FlorisBoard/HeliBoard JSON and text layouts (`core/language/.../layout/ForeignLayout.kt`), Windows `.klc`, macOS `.keylayout` and XKB (`DesktopLayout.kt`), Keyman and Multiling, but not LDML. A grep for `ldml`, `keyboard3`, `tr35` or `cldr.*keyboard` across `app`, `core`, `feature` and `tools` finds nothing. Because FlorisBoard dropped its JSON format, WM's FlorisBoard importer will only read old files from now on. Size: M. A parser for the keyboard3 schema into `LayoutSpec` (keys, layers, flicks, `longPressKeyIds`), transforms mapped onto the existing composer/table-import machinery.

### Tier 2: real but narrow

3. **Adjustable multi-tap timeout**: FlorisBoard has "Multi tap timeout" with a "use system" default, plus a highlight of the pending character. WM's multi-tap window is fixed at `MultitapCycle.TIMEOUT_MS = 1_000L` (`core/input/.../MultitapCycle.kt:90`), and the service builds it with the default (`feature/ime/.../WMKeyboardService.kt:10526`); no setting reads it (grep `multitap.*timeout` in `core/settings`: none). That matters for Cheonjiin, T9-style keypads and Samsung's punctuation key. The highlight half is not claimed: whether WM's composing underline already shows the pending step was not checked on a device. Size: S.
4. **Email recognition on clipboard items**: FlorisBoard tags a copied email address with an icon and description in the clipboard panel. WM's clip entities are `OTP`, `PHONE`, `URL`, `PLACE` (`core/content/.../clipboard/ClipEntities.kt:14`); the selection bar knows `EMAIL` (`SelectionMacros.kt:150`), but the clipboard panel and its hold actions (`ClipHoldAction`, `SettingsRepository.kt:8129`) have no email kind or "compose email" action. This is the remainder of July gap 4. Size: XS–S.

### Tier 3: minor

5. **Choice of corner for the long-press hint**: FlorisBoard offers six placements. WM always anchors the hint top-end and lets the user nudge it with X/Y offset sliders (`Modifier.hintPlacement`, `feature/ime/.../ui/KeyboardScreen.kt:29809`; `hintOffsetDp` / `hintOffsetXDp`, `SettingsRepository.kt:9961`, `:9967`). No bottom or start placement. Size: S.
6. **Snygg-style rule sheets (remainder of July gap 7)**: FlorisBoard's theme editor writes arbitrary per-element, per-state rules with selectors, and now selects keys by output with a key-press recorder. WM has closed most of this: it imports Snygg v2 sheets including `output=` selectors (`core/theme/.../SnyggMapper.kt:444`), has per-key overrides for face, label, border, popup, hint, texture and effect (`KeyOverride`, `core/theme/.../ThemeSpec.kt:759`), and picks a key by tapping the pinned preview (`PreviewPicker`, `feature/ime/.../app/ThemeKeyboardPreview.kt:480`). What remains is a user-authored rule sheet with variables. Size: L, and low value given the editor WM already has.
7. **Long-press delay that follows the system**: FlorisBoard's long-press timeout defaults to Android's accessibility value. WM's `longPressDelayMs` is a fixed 300 ms slider (`SettingsRepository.kt:4061`); `ViewConfiguration.getLongPressTimeout()` is read only by the floating button (`feature/ime/.../floating/FloatingButton.kt:1206`). Users who raised the system "touch and hold delay" for motor reasons don't get it in WM. Size: XS.
8. **"Settings" link on the system App info page**: FlorisBoard registers for `android.intent.action.APPLICATION_PREFERENCES` (#3208), so App info shows a gear that opens its settings. No WM manifest or source file mentions `APPLICATION_PREFERENCES`. Size: XS, a manifest intent filter on the settings activity.

## Closed since July (WM has these now)

| July gap | Where WM has it |
|---|---|
| 1. Auto-space after punctuation | `autoText.spaceAfterPunctuation` + editable marks, `core/settings/.../SettingsRepository.kt:3124`, `:3132`; UI `app/.../TypingSettingsScreens.kt:805` |
| 2. Extensions / add-ons store | `feature/addons` (repo manifests, downloads), update detection `AddonStatus.UpdateAvailable` (`AddonDownloadManager.kt:63`), store UI `app/.../AddonScreens.kt:365` |
| 3. Clipboard sensitive-content handling | Reads `EXTRA_IS_SENSITIVE` (`feature/ime/.../ClipboardWriteGate.kt:41`, `:107`); dots, short timer, kept out of backups (`strings_clipboard.xml:198`) |
| 4. Clipboard phone-number detection | `ClipEntityKind.PHONE` (`core/content/.../clipboard/ClipEntities.kt:16`); email part still open, see gap 4 |
| 5. Scheduled theme switching | `AutoThemeTrigger` with clock schedule and sunrise/sunset, `SettingsRepository.kt:242`–`:341` |
| 6. Docked resize and move | `ResizeOverlay.kt` resizes the docked frame (`:293`, `:461`, handles `:746`) |
| 7. Per-element theme styling | Mostly closed; see gap 6 for what remains |
| 8. Clipboard type filters and timestamps | `ClipFilter` TEXT/LINKS/IMAGES/FILES/DUPLICATES (`feature/ime/.../ui/ClipboardFields.kt:288`); relative copy time (`ClipboardFields.kt:3250`) |
| 9. Clipboard history in backup | `ConfigBackup.Section.CLIPBOARD` (`app/.../BackupScreens.kt:724`) |
| 10. Direct boot | `android:directBootAware="true"` on the IME service (`app/src/main/AndroidManifest.xml:1652`); `core/common/.../directboot/DirectBoot.kt` |
| 11. On-screen forward delete | `forwardDeleteSwipe`, on by default (`SettingsRepository.kt:7446`); `KeyAction.ForwardDelete` for custom keys (`core/language/.../layout/KeyActions.kt:67`) |
| 12. Debug log viewer | Diagnostics screen `app/.../DebugLogScreen.kt:78` (copy, share, system log) |
| 13. Video clips in clipboard | `ClipKind.VIDEO` (`core/content/.../clipboard/ClipboardStore.kt:20`) |
| 14. Kaomoji | `core/emoji/.../KaomojiLibrary.kt:11`, plus Emoticon archive import |

## New FlorisBoard features WM already has (false positives ruled out)

- **Arbitrary layers and named layer entry points**: `LayoutSpec.layers` (`core/language/.../layout/LayoutSpec.kt:297`); an omitted layer inherits the shipped one.
- **Layer peeking**: drag off a layer key to type from it (issue #108), `detectLayerPeek` / `LayerPeekPopup` (`feature/ime/.../ui/KeyboardScreen.kt:17812`), also on panel grids (`PanelLayoutGrid.kt:95`).
- **Multi-tap keys**: `Key.multitap` (`core/language/.../layout/KeyboardLayout.kt:374`) with `MultitapCycle`; only the timeout setting is missing (gap 3).
- **Flick arms and flick hints**: per-key arms from Settings (`keySwipes`), `flickHints` toggle (`SettingsRepository.kt:9839`).
- **Configurable numpad**: user-editable `PanelKind.NUMPAD` (`core/language/.../layout/PanelLayoutSpec.kt`), calculator layout option (`numpadCalculatorLayout`, `SettingsRepository.kt:4298`).
- **Custom key widths in layouts**: `Key.width` (`KeyboardLayout.kt:89`).
- **Gesture to toggle the compact/one-handed layout**: multi-finger swipes bind any `KeyAction`, including `KeyAction.Tool(ToolbarTool.ONE_HANDED)` (`core/language/.../layout/FingerSwipes.kt:49`, `core/common/.../settings/ToolbarTool.kt:41`).
- **Emoji search**: `core/emoji/.../EmojiSearch.kt:21` and the emoji-panel search field (`feature/ime/.../ui/EmojiFields.kt:373`); FlorisBoard's is still a dev-tools experiment.
- **Theme key picking and per-key styles**: see gap 6.
- **Restore from a backup during setup**: `app/.../Onboarding.kt:112` (restore from the welcome page), `OnboardingGraph.kt:61`.
- **Extension update checks**: `AddonStatus.UpdateAvailable`.
- **AMOLED settings theme**: `ThemeMode.AMOLED` (`app/.../MainActivity.kt:742`).
- **Backspace keeps emoji whole**: grapheme-aware backspace in `core/common/.../text/Graphemes.kt:42`.
- **Unijoy Bengali**: `app/src/main/assets/layouts/bn_unijoy.wmlayout.json`.
- **Predictive back**: `android:enableOnBackInvokedCallback="true"` (`AndroidManifest.xml:484`).
- Not counted as gaps: FlorisBoard's removals (utility key, vibration strength/duration, hinted symbols) and its Material 3 Expressive restyle, which is a look rather than a feature.

## Ranked by value to users

1. **Action key with a user-built long-press list** (gap 1, S–M). Every user can use it, and it reuses WM's existing `KeyAction` picker.
2. **CLDR keyboard3 / LDML layout import** (gap 2, M). Keeps WM able to read FlorisBoard's layouts, and later its store's layouts, now that the JSON format is gone; also opens CLDR's own keyboards.
3. **Adjustable multi-tap timeout** (gap 3, S). Small, and it matters to Cheonjiin, T9 and Samsung-punctuation users.
4. **Long-press delay follows the system** (gap 7, XS). Cheap accessibility win.
5. **Email recognition on clipboard items** (gap 4, XS–S).
6. **Hint corner choice** (gap 5, S).
7. **App info → Settings link** (gap 8, XS).
8. **User-authored theme rule sheets** (gap 6, L). Lowest value for the size.

## Verification (how to re-check before building any item)

- FlorisBoard prefs truth: `app/src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt` on `main`; diff against tag `v0.6.0-alpha02`.
- WM settings: `core/settings/.../SettingsRepository.kt` (`KeyboardSettings` and nested classes; mind the field ceiling, memory `settings-class-jvm-ceiling`).
- Keys and actions: `core/language/.../layout/KeyActions.kt`, `KeyboardLayout.kt`, `FingerSwipes.kt`.
- Multi-tap: `core/input/.../MultitapCycle.kt`, `WMKeyboardService.onMultitapKey`.
- Clipboard: `core/content/.../clipboard/` (store, entities, sensitivity), `feature/ime/.../ui/ClipboardFields.kt`.
- Layout import: `core/language/.../layout/ForeignLayout.kt`, `DesktopLayout.kt`, `DesktopKeymaps.kt`.
- FlorisBoard is Apache-2.0: describe features, write WM's own code; no copying.
