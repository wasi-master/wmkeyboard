//! The glide decoder: the Kotlin `GlideBeam` (core/gesture), line for line.
//! A best-first lattice over the dictionary trie itself; see the Kotlin
//! class for the model (the alignment, the bound, the loops and pauses, the
//! shape channel), which is kept there with its measurements.
//!
//! Arithmetic follows the Kotlin types exactly: `Float` is `f32`, `Double`
//! is `f64`, the same operations in the same order, logarithms through
//! [`strict_math`], so a decode returns the same candidates with the same
//! scores to the bit. The user's learned shapes are passed in by the caller
//! ([`LearnedShapes`]); the vocabulary cap arrives as each source's minimum
//! frequency, which the caller reads off its own rank tables.

pub mod ctc;
pub mod duet;
pub mod keymap;
pub mod loops;
pub mod workspace;

use crate::fuzzy::{Tier, Walker};
use crate::kernels::band;
use crate::sort;
use crate::strict_math;
use keymap::KeyMap;
use loops::{is_joiner, is_non_word, LoopScan};
use std::collections::HashMap;
use workspace::{Workspace, MAX_IDEAL_POINTS, NO_LABEL, SAMPLE_POINTS, SHAPE_POINTS, UNREACHABLE};

/// A touch point in pixels, with its time in milliseconds.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Point {
    pub x: f32,
    pub y: f32,
    pub t: i64,
}

/// `GlideBeam.Tuning`. Defaults are the shipped ones, fitted on real swipes
/// (`examples/glide_beam_eval`, `native/bench/futo-swipe/fit_glide.py`).
/// `freq_weight` is the weight on `ln(1 + frequency)`, the one field a
/// language profile moves (#966, `GlideLanguageProfiles` on the Kotlin
/// side): every bound carries it too, so the walk's pruning stays sound
/// at any value, and at 1.0 a decode is the unweighted one to the bit.
/// `join_cost` is the charge, in nats, for each join of a compound read
/// as two or three listed words in a row (#966 §5.2); infinite, the
/// default, switches the joining off and the decode is the plain one to
/// the bit. A language profile sets it where the list cannot hold the
/// compounds people swipe. `join_min_part` is the letters every part must
/// have, and `join_part_penalty` the nats each part a join goes on from
/// pays per `ln(1 + frequency)` of its own: the guard that keeps two common
/// short words (`hast` + `alt`) from outbidding the listed `haushalt`
/// (#966, measured on 2,588 German swipes at 8 / 4 / 0.1). At their
/// defaults, 3 and 0, a joined decode is the one before them to the bit.
#[derive(Clone, Debug, PartialEq)]
pub struct Tuning {
    pub sigma: f32,
    pub max_point_cost: f32,
    pub gap_weight: f32,
    pub gap_window: f32,
    pub shape_weight: f64,
    pub freq_weight: f64,
    pub repeat_cost: f32,
    pub unlooped_repeat: f32,
    pub dwell_full: f32,
    pub unclaimed_dwell: f32,
    pub loop_extent: f32,
    pub loop_min_arc: f32,
    pub loop_radius: f32,
    pub wiggle_extent: f32,
    pub wiggle_weight: f32,
    pub loop_exclusion: f32,
    pub unclaimed_loop: f32,
    pub learned_shape_gain: f64,
    pub shape_seeding: bool,
    pub shape_channel: f64,
    pub start_radius: f32,
    pub end_radius: f32,
    pub near_radius: f32,
    pub vocabulary_rank: i32,
    pub look_ahead_cost: f64,
    pub look_ahead_budget: i32,
    pub joiner_cost: f32,
    pub join_cost: f64,
    pub join_min_part: i32,
    pub join_part_penalty: f64,
}

impl Default for Tuning {
    fn default() -> Self {
        Tuning {
            sigma: 0.42,
            max_point_cost: 18.0,
            gap_weight: 1.33,
            gap_window: 32.0,
            shape_weight: 0.5,
            freq_weight: 1.0,
            repeat_cost: 0.8,
            unlooped_repeat: 0.1,
            dwell_full: 2.0,
            unclaimed_dwell: 0.9,
            loop_extent: 0.8,
            loop_min_arc: 1.0,
            loop_radius: 0.6,
            wiggle_extent: 0.0,
            wiggle_weight: 0.0,
            loop_exclusion: 1.5,
            unclaimed_loop: 1.1,
            learned_shape_gain: 0.15,
            shape_seeding: false,
            shape_channel: 6.0,
            start_radius: 1.6,
            end_radius: 2.0,
            near_radius: 1.9,
            vocabulary_rank: 0,
            look_ahead_cost: 1.4,
            look_ahead_budget: 48,
            joiner_cost: 0.2,
            join_cost: f64::INFINITY,
            join_min_part: MIN_JOIN_PART,
            join_part_penalty: 0.0,
        }
    }
}

const MIN_DWELL_FULL: f32 = 0.1;
/// `GlideBeam.Tuning.SLACK_BASE`: the anchor radius past which the score
/// forgives the distance as well as the gate admitting it.
const SLACK_BASE: f32 = 1.6;

impl Tuning {
    fn inv_two_sigma_sq(&self) -> f32 {
        1.0 / (2.0 * self.sigma * self.sigma)
    }
    fn start_radius_sq(&self) -> f32 {
        self.start_radius * self.start_radius
    }
    fn end_radius_sq(&self) -> f32 {
        self.end_radius * self.end_radius
    }
    fn near_cost(&self) -> f32 {
        self.near_radius * self.near_radius * self.inv_two_sigma_sq()
    }
    fn loop_radius_sq(&self) -> f32 {
        self.loop_radius * self.loop_radius
    }
    fn start_slack(&self) -> f32 {
        (self.start_radius - SLACK_BASE).max(0.0)
    }
    fn end_slack(&self) -> f32 {
        (self.end_radius - SLACK_BASE).max(0.0)
    }
    fn dwell_scale(&self) -> f32 {
        if self.dwell_full > MIN_DWELL_FULL {
            self.dwell_full
        } else {
            MIN_DWELL_FULL
        }
    }
    /// What `joins` joins cost: nothing for none, which keeps a plain
    /// word's score the plain one even with the joining off (infinite
    /// times zero is not zero).
    fn join_charge(&self, joins: i32) -> f64 {
        if joins > 0 {
            self.join_cost * joins as f64
        } else {
            0.0
        }
    }
}

/// `GlideBeam.Candidate`, the word in UTF-16.
#[derive(Clone, Debug, PartialEq)]
pub struct Candidate {
    pub word: Vec<u16>,
    pub score: f64,
    pub shape_cost: f64,
    pub tier: Tier,
    pub ahead: i32,
}

/// `GlideBeam.Alignment`.
#[derive(Clone, Debug, PartialEq)]
pub struct Alignment {
    pub keys: Vec<i32>,
    pub x: Vec<f32>,
    pub y: Vec<f32>,
    pub samples: Vec<i32>,
    pub chars: Vec<i32>,
    pub sample_count: i32,
}

/// One weighted trie for the decoder, with the vocabulary cap as the least
/// frequency a word in it may have (0 for none).
pub struct Source<'a> {
    pub walker: &'a dyn Walker,
    pub log_weight: f64,
    pub tier: Tier,
    pub min_frequency: i32,
}

/// The user's own drawn shapes per word, quantised as the shape store keeps
/// them; empty for a word they have never glided.
pub type LearnedShapes = HashMap<Vec<u16>, Vec<Vec<i8>>>;

/// The marks [`Decoder::marks`] reads in a stroke that a decoder which does
/// not walk the stroke itself can use: each loop as its key and score (a
/// doubled letter there), and whether the stroke closes on a loop (the word
/// ends there).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Marks {
    pub loops: Vec<(i32, f32)>,
    pub ends_on_loop: bool,
}

impl Marks {
    /// The keys `word` visits in order, a letter typed twice in a row
    /// visiting its key once: what a stroke can tell apart. -1 for a letter
    /// the map has no key for.
    pub fn visits(word: &[u16], keys: &KeyMap) -> Vec<i32> {
        let mut out: Vec<i32> = Vec::new();
        for (cp, _) in code_points(word) {
            let key = keys.key_index(cp);
            if out.last() != Some(&key) || key < 0 {
                out.push(key);
            }
        }
        out
    }

    /// Whether `word` types a letter twice in a row on `key`.
    pub fn doubles(word: &[u16], key: i32, keys: &KeyMap) -> bool {
        let letters = code_points(word);
        letters.windows(2).any(|w| w[0].0 == w[1].0 && keys.key_index(w[1].0) == key)
    }
}

/// Letters each part of a compound must have unless the tuning asks for
/// more (#966): `an` + `halt` is not a reading of `anhalt`, it is a search
/// of every short word against every other, and the charge alone would not
/// keep that out of the pool. `Tuning::join_min_part`'s default.
const MIN_JOIN_PART: i32 = 3;
/// Most joins in one reading: three parts, `bundes`-`verfassungs`-`gericht`
/// sized, and no further.
const MAX_JOINS: i32 = 2;
const MIN_STROKE_LENGTH: f32 = 0.5;
const MIN_SAMPLES: usize = 3;
const MIN_WORD_LENGTH: i32 = 2;
const MIN_LOOKAHEAD_PREFIX: i32 = 3;
/// Letters the walk spells at most. 24 until issue #966: a 26-letter
/// compound ("Qualitätsmanagementsystem") could not be read at all.
/// `MAX_IDEAL_POINTS` follows it.
pub const MAX_WORD_LENGTH: i32 = 32;
const MIN_SHAPE_SPREAD: f32 = 1e-4;
const SHAPE_BYTE_LIMIT: i32 = 127;
const DWELL_RADIUS: f32 = 0.8;
const DWELL_TIE: f32 = 0.25;
const END_LOOP_REACH: f32 = 0.5;
pub const MAX_POPS: usize = 2000;
pub const RESULT_K: usize = 8;
const EPS: f64 = 1e-9;
/// `GlideShapeStore.QUANT`.
pub const SHAPE_QUANT: f32 = 10.0;

fn ln1p(v: i32) -> f64 {
    strict_math::ln1p_count(v)
}

/// The frequency a compound's language-model term is read at: the least
/// of its finished parts' (`cap`, `workspace::NO_PART` for none) and `f`, the current
/// part's or its subtree's. `f` itself for a plain word, so the plain
/// decode's arithmetic is untouched.
fn lm_cap(cap: i32, f: i32) -> i32 {
    if f < cap {
        f
    } else {
        cap
    }
}

