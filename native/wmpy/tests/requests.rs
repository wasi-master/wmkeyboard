//! A plugin written the way Python users write one: `import requests`.
//! It checks, tests and renders with a mock, and `wmpy pip install
//! requests` knows there is nothing to install.

use std::path::{Path, PathBuf};
use std::process::Command;

fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-requests-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn cli(args: &[&str]) -> (i32, String, String) {
    let out = Command::new(env!("CARGO_BIN_EXE_wmpy")).args(args).env("NO_COLOR", "1").output().unwrap();
    (out.status.code().unwrap_or(-1), String::from_utf8_lossy(&out.stdout).into(), String::from_utf8_lossy(&out.stderr).into())
}

fn s(p: &Path) -> &str {
    p.to_str().unwrap()
}

const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.stars",
  "name": "Stars",
  "pluginVersion": "1.0.0",
  "author": "A. Person",
  "apiVersion": 5,
  "permissions": ["network"],
  "hosts": ["api.github.com"]
}
"#;

/// What a beginner writes from the requests quickstart.
const MAIN: &str = r#"import requests
from wm import plugin, ui

REPO = "https://api.github.com/repos/psf/requests"

state = plugin.state(stars=None, error="")


def load():
    try:
        r = requests.get(REPO, headers={"Accept": "application/vnd.github+json"}, timeout=10)
        r.raise_for_status()
        state.stars = r.json()["stargazers_count"]
    except requests.exceptions.HTTPError as e:
        state.error = f"GitHub said {e.response.status_code}"
    except requests.RequestException as e:
        state.error = str(e)


load()


def render():
    if state.error:
        ui.label(state.error)
    else:
        ui.label(f"requests has {state.stars} stars")
"#;

const TEST: &str = r#"import json

MOCK = {
    "http.request": [
        {"when": {"url": "https://api.github.com/repos/psf/requests"},
         "reply": {"status": 200, "headers": {"Content-Type": "application/json; charset=utf-8"}, "text": json.dumps({"stargazers_count": 52000})}},
    ],
}


def test_it_shows_the_stars():
    s = plugin_test.session(mock=MOCK)
    r = s["load"]()
    assert r["ok"], r["error"]
    assert "requests has 52000 stars" in json.dumps(r["ui"]), r["ui"]
    args = s["calls"]()[0]["args"]
    assert args["headers"]["Accept"] == "application/vnd.github+json", args


def test_a_missing_repository_is_said():
    s = plugin_test.session(mock={"http.request": {"status": 404, "reason": "Not Found", "text": "{}", "headers": {}}})
    r = s["load"]()
    assert "GitHub said 404" in json.dumps(r["ui"]), r["ui"]


def test_no_network_is_said():
    s = plugin_test.session(mock={"http.request": {"error": {"kind": "ConnectionError", "message": "Unable to resolve host"}}})
    r = s["load"]()
    assert "could not reach api.github.com" in json.dumps(r["ui"]), r["ui"]
"#;

const MOCK: &str = r#"{"http.request": {"status": 200, "headers": {"Content-Type": "application/json"}, "text": "{\"stargazers_count\": 7}"}}"#;

#[test]
fn a_requests_plugin_checks_tests_and_renders() {
    let dir = plugin("plugin", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("test.py", TEST), ("mock.json", MOCK)]);
    let (code, out, err) = cli(&["check", s(&dir), "--json"]);
    assert_eq!(code, 0, "{out}{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["problems"].as_array().map(Vec::len), Some(0), "{v:#}");

    let (code, out, err) = cli(&["test", s(&dir), "--json"]);
    assert_eq!(code, 0, "{out}{err}");
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!((v["passed"].as_u64(), v["failed"].as_u64()), (Some(3), Some(0)), "{v:#}");

    let (code, out, err) = cli(&["render", s(&dir), "--mock", s(&dir.join("mock.json"))]);
    assert_eq!(code, 0, "{out}{err}");
    assert!(out.contains("requests has 7 stars"), "{out}");

    // A host plugin.json does not list is marked where the address is written.
    std::fs::write(dir.join("plugin.json"), MANIFEST.replace("api.github.com", "api.example.com")).unwrap();
    let (_, out, _) = cli(&["check", s(&dir), "--json"]);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert!(v["problems"].as_array().unwrap().iter().any(|p| p["code"] == "host_missing"), "{v:#}");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn pip_install_requests_says_it_is_built_in() {
    let dir = plugin("pip", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, out, err) = cli(&["pip", "install", "requests", "--dir", s(&dir)]);
    assert_eq!(code, 0, "{out}{err}");
    assert!(out.contains("requests is built into WM Keyboard's plugins"), "{out}{err}");
    assert!(!err.contains("looking up") && !err.contains("downloading"), "{err}");
    assert!(!dir.join("wmpy.lock").exists() && !dir.join("requests").exists());
    let (code, out, _) = cli(&["pip", "install", "HTTPX", "--dir", s(&dir), "--json"]);
    assert_eq!(code, 0);
    let v: serde_json::Value = serde_json::from_str(&out).unwrap();
    assert_eq!(v["packages"], serde_json::json!([]));
    assert!(v["notes"][0].as_str().unwrap().contains("httpx is built into"), "{v}");
    let (code, out, _) = cli(&["pip", "check", "requests"]);
    assert_eq!(code, 0);
    assert!(out.contains("built into"), "{out}");
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_stubs_include_requests_and_httpx() {
    let dir = plugin("stubs", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let (code, _, err) = cli(&["stubs", s(&dir)]);
    assert_eq!(code, 0, "{err}");
    let stubs = dir.join(".wmpy/stubs");
    let init = std::fs::read_to_string(stubs.join("requests/__init__.pyi")).unwrap();
    assert!(init.contains("def get(url, params=None, **kwargs) -> Response:"), "{init}");
    assert!(stubs.join("requests/exceptions.pyi").is_file() && stubs.join("httpx.pyi").is_file());
    let _ = std::fs::remove_dir_all(&dir);
}
