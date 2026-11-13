//! `plugin.settings`: secret rows read through `secrets.get`, `set` and
//! `reset` go to the keyboard and update what the script reads, and a
//! setting event from the page updates it too.

use std::cell::RefCell;
use std::rc::Rc;

use serde_json::{json, Value as Json};
use wm_python::host::Session;
use wm_python::interp::Clock;

struct FixedClock;

impl Clock for FixedClock {
    fn now(&self) -> f64 {
        1_893_456_000.0
    }
    fn monotonic(&self) -> f64 {
        0.0
    }
    fn utc_offset(&self, _: i64) -> i32 {
        0
    }
}

fn session(permissions: &[&str], settings: Json) -> Session {
    let config = json!({
        "id": "com.example.settings", "version": "1.0.0", "permissions": permissions,
        "mode": "panel", "apiVersion": 5, "settings": settings,
    });
    Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap()
}

fn parse(s: String) -> Json {
    serde_json::from_str(&s).unwrap()
}

fn big<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

fn label(r: &Json) -> String {
    assert_eq!(r["ok"], true, "{r:#}");
    fn walk(n: &Json) -> Option<String> {
        if n.get("type").and_then(Json::as_str) == Some("label") {
            return n["text"].as_str().map(str::to_string);
        }
        for key in ["children", "root"] {
            if let Some(Json::Array(a)) = n.get(key) {
                for c in a {
                    if let Some(t) = walk(c) {
                        return Some(t);
                    }
                }
            }
        }
        None
    }
    walk(&r["ui"]["root"]).or_else(|| walk(&r["ui"])).expect("a label")
}

/// A bridge that records each call and answers like the keyboard: a secret
/// store, and `settings.set` echoing the value (or the default for None).
fn keyboard(s: &mut Session) -> Rc<RefCell<Vec<(String, Json)>>> {
    let calls = Rc::new(RefCell::new(Vec::<(String, Json)>::new()));
    let seen = calls.clone();
    let secrets = RefCell::new(serde_json::Map::new());
    s.set_bridge(Some(Box::new(move |op: &str, args: &str| {
        let args: Json = serde_json::from_str(args).unwrap();
        seen.borrow_mut().push((op.to_string(), args.clone()));
        let value = match op {
            "secrets.get" => secrets.borrow().get(args["name"].as_str().unwrap()).cloned().unwrap_or(Json::Null),
            "settings.set" => match (args["key"].as_str().unwrap(), &args["value"]) {
                ("server", _) => return json!({"error": {"kind": "PermissionError", "message": "host"}}).to_string(),
                ("token", Json::Null) => {
                    secrets.borrow_mut().remove("token");
                    json!({"$secret": true})
                }
                ("token", v) => {
                    secrets.borrow_mut().insert("token".into(), v.clone());
                    json!({"$secret": true})
                }
                ("units", Json::Null) => json!("metric"),
                (_, v) => v.clone(),
            },
            _ => Json::Null,
        };
        json!({"value": value}).to_string()
    })));
    calls
}

#[test]
fn secret_rows_read_through_secrets_and_hide_in_repr() {
    big(|| {
        let mut s = session(&["storage"], json!({"units": "metric", "token": {"$secret": true}}));
        let calls = keyboard(&mut s);
        let src = r#"
from wm import plugin, ui
def render():
    t = plugin.settings.token
    ui.label(str(t) + "|" + str(plugin.settings.get("token", "none")) + "|" + repr(plugin.settings))
"#;
        let text = label(&parse(s.load(src)));
        assert_eq!(text, "None|none|plugin.settings({'units': 'metric', 'token': '***'})");
        assert!(calls.borrow().iter().all(|(op, _)| op == "secrets.get"));
    });
}

#[test]
fn set_and_reset_update_what_the_script_reads() {
    big(|| {
        let mut s = session(&["storage"], json!({"units": "metric", "count": 5, "token": {"$secret": true}}));
        let calls = keyboard(&mut s);
        let src = r#"
from wm import plugin, ui
log = []
plugin.settings.set("units", "imperial")
log.append(plugin.settings.units)
plugin.settings.set("count", 9)
log.append(str(plugin.settings.count))
plugin.settings.set("token", "abc")
log.append(plugin.settings.token)
plugin.settings.reset("token")
log.append(str(plugin.settings.token))
plugin.settings.reset("units")
log.append(plugin.settings.units)
def render():
    ui.label(",".join(log))
"#;
        assert_eq!(label(&parse(s.load(src))), "imperial,9,abc,None,metric");
        let sets: Vec<Json> = calls.borrow().iter().filter(|(op, _)| op == "settings.set").map(|(_, a)| a.clone()).collect();
        assert_eq!(sets[0], json!({"key": "units", "value": "imperial"}));
        assert_eq!(sets[1], json!({"key": "count", "value": 9}));
        assert_eq!(sets[3], json!({"key": "token", "value": null}));
    });
}

#[test]
fn set_refuses_unknown_keys_none_and_host_rows() {
    big(|| {
        let mut s = session(&["storage"], json!({"units": "metric", "server": ""}));
        let calls = keyboard(&mut s);
        let src = r#"
from wm import plugin, ui
out = []
for key, value in [("nope", 1), ("units", None), ("server", "https://evil.example")]:
    try:
        plugin.settings.set(key, value)
        out.append("set")
    except Exception as e:
        out.append(type(e).__name__)
def render():
    ui.label(",".join(out) + "|" + plugin.settings.server)
"#;
        assert_eq!(label(&parse(s.load(src))), "AttributeError,ValueError,PermissionError|");
        // Only the host row reached the keyboard, which refused it.
        let ops: Vec<String> = calls.borrow().iter().map(|(op, _)| op.clone()).collect();
        assert_eq!(ops, vec!["settings.set".to_string()]);
    });
}

#[test]
fn a_setting_event_from_the_page_updates_plugin_settings() {
    big(|| {
        let mut s = session(&[], json!({"units": "metric"}));
        let src = r#"
from wm import plugin, ui
heard = []
@plugin.setting_changed("units")
def changed(v):
    heard.append(v)
def render():
    ui.label(plugin.settings.units + "|" + ",".join(heard))
"#;
        label(&parse(s.load(src)));
        let r = parse(s.event(&json!({"type": "setting", "key": "units", "value": "imperial"}).to_string()));
        assert_eq!(label(&r), "imperial|imperial");
    });
}
