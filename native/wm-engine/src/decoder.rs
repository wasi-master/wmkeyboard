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
}

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
/// bar, so the default ×4 asks for 0.9. Fitted on Vertanen & Kristensson's
/// dev set and simulated sentences with 40% of the list's words below rank
/// 1000 held out, checked on the test set and other sentences: at 0.9 the
/// gate applied 316 more right fixes than the fixed bars, changed 4 fewer
/// correctly typed words and one fewer held-out word; at 0.8 it applied 502
/// more and changed 14 more correctly typed words.
pub const GATE_CONFIDENCE_SCALE: f64 = 2.25;
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
    pub fn probability(f: &[f64; GATE_FEATURES]) -> f64 {
        let mut z = [0.0; GATE_FEATURES];
        for i in 0..GATE_FEATURES {
            let s = if GATE_SCALE[i] > 0.0 { GATE_SCALE[i] } else { 1.0 };
            z[i] = (f[i] - GATE_MEAN[i]) / s;
        }
        let mut out = GATE_B2;
        for j in 0..GATE_HIDDEN {
            let mut h = GATE_B1[j];
            for i in 0..GATE_FEATURES {
                h += GATE_W1[j][i] * z[i];
            }
            out += GATE_W2[j] * h.tanh();
        }
        1.0 / (1.0 + (-out).exp())
    }

    /// Applies the candidate at `crate::confidence::apply_threshold` of the
    /// slider's `confidence` times [`GATE_CONFIDENCE_SCALE`], offers it at the
    /// offer threshold of the same.
    pub fn verdict(decoded: &Decoded, typed: &str, confidence: f64) -> Verdict {
        let confidence = confidence * GATE_CONFIDENCE_SCALE;
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

// GATE FIT BEGIN
const GATE_MEAN: [f64; GATE_FEATURES] = [13.0477, -3.32291, -1.93191, -4.98418, -0.562198, -5.65504, -17.0694, 1.0, 0.0812767, 1.21134, 0.238236, 1.14898, 0.211336, 0.00370188, 5.70936, 5.47821, 0.0107766];
const GATE_SCALE: [f64; GATE_FEATURES] = [8.26648, 3.3565, 1.32343, 3.37178, 1.16668, 3.25686, 8.15407, 1.0, 0.27326, 0.481654, 0.473905, 0.949411, 0.481654, 0.0607303, 3.12712, 2.67155, 0.103249];
const GATE_W1: [[f64; GATE_FEATURES]; GATE_HIDDEN] = [
    [0.527472, 0.0651673, 0.0906895, 0.0404258, -0.0843969, -0.390606, -0.308375, 4.0886e-93, 0.050597, 0.0400649, -0.0479314, 0.112874, 0.0400649, 0.0210348, -0.11255, 0.0128921, 0.0983211],
    [0.57078, 0.185773, 0.149588, 0.138735, -0.0576366, -0.00761492, -0.40924, 9.91387e-93, -0.0281786, -0.063009, -0.0760009, -0.065663, -0.063009, 0.0190811, -0.213317, -0.0460129, 0.107847],
    [-0.526665, 0.0349676, -0.046067, 0.0165406, 0.0517243, 0.368124, 0.393367, 1.13565e-92, 0.232383, -0.012544, -0.0642603, -0.0987854, -0.012544, -0.0176603, 0.053818, -0.0489796, -0.116406],
    [0.570936, 0.185763, 0.149602, 0.138727, -0.0576921, -0.00771792, -0.409354, 9.82399e-93, -0.028214, -0.0629797, -0.0759857, -0.065594, -0.0629797, 0.0190843, -0.213274, -0.0459495, 0.107846],
    [0.615025, -0.0428025, -0.0242191, -0.0292198, -0.0706812, -0.322874, -0.518057, -5.38232e-93, 0.341736, 0.0229973, 0.0266821, 0.0668562, 0.0229972, 0.0155091, -0.0243632, 0.0575666, 0.0933242],
    [0.526928, 0.0651168, 0.0906637, 0.0404005, -0.0842918, -0.390456, -0.307948, 2.18421e-92, 0.0506005, 0.0400672, -0.0479898, 0.112883, 0.0400672, 0.0210209, -0.112581, 0.0128006, 0.0982994],
    [0.52685, 0.0651103, 0.090659, 0.0403979, -0.0842768, -0.390434, -0.307887, -2.85919e-92, 0.0506035, 0.0400688, -0.0479994, 0.112886, 0.0400688, 0.0210189, -0.112583, 0.0127896, 0.0982956],
    [0.526889, 0.0651139, 0.0906586, 0.0404009, -0.084285, -0.390436, -0.307921, 6.19746e-93, 0.0506028, 0.0400709, -0.0479967, 0.112886, 0.0400708, 0.0210198, -0.112573, 0.0128012, 0.0982948],
];
const GATE_B1: [f64; GATE_HIDDEN] = [1.17195, 1.05663, -0.929857, 1.05731, 1.49486, 1.1702, 1.16996, 1.17011];
const GATE_W2: [f64; GATE_HIDDEN] = [0.915101, 0.938312, -0.915154, 0.938363, 1.06852, 0.914621, 0.914546, 0.914553];
const GATE_B2: f64 = 1.12389;
// GATE FIT END

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

/// Re-reads the taps of the buffer committed as `committed`, now that the
/// words `after` it have landed: "I want to by a car" has `by` wrong once
/// `a car` is in. `before` are the words before the buffer. The taps are
/// decoded as at commit time, then every reading (and the literal) gains
/// what the pairs say about the words after it, and, with `lm`, the context
/// model's say over the reading followed by `after`. What was committed has
/// to be among the readings or be the literal, or there is nothing to
/// compare with.
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
    if after.is_empty() || taps.is_empty() {
        return Recheck::default();
    }
    let typed: String = String::from_utf16_lossy(&taps.iter().map(|t| t.typed).collect::<Vec<_>>());
    let typed = letters_of(&typed).to_string();
    // An apostrophe the keyboard put in ("im" committed as "i'm") is its own
    // rule's call; read again, the taps would price it as a letter left out
    // and hand "in" the win.
    if committed.contains('\'') && !typed.contains('\'') {
        return Recheck::default();
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
    let own = d
        .readings
        .iter()
        .find(|r| r.text == committed)
        .map(|r| r.score)
        .into_iter()
        .chain((committed == typed).then_some(d.literal))
        .reduce(f64::max);
    let Some(own) = own else { return Recheck::default() };
    let Some(best) = d.readings.first() else { return Recheck::default() };
    if best.text == committed {
        return Recheck::default();
    }
    let lead = best.score - own;
    let bar = if committed == typed && d.literal_listed { known_margin } else { margin };
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
        assert!((p - 0.998881).abs() < 1e-3, "{p}");
        // A decode's features come out in the fitter's order.
        let d = Decoded {
            readings: vec![
                Reading { text: "hello".into(), score: -5.0, edits: 0, slips: 1, spaces: 0, lexical: -3.0, model: -2.0, edge: 0 },
                Reading { text: "help".into(), score: -6.0, edits: 1, slips: 0, spaces: 0, lexical: -2.5, model: -1.5, edge: 0 },
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
