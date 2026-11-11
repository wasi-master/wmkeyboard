//! `GlideWorkspace`: the decoder's pooled state, per thread. The heap is
//! the Kotlin one's, so states of equal bound pop in the same order.

/// The most samples a stroke is resampled to, and what every per-sample
/// array holds. See [`samples_for`].
pub const SAMPLE_POINTS: usize = 96;
pub const SHORT_SAMPLES: usize = 48;
/// Strokes at least this long, in key widths, get the finer sampling.
pub const LONG_STROKE: f32 = 40.0;
/// Samples a stroke of exactly `LONG_STROKE` gets.
pub const LONG_SAMPLES: usize = 64;
/// A letter's worth of stroke, in key widths: the mean key-to-key step over
/// the English list, drawn centre to centre, is 2.95.
pub const LETTER_ARC: f32 = 3.0;
/// Samples each `LETTER_ARC` past `LONG_STROKE` adds.
pub const SAMPLES_PER_LETTER: usize = 2;
pub const SHAPE_POINTS: usize = 48;

/// `KotlinGlideWorkspace.samplesFor`: how many samples a stroke of `arc`
/// key widths is resampled to. `SHORT_SAMPLES` under `LONG_STROKE`,
/// `LONG_SAMPLES` at it, and `SAMPLES_PER_LETTER` more for every
/// `LETTER_ARC` beyond, up to `SAMPLE_POINTS` (issue #966 §5.3). The
/// letter count is read off the arc because the stroke is resampled before
/// any word is known; the arc predicts it well (r = 0.93 on the issue's
/// real strokes). Every stroke under `LONG_STROKE` is sampled exactly as it
/// was before the scaling, and so is one at it, so the eval floors on
/// strokes of up to fourteen letters stand.
pub fn samples_for(arc: f32) -> usize {
    if arc < LONG_STROKE {
        SHORT_SAMPLES
    } else {
        let letters = ((arc - LONG_STROKE) / LETTER_ARC) as usize;
        (LONG_SAMPLES + SAMPLES_PER_LETTER * letters).min(SAMPLE_POINTS)
    }
}
/// Frontier cap; the pool grows only when a long stroke needs it.
///
/// It was 8,192. At the 4,000-pop budget that cap bound again on real German
/// swipes: 12,288 states lifted top-1 from 75.81 to 76.31 and top-64 coverage
/// from 87.13 % to 87.75 %, for ~4.5 MB of columns at the longest stroke.
/// 16,384 gained nothing more on the same set.
pub const MAX_STATES: usize = 12288;
/// Longest key sequence a candidate's ideal path can have: a word of
/// `MAX_WORD_LENGTH` letters visits at most that many keys, plus the two
/// anchor leads the shape channel adds.
pub const MAX_IDEAL_POINTS: usize = 34;
pub const UNREACHABLE: f32 = f32::MAX / 4.0;
pub const NO_LABEL: u16 = 0;
/// `part_freq` of a state that has joined nothing: no cap on the frequency.
pub const NO_PART: i32 = i32::MAX;

pub struct Workspace {
    pub node: Vec<u32>,
    pub parent: Vec<i32>,
    pub via_label: Vec<u16>,
    pub letter_cp: Vec<i32>,
    pub last_key: Vec<i32>,
    /// A short in Kotlin; never past `MAX_WORD_LENGTH` here.
    pub length: Vec<i32>,
    pub extra: Vec<f32>,
    pub floor_cost: Vec<f32>,
    pub bound: Vec<f64>,
    /// Compounds (#966): the least frequency among the parts this state has
    /// already finished, `NO_PART` before any join; the length at which the
    /// current part began; and how many joins the chain has made. Copied
    /// from the parent on every push, so the walk only touches them where
    /// it joins.
    pub part_freq: Vec<i32>,
    pub part_from: Vec<i32>,
    pub joins: Vec<i32>,
    /// The `join_part_penalty` nats the parts this state has joined past
    /// have earned between them; 0.0 before any join.
    pub join_penalty: Vec<f64>,
    pub samples: usize,
    pub cols: Vec<f32>,
    heap: Vec<u32>,

    pub path_x: [f32; SAMPLE_POINTS],
    pub path_y: [f32; SAMPLE_POINTS],
    pub path_t: [i64; SAMPLE_POINTS],
    pub arc_step: f32,
    pub point_cost: Vec<f32>,
    pub remain: Vec<f32>,
    /// `point_cost` and `remain` of the near keys, key by key
    /// (`key * SAMPLE_POINTS + sample`): what `build_remain`'s band reads.
    pub point_cost_t: Vec<f32>,
    pub remain_t: Vec<f32>,
    pub near_list: Vec<usize>,
    pub near_count: usize,