fn by_score(a: &Candidate, b: &Candidate) -> std::cmp::Ordering {
    b.score.total_cmp(&a.score).then_with(|| a.word.cmp(&b.word))
}

fn is_high(u: u16) -> bool {
    (0xD800..0xDC00).contains(&u)
}

fn is_low(u: u16) -> bool {
    (0xDC00..0xE000).contains(&u)
}

/// The code points of a UTF-16 word with each one's starting offset,
/// unpaired surrogates as themselves, as `codePointAt` walks it.
fn code_points(word: &[u16]) -> Vec<(i32, usize)> {
    let mut out = Vec::with_capacity(word.len());
    let mut at = 0;
    while at < word.len() {
        let u = word[at];
        if is_high(u) && at + 1 < word.len() && is_low(word[at + 1]) {
            let cp = 0x10000 + (((u as i32) - 0xD800) << 10) + ((word[at + 1] as i32) - 0xDC00);
            out.push((cp, at));
            at += 2;
        } else {
            out.push((u as i32, at));
            at += 1;
        }
    }
    out
}

/// What [`Decoder::decode_ranked`] leaves for [`Decoder::finish`]: the
/// readings and the guesses before the shape channel.
pub struct Ranked {
    pub read: Vec<Candidate>,
    pub guesses: Vec<Candidate>,
    pub look_ahead: usize,
    pub limit: usize,
}

pub struct Decoder {
    pub tuning: Tuning,
    pub ws: Workspace,
    scan: LoopScan,
    /// How many of the path's leading samples are the walk up from the
    /// spacebar (#968): a chained stroke's later word starts at the bar's
    /// edge, not on a key. The first letter may stand anywhere on them and
    /// the ones before it cost nothing. `0` for a stroke that started on a
    /// key. Set before `decode`, read by every decode until changed.
    pub open_head: usize,
    /// The same for the trailing samples, the walk down to the bar from a
    /// chained stroke's earlier word.
    pub open_tail: usize,
}

impl Decoder {
    pub fn new(tuning: Tuning) -> Decoder {
        Decoder { tuning, ws: Workspace::new(), scan: LoopScan::default(), open_head: 0, open_tail: 0 }
    }

    /// `decode`, all of it: when the caller has its learned shapes at hand.
    #[allow(clippy::too_many_arguments)]
    pub fn decode(
        &mut self,
        path: &[Point],
        keys: &KeyMap,
        key_width: f32,
        sources: &[Source],
        limit: usize,
        shapes: Option<&LearnedShapes>,
        look_ahead: usize,
    ) -> Vec<Candidate> {
        match self.decode_ranked(path, keys, key_width, sources, limit, look_ahead) {
            None => Vec::new(),
            Some(ranked) => self.finish(ranked, keys, shapes, None),
        }
    }

    /// The walk, up to the shape channel: the ranked readings and guesses,
    /// or `None` when the stroke or the request is not a decode at all. The
    /// workspace keeps what [`Decoder::finish`] needs, so the two are called
    /// in turn on the same decoder.
    pub fn decode_ranked(
        &mut self,
        path: &[Point],
        keys: &KeyMap,
        key_width: f32,
        sources: &[Source],
        limit: usize,
        look_ahead: usize,
    ) -> Option<Ranked> {
        if path.len() < MIN_SAMPLES || key_width <= 0.0 {
            return None;
        }
        if keys.key_count() == 0 || sources.is_empty() || limit == 0 {
            return None;
        }
        if !self.resample(path, key_width) {
            return None;
        }
        self.ws.prepare_keys(keys.key_count());
        self.build_costs(keys);
        self.build_dwell(keys);
        if self.ws.arc_step <= 0.0 {
            return None;
        }
        self.build_loops(path, key_width, keys);
        self.build_remain(keys);

        let k = (limit * 2).max(RESULT_K);
        let mut results: HashMap<Vec<u16>, Candidate> = HashMap::new();
        let mut ahead: Option<HashMap<Vec<u16>, Candidate>> =
            if look_ahead > 0 && !self.ends_on_loop() { Some(HashMap::new()) } else { None };
        let mut budget = self.tuning.look_ahead_budget;
        let mut floor = f64::NEG_INFINITY;
        let mut ordered: Vec<&Source> = sources.iter().collect();
        let fw = self.tuning.freq_weight;
        let root_bound = |s: &Source| s.log_weight + fw * ln1p(s.walker.max_subtree(s.walker.root()));
        sort::by(&mut ordered, |a, b| root_bound(b).total_cmp(&root_bound(a)));
        for src in ordered {
            if root_bound(src) < floor - EPS {
                continue;
            }
            floor = self.search_one(src, keys, k, &mut results, floor, ahead.as_mut(), &mut budget);
        }
        let mut read: Vec<Candidate> = results.into_values().collect();
        sort::by(&mut read, by_score);
        let guesses = ahead.map(|a| a.into_values().collect()).unwrap_or_default();
        Some(Ranked { read, guesses, look_ahead, limit })
    }

    /// The shape channel over a [`Ranked`] walk, the learned-shape seeding,
    /// and the guesses merged in. `seeds` are the words whose stored shapes
    /// sit near the drawn one, nearest first, when seeding is on: the store
    /// that finds them is the caller's, so the caller asks it (with
    /// [`Decoder::sample_shape`] of the same stroke) and passes the answer.
    pub fn finish(
        &mut self,
        ranked: Ranked,
        keys: &KeyMap,
        shapes: Option<&LearnedShapes>,
        seeds: Option<&[Vec<u16>]>,
    ) -> Vec<Candidate> {
        let Ranked { read, guesses, look_ahead, limit } = ranked;
        let mut read = self.rescore_shape(read, keys, shapes);
        read.truncate(limit);
        if let Some(near) = seeds.filter(|n| !n.is_empty()) {
            let top = read.iter().map(|c| c.score).reduce(f64::max).unwrap_or(0.0);
            let had: std::collections::HashSet<Vec<u16>> = read.iter().map(|c| c.word.clone()).collect();
            for c in read.iter_mut() {
                if near.contains(&c.word) {
                    c.score = c.score.max(top);
                }
            }
            for word in near {
                if !had.contains(word) {
                    read.push(Candidate { word: word.clone(), score: top, shape_cost: 0.0, tier: Tier::Dictionary, ahead: 0 });
                }
            }
            sort::by(&mut read, by_score);
            read.truncate(limit);
        }
        if guesses.is_empty() {
            return read;
        }
        let read_words: std::collections::HashSet<&Vec<u16>> = read.iter().map(|c| &c.word).collect();
        let guesses: Vec<Candidate> = guesses.into_iter().filter(|g| !read_words.contains(&g.word)).collect();
        let mut completions = self.rescore_shape(guesses, keys, shapes);
        completions.truncate(look_ahead);
        let mut all = read;
        all.extend(completions);
        sort::by(&mut all, by_score);
        all
    }

    /// `sampleShape`: the stroke as the shape store keeps one.
    pub fn sample_shape(&mut self, path: &[Point], key_width: f32) -> Option<Vec<i8>> {
        if path.len() < MIN_SAMPLES || key_width <= 0.0 {
            return None;
        }
        if !self.resample(path, key_width) {
            return None;
        }
        self.drawn_shape();
        let mut out = vec![0i8; 2 * SHAPE_POINTS];
        quantise(&self.ws.drawn_shape_x, &self.ws.drawn_shape_y, &mut out);
        Some(out)
    }

    /// The drawn stroke quantised, after a decode, for the learned-shape
    /// seeding the caller does.
    pub fn drawn_shape8(&mut self) -> Vec<i8> {
        self.drawn_shape();
        let mut out = vec![0i8; 2 * SHAPE_POINTS];
        quantise(&self.ws.drawn_shape_x, &self.ws.drawn_shape_y, &mut out);
        out
    }

    /// `marks`: the loops `decode` would read in the stroke, found exactly as
    /// it finds them, for a decoder that does not walk the stroke itself
    /// (`ctc`). `None` when `decode` would not read the stroke at all.
    pub fn marks(&mut self, path: &[Point], keys: &KeyMap, key_width: f32) -> Option<Marks> {
        if path.len() < MIN_SAMPLES || key_width <= 0.0 || keys.key_count() == 0 {
            return None;
        }
        if !self.resample(path, key_width) {
            return None;
        }
        self.ws.prepare_keys(keys.key_count());
        self.build_costs(keys);
        self.build_dwell(keys);
        if self.ws.arc_step <= 0.0 {
            return None;
        }
        self.build_loops(path, key_width, keys);
        let mut marks = Marks { ends_on_loop: self.ends_on_loop(), ..Marks::default() };
        for e in 0..self.ws.loop_count {
            if self.ws.loop_score[e] > 0.0 && self.ws.loop_key[e] >= 0 {
                marks.loops.push((self.ws.loop_key[e], self.ws.loop_score[e]));
            }
        }
        Some(marks)
    }

    /// The stroke resampled as the shape channel reads it, ready for
    /// [`Decoder::learned_gain`]; false when it is too short to have a shape.
    pub fn prepare_shape(&mut self, path: &[Point], key_width: f32) -> bool {
        if path.len() < MIN_SAMPLES || key_width <= 0.0 || !self.resample(path, key_width) {
            return false;
        }
        self.drawn_shape();
        normalise_in_place(&mut self.ws.drawn_shape_x, &mut self.ws.drawn_shape_y);
        self.plain_shape(false);
        true
    }

    /// How much nearer the stroke [`Decoder::prepare_shape`] took sits to
    /// `word` as the user has drawn it before than to the textbook path
    /// through its keys: the shape channel's own learned-shape benefit, in
    /// its normalised distance units, 0 for a word with no stored shapes and
    /// never more than `learned_shape_gain`.
    pub fn learned_gain(&mut self, word: &[u16], keys: &KeyMap, shapes: &LearnedShapes) -> f64 {
        if self.tuning.learned_shape_gain <= 0.0 || shapes.get(word).is_none_or(|s| s.is_empty()) {
            return 0.0;
        }
        let Some(ideal) = self.shape_distance(word, keys) else { return 0.0 };
        ideal - self.learned_distance(word, ideal, Some(shapes))
    }

