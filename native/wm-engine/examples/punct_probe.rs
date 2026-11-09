//! A look at a `.wmpt`: the mark the smart punctuation key would show for
//! each sentence given, with the probabilities, and the time one call takes.
//! Checks the reader against `tools/wmpunct/check.py`.
//!
//! ```text
//! cargo run --release --example punct_probe -- model.wmpt "how are you" "i am fine" "if you want"
//! ```

use std::time::Instant;

use wm_engine::punct::{argmax, Punct, LABELS};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let m = Punct::open(std::fs::read(&args[1]).expect("model")).expect("a .wmpt");
    println!(
        "{} e{} d{} layers {} heads {} ff {} ctx {} vocab {} langs {}",
        m.name,
        m.e,
        m.d,
        m.layers,
        m.heads,
        m.ff,
        m.ctx,
        m.vocab_size(),
        m.langs.len()
    );
    for sentence in args.iter().skip(2) {
        let words: Vec<&str> = sentence.split_whitespace().collect();
        let tokens = m.tokens_for(&words);
        let started = Instant::now();
        let p = m.predict_tokens(&tokens);
        let took = started.elapsed();
        let probs: Vec<String> = LABELS.iter().zip(&p).map(|(l, v)| format!("{l} {v:.4}")).collect();
        println!("{sentence:?} {tokens:?} -> {} ({}) in {took:?}", LABELS[argmax(&p, 3)], probs.join(" "));
    }
}
