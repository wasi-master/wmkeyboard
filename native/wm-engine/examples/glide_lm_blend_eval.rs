//! The glide pool re-ranked by a real `.wmlm` in score space, the way
//! `SuggestionEngine.blendGlideContextModel` does it: score + clamp(w*(lp-lp_leader)).
//!
//! cargo run --release --example glide_lm_blend_eval -- records.jsonl layout.json list.txt model.wmlm
//!
//! `RESULTS=8`, `COLS=10`, `THREADS=8`, `WEIGHT=0.7`, `CAP=3.0`,
//! `FREQ`/`GAP` = the engine's shipped `Tuning` defaults. Prints no-blend and
//! blended top-1/top-4, and the number of strokes with a previous word.
//! The German profile is `COLS=11 WEIGHT=0.5 CAP=5 FREQ=0.75 GAP=2.0`.
//! The harness applies `freq`/`gap` only; the app's German join settings
//! (`joinCost=8`, `joinMinPart=4`, `joinPartPenalty=0.1`) are not wired here.
//!
//! `ITEMS=<path>` (optional) writes one TSV row per evaluated record, sorted
//! by `item_id` (the record's index in the filtered `records` vector). Header
//! `item_id\tblend_top1\tblend_top4\traw_top1\traw_top4`; the four booleans
//! are exactly what the existing loop counts, recorded per item. Workers
//! return their rows to the main thread, which sorts and writes after the
//! join, so the dump is independent of `THREADS` and of decode order.

use std::io::{BufRead, Write};
use std::sync::Arc;

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, Point, Source, Tuning};
use wm_engine::lm::{Lm, Prefix, Session};
use wm_engine::user_lexicon::UserTrie;

