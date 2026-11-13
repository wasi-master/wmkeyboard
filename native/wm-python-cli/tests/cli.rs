//! `wm-python-cli test` and `run` against plugin folders made on the fly.

use std::path::PathBuf;
use std::process::Command;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wm-python-cli-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (f, text) in files {
        std::fs::write(dir.join(f), text).unwrap();
    }
    dir
}

fn cli(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wm-python-cli")).args(args).output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

const MANIFEST: &str = r#"{"id": "com.example.cipher", "version": "2.0.0", "apiVersion": 4, "permissions": ["storage"]}"#;

const MAIN: &str = "shift = 3\n\
def transform(text, action=None):\n    return ''.join(chr(ord(c) + shift) for c in text)\n\
def render():\n    wm.storage.set('seen', 'yes')\n    return ui.column(ui.label('Cipher', style='title'), ui.output(transform('abc'), id='out'))\n";

#[test]
fn a_passing_test_exits_zero_and_prints() {
    let test = "import json\n\
m = plugin_test.load('main.py', wm, ui)\n\
assert_ok = m['transform']('abc') == 'def'\n\
if not assert_ok:\n    raise ValueError('transform broke')\n\
tree = m.render()\n\
if tree['children'][1]['text'] != 'def':\n    raise ValueError('render broke')\n\
if wm.storage.get('seen') != 'yes':\n    raise ValueError('storage broke')\n\
cases = plugin_test.read('cases.txt').splitlines()\n\
print('cases', len(cases))\n\
fake = {'storage': {'set': lambda k, v: None}}\n\
other = plugin_test.load('main.py', fake, ui)\n\
other['render']()\n";
    let dir = plugin("pass", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("test.py", test), ("cases.txt", "a\nb\nc")]);
    let (code, out, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!((code, out.as_str(), err.as_str()), (0, "cases 3\n", ""));
}

#[test]
fn an_uncaught_exception_fails_with_kind_message_and_line() {
    let dir = plugin("fail", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("test.py", "x = 1\nraise ValueError('case 7 gave 8')\n")]);
    let (code, _, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!(1, code);
    assert_eq!("ValueError: case 7 gave 8 (line 2)\n", err);
}

#[test]
fn files_outside_the_plugin_are_refused() {
    let dir = plugin("escape", &[("test.py", "plugin_test.read('../secret.txt')\n")]);
    let (code, _, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!(1, code);
    assert!(err.contains("not a file inside the plugin folder"), "{err}");
    let dir = plugin("absolute", &[("test.py", "plugin_test.read('/etc/hosts')\n")]);
    assert_eq!(1, cli(&["test", dir.to_str().unwrap()]).0);
}

#[test]
fn run_prints_the_first_render() {
    let dir = plugin("run", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, _) = cli(&["run", dir.to_str().unwrap()]);
    assert_eq!(0, code);
    let v: serde_json::Value = serde_json::from_str(out.trim()).unwrap();
    assert_eq!(v["ok"], true);
    assert_eq!(v["ui"]["root"][0]["children"][1]["text"], "def");
}

#[test]
fn bad_usage_exits_two() {
    assert_eq!(2, cli(&["nope"]).0);
    assert_eq!(2, cli(&["frobnicate", "."]).0);
}

const API5_MANIFEST: &str = r#"{"id": "com.example.shop", "version": "1.0.0", "apiVersion": 5, "permissions": ["network", "storage"]}"#;

const API5_MAIN: &str = r#"from wm import plugin, ui, http, secrets

state = plugin.state(stock="", added="", token=secrets.get("key", ""))


def got_stock(r):
    state.stock = str(len(r.json()))
    http.post("https://shop.example.com/api/add", json={"amount": 2}, headers={"K": state.token}, then=added)


def added(r):
    state.added = r.json()["ok"]


def failed(e):
    state.added = "failed: " + str(e)


def load():
    http.get("https://shop.example.com/api/stock", then=got_stock, on_error=failed)


def render():
    ui.button("Load", on_click=load)
    ui.label("stock " + state.stock)
    ui.label("added " + str(state.added))
"#;

#[test]
fn routed_mocks_settle_then_calls_and_log_them() {
    let test = r#"import json
mock = {
    "secrets.get": [{"when": {"name": "key"}, "reply": "s3cret"}],
    "http.request": [
        {"when": {"method": "GET", "url": "https://shop.example.com/api/stock"}, "reply": {"status": 200, "text": "[1, 2, 3]"}},
        {"when": {"method": "POST", "url": "https://shop.example.com/api/*"}, "reply": {"status": 200, "text": "{\"ok\": \"yes\"}"}},
    ],
}
s = plugin_test.session(mock=mock)
assert s["load"]()["ok"]
s["event"]({"type": "click", "id": "button:Load"})
r = s["settle"]()
text = json.dumps(r["ui"])
assert "stock 3" in text, text
assert "added yes" in text, text
calls = [c for c in s["calls"]() if c["op"] == "http.request"]
assert len(calls) == 2, calls
assert json.loads(calls[1]["args"]["body"]) == {"amount": 2}, calls[1]
assert calls[1]["args"]["headers"]["K"] == "s3cret", calls[1]

bad = plugin_test.session(mock={"secrets.get": "k", "http.request": [{"when": {"url": "https://other.example.com/*"}, "reply": {"status": 200}}]})
bad["load"]()
bad["event"]({"type": "click", "id": "button:Load"})
text = json.dumps(bad["settle"]()["ui"])
assert "no mock route for http.request GET https://shop.example.com/api/stock" in text, text

# A callback that fails along the way is what settle() gives back.
s = plugin_test.session(mock={"secrets.get": "k", "http.request": [
    {"when": {"method": "GET"}, "reply": {"status": 200, "text": "not json"}},
]})
s["load"]()
s["event"]({"type": "click", "id": "button:Load"})
r = s["settle"]()
assert not r["ok"] and "JSON" in json.dumps(r["error"]), r
print("routed ok")
"#;
    let dir = plugin("routes", &[("plugin.json", API5_MANIFEST), ("main.py", API5_MAIN), ("test.py", test)]);
    let (code, out, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!((code, out.as_str(), err.as_str()), (0, "routed ok\n", ""));
}

#[test]
fn an_api_5_test_imports_the_plugins_own_modules() {
    let manifest = r#"{"id": "com.example.fares", "pluginVersion": "1.0.0", "apiVersion": 5}"#;
    let main = "from wm import ui\nimport fares\ndef render():\n    ui.label(str(fares.total([2, 3])))\n";
    let fares = "def total(xs):\n    return sum(xs)\n";
    let test = "import fares\nassert fares.total([2.5, 1]) == 3.5\nprint('ok')\n";
    let dir = plugin("modules", &[("plugin.json", manifest), ("main.py", main), ("fares.py", fares), ("test.py", test)]);
    let (code, out, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!((code, out.as_str(), err.as_str()), (0, "ok\n", ""));
}

#[test]
fn an_api_5_test_imports_the_plugins_own_modules() {
    let manifest = r#"{"id": "com.example.fares", "pluginVersion": "1.0.0", "apiVersion": 5}"#;
    let main = "from wm import ui\nimport fares\ndef render():\n    ui.label(str(fares.total([2, 3])))\n";
    let fares = "def total(xs):\n    return sum(xs)\n";
    let test = "import fares\nassert fares.total([2.5, 1]) == 3.5\nprint('ok')\n";
    let dir = plugin("modules", &[("plugin.json", manifest), ("main.py", main), ("fares.py", fares), ("test.py", test)]);
    let (code, out, err) = cli(&["test", dir.to_str().unwrap()]);
    assert_eq!((code, out.as_str(), err.as_str()), (0, "ok\n", ""));
}
