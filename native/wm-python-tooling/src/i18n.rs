//! A plugin's translatable text and its translation files.
//!
//! The text is what the code passes as a literal to `_()`, `gettext()` and
//! `ngettext()` from `wm.i18n` (`wm-python/src/host/prelude/i18n.py`), and
//! what the app shows from plugin.json before any code runs: `@name`,
//! `@description`, `@action.<id>`, `@action.<id>.summary`, `@setting.<key>`,
//! `@setting.<key>.summary` and `@option.<key>.<value>` (PluginLocales.kt).
//!
//! A translation file, `locales/<tag>.json`, maps that English to the text
//! in one language; a plural is an object of CLDR forms. The keyboard shows
//! the English for a key a file leaves out and for an empty translation
//! (`gettext` and `ngettext` skip an empty string, `label` a blank one), so
//! an empty string is how a file marks text not translated yet.

use std::collections::{BTreeSet, HashMap};

use serde_json::{json, Map, Value};

use crate::ast::*;
use crate::document::Document;
use crate::keyboard;
use crate::manifest as codec;
use crate::text::Span;
use crate::usage::Source;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Kind {
    /// `_("text")`: translated as one string.
    Text,
    /// `ngettext("singular", "plural", n)`: translated as plural forms.
    Plural,
    /// An `@` key: what the app shows from plugin.json.
    Label,
}

impl Kind {
    pub fn name(self) -> &'static str {
        match self {
            Kind::Text => "text",
            Kind::Plural => "plural",
            Kind::Label => "label",
        }
    }
}

/// Where a message is written.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Place {
    pub file: String,
    /// 1-based.
    pub line: usize,
    /// UTF-16 offsets into the file; empty for plugin.json.
    pub span: Span,
}

/// One piece of text to translate.
#[derive(Clone, Debug)]
pub struct Message {
    /// The key in the translation files: the English, or an `@` key.
    pub key: String,
    /// The English it stands for: the key itself, or the manifest's text for an `@` key.
    pub source: String,
    /// For a plural: the English for every count but 1.
    pub plural: Option<String>,
    pub kind: Kind,
    pub places: Vec<Place>,
}

impl Message {
    /// The `{name}` values its translations may use.
    pub fn placeholders(&self) -> BTreeSet<String> {
        let mut out = placeholders(&self.source);
        if let Some(p) = &self.plural {
            out.extend(placeholders(p));
            out.insert("n".into());
        }
        out
    }
}

/// A call whose text is not a literal, so no file can translate it.
#[derive(Clone, Debug)]
pub struct Unreadable {
    /// `_`, `gettext` or `ngettext`.
    pub call: String,
    pub place: Place,
}

/// What [extract] found.
#[derive(Clone, Debug, Default)]
pub struct Catalog {
    /// The `@` keys first, in plugin.json's order, then the code's text in the order it is written.
    pub messages: Vec<Message>,
    pub unreadable: Vec<Unreadable>,
    /// Keys the code passes both to `_()` and, as a singular, to `ngettext()`.
    pub mixed: Vec<String>,
}

impl Catalog {
    pub fn get(&self, key: &str) -> Option<&Message> {
        self.messages.iter().find(|m| m.key == key)
    }

    pub fn json(&self) -> Value {
        let place = |p: &Place| json!({"file": p.file, "line": p.line});
        json!({
            "messages": self.messages.iter().map(|m| json!({
                "key": m.key, "source": m.source, "plural": m.plural, "kind": m.kind.name(),
                "places": m.places.iter().map(place).collect::<Vec<_>>(),
            })).collect::<Vec<_>>(),
            "unreadable": self.unreadable.iter().map(|u| json!({"call": u.call, "file": u.place.file, "line": u.place.line})).collect::<Vec<_>>(),
            "mixed": self.mixed,
        })
    }
}

/// Every translatable text of the plugin in [manifest] (plugin.json's text) and [files].
pub fn extract(manifest: &str, files: &[Source]) -> Catalog {
    let mut c = Catalog::default();
    labels(manifest, &mut c);
    for f in files {
        code(f, &mut c);
    }
    c
}

