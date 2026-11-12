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
//! # API 5
//!
//! [`encode_v5`] reads the `wm.ui` widgets (instances whose attributes are
//! the fields; dicts still work), allows 1,024 nodes, 128K of text, and adds:
//!
//! ```text
//! column/row: + "gap":int|null, "align":s|null; row + "weights":[num]|null
//! {"type":"label",…, "color":role|null}   style also "headline"
//! {"type":"button",…, "icon":s|null, "color":role|null, "compact":b}
//! {"type":"input",…, "multiline":b, "kind":"text"|"number"|"email"|"url"|"password"|"phone", "live":b, "submit":b}
//! {"type":"grid","columns":1..6,"children":[...]}
//! {"type":"card","id":s,"title":s,"clickable":b,"children":[...]}
//! {"type":"section","id":s,"title":s,"collapsible":b,"open":b,"children":[...]}
//! {"type":"scroll","height":int|null,"children":[...]}
//! {"type":"markdown","text":s}
//! {"type":"empty","id":s,"text":s,"icon":s|null,"action":s}
//! {"type":"checkbox","id":s,"label":s,"checked":b}
//! {"type":"search","id":s,"placeholder":s,"live":b}
//! {"type":"slider"|"stepper","id":s,"label":s,"value":num,"min":num,"max":num,"step":num}
//! {"type":"select","id":s,"label":s,"options":[{"value":s,"label":s}],"value":s|null,"style":"dropdown"|"segmented"}
//! {"type":"chips","id":s,"options":[...],"value":[s],"multi":b}
//! {"type":"keys","id":s,"rows":[[s]]}
//! {"type":"list","id":s,"items":[item],"empty":s,"selectable":b}
//! {"type":"item","id":s,"title":s,"subtitle":s,"icon":s|null,"trailing":s,"clickable":b,"actions":[s],"selected":b}
//! {"type":"image","id":s,"src":s,"alt":s,"height":int|null,"insertable":b,"clickable":b}
//! {"type":"icon","name":s,"size":int|null,"color":role|null}
//! {"type":"qr","text":s,"size":int|null}
//! {"type":"stat","id":s,"label":s,"value":s,"caption":s}
//! {"type":"ring","id":s,"value":0..1,"label":s,"caption":s}
//! {"type":"progress","value":0..1|null}
//! {"type":"chart","id":s,"kind":"line"|"bar"|"spark","series":[{"name":s,"values":[num]}],"labels":[s],"height":int|null}
//! {"type":"canvas","id":s,"width":num,"height":num,"shapes":[shape],"tappable":b,"draggable":b}
//! ```
//!
//! A role is one of primary, secondary, muted, danger, success, warning,
//! accent; anything else is null. An image `src` is `assets/<name>`, an
//! `https://` address, or `data:<base64>` up to 512 KB; anything else drops
//! the image (BAD_IMAGE). Shapes are `{"shape":"rect","x","y","w","h","radius","fill","color"}`,
//! `{"shape":"circle","x","y","r","fill","color"}`, `{"shape":"line","x","y","x2","y2","width","color"}`
//! and `{"shape":"text","x","y","text","size","color"}`, at most 2,000.
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

/// API 5: nodes in one tree.
pub const MAX_NODES_V5: usize = 1024;
/// API 5: UTF-16 units across the whole tree.
pub const MAX_TOTAL_TEXT_V5: usize = 128 * 1024;
/// API 5: base64 characters in one `data:` image.
pub const MAX_IMAGE_DATA: usize = 700_000;
pub const MAX_SHAPES: usize = 2000;
pub const MAX_SERIES: usize = 8;
pub const MAX_POINTS: usize = 500;
pub const MAX_OPTIONS: usize = 64;
pub const COLOR_ROLES: &[&str] = &["primary", "secondary", "muted", "danger", "success", "warning", "accent"];

/// Turn a `render()` result into the finished tree.
pub fn encode(value: &Value) -> Json {
    run(value, false)
}

/// Turn an API 5 render (the `wm.ui` widgets) into the finished tree.
pub fn encode_v5(value: &Value) -> Json {
    run(value, true)
}

