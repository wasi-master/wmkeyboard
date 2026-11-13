//! API 5 sessions: the `wm` package, callbacks and `with` blocks, state,
//! decorators, timers, host calls through a bridge and the checks before
//! them, multi-file plugins, and the new widgets.

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

fn session(permissions: &[&str], extra: Json) -> Session {
    let mut config = json!({"id": "com.example.v5", "version": "1.0.0", "permissions": permissions, "mode": "panel", "apiVersion": 5});
    if let (Json::Object(c), Json::Object(e)) = (&mut config, extra) {
        c.extend(e);
    }
    Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap()
}

fn parse(s: String) -> Json {
    serde_json::from_str(&s).unwrap()
}

fn big<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

fn ok(r: &Json) {
    assert_eq!(r["ok"], true, "{r:#}");
}

fn root(r: &Json) -> Json {
    r["ui"]["root"].clone()
}

/// The first widget of [ty] anywhere in the tree.
fn find(node: &Json, ty: &str) -> Option<Json> {
    if node.get("type").and_then(Json::as_str) == Some(ty) {
        return Some(node.clone());
    }
    for key in ["children", "items", "pages", "root"] {
        if let Some(Json::Array(a)) = node.get(key) {
            for c in a {
                if let Some(f) = find(c, ty) {
                    return Some(f);
                }
            }
        }
    }
    if let Json::Array(a) = node {
        for c in a {
            if let Some(f) = find(c, ty) {
                return Some(f);
            }
        }
    }
    None
}

#[test]
fn with_blocks_and_callbacks_draw_and_route() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = r#"
from wm import plugin, ui

state = plugin.state(count=0)

def bump():
    state.count += 1

def render():
    with ui.column(gap=4):
        ui.label(f"Count {state.count}", style="title")
        with ui.row():
            ui.button("Add", on_click=bump, primary=True)
            ui.button("Reset", on_click=lambda: state.update(count=0))
"#;
        let r = parse(s.load(src));
        ok(&r);
        let col = &root(&r)[0];
        assert_eq!(col["type"], "column");
        assert_eq!(col["gap"], 4);
        assert_eq!(col["children"][0]["text"], "Count 0");
        let add = find(&r["ui"], "button").unwrap();
        assert_eq!(add["text"], "Add");
        assert_eq!(add["primary"], true);
        let id = add["id"].as_str().unwrap().to_string();
        assert!(!id.is_empty());

        let r = parse(s.event(&json!({"type": "click", "id": id}).to_string()));
        ok(&r);
        assert_eq!(root(&r)[0]["children"][0]["text"], "Count 1");
        // Ids are stable from one draw to the next.
        assert_eq!(find(&r["ui"], "button").unwrap()["id"], id.as_str());
    });
}

#[test]
fn returned_trees_still_work_and_widgets_nest_once() {
    big(|| {
        let mut s = session(&[], json!({}));
        let r = parse(s.load("from wm import ui\ndef render():\n    return ui.column(ui.label('a'), ui.row(ui.label('b')))"));
        ok(&r);
        assert_eq!(r["ui"]["root"].as_array().unwrap().len(), 1, "{r:#}");
        assert_eq!(r["ui"]["root"][0]["children"][1]["children"][0]["text"], "b");
    });
}

#[test]
fn inputs_change_and_submit_with_values() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = r#"
from wm import ui
said = []
def render():
    ui.input(label="Name", on_submit=said.append, key="name")
    ui.label(", ".join(said))
"#;
        let r = parse(s.load(src));
        ok(&r);
        let input = find(&r["ui"], "input").unwrap();
        assert_eq!(input["id"], "name");
        assert_eq!(input["submit"], true);
        assert_eq!(input["live"], false);
        let r = parse(s.event(&json!({"type": "submit", "id": "name", "value": "Ada"}).to_string()));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "Ada");
    });
}

#[test]
fn input_value_is_pushed_only_when_it_changes() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = "from wm import ui, plugin\nst = plugin.state(q='hi')\ndef clear():\n    st.q = ''\ndef render():\n    ui.input(value=st.q, key='q', on_change=lambda v: setattr(st, 'q', v))\n    ui.button('Clear', on_click=clear, key='c')";
        let r = parse(s.load(src));
        ok(&r);
        assert_eq!(r["inputs"], json!([["q", "hi"]]));
        let r = parse(s.event(&json!({"type": "change", "id": "q", "value": "hix"}).to_string()));
        ok(&r);
        assert_eq!(r["inputs"], json!([]), "typing must not be echoed back");
        let r = parse(s.event(&json!({"type": "click", "id": "c"}).to_string()));
        assert_eq!(r["inputs"], json!([["q", ""]]));
    });
}