fn add(c: &mut Catalog, key: String, source: String, plural: Option<String>, kind: Kind, place: Place) {
    match c.messages.iter_mut().find(|m| m.key == key) {
        Some(m) => {
            if m.kind != kind && m.kind != Kind::Label && kind != Kind::Label {
                if !c.mixed.contains(&key) {
                    c.mixed.push(key.clone());
                }
                // A plural keeps its forms: ngettext reads them, and _() falls back to English.
                if kind == Kind::Plural {
                    m.kind = Kind::Plural;
                    m.plural = plural;
                }
            }
            m.places.push(place);
        }
        None => c.messages.push(Message { key, source, plural, kind, places: vec![place] }),
    }
}

/// The `@` keys of the manifest, as PluginLocales and the app's screens read them.
fn labels(manifest: &str, c: &mut Catalog) {
    let Some(m) = codec::check(manifest).manifest else { return };
    let mut label = |key: String, text: &str| {
        let text = text.trim();
        if text.is_empty() {
            return;
        }
        let line = value_line(manifest, text);
        add(c, key, text.to_string(), None, Kind::Label, Place { file: codec::MANIFEST.into(), line, span: Span::default() });
    };
    label("@name".into(), &m.raw_name);
    label("@description".into(), &m.description);
    for a in &m.actions {
        let id = a.id.trim();
        if id.is_empty() {
            continue;
        }
        label(format!("@action.{id}"), &a.name);
        label(format!("@action.{id}.summary"), &a.summary);
    }
    for s in &m.settings {
        let key = s.key.trim();
        if key.is_empty() {
            continue;
        }
        label(format!("@setting.{key}"), &s.label);
        label(format!("@setting.{key}.summary"), &s.summary);
        if s.kind.trim() == "choice" {
            for o in &s.options {
                label(format!("@option.{key}.{}", o.trim()), o);
            }
        }
    }
}

/// The line of plugin.json that holds the string [text], or 1.
fn value_line(manifest: &str, text: &str) -> usize {
    let quoted = serde_json::to_string(text).unwrap_or_default();
    manifest.find(&quoted).map_or(1, |at| manifest[..at].matches('\n').count() + 1)
}

/// The `_()`, `gettext()` and `ngettext()` calls of one file.
fn code(f: &Source, c: &mut Catalog) {
    let doc = Document::new(f.text);
    let a = &doc.analysis;
    let place = |span: Span| Place { file: f.name.to_string(), line: doc.lines.line_of(span.start) + 1, span };
    let mut found: Vec<(Span, String, Option<String>, Kind)> = Vec::new();
    let mut unreadable: Vec<Unreadable> = Vec::new();
    walk_stmts(&doc.module.body, &mut |stmt| {
        for e in stmt_exprs(stmt) {
            walk_expr(e, &mut |x| {
                let ExprKind::Call { func, args, keywords, .. } = &x.kind else { return };
                let Some(path) = keyboard::api_path(a, func) else { return };
                if !path.starts_with(crate::package::ROOT) {
                    return;
                }
                let short = keyboard::short(&path);
                let name = short.strip_prefix("i18n.").unwrap_or(short);
                let arg = |i: usize, keyword: &str| keywords.iter().find(|k| k.name == keyword).map(|k| &k.value).or_else(|| args.get(i));
                let literal = |e: Option<&Expr>| match e.map(|e| &e.kind) {
                    Some(ExprKind::Str { value: Some(v), fstring: false, .. }) => Some(v.clone()),
                    _ => None,
                };
                match name {
                    "_" | "gettext" => match literal(arg(0, "text")) {
                        Some(text) => found.push((x.span, text, None, Kind::Text)),
                        None => unreadable.push(Unreadable { call: name.to_string(), place: place(arg(0, "text").map_or(x.span, |e| e.span)) }),
                    },
                    "ngettext" => match (literal(arg(0, "singular")), literal(arg(1, "plural"))) {
                        (Some(one), Some(other)) => found.push((x.span, one, Some(other), Kind::Plural)),
                        _ => {
                            let at = [arg(0, "singular"), arg(1, "plural")].into_iter().flatten().find(|e| literal(Some(*e)).is_none()).map_or(x.span, |e| e.span);
                            unreadable.push(Unreadable { call: name.to_string(), place: place(at) });
                        }
                    },
                    _ => {}
                }
            });
        }
    });
    for (span, text, plural, kind) in found {
        add(c, text.clone(), text, plural, kind, place(span));
    }
    c.unreadable.extend(unreadable);
}

