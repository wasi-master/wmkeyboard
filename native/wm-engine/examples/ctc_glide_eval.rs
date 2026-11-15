//! The FUTO Swipe engine's decoder (`glide::ctc`) on real swipes: FUTO's MIT
//! swipe.futo.org strokes with the model's emissions precomputed by
//! `native/bench/futo-swipe/emit.py; see native/bench/futo-swipe/README.md`.
//!
//! cargo run --release --example ctc_glide_eval -- <records.bin> <word list> [mode]
//!
//! `mode` names what the decode is given beside the emissions, any of
//! `marks` (the stroke's loops), `ahead` (four guesses, gated as
//! `GlideGuessGate.admit` gates them at `MARGIN`, default 1) and `shapes`
//! (each session's earlier swipes as learned shapes); none is the bare
//! decoder. `LW`, `LAC` and `SW` override the loop, look-ahead and shape
//! weights, `FW`, `LN` and `LB` the frequency weight and length terms (for
//! emissions from another model); `THREADS` and `LIMIT` the run. Prints top-1 and top-4, the
//! doubled-letter and looped subsets, the misses by kind, the guesses that
//! led, the words drawn before in their session, and the decode time.

use std::collections::HashMap;
use std::time::Instant;

use wm_engine::fuzzy::{RankScale, Tier};
use wm_engine::glide::ctc::{CtcDecoder, Scoring, Stroke, CLASSES};
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, LearnedShapes, Marks, Point, Source, Tuning, SHAPE_QUANT};
use wm_engine::user_lexicon::UserTrie;

struct Record {
    word: String,
    session: u32,
    width: f32,
    height: f32,
    points: Vec<(f32, f32, i32)>,
    steps: usize,
    log_probs: Vec<f32>,
}

fn read(path: &str) -> Vec<Record> {
    let bytes = std::fs::read(path).expect("records");
    assert_eq!(&bytes[..4], b"FSW1");
    let mut at = 4;
    let u32_at = |b: &[u8], i: usize| u32::from_le_bytes(b[i..i + 4].try_into().unwrap());
    let f32_at = |b: &[u8], i: usize| f32::from_le_bytes(b[i..i + 4].try_into().unwrap());
    let count = u32_at(&bytes, at) as usize;
    at += 4;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        let len = u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap()) as usize;
        at += 2;
        let word = String::from_utf8(bytes[at..at + len].to_vec()).unwrap();
        at += len;
        let session = u32_at(&bytes, at);
        let width = f32_at(&bytes, at + 4);
        let height = f32_at(&bytes, at + 8);
        let n = u32_at(&bytes, at + 12) as usize;
        at += 16;
        let mut points = Vec::with_capacity(n);
        for _ in 0..n {
            points.push((f32_at(&bytes, at), f32_at(&bytes, at + 4), u32_at(&bytes, at + 8) as i32));
            at += 12;
        }
        let steps = u16::from_le_bytes(bytes[at..at + 2].try_into().unwrap()) as usize;
        at += 2;
        let log_probs = (0..steps * CLASSES).map(|i| f32_at(&bytes, at + 4 * i)).collect();
        at += 4 * steps * CLASSES;
        out.push(Record { word, session, width, height, points, steps, log_probs });
    }
    out
}

