//! Errors say which file they came from and the calls they came out
//! through, outermost first, as Python's traceback does.

mod common;
use common::big_stack;

use wm_python::interp::{Limits, PyError, Runtime, STANDARD_MODULES};

fn failure(files: &'static [(&'static str, &'static str)], main: &'static str) -> PyError {
    big_stack(move || {
        let mut rt = Runtime::new(Limits::default(), STANDARD_MODULES);
        rt.enable_python_stdlib();
        rt.set_module_file("__main__", "main.py");
        for (file, source) in files {
            let module = file.trim_end_matches(".py");
            rt.add_source_module(module, source, false);
            rt.set_module_file(module, file);
        }
        rt.run(main).unwrap_err()
    })
}

fn frames(e: &PyError) -> Vec<(Option<&str>, Option<u32>, &str)> {
    e.frames.iter().map(|f| (f.file.as_deref(), f.line, f.function.as_str())).collect()
}

#[test]
fn calls_across_files() {
    let e = failure(
        &[("helpers.py", "def parse(s):\n    return int(s)\n\ndef twice(s):\n    return parse(s) * 2\n")],
        "import helpers\n\ndef go():\n    return helpers.twice('x')\n\ngo()\n",
    );
    assert_eq!(e.kind, "ValueError");
    assert_eq!((e.file.as_deref(), e.line), (Some("helpers.py"), Some(2)));
    assert_eq!(
        frames(&e),
        [(Some("main.py"), Some(6), "<module>"), (Some("main.py"), Some(4), "go"), (Some("helpers.py"), Some(5), "twice"), (Some("helpers.py"), Some(2), "parse")]
    );
}

#[test]
fn an_imported_module_failing_as_it_loads() {
    let e = failure(&[("broken.py", "x = 1\ny = 1 / 0\n")], "import json\nimport broken\n");
    assert_eq!(e.kind, "ZeroDivisionError");
    assert_eq!(frames(&e), [(Some("main.py"), Some(2), "<module>"), (Some("broken.py"), Some(2), "<module>")]);
}

#[test]
fn methods_generators_and_bundled_modules() {
    let e = failure(
        &[],
        "class C:\n    def m(self):\n        yield 1\n        raise KeyError('k')\n\ndef use():\n    for _ in C().m():\n        pass\n\nuse()\n",
    );
    assert_eq!(e.kind, "KeyError");
    assert_eq!(frames(&e), [(Some("main.py"), Some(10), "<module>"), (Some("main.py"), Some(7), "use"), (Some("main.py"), Some(4), "m")]);
    // A frame inside the bundled library has no file, but still a name.
    let e = failure(&[], "import json\nimport textwrap\ntextwrap.dedent(5)\n");
    let names: Vec<&str> = e.frames.iter().map(|f| f.function.as_str()).collect();
    assert_eq!(names.first(), Some(&"<module>"));
    assert!(e.frames.iter().skip(1).all(|f| f.file.is_none()), "{:?}", e.frames);
}

#[test]
fn a_caught_error_leaves_no_frames_on_the_next() {
    let e = failure(&[], "def f():\n    raise ValueError('a')\ntry:\n    f()\nexcept ValueError:\n    pass\nraise KeyError('b')\n");
    assert_eq!(frames(&e), [(Some("main.py"), Some(7), "<module>")]);
}

#[test]
fn runaway_recursion_keeps_the_innermost_frames() {
    let e = failure(&[], "def f(n):\n    return f(n + 1)\nf(0)\n");
    assert!(e.is_abort());
    assert_eq!(e.frames.len(), wm_python::interp::MAX_FRAMES);
    assert!(e.frames.iter().all(|f| f.function == "f" && f.line == Some(2)));
}

#[test]
fn syntax_errors_name_their_file() {
    let e = failure(&[("bad.py", "def (:\n")], "import bad\n");
    assert_eq!(e.file.as_deref(), Some("bad.py"));
    let e = big_stack(|| {
        let mut rt = Runtime::new(Limits::default(), STANDARD_MODULES);
        rt.set_module_file("__main__", "main.py");
        rt.run("x = (\n").unwrap_err()
    });
    assert_eq!(e.file.as_deref(), Some("main.py"));
}

/// Each case's frames, outermost first, as CPython 3.13's
/// `traceback.extract_tb` lists them for the same source run as main.py.
#[test]
fn re_raising_keeps_the_traceback() {
    let cases: &[(&str, &[(&str, u32)])] = &[
        (
            "def inner():\n    raise ValueError('x')\ndef middle():\n    try:\n        inner()\n    except ValueError:\n        raise\nmiddle()\n",
            &[("<module>", 8), ("middle", 5), ("inner", 2)],
        ),
        (
            "def inner():\n    raise ValueError('x')\ndef middle():\n    try:\n        inner()\n    except ValueError as e:\n        raise e\nmiddle()\n",
            &[("<module>", 8), ("middle", 7), ("middle", 5), ("inner", 2)],
        ),
        (
            "def middle():\n    try:\n        raise KeyError('k')\n    except KeyError as e:\n        x = 1\n        raise e\nmiddle()\n",
            &[("<module>", 7), ("middle", 6), ("middle", 3)],
        ),
        (
            "from contextlib import contextmanager\n@contextmanager\ndef cm():\n    yield\ndef f():\n    with cm():\n        raise ValueError('v')\nf()\n",
            &[("<module>", 8), ("f", 7)],
        ),
        ("def f():\n    try:\n        raise ValueError('v')\n    finally:\n        pass\nf()\n", &[("<module>", 6), ("f", 3)]),
    ];
    for (source, want) in cases {
        let e = failure(&[], source);
        let got: Vec<(&str, u32)> =
            e.frames.iter().filter(|f| f.file.as_deref() == Some("main.py")).map(|f| (f.function.as_str(), f.line.unwrap_or(0))).collect();
        assert_eq!(&got, want, "{source}");
        assert_eq!(e.line, Some(want[want.len() - 1].1), "{source}");
    }
}
