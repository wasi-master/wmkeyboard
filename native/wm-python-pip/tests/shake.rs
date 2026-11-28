//! `shake` on small plugins, and the check that matters: a shaken plugin
//! draws exactly what the whole one draws.

use std::collections::BTreeMap;

use serde_json::{json, Value as Json};
use wm_python_pip::{shake, ShakeOptions, ShakeReport};

fn files(list: &[(&str, &str)]) -> BTreeMap<String, String> {
    list.iter().map(|(p, s)| (p.to_string(), s.to_string())).collect()
}

fn on_big_stack<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

fn shaken(list: &[(&str, &str)], roots: &[&str], symbols: bool) -> ShakeReport {
    let f = files(list);
    let roots: Vec<String> = roots.iter().map(|s| s.to_string()).collect();
    on_big_stack(move || shake(&f, "main.py", &roots, &ShakeOptions { symbols }))
}

/// The plugin's first draw as the keyboard runs it: `{ok, ui, log, error}`.
fn render(files: &BTreeMap<String, String>) -> Json {
    let files = files.clone();
    on_big_stack(move || {
        let modules: serde_json::Map<String, Json> =
            files.iter().filter(|(p, _)| p.as_str() != "main.py").map(|(p, s)| (p.clone(), Json::String(s.clone()))).collect();
        let config = json!({
            "id": "com.example.shake", "version": "1.0", "permissions": [], "mode": "panel", "apiVersion": 5,
            "files": modules, "settings": {}, "context": {"locale": "en", "theme": "light", "surface": "panel"},
        });
        let mut session = wm_python::host::Session::new(&config.to_string()).unwrap();
        let r: Json = serde_json::from_str(&session.load(&files["main.py"])).unwrap();
        json!({"ok": r["ok"], "ui": r["ui"], "log": r["log"], "error": r.get("error")})
    })
}

const INIT: &str = r#""""Text helpers."""
from .case import shout, whisper
from .count import words

__version__ = "1.0"
__all__ = ["shout", "whisper", "words"]
"#;

const CASE: &str = r#"import re

_SPACES = re.compile(r"\s+")


def _squash(text):
    return _SPACES.sub(" ", text.strip())


def shout(text):
    """Loud."""
    return _squash(text).upper() + "!"


def whisper(text):
    return _squash(text).lower() + "..."


class Loud:
    def __init__(self, text):
        self.text = text

    def say(self):
        return shout(self.text)


if __name__ == "__main__":
    print(shout("cli"))
"#;

const COUNT: &str = r#"from .case import _squash


def words(text):
    return len(_squash(text).split(" "))


def chars(text):
    return len(text)
"#;

const UNUSED: &str = "def nothing():\n    return None\n";

const MAIN: &str = r#"from wm import ui
from textkit import shout


def render():
    with ui.column():
        ui.title(shout("  hello   world "))
"#;

fn textkit(main: &str) -> Vec<(&'static str, String)> {
    vec![
        ("main.py", main.to_string()),
        ("textkit/__init__.py", INIT.to_string()),
        ("textkit/case.py", CASE.to_string()),
        ("textkit/count.py", COUNT.to_string()),
        ("textkit/unused.py", UNUSED.to_string()),
    ]
}

