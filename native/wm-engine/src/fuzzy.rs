//! Trie-guided fuzzy search: corrections and completions in one best-first
//! walk. The Kotlin `FuzzyBeamSearch` (core/prediction), line for line: the
//! phone runs this through JNI, and the parity tests hold the two to the
//! same candidates, scores and edit counts.
//!
//! Instead of generating every edit of the typed word and probing the
//! dictionary, the walk descends the trie itself, so only words the
//! dictionary holds are ever considered: two-edit corrections stay
//! affordable, a mid-word prefix is corrected while it is completed, and
//! substitutions come from the trie's own edge labels, in any script.
//!
//! Scoring is log-space: `score = sourceLogWeight + ln(1 + frequency) -
//! editCost`, the edit penalties being the negative logs of the legacy
//! multiplicative weights. A word reachable several ways keeps its best
//! score. The expansion order is admissible best-first: a state's bound is
//! `sourceLogWeight + ln(1 + maxSubtree(node)) - cost`, which only ever
//! overestimates, so pruning against the K-th best never loses a top-K word.
//!
//! Text is UTF-16 code units throughout, as the dictionaries store it and as
//! the Kotlin walk reads it. Logarithms are [`strict_math::log`], fdlibm's,
//! which is what the JVM's `Math.log` returns on the machines the evals run
//! on; the platform `ln` rounds about one value in a hundred differently.
//! Touch evidence ([`TouchScoring`]), ambiguous keys ([`KeySets`]) and the
//! learned slip habits ([`EditHabits`]) are optional parameters.

use crate::accents;
use crate::proximity::KeyProximity;
use crate::sort;
use crate::strict_math;
use crate::wmdict::Dict;
use std::collections::HashMap;

/// Node-level read access to a trie. Handles are plain integers; the root
/// is 0.
pub trait Walker {
    fn root(&self) -> u32 {
        0
    }
    fn child(&self, node: u32, label: u16) -> Option<u32>;
    /// Every `(label, child)` of `node` into `out`, cleared first, in the
    /// order the Kotlin walker of the same trie lists them.
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>);
    fn is_word(&self, node: u32) -> bool;
    fn frequency(&self, node: u32) -> i32;
    /// The highest word frequency anywhere under `node`, itself included.
    fn max_subtree(&self, node: u32) -> i32;
}

impl<B: AsRef<[u8]>> Walker for Dict<B> {
    fn child(&self, node: u32, label: u16) -> Option<u32> {
        Dict::child(self, node, label)
    }
    fn children_into(&self, node: u32, out: &mut Vec<(u16, u32)>) {
        Dict::children_into(self, node, out)
    }
    fn is_word(&self, node: u32) -> bool {
        Dict::is_word(self, node)
    }
    fn frequency(&self, node: u32) -> i32 {
        Dict::frequency(self, node)
    }
    fn max_subtree(&self, node: u32) -> i32 {
        Dict::max_subtree(self, node)
    }
}

/// How a learned slip habit shrinks an edit's price: 0 for none, up to 1.
/// The Kotlin `EditHabits`.
pub trait EditHabits {
    fn deletion(&self, _typed: u16) -> f64 {
        0.0
    }
    fn substitution(&self, _typed: u16, _meant: u16) -> f64 {
        0.0
    }
    fn insertion(&self, _meant: u16) -> f64 {
        0.0
    }
}

pub struct NoHabits;
impl EditHabits for NoHabits {}
pub const NO_HABITS: NoHabits = NoHabits;

/// Habits as the Kotlin `EditHabits` stores them: sorted keys and their
/// shrinks, looked up by binary search. Substitution keys are
/// `typed << 16 | intended`.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct HabitTables {
    pub sub_keys: Vec<i32>,
    pub sub_values: Vec<f64>,
    pub insert_keys: Vec<u16>,
    pub insert_values: Vec<f64>,
    pub delete_keys: Vec<u16>,
    pub delete_values: Vec<f64>,
}

fn lookup<K: Ord>(keys: &[K], values: &[f64], key: K) -> f64 {
    keys.binary_search(&key).ok().and_then(|i| values.get(i).copied()).unwrap_or(0.0)
}

impl EditHabits for HabitTables {
    fn deletion(&self, typed: u16) -> f64 {
        lookup(&self.delete_keys, &self.delete_values, typed)
    }
    fn substitution(&self, typed: u16, meant: u16) -> f64 {
        lookup(&self.sub_keys, &self.sub_values, ((typed as i32) << 16) | meant as i32)
    }
    fn insertion(&self, meant: u16) -> f64 {
        lookup(&self.insert_keys, &self.insert_values, meant)
    }
}

/// The layout's key centres, for touch evidence: the Kotlin `KeyTouchModel`.
/// Positions are in key widths, as `f32`, the way taps arrive.
#[derive(Clone, Debug, PartialEq)]
pub struct TouchModel {
    /// In the order the Kotlin model lists them, which decides ties in
    /// [`TouchModel::best_key`].
    keys: Vec<(u16, f32, f32)>,
    index: HashMap<u16, usize>,
    sigma: f64,
    /// How far from a tap, in key widths, a key's centre may sit for the tap
    /// to be read as a slip of that key: a mistyped letter priced like the
    /// substitution it is, but outside the edit budget. 0 turns slips off.
    slip_radius: f64,
}

impl TouchModel {
    pub const SIGMA: f64 = 0.5;
    /// The default slip reach, in sigmas: 1.25 key widths at the default
    /// sigma, which takes in the keys either side of the one hit and, on a
    /// staggered board, the nearer diagonals.
    pub const SLIP_SIGMAS: f64 = 2.5;

    pub fn new(keys: Vec<(u16, f32, f32)>, sigma: f64) -> TouchModel {
        let mut index = HashMap::with_capacity(keys.len());
        for (i, &(key, _, _)) in keys.iter().enumerate() {
            // A Kotlin map holds a key once; its centre is the last one put.
            index.insert(key, i);
        }
        TouchModel { keys, index, sigma, slip_radius: TouchModel::SLIP_SIGMAS * sigma }
    }

    /// The same model with slips reaching `radius` key widths (0: none).
    pub fn with_slip_radius(mut self, radius: f64) -> TouchModel {
        self.slip_radius = radius;
        self
    }

    pub fn knows(&self, key: u16) -> bool {
        self.index.contains_key(&key)
    }

    /// Whether `key`'s centre lies within the slip reach of `p`.
    pub fn within_slip(&self, p: (f32, f32), key: u16) -> bool {
        if self.slip_radius <= 0.0 {
            return false;
        }
        let Some(&i) = self.index.get(&key) else {
            return false;
        };
        let (_, cx, cy) = self.keys[i];
        let dx = (p.0 - cx) as f64;
        let dy = (p.1 - cy) as f64;
        dx * dx + dy * dy <= self.slip_radius * self.slip_radius
    }

    /// ln P(tap at `p` | key), up to a constant: `-(d^2) / (2 sigma^2)`.
    pub fn log_likelihood(&self, p: (f32, f32), key: u16) -> f64 {
        let Some(&i) = self.index.get(&key) else {
            return f64::NEG_INFINITY;
        };
        let (_, cx, cy) = self.keys[i];
        let dx = (p.0 - cx) as f64;
        let dy = (p.1 - cy) as f64;
        -(dx * dx + dy * dy) / (2.0 * self.sigma * self.sigma)
    }

    /// How far `p` lies from the nearest key's centre, in key widths; `None`
    /// off an empty model.
    pub fn nearest_distance(&self, p: (f32, f32)) -> Option<f64> {
        self.keys
            .iter()
            .map(|&(_, cx, cy)| {
                let dx = (p.0 - cx) as f64;
                let dy = (p.1 - cy) as f64;
                (dx * dx + dy * dy).sqrt()
            })
            .fold(None, |m: Option<f64>, d| Some(m.map_or(d, |m| m.min(d))))
    }

    /// The key a tap most likely meant: the nearest centre, the first listed
    /// on a tie.
    pub fn best_key(&self, p: (f32, f32)) -> Option<u16> {
        let mut best = None;
        let mut best_score = f64::NEG_INFINITY;
        for &(key, cx, cy) in &self.keys {
            let dx = (p.0 - cx) as f64;
            let dy = (p.1 - cy) as f64;
            let score = -(dx * dx + dy * dy);
            if score > best_score {
                best_score = score;
                best = Some(key);
            }
        }
        best
    }
}

