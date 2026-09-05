# ADR-0001: Settings search ranking

**Status:** Accepted
**Date:** 2026-09-06
**Deciders:** Wasi Master

## Context

Settings search (`app/.../SettingsSearch.kt`, `SettingsSearchMatch.kt`,
`SettingsSearchScreen.kt`) indexes about 900 rows and ranks them with a
hand-tuned scorer: per-word match tiers (exact, prefix, stem, substring,
fuzzy) weighted by field (title, keywords, breadcrumb, subtitle), scaled by
one of three entry weights (screen 200 %, row 100 %, mirror 40 %), ties
broken by title length.

Two complaints, both real when checked against the index:

1. **Irrelevant results.** Any subtitle that contains the word is a result on
   equal footing with rows named after it. "space" returns every row whose
   subtitle says "when you press space". Three-letter words match inside
   longer words: "row" finds "arrow", "narrow", "browser".
2. **Wrong order among equal matches.** Two rows match a word equally well
   and one is the setting people change weekly while the other is a detail
   of it. "vibrate" ranked "Vibrate on space bar", "Vibrate on delete
   swipe" and "Vibrate on repeat" above "Haptics", because the first three
   start with the typed word and the fourth is only reached through a
   synonym at a 45 % discount. Nothing in the scorer knows which row is the
   feature. The only tie-breaker is title length.

Structural problems found on the way:

- Screens got their 200 % on *every* field. A screen whose keyword list
  merely includes the word outranked the row named after it ("vibration"
  → Key press screen, via keywords, over Haptics).
- The sub-pages a hub is split into (Corrections, Suggestions, Gestures,
  Haptics & sound, Key popup, …) were not destinations in the index. A
  search for "corrections" returned the twenty rows on that page through
  their breadcrumb and never the page.
- Six rows were listed twice. The results list keys on route + title
  resource, so a query that surfaced a duplicated row (for example
  "language detection") would have crashed the LazyColumn with a duplicate
  key.
- The index needed `android.content.res.Resources`, so the ranking could
  only be tested on a ten-row fixture. Nothing checked the real index.

Constraints: no telemetry (privacy is a product promise), no new
dependencies, translations must keep working (the index is built from the
same string resources the screens draw), and `KeyboardSettings` is at its
JVM field ceiling so nothing can be added there.

## Decision

Keep the hand-tuned lexical scorer and add three signals on top, each capped
so the words still decide, plus the test harness that makes the ranking
measurable against the real index.

1. **Entry kind as a title bonus, not a multiplier.** `EntryWeight` becomes
   `SECTION(+35)`, `PRIMARY(+32)`, `NORMAL(0)`, `TOOL(×0.9)`, `DETAIL(×0.8)`, `MIRROR(×0.4)`. The bonus
   applies only when a query word landed on the *title*; for sections only
   when it landed there as typed, for primary rows also through a synonym.
   `PRIMARY` is a new, hand-labelled tier for the switch that is the feature:
   24 rows today (Autocorrect, Key press haptics, Key sound, Key popup,
   Number row, Key height, One-handed mode, Glide typing, Clipboard history,
   Incognito mode, …). `TOOL` is a tool's own page; `DETAIL` is a row on a
   tool's page or a permission, named after a feature it only touches (the
   camera's "Haptics" switch, the "Vibration" permission).
2. **Personal history.** Every result opened from search is recorded
   (`SearchPicks`, its own `SharedPreferences` file, on device only, with a
   Clear button). A pick lifts its row by up to 8 points in any later search
   and by up to 30 when the new query starts the way the old one did, fading
   with a 60-day half-life. 30 is under the gap between a title that starts
   with the word (80) and a title that is the word (100): history reorders
   near-misses and never displaces an exact hit.
3. **Hits and mentions.** Results a query reaches only through a subtitle
   or breadcrumb are listed after the rest under their own heading
   ("Mentioned in the description"). Nothing is hidden; the top of the list
   is the rows the query names.

