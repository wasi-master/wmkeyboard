//! How long the built-in glide decoder takes per stroke, on the word lists a
//! keyboard really decodes against: a Hinglish user glides against 179k
//! words of their own list and English beside it, where the bundled English
//! list alone is 17k. Single-threaded, wall clock, after a warm-up.
//!
//! cargo run --release --example glide_latency -- <records.jsonl> <qwerty.json> <list> [<list> ...]
//!
//! Every list is one source, as `SuggestionEngine.walkSources` hands them to
//! the decoder, the first at log weight 0 and the rest at `SECONDARY` (-1.5
//! unless set). `CAP` is the vocabulary cap in words per list (300000, the
//! shipped "Large"; 0 for none). `POPS` and `STATES` set the decode's budget
//! (the lift's 4,000 / 12,288 unless set; a preview is 2,000 / 8,192),
//! `RESULTS` the pool (8, `GLIDE_RERANK_POOL`), `AHEAD` the look-ahead
//! limit (0). `LIMIT` strokes (500). Prints the mean, median and p95
//! milliseconds and the top-1 over the strokes whose word some list holds,
//! so a cheaper budget can be judged on what it costs, and the states the
//! walk popped and the column cells it read per stroke, which do not
//! wobble with the machine's load the way the clock does. `DUMP=<path>`
//! writes every stroke's candidates with their scores' exact bits, for
//! proving a change to the walk returns the same answers.

use std::collections::HashSet;
use std::io::BufRead;
use std::time::Instant;

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

fn env<T: std::str::FromStr>(name: &str, default: T) -> T {
    std::env::var(name).ok().and_then(|v| v.parse().ok()).unwrap_or(default)
}

fn number(v: &serde_json::Value, key: &str) -> f32 {
    v[key].as_f64().unwrap_or(0.0) as f32
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let layout_json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&args[2]).expect("layout")).expect("layout json");
    let layout: Vec<(i32, f32, f32)> = layout_json["keys"]
        .as_array()
        .expect("keys")
        .iter()
        .filter_map(|k| Some((k["letter"].as_str()?.chars().next()? as i32, number(k, "cx"), number(k, "cy"))))
        .collect();
    let cap: usize = env("CAP", 300_000);
    let secondary: f64 = env("SECONDARY", -1.5);
    let mut known: HashSet<String> = HashSet::new();
    let mut tries: Vec<(UserTrie, i32)> = Vec::new();
    for path in &args[3..] {
        let text = std::fs::read_to_string(path).expect("word list");
        let entries = wm_engine::wordlist::parse(&text);
        let mut freqs: Vec<i32> = entries.iter().map(|e| e.1).collect();
        freqs.sort_unstable_by(|a, b| b.cmp(a));
        let floor = if cap > 0 && cap < freqs.len() { freqs[cap - 1] } else { 0 };
        let mut trie = UserTrie::new();
        for (word, frequency) in &entries {
            known.insert(word.to_lowercase());
            trie.insert(word, *frequency);
        }
        eprintln!("{path}: {} words, cap floor {floor}", entries.len());
        tries.push((trie, floor));
    }
    let sources: Vec<Source> = tries
        .iter()
        .enumerate()
        .map(|(i, (trie, floor))| Source {
            walker: trie,
            log_weight: if i == 0 { 0.0 } else { secondary },
            tier: Tier::Dictionary,
            min_frequency: *floor,
        })
        .collect();

    let limit: usize = env("LIMIT", 500);
    let file = std::io::BufReader::new(std::fs::File::open(&args[1]).expect("records"));
    let strokes: Vec<(String, f32, f32, Vec<Point>)> = file
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| {
            let v: serde_json::Value = serde_json::from_str(&line).ok()?;
            let word = v["word"].as_str()?.to_lowercase();
            let (w, h) = (number(&v, "canvas_width"), number(&v, "canvas_height"));
            let points = v["data"]
                .as_array()?
                .iter()
                .map(|p| Point { x: number(p, "x") * w, y: number(p, "y") * h, t: p["t"].as_i64().unwrap_or(0) })
                .collect();
            Some((word, w, h, points))
        })
        .take(limit)
        .collect();

    let mut tuning = Tuning::default();
    tuning.max_pops = env("POPS", tuning.max_pops);
    tuning.max_states = env("STATES", tuning.max_states);
    let results: usize = env("RESULTS", 8);
    let ahead: usize = env("AHEAD", 0);
    let mut decoder = Decoder::new(tuning);

    let dump = std::env::var("DUMP").ok().filter(|p| !p.is_empty());
    let mut lines: Vec<String> = Vec::new();
    let mut work = (0usize, 0u64, 0u64, 0u64);
    let mut run = |decoder: &mut Decoder, record: bool| -> (Vec<f64>, usize, usize) {
        let mut times = Vec::new();
        let (mut n, mut top1) = (0, 0);
        for (word, w, h, points) in &strokes {
            let kw = w / 10.0;
            let mut order: Vec<usize> = (0..layout.len()).collect();
            order.sort_by_key(|&i| layout[i].0);
            let keys = KeyMap::new(
                layout.iter().map(|k| k.1 * w / kw).collect(),
                layout.iter().map(|k| k.2 * h / kw).collect(),
                order.iter().map(|&i| layout[i].0).collect(),
                order.iter().map(|&i| i as i32).collect(),
            );
            let started = Instant::now();
            let out = decoder.decode(points, &keys, kw, &sources, results, None, ahead);
            let ms = started.elapsed().as_secs_f64() * 1000.0;
            if record {
                times.push(ms);
                // STATS
                work.0 += decoder.pops;
                work.1 += decoder.cells;
                work.2 += decoder.remain_cells;
                work.3 += decoder.skipped;
                // STATS END
                if dump.is_some() {
                    let cands: Vec<String> = out
                        .iter()
                        .map(|c| format!("{}:{:016x}:{}", String::from_utf16_lossy(&c.word), c.score.to_bits(), c.ahead))
                        .collect();
                    lines.push(format!("{word}\t{}", cands.join("|")));
                }
                if known.contains(word) {
                    n += 1;
                    if out.first().is_some_and(|c| String::from_utf16_lossy(&c.word).to_lowercase() == *word) {
                        top1 += 1;
                    }
                }
            }
        }
        (times, n, top1)
    };
    run(&mut decoder, false);
    let (mut times, n, top1) = run(&mut decoder, true);
    times.sort_by(f64::total_cmp);
    let mean = times.iter().sum::<f64>() / times.len() as f64;
    let at = |q: f64| times[((times.len() - 1) as f64 * q) as usize];
    println!(
        "strokes {}  mean {:.2} ms  p50 {:.2}  p95 {:.2}  max {:.2}  top-1 {:.4} ({n})  pops/stroke {:.0}  cells/stroke {:.0}  remain cells/stroke {:.0}  skipped/stroke {:.0}",
        times.len(),
        mean,
        at(0.5),
        at(0.95),
        at(1.0),
        top1 as f64 / n.max(1) as f64,
        work.0 as f64 / times.len() as f64,
        work.1 as f64 / times.len() as f64,
        work.2 as f64 / times.len() as f64,
        work.3 as f64 / times.len() as f64,
    );
    if let Some(path) = dump {
        std::fs::write(path, lines.join("\n") + "\n").expect("dump");
    }
}