/// One keystroke's touch evidence: the model and the tap of each typed
/// character (`None` where unknown: hardware keys, pasted text).
pub struct TouchScoring<'a> {
    pub model: &'a TouchModel,
    pub points: Vec<Option<(f32, f32)>>,
    best_log_likelihoods: Vec<f64>,
}

impl<'a> TouchScoring<'a> {
    pub fn new(model: &'a TouchModel, points: Vec<Option<(f32, f32)>>) -> TouchScoring<'a> {
        let best_log_likelihoods = points
            .iter()
            .map(|p| match p.and_then(|p| model.best_key(p).map(|best| (p, best))) {
                Some((p, best)) => model.log_likelihood(p, best),
                None => f64::NEG_INFINITY,
            })
            .collect();
        TouchScoring { model, points, best_log_likelihoods }
    }

    fn point(&self, pos: usize) -> Option<(f32, f32)> {
        self.points.get(pos).copied().flatten()
    }
}

/// The letters each keystroke could have meant, on a keyboard with several
/// on a key: the Kotlin `KeySets`. `spellings[pos]` lists the members a word
/// list spells in several units (क़ as क + nukta), already normalised by the
/// caller.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct KeySets {
    sets: Vec<Vec<u16>>,
    spellings: Vec<Option<Vec<Vec<u16>>>>,
    ambiguous: bool,
}

impl KeySets {
    pub fn new(sets: Vec<Vec<u16>>, spellings: Vec<Option<Vec<Vec<u16>>>>) -> KeySets {
        let ambiguous = sets.iter().any(|s| s.len() > 1);
        KeySets { sets, spellings, ambiguous }
    }

    pub fn is_ambiguous(&self) -> bool {
        self.ambiguous
    }

    /// Keystroke `pos`'s set, or `None` where it could only mean one letter.
    pub fn at(&self, pos: usize) -> Option<&[u16]> {
        self.sets.get(pos).filter(|s| s.len() > 1).map(Vec::as_slice)
    }

    pub fn spellings_at(&self, pos: usize) -> Option<&[Vec<u16>]> {
        self.spellings.get(pos).and_then(|s| s.as_deref())
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Tier {
    Dictionary,
    User,
}

/// One weighted trie in a search.
pub struct WalkSource<'a> {
    pub walker: &'a dyn Walker,
    pub log_weight: f64,
    pub tier: Tier,
}

/// A candidate with its word as UTF-16, as the phone holds it.
#[derive(Clone, Debug, PartialEq)]
pub struct Candidate {
    pub word: Vec<u16>,
    pub score: f64,
    pub edit_cost: f64,
    pub edits: u32,
    /// Characters appended beyond the typed length by completion.
    pub completed_chars: u32,
    pub tier: Tier,
    /// Best score from dictionary-tier sources (-inf when none).
    pub dict_score: f64,
    /// Best score from user-tier sources (-inf when none).
    pub user_score: f64,
    /// Letters read as their accented form; not edits.
    pub accents: u32,
    /// Of [`Candidate::edits`], the substitutions read as slips: a tap that
    /// landed within reach of the meant key. Counted among the edits, but
    /// never against the edit budget.
    pub slips: u32,
}

#[derive(Clone, Debug, PartialEq)]
pub struct ScoredCandidate {
    pub word: String,
    pub score: f64,
    pub edit_cost: f64,
    pub edits: u32,
    /// Characters appended beyond the typed length by completion.
    pub completed_chars: u32,
    pub tier: Tier,
    /// Best score from dictionary-tier sources (-inf when none).
    pub dict_score: f64,
    /// Best score from user-tier sources (-inf when none).
    pub user_score: f64,
    /// Letters read as their accented form; not edits.
    pub accents: u32,
    /// Of the edits, the slips ([`Candidate::slips`]).
    pub slips: u32,
}

pub fn ln(x: f64) -> f64 {
    strict_math::log(x)
}

/// Two keys swapped across the hands: the ordinary racing-hands slip.
pub fn cost_transposition() -> f64 {
    -ln(0.9)
}
/// The same swap within one hand, rarer and a worse explanation.
pub fn cost_transposition_same_hand() -> f64 {
    -ln(0.7)
}
pub fn cost_sub_adjacent() -> f64 {
    -ln(0.9)
}
pub fn cost_deletion() -> f64 {
    -ln(0.7)
}
/// Deleting a char that doubles its neighbour ("helllo").
pub fn cost_delete_doubled() -> f64 {
    -ln(0.9)
}
pub fn cost_insert_adjacent() -> f64 {
    -ln(0.7)
}
pub fn cost_insert_far() -> f64 {
    -ln(0.25)
}
pub fn cost_sub_far() -> f64 {
    -ln(0.2)
}
/// A letter typed without its accent: priced like the adjacent slip, but
/// outside the edit budget.
pub fn cost_accent() -> f64 {
    -ln(0.9)
}
pub const COMPLETION_STEP: f64 = 0.0;
/// Per-character completion cost once the path holds an edit.
pub fn cost_completion_after_edit() -> f64 {
    -ln(0.25)
}
/// Two far substitutions can never survive; two adjacent slips can.
pub const MAX_EDIT_COST: f64 = 2.0;
/// The second edit's surcharge: a two-edit word ranks below any one-edit fix.
pub const SECOND_EDIT_SURCHARGE: f64 = 2.0;
/// Autocorrect wants a deeper list than the strip shows.
pub const AUTOCORRECT_K: usize = 8;
/// Cap on the rank cost an off-centre tap adds to an exact match.
pub const MATCH_CAP: f64 = 1.0;
/// Edits allowed on top of key ambiguity.
pub const AMBIGUOUS_MAX_EDITS: u32 = 1;
/// Extra cost on any edit taken while decoding ambiguous keystrokes.
pub const AMBIGUOUS_EDIT_SURCHARGE: f64 = 6.0;
/// No learned habit prices an edit under the adjacent-slip tier.
pub fn habit_floor() -> f64 {
    cost_sub_adjacent()
}
/// Runaway-state backstop.
pub const MAX_POPS: usize = 4096;
const EPS: f64 = 1e-9;
/// The Persian half-space, U+200C.
const ZWNJ: u16 = 0x200C;
const NO_LABEL: u16 = 0;

pub fn default_max_edits(typed_len: usize) -> u32 {
    if typed_len >= 5 {
        2
    } else {
        1
    }
}

/// The Kotlin walk's `ln1p(v)`, which is `ln(1.0 + v)`.
fn ln1p(v: i32) -> f64 {
    strict_math::ln1p_count(v)
}

/// `Double.coerceIn(lo, hi)`.
fn coerce_in(v: f64, lo: f64, hi: f64) -> f64 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

/// Pooled state store and index heap: the search allocates nothing per
/// keystroke beyond emitted words when a workspace is reused. The heap is
/// `BeamWorkspace`'s, so states of equal bound pop in the same order.
#[derive(Default)]
pub struct Workspace {
    node: Vec<u32>,
    pos: Vec<u16>,
    cost: Vec<f64>,
    edit_cost: Vec<f64>,
    edits: Vec<u8>,
    comp: Vec<u8>,
    accents: Vec<u8>,
    slips: Vec<u8>,
    parent: Vec<i32>,
    via: Vec<u16>,
    bound: Vec<f64>,
    heap: Vec<u32>,
    children: Vec<(u16, u32)>,
    scores: Vec<f64>,
}

impl Workspace {
    pub fn new() -> Workspace {
        Workspace::default()
    }

    fn reset(&mut self) {
        self.node.clear();
        self.pos.clear();
        self.cost.clear();
        self.edit_cost.clear();
        self.edits.clear();
        self.comp.clear();
        self.accents.clear();
        self.slips.clear();
        self.parent.clear();
        self.via.clear();
        self.bound.clear();
        self.heap.clear();
    }

    /// `pushRecord`: a state that is linked into a word but never expanded.
    #[allow(clippy::too_many_arguments)]
    fn record(
        &mut self,
        node: u32,
        pos: usize,
        cost: f64,
        edit: f64,
        edits: u32,
        comp: u32,
        accents: u32,
        slips: u32,
        parent: i32,
        via: u16,
    ) -> i32 {
        let id = self.node.len() as i32;
        self.node.push(node);
        self.pos.push(pos as u16);
        self.cost.push(cost);
        self.edit_cost.push(edit);
        // Kotlin stores these as bytes too.
        self.edits.push(edits as u8);
        self.comp.push(comp as u8);
        self.accents.push(accents as u8);
        self.slips.push(slips as u8);
        self.parent.push(parent);
        self.via.push(via);
        self.bound.push(0.0);
        id
    }

