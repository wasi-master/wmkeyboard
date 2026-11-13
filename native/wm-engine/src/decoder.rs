//! The tap decoder: what a run of taps most likely meant, as one word or
//! several, scored the way a noisy channel scores it.
//!
//! The fuzzy walk ([`crate::fuzzy`]) ranks single words by an edit budget
//! and leaves the decision to rules on top. This asks the question directly:
//! over every way of reading the taps as a sequence of listed words,
//!
//! ```text
//! score = Σ ln P(tap | key meant)          where the finger landed
//!       + Σ ln P(edit)                     letters left out, doubled, swapped
//!       + Σ ln P(word) + context(word)     how likely that word is, here
//! ```
//!
//! and returns the best readings with the score of the taps read literally,
//! so the caller can ask how much better the best reading is than what was
//! typed. Space is one more key: a tap that landed on `n` may have been meant
//! for the bar under it, and a space may be missing altogether, so
//! `yhebsamebperson` can come back as `the same person`. Keyboards that
//! decode this way: Gboard's FST decoder (Ouyang et al. 2017, the space as
//! an optional lexicon arc), AOSP LatinIME (`SPACE_OMISSION`,
//! `SPACE_SUBSTITUTION`), VelociTap (Vertanen et al. 2015).
//!
//! The other way round happens too: a tap meant for the `n` at the end of
//! `in` lands on the bar, and `in the` comes out `i  the`. So the taps of a
//! buffer may begin or end with the space taps around it (typed `' '`, with
//! where they landed), and the one next to the letters may be read as the
//! word's first or last letter ([`Reading::edge`]). Two space taps in a row
//! say one of them was not a space; a lone one read as a letter means the
//! space itself was left out, and pays [`Params::ln_space_omit`].
//!
//! The search is token passing over tap positions: a state is a place in a
//! word list's trie with the words finished before it. Each position keeps
//! [`Params::beam`] states ranked by score plus the best the rest of their
//! word could still add, so a rare word is not carried along to the end of
//! the buffer before it loses.

use std::collections::HashMap;

use crate::fuzzy::Walker;

/// The decoder's knobs. Rates are per letter; spreads are in key widths.
#[derive(Clone, Debug)]
pub struct Params {
    /// Spread of a tap around the key meant, across and down.
    pub sigma_x: f64,
    pub sigma_y: f64,
    /// A second, wider spread mixed in at this weight: most taps land near
    /// the key, a few land far, and one Gaussian has to choose between being
    /// sharp for the first and forgiving for the second. 0 is one Gaussian.
    pub tail_weight: f64,
    /// How many times wider the tail is than the core.
    pub tail_scale: f64,
    /// Floor on one tap's price: a key no finger slip reaches is a letter
    /// the user meant to type ("definately"), rare but not impossible. Kept
    /// well under a swap's price, or `wnat` reads as `what` with an `n`
    /// nobody's finger explains rather than as `want` with two keys swapped.
    pub ln_far: f64,
    /// A letter of the word never tapped.
    pub ln_omit: f64,
    /// A tap that belongs to no letter of the word.
    pub ln_insert: f64,
    /// The same letter tapped twice.
    pub ln_double: f64,
    /// Two letters tapped in the wrong order.
    pub ln_swap: f64,
    /// A space between two words that has no tap at all.
    pub ln_space_omit: f64,
    /// Two space taps in a row both meant as spaces, mid-sentence: what the
    /// alternative, one of them a letter that landed on the bar, is weighed
    /// against.
    pub ln_double_space: f64,
    /// Every word after the first: what a second word costs on top of its
    /// own prior, which keeps a run of short words from beating one long one
    /// on spelling alone.
    pub ln_extra_word: f64,
    /// ln P of a word no list holds, for the literal reading.
    pub ln_unknown_word: f64,
    /// Per letter of an unknown word, on top of [`Params::ln_unknown_word`].
    pub ln_unknown_letter: f64,
    /// Edits (not slips) allowed in one word.
    pub max_edits: u8,
    /// Words one buffer may become.
    pub max_words: usize,
    /// States kept per tap position.
    pub beam: usize,
}

impl Default for Params {
    fn default() -> Params {
        Params {
            sigma_x: 0.32,
            sigma_y: 0.34,
            tail_weight: 0.0,
            tail_scale: 2.5,
            ln_far: (0.0005f64).ln(),
            ln_omit: (0.01f64).ln(),
            ln_insert: (0.004f64).ln(),
            ln_double: (0.01f64).ln(),
            ln_swap: (0.01f64).ln(),
            ln_space_omit: (0.004f64).ln(),
            ln_double_space: (0.01f64).ln(),
            ln_extra_word: (0.5f64).ln(),
            ln_unknown_word: (1e-6f64).ln(),
            ln_unknown_letter: (0.5f64).ln(),
            max_edits: 2,
            max_words: 5,
            beam: 96,
        }
    }
}

/// Where the keys are: letter centres in key widths, and the space bar as a
/// rectangle (centre x, centre y, half width, half height), when known.
#[derive(Clone, Debug, Default)]
pub struct Geometry {
    pub keys: Vec<(u16, f32, f32)>,
    pub space: Option<(f32, f32, f32, f32)>,
}

/// One word list in the search, with how likely each of its words is.
pub struct Source<'a> {
    pub walker: &'a dyn Walker,
    /// ln P(word) from the frequency the list stores; must not decrease as
    /// the frequency grows, since the search bounds a word by its subtree's
    /// best.
    pub prior: &'a dyn Fn(i32) -> f64,
}

/// What the words before say about the next one, as a bonus in nats over
/// the word's own prior.
pub trait Context {
    fn bonus(&self, prev2: Option<&str>, prev: Option<&str>, word: &str) -> f64;
    /// The most [`Context::bonus`] ever returns.
    fn cap(&self) -> f64;
}

/// No context: every word on its prior alone.
pub struct NoContext;
impl Context for NoContext {
    fn bonus(&self, _: Option<&str>, _: Option<&str>, _: &str) -> f64 {
        0.0
    }
    fn cap(&self) -> f64 {
        0.0
    }
}

/// One keystroke of the buffer: the character it typed and, when the
/// keyboard knows it, where the finger landed (key widths).
#[derive(Clone, Copy, Debug)]
pub struct Tap {
    pub typed: u16,
    pub at: Option<(f32, f32)>,
}

/// A reading of the buffer.
#[derive(Clone, Debug, PartialEq)]
pub struct Reading {
    /// The words, space-separated.
    pub text: String,
    pub score: f64,
    /// Edits spent across all words (omissions, insertions, swaps).
    pub edits: u32,
    /// Taps read as a key other than the one they typed.
    pub slips: u32,
    /// Spaces put in where the buffer had none.
    pub spaces: u32,
    /// Of [`Reading::score`], the part the words' priors and context gave;
    /// the rest is the channel (where the taps landed, edits, spaces).
    pub lexical: f64,
    /// Of [`Reading::lexical`], the part the context model gave ([`blend`]);
    /// 0 without one.
    pub model: f64,
    /// Which of the space taps around the buffer it read as a letter: 1 the
    /// one before its first letter, 2 the one after its last.
    pub edge: u8,
    /// Of [`Reading::edits`], by kind: [`EDIT_OMIT`], [`EDIT_INSERT`],
    /// [`EDIT_DOUBLE`], [`EDIT_SWAP`], [`EDIT_FAR`].
    pub kinds: [u8; 5],
}

/// A letter of the word never tapped.
pub const EDIT_OMIT: usize = 0;
/// A tap that belongs to no letter.
pub const EDIT_INSERT: usize = 1;
/// A key tapped twice.
pub const EDIT_DOUBLE: usize = 2;
/// Two letters tapped the wrong way round.
pub const EDIT_SWAP: usize = 3;
/// A letter no slip of the finger reaches ("definately").
pub const EDIT_FAR: usize = 4;

/// What [`decode`] found.
#[derive(Clone, Debug, Default)]
pub struct Decoded {
    /// Best first, distinct texts.
    pub readings: Vec<Reading>,
    /// The taps read exactly as typed, as one word: its list score when a
    /// list holds it, the unknown-word score otherwise.
    pub literal: f64,
    /// Whether a list holds the typed buffer as it stands.
    pub literal_listed: bool,
    /// Of [`Decoded::literal`], the part its prior and context gave.
    pub literal_lexical: f64,
    /// Of [`Decoded::literal_lexical`], the part the context model gave.
    pub literal_model: f64,
}

impl Decoded {
    pub fn best(&self) -> Option<&Reading> {
        self.readings.first()
    }
}

#[derive(Clone)]
struct State {
    src: usize,
    node: u32,
    /// The current word so far.
    cur: Vec<u16>,
    /// Words finished, oldest first.
    words: Vec<String>,
    score: f64,
    edits_here: u8,
    edits: u32,
    slips: u32,
    spaces: u32,
    lexical: f64,
    edge: u8,
    kinds: [u8; 5],
}

/// Per-tap channel prices: `price[pos][key]` for every letter key, and the
/// space bar's, each relative to the best key for that tap (0 at best).
#[derive(Clone, Default)]
struct Channel {
    letters: Vec<HashMap<u16, f64>>,
    space: Vec<f64>,
    nearest: Vec<u16>,
}

impl Channel {
    /// Prices one more tap.
    fn push(&mut self, tap: &Tap, geometry: &Geometry, params: &Params, adjacent: &dyn Fn(u16, u16) -> bool) {
        let sx2 = 2.0 * params.sigma_x * params.sigma_x;
        let sy2 = 2.0 * params.sigma_y * params.sigma_y;
        // ln of the core-and-tail mixture at a squared-and-scaled distance
        // `q` (the core's exponent), up to a constant.
        let (w, k2) = (params.tail_weight, params.tail_scale * params.tail_scale);
        let mix = |q: f64| -> f64 {
            if w <= 0.0 {
                -q
            } else {
                ((1.0 - w) * (-q).exp() + w / k2 * (-q / k2).exp()).ln()
            }
        };
        let mut m = HashMap::with_capacity(geometry.keys.len());
        match tap.at {
            Some(p) if !geometry.keys.is_empty() => {
                let mut best = f64::NEG_INFINITY;
                let mut best_key = tap.typed;
                for &(k, cx, cy) in &geometry.keys {
                    let dx = (p.0 - cx) as f64;
                    let dy = (p.1 - cy) as f64;
                    let ll = mix((dx * dx) / sx2 + (dy * dy) / sy2);
                    if ll > best {
                        best = ll;
                        best_key = k;
                    }
                    m.insert(k, ll);
                }
                let sp = geometry.space.map_or(f64::NEG_INFINITY, |(cx, cy, hw, hh)| {
                    let dx = ((p.0 - cx).abs() - hw).max(0.0) as f64;
                    let dy = ((p.1 - cy).abs() - hh).max(0.0) as f64;
                    mix((dx * dx) / sx2 + (dy * dy) / sy2)
                });
                let top = best.max(sp);
                for v in m.values_mut() {
                    *v = (*v - top).max(params.ln_far);
                }
                self.space.push((sp - top).max(params.ln_far));
                self.nearest.push(best_key);
            }
            _ => {
                // No coordinates: the typed key, its neighbours a slip away.
                for &(k, _, _) in &geometry.keys {
                    let v = if k == tap.typed {
                        0.0
                    } else if adjacent(k, tap.typed) {
                        (0.05f64).ln()
                    } else {
                        params.ln_far
                    };
                    m.insert(k, v);
                }
                self.space.push(params.ln_far);
                self.nearest.push(tap.typed);
            }
        }
        self.letters.push(m);
    }

    /// The price of reading the tap at `pos` as `key`. A letter no key
    /// carries is read on the key it is written on: `é` and `ü` one
    /// long-press from `e` and `u` ([`LN_ACCENT`]), and a capital (a German
    /// noun as a list spells it) on its own lower-case key, free.
    fn letter(&self, pos: usize, key: u16, params: &Params) -> f64 {
        let m = &self.letters[pos];
        if let Some(&v) = m.get(&key) {
            return v;
        }
        let lower = lower_unit(key);
        if lower != key {
            if let Some(&v) = m.get(&lower) {
                return v;
            }
        }
        let bare = crate::accents::bare(lower);
        if bare != lower {
            if let Some(&v) = m.get(&lower_unit(bare)) {
                return v + LN_ACCENT;
            }
        }
        params.ln_far
    }
}

