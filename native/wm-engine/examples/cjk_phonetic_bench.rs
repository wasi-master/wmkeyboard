//! The Rust side of native/bench/phase0's `cjk` and `phonetic` groups: the
//! ports nothing on the phone calls yet, timed on the same files and buffers
//! as the Kotlin composers, with no JNI crossing. Run the Kotlin group first:
//! it writes `bn_inputs.txt`.
//!
//! ```text
//! cargo run --release --example cjk_phonetic_bench -- <data dir> [dump]
//! ```
//!
//! With `dump` it also writes `rust_*.txt` next to the Kotlin group's
//! `kotlin_*.txt`, one `buffer<TAB>candidates` line per buffer, to compare.

use std::fmt::Write as _;
use std::fs;
use std::hint::black_box;
use std::path::Path;
use std::time::{Duration, Instant};

use wm_engine::bengali_index::BengaliPhoneticIndex;
use wm_engine::cjk::dict::ConversionDictionary;
use wm_engine::cjk::engine::{Cjk, Method};
use wm_engine::cjk::ngrams::CjkNgrams;
use wm_engine::phonetic_index::PhoneticIndex;
use wm_engine::wordlist;

const JAPANESE_ROMAJI: [&str; 16] = [
    "watashihagakuseidesu",
    "kyouhaiitenkidesune",
    "ashitanokaigiwananjikaradesuka",
    "arigatougozaimasu",
    "denshawookuremashita",
    "tomodachitoeigawomimashita",
    "konbanhananiwotabemasuka",
    "nihongonobenkyouhatanoshii",
    "sumimasenmichiwooshietekudasai",
    "shigotogaowattararenrakushimasu",
    "kaisyanomaedematteimasu",
    "ameganottekitanode",
    "kodomonokoronoomoide",
    "sensyuunodoyoubiniikimashita",
    "ohayougozaimasu",
    "yoroshikuonegaishimasu",
];

/// The harness's rule: warm for a second, then up to `max_calls` or three seconds.
fn bench<T>(name: &str, min_warm: usize, max_calls: usize, mut op: impl FnMut() -> T) {
    let warm_until = Instant::now() + Duration::from_secs(1);
    let mut warmed = 0;
    while warmed < min_warm || Instant::now() < warm_until {
        black_box(op());
        warmed += 1;
    }
    let stop_at = Instant::now() + Duration::from_secs(3);
    let mut times = Vec::with_capacity(max_calls);
    while times.len() < max_calls && (times.len() < 15 || Instant::now() < stop_at) {
        let t0 = Instant::now();
        black_box(op());
        times.push(t0.elapsed().as_secs_f64() * 1e3);
    }
    times.sort_by(f64::total_cmp);
    let n = times.len();
    println!(
        "{name:<46} median {:9.3} ms  p95 {:9.3} ms  n={n}",
        times[n / 2],
        times[(n * 95 / 100).min(n - 1)]
    );
}

/// Resident memory in MB; 0 where there is no `/proc`.
fn rss_mb() -> f64 {
    let status = fs::read_to_string("/proc/self/status").unwrap_or_default();
    status
        .lines()
        .find_map(|l| l.strip_prefix("VmRSS:"))
        .and_then(|v| v.split_whitespace().next()?.parse::<f64>().ok())
        .map_or(0.0, |kb| kb / 1024.0)
}

fn first_load<T>(name: &str, op: impl FnOnce() -> T) -> T {
    let before = rss_mb();
    let t0 = Instant::now();
    let value = op();
    println!(
        "{name:<46} first load {:9.1} ms, RSS +{:.1} MB",
        t0.elapsed().as_secs_f64() * 1e3,
        rss_mb() - before
    );
    value
}

fn prefixes(words: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    for word in words {
        let chars: Vec<char> = word.chars().collect();
        for n in 1..=chars.len() {
            out.push(chars[..n].iter().collect());
        }
    }
    out
}

