//! What the user's own fixes have taught autocorrect: the Kotlin
//! `CorrectionMemory`, `EditOps` and `EditHabits` (core/prediction).
//!
//! A word typed, left standing, then repaired by hand teaches two things:
//! the pair (`teh` -> `the`, counted; fixed twice is a fix to make for the
//! user, once is one to offer) and the slip behind it, aligned letter by
//! letter and counted per layout, which [`LearnedHabits`] turns into cheaper
//! edits for the fuzzy walk.
//!
//! The file format is the phone's `learning/learned_corrections.json`:
//! `{"pairs":{"teh":[{"fixed","count","gen","hand"}]},"habits":{"layout":{"sub:3e":2}},
//! "generation"}`; `hand` is written only for a fix the user set by hand.

use crate::fuzzy::EditHabits;
use crate::sort;
use crate::user_lexicon::cmp_utf16;
use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// Fixes of the same spelling that make it a fix the user wants made.
pub const APPLY_AT: i32 = 2;
/// ...when the spelling is itself a word ("form"): the bar is higher, and
/// the context has to agree.
pub const APPLY_KNOWN_AT: i32 = 3;
/// A fix has to outnumber the next fix of the same spelling this many times.
const DOMINANCE: i32 = 2;
const MAX_FIXES_PER_TYPED: usize = 3;
const MAX_PAIRS: usize = 1000;
const MAX_COUNT: i32 = 100;
const EXPIRE_GENERATIONS: i64 = 180;
/// Habit counters halve past this many sightings on one layout.
const HABIT_WINDOW: i32 = 400;
const MIN_TYPED_LENGTH: usize = 3;
const MAX_WORD_LENGTH: usize = 32;
const MIN_HALF_LENGTH: usize = 2;
const MAX_EDITS: usize = 2;
const LONG_WORD_LENGTH: usize = 8;
const MAX_EDITS_LONG: usize = 3;
/// The most a habit may take off a base cost.
const MAX_SHRINK: f64 = 0.75;
/// Sightings at which a habit is worth half of [`MAX_SHRINK`].
const PIVOT: f64 = 2.0;
/// Fixes a layout has to have taught before any habit moves a cost.
const WARMUP: i32 = 8;

/// What a fix teaches: the pair alone (a real word swapped for another is a
/// choice, not a slip), or the pair and the slip behind it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    PairOnly,
    PairAndHabits,
}

/// A fix taught for one typed spelling, and how often.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Taught {
    pub fixed: String,
    pub count: i32,
    /// Set by hand in the viewer: it does not age out, and is never the one
    /// evicted to make room.
    pub by_hand: bool,
}

#[derive(Clone, Debug)]
struct Stored {
    fixed: String,
    count: i32,
    gen: i64,
    by_hand: bool,
}

#[derive(Default)]
pub struct CorrectionMemory {
    pairs: HashMap<String, Vec<Stored>>,
    habits: HashMap<String, HashMap<String, i32>>,
    generation: i64,
    dirty: bool,
    /// Bumped on every change, as the Kotlin store's `version` (an `Int`).
    version: i32,
}

/// One layout's habit shrinks as the Kotlin `EditHabits` is built from
/// them: substitution keys `typed << 16 | intended`, the others by unit.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct HabitExport {
    pub sub_keys: Vec<i32>,
    pub sub_values: Vec<f64>,
    pub insert_keys: Vec<u16>,
    pub insert_values: Vec<f64>,
    pub delete_keys: Vec<u16>,
    pub delete_values: Vec<f64>,
    pub space_keys: Vec<u16>,
    pub space_values: Vec<f64>,
}

impl HabitExport {
    pub fn is_empty(&self) -> bool {
        self.sub_keys.is_empty() && self.insert_keys.is_empty() && self.delete_keys.is_empty() && self.space_keys.is_empty()
    }
}