/// Whether `text` is `typed` with accents (or capitals) put back.
pub fn folds_to(text: &str, typed: &str) -> bool {
    let fold = |s: &str| -> Vec<u16> { s.encode_utf16().map(|c| crate::accents::bare(lower_unit(c))).collect() };
    text != typed && fold(text) == fold(typed)
}

/// A letter left without its accent: nearly free, the way the walk prices
/// it (`fuzzy::cost_accent`), since most typists leave accents to the
/// keyboard.
pub const LN_ACCENT: f64 = -0.105_360_515_657_826_3; // ln 0.9

/// `c` in lower case, when it is one code unit that has one.
fn lower_unit(c: u16) -> u16 {
    char::from_u32(c as u32)
        .and_then(|ch| {
            let mut l = ch.to_lowercase();
            match (l.next(), l.next()) {
                (Some(x), None) if (x as u32) < 0x1_0000 => Some(x as u16),
                _ => None,
            }
        })
        .unwrap_or(c)
}

/// Reads `taps` against `sources`, with `before` the words already in the
/// field (oldest first) for the context. `adjacent` answers for taps without
/// coordinates.
#[allow(clippy::too_many_arguments)]
pub fn decode(
    taps: &[Tap],
    geometry: &Geometry,
    sources: &[Source],
    context: &dyn Context,
    before: &[&str],
    params: &Params,
    adjacent: &dyn Fn(u16, u16) -> bool,
    limit: usize,
) -> Decoded {
    Session::default().decode(0, taps, geometry, sources, context, before, params, adjacent, limit)
}

/// A decode that carries on from the keystroke before.
///
/// The search never looks at taps still to come: a state's bound is its
/// score plus the best its word could still add, and only two expansions
/// (two taps swapped, a tap meant for the space bar) need the tap after
/// theirs to exist. So every position but the last two comes out the same
/// whatever is typed next, and a buffer one tap longer than the last one
/// costs those two positions, not the whole buffer again, the way AOSP
/// LatinIME resumes its search from cached nodes. The keyboard asks for the
/// buffer's reading after every key ([`Session::decode`]), so this is most
/// of what autocorrect costs while typing. The result is the same as a
/// fresh [`decode`] (tested); anything that is not a longer buffer of the
/// same taps under the same `key` starts over.
#[derive(Default)]
pub struct Session {
    key: u64,
    taps: Vec<Tap>,
    ch: Channel,
    /// States arriving at each position, not yet ranked.
    pending: Vec<Vec<State>>,
    /// Positions expanded for good.
    done: usize,
    /// Positions expanded over the session's life, for measuring.
    pub expansions: usize,
}

fn same_tap(a: &Tap, b: &Tap) -> bool {
    a.typed == b.typed
        && match (a.at, b.at) {
            (Some(p), Some(q)) => p.0.to_bits() == q.0.to_bits() && p.1.to_bits() == q.1.to_bits(),
            (None, None) => true,
            _ => false,
        }
}

impl Session {
    /// The buffer's reading. `key` names everything but the taps (lists,
    /// context, words before, knobs, geometry); a different key starts over.
    #[allow(clippy::too_many_arguments)]
    pub fn decode(
        &mut self,
        key: u64,
        taps: &[Tap],
        geometry: &Geometry,
        sources: &[Source],
        context: &dyn Context,
        before: &[&str],
        params: &Params,
        adjacent: &dyn Fn(u16, u16) -> bool,
        limit: usize,
    ) -> Decoded {
        let n = taps.len();
        if n == 0 || sources.is_empty() {
            return Decoded::default();
        }
        let kept = self.taps.iter().zip(taps).take_while(|(a, b)| same_tap(a, b)).count();
        if key != self.key || kept < self.taps.len() || self.pending.is_empty() {
            self.key = key;
            self.taps.clear();
            self.ch = Channel::default();
            self.done = 0;
            self.pending = vec![sources
                .iter()
                .enumerate()
                .map(|(i, src)| State {
                    src: i,
                    node: src.walker.root(),
                    cur: Vec::new(),
                    words: Vec::new(),
                    score: 0.0,
                    edits_here: 0,
                    edits: 0,
                    slips: 0,
                    spaces: 0,
                    lexical: 0.0,
                    edge: 0,
                    kinds: [0; 5],
                })
                .collect()];
        }
        for tap in &taps[self.taps.len()..] {
            self.ch.push(tap, geometry, params, adjacent);
            self.taps.push(*tap);
        }
        if self.pending.len() < n + 1 {
            self.pending.resize_with(n + 1, Vec::new);
        }
        let typed: Vec<u16> = taps.iter().map(|t| t.typed).collect();
        let edges = Edges::of(&typed);
        if edges.letters(n).is_empty() {
            return Decoded::default();
        }
        let env = Env { sources, context, before, params, edges };
        let mut kids: Vec<(u16, u32)> = Vec::new();
        let mut grandkids: Vec<(u16, u32)> = Vec::new();
        // For good: every position whose next tap exists and will stay.
        while self.done + 2 <= n {
            let pos = self.done;
            let ranked = env.rank(std::mem::take(&mut self.pending[pos]));
            let (_, rest) = self.pending.split_at_mut(pos + 1);
            let (one, two) = rest.split_at_mut(1);
            for s in &ranked {
                env.expand(&self.ch, &typed, pos, s, true, &mut one[0], two.first_mut(), &mut kids, &mut grandkids);
            }
            self.done += 1;
            self.expansions += 1;
        }
        // For now: the last tap and the end, on copies.
        let mut tail: Vec<Vec<State>> = self.pending[self.done..=n].to_vec();
        let mut finals: Vec<Reading> = Vec::new();
        for pos in self.done..=n {
            let ranked = env.rank(std::mem::take(&mut tail[pos - self.done]));
            self.expansions += 1;
            if pos == n {
                for s in &ranked {
                    env.end(s, &mut finals, &mut kids);
                }
                continue;
            }
            let (_, rest) = tail.split_at_mut(pos - self.done + 1);
            let (one, two) = rest.split_at_mut(1);
            for s in &ranked {
                env.expand(&self.ch, &typed, pos, s, pos + 1 < n, &mut one[0], two.first_mut(), &mut kids, &mut grandkids);
            }
        }
        env.conclude(&self.ch, &typed, finals, limit)
    }
}

/// What the search reads besides the taps.
struct Env<'a, 'b> {
    sources: &'a [Source<'b>],
    context: &'a dyn Context,
    before: &'a [&'a str],
    params: &'a Params,
    edges: Edges,
}

/// How many space taps open and close the buffer (each 0 to 2).
#[derive(Clone, Copy, Debug, Default, PartialEq)]
struct Edges {
    lead: usize,
    trail: usize,
}

impl Edges {
    fn of(typed: &[u16]) -> Edges {
        let space = ' ' as u16;
        let lead = typed.iter().take_while(|&&c| c == space).count().min(2);
        let trail = typed[lead..].iter().rev().take_while(|&&c| c == space).count().min(2);
        Edges { lead, trail }
    }

    /// The letters' positions.
    fn letters(&self, n: usize) -> std::ops::Range<usize> {
        self.lead..n.saturating_sub(self.trail).max(self.lead)
    }
}

/// The letters of a buffer, without the space taps around it.
pub fn letters_of(typed: &str) -> &str {
    typed.trim_matches(' ')
}

impl Env<'_, '_> {
    fn prev_of(&self, words: &[String], back: usize) -> Option<String> {
        if words.len() >= back {
            Some(words[words.len() - back].clone())
        } else {
            let rest = back - words.len();
            self.before.len().checked_sub(rest).map(|i| self.before[i].to_string())
        }
    }

    /// ln P(word) + context for a word finishing at `node` of `s`'s list.
    fn finish(&self, s: &State, node: u32, word: &str) -> Option<f64> {
        let src = &self.sources[s.src];
        if !src.walker.is_word(node) {
            return None;
        }
        let prior = (src.prior)(src.walker.frequency(node));
        let p1 = self.prev_of(&s.words, 1);
        let p2 = self.prev_of(&s.words, 2);
        let bonus = self.context.bonus(p2.as_deref(), p1.as_deref(), word);
        let extra = if s.words.is_empty() { 0.0 } else { self.params.ln_extra_word };
        Some(prior + bonus + extra)
    }

    fn bound(&self, s: &State) -> f64 {
        let src = &self.sources[s.src];
        s.score + (src.prior)(src.walker.max_subtree(s.node)) + self.context.cap()
    }

    /// Prune: merge equal states, keep the best `beam` by bound.
    fn rank(&self, beam: Vec<State>) -> Vec<State> {
        let mut seen: HashMap<(usize, u32, String), usize> = HashMap::new();
        let mut merged: Vec<State> = Vec::with_capacity(beam.len());
        for s in beam {
            let key = (s.src, s.node, s.words.join(" "));
            match seen.get(&key) {
                Some(&i) => {
                    if s.score > merged[i].score {
                        merged[i] = s;
                    }
                }
                None => {
                    seen.insert(key, merged.len());
                    merged.push(s);
                }
            }
        }
        let mut ranked: Vec<(f64, State)> = merged.into_iter().map(|s| (self.bound(&s), s)).collect();
        ranked.sort_by(|a, b| b.0.total_cmp(&a.0));
        ranked.truncate(self.params.beam);
        ranked.into_iter().map(|(_, s)| s).collect()
    }

    /// The end of the buffer: the word must be finished here, or one letter
    /// short of it.
    fn end(&self, s: &State, finals: &mut Vec<Reading>, kids: &mut Vec<(u16, u32)>) {
        let params = self.params;
        let walker = self.sources[s.src].walker;
        if s.cur.is_empty() {
            return;
        }
        let word = String::from_utf16_lossy(&s.cur);
        if let Some(add) = self.finish(s, s.node, &word) {
            finals.push(reading(s, &word, s.score + add, add));
        }
        if s.edits_here < params.max_edits {
            walker.children_into(s.node, kids);
            for &(c, child) in kids.iter() {
                let mut cur = s.cur.clone();
                cur.push(c);
                let w = String::from_utf16_lossy(&cur);
                if let Some(add) = self.finish(s, child, &w) {
                    let mut t = s.clone();
                    t.edits += 1;
                    t.kinds[EDIT_OMIT] = t.kinds[EDIT_OMIT].saturating_add(1);
                    finals.push(reading(&t, &w, s.score + params.ln_omit + add, add));
                }
            }
        }
    }