    /// `align`: where `word`'s keys landed along the stroke.
    pub fn align(&mut self, word: &[u16], path: &[Point], keys: &KeyMap, key_width: f32) -> Option<Alignment> {
        if path.len() < MIN_SAMPLES || key_width <= 0.0 || keys.key_count() == 0 {
            return None;
        }
        let mut visits: Vec<usize> = Vec::new();
        let mut starts: Vec<i32> = Vec::new();
        let mut previous = -1;
        for (cp, start) in code_points(word) {
            let key = keys.key_index(cp);
            if key < 0 {
                if is_joiner(cp) {
                    continue;
                }
                return None;
            }
            if key == previous {
                continue;
            }
            if visits.len() >= MAX_IDEAL_POINTS {
                return None;
            }
            visits.push(key as usize);
            starts.push(start as i32);
            previous = key;
        }
        let count = visits.len();
        if count < 2 {
            return None;
        }
        if !self.resample(path, key_width) {
            return None;
        }
        if self.ws.arc_step <= 0.0 {
            return None;
        }
        self.ws.prepare_keys(keys.key_count());
        self.build_costs(keys);
        self.build_loops(path, key_width, keys);

        let n = self.ws.samples;
        let key_count = keys.key_count();
        let step = self.ws.arc_step;
        let mut back = vec![0i32; count * n];
        let mut prev = vec![0f32; n];
        let mut cur = vec![0f32; n];
        prev[0] = self.ws.point_cost[visits[0]];
        for v in prev.iter_mut().skip(1) {
            *v = UNREACHABLE;
        }
        for m in 1..count {
            let key = visits[m];
            let span = keys.distance(visits[m - 1], key);
            let expected = span / step;
            let lo = 1.max((expected - self.tuning.gap_window) as i32);
            let hi = lo.max((expected + self.tuning.gap_window) as i32 + 1) + self.ws.collapsed_samples;
            let lead = self.anchor_lead(0, visits[m - 1] as i32, self.tuning.start_slack(), keys);
            let tail = self.anchor_lead(n - 1, key as i32, self.tuning.end_slack(), keys);
            cur[0] = UNREACHABLE;
            for j in 1..n {
                let mut best = UNREACHABLE;
                let mut from: i32 = -1;
                let arc_j = self.ws.arc_at[j];
                let ease_j = if j == n - 1 { tail } else { 0.0 };
                let mut i = 0.max(j as i32 - hi);
                let to = j as i32 - lo;
                while i <= to {
                    let before = prev[i as usize];
                    if before < UNREACHABLE {
                        let gap = arc_j - self.ws.arc_at[i as usize] - span;
                        let v = before + self.gap_charge(gap, if i == 0 { lead + ease_j } else { ease_j });
                        if v < best {
                            best = v;
                            from = i;
                        }
                    }
                    i += 1;
                }
                cur[j] = if from < 0 { UNREACHABLE } else { best + self.ws.point_cost[j * key_count + key] };
                back[m * n + j] = from;
            }
            std::mem::swap(&mut prev, &mut cur);
        }
        if prev[n - 1] >= UNREACHABLE {
            return None;
        }
        let mut x = vec![0f32; count];
        let mut y = vec![0f32; count];
        let mut samples = vec![0i32; count];
        let mut j = n - 1;
        for m in (0..count).rev() {
            x[m] = self.ws.path_x[j];
            y[m] = self.ws.path_y[j];
            samples[m] = j as i32;
            if m > 0 {
                j = back[m * n + j] as usize;
            }
        }
        Some(Alignment {
            keys: visits.iter().map(|&k| k as i32).collect(),
            x,
            y,
            samples,
            chars: starts,
            sample_count: n as i32,
        })
    }

    // ---- the walk ----

    #[allow(clippy::too_many_arguments)]
    fn search_one(
        &mut self,
        src: &Source,
        keys: &KeyMap,
        k: usize,
        results: &mut HashMap<Vec<u16>, Candidate>,
        floor_in: f64,
        mut ahead: Option<&mut HashMap<Vec<u16>, Candidate>>,
        budget: &mut i32,
    ) -> f64 {
        let mut floor = floor_in;
        let walker = src.walker;
        let n = self.ws.samples;
        let key_count = keys.key_count();
        let min_frequency = src.min_frequency;
        let tuning = self.tuning.clone();
        let join_on = tuning.join_cost.is_finite();

        self.ws.reset();
        let root = walker.root();
        self.ws.push(root, -1, NO_LABEL, -1, 0, 0.0, 0.0, src.log_weight + tuning.freq_weight * ln1p(walker.max_subtree(root)), 0);

        let mut pops = 0;
        let mut children = std::mem::take(&mut self.ws.children);
        while self.ws.heap_size() > 0 && pops < MAX_POPS {
            let s = self.ws.pop_best();
            if self.ws.bound[s] < floor - EPS {
                break;
            }
            pops += 1;

            let node = self.ws.node[s];
            let length = self.ws.length[s];
            let extra = self.ws.extra[s];
            let last_key = self.ws.last_key[s];

            let part_freq = self.ws.part_freq[s];
            let part_from = self.ws.part_from[s];
            let joins = self.ws.joins[s];
            // The join charge plus the common-part penalties this state
            // already earned; both 0.0 for a plain word.
            let charge = tuning.join_charge(joins) + self.ws.join_penalty[s];

            let total = self.terminal_cost(self.ws.column_of(s));
            let at_end = last_key >= 0 && self.ws.end_key[last_key as usize] && total < UNREACHABLE;
            if at_end {
                let shape = total + extra + self.unclaimed_dwell(s, keys) + self.unclaimed_loop(s);
                // A compound's last part is held to the same length as its
                // first; `part_from` is 0 and `charge` 0.0 for a plain word.
                if length >= MIN_WORD_LENGTH
                    && length - part_from >= if joins > 0 { tuning.join_min_part } else { 0 }
                    && walker.is_word(node)
                    && walker.frequency(node) >= min_frequency
                {
                    let score = src.log_weight + tuning.freq_weight * ln1p(lm_cap(part_freq, walker.frequency(node)))
                        - tuning.shape_weight * shape as f64
                        - charge;
                    if score > floor - EPS || results.len() < k {
                        let word = self.ws.materialize(s);
                        if !(src.tier == Tier::Dictionary && is_non_word(&word)) {
                            emit(word, score, shape as f64, src.tier, results);
                            if results.len() >= k {
                                floor = kth_best(results, k, &mut self.ws.score_scratch);
                            }
                        }
                    }
                }
                if let Some(ahead) = ahead.as_deref_mut() {
                    if *budget > 0 && length - part_from >= MIN_LOOKAHEAD_PREFIX {
                        self.look_ahead(src, node, s, shape, min_frequency, ahead, budget);
                    }
                }
            }
            if length >= MAX_WORD_LENGTH || length as usize >= n {
                continue;
            }

            // A compound (#966 §5.2): a finished part of three letters or
            // more may hand the rest of the stroke to a second word, read
            // from the trie root on the same alignment column, as if the
            // list held the joined spelling. The joined word scores on the
            // rarest of its parts, `join_cost` the poorer per join, and
            // the bound carries both, so a join never ranks above what the
            // single-word bound allows and the walk's pruning stays exact.
            // The part joined past pays `join_part_penalty` on its own
            // frequency, so two common parts do not outbid a listed word;
            // the last part is followed by no join and pays nothing.
            if join_on
                && joins < MAX_JOINS
                && length - part_from >= tuning.join_min_part
                && (length + tuning.join_min_part) as usize <= n
                && walker.is_word(node)
                && walker.frequency(node) >= min_frequency
            {
                let cap = lm_cap(part_freq, walker.frequency(node));
                let floor_cost = self.ws.floor_cost[s];
                let part_penalty = tuning.join_part_penalty * ln1p(walker.frequency(node));
                let bound = src.log_weight + tuning.freq_weight * ln1p(lm_cap(cap, walker.max_subtree(root)))
                    - tuning.shape_weight * (floor_cost + extra) as f64
                    - tuning.join_charge(joins + 1)
                    - self.ws.join_penalty[s]
                    - part_penalty;
                if bound >= floor - EPS {
                    if let Some(id) = self.ws.push(root, s as i32, NO_LABEL, last_key, length, extra, floor_cost, bound, self.ws.letter_cp[s]) {
                        self.ws.part_freq[id] = cap;
                        self.ws.part_from[id] = length;
                        self.ws.joins[id] = joins + 1;
                        self.ws.join_penalty[id] = self.ws.join_penalty[s] + part_penalty;
                        self.ws.copy_column(s, id);
                    }
                }
            }

            walker.children_into(node, &mut children);
            let via = self.ws.via_label[s];
            let pending_high = if is_high(via) { Some(via) } else { None };
            for &(label, child) in children.iter() {
                let subtree = walker.max_subtree(child);
                if subtree < min_frequency {
                    continue;
                }
                let code_point: i32;
                if let Some(high) = pending_high {
                    if !is_low(label) {
                        continue;
                    }
                    code_point = 0x10000 + (((high as i32) - 0xD800) << 10) + ((label as i32) - 0xDC00);
                } else if is_high(label) {
                    if !self.descend_silent(src, subtree, s, child, label, extra, floor) {
                        break;
                    }
                    continue;
                } else {
                    code_point = label as i32;
                }

                let key = keys.key_index(code_point);
                if key < 0 && length > 0 && is_joiner(code_point) && !is_joiner(self.ws.via_label[s] as i32) {
                    let cost = extra + tuning.joiner_cost;
                    if !self.descend_silent(src, subtree, s, child, label, cost, floor) {
                        break;
                    }
                    continue;
                }
                if key < 0 || key as usize >= key_count || !self.ws.near_key[key as usize] {
                    continue;
                }
                if length == 0 && !self.ws.start_key[key as usize] {
                    continue;
                }
                let repeat = length > 0 && code_point == self.ws.letter_cp[s];

                let min_col: f32;
                let mut child_extra = extra;
                if repeat {
                    min_col = self.ws.floor_cost[s];
                    child_extra += self.repeat_charge(last_key as usize);
                } else {
                    let prev_column = if length == 0 { None } else { Some(self.ws.column_of(s)) };
                    min_col = self.advance(prev_column, key as usize, last_key, keys);
                    if min_col >= UNREACHABLE {
                        continue;
                    }
                }

                let bound = src.log_weight + tuning.freq_weight * ln1p(lm_cap(part_freq, subtree))
                    - tuning.shape_weight * (min_col + child_extra) as f64
                    - charge;
                if bound < floor - EPS {
                    continue;
                }
                let Some(id) =
                    self.ws.push(child, s as i32, label, key, length + 1, child_extra, min_col, bound, code_point)
                else {
                    break;
                };
                if repeat {
                    self.ws.copy_column(s, id);
                } else {
                    self.ws.store_scratch(id);
                }
            }
        }
        self.ws.children = children;
        floor
    }