/// One step of an alignment of what was typed onto what was meant, in
/// UTF-16 units as the Kotlin `EditOps` counts them.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    Match(u16),
    /// `typed` stood where `intended` was meant.
    Sub {
        typed: u16,
        intended: u16,
    },
    /// `intended` was meant and never typed.
    Ins(u16),
    /// A stray keypress.
    Del(u16),
    /// The pair was meant the other way round: `first` then `second`.
    Swap {
        first: u16,
        second: u16,
    },
}

fn swappable(t: &[u16], m: &[u16], i: usize, j: usize) -> bool {
    i > 1 && j > 1 && t[i - 1] == m[j - 2] && t[i - 2] == m[j - 1] && t[i - 1] != t[i - 2]
}

fn table(t: &[u16], m: &[u16]) -> Vec<Vec<usize>> {
    let (n, w) = (t.len(), m.len());
    let mut d = vec![vec![0usize; w + 1]; n + 1];
    for (i, row) in d.iter_mut().enumerate() {
        row[0] = i;
    }
    for (j, cell) in d[0].iter_mut().enumerate() {
        *cell = j;
    }
    for i in 1..=n {
        for j in 1..=w {
            let cost = usize::from(t[i - 1] != m[j - 1]);
            let mut best = (d[i - 1][j] + 1).min(d[i][j - 1] + 1).min(d[i - 1][j - 1] + cost);
            if swappable(t, m, i, j) {
                best = best.min(d[i - 2][j - 2] + 1);
            }
            d[i][j] = best;
        }
    }
    d
}

/// Edit distance in UTF-16 units, adjacent swaps counting one.
pub fn distance(typed: &str, intended: &str) -> usize {
    let (t, m) = (units(typed), units(intended));
    if t.is_empty() {
        return m.len();
    }
    if m.is_empty() {
        return t.len();
    }
    table(&t, &m)[t.len()][m.len()]
}

/// The cheapest alignment of `typed` onto `intended`, left to right, ties
/// broken match, swap, substitution, deletion, insertion.
pub fn align(typed: &str, intended: &str) -> Vec<Op> {
    align_units(&units(typed), &units(intended))
}

pub fn align_units(t: &[u16], m: &[u16]) -> Vec<Op> {
    let d = table(t, m);
    let (mut i, mut j) = (t.len(), m.len());
    let mut ops = Vec::new();
    while i > 0 || j > 0 {
        if i > 0 && j > 0 && t[i - 1] == m[j - 1] && d[i][j] == d[i - 1][j - 1] {
            ops.push(Op::Match(t[i - 1]));
            i -= 1;
            j -= 1;
        } else if swappable(t, m, i, j) && d[i][j] == d[i - 2][j - 2] + 1 {
            ops.push(Op::Swap { first: m[j - 2], second: m[j - 1] });
            i -= 2;
            j -= 2;
        } else if i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 {
            ops.push(Op::Sub { typed: t[i - 1], intended: m[j - 1] });
            i -= 1;
            j -= 1;
        } else if i > 0 && d[i][j] == d[i - 1][j] + 1 {
            ops.push(Op::Del(t[i - 1]));
            i -= 1;
        } else {
            ops.push(Op::Ins(m[j - 1]));
            j -= 1;
        }
    }
    ops.reverse();
    ops
}

const SPACE: u16 = b' ' as u16;

fn habit_key(op: Op) -> Option<String> {
    let text = |prefix: &str, units: &[u16]| format!("{prefix}:{}", String::from_utf16_lossy(units));
    match op {
        Op::Match(_) => None,
        Op::Sub { typed, intended: SPACE } => Some(text("space", &[typed])),
        Op::Sub { typed: SPACE, .. } => None,
        Op::Sub { typed, intended } => Some(text("sub", &[typed, intended])),
        Op::Ins(SPACE) => Some("nospace".into()),
        Op::Ins(c) => Some(text("ins", &[c])),
        Op::Del(SPACE) => None,
        Op::Del(c) => Some(text("del", &[c])),
        Op::Swap { first, second } => Some(text("swap", &[first, second])),
    }
}

