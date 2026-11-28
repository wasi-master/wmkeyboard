//! `wmpy check --fix` against plugin folders made on the fly.

use std::path::{Path, PathBuf};
use std::process::Command;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-static-fix-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn cli(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(args).output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

fn json(text: &str) -> serde_json::Value {
    serde_json::from_str(text.trim()).unwrap_or_else(|e| panic!("not JSON ({e}): {text}"))
}

fn read(dir: &Path, f: &str) -> String {
    std::fs::read_to_string(dir.join(f)).unwrap()
}

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

const MANIFEST: &str = "{\n    \"format\": \"wmkeyboard-plugin\",\n    \"id\": \"com.example.fixme\",\n    \"name\": \"Fix me\",\n    \"pluginVersion\": \"1.0.0\",\n    \"apiVersion\": 5,\n    \"permissions\": [\"selection\"],\n    \"actions\": [{\"id\": \"up\", \"name\": \"Up\"}]\n}\n";

const MAIN: &str = "from wm import plugin, ui\n\nstate = plugin.state(count=0, persist=True)\n\n\ndef render():\n    ui.lable(str(math.floor(state.count)))\n    ui.label(clipboard.get())\n    http.get(\"https://api.example.com/x\")\n\n\n@plugin.action(\"up\")\ndef up(text):\n    return text.upper()\n";

#[test]
fn fix_makes_the_safe_fixes_and_leaves_risky_ones() {
    let dir = plugin("safe", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, err) = cli(&["check", s(&dir), "--fix"]);
    // The risky fixes are left, and a missing permission or host is an error.
    assert_eq!(code, 1, "{out}{err}");
    let main = read(&dir, "main.py");
    // Two names for one `from wm import` line take two rounds.
    assert!(main.starts_with("from wm import plugin, ui, clipboard, http\nimport math\n"), "{main}");
    assert!(main.contains("ui.label(str(math.floor(state.count)))"), "{main}");
    let manifest = read(&dir, "plugin.json");
    // Added in the file's own layout; the rest is as it was.
    assert_eq!(manifest, MANIFEST.replace("[\"selection\"]", "[\"selection\", \"storage\"]"));
    assert!(err.contains("fixed main.py: 4 changes") && err.contains("fixed plugin.json: 1 change"), "{err}");
    assert!(err.contains("add “clipboard” to the import [wm_not_imported]"), "{err}");
    assert!(err.contains("made 5 fixes in 2 files"), "{err}");
    assert!(err.contains("not fixed: add the “network” permission") && err.contains("--fix-risky"), "{err}");
    assert!(err.contains("not fixed: add “api.example.com” to plugin.json's hosts"), "{err}");
    // What is left is checked again.
    assert!(err.contains("error[permission_missing]") && err.contains("checked plugin.json and 1 Python file: 3 errors, 0 warnings"), "{err}");
    // Nothing more to do.
    let (_, _, err) = cli(&["check", s(&dir), "--fix"]);
    assert!(err.contains("nothing to fix"), "{err}");

    let (code, out, err) = cli(&["check", s(&dir), "--fix", "--fix-risky", "--json"]);
    let v = json(&out);
    assert_eq!(code, 0, "{v:#}\n{err}");
    let manifest: serde_json::Value = json(&read(&dir, "plugin.json"));
    assert_eq!(manifest["permissions"], serde_json::json!(["selection", "storage", "network", "clipboard.read"]));
    assert_eq!(manifest["hosts"], serde_json::json!(["api.example.com"]));
    assert_eq!(v["warnings"], 0, "{v:#}");
    assert_eq!(v["written"], true);
    assert!(v["fixed"].as_array().unwrap().iter().all(|f| f["file"] == "plugin.json"), "{v:#}");
}

#[test]
fn dry_run_prints_diffs_and_writes_nothing() {
    let dir = plugin("dry", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, err) = cli(&["check", s(&dir), "--fix", "--dry-run"]);
    // What the risky fixes would mend is still an error.
    assert_eq!(code, 1, "{err}");
    assert_eq!(read(&dir, "main.py"), MAIN);
    assert_eq!(read(&dir, "plugin.json"), MANIFEST);
    assert!(out.starts_with("--- a/main.py\n+++ b/main.py\n@@ -1,10 +1,11 @@\n-from wm import plugin, ui\n+from wm import plugin, ui, clipboard, http\n+import math\n"), "{out}");
    assert!(out.contains("--- a/plugin.json\n+++ b/plugin.json\n") && out.contains("+    \"permissions\": [\"selection\", \"storage\"],\n"), "{out}");
    assert!(!out.contains("warning["), "the diffs only on stdout: {out}");
    assert!(err.contains("would make 5 fixes in 2 files"), "{err}");

    let (_, out, _) = cli(&["check", s(&dir), "--fix", "--dry-run", "--json"]);
    let v = json(&out);
    assert_eq!(v["written"], false);
    let files: Vec<&str> = v["diffs"].as_array().unwrap().iter().map(|d| d["file"].as_str().unwrap()).collect();
    assert_eq!(files, ["main.py", "plugin.json"]);
    assert_eq!(v["left"].as_array().unwrap().len(), 3, "{v:#}");
    assert_eq!(read(&dir, "main.py"), MAIN);
}

#[test]
fn manifest_rules_and_stubs_are_fixed_across_modules() {
    // An action the code answers and plugin.json lacks: the action, then the
    // selection permission it needs. A provider plugin.json declares and no
    // module answers: a stub in the entry script.
    let manifest = "{\"format\": \"wmkeyboard-plugin\", \"id\": \"com.example.m\", \"name\": \"M\", \"pluginVersion\": \"1\", \"apiVersion\": 5,\n \"providers\": [{\"tool\": \"search\"}]}\n";
    let main = "from wm import plugin, ui\nimport helpers\n\n\ndef render():\n    ui.label(helpers.title())\n";
    let helpers = "from wm import plugin\n\n\ndef title():\n    return 'x'\n\n\n@plugin.action(\"shout\")\ndef shout(text):\n    return text.upper()\n";
    let dir = plugin("modules", &[("plugin.json", manifest), ("main.py", main), ("helpers.py", helpers)]);
    let (code, out, err) = cli(&["check", s(&dir), "--fix", "--json"]);
    let v = json(&out);
    assert_eq!(code, 0, "{v:#}\n{err}");
    assert_eq!(v["errors"], 0, "{v:#}");
    let m: serde_json::Value = json(&read(&dir, "plugin.json"));
    assert_eq!(m["actions"], serde_json::json!([{"id": "shout", "name": "Shout"}]));
    assert_eq!(m["permissions"], serde_json::json!(["selection"]));
    let main_after = read(&dir, "main.py");
    assert!(main_after.contains("@plugin.provider(\"search\")\ndef search(request):"), "{main_after}");
    assert_eq!(read(&dir, "helpers.py"), helpers);
}

#[test]
fn an_existing_keyword_is_not_replaced() {
    let manifest = "{\"format\": \"wmkeyboard-plugin\", \"id\": \"com.example.k\", \"name\": \"K\", \"pluginVersion\": \"1\", \"apiVersion\": 5,\n \"tool\": {\"keyword\": \"look\", \"chip\": true}}\n";
    let main = "from wm import plugin, ui\n\n\ndef render():\n    ui.label('x')\n\n\n@plugin.keyword(\"define\")\ndef define(word):\n    pass\n";
    let dir = plugin("keyword", &[("plugin.json", manifest), ("main.py", main)]);
    let (_, out, _) = cli(&["check", s(&dir), "--fix", "--json"]);
    let v = json(&out);
    assert_eq!(read(&dir, "plugin.json"), manifest);
    let left = v["left"].as_array().unwrap();
    assert!(left.iter().any(|l| l["code"] == "keyword_not_declared" && l["why"].as_str().unwrap().contains("“look”")), "{v:#}");
}
