//! Runs a script the way the PyMini oracle used for the probe corpus does
//! (see `tests/probes.rs`): `run(src)` and its repr, or
//! `ERROR: <description>`, then each printed line as `OUT: <line>`.
//! With a second argument, loads the file and calls `transform(arg)` instead.
//!
//!     cargo run --example oracle -- script.py

use wm_python::interp::{Limits, Runtime, Value, STANDARD_MODULES};

pub fn main() {
    let args: Vec<String> = std::env::args().collect();
    let src = std::fs::read_to_string(&args[1]).expect("read script");
    let child = std::thread::Builder::new().stack_size(64 << 20).spawn(move || {
        let mut py = Runtime::new(Limits::PYMINI, STANDARD_MODULES);
        let mut out = String::new();
        let r = if args.len() > 2 {
            py.load(&src).and_then(|_| py.call("transform", vec![Value::str(&args[2])]))
        } else {
            py.run(&src)
        };
        match r {
            Ok(v) => out.push_str(&format!("{}\n", v.repr())),
            Err(e) => out.push_str(&format!("ERROR: {}\n", e.description())),
        }
        for l in py.output() {
            out.push_str(&format!("OUT: {l}\n"));
        }
        print!("{out}");
    });
    child.unwrap().join().unwrap();
}