/// Whether a fix of `original` into `revised` is worth learning: a
/// word-sized typed token, a revised form the keyboard knows (or two known
/// halves), a slip's distance apart, and not a word committed early.
pub fn accepts(original: &str, revised: &str, known: &dyn Fn(&str) -> bool) -> bool {
    let o = original.trim().to_lowercase();
    let r = revised.trim().to_lowercase();
    let len = o.chars().count();
    if !(MIN_TYPED_LENGTH..=MAX_WORD_LENGTH).contains(&len) || r.is_empty() {
        return false;
    }
    if o == r || o.chars().any(char::is_whitespace) {
        return false;
    }
    let parts: Vec<&str> = r.split(' ').collect();
    if parts.len() > 2
        || parts
            .iter()
            .any(|p| p.chars().count() < MIN_HALF_LENGTH || !known(p))
    {
        return false;
    }
    if r.starts_with(&o) {
        return false;
    }
    let limit = if len >= LONG_WORD_LENGTH {
        MAX_EDITS_LONG
    } else {
        MAX_EDITS
    };
    distance(&o, &r) <= limit
}

fn shrink(count: i32) -> f64 {
    if count <= 0 {
        0.0
    } else {
        MAX_SHRINK * count as f64 / (count as f64 + PIVOT)
    }
}

/// The slips one hand makes on one layout, as shrinks of the walk's costs.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct LearnedHabits {
    subs: HashMap<(u16, u16), f64>,
    inserts: HashMap<u16, f64>,
    deletes: HashMap<u16, f64>,
}

impl LearnedHabits {
    pub fn is_empty(&self) -> bool {
        self.subs.is_empty() && self.inserts.is_empty() && self.deletes.is_empty()
    }
}

impl EditHabits for LearnedHabits {
    fn deletion(&self, typed: u16) -> f64 {
        self.deletes.get(&typed).copied().unwrap_or(0.0)
    }
    fn substitution(&self, typed: u16, meant: u16) -> f64 {
        self.subs.get(&(typed, meant)).copied().unwrap_or(0.0)
    }
    fn insertion(&self, meant: u16) -> f64 {
        self.inserts.get(&meant).copied().unwrap_or(0.0)
    }
}

impl CorrectionMemory {
    pub fn new() -> CorrectionMemory {
        CorrectionMemory::default()
    }

    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    pub fn version(&self) -> i32 {
        self.version
    }

    fn changed(&mut self) {
        self.dirty = true;
        self.version = self.version.wrapping_add(1);
    }

    /// The user turned `original` into `revised` by hand. Callers gate
    /// with [`accepts`] first. Folds both by trimming and lower-casing, as
    /// WMK Desktop types them; the phone folds itself and calls
    /// [`CorrectionMemory::teach_keys`].
    pub fn teach(&mut self, original: &str, revised: &str, layout: &str, kind: Kind) {
        let layout = if layout.trim().is_empty() { "" } else { layout };
        self.teach_keys(&original.trim().to_lowercase(), &revised.trim().to_lowercase(), layout, kind);
    }

    /// `teach` over folded spellings; `layout` empty for none.
    pub fn teach_keys(&mut self, typed: &str, fixed: &str, layout: &str, kind: Kind) {
        if typed.is_empty() || fixed.is_empty() || typed == fixed {
            return;
        }
        let generation = self.generation;
        let list = self.pairs.entry(typed.to_string()).or_default();
        match list.iter().position(|s| s.fixed == fixed) {
            Some(at) => {
                list[at].count = (list[at].count + 1).min(MAX_COUNT);
                list[at].gen = generation;
            }
            None => list.push(Stored { fixed: fixed.to_string(), count: 1, gen: generation, by_hand: false }),
        }
        // Stable, most used first, as Kotlin's sortByDescending.
        sort::by(list, |a, b| b.count.cmp(&a.count));
        list.truncate(MAX_FIXES_PER_TYPED);
        self.make_room();
        if kind == Kind::PairAndHabits && !layout.is_empty() {
            let counts = self.habits.entry(layout.to_string()).or_default();
            for op in align_units(&units(typed), &units(fixed)) {
                if let Some(k) = habit_key(op) {
                    let n = counts.entry(k).or_insert(0);
                    *n = (*n + 1).min(MAX_COUNT);
                }
            }
            if counts.values().fold(0i32, |a, &b| a.wrapping_add(b)) >= HABIT_WINDOW {
                counts.retain(|_, n| {
                    *n /= 2;
                    *n > 0
                });
            }
        }
        self.changed();
    }