Supporting changes: a query of several words that a title contains whole
("clipboard history", "swipe typing" through the synonym) is ordered before
everything else that matched; substring matches need four letters, in the
text and in the text with its spaces removed; the query as a phrase inside a
title earns +15 (+9 in keywords); keywords weigh 85 % instead of 60 % (a
screen's own search word beats a row that merely contains a synonym); the
synonym discount is loosened from 45 % to 75 % (safe now that the section
bonus needs the word as typed); ties break towards the kind of entry a search
for settings means, then the deeper screen, then the shorter title; the
fourteen sub-pages are indexed as destinations; duplicates are removed at
source and guarded by `distinctBy(key)`; matched words are bold in result
titles; recent picks are offered under the empty search field.

The index reads through a `SearchStrings` interface (`Resources` in the app,
a parse of `strings*.xml` plus reflection over the `R` classes in tests), so
`SettingsSearchRankingTest` ranks the *real* index against 60 golden queries
and dumps the top results for every query to `build/reports/`.

## Options considered

### Option A: Tune the existing scorer only

Adjust weights, restrict substring matches, fix duplicates, add sub-page
sections. No new state, no new tests against the real index.

| Dimension | Assessment |
|-----------|------------|
| Complexity | Low |
| Cost | A day |
| Scalability | Same as today |
| Team familiarity | High |

**Pros:** small diff; no persistence.
**Cons:** cannot solve complaint 2. Two rows with the same match quality
still tie, and the words carry no information about which one is wanted.
Every tuning change stays unverifiable beyond a ten-row fixture.

### Option B: Lexical scorer + static priority + personal history + real-index tests (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium |
| Cost | Two days |
| Scalability | O(rows × words) per keystroke, ~900 rows: fine on the main thread |
| Team familiarity | High: same shape as the existing scorer |

**Pros:** attacks both complaints; the priority tier is one word at the
index site; history is the only signal that can know what *this* person
means by "sound"; golden tests turn "search feels bad" into a failing case.
**Cons:** 24 hand-labelled rows to keep honest as screens change; a
SharedPreferences file to consider in privacy review (it holds resource
names and typed queries, never keyboard input, and has a Clear button).

### Option C: An information-retrieval engine (BM25 / Lucene-style inverted index) with learning-to-rank

| Dimension | Assessment |
|-----------|------------|
| Complexity | High |
| Cost | Weeks, plus a dependency or a home-grown engine |
| Scalability | Overkill: 900 documents of five words each |
| Team familiarity | Low |

**Pros:** principled term weighting (IDF), well-understood.
**Cons:** IDF buys little here because multi-word queries are already
AND-first; document-length normalisation is already the title-length
tie-break; learning-to-rank needs training data the app deliberately does
not collect. The engine would still need the hand-tuned parts (synonyms,
fuzzy matching, entry kinds) layered on top.

## Trade-off analysis

- **Static priority vs. learned only.** History is empty on a fresh install
  and the first search has to be right. The priority tier covers the first
  week; history covers the person. Both are capped so neither beats an
  exact title.
- **Bonus vs. multiplier for screens.** A multiplier on every field let a
  screen's keyword list outrank a row's own name. An additive bonus on
  title hits keeps "the word as typed on a name" the strongest signal
  everywhere.
- **Hiding low-relevance results vs. grouping them.** A score cutoff drops
  legitimate results ("space" → "Auto space after punctuation" scores well
  above the cutoff, but a subtitle-only hit on a row someone needed does
  not). Grouping keeps recall and gives precision at the top.
- **IDF.** Considered and rejected: with AND-first matching the common word
  rarely decides, and the spelled-out rule fixes the case it would have
  ("key sound" → the row, not the Key press screen).
- **Hand labels vs. a heuristic on resource names.** Marking tuning rows by
  their resource name (`_delay`, `_offset`, `_duration`) would label 150 rows
  for free and invisibly. One explicit word at the index site is what the
  rest of the index does, and it is what a reviewer can argue with.

## Consequences

- Easier: a wrong ranking is now a one-line golden case that fails; a
  screen or row that should win a word is one `EntryWeight` label.
- Harder: every new hub sub-page needs a `SECTION` entry as well as its
  rows (the index test checks the icon table, not the section list).
- To revisit: whether `PRIMARY` needs a counterpart (`DETAIL`, a demotion
  for tuning rows such as offsets and durations) once history shows what
  people actually pick; whether the recent-picks list should also appear
  on the settings home; ranking on a background dispatcher if the index
  doubles.

## Action items

1. [x] `SearchStrings` interface; index and vocabulary readable off XML in tests
2. [x] Remove duplicated rows; `distinctBy(key)` guard; test
3. [x] `EntryWeight` title bonuses; `PRIMARY` labels on 24 rows
4. [x] Sub-page sections indexed as destinations
5. [x] Substring minimum length, phrase bonus, synonym discount 70 %
6. [x] `SearchPicks` store, `SearchBoost` hook, Clear affordance
7. [x] Hits / mentions split, bold matches, recent picks under the empty field
8. [x] `SettingsSearchRankingTest`: golden top-1 and top-3 cases on the real index
9. [ ] After a release: review the rankings dump against what people report, label `DETAIL` if needed