fn per_length(name: &str, buffers: &[String], mut op: impl FnMut(&str) -> usize) {
    let mut i = 0;
    bench(&format!("{name}, every prefix"), 20, 400, || {
        i += 1;
        op(&buffers[i % buffers.len()])
    });
    for (label, low, high) in [
        ("1-8", 1, 8),
        ("9-16", 9, 16),
        ("17+", 17, usize::MAX),
    ] {
        let some: Vec<&String> = buffers
            .iter()
            .filter(|b| (low..=high).contains(&b.chars().count()))
            .collect();
        if some.is_empty() {
            continue;
        }
        let mut k = 0;
        bench(&format!("  {name}, {label} letters"), 20, 400, || {
            k += 1;
            op(some[k % some.len()])
        });
    }
}

fn dump(file: &Path, buffers: &[String], mut answer: impl FnMut(&str) -> Vec<String>) {
    let mut out = String::new();
    for b in buffers {
        let _ = writeln!(out, "{b}\t{}", answer(b).join("|"));
    }
    fs::write(file, out).expect("dump");
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let dir = Path::new(&args[1]);
    let dumping = args.get(2).is_some_and(|a| a == "dump");
    let text = |name: &str| fs::read_to_string(dir.join(name)).expect(name);

    let mut cjk = Cjk::bundled();
    cjk.learning = false;
    cjk.pinyin_dict = first_load("ConversionDictionary pinyin_v2.tsv", || {
        ConversionDictionary::parse(&text("pinyin_v2.tsv"))
    });
    cjk.pinyin_ngrams = first_load("CjkNgrams pinyin_ngrams.tsv", || {
        CjkNgrams::parse(&text("pinyin_ngrams.tsv"))
    });
    cjk.japanese_dict = first_load("ConversionDictionary ja_kana.tsv", || {
        ConversionDictionary::parse(&text("ja_kana.tsv"))
    });
    bench("ConversionDictionary pinyin_v2.tsv, warm", 1, 5, || {
        ConversionDictionary::parse(&text("pinyin_v2.tsv")).len()
    });
    bench("CjkNgrams pinyin_ngrams.tsv, warm", 1, 5, || {
        CjkNgrams::parse(&text("pinyin_ngrams.tsv")).is_empty()
    });
    bench("ConversionDictionary ja_kana.tsv, warm", 1, 3, || {
        ConversionDictionary::parse(&text("ja_kana.tsv")).len()
    });

    let clauses: Vec<String> = text("pinyin_eval.tsv")
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.starts_with('#'))
        .step_by(5)
        .take(200)
        .map(|l| l.split('\t').next().unwrap_or("").to_string())
        .collect();
    let pinyin = prefixes(&clauses);
    let romaji: Vec<String> = JAPANESE_ROMAJI.iter().map(|s| s.to_string()).collect();
    let kana = prefixes(&romaji);
    println!(
        "cjk: {} clauses, {} pinyin buffers, {} romaji buffers",
        clauses.len(),
        pinyin.len(),
        kana.len()
    );
    per_length("Cjk::candidates Pinyin", &pinyin, |b| {
        cjk.invalidate();
        cjk.candidates(Method::Pinyin, b, 12).len()
    });
    per_length("Cjk::candidates Japanese", &kana, |b| {
        cjk.invalidate();
        cjk.candidates(Method::Japanese, b, 12).len()
    });
    if dumping {
        dump(&dir.join("rust_pinyin.txt"), &pinyin, |b| {
            cjk.invalidate();
            cjk.candidates(Method::Pinyin, b, 12).into_iter().map(|c| c.text).collect()
        });
        dump(&dir.join("rust_japanese.txt"), &kana, |b| {
            cjk.invalidate();
            cjk.candidates(Method::Japanese, b, 12).into_iter().map(|c| c.text).collect()
        });
    }
    drop(cjk);

    let typed: Vec<String> = text("bn_inputs.txt").lines().map(str::to_string).collect();
    let inputs = prefixes(&typed);
    let index = first_load("BengaliPhoneticIndex::new bn_full_v2.txt", || {
        BengaliPhoneticIndex::new(wordlist::parse(&text("bn_full_v2.txt")))
    });
    per_length("BengaliPhoneticIndex::lookup", &inputs, |b| index.lookup(b).len());
    per_length("BengaliPhoneticIndex::lookup_loose", &inputs, |b| index.lookup_loose(b).len());
    if dumping {
        dump(&dir.join("rust_bn.txt"), &inputs, |b| index.lookup(b));
    }
    println!("done");
}
