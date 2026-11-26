//! `wmpy profile`: the slow function is on top, methods and nested
//! functions are counted, the tests run instrumented, and errors point at
//! the plugin's own lines.

use std::path::PathBuf;
use std::process::Command;

fn folder(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-runtime-profile-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn wmpy(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(args).env("NO_COLOR", "1").output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into_owned(), String::from_utf8_lossy(&out.stderr).into_owned())
}

const MANIFEST: &str = r#"{"id": "com.example.slow", "name": "Slow", "version": "1.0.0", "apiVersion": 5, "permissions": []}"#;
const MAIN: &str = "from wm import plugin, ui
import fares
state = plugin.state(n=0)

class Box:
    def size(self):
        return 3

def slow_sum(n):
    total = 0
    for i in range(n):
        total += i
    return total

def go():
    state.n = slow_sum(150000)

@plugin.action('double')
def double(text):
    return text + text

def render():
    ui.label(str(state.n) + ' ' + str(Box().size()) + ' ' + str(fares.total([1, 2])))
    ui.button('Go', on_click=go)
";
const FARES: &str = "def total(xs):
    def add(a, b):
        return a + b
    out = 0
    for x in xs:
        out = add(out, x)
    return out
";
const TEST: &str = "import fares

def test_fares():
    assert fares.total([2, 3]) == 5

def test_action():
    s = plugin_test.session()
    s['load']()
    assert s['transform']('ab', 'double')['value'] == 'abab'
";

#[test]
fn the_slow_function_and_the_rest_are_counted() {
    let dir = folder("clicks", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("fares.py", FARES)]);
    let (code, out, err) = wmpy(&["profile", dir.to_str().unwrap(), "--json"]);
    assert_eq!(code, 0, "{out}\n{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap_or_else(|e| panic!("{e}: {out}\n{err}"));
    let f = |name: &str| v["functions"].as_array().unwrap().iter().find(|f| f["name"] == name).cloned().unwrap_or_else(|| panic!("no {name}: {v:#}"));
    assert_eq!(f("slow_sum")["calls"], 1);
    assert_eq!(f("slow_sum")["line"], 9);
    assert_eq!(f("go")["calls"], 1);
    assert_eq!(f("Box.size")["calls"], 2, "the first draw and the one after the click");
    assert_eq!(f("total")["file"], "fares.py");
    assert_eq!(f("total.add")["calls"], 4);
    // The click's time is mostly slow_sum's, and nothing is slower than the click.
    assert!(f("slow_sum")["seconds"].as_f64().unwrap() > f("Box.size")["seconds"].as_f64().unwrap(), "{v:#}");
    let names: Vec<&str> = v["functions"].as_array().unwrap().iter().filter_map(|f| f["name"].as_str()).collect();
    assert!(names[..2].contains(&"slow_sum"), "{names:?}");
    let (code, text, _) = wmpy(&["profile", dir.to_str().unwrap(), "--top", "3"]);
    assert_eq!(code, 0);
    assert!(text.contains("slow_sum") && text.contains("main.py:9"), "{text}");
    assert!(text.contains("includes the profiler's own work"), "{text}");
    assert!(text.contains("per phase, not per function"), "{text}");
    assert!(text.contains("more (--top"), "{text}");
    // The plugin's own files are untouched.
    assert_eq!(std::fs::read_to_string(dir.join("main.py")).unwrap(), MAIN);
}

#[test]
fn tests_run_instrumented_and_count_direct_calls() {
    let dir = folder("tests", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("fares.py", FARES), ("test.py", TEST)]);
    let (code, out, err) = wmpy(&["profile", dir.to_str().unwrap(), "-k", "test", "--json"]);
    assert_eq!(code, 0, "{out}\n{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["failures"], serde_json::json!([]), "{v:#}");
    let f = |name: &str| v["functions"].as_array().unwrap().iter().find(|f| f["name"] == name).cloned().unwrap_or_else(|| panic!("no {name}: {v:#}"));
    assert_eq!(f("double")["calls"], 1, "the action, through its decorator");
    // test_fares calls fares.total in the test's own interpreter; the session's draw calls it too.
    assert_eq!(f("total")["calls"], 2, "{v:#}");
}

#[test]
fn errors_point_at_the_plugins_own_lines() {
    let main = "from wm import ui\nclass Box:\n    def boom(self):\n        raise ValueError('bad box')\ndef render():\n    Box().boom()\n";
    let dir = folder("errors", &[("plugin.json", MANIFEST), ("main.py", main)]);
    let (code, out, err) = wmpy(&["profile", dir.to_str().unwrap()]);
    // The draw failed after a good load: the panel still takes events, so the run goes on.
    assert_eq!(code, 0, "{out}\n{err}");
    assert!(out.contains("in load:\nerror[ValueError]: bad box"), "{out}");
    // Three lines were added to the copy (no free line above them); the error is on the plugin's line 4.
    assert!(out.contains("--> main.py:4") && out.contains("raise ValueError('bad box')"), "{out}");
    assert!(out.contains("Box.boom  main.py:3"), "{out}");
}