#[test]
fn persisted_state_survives_a_new_session() {
    big(|| {
        let dir = std::env::temp_dir().join(format!("wm-v5-state-{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);
        let path = dir.join("storage.json");
        let _ = std::fs::remove_file(&path);
        let src = "from wm import plugin, ui\nst = plugin.state(n=0, persist=True)\ndef render():\n    ui.button(str(st.n), on_click=lambda: setattr(st, 'n', st.n + 1), key='b')";
        let extra = json!({"storagePath": path.to_str().unwrap()});
        let mut s = session(&["storage"], extra.clone());
        ok(&parse(s.load(src)));
        let r = parse(s.event(&json!({"type": "click", "id": "b"}).to_string()));
        assert_eq!(find(&r["ui"], "button").unwrap()["text"], "1");
        let mut again = session(&["storage"], extra);
        let r = parse(again.load(src));
        assert_eq!(find(&r["ui"], "button").unwrap()["text"], "1");
        let _ = std::fs::remove_dir_all(&dir);
    });
}

#[test]
fn storage_is_a_dict_of_json_values() {
    big(|| {
        let mut s = session(&["storage"], json!({}));
        let src = "from wm import storage, ui\nstorage['items'] = ['milk', {'n': 2}]\nstorage.setdefault('count', 3)\ndef render():\n    ui.label(repr(storage['items']) + ' ' + str(storage.get('count')) + ' ' + str('x' in storage) + ' ' + str(len(storage)))";
        let r = parse(s.load(src));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "['milk', {'n': 2}] 3 False 2");
        let stored: Json = serde_json::from_str(&s.storage_json()).unwrap();
        assert_eq!(stored["items"], "[\"milk\", {\"n\": 2}]");
    });
}

#[test]
fn storage_without_the_permission_is_a_permission_error() {
    big(|| {
        let mut s = session(&[], json!({}));
        let r = parse(s.load("from wm import storage, ui\ntry:\n    storage['a'] = 1\n    msg = 'stored'\nexcept PermissionError as e:\n    msg = 'refused'\ndef render():\n    ui.label(msg)"));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "refused");
    });
}

#[test]
fn decorators_report_timers_commands_and_actions() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = r#"
from wm import plugin, ui
ticks = plugin.state(n=0, opened=0)

@plugin.on_open
def opened():
    ticks.opened += 1

@plugin.every(minutes=1)
def tick():
    ticks.n += 1

@plugin.command("Refresh", icon="refresh")
def refresh():
    ticks.n = 100

@plugin.action("shout")
def shout(text):
    return text.upper()

def render():
    ui.label(f"{ticks.n} {ticks.opened}")
"#;
        let r = parse(s.load(src));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "0 1");
        assert_eq!(r["host"]["timers"], json!([{"id": "tick", "seconds": 60, "repeat": true}]));
        assert_eq!(r["host"]["commands"], json!([{"id": "refresh", "label": "Refresh", "icon": "refresh"}]));
        assert_eq!(r["host"]["actions"], json!(["shout"]));
        let r = parse(s.event(&json!({"type": "timer", "id": "tick"}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "1 1");
        let r = parse(s.event(&json!({"type": "command", "id": "refresh"}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "100 1");
        let r = parse(s.transform("hey", "shout"));
        assert_eq!(r["value"], "HEY");
    });
}

#[test]
fn action_mode_runs_decorated_actions_and_plain_transform() {
    big(|| {
        let config = json!({"id": "a", "version": "1", "permissions": [], "mode": "action", "apiVersion": 5});
        let mut s = Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap();
        let src = "from wm import plugin\n@plugin.action('rev')\ndef rev(t):\n    return t[::-1]\ndef transform(text, action):\n    return action + ':' + text";
        ok(&parse(s.load(src)));
        assert_eq!(parse(s.transform("abc", "rev"))["value"], "cba");
        assert_eq!(parse(s.transform("abc", "other"))["value"], "other:abc");
        let mut none = Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap();
        ok(&parse(none.load("x = 1")));
        assert_eq!(parse(none.transform("abc", "z"))["error"]["kind"], "no_transform");
    });
}

#[test]
fn pages_push_and_go_back() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = "from wm import plugin, ui\ndef detail():\n    ui.label('detail')\ndef render():\n    ui.button('Open', on_click=lambda: plugin.push(detail, title='Detail'), key='o')";
        let r = parse(s.load(src));
        assert_eq!(r["host"]["canBack"], false);
        let r = parse(s.event(&json!({"type": "click", "id": "o"}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "detail");
        assert_eq!(r["host"]["canBack"], true);
        assert_eq!(r["host"]["title"], "Detail");
        let r = parse(s.event(&json!({"type": "back"}).to_string()));
        assert!(find(&r["ui"], "button").is_some());
    });
}

