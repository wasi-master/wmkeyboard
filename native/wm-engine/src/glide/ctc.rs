//! `CtcGlideBeam`: glide decoding off a neural model's emissions, a beam
//! search over the dictionary tries, time step by time step, that only ever
//! spells what a trie holds. The Kotlin class documents the scoring; this is
//! its port, step for step, so the two keep the same hypotheses in the same
//! order and score them to the same bit.

use std::collections::HashMap;

use super::keymap::KeyMap;
use super::loops::{is_joiner, is_non_word};
use super::{by_score, Candidate, Marks, Source};
use crate::fuzzy::Tier;
use crate::sort;
use crate::strict_math;

/// a..z, then blank.
pub const CLASSES: usize = 27;
pub const BLANK: usize = 26;

const NO_CHAR: u16 = 0;
/// Next-beam slots per kept hypothesis before it grows.
const EXPANSION: usize = 16;
const MIN_WORD_LENGTH: i32 = 2;
const MAX_WORD: usize = 64;
/// Letters a guess must have drawn before it may be finished, as `GlideBeam`.
const MIN_LOOKAHEAD_PREFIX: i32 = 3;
/// Letters a guess may add, as `GlideBeam`'s longest word.
const MAX_LOOKAHEAD_WORD: usize = 24;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Scoring {
    pub length_norm: f32,
    pub length_bonus: f32,
    pub freq_weight: f32,
    pub prune_norm: f32,
    pub prune_bonus: f32,
    pub width: usize,
    /// Charge per letter a guess adds past what the stroke drew.
    pub look_ahead_cost: f32,
    /// Charge per loop on a key the word does not double, against a rival
    /// spelling that does.
    pub loop_weight: f32,
    /// Weight on the learned-shape benefit (`glide::Decoder::learned_gain`).
    pub shape_weight: f32,
}

impl Default for Scoring {
    fn default() -> Self {
        Scoring {
            length_norm: 0.5949,
            length_bonus: 0.7271,
            freq_weight: 0.166,
            prune_norm: 0.1902,
            prune_bonus: 1.2727,
            width: 100,
            look_ahead_cost: 0.5,
            loop_weight: 2.0,
            shape_weight: 10.0,
        }
    }
}

/// Kotlin's `Float.pow(Float)`: the double `pow`, rounded back.
fn powf(x: f32, y: f32) -> f32 {
    strict_math::pow(x as f64, y as f64) as f32
}

#[derive(Clone, Copy, Default)]
struct Hyp {
    node: u32,
    parent: i64,
    label: u16,
    hop: u16,
    blank: bool,
    depth: i32,
    last: i32,
    score: f32,
    prune: f32,
}

/// `(node << 1 | blank)` to its index in the next beam: open addressing,
/// cleared by generation, as the Kotlin `SlotMap`.
#[derive(Default)]
struct SlotMap {
    keys: Vec<i64>,
    values: Vec<usize>,
    stamps: Vec<i32>,
    stamp: i32,
    mask: usize,
    load: usize,
}

impl SlotMap {
    fn clear(&mut self, expected: usize) {
        let mut size = 64;
        while size < expected * 2 {
            size <<= 1;
        }
        if size > self.keys.len() {
            self.keys = vec![0; size];
            self.values = vec![0; size];
            self.stamps = vec![0; size];
            self.mask = size - 1;
            self.stamp = 0;
        }
        self.stamp += 1;
        self.load = 0;
        if self.stamp == i32::MAX {
            self.stamps.fill(0);
            self.stamp = 1;
        }
    }

    fn hash(key: i64) -> usize {
        let mut x = key.wrapping_mul(-0x61c8864680b583ebi64);
        x ^= ((x as u64) >> 29) as i64;
        (x as i32) as u32 as usize
    }