    #[allow(clippy::too_many_arguments)]
    fn descend_silent(&mut self, src: &Source, subtree: i32, s: usize, child: u32, label: u16, extra: f32, floor: f64) -> bool {
        let bound = src.log_weight + self.tuning.freq_weight * ln1p(lm_cap(self.ws.part_freq[s], subtree))
            - self.tuning.shape_weight * (self.ws.floor_cost[s] + extra) as f64
            - self.tuning.join_charge(self.ws.joins[s])
            - self.ws.join_penalty[s];
        if bound < floor - EPS {
            return true;
        }
        let (last_key, length, floor_cost, letter_cp) =
            (self.ws.last_key[s], self.ws.length[s], self.ws.floor_cost[s], self.ws.letter_cp[s]);
        let Some(id) = self.ws.push(child, s as i32, label, last_key, length, extra, floor_cost, bound, letter_cp) else {
            return false;
        };
        self.ws.copy_column(s, id);
        true
    }

    #[allow(clippy::too_many_arguments)]
    fn look_ahead(
        &mut self,
        src: &Source,
        node: u32,
        s: usize,
        shape: f32,
        min_frequency: i32,
        ahead: &mut HashMap<Vec<u16>, Candidate>,
        budget: &mut i32,
    ) {
        let walker = src.walker;
        let best = walker.max_subtree(node);
        if best < min_frequency || best <= walker.frequency(node) {
            return;
        }
        *budget -= 1;
        let prefix = self.ws.materialize(s);
        let mut word = prefix.clone();
        let mut at = node;
        let mut steps = 0;
        let mut children = std::mem::take(&mut self.ws.look_ahead_children);
        while steps < MAX_WORD_LENGTH {
            if walker.is_word(at) && walker.frequency(at) == best {
                break;
            }
            walker.children_into(at, &mut children);
            let next = children.iter().find(|&&(_, c)| walker.max_subtree(c) == best).copied();
            let Some((label, child)) = next else {
                self.ws.look_ahead_children = children;
                return;
            };
            word.push(label);
            at = child;
            steps += 1;
        }
        self.ws.look_ahead_children = children;
        if steps == 0 || word.len() <= prefix.len() {
            return;
        }
        if src.tier == Tier::Dictionary && is_non_word(&word) {
            return;
        }
        let extra = (word.len() - prefix.len()) as i32;
        let score = src.log_weight + self.tuning.freq_weight * ln1p(lm_cap(self.ws.part_freq[s], best))
            - self.tuning.shape_weight * shape as f64
            - self.tuning.look_ahead_cost * extra as f64
            - self.tuning.join_charge(self.ws.joins[s])
            - self.ws.join_penalty[s];
        let replace = match ahead.get(&word) {
            None => true,
            Some(existing) => score > existing.score,
        };
        if replace {
            ahead.insert(word.clone(), Candidate { word, score, shape_cost: shape as f64, tier: src.tier, ahead: extra });
        }
    }

    fn advance(&mut self, prev_column: Option<usize>, key: usize, prev_key: i32, keys: &KeyMap) -> f32 {
        let n = self.ws.samples;
        let key_count = keys.key_count();
        let Some(prev_column) = prev_column else {
            // The first letter stands on the first sample, or on any sample
            // of an open head (#968): the walk up from the spacebar before
            // it is nobody's letter.
            let head_to = self.ws.open_head_to;
            let mut best = UNREACHABLE;
            for j in 0..n {
                if j <= head_to {
                    let at = self.ws.point_cost[j * key_count + key];
                    self.ws.scratch[j] = at;
                    let remain = self.ws.remain[j * key_count + key];
                    if remain < UNREACHABLE && at + remain < best {
                        best = at + remain;
                    }
                } else {
                    self.ws.scratch[j] = UNREACHABLE;
                }
            }
            return best;
        };
        let step = self.ws.arc_step;
        let span = keys.distance(prev_key as usize, key);
        let expected = span / step;
        let lo = 1.max((expected - self.tuning.gap_window) as i32);
        let hi = lo.max((expected + self.tuning.gap_window) as i32 + 1) + self.ws.collapsed_samples;
        let lead = self.anchor_lead(0, prev_key, self.tuning.start_slack(), keys);
        let tail = self.anchor_lead(n - 1, key as i32, self.tuning.end_slack(), keys);

        self.ws.scratch[0] = UNREACHABLE;
        let mut best = UNREACHABLE;
        for j in 1..n {
            // The stroke's two ends are open past the band (#886): the first
            // letter may stand any distance before the next and the last any
            // distance after the one before, the extra travel paying the gap
            // charge. A loop the scan missed, or collapsed only in part, used
            // to put `ill`'s second key out of reach of its first entirely.
            let from = if j == n - 1 { 0 } else { 0.max(j as i32 - hi) };
            let to = j as i32 - lo;
            let arc_j = self.ws.arc_at[j];
            let ease_j = if j == n - 1 { tail } else { 0.0 };
            let mut best_prev = UNREACHABLE;
            let mut i = from;
            // Sample 0 eases by the lead too; the rest of the band is one
            // kernel call (kernels::band, the same arithmetic four at a time).
            if 0 <= to {
                let at = self.ws.cols[prev_column];
                if at < UNREACHABLE {
                    let travelled = arc_j - self.ws.arc_at[0];
                    let gap = travelled - span;
                    let v = at + self.gap_charge(gap, lead + ease_j);
                    if v < best_prev {
                        best_prev = v;
                    }
                }
                i = i.max(1);
            }
            if i <= to {
                let (a, b) = (i as usize, to as usize + 1);
                let v = band::min_arc(
                    &self.ws.cols[prev_column + a..prev_column + b],
                    &self.ws.arc_at[a..b],
                    arc_j,
                    span,
                    ease_j,
                    self.tuning.gap_weight,
                    UNREACHABLE,
                );
                if v < best_prev {
                    best_prev = v;
                }
            }
            let value = if best_prev >= UNREACHABLE { UNREACHABLE } else { best_prev + self.ws.point_cost[j * key_count + key] };
            self.ws.scratch[j] = value;
            let rest = self.ws.remain[j * key_count + key];
            if value < UNREACHABLE && rest < UNREACHABLE && value + rest < best {
                best = value + rest;
            }
        }
        best
    }

    fn build_remain(&mut self, keys: &KeyMap) {
        let n = self.ws.samples;
        let key_count = keys.key_count();
        let step = self.ws.arc_step;
        for k in 0..key_count {
            if self.ws.near_key[k] {
                let c = self.ws.near_count;
                self.ws.near_list[c] = k;
                self.ws.near_count += 1;
            }
        }
        let count = self.ws.near_count;
        let last = (n - 1) * key_count;
        for k in 0..key_count {
            self.ws.remain[last + k] = if self.ws.end_key[k] { 0.0 } else { UNREACHABLE };
        }
        // An open tail (#968) ends on any of its samples at no further cost.
        let tail_from = self.ws.open_tail_from.min(n - 1);
        for j in tail_from..n - 1 {
            for k in 0..key_count {
                self.ws.remain[j * key_count + k] = 0.0;
            }
        }
        // The near keys' costs and remainders again, key by key in sample
        // order, for the band kernel to read in runs.
        for x in 0..count {
            let k = self.ws.near_list[x];
            for j in 0..n {
                self.ws.point_cost_t[k * SAMPLE_POINTS + j] = self.ws.point_cost[j * key_count + k];
            }
            for j in tail_from..n {
                self.ws.remain_t[k * SAMPLE_POINTS + j] = self.ws.remain[j * key_count + k];
            }
        }
        let (start_slack, end_slack) = (self.tuning.start_slack(), self.tuning.end_slack());
        for j in (0..tail_from).rev() {
            let base = j * key_count;
            let arc_j = self.ws.arc_at[j];
            for a in 0..key_count {
                self.ws.remain[base + a] = UNREACHABLE;
            }
            for x in 0..count {
                let from = self.ws.near_list[x];
                let lead = if j == 0 { self.anchor_lead(0, from as i32, start_slack, keys) } else { 0.0 };
                let mut best = UNREACHABLE;
                for y in 0..count {
                    let to = self.ws.near_list[y];
                    let span = keys.distance(from, to);
                    let expected = span / step;
                    let lo = 1.max((expected - self.tuning.gap_window) as i32);
                    let hi = lo.max((expected + self.tuning.gap_window) as i32 + 1) + self.ws.collapsed_samples;
                    // Open to the last sample from anywhere, and from the
                    // first to anywhere, as in `advance`.
                    let end = if j == 0 { n as i32 - 1 } else { (n as i32 - 1).min(j as i32 + hi) };
                    let start = j as i32 + lo;
                    // The band up to the last sample is one kernel call over
                    // `to`'s costs and remainders in sample order; the last
                    // sample eases by the end anchor too.
                    let last = if end == n as i32 - 1 { end - 1 } else { end };
                    if start <= last {
                        let (a, b) = (start as usize, last as usize + 1);
                        let row = to * SAMPLE_POINTS;
                        let v = band::min_ahead(
                            &self.ws.point_cost_t[row + a..row + b],
                            &self.ws.remain_t[row + a..row + b],
                            &self.ws.arc_at[a..b],
                            arc_j,
                            span,
                            lead,
                            self.tuning.gap_weight,
                            UNREACHABLE,
                        );
                        if v < best {
                            best = v;
                        }
                    }
                    if start <= n as i32 - 1 {
                        let iu = n - 1;
                        let rest = self.ws.remain[iu * key_count + to];
                        if rest < UNREACHABLE {
                            let gap = self.ws.arc_at[iu] - arc_j - span;
                            let ease = lead + self.anchor_lead(n - 1, to as i32, end_slack, keys);
                            let v = self.gap_charge(gap, ease) + self.ws.point_cost[iu * key_count + to] + rest;
                            if v < best {
                                best = v;
                            }
                        }
                    }
                }
                self.ws.remain[base + from] = best;
                self.ws.remain_t[from * SAMPLE_POINTS + j] = best;
            }
        }
    }

    // ---- per-decode preparation ----

