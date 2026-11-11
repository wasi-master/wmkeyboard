//! `DuetBeam`: a word typed with both thumbs at once, any mix of taps and
//! swipes overlapping in time, read as one word. The Kotlin class documents
//! the model (the clock rules, the open-swipe slots, the admissible bound);
//! this is its port, step for step, with `DuetWorkspace`'s pool and heap, so
//! states pop in the same order and score to the same bit.

use std::collections::HashMap;

use super::keymap::KeyMap;
use super::loops::{is_joiner, is_non_word, LoopScan};
use super::workspace::{NO_LABEL, UNREACHABLE};
use super::{by_score, Candidate, Point, Source, Tuning, RESULT_K};
use crate::fuzzy::Tier;
use crate::kernels::band;
use crate::sort;
use crate::strict_math;

pub const MAX_TOKENS: usize = 24;
pub const SLOTS: usize = 2;
pub const MAX_SAMPLES: usize = 48;
pub const MAX_STATES: usize = 4096;

const MS: usize = MAX_SAMPLES;
const MT: usize = MAX_TOKENS;
const MIN_STROKE_LENGTH: f32 = 0.5;
const MIN_WORD_LENGTH: i32 = 2;
const MAX_WORD_LENGTH: i32 = 24;
const CLOCKLESS_SPACING: i64 = 1_000;
const EPS: f64 = 1e-9;

fn ln1p(v: i32) -> f64 {
    strict_math::ln1p_count(v)
}

/// `DuetBeam.Duet`: the two-thumb weights.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Duet {
    pub order_slack_ms: i64,
    pub tap_window_ms: i64,
    pub tap_sigma: f32,
    pub tap_near_radius: f32,
    pub tap_max_cost: f32,
    pub tap_repeat_cost: f32,
    pub samples_per_key: f32,
    pub min_samples: i32,
}

impl Default for Duet {
    fn default() -> Self {
        Duet {
            order_slack_ms: 60,
            tap_window_ms: 120,
            tap_sigma: 0.5,
            tap_near_radius: 1.3,
            tap_max_cost: 6.0,
            tap_repeat_cost: 1.2,
            samples_per_key: 6.0,
            min_samples: 8,
        }
    }
}

impl Duet {
    fn tap_inv_two_sigma_sq(&self) -> f32 {
        1.0 / (2.0 * self.tap_sigma * self.tap_sigma)
    }
}

/// `GlideToken`: a tap, or a swipe's whole path.
pub enum Token {
    Tap { x: f32, y: f32, t: i64 },
    Swipe(Vec<Point>),
}

impl Token {
    fn t_start(&self) -> i64 {
        match self {
            Token::Tap { t, .. } => *t,
            Token::Swipe(points) => points[0].t,
        }
    }
    fn t_end(&self) -> i64 {
        match self {
            Token::Tap { t, .. } => *t,
            Token::Swipe(points) => points[points.len() - 1].t,
        }
    }
}

/// `DuetWorkspace`.
pub struct DuetWorkspace {
    token_count: usize,
    key_count: usize,
    is_swipe: [bool; MT],
    t_start: [i64; MT],
    t_end: [i64; MT],
    samples: [usize; MT],
    arc_step: [f32; MT],
    sample_t: Vec<i64>,
    sample_x: Vec<f32>,
    sample_y: Vec<f32>,
    cost: Vec<f32>,
    near: Vec<bool>,
    start_key: Vec<bool>,
    end_key: Vec<bool>,
    must_be_done: [i32; MT],
    must_be_begun: [i32; MT],
    tap_hi: Vec<i32>,
    tap_lo: Vec<i32>,
    loop_scan: LoopScan,
    loop_tok: Vec<usize>,
    loop_key: Vec<usize>,
    key_loop: Vec<f32>,

    node: Vec<u32>,
    parent: Vec<i32>,
    via_label: Vec<u16>,
    letter_cp: Vec<i32>,
    last_tok: Vec<i32>,
    last_key: Vec<i32>,
    length: Vec<i32>,
    done: Vec<i32>,
    settled: Vec<f32>,
    bound: Vec<f64>,
    slot_tok: Vec<i32>,
    slot_key: Vec<i32>,
    slot_min: Vec<i32>,
    slot_floor: Vec<f32>,
    cols: Vec<f32>,
    heap: Vec<u32>,

    scratch: [f32; SLOTS * MS],
    scratch_tok: [i32; SLOTS],
    scratch_key: [i32; SLOTS],
    scratch_min: [i32; SLOTS],
    scratch_floor: [f32; SLOTS],
    children: Vec<(u16, u32)>,
    score_scratch: Vec<f64>,
}