    /// Everything the tap at `pos` may have been for `s`, pushed to the
    /// position it leads to: `next` (pos + 1) or `after` (pos + 2, swaps).
    /// `has_next`: whether a tap follows this one.
    #[allow(clippy::too_many_arguments)]
    fn expand(
        &self,
        ch: &Channel,
        typed: &[u16],
        pos: usize,
        s: &State,
        has_next: bool,
        next: &mut Vec<State>,
        after: Option<&mut Vec<State>>,
        kids: &mut Vec<(u16, u32)>,
        grandkids: &mut Vec<(u16, u32)>,
    ) {
        let params = self.params;
        let walker = self.sources[s.src].walker;
        if !self.edges.letters(typed.len()).contains(&pos) {
            self.expand_edge(ch, typed.len(), pos, s, next, kids);
            return;
        }
        // Swaps and a tap meant for the bar need a letter after this one.
        let has_next = has_next && self.edges.letters(typed.len()).contains(&(pos + 1));
        walker.children_into(s.node, kids);
        let kids_now = kids.clone();
        // Match or slip: the tap at `pos` meant child `c`.
        for &(c, child) in &kids_now {
            let price = ch.letter(pos, c, params);
            if price <= params.ln_far && c != typed[pos] && s.edits_here >= params.max_edits {
                continue;
            }
            let mut t = s.clone();
            t.node = child;
            t.cur.push(c);
            t.score += price;
            if c != ch.nearest[pos] {
                t.slips += 1;
            }
            if price <= params.ln_far {
                t.edits_here += 1;
                t.edits += 1;
                t.kinds[EDIT_FAR] = t.kinds[EDIT_FAR].saturating_add(1);
            }
            next.push(t);
        }
        if s.edits_here < params.max_edits {
            // Omission: child `a` was never tapped; the tap meant its
            // child `b`.
            for &(a, child) in &kids_now {
                walker.children_into(child, grandkids);
                for &(b, grandchild) in grandkids.iter() {
                    let price = ch.letter(pos, b, params);
                    if price <= params.ln_far {
                        continue;
                    }
                    let mut t = s.clone();
                    t.node = grandchild;
                    t.cur.push(a);
                    t.cur.push(b);
                    t.score += params.ln_omit + price;
                    t.edits_here += 1;
                    t.edits += 1;
                    t.kinds[EDIT_OMIT] = t.kinds[EDIT_OMIT].saturating_add(1);
                    next.push(t);
                }
            }
            // Insertion: the tap belongs to no letter. A tap on the
            // letter just typed is a doubled key, the cheaper slip.
            if !s.cur.is_empty() {
                let doubled = s.cur.last() == Some(&ch.nearest[pos]);
                let mut t = s.clone();
                t.score += if doubled { params.ln_double } else { params.ln_insert };
                t.edits_here += 1;
                t.edits += 1;
                let kind = if doubled { EDIT_DOUBLE } else { EDIT_INSERT };
                t.kinds[kind] = t.kinds[kind].saturating_add(1);
                next.push(t);
            }
            // Swap: taps `pos` and `pos + 1` meant the next two letters
            // the other way round.
            if has_next {
                if let Some(after) = after {
                    for &(a, child) in &kids_now {
                        walker.children_into(child, grandkids);
                        for &(b, grandchild) in grandkids.iter() {
                            if a == b {
                                continue;
                            }
                            let price = ch.letter(pos, b, params) + ch.letter(pos + 1, a, params);
                            if price < 2.0 * params.ln_far + 1e-9 {
                                continue;
                            }
                            let mut t = s.clone();
                            t.node = grandchild;
                            t.cur.push(a);
                            t.cur.push(b);
                            t.score += params.ln_swap + price;
                            t.edits_here += 1;
                            t.edits += 1;
                            t.kinds[EDIT_SWAP] = t.kinds[EDIT_SWAP].saturating_add(1);
                            after.push(t);
                        }
                    }
                }
            }
        }
        // A word boundary: finish the word here and start the next.
        if !s.cur.is_empty() && s.words.len() + 1 < params.max_words {
            let word = String::from_utf16_lossy(&s.cur);
            if let Some(add) = self.finish(s, s.node, &word) {
                for (si, src) in self.sources.iter().enumerate() {
                    let mut base = s.clone();
                    base.words.push(word.clone());
                    base.cur.clear();
                    base.src = si;
                    base.node = src.walker.root();
                    base.edits_here = 0;
                    base.score += add;
                    base.lexical += add;
                    base.spaces += 1;
                    // The tap at `pos` was meant for the space bar.
                    if has_next {
                        let mut t = base.clone();
                        t.score += ch.space[pos];
                        next.push(t);
                    }
                    // No space was tapped: the tap at `pos` starts the
                    // next word.
                    src.walker.children_into(base.node, grandkids);
                    for &(c, child) in grandkids.iter() {
                        let price = ch.letter(pos, c, params);
                        if price <= params.ln_far {
                            continue;
                        }
                        let mut t = base.clone();
                        t.node = child;
                        t.cur.push(c);
                        t.score += params.ln_space_omit + price;
                        next.push(t);
                    }
                }
            }
        }
    }

    /// A space tap before or after the letters: a space, priced where it
    /// landed, or, for the one next to the letters, the word's first or last
    /// letter. A lone space tap read as a letter leaves the words without a
    /// space between them.
    fn expand_edge(&self, ch: &Channel, n: usize, pos: usize, s: &State, next: &mut Vec<State>, kids: &mut Vec<(u16, u32)>) {
        let params = self.params;
        let walker = self.sources[s.src].walker;
        let mut t = s.clone();
        t.score += ch.space[pos];
        // The second of two space taps, both read as spaces.
        let lead_pair = self.edges.lead == 2 && pos == 1;
        let trail_pair = self.edges.trail == 2 && pos + 1 == n && s.edge & 2 == 0;
        if lead_pair || trail_pair {
            t.score += params.ln_double_space;
        }
        next.push(t);
        let (letter_here, block, bit) = if pos < self.edges.lead {
            (pos + 1 == self.edges.lead && s.cur.is_empty() && s.words.is_empty(), self.edges.lead, 1u8)
        } else {
            (pos == n - self.edges.trail && !s.cur.is_empty() && s.edge & 2 == 0, self.edges.trail, 2u8)
        };
        if !letter_here {
            return;
        }
        let extra = if block == 1 { params.ln_space_omit } else { 0.0 };
        walker.children_into(s.node, kids);
        for &(c, child) in kids.iter() {
            let price = ch.letter(pos, c, params);
            if price <= params.ln_far {
                continue;
            }
            let mut t = s.clone();
            t.node = child;
            t.cur.push(c);
            t.score += price + extra;
            t.slips += 1;
            t.edge |= bit;
            next.push(t);
        }
    }

    /// The readings found, best first, with the literal's score beside them.
    fn conclude(&self, ch: &Channel, typed: &[u16], mut finals: Vec<Reading>, limit: usize) -> Decoded {
        let params = self.params;
        let all = typed;
        let letters = self.edges.letters(all.len());
        let typed = &all[letters.clone()];
        let n = typed.len();
        let typed_word = String::from_utf16_lossy(typed);
        let mut out = Decoded::default();
        // The literal reading: listed, or unknown.
        let literal_listed =
            self.sources.iter().any(|src| node_of(src.walker, typed).is_some_and(|nd| src.walker.is_word(nd)));
        let p1 = self.before.last().copied();
        let p2 = self.before.len().checked_sub(2).map(|i| self.before[i]);
        let mut literal = params.ln_unknown_word + params.ln_unknown_letter * n as f64;
        for src in self.sources {
            if let Some(nd) = node_of(src.walker, typed) {
                if src.walker.is_word(nd) {
                    let listed = (src.prior)(src.walker.frequency(nd)) + self.context.bonus(p2, p1, &typed_word);
                    literal = literal.max(listed);
                }
            }
        }
        let literal_lexical = literal;
        // Spatial price of the literal: every tap on the key it typed, which
        // is the nearest key unless coordinates say otherwise.
        for (pos, &t) in all.iter().enumerate() {
            literal += if letters.contains(&pos) { ch.letter(pos, t, params) } else { ch.space[pos] };
        }
        for block in [self.edges.lead, self.edges.trail] {
            if block == 2 {
                literal += params.ln_double_space;
            }
        }
        finals.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.text.cmp(&b.text)));
        let mut seen = std::collections::HashSet::new();
        for r in finals {
            if seen.insert(r.text.clone()) {
                out.readings.push(r);
                if out.readings.len() >= limit {
                    break;
                }
            }
        }
        out.literal = literal;
        out.literal_listed = literal_listed;
        out.literal_lexical = literal_lexical;
        out
    }
}

/// Where one person's finger lands on each key, learned from the words they
/// keep: per key, how far their taps sit from its centre, and how widely
/// they spread. A key seen a few times leans on the person's overall offset,
/// which in turn leans on none at all, so a handful of taps moves nothing
/// much and a habit (landing low on a phone held in one hand) shows up over
/// a few dozen words. Sivek & Riley (2022) report the same layering for
/// Gboard's personal key centres; Yin et al. (CHI 2013) the backoff.
#[derive(Clone, Debug, Default)]
pub struct TouchOffsets {
    /// Per key: taps, summed x and y offsets, summed squares.
    pub keys: HashMap<u16, [f64; 5]>,
    pub all: [f64; 5],
}

impl TouchOffsets {
    /// A tap more than this far from the key it typed (key widths) says
    /// nothing about where that key is aimed at (the keyboard's
    /// `KeyOffsets.MAX_OBSERVED`).
    pub const REACH: f32 = 0.9;
    /// Taps remembered, overall and per key: past these the sums are scaled
    /// down as new taps come in, which makes each a running mean whose
    /// newest tap weighs `1/memory`, as `KeyOffsets` keeps them (its
    /// `GLOBAL_MEMORY` and `CELL_MEMORY`), so a hand that changes (a new
    /// phone, a case) is followed rather than averaged against for good.
    pub const MEMORY: f64 = 200.0;
    pub const KEY_MEMORY: f64 = 40.0;
    /// Shrinkage, in taps' worth: the overall offset toward none, a key's
    /// toward the overall one, the spread toward the decoder's own.
    pub const K_GLOBAL: f32 = 20.0;
    pub const K_KEY: f32 = 15.0;
    pub const K_SPREAD: f64 = 200.0;
    /// No learned shift moves a key further than this (key widths).
    pub const MAX_SHIFT: f32 = 0.5;
    /// The spread of a typical hand's taps around its own key centres (key
    /// widths), which the decoder's spread was fitted for: a person whose taps
    /// spread twice as wide gets a decoder spread twice as wide. Fitted on
    /// Vertanen & Kristensson's dev set; a smaller one rewrote correct words.
    pub const SPREAD_REF: f64 = 0.25;

    /// Learns from a word kept as typed: each tap belongs to the letter at
    /// its place, so only words with one tap a letter teach anything.
    pub fn learn(&mut self, word: &str, taps: &[(f32, f32)], keys: &[(u16, f32, f32)]) {
        let units: Vec<u16> = word.encode_utf16().collect();
        if units.len() != taps.len() || units.len() < 2 {
            return;
        }
        for (&u, &(x, y)) in units.iter().zip(taps) {
            let Some(&(_, cx, cy)) = keys.iter().find(|k| k.0 == u) else { continue };
            let (dx, dy) = (x - cx, y - cy);
            if dx.abs() > Self::REACH || dy.abs() > Self::REACH {
                continue;
            }
            let (dx, dy) = (dx as f64, dy as f64);
            for (acc, cap) in [(self.keys.entry(u).or_default(), Self::KEY_MEMORY), (&mut self.all, Self::MEMORY)] {
                if acc[0] >= cap {
                    let keep = (cap - 1.0) / acc[0];
                    for v in acc.iter_mut() {
                        *v *= keep;
                    }
                }
                acc[0] += 1.0;
                acc[1] += dx;
                acc[2] += dy;
                acc[3] += dx * dx;
                acc[4] += dy * dy;
            }
        }
    }

    /// How many taps it has learned from.
    pub fn taps(&self) -> f64 {
        self.all[0]
    }

    /// The person's offset on `key`: the overall offset shrunk by
    /// `k_global` taps of none, and the key's own shrunk by `k_key` taps of
    /// the overall one.
    pub fn offset(&self, key: u16, k_global: f32, k_key: f32) -> (f32, f32) {
        let n = self.all[0] as f32;
        let g = (self.all[1] as f32 / (n + k_global), self.all[2] as f32 / (n + k_global));
        match self.keys.get(&key) {
            Some(e) => {
                let m = e[0] as f32;
                (g.0 + (e[1] as f32 - m * g.0) / (m + k_key), g.1 + (e[2] as f32 - m * g.1) / (m + k_key))
            }
            None => g,
        }
    }

    /// `keys` moved to where this person aims at them.
    pub fn shifted(&self, keys: &[(u16, f32, f32)], k_global: f32, k_key: f32) -> Vec<(u16, f32, f32)> {
        keys.iter()
            .map(|&(k, x, y)| {
                let (mut dx, mut dy) = self.offset(k, k_global, k_key);
                let length = (dx * dx + dy * dy).sqrt();
                if length > Self::MAX_SHIFT {
                    dx *= Self::MAX_SHIFT / length;
                    dy *= Self::MAX_SHIFT / length;
                }
                (k, x + dx, y + dy)
            })
            .collect()
    }

    /// The decoder's spread for this person: their taps' spread around
    /// their own key centres, pooled over keys, shrunk to `prior` by `k`
    /// taps' worth.
    pub fn spread(&self, prior: (f64, f64), k: f64) -> (f64, f64) {
        let (mut sx, mut sy, mut n) = (0.0, 0.0, 0.0);
        for e in self.keys.values() {
            if e[0] < 2.0 {
                continue;
            }
            sx += e[3] - e[1] * e[1] / e[0];
            sy += e[4] - e[2] * e[2] / e[0];
            n += e[0] - 1.0;
        }
        (((sx + k * prior.0 * prior.0) / (n + k)).sqrt(), ((sy + k * prior.1 * prior.1) / (n + k)).sqrt())
    }