    fn resample(&mut self, path: &[Point], key_width: f32) -> bool {
        let mut total_px = 0f32;
        for i in 1..path.len() {
            total_px += distance(&path[i - 1], &path[i]);
        }
        if total_px / key_width < MIN_STROKE_LENGTH {
            return false;
        }
        let n = workspace::samples_for(total_px / key_width);
        self.ws.samples = n;
        let step = total_px / (n - 1) as f32;
        self.ws.arc_step = step / key_width;
        let arc_step = self.ws.arc_step;
        for j in 0..n {
            self.ws.arc_at[j] = j as f32 * arc_step;
        }
        self.ws.collapsed_samples = 0;
        let first = path[0];
        self.ws.path_x[0] = first.x / key_width;
        self.ws.path_y[0] = first.y / key_width;
        self.ws.path_t[0] = first.t;
        let mut out = 1;
        let mut index = 0;
        let (mut cx, mut cy, mut ct) = (first.x, first.y, first.t);
        let mut accumulated = 0f32;
        while out < n - 1 && index + 1 < path.len() {
            let next = path[index + 1];
            let segment = ((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy)).sqrt();
            if accumulated + segment >= step && segment > 0.0 {
                let fraction = (step - accumulated) / segment;
                cx += fraction * (next.x - cx);
                cy += fraction * (next.y - cy);
                ct += ((next.t - ct) as f32 * fraction) as i64;
                self.ws.path_x[out] = cx / key_width;
                self.ws.path_y[out] = cy / key_width;
                self.ws.path_t[out] = ct;
                out += 1;
                accumulated = 0.0;
            } else {
                accumulated += segment;
                cx = next.x;
                cy = next.y;
                ct = next.t;
                index += 1;
            }
        }
        let last = path[path.len() - 1];
        while out < n {
            self.ws.path_x[out] = last.x / key_width;
            self.ws.path_y[out] = last.y / key_width;
            self.ws.path_t[out] = last.t;
            out += 1;
        }
        self.map_open_ends(path, total_px);
        true
    }

    /// Where the open head ends and the open tail begins on the resampled
    /// stroke (#968): the raw sample counts in `open_head` and `open_tail`,
    /// read off the path by arc length. At least one sample stays closed at
    /// each end, and the two zones never meet.
    fn map_open_ends(&mut self, path: &[Point], total_px: f32) {
        let n = self.ws.samples;
        self.ws.open_head_to = 0;
        self.ws.open_tail_from = n - 1;
        self.ws.open_ends = false;
        if (self.open_head == 0 && self.open_tail == 0) || total_px <= 0.0 || path.len() < 2 {
            return;
        }
        let at = |raw: usize| -> usize {
            let mut arc = 0f32;
            for i in 1..=raw.min(path.len() - 1) {
                arc += distance(&path[i - 1], &path[i]);
            }
            ((arc / total_px) * (n - 1) as f32).round() as usize
        };
        if self.open_head > 0 {
            self.ws.open_head_to = at(self.open_head.min(path.len() - 1)).min(n - 2);
        }
        if self.open_tail > 0 {
            let raw = path.len() - 1 - self.open_tail.min(path.len() - 1);
            self.ws.open_tail_from = at(raw).max(1).max(self.ws.open_head_to + 1).min(n - 1);
        }
        self.ws.open_ends = self.ws.open_head_to > 0 || self.ws.open_tail_from < n - 1;
    }

    /// The cost of a finished word on the column `col`: the last sample's,
    /// or the least over an open tail (#968).
    fn terminal_cost(&self, col: usize) -> f32 {
        let n = self.ws.samples;
        let mut best = self.ws.cols[col + n - 1];
        for j in self.ws.open_tail_from..n - 1 {
            let v = self.ws.cols[col + j];
            if v < best {
                best = v;
            }
        }
        best
    }

    fn build_costs(&mut self, keys: &KeyMap) {
        let n = self.ws.samples;
        let key_count = keys.key_count();
        let inv = self.tuning.inv_two_sigma_sq();
        let max_point_cost = self.tuning.max_point_cost;
        let near_cost = self.tuning.near_cost();
        for i in 0..n {
            let base = i * key_count;
            let (px, py) = (self.ws.path_x[i], self.ws.path_y[i]);
            for k in 0..key_count {
                let dx = px - keys.key_x[k];
                let dy = py - keys.key_y[k];
                let scaled = (dx * dx + dy * dy) * inv;
                self.ws.point_cost[base + k] = if scaled > max_point_cost { max_point_cost } else { scaled };
                if scaled <= near_cost {
                    self.ws.near_key[k] = true;
                }
            }
        }
        let (x0, y0, xn, yn) = (self.ws.path_x[0], self.ws.path_y[0], self.ws.path_x[n - 1], self.ws.path_y[n - 1]);
        anchor_mask(x0, y0, keys, &mut self.ws.start_key, self.tuning.start_radius_sq());
        anchor_mask(xn, yn, keys, &mut self.ws.end_key, self.tuning.end_radius_sq());
        self.anchor_slack(0, self.tuning.start_slack(), true, keys);
        self.anchor_slack(n - 1, self.tuning.end_slack(), false, keys);
        // An open end (#968) is no anchor at all: the stroke's end sits at
        // the spacebar's edge, and any key the walk passed may begin or end
        // the word. The near set widens to every key the open zone passed.
        if self.ws.open_head_to > 0 {
            for k in 0..key_count {
                self.ws.start_key[k] = true;
            }
        }
        if self.ws.open_tail_from < n - 1 {
            for k in 0..key_count {
                self.ws.end_key[k] = true;
            }
        }
    }

    fn anchor_slack(&mut self, j: usize, slack: f32, start: bool, keys: &KeyMap) {
        if slack <= 0.0 {
            return;
        }
        let key_count = keys.key_count();
        let base = j * key_count;
        let inv = self.tuning.inv_two_sigma_sq();
        let max_point_cost = self.tuning.max_point_cost;
        for k in 0..key_count {
            let dx = self.ws.path_x[j] - keys.key_x[k];
            let dy = self.ws.path_y[j] - keys.key_y[k];
            let past = (dx * dx + dy * dy).sqrt() - slack;
            let scaled = if past > 0.0 { past * past * inv } else { 0.0 };
            self.ws.point_cost[base + k] = if scaled > max_point_cost { max_point_cost } else { scaled };
            let masked = if start { self.ws.start_key[k] } else { self.ws.end_key[k] };
            if masked {
                self.ws.near_key[k] = true;
            }
        }
    }

    fn anchor_lead(&self, j: usize, key: i32, slack: f32, keys: &KeyMap) -> f32 {
        if slack <= 0.0 || key < 0 {
            return 0.0;
        }
        let dx = self.ws.path_x[j] - keys.key_x[key as usize];
        let dy = self.ws.path_y[j] - keys.key_y[key as usize];
        let d = (dx * dx + dy * dy).sqrt();
        if d < slack {
            d
        } else {
            slack
        }
    }

    fn gap_charge(&self, gap: f32, ease: f32) -> f32 {
        let over = (if gap < 0.0 { -gap } else { gap }) - ease;
        if over > 0.0 {
            self.tuning.gap_weight * over
        } else {
            0.0
        }
    }

    fn build_dwell(&mut self, keys: &KeyMap) {
        if self.tuning.unclaimed_dwell <= 0.0 {
            return;
        }
        let n = self.ws.samples;
        let span = (self.ws.path_t[n - 1] - self.ws.path_t[0]) as f32;
        if span <= 0.0 {
            return;
        }
        let even_step = span / (n - 1) as f32;
        let dwell_scale = self.tuning.dwell_scale();
        let mut run_end: i32 = -1;
        for j in 1..n {
            let step = (self.ws.path_t[j] - self.ws.path_t[j - 1]) as f32;
            let lingering = ((step / even_step) - 1.0) / dwell_scale;
            if lingering <= 0.0 {
                continue;
            }
            let score = if lingering > 1.0 { 1.0 } else { lingering };
            self.record_pause(j, score, run_end);
            run_end = j as i32;
        }
        for p in 0..self.ws.pause_count {
            let j = self.ws.pause_at[p];
            let (x, y) = (self.ws.path_x[j], self.ws.path_y[j]);
            self.place_pause(p, x, y, keys);
        }
    }

    fn place_pause(&mut self, p: usize, x: f32, y: f32, keys: &KeyMap) {
        let mut nearest_sq = f32::MAX;
        for k in 0..keys.key_count() {
            let dx = x - keys.key_x[k];
            let dy = y - keys.key_y[k];
            let d = dx * dx + dy * dy;
            if d < nearest_sq {
                nearest_sq = d;
            }
        }
        let reach = DWELL_RADIUS.min(nearest_sq.sqrt() + DWELL_TIE);
        self.ws.pause_x[p] = x;
        self.ws.pause_y[p] = y;
        self.ws.pause_reach_sq[p] = reach * reach;
    }

    fn record_pause(&mut self, j: usize, score: f32, run_end: i32) {
        let count = self.ws.pause_count;
        if count > 0 && run_end == j as i32 - 1 {
            if score > self.ws.pause_score[count - 1] {
                self.ws.pause_score[count - 1] = score;
                self.ws.pause_at[count - 1] = j;
            }
            return;
        }
        self.ws.pause_at[count] = j;
        self.ws.pause_score[count] = score;
        self.ws.pause_count = count + 1;
    }

    fn unclaimed_dwell(&self, s: usize, keys: &KeyMap) -> f32 {
        let weight = self.tuning.unclaimed_dwell;
        if weight <= 0.0 || self.ws.pause_count == 0 {
            return 0.0;
        }
        let mut charge = 0f32;
        for p in 0..self.ws.pause_count {
            let (px, py, reach_sq) = (self.ws.pause_x[p], self.ws.pause_y[p], self.ws.pause_reach_sq[p]);
            let mut claimed = false;
            let mut cur = s as i32;
            while cur >= 0 && !claimed {
                let key = self.ws.last_key[cur as usize];
                if key >= 0 {
                    let dx = px - keys.key_x[key as usize];
                    let dy = py - keys.key_y[key as usize];
                    claimed = dx * dx + dy * dy <= reach_sq;
                }
                cur = self.ws.parent[cur as usize];
            }
            if !claimed {
                charge += self.ws.pause_score[p];
            }
        }
        weight * charge
    }

    // ---- loops ----

    fn build_loops(&mut self, path: &[Point], key_width: f32, keys: &KeyMap) {
        self.ws.loop_count = 0;
        let found = self.scan.find(path, key_width, &self.tuning);
        for w in 0..found {
            let is_loop = self.scan.window_is_loop[w];
            let (from, to) = (self.scan.window_from[w], self.scan.window_to[w]);
            let e = self.record_loop(from, to, if is_loop { 1.0 } else { 0.0 }, keys);
            if !is_loop {
                self.record_wiggle(e, keys);
            }
        }
        if self.ws.loop_count > 0 {
            self.exclude_neighbours(keys);
            self.collapse_arc();
        }
    }