    /// `pushState`: a record scheduled for expansion by its bound.
    #[allow(clippy::too_many_arguments)]
    fn state(
        &mut self,
        node: u32,
        pos: usize,
        cost: f64,
        edit: f64,
        edits: u32,
        comp: u32,
        accents: u32,
        slips: u32,
        parent: i32,
        via: u16,
        bound: f64,
    ) {
        let id = self.record(node, pos, cost, edit, edits, comp, accents, slips, parent, via);
        self.bound[id as usize] = bound;
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
    }

    fn pop(&mut self) -> usize {
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

    /// The word for state `s`, from its parent chain, one unit at a time
    /// (a surrogate pair's halves arrive leaf first).
    fn materialize(&self, s: usize) -> Vec<u16> {
        let mut units = Vec::new();
        let mut cur = s as i32;
        while cur >= 0 {
            let label = self.via[cur as usize];
            if label != NO_LABEL {
                units.push(label);
            }
            cur = self.parent[cur as usize];
        }
        units.reverse();
        units
    }
}

fn find(children: &[(u16, u32)], label: u16) -> Option<u32> {
    children.iter().find(|(l, _)| *l == label).map(|(_, n)| *n)
}

fn discounted(base: f64, shrink: f64) -> f64 {
    if shrink <= 0.0 {
        base
    } else {
        // Kotlin's maxOf(Double, Double) is Math.max.
        let floor = habit_floor();
        let v = base * (1.0 - shrink);
        if v.is_nan() {
            v
        } else {
            floor.max(v)
        }
    }
}

/// The search over strings, for callers that hold `str`: up to
/// `max(limit * 2, AUTOCORRECT_K)` candidates, best first (ties by the word).
#[allow(clippy::too_many_arguments)]
pub fn search(
    sources: &[WalkSource],
    typed: &str,
    proximity: &KeyProximity,
    limit: usize,
    ws: &mut Workspace,
    max_edits: Option<u32>,
    habits: &dyn EditHabits,
) -> Vec<ScoredCandidate> {
    let typed: Vec<u16> = typed.encode_utf16().collect();
    let max_edits = max_edits.unwrap_or_else(|| default_max_edits(typed.len()));
    search_units(sources, &typed, proximity, limit, ws, max_edits, None, habits, None)
        .into_iter()
        .map(|c| ScoredCandidate {
            word: String::from_utf16_lossy(&c.word),
            score: c.score,
            edit_cost: c.edit_cost,
            edits: c.edits,
            completed_chars: c.completed_chars,
            tier: c.tier,
            dict_score: c.dict_score,
            user_score: c.user_score,
            accents: c.accents,
            slips: c.slips,
        })
        .collect()
}

/// `FuzzyBeamSearch.search`: up to `max(limit * 2, AUTOCORRECT_K)`
/// candidates for `typed`, best score first, ties by the word in UTF-16
/// order.
#[allow(clippy::too_many_arguments)]
pub fn search_units(
    sources: &[WalkSource],
    typed: &[u16],
    proximity: &KeyProximity,
    limit: usize,
    ws: &mut Workspace,
    max_edits: u32,
    touch: Option<&TouchScoring>,
    habits: &dyn EditHabits,
    keys: Option<&KeySets>,
) -> Vec<Candidate> {
    if typed.is_empty() || limit == 0 {
        return Vec::new();
    }
    let k = (limit * 2).max(AUTOCORRECT_K);
    let ambiguous = keys.is_some_and(KeySets::is_ambiguous);
    let edits = if ambiguous { max_edits.min(AMBIGUOUS_MAX_EDITS) } else { max_edits };
    let mut results: HashMap<Vec<u16>, Candidate> = HashMap::new();
    let mut floor = f64::NEG_INFINITY;
    // Heaviest source first: its emissions raise the floor early. A stable
    // sort, as Kotlin's sortedByDescending is.
    let mut ordered: Vec<&WalkSource> = sources.iter().collect();
    if ordered.len() > 1 {
        sort::by(&mut ordered, |a, b| root_bound(b).total_cmp(&root_bound(a)));
    }
    let walk = Walk { typed, proximity, max_edits: edits, k, touch, habits, keys, ambiguous };
    for src in ordered {
        if root_bound(src) < floor - EPS {
            continue;
        }
        floor = walk.one(src, &mut results, floor, ws);
    }
    let mut out: Vec<Candidate> = results.into_values().collect();
    sort::by(&mut out, |a, b| b.score.total_cmp(&a.score).then_with(|| a.word.cmp(&b.word)));
    out.truncate(k);
    out
}

fn root_bound(src: &WalkSource) -> f64 {
    src.log_weight + ln1p(src.walker.max_subtree(src.walker.root()))
}

/// One search's fixed inputs.
struct Walk<'a> {
    typed: &'a [u16],
    proximity: &'a KeyProximity,
    max_edits: u32,
    k: usize,
    touch: Option<&'a TouchScoring<'a>>,
    habits: &'a dyn EditHabits,
    keys: Option<&'a KeySets>,
    ambiguous: bool,
}

impl Walk<'_> {
    /// Rank cost of consuming `expected` as itself: zero without touch
    /// data, else the likelihood gap to the tap's best key, capped.
    fn match_cost(&self, pos: usize, expected: u16) -> f64 {
        let Some(touch) = self.touch else { return 0.0 };
        let Some(p) = touch.point(pos) else { return 0.0 };
        if !touch.model.knows(expected) {
            return 0.0;
        }
        let best = touch.best_log_likelihoods.get(pos).copied().unwrap_or(f64::NEG_INFINITY);
        if best == f64::NEG_INFINITY {
            return 0.0;
        }
        coerce_in(best - touch.model.log_likelihood(p, expected), 0.0, MATCH_CAP)
    }

    /// Cost of reading `expected` as a slip for `label`.
    fn substitution_cost(&self, pos: usize, expected: u16, label: u16) -> f64 {
        let point = self.touch.and_then(|t| t.point(pos).map(|p| (t, p)));
        match point {
            Some((touch, p)) if touch.model.knows(expected) && touch.model.knows(label) => {
                let gap = touch.model.log_likelihood(p, expected) - touch.model.log_likelihood(p, label);
                cost_sub_adjacent() + coerce_in(gap, 0.0, cost_sub_far() - cost_sub_adjacent())
            }
            _ => {
                if self.proximity.are_adjacent(expected, label) {
                    cost_sub_adjacent()
                } else {
                    cost_sub_far()
                }
            }
        }
    }

    /// Whether reading `label` for the typed `expected` is a slip: the tap
    /// landed within the model's slip reach of `label`'s key. Never on an
    /// ambiguous board, where every keystroke already fans out, nor in a walk
    /// that allows no edits at all.
    fn slip_reach(&self, pos: usize, expected: u16, label: u16) -> bool {
        if self.ambiguous || self.max_edits == 0 {
            return false;
        }
        let Some(touch) = self.touch else { return false };
        let Some(p) = touch.point(pos) else { return false };
        touch.model.knows(expected) && touch.model.within_slip(p, label)
    }

    /// Whether `label` is the typed `expected` with its accent left off,
    /// or another letter the same key carries (#970): read as an accent,
    /// never as an edit.
    fn alternate_of(&self, label: u16, expected: u16) -> bool {
        accents::is_accent_of(label, expected) || self.proximity.carries(expected, label)
    }