    /// The factors on the decoder's spread, across and down: this person's
    /// spread over [`TouchOffsets::SPREAD_REF`], with `k` taps of a typical
    /// hand mixed in. Measured on recorded taps (second half of each
    /// person's sentences, learned from what the keyboard committed in the
    /// first): words left wrong 20.0% to 17.9% over learned offsets alone,
    /// correct words changed unmoved.
    pub fn spread_factors(&self, k: f64) -> (f64, f64) {
        let r = Self::SPREAD_REF;
        let (sx, sy) = self.spread((r, r), k);
        (sx / r, sy / r)
    }
}

fn reading(s: &State, last: &str, score: f64, last_lexical: f64) -> Reading {
    let mut text = s.words.join(" ");
    if !text.is_empty() {
        text.push(' ');
    }
    text.push_str(last);
    Reading {
        text,
        score,
        edits: s.edits,
        slips: s.slips,
        spaces: s.spaces,
        lexical: s.lexical + last_lexical,
        model: 0.0,
        edge: s.edge,
        kinds: s.kinds,
    }
}

fn node_of(walker: &dyn Walker, word: &[u16]) -> Option<u32> {
    let mut node = walker.root();
    for &c in word {
        node = walker.child(node, c)?;
    }
    Some(node)
}

/// The frequencies a trie holds, sorted, so a word's rank, and from it a
/// Zipf prior, can be read off its frequency: lists store frequencies on
/// scales of their own (subtitle counts, AOSP's 0..255, a learned count), and
/// a rank is the one thing they share.
#[derive(Clone, Debug, Default)]
pub struct Ranks {
    sorted: Vec<i32>,
}

impl Ranks {
    pub fn of(walker: &dyn Walker) -> Ranks {
        let mut sorted = Vec::new();
        let mut stack = vec![walker.root()];
        let mut kids = Vec::new();
        while let Some(node) = stack.pop() {
            if walker.is_word(node) {
                sorted.push(walker.frequency(node));
            }
            walker.children_into(node, &mut kids);
            stack.extend(kids.iter().map(|&(_, c)| c));
        }
        sorted.sort_unstable();
        Ranks { sorted }
    }

    pub fn size(&self) -> usize {
        self.sorted.len()
    }

    /// 1 + how many words stand above `frequency`.
    pub fn rank(&self, frequency: i32) -> usize {
        self.sorted.len() - self.sorted.partition_point(|&f| f <= frequency) + 1
    }

    /// ln P of a word stored at `frequency`.
    pub fn prior(&self, frequency: i32) -> f64 {
        zipf(self.rank(frequency), self.sorted.len())
    }
}

/// A learned word's prior from how often it was typed: a word typed once
/// stands where the list's word ranked [`LEARNED_RANK_ONCE`] does, and every
/// doubling of its count moves it up as a doubling of frequency would.
pub fn learned_prior(count: i32, list_size: usize) -> f64 {
    let rank = (LEARNED_RANK_ONCE / count.max(1) as f64).max(1.0);
    zipf(rank as usize, list_size.max(2))
}

/// The rank a word typed once is given. Swept on the sentence harness with
/// a lexicon learned from 3,000 other sentences (and again with a tenth of
/// its words also learned once misspelled): 1000 beat 3000 on every hand
/// (words left wrong 9.1/14.3/25.9% against 9.2/14.8/28.0%, none at all
/// 9.7/15.7/28.8%); 300 did better on the sloppiest hand but began to
/// change correctly typed words.
pub const LEARNED_RANK_ONCE: f64 = 1000.0;

/// Pair and triple counts as context: `alpha * ln(1 + pairs) + tri * ln(1 +
/// triples)`, capped. Counts come from whatever the caller has (the corpus
/// pack, the bundled pairs, the user's own).
pub struct Pairs<'a> {
    pub bigram: &'a dyn Fn(&str, &str) -> i32,
    pub trigram: &'a dyn Fn(&str, &str, &str) -> i32,
    pub alpha: f64,
    pub tri: f64,
    pub cap: f64,
}

/// Measured on the sentence harness: stronger pair weights cost more words
/// than they fix, the bonus counting a common pair's words twice.
pub const PAIR_ALPHA: f64 = 0.3;
pub const TRIPLE_ALPHA: f64 = 0.3;
pub const PAIR_CAP: f64 = 6.0;

impl Context for Pairs<'_> {
    fn bonus(&self, prev2: Option<&str>, prev: Option<&str>, word: &str) -> f64 {
        let Some(prev) = prev else { return 0.0 };
        let mut b = self.alpha * (1.0 + (self.bigram)(prev, word).max(0) as f64).ln();
        if let Some(p2) = prev2 {
            b += self.tri * (1.0 + (self.trigram)(p2, prev, word).max(0) as f64).ln();
        }
        b.min(self.cap)
    }
    fn cap(&self) -> f64 {
        self.cap
    }
}

/// The context model's say, blended in: each reading's lexical part (priors
/// and pair context) is replaced by `weight` of the model's log-probability
/// of its words in turn, `seq` answering for a text, `None` where the model
/// cannot read it. The literal is rescored the same way, then the readings
/// are sorted again.
pub fn blend(decoded: &mut Decoded, typed: &str, weight: f64, mut seq: impl FnMut(&str) -> Option<f64>) {
    if weight <= 0.0 {
        return;
    }
    for r in decoded.readings.iter_mut() {
        if let Some(l) = seq(&r.text) {
            let lexical = (1.0 - weight) * r.lexical + weight * l;
            r.score += lexical - r.lexical;
            r.lexical = lexical;
            r.model = weight * l;
        }
    }
    if let Some(l) = seq(typed) {
        let lexical = (1.0 - weight) * decoded.literal_lexical + weight * l;
        decoded.literal += lexical - decoded.literal_lexical;
        decoded.literal_lexical = lexical;
        decoded.literal_model = weight * l;
    }
    decoded.readings.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.text.cmp(&b.text)));
}

/// The weight [`blend`] gives the context model, measured on the sentence
/// harness (0.7 and 0.85 tie; past that the priors' word-level knowledge is
/// missed).
pub const MODEL_WEIGHT: f64 = 0.7;

/// What to do with the buffer.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Verdict {
    /// The reading to put in its place.
    pub apply: Option<String>,
    /// A reading close enough to ask about.
    pub offer: Option<String>,
    /// How far the best reading leads the literal, in nats.
    pub lead: f64,
}

/// How far the best reading must lead an unknown literal to replace it.
pub const APPLY_MARGIN: f64 = 2.0;
/// How far it must lead a literal some list holds: a real word is only
/// rewritten when the taps and the context say so loudly.
pub const KNOWN_MARGIN: f64 = 6.0;
/// How far a reading that only puts accents back on a listed word must
/// lead it ([`verdict_with`]). Swept on the sentence harness over 0.5..6 in
/// Spanish, French and Portuguese: 1.5 restored 67-71% of the accents left
/// off (6% to 32% at the known-word bar) and took the words left wrong from
/// 12.1/17.2/13.1% to 9.1/14.8/11.0%; 0.5 began changing correctly typed
/// French words (1.7%).
pub const ACCENT_MARGIN: f64 = 1.5;
/// The share of the bar a reading must reach to be offered instead.
pub const OFFER_SHARE: f64 = 0.35;

/// Applies the best reading when it leads the literal by `margin` (or
/// `known_margin` for a listed literal), offers it past `OFFER_SHARE` of
/// that, and otherwise leaves the buffer as typed.
pub fn verdict(decoded: &Decoded, typed: &str, margin: f64, known_margin: f64) -> Verdict {
    verdict_with(decoded, typed, margin, known_margin, known_margin)
}

/// [`verdict`] with its own bar, `accent_margin`, for a reading that only
/// puts accents back on a listed word as typed (`esta` → `está`): the taps
/// say nothing either way there, so the words around it decide.
pub fn verdict_with(decoded: &Decoded, typed: &str, margin: f64, known_margin: f64, accent_margin: f64) -> Verdict {
    verdict_full(decoded, typed, margin, known_margin, accent_margin, margin)
}

/// [`verdict_with`] with its own bar, `edge_margin`, for a reading that
/// reads a space tap around the buffer as a letter (`i` and two spaces as
/// `in`): the second space already says the first was not one.
pub fn verdict_full(
    decoded: &Decoded,
    typed: &str,
    margin: f64,
    known_margin: f64,
    accent_margin: f64,
    edge_margin: f64,
) -> Verdict {
    let typed = letters_of(typed);
    let Some(best) = decoded.readings.iter().find(|r| r.text != typed) else {
        return Verdict::default();
    };
    // A reading that is the literal itself (the typed word, listed) beats
    // every other: nothing to do.
    if decoded.readings.first().is_some_and(|r| r.text == typed) {
        return Verdict { lead: best.score - decoded.literal, ..Verdict::default() };
    }
    let bar = if !decoded.literal_listed {
        margin
    } else if best.edge != 0 {
        edge_margin
    } else if folds_to(&best.text, typed) {
        accent_margin
    } else {
        known_margin
    };
    let lead = best.score - decoded.literal;
    if lead >= bar {
        Verdict { apply: Some(best.text.clone()), offer: None, lead }
    } else if lead >= bar * OFFER_SHARE {
        Verdict { apply: None, offer: Some(best.text.clone()), lead }
    } else {
        Verdict { lead, ..Verdict::default() }
    }
}

/// The verdict as one probability, fitted on recorded taps
/// (`tools/autocorrect/fit_gate.py`): that the best reading other than the
/// letters as typed is the text meant, given how far it leads them and where
/// that lead comes from (the taps, the lists and pairs, the context model),
/// whether a list holds what was typed, how much the reading changed, and how
/// far it stands from the reading behind it. The fixed bars
/// ([`verdict_full`]) treat `mr` like `the`: a listed word is a listed word.
/// The gate sees that a rare listed spelling with a common word one slip
/// away is a different case from a common word typed right.
pub struct Gate;

/// Features [`Gate::features`] computes.
pub const GATE_FEATURES: usize = 17;
const GATE_HIDDEN: usize = 8;
/// The slider's ratio is multiplied by this before it becomes the gate's
/// bar, so the default ×4 asks for 0.85. Fitted on Vertanen & Kristensson's
/// dev set and simulated sentences with 40% of the list's words below rank
/// 1000 held out of the lists and of the context model's vocabulary (a word
/// it has never seen is spelled in pieces, as on the phone), checked on the
/// test set and other sentences. Against the fixed bars there: recorded
/// test 10.13% of words left wrong to 9.66%, correct words changed 18 to 17;
/// simulated 10.5/15.9/25.6% to 9.0/13.5/19.9%, held-out words changed 131
/// to 27 of 407. The first fit, whose model knew the held-out words, scored
/// 9.55% on the recorded test and changed 56 of them.
pub const GATE_CONFIDENCE_SCALE: f64 = 1.42;
/// The same for readings made without the context model, so the default ×4
/// asks for 0.8. Fitted the same way on runs with no model: at 0.8 it applied
/// 16 more right fixes on the recorded test set for one more correctly typed
/// word changed, and on simulated sentences 129 more, changing 31 correctly
/// typed words where the fixed bars changed 221 (23 held-out words against
/// 211). At 0.9 it gave up 256 right fixes on the recorded taps.
pub const GATE_PLAIN_CONFIDENCE_SCALE: f64 = 1.0;
/// The gap to the next reading is capped at this, and taken as this when
/// there is none.
const GATE_GAP_CAP: f64 = 8.0;