/// The names of the `{fields}` in [text], as `str.format` reads them: `{name}`,
/// `{name:>8}`, `{user.name}` (as `user`), and `{}` or `{0}` by position
/// (which `_()` never fills, as it passes values by name). `{{` and `}}` are
/// braces.
pub fn placeholders(text: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let chars: Vec<char> = text.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        match chars[i] {
            '{' if chars.get(i + 1) == Some(&'{') => i += 2,
            '}' if chars.get(i + 1) == Some(&'}') => i += 2,
            '{' => {
                let mut depth = 1;
                let mut j = i + 1;
                let mut field = String::new();
                let mut in_name = true;
                let mut nested = String::new();
                while j < chars.len() && depth > 0 {
                    let ch = chars[j];
                    match ch {
                        '{' => {
                            depth += 1;
                            nested.clear();
                        }
                        '}' => {
                            depth -= 1;
                            if depth >= 1 {
                                out.insert(field_name(&nested));
                            }
                        }
                        _ if depth == 1 && in_name && matches!(ch, ':' | '!') => in_name = false,
                        _ if depth == 1 && in_name => field.push(ch),
                        _ if depth > 1 => nested.push(ch),
                        _ => {}
                    }
                    j += 1;
                }
                if depth == 0 {
                    out.insert(field_name(&field));
                }
                i = j;
            }
            _ => i += 1,
        }
    }
    out
}

fn field_name(field: &str) -> String {
    field.split(['.', '[']).next().unwrap_or("").trim().to_string()
}

/// The CLDR plural forms `ngettext` asks a file in [tag] for: the categories
/// i18n.py's `_category` gives that language.
pub fn plural_forms(tag: &str) -> &'static [&'static str] {
    let lang = tag.split('-').next().unwrap_or(tag).to_lowercase();
    match lang.as_str() {
        "zh" | "ja" | "ko" | "th" | "vi" | "id" | "ms" | "my" | "km" | "lo" => &["other"],
        "ru" | "uk" | "be" | "sr" | "hr" | "bs" | "pl" => &["one", "few", "many"],
        "cs" | "sk" | "ro" | "lt" => &["one", "few", "other"],
        "ar" => &["zero", "one", "two", "few", "many", "other"],
        "he" => &["one", "two", "other"],
        "lv" => &["zero", "one", "other"],
        "sl" => &["one", "two", "few", "other"],
        _ => &["one", "other"],
    }
}

const FORMS: &[&str] = &["zero", "one", "two", "few", "many", "other"];

/// One thing wrong with one translation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Problem {
    pub key: String,
    /// The plural form it is about.
    pub form: Option<String>,
    pub message: String,
}

/// How one translation file stands against the catalog.
#[derive(Clone, Debug, Default)]
pub struct LocaleReport {
    pub tag: String,
    pub total: usize,
    /// Keys with a translation that has no problem.
    pub translated: usize,
    /// Keys the file does not have.
    pub missing: Vec<String>,
    /// Keys the file has, left empty: the keyboard shows the English.
    pub empty: Vec<String>,
    /// Keys nothing in the plugin uses any more.
    pub unused: Vec<String>,
    pub problems: Vec<Problem>,
}

impl LocaleReport {
    /// Whether every key is translated and nothing is wrong.
    pub fn complete(&self) -> bool {
        self.missing.is_empty() && self.empty.is_empty() && self.problems.is_empty()
    }

    pub fn json(&self) -> Value {
        json!({
            "tag": self.tag, "total": self.total, "translated": self.translated,
            "missing": self.missing, "empty": self.empty, "unused": self.unused,
            "problems": self.problems.iter().map(|p| json!({"key": p.key, "form": p.form, "message": p.message})).collect::<Vec<_>>(),
        })
    }
}

fn listed(names: &BTreeSet<String>) -> String {
    names.iter().map(|n| format!("{{{n}}}")).collect::<Vec<_>>().join(", ")
}

