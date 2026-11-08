//! The widget-tree codec: whatever a plugin's `render()` returned, validated
//! and repaired into a finished tree (port of `PluginUiCodec.kt` and the
//! shapes of `PluginWidget.kt`).
//!
//! It repairs rather than refuses: dropping the one node that made no sense
//! and reporting it beats an empty panel. Every cap exists because the
//! alternative is the keyboard hanging or growing without bound while drawing
//! someone else's UI.
//!
//! # Output JSON
//!
//! ```text
//! { "root": [widget, ...],
//!   "repairs": [ {"code": "UNKNOWN_TYPE", "arg": "slider"}, {"code": "TOO_DEEP", "arg": null}, ... ] }
//! ```
//!
//! Every widget has every key, already defaulted:
//!
//! ```text
//! {"type":"column","children":[...]}            {"type":"row","children":[...]}
//! {"type":"label","text":s,"style":"title"|"body"|"caption"}
//! {"type":"output","id":s,"text":s,"mono":b,"insertable":b,"copyable":b}
//! {"type":"button","id":s,"text":s,"primary":b,"enabled":b}
//! {"type":"toggle","id":s,"label":s,"checked":b}
//! {"type":"input","id":s,"label":s,"placeholder":s}
//! {"type":"date","id":s,"label":s,"value":int|null,"time":b,"step":int}
//! {"type":"spacer","height":int}   {"type":"divider"}   {"type":"progress"}
//! {"type":"tabs","id":s,"pages":[{"title":s,"children":[...]}]}
//! ```
//!
//! Repair codes are `PluginRepair`'s names: NOT_A_WIDGET, TOO_DEEP,
//! TOO_MANY_WIDGETS (arg "256"), NO_TYPE, UNKNOWN_TYPE (arg: the type, first
//! 24 characters), TABS_NO_PAGES, TOO_MANY_TABS (arg "8"), TEXT_BUDGET,
//! TEXT_SHORTENED. At most 8, deduplicated by (code, arg).
//!
//! Reading Python values: a widget is a dict with a `type` key; a dict
//! without one, or anything that is not a dict or list, is no children. A
//! missing key and `None` both mean "use the default". Text fields take a str,
//! or `str()` of an int, float or bool. Flags take Python truthiness. Numbers
//! (`value`, `step`, `height`) take an int or float, not a bool.

use serde_json::{json, Map, Value as Json};

use crate::interp::{take_utf16, utf16_len, HKey, Value};

/// Nodes in one tree.
pub const MAX_NODES: usize = 256;
/// Nesting depth, which also breaks any cycle a script builds.
pub const MAX_DEPTH: usize = 12;
/// UTF-16 units in any one piece of text.
pub const MAX_TEXT: usize = 2048;
/// UTF-16 units across the whole tree.
pub const MAX_TOTAL_TEXT: usize = 64 * 1024;
pub const MAX_TABS: usize = 8;
const MAX_REPAIRS: usize = 8;
const MAX_ID: usize = 64;
const MAX_SPACER: i64 = 64;
const DEFAULT_SPACER: i64 = 8;
const DEFAULT_STEP: i64 = 15;
const MAX_STEP: i64 = 60;
/// 0001-01-01 and 9999-12-31, UTC, in seconds.
const MIN_SECONDS: f64 = -62_135_596_800.0;
const MAX_SECONDS: f64 = 253_402_300_799.0;

/// Turn a `render()` result into the finished tree.
pub fn encode(value: &Value) -> Json {
    let mut walk = Walk { repairs: Vec::new(), nodes: 0, total_text: 0 };
    let root = walk.children(value, 0);
    let repairs: Vec<Json> = walk.repairs.into_iter().map(|(code, arg)| json!({"code": code, "arg": arg})).collect();
    json!({"root": root, "repairs": repairs})
}

struct Walk {
    repairs: Vec<(&'static str, Option<String>)>,
    nodes: usize,
    total_text: usize,
}

fn field(d: &Value, key: &str) -> Value {
    match d {
        Value::Dict(m) => m.borrow().get(&HKey::str(key)).cloned().unwrap_or(Value::None),
        _ => Value::None,
    }
}

fn flag(d: &Value, key: &str, default: bool) -> bool {
    match field(d, key) {
        Value::None => default,
        v => v.truthy(),
    }
}

fn number(v: &Value) -> Option<f64> {
    match v {
        Value::Int(i) => Some(*i as f64),
        Value::Float(f) => Some(*f),
        _ => None,
    }
}

fn scalar_text(v: &Value) -> Option<String> {
    match v {
        Value::Str(s) => Some(s.to_string()),
        Value::Int(_) | Value::Float(_) | Value::Bool(_) => Some(v.to_str()),
        _ => None,
    }
}

impl Walk {
    fn repair(&mut self, code: &'static str, arg: Option<String>) {
        if self.repairs.len() < MAX_REPAIRS && !self.repairs.iter().any(|(c, a)| *c == code && *a == arg) {
            self.repairs.push((code, arg));
        }
    }

