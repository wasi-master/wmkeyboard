//! `wmpy budget` on a plugin with a deliberate runaway loop: the default
//! clicks, a scenario, the tests, the device speed and the exit codes.

use std::path::PathBuf;
use std::process::Command;

fn folder(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-runtime-budget-{}-{name}", std::process::id()));
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

const MANIFEST: &str = r#"{"id": "com.example.spin", "name": "Spin", "version": "1.0.0", "apiVersion": 5, "permissions": ["storage"]}"#;
const MAIN: &str = "from wm import plugin, ui
state = plugin.state(n=0, persist=True)

def quick():
    state.n += 1

def spin():
    while True:
        pass

def render():
    ui.label('n ' + str(state.n))
    ui.button('Quick', on_click=quick)
    ui.button('Spin', on_click=spin)
";
const TEST: &str = "def test_quick():
    s = plugin_test.session()
    assert s['load']()['ok']
    r = s['event']({'type': 'click', 'id': 'button:Quick'})
    assert 'n 1' in str(r['ui']), r
";

fn json(text: &str) -> serde_json::Value {
    serde_json::from_str(text).unwrap_or_else(|e| panic!("not JSON: {e}\n{text}"))
}

#[test]
fn the_default_clicks_find_the_runaway_button_and_fail() {
    let dir = folder("clicks", &[("plugin.json", MANIFEST), ("main.py", MAIN), (".wmpy/storage.json", "{}")]);
    let before = std::fs::read_to_string(dir.join(".wmpy/storage.json")).unwrap();
    let (code, out, err) = wmpy(&["budget", dir.to_str().unwrap(), "--json"]);
    assert_eq!(code, 1, "{out}\n{err}");
    let v = json(&out);
    assert_eq!(v["over"], true);
    assert_eq!(v["deviceSpeedFrom"], "default");
    assert!(v["drivenBy"].as_str().unwrap().contains("2 buttons"), "{v:#}");
    let event = &v["phases"]["event"];
    assert_eq!(event["runs"], 2, "{v:#}");
    assert_eq!(event["level"], "over");
    assert_eq!(event["worst"]["widget"], "button:Spin", "{v:#}");
    assert_eq!(v["phases"]["load"]["level"], "ok", "{v:#}");
    assert_eq!(v["phases"]["load"]["stepsLimit"], 9_000_000);
    assert!(v["calls"].as_array().unwrap().iter().any(|c| c["widget"] == "button:Spin" && c["abort"].is_string()), "{v:#}");
    // The run used a copy of the state: the plugin's own is as it was.
    assert_eq!(std::fs::read_to_string(dir.join(".wmpy/storage.json")).unwrap(), before);
    let (code, text, _) = wmpy(&["budget", dir.to_str().unwrap()]);
    assert_eq!(code, 1);
    assert!(text.contains("worst event: click button:Spin"), "{text}");
    assert!(text.contains("over: the keyboard stopped click button:Spin"), "{text}");
    assert!(text.contains("--device-speed 1.0, the default"), "{text}");
}

#[test]
fn a_scenario_measures_only_its_events_and_the_device_speed_scales_time() {
    let dir = folder("scenario", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("quick.json", r#"[{"type": "click", "id": "button:Quick"}, {"type": "click", "id": "button:Quick"}]"#)]);
    let scenario = dir.join("quick.json");
    let (code, out, err) = wmpy(&["budget", dir.to_str().unwrap(), "--scenario", scenario.to_str().unwrap(), "--json"]);
    assert_eq!(code, 0, "{out}\n{err}");
    let v = json(&out);
    assert_eq!(v["phases"]["event"]["runs"], 2);
    assert_eq!(v["phases"]["render"]["runs"], 3, "the first draw and one after each click: {v:#}");
    assert_eq!(v["phases"]["event"]["level"], "ok");
    // A phone a million times slower than this computer runs out of time.
    let (code, out, _) = wmpy(&["budget", dir.to_str().unwrap(), "--scenario", scenario.to_str().unwrap(), "--device-speed", "0.000001", "--json"]);
    assert_eq!(code, 1);
    let v = json(&out);
    assert_eq!((v["deviceSpeedFrom"].as_str(), v["over"].as_bool()), (Some("flag"), Some(true)), "{v:#}");
    let (code, _, err) = wmpy(&["budget", dir.to_str().unwrap(), "--device-speed", "0"]);
    assert_eq!(code, 2, "{err}");
}

#[test]
fn the_tests_are_measured_too() {
    let dir = folder("tests", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("test.py", TEST)]);
    let (code, out, err) = wmpy(&["budget", dir.to_str().unwrap(), "--tests", "--json"]);
    assert_eq!(code, 0, "{out}\n{err}");
    let v = json(&out);
    assert_eq!(v["tests"], serde_json::json!({"passed": 1, "failed": 0}));
    assert_eq!(v["phases"]["event"]["runs"], 1);
    let label = v["calls"][1]["label"].as_str().unwrap();
    assert_eq!(label, "test.py::test_quick: event click button:Quick", "{v:#}");
}
