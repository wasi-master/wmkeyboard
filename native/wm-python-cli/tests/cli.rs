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
