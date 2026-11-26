//! The development host against plugin folders made on the fly.

use std::path::PathBuf;
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};

use super::*;

pub fn plugin(name: &str, files: &[(&str, &str)]) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("wmpy-devhost-{}-{name}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    for (f, text) in files {
        let path = dir.join(f);
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }
    dir
}

fn open(dir: &Path) -> DevHost {
    DevHost::open(Options { dir: dir.to_path_buf(), ..Options::default() }).unwrap()
}

/// Tick until [done] holds, or give up after two seconds.
fn settle(h: &mut DevHost, done: impl Fn(&DevHost) -> bool) {
    let until = Instant::now() + Duration::from_secs(5);
    while !done(h) && Instant::now() < until {
        std::thread::sleep(h.next_wake().min(Duration::from_millis(20)));
        h.tick();
    }
}

fn texts(ui: &Json) -> Vec<String> {
    let mut out = Vec::new();
    fn walk(v: &Json, out: &mut Vec<String>) {
        match v {
            Json::Array(a) => a.iter().for_each(|x| walk(x, out)),
            Json::Object(o) => {
                for k in ["text", "title", "label"] {
                    if let Some(t) = o.get(k).and_then(Json::as_str) {
                        out.push(t.to_string());
                    }
                }
                for (k, x) in o {
                    if matches!(k.as_str(), "children" | "items" | "pages") {
                        walk(x, out);
                    }
                }
            }
            _ => {}
        }
    }
    walk(&ui["root"], &mut out);
    out
}

const MANIFEST: &str = r#"{"id": "com.example.counter", "name": "Counter", "version": "1.0.0", "apiVersion": 5,
  "permissions": ["storage", "field.write", "location", "network"], "hosts": ["api.example.com"],
  "settings": [{"key": "step", "type": "number", "default": "1"}, {"key": "unit", "type": "choice", "default": "m", "options": ["m", "ft"]}]}"#;

const MAIN: &str = r#"from wm import plugin, ui, field, location
state = plugin.state(count=0, persist=True, place="")

def add():
    state.count += plugin.settings.step
    field.insert("+")

def where():
    location.get(then=lambda p: setattr(state, "place", str(p["lat"])))

def render():
    ui.label("count " + str(state.count))
    ui.button("Add", on_click=add)
    ui.button("Where", on_click=where)
    ui.label("place " + state.place)
    ui.label("unit " + plugin.settings.unit)
"#;

