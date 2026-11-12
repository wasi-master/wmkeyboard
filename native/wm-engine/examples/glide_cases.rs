//! The built-in glide decoder on strokes drawn for named words, so a report
//! of the form "X comes out when I swipe Y" can be reproduced and a mark (a
//! pause, a loop) can be drawn on a chosen letter.
//!
//! cargo run --release --example glide_cases -- <qwerty.json> <word list> < cases
//!
//! One case a line on stdin:
//!
//!     word [rival] [cut=0.3] [jitter=0.06] [short=0.0] [pause=<letter index>:<ms>]
//!          [loop=<letter index>] [ahead=<n>] [keep=<share>] [seeds=8] [tuning...]
//!
//! `cut` is how far the finger cuts corners, in key widths (a Gaussian over
//! the path, so 0 is a ruler through the key centres). `jitter` is the
//! tremor, `short` how far before the last key the finger lifts, `pause`
//! holds on a letter for that long, `loop` draws a circle on one, `keep`
//! cuts the stroke at a share of its length (a finger lifted early) and
//! `ahead` asks the decoder for look-ahead guesses. Every other `name=value`
//! is a `Tuning` field, and `tuning=prefit` is the weights before the
//! real-swipe fit of 2026-10-22. `unclaimed_dwell=0.9 unclaimed_loop=1.1`
//! is what 0.6.4 to 0.6.6 shipped (issue #1250).
//!
//! Prints, per case, how many of the seeds read `word` first, the mean gap
//! in nats between `word` and the leader (or the runner-up when it leads),
//! and the first seed's pool.

use std::io::BufRead;

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

const WIDTH: f32 = 1000.0;
const HEIGHT: f32 = 420.0;
const KW: f32 = WIDTH / 10.0;
const SAMPLE_MS: f32 = 8.0;
/// Milliseconds a finger takes to cross one key width.
const MS_PER_KEY: f32 = 45.0;

struct Lcg(u64);

impl Lcg {
    fn next(&mut self) -> f32 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((self.0 >> 33) as f32 / (1u64 << 31) as f32) - 0.5
    }
}

fn prefit() -> Tuning {
    Tuning {
        max_point_cost: 12.0,
        gap_weight: 2.0,
        gap_window: 14.0,
        shape_weight: 2.5,
        repeat_cost: 0.05,
        dwell_full: 3.0,
        unclaimed_dwell: 2.0,
        unclaimed_loop: 0.5,
        shape_channel: 45.0,
        end_radius: 1.6,
        near_radius: 1.5,
        look_ahead_cost: 1.4,
        ..Tuning::default()
    }
}

fn set(t: &mut Tuning, name: &str, value: f64) -> bool {
    match name {
        "sigma" => t.sigma = value as f32,
        "max_point_cost" => t.max_point_cost = value as f32,
        "gap_weight" => t.gap_weight = value as f32,
        "gap_window" => t.gap_window = value as f32,
        "shape_weight" => t.shape_weight = value,
        "freq_weight" => t.freq_weight = value,
        "repeat_cost" => t.repeat_cost = value as f32,
        "unlooped_repeat" => t.unlooped_repeat = value as f32,
        "dwell_full" => t.dwell_full = value as f32,
        "unclaimed_dwell" => t.unclaimed_dwell = value as f32,
        "loop_extent" => t.loop_extent = value as f32,
        "loop_min_arc" => t.loop_min_arc = value as f32,
        "loop_radius" => t.loop_radius = value as f32,
        "wiggle_extent" => t.wiggle_extent = value as f32,
        "wiggle_weight" => t.wiggle_weight = value as f32,
        "loop_exclusion" => t.loop_exclusion = value as f32,
        "unclaimed_loop" => t.unclaimed_loop = value as f32,
        "shape_channel" => t.shape_channel = value,
        "start_radius" => t.start_radius = value as f32,
        "end_radius" => t.end_radius = value as f32,
        "near_radius" => t.near_radius = value as f32,
        "look_ahead_cost" => t.look_ahead_cost = value,
        _ => return false,
    }
    true
}

#[derive(Clone)]
struct Draw {
    cut: f32,
    jitter: f32,
    short: f32,
    pause: Option<(usize, f32)>,
    circle: Option<usize>,
    keep: f32,
}