impl Default for DuetWorkspace {
    fn default() -> Self {
        DuetWorkspace {
            token_count: 0,
            key_count: 0,
            is_swipe: [false; MT],
            t_start: [0; MT],
            t_end: [0; MT],
            samples: [0; MT],
            arc_step: [0.0; MT],
            sample_t: vec![0; MT * MS],
            sample_x: vec![0.0; MT * MS],
            sample_y: vec![0.0; MT * MS],
            cost: Vec::new(),
            near: Vec::new(),
            start_key: Vec::new(),
            end_key: Vec::new(),
            must_be_done: [0; MT],
            must_be_begun: [0; MT],
            tap_hi: vec![0; MT * MT],
            tap_lo: vec![0; MT * MT],
            loop_scan: LoopScan::default(),
            loop_tok: Vec::new(),
            loop_key: Vec::new(),
            key_loop: Vec::new(),
            node: Vec::new(),
            parent: Vec::new(),
            via_label: Vec::new(),
            letter_cp: Vec::new(),
            last_tok: Vec::new(),
            last_key: Vec::new(),
            length: Vec::new(),
            done: Vec::new(),
            settled: Vec::new(),
            bound: Vec::new(),
            slot_tok: Vec::new(),
            slot_key: Vec::new(),
            slot_min: Vec::new(),
            slot_floor: Vec::new(),
            cols: Vec::new(),
            heap: Vec::new(),
            scratch: [0.0; SLOTS * MS],
            scratch_tok: [0; SLOTS],
            scratch_key: [0; SLOTS],
            scratch_min: [0; SLOTS],
            scratch_floor: [0.0; SLOTS],
            children: Vec::new(),
            score_scratch: Vec::new(),
        }
    }
}

impl DuetWorkspace {
    fn prepare_keys(&mut self, keys: usize) {
        self.key_count = keys;
        let flags = MT * keys;
        for v in [&mut self.near, &mut self.start_key, &mut self.end_key] {
            v.clear();
            v.resize(flags, false);
        }
        self.key_loop.clear();
        self.key_loop.resize(flags, 0.0);
        self.loop_tok.clear();
        self.loop_key.clear();
        let costs = MT * MS * keys;
        if self.cost.len() < costs {
            self.cost.resize(costs, 0.0);
        }
    }

    fn add_loop(&mut self, tok: usize, key: usize) {
        self.loop_tok.push(tok);
        self.loop_key.push(key);
        self.key_loop[tok * self.key_count + key] = 1.0;
    }

    fn reset(&mut self) {
        self.node.clear();
        self.parent.clear();
        self.via_label.clear();
        self.letter_cp.clear();
        self.last_tok.clear();
        self.last_key.clear();
        self.length.clear();
        self.done.clear();
        self.settled.clear();
        self.bound.clear();
        self.slot_tok.clear();
        self.slot_key.clear();
        self.slot_min.clear();
        self.slot_floor.clear();
        self.cols.clear();
        self.heap.clear();
    }

    fn load_scratch(&mut self, s: usize) {
        self.scratch_tok.copy_from_slice(&self.slot_tok[s * SLOTS..s * SLOTS + SLOTS]);
        self.scratch_key.copy_from_slice(&self.slot_key[s * SLOTS..s * SLOTS + SLOTS]);
        self.scratch_min.copy_from_slice(&self.slot_min[s * SLOTS..s * SLOTS + SLOTS]);
        self.scratch_floor.copy_from_slice(&self.slot_floor[s * SLOTS..s * SLOTS + SLOTS]);
        self.scratch.copy_from_slice(&self.cols[s * SLOTS * MS..(s + 1) * SLOTS * MS]);
    }

    fn clear_scratch_slot(&mut self, slot: usize) {
        self.scratch_tok[slot] = -1;
        self.scratch_key[slot] = -1;
        self.scratch_min[slot] = 0;
        self.scratch_floor[slot] = 0.0;
    }

    /// A state with the scratch's slots; `None` once the pool is full.
    #[allow(clippy::too_many_arguments)]
    fn push(
        &mut self,
        node: u32,
        parent: i32,
        via_label: u16,
        letter_cp: i32,
        last_tok: i32,
        last_key: i32,
        length: i32,
        done: i32,
        settled: f32,
        bound: f64,
    ) -> Option<usize> {
        if self.node.len() >= MAX_STATES {
            return None;
        }
        let id = self.node.len();
        self.node.push(node);
        self.parent.push(parent);
        self.via_label.push(via_label);
        self.letter_cp.push(letter_cp);
        self.last_tok.push(last_tok);
        self.last_key.push(last_key);
        // A short in Kotlin; never past MAX_WORD_LENGTH here.
        self.length.push(length);
        self.done.push(done);
        self.settled.push(settled);
        self.bound.push(bound);
        self.slot_tok.extend_from_slice(&self.scratch_tok);
        self.slot_key.extend_from_slice(&self.scratch_key);
        self.slot_min.extend_from_slice(&self.scratch_min);
        self.slot_floor.extend_from_slice(&self.scratch_floor);
        self.cols.extend_from_slice(&self.scratch);
        self.heap.push(id as u32);
        let mut i = self.heap.len() - 1;
        while i > 0 {
            let up = (i - 1) >> 1;
            if self.bound[self.heap[up] as usize] >= self.bound[self.heap[i] as usize] {
                break;
            }
            self.heap.swap(up, i);
            i = up;
        }
        Some(id)
    }