    pub pause_at: [usize; SAMPLE_POINTS],
    pub pause_score: [f32; SAMPLE_POINTS],
    pub pause_x: [f32; SAMPLE_POINTS],
    pub pause_y: [f32; SAMPLE_POINTS],
    pub pause_reach_sq: [f32; SAMPLE_POINTS],
    pub pause_count: usize,

    pub arc_at: [f32; SAMPLE_POINTS],
    pub collapsed_samples: i32,
    /// Open ends (#968): the first letter may stand on any sample up to
    /// `open_head_to`, and the word may end on any sample from
    /// `open_tail_from`; the samples before or after are the walk from or
    /// to the spacebar and cost nothing when no letter claims them. `0` and
    /// `samples - 1` for an ordinary stroke.
    pub open_head_to: usize,
    pub open_tail_from: usize,
    /// Whether either end is open: the shape channel then stands down.
    pub open_ends: bool,
    pub loop_from: [usize; SAMPLE_POINTS],
    pub loop_to: [usize; SAMPLE_POINTS],
    pub loop_x: [f32; SAMPLE_POINTS],
    pub loop_y: [f32; SAMPLE_POINTS],
    pub loop_score: [f32; SAMPLE_POINTS],
    pub loop_key: [i32; SAMPLE_POINTS],
    pub loop_shrink: [f32; SAMPLE_POINTS],
    pub loop_count: usize,
    pub key_loop: Vec<f32>,
    pub near_key: Vec<bool>,
    pub start_key: Vec<bool>,
    pub end_key: Vec<bool>,

    pub children: Vec<(u16, u32)>,
    pub look_ahead_children: Vec<(u16, u32)>,
    pub score_scratch: Vec<f64>,
    pub scratch: [f32; SAMPLE_POINTS],
    pub drawn_shape_x: [f32; SHAPE_POINTS],
    pub drawn_shape_y: [f32; SHAPE_POINTS],
    pub drawn_shape8: [i8; 2 * SHAPE_POINTS],
    /// The drawn shape with its doubling loops cut, what the ideal paths are
    /// compared against (see `Decoder::plain_shape`).
    pub plain_shape_x: [f32; SHAPE_POINTS],
    pub plain_shape_y: [f32; SHAPE_POINTS],
    pub ideal_shape_x: [f32; SHAPE_POINTS],
    pub ideal_shape_y: [f32; SHAPE_POINTS],
    pub learned_shape_x: [f32; SHAPE_POINTS],
    pub learned_shape_y: [f32; SHAPE_POINTS],
    pub ideal_x: [f32; MAX_IDEAL_POINTS + 1],
    pub ideal_y: [f32; MAX_IDEAL_POINTS + 1],
    pub saturated: bool,
    /// The frontier cap for the current decode: [`MAX_STATES`] unless the
    /// caller asks for a smaller per-decode backstop. `push` refuses past it.
    pub max_states: usize,
}

impl Workspace {
    pub fn new() -> Workspace {
        Workspace {
            node: Vec::new(),
            parent: Vec::new(),
            via_label: Vec::new(),
            letter_cp: Vec::new(),
            last_key: Vec::new(),
            length: Vec::new(),
            extra: Vec::new(),
            floor_cost: Vec::new(),
            bound: Vec::new(),
            part_freq: Vec::new(),
            part_from: Vec::new(),
            joins: Vec::new(),
            join_penalty: Vec::new(),
            samples: SHORT_SAMPLES,
            cols: Vec::new(),
            heap: Vec::new(),
            path_x: [0.0; SAMPLE_POINTS],
            path_y: [0.0; SAMPLE_POINTS],
            path_t: [0; SAMPLE_POINTS],
            arc_step: 0.0,
            point_cost: Vec::new(),
            remain: Vec::new(),
            point_cost_t: Vec::new(),
            remain_t: Vec::new(),
            near_list: Vec::new(),
            near_count: 0,
            pause_at: [0; SAMPLE_POINTS],
            pause_score: [0.0; SAMPLE_POINTS],
            pause_x: [0.0; SAMPLE_POINTS],
            pause_y: [0.0; SAMPLE_POINTS],
            pause_reach_sq: [0.0; SAMPLE_POINTS],
            pause_count: 0,
            arc_at: [0.0; SAMPLE_POINTS],
            collapsed_samples: 0,
            open_head_to: 0,
            open_tail_from: 0,
            open_ends: false,
            loop_from: [0; SAMPLE_POINTS],
            loop_to: [0; SAMPLE_POINTS],
            loop_x: [0.0; SAMPLE_POINTS],
            loop_y: [0.0; SAMPLE_POINTS],
            loop_score: [0.0; SAMPLE_POINTS],
            loop_key: [0; SAMPLE_POINTS],
            loop_shrink: [0.0; SAMPLE_POINTS],
            loop_count: 0,
            key_loop: Vec::new(),
            near_key: Vec::new(),
            start_key: Vec::new(),
            end_key: Vec::new(),
            children: Vec::new(),
            look_ahead_children: Vec::new(),
            score_scratch: Vec::new(),
            scratch: [0.0; SAMPLE_POINTS],
            drawn_shape_x: [0.0; SHAPE_POINTS],
            drawn_shape_y: [0.0; SHAPE_POINTS],
            drawn_shape8: [0; 2 * SHAPE_POINTS],
            plain_shape_x: [0.0; SHAPE_POINTS],
            plain_shape_y: [0.0; SHAPE_POINTS],
            ideal_shape_x: [0.0; SHAPE_POINTS],
            ideal_shape_y: [0.0; SHAPE_POINTS],
            learned_shape_x: [0.0; SHAPE_POINTS],
            learned_shape_y: [0.0; SHAPE_POINTS],
            ideal_x: [0.0; MAX_IDEAL_POINTS + 1],
            ideal_y: [0.0; MAX_IDEAL_POINTS + 1],
            saturated: false,
            max_states: MAX_STATES,
        }
    }