    fn ends_on_loop(&self) -> bool {
        let last = self.ws.samples - 1;
        for e in (0..self.ws.loop_count).rev() {
            if self.ws.loop_score[e] <= 0.0 || self.ws.loop_key[e] < 0 {
                continue;
            }
            return self.ws.arc_at[last] - self.ws.arc_at[self.ws.loop_to[e]] <= END_LOOP_REACH;
        }
        false
    }

    fn record_wiggle(&mut self, e: usize, keys: &KeyMap) {
        let p = self.ws.pause_count;
        if self.tuning.unclaimed_dwell <= 0.0 || self.tuning.wiggle_weight <= 0.0 {
            return;
        }
        if p >= self.ws.samples {
            return;
        }
        self.ws.pause_at[p] = (self.ws.loop_from[e] + self.ws.loop_to[e]) / 2;
        self.ws.pause_score[p] = self.tuning.wiggle_weight;
        let (x, y) = (self.ws.loop_x[e], self.ws.loop_y[e]);
        self.place_pause(p, x, y, keys);
        self.ws.pause_count = p + 1;
    }

    fn exclude_neighbours(&mut self, keys: &KeyMap) {
        let charge = self.tuning.loop_exclusion;
        if charge <= 0.0 {
            return;
        }
        let key_count = keys.key_count();
        let cap = self.tuning.max_point_cost;
        for e in 0..self.ws.loop_count {
            let own = self.ws.loop_key[e];
            if own < 0 {
                continue;
            }
            for j in self.ws.loop_from[e] + 1..self.ws.loop_to[e] {
                let base = j * key_count;
                for k in 0..key_count {
                    if k as i32 != own {
                        let c = self.ws.point_cost[base + k] + charge;
                        self.ws.point_cost[base + k] = if cap < c { cap } else { c };
                    }
                }
            }
        }
    }

    fn record_loop(&mut self, from: usize, to: usize, score: f32, keys: &KeyMap) -> usize {
        self.scan.measure(from, to);
        let (cx, cy, length, chord) = (self.scan.centre_x, self.scan.centre_y, self.scan.length, self.scan.chord);
        let last = self.ws.samples - 1;
        let to_sample = self.scan.fine_step / self.ws.arc_step;
        let mut a = last.min((from as f32 * to_sample + 0.5) as i32 as usize);
        let mut b = last.min((to as f32 * to_sample + 0.5) as i32 as usize);
        if b <= a {
            if a < last {
                b = a + 1;
            } else {
                a = b - 1;
            }
        }
        let e = self.ws.loop_count;
        self.ws.loop_from[e] = a;
        self.ws.loop_to[e] = b;
        self.ws.loop_shrink[e] = if length > 0.0 { chord / length } else { 1.0 };
        self.ws.loop_x[e] = cx;
        self.ws.loop_y[e] = cy;
        self.ws.loop_score[e] = score;
        self.ws.loop_count = e + 1;
        let own = self.scan.nearest_key(cx, cy, keys, self.tuning.loop_radius_sq());
        self.ws.loop_key[e] = own;
        if own >= 0 && score > self.ws.key_loop[own as usize] {
            self.ws.key_loop[own as usize] = score;
        }
        e
    }

    fn collapse_arc(&mut self) {
        let n = self.ws.samples;
        let step = self.ws.arc_step;
        let mut removed = 0f32;
        let mut e = 0;
        self.ws.arc_at[0] = 0.0;
        for j in 1..n {
            if e < self.ws.loop_count && j > self.ws.loop_from[e] && j <= self.ws.loop_to[e] {
                let a = self.ws.loop_from[e];
                let shrink = self.ws.loop_shrink[e];
                self.ws.arc_at[j] = self.ws.arc_at[a] + (j - a) as f32 * step * shrink;
                if j == self.ws.loop_to[e] {
                    removed += (j - a) as f32 * step * (1.0 - shrink);
                    e += 1;
                }
            } else {
                self.ws.arc_at[j] = self.ws.arc_at[j - 1] + step;
            }
        }
        self.ws.collapsed_samples = (removed / step).ceil() as i32;
    }

    fn repeat_charge(&self, key: usize) -> f32 {
        (self.tuning.repeat_cost + self.tuning.unlooped_repeat) * (1.0 - self.ws.key_loop[key])
    }

    fn unclaimed_loop(&self, s: usize) -> f32 {
        let weight = self.tuning.unclaimed_loop;
        if weight <= 0.0 || self.ws.loop_count == 0 {
            return 0.0;
        }
        let mut charge = 0f32;
        for e in 0..self.ws.loop_count {
            let own = self.ws.loop_key[e];
            if self.ws.loop_score[e] <= 0.0 || own < 0 {
                continue;
            }
            let mut claimed = false;
            let mut cur = s as i32;
            while cur >= 0 && !claimed {
                let parent = self.ws.parent[cur as usize];
                let c = cur as usize;
                if parent >= 0
                    && self.ws.letter_cp[c] == self.ws.letter_cp[parent as usize]
                    && self.ws.length[c] == self.ws.length[parent as usize] + 1
                {
                    claimed = self.ws.last_key[c] == own;
                }
                cur = parent;
            }
            if !claimed {
                charge += self.ws.loop_score[e];
            }
        }
        weight * charge
    }

    // ---- shape channel ----

    fn rescore_shape(&mut self, ranked: Vec<Candidate>, keys: &KeyMap, shapes: Option<&LearnedShapes>) -> Vec<Candidate> {
        let weight = self.tuning.shape_channel;
        // A stroke with an open end carries a walk to or from the spacebar
        // that no word's ideal path has (#968): the drawn shape and the
        // ideal cannot be compared, so the walk's ranking stands.
        if weight <= 0.0 || ranked.is_empty() || self.ws.open_ends {
            return ranked;
        }
        self.drawn_shape();
        normalise_in_place(&mut self.ws.drawn_shape_x, &mut self.ws.drawn_shape_y);
        self.plain_shape(true);
        let learned = shapes.filter(|_| self.tuning.learned_shape_gain > 0.0);
        let mut rescored = Vec::with_capacity(ranked.len());
        for candidate in ranked {
            let distance = if candidate.ahead > 0 {
                let keep = candidate.word.len().saturating_sub(candidate.ahead as usize);
                self.shape_distance(&candidate.word[..keep], keys)
            } else {
                self.shape_distance(&candidate.word, keys)
                    .map(|ideal| self.learned_distance(&candidate.word, ideal, learned))
            };
            rescored.push(match distance {
                None => candidate,
                Some(d) => Candidate { score: candidate.score - weight * d, ..candidate },
            });
        }
        sort::by(&mut rescored, by_score);
        rescored
    }

    fn shape_distance(&mut self, word: &[u16], keys: &KeyMap) -> Option<f64> {
        let mut count = 0usize;
        let mut previous = -1;
        for (cp, _) in code_points(word) {
            let key = keys.key_index(cp);
            if key < 0 {
                if is_joiner(cp) {
                    continue;
                }
                return None;
            }
            if key == previous {
                continue;
            }
            if count >= MAX_IDEAL_POINTS {
                return None;
            }
            self.ws.ideal_x[count] = keys.key_x[key as usize];
            self.ws.ideal_y[count] = keys.key_y[key as usize];
            count += 1;
            previous = key;
        }
        if count < 2 {
            return None;
        }
        let count = self.with_anchor_leads(count);
        let (ix, iy) = (self.ws.ideal_x, self.ws.ideal_y);
        resample_polyline(&ix, &iy, count, &mut self.ws.ideal_shape_x, &mut self.ws.ideal_shape_y);
        normalise_in_place(&mut self.ws.ideal_shape_x, &mut self.ws.ideal_shape_y);
        let mut sum = 0f64;
        for j in 0..SHAPE_POINTS {
            let dx = self.ws.plain_shape_x[j] - self.ws.ideal_shape_x[j];
            let dy = self.ws.plain_shape_y[j] - self.ws.ideal_shape_y[j];
            sum += (dx * dx + dy * dy).sqrt() as f64;
        }
        Some(sum / SHAPE_POINTS as f64)
    }

    fn with_anchor_leads(&mut self, count: usize) -> usize {
        let mut size = count;
        let start = self.tuning.start_slack();
        if start > 0.0 && size < MAX_IDEAL_POINTS {
            for p in (1..=size).rev() {
                self.ws.ideal_x[p] = self.ws.ideal_x[p - 1];
                self.ws.ideal_y[p] = self.ws.ideal_y[p - 1];
            }
            size += 1;
            let (x, y) = (self.ws.path_x[0], self.ws.path_y[0]);
            self.reach_toward(1, 0, x, y, start);
        }
        let end = self.tuning.end_slack();
        if end > 0.0 && size < MAX_IDEAL_POINTS {
            let last = self.ws.samples - 1;
            let (x, y) = (self.ws.path_x[last], self.ws.path_y[last]);
            self.reach_toward(size - 1, size, x, y, end);
            size += 1;
        }
        size
    }

    fn reach_toward(&mut self, from: usize, into: usize, x: f32, y: f32, reach: f32) {
        let (fx, fy) = (self.ws.ideal_x[from], self.ws.ideal_y[from]);
        let dx = x - fx;
        let dy = y - fy;
        let d = (dx * dx + dy * dy).sqrt();
        let t = if d <= reach { 1.0 } else { reach / d };
        self.ws.ideal_x[into] = fx + dx * t;
        self.ws.ideal_y[into] = fy + dy * t;
    }

    fn learned_distance(&mut self, word: &[u16], ideal: f64, shapes: Option<&LearnedShapes>) -> f64 {
        let Some(shapes) = shapes else { return ideal };
        let Some(stored) = shapes.get(word).filter(|s| !s.is_empty()) else {
            return ideal;
        };
        let n = SHAPE_POINTS;
        let mut own = f64::MAX;
        for shape in stored {
            for j in 0..n {
                self.ws.learned_shape_x[j] = shape[2 * j] as f32 / SHAPE_QUANT;
                self.ws.learned_shape_y[j] = shape[2 * j + 1] as f32 / SHAPE_QUANT;
            }
            normalise_in_place(&mut self.ws.learned_shape_x, &mut self.ws.learned_shape_y);
            let mut sum = 0f64;
            for j in 0..n {
                let dx = self.ws.drawn_shape_x[j] - self.ws.learned_shape_x[j];
                let dy = self.ws.drawn_shape_y[j] - self.ws.learned_shape_y[j];
                sum += (dx * dx + dy * dy).sqrt() as f64;
            }
            own = java_min(own, sum / n as f64);
        }
        java_max(java_min(ideal, own), ideal - self.tuning.learned_shape_gain)
    }

