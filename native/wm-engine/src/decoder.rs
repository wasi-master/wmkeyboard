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
            ln_far: (0.0005f64).ln(),
            ln_omit: (0.01f64).ln(),
            ln_insert: (0.004f64).ln(),
            ln_double: (0.01f64).ln(),
            ln_swap: (0.01f64).ln(),
            ln_space_omit: (0.004f64).ln(),
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
}

/// Per-tap channel prices: `price[pos][key]` for every letter key, and the
/// space bar's, each relative to the best key for that tap (0 at best).
struct Channel {
    letters: Vec<HashMap<u16, f64>>,
    space: Vec<f64>,
    nearest: Vec<u16>,
}

fn channel(taps: &[Tap], geometry: &Geometry, params: &Params, adjacent: &dyn Fn(u16, u16) -> bool) -> Channel {
    let sx2 = 2.0 * params.sigma_x * params.sigma_x;
    let sy2 = 2.0 * params.sigma_y * params.sigma_y;
    let mut letters = Vec::with_capacity(taps.len());
    let mut space = Vec::with_capacity(taps.len());
    let mut nearest = Vec::with_capacity(taps.len());
    for tap in taps {
        let mut m = HashMap::with_capacity(geometry.keys.len());
        match tap.at {
            Some(p) if !geometry.keys.is_empty() => {
                let mut best = f64::NEG_INFINITY;
                let mut best_key = tap.typed;
                for &(k, cx, cy) in &geometry.keys {
                    let dx = (p.0 - cx) as f64;
                    let dy = (p.1 - cy) as f64;
                    let ll = -(dx * dx) / sx2 - (dy * dy) / sy2;
                    if ll > best {
                        best = ll;
                        best_key = k;
                    }
                    m.insert(k, ll);
                }
                let sp = geometry.space.map_or(f64::NEG_INFINITY, |(cx, cy, hw, hh)| {
                    let dx = ((p.0 - cx).abs() - hw).max(0.0) as f64;
                    let dy = ((p.1 - cy).abs() - hh).max(0.0) as f64;
                    -(dx * dx) / sx2 - (dy * dy) / sy2
                });
                let top = best.max(sp);
                for v in m.values_mut() {
                    *v = (*v - top).max(params.ln_far);
                }
                space.push((sp - top).max(params.ln_far));
                nearest.push(best_key);
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
                space.push(params.ln_far);
                nearest.push(tap.typed);
            }
        }
        letters.push(m);
    }
    Channel { letters, space, nearest }
}

impl Channel {
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
    let n = taps.len();
    let mut out = Decoded::default();
    if n == 0 || sources.is_empty() {
        return out;
    }
    let ch = channel(taps, geometry, params, adjacent);
    let typed: Vec<u16> = taps.iter().map(|t| t.typed).collect();
    let typed_word = String::from_utf16_lossy(&typed);
    let prev_of = |words: &[String], back: usize| -> Option<String> {
        if words.len() >= back {
            Some(words[words.len() - back].clone())
        } else {
            let rest = back - words.len();
            before.len().checked_sub(rest).map(|i| before[i].to_string())
        }
    };
    // ln P(word) + context for a word finishing at `node` of `src`.
    let finish = |s: &State, node: u32, word: &str| -> Option<f64> {
        let src = &sources[s.src];
        if !src.walker.is_word(node) {
            return None;
        }
        let prior = (src.prior)(src.walker.frequency(node));
        let p1 = prev_of(&s.words, 1);
        let p2 = prev_of(&s.words, 2);
        let bonus = context.bonus(p2.as_deref(), p1.as_deref(), word);
        let extra = if s.words.is_empty() { 0.0 } else { params.ln_extra_word };
        Some(prior + bonus + extra)
    };
    let bound = |s: &State| -> f64 {
        let src = &sources[s.src];
        s.score + (src.prior)(src.walker.max_subtree(s.node)) + context.cap()
    };

    let mut beams: Vec<Vec<State>> = vec![Vec::new(); n + 1];
    for (i, src) in sources.iter().enumerate() {
        beams[0].push(State {
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
        });
    }
    let mut kids: Vec<(u16, u32)> = Vec::new();
    let mut grandkids: Vec<(u16, u32)> = Vec::new();
    let mut finals: Vec<Reading> = Vec::new();

