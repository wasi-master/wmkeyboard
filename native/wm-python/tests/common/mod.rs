//! Port of PyTestHelpers.kt (PyMini's tests/Helpers.swift).
#![allow(dead_code)]

use wm_python::interp::{PyError, Runtime, Value};

/// Runs [f] on a thread with a large stack: deep recursion in a debug build
/// needs more than the test harness's default 2 MB.
pub fn big_stack<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

/// `str()` of an expression.
pub fn evaluate(expression: &str) -> String {
    match Runtime::default().run(expression) {
        Ok(v) => v.to_str(),
        Err(e) => panic!("{expression}: {e}"),
    }
}

/// Run a script and read back `transform(input)` when it defines one, otherwise its `result` global.
pub fn output_with(source: &str, input: &str) -> String {
    let mut py = Runtime::default();
    py.load(source).unwrap_or_else(|e| panic!("{e}"));
    if py.has_function("transform") {
        return py.call("transform", vec![Value::str(input)]).unwrap_or_else(|e| panic!("{e}")).to_str();
    }
    py.get("result").map(|v| v.to_str()).unwrap_or_else(|| "None".into())
}

pub fn output(source: &str) -> String {
    output_with(source, "")
}

/// The message a failing script ends with.
pub fn failure(source: &str) -> String {
    match Runtime::default().load(source) {
        Err(e) => e.to_string(),
        Ok(()) => panic!("expected an error"),
    }
}

/// The error [r] holds.
pub fn py_error<T: std::fmt::Debug>(r: Result<T, PyError>) -> PyError {
    r.expect_err("expected a PyError")
}