    /// Past [`MAX_PAIRS`] the longest untouched spelling goes, ties by the
    /// spelling. One holding a fix set by hand stays.
    fn make_room(&mut self) {
        if self.pairs.len() <= MAX_PAIRS {
            return;
        }
        let oldest = self
            .pairs
            .iter()
            .filter(|(_, list)| !list.iter().any(|s| s.by_hand))
            .min_by(|a, b| {
                let ga = a.1.iter().map(|s| s.gen).max().unwrap_or(i64::MIN);
                let gb = b.1.iter().map(|s| s.gen).max().unwrap_or(i64::MIN);
                ga.cmp(&gb).then_with(|| cmp_utf16(a.0, b.0))
            })
            .map(|(k, _)| k.clone());
        if let Some(oldest) = oldest {
            self.pairs.remove(&oldest);
        }
    }

    /// The user set `typed -> fixed` by hand, at exactly `count` (folded
    /// spellings). A new fix takes the place of the least used one when the
    /// spelling already has as many as it may keep.
    pub fn set_pair_keys(&mut self, typed: &str, fixed: &str, count: i32) {
        if typed.is_empty() || fixed.is_empty() || typed == fixed {
            return;
        }
        let stored = Stored {
            fixed: fixed.to_string(),
            count: count.clamp(1, MAX_COUNT),
            gen: self.generation,
            by_hand: true,
        };
        let list = self.pairs.entry(typed.to_string()).or_default();
        match list.iter().position(|s| s.fixed == fixed) {
            Some(at) => list[at] = stored,
            None => {
                list.truncate(MAX_FIXES_PER_TYPED - 1);
                list.push(stored);
            }
        }
        sort::by(list, |a, b| b.count.cmp(&a.count));
        self.make_room();
        self.changed();
    }

    /// Forgets one fix of `typed`, leaving its other fixes (folded spellings).
    pub fn remove_pair_keys(&mut self, typed: &str, fixed: &str) {
        let Some(list) = self.pairs.get_mut(typed) else {
            return;
        };
        let before = list.len();
        list.retain(|s| s.fixed != fixed);
        if list.len() == before {
            return;
        }
        if list.is_empty() {
            self.pairs.remove(typed);
        }
        self.changed();
    }

    /// Sets one slip of `layout` to `count`; zero or less forgets it. `key`
    /// is the store's own (`sub:3e`, `nospace`).
    pub fn set_habit(&mut self, layout: &str, key: &str, count: i32) {
        if layout.is_empty() || key.is_empty() {
            return;
        }
        if count <= 0 {
            let Some(counts) = self.habits.get_mut(layout) else {
                return;
            };
            if counts.remove(key).is_none() {
                return;
            }
            if counts.is_empty() {
                self.habits.remove(layout);
            }
        } else {
            self.habits.entry(layout.to_string()).or_default().insert(key.to_string(), count.min(MAX_COUNT));
        }
        self.changed();
    }

    /// The layouts that have taught a slip, in order.
    pub fn habit_layouts(&self) -> Vec<String> {
        let mut out: Vec<String> = self.habits.keys().cloned().collect();
        sort::by(&mut out, |a, b| cmp_utf16(a, b));
        out
    }

    /// The user undid a taught fix: one count off.
    pub fn unteach(&mut self, typed: &str, fixed: &str) {
        self.unteach_keys(&typed.to_lowercase(), &fixed.to_lowercase());
    }

    pub fn unteach_keys(&mut self, typed: &str, fixed: &str) {
        let generation = self.generation;
        let Some(list) = self.pairs.get_mut(typed) else {
            return;
        };
        let Some(at) = list.iter().position(|s| s.fixed == fixed) else {
            return;
        };
        if list[at].count <= 1 {
            list.remove(at);
        } else {
            list[at].count -= 1;
            list[at].gen = generation;
        }
        if list.is_empty() {
            self.pairs.remove(typed);
        }
        self.changed();
    }