    fn drawn_shape(&mut self) {
        if self.ws.samples == SHAPE_POINTS {
            self.ws.drawn_shape_x.copy_from_slice(&self.ws.path_x[..SHAPE_POINTS]);
            self.ws.drawn_shape_y.copy_from_slice(&self.ws.path_y[..SHAPE_POINTS]);
        } else {
            let (px, py) = (self.ws.path_x, self.ws.path_y);
            resample_polyline(&px, &py, self.ws.samples, &mut self.ws.drawn_shape_x, &mut self.ws.drawn_shape_y);
        }
    }
}

impl Decoder {
    /// `plainShape`: the normalised drawn shape into `plain_shape_*`, with the
    /// stroke's doubling loops cut to their chords when `loops` says this
    /// stroke's loops have been built. Only the ideal paths are compared
    /// against it: no word's ideal path has a loop in it, so a circle left in
    /// the shape matched the words that zigzag where it was drawn better than
    /// the word it doubled, and "need" lost to "needed", "bee" to "bede"
    /// (#886). The learned shapes keep the raw stroke they were stored from.
    fn plain_shape(&mut self, loops: bool) {
        if loops && self.shape_without_loops() {
            normalise_in_place(&mut self.ws.plain_shape_x, &mut self.ws.plain_shape_y);
        } else {
            self.ws.plain_shape_x = self.ws.drawn_shape_x;
            self.ws.plain_shape_y = self.ws.drawn_shape_y;
        }
    }

    /// The stroke resampled into the plain shape with every doubling loop's
    /// inside left out, as [`Decoder::collapse_arc`] collapses the arc; false,
    /// with nothing written, when the stroke has none.
    fn shape_without_loops(&mut self) -> bool {
        let n = self.ws.samples;
        let mut cut = false;
        let mut xs = [0f32; SAMPLE_POINTS];
        let mut ys = [0f32; SAMPLE_POINTS];
        let mut count = 0usize;
        let mut e = 0usize;
        for j in 0..n {
            while e < self.ws.loop_count && j >= self.ws.loop_to[e] {
                e += 1;
            }
            let inside = e < self.ws.loop_count
                && self.ws.loop_score[e] > 0.0
                && self.ws.loop_key[e] >= 0
                && j > self.ws.loop_from[e]
                && j < self.ws.loop_to[e];
            if inside {
                cut = true;
                continue;
            }
            xs[count] = self.ws.path_x[j];
            ys[count] = self.ws.path_y[j];
            count += 1;
        }
        if !cut || count < 2 {
            return false;
        }
        resample_polyline(&xs, &ys, count, &mut self.ws.plain_shape_x, &mut self.ws.plain_shape_y);
        true
    }
}

fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a == b {
        if a.is_sign_negative() { a } else { b }
    } else if a < b {
        a
    } else {
        b
    }
}

fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if a == b {
        if a.is_sign_negative() { b } else { a }
    } else if a > b {
        a
    } else {
        b
    }
}

/// How many samples at one end of `path` are a straight walk to or from
/// the spacebar (#968): from the bar end inward, every sample that lies
/// below the next one inward (within [`OPEN_RUN_LEVEL`] of level: the bar
/// is under the keys, so the walk descends toward it in a tail and climbs
/// away from it in a head), for as long as the samples between stay within
/// [`OPEN_RUN_BEND`] key widths of the straight line from the bar end to
/// that sample. Straight, not vertical: the bar spans the middle five keys,
/// so a word ending on `p` walks to it diagonally. The first bend is the
/// corner at the letter the walk left. The run has to drop at least [`OPEN_RUN_DESCENT`]
/// in all, so the shallow last leg of an ordinary word does not pass for
/// one. The letter the walk left may sit on the run itself ("then": `n` is
/// straight above the bar), which is why the run becomes optional for the
/// decoder rather than cut. `0` for no run, and never more than leaves
/// three samples closed.
pub fn open_run(path: &[Point], key_width: f32, at_end: bool) -> usize {
    let n = path.len();
    if n < 4 || key_width <= 0.0 {
        return 0;
    }
    let level = OPEN_RUN_LEVEL * key_width;
    let bend = OPEN_RUN_BEND * key_width;
    let at = |i: usize| if at_end { path[n - 1 - i] } else { path[i] };
    let bar_end = at(0);
    let mut run = 0usize;
    let max_run = n - 3;
    while run < max_run {
        // The next sample inward, and the one between it and the bar.
        let (inner, outer) = (at(run + 1), at(run));
        // The bar-side sample sits lower on the board than the one inward.
        if outer.y < inner.y - level {
            break;
        }
        // Every sample so far on the straight line to the new one.
        let (dx, dy) = (inner.x - bar_end.x, inner.y - bar_end.y);
        let reach = (dx * dx + dy * dy).sqrt().max(1e-3);
        if (1..=run).any(|i| ((at(i).x - bar_end.x) * dy - (at(i).y - bar_end.y) * dx).abs() > bend * reach) {
            break;
        }
        run += 1;
    }
    if run < 2 {
        return 0;
    }
    if bar_end.y - at(run).y < OPEN_RUN_DESCENT * key_width {
        return 0;
    }
    run
}

/// `open_run`: how much a sample may sit above the next one inward, in key
/// widths, and still be part of the walk.
pub const OPEN_RUN_LEVEL: f32 = 0.05;

/// `open_run`: how far a sample may lie off the straight line of the walk, in
/// key widths. On phrases of real strokes joined by curved walks (`PHRASES=1`
/// in `glide_beam_eval`), words read right: .790 at 0.15, .781 at 0.25, .766
/// at 0.4, against .792 told the walks' true lengths. A hand curves more than
/// the harness does, so this sits a little wide of the best.
pub const OPEN_RUN_BEND: f32 = 0.2;

/// `open_run`: the least the walk must descend toward the bar in all, in key
/// widths. The bottom row's centre is about seven tenths of a key above
/// the bar's edge.
pub const OPEN_RUN_DESCENT: f32 = 0.5;

fn distance(a: &Point, b: &Point) -> f32 {
    let dx = b.x - a.x;
    let dy = b.y - a.y;
    (dx * dx + dy * dy).sqrt()
}

fn quantise(xs: &[f32], ys: &[f32], out: &mut [i8]) {
    for j in 0..SHAPE_POINTS {
        out[2 * j] = ((xs[j] * SHAPE_QUANT) as i32).clamp(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT) as i8;
        out[2 * j + 1] = ((ys[j] * SHAPE_QUANT) as i32).clamp(-SHAPE_BYTE_LIMIT, SHAPE_BYTE_LIMIT) as i8;
    }
}

fn normalise_in_place(xs: &mut [f32], ys: &mut [f32]) {
    let n = SHAPE_POINTS;
    let (mut cx, mut cy) = (0f32, 0f32);
    for j in 0..n {
        cx += xs[j];
        cy += ys[j];
    }
    cx /= n as f32;
    cy /= n as f32;
    let mut spread = 0f32;
    for j in 0..n {
        let dx = xs[j] - cx;
        let dy = ys[j] - cy;
        spread += dx * dx + dy * dy;
    }
    let root = (spread / n as f32).sqrt();
    let scale = if root > MIN_SHAPE_SPREAD { root } else { 1.0 };
    for j in 0..n {
        xs[j] = (xs[j] - cx) / scale;
        ys[j] = (ys[j] - cy) / scale;
    }
}

fn resample_polyline(xs: &[f32], ys: &[f32], count: usize, out_x: &mut [f32], out_y: &mut [f32]) {
    let n = SHAPE_POINTS;
    let mut total = 0f32;
    for i in 1..count {
        let dx = xs[i] - xs[i - 1];
        let dy = ys[i] - ys[i - 1];
        total += (dx * dx + dy * dy).sqrt();
    }
    if total <= 0.0 {
        for j in 0..n {
            out_x[j] = xs[0];
            out_y[j] = ys[0];
        }
        return;
    }
    let step = total / (n - 1) as f32;
    out_x[0] = xs[0];
    out_y[0] = ys[0];
    let mut out = 1;
    let mut index = 0;
    let (mut cx, mut cy) = (xs[0], ys[0]);
    let mut accumulated = 0f32;
    while out < n - 1 && index + 1 < count {
        let (nx, ny) = (xs[index + 1], ys[index + 1]);
        let segment = ((nx - cx) * (nx - cx) + (ny - cy) * (ny - cy)).sqrt();
        if accumulated + segment >= step && segment > 0.0 {
            let fraction = (step - accumulated) / segment;
            cx += fraction * (nx - cx);
            cy += fraction * (ny - cy);
            out_x[out] = cx;
            out_y[out] = cy;
            out += 1;
            accumulated = 0.0;
        } else {
            accumulated += segment;
            cx = nx;
            cy = ny;
            index += 1;
        }
    }
    while out < n {
        out_x[out] = xs[count - 1];
        out_y[out] = ys[count - 1];
        out += 1;
    }
}

fn anchor_mask(x: f32, y: f32, keys: &KeyMap, out: &mut [bool], radius_sq: f32) {
    for k in 0..keys.key_count() {
        let dx = x - keys.key_x[k];
        let dy = y - keys.key_y[k];
        out[k] = dx * dx + dy * dy <= radius_sq;
    }
}

fn emit(word: Vec<u16>, score: f64, shape_cost: f64, tier: Tier, results: &mut HashMap<Vec<u16>, Candidate>) {
    let replace = match results.get(&word) {
        None => true,
        Some(existing) => score > existing.score,
    };
    if replace {
        results.insert(word.clone(), Candidate { word, score, shape_cost, tier, ahead: 0 });
    }
}

fn kth_best(results: &HashMap<Vec<u16>, Candidate>, k: usize, scores: &mut Vec<f64>) -> f64 {
    let size = results.len();
    if size < k {
        return f64::NEG_INFINITY;
    }
    scores.clear();
    scores.extend(results.values().map(|c| c.score));
    scores.sort_by(f64::total_cmp);
    scores[size - k]
}

#[cfg(test)]
mod kernel_parity {
    //! The band kernels (`kernels::band`) against their scalar references
    //! through whole decodes: the scalar path is the loop the Kotlin oracles
    //! were matched against, so equal decodes here keep that parity.

    use super::duet::{DuetDecoder, Token};
    use super::*;
    use crate::kernels::testing::{Rng, FORCE};
    use crate::kernels::{active, set_force_scalar};
    use crate::user_lexicon::UserTrie;

