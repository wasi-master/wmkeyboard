//! Host check: `cargo run --release --example probe -- <bundle.drb> "<text>"`.
use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let t = Instant::now();
    let checker = wmdivvungrammar::Checker::open(&args[1]).expect("open");
    eprintln!("open: {:?}", t.elapsed());
    eprintln!("locales: {}", checker.message_locales().unwrap_or_else(|e| e));
    for text in &args[2..] {
        let t = Instant::now();
        let out = checker.check(text, &["en".to_string()]).expect("check");
        eprintln!("check: {:?}", t.elapsed());
        println!("{out}");
    }
}
