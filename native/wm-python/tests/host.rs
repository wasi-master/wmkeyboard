//! The plugin host: `wm` and `ui`, permission gating, the storage and
//! reminders files, the widget codec, and the Session JSON contract.

use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use serde_json::{json, Value as Json};
use wm_python::host::files::{kotlin_quoted, Reminders, Storage};
use wm_python::host::Session;
use wm_python::interp::Clock;

/// 2030-01-01 00:00:00 UTC.
const NOW: f64 = 1_893_456_000.0;

struct FixedClock {
    offset: i32,
}

impl Clock for FixedClock {
    fn now(&self) -> f64 {
        NOW
    }
    fn monotonic(&self) -> f64 {
        0.0
    }
    fn utc_offset(&self, _: i64) -> i32 {
        self.offset
    }
}

fn temp_dir(name: &str) -> PathBuf {
    static N: AtomicUsize = AtomicUsize::new(0);
    let dir = std::env::temp_dir().join(format!("wm-python-test-{}-{}-{name}", std::process::id(), N.fetch_add(1, Ordering::SeqCst)));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

fn fixture(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/fixtures/host/{name}", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

fn session_with(config: Json) -> Session {
    Session::with_clock(&config.to_string(), Box::new(FixedClock { offset: 0 })).unwrap()
}

fn panel(permissions: &[&str]) -> Session {
    session_with(json!({"id": "com.example.test", "version": "1.2.3", "permissions": permissions, "mode": "panel"}))
}

fn parse(result: String) -> Json {
    serde_json::from_str(&result).unwrap()
}

/// Run on a big stack, as a host would.
fn big<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    std::thread::Builder::new().stack_size(64 << 20).spawn(f).unwrap().join().unwrap()
}

fn load(s: &mut Session, source: &str) -> Json {
    parse(s.load(source))
}

// MARK: - the result contract

#[test]
fn a_panel_load_runs_the_top_level_then_renders() {
    let mut s = panel(&[]);
    let r = load(&mut s, "print('hello', 1)\ndef render():\n    return ui.column(ui.label('Hi', style='title'), ui.button('go', 'Go', style='primary'))");
    assert_eq!(r["ok"], true);
    assert_eq!(r["log"], json!(["hello 1"]));
    assert_eq!(r["inputs"], json!([]));
    assert_eq!(r["remindersChanged"], false);
    assert!(r.get("error").is_none());
    assert!(r.get("value").is_none());
    assert_eq!(
        r["ui"],
        json!({"root": [{"type": "column", "children": [
            {"type": "label", "text": "Hi", "style": "title"},
            {"type": "button", "id": "go", "text": "Go", "primary": true, "enabled": true},
        ]}], "repairs": []})
    );
    let phases = r["phases"].as_array().unwrap();
    assert_eq!(phases.len(), 2);
    assert_eq!(phases[0]["phase"], "load");
    assert_eq!(phases[0]["stepsLimit"], 3_000_000);
    assert_eq!(phases[0]["millisLimit"], 3000);
    assert!(phases[0]["steps"].as_u64().unwrap() > 0);
    assert_eq!(phases[1]["phase"], "render");
    assert_eq!(phases[1]["stepsLimit"], 400_000);
    assert_eq!(phases[1]["millisLimit"], 500);
    assert!(phases[1].get("elapsedMicros").unwrap().is_u64());
    let usage: Json = serde_json::from_str(&s.usage()).unwrap();
    assert_eq!(usage["steps_limit"], 400_000);
    assert_eq!(usage["millis_limit"], 500);
}

#[test]
fn a_plugin_without_render_says_so() {
    let mut s = panel(&[]);
    let r = load(&mut s, "x = 1");
    assert_eq!(r["ok"], false);
    assert_eq!(r["error"], json!({"kind": "no_render", "message": "", "line": null, "abort": null, "exception": null}));
}

#[test]
fn compile_and_runtime_errors() {
    let mut s = panel(&[]);
    let r = load(&mut s, "def render():\n    return [\n");
    assert_eq!(r["ok"], false);
    assert_eq!(r["error"]["kind"], "compile");
    assert!(r["phases"].as_array().unwrap().is_empty());
    let r = load(&mut s, "x = 1\n\ny = int('nope')\ndef render():\n    return []");
    assert_eq!(
        r["error"],
        json!({"kind": "runtime", "message": "ValueError: invalid literal for int(): 'nope'", "line": 3, "abort": null, "exception": "ValueError"})
    );
    let r = load(&mut s, "def render():\n    return 1 / 0");
    assert_eq!(r["error"]["kind"], "runtime");
    assert_eq!(r["error"]["line"], 2);
    assert_eq!(r["phases"][1]["phase"], "render");
}

#[test]
fn events_reach_on_event_as_a_dict_then_render() {
    let mut s = panel(&[]);
    let src = "seen = []\ndef on_event(e):\n    seen.append(f\"{e.type}:{e['id']}:{e.get('value')}:{e.get('index')}\")\ndef render():\n    return ui.output(' '.join(seen), id='out')";
    assert_eq!(load(&mut s, src)["ok"], true);
    parse(s.event(r#"{"type":"click","id":"go"}"#));
    parse(s.event(r#"{"type":"toggle","id":"t","value":true}"#));
    parse(s.event(r#"{"type":"date_changed","id":"d","value":null}"#));
    let r = parse(s.event(r#"{"type":"tab_selected","id":"tabs","index":2}"#));
    assert_eq!(r["ok"], true);
    assert_eq!(
        r["ui"]["root"][0],
        json!({"type": "output", "id": "out", "text": "click:go:None:None toggle:t:True:None date_changed:d:None:None tab_selected:tabs:None:2",
               "mono": false, "insertable": true, "copyable": true})
    );
    let phases: Vec<&str> = r["phases"].as_array().unwrap().iter().map(|p| p["phase"].as_str().unwrap()).collect();
    assert_eq!(phases, ["event", "render"]);
    // A plugin without on_event still renders.
    let mut quiet = panel(&[]);
    load(&mut quiet, "def render():\n    return ui.divider()");
    assert_eq!(parse(quiet.event(r#"{"type":"click","id":"x"}"#))["ui"]["root"][0]["type"], "divider");
    assert_eq!(parse(quiet.event("nope"))["error"]["kind"], "internal");
}

#[test]
fn set_input_is_queued_and_capped() {
    let mut s = panel(&[]);
    load(&mut s, "def on_event(e):\n    wm.ui.set_input('box', 'x' * 9000)\n    wm.ui.set_input('', 'ignored')\ndef render():\n    return []");
    let r = parse(s.event(r#"{"type":"click","id":"b"}"#));
    let inputs = r["inputs"].as_array().unwrap();
    assert_eq!(inputs.len(), 1);
    assert_eq!(inputs[0][0], "box");
    assert_eq!(inputs[0][1].as_str().unwrap().len(), 8192);
    // Each call starts with no inputs and no log.
    let r = parse(s.render());
    assert_eq!(r["inputs"], json!([]));
}

#[test]
fn log_and_print_share_one_capped_channel() {
    let mut s = panel(&[]);
    let r = load(&mut s, "wm.log('a', 1, None, [2])\nprint('b')\nwm.log('y' * 600)\ndef render():\n    return []");
    let log = r["log"].as_array().unwrap();
    assert_eq!(log[0], "a 1 None [2]");
    assert_eq!(log[1], "b");
    assert_eq!(log[2].as_str().unwrap().len(), 512);
}

#[test]
fn wm_identifies_the_plugin() {
    let mut s = panel(&[]);
    let r = load(&mut s, "def render():\n    return ui.label(f'{wm.api_version} {wm.plugin_id} {wm.plugin_version}')");
    assert_eq!(r["ui"]["root"][0]["text"], "4 com.example.test 1.2.3");
}

// MARK: - transform

#[test]
fn action_sessions_call_transform_fresh_each_time() {
    let mut s = session_with(json!({"id": "a", "version": "1", "permissions": ["selection"], "mode": "action"}));
    let r = load(&mut s, "count = 0\ndef transform(text, action):\n    global count\n    count += 1\n    return f'{action}:{text.upper()}:{count}'");
    assert_eq!(r["ok"], true);
    assert!(r.get("ui").is_none());
    assert!(r["phases"].as_array().unwrap().is_empty());
    let r = parse(s.transform("hi", "shout"));
    assert_eq!(r["value"], "shout:HI:1");
    // A fresh interpreter every time: the global starts over.
    assert_eq!(parse(s.transform("hi", "shout"))["value"], "shout:HI:1");
    let p = &r["phases"][0];
    assert_eq!(p["phase"], "action");
    assert_eq!(p["stepsLimit"], 3_000_000);
    assert!(p["steps"].as_u64().unwrap() > 10);
    // No wm.ui in an action.
    load(&mut s, "def transform(text):\n    wm.ui.set_input('a', 'b')");
    let r = parse(s.transform("x", "a"));
    assert_eq!(r["error"]["message"], "AttributeError: module 'wm' has no attribute 'ui'");
}

#[test]
fn transform_results() {
    let mut s = session_with(json!({"id": "a", "version": "1", "mode": "action"}));
    load(&mut s, "def transform(text):\n    return None if text == 'none' else (len(text) if text == 'len' else text[::-1])");
    assert_eq!(parse(s.transform("abc", "x"))["value"], "cba");
    let r = parse(s.transform("none", "x"));
    assert_eq!(r["ok"], true);
    assert_eq!(r["value"], Json::Null);
    assert_eq!(parse(s.transform("len", "x"))["value"], "3");
    load(&mut s, "def other(text):\n    return text");
    let r = parse(s.transform("abc", "x"));
    assert_eq!(r["error"]["kind"], "no_transform");
    assert_eq!(r["value"], Json::Null);
}

// MARK: - stopping

#[test]
fn step_budget_aborts() {
    let mut s = session_with(json!({"id": "a", "version": "1", "mode": "panel", "limits": {"load": {"steps": 10000}}}));
    let r = load(&mut s, "while True:\n    pass");
    assert_eq!(r["error"]["kind"], "abort");
    assert_eq!(r["error"]["abort"], "steps");
    assert_eq!(r["error"]["exception"], "SystemExit");
    assert_eq!(r["phases"][0]["steps"], 10000);
}

#[test]
fn deadline_aborts() {
    big(|| {
        let mut s = session_with(json!({"id": "a", "version": "1", "mode": "panel", "limits": {"load": {"steps": 1_000_000_000u64, "millis": 50}}}));
        let started = Instant::now();
        let r = load(&mut s, "while True:\n    pass");
        assert!(started.elapsed() < Duration::from_secs(5));
        assert_eq!(r["error"]["abort"], "deadline");
    });
}

#[test]
fn cancel_from_another_thread_stops_the_call_in_flight_only() {
    big(|| {
        let mut s = session_with(json!({"id": "a", "version": "1", "mode": "panel", "limits": {"event": {"steps": 1_000_000_000u64, "millis": 60_000}}}));
        load(&mut s, "def on_event(e):\n    while True:\n        pass\ndef render():\n    return ui.label('ok')");
        let flag = s.cancel_handle();
        let canceller = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(50));
            flag.store(true, std::sync::atomic::Ordering::SeqCst);
        });
        let r = parse(s.event(r#"{"type":"click","id":"spin"}"#));
        canceller.join().unwrap();
        assert_eq!(r["error"]["abort"], "cancelled");
        // The flag is cleared when the next call starts.
        assert_eq!(parse(s.render())["ok"], true);
    });
}

#[test]
fn a_session_can_move_between_threads() {
    let mut s = panel(&[]);
    load(&mut s, "n = 0\ndef on_event(e):\n    global n\n    n += 1\ndef render():\n    return ui.label(str(n))");
    let r = std::thread::spawn(move || parse(s.event(r#"{"type":"click","id":"x"}"#))).join().unwrap();
    assert_eq!(r["ui"]["root"][0]["text"], "1");
}

// MARK: - permissions and storage

#[test]
fn storage_and_reminders_need_their_permissions() {
    let mut s = panel(&[]);
    let r = load(&mut s, "wm.storage.set('a', 'b')\ndef render():\n    return []");
    assert_eq!(r["error"]["message"], "AttributeError: module 'wm' has no attribute 'storage'");
    let r = load(&mut s, "wm.reminders.list()\ndef render():\n    return []");
    assert_eq!(r["error"]["message"], "AttributeError: module 'wm' has no attribute 'reminders'");
    let r = load(&mut s, "x = 'yes' if wm.api_version == 4 else 'no'\ndef render():\n    return ui.label(x)");
    assert_eq!(r["ok"], true);
}

#[test]
fn storage_api() {
    let mut s = panel(&["storage"]);
    let r = load(
        &mut s,
        "wm.storage.set('a', '1')\nwm.storage.set('b', '2')\nwm.storage.remove('a')\nkeys = wm.storage.keys()\n\
         got = [wm.storage.get('b'), wm.storage.get('a'), wm.storage.get('a', 'dflt')]\n\
         try:\n    wm.storage.set('n', 5)\nexcept TypeError as e:\n    got.append(str(e))\n\
         try:\n    wm.storage.set('k' * 65, 'v')\nexcept ValueError as e:\n    got.append(str(e))\n\
         try:\n    wm.storage.set('big', 'v' * 9000)\nexcept ValueError as e:\n    got.append(str(e))\n\
         def render():\n    return ui.label(str([keys, got]))",
    );
    assert_eq!(
        r["ui"]["root"][0]["text"],
        "[['b'], ['2', None, 'dflt', 'set() argument \\'value\\' must be str, not int', 'storage keys can\\'t be longer than 64 characters', 'that value is larger than the 8 KB limit for one key']]"
    );
}

#[test]
fn storage_quotas_match_kotlin() {
    let mut st = Storage::new(None);
    for i in 0..128 {
        st.set(&format!("k{i}"), "v").unwrap();
    }
    assert_eq!(Err("this plugin already has the maximum of 128 stored keys".to_string()), st.set("one-more", "v"));
    st.set("k0", "replaced").unwrap();
    assert_eq!(Err("storage keys can't be empty".to_string()), st.set("", "v"));
    let mut big = Storage::new(None);
    for i in 0..7 {
        big.set(&format!("{i}"), &"x".repeat(8192)).unwrap();
    }
    // 7 × (1 + 8192) = 57,351 used; another full value would pass 64 KB.
    assert_eq!(Err("this plugin has used all 64 KB of its storage".to_string()), big.set("7", &"x".repeat(8192)));
    // Overwriting costs only the difference.
    big.set("0", &"y".repeat(8192)).unwrap();
    assert_eq!(57_351, big.used_bytes());
    // Lengths are UTF-16 units: an emoji is two.
    let mut units = Storage::new(None);
    assert!(units.set(&"😀".repeat(32), "v").is_ok());
    assert!(units.set(&"😀".repeat(33), "v").is_err());
}

#[test]
fn storage_file_matches_kotlins_bytes() {
    let dir = temp_dir("storage");
    let path = dir.join("storage.json");
    let mut st = Storage::new(Some(path.clone()));
    st.set("items", "[\"buy milk\",\"call \\\"mum\\\"\"]").unwrap();
    st.set("naïve", "tab\there\u{1} é 😀").unwrap();
    st.set("empty", "").unwrap();
    assert_eq!(fixture("storage.json"), std::fs::read_to_string(&path).unwrap());
    assert!(!dir.join("storage.json.part").exists());

    // And reads what Kotlin wrote.
    let read = Storage::new(Some(format!("{}/tests/fixtures/host/storage.json", env!("CARGO_MANIFEST_DIR")).into()));
    assert_eq!(vec!["items", "naïve", "empty"], read.keys());
    assert_eq!(Some("tab\there\u{1} é 😀".to_string()), read.get("naïve"));
    assert_eq!("\"\\b\\f\\u001f\\u007f\"".replace("\\u007f", "\u{7f}"), kotlin_quoted("\u{8}\u{c}\u{1f}\u{7f}"));
}

#[test]
fn storage_is_reread_on_every_call() {
    let dir = temp_dir("reread");
    let path = dir.join("storage.json");
    let mut s = session_with(json!({"id": "a", "version": "1", "permissions": ["storage"], "mode": "panel", "storagePath": path}));
    load(&mut s, "def on_event(e):\n    pass\ndef render():\n    return ui.label(str(wm.storage.get('k')))");
    // The app's storage inspector edits the file while the plugin is open.
    std::fs::write(&path, r#"{"k":"from the inspector"}"#).unwrap();
    assert_eq!(parse(s.render())["ui"]["root"][0]["text"], "from the inspector");
    // A file that is not an object of strings reads as empty, as Kotlin's does.
    std::fs::write(&path, r#"{"k":1}"#).unwrap();
    assert_eq!(parse(s.render())["ui"]["root"][0]["text"], "None");
    std::fs::write(&path, "not json").unwrap();
    assert_eq!(parse(s.render())["ui"]["root"][0]["text"], "None");
}

#[test]
fn reminders_file_matches_kotlins_bytes() {
    let dir = temp_dir("reminders");
    let path = dir.join("reminders.json");
    let now = 1_893_000_000_000;
    let mut r = Reminders::new(Some(path.clone()));
    r.set("milk", 1_893_456_000_000, "Buy \"oat\" milk", now).unwrap();
    // Control characters are cleaned the way a plugin's name is.
    r.set("call", 1_893_459_600_000, " Call mum\u{7} 📞\u{202e} ", now).unwrap();
    assert_eq!(fixture("reminders.json"), std::fs::read_to_string(&path).unwrap());
    let read = Reminders::new(Some(format!("{}/tests/fixtures/host/reminders.json", env!("CARGO_MANIFEST_DIR")).into()));
    let list = read.list();
    assert_eq!(2, list.len());
    assert_eq!(("milk", 1_893_456_000_000, "Buy \"oat\" milk"), (list[0].id.as_str(), list[0].at, list[0].text.as_str()));
    // Cancelling the last one deletes the file.
    assert!(r.cancel("milk"));
    assert!(!r.cancel("milk"));
    assert!(r.cancel("call"));
    assert!(!path.exists());
}

#[test]
fn reminder_rules_match_kotlin() {
    let now = 1_000_000;
    let mut r = Reminders::new(None);
    let ahead = now + 60_000;
    assert_eq!(Err("a reminder id can't be empty".to_string()), r.set("", ahead, "t", now));
    assert_eq!(Err("reminder ids can't be longer than 64 characters".to_string()), r.set(&"i".repeat(65), ahead, "t", now));
    assert_eq!(Err("a reminder needs some text to show".to_string()), r.set("a", ahead, " \u{7}\u{200b} ", now));
    assert_eq!(Err("that time has already passed".to_string()), r.set("a", now, "t", now));
    assert_eq!(Err("a reminder can't be more than ten years ahead".to_string()), r.set("a", now + 10 * 366 * 86_400_000 + 1, "t", now));
    for i in 0..64 {
        r.set(&format!("r{i}"), ahead + i, "t", now).unwrap();
    }
    assert_eq!(Err("this plugin already has the maximum of 64 reminders".to_string()), r.set("r64", ahead, "t", now));
    // Replacing one is always allowed.
    r.set("r0", ahead + 1000, &"x".repeat(300), now).unwrap();
    let r0 = r.list().into_iter().find(|x| x.id == "r0").unwrap();
    assert_eq!(200, r0.text.len());
    // Hand-edited files are read as far as they make sense.
    let dir = temp_dir("lenient");
    let path = dir.join("reminders.json");
    std::fs::write(&path, r#"[{"id":"a","at":5,"text":"one","extra":true},{"id":"a","at":6,"text":"dup"},{"id":"","at":7},{"at":8,"text":"no id"},{"id":"b","at":3}]"#).unwrap();
    let list = Reminders::new(Some(path.clone())).list();
    assert_eq!(vec![("b".to_string(), 3, String::new()), ("a".to_string(), 5, "one".to_string())], list.into_iter().map(|x| (x.id, x.at, x.text)).collect::<Vec<_>>());
    std::fs::write(&path, r#"[{"id":"a","at":"soon"}]"#).unwrap();
    assert!(Reminders::new(Some(path)).list().is_empty());
}

#[test]
fn reminders_api() {
    let mut s = panel(&["reminders"]);
    let r = load(
        &mut s,
        "t = wm.timestamp(2030, 1, 2, 9)\nwm.reminders.set('dentist', t, 'Dentist at nine')\n\
         wm.reminders.set('later', t + 3600, 'Later')\nwm.reminders.set('dentist', t - 60, 'Dentist, earlier')\n\
         errs = []\nfor args in [('x', 5, 'past'), ('x', 'soon', 'text'), ('', t, 'no id')]:\n    try:\n        wm.reminders.set(*args)\n    except ValueError as e:\n        errs.append(str(e))\n\
         gone = [wm.reminders.cancel('later'), wm.reminders.cancel('later')]\n\
         def render():\n    return ui.label(str([wm.reminders.list(), errs, gone, t]))",
    );
    assert_eq!(r["remindersChanged"], true);
    assert_eq!(
        r["ui"]["root"][0]["text"],
        "[[{'id': 'dentist', 'time': 1893574740, 'text': 'Dentist, earlier'}], ['that time has already passed', 'the time must be a number of seconds, as time.time() or wm.timestamp() gives', 'a reminder id can\\'t be empty'], [True, False], 1893574800]"
    );
    assert_eq!(parse(s.render())["remindersChanged"], false);
}

#[test]
fn timestamp_is_local_and_lenient() {
    let mut s = Session::with_clock(
        &json!({"id": "a", "version": "1", "mode": "panel"}).to_string(),
        Box::new(FixedClock { offset: 3600 }),
    )
    .unwrap();
    let r = load(
        &mut s,
        "a = wm.timestamp(2030, 1, 1, 0)\nb = wm.timestamp(2029, 12, 32, 1)\nc = wm.timestamp(2030, 13, 1)\nd = wm.timestamp(year=2030, month=2, day=0, hour=24, minute=-1, second=60)\n\
         e = []\ntry:\n    wm.timestamp(2030, 1, 1e7)\nexcept ValueError as x:\n    e.append(str(x))\n\
         def render():\n    return ui.label(str([a, b, c, d, e]))",
    );
    // UTC+1: local midnight is 23:00 UTC the day before.
    assert_eq!(
        r["ui"]["root"][0]["text"],
        "[1893452400, 1893456000, 1925031600, 1896130800, ['field \\'day\\' is out of range']]"
    );
}

// MARK: - ui and the codec

#[test]
fn ui_builders_are_plain_dicts() {
    let mut s = panel(&[]);
    let r = load(
        &mut s,
        "def render():\n    return ui.label(str([ui.label('t'), ui.output('o', id='x', mono=True), ui.button('b', 'B', enabled=False), ui.toggle('t', 'T', checked=True), ui.input('i', placeholder='p'), ui.date('d', 'D', 5, time=True, step=5), ui.spacer(), ui.divider(), ui.progress(), ui.row([ui.divider()]), ui.tabs('tb', ui.page('P', ui.divider()))]))",
    );
    assert_eq!(
        r["ui"]["root"][0]["text"],
        "[{'type': 'label', 'text': 't', 'style': None}, {'type': 'output', 'id': 'x', 'text': 'o', 'mono': True, 'insertable': True, 'copyable': True}, {'type': 'button', 'id': 'b', 'text': 'B', 'style': None, 'enabled': False}, {'type': 'toggle', 'id': 't', 'label': 'T', 'checked': True}, {'type': 'input', 'id': 'i', 'label': None, 'placeholder': 'p'}, {'type': 'date', 'id': 'd', 'label': 'D', 'value': 5, 'time': True, 'step': 5}, {'type': 'spacer', 'height': 8}, {'type': 'divider'}, {'type': 'progress'}, {'type': 'row', 'children': [{'type': 'divider'}]}, {'type': 'tabs', 'id': 'tb', 'pages': [{'title': 'P', 'children': [{'type': 'divider'}]}]}]"
    );
    let r = load(&mut s, "def render():\n    return ui.label()");
    assert_eq!(r["error"]["message"], "TypeError: label() missing required argument 'text'");
    let r = load(&mut s, "def render():\n    return ui.label('a', colour='red')");
    assert_eq!(r["error"]["message"], "TypeError: label() got an unexpected keyword argument 'colour'");
}

#[test]
fn every_widget_comes_out_fully_defaulted() {
    let mut s = panel(&[]);
    let r = load(
        &mut s,
        "def render():\n    return [{'type': 'output'}, {'type': 'button'}, {'type': 'toggle'}, {'type': 'input'}, {'type': 'date'}, {'type': 'spacer'}, {'type': 'label', 'text': 5, 'style': 'loud'}, {'type': 'column'}, {'type': 'output', 'text': True, 'id': 7}]",
    );
    assert_eq!(
        r["ui"]["root"],
        json!([
            {"type": "output", "id": "", "text": "", "mono": false, "insertable": true, "copyable": true},
            {"type": "button", "id": "", "text": "", "primary": false, "enabled": true},
            {"type": "toggle", "id": "", "label": "", "checked": false},
            {"type": "input", "id": "", "label": "", "placeholder": ""},
            {"type": "date", "id": "", "label": "", "value": null, "time": false, "step": 15},
            {"type": "spacer", "height": 8},
            {"type": "label", "text": "5", "style": "body"},
            {"type": "column", "children": []},
            {"type": "output", "id": "7", "text": "True", "mono": false, "insertable": true, "copyable": true},
        ])
    );
}

#[test]
fn the_codec_clamps_numbers() {
    let mut s = panel(&[]);
    let r = load(
        &mut s,
        "def render():\n    return [ui.date('a', step=0), ui.date('b', step=90, value=1e12), ui.date('c', value=-62135596801), ui.date('d', value=1709249405.9), ui.spacer(-5), ui.spacer(500), ui.date('e', value='soon')]",
    );
    let root = r["ui"]["root"].as_array().unwrap();
    assert_eq!(root[0]["step"], 1);
    assert_eq!(root[1]["step"], 60);
    assert_eq!(root[1]["value"], Json::Null);
    assert_eq!(root[2]["value"], Json::Null);
    assert_eq!(root[3]["value"], 1709249405);
    assert_eq!(root[4]["height"], 0);
    assert_eq!(root[5]["height"], 64);
    assert_eq!(root[6]["value"], Json::Null);
}

#[test]
fn codec_repairs() {
    let mut s = panel(&[]);
    let codes = |s: &mut Session, src: &str| -> Json { load(s, &format!("def render():\n{src}"))["ui"]["repairs"].clone() };
    assert_eq!(codes(&mut s, "    return [ui.label('a'), 5, None, 'text']"), json!([{"code": "NOT_A_WIDGET", "arg": null}]));
    assert_eq!(
        codes(&mut s, "    return [{'text': 'x'}, {'type': 'slider_with_a_very_long_name_indeed'}, {'type': 'slider_with_a_very_long_name_indeed'}]"),
        json!([{"code": "NO_TYPE", "arg": null}, {"code": "UNKNOWN_TYPE", "arg": "slider_with_a_very_long_"}])
    );
    assert_eq!(
        codes(&mut s, "    w = ui.label('deep')\n    for _ in range(13):\n        w = ui.column(w)\n    return w"),
        json!([{"code": "TOO_DEEP", "arg": null}])
    );
    assert_eq!(codes(&mut s, "    return [ui.divider() for _ in range(300)]"), json!([{"code": "TOO_MANY_WIDGETS", "arg": "256"}]));
    assert_eq!(
        codes(&mut s, "    return [ui.tabs('a'), {'type': 'tabs', 'pages': 3}, ui.tabs('b', [1, 2])]"),
        json!([{"code": "TABS_NO_PAGES", "arg": null}])
    );
    assert_eq!(
        codes(&mut s, "    return ui.tabs('t', [ui.page(str(i)) for i in range(10)])"),
        json!([{"code": "TOO_MANY_TABS", "arg": "8"}])
    );
    assert_eq!(
        codes(&mut s, "    return [ui.label('x' * 3000)] + [ui.label('y' * 2048) for _ in range(40)]"),
        json!([{"code": "TEXT_SHORTENED", "arg": null}, {"code": "TEXT_BUDGET", "arg": null}])
    );
    let many = load(&mut s, "def render():\n    return [{'type': 't' + str(i)} for i in range(20)]");
    assert_eq!(8, many["ui"]["repairs"].as_array().unwrap().len());
    // A bare widget, and a dict without a type at the top, which is no children.
    assert_eq!(load(&mut s, "def render():\n    return ui.divider()")["ui"]["root"], json!([{"type": "divider"}]));
    assert_eq!(load(&mut s, "def render():\n    return {'a': 1}")["ui"], json!({"root": [], "repairs": []}));
    // A tree that holds itself stops at the depth cap.
    let r = load(&mut s, "def render():\n    c = ui.column()\n    c['children'].append(c)\n    return c");
    assert_eq!(r["ui"]["repairs"], json!([{"code": "TOO_DEEP", "arg": null}]));
}

#[test]
fn text_caps_count_utf16_units() {
    let mut s = panel(&[]);
    let r = load(&mut s, "def render():\n    return ui.label('😀' * 1500)");
    // 2,048 units hold 1,024 emoji.
    assert_eq!(r["ui"]["root"][0]["text"].as_str().unwrap().chars().count(), 1024);
    assert_eq!(r["ui"]["repairs"], json!([{"code": "TEXT_SHORTENED", "arg": null}]));
}
