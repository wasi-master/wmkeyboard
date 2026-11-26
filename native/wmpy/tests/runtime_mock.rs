//! `wmpy mock record`: replies (simulated here by a scenario's mock, as no
//! test may reach the network) written as routes, secrets redacted, and the
//! file replaying in `wmpy test`.

use std::path::PathBuf;
use std::process::Command;

fn folder(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-runtime-mock-{}-{name}", std::process::id()));
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

const MANIFEST: &str = r#"{"id": "com.example.weather", "name": "Weather", "version": "1.0.0", "apiVersion": 5,
  "permissions": ["network", "storage", "location"], "hosts": ["api.example.com"],
  "settings": [{"key": "api_key", "type": "secret", "label": "Key"}]}"#;
const MAIN: &str = "import json
from wm import plugin, ui, http, secrets, location
state = plugin.state(temp='', place='')

def fetch():
    key = secrets.get('api_key')
    r = http.get('https://api.example.com/v1/weather', params={'q': 'Dhaka', 'appid': key}, headers={'Authorization': 'Bearer ' + key})
    state.temp = str(json.loads(r.text)['temp'])
    location.get(then=lambda p: setattr(state, 'place', str(p['lat'])))
    ui.toast('fetched')

def render():
    ui.label('temp ' + state.temp)
    ui.label('place ' + state.place)
    ui.button('Fetch', on_click=fetch)
";
const SECRET: &str = "S3CRET-KEY-123";
/// What the service would answer: it echoes the key and sets a cookie.
const SCENARIO: &str = r#"{"events": [{"type": "click", "id": "button:Fetch"}], "mock": {"http.request": [{"when": {"url": "https://api.example.com/*"},
  "reply": {"status": 200, "reason": "OK", "url": "https://api.example.com/v1/weather?q=Dhaka&appid=S3CRET-KEY-123",
            "headers": {"Set-Cookie": "sid=abc", "Content-Type": "application/json", "X-Api-Key": "S3CRET-KEY-123"},
            "text": "{\"temp\": 21, \"token\": \"tok-999\", \"echo\": \"S3CRET-KEY-123\"}"}}]}}"#;
const TEST: &str = "import json

def test_replays():
    s = plugin_test.session(mock=json.loads(plugin_test.read('mocks/weather.json')))
    assert s['load']()['ok']
    s['event']({'type': 'click', 'id': 'button:Fetch'})
    r = s['settle']()
    assert 'temp 21' in json.dumps(r['ui']), r
    assert 'place 23.8103' in json.dumps(r['ui']), r
";

#[test]
fn a_recording_is_routes_without_secrets_and_replays() {
    let dir = folder("weather", &[
        ("plugin.json", MANIFEST),
        ("main.py", MAIN),
        ("scenario.json", SCENARIO),
        (".wmpy/secrets.json", &format!("{{\"api_key\": \"{SECRET}\"}}")),
        ("test.py", TEST),
    ]);
    let out = dir.join("mocks/weather.json");
    let (code, stdout, err) = wmpy(&["mock", "record", dir.to_str().unwrap(), "--out", out.to_str().unwrap(), "--scenario", dir.join("scenario.json").to_str().unwrap()]);
    assert_eq!(code, 0, "{stdout}\n{err}");
    assert!(err.contains("wrote") && err.contains("1 http route"), "{err}");
    assert!(err.contains("plugin_test.read(\"mocks/weather.json\")"), "{err}");
    let text = std::fs::read_to_string(&out).unwrap();
    assert!(!text.contains(SECRET), "{text}");
    assert!(!text.contains("tok-999") && !text.contains("sid=abc"), "{text}");
    let mock: serde_json::Value = serde_json::from_str(&text).unwrap();
    let route = &mock["http.request"][0];
    assert_eq!(route["when"], serde_json::json!({"method": "GET", "url": "https://api.example.com/v1/weather", "params": {"q": "Dhaka"}}), "{mock:#}");
    assert_eq!(route["reply"]["headers"]["Set-Cookie"], "<redacted>");
    assert_eq!(route["reply"]["headers"]["X-Api-Key"], "<redacted>");
    assert_eq!(route["reply"]["headers"]["Content-Type"], "application/json");
    assert_eq!(mock["secrets.get"], "<redacted>", "{mock:#}");
    assert_eq!(mock["location.get"]["lat"], 23.8103, "the pretend answer is kept so the file replays offline: {mock:#}");
    assert!(mock.get("ui.toast").is_some_and(|t| t.is_null()), "{mock:#}");
    // The secret stays where it was.
    assert!(std::fs::read_to_string(dir.join(".wmpy/secrets.json")).unwrap().contains(SECRET));
    let (code, _, err) = wmpy(&["test", dir.to_str().unwrap()]);
    assert_eq!(code, 0, "the recording replays: {err}");
}

#[test]
fn extra_names_are_redacted_and_bad_usage_exits_two() {
    let dir = folder("extra", &[("plugin.json", MANIFEST), ("main.py", MAIN), ("scenario.json", SCENARIO), (".wmpy/secrets.json", &format!("{{\"api_key\": \"{SECRET}\"}}"))]);
    let out = dir.join("m.json");
    let (code, _, err) = wmpy(&["mock", "record", dir.to_str().unwrap(), "-o", out.to_str().unwrap(), "--scenario", dir.join("scenario.json").to_str().unwrap(), "--redact", "Content-Type"]);
    assert_eq!(code, 0, "{err}");
    let mock: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&out).unwrap()).unwrap();
    assert_eq!(mock["http.request"][0]["reply"]["headers"]["Content-Type"], "<redacted>");
    let (code, _, _) = wmpy(&["mock", "record", dir.to_str().unwrap()]);
    assert_eq!(code, 2, "--out is needed");
    let (code, _, err) = wmpy(&["mock", "record", dir.to_str().unwrap(), "-o", out.to_str().unwrap(), "--scenario", "/no/such/file.json"]);
    assert_eq!(code, 2, "{err}");
}