/// What [file] (a parsed `locales/<tag>.json`) lacks and gets wrong.
pub fn check_locale(catalog: &Catalog, tag: &str, file: &Map<String, Value>) -> LocaleReport {
    let mut r = LocaleReport { tag: tag.to_string(), total: catalog.messages.len(), ..LocaleReport::default() };
    let wanted = plural_forms(tag);
    for m in &catalog.messages {
        let problem = |form: Option<&str>, message: String| Problem { key: m.key.clone(), form: form.map(String::from), message };
        let before = r.problems.len();
        match file.get(&m.key) {
            None => r.missing.push(m.key.clone()),
            Some(Value::String(s)) if s.trim().is_empty() => r.empty.push(m.key.clone()),
            Some(Value::String(s)) => match m.kind {
                Kind::Plural => r.problems.push(problem(None, format!("ngettext() needs plural forms ({}); plain text is used only when the count is 1", wanted.join(", ")))),
                Kind::Label => {}
                Kind::Text => {
                    let (want, have) = (placeholders(&m.source), placeholders(s));
                    let extra: BTreeSet<String> = have.difference(&want).cloned().collect();
                    let lost: BTreeSet<String> = want.difference(&have).cloned().collect();
                    if !extra.is_empty() {
                        r.problems.push(problem(None, format!("uses {} that the English does not have, so the keyboard shows the English", listed(&extra))));
                    }
                    if !lost.is_empty() {
                        r.problems.push(problem(None, format!("leaves out {}", listed(&lost))));
                    }
                }
            },
            Some(Value::Object(forms)) => {
                if m.kind != Kind::Plural {
                    r.problems.push(problem(None, "is plural forms, but the code calls _() for it, which needs text: the keyboard shows the English".into()));
                } else {
                    let all_empty = forms.values().all(|v| v.as_str().is_some_and(|s| s.trim().is_empty()));
                    if all_empty {
                        r.empty.push(m.key.clone());
                        continue;
                    }
                    for (form, v) in forms {
                        if !FORMS.contains(&form.as_str()) {
                            r.problems.push(problem(Some(form.as_str()), format!("\"{form}\" is not a plural form (zero, one, two, few, many, other), and the app refuses the file")));
                            continue;
                        }
                        let Some(text) = v.as_str() else {
                            r.problems.push(problem(Some(form.as_str()), "is not text, and the app refuses the file".into()));
                            continue;
                        };
                        if text.trim().is_empty() {
                            continue;
                        }
                        let allowed = m.placeholders();
                        let have = placeholders(text);
                        let extra: BTreeSet<String> = have.difference(&allowed).cloned().collect();
                        let required: BTreeSet<String> = allowed.iter().filter(|n| *n != "n").cloned().collect();
                        let lost: BTreeSet<String> = required.difference(&have).cloned().collect();
                        if !extra.is_empty() {
                            r.problems.push(problem(Some(form.as_str()), format!("uses {} that the English does not have, so the keyboard shows the English", listed(&extra))));
                        }
                        if !lost.is_empty() {
                            r.problems.push(problem(Some(form.as_str()), format!("leaves out {}", listed(&lost))));
                        }
                    }
                    let absent: Vec<&str> = wanted.iter().copied().filter(|f| forms.get(*f).and_then(Value::as_str).is_none_or(|s| s.trim().is_empty())).collect();
                    if !absent.is_empty() {
                        let tail = if absent.len() == 1 { "form" } else { "forms" };
                        r.problems.push(problem(None, format!("has no {} {tail}, which {tag} needs", absent.join(", "))));
                    }
                }
            }
            Some(_) => r.problems.push(problem(None, "is neither text nor plural forms, and the app refuses the file".into())),
        }
        if r.problems.len() == before && file.get(&m.key).is_some_and(|v| !v.as_str().is_some_and(|s| s.trim().is_empty())) {
            r.translated += 1;
        }
    }
    r.unused = file.keys().filter(|k| catalog.get(k).is_none()).cloned().collect();
    r
}

/// What [update_locale] changed.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Update {
    /// Keys added, empty.
    pub added: Vec<String>,
    /// `(key, form)`: plural forms added, empty, to a plural the file had.
    pub forms_added: Vec<(String, String)>,
    /// Keys taken out (only with prune).
    pub pruned: Vec<String>,
    /// Keys nothing uses, kept.
    pub unused: Vec<String>,
}