fn env_f(name: &str, default: f32) -> f32 {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

/// QWERTY in key widths, as swipe.futo.org draws it: ten keys to the
/// canvas width, three rows to its height.
fn keymap(width: f32, height: f32) -> KeyMap {
    const ROWS: [&str; 3] = ["qwertyuiop", "asdfghjkl", "zxcvbnm"];
    const LEAD: [f32; 3] = [0.05, 0.10, 0.20];
    let kw = width * 0.1;
    let (mut xs, mut ys, mut cps) = (Vec::new(), Vec::new(), Vec::new());
    for (r, row) in ROWS.iter().enumerate() {
        for (i, c) in row.chars().enumerate() {
            xs.push((LEAD[r] + 0.1 * i as f32) * width / kw);
            ys.push((r as f32 + 0.5) / 3.0 * height / kw);
            cps.push(c as i32);
        }
    }
    let mut order: Vec<usize> = (0..cps.len()).collect();
    order.sort_by_key(|&i| cps[i]);
    let sorted: Vec<i32> = order.iter().map(|&i| cps[i]).collect();
    let key_of: Vec<i32> = order.iter().map(|&i| i as i32).collect();
    KeyMap::new(xs, ys, sorted, key_of)
}

fn path_of(r: &Record) -> Vec<Point> {
    r.points.iter().map(|&(x, y, t)| Point { x, y, t: t as i64 }).collect()
}

fn doubled(w: &str) -> bool {
    w.as_bytes().windows(2).any(|p| p[0] == p[1])
}

fn squeeze(w: &str) -> String {
    let mut out = String::new();
    for c in w.chars() {
        if !out.ends_with(c) {
            out.push(c);
        }
    }
    out
}

#[derive(Default)]
struct Tally {
    n: usize,
    top1: usize,
    top4: usize,
    dbl_n: usize,
    dbl_top1: usize,
    miss_double: usize,
    miss_prefix: usize,
    looped: usize,
    looped_on_double: usize,
    loop_n: usize,
    loop_top1: usize,
    guess_right: usize,
    guess_wrong: usize,
    seen_n: usize,
    seen_top1: usize,
    micros: Vec<f64>,
}

/// Mean point distance between two quantised shapes, in key widths.
fn shape_distance(a: &[i8], b: &[i8]) -> f32 {
    let mut sum = 0f32;
    for j in 0..a.len() / 2 {
        let dx = (a[2 * j] as f32 - b[2 * j] as f32) / SHAPE_QUANT;
        let dy = (a[2 * j + 1] as f32 - b[2 * j + 1] as f32) / SHAPE_QUANT;
        sum += (dx * dx + dy * dy).sqrt();
    }
    sum / (a.len() / 2) as f32
}

/// A rough `GlideShapeStore.learn`: a shape near one kept is merged into it
/// (kept as it was), a new one joins, at most three a word, oldest out.
fn learn(store: &mut LearnedShapes, word: Vec<u16>, shape: Vec<i8>) {
    let kept = store.entry(word).or_default();
    if kept.iter().any(|s| shape_distance(s, &shape) <= 0.35) {
        return;
    }
    kept.push(shape);
    if kept.len() > 3 {
        kept.remove(0);
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let records = read(&args[1]);
    let text = std::fs::read_to_string(&args[2]).expect("word list");
    let mode = args.get(3).cloned().unwrap_or_default();
    let mut trie = UserTrie::new();
    let mut known: HashMap<String, i32> = HashMap::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        known.insert(word.to_lowercase(), frequency);
        trie.insert(&word, frequency);
    }
    let mut scoring = Scoring::default();
    scoring.freq_weight = env_f("FW", scoring.freq_weight);
    scoring.length_norm = env_f("LN", scoring.length_norm);
    scoring.length_bonus = env_f("LB", scoring.length_bonus);
    scoring.loop_weight = env_f("LW", scoring.loop_weight);
    scoring.look_ahead_cost = env_f("LAC", scoring.look_ahead_cost);
    let marks_on = mode.contains("marks");
    let look_ahead = if mode.contains("ahead") { 4 } else { 0 };
    let margin = env_f("MARGIN", 1.0) as f64;
    scoring.shape_weight = env_f("SW", scoring.shape_weight);
    let shapes_on = mode.contains("shapes");
    let limit = env_f("LIMIT", 1e9) as usize;
    let picked: Vec<&Record> =
        records.iter().filter(|r| known.contains_key(&r.word.to_lowercase())).take(limit).collect();
    let threads = env_f("THREADS", 8.0) as usize;
    // Whole sessions to a thread, in order, so a session's store grows as it did.
    let chunk = picked.len().div_ceil(threads);
    let mut parts: Vec<Vec<&Record>> = vec![Vec::new()];
    for r in &picked {
        let last = parts.last_mut().unwrap();
        if last.len() >= chunk && last.last().is_some_and(|p| p.session != r.session) {
            parts.push(Vec::new());
        }
        parts.last_mut().unwrap().push(r);
    }
    let started = Instant::now();
    let tallies: Vec<Tally> = std::thread::scope(|scope| {
        let handles: Vec<_> = parts
            .iter()
            .map(|part| {
                let trie = &trie;
                scope.spawn(move || {
                    let sources = [Source { walker: trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0, scale: RankScale::RAW }];
                    let mut decoder = CtcDecoder::new(scoring);
                    let mut glide = Decoder::new(Tuning::default());
                    let mut t = Tally::default();
                    let mut store: LearnedShapes = LearnedShapes::new();
                    let mut session = u32::MAX;
                    for &r in part {
                        if r.session != session {
                            session = r.session;
                            store.clear();
                        }
                        let target = r.word.to_lowercase();
                        let keys = keymap(r.width, r.height);
                        let path = path_of(r);
                        let kw = r.width * 0.1;
                        let units: Vec<u16> = target.encode_utf16().collect();
                        let seen = store.contains_key(&units);
                        let drawn = glide.sample_shape(&path, kw);
                        let found = glide.marks(&path, &keys, kw);
                        let has_loop = found.as_ref().is_some_and(|m| !m.loops.is_empty());
                        // Timed: what the engine runs for a decode in this mode.
                        let clock = Instant::now();
                        let marks = if marks_on { glide.marks(&path, &keys, kw) } else { None };
                        let stroke = Stroke { look_ahead, marks: marks.map(|m| (m, &keys)) };
                        let ranked = decoder.walk(&r.log_probs, r.steps, &sources, 8, 100, &stroke);
                        let prepared = shapes_on && glide.prepare_shape(&path, kw);
                        let mut out = decoder.finish(
                            ranked,
                            &mut |w: &[u16]| {
                                let lower: Vec<u16> = String::from_utf16_lossy(w).to_lowercase().encode_utf16().collect();
                                if prepared && store.contains_key(&lower) { glide.learned_gain(&lower, &keys, &store) } else { 0.0 }
                            },
                        );
                        t.micros.push(clock.elapsed().as_secs_f64() * 1e6);
                        let marks = found;
                        if let Some(d) = drawn {
                            learn(&mut store, units.clone(), d);
                        }
                        // GlideGuessGate.admit
                        if let Some(best_read) = out.iter().filter(|c| c.ahead == 0).map(|c| c.score).reduce(f64::max) {
                            out.retain(|c| c.ahead == 0 || c.score - best_read >= margin);
                        }
                        if let Some(first) = out.first() {
                            if first.ahead > 0 {
                                if String::from_utf16_lossy(&first.word).to_lowercase() == target {
                                    t.guess_right += 1;
                                } else {
                                    t.guess_wrong += 1;
                                }
                            }
                        }
                        let words: Vec<String> = out.iter().map(|c| String::from_utf16_lossy(&c.word).to_lowercase()).collect();
                        t.n += 1;
                        let hit = words.first() == Some(&target);
                        t.top1 += hit as usize;
                        t.top4 += words.iter().take(4).any(|w| *w == target) as usize;
                        if seen {
                            t.seen_n += 1;
                            t.seen_top1 += hit as usize;
                        }
                        if doubled(&target) {
                            t.dbl_n += 1;
                            t.dbl_top1 += hit as usize;
                        }
                        if has_loop {
                            t.loop_n += 1;
                            t.loop_top1 += hit as usize;
                            t.looped += 1;
                            let m = marks.as_ref().unwrap();
                            let units: Vec<u16> = target.encode_utf16().collect();
                            if m.loops.iter().any(|&(k, _)| Marks::doubles(&units, k, &keys)) {
                                t.looped_on_double += 1;
                            }
                        }
                        if !hit {
                            if let Some(first) = words.first() {
                                if squeeze(first) == squeeze(&target) {
                                    t.miss_double += 1;
                                } else if target.starts_with(first.as_str()) || first.starts_with(target.as_str()) {
                                    t.miss_prefix += 1;
                                }
                            }
                        }
                    }
                    t
                })
            })
            .collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    let mut t = Tally::default();
    for x in tallies {
        t.n += x.n;
        t.top1 += x.top1;
        t.top4 += x.top4;
        t.dbl_n += x.dbl_n;
        t.dbl_top1 += x.dbl_top1;
        t.miss_double += x.miss_double;
        t.miss_prefix += x.miss_prefix;
        t.looped += x.looped;
        t.looped_on_double += x.looped_on_double;
        t.loop_n += x.loop_n;
        t.loop_top1 += x.loop_top1;
        t.guess_right += x.guess_right;
        t.guess_wrong += x.guess_wrong;
        t.seen_n += x.seen_n;
        t.seen_top1 += x.seen_top1;
        t.micros.extend(x.micros);
    }
    t.micros.sort_by(f64::total_cmp);
    let q = |p: f64| t.micros[((t.micros.len() as f64 - 1.0) * p) as usize];
    println!("decode us: mean {:.0} p50 {:.0} p95 {:.0}", t.micros.iter().sum::<f64>() / t.micros.len() as f64, q(0.5), q(0.95));
    let f = |a: usize, b: usize| a as f64 / b.max(1) as f64;
    println!("drawn before in the session: n {} top1 {:.4}; the rest: n {} top1 {:.4}", t.seen_n, f(t.seen_top1, t.seen_n), t.n - t.seen_n, f(t.top1 - t.seen_top1, t.n - t.seen_n));
    println!("guesses leading: right {} wrong {} ({:.4} / {:.4})", t.guess_right, t.guess_wrong, f(t.guess_right, t.n), f(t.guess_wrong, t.n));
    println!(
        "mode '{mode}' n {} top1 {:.4} top4 {:.4} | doubled n {} top1 {:.4} | misses: double-letter {} prefix {} other {} | looped strokes {} ({} on a doubled key) top1 {:.4} | {:.1} s",
        t.n,
        f(t.top1, t.n),
        f(t.top4, t.n),
        t.dbl_n,
        f(t.dbl_top1, t.dbl_n),
        t.miss_double,
        t.miss_prefix,
        t.n - t.top1 - t.miss_double - t.miss_prefix,
        t.looped,
        t.looped_on_double,
        f(t.loop_top1, t.loop_n),
        started.elapsed().as_secs_f64()
    );
}