#[test]
fn host_calls_go_through_the_bridge() {
    big(|| {
        let mut s = session(&["network"], json!({}));
        let seen: Rc<RefCell<Vec<(String, Json)>>> = Rc::new(RefCell::new(Vec::new()));
        let seen2 = seen.clone();
        s.set_bridge(Some(Box::new(move |op: &str, args: &str| {
            let args: Json = serde_json::from_str(args).unwrap();
            seen2.borrow_mut().push((op.to_string(), args));
            json!({"value": {"status": 200, "text": "{\"temp\": 21}", "headers": {"Content-Type": "application/json"}, "url": "https://api.example.com/w"}}).to_string()
        })));
        let src = r#"
from wm import http, ui
r = http.get("https://api.example.com/w", params={"q": "Dhaka"}, headers={"X-A": "1"})
r.raise_for_status()
temp = r.json()["temp"]
def render():
    ui.label(f"{temp} {r.status_code} {r.headers['content-type']} {r.ok}")
"#;
        let r = parse(s.load(src));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "21 200 application/json True");
        let calls = seen.borrow();
        assert_eq!(calls[0].0, "http.request");
        assert_eq!(calls[0].1["method"], "GET");
        assert_eq!(calls[0].1["params"], json!({"q": "Dhaka"}));
        assert_eq!(calls[0].1["headers"], json!({"X-A": "1"}));
    });
}

#[test]
fn an_undeclared_permission_is_refused_before_the_bridge() {
    big(|| {
        let mut s = session(&[], json!({}));
        let called = Rc::new(RefCell::new(false));
        let c2 = called.clone();
        s.set_bridge(Some(Box::new(move |_: &str, _: &str| {
            *c2.borrow_mut() = true;
            "{}".into()
        })));
        let r = parse(s.load("from wm import http, ui\ntry:\n    http.get('https://x.example')\n    m = 'sent'\nexcept PermissionError as e:\n    m = str(e)\ndef render():\n    ui.label(m)"));
        ok(&r);
        assert!(find(&r["ui"], "label").unwrap()["text"].as_str().unwrap().contains("'network'"));
        assert!(!*called.borrow());
    });
}

#[test]
fn a_missing_grant_is_reported_as_needs() {
    big(|| {
        let mut s = session(&["network"], json!({}));
        s.set_bridge(Some(Box::new(|_: &str, _: &str| {
            json!({"error": {"kind": "PermissionError", "message": "not allowed yet", "needs": "network"}}).to_string()
        })));
        let r = parse(s.load("from wm import http, ui\ndef go():\n    http.get('https://x.example')\ndef render():\n    ui.button('Go', on_click=go, key='g')"));
        ok(&r);
        let r = parse(s.event(&json!({"type": "click", "id": "g"}).to_string()));
        assert_eq!(r["ok"], false);
        assert_eq!(r["error"]["exception"], "PermissionError");
        assert_eq!(r["needs"], json!(["network"]));
    });
}

