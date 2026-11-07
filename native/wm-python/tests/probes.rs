//! The differential corpus: each `tests/fixtures/probes/NNN-name.py` was run
//! through a real PyMini 2.0.1 build (`pymini_oracle`, built from the Swift
//! sources with a main that does `PyRuntime().run(src)`, prints the result's
//! repr or `ERROR: <description>`, then each printed line as `OUT: <line>`),
//! and its output saved beside it as `.out`. This port, run the same way
//! under PyMini's own limits (`Limits::PYMINI`), must print the same.
//!
//! Left out on purpose: scripts on which PyMini itself crashes (runaway
//! recursion, `int(1e19)`), and `random.seed(str)`, which PyMini seeds from
//! Swift's per-process `hashValue`.

use wm_python::interp::{Limits, Runtime, STANDARD_MODULES};

fn run_like_the_oracle(src: String) -> String {
    std::thread::Builder::new()
        .stack_size(64 << 20)
        .spawn(move || {
            let mut py = Runtime::new(Limits::PYMINI, STANDARD_MODULES);
            let mut out = match py.run(&src) {
                Ok(v) => format!("{}\n", v.repr()),
                Err(e) => format!("ERROR: {}\n", e.description()),
            };
            for l in py.output() {
                out.push_str(&format!("OUT: {l}\n"));
            }
            out
        })
        .unwrap()
        .join()
        .unwrap()
}

#[test]
fn probes_match_pymini() {
    let dir = format!("{}/tests/fixtures/probes", env!("CARGO_MANIFEST_DIR"));
    let mut names: Vec<_> = std::fs::read_dir(&dir)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "py"))
        .collect();
    names.sort();
    assert!(names.len() >= 80, "only {} probes", names.len());
    let mut failures = Vec::new();
    for py in &names {
        let src = std::fs::read_to_string(py).unwrap();
        let expected = std::fs::read_to_string(py.with_extension("out")).unwrap();
        let actual = run_like_the_oracle(src);
        if actual != expected {
            failures.push(format!("{}\n--- expected\n{expected}--- actual\n{actual}", py.display()));
        }
    }
    assert!(failures.is_empty(), "{} of {} probes differ:\n{}", failures.len(), names.len(), failures.join("\n"));
}