    fn get(&self, key: i64) -> Option<usize> {
        let mut i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            if self.keys[i] == key {
                return Some(self.values[i]);
            }
            i = (i + 1) & self.mask;
        }
        None
    }

    fn put(&mut self, key: i64, value: usize) {
        let mut i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            if self.keys[i] == key {
                self.values[i] = value;
                return;
            }
            i = (i + 1) & self.mask;
        }
        let load = self.load;
        self.load += 1;
        if load * 2 > self.keys.len() {
            self.grow();
        }
        i = Self::hash(key) & self.mask;
        while self.stamps[i] == self.stamp {
            i = (i + 1) & self.mask;
        }
        self.keys[i] = key;
        self.values[i] = value;
        self.stamps[i] = self.stamp;
    }

    fn grow(&mut self) {
        let old_keys = std::mem::take(&mut self.keys);
        let old_values = std::mem::take(&mut self.values);
        let old_stamps = std::mem::take(&mut self.stamps);
        let live = self.stamp;
        let size = old_keys.len() * 2;
        self.keys = vec![0; size];
        self.values = vec![0; size];
        self.stamps = vec![0; size];
        self.mask = size - 1;
        self.stamp = 1;
        self.load = 0;
        for i in 0..old_keys.len() {
            if old_stamps[i] == live {
                self.put(old_keys[i], old_values[i]);
            }
        }
    }
}

/// What a decode knows of the stroke besides its emissions.
#[derive(Default)]
pub struct Stroke<'a> {
    /// How many guesses may join the readings; 0 for none.
    pub look_ahead: usize,
    /// The marks `glide::Decoder::marks` read in the stroke, and the map
    /// their keys are on.
    pub marks: Option<(Marks, &'a KeyMap)>,
}

/// [`CtcDecoder::walk`]'s answer: every reading and guess, best first.
#[derive(Clone, Debug, Default)]
pub struct CtcRanked {
    pub read: Vec<Candidate>,
    pub guesses: Vec<Candidate>,
    pub look_ahead: usize,
    pub limit: usize,
}

/// The decoder and its pooled state, reused across decodes.
#[derive(Default)]
pub struct CtcDecoder {
    pub scoring: Scoring,
    cur: Vec<Hyp>,
    next: Vec<Hyp>,
    slots: SlotMap,
    /// How each surviving node was spelled from its parent: `(from, hop, label)`.
    spelled: HashMap<u32, (u32, u16, u16)>,
    children: Vec<(u16, u32)>,
    hop_children: Vec<(u16, u32)>,
    order: Vec<usize>,
}

impl CtcDecoder {
    pub fn new(scoring: Scoring) -> CtcDecoder {
        CtcDecoder { scoring, ..Default::default() }
    }

    /// Words for `log_probs` (`steps` rows of [`CLASSES`]) out of `sources`,
    /// best first, at most `limit`. Each source's `min_frequency` is its
    /// vocabulary cap, as the caller read it.
    pub fn decode(&mut self, log_probs: &[f32], steps: usize, sources: &[Source], limit: usize, width: usize) -> Vec<Candidate> {
        let ranked = self.walk(log_probs, steps, sources, limit, width, &Stroke::default());
        self.finish(ranked, &mut |_| 0.0)
    }

    /// The search with what the stroke itself says beside the emissions:
    /// readings and, when `stroke.look_ahead` asks for them, guesses, with
    /// the stroke's loops read as doubled letters, best first and not yet
    /// cut to the limit. [`CtcDecoder::finish`] adds the learned shapes.
    pub fn walk(
        &mut self,
        log_probs: &[f32],
        steps: usize,
        sources: &[Source],
        limit: usize,
        width: usize,
        stroke: &Stroke,
    ) -> CtcRanked {
        let mut ranked = CtcRanked { limit, look_ahead: stroke.look_ahead, ..CtcRanked::default() };
        if limit == 0 || sources.is_empty() || steps == 0 {
            return ranked;
        }
        // A loop closing the stroke says the word ends there.
        let guessing = stroke.look_ahead > 0 && !stroke.marks.as_ref().is_some_and(|(m, _)| m.ends_on_loop);
        let mut best: HashMap<Vec<u16>, Candidate> = HashMap::new();
        let mut ahead: HashMap<Vec<u16>, Candidate> = HashMap::new();
        for src in sources {
            self.search(src, log_probs, steps, width, &mut best, if guessing { Some(&mut ahead) } else { None });
        }
        ranked.read = best.into_values().collect();
        ranked.guesses = ahead.into_values().collect();
        if let Some((marks, keys)) = stroke.marks.as_ref() {
            self.charge_loops(&mut ranked, marks, keys);
        }
        sort::by(&mut ranked.read, by_score);
        sort::by(&mut ranked.guesses, by_score);
        ranked
    }

