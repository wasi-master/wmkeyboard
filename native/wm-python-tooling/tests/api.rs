//! The API model against §1 of the plugin plan, and the JSON entry points.

mod common;

use common::*;
use serde_json::Value;
use wm_python_tooling::api::{self, ApiKind};
use wm_python_tooling::api_json;

/// §1, written out by hand: every `wm` and `ui` name a plugin has.
const SPEC: &[&str] = &[
    "wm", "wm.api_version", "wm.plugin_id", "wm.plugin_version", "wm.log", "wm.timestamp", "wm.ui", "wm.ui.set_input",
    "wm.storage", "wm.storage.get", "wm.storage.set", "wm.storage.remove", "wm.storage.keys", "wm.reminders",
    "wm.reminders.set", "wm.reminders.cancel", "wm.reminders.list", "ui", "ui.column", "ui.row", "ui.label",
    "ui.output", "ui.button", "ui.toggle", "ui.input", "ui.date", "ui.spacer", "ui.divider", "ui.progress", "ui.tabs",
    "ui.page",
];

#[test]
fn the_host_api_is_exactly_section_1() {
    let mut found: Vec<&str> = api::model().entries.iter().map(|e| e.path).filter(|p| *p == "wm" || *p == "ui" || p.starts_with("wm.") || p.starts_with("ui.")).collect();
    let mut want = SPEC.to_vec();
    found.sort();
    want.sort();
    assert_eq!(found, want);
    let sig = |p: &str| api::model().find(p).unwrap().signature.unwrap();
    assert_eq!(sig("wm.storage.get"), "get(key, default=None)");
    assert_eq!(sig("wm.timestamp"), "timestamp(year, month, day, hour=12, minute=0, second=0)");
    assert_eq!(sig("ui.output"), "output(text, id=None, mono=False, insertable=True, copyable=True)");
    assert_eq!(sig("ui.date"), "date(id, label=None, value=None, time=False, step=15)");
    assert_eq!(sig("ui.tabs"), "tabs(id, *pages)");
    assert_eq!(api::model().find("wm.storage.set").unwrap().permission, Some("storage"));
    assert_eq!(api::model().find("wm.reminders").unwrap().permission, Some("reminders"));
    // Every ui builder signature names the parameters its shape lists.
    for shape in &api::model().ui {
        let s = sig(&format!("ui.{}", shape.name));
        for p in &shape.params {
            assert!(s.contains(p.name), "{s} lacks {}", p.name);
        }
    }
}

#[test]
fn the_pymini_modules_and_builtins_are_all_there() {
    let modules: &[(&str, &[&str])] = &[
        ("math", &["sqrt", "floor", "ceil", "trunc", "fabs", "pow", "exp", "log", "log2", "log10", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "degrees", "radians", "hypot", "gcd", "lcm", "factorial", "isnan", "isinf", "isfinite", "copysign", "fmod", "comb", "prod", "pi", "e", "tau", "inf", "nan"]),
        ("random", &["random", "randint", "randrange", "choice", "choices", "shuffle", "sample", "uniform", "seed"]),
        ("time", &["time", "monotonic", "parts", "format"]),
        ("json", &["dumps", "loads"]),
        ("re", &["search", "match", "fullmatch", "findall", "finditer", "sub", "subn", "split", "escape", "I", "IGNORECASE", "M", "MULTILINE", "S", "DOTALL"]),
        ("sys", &["version"]),
    ];
    for (module, names) in modules {
        let mut found: Vec<&str> = api::model().children(module).iter().map(|e| e.name()).collect();
        let mut want = names.to_vec();
        found.sort();
        want.sort();
        assert_eq!(found, want, "{module}");
    }
    for b in ["len", "str", "repr", "print", "int", "float", "bool", "abs", "round", "min", "max", "sum", "range", "sorted", "reversed", "list", "dict", "set", "tuple", "enumerate", "zip", "ord", "chr", "any", "all", "type", "map", "filter", "divmod", "pow", "hex", "bin", "oct", "format", "isinstance", "callable", "bytes"] {
        assert_eq!(api::model().find(b).map(|e| e.kind), Some(ApiKind::Builtin), "{b}");
    }
    for m in ["str.encode", "bytes.fromhex", "bytes.hex", "bytes.decode", "list.append", "dict.items", "str.removeprefix"] {
        assert!(api::model().find(m).is_some(), "{m}");
    }
}

#[test]
fn every_entry_has_a_paragraph_and_every_callable_a_signature() {
    for e in &api::model().entries {
        assert!(e.doc.len() >= 8, "{}", e.path);
        if matches!(e.kind, ApiKind::Function | ApiKind::Method | ApiKind::Builtin) {
            assert!(e.signature.is_some(), "{}", e.path);
        }
    }
}