#[test]
fn reading_the_field_needs_a_user_event() {
    big(|| {
        let mut s = session(&["field.read", "field.write"], json!({}));
        s.set_bridge(Some(Box::new(|op: &str, _: &str| match op {
            "field.selection" => json!({"value": "picked"}).to_string(),
            _ => json!({"value": null}).to_string(),
        })));
        let src = r#"
from wm import field, plugin, ui
got = plugin.state(text="", early="")
try:
    field.selection()
    got.early = "read at load"
except PermissionError:
    got.early = "refused at load"

@plugin.every(seconds=5)
def poll():
    got.text = field.selection()

def grab():
    got.text = field.selection()
    field.insert("!")

def render():
    ui.label(got.early + "|" + got.text)
    ui.button("Grab", on_click=grab, key="g")
"#;
        let r = parse(s.load(src));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "refused at load|");
        let r = parse(s.event(&json!({"type": "timer", "id": "poll"}).to_string()));
        assert_eq!(r["ok"], false);
        assert_eq!(r["error"]["exception"], "PermissionError");
        let r = parse(s.event(&json!({"type": "click", "id": "g"}).to_string()));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "refused at load|picked");
    });
}

#[test]
fn async_calls_answer_later_as_result_events() {
    big(|| {
        let mut s = session(&["ai"], json!({}));
        let src = r#"
from wm import ai, plugin, ui
st = plugin.state(reply="", err="")
def ask():
    ai.ask("hi", then=lambda text: setattr(st, "reply", text), on_error=lambda e: setattr(st, "err", str(e)))
def render():
    ui.button("Ask", on_click=ask, key="a")
    ui.label(st.reply or st.err or "waiting")
"#;
        ok(&parse(s.load(src)));
        let r = parse(s.event(&json!({"type": "click", "id": "a"}).to_string()));
        ok(&r);
        // No bridge here: the call is recorded for the front end.
        let call = &r["calls"][0];
        assert_eq!(call["op"], "ai.ask");
        let token = call["args"]["async"].as_str().unwrap().to_string();
        assert_eq!(r["host"]["pending"], 1);
        let r = parse(s.event(&json!({"type": "result", "token": token, "value": {"text": "hello"}}).to_string()));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "hello");
        assert_eq!(r["host"]["pending"], 0);
    });
}

#[test]
fn async_errors_reach_on_error() {
    big(|| {
        let mut s = session(&["network"], json!({}));
        let src = "from wm import http, plugin, ui\nst = plugin.state(m='')\nhttp.get('https://a.example', then=lambda r: None, on_error=lambda e: setattr(st, 'm', type(e).__name__ + ':' + str(e)))\ndef render():\n    ui.label(st.m)";
        let r = parse(s.load(src));
        let token = r["calls"][0]["args"]["async"].as_str().unwrap().to_string();
        let r = parse(s.event(&json!({"type": "result", "token": token, "value": {"error": {"kind": "TimeoutError", "message": "took too long"}}}).to_string()));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "TimeoutError:took too long");
    });
}

#[test]
fn plugins_import_their_own_modules() {
    big(|| {
        let files = json!({"files": {
            "helpers.py": "def shout(t):\n    return t.upper() + '!'\n",
            "lib/__init__.py": "from .maths import double\n",
            "lib/maths.py": "def double(x):\n    return x * 2\n",
            "wm.py": "evil = True\n",
        }});
        let mut s = session(&[], files);
        let r = parse(s.load("from wm import ui\nimport helpers\nfrom lib import double\ndef render():\n    ui.label(helpers.shout('hi') + str(double(21)))"));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "HI!42");
    });
}

#[test]
fn settings_and_context_are_readable() {
    big(|| {
        let mut s = session(&[], json!({"settings": {"units": "metric"}, "context": {"locale": "bn", "theme": "dark"}}));
        let r = parse(s.load("from wm import plugin, ui\ndef render():\n    ui.label(plugin.settings.units + ' ' + plugin.locale + ' ' + str(plugin.dark))"));
        ok(&r);
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "metric bn True");
    });
}

#[test]
fn setting_and_keyword_events_reach_their_handlers() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = "from wm import plugin, ui\nst = plugin.state(m='')\n@plugin.setting_changed('units')\ndef units(v):\n    st.m = 'units=' + v\n@plugin.keyword('w')\ndef w(q):\n    st.m = 'query=' + q\ndef render():\n    ui.label(st.m)";
        ok(&parse(s.load(src)));
        let r = parse(s.event(&json!({"type": "setting", "key": "units", "value": "imperial"}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "units=imperial");
        let r = parse(s.event(&json!({"type": "keyword", "id": "w", "value": "rain"}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "query=rain");
    });
}

#[test]
fn unrouted_events_fall_back_to_on_event() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = "from wm import ui\nseen = []\ndef on_event(e):\n    seen.append(e['type'] + ':' + str(e.get('extra')))\ndef render():\n    ui.label(','.join(seen))";
        ok(&parse(s.load(src)));
        let r = parse(s.event(&json!({"type": "custom", "extra": 7}).to_string()));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "custom:7");
    });
}

