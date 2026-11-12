//! Speed on a 1,500-line plugin. The ceilings hold in release builds
//! (`cargo test --release --test bench`); a debug build only prints.

mod common;

use std::time::{Duration, Instant};

use common::*;
use wm_python_tooling::complete::complete;
use wm_python_tooling::diagnostics::diagnose;
use wm_python_tooling::document::{Document, Host};
use wm_python_tooling::lexer::lex_str;

fn plugin() -> String {
    let mut out = String::from("import json\nimport time\n\nitems = []\ncount = 0\n\n\n");
    let mut n = 0;
    while out.lines().count() < 1500 {
        out.push_str(&format!(
            "def helper_{n}(text, by=1):\n    \"\"\"Shifts text by {n}.\"\"\"\n    global count\n    result = []\n    for c in text:\n        if c.isalpha():\n            result.append(chr(ord(c) + by))\n        elif c in \"0123456789\":\n            result.append(c * 2)\n        else:\n            result.append(c)\n    count += len(result)\n    data = json.dumps({{\"n\": {n}, \"at\": time.time(), \"items\": [x for x in items if x]}})\n    return \"\".join(result) + f\"{{data}}\"\n\n\n"
        ));
        n += 1;
    }
    out.push_str("def render():\n    return ui.column(ui.label(str(count)), ui.button(\"go\", \"Go\"))\n\n\ndef on_event(e):\n    if e.type == \"click\" and e.id == \"go\":\n        helper_0(\"abc\")\n");
    out
}

fn best<T>(runs: usize, mut f: impl FnMut() -> T) -> Duration {
    (0..runs)
        .map(|_| {
            let start = Instant::now();
            std::hint::black_box(f());
            start.elapsed()
        })
        .min()
        .unwrap()
}

#[test]
fn ceilings_on_a_1500_line_plugin() {
    let source = plugin();
    let lines = source.lines().count();
    assert!((1500..1600).contains(&lines));
    let host = Host { panel: Some(true), storage: Some(false), reminders: Some(false), selection: Some(false), actions: Some(vec![]), modules: None, verdict: None };
    let lex = best(5, || lex_str(&source));
    let analyse = best(5, || {
        let doc = Document::new(&source);
        diagnose(&doc, &host).diagnostics.len()
    });
    let doc = Document::new(&source);
    assert!(serious(&source, &host).is_empty(), "{:?}", serious(&source, &host));
    let carets: Vec<u32> = ["json.", "result.app", "ui.", "    re"].iter().filter_map(|m| source.rfind(m).map(|at| (at + m.len()) as u32)).collect();
    let completion = best(5, || carets.iter().map(|c| complete(&doc, *c, true, &host).map_or(0, |c| c.items.len())).sum::<usize>()) / carets.len() as u32;
    println!("{lines} lines, {} units: lex {lex:?}, analyse+diagnostics {analyse:?}, completion {completion:?}", source.encode_utf16().count());
    if !cfg!(debug_assertions) {
        assert!(lex < Duration::from_millis(15), "lex {lex:?}");
        assert!(analyse < Duration::from_millis(60), "analyse {analyse:?}");
        assert!(completion < Duration::from_millis(20), "completion {completion:?}");
    }
}
