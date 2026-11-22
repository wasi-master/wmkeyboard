//! Compiles libchewing-data's dictionary sources into the one file the
//! keyboard's Chewing engine opens:
//!
//!     chewing-dictc <word.csv> <tsi.csv> <version> <out.dat>
//!
//! libchewing ships the two as `word.dat` (single characters, in the order
//! their readings are listed) and `tsi.dat` (phrases), loaded as two layers.
//! This writes both into one trie, which looks up the same: a character's
//! readings keep `word.csv`'s order, and an entry in both files keeps the
//! higher frequency, as libchewing's layered lookup reports it. One file is
//! one download and one checksum.
//!
//! The output depends only on the inputs: no time, no temporary name.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Write};

use chewing::dictionary::{DictionaryBuilder, DictionaryInfo, DictionaryUsage, TrieBuilder};
use chewing::zhuyin::Syllable;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 5 {
        eprintln!("usage: chewing-dictc <word.csv> <tsi.csv> <version> <out.dat>");
        std::process::exit(2);
    }
    let mut builder = TrieBuilder::new();
    let mut seen: HashMap<(Vec<u16>, String), u32> = HashMap::new();
    let mut entries = 0usize;
    for path in &args[1..3] {
        let reader = BufReader::new(File::open(path).unwrap_or_else(|e| panic!("{path}: {e}")));
        for (n, line) in reader.lines().enumerate() {
            let line = line.unwrap_or_else(|e| panic!("{path}:{}: {e}", n + 1));
            let line = line.trim_end_matches('\r');
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            // phrase,frequency,ㄅㄆˊ ㄇㄈ — the reading's syllables space-separated.
            let mut fields = line.splitn(3, ',');
            let (Some(text), Some(freq), Some(reading)) = (fields.next(), fields.next(), fields.next()) else {
                panic!("{path}:{}: expected phrase,frequency,reading", n + 1);
            };
            let freq: u32 = freq.trim().parse().unwrap_or_else(|_| panic!("{path}:{}: bad frequency", n + 1));
            let syllables: Vec<Syllable> = reading
                .split_whitespace()
                .map(|s| s.parse().unwrap_or_else(|_| panic!("{path}:{}: bad syllable {s}", n + 1)))
                .collect();
            if syllables.is_empty() {
                continue;
            }
            let key = (syllables.iter().map(|s| s.to_u16()).collect::<Vec<_>>(), text.to_owned());
            match seen.get(&key) {
                Some(&had) if had >= freq => continue,
                // Inserting the same entry again updates it in place.
                _ => {
                    seen.insert(key, freq);
                }
            }
            builder.insert(&syllables, (text, freq).into()).expect("insert");
            entries += 1;
        }
    }
    builder
        .set_info(DictionaryInfo {
            name: "libchewing-data".into(),
            copyright: "Copyright (c) 2025 libchewing Core Team".into(),
            license: "LGPL-2.1-or-later".into(),
            version: args[3].clone(),
            software: format!("chewing-dictc (chewing {})", "0.13.1"),
            usage: DictionaryUsage::BuiltIn,
        })
        .expect("info");
    builder.set_usage(DictionaryUsage::BuiltIn);
    let out = File::create(&args[4]).unwrap_or_else(|e| panic!("{}: {e}", args[4]));
    let mut writer = BufWriter::new(out);
    let bytes = builder.write(&mut writer).expect("write");
    writer.flush().expect("flush");
    eprintln!("{} entries, {} distinct, {} bytes", entries, seen.len(), bytes);
}