#[test]
fn new_widgets_encode() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = r#"
from wm import ui
def render():
    with ui.card(title="Card"):
        ui.list([ui.item("One", subtitle="first", icon="cloud", actions={"Delete": lambda: None}),
                 ui.item("Two", trailing="2")], on_select=print)
        ui.slider(30, min=0, max=50, on_change=print, label="Size")
        ui.select(["a", ("b", "Bee")], value="b", style="segmented", on_change=print)
        ui.chips(["x", "y"], value=["y"], multi=True, on_change=print)
        ui.chart([1, 2, 3], kind="bar", labels=["a", "b", "c"])
        ui.canvas(100, 50, [ui.rect(0, 0, 10, 10, color="danger"), ui.circle(5, 5, 2), ui.text_at(1, 1, "hi")], on_tap=print)
        ui.image("assets/logo.png", alt="Logo")
        ui.image("http://insecure.example/x.png")
        ui.progress(1.5)
        ui.ring(0.25, label="Done")
        ui.label("warn", color="danger")
        ui.label("plain")
        ui.keys([["1", "2"], ["3"]], on_press=print)
"#;
        let r = parse(s.load(src));
        ok(&r);
        let ui = &r["ui"];
        let card = find(ui, "card").unwrap();
        assert_eq!(card["title"], "Card");
        let list = find(ui, "list").unwrap();
        assert_eq!(list["selectable"], true);
        assert_eq!(list["items"][0]["subtitle"], "first");
        assert_eq!(list["items"][0]["icon"], "cloud");
        assert_eq!(list["items"][0]["actions"], json!(["Delete"]));
        assert_eq!(list["items"][1]["trailing"], "2");
        let slider = find(ui, "slider").unwrap();
        assert_eq!(slider["value"], 30.0);
        assert_eq!(slider["max"], 50.0);
        let select = find(ui, "select").unwrap();
        assert_eq!(select["options"], json!([{"value": "a", "label": "a"}, {"value": "b", "label": "Bee"}]));
        assert_eq!(select["value"], "b");
        assert_eq!(select["style"], "segmented");
        assert_eq!(find(ui, "chips").unwrap()["value"], json!(["y"]));
        let chart = find(ui, "chart").unwrap();
        assert_eq!(chart["series"][0]["values"], json!([1.0, 2.0, 3.0]));
        let canvas = find(ui, "canvas").unwrap();
        assert_eq!(canvas["shapes"].as_array().unwrap().len(), 3);
        assert_eq!(canvas["shapes"][0]["color"], "danger");
        assert_eq!(canvas["tappable"], true);
        assert_eq!(find(ui, "image").unwrap()["src"], "assets/logo.png");
        assert!(r["ui"]["repairs"].as_array().unwrap().iter().any(|x| x["code"] == "BAD_IMAGE"));
        assert_eq!(find(ui, "progress").unwrap()["value"], 1.0);
        assert_eq!(find(ui, "ring").unwrap()["value"], 0.25);
        let labels: Vec<Json> = card["children"].as_array().unwrap().iter().filter(|w| w["type"] == "label").cloned().collect();
        assert_eq!(labels[0]["color"], "danger");
        assert_eq!(labels[1]["color"], Json::Null);
        assert_eq!(find(ui, "keys").unwrap()["rows"], json!([["1", "2"], ["3"]]));
    });
}

#[test]
fn ui_color_roles_are_checked_in_python() {
    big(|| {
        let mut s = session(&[], json!({}));
        let r = parse(s.load("from wm import ui\ntry:\n    ui.icon('x', color='red')\n    m = 'drawn'\nexcept ValueError as e:\n    m = 'refused'\ndef render():\n    ui.label(m)"));
        assert_eq!(find(&r["ui"], "label").unwrap()["text"], "refused");
    });
}