    /// A list of widgets, or a single widget dict, since `return ui.label(...)`
    /// is the obvious thing to write.
    fn children(&mut self, value: &Value, depth: usize) -> Vec<Json> {
        match value {
            Value::Dict(_) => {
                if field(value, "type").is_none() {
                    return Vec::new();
                }
                self.widget(value, depth).into_iter().collect()
            }
            Value::List(_) | Value::Tuple(_) => {
                let items = value.as_items().unwrap_or_default();
                let mut out = Vec::new();
                for child in items {
                    match child {
                        Value::Dict(_) => out.extend(self.widget(&child, depth)),
                        Value::None => {}
                        _ => self.repair("NOT_A_WIDGET", None),
                    }
                }
                out
            }
            _ => Vec::new(),
        }
    }

    fn widget(&mut self, d: &Value, depth: usize) -> Option<Json> {
        if depth > MAX_DEPTH {
            self.repair("TOO_DEEP", None);
            return None;
        }
        self.nodes += 1;
        if self.nodes > MAX_NODES {
            self.repair("TOO_MANY_WIDGETS", Some(MAX_NODES.to_string()));
            return None;
        }
        let ty = match field(d, "type") {
            Value::Str(s) => s.to_string(),
            _ => String::new(),
        };
        Some(match ty.as_str() {
            "column" | "row" => json!({"type": ty, "children": self.children(&field(d, "children"), depth + 1)}),
            "label" => {
                let style = match field(d, "style").as_str() {
                    Some("title") => "title",
                    Some("caption") => "caption",
                    _ => "body",
                };
                json!({"type": "label", "text": self.text(d, "text"), "style": style})
            }
            "output" => json!({
                "type": "output", "id": id(d), "text": self.text(d, "text"), "mono": flag(d, "mono", false),
                "insertable": flag(d, "insertable", true), "copyable": flag(d, "copyable", true),
            }),
            "button" => json!({
                "type": "button", "id": id(d), "text": self.text(d, "text"),
                "primary": field(d, "style").as_str() == Some("primary"), "enabled": flag(d, "enabled", true),
            }),
            "toggle" => json!({"type": "toggle", "id": id(d), "label": self.text(d, "label"), "checked": flag(d, "checked", false)}),
            "input" => json!({"type": "input", "id": id(d), "label": self.text(d, "label"), "placeholder": self.text(d, "placeholder")}),
            "date" => {
                let step = number(&field(d, "step")).map(|s| s as i64).unwrap_or(DEFAULT_STEP).clamp(1, MAX_STEP);
                json!({
                    "type": "date", "id": id(d), "label": self.text(d, "label"), "value": seconds(&field(d, "value")),
                    "time": flag(d, "time", false), "step": step,
                })
            }
            "spacer" => {
                let h = number(&field(d, "height")).map(|s| s as i64).unwrap_or(DEFAULT_SPACER).clamp(0, MAX_SPACER);
                json!({"type": "spacer", "height": h})
            }
            "divider" => json!({"type": "divider"}),
            "progress" => json!({"type": "progress"}),
            "tabs" => return self.tabs(d, depth),
            _ => {
                if ty.is_empty() {
                    self.repair("NO_TYPE", None);
                } else {
                    self.repair("UNKNOWN_TYPE", Some(ty.chars().take(24).collect()));
                }
                return None;
            }
        })
    }

    fn tabs(&mut self, d: &Value, depth: usize) -> Option<Json> {
        let pages_value = field(d, "pages");
        let Some(pages) = pages_value.as_items() else {
            self.repair("TABS_NO_PAGES", None);
            return None;
        };
        let mut out = Vec::new();
        for page in pages.iter().take(MAX_TABS) {
            if let Value::Dict(_) = page {
                let mut p = Map::new();
                // Left empty when the plugin gave no title: the panel names
                // the tab after its position, in the language the user reads.
                p.insert("title".into(), Json::String(self.text(page, "title")));
                p.insert("children".into(), Json::Array(self.children(&field(page, "children"), depth + 1)));
                out.push(Json::Object(p));
            }
        }
        if pages.len() > MAX_TABS {
            self.repair("TOO_MANY_TABS", Some(MAX_TABS.to_string()));
        }
        if out.is_empty() {
            self.repair("TABS_NO_PAGES", None);
            return None;
        }
        Some(json!({"type": "tabs", "id": id(d), "pages": out}))
    }

    /// One text field, capped twice: once so a single node cannot be a novel,
    /// and once against the whole-tree budget.
    fn text(&mut self, d: &Value, key: &str) -> String {
        let Some(value) = scalar_text(&field(d, key)) else { return String::new() };
        let remaining = MAX_TOTAL_TEXT.saturating_sub(self.total_text);
        if remaining == 0 {
            self.repair("TEXT_BUDGET", None);
            return String::new();
        }
        let capped = take_utf16(&value, MAX_TEXT.min(remaining));
        if capped.len() < value.len() {
            self.repair("TEXT_SHORTENED", None);
        }
        self.total_text += utf16_len(capped);
        capped.to_string()
    }
}

fn id(d: &Value) -> String {
    match field(d, "id") {
        Value::Str(s) => take_utf16(&s, MAX_ID).to_string(),
        v @ (Value::Int(_) | Value::Float(_)) => take_utf16(&v.to_str(), MAX_ID).to_string(),
        _ => String::new(),
    }
}

/// Whole seconds, or null for none: anything that is not a number, or is
/// outside the years 1 to 9999, is no date rather than one the formatter
/// cannot draw.
fn seconds(v: &Value) -> Json {
    match number(v) {
        Some(n) if n.is_finite() && (MIN_SECONDS..=MAX_SECONDS).contains(&n) => json!(n.floor() as i64),
        _ => Json::Null,
    }
}