fn borrow<'a>(v: &'a [(&'static str, String)]) -> Vec<(&'static str, &'a str)> {
    v.iter().map(|(p, s)| (*p, s.as_str())).collect()
}

#[test]
fn modules_nothing_imports_are_left_out() {
    let plugin = textkit(MAIN);
    let r = shaken(&borrow(&plugin), &["textkit"], false);
    assert_eq!(r.removed_files, vec!["textkit/unused.py"]);
    assert!(r.removed_symbols.is_empty());
    assert_eq!(r.files["textkit/case.py"], CASE, "module shaking leaves kept files alone");
    assert!(r.bytes_after < r.bytes_before);
}

#[test]
fn unused_definitions_go_and_the_plugin_draws_the_same() {
    let plugin = textkit(MAIN);
    let r = shaken(&borrow(&plugin), &["textkit"], true);
    let before = render(&files(&borrow(&plugin)));
    let after = render(&r.files);
    assert_eq!(before["ok"], true, "{before}");
    assert_eq!(before, after);
    // words() is never used, so count.py is not even loaded any more.
    assert_eq!(r.removed_files, vec!["textkit/count.py", "textkit/unused.py"]);
    let removed: Vec<(&str, &str)> = r.removed_symbols.iter().map(|(f, n)| (f.as_str(), n.as_str())).collect();
    for x in [("textkit/case.py", "whisper"), ("textkit/case.py", "Loud"), ("textkit/__init__.py", "__version__"), ("textkit/__init__.py", "__all__")] {
        assert!(removed.contains(&x), "{x:?} not in {removed:?}");
    }
    for kept in ["def shout", "def _squash", "_SPACES = re.compile", "import re"] {
        assert!(r.files["textkit/case.py"].contains(kept), "{kept} lost:\n{}", r.files["textkit/case.py"]);
    }
    assert!(!r.files["textkit/case.py"].contains("__main__"), "the main guard never runs in a module");
    assert!(r.files["textkit/__init__.py"].contains("from .case import shout\n"), "{}", r.files["textkit/__init__.py"]);
    assert!(!r.files["textkit/__init__.py"].contains("count"), "{}", r.files["textkit/__init__.py"]);
    // Line numbers stay where they were, for tracebacks.
    for f in ["textkit/__init__.py", "textkit/case.py"] {
        let original = plugin.iter().find(|(p, _)| *p == f).unwrap().1.lines().count();
        assert_eq!(r.files[f].lines().count(), original, "{f}");
    }
}

#[test]
fn every_entry_point_keeps_what_it_reaches() {
    // A module attribute chain, a string lookup, a call at import time and a decorator from the package.
    let main = r#"from wm import ui
import textkit
import textkit.count


def render():
    with ui.column():
        ui.title(textkit.whisper("A  B"))
        ui.label(str(textkit.count.words("a b c")))
"#;
    let plugin = textkit(main);
    let r = shaken(&borrow(&plugin), &["textkit"], true);
    assert_eq!(render(&files(&borrow(&plugin))), render(&r.files));
    let case = &r.files["textkit/case.py"];
    assert!(case.contains("def whisper") && !case.contains("def shout") && !case.contains("class Loud"), "{case}");
    assert!(r.files["textkit/count.py"].contains("def words") && !r.files["textkit/count.py"].contains("def chars"));
}

#[test]
fn a_module_used_as_a_value_keeps_everything() {
    let main = r#"from wm import ui
from textkit import case


def render():
    pick = getattr(case, "shout")
    with ui.column():
        ui.title(pick("x"))
"#;
    let plugin = textkit(main);
    let r = shaken(&borrow(&plugin), &["textkit"], true);
    assert_eq!(r.files["textkit/case.py"], CASE);
    assert!(r.notes.iter().any(|n| n.contains("textkit/case.py keeps everything") && n.contains("main.py:6")), "{:?}", r.notes);
    assert_eq!(render(&files(&borrow(&plugin))), render(&r.files));
}

#[test]
fn statements_that_do_something_stay_with_what_they_use() {
    let lib = r#"REGISTRY = []


def register(f):
    REGISTRY.append(f.__name__)
    return f


@register
def plugin_a():
    return 1


def helper():
    return 2


def unused():
    return 3


TOTAL = helper()
COUNT = len(REGISTRY)
"#;
    let main = r#"from wm import ui
import reg


def render():
    with ui.column():
        ui.title(str(reg.COUNT) + str(reg.TOTAL))
"#;
    let r = shaken(&[("main.py", main), ("reg.py", lib)], &["reg"], true);
    let out = &r.files["reg.py"];
    for kept in ["REGISTRY = []", "def register", "@register", "def plugin_a", "def helper", "TOTAL = helper()", "COUNT = len(REGISTRY)"] {
        assert!(out.contains(kept), "{kept} lost:\n{out}");
    }
    assert!(!out.contains("def unused"), "{out}");
    assert_eq!(render(&files(&[("main.py", main), ("reg.py", lib)])), render(&r.files));
}

/// Whether this build's interpreter runs [source] without an error.
fn interpreter_runs(source: &'static str) -> bool {
    on_big_stack(move || wm_python::interp::Runtime::default().load(source).is_ok())
}

#[test]
fn modules_that_reach_their_own_namespace_keep_everything() {
    let lib = "def a():\n    return 1\n\n\ndef b():\n    return globals()['a']()\n";
    let main = "import dyn\n\ndef render():\n    return None\n";
    let r = shaken(&[("main.py", main), ("dyn.py", lib)], &["dyn"], true);
    // Only an interpreter that has globals() can look a name up by string.
    if interpreter_runs("g = globals()\n") {
        assert_eq!(r.files["dyn.py"], lib);
        assert!(r.notes.iter().any(|n| n.contains("dyn.py keeps everything") && n.contains("globals()")), "{:?}", r.notes);
    } else {
        assert!(!r.notes.iter().any(|n| n.contains("keeps everything")), "{:?}", r.notes);
        assert!(!r.files["dyn.py"].contains("def a"), "{}", r.files["dyn.py"]);
    }
}

#[test]
fn a_lazy_import_through_a_global_is_followed() {
    let core = r#"easter = None


def when(year):
    global easter
    if easter is None:
        from pkg import easter
    return easter.compute(year)


def unused():
    return 1
"#;
    let easter = "def compute(y):\n    return y + 1\n\n\ndef other():\n    return 2\n";
    let main = "from wm import ui\nfrom pkg.core import when\n\n\ndef render():\n    with ui.column():\n        ui.title(str(when(2000)))\n";
    let list = [("main.py", main), ("pkg/__init__.py", ""), ("pkg/core.py", core), ("pkg/easter.py", easter)];
    let r = shaken(&list, &["pkg"], true);
    assert_eq!(render(&files(&list)), render(&r.files));
    assert!(r.files["pkg/easter.py"].contains("def compute") && !r.files["pkg/easter.py"].contains("def other"), "{}", r.files["pkg/easter.py"]);
    assert!(!r.files["pkg/core.py"].contains("def unused"));
    assert!(!r.notes.iter().any(|n| n.contains("keeps everything")), "{:?}", r.notes);
}

#[test]
fn a_package_nothing_imports_goes_whole() {
    let main = "def render():\n    return None\n";
    let r = shaken(&[("main.py", main), ("lonely/__init__.py", "X = 1\n"), ("lonely/more.py", "Y = 2\n"), ("helpers.py", "Z = 3\n")], &["lonely"], false);
    assert_eq!(r.removed_files, vec!["lonely/__init__.py", "lonely/more.py"]);
    assert!(r.files.contains_key("helpers.py"), "the plugin's own modules always stay");
    assert!(r.notes.iter().any(|n| n.contains("'lonely' is not imported anywhere")), "{:?}", r.notes);
}

#[test]
fn relative_imports_and_submodules_from_a_package() {
    let main = "from wm import ui\nfrom pkg import sub\n\ndef render():\n    with ui.column():\n        ui.title(sub.f())\n";
    let r = shaken(
        &[
            ("main.py", main),
            ("pkg/__init__.py", ""),
            ("pkg/sub.py", "from ..pkg import other\nfrom . import deep\n\ndef f():\n    return deep.g() + other.h()\n"),
            ("pkg/other.py", "def h():\n    return 'o'\n"),
            ("pkg/deep.py", "def g():\n    return 'd'\n"),
            ("pkg/spare.py", "def s():\n    return 's'\n"),
        ],
        &["pkg"],
        false,
    );
    assert_eq!(r.removed_files, vec!["pkg/spare.py"]);
}

#[test]
fn a_dynamic_import_keeps_the_whole_package() {
    let main = "import lazy\n\ndef render():\n    return None\n";
    let r = shaken(
        &[("main.py", main), ("lazy/__init__.py", "def load(name):\n    return __import__('lazy.' + name)\n"), ("lazy/part.py", "X = 1\n")],
        &["lazy"],
        true,
    );
    // Only an interpreter with __import__ or importlib can load a module by name.
    if interpreter_runs("m = __import__('math')\n") || interpreter_runs("import importlib\n") {
        assert!(r.removed_files.is_empty(), "{:?}", r.removed_files);
        assert!(r.notes.iter().any(|n| n.contains("imports modules by name")), "{:?}", r.notes);
    } else {
        assert_eq!(r.removed_files, vec!["lazy/part.py"]);
    }
}

#[test]
fn the_json_has_the_shape_the_web_editor_reads() {
    let plugin = textkit(MAIN);
    let j = shaken(&borrow(&plugin), &["textkit"], true).to_json();
    assert!(j["files"].is_object() && j["removedFiles"].is_array() && j["notes"].is_array());
    assert!(j["removedSymbols"][0].is_array());
    assert!(j["bytesBefore"].as_u64().unwrap() > j["bytesAfter"].as_u64().unwrap());
}

#[test]
fn a_package_keeps_its_data_module_while_it_is_used() {
    let data = ("words/_wmdata.py", "TEXT = {\"words.txt\": \"a b\"}\nDATA = {}\n");
    let init = ("words/__init__.py", "import os\n\ndef load():\n    with open(os.path.join(os.path.dirname(__file__), 'words.txt')) as f:\n        return f.read()\n");
    let used = shaken(&[("main.py", "import words\n\ndef render():\n    words.load()\n"), init, data], &["words"], false);
    assert!(used.files.contains_key("words/_wmdata.py"), "{:?}", used.removed_files);
    let unused = shaken(&[("main.py", "def render():\n    pass\n"), init, data], &["words"], false);
    assert!(unused.removed_files.contains(&"words/_wmdata.py".to_string()), "{:?}", unused.removed_files);
}