    fn one(&self, src: &WalkSource, results: &mut HashMap<Vec<u16>, Candidate>, floor_in: f64, ws: &mut Workspace) -> f64 {
        let mut floor = floor_in;
        let w = src.walker;
        let typed = self.typed;
        let n = typed.len();
        ws.reset();
        let root = w.root();
        ws.state(root, 0, 0.0, 0.0, 0, 0, 0, 0, -1, NO_LABEL, src.log_weight + ln1p(w.max_subtree(root)));
        let mut pops = 0;
        let mut children = std::mem::take(&mut ws.children);
        macro_rules! push {
            ($node:expr, $pos:expr, $cost:expr, $edit:expr, $edits:expr, $comp:expr, $acc:expr, $slips:expr, $parent:expr, $via:expr) => {{
                let node = $node;
                let cost = $cost;
                let bound = src.log_weight + ln1p(w.max_subtree(node)) - cost;
                if !(bound < floor - EPS) {
                    ws.state(node, $pos, cost, $edit, $edits, $comp, $acc, $slips, $parent, $via, bound);
                }
            }};
        }
        while !ws.heap.is_empty() && pops < MAX_POPS {
            let s = ws.pop();
            if ws.bound[s] < floor - EPS {
                break;
            }
            pops += 1;
            let node = ws.node[s];
            let pos = ws.pos[s] as usize;
            let cost = ws.cost[s];
            let edit = ws.edit_cost[s];
            let edits = ws.edits[s] as u32;
            let comp = ws.comp[s] as u32;
            let acc = ws.accents[s] as u32;
            let slips = ws.slips[s] as u32;
            let parent = s as i32;

            if pos == n {
                if w.is_word(node) {
                    let score = src.log_weight + ln1p(w.frequency(node)) - cost;
                    if score > floor - EPS || results.len() < self.k {
                        emit(ws.materialize(s), score, edit, edits, comp, acc, slips, src.tier, results);
                        if results.len() >= self.k {
                            floor = kth_best(results, self.k, &mut ws.scores);
                        }
                    }
                }
                // Completion: free on a clean prefix, charged after an edit.
                let step = if edits > 0 { cost_completion_after_edit() } else { COMPLETION_STEP };
                w.children_into(node, &mut children);
                for &(label, child) in &children {
                    push!(child, n, cost + step, edit, edits, comp + 1, acc, slips, parent, label);
                }
                continue;
            }

            let expected = typed[pos];
            let key_set = self.keys.and_then(|k| k.at(pos));
            w.children_into(node, &mut children);
            // A half-space the word is spelled with and the typing left out.
            if expected != ZWNJ {
                if let Some(joined) = find(&children, ZWNJ) {
                    push!(joined, pos, cost + cost_accent(), edit, edits, comp, acc + 1, slips, parent, ZWNJ);
                }
            }
            match key_set {
                None => {
                    // The typed character itself.
                    if let Some(matched) = find(&children, expected) {
                        push!(matched, pos + 1, cost + self.match_cost(pos, expected), edit, edits, comp, acc, slips, parent, expected);
                    }
                    // The same key with its accent left off, or with the Shift
                    // or hold that reaches one of its other letters (#970).
                    for &(label, child) in &children {
                        if !self.alternate_of(label, expected) {
                            continue;
                        }
                        push!(
                            child,
                            pos + 1,
                            cost + self.match_cost(pos, expected) + cost_accent(),
                            edit,
                            edits,
                            comp,
                            acc + 1,
                            slips,
                            parent,
                            label
                        );
                    }
                }
                Some(set) => {
                    // Every letter on the pressed key matches, free and unedited.
                    for &(label, child) in &children {
                        if !set.contains(&label) {
                            continue;
                        }
                        push!(child, pos + 1, cost + self.match_cost(pos, label), edit, edits, comp, acc, slips, parent, label);
                    }
                    // A letter on the key a word list spells in several units.
                    if let Some(spellings) = self.keys.and_then(|k| k.spellings_at(pos)) {
                        'spelled: for spelled in spellings {
                            let mut at = node;
                            let mut link = parent;
                            for &unit in &spelled[..spelled.len() - 1] {
                                match w.child(at, unit) {
                                    None => continue 'spelled,
                                    Some(next) => at = next,
                                }
                                link = ws.record(at, pos, cost, edit, edits, comp, acc, slips, link, unit);
                            }
                            let last_unit = spelled[spelled.len() - 1];
                            if let Some(last) = w.child(at, last_unit) {
                                push!(last, pos + 1, cost + self.match_cost(pos, spelled[0]), edit, edits, comp, acc, slips, link, last_unit);
                            }
                        }
                    }
                }
            }

            // The edits on this path that are not slips: what the budget counts.
            let budget = edits - slips;
            // Slips: a tap that landed within reach of another key reads as
            // that key at the substitution's price, but spends nothing of the
            // edit budget, and further slips pay no second-edit surcharge. A
            // finger that lands a little wide on every key of a word has made
            // one kind of error several times, not several mistakes (Fleksy's
            // US 9,024,882 reads whole words from the taps' shape the same
            // way). Mixing kinds is still speculation stacked on speculation,
            // so the first slip on a path that already holds a real edit pays
            // the surcharge, as a real edit after a slip does below. The noisy
            // tap eval (`noisy_tap_eval`) is where both rules were measured.
            if key_set.is_none() {
                for &(label, child) in &children {
                    if label == expected || self.alternate_of(label, expected) || !self.slip_reach(pos, expected, label) {
                        continue;
                    }
                    let mixed = if budget > 0 && slips == 0 { SECOND_EDIT_SURCHARGE } else { 0.0 };
                    let sub = discounted(
                        self.substitution_cost(pos, expected, label),
                        self.habits.substitution(expected, label),
                    );
                    push!(child, pos + 1, cost + sub + mixed, edit, edits + 1, comp, acc, slips + 1, parent, label);
                }
            }

            if budget < self.max_edits && edit < MAX_EDIT_COST {
                // Any earlier edit, slips included, makes this one a second.
                let surcharge = (if edits + 1 >= 2 { SECOND_EDIT_SURCHARGE } else { 0.0 })
                    + if self.ambiguous { AMBIGUOUS_EDIT_SURCHARGE } else { 0.0 };
                // Deletion: the typed character was an extra keypress.
                let doubled = (pos + 1 < n && typed[pos + 1] == expected) || (pos > 0 && typed[pos - 1] == expected);
                let del = discounted(
                    if doubled { cost_delete_doubled() } else { cost_deletion() },
                    self.habits.deletion(expected),
                );
                if edit + del <= MAX_EDIT_COST {
                    push!(node, pos + 1, cost + del + surcharge, edit + del, edits + 1, comp, acc, slips, parent, NO_LABEL);
                }
                for &(label, child) in &children {
                    let on_key = key_set.is_some_and(|set| set.contains(&label));
                    let accented = key_set.is_none() && self.alternate_of(label, expected);
                    if label != expected && !on_key && !accented && !self.slip_reach(pos, expected, label) {
                        let sub = discounted(
                            self.substitution_cost(pos, expected, label),
                            self.habits.substitution(expected, label),
                        );
                        if edit + sub <= MAX_EDIT_COST {
                            push!(child, pos + 1, cost + sub + surcharge, edit + sub, edits + 1, comp, acc, slips, parent, label);
                        }
                    }
                    // Insertion: the word has `label` here and the typing missed it.
                    let adjacent = self.proximity.are_adjacent(expected, label)
                        || (pos > 0 && (typed[pos - 1] == label || self.proximity.are_adjacent(typed[pos - 1], label)));
                    let ins = discounted(
                        if adjacent { cost_insert_adjacent() } else { cost_insert_far() },
                        self.habits.insertion(label),
                    );
                    if edit + ins <= MAX_EDIT_COST {
                        push!(child, pos, cost + ins + surcharge, edit + ins, edits + 1, comp, acc, slips, parent, label);
                    }
                }
                // Transposition of the next two typed characters, priced by hand.
                let transpose = if pos + 1 < n && self.proximity.same_hand(typed[pos], typed[pos + 1]) {
                    cost_transposition_same_hand()
                } else {
                    cost_transposition()
                };
                let next_unambiguous = self.keys.and_then(|k| k.at(pos + 1)).is_none();
                if pos + 1 < n
                    && typed[pos] != typed[pos + 1]
                    && key_set.is_none()
                    && next_unambiguous
                    && edit + transpose <= MAX_EDIT_COST
                {
                    if let Some(first) = find(&children, typed[pos + 1]) {
                        if let Some(second) = w.child(first, typed[pos]) {
                            let link = ws.record(first, pos + 1, cost, edit, edits, comp, acc, slips, parent, typed[pos + 1]);
                            push!(second, pos + 2, cost + transpose + surcharge, edit + transpose, edits + 1, comp, acc, slips, link, typed[pos]);
                        }
                    }
                }
            }
        }
        ws.children = children;
        floor
    }
}