impl Gate {
    /// The candidate's index in `decoded.readings` and what the gate reads,
    /// or `None` when the best reading is the letters as typed.
    pub fn features(decoded: &Decoded, typed: &str) -> Option<(usize, [f64; GATE_FEATURES])> {
        let typed = letters_of(typed);
        let c = decoded.readings.first()?;
        if c.text == typed {
            return None;
        }
        let gap = decoded
            .readings
            .iter()
            .skip(1)
            .find(|r| r.text != typed)
            .map_or(GATE_GAP_CAP, |r| (c.score - r.score).min(GATE_GAP_CAP))
            .max(0.0);
        let (ls, llex, lmod) = (decoded.literal, decoded.literal_lexical, decoded.literal_model);
        let f = [
            c.score - ls,
            c.score - c.lexical,
            c.lexical - c.model,
            c.model,
            ls - llex,
            llex - lmod,
            lmod,
            f64::from(u8::from(c.model != 0.0 || lmod != 0.0)),
            f64::from(u8::from(decoded.literal_listed)),
            c.text.split(' ').count() as f64,
            f64::from(c.edits),
            f64::from(c.slips),
            f64::from(c.spaces),
            f64::from(u8::from(c.edge != 0)),
            typed.chars().count() as f64,
            gap,
            f64::from(u8::from(folds_to(&c.text, typed))),
        ];
        Some((0, f))
    }

    /// P(the candidate is the text meant).
    ///
    /// Two sets of weights: one for readings the context model weighed
    /// (feature 7), fitted with the model open, and one fitted without it,
    /// since without the model its two features are 0 and the lead is made of
    /// priors and pairs alone, which the first set never saw.
    pub fn probability(f: &[f64; GATE_FEATURES]) -> f64 {
        if f[7] > 0.5 {
            net(f, &GATE_MEAN, &GATE_SCALE, &GATE_W1, &GATE_B1, &GATE_W2, GATE_B2)
        } else {
            net(f, &GATE_PLAIN_MEAN, &GATE_PLAIN_SCALE, &GATE_PLAIN_W1, &GATE_PLAIN_B1, &GATE_PLAIN_W2, GATE_PLAIN_B2)
        }
    }

    /// Applies the candidate at `crate::confidence::apply_threshold` of the
    /// slider's `confidence` times [`GATE_CONFIDENCE_SCALE`], offers it at the
    /// offer threshold of the same.
    pub fn verdict(decoded: &Decoded, typed: &str, confidence: f64) -> Verdict {
        let modelled = Gate::features(decoded, typed).is_some_and(|(_, f)| f[7] > 0.5);
        let confidence = confidence * if modelled { GATE_CONFIDENCE_SCALE } else { GATE_PLAIN_CONFIDENCE_SCALE };
        let Some((i, f)) = Gate::features(decoded, typed) else {
            let lead = decoded.readings.iter().find(|r| r.text != letters_of(typed)).map_or(0.0, |r| r.score - decoded.literal);
            return Verdict { lead, ..Verdict::default() };
        };
        let p = Gate::probability(&f);
        let text = decoded.readings[i].text.clone();
        let lead = f[0];
        if p >= crate::confidence::apply_threshold(confidence) {
            Verdict { apply: Some(text), offer: None, lead }
        } else if p >= crate::confidence::offer_threshold(confidence) {
            Verdict { apply: None, offer: Some(text), lead }
        } else {
            Verdict { lead, ..Verdict::default() }
        }
    }
}

/// One hidden layer of tanh on standardised features, a logistic out.
fn net<const N: usize>(
    f: &[f64; N],
    mean: &[f64; N],
    scale: &[f64; N],
    w1: &[[f64; N]],
    b1: &[f64],
    w2: &[f64],
    b2: f64,
) -> f64 {
    let mut z = [0.0; N];
    for i in 0..N {
        let s = if scale[i] > 0.0 { scale[i] } else { 1.0 };
        z[i] = (f[i] - mean[i]) / s;
    }
    let mut out = b2;
    for ((row, b), w) in w1.iter().zip(b1).zip(w2) {
        let mut h = *b;
        for i in 0..N {
            h += row[i] * z[i];
        }
        out += w * h.tanh();
    }
    1.0 / (1.0 + (-out).exp())
}

// GATE FIT BEGIN
const GATE_MEAN: [f64; GATE_FEATURES] = [13.2718, -3.32522, -1.92539, -5.06327, -0.549551, -5.65714, -17.379, 1.0, 0.0811369, 1.21119, 0.241428, 1.14203, 0.211187, 0.00132199, 5.70858, 5.44827, 0.00941915];
const GATE_SCALE: [f64; GATE_FEATURES] = [7.75069, 3.35509, 1.31941, 3.4978, 1.14649, 3.26311, 7.85297, 1.0, 0.273045, 0.481862, 0.475852, 0.94651, 0.481862, 0.0363351, 3.12714, 2.68342, 0.0965942];
const GATE_W1: [[f64; GATE_FEATURES]; GATE_HIDDEN] = [
    [0.447005, 0.0874779, 0.0991573, -0.00803999, -0.0817781, -0.388864, -0.217253, 4.0886e-93, 0.0468992, 0.041157, -0.0115419, 0.189695, 0.0411567, 0.0128304, -0.173145, 0.0388722, 0.0872277],
    [0.559569, 0.150291, 0.191569, 0.396687, -0.0628201, -0.0419324, -0.252685, 9.91387e-93, 0.0445874, 0.0156074, 0.0877546, -0.00649934, 0.0156074, 0.00989838, -0.308022, -0.079892, 0.128978],
    [-0.446135, -0.087336, -0.0990158, 0.00806559, 0.0815931, 0.388367, 0.216786, 1.13565e-92, -0.0472492, -0.040955, 0.0115485, -0.189582, -0.0409562, -0.0128026, 0.17294, -0.0388969, -0.087106],
    [0.490612, 0.286712, 0.146086, 0.0605983, -0.0881117, -0.102106, -0.254994, 9.82399e-93, 0.00882223, -0.0531362, -0.221467, 0.0583966, -0.0531362, 0.00793998, -0.231043, -0.0576379, 0.0803226],
    [0.444011, 0.0867603, 0.0985593, -0.00872994, -0.0814071, -0.387225, -0.215775, -5.38232e-93, 0.0467715, 0.0407763, -0.0117007, 0.189033, 0.0408239, 0.0127659, -0.171928, 0.0391877, 0.0870027],
    [0.444652, 0.0869972, 0.0986102, -0.00852157, -0.0814697, -0.387674, -0.21608, 2.18421e-92, 0.0472064, 0.040734, -0.0116006, 0.189143, 0.040733, 0.0127869, -0.172304, 0.038984, 0.0870187],
    [0.444081, 0.0867923, 0.0985688, -0.00865177, -0.0812763, -0.387176, -0.215793, -2.85919e-92, 0.0468508, 0.0405469, -0.0116712, 0.188961, 0.0405454, 0.0127747, -0.172126, 0.0391735, 0.087033],
    [0.444174, 0.0868102, 0.0985581, -0.00865271, -0.0813231, -0.387265, -0.215847, 6.19746e-93, 0.0467754, 0.040677, -0.0115895, 0.189035, 0.0407293, 0.0127762, -0.172103, 0.0391003, 0.086989],
];
const GATE_B1: [f64; GATE_HIDDEN] = [0.896073, 0.584484, -0.894454, 0.901296, 0.88609, 0.887911, 0.885938, 0.886408];
const GATE_W2: [f64; GATE_HIDDEN] = [0.815304, 0.939196, -0.814105, 0.836974, 0.81142, 0.812294, 0.811366, 0.811509];
const GATE_B2: f64 = 1.94972;
// GATE FIT END

const GATE_PLAIN_HIDDEN: usize = 8;
// GATE PLAIN FIT BEGIN
const GATE_PLAIN_MEAN: [f64; GATE_FEATURES] = [8.28968, -3.14464, -6.39072, 0.0, -0.537301, -17.2877, 0.0, 0.0, 0.0848547, 1.20408, 0.218565, 1.13028, 0.20408, 0.00102854, 5.70215, 5.15221, 0.0106283];
const GATE_PLAIN_SCALE: [f64; GATE_FEATURES] = [4.74491, 3.19464, 4.39823, 1.0, 1.13621, 3.06888, 1.0, 1.0, 0.278665, 0.473438, 0.45134, 0.942687, 0.473438, 0.0320544, 3.11495, 2.51362, 0.102544];
const GATE_PLAIN_W1: [[f64; GATE_FEATURES]; GATE_PLAIN_HIDDEN] = [
    [0.20756, -0.0436694, 0.0123625, -1.04787e-92, -0.24019, -0.259718, -3.32482e-92, 4.0886e-93, 0.33769, -0.0086038, -0.308419, 0.470614, -0.00860348, 0.00717868, 0.128241, 0.179838, 0.0778694],
    [0.295823, 0.0622363, 0.0322862, 3.59943e-11, -0.220092, -0.264776, -1.56092e-92, 9.91387e-93, 0.163174, 0.0398267, 0.117749, 0.341485, 0.0398267, 0.010326, -0.0510784, 0.218178, 0.0827133],
    [-0.298231, -0.0632907, -0.0325399, 6.75199e-93, 0.220271, 0.266908, 4.81084e-93, 1.13565e-92, -0.165565, -0.0398453, -0.116493, -0.339033, -0.0398445, -0.0103231, 0.0535902, -0.21864, -0.08242],
    [0.763927, 0.531006, 0.260746, -1.1458e-92, -0.0628143, -0.23147, 8.56503e-93, 9.82399e-93, 0.184512, -0.158277, 0.100256, -0.251588, -0.158277, 0.0177309, -0.475209, -0.0883923, 0.113211],
    [0.235973, -0.0393538, 0.0583748, 4.47529e-93, -0.210577, -0.244193, -6.11841e-09, -5.38232e-93, 0.296356, -0.0369502, -0.400263, 0.420435, -0.0369503, 0.00663721, 0.0919501, 0.173426, 0.0735292],
    [0.19058, -0.037066, 0.240114, 3.15764e-93, -0.0743063, 0.0383301, 8.52737e-93, 2.18421e-92, 0.204335, 0.302104, 0.0713355, 0.211235, 0.302104, 0.00422503, -0.087282, 0.227166, 0.0607755],
    [0.313105, 0.0728242, -0.000954451, 1.79753e-92, -0.167423, -0.347677, 2.55278e-92, -2.85919e-92, 0.091617, -0.00996345, 0.141044, 0.0743788, -0.0099643, 0.0129942, 0.0331108, 0.370542, 0.0697958],
    [0.330731, 0.0520865, 0.124489, 1.39247e-93, -0.0671678, -0.253858, 1.93571e-93, 6.19746e-93, -0.0709882, 0.342704, 0.0316579, 0.572091, 0.342704, 0.00765499, -0.62779, -0.0799019, 0.0669341],
];
const GATE_PLAIN_B1: [f64; GATE_PLAIN_HIDDEN] = [0.32056, 0.463426, -0.476984, 0.00662504, 0.415945, 0.904437, 0.846823, 1.39981];
const GATE_PLAIN_W2: [f64; GATE_PLAIN_HIDDEN] = [0.910291, 0.777632, -0.778965, 1.25461, 0.899059, 0.671907, 0.761707, 1.1008];
const GATE_PLAIN_B2: f64 = 1.61422;
// GATE PLAIN FIT END

/// What re-reading a committed buffer with the words after it found.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Recheck {
    /// The reading to put in place of what was committed.
    pub apply: Option<String>,
    /// A reading close enough to offer as a chip.
    pub offer: Option<String>,
    /// How far the best reading leads what was committed, in nats.
    pub lead: f64,
}

/// How far a re-read must lead what was committed before it rewrites text
/// the user has already seen land. Measured on the sentence harness over
/// 2..5 and 4..10: 3 and 5 fixed 26 to 41 words per 400 sentences and broke
/// none on a careful hand, where 2 and 4 broke three.
pub const RECHECK_MARGIN: f64 = 3.0;
/// The same, when what stands is the word as typed and a list holds it.
pub const RECHECK_KNOWN_MARGIN: f64 = 5.0;

/// What re-reading a committed buffer saw, before any verdict: the decode
/// with the words after it weighed in, and where what was committed stands.
#[derive(Clone, Debug, Default)]
pub struct RecheckRead {
    /// The readings, best first, each with the pairs it makes with the
    /// words after it (and the context model, when blended) in its score.
    pub decoded: Decoded,
    /// The buffer's letters as typed.
    pub typed: String,
    /// What was committed: its score and its lexical and model parts.
    pub own: f64,
    pub own_lexical: f64,
    pub own_model: f64,
    /// Whether what was committed is the letters as typed.
    pub own_literal: bool,
    /// What the pairs with the words after gave the best reading and what
    /// was committed.
    pub best_right: f64,
    pub own_right: f64,
}