struct Record {
    word: String,
    sentence: String,
    word_idx: usize,
    width: f32,
    height: f32,
    points: Vec<Point>,
}
fn number(v: &serde_json::Value, key: &str) -> f32 {
    v[key].as_f64().unwrap_or(0.0) as f32
}
fn keymap(layout: &[(i32, f32, f32)], width: f32, height: f32, cols: f32) -> KeyMap {
    let kw = width / cols;
    let mut order: Vec<usize> = (0..layout.len()).collect();
    order.sort_by_key(|&i| layout[i].0);
    let xs = layout.iter().map(|k| k.1 * width / kw).collect();
    let ys = layout.iter().map(|k| k.2 * height / kw).collect();
    let sorted = order.iter().map(|&i| layout[i].0).collect();
    let key_of = order.iter().map(|&i| i as i32).collect();
    KeyMap::new(xs, ys, sorted, key_of)
}
fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 5 {
        eprintln!(
            "usage: glide_lm_blend_eval <records.jsonl> <layout.json> <list.txt> <model.wmlm>"
        );
        std::process::exit(2);
    }
    let layout_json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&args[2]).unwrap()).unwrap();
    let layout: Vec<(i32, f32, f32)> = layout_json["keys"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|k| {
            Some((
                k["letter"].as_str()?.chars().next()? as i32,
                number(k, "cx"),
                number(k, "cy"),
            ))
        })
        .collect();
    let text = std::fs::read_to_string(&args[3]).unwrap();
    let mut trie = UserTrie::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        trie.insert(&word, frequency);
    }
    let lm = Arc::new(Lm::open(std::fs::read(&args[4]).unwrap()).expect("a .wmlm"));
    let cols: f32 = std::env::var("COLS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(10.0);
    if cols <= 0.0 {
        eprintln!("COLS must be positive");
        std::process::exit(2);
    }
    let results: usize = std::env::var("RESULTS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(8)
        .max(1);
    let threads: usize = std::env::var("THREADS")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(8)
        .max(1);
    let defaults = Tuning::default();
    let weight: f64 = std::env::var("WEIGHT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(0.7);
    let cap: f64 = std::env::var("CAP")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(3.0);
    let freq: f64 = std::env::var("FREQ")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(defaults.freq_weight);
    let gap: f32 = std::env::var("GAP")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(defaults.gap_weight);
    let records: Vec<Record> = std::io::BufReader::new(std::fs::File::open(&args[1]).unwrap())
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| {
            let v: serde_json::Value = serde_json::from_str(&line).ok()?;
            let word = v["word"].as_str()?.to_lowercase();
            if word.chars().count() < 2 || !word.chars().all(char::is_alphabetic) {
                return None;
            }
            let (width, height) = (number(&v, "canvas_width"), number(&v, "canvas_height"));
            let points = v["data"]
                .as_array()?
                .iter()
                .map(|p| Point {
                    x: number(p, "x") * width,
                    y: number(p, "y") * height,
                    t: p["t"].as_i64().unwrap_or(0),
                })
                .collect();
            Some(Record {
                word,
                sentence: v["sentence"].as_str().unwrap_or("").to_string(),
                word_idx: v["word_idx"].as_u64().unwrap_or(0) as usize,
                width,
                height,
                points,
            })
        })
        .collect();
    eprintln!("{} strokes", records.len());
    let chunk = records.len().div_ceil(threads).max(1);
    // Optional per-item dump. `item_id` is the record's index in `records`
    // (the filter is deterministic, so ids align across model dumps). Workers
    // tag their rows with a global index; the main thread sorts and writes
    // after the join, so the file does not depend on THREADS.
    let items_path = std::env::var("ITEMS").ok();
    type Row = (usize, bool, bool, bool, bool); // (item_id, blend_top1, blend_top4, raw_top1, raw_top4)
    let collect_rows = items_path.is_some();
    let worker_results: Vec<(usize, usize, usize, usize, usize, usize, usize, Vec<Row>)> =
        std::thread::scope(|scope| {
            let handles: Vec<_> = records
                .chunks(chunk)
                .enumerate()
                .map(|(chunk_idx, part)| {
                    let (trie, layout, lm) = (&trie, &layout, &lm);
                    let base = chunk_idx * chunk;
                    scope.spawn(move || {
                        let mut tuning = Tuning::default();
                        tuning.freq_weight = freq;
                        tuning.gap_weight = gap;
                        let mut decoder = Decoder::new(tuning);
                        let mut session = Session::new(lm.clone());
                        let (mut n, mut top1, mut top4, mut b1, mut b4, mut with_prev, mut moved) =
                            (0, 0, 0, 0, 0, 0, 0);
                        let mut rows: Vec<Row> = Vec::new();
                        for (i, r) in part.iter().enumerate() {
                            let keys = keymap(layout, r.width, r.height, cols);
                            let sources = [Source {
                                walker: trie,
                                log_weight: 0.0,
                                tier: Tier::Dictionary,
                                min_frequency: 0,
                            }];
                            let out = decoder.decode(
                                &r.points,
                                &keys,
                                r.width / cols,
                                &sources,
                                results,
                                None,
                                0,
                            );
                            let words: Vec<String> = out
                                .iter()
                                .map(|c| String::from_utf16_lossy(&c.word).to_lowercase())
                                .collect();
                            n += 1;
                            let raw_top1 = words.first() == Some(&r.word);
                            let raw_top4 = words.iter().take(4).any(|w| *w == r.word);
                            top1 += raw_top1 as usize;
                            top4 += raw_top4 as usize;
                            // Context: the sentence's words before the target.
                            let ctx: Vec<&str> =
                                r.sentence.split_whitespace().take(r.word_idx).collect();
                            let mut blended: Vec<(String, f64)> = out
                                .iter()
                                .zip(words.iter())
                                .map(|(c, w)| (w.clone(), c.score))
                                .collect();
                            let blend_top1;
                            let blend_top4;
                            // Known limitation: this harness skips empty-context
                            // strokes; the app blends them with the session's empty
                            // context. The fixed RESULTS pool also does not mirror
                            // the app's 8 -> 16 close-stroke widening.
                            if !ctx.is_empty() && blended.len() > 1 {
                                with_prev += 1;
                                session.set_context(Prefix::default(), &ctx);
                                // Fixed candidate pool of `RESULTS` (default 8),
                                // scored through its pieces. The reference here is
                                // the first finite candidate score; the app uses the
                                // current score leader's logprob, so a harness delta
                                // can differ when the leader's logprob is unknown.
                                let scored = blended.len().min(16);
                                let lps: Vec<Option<f32>> = words
                                    .iter()
                                    .take(scored)
                                    .map(|w| session.logprob(w))
                                    .collect();
                                if let Some(reference) =
                                    lps.iter().flatten().copied().find(|v| v.is_finite())
                                {
                                    let mut any = false;
                                    for (j, (_, score)) in
                                        blended.iter_mut().take(scored).enumerate()
                                    {
                                        if let Some(lp) = lps[j] {
                                            let shift =
                                                (weight * (lp - reference) as f64).clamp(-cap, cap);
                                            if shift != 0.0 {
                                                *score += shift;
                                                any = true;
                                            }
                                        }
                                    }
                                    if any {
                                        moved += 1;
                                        // The app uses a stable descending sort; take the
                                        // first tied candidate, not the last.
                                        blended.sort_by(|a, b| b.1.total_cmp(&a.1));
                                        let best = blended
                                            .first()
                                            .map(|x| x.0.clone())
                                            .unwrap_or_default();
                                        blend_top1 = best == r.word;
                                        b1 += blend_top1 as usize;
                                        blend_top4 =
                                            blended.iter().take(4).any(|(w, _)| *w == r.word);
                                        b4 += blend_top4 as usize;
                                        if collect_rows {
                                            rows.push((
                                                base + i,
                                                blend_top1,
                                                blend_top4,
                                                raw_top1,
                                                raw_top4,
                                            ));
                                        }
                                        continue;
                                    }
                                }
                            }
                            blend_top1 = raw_top1;
                            blend_top4 = raw_top4;
                            b1 += raw_top1 as usize;
                            b4 += raw_top4 as usize;
                            if collect_rows {
                                rows.push((base + i, blend_top1, blend_top4, raw_top1, raw_top4));
                            }
                        }
                        (n, top1, top4, b1, b4, with_prev, moved, rows)
                    })
                })
                .collect();
            handles.into_iter().map(|h| h.join().unwrap()).collect()
        });
    let mut all_rows: Vec<Row> = Vec::new();
    if collect_rows {
        for (_, _, _, _, _, _, _, ref rows) in &worker_results {
            all_rows.extend(rows.iter().cloned());
        }
    }
    if let Some(path) = items_path.as_deref() {
        all_rows.sort_by_key(|r| r.0);
        let mut f = match std::fs::File::create(path) {
            Ok(f) => f,
            Err(e) => {
                eprintln!("ITEMS={path}: cannot create dump file: {e}");
                std::process::exit(1);
            }
        };
        if let Err(e) = writeln!(f, "item_id\tblend_top1\tblend_top4\traw_top1\traw_top4") {
            eprintln!("ITEMS={path}: write header failed: {e}");
            std::process::exit(1);
        }
        for (id, b1r, b4r, r1r, r4r) in &all_rows {
            // 1 / 0 (not true / false) so `paired_eval.py` accepts the dump
            // -- its column contract is 0/1, and 0/1 also matches the
            // McNemar aesthetic everywhere else in the plan.
            let b1_out = if *b1r { 1u8 } else { 0u8 };
            let b4_out = if *b4r { 1u8 } else { 0u8 };
            let r1_out = if *r1r { 1u8 } else { 0u8 };
            let r4_out = if *r4r { 1u8 } else { 0u8 };
            if let Err(e) = writeln!(f, "{id}\t{b1_out}\t{b4_out}\t{r1_out}\t{r4_out}") {
                eprintln!("ITEMS={path}: write row failed: {e}");
                std::process::exit(1);
            }
        }
        if let Err(e) = f.flush() {
            eprintln!("ITEMS={path}: flush failed: {e}");
            std::process::exit(1);
        }
    }
    let (mut n, mut top1, mut top4, mut b1, mut b4, mut wp, mut moved) = (0, 0, 0, 0, 0, 0, 0);
    for (a, b, c, d, e, f, g, _) in worker_results {
        n += a;
        top1 += b;
        top4 += c;
        b1 += d;
        b4 += e;
        wp += f;
        moved += g;
    }
    println!("n {n} | no blend top1 {:.4} top4 {:.4} | blend top1 {:.4} top4 {:.4} | with prev {wp} moved {moved} | w {weight} cap {cap}",
        top1 as f64/n as f64, top4 as f64/n as f64, b1 as f64/n as f64, b4 as f64/n as f64);
    std::io::stdout().flush().ok();
}