#[allow(clippy::too_many_arguments)]
fn emit(
    word: Vec<u16>,
    score: f64,
    edit_cost: f64,
    edits: u32,
    completed: u32,
    accents: u32,
    slips: u32,
    tier: Tier,
    results: &mut HashMap<Vec<u16>, Candidate>,
) {
    let existing = results.get(&word);
    let mine_dict = if tier == Tier::Dictionary { score } else { f64::NEG_INFINITY };
    let mine_user = if tier == Tier::User { score } else { f64::NEG_INFINITY };
    let dict_score = java_max(existing.map_or(f64::NEG_INFINITY, |e| e.dict_score), mine_dict);
    let user_score = java_max(existing.map_or(f64::NEG_INFINITY, |e| e.user_score), mine_user);
    let next = match existing {
        Some(e) if !(score > e.score) => Candidate { dict_score, user_score, ..e.clone() },
        _ => Candidate {
            word: word.clone(),
            score,
            edit_cost,
            edits,
            completed_chars: completed,
            tier,
            dict_score,
            user_score,
            accents,
            slips,
        },
    };
    results.insert(word, next);
}

/// Kotlin's `maxOf(Double, Double)`, which is `Math.max`: NaN wins, and
/// 0.0 beats -0.0.
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

/// The k-th best score so far: every score sorted ascending (as
/// `DoubleArray.sort`), then the one k from the top.
fn kth_best(results: &HashMap<Vec<u16>, Candidate>, k: usize, buf: &mut Vec<f64>) -> f64 {
    let count = results.len();
    if count < k {
        return f64::NEG_INFINITY;
    }
    buf.clear();
    buf.extend(results.values().map(|c| c.score));
    buf.sort_by(f64::total_cmp);
    buf[count - k]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::wmdict::{self, PackedTrie};

    fn dict(entries: &[(&str, i32)]) -> Dict {
        Dict::open(wmdict::write(&PackedTrie::of(entries))).unwrap()
    }

    fn run(
        sources: &[WalkSource],
        typed: &str,
        limit: usize,
        max_edits: Option<u32>,
    ) -> Vec<ScoredCandidate> {
        search(
            sources,
            typed,
            &KeyProximity::qwerty(false),
            limit,
            &mut Workspace::new(),
            max_edits,
            &NO_HABITS,
        )
    }

    fn src(d: &Dict, w: f64) -> WalkSource<'_> {
        WalkSource {
            walker: d,
            log_weight: w,
            tier: Tier::Dictionary,
        }
    }

    fn real() -> Vec<(String, i32)> {
        let text = std::fs::read_to_string(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../app/dictionaries-src/en.txt"
        ))
        .expect("the app's en.txt");
        crate::wordlist::parse(&text)
    }

    #[test]
    fn doubled_letter_deletion_costs_the_cheap_tier() {
        let d = dict(&[("hello", 100)]);
        let got = &run(&[src(&d, 0.0)], "helllo", 5, None)[0];
        assert_eq!("hello", got.word);
        assert!((got.edit_cost - cost_delete_doubled()).abs() < 1e-9);
    }

    #[test]
    fn missed_doubling_inserts_at_adjacent_cost() {
        let d = dict(&[("running", 100)]);
        let got = &run(&[src(&d, 0.0)], "runing", 5, None)[0];
        assert_eq!("running", got.word);
        assert!((got.edit_cost - cost_insert_adjacent()).abs() < 1e-9);
    }

    #[test]
    fn zero_edit_walk_matches_classic_completion() {
        let entries = real();
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        for prefix in ["th", "he", "wo", "pre", "a"] {
            let classic: Vec<String> = d.complete(prefix, 10).into_iter().map(|s| s.word).collect();
            let beam: Vec<String> = run(&[src(&d, 0.0)], prefix, 10, Some(0))
                .into_iter()
                .map(|c| c.word)
                .filter(|w| w.starts_with(prefix))
                .collect();
            assert_eq!(
                classic,
                beam[..classic.len()].to_vec(),
                "completion parity for '{prefix}'"
            );
        }
    }

    /// The legacy Norvig edits-1 generator: candidate -> best weight.
    fn norvig(word: &str, p: &KeyProximity) -> HashMap<String, f64> {
        let w: Vec<char> = word.chars().collect();
        let u = |c: char| c as u16;
        let mut out: HashMap<String, f64> = HashMap::new();
        let mut put = |c: String, weight: f64| {
            if c != word {
                let e = out.entry(c).or_insert(weight);
                *e = e.max(weight);
            }
        };
        for i in 0..w.len() {
            let mut v = w.clone();
            v.remove(i);
            put(v.iter().collect(), 0.7);
        }
        for i in 0..w.len().saturating_sub(1) {
            let mut v = w.clone();
            v.swap(i, i + 1);
            put(v.iter().collect(), 0.9);
        }
        for i in 0..w.len() {
            for c in 'a'..='z' {
                if c == w[i] {
                    continue;
                }
                let mut v = w.clone();
                v[i] = c;
                put(
                    v.iter().collect(),
                    if p.are_adjacent(u(w[i]), u(c)) {
                        0.9
                    } else {
                        0.2
                    },
                );
            }
        }
        for i in 0..=w.len() {
            for c in 'a'..='z' {
                let near = (i > 0 && p.are_adjacent(u(w[i - 1]), u(c)))
                    || (i < w.len() && p.are_adjacent(u(w[i]), u(c)));
                let mut v = w.clone();
                v.insert(i, c);
                put(v.iter().collect(), if near { 0.7 } else { 0.25 });
            }
        }
        out
    }

    #[test]
    fn norvig_candidates_all_surface_with_matching_scores() {
        let entries = real();
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        let q = KeyProximity::qwerty(false);
        for typed in ["helo", "wrold", "teh", "beleive", "recieve", "adress"] {
            let beam: HashMap<String, ScoredCandidate> = run(&[src(&d, 0.0)], typed, 400, Some(1))
                .into_iter()
                .map(|c| (c.word.clone(), c))
                .collect();
            for (cand, weight) in norvig(typed, &q) {
                let freq = d.frequency_of(&cand);
                if freq <= 0 || cand == typed {
                    continue;
                }
                let expected_cost = if cand.starts_with(typed) {
                    0.0
                } else {
                    -weight.ln()
                };
                let expected = (1.0 + freq as f64).ln() - expected_cost;
                let got = beam
                    .get(&cand)
                    .unwrap_or_else(|| panic!("'{typed}' -> '{cand}' missing"));
                assert!(
                    got.score >= expected - 1e-6,
                    "'{typed}' -> '{cand}' {} < {expected}",
                    got.score
                );
            }
        }
    }

    #[test]
    fn two_adjacent_slips_are_reachable() {
        let d = dict(&[("hello", 100)]);
        let r = run(&[src(&d, 0.0)], "gwllo", 5, None);
        assert_eq!(1, r.len());
        assert_eq!(("hello", 2), (r[0].word.as_str(), r[0].edits));
    }

    #[test]
    fn two_far_substitutions_are_not_reachable() {
        let d = dict(&[("hello", 100)]);
        assert!(run(&[src(&d, 0.0)], "hzlqo", 5, None).is_empty());
    }

    #[test]
    fn correction_and_completion_unify() {
        let d = dict(&[("the", 100), ("thermometer", 40)]);
        let words: Vec<String> = run(&[src(&d, 0.0)], "teh", 5, None)
            .into_iter()
            .map(|c| c.word)
            .collect();
        assert!(words.contains(&"the".to_string()) && words.contains(&"thermometer".to_string()));
    }

    #[test]
    fn non_ascii_corrections_come_from_trie_edges() {
        let d = dict(&[("привет", 50)]);
        let r = run(&[src(&d, 0.0)], "правет", 5, None);
        assert_eq!(
            vec!["привет".to_string()],
            r.into_iter().map(|c| c.word).collect::<Vec<_>>()
        );
    }

    #[test]
    fn source_order_does_not_change_results() {
        let a = dict(&[("hello", 70), ("help", 60)]);
        let b = dict(&[("helm", 40), ("hell", 30)]);
        let f: Vec<(String, f64)> = run(&[src(&a, 0.0), src(&b, 2f64.ln())], "helo", 8, None)
            .into_iter()
            .map(|c| (c.word, c.score))
            .collect();
        let r: Vec<(String, f64)> = run(&[src(&b, 2f64.ln()), src(&a, 0.0)], "helo", 8, None)
            .into_iter()
            .map(|c| (c.word, c.score))
            .collect();
        assert_eq!(f, r);
    }

    #[test]
    fn max_merge_across_sources_keeps_best_score_and_tier() {
        let d = dict(&[("hello", 10)]);
        let user = dict(&[("hello", 10)]);
        let r = run(
            &[
                src(&d, 0.0),
                WalkSource {
                    walker: &user,
                    log_weight: 500f64.ln(),
                    tier: Tier::User,
                },
            ],
            "hello",
            4,
            None,
        );
        let hello = r.iter().find(|c| c.word == "hello").unwrap();
        assert_eq!(Tier::User, hello.tier);
        assert!((hello.score - (500f64.ln() + 11f64.ln())).abs() < 1e-9);
    }

    /// Which correction gate an eval run applies: the one-edit shape from
    /// before slips, or `SlipFixes`' careful and eager modes.
    #[derive(Clone, Copy, Debug, PartialEq)]
    enum Gate {
        Old,
        Careful,
        Eager,
    }

    /// decideOrdinary's apply rule, roughly, under `gate`: the best fix with
    /// no completion must clear the runner-up by ln 4. A reading that mixes
    /// slips with a real edit is a target only when eager, and even then
    /// never the runner-up that blocks a cleaner fix. A known word is the
    /// caller's business.
    fn fires(got: &[Candidate], gate: Gate) -> Option<&Candidate> {
        let mixed = |c: &Candidate| c.slips > 0 && c.edits > c.slips;
        let shaped: Vec<&Candidate> = got
            .iter()
            .take(AUTOCORRECT_K)
            .filter(|c| c.completed_chars == 0 && c.edits >= 1 && c.edits - c.slips <= 1)
            .filter(|c| match gate {
                Gate::Old => c.edits == 1,
                Gate::Careful => !mixed(c),
                Gate::Eager => true,
            })
            .collect();
        let best = shaped.first()?;
        let runner = if gate == Gate::Eager && !mixed(best) {
            shaped.iter().skip(1).find(|c| !mixed(c))
        } else {
            shaped.get(1)
        };
        (best.score - runner.map_or(1.0, |c| c.score) >= 4f64.ln()).then_some(*best)
    }

    /// The eval's QWERTY: key centres in key widths, rows a key apart and
    /// staggered a quarter and three quarters of a key.
    fn qwerty_keys() -> Vec<(u16, f32, f32)> {
        let mut keys = Vec::new();
        for (row, (letters, offset)) in [("qwertyuiop", 0.0f32), ("asdfghjkl", 0.25), ("zxcvbnm", 0.75)].iter().enumerate() {
            for (col, ch) in letters.chars().enumerate() {
                keys.push((ch as u16, offset + col as f32, row as f32));
            }
        }
        keys
    }

    fn centre(ch: char) -> (f32, f32) {
        let (_, x, y) = qwerty_keys().into_iter().find(|k| k.0 == ch as u16).unwrap();
        (x, y)
    }

    /// A tap `share` of the way from `from`'s centre to `to`'s.
    fn toward(from: char, to: char, share: f32) -> Option<(f32, f32)> {
        let (a, b) = (centre(from), centre(to));
        Some((a.0 + (b.0 - a.0) * share, a.1 + (b.1 - a.1) * share))
    }

    fn touch_search(d: &Dict, typed: &str, taps: Vec<Option<(f32, f32)>>, model: &TouchModel) -> Vec<Candidate> {
        let units: Vec<u16> = typed.encode_utf16().collect();
        let scoring = TouchScoring::new(model, taps);
        search_units(
            &[src(d, 0.0)],
            &units,
            &KeyProximity::qwerty(false),
            4,
            &mut Workspace::new(),
            default_max_edits(units.len()),
            Some(&scoring),
            &NO_HABITS,
            None,
        )
    }

    #[test]
    fn three_wide_taps_are_three_slips_not_three_edits() {
        let d = dict(&[("the", 1000), ("rho", 5)]);
        // Every tap a little past the edge of the key meant: "rgw" for "the".
        let taps = vec![toward('t', 'r', 0.55), toward('h', 'g', 0.55), toward('e', 'w', 0.55)];
        let model = TouchModel::new(qwerty_keys(), TouchModel::SIGMA);
        let got = touch_search(&d, "rgw", taps.clone(), &model);
        let the = got.iter().find(|c| c.word == "the".encode_utf16().collect::<Vec<_>>()).expect("the");
        assert_eq!((3, 3), (the.edits, the.slips));
        assert_eq!(0.0, the.edit_cost, "slips spend nothing of the budget");
        // Without slips a three-letter word allows one edit, and "the" is out of reach.
        let off = model.clone().with_slip_radius(0.0);
        assert!(touch_search(&d, "rgw", taps, &off).iter().all(|c| c.word != "the".encode_utf16().collect::<Vec<_>>()));
    }

    #[test]
    fn a_slip_leaves_the_edit_budget_for_a_real_edit() {
        let d = dict(&[("thew", 50)]);
        // "rhw" for "thew": r for t is a slip, and the missing e is the one
        // edit a three-letter word allows.
        let taps = vec![toward('t', 'r', 0.55), Some(centre('h')), Some(centre('w'))];
        let model = TouchModel::new(qwerty_keys(), TouchModel::SIGMA);
        let got = touch_search(&d, "rhw", taps, &model);
        assert_eq!(1, got.len());
        assert_eq!((2, 1), (got[0].edits, got[0].slips));
    }

    #[test]
    fn a_far_key_is_never_a_slip() {
        let d = dict(&[("cat", 100)]);
        // A tap dead on q, typed "qat": c is four keys away.
        let model = TouchModel::new(qwerty_keys(), TouchModel::SIGMA);
        let got = touch_search(&d, "qat", vec![Some(centre('q')), Some(centre('a')), Some(centre('t'))], &model);
        assert!(got.iter().all(|c| c.slips == 0));
    }

    /// The noisy-tap walk eval: words from the real list typed by a Gaussian
    /// finger, read with and without slips. Prints a table; run with
    /// `cargo test --release noisy_tap_eval -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn noisy_tap_eval() {
        let entries = real();
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        let usable: Vec<&(String, i32)> = entries
            .iter()
            .filter(|(w, _)| w.len() >= 3 && w.chars().all(|c| c.is_ascii_lowercase()))
            .collect();
        let mut cumulative = Vec::with_capacity(usable.len());
        let mut total = 0f64;
        for (_, f) in &usable {
            total += (*f).max(1) as f64;
            cumulative.push(total);
        }
        let keys = qwerty_keys();
        let nearest = |p: (f32, f32)| {
            keys.iter()
                .min_by(|a, b| {
                    let da = (a.1 - p.0).powi(2) + (a.2 - p.1).powi(2);
                    let db = (b.1 - p.0).powi(2) + (b.2 - p.1).powi(2);
                    da.total_cmp(&db)
                })
                .unwrap()
                .0
        };
        let mut seed = 0x2545_f491_4f6c_dd1du64;
        let mut uniform = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };
        let ws = &mut Workspace::new();
        let q = KeyProximity::qwerty(false);
        for (sx, sy) in [(0.25, 0.3), (0.35, 0.45), (0.45, 0.55)] {
            let mut cases = Vec::new();
            while cases.len() < 3000 {
                let target = uniform() * total;
                let i = cumulative.partition_point(|&c| c <= target).min(usable.len() - 1);
                let word = &usable[i].0;
                let mut taps = Vec::new();
                for ch in word.chars() {
                    let (x, y) = centre(ch);
                    let g = |u: &mut dyn FnMut() -> f64| {
                        let (u1, u2) = (u().max(1e-12), u());
                        (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
                    };
                    let nx = g(&mut uniform) * sx;
                    let ny = g(&mut uniform) * sy;
                    taps.push(Some((x + nx as f32, y + ny as f32)));
                }
                let typed: Vec<u16> = taps.iter().map(|p| nearest(p.unwrap())).collect();
                let meant: Vec<u16> = word.encode_utf16().collect();
                if typed != meant {
                    cases.push((meant, typed, taps));
                }
            }
            println!("taps sigma ({sx}, {sy}): {} mistyped words", cases.len());
            for (radius, gate) in [(0.0, Gate::Old), (1.25, Gate::Careful), (1.25, Gate::Eager)] {
                print!("  {gate:?}");
                let model = TouchModel::new(keys.clone(), TouchModel::SIGMA).with_slip_radius(radius);
                let (mut top1, mut top3, mut fired, mut right) = (0, 0, 0, 0);
                let started = std::time::Instant::now();
                for (meant, typed, taps) in &cases {
                    let scoring = TouchScoring::new(&model, taps.clone());
                    let got = search_units(
                        &[src(&d, 0.0)], typed, &q, 4, ws, default_max_edits(typed.len()), Some(&scoring), &NO_HABITS, None,
                    );
                    if got.first().is_some_and(|c| &c.word == meant) {
                        top1 += 1;
                    }
                    if got.iter().take(3).any(|c| &c.word == meant) {
                        top3 += 1;
                    }
                    // A known word stands.
                    if d.frequency_of(&String::from_utf16_lossy(typed)) > 0 {
                        continue;
                    }
                    if let Some(best) = fires(&got, gate) {
                        fired += 1;
                        if &best.word == meant {
                            right += 1;
                        }
                    }
                }
                let n = cases.len() as f64;
                println!(
                    "  reach {radius:.2}: walk top1 {:.3} top3 {:.3} | autocorrect fired {:.3} precision {:.3} recall {:.3} | {:.0} us/search",
                    top1 as f64 / n,
                    top3 as f64 / n,
                    fired as f64 / n,
                    if fired == 0 { 1.0 } else { right as f64 / fired as f64 },
                    right as f64 / n,
                    started.elapsed().as_micros() as f64 / n,
                );
            }
        }
    }

    /// Words the list does not hold, typed right: how often autocorrect
    /// rewrites them into something else, with and without slips. One word in
    /// seven between ranks 3,000 and 60,000 is held out of the dictionary and
    /// stands in for slang, names and anything newer than the list. Run with
    /// `cargo test --release new_word_eval -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn new_word_eval() {
        let mut entries = real();
        entries.sort_by(|a, b| b.1.cmp(&a.1));
        let mut kept = Vec::new();
        let mut held = Vec::new();
        for (rank, (w, f)) in entries.iter().enumerate() {
            let plain = w.len() >= 4 && w.chars().all(|c| c.is_ascii_lowercase());
            if plain && (3_000..60_000).contains(&rank) && rank % 7 == 0 {
                held.push(w.clone());
            } else {
                kept.push((w.clone(), *f));
            }
        }
        let d = Dict::open(wmdict::write(&PackedTrie::of(&kept))).unwrap();
        let keys = qwerty_keys();
        let nearest = |p: (f32, f32)| {
            keys.iter()
                .min_by(|a, b| {
                    let da = (a.1 - p.0).powi(2) + (a.2 - p.1).powi(2);
                    let db = (b.1 - p.0).powi(2) + (b.2 - p.1).powi(2);
                    da.total_cmp(&db)
                })
                .unwrap()
                .0
        };
        let mut seed = 0x9e37_79b9_7f4a_7c15u64;
        let mut uniform = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };
        let ws = &mut Workspace::new();
        let q = KeyProximity::qwerty(false);
        println!("{} held-out words", held.len());
        for (sx, sy) in [(0.25, 0.3), (0.35, 0.45), (0.45, 0.55)] {
            let mut cases = Vec::new();
            let mut tries = 0;
            while cases.len() < 3000 && tries < 200_000 {
                tries += 1;
                let word = &held[(uniform() * held.len() as f64) as usize % held.len()];
                let mut taps = Vec::new();
                for ch in word.chars() {
                    let (x, y) = centre(ch);
                    let (u1, u2) = (uniform().max(1e-12), uniform());
                    let r = (-2.0 * u1.ln()).sqrt();
                    let a = 2.0 * std::f64::consts::PI * u2;
                    taps.push(Some((x + (r * a.cos() * sx) as f32, y + (r * a.sin() * sy) as f32)));
                }
                let typed: Vec<u16> = taps.iter().map(|p| nearest(p.unwrap())).collect();
                // Typed right: every tap landed on its own key.
                if typed == word.encode_utf16().collect::<Vec<_>>() {
                    cases.push((typed, taps));
                }
            }
            for (radius, gate) in [(0.0, Gate::Old), (1.25, Gate::Careful), (1.25, Gate::Eager)] {
                let model = TouchModel::new(keys.clone(), TouchModel::SIGMA).with_slip_radius(radius);
                let mut fired = 0;
                let mut by_slips = 0;
                for (typed, taps) in &cases {
                    let scoring = TouchScoring::new(&model, taps.clone());
                    let got = search_units(
                        &[src(&d, 0.0)], typed, &q, 4, ws, default_max_edits(typed.len()), Some(&scoring), &NO_HABITS, None,
                    );
                    if let Some(best) = fires(&got, gate) {
                        fired += 1;
                        if best.slips >= 2 {
                            by_slips += 1;
                        }
                    }
                }
                println!(
                    "taps ({sx}, {sy}) {gate:?}: {} new words typed right, {:.3} rewritten ({:.3} by two or more slips)",
                    cases.len(),
                    fired as f64 / cases.len() as f64,
                    by_slips as f64 / cases.len() as f64,
                );
            }
        }
    }

    #[test]
    fn accents_read_without_an_edit() {
        let d = dict(&[("już", 100)]);
        let got = &run(&[src(&d, 0.0)], "juz", 5, None)[0];
        assert_eq!(("już", 0, 1), (got.word.as_str(), got.edits, got.accents));
    }

    #[test]
    fn a_letter_the_key_carries_reads_like_an_accent() {
        // Arabic's ة rides on ه's Shift and أ on ا's hold: typed without
        // either, the word is still one tap per letter and no edit (#970).
        let d = dict(&[("مدرسة", 100), ("أخبار", 100)]);
        let carried = KeyProximity::of_rows(&["ضصثقفغعهخحجد", "شسيبلاتنمكط"])
            .with_carried(vec![('ه' as u16, 'ة' as u16), ('ا' as u16, 'أ' as u16)]);
        for (typed, meant) in [("مدرسه", "مدرسة"), ("اخبار", "أخبار")] {
            let got = &search(&[src(&d, 0.0)], typed, &carried, 5, &mut Workspace::new(), None, &NO_HABITS)[0];
            assert_eq!((meant, 0, 1), (got.word.as_str(), got.edits, got.accents));
            // Without the carried pairs the same reading costs an edit.
            let plain = &search(
                &[src(&d, 0.0)], typed, &KeyProximity::of_rows(&["ضصثقفغعهخحجد", "شسيبلاتنمكط"]), 5,
                &mut Workspace::new(), None, &NO_HABITS,
            )[0];
            assert_eq!((meant, 1, 0), (plain.word.as_str(), plain.edits, plain.accents));
        }
    }

    struct Habit;
    impl EditHabits for Habit {
        fn substitution(&self, typed: u16, meant: u16) -> f64 {
            if typed == 'x' as u16 && meant == 'u' as u16 {
                0.6
            } else {
                0.0
            }
        }
    }

    #[test]
    fn a_learned_habit_discounts_its_edit_but_never_below_the_floor() {
        let d = dict(&[("hullo", 100)]);
        let q = KeyProximity::qwerty(false);
        let plain = &run(&[src(&d, 0.0)], "hxllo", 5, None)[0];
        let learned = &search(
            &[src(&d, 0.0)],
            "hxllo",
            &q,
            5,
            &mut Workspace::new(),
            None,
            &Habit,
        )[0];
        assert!((plain.edit_cost - cost_sub_far()).abs() < 1e-9);
        assert!(learned.edit_cost < plain.edit_cost && learned.edit_cost >= habit_floor());
    }
    /// One-row boards (Minuum's layout) against the same language's three-row
    /// grid: words drawn by frequency from a real list, typed by a Gaussian
    /// finger whose scatter is in *normal* key widths, so a row of 26 keys
    /// each 10/26 wide is as hard to hit as it is on a phone. Prints how often
    /// the word you meant is what ends up in the field. Run with
    /// `ONE_ROW_LIST=<word freq file> ONE_ROW_ROWS=qwertyuiop,asdfghjkl,zxcvbnm
    /// cargo test --release one_row_eval -- --ignored --nocapture`.
    #[test]
    #[ignore]
    fn one_row_eval() {
        let list = std::env::var("ONE_ROW_LIST").expect("ONE_ROW_LIST");
        let rows: Vec<Vec<u16>> = std::env::var("ONE_ROW_ROWS")
            .expect("ONE_ROW_ROWS")
            .split(',')
            .map(|r| r.chars().map(|c| c as u16).collect())
            .collect();
        let words: usize = std::env::var("ONE_ROW_WORDS").ok().and_then(|n| n.parse().ok()).unwrap_or(100_000);
        // Accented letters behind each base key's long press ("eéè aà"), typed
        // at the base key and carried by the one-row sets beside it.
        let groups: Vec<Vec<u16>> = std::env::var("ONE_ROW_GROUPS")
            .unwrap_or_default()
            .split(' ')
            .filter(|g| !g.is_empty())
            .map(|g| g.chars().map(|c| c as u16).collect())
            .collect();
        let base_of = |u: u16| groups.iter().find(|g| g.contains(&u)).map_or(u, |g| g[0]);
        let accents_of = |u: u16| groups.iter().find(|g| g[0] == u).map(|g| g[1..].to_vec()).unwrap_or_default();
        let mut alphabet: std::collections::HashSet<u16> = rows.iter().flatten().copied().collect();
        alphabet.extend(groups.iter().flatten().copied());
        let mut entries: Vec<(String, i32)> = crate::wordlist::parse(&std::fs::read_to_string(&list).unwrap())
            .into_iter()
            .map(|(w, f)| (w.to_lowercase(), f))
            .filter(|(w, _)| w.encode_utf16().all(|u| alphabet.contains(&u)))
            .collect();
        entries.sort_by(|a, b| b.1.cmp(&a.1));
        entries.dedup_by(|a, b| a.0 == b.0);
        entries.truncate(words);
        let d = Dict::open(wmdict::write(&PackedTrie::of(&entries))).unwrap();
        let usable: Vec<&(String, i32)> = entries.iter().filter(|(w, _)| w.chars().count() >= 2).collect();
        // Word frequencies in these lists are log-scaled scores; sample by rank
        // with Zipf weights instead, which is what running text looks like.
        let mut cumulative = Vec::with_capacity(usable.len());
        let mut total = 0f64;
        for i in 0..usable.len() {
            total += 1.0 / (i as f64 + 10.0);
            cumulative.push(total);
        }
        // Three rows centred on the widest, a key apart; one row of n keys each 10/n wide.
        let widest = rows.iter().map(Vec::len).max().unwrap() as f32;
        let grid: Vec<(u16, f32, f32)> = rows
            .iter()
            .enumerate()
            .flat_map(|(r, row)| {
                let offset = (widest - row.len() as f32) / 2.0;
                row.iter().enumerate().map(move |(c, &u)| (u, offset + c as f32, r as f32))
            })
            .collect();
        let order: Vec<u16> = (0..widest as usize)
            .flat_map(|c| rows.iter().filter_map(move |row| row.get(c).copied()))
            .collect();
        let pitch = widest / order.len() as f32;
        let strip: Vec<(u16, f32, f32)> =
            order.iter().enumerate().map(|(i, &u)| (u, (i as f32 + 0.5) * pitch, 0.0)).collect();
        let row_strings: Vec<String> = rows.iter().map(|r| String::from_utf16_lossy(r)).collect();
        let boards = [
            ("three rows", grid, KeyProximity::of_rows(&row_strings)),
            ("one row   ", strip, KeyProximity::of_rows(&[String::from_utf16_lossy(&order)])),
        ];
        println!("{list}: {} words, one row = {}", entries.len(), String::from_utf16_lossy(&order));
        let ws = &mut Workspace::new();
        for (sx, sy) in [(0.25f64, 0.3f64), (0.35, 0.45)] {
            for (name, keys, proximity) in &boards {
                let nearest = |p: (f32, f32)| {
                    keys.iter()
                        .min_by(|a, b| {
                            let da = (a.1 - p.0).powi(2) + (a.2 - p.1).powi(2);
                            let db = (b.1 - p.0).powi(2) + (b.2 - p.1).powi(2);
                            da.total_cmp(&db)
                        })
                        .unwrap()
                        .0
                };
                let sigma: f64 = std::env::var("ONE_ROW_SIGMA").ok().and_then(|v| v.parse().ok()).unwrap_or(TouchModel::SIGMA);
                // Accents sit at their base key, as the app places them.
                let mut model_keys = keys.clone();
                for g in &groups {
                    if let Some(&(_, x, y)) = keys.iter().find(|k| k.0 == g[0]) {
                        model_keys.extend(g[1..].iter().map(|&u| (u, x, y)));
                    }
                }
                let model = TouchModel::new(model_keys, sigma).with_slip_radius(1.25);
                let one_row = keys.iter().all(|k| k.2 == 0.0);
                let mut seed = 0x2545_f491_4f6c_dd1du64;
                let mut uniform = move || {
                    seed ^= seed << 13;
                    seed ^= seed >> 7;
                    seed ^= seed << 17;
                    (seed >> 11) as f64 / (1u64 << 53) as f64
                };
                // Policies, each "is the meant word what the field holds after space".
                let labels = ["exact", "gate", "top1", "top3", "set1", "set2", "set3", "set2 top3", "set1 no-tap", "set2 no-tap"];
                let mut hits = [0usize; 10];
                let n = 3000;
                for _ in 0..n {
                    let target = uniform() * total;
                    let word = &usable[cumulative.partition_point(|&c| c <= target).min(usable.len() - 1)].0;
                    let meant: Vec<u16> = word.encode_utf16().collect();
                    let mut taps = Vec::new();
                    for u in &meant {
                        let (_, x, y) = *keys.iter().find(|k| k.0 == base_of(*u)).unwrap();
                        let (u1, u2) = (uniform().max(1e-12), uniform());
                        let (u3, u4) = (uniform().max(1e-12), uniform());
                        let g1 = (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos();
                        let g2 = (-2.0 * u3.ln()).sqrt() * (2.0 * std::f64::consts::PI * u4).cos();
                        taps.push(Some((x + (g1 * sx) as f32, y + (g2 * sy) as f32)));
                    }
                    let typed: Vec<u16> = taps.iter().map(|p| nearest(p.unwrap())).collect();
                    let max_edits = default_max_edits(typed.len());
                    let scoring = TouchScoring::new(&model, taps.clone());
                    let got = search_units(&[src(&d, 0.0)], &typed, proximity, 4, ws, max_edits, Some(&scoring), &NO_HABITS, None);
                    let is = |c: Option<&Candidate>| c.is_some_and(|c| c.word == meant);
                    if typed == meant { hits[0] += 1; hits[1] += 1; }
                    else if d.frequency_of(&String::from_utf16_lossy(&typed)) == 0 && is(fires(&got, Gate::Careful)) { hits[1] += 1; }
                    if is(got.first()) { hits[2] += 1; }
                    if got.iter().take(3).any(|c| c.word == meant) { hits[3] += 1; }
                    if !one_row { continue; }
                    // The pressed key and its k neighbours either side, pressed letter first.
                    let sets = |k: usize| {
                        KeySets::new(
                            typed.iter().map(|t| {
                                let i = order.iter().position(|o| o == t).unwrap();
                                let mut window: Vec<usize> = (i.saturating_sub(k)..=(i + k).min(order.len() - 1)).collect();
                                window.sort_by_key(|j| j.abs_diff(i));
                                let mut set = Vec::new();
                                for j in window {
                                    for u in std::iter::once(order[j]).chain(accents_of(order[j])) {
                                        if !set.contains(&u) { set.push(u); }
                                    }
                                }
                                set
                            }).collect(),
                            vec![None; typed.len()],
                        )
                    };
                    for (slot, k, touch) in [(4, 1, true), (5, 2, true), (6, 3, true), (8, 1, false), (9, 2, false)] {
                        let ks = sets(k);
                        let r = search_units(&[src(&d, 0.0)], &typed, proximity, 4, ws, max_edits,
                            if touch { Some(&scoring) } else { None }, &NO_HABITS, Some(&ks));
                        if is(r.first()) { hits[slot] += 1; }
                        if k == 2 && touch && r.iter().take(3).any(|c| c.word == meant) { hits[7] += 1; }
                    }
                }
                let cells: Vec<String> = labels.iter().zip(hits).filter(|(_, h)| one_row || *h > 0)
                    .map(|(l, h)| format!("{l} {:.1}", 100.0 * h as f64 / n as f64)).collect();
                println!("  scatter ({sx}, {sy}) {name}: {}", cells.join(" | "));
            }
        }
    }
}