    fn column_of(s: usize, slot: usize) -> usize {
        (s * SLOTS + slot) * MS
    }

    fn pop_best(&mut self) -> usize {
        let top = self.heap[0];
        let last = self.heap.pop().expect("pop on an empty heap");
        if !self.heap.is_empty() {
            self.heap[0] = last;
            let n = self.heap.len();
            let mut i = 0;
            loop {
                let left = i * 2 + 1;
                if left >= n {
                    break;
                }
                let right = left + 1;
                let mut best = left;
                if right < n && self.bound[self.heap[right] as usize] > self.bound[self.heap[left] as usize] {
                    best = right;
                }
                if self.bound[self.heap[i] as usize] >= self.bound[self.heap[best] as usize] {
                    break;
                }
                self.heap.swap(i, best);
                i = best;
            }
        }
        top as usize
    }

    fn materialize(&self, s: usize) -> Vec<u16> {
        let mut units = Vec::new();
        let mut cur = s as i32;
        while cur >= 0 {
            let label = self.via_label[cur as usize];
            if label != NO_LABEL {
                units.push(label);
            }
            cur = self.parent[cur as usize];
        }
        units.reverse();
        units
    }

    fn open_floors(&self) -> f32 {
        let mut sum = 0f32;
        for slot in 0..SLOTS {
            if self.scratch_tok[slot] >= 0 {
                sum += self.scratch_floor[slot];
            }
        }
        sum
    }
}

fn is_high(u: u16) -> bool {
    (0xD800..0xDC00).contains(&u)
}

fn is_low(u: u16) -> bool {
    (0xDC00..0xE000).contains(&u)
}

/// The decoder: `DuetBeam(tuning, duet)` with its workspace.
#[derive(Default)]
pub struct DuetDecoder {
    pub tuning: Tuning,
    pub duet: Duet,
    ws: DuetWorkspace,
}

impl DuetDecoder {
    /// `decode`: up to `limit` words for `tokens`, best first.
    pub fn decode(
        &mut self,
        tokens: &[Token],
        keys: &KeyMap,
        key_width: f32,
        sources: &[Source],
        limit: usize,
        partial: bool,
    ) -> Vec<Candidate> {
        if tokens.is_empty() || tokens.len() > MT {
            return Vec::new();
        }
        if key_width <= 0.0 || keys.key_count() == 0 || sources.is_empty() || limit == 0 {
            return Vec::new();
        }
        self.prepare(tokens, keys, key_width);
        let k = (limit * 2).max(RESULT_K);
        let mut results: HashMap<Vec<u16>, Candidate> = HashMap::new();
        let mut floor = f64::NEG_INFINITY;
        let fw = self.tuning.freq_weight;
        let root_bound = |s: &Source| s.log_weight + fw * ln1p(s.walker.max_subtree(s.walker.root()));
        let mut ordered: Vec<&Source> = sources.iter().collect();
        sort::by(&mut ordered, |a, b| root_bound(b).total_cmp(&root_bound(a)));
        for src in ordered {
            if root_bound(src) < floor - EPS {
                continue;
            }
            floor = self.search_one(src, keys, k, &mut results, floor, partial);
        }
        let mut out: Vec<Candidate> = results.into_values().collect();
        sort::by(&mut out, by_score);
        out.truncate(limit);
        out
    }

    // ---- preparation ----

    fn prepare(&mut self, tokens: &[Token], keys: &KeyMap, key_width: f32) {
        let count = tokens.len();
        self.ws.token_count = count;
        self.ws.prepare_keys(keys.key_count());
        let clockless = tokens.iter().all(|t| t.t_start() == 0 && t.t_end() == 0);
        let mut sorted: Vec<&Token> = tokens.iter().collect();
        if !clockless {
            sort::by_key(&mut sorted, |t| t.t_start());
        }
        for (i, &token) in sorted.iter().enumerate() {
            let prepared = match token {
                Token::Swipe(points) => self.prepare_swipe(i, points, keys, key_width),
                Token::Tap { .. } => false,
            };
            if !prepared {
                let (x, y) = match token {
                    Token::Tap { x, y, .. } => (*x, *y),
                    Token::Swipe(points) => (points[0].x, points[0].y),
                };
                self.prepare_tap(i, x / key_width, y / key_width, keys);
            }
            if clockless {
                self.ws.t_start[i] = i as i64 * CLOCKLESS_SPACING;
                self.ws.t_end[i] = i as i64 * CLOCKLESS_SPACING + CLOCKLESS_SPACING / 2;
            } else {
                self.ws.t_start[i] = token.t_start();
                self.ws.t_end[i] = if self.ws.is_swipe[i] { token.t_end() } else { token.t_start() };
            }
        }
        let ws = &mut self.ws;
        if clockless {
            for i in 0..count {
                if !ws.is_swipe[i] {
                    continue;
                }
                let n = ws.samples[i];
                let span = ws.t_end[i] - ws.t_start[i];
                for j in 0..n {
                    ws.sample_t[i * MS + j] = ws.t_start[i] + span * j as i64 / (n as i64 - 1).max(1);
                }
            }
        }
        let slack = self.duet.order_slack_ms;
        for i in 0..count {
            let (mut must_done, mut must_begun) = (0i32, 0i32);
            for j in 0..count {
                if j == i {
                    continue;
                }
                if ws.t_end[j] + slack < ws.t_start[i] {
                    must_done |= 1 << j;
                }
                if ws.t_start[j] + slack < ws.t_start[i] {
                    must_begun |= 1 << j;
                }
            }
            ws.must_be_done[i] = must_done;
            ws.must_be_begun[i] = must_begun;
        }
        for t in 0..count {
            if ws.is_swipe[t] {
                continue;
            }
            let at = ws.t_start[t];
            for s in 0..count {
                if !ws.is_swipe[s] {
                    continue;
                }
                let n = ws.samples[s];
                let time = at + self.duet.tap_window_ms;
                let mut hi = n as i32 - 1;
                while hi >= 0 && ws.sample_t[s * MS + hi as usize] > time {
                    hi -= 1;
                }
                ws.tap_hi[t * MT + s] = hi;
                let time = at - self.duet.tap_window_ms;
                let mut lo = 0usize;
                while lo < n && ws.sample_t[s * MS + lo] < time {
                    lo += 1;
                }
                ws.tap_lo[t * MT + s] = lo as i32;
            }
        }
    }

