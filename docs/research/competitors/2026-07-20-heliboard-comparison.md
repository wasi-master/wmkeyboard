# WM Keyboard vs HeliBoard — feature comparison

Snapshot taken 2026-07-20 against HeliBoard v4.0 (all 68 releases, from the
OpenBoard fork through 4.0) and WM Keyboard at commit `be2738d`.

**This file goes stale fast.** Both projects move weekly. Treat every row as
"true at the commit named above" and re-verify before acting on it — the
prompt at the bottom re-runs the whole comparison.

## How to re-run it (and how to not get it wrong)

The first attempt at this comparison had **five false negatives**. The cause
is worth recording, because it will happen again to anyone who repeats it:
the "what does WM Keyboard have" half was delegated to a subagent that wrote
a gap-flavored survey, and that survey was believed without checking. It
under-reported spacebar trackpad, customizable toolbar, predictive back,
number row and TLD keys — all of which were present.

Rules that follow from that:

1. **Never report a feature as missing from a summary.** Grep for it and read
   the hit. A summary is a lead, not evidence.
2. **Quote your globs.** In zsh, `grep --include=*.kt` fails with
   `no matches found` and prints *nothing*, which reads exactly like "zero
   matches" — that alone produced several of the false negatives. Use
   `--include="*.kt"`.
3. **Guess vocabulary wrong and you will invent gaps.** "long-press haptics"
   is `hapticOnLongPress`; "skin tone" is `preferredVariant`. If a grep
   returns 0, try the codebase's own naming before concluding anything.
4. **Filter false positives.** `.com` matches 1473 times because of package
   names; `modifierKey` matches theme fields (`modifierKeyBackground`), not
   on-screen modifier keys.

HeliBoard's changelogs are the source for its side:

```
gh api repos/Helium314/HeliBoard/releases --paginate \
  -q '.[] | "## " + .tag_name + " (" + .published_at + ")\n" + .body + "\n---"'
```

## HeliBoard has, WM Keyboard does not

Verified absent by grep at `be2738d`:

| Feature | Notes |
|---|---|
| Language breadth | 7 layouts (QWERTY, AZERTY, QWERTZ, Spanish, Dvorak, Probhat, Jatiya) vs 100+. No Cyrillic, Arabic, Hebrew, Korean, Devanagari, Greek |
| Multilingual typing | No simultaneous two-language suggestions |
| Custom layout editor | Layouts are hardcoded Kotlin; no JSON layouts, no in-app editor |
| On-screen modifier keys | Ctrl exists only internally for undo/redo; no ctrl/alt/meta/fn keys |
| Settings search | No preference search |
| Background gesture data gathering | Opt-in corpus collection to improve glide typing |

This is the real gap and it is concentrated in one place: **language and
layout breadth, plus the layout-customization system.** Feature-per-language,
WM Keyboard is ahead; language count, it is 7 to ~100.

## WM Keyboard has, HeliBoard does not

AI tools (multi-provider: Anthropic/OpenAI/Gemini/Ollama/LM Studio),
on-device LLM (LiteRT-LM), Harper grammar checking **and** Harper as a system
spell checker, OCR / QR / document scanning, handwriting recognition, its own
voice engine (continuous dictation, spoken punctuation), snippets with
variable expansion, ~30 utility tools (calculator, currency, unit convert,
weather, prayer times/qibla, moon phase, Wikipedia, web/image search,
GIF/sticker send, password generator), an animated/gradient theme engine with
background images and color-vision filters, Bengali Avro transliteration,
per-language fonts, and **bundled glide typing** — HeliBoard still requires a
user-supplied external library for that.

## Present in both

Clipboard history (with images/files), split keyboard, floating keyboard,
one-handed mode, incognito, settings backup/restore, number row with its own
height, customizable toolbar with drag reorder, spacebar trackpad cursor
slide, predictive back, TLD/`.com` keys (field-adaptive, `SymbolSets.kt`),
long-press haptics, emoji skin tones (WM stores a *per-emoji* preferred
variant, finer-grained than HeliBoard's single global default), emoji
descriptions on long-press, delete-key word swipe, customizable spacebar
label, caps-lock indicator, autocorrect confidence tuning, per-screen sizing.

## Recently closed gaps

Ported from HeliBoard in `04891b0`: app-name suggestions, inline emoji search
(`:` trigger), autocorrect confidence slider, undo-autocorrect toggle, cursor
/ page / home / end toolbar tools, per-screen (portrait/landscape ×
folded/unfolded) sizing. Dead-key accents were added in the same commit.

Then `a4af6f8` (Harper as system spell checker), `3c2b9eb` (inline autofill /
password manager chips), and `be2738d` (emoji descriptions, whole-emoji
backspace, word swipe).

## Prompt for re-running this

> Compare WM Keyboard against HeliBoard and update `docs/heliboard-comparison.md`.
>
> Read that file first — especially the "how to not get it wrong" section, which
> records the false negatives a previous attempt made and why.
>
> For HeliBoard's side, pull all release notes with the `gh api` command in that
> file and read every release, not just recent ones.
>
> For WM Keyboard's side, **verify each feature by grepping this codebase and
> reading the hits.** Do not report a feature as missing on the strength of a
> summary or a subagent's survey — that is exactly what produced the earlier
> errors. Quote your globs (`--include="*.kt"`), and when a grep returns zero,
> try the codebase's own naming before concluding the feature is absent.
>
> Produce three lists: what HeliBoard has that we lack, what we have that it
> lacks, and what both have. Note the commit you compared at, and rewrite the
> file rather than appending.