    /// A loop is the stroke's one way to say "this letter, twice", and it
    /// decides only that: between spellings the stroke cannot otherwise tell
    /// apart because they visit the same keys in the same order ("god",
    /// "good"), the ones that do not double the looped key pay
    /// [`Scoring::loop_weight`] per loop. A word with no such rival pays
    /// nothing, so a curl that was only a corner moves no other word.
    /// A guess is judged on the letters it drew.
    fn charge_loops(&self, ranked: &mut CtcRanked, marks: &Marks, keys: &KeyMap) {
        let weight = self.scoring.loop_weight;
        if weight <= 0.0 || marks.loops.is_empty() {
            return;
        }
        let drawn = |c: &Candidate| c.word[..c.word.len() - c.ahead as usize].to_vec();
        let mut doubled: Vec<(Vec<i32>, i32)> = Vec::new();
        for c in ranked.read.iter().chain(ranked.guesses.iter()) {
            let word = drawn(c);
            for &(own, _) in &marks.loops {
                if Marks::doubles(&word, own, keys) {
                    doubled.push((Marks::visits(&word, keys), own));
                }
            }
        }
        if doubled.is_empty() {
            return;
        }
        for c in ranked.read.iter_mut().chain(ranked.guesses.iter_mut()) {
            let word = drawn(c);
            let visits = Marks::visits(&word, keys);
            let mut cost = 0f32;
            for &(own, score) in &marks.loops {
                if !Marks::doubles(&word, own, keys) && doubled.iter().any(|(v, k)| *k == own && *v == visits) {
                    cost += score;
                }
            }
            if cost > 0.0 {
                c.score -= (weight * cost) as f64;
            }
        }
    }

    /// `ranked` with the learned shapes applied, as `glide::Decoder::finish`
    /// does for `GlideBeam`: `gain` is each reading's learned-shape benefit
    /// (`glide::Decoder::learned_gain`), weighed by [`Scoring::shape_weight`].
    /// Then the readings cut to the limit and the guesses for words not
    /// read, cut to the look-ahead, merged in. No seeding: lifting the words
    /// stored near the stroke cost FUTO Swipe 0.1 point of top-1 on real
    /// swipes.
    pub fn finish(&self, ranked: CtcRanked, gain: &mut dyn FnMut(&[u16]) -> f64) -> Vec<Candidate> {
        let CtcRanked { mut read, guesses, look_ahead, limit } = ranked;
        let weight = self.scoring.shape_weight as f64;
        if weight > 0.0 {
            let mut moved = false;
            for c in read.iter_mut() {
                let g = gain(&c.word);
                if g > 0.0 {
                    c.score += weight * g;
                    moved = true;
                }
            }
            if moved {
                sort::by(&mut read, by_score);
            }
        }
        read.truncate(limit);
        if guesses.is_empty() || look_ahead == 0 {
            return read;
        }
        let read_words: std::collections::HashSet<&Vec<u16>> = read.iter().map(|c| &c.word).collect();
        let mut all: Vec<Candidate> = guesses.into_iter().filter(|g| !read_words.contains(&g.word)).take(look_ahead).collect();
        all.extend(read);
        sort::by(&mut all, by_score);
        all
    }

    fn prune_key(&self, score: f32, depth: i32) -> f32 {
        score / powf(depth.max(1) as f32, self.scoring.prune_norm) + self.scoring.prune_bonus * depth as f32
    }