    fn prepare_tap(&mut self, i: usize, x: f32, y: f32, keys: &KeyMap) {
        let ws = &mut self.ws;
        ws.is_swipe[i] = false;
        ws.samples[i] = 1;
        ws.arc_step[i] = 0.0;
        ws.sample_x[i * MS] = x;
        ws.sample_y[i * MS] = y;
        let kc = keys.key_count();
        let base = i * MS * kc;
        let near_sq = self.duet.tap_near_radius * self.duet.tap_near_radius;
        let to_units = (1.0 / self.tuning.shape_weight) as f32;
        let inv = self.duet.tap_inv_two_sigma_sq();
        for k in 0..kc {
            let dx = x - keys.key_x[k];
            let dy = y - keys.key_y[k];
            let d = dx * dx + dy * dy;
            let nats = java_min_f32(d * inv, self.duet.tap_max_cost);
            ws.cost[base + k] = nats * to_units;
            ws.near[i * kc + k] = d <= near_sq;
        }
    }

    fn prepare_swipe(&mut self, i: usize, path: &[Point], keys: &KeyMap, key_width: f32) -> bool {
        if path.len() < 2 {
            return false;
        }
        let mut total_px = 0f32;
        for p in 1..path.len() {
            let dx = path[p].x - path[p - 1].x;
            let dy = path[p].y - path[p - 1].y;
            total_px += (dx * dx + dy * dy).sqrt();
        }
        let total_kw = total_px / key_width;
        if total_kw < MIN_STROKE_LENGTH {
            return false;
        }
        let n = ((total_kw * self.duet.samples_per_key).ceil() as i32)
            .wrapping_add(1)
            .clamp(self.duet.min_samples, MS as i32) as usize;
        self.ws.is_swipe[i] = true;
        self.ws.samples[i] = n;
        self.ws.arc_step[i] = total_kw / (n - 1) as f32;
        self.resample(path, key_width, total_px / (n - 1) as f32, n, i * MS);

        let tuning = self.tuning.clone();
        let ws = &mut self.ws;
        let kc = keys.key_count();
        let inv = tuning.inv_two_sigma_sq();
        let max_point_cost = tuning.max_point_cost;
        let near_cost = tuning.near_cost();
        for j in 0..n {
            let base = (i * MS + j) * kc;
            let px = ws.sample_x[i * MS + j];
            let py = ws.sample_y[i * MS + j];
            for k in 0..kc {
                let dx = px - keys.key_x[k];
                let dy = py - keys.key_y[k];
                let scaled = (dx * dx + dy * dy) * inv;
                ws.cost[base + k] = if scaled > max_point_cost { max_point_cost } else { scaled };
                if scaled <= near_cost {
                    ws.near[i * kc + k] = true;
                }
            }
        }
        let anchor = |x: f32, y: f32, out: &mut [bool], radius_sq: f32| {
            for k in 0..kc {
                let dx = x - keys.key_x[k];
                let dy = y - keys.key_y[k];
                out[k] = dx * dx + dy * dy <= radius_sq;
            }
        };
        let (sx, sy) = (ws.sample_x[i * MS], ws.sample_y[i * MS]);
        anchor(sx, sy, &mut ws.start_key[i * kc..(i + 1) * kc], tuning.start_radius_sq());
        let (ex, ey) = (ws.sample_x[i * MS + n - 1], ws.sample_y[i * MS + n - 1]);
        anchor(ex, ey, &mut ws.end_key[i * kc..(i + 1) * kc], tuning.end_radius_sq());
        self.anchor_slack(i, 0, tuning.start_slack(), true, keys);
        self.anchor_slack(i, n - 1, tuning.end_slack(), false, keys);
        self.find_loops(i, path, keys, key_width);
        true
    }

