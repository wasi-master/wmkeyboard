//! The context model's say on whole glided phrases (`glide_beam_eval`'s
//! `PHRASES=1`): reads `<context words>\t<phrase words>` lines from stdin and
//! prints ln P(phrase | context), one per line, `nan` where a word cannot be
//! read, for fitting how much the phrase lattice should weigh it.
//!
//! cargo run --release --example phrase_lm_eval -- model.wmlm < phrases.tsv > logprobs.txt

use std::io::{BufRead, Write};
use std::sync::Arc;

use wm_engine::lm::{Lm, Prefix, Session};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let lm = Arc::new(Lm::open(std::fs::read(&args[1]).expect("model")).expect("a .wmlm"));
    let mut session = Session::new(lm);
    let prefix = Prefix::default();
    let mut out = std::io::BufWriter::new(std::io::stdout().lock());
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        let (context, phrase) = line.split_once('\t').expect("two columns");
        let mut words: Vec<&str> = context.split_whitespace().collect();
        let mut total = 0f32;
        for word in phrase.split_whitespace() {
            session.set_context(prefix, &words);
            total += session.logprob(word).unwrap_or(f32::NAN);
            words.push(word);
        }
        writeln!(out, "{total}").unwrap();
    }
}