    const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
    const KEY_W: f32 = 60.0;
    const KEY_H: f32 = 84.0;

    fn centre(c: char) -> (f32, f32) {
        for (row, (letters, offset)) in ROWS.iter().enumerate() {
            if let Some(i) = letters.find(c) {
                return ((offset + i as f32 + 0.5) * KEY_W, (row as f32 + 0.5) * KEY_H);
            }
        }
        unreachable!()
    }

    fn keymap() -> KeyMap {
        let mut letters: Vec<char> = ROWS.iter().flat_map(|(l, _)| l.chars()).collect();
        letters.sort();
        let xs = letters.iter().map(|&c| centre(c).0 / KEY_W).collect();
        let ys = letters.iter().map(|&c| centre(c).1 / KEY_W).collect();
        let cps = letters.iter().map(|&c| c as i32).collect();
        KeyMap::new(xs, ys, cps, (0..letters.len() as i32).collect())
    }

    fn gauss(r: &mut Rng) -> f32 {
        let u = (r.f32() as f64).max(1e-9);
        ((-2.0 * u.ln()).sqrt() * (2.0 * std::f64::consts::PI * r.f32() as f64).cos()) as f32
    }

    /// The Phase 0 harness's swipe: jittered corners, a point every 4 ms.
    fn swipe(word: &str, r: &mut Rng) -> Vec<Point> {
        let corners: Vec<(f32, f32)> = word
            .chars()
            .map(|c| {
                let (x, y) = centre(c);
                (x + gauss(r) * 0.2 * KEY_W, y + gauss(r) * 0.2 * KEY_H)
            })
            .collect();
        let mut out = vec![Point { x: corners[0].0, y: corners[0].1, t: 0 }];
        let mut t = 0;
        for w in corners.windows(2) {
            let ((ax, ay), (bx, by)) = (w[0], w[1]);
            let len = ((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).sqrt();
            let steps = ((len / KEY_W * 35.0 / 4.0) as usize).max(1);
            for s in 1..=steps {
                t += 4;
                let f = s as f32 / steps as f32;
                out.push(Point { x: ax + (bx - ax) * f + gauss(r) * 1.5, y: ay + (by - ay) * f + gauss(r) * 1.5, t });
            }
        }
        out
    }

    fn both<T: PartialEq + std::fmt::Debug>(mut run: impl FnMut() -> T) -> T {
        set_force_scalar(true);
        let reference = run();
        set_force_scalar(false);
        let kernels = run();
        assert_eq!(reference, kernels);
        kernels
    }

    #[test]
    fn decodes_are_the_same_with_and_without_the_band_kernels() {
        let _pinned = FORCE.lock().unwrap_or_else(|e| e.into_inner());
        let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/dictionaries-src/en.txt")).unwrap();
        let entries = crate::wordlist::parse(&text);
        let mut trie = UserTrie::new();
        for (w, f) in &entries {
            trie.insert(w, *f);
        }
        let sources = [Source { walker: &trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
        let keys = keymap();
        let words: Vec<&str> = entries
            .iter()
            .map(|(w, _)| w.as_str())
            .filter(|w| (3..=10).contains(&w.len()) && w.chars().all(|c| c.is_ascii_lowercase()))
            .take(4000)
            .collect();
        let mut r = Rng::new(7);
        let mut decoder = Decoder::new(Tuning::default());
        let mut duet = DuetDecoder::default();
        let mut candidates = 0;
        for _ in 0..150 {
            let word = words[r.below(words.len() as u64) as usize];
            let path = swipe(word, &mut r);
            for ahead in [0, 1] {
                candidates += both(|| decoder.decode(&path, &keys, KEY_W, &sources, 8, None, ahead)).len();
            }
            // Live previews: the stroke so far, six times.
            for step in 1..=6 {
                let upto = path.len() * step / 6;
                if upto >= 3 {
                    both(|| decoder.decode(&path[..upto], &keys, KEY_W, &sources, 8, None, 1));
                }
            }
            for partial in [false, true] {
                let tokens = [Token::Swipe(path.clone())];
                candidates += both(|| duet.decode(&tokens, &keys, KEY_W, &sources, 8, partial)).len();
            }
        }
        #[cfg(target_arch = "aarch64")]
        assert!(active().neon, "the kernels must have run");
        assert!(candidates > 1000, "{candidates} candidates");
    }
}

#[cfg(test)]
mod chained {
    //! Open ends (#968): a segment of a glide that crossed the spacebar ends
    //! or starts at the bar's edge, and the walk there is not a letter.

    use super::*;
    use crate::user_lexicon::UserTrie;

    const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
    const KEY_W: f32 = 60.0;
    const KEY_H: f32 = 84.0;
    /// The bar's top edge: right under the bottom row.
    const BAR_TOP: f32 = 3.0 * KEY_H;

    fn centre(c: char) -> (f32, f32) {
        for (row, (letters, offset)) in ROWS.iter().enumerate() {
            if let Some(i) = letters.find(c) {
                return ((offset + i as f32 + 0.5) * KEY_W, (row as f32 + 0.5) * KEY_H);
            }
        }
        unreachable!()
    }

    fn keymap() -> KeyMap {
        let mut letters: Vec<char> = ROWS.iter().flat_map(|(l, _)| l.chars()).collect();
        letters.sort();
        let xs = letters.iter().map(|&c| centre(c).0 / KEY_W).collect();
        let ys = letters.iter().map(|&c| centre(c).1 / KEY_W).collect();
        let cps = letters.iter().map(|&c| c as i32).collect();
        KeyMap::new(xs, ys, cps, (0..letters.len() as i32).collect())
    }

    /// A straight line from `from` to `to`, a sample every eighth of a key.
    fn line(out: &mut Vec<Point>, from: (f32, f32), to: (f32, f32)) {
        let len = ((to.0 - from.0).powi(2) + (to.1 - from.1).powi(2)).sqrt();
        let steps = ((len / KEY_W) * 8.0).ceil().max(1.0) as usize;
        let t0 = out.last().map_or(0, |p| p.t);
        for s in 1..=steps {
            let f = s as f32 / steps as f32;
            out.push(Point { x: from.0 + (to.0 - from.0) * f, y: from.1 + (to.1 - from.1) * f, t: t0 + 4 * s as i64 });
        }
    }

    /// The word drawn centre to centre, no jitter.
    fn swipe(word: &str) -> Vec<Point> {
        let mut out = vec![Point { x: centre(word.chars().next().unwrap()).0, y: centre(word.chars().next().unwrap()).1, t: 0 }];
        let chars: Vec<char> = word.chars().collect();
        for w in chars.windows(2) {
            line(&mut out, centre(w[0]), centre(w[1]));
        }
        out
    }

    /// `word` then straight down to the bar's edge: a chained stroke's earlier segment.
    fn walked_to_bar(word: &str) -> Vec<Point> {
        let mut out = swipe(word);
        let last = *out.last().unwrap();
        line(&mut out, (last.x, last.y), (last.x, BAR_TOP));
        out
    }

    /// Up from the bar's edge into `word`: a chained stroke's later segment.
    fn begun_at_bar(word: &str) -> Vec<Point> {
        let first = centre(word.chars().next().unwrap());
        let mut out = vec![Point { x: first.0, y: BAR_TOP, t: 0 }];
        line(&mut out, (first.0, BAR_TOP), first);
        out.extend(swipe(word).into_iter().skip(1).map(|p| Point { t: p.t + 400, ..p }));
        out
    }

    fn trie() -> UserTrie {
        let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../app/dictionaries-src/en.txt")).unwrap();
        let mut trie = UserTrie::new();
        for (word, frequency) in crate::wordlist::parse(&text) {
            trie.insert(&word, frequency);
        }
        trie
    }

    fn top(decoder: &mut Decoder, trie: &UserTrie, path: &[Point]) -> Option<String> {
        let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
        decoder.decode(path, &keymap(), KEY_W, &sources, 4, None, 0).first().map(|c| String::from_utf16_lossy(&c.word))
    }

    #[test]
    fn the_walk_to_the_bar_is_an_open_run_and_a_plain_word_has_none() {
        let walked = walked_to_bar("the");
        let run = open_run(&walked, KEY_W, true);
        // From e (top row) to the bar is a little over two and a half rows
        // straight down: well over the twenty samples a two-row walk has.
        assert!(run >= 20, "{run}");
        assert_eq!(open_run(&swipe("the"), KEY_W, true), 0);
        let begun = begun_at_bar("cat");
        assert!(open_run(&begun, KEY_W, false) >= 4);
    }

    #[test]
    fn a_diagonal_walk_is_a_run_and_it_stops_at_the_corner() {
        // "top" ends far right of the bar; the walk from p makes for its end.
        let mut walked = swipe("top");
        let (last, drawn) = (*walked.last().unwrap(), walked.len());
        let bar = (last.x - 2.0 * KEY_W, BAR_TOP);
        line(&mut walked, (last.x, last.y), bar);
        let run = open_run(&walked, KEY_W, true);
        // The whole walk, and at most the sample the corner is drawn from.
        assert!((walked.len() - drawn..=walked.len() - drawn + 1).contains(&run), "{run}");
    }

    #[test]
    fn words_walked_to_the_bar_read_with_an_open_tail() {
        let trie = trie();
        let mut decoder = Decoder::new(Tuning::default());
        for word in ["the", "then", "want", "similar", "going"] {
            let path = walked_to_bar(word);
            decoder.open_tail = open_run(&path, KEY_W, true);
            assert_eq!(top(&mut decoder, &trie, &path).as_deref(), Some(word), "{word} with an open tail");
            decoder.open_tail = 0;
        }
    }

    #[test]
    fn words_begun_at_the_bar_read_with_an_open_head() {
        let trie = trie();
        let mut decoder = Decoder::new(Tuning::default());
        for word in ["cat", "dog", "to", "understanding", "people"] {
            let path = begun_at_bar(word);
            decoder.open_head = open_run(&path, KEY_W, false);
            assert_eq!(top(&mut decoder, &trie, &path).as_deref(), Some(word), "{word} with an open head");
            decoder.open_head = 0;
        }
    }

    #[test]
    fn an_ordinary_stroke_is_unchanged_by_zero_open_ends() {
        let trie = trie();
        let mut decoder = Decoder::new(Tuning::default());
        let path = swipe("keyboard");
        assert_eq!(top(&mut decoder, &trie, &path).as_deref(), Some("keyboard"));
    }
}