    for pos in 0..=n {
        // Prune: merge equal states, keep the best `beam` by bound.
        let mut beam = std::mem::take(&mut beams[pos]);
        {
            let mut seen: HashMap<(usize, u32, String), usize> = HashMap::new();
            let mut merged: Vec<State> = Vec::with_capacity(beam.len());
            for s in beam.drain(..) {
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
            beam = merged;
        }
        let mut ranked: Vec<(f64, State)> = beam.into_iter().map(|s| (bound(&s), s)).collect();
        ranked.sort_by(|a, b| b.0.total_cmp(&a.0));
        ranked.truncate(params.beam);

        for (_, s) in ranked {
            let src = &sources[s.src];
            let walker = src.walker;
            if pos == n {
                // The end of the buffer: the word must be finished here, or
                // one letter short of it.
                if s.cur.is_empty() {
                    continue;
                }
                let word = String::from_utf16_lossy(&s.cur);
                if let Some(add) = finish(&s, s.node, &word) {
                    finals.push(reading(&s, &word, s.score + add, add));
                }
                if s.edits_here < params.max_edits {
                    walker.children_into(s.node, &mut kids);
                    for &(c, child) in &kids {
                        let mut cur = s.cur.clone();
                        cur.push(c);
                        let w = String::from_utf16_lossy(&cur);
                        if let Some(add) = finish(&s, child, &w) {
                            let mut t = s.clone();
                            t.edits += 1;
                            finals.push(reading(&t, &w, s.score + params.ln_omit + add, add));
                        }
                    }
                }
                continue;
            }
            walker.children_into(s.node, &mut kids);
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
                beams[pos + 1].push(t);
            }
            if s.edits_here < params.max_edits {
                // Omission: child `a` was never tapped; the tap meant its
                // child `b`.
                for &(a, child) in &kids_now {
                    walker.children_into(child, &mut grandkids);
                    for &(b, grandchild) in &grandkids {
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
                        beams[pos + 1].push(t);
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
                    beams[pos + 1].push(t);
                }
                // Swap: taps `pos` and `pos + 1` meant the next two letters
                // the other way round.
                if pos + 1 < n {
                    for &(a, child) in &kids_now {
                        walker.children_into(child, &mut grandkids);
                        for &(b, grandchild) in &grandkids {
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
                            beams[pos + 2].push(t);
                        }
                    }
                }
            }
            // A word boundary: finish the word here and start the next.
            if !s.cur.is_empty() && s.words.len() + 1 < params.max_words {
                let word = String::from_utf16_lossy(&s.cur);
                if let Some(add) = finish(&s, s.node, &word) {
                    for (si, next) in sources.iter().enumerate() {
                        let mut base = s.clone();
                        base.words.push(word.clone());
                        base.cur.clear();
                        base.src = si;
                        base.node = next.walker.root();
                        base.edits_here = 0;
                        base.score += add;
                        base.lexical += add;
                        base.spaces += 1;
                        // The tap at `pos` was meant for the space bar.
                        if pos + 1 < n {
                            let mut t = base.clone();
                            t.score += ch.space[pos];
                            beams[pos + 1].push(t);
                        }
                        // No space was tapped: the tap at `pos` starts the
                        // next word.
                        next.walker.children_into(base.node, &mut grandkids);
                        for &(c, child) in &grandkids {
                            let price = ch.letter(pos, c, params);
                            if price <= params.ln_far {
                                continue;
                            }
                            let mut t = base.clone();
                            t.node = child;
                            t.cur.push(c);
                            t.score += params.ln_space_omit + price;
                            beams[pos + 1].push(t);
                        }
                    }
                }
            }
        }
    }

    // The literal reading: listed, or unknown.
    let literal_listed = sources.iter().any(|src| node_of(src.walker, &typed).is_some_and(|nd| src.walker.is_word(nd)));
    let p1 = before.last().copied();
    let p2 = before.len().checked_sub(2).map(|i| before[i]);
    let mut literal = params.ln_unknown_word + params.ln_unknown_letter * n as f64;
    for src in sources {
        if let Some(nd) = node_of(src.walker, &typed) {
            if src.walker.is_word(nd) {
                let listed = (src.prior)(src.walker.frequency(nd)) + context.bonus(p2, p1, &typed_word);
                literal = literal.max(listed);
            }
        }
    }
    let literal_lexical = literal;
    // Spatial price of the literal: every tap on the key it typed, which is
    // the nearest key unless coordinates say otherwise.
    for (pos, &t) in typed.iter().enumerate() {
        literal += ch.letter(pos, t, params);
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

fn reading(s: &State, last: &str, score: f64, last_lexical: f64) -> Reading {
    let mut text = s.words.join(" ");
    if !text.is_empty() {
        text.push(' ');
    }
    text.push_str(last);
    Reading { text, score, edits: s.edits, slips: s.slips, spaces: s.spaces, lexical: s.lexical + last_lexical }
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
        }
    }
    if let Some(l) = seq(typed) {
        let lexical = (1.0 - weight) * decoded.literal_lexical + weight * l;
        decoded.literal += lexical - decoded.literal_lexical;
        decoded.literal_lexical = lexical;
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
