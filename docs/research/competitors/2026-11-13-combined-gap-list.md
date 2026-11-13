# Combined competitor gap list (2026-11-13)

One ranked list built from the five reports written today:

| Keyboard | Report | Compared at | Open gaps |
|---|---|---|---|
| HeliBoard | `2026-11-13-heliboard-gap-map.md` | v4.3-beta1 + `main` 3e91b4f1 | 1 ❌, 4 ⚠️ |
| FlorisBoard | `2026-11-13-florisboard-gap-analysis.md` | unreleased `main` bac346e8a | 8 |
| LeanType | `2026-11-13-leantype-gap-analysis.md` | v4.3.2 | 7 ❌, 12 ⚠️ |
| FUTO Keyboard | `2026-11-13-futo-gap-analysis.md` | v0.1.30 + `master` 7d61ab41 | 4 ❌, 8 ⚠️ |
| AnySoftKeyboard | `2026-11-13-anysoftkeyboard-gap-analysis.md` | 1.13-r2 + `main` 1ee43e7f0 | 7 ❌, 10 ⚠️ |

The five reports hold 63 open entries between them. Several keyboards ask for
the same thing, and a few small items share a line, so they come to 54
numbered items below. Source IDs (A9, H1, L2, U7,
K1, P6, F1…) point back to the per-keyboard report, where the evidence and
`file:line` references live; FlorisBoard's items are numbered there 1–8 and
appear here as F1–F8. Read the source entry before building anything.

A few top items were re-grepped while merging (the Passwords mode has no
`numberRow`, `MultitapCycle.TIMEOUT_MS` is a constant, `SpaceSwipeAction` has
no text-edit entry, no paste range is tracked, key long-press ignores the
system timeout). The rest rely on the per-keyboard checks.

Size: XS under an hour · S a day or less · M a few days · L a week or more.

## Asked for by more than one keyboard

These came up independently in two or three reports, which is the best signal
here that users notice them.

| Item | Sources | Size |
|---|---|---|
| Number row on in password fields by default | FUTO U7, ASK P6 | XS |
| Spacebar swipes take any action, including the text-editing panel | HeliBoard H1, ASK P1, ASK P2 | S |
| Adjustable multi-tap timeout | FlorisBoard F3, ASK P9 | XS–S |
| Pick the corner for the long-press hint | FlorisBoard F5, ASK P7 | S |
| A key whose long-press holds user-chosen actions (Fn key, Enter hold menu) | FlorisBoard F1, FUTO U9 | S–M |
| A whole-board gesture map when glide is off | ASK P1, LeanType L18 | M |

## Ranked list

### Tier 1: cheap and felt by most users

1. **Number row in password fields** (FUTO U7, ASK P6) — XS. Seed
   `numberRow = true` on the built-in `mode_password` in `KeyboardModes.kt`.
   Every password typed benefits.
2. **Backspace removes a whole paste** (FUTO U1) — S. WM already undoes a
   correction, a glide word and a voice utterance with one backspace; a paste
   or clip insert is the missing case. Track the committed range, honour it if
   the caret has not moved, behind a setting.