    /// The fix taught for `typed`, when one clearly leads.
    pub fn fix_for(&self, typed: &str) -> Option<Taught> {
        self.fix_for_key(&typed.to_lowercase())
    }

    pub fn fix_for_key(&self, typed: &str) -> Option<Taught> {
        let list = self.pairs.get(typed)?;
        let top = list.first()?;
        if let Some(runner) = list.get(1) {
            if top.count < runner.count * DOMINANCE {
                return None;
            }
        }
        Some(Taught { fixed: top.fixed.clone(), count: top.count, by_hand: top.by_hand })
    }

    pub fn forget(&mut self, typed: &str) {
        self.forget_key(&typed.to_lowercase());
    }

    pub fn forget_key(&mut self, typed: &str) {
        if self.pairs.remove(typed).is_some() {
            self.changed();
        }
    }

    /// Every taught pair, most used first, then by the typed spelling.
    pub fn pairs(&self) -> Vec<(String, Taught)> {
        let mut out: Vec<(String, Taught)> = self
            .pairs
            .iter()
            .flat_map(|(t, l)| {
                l.iter().map(move |s| (t.clone(), Taught { fixed: s.fixed.clone(), count: s.count, by_hand: s.by_hand }))
            })
            .collect();
        sort::by(&mut out, |a, b| b.1.count.cmp(&a.1.count).then_with(|| cmp_utf16(&a.0, &b.0)));
        out
    }

    pub fn pair_count(&self) -> usize {
        self.pairs.values().map(Vec::len).sum()
    }

    /// The shrinks for `layout`, empty until it has taught enough slips.
    pub fn habits_for(&self, layout: &str) -> LearnedHabits {
        let export = self.habit_export(layout);
        let mut out = LearnedHabits::default();
        for (i, &k) in export.sub_keys.iter().enumerate() {
            out.subs.insert(((k >> 16) as u16, k as u16), export.sub_values[i]);
        }
        for (i, &k) in export.insert_keys.iter().enumerate() {
            out.inserts.insert(k, export.insert_values[i]);
        }
        for (i, &k) in export.delete_keys.iter().enumerate() {
            out.deletes.insert(k, export.delete_values[i]);
        }
        out
    }