/// Re-reads the taps of the buffer committed as `committed`, now that the
/// words `after` it have landed: "I want to by a car" has `by` wrong once
/// `a car` is in. `before` are the words before the buffer. The taps are
/// decoded as at commit time, then every reading (and the literal) gains
/// what the pairs say about the words after it, and, with `lm`, the context
/// model's say over the reading followed by `after`. What was committed has
/// to be among the readings or be the literal, or there is nothing to
/// compare with, and `None` comes back; so it does when there is nothing
/// after, or the keyboard put in an apostrophe the taps never typed.
#[allow(clippy::too_many_arguments)]
pub fn recheck_read(
    taps: &[Tap],
    geometry: &Geometry,
    sources: &[Source],
    context: &dyn Context,
    before: &[&str],
    committed: &str,
    after: &[&str],
    params: &Params,
    adjacent: &dyn Fn(u16, u16) -> bool,
    lm: Option<(f64, ModelSeq)>,
) -> Option<RecheckRead> {
    if after.is_empty() || taps.is_empty() {
        return None;
    }
    let typed: String = String::from_utf16_lossy(&taps.iter().map(|t| t.typed).collect::<Vec<_>>());
    let typed = letters_of(&typed).to_string();
    // An apostrophe the keyboard put in ("im" committed as "i'm") is its own
    // rule's call; read again, the taps would price it as a letter left out
    // and hand "in" the win.
    if committed.contains('\'') && !typed.contains('\'') {
        return None;
    }
    let mut d = decode(taps, geometry, sources, context, before, params, adjacent, RECHECK_READINGS);
    // The pairs the words after a reading make with it.
    let right = |text: &str| -> f64 {
        let mut seq: Vec<&str> = before.iter().rev().take(2).rev().copied().collect();
        seq.extend(text.split(' '));
        let start = seq.len();
        seq.extend_from_slice(after);
        let mut b = 0.0;
        for j in start..seq.len() {
            let p1 = seq.get(j.wrapping_sub(1)).copied();
            let p2 = if j >= 2 { seq.get(j - 2).copied() } else { None };
            b += context.bonus(p2, p1, seq[j]);
        }
        b
    };
    for r in d.readings.iter_mut() {
        let b = right(&r.text);
        r.score += b;
        r.lexical += b;
    }
    let b = right(&typed);
    d.literal += b;
    d.literal_lexical += b;
    d.readings.sort_by(|a, b| b.score.total_cmp(&a.score).then_with(|| a.text.cmp(&b.text)));
    if let Some((weight, seq)) = lm {
        let tail = after.join(" ");
        blend(&mut d, &typed, weight, |text| seq(&format!("{text} {tail}")));
    }
    let as_reading = d.readings.iter().find(|r| r.text == committed).map(|r| (r.score, r.lexical, r.model, false));
    let as_literal = (committed == typed).then_some((d.literal, d.literal_lexical, d.literal_model, true));
    let (own, own_lexical, own_model, own_literal) = match (as_reading, as_literal) {
        (Some(a), Some(b)) => {
            if b.0 >= a.0 {
                b
            } else {
                a
            }
        }
        (Some(a), None) | (None, Some(a)) => a,
        (None, None) => return None,
    };
    let best_right = d.readings.first().map_or(0.0, |r| right(&r.text));
    let own_right = right(committed);
    Some(RecheckRead { decoded: d, typed, own, own_lexical, own_model, own_literal, best_right, own_right })
}

/// [`recheck_read`] judged at fixed bars: the best reading replaces what
/// was committed when it leads by `margin` (`known_margin` when that is the
/// word as typed and a list holds it), and is offered past [`OFFER_SHARE`].
#[allow(clippy::too_many_arguments)]
pub fn recheck(
    taps: &[Tap],
    geometry: &Geometry,
    sources: &[Source],
    context: &dyn Context,
    before: &[&str],
    committed: &str,
    after: &[&str],
    params: &Params,
    adjacent: &dyn Fn(u16, u16) -> bool,
    lm: Option<(f64, ModelSeq)>,
    margin: f64,
    known_margin: f64,
) -> Recheck {
    let Some(read) = recheck_read(taps, geometry, sources, context, before, committed, after, params, adjacent, lm) else {
        return Recheck::default();
    };
    recheck_verdict(&read, committed, margin, known_margin)
}

/// The re-check's verdict as one probability, fitted on recorded taps and
/// simulated sentences (`tools/autocorrect/fit_recheck.py`): that the best
/// re-read is the text meant, given how far it leads what was committed and
/// where that lead comes from, how much of it the words after gave, whether
/// what stands is the word as typed and listed, and what the re-read
/// changed. The fixed bars ([`recheck_verdict`]) treat every listed word
/// alike, as the space's fixed bars did.
pub struct RecheckGate;

/// Features [`RecheckGate::features`] computes.
pub const RECHECK_FEATURES: usize = 22;
const RECHECK_GATE_HIDDEN: usize = 8;
/// The slider's ratio is multiplied by this before it becomes the
/// re-check gate's bar for a re-read the context model weighed, so the
/// default ×4 asks for 0.8. A re-read that turns one wrong word into another
/// changes nothing in the count but is a rewrite the user sees, and the fit
/// weighs those at half. Fitted 2026-11-13 on Vertanen & Kristensson's dev
/// set (weighted ten times) and simulated sentences (held-out words, words
/// no list has), with and without the model. On rows held back, with the
/// model, against the fixed bars: recorded taps 18 right words put back
/// against 24, none broken against none, wrong words swapped for other
/// wrong ones 4 against 12; simulated 241 put back against 206, 55 broken
/// against 65, 38 wrong for wrong against 167.
pub const RECHECK_CONFIDENCE_SCALE: f64 = 1.0;
/// The same without the context model, so ×4 asks for 0.7: recorded taps 36
/// put back against 37, 5 broken against 3, 7 wrong for wrong against 29;
/// simulated 411 against 422, 83 broken against 153, 64 against 298.
pub const RECHECK_PLAIN_CONFIDENCE_SCALE: f64 = 0.583;

impl RecheckGate {
    /// What the gate reads of a re-read `depth` commits back, or `None`
    /// when the best reading is what was committed.
    pub fn features(read: &RecheckRead, committed: &str, depth: usize) -> Option<[f64; RECHECK_FEATURES]> {
        let d = &read.decoded;
        let c = d.readings.first()?;
        if c.text == committed {
            return None;
        }
        let gap = d
            .readings
            .iter()
            .skip(1)
            .find(|r| r.text != committed)
            .map_or(GATE_GAP_CAP, |r| (c.score - r.score).min(GATE_GAP_CAP))
            .max(0.0);
        Some([
            c.score - read.own,
            c.score - c.lexical,
            c.lexical - c.model,
            c.model,
            read.own - read.own_lexical,
            read.own_lexical - read.own_model,
            read.own_model,
            f64::from(u8::from(c.model != 0.0 || read.own_model != 0.0)),
            f64::from(u8::from(read.own_literal)),
            f64::from(u8::from(d.literal_listed)),
            c.text.split(' ').count() as f64,
            f64::from(c.edits),
            f64::from(c.slips),
            f64::from(c.spaces),
            f64::from(c.kinds[EDIT_OMIT]),
            f64::from(c.kinds[EDIT_INSERT]) + f64::from(c.kinds[EDIT_DOUBLE]),
            f64::from(c.kinds[EDIT_SWAP]),
            f64::from(c.kinds[EDIT_FAR]),
            read.typed.encode_utf16().count() as f64,
            gap,
            read.best_right - read.own_right,
            depth as f64,
        ])
    }

    /// P(the best re-read is the text meant).
    pub fn probability(f: &[f64; RECHECK_FEATURES]) -> f64 {
        net(f, &RECHECK_GATE_MEAN, &RECHECK_GATE_SCALE, &RECHECK_GATE_W1, &RECHECK_GATE_B1, &RECHECK_GATE_W2, RECHECK_GATE_B2)
    }

    /// Replaces what was committed at `crate::confidence::apply_threshold`
    /// of the slider's `confidence` times [`RECHECK_CONFIDENCE_SCALE`] (or
    /// [`RECHECK_PLAIN_CONFIDENCE_SCALE`] without the context model), and
    /// offers the re-read at the offer threshold of the same.
    pub fn verdict(read: &RecheckRead, committed: &str, depth: usize, confidence: f64) -> Recheck {
        let Some(f) = RecheckGate::features(read, committed, depth) else { return Recheck::default() };
        let text = read.decoded.readings[0].text.clone();
        let lead = f[0];
        let p = RecheckGate::probability(&f);
        let confidence = confidence * if f[7] > 0.5 { RECHECK_CONFIDENCE_SCALE } else { RECHECK_PLAIN_CONFIDENCE_SCALE };
        if p >= crate::confidence::apply_threshold(confidence) {
            Recheck { apply: Some(text), offer: None, lead }
        } else if p >= crate::confidence::offer_threshold(confidence) {
            Recheck { apply: None, offer: Some(text), lead }
        } else {
            Recheck { lead, ..Recheck::default() }
        }
    }
}

// RECHECK FIT BEGIN
const RECHECK_GATE_MEAN: [f64; RECHECK_FEATURES] = [0.411078, -8.21234, -5.65126, -6.62181, -1.1506, -11.3725, -8.37336, 0.432416, 0.867847, 0.10965, 1.33908, 1.07246, 0.859496, 0.339081, 0.268636, 0.521497, 0.0946489, 0.187674, 6.29655, 2.41224, 0.842652, 1.0];
const RECHECK_GATE_SCALE: [f64; RECHECK_FEATURES] = [6.71939, 5.24973, 5.5254, 9.12825, 3.07421, 7.13024, 11.1826, 0.495411, 0.338657, 0.312454, 0.624416, 0.753644, 0.953274, 0.624416, 0.466704, 0.692467, 0.30209, 0.410155, 3.40236, 2.22255, 1.43775, 1.0];
const RECHECK_GATE_W1: [[f64; RECHECK_FEATURES]; RECHECK_GATE_HIDDEN] = [
    [-0.330668, -0.365678, 0.347586, 0.0411522, 0.494205, 0.244767, -0.0596678, -0.214594, -0.018077, 0.109219, 0.0351723, 0.00228751, 0.138319, 0.0351726, -0.243006, 0.161368, -0.305652, 0.233771, 0.113438, -0.371452, -0.363976, -1.24273e-93],
    [-0.172759, -0.069061, 0.475772, -0.113665, 0.225037, -0.160862, 0.254523, 0.0693712, -0.604657, 0.323304, -0.444577, 0.0504282, 0.0699699, -0.444581, -0.124447, 0.250308, 0.0505628, -0.226024, 0.356386, 0.446395, 0.0809062, 1.3668e-92],
    [-0.297152, -0.195492, -0.0645392, 0.102844, 0.160623, 0.125123, 0.0148203, 0.0458045, -0.0979268, -0.385665, -0.148524, 0.0861487, -0.632057, -0.148527, -0.159104, 0.413542, 0.442137, -0.684064, -0.0876321, 0.0554859, -0.0988365, 1.1828e-92],
    [-0.563395, 0.3231, -0.470158, -0.241456, -0.0341319, 0.337367, -0.145277, 0.310525, 0.0982794, 0.104309, -0.345926, 0.020126, -0.159931, -0.345917, 0.00314497, 0.0908599, -0.00429659, -0.116639, 0.268499, -0.064982, -0.368721, -8.80652e-16],
    [0.183476, -0.204134, -0.060824, -0.046903, -0.0309783, -0.22104, -0.125309, -0.0463014, 0.285404, -0.124186, 0.0716177, 0.642784, -0.185919, 0.0716107, 0.192714, 0.583775, -0.647554, 0.454738, -0.177217, 0.189698, 0.12583, 6.98321e-93],
    [-0.69371, 0.160371, 0.0983886, 0.0620903, -0.165411, 0.617981, 0.242713, -0.0407736, -0.183528, 0.180097, 0.122622, -0.0914568, 0.267073, 0.122618, -0.0475782, -0.169015, 0.0663787, 0.12258, 0.227442, 0.236132, -0.359147, 4.627e-93],
    [0.538024, 0.504857, -0.227091, -0.00483276, -0.171647, 0.281544, -0.334491, 0.185689, 0.0241283, -0.0962335, -0.261262, 0.0513382, -0.210829, -0.261275, -0.0943924, 0.0895809, -0.0589074, 0.0932566, -0.496632, -0.00535258, 0.147407, 7.07e-94],
    [-0.366078, -0.112709, 0.112309, -0.0881151, -0.264249, 0.31196, 0.0240571, -0.363669, -0.142744, 0.00214567, 0.144457, -0.135289, 0.00232086, 0.14446, -0.375521, 0.253953, 0.156446, -0.36473, -0.793823, -0.348938, 0.384191, 4.46388e-93],
];
const RECHECK_GATE_B1: [f64; RECHECK_GATE_HIDDEN] = [0.177655, 0.533604, -0.203326, -0.283072, 1.02324, 0.125106, -1.02621, -0.0437382];
const RECHECK_GATE_W2: [f64; RECHECK_GATE_HIDDEN] = [-1.07181, 1.38045, -1.18201, -1.14162, -1.49827, -1.0678, 1.2453, -1.3704];
const RECHECK_GATE_B2: f64 = -0.49807;
// RECHECK FIT END