    fn search(
        &mut self,
        src: &Source,
        lp: &[f32],
        steps: usize,
        width: usize,
        best: &mut HashMap<Vec<u16>, Candidate>,
        mut ahead: Option<&mut HashMap<Vec<u16>, Candidate>>,
    ) {
        let walker = src.walker;
        let min_frequency = src.min_frequency;
        self.spelled.clear();
        self.cur.clear();
        let root = walker.root();
        let prune = self.prune_key(0.0, 0);
        self.cur.push(Hyp { node: root, parent: -1, label: NO_CHAR, hop: NO_CHAR, blank: false, depth: 0, last: -1, score: 0.0, prune });
        let mut children = std::mem::take(&mut self.children);
        let mut hop_children = std::mem::take(&mut self.hop_children);
        for t in 0..steps {
            let row = t * CLASSES;
            let blank = lp[row + BLANK];
            self.next.clear();
            self.slots.clear(width * EXPANSION);
            for h in 0..self.cur.len() {
                let cur = self.cur[h];
                let (node, score, depth) = (cur.node, cur.score, cur.depth);
                self.offer(node, cur.parent, cur.label, cur.hop, true, depth, cur.last, score + blank);
                let last = cur.last;
                if !cur.blank && last >= 0 {
                    self.offer(node, cur.parent, cur.label, cur.hop, false, depth, last, score + lp[row + last as usize]);
                }
                walker.children_into(node, &mut children);
                for &(label, child) in children.iter() {
                    if min_frequency > 0 && walker.max_subtree(child) < min_frequency {
                        continue;
                    }
                    let cls = class_of(label);
                    if cls >= 0 {
                        self.offer(child, node as i64, label, NO_CHAR, false, depth + 1, cls, score + lp[row + cls as usize]);
                    } else if depth > 0 && is_joiner(label as i32) {
                        walker.children_into(child, &mut hop_children);
                        for &(hop_label, grandchild) in hop_children.iter() {
                            let hop_cls = class_of(hop_label);
                            if hop_cls < 0 {
                                continue;
                            }
                            if min_frequency > 0 && walker.max_subtree(grandchild) < min_frequency {
                                continue;
                            }
                            self.offer(grandchild, node as i64, hop_label, label, false, depth + 1, hop_cls, score + lp[row + hop_cls as usize]);
                        }
                    }
                }
            }
            self.prune(width);
            if self.cur.is_empty() {
                break;
            }
        }
        self.children = children;
        self.hop_children = hop_children;
        if self.cur.is_empty() {
            return;
        }
        for h in 0..self.cur.len() {
            let cur = self.cur[h];
            if !walker.is_word(cur.node) || cur.depth < MIN_WORD_LENGTH {
                continue;
            }
            let frequency = walker.frequency(cur.node);
            if frequency < min_frequency {
                continue;
            }
            let Some(word) = self.word(cur.node) else { continue };
            if src.tier == Tier::Dictionary && is_non_word(&word) {
                continue;
            }
            let length = cur.depth as f32;
            let path = cur.score / powf(length, self.scoring.length_norm);
            let score = path
                + self.scoring.length_bonus * length
                + self.scoring.freq_weight * (src.log_weight + src.scale.ln1p(frequency)) as f32;
            let better = match best.get(&word) {
                None => true,
                Some(existing) => existing.score < score as f64,
            };
            if better {
                best.insert(
                    word.clone(),
                    Candidate { word, score: score as f64, shape_cost: -(path as f64), tier: src.tier, ahead: 0 },
                );
            }
        }
        if let Some(ahead) = ahead.as_deref_mut() {
            for h in 0..self.cur.len() {
                let cur = self.cur[h];
                if cur.depth >= MIN_LOOKAHEAD_PREFIX {
                    self.look_ahead(src, cur, ahead);
                }
            }
        }
    }