    pub fn reset(&mut self) {
        self.node.clear();
        self.parent.clear();
        self.via_label.clear();
        self.letter_cp.clear();
        self.last_key.clear();
        self.length.clear();
        self.extra.clear();
        self.floor_cost.clear();
        self.bound.clear();
        self.part_freq.clear();
        self.part_from.clear();
        self.joins.clear();
        self.join_penalty.clear();
        self.heap.clear();
        self.saturated = false;
    }

    pub fn heap_size(&self) -> usize {
        self.heap.len()
    }

    pub fn prepare_keys(&mut self, key_count: usize) {
        for v in [&mut self.near_key, &mut self.start_key, &mut self.end_key] {
            v.clear();
            v.resize(key_count, false);
        }
        self.key_loop.clear();
        self.key_loop.resize(key_count, 0.0);
        self.pause_count = 0;
        self.loop_count = 0;
        let needed = SAMPLE_POINTS * key_count;
        if self.point_cost.len() < needed {
            self.point_cost.resize(needed, 0.0);
        }
        if self.remain.len() < needed {
            self.remain.resize(needed, 0.0);
        }
        if self.point_cost_t.len() < needed {
            self.point_cost_t.resize(needed, 0.0);
            self.remain_t.resize(needed, 0.0);
        }
        self.near_list.clear();
        self.near_list.resize(key_count, 0);
        self.near_count = 0;
    }

    /// `push`: a state, scheduled by its bound; `None` once the pool is full.
    #[allow(clippy::too_many_arguments)]
    pub fn push(
        &mut self,
        node: u32,
        parent: i32,
        via_label: u16,
        last_key: i32,
        length: i32,
        extra: f32,
        floor_cost: f32,
        bound: f64,
        letter_cp: i32,
    ) -> Option<usize> {
        if self.node.len() >= self.max_states.max(1) {
            self.saturated = true;
            return None;
        }
        let id = self.node.len();
        self.node.push(node);
        self.parent.push(parent);
        self.via_label.push(via_label);
        self.letter_cp.push(letter_cp);
        self.last_key.push(last_key);
        self.length.push(length);
        self.extra.push(extra);
        self.floor_cost.push(floor_cost);
        self.bound.push(bound);
        if parent >= 0 {
            let p = parent as usize;
            self.part_freq.push(self.part_freq[p]);
            self.part_from.push(self.part_from[p]);
            self.joins.push(self.joins[p]);
            self.join_penalty.push(self.join_penalty[p]);
        } else {
            self.part_freq.push(NO_PART);
            self.part_from.push(0);
            self.joins.push(0);
            self.join_penalty.push(0.0);
        }
        let needed = (id + 1) * self.samples.max(1);
        if self.cols.len() < needed {
            self.cols.resize(needed.max(self.cols.len() * 2), 0.0);
        }
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

    pub fn column_of(&self, id: usize) -> usize {
        id * self.samples
    }

    pub fn store_scratch(&mut self, id: usize) {
        let n = self.samples;
        self.cols[id * n..id * n + n].copy_from_slice(&self.scratch[..n]);
    }

    pub fn copy_column(&mut self, from: usize, to: usize) {
        let n = self.samples;
        self.cols.copy_within(from * n..from * n + n, to * n);
    }

    pub fn pop_best(&mut self) -> usize {
        let top = self.heap[0];
        let last = self.heap.pop().expect("pop on an empty heap");
        if !self.heap.is_empty() {
            self.heap[0] = last;
            let mut i = 0;
            let n = self.heap.len();
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

    /// The word state `s` spells, from its parent chain, in UTF-16 units.
    pub fn materialize(&self, s: usize) -> Vec<u16> {
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
}

impl Default for Workspace {
    fn default() -> Self {
        Workspace::new()
    }
}