3. **Spacebar swipes take any action** (HeliBoard H1, ASK P1/P2) — S. Let the
   short, long and up swipes take a `KeyAlternate` the way finger swipes do.
   This covers HeliBoard's D-pad shortcut and ASK's utility keyboard in one
   change. Add an opt-in text-editing key on the symbols layer as well (off by
   default, per HeliBoard #2778).
4. **Multi-tap timeout setting** (FlorisBoard F3, ASK P9) — XS–S. The 1,000 ms
   window is fixed; a slider with a "follow system" default helps Cheonjiin,
   T9 and Samsung-punctuation users.
5. **Key long-press delay follows the system** (FlorisBoard F7) — XS. Users who
   raised Android's touch-and-hold delay for motor reasons don't get it on keys.
6. **One-tap timestamp** (HeliBoard A9) — S. A toolbar tool or `KeyAction`
   that commits the Clock tool's chosen `insertFormat`.
7. **Quick period key preset** (FUTO U8) — S. One switch that writes `! , . ?`
   swipe arms onto the period key.
8. **Clipboard modifiers in snippets** (LeanType L2) — S. `{clip:clean}`,
   `{clip:upper}`, slug, snake and so on, reusing the selection-macro
   transforms.
9. **Per-app Enter override** (LeanType L9) — S. Widen the Mode's
   `enterNewline` boolean to an action (Send, Search, Go, Newline…).
10. **Regex blocked words, with their own import and export** (LeanType L8) —
    S. Same compile guards the pattern snippets already use.

### Tier 2: larger, still high value

11. **URLs as one word** (HeliBoard A23) — M. Spacing and autocorrect already
    know about addresses; the suggestion lookup and word-delete still split at
    each dot.
12. **Image snippets** (LeanType L1) — M. A snippet that carries a picture,
    inserted through the clipboard's existing `commitContent` path.
13. **Fill-in prompt when a snippet expands** (LeanType L3) — M. Also lets
    Espanso forms import without being flattened.
14. **Action key with a user-built long-press list** (FlorisBoard F1, FUTO U9)
    — S–M. A hold list of actions on the 🌐, comma or Enter key, set from
    Settings rather than the layout editor.
15. **Bottom-row presets across all layouts** (ASK P3) — S–M. iPhone-style,
    minimal and similar rows without editing each layout.
16. **Delete swipe by wrapped lines** (LeanType L15) — S–M. Use
    `requestTextBoundsInfo` on Android 14+ so a long paragraph selects line by
    line as drawn.
17. **Japanese toggle input (keitai)** (FUTO U5) — S. Add the vowel multitap
    rings to `ja_flick` and a "flick only" switch.
18. **Pinch and stretch to split and merge** (ASK K1) — S–M. Gate it so it
    never fights two-thumb glide.
19. **CLDR keyboard3 (LDML) layout import** (FlorisBoard F2) — M. FlorisBoard
    dropped its JSON format, so WM's FlorisBoard importer only reads old files
    from now on; LDML also opens CLDR's own keyboards.
20. **MS-IME and ATOK dictionary import** (FUTO U10) — S. UTF-16 and
    Shift-JIS sniffing on word-list import. Untested with a real export, so
    confirm the gap first.

### Tier 3: small knobs and narrow audiences

21. **Hint corner choice** (FlorisBoard F5, ASK P7) — S.
22. **Night mode mutes sound and vibration** (ASK P4) — S. A dark-mode
    trigger for Keyboard Modes covers it.
23. **Extra snippet variables** (LeanType L10) — S. Greeting, bullet and
    numbered-list generators, battery, keyboard language.
24. **Emoji default gender, random tone** (ASK K2) — S.
25. **Keep images out of clipboard history** (FUTO U11) — XS.
26. **Email recognition on clipboard items** (FlorisBoard F4) — XS–S.
27. **Power saving: dark theme swap, suggestions off** (ASK P5) — S.
28. **Dedicated theme colours for emoji search and the clipboard chip**
    (HeliBoard A53 + H2) — S. HeliBoard theme imports lose these today.
29. **Corner radius per key class** (LeanType L13) — S–M. A shape field on the
    per-key override would cover it.
30. **Key preview bubble width and corners** (LeanType L14) — S.
31. **Key-label letter-case override** (ASK K3) — S.
32. **Personal dictionary `.txt` export** (LeanType L12) — S.
33. **Edit all snippets as one text document** (LeanType L11) — M.
34. **Keep `%` and `_` in the word per app, for Tasker** (LeanType L5) — S–M.
35. **Per-key pictures from FUTO advanced themes** (FUTO U6) — M–L. Nine-patch
    key backgrounds and icon swaps; skip the shader.
36. **Symbol palette topics: stars, weather, music, technical** (LeanType L16)
    — S, data only.
37. **Missing layouts**: Bribri, Ergo-L, Rulemak, AdNW/KOY (ASK K7), Tamil V4
    once identified (LeanType L19) — S each. ASK layouts import today.
38. **Klipy memes category** (LeanType, settled 2026-10-31) — S. Endpoints
    only, no GPL code.
39. **Alt+Space and Shift+Space language switch** (ASK P8) — XS.
40. **Autocorrect underline toggle** (LeanType L17) — XS.
41. **"Settings" link on the App info page** (FlorisBoard F8) — XS. An
    `APPLICATION_PREFERENCES` intent filter.
42. **Application-level predictive back flag** (HeliBoard A66) — XS. Only
    Android 13–15 notices.
43. **"ie" → "i.e.", "eg" → "e.g."** (FUTO U4) — XS.

### Tier 4: low value or skip

44. **Whole-board one-finger gesture map** (ASK P1, LeanType L18) — M. Only
    makes sense with glide off; multi-finger swipes already cover the need.
45. **Pop text from keys** (ASK K4) — S–M. Cosmetic.
46. **Next-word aggressiveness** (ASK P10) — S.
47. **Language-model file import and strength slider** (FUTO U12) — S.
48. **Voice: suppress symbols** (FUTO U2) — S.
49. **Debug info action** (FUTO U3) — S. Helps triage, not typing.
50. **Portrait full-screen editor** (ASK K6) — XS.
51. **CJK homophone search** (ASK K5) — M.
52. **Hexagonal layouts** (LeanType L4) — L.
53. **User-authored theme rule sheets** (FlorisBoard F6) — L.
54. **Physical layout remap, restart button, glide data gathering**
    (LeanType L6, L7; HeliBoard A34) — skip. Android covers the first, WM
    reloads settings live, and the third serves another project's model.

## Clusters worth doing as one change

- **Spacebar and hold actions**: items 3, 14 and 44 share the `KeyAlternate`
  picker. Building 3 first makes 14 mostly UI.
- **Snippets**: items 8, 12, 13, 23 and 33 all live in `SnippetStore` and the
  snippet screens.
- **Keyboard Modes**: items 1, 9, 22 and 34 are each one Mode field or trigger.
- **Key timing**: items 4 and 5 are both "use the system value unless set".
- **Theme roles**: items 28, 29, 30 and 31 touch `ThemeSpec` and the per-key
  override.

## Not gaps

Each report also lists what WM has that the other keyboard lacks. The common
thread across all five is built-in glide in every language, on-device voice,
AI and translation, the tools layer, Keyboard Modes, and far wider language
and layout coverage (3,685 layout files). FlorisBoard still has no suggestions
or glide on `main`, and FUTO's main advantage is a build with no Internet
permission, which WM matches with its offline flavour.