impl Update {
    pub fn changed(&self) -> bool {
        !self.added.is_empty() || !self.forms_added.is_empty() || !self.pruned.is_empty()
    }
}

/// [file] with every key of [catalog] it lacks added, empty, at the end in
/// the catalog's order, and each plural given the forms [tag] needs. Nothing
/// is taken out unless [prune] says so, and then only keys nothing uses.
pub fn update_locale(catalog: &Catalog, tag: &str, file: &Map<String, Value>, prune: bool) -> (Map<String, Value>, Update) {
    let mut out = file.clone();
    let mut u = Update::default();
    let forms = plural_forms(tag);
    for m in &catalog.messages {
        match out.get_mut(&m.key) {
            None => {
                let value = if m.kind == Kind::Plural {
                    Value::Object(forms.iter().map(|f| (f.to_string(), Value::String(String::new()))).collect())
                } else {
                    Value::String(String::new())
                };
                out.insert(m.key.clone(), value);
                u.added.push(m.key.clone());
            }
            Some(Value::Object(have)) if m.kind == Kind::Plural => {
                for f in forms {
                    if !have.contains_key(*f) {
                        have.insert(f.to_string(), Value::String(String::new()));
                        u.forms_added.push((m.key.clone(), f.to_string()));
                    }
                }
            }
            Some(_) => {}
        }
    }
    let unused: Vec<String> = out.keys().filter(|k| catalog.get(k).is_none()).cloned().collect();
    if prune {
        out = out.into_iter().filter(|(k, _)| !unused.contains(k)).collect();
        u.pruned = unused;
    } else {
        u.unused = unused;
    }
    (out, u)
}

/// A translation file's text: one key a line, a plural's forms on its line,
/// indented as [like] (the file's old text) is, two spaces when it is new.
pub fn write_locale(file: &Map<String, Value>, like: Option<&str>) -> String {
    let indent = like
        .and_then(|t| t.lines().skip(1).map(|l| &l[..l.len() - l.trim_start().len()]).find(|i| !i.is_empty()))
        .unwrap_or("  ")
        .to_string();
    if file.is_empty() {
        return "{}\n".into();
    }
    let q = |s: &str| serde_json::to_string(s).unwrap_or_default();
    let value = |v: &Value| match v {
        Value::Object(forms) => format!("{{{}}}", forms.iter().map(|(k, v)| format!("{}: {}", q(k), serde_json::to_string(v).unwrap_or_default())).collect::<Vec<_>>().join(", ")),
        other => serde_json::to_string(other).unwrap_or_default(),
    };
    let lines: Vec<String> = file.iter().map(|(k, v)| format!("{indent}{}: {}", q(k), value(v))).collect();
    format!("{{\n{}\n}}\n", lines.join(",\n"))
}

/// How many messages of each kind [catalog] holds: `(labels, texts, plurals)`.
pub fn counts(catalog: &Catalog) -> (usize, usize, usize) {
    let mut by: HashMap<Kind, usize> = HashMap::new();
    for m in &catalog.messages {
        *by.entry(m.kind).or_default() += 1;
    }
    let n = |k: Kind| by.get(&k).copied().unwrap_or(0);
    (n(Kind::Label), n(Kind::Text), n(Kind::Plural))
}

#[cfg(test)]
mod tests {
    use super::*;