    /// `buildHabits`: the layout's counts as shrinks, nothing until it has
    /// taught [`WARMUP`] slips. Keys in UTF-16 order.
    pub fn habit_export(&self, layout: &str) -> HabitExport {
        let mut out = HabitExport::default();
        let Some(counts) = self.habits.get(layout) else {
            return out;
        };
        if counts.values().fold(0i32, |a, &b| a.wrapping_add(b)) < WARMUP {
            return out;
        }
        let mut keys: Vec<&String> = counts.keys().collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a, b));
        for key in keys {
            let s = shrink(counts[key]);
            if s <= 0.0 {
                continue;
            }
            let Some((kind, arg)) = key.split_once(':') else {
                continue;
            };
            let arg = units(arg);
            match (kind, arg.as_slice()) {
                ("sub", [a, b]) => {
                    out.sub_keys.push(((*a as i32) << 16) | *b as i32);
                    out.sub_values.push(s);
                }
                ("ins", [c]) => {
                    out.insert_keys.push(*c);
                    out.insert_values.push(s);
                }
                ("del", [c]) => {
                    out.delete_keys.push(*c);
                    out.delete_values.push(s);
                }
                ("space", [c]) => {
                    out.space_keys.push(*c);
                    out.space_values.push(s);
                }
                _ => {}
            }
        }
        out
    }

    /// A layout's raw habit counts, or every layout's summed when `layout`
    /// is `None`, in key order: what the viewer summarises.
    pub fn habit_counts(&self, layout: Option<&str>) -> Vec<(String, i32)> {
        let mut merged: HashMap<String, i32> = HashMap::new();
        match layout {
            Some(layout) => {
                if let Some(counts) = self.habits.get(layout) {
                    merged = counts.clone();
                }
            }
            None => {
                for counts in self.habits.values() {
                    for (k, &n) in counts {
                        let m = merged.entry(k.clone()).or_insert(0);
                        *m = m.wrapping_add(n);
                    }
                }
            }
        }
        let mut out: Vec<(String, i32)> = merged.into_iter().collect();
        sort::by(&mut out, |a, b| cmp_utf16(&a.0, &b.0));
        out
    }

    /// `clear`: `deleted` is whether the caller's delete of the file worked.
    pub fn clear_with(&mut self, deleted: bool) {
        self.pairs.clear();
        self.habits.clear();
        self.version = self.version.wrapping_add(1);
        self.dirty = !deleted;
    }

    pub fn clear(&mut self) {
        self.clear_with(false);
    }

    /// `reload`'s first half: everything dropped, the clock back to zero.
    pub fn reset_for_reload(&mut self) {
        self.pairs.clear();
        self.habits.clear();
        self.generation = 0;
    }

    /// `reload`'s end: memory is the file again.
    pub fn finish_reload(&mut self) {
        self.dirty = false;
        self.version = self.version.wrapping_add(1);
    }

    /// `save`'s first half: when anything changed, ages the store a
    /// generation, expires stale pairs and returns the file's text.
    pub fn prepare_save(&mut self) -> Option<String> {
        if !self.dirty {
            return None;
        }
        Some(self.to_json())
    }

    pub fn mark_unsaved(&mut self) {
        self.dirty = true;
    }

    /// The file's text, ageing pairs untouched for a long time.
    pub fn to_json(&mut self) -> String {
        self.generation += 1;
        self.expire_stale_pairs();
        self.dirty = false;
        let mut root = Map::new();
        let mut keys: Vec<&String> = self.pairs.keys().collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a, b));
        if !keys.is_empty() {
            let mut pairs = Map::new();
            for k in keys {
                let list: Vec<Value> = self.pairs[k]
                    .iter()
                    .map(|s| {
                        let mut stored = json!({"fixed": s.fixed, "count": s.count, "gen": s.gen});
                        if s.by_hand {
                            stored["hand"] = json!(true);
                        }
                        stored
                    })
                    .collect();
                pairs.insert(k.clone(), Value::Array(list));
            }
            root.insert("pairs".into(), Value::Object(pairs));
        }
        if !self.habits.is_empty() {
            let mut layouts: Vec<&String> = self.habits.keys().collect();
            sort::by(&mut layouts, |a, b| cmp_utf16(a, b));
            let mut habits = Map::new();
            for layout in layouts {
                let counts = &self.habits[layout];
                let mut ks: Vec<&String> = counts.keys().collect();
                sort::by(&mut ks, |a, b| cmp_utf16(a, b));
                let mut layout_counts = Map::new();
                for k in ks {
                    layout_counts.insert(k.clone(), json!(counts[k]));
                }
                habits.insert(layout.clone(), Value::Object(layout_counts));
            }
            root.insert("habits".into(), Value::Object(habits));
        }
        if self.generation != 0 {
            root.insert("generation".into(), json!(self.generation));
        }
        Value::Object(root).to_string()
    }

    fn expire_stale_pairs(&mut self) {
        let generation = self.generation;
        self.pairs.retain(|_, list| {
            let kept: Vec<Stored> = list
                .iter()
                .filter_map(|s| {
                    if s.by_hand || generation - s.gen <= EXPIRE_GENERATIONS {
                        Some(s.clone())
                    } else if s.count > 1 {
                        Some(Stored { fixed: s.fixed.clone(), count: s.count - 1, gen: generation, by_hand: false })
                    } else {
                        None
                    }
                })
                .collect();
            *list = kept;
            !list.is_empty()
        });
    }

    /// A store read from its file, strictly as kotlinx reads it: a value of
    /// the wrong type, or a stored fix missing a field, loads nothing.
    pub fn from_json(text: &str) -> CorrectionMemory {
        let mut out = CorrectionMemory::new();
        out.load_json(text);
        out
    }

    /// Reads `text` into this store (after a reset, on a reload).
    pub fn load_json(&mut self, text: &str) {
        let Some((pairs, habits, generation)) = parse_snapshot(text) else {
            return;
        };
        for (typed, list) in pairs {
            let mut clean: Vec<Stored> = list.into_iter().filter(|s| s.count > 0 && !s.fixed.is_empty()).collect();
            if !clean.is_empty() {
                sort::by(&mut clean, |a, b| b.count.cmp(&a.count));
                self.pairs.insert(typed, clean);
            }
        }
        for (layout, counts) in habits {
            let clean: HashMap<String, i32> = counts.into_iter().filter(|(_, n)| *n > 0).collect();
            if !clean.is_empty() {
                self.habits.insert(layout, clean);
            }
        }
        self.generation = generation;
    }
}