    /// The commonest word under a surviving prefix, when it is commoner than
    /// the prefix as a word of its own: what the stroke would spell if it
    /// went on, `GlideBeam`'s look-ahead on this decoder's scores. Its path
    /// is the prefix's, flattened and paid for by the letters drawn; each
    /// letter it adds pays [`Scoring::look_ahead_cost`] instead of earning
    /// the length bonus.
    fn look_ahead(&mut self, src: &Source, cur: Hyp, ahead: &mut HashMap<Vec<u16>, Candidate>) {
        let walker = src.walker;
        let node = cur.node;
        let best = walker.max_subtree(node);
        if best < src.min_frequency || best <= walker.frequency(node) {
            return;
        }
        let Some(mut word) = self.word(node) else { return };
        let drawn = word.len();
        let mut at = node;
        let mut children = std::mem::take(&mut self.children);
        while word.len() < MAX_LOOKAHEAD_WORD {
            if walker.is_word(at) && walker.frequency(at) == best {
                break;
            }
            walker.children_into(at, &mut children);
            let Some(&(label, child)) = children.iter().find(|&&(_, c)| walker.max_subtree(c) == best) else {
                self.children = children;
                return;
            };
            word.push(label);
            at = child;
        }
        self.children = children;
        if word.len() <= drawn || !walker.is_word(at) {
            return;
        }
        if src.tier == Tier::Dictionary && is_non_word(&word) {
            return;
        }
        let extra = (word.len() - drawn) as i32;
        let length = cur.depth as f32;
        let path = cur.score / powf(length, self.scoring.length_norm);
        let score = path + self.scoring.length_bonus * length
            + self.scoring.freq_weight * (src.log_weight + src.scale.ln1p(best)) as f32
            - self.scoring.look_ahead_cost * extra as f32;
        let better = match ahead.get(&word) {
            None => true,
            Some(existing) => existing.score < score as f64,
        };
        if better {
            ahead.insert(word.clone(), Candidate { word, score: score as f64, shape_cost: -(path as f64), tier: src.tier, ahead: extra });
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn offer(&mut self, node: u32, parent: i64, label: u16, hop: u16, blank: bool, depth: i32, last: i32, score: f32) {
        let key = ((node as i64) << 1) | blank as i64;
        if let Some(at) = self.slots.get(key) {
            if self.next[at].score < score {
                let prune = self.prune_key(score, depth);
                let h = &mut self.next[at];
                h.parent = parent;
                h.label = label;
                h.hop = hop;
                h.depth = depth;
                h.last = last;
                h.score = score;
                h.prune = prune;
            }
            return;
        }
        self.slots.put(key, self.next.len());
        let prune = self.prune_key(score, depth);
        self.next.push(Hyp { node, parent, label, hop, blank, depth, last, score, prune });
    }

    fn prune(&mut self, width: usize) {
        let n = self.next.len();
        self.order.clear();
        self.order.extend(0..n);
        let keep = n.min(width);
        if n > width {
            self.select(n, width);
        }
        self.cur.clear();
        for k in 0..keep {
            let h = self.next[self.order[k]];
            self.cur.push(h);
            if h.parent >= 0 {
                self.spelled.entry(h.node).or_insert((h.parent as u32, h.hop, h.label));
            }
        }
    }

    /// The `k` highest pruning keys among the first `n` of `order` to its
    /// front: the Kotlin quickselect, swap for swap.
    fn select(&mut self, n: usize, k: usize) {
        let prune = |order: &[usize], next: &[Hyp], i: usize| next[order[i]].prune;
        let (mut lo, mut hi) = (0i64, n as i64 - 1);
        let k = k as i64;
        while lo < hi {
            let pivot = prune(&self.order, &self.next, ((lo + hi) as u64 >> 1) as usize);
            let (mut i, mut j) = (lo, hi);
            while i <= j {
                while prune(&self.order, &self.next, i as usize) > pivot {
                    i += 1;
                }
                while prune(&self.order, &self.next, j as usize) < pivot {
                    j -= 1;
                }
                if i <= j {
                    self.order.swap(i as usize, j as usize);
                    i += 1;
                    j -= 1;
                }
            }
            if k - 1 <= j {
                hi = j;
            } else if k - 1 >= i {
                lo = i;
            } else {
                return;
            }
        }
    }

    /// The word a surviving node spells, from the recorded steps.
    fn word(&self, node: u32) -> Option<Vec<u16>> {
        let mut out: Vec<u16> = Vec::new();
        let mut at = node;
        let mut guard = 0;
        while let Some(&(from, hop, label)) = self.spelled.get(&at) {
            out.push(label);
            if hop != 0 {
                out.push(hop);
            }
            at = from;
            guard += 1;
            if guard > MAX_WORD {
                return None;
            }
        }
        if out.is_empty() {
            return None;
        }
        out.reverse();
        Some(out)
    }
}

/// a..z for a letter of either case, -1 for anything the model has no class for.
fn class_of(label: u16) -> i32 {
    let c = if (b'A' as u16..=b'Z' as u16).contains(&label) { label + (b'a' - b'A') as u16 } else { label };
    if (b'a' as u16..=b'z' as u16).contains(&c) {
        (c - b'a' as u16) as i32
    } else {
        -1
    }
}
