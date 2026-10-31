//! Cuts each line of stdin into words with `segment::words`, the segmenter the
//! keyboard reads Thai, Lao, Khmer and Burmese context with, and writes them
//! space-separated. The data repo's `build_corpus_packs.py` counts n-grams over
//! this output so that a pack's keys are the words the keyboard will look up.

use std::io::{self, BufRead, Write};
use wm_engine::segment;

fn main() -> io::Result<()> {
    let stdin = io::stdin();
    let mut out = io::stdout().lock();
    for line in stdin.lock().lines() {
        writeln!(out, "{}", segment::words_str(&line?).join(" "))?;
        out.flush()?;
    }
    Ok(())
}