type ParsedPairs = Vec<(String, Vec<Stored>)>;
type ParsedHabits = Vec<(String, Vec<(String, i32)>)>;

fn parse_snapshot(text: &str) -> Option<(ParsedPairs, ParsedHabits, i64)> {
    let v: Value = serde_json::from_str(text).ok()?;
    let root = v.as_object()?;
    let mut pairs = Vec::new();
    let mut habits = Vec::new();
    let mut generation = 0;
    for (field, value) in root {
        match field.as_str() {
            "pairs" => {
                for (typed, list) in value.as_object()? {
                    let mut stored = Vec::new();
                    for s in list.as_array()? {
                        let s = s.as_object()?;
                        stored.push(Stored {
                            fixed: s.get("fixed")?.as_str()?.to_string(),
                            count: i32::try_from(s.get("count")?.as_i64()?).ok()?,
                            gen: s.get("gen")?.as_i64()?,
                            by_hand: match s.get("hand") {
                                None => false,
                                Some(hand) => hand.as_bool()?,
                            },
                        });
                    }
                    pairs.push((typed.clone(), stored));
                }
            }
            "habits" => {
                for (layout, counts) in value.as_object()? {
                    let mut c = Vec::new();
                    for (k, n) in counts.as_object()? {
                        c.push((k.clone(), i32::try_from(n.as_i64()?).ok()?));
                    }
                    habits.push((layout.clone(), c));
                }
            }
            "generation" => generation = value.as_i64()?,
            _ => {}
        }
    }
    Some((pairs, habits, generation))
}