/// The stroke a finger draws for `word`: through its keys, corners cut,
/// with tremor, at an even pace but for a hold.
fn stroke(word: &str, centres: &[(char, f32, f32)], draw: &Draw, rng: &mut Lcg) -> Option<Vec<Point>> {
    let mut way: Vec<(f32, f32)> = Vec::new();
    let mut letter_of: Vec<usize> = Vec::new();
    for (i, c) in word.chars().enumerate() {
        let (_, x, y) = centres.iter().find(|k| k.0 == c)?;
        if way.last() != Some(&(*x, *y)) {
            way.push((*x, *y));
            letter_of.push(i);
        }
    }
    if way.len() < 2 {
        return None;
    }
    if draw.short > 0.0 {
        let n = way.len();
        let (ax, ay) = way[n - 2];
        let (bx, by) = way[n - 1];
        let d = ((bx - ax).powi(2) + (by - ay).powi(2)).sqrt();
        let f = ((d - draw.short * KW) / d).max(0.2);
        way[n - 1] = (ax + (bx - ax) * f, ay + (by - ay) * f);
    }
    // Dense polyline, a twentieth of a key apart, remembering where each
    // waypoint fell.
    let step = KW * 0.05;
    let mut dense: Vec<(f32, f32)> = vec![way[0]];
    let mut at_way: Vec<usize> = vec![0];
    for w in way.windows(2) {
        let d = ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
        let n = (d / step).ceil().max(1.0) as usize;
        for i in 1..=n {
            let f = i as f32 / n as f32;
            dense.push((w[0].0 + (w[1].0 - w[0].0) * f, w[0].1 + (w[1].1 - w[0].1) * f));
        }
        at_way.push(dense.len() - 1);
    }
    // Corner cutting: a Gaussian along the path, the ends held.
    let mut smooth: Vec<(f32, f32)> = if draw.cut > 0.0 {
        let sigma = draw.cut * KW / step;
        let reach = (3.0 * sigma).ceil() as i64;
        (0..dense.len() as i64)
            .map(|i| {
                let (mut sx, mut sy, mut sw) = (0f32, 0f32, 0f32);
                for o in -reach..=reach {
                    let j = (i + o).clamp(0, dense.len() as i64 - 1) as usize;
                    let w = (-(o * o) as f32 / (2.0 * sigma * sigma)).exp();
                    sx += dense[j].0 * w;
                    sy += dense[j].1 * w;
                    sw += w;
                }
                (sx / sw, sy / sw)
            })
            .collect()
    } else {
        dense.clone()
    };
    // A circle on a letter, drawn on the way through; after the corner
    // cutting, which would otherwise flatten it.
    if let Some(letter) = draw.circle {
        if let Some(v) = letter_of.iter().position(|&l| l == letter) {
            let at = at_way[v];
            let (cx, cy) = smooth[at];
            let r = 0.3 * KW;
            let ring: Vec<(f32, f32)> = (0..=24)
                .map(|i| {
                    let a = i as f32 / 24.0 * std::f32::consts::TAU;
                    (cx + r * a.sin(), cy - r + r * a.cos())
                })
                .collect();
            let count = ring.len();
            smooth.splice(at..at, ring);
            for w in at_way.iter_mut().skip(v) {
                *w += count;
            }
        }
    }
    // Tremor: a slow random walk, so it bends the path rather than fuzzing it.
    let mut drift = (0f32, 0f32);
    let wobbled: Vec<(f32, f32)> = smooth
        .iter()
        .map(|&(x, y)| {
            drift.0 = drift.0 * 0.9 + rng.next() * draw.jitter * KW * 0.45;
            drift.1 = drift.1 * 0.9 + rng.next() * draw.jitter * KW * 0.45;
            (x + drift.0, y + drift.1)
        })
        .collect();
    let keep = ((wobbled.len() as f32 * draw.keep).ceil() as usize).clamp(3, wobbled.len());
    let hold_at = draw.pause.and_then(|(letter, ms)| letter_of.iter().position(|&l| l == letter).map(|v| (at_way[v], ms)));
    // Time: an even pace along the path, read off every `SAMPLE_MS`.
    let ms_per_step = MS_PER_KEY * 0.05;
    let mut out: Vec<Point> = Vec::new();
    let mut t = 0f32;
    let mut due = 0f32;
    for (i, &(x, y)) in wobbled.iter().take(keep).enumerate() {
        if t >= due {
            out.push(Point { x, y, t: t as i64 });
            due += SAMPLE_MS;
        }
        if let Some((at, ms)) = hold_at {
            if i == at {
                let until = t + ms;
                while t < until {
                    t += SAMPLE_MS;
                    out.push(Point { x: x + rng.next() * 0.02 * KW, y: y + rng.next() * 0.02 * KW, t: t as i64 });
                }
                due = t + SAMPLE_MS;
            }
        }
        t += ms_per_step;
    }
    let last = wobbled[keep - 1];
    out.push(Point { x: last.0, y: last.1, t: t as i64 });
    Some(out)
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let layout_json: serde_json::Value =
        serde_json::from_str(&std::fs::read_to_string(&args[1]).expect("layout")).expect("layout json");
    let centres: Vec<(char, f32, f32)> = layout_json["keys"]
        .as_array()
        .expect("keys")
        .iter()
        .filter_map(|k| {
            let letter = k["letter"].as_str()?.chars().next()?;
            Some((letter, k["cx"].as_f64()? as f32 * WIDTH, k["cy"].as_f64()? as f32 * HEIGHT))
        })
        .collect();
    let mut order: Vec<usize> = (0..centres.len()).collect();
    order.sort_by_key(|&i| centres[i].0 as i32);
    let keys = KeyMap::new(
        centres.iter().map(|k| k.1 / KW).collect(),
        centres.iter().map(|k| k.2 / KW).collect(),
        order.iter().map(|&i| centres[i].0 as i32).collect(),
        order.iter().map(|&i| i as i32).collect(),
    );
    let text = std::fs::read_to_string(&args[2]).expect("word list");
    let mut trie = UserTrie::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        trie.insert(&word, frequency);
    }
    let sources = [Source { walker: &trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];
    let quiet = std::env::var("QUIET").is_ok_and(|v| v == "1");
    let (mut total, mut total_hit) = (0usize, 0usize);

    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut words: Vec<&str> = Vec::new();
        let mut tuning = Tuning::default();
        let mut draw = Draw { cut: 0.3, jitter: 0.06, short: 0.0, pause: None, circle: None, keep: 1.0 };
        let mut seeds = 8u64;
        let mut ahead = 0usize;
        // `tuning=prefit` first, so the fields after it lay over it.
        if line.split_whitespace().any(|p| p == "tuning=prefit") {
            tuning = prefit();
        }
        for part in line.split_whitespace() {
            let Some((name, value)) = part.split_once('=') else {
                words.push(part);
                continue;
            };
            match name {
                "tuning" => {}
                "cut" => draw.cut = value.parse().expect("cut"),
                "jitter" => draw.jitter = value.parse().expect("jitter"),
                "short" => draw.short = value.parse().expect("short"),
                "keep" => draw.keep = value.parse().expect("keep"),
                "seeds" => seeds = value.parse().expect("seeds"),
                "ahead" => ahead = value.parse().expect("ahead"),
                "loop" => draw.circle = Some(value.parse().expect("loop")),
                "pause" => {
                    let (letter, ms) = value.split_once(':').expect("pause=<letter>:<ms>");
                    draw.pause = Some((letter.parse().expect("letter"), ms.parse().expect("ms")));
                }
                _ => assert!(set(&mut tuning, name, value.parse().expect("number")), "no field {name}"),
            }
        }
        let word = words[0];
        let rival = words.get(1).copied();
        let mut decoder = Decoder::new(tuning);
        let (mut hits, mut gap_sum, mut rival_wins) = (0usize, 0f64, 0usize);
        let mut first_pool = String::new();
        for seed in 0..seeds {
            let mut rng = Lcg(seed * 7919 + 17);
            let Some(points) = stroke(word, &centres, &draw, &mut rng) else {
                println!("{word}: a letter the layout has no key for");
                break;
            };
            let out = decoder.decode(&points, &keys, KW, &sources, 8, None, ahead);
            let pool: Vec<(String, f64, i32)> =
                out.iter().map(|c| (String::from_utf16_lossy(&c.word).to_lowercase(), c.score, c.ahead)).collect();
            let own = pool.iter().find(|c| c.0 == word).map(|c| c.1);
            let other = pool.iter().find(|c| c.0 != word).map(|c| c.1);
            let hit = pool.first().is_some_and(|c| c.0 == word);
            hits += hit as usize;
            if let (false, Some(r)) = (hit, rival) {
                rival_wins += pool.first().is_some_and(|c| c.0 == r) as usize;
            }
            // Positive when the word leads, by how much; negative when it trails.
            gap_sum += match (own, other) {
                (Some(a), Some(b)) => a - b,
                (Some(_), None) => 20.0,
                (None, _) => -20.0,
            };
            if seed == 0 {
                first_pool = pool
                    .iter()
                    .take(5)
                    .map(|c| if c.2 > 0 { format!("{}+{}:{:.2}", c.0, c.2, c.1) } else { format!("{}:{:.2}", c.0, c.1) })
                    .collect::<Vec<_>>()
                    .join(" ");
            }
        }
        total += seeds as usize;
        total_hit += hits;
        if !quiet {
            println!(
                "{word:<12} {hits}/{seeds} first, margin {:+.2}{} | {first_pool}",
                gap_sum / seeds as f64,
                rival.map(|r| format!(", {r} first {rival_wins}")).unwrap_or_default(),
            );
        }
    }
    println!("all cases: {total_hit}/{total} = {:.4}", total_hit as f64 / total.max(1) as f64);
}
