//! What autocorrect has learned about its own mistakes: the Kotlin
//! `CorrectionStats` (core/prediction). Per-pair revert penalties, and a
//! fired/reverted ratio that adapts the confidence gate.
//!
//! The file format is the phone's `learning/correction_stats.json`, so the
//! two can share one: `{"pairs":{"typed\u0000fixed":{"count","gen","kept","held"}},
//! "fired","reverted","generation"}`. Reading and writing the file is the
//! caller's business; [`CorrectionStats::to_json`] and
//! [`CorrectionStats::from_json`] are the text.

use crate::sort;
use crate::user_lexicon::cmp_utf16;
use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// How hard the keyboard holds on to a correction the user undid.
///
/// `Off` forgets pair by pair; the global revert rate still counts. `Light`
/// handicaps an undone pair but never retires it. `Normal` holds a deliberate
/// undo for the run of typing that earned it, retires a pair undone twice,
/// and offers a retired pair again eventually. `Strict` takes the first undo
/// as final.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum UndoMemory {
    Off,
    Light,
    #[default]
    Normal,
    Strict,
}

impl UndoMemory {
    /// The phone's names: "OFF", "LIGHT", "NORMAL", "STRICT", any case.
    pub fn parse(name: &str) -> UndoMemory {
        match name.to_ascii_uppercase().as_str() {
            "OFF" => UndoMemory::Off,
            "LIGHT" => UndoMemory::Light,
            "STRICT" => UndoMemory::Strict,
            _ => UndoMemory::Normal,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Penalty {
    None,
    /// Rejected once, indirectly: still ranks, fights with a handicap.
    Penalized,
    /// Retired long ago: may be offered again, never applied.
    Probation,
    /// Undone on purpose: never a target, never a runner-up.
    Blocked,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct PairStat {
    count: i32,
    gen: i64,
    kept: i32,
    /// Stopped by hand: stays [`Penalty::Blocked`], whatever time or kept
    /// corrections would otherwise buy back.
    held: bool,
}

/// One undone correction, for the viewer: its count, its standing as stored
/// (a block for the run of typing not included), and whether it is held.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Undone {
    pub typed: String,
    pub fixed: String,
    pub count: i32,
    pub penalty: Penalty,
    pub held: bool,
}

/// Second persisted revert of the same pair blocks it outright.
const BLOCK_AT: i32 = 2;
/// Accepted uses of a rejected pair that buy back one rejection.
const KEEPS_TO_FORGIVE: i32 = 3;
const MAX_PAIRS: usize = 500;
const EXPIRE_GENERATIONS: i64 = 180;
/// Verdicts an in-process block survives.
const SESSION_BLOCK_SPAN: u64 = 20;
/// Saves a retired pair sits quiet before it may be offered again.
const REOFFER_GENERATIONS: i64 = 40;
/// Halving window for the fired/reverted counters.
const WINDOW: i32 = 200;
const MIN_SAMPLE: i32 = 20;
const TARGET_RATE: f64 = 0.03;
const RATE_GAIN: f64 = 20.0;
const MIN_MULTIPLIER: f64 = 0.85;
const MAX_MULTIPLIER: f64 = 2.5;

fn key(typed: &str, fixed: &str) -> String {
    format!("{}\u{0}{}", typed.to_lowercase(), fixed.to_lowercase())
}

#[derive(Default)]
pub struct CorrectionStats {
    pairs: HashMap<String, PairStat>,
    fired: i32,
    reverted: i32,
    generation: i64,
    /// Pairs reverted in this run, against the tick each was reverted at.
    session_rejected: HashMap<String, u64>,
    /// Verdicts delivered since the run started; only ages the blocks out.
    tick: u64,
    dirty: bool,
    pub memory: UndoMemory,
    /// A fixed multiplier instead of the revert rate's, for tests and for
    /// a caller that keeps its own.
    pub confidence_multiplier: Option<f64>,
}

impl CorrectionStats {
    pub fn new() -> CorrectionStats {
        CorrectionStats::default()
    }

    /// Whether something changed since the last [`to_json`](Self::to_json).
    pub fn is_dirty(&self) -> bool {
        self.dirty
    }

    fn bump_fired(&mut self) {
        self.fired += 1;
        self.tick += 1;
        if self.fired >= WINDOW {
            self.fired /= 2;
            self.reverted /= 2;
        }
        self.dirty = true;
    }

    /// The user undid the correction of `typed` into `fixed`. Only a
    /// deliberate undo (a backspace on the fix, the undo chip) earns the
    /// in-process block.
    pub fn record_revert(&mut self, typed: &str, fixed: &str, deliberate: bool) {
        self.record_revert_key(&key(typed, fixed), deliberate);
    }

    /// The `_key` methods take the pair key as the phone builds it
    /// (`typed.lowercase() NUL fixed.lowercase()`, Kotlin's lower-casing).
    pub fn record_revert_key(&mut self, k: &str, deliberate: bool) {
        self.reverted += 1;
        self.bump_fired();
        if self.memory == UndoMemory::Off {
            return;
        }
        let k = k.to_string();
        if deliberate || self.memory == UndoMemory::Strict {
            self.session_rejected.insert(k.clone(), self.tick);
        }
        if self.pairs.get(&k).is_some_and(|p| p.held) {
            self.dirty = true;
            return;
        }
        let bumped = self.pairs.get(&k).map_or(0, |p| p.count) + 1;
        let count = match self.memory {
            UndoMemory::Light => 1,
            UndoMemory::Strict => bumped.max(BLOCK_AT),
            _ => bumped,
        };
        self.pairs.insert(
            k,
            PairStat {
                count,
                gen: self.generation,
                kept: 0,
                held: false,
            },
        );
        if self.pairs.len() > MAX_PAIRS {
            if let Some(oldest) = self
                .pairs
                .iter()
                .filter(|(_, p)| !p.held)
                .min_by(|a, b| a.1.gen.cmp(&b.1.gen).then_with(|| cmp_utf16(a.0, b.0)))
                .map(|(k, _)| k.clone())
            {
                self.pairs.remove(&oldest);
            }
        }
        self.dirty = true;
    }

    fn session_blocked(&mut self, k: &str) -> bool {
        let Some(&stamp) = self.session_rejected.get(k) else {
            return false;
        };
        if self.tick - stamp < SESSION_BLOCK_SPAN {
            return true;
        }
        self.session_rejected.remove(k);
        false
    }

    fn session_blocked_now(&self, k: &str) -> bool {
        self.session_rejected
            .get(k)
            .is_some_and(|&stamp| self.tick - stamp < SESSION_BLOCK_SPAN)
    }

    /// The user left the field: in-process blocks end with it.
    pub fn end_field_session(&mut self) {
        self.session_rejected.clear();
    }

    /// The user let the correction of `typed` into `fixed` stand.
    pub fn record_kept(&mut self, typed: &str, fixed: &str) {
        self.record_kept_key(&key(typed, fixed));
    }

    pub fn record_kept_key(&mut self, k: &str) {
        self.bump_fired();
        if self.memory == UndoMemory::Off {
            return;
        }
        let k = k.to_string();
        if self.session_blocked(&k) {
            return;
        }
        let Some(existing) = self.pairs.get(&k).copied() else {
            return;
        };
        if existing.held {
            return;
        }
        let kept = existing.kept + 1;
        if kept < KEEPS_TO_FORGIVE {
            self.pairs.insert(
                k,
                PairStat {
                    kept,
                    gen: self.generation,
                    ..existing
                },
            );
        } else if existing.count <= 1 {
            self.pairs.remove(&k);
        } else {
            self.pairs.insert(
                k,
                PairStat {
                    count: existing.count - 1,
                    gen: self.generation,
                    kept: 0,
                    held: false,
                },
            );
        }
    }

    /// How strongly this exact correction is disfavored.
    pub fn penalty(&self, typed: &str, fixed: &str) -> Penalty {
        self.penalty_key(&key(typed, fixed))
    }

    pub fn penalty_key(&self, k: &str) -> Penalty {
        if self.memory == UndoMemory::Off {
            return Penalty::None;
        }
        if self.session_blocked_now(k) {
            return Penalty::Blocked;
        }
        let Some(stat) = self.pairs.get(k) else {
            return Penalty::None;
        };
        if stat.count < BLOCK_AT {
            Penalty::Penalized
        } else if !stat.held
            && self.memory != UndoMemory::Strict
            && self.generation - stat.gen > REOFFER_GENERATIONS
        {
            Penalty::Probation
        } else {
            Penalty::Blocked
        }
    }

    /// Sets a pair's standing directly: Blocked as a deliberate undo,
    /// Penalized as one indirect one, None to forget it.
    pub fn set(&mut self, typed: &str, fixed: &str, penalty: Penalty) {
        let k = key(typed, fixed);
        match penalty {
            Penalty::None => {
                self.pairs.remove(&k);
                self.session_rejected.remove(&k);
            }
            Penalty::Penalized => {
                self.pairs.insert(
                    k,
                    PairStat {
                        count: 1,
                        gen: self.generation,
                        kept: 0,
                        held: false,
                    },
                );
            }
            Penalty::Probation => {
                self.pairs.insert(
                    k,
                    PairStat {
                        count: BLOCK_AT,
                        gen: self.generation - REOFFER_GENERATIONS - 1,
                        kept: 0,
                        held: false,
                    },
                );
            }
            Penalty::Blocked => {
                self.session_rejected.insert(k.clone(), self.tick);
                self.pairs.insert(
                    k,
                    PairStat {
                        count: BLOCK_AT,
                        gen: self.generation,
                        kept: 0,
                        held: false,
                    },
                );
            }
        }
        self.dirty = true;
    }

    /// Sets one pair by hand, by its key: `count` undos against it, `held` to
    /// keep it stopped for good. Zero or less forgets it, block for the run
    /// of typing included. The revert rate is not touched: that is what the
    /// keyboard did, whatever the user now thinks of this pair.
    pub fn set_pair_key(&mut self, k: &str, count: i32, held: bool) {
        if count <= 0 {
            self.pairs.remove(k);
            self.session_rejected.remove(k);
        } else {
            self.pairs.insert(k.to_string(), PairStat { count, gen: self.generation, kept: 0, held });
        }
        self.dirty = true;
    }

    /// Every undone pair, by its key, for the viewer.
    pub fn pairs(&self) -> Vec<Undone> {
        let mut keys: Vec<&String> = self.pairs.keys().collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a, b));
        keys.into_iter()
            .filter_map(|k| {
                let (typed, fixed) = k.split_once('\u{0}')?;
                let stat = self.pairs[k];
                Some(Undone {
                    typed: typed.to_string(),
                    fixed: fixed.to_string(),
                    count: stat.count,
                    penalty: self.penalty_key(k),
                    held: stat.held,
                })
            })
            .collect()
    }

    /// The multiplier on the confidence setting from the recent revert rate;
    /// 1 until there is a real sample.
    pub fn multiplier(&self) -> f64 {
        if let Some(m) = self.confidence_multiplier {
            return m;
        }
        if self.fired < MIN_SAMPLE {
            return 1.0;
        }
        let rate = self.reverted as f64 / self.fired as f64;
        (1.0 + RATE_GAIN * (rate - TARGET_RATE)).clamp(MIN_MULTIPLIER, MAX_MULTIPLIER)
    }

    /// How many corrections reached a verdict, and how many were undone,
    /// in the current window.
    pub fn counts(&self) -> (i32, i32) {
        (self.fired, self.reverted)
    }

    pub fn clear(&mut self) {
        self.clear_with(false);
    }

    /// `clear`: `deleted` is whether the caller's delete of the file worked.
    pub fn clear_with(&mut self, deleted: bool) {
        self.pairs.clear();
        self.session_rejected.clear();
        self.fired = 0;
        self.reverted = 0;
        self.dirty = !deleted;
    }

    /// `reload`: the persisted counts dropped and read again; the session's
    /// blocks and the generation stay until the file says otherwise.
    pub fn reload_json(&mut self, text: &str) {
        self.pairs.clear();
        self.fired = 0;
        self.reverted = 0;
        self.load_json(text);
        self.dirty = false;
    }

    /// `save`'s first half: the file's text when anything changed.
    pub fn prepare_save(&mut self) -> Option<String> {
        if !self.dirty {
            return None;
        }
        Some(self.to_json())
    }

    pub fn mark_unsaved(&mut self) {
        self.dirty = true;
    }

    /// The file's text, ageing stale pairs as the phone does on each save.
    pub fn to_json(&mut self) -> String {
        self.generation += 1;
        let generation = self.generation;
        // A pair untouched this long loses a count and starts a new period,
        // its progress towards forgiveness reset with it.
        self.pairs.retain(|_, p| {
            if !p.held && generation - p.gen > EXPIRE_GENERATIONS {
                if p.count <= 1 {
                    return false;
                }
                *p = PairStat { count: p.count - 1, gen: generation, kept: 0, held: false };
            }
            true
        });
        self.dirty = false;
        let mut root = Map::new();
        let mut keys: Vec<&String> = self.pairs.keys().collect();
        sort::by(&mut keys, |a, b| cmp_utf16(a, b));
        if !keys.is_empty() {
            let mut pairs = Map::new();
            for k in keys {
                let p = self.pairs[k];
                let mut stat = Map::new();
                stat.insert("count".into(), json!(p.count));
                stat.insert("gen".into(), json!(p.gen));
                if p.kept != 0 {
                    stat.insert("kept".into(), json!(p.kept));
                }
                if p.held {
                    stat.insert("held".into(), json!(true));
                }
                pairs.insert(k.clone(), Value::Object(stat));
            }
            root.insert("pairs".into(), Value::Object(pairs));
        }
        if self.fired != 0 {
            root.insert("fired".into(), json!(self.fired));
        }
        if self.reverted != 0 {
            root.insert("reverted".into(), json!(self.reverted));
        }
        if self.generation != 0 {
            root.insert("generation".into(), json!(self.generation));
        }
        Value::Object(root).to_string()
    }

    pub fn from_json(text: &str) -> CorrectionStats {
        let mut out = CorrectionStats::new();
        out.load_json(text);
        out
    }

    /// Reads `text` into this store as kotlinx reads the phone's file: every
    /// pair as stored, and nothing at all when a value has the wrong type or
    /// a pair lacks its count or generation.
    pub fn load_json(&mut self, text: &str) {
        let Some((pairs, fired, reverted, generation)) = parse_snapshot(text) else {
            return;
        };
        self.pairs.extend(pairs);
        if let Some(fired) = fired {
            self.fired = fired;
        } else {
            self.fired = 0;
        }
        self.reverted = reverted.unwrap_or(0);
        self.generation = generation.unwrap_or(0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_deliberate_undo_blocks_for_the_run_and_two_retire_the_pair() {
        let mut s = CorrectionStats::new();
        s.record_revert("teh", "ten", true);
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"));
        for _ in 0..SESSION_BLOCK_SPAN {
            s.record_kept("x", "y");
        }
        assert_eq!(Penalty::Penalized, s.penalty("Teh", "TEN"));
        s.record_revert("teh", "ten", false);
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"));
    }

    #[test]
    fn keeping_a_pair_three_times_forgives_one_undo() {
        let mut s = CorrectionStats::new();
        s.record_revert("cst", "cut", false);
        assert_eq!(Penalty::Penalized, s.penalty("cst", "cut"));
        for _ in 0..3 {
            s.record_kept("cst", "cut");
        }
        assert_eq!(Penalty::None, s.penalty("cst", "cut"));
    }

    #[test]
    fn the_levels() {
        let mut light = CorrectionStats {
            memory: UndoMemory::Light,
            ..CorrectionStats::new()
        };
        light.record_revert("a1", "b1", false);
        light.record_revert("a1", "b1", false);
        assert_eq!(Penalty::Penalized, light.penalty("a1", "b1"));
        let mut strict = CorrectionStats {
            memory: UndoMemory::Strict,
            ..CorrectionStats::new()
        };
        strict.record_revert("a1", "b1", false);
        for _ in 0..30 {
            strict.record_kept("x", "y");
        }
        assert_eq!(Penalty::Blocked, strict.penalty("a1", "b1"));
        let mut off = CorrectionStats {
            memory: UndoMemory::Off,
            ..CorrectionStats::new()
        };
        off.record_revert("a1", "b1", true);
        assert_eq!(Penalty::None, off.penalty("a1", "b1"));
        assert_eq!((1, 1), off.counts());
    }

    #[test]
    fn many_undos_tighten_the_gate() {
        let mut s = CorrectionStats::new();
        for i in 0..20 {
            if i % 4 == 0 {
                s.record_revert(&format!("w{i}"), "x", false);
            } else {
                s.record_kept("a", "b");
            }
        }
        assert!(s.multiplier() > 1.5, "{}", s.multiplier());
        let mut clean = CorrectionStats::new();
        for _ in 0..40 {
            clean.record_kept("a", "b");
        }
        assert!(clean.multiplier() < 1.0);
    }

    #[test]
    fn the_file_round_trips_in_the_phones_format() {
        let mut s = CorrectionStats::new();
        s.record_revert("teh", "ten", false);
        let text = s.to_json();
        assert!(text.contains("\"teh\\u0000ten\""), "{text}");
        let back = CorrectionStats::from_json(&text);
        assert_eq!(Penalty::Penalized, back.penalty("teh", "ten"));
        assert_eq!((1, 1), back.counts());
        assert_eq!(0, CorrectionStats::from_json("nonsense").counts().0);
    }

    #[test]
    fn a_pair_stopped_by_hand_stays_stopped() {
        let mut s = CorrectionStats::new();
        let k = key("teh", "ten");
        s.set_pair_key(&k, BLOCK_AT, true);
        for _ in 0..=EXPIRE_GENERATIONS {
            s.mark_unsaved();
            s.to_json();
        }
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"), "time buys nothing back");
        for _ in 0..KEEPS_TO_FORGIVE {
            s.record_kept("teh", "ten");
        }
        s.record_revert("teh", "ten", false);
        assert_eq!(Penalty::Blocked, s.penalty("teh", "ten"));
        let text = s.to_json();
        assert!(text.contains("\"held\":true"), "{text}");
        let back = CorrectionStats::from_json(&text);
        let listed = &back.pairs()[0];
        assert_eq!(("teh", "ten", true), (listed.typed.as_str(), listed.fixed.as_str(), listed.held));
    }

    #[test]
    fn one_pair_can_be_eased_or_forgiven_and_the_rate_stays() {
        let mut s = CorrectionStats::new();
        s.record_revert("cst", "cut", true);
        s.record_revert("cst", "cut", true);
        s.record_revert("teh", "ten", false);
        let k = key("cst", "cut");
        s.end_field_session();
        assert_eq!(Penalty::Blocked, s.penalty("cst", "cut"));
        s.set_pair_key(&k, 1, false);
        assert_eq!(Penalty::Penalized, s.penalty("cst", "cut"));
        s.set_pair_key(&k, 0, false);
        assert_eq!(Penalty::None, s.penalty("cst", "cut"));
        assert_eq!(Penalty::Penalized, s.penalty("teh", "ten"), "the other pair is untouched");
        assert_eq!((3, 3), s.counts());
    }
}

type ParsedStats = (Vec<(String, PairStat)>, Option<i32>, Option<i32>, Option<i64>);

fn parse_snapshot(text: &str) -> Option<ParsedStats> {
    let v: Value = serde_json::from_str(text).ok()?;
    let int = |v: &Value| i32::try_from(v.as_i64()?).ok();
    let mut pairs = Vec::new();
    let (mut fired, mut reverted, mut generation) = (None, None, None);
    for (field, value) in v.as_object()? {
        match field.as_str() {
            "pairs" => {
                for (k, p) in value.as_object()? {
                    let p = p.as_object()?;
                    let kept = match p.get("kept") {
                        None => 0,
                        Some(v) => int(v)?,
                    };
                    let held = match p.get("held") {
                        None => false,
                        Some(v) => v.as_bool()?,
                    };
                    pairs.push((k.clone(), PairStat { count: int(p.get("count")?)?, gen: p.get("gen")?.as_i64()?, kept, held }));
                }
            }
            "fired" => fired = Some(int(value)?),
            "reverted" => reverted = Some(int(value)?),
            "generation" => generation = Some(value.as_i64()?),
            _ => {}
        }
    }
    Some((pairs, fired, reverted, generation))
}