fn units(s: &str) -> Vec<u16> {
    s.encode_utf16().collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn u(c: char) -> u16 {
        c as u16
    }

    #[test]
    fn alignment_names_the_slip() {
        assert_eq!(
            vec![Op::Match(u('t')), Op::Swap { first: u('h'), second: u('e') }],
            align("teh", "the")
        );
        assert_eq!(1, distance("t3st", "test"));
        assert!(align("amibtomake", "ami tomake").contains(&Op::Sub { typed: u('b'), intended: u(' ') }));
    }

    #[test]
    fn what_is_worth_learning() {
        let known = |w: &str| ["the", "from", "ami", "tomake"].contains(&w);
        assert!(accepts("teh", "the", &known));
        assert!(!accepts("th", "the", &known), "too short");
        assert!(!accepts("hel", "hello", &known), "unknown and a completion");
        assert!(!accepts("teh", "xyz", &known), "an unknown fix");
        assert!(accepts("amibtomake", "ami tomake", &known));
    }

    #[test]
    fn two_fixes_make_the_fix_and_habits_follow() {
        let mut m = CorrectionMemory::new();
        m.teach("teh", "the", "qwerty", Kind::PairAndHabits);
        assert_eq!(1, m.fix_for("TEH").unwrap().count);
        m.teach("teh", "the", "qwerty", Kind::PairAndHabits);
        m.teach("teh", "ten", "qwerty", Kind::PairAndHabits);
        assert_eq!("the", m.fix_for("teh").unwrap().fixed);
        m.teach("teh", "ten", "qwerty", Kind::PairAndHabits);
        assert_eq!(
            None,
            m.fix_for("teh"),
            "two fixes of one spelling, neither leads"
        );
        m.unteach("teh", "ten");
        m.unteach("teh", "ten");
        assert_eq!("the", m.fix_for("teh").unwrap().fixed);
        assert!(m.habits_for("qwerty").is_empty(), "not warmed up");
        for _ in 0..8 {
            m.teach("t3st", "test", "qwerty", Kind::PairAndHabits);
        }
        let h = m.habits_for("qwerty");
        assert!(h.substitution(u16::from(b'3'), u16::from(b'e')) > 0.5);
        assert_eq!(0.0, h.substitution(u16::from(b'e'), u16::from(b'3')));
        let text = m.to_json();
        let back = CorrectionMemory::from_json(&text);
        assert_eq!(back.pairs(), m.pairs());
        assert!(!back.habits_for("qwerty").is_empty());
    }

    #[test]
    fn a_fix_set_by_hand_holds_its_count_and_never_ages() {
        let mut m = CorrectionMemory::new();
        m.set_pair_keys("recieve", "receive", 5);
        m.teach_keys("teh", "the", "", Kind::PairOnly);
        for _ in 0..=EXPIRE_GENERATIONS {
            m.mark_unsaved();
            m.to_json();
        }
        assert_eq!(None, m.fix_for("teh"), "a learned fix ages out");
        let kept = m.fix_for("recieve").unwrap();
        assert_eq!((5, true), (kept.count, kept.by_hand));
        let text = m.to_json();
        assert!(text.contains("\"hand\":true"), "{text}");
        assert!(CorrectionMemory::from_json(&text).fix_for("recieve").unwrap().by_hand);

        m.set_pair_keys("recieve", "receive", 1);
        assert_eq!(1, m.fix_for("recieve").unwrap().count, "the number set stands, nothing is added");
        m.teach_keys("recieve", "receive", "", Kind::PairOnly);
        let taught = m.fix_for("recieve").unwrap();
        assert_eq!((2, true), (taught.count, taught.by_hand), "learning goes on counting");
    }

    #[test]
    fn a_fix_set_by_hand_outlasts_the_list_it_joins_and_one_goes_alone() {
        let mut m = CorrectionMemory::new();
        for fix in ["ab1", "ab2", "ab3"] {
            m.teach_keys("abc", fix, "", Kind::PairOnly);
        }
        m.set_pair_keys("abc", "abd", 1);
        assert!(m.pairs().iter().any(|(_, t)| t.fixed == "abd"));
        assert_eq!(MAX_FIXES_PER_TYPED, m.pair_count());
        m.remove_pair_keys("abc", "abd");
        assert_eq!(MAX_FIXES_PER_TYPED - 1, m.pair_count());
        m.remove_pair_keys("abc", "nothing");
        assert_eq!(MAX_FIXES_PER_TYPED - 1, m.pair_count());
    }

    #[test]
    fn a_full_store_keeps_what_was_set_by_hand() {
        let mut m = CorrectionMemory::new();
        m.set_pair_keys("aaa", "aab", 1);
        for i in 0..=MAX_PAIRS {
            m.teach_keys(&format!("w{i}"), "x", "", Kind::PairOnly);
        }
        assert!(m.fix_for("aaa").is_some());
        assert_eq!(MAX_PAIRS, m.pairs.len());
    }

    #[test]
    fn one_slip_of_one_layout_moves_alone() {
        let mut m = CorrectionMemory::new();
        m.teach_keys("t3st", "test", "qwerty", Kind::PairAndHabits);
        m.teach_keys("t3st", "test", "azerty", Kind::PairAndHabits);
        assert_eq!(vec!["azerty".to_string(), "qwerty".to_string()], m.habit_layouts());
        m.set_habit("qwerty", "sub:3e", 9);
        assert_eq!(vec![("sub:3e".to_string(), 9)], m.habit_counts(Some("qwerty")));
        assert_eq!(vec![("sub:3e".to_string(), 1)], m.habit_counts(Some("azerty")));
        assert!(!m.habits_for("qwerty").is_empty(), "nine slips is past the warm-up");
        m.set_habit("qwerty", "sub:3e", 0);
        assert_eq!(vec!["azerty".to_string()], m.habit_layouts());
    }
}