/// The fixed bars of [`recheck`] on a reading already made.
pub fn recheck_verdict(read: &RecheckRead, committed: &str, margin: f64, known_margin: f64) -> Recheck {
    let d = &read.decoded;
    let Some(best) = d.readings.first() else { return Recheck::default() };
    if best.text == committed {
        return Recheck::default();
    }
    let lead = best.score - read.own;
    let bar = if committed == read.typed && d.literal_listed { known_margin } else { margin };
    if lead >= bar {
        Recheck { apply: Some(best.text.clone()), offer: None, lead }
    } else if lead >= bar * OFFER_SHARE {
        Recheck { apply: None, offer: Some(best.text.clone()), lead }
    } else {
        Recheck { lead, ..Recheck::default() }
    }
}

/// The context model's log-probability of a text's words in turn, `None`
/// where it cannot read them; what [`recheck`] blends in.
pub type ModelSeq<'a> = &'a mut dyn FnMut(&str) -> Option<f64>;

/// How far a reading of two buffers as one word must lead what the field
/// holds for them before it replaces both. Swept on the sentence harness's
/// recorded taps (`REJOIN_MARGIN`).
pub const REJOIN_MARGIN: f64 = 3.0;

/// The price of reading `tap` as the space bar, relative to the best key for
/// it, as the search prices it.
pub fn bar_price(tap: &Tap, geometry: &Geometry, params: &Params) -> f64 {
    let mut ch = Channel::default();
    ch.push(tap, geometry, params, &|_, _| false);
    ch.space[0]
}

/// Reads two buffers and the one space tap between them as one run of taps,
/// in which that tap may have been a letter that landed on the bar:
/// `feeli g` is *feeling* with its `n` on the bar, `u real` *unreal*. The
/// decoder reads a space tap as a letter only at a buffer's edge, so a word
/// broken in the middle stayed broken.
///
/// `prev` and `cur` are the letters' taps, `bar` the space tap between them,
/// `standing` what the field holds for each, `before` the words before
/// `prev`. What stands is priced as the search would price it: each buffer's
/// reading (or its letters as typed) with the words before it, the bar tap
/// read as a space, and the second word's cost. A reading of all the taps
/// with fewer words than stand replaces them when it leads by `margin`, and
/// is offered past [`OFFER_SHARE`] of that. With `lm`, the context model is
/// blended into every reading, as at the space.
#[allow(clippy::too_many_arguments)]
pub fn rejoin(
    prev: &[Tap],
    bar: Tap,
    cur: &[Tap],
    geometry: &Geometry,
    sources: &[Source],
    context: &dyn Context,
    before: &[&str],
    standing: (&str, &str),
    params: &Params,
    adjacent: &dyn Fn(u16, u16) -> bool,
    mut lm: Option<(f64, ModelSeq)>,
    margin: f64,
) -> Recheck {
    if prev.is_empty() || cur.is_empty() || bar.at.is_none() {
        return Recheck::default();
    }
    let text = |taps: &[Tap]| String::from_utf16_lossy(&taps.iter().map(|t| t.typed).collect::<Vec<_>>());
    let (typed1, typed2) = (text(prev), text(cur));
    // What a buffer's standing text scored when it was read alone.
    let own = |d: &Decoded, typed: &str, standing: &str| -> Option<f64> {
        d.readings
            .iter()
            .find(|r| r.text == standing)
            .map(|r| r.score)
            .into_iter()
            .chain((standing == typed).then_some(d.literal))
            .reduce(f64::max)
    };
    let mut d1 = decode(prev, geometry, sources, context, before, params, adjacent, RECHECK_READINGS);
    let mut after1: Vec<&str> = before.to_vec();
    after1.extend(standing.0.split(' '));
    let mut d2 = decode(cur, geometry, sources, context, &after1, params, adjacent, RECHECK_READINGS);
    let joined: Vec<Tap> = prev.iter().copied().chain(std::iter::once(bar)).chain(cur.iter().copied()).collect();
    let mut d = decode(&joined, geometry, sources, context, before, params, adjacent, RECHECK_READINGS);
    if let Some((weight, seq)) = lm.as_mut() {
        blend(&mut d1, &typed1, *weight, |t| seq(t));
        let head = standing.0.to_string();
        blend(&mut d2, &typed2, *weight, |t| Some(seq(&format!("{head} {t}"))? - seq(&head)?));
        blend(&mut d, &format!("{typed1} {typed2}"), *weight, |t| seq(t));
    }
    let (Some(o1), Some(o2)) = (own(&d1, &typed1, standing.0), own(&d2, &typed2, standing.1)) else {
        return Recheck::default();
    };
    let stands = o1 + o2 + bar_price(&bar, geometry, params) + params.ln_extra_word;
    let words = standing.0.split(' ').count() + standing.1.split(' ').count();
    // A reading with fewer words that keeps every letter typed: one that
    // drops the second buffer's taps as strays (`quest v` as *quest*) is not
    // a word the bar broke but a word deleted.
    let typed_letters = typed1.encode_utf16().count() + typed2.encode_utf16().count();
    let Some(best) = d
        .readings
        .iter()
        .find(|r| r.text.split(' ').count() < words && r.text.encode_utf16().filter(|&c| c != ' ' as u16).count() >= typed_letters)
    else {
        return Recheck::default();
    };
    let lead = best.score - stands;
    if lead >= margin {
        Recheck { apply: Some(best.text.clone()), offer: None, lead }
    } else if lead >= margin * OFFER_SHARE {
        Recheck { apply: None, offer: Some(best.text.clone()), lead }
    } else {
        Recheck { lead, ..Recheck::default() }
    }
}

/// Readings a re-check weighs.
const RECHECK_READINGS: usize = 8;

impl Geometry {
    /// The space bar where a phone keyboard puts it, when the caller has no
    /// rectangle for it: one row below the bottom letter row, from the left
    /// edge of that row's second key to the right edge of its second-last
    /// (QWERTY: x to n, as on Gboard's and AOSP's layouts).
    pub fn with_estimated_space(mut self) -> Geometry {
        if self.space.is_some() || self.keys.len() < 6 {
            return self;
        }
        let bottom = self.keys.iter().map(|k| k.2).fold(f32::MIN, f32::max);
        let above = self.keys.iter().map(|k| k.2).filter(|&y| y < bottom - 0.25).fold(f32::MIN, f32::max);
        if above == f32::MIN {
            return self;
        }
        let pitch = bottom - above;
        let mut row: Vec<f32> = self.keys.iter().filter(|k| (k.2 - bottom).abs() < 0.25).map(|k| k.1).collect();
        row.sort_by(f32::total_cmp);
        if row.len() < 4 {
            return self;
        }
        let left = row[1] - 0.5;
        let right = row[row.len() - 2] + 0.5;
        self.space = Some(((left + right) / 2.0, bottom + pitch, (right - left) / 2.0, pitch / 2.0));
        self
    }
}

