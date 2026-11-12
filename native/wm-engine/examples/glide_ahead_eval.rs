//! "Finish long words early" on real swipes: strokes for words of six
//! letters or more, cut at a share of their length the way a finger lifted
//! early leaves them, decoded with look-ahead and passed through the gate
//! the keyboard applies (`GlideGuessGate.admit`: a guess stands only when it
//! beats the best word the stroke spells by the tier's margin).
//!
//! cargo run --release --example glide_ahead_eval -- <records.jsonl> <qwerty.json> <word list>
//!
//! One measurement a line on stdin: `keep=0.6 margin=1 [ahead=8]
//! [tuning=prefit] [name=value ...]`. `keep=1` is the stroke drawn to its
//! end, where every guess that leads is a word nobody asked for.
//! `look_ahead_cost=1.4` is the charge 0.6.4 to 0.6.6 shipped (issue #1250).
//!
//! Prints how often the word leads, how often another guess leads instead,
//! and how often no guess is shown at all.

use std::io::BufRead;

use wm_engine::fuzzy::Tier;
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::{Decoder, Point, Source, Tuning};
use wm_engine::user_lexicon::UserTrie;

struct Record {
    word: String,
    width: f32,
    height: f32,
    points: Vec<Point>,
}

fn number(v: &serde_json::Value, key: &str) -> f32 {
    v[key].as_f64().unwrap_or(0.0) as f32
}

fn keymap(layout: &[(i32, f32, f32)], width: f32, height: f32) -> KeyMap {
    let kw = width / 10.0;
    let mut order: Vec<usize> = (0..layout.len()).collect();
    order.sort_by_key(|&i| layout[i].0);
    KeyMap::new(
        layout.iter().map(|k| k.1 * width / kw).collect(),
        layout.iter().map(|k| k.2 * height / kw).collect(),
        order.iter().map(|&i| layout[i].0).collect(),
        order.iter().map(|&i| i as i32).collect(),
    )
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

fn set(t: &mut Tuning, name: &str, value: f64) {
    match name {
        "shape_weight" => t.shape_weight = value,
        "shape_channel" => t.shape_channel = value,
        "gap_weight" => t.gap_weight = value as f32,
        "look_ahead_cost" => t.look_ahead_cost = value,
        "look_ahead_budget" => t.look_ahead_budget = value as i32,
        "end_radius" => t.end_radius = value as f32,
        "near_radius" => t.near_radius = value as f32,
        "unclaimed_dwell" => t.unclaimed_dwell = value as f32,
        _ => panic!("no tuning field {name}"),
    }
}

/// `path` up to `share` of its length.
fn cut(path: &[Point], share: f32) -> Vec<Point> {
    if share >= 1.0 {
        return path.to_vec();
    }
    let mut arc = vec![0f32];
    for w in path.windows(2) {
        arc.push(arc[arc.len() - 1] + ((w[1].x - w[0].x).powi(2) + (w[1].y - w[0].y).powi(2)).sqrt());
    }
    let stop = arc[arc.len() - 1] * share;
    path.iter().zip(&arc).filter(|(_, s)| **s <= stop).map(|(p, _)| *p).collect()
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
    let text = std::fs::read_to_string(&args[3]).expect("word list");
    let mut trie = UserTrie::new();
    for (word, frequency) in wm_engine::wordlist::parse(&text) {
        trie.insert(&word, frequency);
    }
    let min_letters: usize = std::env::var("MIN_LETTERS").ok().and_then(|v| v.parse().ok()).unwrap_or(6);
    let file = std::io::BufReader::new(std::fs::File::open(&args[1]).expect("records"));
    let records: Vec<Record> = file
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| {
            let v: serde_json::Value = serde_json::from_str(&line).ok()?;
            let word = v["word"].as_str()?.to_lowercase();
            if word.len() < min_letters || !word.bytes().all(|b| b.is_ascii_lowercase()) {
                return None;
            }
            let (width, height) = (number(&v, "canvas_width"), number(&v, "canvas_height"));
            let points: Vec<Point> = v["data"]
                .as_array()?
                .iter()
                .map(|p| Point { x: number(p, "x") * width, y: number(p, "y") * height, t: p["t"].as_i64().unwrap_or(0) })
                .collect();
            Some(Record { word, width, height, points })
        })
        .collect();
    eprintln!("{} strokes of {min_letters}+ letters", records.len());
    let sources = [Source { walker: &trie, log_weight: 0.0, tier: Tier::Dictionary, min_frequency: 0 }];

    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let line = line.trim().to_string();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut tuning = if line.split_whitespace().any(|p| p == "tuning=prefit") { prefit() } else { Tuning::default() };
        let (mut keep, mut margin, mut ahead) = (0.6f32, 1.0f64, 8usize);
        for part in line.split_whitespace() {
            let (name, value) = part.split_once('=').expect("name=value");
            match name {
                "tuning" => {}
                "keep" => keep = value.parse().expect("keep"),
                "margin" => margin = value.parse().expect("margin"),
                "ahead" => ahead = value.parse().expect("ahead"),
                _ => set(&mut tuning, name, value.parse().expect("number")),
            }
        }
        let mut decoder = Decoder::new(tuning);
        let (mut n, mut right, mut wrong, mut none, mut read_right) = (0usize, 0usize, 0usize, 0usize, 0usize);
        for r in &records {
            let path = cut(&r.points, keep);
            if path.len() < 3 {
                continue;
            }
            let keys = keymap(&layout, r.width, r.height);
            let out = decoder.decode(&path, &keys, r.width / 10.0, &sources, 8, None, ahead);
            n += 1;
            // GlideGuessGate.admit, then the leader.
            let best_read = out.iter().filter(|c| c.ahead == 0).map(|c| c.score).fold(f64::NEG_INFINITY, f64::max);
            let leader = out.iter().find(|c| c.ahead == 0 || c.score - best_read >= margin);
            match leader {
                Some(c) if String::from_utf16_lossy(&c.word).to_lowercase() == r.word => {
                    right += 1;
                    read_right += (c.ahead == 0) as usize;
                }
                Some(c) if c.ahead > 0 => wrong += 1,
                _ => none += 1,
            }
        }
        let f = |a: usize| a as f64 / n.max(1) as f64;
        println!(
            "{line:<58} n {n} | word leads {:.4} (as a reading {:.4}) | another guess leads {:.4} | no guess {:.4}",
            f(right),
            f(read_right),
            f(wrong),
            f(none)
        );
    }
}