#[test]
fn item_actions_and_canvas_taps_route() {
    big(|| {
        let mut s = session(&[], json!({}));
        let src = r#"
from wm import ui
log = []
def render():
    ui.list([ui.item("One", key="one", actions={"Delete": lambda: log.append("deleted")})])
    ui.canvas(10, 10, [], on_tap=lambda x, y: log.append(f"tap {x},{y}"), key="c")
    ui.select(["a", "b"], on_change=lambda v: log.append("chose " + v), key="s")
    ui.label(";".join(log))
"#;
        ok(&parse(s.load(src)));
        parse(s.event(&json!({"type": "item_action", "id": "one", "value": "Delete"}).to_string()));
        parse(s.event(&json!({"type": "tap", "id": "c", "x": 3, "y": 4}).to_string()));
        let r = parse(s.event(&json!({"type": "change", "id": "s", "value": "b"}).to_string()));
        ok(&r);
        let labels: Vec<Json> = r["ui"]["root"].as_array().unwrap().iter().filter(|w| w["type"] == "label").cloned().collect();
        assert_eq!(labels[0]["text"], "deleted;tap 3,4;chose b");
    });
}

#[test]
fn api_4_sessions_keep_the_old_globals() {
    big(|| {
        let config = json!({"id": "old", "version": "1", "permissions": [], "mode": "panel"});
        let mut s = Session::with_clock(&config.to_string(), Box::new(FixedClock)).unwrap();
        let r = parse(s.load("def render():\n    return ui.column(ui.button('go', 'Go'))"));
        ok(&r);
        assert_eq!(r["ui"]["root"][0]["children"][0], json!({"type": "button", "id": "go", "text": "Go", "primary": false, "enabled": true}));
        assert!(r.get("host").is_none());
    });
}

#[test]
fn a_plugin_without_render_is_reported() {
    big(|| {
        let mut s = session(&[], json!({}));
        let r = parse(s.load("x = 1"));
        assert_eq!(r["error"]["kind"], "no_render");
    });
}

#[test]
fn the_load_phase_counts_the_package_and_the_plugin_together() {
    big(|| {
        let src = "from wm import ui\ndef render():\n    ui.label('x')";
        let mut s = session(&[], json!({}));
        let r = parse(s.load(src));
        let package = r["phases"][0]["steps"].as_u64().unwrap();
        assert!(package > 100, "{r:#}");
        // Room for the package but not for the package and a 300-step loop.
        let limit = package + 150;
        let mut s = session(&[], json!({"limits": {"load": {"steps": limit, "millis": 3000}}}));
        ok(&parse(s.load(src)));
        let looped = format!("{src}\nfor i in range(300):\n    pass");
        let r = parse(s.load(&looped));
        assert_eq!(r["error"]["abort"], "steps", "{r:#}");
    });
}

#[test]
fn translations_fill_text_and_plurals_and_fall_back_to_english() {
    big(|| {
        let strings = json!({
            "@lang": "ru",
            "Look up": "Найти",
            "Hello, {name}": "Привет, {name}",
            "{n} day": {"one": "{n} день", "few": "{n} дня", "many": "{n} дней"},
            "Broken {who}": "Сломано {nobody}",
        });
        let mut s = session(&[], json!({"strings": strings}));
        let src = r#"
from wm import ui, _, ngettext
from wm import i18n

def render():
    return ui.column(
        ui.label(_("Look up")),
        ui.label(_("Not translated")),
        ui.label(_("Hello, {name}", name="Ana")),
        ui.label(" | ".join(ngettext("{n} day", "{n} days", n) for n in [1, 3, 5, 21])),
        ui.label(_("Broken {who}", who="me")),
        ui.label(str(i18n.language)),
    )
"#;
        let r = parse(s.load(src));
        ok(&r);
        let labels: Vec<String> = root(&r)[0]["children"].as_array().unwrap().iter().map(|w| w["text"].as_str().unwrap().to_string()).collect();
        assert_eq!(
            labels,
            vec!["Найти", "Not translated", "Привет, Ana", "1 день | 3 дня | 5 дней | 21 день", "Broken me", "ru"]
        );
    });
}

#[test]
fn without_translations_english_and_plurals_still_work() {
    big(|| {
        let mut s = session(&[], json!({}));
        let r = parse(s.load("from wm import ui, _, ngettext\ndef render():\n    return ui.label(_('Hi') + ' ' + ngettext('{n} item', '{n} items', 2))\n"));
        ok(&r);
        assert_eq!(root(&r)[0]["text"], "Hi 2 items");
    });
}
