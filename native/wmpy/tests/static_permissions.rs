//! `wmpy permissions` against plugin folders made on the fly.

use std::path::{Path, PathBuf};
use std::process::Command;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-static-perm-{}-{name}", std::process::id()));
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

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.perm",
  "name": "Perm",
  "pluginVersion": "1",
  "apiVersion": 5,
  "permissions": ["network", "storage", "location"],
  "hosts": ["api.example.com", "*.example.org", "old.example.net"]
}
"#;

const MAIN: &str = "from wm import ui, http, location\nimport weather\n\n\ndef render():\n    ui.label(weather.today())\n    ui.label(clipboard_text())\n";

const WEATHER: &str = "from wm import http, clipboard\n\nBASE = \"https://api.example.com/v1\"\n\n\ndef today():\n    return http.get(BASE + \"/today\").text\n\n\ndef tiles():\n    return http.get(f\"https://tiles.example.org/{1}\").text\n\n\ndef clip():\n    return clipboard.get()\n\n\ndef far():\n    return http.post(\"https://elsewhere.io/x\")\n";

#[test]
fn reports_each_permission_and_host_across_modules() {
    let dir = plugin("modules", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("weather.py", WEATHER), ("test.py", "import json\n")]);
    let (code, out, err) = cli(&["permissions", s(&dir), "--json"]);
    let v = json(&out);
    assert_eq!(code, 1, "clipboard.read and elsewhere.io are missing: {v:#}\n{err}");
    let perm = |name: &str| v["permissions"].as_array().unwrap().iter().find(|p| p["name"] == name).cloned().unwrap_or_else(|| panic!("no {name}: {v:#}"));
    assert_eq!(perm("network")["verdict"], "needed");
    assert_eq!(perm("network")["tier"], "risky");
    assert_eq!(perm("network")["declaredLine"], 7);
    assert_eq!(perm("storage")["verdict"], "unused");
    assert_eq!(perm("location")["verdict"], "imported_only");
    assert_eq!(perm("clipboard.read")["verdict"], "missing");
    assert_eq!(perm("clipboard.read")["uses"][0], serde_json::json!({"kind": "code", "what": "clipboard.get", "file": "weather.py", "line": 15}));
    let calls: Vec<(String, u64)> = perm("network")["uses"].as_array().unwrap().iter().map(|u| (u["file"].as_str().unwrap().to_string(), u["line"].as_u64().unwrap())).collect();
    assert!(calls.contains(&("weather.py".into(), 7)) && calls.contains(&("main.py".into(), 1)), "{calls:?}");

    let host = |entry: &str| v["hosts"]["declared"].as_array().unwrap().iter().find(|h| h["entry"] == entry).cloned().unwrap();
    assert_eq!(host("api.example.com")["verdict"], "used");
    assert_eq!(host("api.example.com")["uses"][0]["line"], 3, "the constant's line");
    assert_eq!(host("*.example.org")["verdict"], "used");
    assert_eq!(host("old.example.net")["verdict"], "unused");
    assert_eq!(v["hosts"]["missing"][0]["host"], "elsewhere.io");
    assert_eq!(v["errors"], 2);
    assert_eq!(v["suggestions"], 3, "storage, location and old.example.net");

    // The table.
    let (code, out, _) = cli(&["permissions", s(&dir)]);
    assert_eq!(code, 1);
    assert!(out.contains("weather.py:15") && out.contains("clipboard.get"), "{out}");
    assert!(out.contains("add \"elsewhere.io\" to plugin.json's hosts"), "{out}");
    assert!(out.ends_with("2 entries missing; 3 plugin.json could drop\n"), "{out}");
}

#[test]
fn a_tidy_plugin_passes_and_strict_fails_on_leftovers() {
    let manifest = MANIFEST.replace("\"network\", \"storage\", \"location\"", "\"network\"").replace(", \"*.example.org\", \"old.example.net\"", "");
    let main = "from wm import ui, http\n\n\ndef render():\n    ui.label(http.get(\"https://api.example.com/x\").text)\n";
    let dir = plugin("tidy", &[("plugin.json", manifest.as_str()), ("main.py", main)]);
    let (code, out, _) = cli(&["permissions", s(&dir)]);
    assert_eq!(code, 0, "{out}");
    assert!(out.ends_with("plugin.json asks for what the code needs, and no more\n"), "{out}");
    assert_eq!(cli(&["permissions", s(&dir), "--strict"]).0, 0);

    let dir = plugin("strict", &[("plugin.json", MANIFEST), ("main.py", main)]);
    assert_eq!(cli(&["permissions", s(&dir)]).0, 0, "unused is a suggestion");
    assert_eq!(cli(&["permissions", s(&dir), "--strict"]).0, 1);
}

#[test]
fn a_folder_without_plugin_json_is_refused() {
    let dir = plugin("none", &[("main.py", "x = 1\n")]);
    let (code, _, err) = cli(&["permissions", s(&dir)]);
    assert_eq!(code, 2);
    assert!(err.contains("has no plugin.json"), "{err}");
}