fn run(value: &Value, v5: bool) -> Json {
    let mut walk = Walk { repairs: Vec::new(), nodes: 0, total_text: 0, v5 };
    let root = walk.children(value, 0);
    let repairs: Vec<Json> = walk.repairs.into_iter().map(|(code, arg)| json!({"code": code, "arg": arg})).collect();
    json!({"root": root, "repairs": repairs})
}

struct Walk {
    repairs: Vec<(&'static str, Option<String>)>,
    nodes: usize,
    total_text: usize,
    v5: bool,
}

fn field(d: &Value, key: &str) -> Value {
    match d {
        Value::Dict(m) => m.borrow().get(&HKey::str(key)).cloned().unwrap_or(Value::None),
        Value::Instance(i) => i.attrs.borrow().get(key).cloned().unwrap_or(Value::None),
        _ => Value::None,
    }
}

fn is_widget(v: &Value) -> bool {
    matches!(v, Value::Dict(_) | Value::Instance(_))
}

fn has(d: &Value, key: &str) -> bool {
    !field(d, key).is_none()
}

fn role(d: &Value, key: &str) -> Json {
    match field(d, key) {
        Value::Str(s) if COLOR_ROLES.contains(&s.as_ref()) => Json::String(s.to_string()),
        _ => Json::Null,
    }
}

fn opt_int(d: &Value, key: &str, min: i64, max: i64) -> Json {
    match number(&field(d, key)) {
        Some(n) if n.is_finite() => json!((n as i64).clamp(min, max)),
        _ => Json::Null,
    }
}

fn num(d: &Value, key: &str, default: f64) -> f64 {
    number(&field(d, key)).filter(|n| n.is_finite()).unwrap_or(default)
}

fn short(v: &Value, max: usize) -> String {
    scalar_text(v).map(|s| take_utf16(&s, max).to_string()).unwrap_or_default()
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
            Value::Dict(_) | Value::Instance(_) if self.v5 || matches!(value, Value::Dict(_)) => {
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
                        Value::Instance(_) if self.v5 => out.extend(self.widget(&child, depth)),
                        Value::None => {}
                        _ => self.repair("NOT_A_WIDGET", None),
                    }
                }
                out
            }
            _ => Vec::new(),
        }
    }

    fn max_nodes(&self) -> usize {
        if self.v5 {
            MAX_NODES_V5
        } else {
            MAX_NODES
        }
    }

    fn widget(&mut self, d: &Value, depth: usize) -> Option<Json> {
        if depth > MAX_DEPTH {
            self.repair("TOO_DEEP", None);
            return None;
        }
        self.nodes += 1;
        if self.nodes > self.max_nodes() {
            self.repair("TOO_MANY_WIDGETS", Some(self.max_nodes().to_string()));
            return None;
        }
        let ty = match field(d, "type") {
            Value::Str(s) => s.to_string(),
            _ => String::new(),
        };
        if self.v5 {
            if let Some(w) = self.widget_v5(&ty, d, depth) {
                return w;
            }
        }
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
            if is_widget(page) {
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
        self.budgeted(&value)
    }

    fn budgeted(&mut self, value: &str) -> String {
        let total = if self.v5 { MAX_TOTAL_TEXT_V5 } else { MAX_TOTAL_TEXT };
        let remaining = total.saturating_sub(self.total_text);
        if remaining == 0 {
            self.repair("TEXT_BUDGET", None);
            return String::new();
        }
        let capped = take_utf16(value, MAX_TEXT.min(remaining));
        if capped.len() < value.len() {
            self.repair("TEXT_SHORTENED", None);
        }
        self.total_text += utf16_len(capped);
        capped.to_string()
    }

    fn kids(&mut self, d: &Value, depth: usize) -> Json {
        Json::Array(self.children(&field(d, "children"), depth + 1))
    }

    fn options(&mut self, d: &Value) -> Vec<Json> {
        let items = field(d, "options").as_items().unwrap_or_default();
        if items.len() > MAX_OPTIONS {
            self.repair("TOO_MANY_OPTIONS", Some(MAX_OPTIONS.to_string()));
        }
        let mut out = Vec::new();
        for o in items.iter().take(MAX_OPTIONS) {
            let (value, label) = match o {
                Value::Dict(_) => (short(&field(o, "value"), MAX_ID), self.text(o, "label")),
                other => {
                    let t = short(other, MAX_ID);
                    let l = self.budgeted(&t);
                    (t, l)
                }
            };
            out.push(json!({"value": value, "label": label}));
        }
        out
    }

    /// The API 5 widgets and the API 5 form of the old ones. None when [ty]
    /// is not one of them (the API 4 table then decides); Some(None) drops
    /// the node.
    fn widget_v5(&mut self, ty: &str, d: &Value, depth: usize) -> Option<Option<Json>> {
        let w = match ty {
            "column" | "row" => {
                let mut o = json!({"type": ty, "children": self.kids(d, depth), "gap": opt_int(d, "gap", 0, 64), "align": short(&field(d, "align"), 16)});
                if ty == "row" {
                    let weights = field(d, "weights").as_items().map(|w| w.iter().map(|x| number(x).filter(|n| n.is_finite() && *n > 0.0).unwrap_or(1.0)).collect::<Vec<_>>());
                    o["weights"] = json!(weights);
                }
                o
            }
            "label" => {
                let style = match field(d, "style").as_str() {
                    Some(s @ ("title" | "caption" | "headline")) => s.to_string(),
                    _ => "body".to_string(),
                };
                json!({"type": "label", "text": self.text(d, "text"), "style": style, "color": role(d, "color")})
            }
            "button" => json!({
                "type": "button", "id": id(d), "text": self.text(d, "text"),
                "primary": field(d, "style").as_str() == Some("primary"), "enabled": flag(d, "enabled", true),
                "icon": icon_name(d, "icon"), "color": role(d, "color"), "compact": flag(d, "compact", false),
            }),
            "input" => {
                let kind = match field(d, "kind").as_str() {
                    Some(k @ ("number" | "email" | "url" | "password" | "phone")) => k.to_string(),
                    _ => "text".to_string(),
                };
                json!({
                    "type": "input", "id": id(d), "label": self.text(d, "label"), "placeholder": self.text(d, "placeholder"),
                    "multiline": flag(d, "multiline", false), "kind": kind, "live": has(d, "on_change"), "submit": has(d, "on_submit"),
                })
            }
            "grid" => json!({"type": "grid", "columns": num(d, "columns", 3.0).clamp(1.0, 6.0) as i64, "children": self.kids(d, depth)}),
            "card" => json!({"type": "card", "id": id(d), "title": self.text(d, "title"), "clickable": has(d, "on_click"), "children": self.kids(d, depth)}),
            "section" => json!({
                "type": "section", "id": id(d), "title": self.text(d, "title"), "collapsible": flag(d, "collapsible", false),
                "open": flag(d, "open", true), "children": self.kids(d, depth),
            }),
            "scroll" => json!({"type": "scroll", "height": opt_int(d, "height", 40, 2000), "children": self.kids(d, depth)}),
            "markdown" => json!({"type": "markdown", "text": self.text(d, "text")}),
            "empty" => json!({
                "type": "empty", "id": id(d), "text": self.text(d, "text"), "icon": icon_name(d, "icon"),
                "action": if has(d, "on_click") { self.text(d, "action") } else { String::new() },
            }),
            "checkbox" => json!({"type": "checkbox", "id": id(d), "label": self.text(d, "label"), "checked": flag(d, "checked", false)}),
            "search" => json!({"type": "search", "id": id(d), "placeholder": self.text(d, "placeholder"), "live": has(d, "on_change")}),
            "slider" | "stepper" => {
                let min = num(d, "min", 0.0);
                let max = num(d, "max", if ty == "slider" { 100.0 } else { 1e9 }).max(min);
                let min = if ty == "stepper" && field(d, "min").is_none() { -1e9 } else { min };
                let step = num(d, "step", 1.0).abs().max(1e-9);
                json!({
                    "type": ty, "id": id(d), "label": self.text(d, "label"), "value": num(d, "value", min).clamp(min, max),
                    "min": min, "max": max, "step": step,
                })
            }
            "select" => {
                let style = if field(d, "style").as_str() == Some("segmented") { "segmented" } else { "dropdown" };
                let value = match field(d, "value") {
                    Value::None => Json::Null,
                    v => Json::String(short(&v, MAX_ID)),
                };
                json!({"type": "select", "id": id(d), "label": self.text(d, "label"), "options": self.options(d), "value": value, "style": style})
            }
            "chips" => {
                let chosen: Vec<String> = field(d, "value").as_items().unwrap_or_default().iter().map(|v| short(v, MAX_ID)).collect();
                json!({"type": "chips", "id": id(d), "options": self.options(d), "value": chosen, "multi": flag(d, "multi", false)})
            }
            "keys" => {
                let mut rows = Vec::new();
                for r in field(d, "rows").as_items().unwrap_or_default().iter().take(8) {
                    let keys: Vec<String> = r.as_items().unwrap_or_default().iter().take(12).map(|k| short(k, 16)).collect();
                    rows.push(json!(keys));
                }
                json!({"type": "keys", "id": id(d), "rows": rows})
            }
            "list" => {
                let mut items = Vec::new();
                for it in field(d, "items").as_items().unwrap_or_default() {
                    if is_widget(&it) {
                        items.extend(self.widget(&it, depth + 1));
                    }
                }
                json!({"type": "list", "id": id(d), "items": items, "empty": self.text(d, "empty"), "selectable": has(d, "on_select")})
            }
            "item" => {
                let actions: Vec<String> = field(d, "actions")
                    .as_items()
                    .unwrap_or_default()
                    .iter()
                    .take(4)
                    .map(|a| match a {
                        Value::Dict(_) => short(&field(a, "label"), 32),
                        other => short(other, 32),
                    })
                    .collect();
                json!({
                    "type": "item", "id": id(d), "title": self.text(d, "title"), "subtitle": self.text(d, "subtitle"),
                    "icon": icon_name(d, "icon"), "trailing": self.text(d, "trailing"), "clickable": has(d, "on_click"),
                    "actions": actions, "selected": flag(d, "selected", false),
                })
            }
            "image" => {
                let src = match field(d, "src") {
                    Value::Str(s) => s.to_string(),
                    _ => String::new(),
                };
                if !image_src_ok(&src) {
                    self.repair("BAD_IMAGE", None);
                    return Some(None);
                }
                json!({
                    "type": "image", "id": id(d), "src": src, "alt": self.text(d, "alt"), "height": opt_int(d, "height", 16, 1000),
                    "insertable": flag(d, "insertable", false), "clickable": has(d, "on_click"),
                })
            }
            "icon" => json!({"type": "icon", "name": icon_name(d, "name"), "size": opt_int(d, "size", 8, 96), "color": role(d, "color")}),
            "qr" => json!({"type": "qr", "text": self.text(d, "text"), "size": opt_int(d, "size", 48, 600)}),
            "stat" => json!({"type": "stat", "id": id(d), "label": self.text(d, "label"), "value": self.text(d, "value"), "caption": self.text(d, "caption")}),
            "ring" => json!({
                "type": "ring", "id": id(d), "value": num(d, "value", 0.0).clamp(0.0, 1.0), "label": self.text(d, "label"), "caption": self.text(d, "caption"),
            }),
            "progress" => {
                let value = match number(&field(d, "value")) {
                    Some(n) if n.is_finite() => json!(n.clamp(0.0, 1.0)),
                    _ => Json::Null,
                };
                json!({"type": "progress", "value": value})
            }
            "chart" => self.chart(d),
            "canvas" => self.canvas(d),
            _ => return None,
        };
        Some(Some(w))
    }

    fn chart(&mut self, d: &Value) -> Json {
        let kind = match field(d, "kind").as_str() {
            Some(k @ ("bar" | "spark")) => k.to_string(),
            _ => "line".to_string(),
        };
        let all = field(d, "series").as_items().unwrap_or_default();
        if all.len() > MAX_SERIES {
            self.repair("TOO_MANY_SERIES", Some(MAX_SERIES.to_string()));
        }
        let mut series = Vec::new();
        for s in all.iter().take(MAX_SERIES) {
            let values = field(s, "values").as_items().unwrap_or_default();
            if values.len() > MAX_POINTS {
                self.repair("TOO_MANY_POINTS", Some(MAX_POINTS.to_string()));
            }
            let values: Vec<f64> = values.iter().take(MAX_POINTS).map(|v| number(v).filter(|n| n.is_finite()).unwrap_or(0.0)).collect();
            series.push(json!({"name": self.text(s, "name"), "values": values}));
        }
        let labels: Vec<String> = field(d, "labels").as_items().unwrap_or_default().iter().take(MAX_POINTS).map(|l| short(l, 32)).collect();
        json!({"type": "chart", "id": id(d), "kind": kind, "series": series, "labels": labels, "height": opt_int(d, "height", 40, 600)})
    }

    fn canvas(&mut self, d: &Value) -> Json {
        let all = field(d, "shapes").as_items().unwrap_or_default();
        if all.len() > MAX_SHAPES {
            self.repair("TOO_MANY_SHAPES", Some(MAX_SHAPES.to_string()));
        }
        let mut shapes = Vec::new();
        for sh in all.iter().take(MAX_SHAPES) {
            let n = |k: &str| num(sh, k, 0.0);
            let color = match role(sh, "color") {
                Json::Null => Json::String("primary".into()),
                c => c,
            };
            let shape = match field(sh, "shape").as_str() {
                Some("rect") => json!({"shape": "rect", "x": n("x"), "y": n("y"), "w": n("w"), "h": n("h"), "radius": n("radius"), "fill": flag(sh, "fill", true), "color": color}),
                Some("circle") => json!({"shape": "circle", "x": n("x"), "y": n("y"), "r": n("r"), "fill": flag(sh, "fill", true), "color": color}),
                Some("line") => json!({"shape": "line", "x": n("x"), "y": n("y"), "x2": n("x2"), "y2": n("y2"), "width": num(sh, "width", 1.0), "color": color}),
                Some("text") => {
                    let text = short(&field(sh, "text"), 64);
                    json!({"shape": "text", "x": n("x"), "y": n("y"), "text": self.budgeted(&text), "size": num(sh, "size", 12.0).clamp(4.0, 96.0), "color": color})
                }
                _ => {
                    self.repair("BAD_SHAPE", None);
                    continue;
                }
            };
            shapes.push(shape);
        }
        json!({
            "type": "canvas", "id": id(d), "width": num(d, "width", 100.0).clamp(1.0, 10_000.0), "height": num(d, "height", 100.0).clamp(1.0, 10_000.0),
            "shapes": shapes, "tappable": has(d, "on_tap"), "draggable": has(d, "on_drag"),
        })
    }
}

/// A Material Symbols name: lowercase letters, digits and underscores.
fn icon_name(d: &Value, key: &str) -> Json {
    match field(d, key) {
        Value::Str(s) if !s.is_empty() && s.len() <= 48 && s.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_') => {
            Json::String(s.to_string())
        }
        _ => Json::Null,
    }
}

/// `assets/<name>` inside the plugin, an https address, or inline base64.
fn image_src_ok(src: &str) -> bool {
    if let Some(name) = src.strip_prefix("assets/") {
        return !name.is_empty() && name.len() <= 128 && !name.contains("..") && !name.starts_with('/');
    }
    if let Some(rest) = src.strip_prefix("https://") {
        return !rest.is_empty() && src.len() <= 2048 && !src.chars().any(char::is_whitespace);
    }
    if let Some(data) = src.strip_prefix("data:") {
        return !data.is_empty() && data.len() <= MAX_IMAGE_DATA && data.bytes().all(|b| b.is_ascii_alphanumeric() || b"+/=".contains(&b));
    }
    false
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