    const MANIFEST: &str = r#"{
  "format": "wmkeyboard-plugin", "id": "com.example.w", "name": "Weather", "pluginVersion": "1", "apiVersion": 5,
  "description": "Today's forecast",
  "settings": [{"key": "units", "type": "choice", "label": "Units", "options": ["metric", "imperial"]}]
}
"#;

    const MAIN: &str = "from wm import ui, _, ngettext\nfrom wm import i18n\n\n\ndef render():\n    ui.button(_(\"Look up\"))\n    ui.label(_(\"Hello, {name}\", name=user))\n    ui.label(ngettext(\"{n} day\", \"{n} days\", 3))\n    ui.label(i18n.gettext(\"Look up\"))\n    ui.label(_(f\"Hi {user}\"))\n";

    fn catalog() -> Catalog {
        extract(MANIFEST, &[Source { name: "main.py", text: MAIN, entry: true }])
    }

    #[test]
    fn extracts_labels_then_code_in_order() {
        let c = catalog();
        let keys: Vec<&str> = c.messages.iter().map(|m| m.key.as_str()).collect();
        assert_eq!(keys, ["@name", "@description", "@setting.units", "@option.units.metric", "@option.units.imperial", "Look up", "Hello, {name}", "{n} day"]);
        assert_eq!(c.get("@name").unwrap().source, "Weather");
        assert_eq!(c.get("Look up").unwrap().places.len(), 2);
        assert_eq!(c.get("{n} day").unwrap().plural.as_deref(), Some("{n} days"));
        assert_eq!(c.unreadable.len(), 1);
        assert_eq!((c.unreadable[0].call.as_str(), c.unreadable[0].place.line), ("_", 10));
    }

    #[test]
    fn placeholder_names() {
        let names = |t: &str| placeholders(t).into_iter().collect::<Vec<_>>();
        assert_eq!(names("Hello, {name} and {other:>8} {user.city} {{literal}}"), ["name", "other", "user"]);
        assert_eq!(names("{} {0}"), ["", "0"]);
        assert_eq!(names("{x:{width}}"), ["width", "x"]);
        assert!(names("no braces").is_empty());
    }

    #[test]
    fn updates_keep_translations_and_add_empty_keys() {
        let c = catalog();
        let file: Map<String, Value> = serde_json::from_str(r#"{"Look up": "Найти", "Old": "Старое", "{n} day": {"one": "{n} день"}}"#).unwrap();
        let (out, u) = update_locale(&c, "ru", &file, false);
        assert_eq!(out["Look up"], "Найти");
        assert_eq!(out["Old"], "Старое", "kept without --prune");
        assert_eq!(u.unused, ["Old"]);
        assert_eq!(out["Hello, {name}"], "");
        assert_eq!(out["{n} day"], json!({"one": "{n} день", "few": "", "many": ""}));
        assert!(u.added.contains(&"@name".to_string()));
        let (pruned, u) = update_locale(&c, "ru", &file, true);
        assert!(pruned.get("Old").is_none());
        assert_eq!(u.pruned, ["Old"]);
        let text = write_locale(&out, Some("{\n    \"a\": \"b\"\n}\n"));
        assert!(text.starts_with("{\n    \"Look up\": \"Найти\",\n"), "{text}");
        assert!(text.contains("\"{n} day\": {\"one\": \"{n} день\", \"few\": \"\", \"many\": \"\"}"), "{text}");
        let back: Map<String, Value> = serde_json::from_str(&text).unwrap();
        assert_eq!(back, out);
    }

    #[test]
    fn checks_placeholders_and_plural_forms() {
        let c = catalog();
        let file: Map<String, Value> = serde_json::from_str(
            r#"{"@name": "Погода", "@description": "", "Look up": "Найти", "Hello, {name}": "Привет, {nmae}",
               "{n} day": {"one": "{n} день", "few": "{n} дня"}, "Gone": "x"}"#,
        )
        .unwrap();
        let r = check_locale(&c, "ru", &file);
        assert_eq!(r.total, 8);
        assert_eq!(r.translated, 2, "{r:#?}");
        assert_eq!(r.missing, ["@setting.units", "@option.units.metric", "@option.units.imperial"]);
        assert_eq!(r.empty, ["@description"]);
        assert_eq!(r.unused, ["Gone"]);
        let msgs: Vec<String> = r.problems.iter().map(|p| format!("{}: {}", p.key, p.message)).collect();
        assert!(msgs.iter().any(|m| m.starts_with("Hello, {name}: uses {nmae}")), "{msgs:?}");
        assert!(msgs.iter().any(|m| m.starts_with("Hello, {name}: leaves out {name}")), "{msgs:?}");
        assert!(msgs.iter().any(|m| m == "{n} day: has no many form, which ru needs"), "{msgs:?}");
        assert!(!r.complete());
    }

    #[test]
    fn plural_forms_follow_the_interpreter() {
        assert_eq!(plural_forms("ja"), ["other"]);
        assert_eq!(plural_forms("pt-BR"), ["one", "other"]);
        assert_eq!(plural_forms("ar"), FORMS);
        assert_eq!(plural_forms("pl"), ["one", "few", "many"]);
    }
}