/// A Zipf prior from a rank: ln P(word of rank r) in a list of `size` words,
/// `-ln r - ln H(size)`, with H(n) ≈ ln n + γ the harmonic number. Rank 0
/// (unranked) reads as the last.
pub fn zipf(rank: usize, size: usize) -> f64 {
    let size = size.max(2) as f64;
    let r = if rank == 0 { size } else { rank as f64 };
    -r.ln() - (size.ln() + 0.5772).ln()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::predict::WordList;

    fn qwerty() -> Geometry {
        let mut keys = Vec::new();
        for (row, (letters, offset)) in [("qwertyuiop", 0.0f32), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)].iter().enumerate() {
            for (col, ch) in letters.chars().enumerate() {
                keys.push((ch as u16, offset + col as f32, row as f32 * 1.5));
            }
        }
        Geometry { keys, space: Some((4.5, 4.5, 2.5, 0.75)) }
    }

    fn tap(g: &Geometry, ch: char, dx: f32, dy: f32) -> Tap {
        if ch == ' ' {
            return Tap { typed: ' ' as u16, at: Some((4.5 + dx, 4.5 + dy)) };
        }
        let k = g.keys.iter().find(|k| k.0 == ch as u16).unwrap();
        Tap { typed: ch as u16, at: Some((k.1 + dx, k.2 + dy)) }
    }

    fn list() -> WordList {
        let words = [
            ("the", 1000), ("same", 400), ("person", 300), ("what", 600), ("want", 300), ("is", 900),
            ("a", 950), ("of", 980), ("hello", 200), ("help", 250), ("about", 500), ("just", 450),
            ("must", 300), ("to", 990), ("you", 970), ("thee", 5), ("sam", 30), ("per", 40), ("son", 60),
        ];
        WordList::of(&words)
    }

    #[test]
    fn a_hand_that_lands_low_and_wide_is_learned() {
        let g = qwerty();
        let mut hand = TouchOffsets::default();
        assert_eq!(hand.spread_factors(TouchOffsets::K_SPREAD), (1.0, 1.0));
        // Every tap a third of a key low, scattered half a key either way.
        let mut flip = 1.0f32;
        for _ in 0..60 {
            for word in ["the", "same", "person"] {
                let taps: Vec<(f32, f32)> = word
                    .chars()
                    .map(|c| {
                        let k = g.keys.iter().find(|k| k.0 == c as u16).unwrap();
                        flip = -flip;
                        (k.1 + 0.5 * flip, k.2 + 0.33)
                    })
                    .collect();
                hand.learn(word, &taps, &g.keys);
            }
        }
        let (dx, dy) = hand.offset('e' as u16, TouchOffsets::K_GLOBAL, TouchOffsets::K_KEY);
        assert!(dx.abs() < 0.1 && (dy - 0.33).abs() < 0.05, "{dx} {dy}");
        let (fx, fy) = hand.spread_factors(TouchOffsets::K_SPREAD);
        assert!(fx > 1.5 && fy < 1.0, "{fx} {fy}");
        // Taps beyond reach teach nothing.
        let before = hand.taps();
        hand.learn("to", &[(9.0, 9.0), (9.0, 9.0)], &g.keys);
        assert_eq!(hand.taps(), before);
    }

    #[test]
    fn a_session_reads_what_a_fresh_decode_reads() {
        let g = qwerty();
        let list = list();
        let rank = |f: i32| -> f64 { zipf((1000 - f.min(1000)) as usize / 50 + 1, 19) };
        let src = [Source { walker: list.dict(), prior: &rank }];
        let params = Params::default();
        let same = |a: &Decoded, b: &Decoded| {
            assert_eq!(a.readings, b.readings);
            assert_eq!(a.literal.to_bits(), b.literal.to_bits());
            assert_eq!(a.literal_listed, b.literal_listed);
        };
        let mut seed = 0x2545_f491_4f6c_dd1du64;
        let mut jitter = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            ((seed >> 11) as f64 / (1u64 << 53) as f64 - 0.5) as f32 * 0.9
        };
        for text in ["yhebsamebperson", "wnat", "tonthe", "hellp", "abuot", "jsut", "thesame", "  jsut", " hellp", "wnat ", "tonthe  ", " abot  "] {
            let taps: Vec<Tap> = text.chars().map(|c| tap(&g, c, jitter(), jitter())).collect();
            let mut session = Session::default();
            for n in 1..=taps.len() {
                let fresh = decode(&taps[..n], &g, &src, &NoContext, &[], &params, &|_, _| false, 5);
                let kept = session.decode(7, &taps[..n], &g, &src, &NoContext, &[], &params, &|_, _| false, 5);
                same(&fresh, &kept);
            }
            // Backspace, then a different tap: starts over, same answer.
            let mut other = taps.clone();
            other.pop();
            other.push(tap(&g, 'e', 0.1, 0.0));
            let fresh = decode(&other, &g, &src, &NoContext, &[], &params, &|_, _| false, 5);
            same(&fresh, &session.decode(7, &other, &g, &src, &NoContext, &[], &params, &|_, _| false, 5));
        }
        // Typing a word costs about two positions a key, not the buffer.
        let taps: Vec<Tap> = "yhebsamebperson".chars().map(|c| tap(&g, c, 0.0, 0.0)).collect();
        let mut session = Session::default();
        for n in 1..=taps.len() {
            session.decode(1, &taps[..n], &g, &src, &NoContext, &[], &params, &|_, _| false, 5);
        }
        let fresh: usize = (1..=taps.len()).map(|n| n + 1).sum();
        assert!(session.expansions * 3 < fresh, "{} vs {fresh}", session.expansions);
    }

    fn run(list: &WordList, taps: &[Tap]) -> Decoded {
        let size = 19;
        let rank = |f: i32| -> f64 {
            // Toy: rank from frequency order of the list above.
            let r = (1000 - f.min(1000)) as usize / 50 + 1;
            zipf(r, size)
        };
        let src = [Source { walker: list.dict(), prior: &rank }];
        decode(taps, &qwerty(), &src, &NoContext, &[], &Params::default(), &|_, _| false, 5)
    }

    #[test]
    fn three_words_with_letters_for_spaces() {
        let g = qwerty();
        let l = list();
        // yhebsamebperson: y for t, b for the space twice (low taps).
        let mut taps = vec![tap(&g, 'y', -0.45, 0.0), tap(&g, 'h', 0.0, 0.0), tap(&g, 'e', 0.0, 0.0), tap(&g, 'b', 0.1, 0.6)];
        taps.extend("same".chars().map(|c| tap(&g, c, 0.0, 0.0)));
        taps.push(tap(&g, 'b', 0.1, 0.6));
        taps.extend("person".chars().map(|c| tap(&g, c, 0.0, 0.0)));
        let d = run(&l, &taps);
        assert_eq!(d.best().unwrap().text, "the same person");
        assert!(d.best().unwrap().score > d.literal + 5.0);
    }

    #[test]
    fn the_gate_matches_its_fit() {
        // The fitter's own case (`fit_gate.py --rust` prints it), at the
        // three decimals it prints.
        let f = [
            11.702, -2.055, -2.541, -7.290, 0.000, -5.185, -18.403, 1.000, 0.000, 1.000, 0.000, 1.000, 0.000, 0.000,
            5.000, 1.000, 0.000,
        ];
        let p = Gate::probability(&f);
        assert!((p - 0.996183).abs() < 1e-3, "{p}");
        // And the set fitted without the context model (feature 7 is 0).
        let plain = [
            6.755, -2.056, -8.470, 0.000, 0.000, -17.281, 0.000, 0.000, 0.000, 1.000, 0.000, 1.000, 0.000, 0.000, 5.000,
            3.145, 0.000,
        ];
        let p = Gate::probability(&plain);
        assert!((p - 0.936585).abs() < 1e-3, "{p}");
        // The re-check's gate (`fit_recheck.py --rust`).
        let r = [
            2.680, -1.827, -1.473, -8.658, 0.000, -2.900, -11.738, 1.000, 1.000, 1.000, 1.000, 0.000, 1.000, 0.000,
            0.000, 0.000, 0.000, 0.000, 4.000, 8.000, 1.441, 1.000,
        ];
        let p = RecheckGate::probability(&r);
        assert!((p - 0.950985).abs() < 1e-3, "{p}");
        // A decode's features come out in the fitter's order.
        let d = Decoded {
            readings: vec![
                Reading { text: "hello".into(), score: -5.0, edits: 0, slips: 1, spaces: 0, lexical: -3.0, model: -2.0, edge: 0, kinds: [0; 5] },
                Reading { text: "help".into(), score: -6.0, edits: 1, slips: 0, spaces: 0, lexical: -2.5, model: -1.5, edge: 0, kinds: [1, 0, 0, 0, 0] },
            ],
            literal: -16.0,
            literal_listed: false,
            literal_lexical: -15.0,
            literal_model: -9.0,
        };
        let (i, f) = Gate::features(&d, "hellp").unwrap();
        assert_eq!(i, 0);
        assert_eq!(f, [11.0, -2.0, -1.0, -2.0, -1.0, -6.0, -9.0, 1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 5.0, 1.0, 0.0]);
        // The letters as typed reading best: nothing to judge.
        assert!(Gate::features(&d, "hello").is_none());
    }

    #[test]
    fn a_letter_that_landed_on_the_bar() {
        let g = qwerty();
        let l = WordList::of(&[("i", 900), ("in", 950), ("the", 1000), ("all", 700), ("call", 500), ("a", 990)]);
        let rank = |f: i32| zipf(((1000 - f.min(1000)) / 50 + 1) as usize, 6);
        let src = [Source { walker: l.dict(), prior: &rank }];
        let read = |taps: &[Tap]| decode(taps, &g, &src, &NoContext, &[], &Params::default(), &|_, _| false, 4);
        // `in`, the n tapped just onto the bar, then the space: `i` and two spaces.
        let n_low = Tap { typed: ' ' as u16, at: Some((6.5, 3.95)) };
        let space = tap(&g, ' ', 0.0, 0.0);
        let d = read(&[tap(&g, 'i', 0.0, 0.0), n_low, space]);
        let best = d.best().unwrap();
        assert_eq!((best.text.as_str(), best.edge), ("in", 2));
        assert!(best.score > d.literal, "{} vs {}", best.score, d.literal);
        // A tap in the middle of the bar is a space.
        let d = read(&[tap(&g, 'i', 0.0, 0.0), space, space]);
        assert!(d.literal_listed && d.best().is_none_or(|b| b.text == "i" || b.score < d.literal));
        // `call`, the c tapped onto the bar after a space.
        let c_low = Tap { typed: ' ' as u16, at: Some((3.5, 3.95)) };
        let mut taps = vec![space, c_low];
        taps.extend("all".chars().map(|c| tap(&g, c, 0.0, 0.0)));
        let best = read(&taps).best().unwrap().clone();
        assert_eq!((best.text.as_str(), best.edge), ("call", 1));
        // The literal is the letters alone.
        assert_eq!(letters_of("  all "), "all");
    }

    #[test]
    fn a_word_the_bar_broke_in_two() {
        let g = qwerty();
        let l = WordList::of(&[("feeling", 500), ("feel", 400), ("i", 900), ("go", 600), ("the", 1000), ("going", 450)]);
        let rank = |f: i32| zipf(((1000 - f.min(1000)) / 50 + 1) as usize, 6);
        let src = [Source { walker: l.dict(), prior: &rank }];
        let typed = |w: &str| w.chars().map(|c| tap(&g, c, 0.0, 0.0)).collect::<Vec<_>>();
        let none = |_: u16, _: u16| false;
        let run = |a: &str, bar: Tap, b: &str| {
            rejoin(&typed(a), bar, &typed(b), &g, &src, &NoContext, &[], (a, b), &Params::default(), &none, None, REJOIN_MARGIN)
        };
        // `feeling` with its `n` on the top edge of the bar: `feeli g`.
        let n_low = Tap { typed: ' ' as u16, at: Some((6.5, 3.95)) };
        assert_eq!(run("feeli", n_low, "g").apply.as_deref(), Some("feeling"));
        // A reading that drops a buffer's letters as strays is no rejoin.
        assert_eq!(run("the", n_low, "l").apply, None);
        // Two words with a tap in the middle of the bar between them stay two.
        assert_eq!(run("the", tap(&g, ' ', 0.0, 0.0), "go").apply, None);
        // No position for the bar tap: nothing to read.
        assert_eq!(run("feeli", Tap { typed: ' ' as u16, at: None }, "g"), Recheck::default());
    }

    #[test]
    fn a_word_typed_right_stays() {
        let g = qwerty();
        let l = list();
        let taps: Vec<Tap> = "hello".chars().map(|c| tap(&g, c, 0.0, 0.0)).collect();
        let d = run(&l, &taps);
        assert_eq!(d.best().unwrap().text, "hello");
        assert!(d.literal_listed);
    }

    #[test]
    fn a_tap_near_the_boundary_reads_as_the_neighbour() {
        let g = qwerty();
        let l = list();
        // "wjar": the j tapped at the h side, the r at the t side.
        let taps = vec![tap(&g, 'w', 0.0, 0.0), tap(&g, 'j', -0.45, 0.0), tap(&g, 'a', 0.0, 0.0), tap(&g, 'r', 0.45, 0.0)];
        let d = run(&l, &taps);
        assert_eq!(d.best().unwrap().text, "what");
    }

    #[test]
    fn the_next_word_proves_the_last_one_wrong() {
        let g = qwerty();
        let l = WordList::of(&[("i", 900), ("live", 300), ("in", 950), ("on", 940), ("london", 100)]);
        let rank = |f: i32| zipf(((1000 - f.min(1000)) / 50 + 1) as usize, 5);
        let src = [Source { walker: l.dict(), prior: &rank }];
        let bigram = |a: &str, b: &str| match (a, b) {
            ("in", "london") => 5000,
            ("live", "in") | ("live", "on") => 100,
            _ => 0,
        };
        let trigram = |_: &str, _: &str, _: &str| 0;
        let ctx = Pairs { bigram: &bigram, trigram: &trigram, alpha: 1.0, tri: 0.0, cap: 9.0 };
        // `on`, the o tapped toward the i.
        let taps = [tap(&g, 'o', -0.3, 0.0), tap(&g, 'n', 0.0, 0.0)];
        let p = Params::default();
        let none = |_: u16, _: u16| false;
        let run = |after: &[&str], committed: &str| {
            recheck(&taps, &g, &src, &ctx, &["i", "live"], committed, after, &p, &none, None, RECHECK_MARGIN, RECHECK_KNOWN_MARGIN)
        };
        assert_eq!(run(&["london"], "on").apply.as_deref(), Some("in"));
        // Nothing after it yet, or nothing the taps could have been: left be.
        assert_eq!(run(&[], "on"), Recheck::default());
        assert_eq!(run(&["london"], "of").apply, None);
    }

    #[test]
    fn accents_and_capitals_read_on_their_keys() {
        let g = qwerty();
        let l = WordList::of(&[("für", 900), ("Haus", 500), ("hat", 400)]);
        let rank = |f: i32| zipf(((1000 - f.min(1000)) / 50 + 1) as usize, 3);
        let src = [Source { walker: l.dict(), prior: &rank }];
        let read = |w: &str| {
            let taps: Vec<Tap> = w.chars().map(|c| tap(&g, c, 0.0, 0.0)).collect();
            decode(&taps, &g, &src, &NoContext, &[], &Params::default(), &|_, _| false, 3).best().unwrap().text.clone()
        };
        assert_eq!(read("fur"), "für");
        assert_eq!(read("haus"), "Haus");
        assert!(folds_to("está", "esta"));
        assert!(!folds_to("esta", "esta"));
    }

    #[test]
    fn a_swap_and_an_omission() {
        let g = qwerty();
        let l = list();
        let swapped: Vec<Tap> = "wnat".chars().map(|c| tap(&g, c, 0.0, 0.0)).collect();
        assert_eq!(run(&l, &swapped).best().unwrap().text, "want");
        let short: Vec<Tap> = "abot".chars().map(|c| tap(&g, c, 0.0, 0.0)).collect();
        assert_eq!(run(&l, &short).best().unwrap().text, "about");
    }
}