#[test]
fn a_panel_loads_draws_and_takes_events_with_storage_on_disk() {
    let dir = plugin("counter", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let mut h = open(&dir);
    assert_eq!(h.status, Status::Ready, "{:?}", h.error);
    assert!(texts(&h.ui).contains(&"count 0".to_string()), "{:?}", texts(&h.ui));
    assert!(h.send(json!({"type": "click", "id": "button:Add"})));
    assert!(texts(&h.ui).contains(&"count 1".to_string()));
    assert_eq!(h.world.borrow().field.string(), "+");
    let stored = std::fs::read_to_string(dir.join(".wmpy/storage.json")).unwrap();
    assert!(stored.contains("count"), "{stored}");
    // A reload keeps storage and the pretend field.
    h.reload("test");
    assert!(texts(&h.ui).contains(&"count 1".to_string()), "{:?}", texts(&h.ui));
    assert_eq!(h.world.borrow().field.string(), "+");
    assert!(h.console.iter().any(|c| c.kind == Kind::Reload));
}

#[test]
fn then_answers_arrive_from_tick() {
    let dir = plugin("then", &[("plugin.json", MANIFEST), ("main.py", MAIN)]);
    let mut h = open(&dir);
    h.send(json!({"type": "click", "id": "button:Where"}));
    assert_eq!(h.world.borrow().pending, 1);
    settle(&mut h, |h| texts(&h.ui).contains(&"place 23.8103".to_string()));
    assert!(texts(&h.ui).contains(&"place 23.8103".to_string()), "{:?}", texts(&h.ui));
    assert_eq!(h.world.borrow().pending, 0);
    assert!(h.calls.iter().any(|c| c.op == "location.get" && c.source == "pretend"));
}

#[test]
fn settings_come_from_the_manifest_then_the_file_then_set() {
    let dir = plugin("settings", &[("plugin.json", MANIFEST), ("main.py", MAIN), (".wmpy/settings.json", r#"{"unit": "ft"}"#)]);
    let mut h = DevHost::open(Options { dir: dir.clone(), sets: vec![("step".into(), "5".into())], ..Options::default() }).unwrap();
    assert!(texts(&h.ui).contains(&"unit ft".to_string()), "{:?}", texts(&h.ui));
    h.send(json!({"type": "click", "id": "button:Add"}));
    assert!(texts(&h.ui).contains(&"count 5".to_string()), "{:?}", texts(&h.ui));
    let bad = DevHost::open(Options { dir, sets: vec![("nope".into(), "1".into())], ..Options::default() });
    assert!(bad.err().unwrap().contains("no setting 'nope'"));
}

#[test]
fn a_mock_answers_first_and_unrouted_requests_are_still_checked() {
    let main = r#"from wm import plugin, ui, http, location
state = plugin.state(out="")
def go():
    state.out = str(location.get()["lat"]) + " " + http.get("https://api.example.com/v1").text
def other():
    try:
        http.get("https://evil.example.org/")
    except PermissionError as e:
        state.out = "refused: " + str(e)
def render():
    ui.button("Go", on_click=go)
    ui.button("Other", on_click=other)
    ui.label(state.out)
"#;
    let mock = r#"{"location.get": {"lat": 1.5, "lon": 2}, "http.request": [{"when": {"url": "https://api.example.com/*"}, "reply": {"status": 200, "text": "hi"}}]}"#;
    let dir = plugin("mock", &[("plugin.json", MANIFEST), ("main.py", main), ("mock.json", mock)]);
    let mut h = DevHost::open(Options { dir: dir.clone(), mock: Some(dir.join("mock.json")), ..Options::default() }).unwrap();
    h.send(json!({"type": "click", "id": "button:Go"}));
    assert!(texts(&h.ui).contains(&"1.5 hi".to_string()), "{:?} {:?}", texts(&h.ui), h.error);
    h.send(json!({"type": "click", "id": "button:Other"}));
    assert!(texts(&h.ui).iter().any(|t| t.contains("not allowed to connect to evil.example.org")), "{:?}", texts(&h.ui));
    assert!(h.calls.iter().any(|c| c.source == "mock"));
    assert!(h.calls.iter().any(|c| c.source == "refused"));
}

#[test]
fn timers_toasts_and_dialogs() {
    let main = r#"from wm import plugin, ui
state = plugin.state(ticks=0, answer="")
def later():
    state.ticks += 1
def start():
    plugin.after(0.05, later)
    ui.toast("started")
def ask():
    ui.dialog("Delete?", buttons=["Delete", "Keep"], on_result=lambda b: setattr(state, "answer", str(b)))
def render():
    ui.button("Start", on_click=start)
    ui.button("Ask", on_click=ask)
    ui.label("ticks " + str(state.ticks))
    ui.label("answer " + state.answer)
"#;
    let dir = plugin("timers", &[("plugin.json", r#"{"id": "com.example.t", "version": "1", "apiVersion": 5}"#), ("main.py", main)]);
    let mut h = open(&dir);
    h.send(json!({"type": "click", "id": "button:Start"}));
    assert_eq!(h.toasts(), vec!["started".to_string()]);
    settle(&mut h, |h| texts(&h.ui).contains(&"ticks 1".to_string()));
    assert!(texts(&h.ui).contains(&"ticks 1".to_string()), "{:?}", texts(&h.ui));
    h.send(json!({"type": "click", "id": "button:Ask"}));
    assert_eq!(h.dialog().unwrap().buttons, vec!["Delete".to_string(), "Keep".to_string()]);
    h.answer_dialog(Some(1));
    settle(&mut h, |h| texts(&h.ui).contains(&"answer Keep".to_string()));
    assert!(texts(&h.ui).contains(&"answer Keep".to_string()), "{:?}", texts(&h.ui));
}

#[test]
fn errors_carry_the_place_and_budgets_explain_themselves() {
    let main = "from wm import ui\ndef boom():\n    raise ValueError('bad input')\ndef spin():\n    while True:\n        pass\ndef render():\n    ui.button('Boom', on_click=boom)\n    ui.button('Spin', on_click=spin)\n";
    let dir = plugin("errors", &[("plugin.json", r#"{"id": "com.example.e", "version": "1", "apiVersion": 5}"#), ("main.py", main)]);
    let mut h = open(&dir);
    assert!(!h.send(json!({"type": "click", "id": "button:Boom"})));
    let d = h.error.clone().unwrap();
    assert_eq!((d.code.as_deref(), d.line), (Some("ValueError"), Some(3)));
    assert_eq!(d.file.as_deref(), Some("main.py"));
    assert!(!h.send(json!({"type": "click", "id": "button:Spin"})));
    let d = h.error.clone().unwrap();
    assert_eq!(d.code.as_deref(), Some("steps"), "{d:?}");
    assert!(d.help.unwrap().contains("6 million"));
    // A failed event leaves the panel usable.
    assert!(h.loaded);
    let snap = h.snapshot();
    assert_eq!(snap["error"]["code"], "steps");
    assert!(snap["console"].as_array().unwrap().iter().any(|c| c["kind"] == "error"));
}

#[test]
fn a_syntax_error_and_a_fix_reload_from_the_watched_folder() {
    let dir = plugin("watch", &[("plugin.json", r#"{"id": "com.example.w", "version": "1", "apiVersion": 5}"#), ("main.py", "def render(:\n")]);
    let mut h = DevHost::open(Options { dir: dir.clone(), watch: true, ..Options::default() }).unwrap();
    assert_eq!(h.status, Status::Failed);
    assert!(h.error.is_some());
    std::thread::sleep(Duration::from_millis(20));
    std::fs::write(dir.join("main.py"), "from wm import ui\ndef render():\n    ui.label('fixed')\n").unwrap();
    settle(&mut h, |h| h.status == Status::Ready);
    assert_eq!(h.status, Status::Ready, "{:?}", h.error);
    assert!(texts(&h.ui).contains(&"fixed".to_string()));
    assert!(h.console.iter().any(|c| c.kind == Kind::Reload && c.text.contains("main.py")));
}