#[test]
fn json_shapes() {
    let v: Value = serde_json::from_str(&api_json::api_entries()).unwrap();
    assert_eq!(v["eventTypes"], serde_json::json!(["click", "toggle", "input_changed", "tab_selected", "date_changed"]));
    assert_eq!(v["hooks"], serde_json::json!(["render", "on_event", "transform"]));
    let get = v["entries"].as_array().unwrap().iter().find(|e| e["path"] == "wm.storage.get").unwrap();
    assert_eq!(get["parent"], "wm.storage");
    assert_eq!(get["name"], "get");
    assert_eq!(get["kind"], "function");
    assert_eq!(get["permission"], "storage");

    let source = "def render():\n    return ui.label(nope)\n";
    let v: Value = serde_json::from_str(&api_json::analyse(source, r#"{"panel":true,"verdict":{"message":"m","kind":"K","line":2}}"#)).unwrap();
    let d = v["diagnostics"].as_array().unwrap().iter().find(|d| d["code"] == "undefined_name").unwrap();
    assert_eq!(d["code"], "undefined_name");
    assert_eq!(d["severity"], "warning");
    assert_eq!(d["args"], serde_json::json!(["nope"]));
    assert_eq!(v["verdictUsed"], true);
    assert_eq!(v["outline"][0]["name"], "render");
    assert_eq!(v["outline"][0]["nameStart"], 4);
    assert_eq!(v["folds"][0], serde_json::json!([0, 39]));
    let v: Value = serde_json::from_str(&api_json::analyse(source, "{}")).unwrap();
    assert_eq!(v["verdictUsed"], false);
    let v: Value = serde_json::from_str(&api_json::analyse(source, "not json")).unwrap();
    assert!(v["diagnostics"].is_array());

    let v: Value = serde_json::from_str(&api_json::complete("wm.lo", 5, false, "{}")).unwrap();
    assert_eq!((v["replaceStart"].as_u64(), v["replaceEnd"].as_u64()), (Some(3), Some(5)));
    let item = &v["items"][0];
    assert_eq!((item["label"].as_str(), item["kind"].as_str(), item["insert"].as_str(), item["caret"].as_u64()), (Some("log"), Some("function"), Some("log()"), Some(4)));
    assert_eq!(item["docPath"], "wm.log");
    assert_eq!(api_json::complete("x", -1, true, "{}"), "null");
    assert_eq!(api_json::complete("# x", 3, true, "{}"), "null");

    assert_eq!(api_json::definition("a = 1\nb = a\n", 10), r#"{"start":0,"end":1}"#);
    assert_eq!(api_json::definition("a = 1\n", 99), "null");
    assert_eq!(api_json::references("a = 1\nb = a\n", 10), "[[0,1],[10,11]]");
    assert_eq!(api_json::references("", 0), "[]");
    assert_eq!(api_json::rename("a = 1\nb = a\n", 0, "c"), r#"{"spans":[[0,1],[10,11]],"problem":null}"#);
    assert_eq!(api_json::rename("a = 1\nb = a\n", 0, "b"), r#"{"spans":[[0,1],[10,11]],"problem":"taken"}"#);
    assert_eq!(api_json::rename("a = 1\n", 2, "c"), "null");
    let v: Value = serde_json::from_str(&api_json::api_at("wm.log('x')", 4)).unwrap();
    assert_eq!((v["path"].as_str(), v["kind"].as_str()), (Some("wm.log"), Some("function")));
    assert_eq!(api_json::api_at("x = 1", 0), "null");
    assert_eq!(api_json::format("x=1"), "x = 1\n");
    assert!(api_json::in_inert("x = 'abc", 7));
    assert!(api_json::in_inert("x = 1  # note", 12));
    assert!(!api_json::in_inert("x = 'abc'", 9));
    assert!(!api_json::in_inert("x", -3));
    assert!(api_json::opens_block("if x == ':':  # yes"));
    assert!(!api_json::opens_block("d = {'a':"));
    assert!(!api_json::opens_block("x = 1  # :"));
    let codes: Value = serde_json::from_str(&api_json::diagnostic_codes()).unwrap();
    assert!(codes.as_array().unwrap().len() > 50);
}

#[test]
fn packed_tokens_carry_kind_flags_and_name_styles() {
    use wm_python_tooling::highlight::{kind, style};
    let source = "import json\nTOTAL = 1\n\n\ndef f(a):\n    return len(a) + json.dumps(a) + wm.log(a).x + g(f'{a}') + 'open";
    let packed = api_json::tokens(source);
    assert_eq!(packed.len() % 3, 0);
    let units = source.encode_utf16().count() as i32;
    assert_eq!(packed[1], 0);
    assert_eq!(packed[packed.len() - 1], units);
    let style_of = |text: &str, nth: usize| -> (i32, i32, bool) {
        let mut seen = 0;
        for chunk in packed.chunks(3) {
            if utf16_slice(source, chunk[1] as u32, chunk[2] as u32) == text {
                if seen == nth {
                    return (chunk[0] & 0xff, chunk[0] >> 12, chunk[0] & 0x100 != 0);
                }
                seen += 1;
            }
        }
        panic!("no {text}");
    };
    assert_eq!(style_of("len", 0).1, style::BUILTIN);
    assert_eq!(style_of("json", 1).1, style::API);
    assert_eq!(style_of("dumps", 0).1, style::API);
    assert_eq!(style_of("wm", 0).1, style::API);
    assert_eq!(style_of("log", 0).1, style::API);
    assert_eq!(style_of("x", 0).1, style::ATTRIBUTE);
    assert_eq!(style_of("f", 0).1, style::FUNCTION);
    assert_eq!(style_of("g", 0).1, style::FUNCTION);
    assert_eq!(style_of("a", 0).1, style::PARAMETER);
    assert_eq!(style_of("a", 2).1, style::PARAMETER);
    assert_eq!(style_of("TOTAL", 0).1, style::CONSTANT);
    assert_eq!(style_of("f'{a}'", 0).0, kind::FSTRING);
    assert_eq!(style_of("def", 0).0, kind::KEYWORD);
    let last = style_of("'open", 0);
    assert_eq!((last.0, last.2), (kind::STRING, true));
}