    fn find_loops(&mut self, i: usize, path: &[Point], keys: &KeyMap, key_width: f32) {
        let tuning = self.tuning.clone();
        let found = self.ws.loop_scan.find(path, key_width, &tuning);
        for w in 0..found {
            if !self.ws.loop_scan.window_is_loop[w] {
                continue;
            }
            let (from, to) = (self.ws.loop_scan.window_from[w], self.ws.loop_scan.window_to[w]);
            self.ws.loop_scan.measure(from, to);
            let scan = &self.ws.loop_scan;
            let own = scan.nearest_key(scan.centre_x, scan.centre_y, keys, tuning.loop_radius_sq());
            if own >= 0 {
                self.ws.add_loop(i, own as usize);
            }
        }
    }

    fn resample(&mut self, path: &[Point], key_width: f32, step_px: f32, n: usize, offset: usize) {
        let ws = &mut self.ws;
        let first = &path[0];
        ws.sample_x[offset] = first.x / key_width;
        ws.sample_y[offset] = first.y / key_width;
        ws.sample_t[offset] = first.t;
        let mut out = 1;
        let mut index = 0;
        let (mut cx, mut cy, mut ct) = (first.x, first.y, first.t);
        let mut accumulated = 0f32;
        while out < n - 1 && index < path.len() - 1 {
            let next = &path[index + 1];
            let segment = ((next.x - cx) * (next.x - cx) + (next.y - cy) * (next.y - cy)).sqrt();
            if accumulated + segment >= step_px && segment > 0.0 {
                let fraction = (step_px - accumulated) / segment;
                cx += fraction * (next.x - cx);
                cy += fraction * (next.y - cy);
                // Long times Float is a Float in Kotlin, truncated back.
                ct += (next.t.wrapping_sub(ct) as f32 * fraction) as i64;
                ws.sample_x[offset + out] = cx / key_width;
                ws.sample_y[offset + out] = cy / key_width;
                ws.sample_t[offset + out] = ct;
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
        let last = &path[path.len() - 1];
        while out < n {
            ws.sample_x[offset + out] = last.x / key_width;
            ws.sample_y[offset + out] = last.y / key_width;
            ws.sample_t[offset + out] = last.t;
            out += 1;
        }
    }

    fn anchor_slack(&mut self, i: usize, j: usize, slack: f32, start: bool, keys: &KeyMap) {
        if slack <= 0.0 {
            return;
        }
        let ws = &mut self.ws;
        let kc = keys.key_count();
        let base = (i * MS + j) * kc;
        let inv = self.tuning.inv_two_sigma_sq();
        let max_point_cost = self.tuning.max_point_cost;
        for k in 0..kc {
            let dx = ws.sample_x[i * MS + j] - keys.key_x[k];
            let dy = ws.sample_y[i * MS + j] - keys.key_y[k];
            let past = (dx * dx + dy * dy).sqrt() - slack;
            let scaled = if past > 0.0 { past * past * inv } else { 0.0 };
            ws.cost[base + k] = if scaled > max_point_cost { max_point_cost } else { scaled };
            let mask = if start { ws.start_key[i * kc + k] } else { ws.end_key[i * kc + k] };
            if mask {
                ws.near[i * kc + k] = true;
            }
        }
    }

    fn anchor_lead(&self, tok: usize, j: usize, key: i32, slack: f32, keys: &KeyMap) -> f32 {
        if slack <= 0.0 || key < 0 {
            return 0.0;
        }
        let dx = self.ws.sample_x[tok * MS + j] - keys.key_x[key as usize];
        let dy = self.ws.sample_y[tok * MS + j] - keys.key_y[key as usize];
        let d = (dx * dx + dy * dy).sqrt();
        if d < slack {
            d
        } else {
            slack
        }
    }

    // ---- the walk ----

    #[allow(clippy::too_many_arguments, clippy::cognitive_complexity)]
    fn search_one(
        &mut self,
        src: &Source,
        keys: &KeyMap,
        k: usize,
        results: &mut HashMap<Vec<u16>, Candidate>,
        floor_in: f64,
        partial: bool,
    ) -> f64 {
        let mut floor = floor_in;
        let walker = src.walker;
        let kc = keys.key_count();
        let count = self.ws.token_count;
        let all_done: i32 = if count >= 32 { -1 } else { (1i32 << count) - 1 };
        let min_frequency = src.min_frequency;
        let weight = self.tuning.shape_weight;
        let mut eligible = [0usize; MT];

        self.ws.reset();
        for slot in 0..SLOTS {
            self.ws.clear_scratch_slot(slot);
        }
        // The pool starts with its scratch columns as the Kotlin arrays
        // start: zeroed.
        self.ws.scratch = [0.0; SLOTS * MS];
        let root = walker.root();
        self.ws.push(root, -1, NO_LABEL, 0, -1, -1, 0, 0, 0.0, src.log_weight + self.tuning.freq_weight * ln1p(walker.max_subtree(root)));

        let mut children = std::mem::take(&mut self.ws.children);
        let mut pops = 0;
        let max_pops = self.tuning.max_pops.max(1);
        while !self.ws.heap.is_empty() && pops < max_pops {
            let s = self.ws.pop_best();
            if self.ws.bound[s] < floor - EPS {
                break;
            }
            pops += 1;

            let node = self.ws.node[s];
            let length = self.ws.length[s];
            let done = self.ws.done[s];
            let settled = self.ws.settled[s];
            let mut open_mask = 0i32;
            let mut free_slot: i32 = -1;
            for slot in 0..SLOTS {
                let tok = self.ws.slot_tok[s * SLOTS + slot];
                if tok >= 0 {
                    open_mask |= 1 << tok;
                } else if free_slot < 0 {
                    free_slot = slot as i32;
                }
            }

            let emits = open_mask == 0
                && done == all_done
                && if partial {
                    length >= 1 && walker.max_subtree(node) >= min_frequency.max(1)
                } else {
                    length >= MIN_WORD_LENGTH && walker.is_word(node) && walker.frequency(node) >= min_frequency
                };
            if emits {
                let frequency = if partial { walker.max_subtree(node) } else { walker.frequency(node) };
                let cost = settled + self.unclaimed_loop(s);
                let score = src.log_weight + self.tuning.freq_weight * ln1p(frequency) - weight * cost as f64;
                if score > floor - EPS || results.len() < k {
                    let word = self.ws.materialize(s);
                    if partial || !(src.tier == Tier::Dictionary && is_non_word(&word)) {
                        let better = match results.get(&word) {
                            None => true,
                            Some(existing) => score > existing.score,
                        };
                        if better {
                            results.insert(
                                word.clone(),
                                Candidate { word, score, shape_cost: cost as f64, tier: src.tier, ahead: 0 },
                            );
                        }
                        if results.len() >= k {
                            floor = self.kth_best(results, k);
                        }
                    }
                }
            }

            // Closing an open swipe.
            let mut saturated = false;
            for slot in 0..SLOTS {
                let tok = self.ws.slot_tok[s * SLOTS + slot];
                if tok < 0 {
                    continue;
                }
                let tok_u = tok as usize;
                let end = self.ws.cols[DuetWorkspace::column_of(s, slot) + self.ws.samples[tok_u] - 1];
                if end >= UNREACHABLE {
                    continue;
                }
                if !self.ws.end_key[tok_u * kc + self.ws.slot_key[s * SLOTS + slot] as usize] {
                    continue;
                }
                self.ws.load_scratch(s);
                self.ws.clear_scratch_slot(slot);
                let child_settled = settled + end;
                let bound = src.log_weight + self.tuning.freq_weight * ln1p(walker.max_subtree(node)) - weight * (child_settled + self.ws.open_floors()) as f64;
                if bound < floor - EPS {
                    continue;
                }
                let (cp, lt, lk) = (self.ws.letter_cp[s], self.ws.last_tok[s], self.ws.last_key[s]);
                if self.ws.push(node, s as i32, NO_LABEL, cp, lt, lk, length, done | (1 << tok), child_settled, bound).is_none() {
                    saturated = true;
                    break;
                }
            }
            let _ = saturated;
            if length >= MAX_WORD_LENGTH {
                continue;
            }

            let mut eligible_count = 0;
            let begun = done | open_mask;
            for t in 0..count {
                let bit = 1i32 << t;
                if begun & bit != 0 {
                    continue;
                }
                if self.ws.must_be_done[t] & !done != 0 {
                    continue;
                }
                if self.ws.must_be_begun[t] & !begun != 0 {
                    continue;
                }
                if self.ws.is_swipe[t] && free_slot < 0 {
                    continue;
                }
                eligible[eligible_count] = t;
                eligible_count += 1;
            }
            let last_tok = self.ws.last_tok[s];
            let tap_repeatable = last_tok >= 0 && !self.ws.is_swipe[last_tok as usize];
            if eligible_count == 0 && open_mask == 0 && !tap_repeatable {
                continue;
            }

            walker.children_into(node, &mut children);
            let via = self.ws.via_label[s];
            let pending_high = if is_high(via) { Some(via) } else { None };
            'children: for &(label, child) in children.iter() {
                let subtree = walker.max_subtree(child);
                if subtree < min_frequency {
                    continue;
                }
                let head = src.log_weight + self.tuning.freq_weight * ln1p(subtree);

                let code_point: i32;
                if let Some(high) = pending_high {
                    if !is_low(label) {
                        continue;
                    }
                    code_point = 0x10000 + (((high as i32) - 0xD800) << 10) + ((label as i32) - 0xDC00);
                } else if is_high(label) {
                    if !self.silent(s, child, label, settled, head, floor) {
                        break;
                    }
                    continue;
                } else {
                    code_point = label as i32;
                }

                let key = keys.key_index(code_point);
                if key < 0 && length > 0 && is_joiner(code_point) && !is_joiner(self.ws.via_label[s] as i32) {
                    if !self.silent(s, child, label, settled + self.tuning.joiner_cost, head, floor) {
                        break;
                    }
                    continue;
                }
                if key < 0 || key as usize >= kc {
                    continue;
                }
                let key_u = key as usize;

                let repeat = length > 0 && code_point == self.ws.letter_cp[s];
                if repeat && last_tok >= 0 {
                    let lt = last_tok as usize;
                    let charge = if self.ws.is_swipe[lt] {
                        if open_mask & (1 << last_tok) == 0 {
                            f32::NAN
                        } else {
                            (self.tuning.repeat_cost + self.tuning.unlooped_repeat) * (1.0 - self.ws.key_loop[lt * kc + key_u])
                        }
                    } else {
                        self.duet.tap_repeat_cost * (1.0 / weight) as f32
                    };
                    if !charge.is_nan() {
                        self.ws.load_scratch(s);
                        let child_settled = settled + charge;
                        let bound = head - weight * (child_settled + self.ws.open_floors()) as f64;
                        if bound >= floor - EPS
                            && self
                                .ws
                                .push(child, s as i32, label, code_point, last_tok, key, length + 1, done, child_settled, bound)
                                .is_none()
                        {
                            break 'children;
                        }
                    }
                }

                // A tap.
                for &t in eligible.iter().take(eligible_count) {
                    if self.ws.is_swipe[t] || !self.ws.near[t * kc + key_u] {
                        continue;
                    }
                    self.ws.load_scratch(s);
                    if !self.place_tap_among_open(t) {
                        continue;
                    }
                    let child_settled = settled + self.ws.cost[t * MS * kc + key_u];
                    let bound = head - weight * (child_settled + self.ws.open_floors()) as f64;
                    if bound < floor - EPS {
                        continue;
                    }
                    if self
                        .ws
                        .push(child, s as i32, label, code_point, t as i32, key, length + 1, done | (1 << t), child_settled, bound)
                        .is_none()
                    {
                        break;
                    }
                }

                // The first letter of a swipe, on its first sample.
                if free_slot >= 0 {
                    let fs = free_slot as usize;
                    for &t in eligible.iter().take(eligible_count) {
                        if !self.ws.is_swipe[t] {
                            continue;
                        }
                        if !self.ws.start_key[t * kc + key_u] || !self.ws.near[t * kc + key_u] {
                            continue;
                        }
                        self.ws.load_scratch(s);
                        let base = fs * MS;
                        let first = self.ws.cost[t * MS * kc + key_u];
                        self.ws.scratch[base] = first;
                        for j in 1..MS {
                            self.ws.scratch[base + j] = UNREACHABLE;
                        }
                        self.ws.scratch_tok[fs] = t as i32;
                        self.ws.scratch_key[fs] = key;
                        self.ws.scratch_min[fs] = 1;
                        self.ws.scratch_floor[fs] = first;
                        let bound = head - weight * (settled + self.ws.open_floors()) as f64;
                        if bound < floor - EPS {
                            continue;
                        }
                        if self
                            .ws
                            .push(child, s as i32, label, code_point, t as i32, key, length + 1, done, settled, bound)
                            .is_none()
                        {
                            break;
                        }
                    }
                }

                // The next letter along an open swipe.
                for slot in 0..SLOTS {
                    let tok = self.ws.slot_tok[s * SLOTS + slot];
                    if tok < 0 || !self.ws.near[tok as usize * kc + key_u] {
                        continue;
                    }
                    let prev_key = self.ws.slot_key[s * SLOTS + slot];
                    if prev_key == key && self.ws.last_tok[s] == tok {
                        continue;
                    }
                    self.ws.load_scratch(s);
                    let min = self.advance(s, slot, tok as usize, prev_key, key, keys);
                    if min >= UNREACHABLE {
                        continue;
                    }
                    self.ws.scratch_key[slot] = key;
                    self.ws.scratch_floor[slot] = min;
                    let bound = head - weight * (settled + self.ws.open_floors()) as f64;
                    if bound < floor - EPS {
                        continue;
                    }
                    if self
                        .ws
                        .push(child, s as i32, label, code_point, tok, key, length + 1, done, settled, bound)
                        .is_none()
                    {
                        break;
                    }
                }
            }
        }
        self.ws.children = children;
        floor
    }

    fn place_tap_among_open(&mut self, t: usize) -> bool {
        let ws = &mut self.ws;
        for slot in 0..SLOTS {
            let tok = ws.scratch_tok[slot];
            if tok < 0 {
                continue;
            }
            let tok = tok as usize;
            let n = ws.samples[tok];
            let hi = ws.tap_hi[t * MT + tok];
            let base = slot * MS;
            let mut best = UNREACHABLE;
            for j in 0..n {
                if j as i32 > hi {
                    ws.scratch[base + j] = UNREACHABLE;
                }
                let v = ws.scratch[base + j];
                if v < best {
                    best = v;
                }
            }
            if best >= UNREACHABLE {
                return false;
            }
            ws.scratch_floor[slot] = best;
            let lo = ws.tap_lo[t * MT + tok];
            if lo > ws.scratch_min[slot] {
                ws.scratch_min[slot] = lo;
            }
        }
        true
    }

    fn advance(&mut self, s: usize, slot: usize, tok: usize, prev_key: i32, key: i32, keys: &KeyMap) -> f32 {
        let n = self.ws.samples[tok];
        let kc = keys.key_count();
        let step = self.ws.arc_step[tok];
        let span = keys.distance(prev_key as usize, key as usize);
        let expected = span / step;
        let lo = 1.max((expected - self.tuning.gap_window) as i32);
        let hi = lo.max((expected + self.tuning.gap_window) as i32 + 1);
        let prev = DuetWorkspace::column_of(s, slot);
        let out = slot * MS;
        let first = 1.max(self.ws.scratch_min[slot]) as usize;
        for j in 0..first.min(n) {
            self.ws.scratch[out + j] = UNREACHABLE;
        }
        let lead = self.anchor_lead(tok, 0, prev_key, self.tuning.start_slack(), keys);
        let tail = self.anchor_lead(tok, n - 1, key, self.tuning.end_slack(), keys);
        let gap_weight = self.tuning.gap_weight;
        let ws = &mut self.ws;
        let mut best = UNREACHABLE;
        for j in first..n {
            let from = 0.max(j as i32 - hi);
            let to = j as i32 - lo;
            let ease_j = if j == n - 1 { tail } else { 0.0 };
            let mut best_prev = UNREACHABLE;
            let mut i = from;
            // Sample 0 eases by the lead too; the rest of the band is one
            // kernel call (kernels::band, the same arithmetic four at a time).
            if i == 0 && i <= to {
                let at = ws.cols[prev];
                if at < UNREACHABLE {
                    let gap = j as i32 as f32 * step - span;
                    let over = (if gap < 0.0 { -gap } else { gap }) - (lead + ease_j);
                    let v = at + if over > 0.0 { gap_weight * over } else { 0.0 };
                    if v < best_prev {
                        best_prev = v;
                    }
                }
                i = 1;
            }
            if i <= to {
                let cols = &ws.cols[prev + i as usize..prev + to as usize + 1];
                let v = band::min_steps(cols, j as i32 - i, step, span, ease_j, gap_weight, UNREACHABLE);
                if v < best_prev {
                    best_prev = v;
                }
            }
            let value = if best_prev >= UNREACHABLE {
                UNREACHABLE
            } else {
                best_prev + ws.cost[(tok * MS + j) * kc + key as usize]
            };
            ws.scratch[out + j] = value;
            if value < best {
                best = value;
            }
        }
        best
    }

    /// A step over an edge the gestures say nothing about; false when the
    /// pool saturated.
    fn silent(&mut self, s: usize, child: u32, label: u16, settled: f32, head: f64, floor: f64) -> bool {
        self.ws.load_scratch(s);
        let bound = head - self.tuning.shape_weight * (settled + self.ws.open_floors()) as f64;
        if bound < floor - EPS {
            return true;
        }
        let (cp, lt, lk, len, done) =
            (self.ws.letter_cp[s], self.ws.last_tok[s], self.ws.last_key[s], self.ws.length[s], self.ws.done[s]);
        self.ws.push(child, s as i32, label, cp, lt, lk, len, done, settled, bound).is_some()
    }

    fn unclaimed_loop(&self, s: usize) -> f32 {
        let weight = self.tuning.unclaimed_loop;
        let ws = &self.ws;
        if weight <= 0.0 || ws.loop_tok.is_empty() {
            return 0.0;
        }
        let mut charge = 0f32;
        for e in 0..ws.loop_tok.len() {
            let tok = ws.loop_tok[e] as i32;
            let own = ws.loop_key[e] as i32;
            let mut claimed = false;
            let mut cur = s as i32;
            while cur >= 0 && !claimed {
                let c = cur as usize;
                let parent = ws.parent[c];
                if parent >= 0 {
                    let p = parent as usize;
                    if ws.letter_cp[c] == ws.letter_cp[p] && ws.length[c] == ws.length[p] + 1 {
                        claimed = ws.last_key[c] == own && (ws.last_tok[c] == tok || ws.last_tok[p] == tok);
                    }
                }
                cur = parent;
            }
            if !claimed {
                charge += 1.0;
            }
        }
        weight * charge
    }

    fn kth_best(&mut self, results: &HashMap<Vec<u16>, Candidate>, k: usize) -> f64 {
        let size = results.len();
        if size < k {
            return f64::NEG_INFINITY;
        }
        let scores = &mut self.ws.score_scratch;
        scores.clear();
        scores.extend(results.values().map(|c| c.score));
        scores.sort_by(f64::total_cmp);
        scores[size - k]
    }
}

/// Kotlin's `minOf(Float, Float)`: NaN wins, and -0 below +0.
fn java_min_f32(a: f32, b: f32) -> f32 {
    if a.is_nan() || b.is_nan() {
        f32::NAN
    } else if a == b {
        if a.is_sign_negative() {
            a
        } else {
            b
        }
    } else if a < b {
        a
    } else {
        b
    }
}
