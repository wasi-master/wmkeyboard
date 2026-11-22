//! What the editor knows about WM Keyboard itself, beyond Python: the
//! provider lists a plugin can answer and the requests they send, the values
//! widget arguments take, the icon names, the decorators API 5 plugins are
//! built from, and how a signature string reads as parameters.
//!
//! Completion, signature help, hover and the checks all read this, so a
//! suggestion and the check that would flag the same text never disagree.

use crate::analysis::{Analysis, Target};
use crate::api;
use crate::ast::*;
use crate::text::Span;

/// One provider list a plugin can answer (`@plugin.provider(tool)`).
pub struct ProviderTool {
    pub name: &'static str,
    /// What the list is called in the keyboard's settings.
    pub label: &'static str,
    /// The request's keys, each with what it holds.
    pub request: &'static [(&'static str, &'static str)],
    /// A request as the keyboard sends one, as JSON, for trying the provider.
    pub sample: &'static str,
    /// What to return, in one line.
    pub reply: &'static str,
    /// The body of a stub that answers it, `{i}` the indentation of the def.
    pub body: &'static str,
}

/// Every list `providers` may name, in the order the guide lists them.
pub const PROVIDER_TOOLS: &[ProviderTool] = &[
    ProviderTool {
        name: "search",
        label: "Web search",
        request: &[
            ("kind", "\"web\", or \"images\" for the Images tab"),
            ("query", "what the user searched for"),
            ("count", "how many results to return"),
            ("safe", "True when Safe search is on"),
            ("page", "the page of results, from 0"),
        ],
        sample: r#"{"kind": "web", "query": "cats", "count": 10, "safe": true, "page": 0}"#,
        reply: "{\"results\": [{\"title\", \"url\", \"snippet\"}]}, or a list of results",
        body: "{i}    results = []\n{i}    return {\"results\": results}",
    },
    ProviderTool {
        name: "translate",
        label: "Translate",
        request: &[("text", "the text to translate"), ("to", "the language to translate into"), ("from", "the text's language, or \"auto\"")],
        sample: r#"{"text": "Good morning", "to": "de", "from": "auto"}"#,
        reply: "the translation as a string, or {\"text\", \"from\"}",
        body: "{i}    return request[\"text\"]",
    },
    ProviderTool {
        name: "dictionary",
        label: "Dictionary",
        request: &[("word", "the word to look up")],
        sample: r#"{"word": "serene"}"#,
        reply: "a list of entries: {\"word\", \"phonetic\", \"meanings\": [{\"pos\", \"definitions\"}]}",
        body: "{i}    return [{\"word\": request[\"word\"], \"meanings\": []}]",
    },
    ProviderTool {
        name: "synonyms",
        label: "Synonyms",
        request: &[("word", "the word to find synonyms of")],
        sample: r#"{"word": "happy"}"#,
        reply: "a list of words, or {\"groups\": [{\"pos\", \"sense\", \"words\"}]}",
        body: "{i}    return []",
    },
    ProviderTool {
        name: "vocab_autofill",
        label: "Vocabulary look-up",
        request: &[("word", "the word being added"), ("translations", "the languages to translate it into")],
        sample: r#"{"word": "serene", "translations": ["de"]}"#,
        reply: "dictionary entries, or a vocabulary record {\"word\", \"senses\"}",
        body: "{i}    return [{\"word\": request[\"word\"], \"meanings\": []}]",
    },
    ProviderTool {
        name: "currency_fiat",
        label: "Currency rates",
        request: &[("base", "the currency the rates are for, such as \"USD\"")],
        sample: r#"{"base": "USD"}"#,
        reply: "{\"base\", \"rates\": {\"EUR\": 0.92}}",
        body: "{i}    return {\"base\": request[\"base\"], \"rates\": {}}",
    },
    ProviderTool {
        name: "currency_crypto",
        label: "Crypto prices",
        request: &[("base", "always \"USD\""), ("codes", "the coins wanted, such as [\"BTC\"]")],
        sample: r#"{"base": "USD", "codes": ["BTC", "ETH"]}"#,
        reply: "each coin's price in US dollars: {\"BTC\": 65000.0}",
        body: "{i}    return {}",
    },
    ProviderTool {
        name: "grammar",
        label: "Grammar",
        request: &[("text", "the text to check"), ("language", "its language, such as \"en-US\"")],
        sample: r#"{"text": "Their is a cat.", "language": "en-US"}"#,
        reply: "a list of problems: {\"start\", \"end\", \"message\", \"suggestions\"}",
        body: "{i}    return []",
    },
    ProviderTool {
        name: "gif",
        label: "GIFs",
        request: &[("query", "what the user searched for"), ("kind", "\"gif\""), ("limit", "how many to return"), ("safe", "True when Safe search is on")],
        sample: r#"{"query": "happy", "kind": "gif", "limit": 24, "safe": true}"#,
        reply: "a list of pictures: {\"url\", \"preview\", \"width\", \"height\"}",
        body: "{i}    return []",
    },
    ProviderTool {
        name: "sticker",
        label: "Stickers",
        request: &[("query", "what the user searched for"), ("kind", "\"sticker\""), ("limit", "how many to return"), ("safe", "True when Safe search is on")],
        sample: r#"{"query": "happy", "kind": "sticker", "limit": 24, "safe": true}"#,
        reply: "a list of pictures: {\"url\", \"preview\", \"width\", \"height\"}",
        body: "{i}    return []",
    },
    ProviderTool {
        name: "ocr",
        label: "Online text reading",
        request: &[("image", "the photo as base64"), ("mime", "\"image/jpeg\"")],
        sample: r#"{"image": "", "mime": "image/jpeg"}"#,
        reply: "the text in the photo, as a string",
        body: "{i}    return \"\"",
    },
    ProviderTool {
        name: "music",
        label: "Music ID",
        request: &[
            ("audio", "the clip as base64, 16-bit samples"),
            ("encoding", "\"pcm16le\""),
            ("sampleRate", "16000"),
            ("channels", "1"),
        ],
        sample: r#"{"audio": "", "encoding": "pcm16le", "sampleRate": 16000, "channels": 1}"#,
        reply: "{\"title\", \"artist\", \"album\"}, or None for no match",
        body: "{i}    return None",
    },
    ProviderTool {
        name: "voice",
        label: "Voice typing",
        request: &[
            ("audio", "the clip as base64, 16-bit samples"),
            ("encoding", "\"pcm16le\""),
            ("sampleRate", "16000"),
            ("channels", "1"),
            ("language", "the dictation language, such as \"en\""),
        ],
        sample: r#"{"audio": "", "encoding": "pcm16le", "sampleRate": 16000, "channels": 1, "language": "en"}"#,
        reply: "what was said, as a string",
        body: "{i}    return \"\"",
    },
];

pub fn provider_tool(name: &str) -> Option<&'static ProviderTool> {
    PROVIDER_TOOLS.iter().find(|t| t.name == name)
}

pub fn provider_tool_names() -> impl Iterator<Item = &'static str> {
    PROVIDER_TOOLS.iter().map(|t| t.name)
}

pub const COLOR_ROLES: &[&str] = &["primary", "secondary", "muted", "danger", "success", "warning", "accent"];
const LABEL_STYLES: &[&str] = &["headline", "title", "body", "caption"];
const INPUT_KINDS: &[&str] = &["text", "number", "email", "url", "password", "phone"];
const SELECT_STYLES: &[&str] = &["dropdown", "segmented"];
const CHART_KINDS: &[&str] = &["line", "bar", "spark"];
const ACCURACY: &[&str] = &["coarse", "fine"];
const SENSORS: &[&str] = &["accelerometer", "gyroscope", "magnetometer", "light", "steps", "proximity", "pressure"];
const METHODS: &[&str] = &["GET", "POST", "PUT", "PATCH", "DELETE"];

/// Material Symbols names plugins reach for most. Any name the font has
/// works; these are the ones offered.
pub const ICONS: &[&str] = &[
    "add", "add_circle", "alarm", "analytics", "apps", "arrow_back", "arrow_forward", "article", "auto_awesome",
    "bolt", "book", "bookmark", "bug_report", "build", "calculate", "calendar_month", "call", "camera",
    "chat", "check", "check_circle", "checklist", "chevron_left", "chevron_right", "close", "cloud", "code",
    "content_copy", "content_paste", "currency_exchange", "dark_mode", "delete", "description", "dictionary",
    "done", "download", "edit", "edit_note", "email", "emoji_emotions", "event", "explore", "extension",
    "favorite", "filter_list", "flag", "flight", "folder", "format_quote", "functions", "g_translate", "gif",
    "grade", "group", "help", "history", "home", "hourglass_empty", "image", "info", "key", "keyboard",
    "label", "language", "light_mode", "lightbulb", "link", "list", "local_cafe", "location_on", "lock",
    "login", "logout", "mail", "map", "menu", "mic", "mood", "more_horiz", "more_vert", "music_note",
    "notes", "notifications", "open_in_new", "palette", "pause", "person", "photo", "photo_camera", "pin",
    "play_arrow", "public", "qr_code", "qr_code_scanner", "question_mark", "receipt", "redo", "refresh",
    "remove", "restaurant", "rocket_launch", "save", "schedule", "school", "science", "search", "send",
    "settings", "share", "shopping_cart", "shuffle", "sort", "spellcheck", "star", "stop", "sync",
    "terminal", "text_fields", "thermostat", "thumb_up", "timer", "today", "translate", "trending_up",
    "tune", "undo", "upload", "visibility", "volume_up", "wallet", "warning", "water_drop", "wb_sunny",
    "wifi", "work",
];

/// The literal values parameter [param] of the API function at [path] (a
/// model path, `wm5.ui.label`) takes, when it takes only a few.
pub fn values_for(path: &str, param: &str) -> Option<&'static [&'static str]> {
    let name = path.strip_prefix(crate::package::ROOT).and_then(|p| p.strip_prefix('.')).unwrap_or(path);
    let module = name.split('.').next().unwrap_or("");
    Some(match (name, param) {
        (_, "color") if module == "ui" => COLOR_ROLES,
        ("ui.label" | "ui.text", "style") => LABEL_STYLES,
        ("ui.button", "style") => &["primary"],
        ("ui.input", "kind") => INPUT_KINDS,
        ("ui.select", "style") => SELECT_STYLES,
        ("ui.chart", "kind") => CHART_KINDS,
        ("location.get", "accuracy") => ACCURACY,
        ("sensors.subscribe", "kind") => SENSORS,
        ("http.request", "method") => METHODS,
        ("plugin.provider", "tool") => return Some(PROVIDER_NAMES),
        (_, "icon") if matches!(module, "ui" | "plugin") => ICONS,
        ("ui.icon", "name") => ICONS,
        _ => return None,
    })
}

const PROVIDER_NAMES: &[&str] = &[
    "search", "translate", "dictionary", "synonyms", "vocab_autofill", "currency_fiat", "currency_crypto", "grammar",
    "gif", "sticker", "ocr", "music", "voice",
];

/// Whether a value set is the open-ended icon list, which no check holds a
/// script to: the font has thousands of names.
pub fn is_open_set(values: &[&str]) -> bool {
    std::ptr::eq(values, ICONS)
}

/// A parameter that takes a function the keyboard calls later.
pub fn is_callback(param: &str) -> bool {
    param.starts_with("on_") || matches!(param, "then" | "render" | "fn")
}

// ---- signatures --------------------------------------------------------------

/// One parameter of a signature string, and where it sits in that string.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SigParam {
    pub name: String,
    pub default: Option<String>,
    /// 1 for `*args`, 2 for `**options`.
    pub star: u8,
    /// Byte offsets into the signature, which is ASCII.
    pub start: usize,
    pub end: usize,
}

/// The parameters of `name(a, b=1, *rest, **options)`.
pub fn sig_params(signature: &str) -> Vec<SigParam> {
    let Some(open) = signature.find('(') else { return Vec::new() };
    let close = signature.rfind(')').unwrap_or(signature.len());
    if close <= open {
        return Vec::new();
    }
    let inner = &signature[open + 1..close];
    let mut out = Vec::new();
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    let mut part_start = 0usize;
    let push = |from: usize, to: usize, out: &mut Vec<SigParam>| {
        let raw = &inner[from..to];
        let lead = raw.len() - raw.trim_start().len();
        let text = raw.trim();
        if text.is_empty() || text == "*" || text == "/" {
            return;
        }
        let (star, rest) = if let Some(r) = text.strip_prefix("**") {
            (2, r)
        } else if let Some(r) = text.strip_prefix('*') {
            (1, r)
        } else {
            (0, text)
        };
        let (name, default) = match rest.split_once('=') {
            Some((n, d)) => (n.trim(), Some(d.trim().to_string())),
            None => (rest.trim(), None),
        };
        let name = name.split(':').next().unwrap_or(name).trim().to_string();
        let start = open + 1 + from + lead;
        out.push(SigParam { name, default, star, start, end: start + text.len() });
    };
    for (i, c) in inner.char_indices() {
        match quote {
            Some(q) if c == q => quote = None,
            Some(_) => {}
            None => match c {
                '"' | '\'' => quote = Some(c),
                '(' | '[' | '{' => depth += 1,
                ')' | ']' | '}' => depth -= 1,
                ',' if depth == 0 => {
                    push(part_start, i, &mut out);
                    part_start = i + 1;
                }
                _ => {}
            },
        }
    }
    push(part_start, inner.len(), &mut out);
    out
}

/// Whether a call of [params] takes keyword arguments it does not name.
pub fn takes_any_keyword(params: &[SigParam]) -> bool {
    params.iter().any(|p| p.star == 2)
}

// ---- names ---------------------------------------------------------------------

/// The API path a chain of names means, when it starts at `wm`, `ui` or an
/// imported module: `wm5.http.get` for `http.get` after `from wm import http`.
pub fn api_path(a: &Analysis, e: &Expr) -> Option<String> {
    match &e.kind {
        ExprKind::Name(n) => match a.occurrence_at(e.span.start).filter(|o| o.span == e.span).map(|o| o.target) {
            Some(Target::Api) => Some(n.clone()),
            Some(Target::Symbol(id)) => {
                let s = &a.symbols[id];
                match &s.import {
                    Some((module, None)) if api::MODULES.contains(&module.as_str()) => Some(module.clone()),
                    Some((module, member)) if module == "wm" || module.starts_with("wm.") => {
                        let path = api::import_path(module, member.as_deref());
                        path.starts_with(crate::package::ROOT).then_some(path)
                    }
                    Some((module, Some(member))) if api::MODULES.contains(&module.as_str()) => Some(format!("{module}.{member}")),
                    _ => None,
                }
            }
            _ => None,
        },
        ExprKind::Attribute { value, attr, .. } => {
            let base = api_path(a, value)?;
            let full = format!("{base}.{attr}");
            api::model().find(&full).map(|_| full)
        }
        _ => None,
    }
}

/// [path] without the package root: `plugin.provider` for `wm5.plugin.provider`.
pub fn short(path: &str) -> &str {
    path.strip_prefix(crate::package::ROOT).and_then(|p| p.strip_prefix('.')).unwrap_or(path)
}

// ---- decorators ----------------------------------------------------------------

/// A function under one of the package's decorators: `@plugin.provider("search")`.
#[derive(Clone, Debug)]
pub struct Decorated<'a> {
    /// The decorator's path without the root: `plugin.provider`.
    pub what: String,
    /// The decorator call's arguments, when it is called.
    pub args: &'a [Expr],
    pub keywords: &'a [Keyword],
    /// The whole `@...` line.
    pub span: Span,
    pub def_name: &'a str,
    pub def_name_span: Span,
    pub params: &'a Params,
    pub def_span: Span,
}

impl Decorated<'_> {
    /// The string given as argument [index] (or by [keyword]), and its span.
    pub fn literal(&self, index: usize, keyword: &str) -> Option<(&str, Span)> {
        let e = self.keywords.iter().find(|k| k.name == keyword).map(|k| &k.value).or_else(|| self.args.get(index))?;
        match &e.kind {
            ExprKind::Str { value: Some(v), fstring: false, .. } => Some((v.as_str(), e.span)),
            _ => None,
        }
    }

    /// `(tool, id)` for a provider: the id defaults to the tool.
    pub fn provider(&self) -> Option<(String, String, Span)> {
        let (tool, span) = self.literal(0, "tool")?;
        let id = self.literal(1, "id").map(|(i, _)| i.to_string()).unwrap_or_else(|| tool.to_string());
        Some((tool.to_string(), id, span))
    }
}

/// Every top-level function under a decorator from the `wm` package.
pub fn decorated<'a>(module: &'a Module, a: &Analysis) -> Vec<Decorated<'a>> {
    let mut out = Vec::new();
    let body = &module.body;
    for (i, stmt) in body.iter().enumerate() {
        let StmtKind::Unsupported { what: "decorators", exprs, .. } = &stmt.kind else { continue };
        let Some(e) = exprs.first() else { continue };
        // The def the decorators stand over: the first statement after them that is not one.
        let Some(def) = body[i + 1..].iter().find(|s| !matches!(s.kind, StmtKind::Unsupported { what: "decorators", .. })) else { continue };
        let StmtKind::Def { name, name_span, params, .. } = &def.kind else { continue };
        let (callee, args, keywords): (&Expr, &[Expr], &[Keyword]) = match &e.kind {
            ExprKind::Call { func, args, keywords, .. } => (func, args, keywords),
            _ => (e, &[], &[]),
        };
        let Some(path) = api_path(a, callee) else { continue };
        if !path.starts_with(crate::package::ROOT) {
            continue;
        }
        out.push(Decorated {
            what: short(&path).to_string(),
            args,
            keywords,
            span: stmt.span,
            def_name: name,
            def_name_span: *name_span,
            params,
            def_span: def.span,
        });
    }
    out
}

/// Whether the file imports anything from the `wm` package.
pub fn imports_wm(a: &Analysis) -> bool {
    a.symbols.iter().any(|s| s.import.as_ref().is_some_and(|(m, _)| m == "wm" || m.starts_with("wm.")))
}

/// The name the file binds for package module [module] (`plugin`, `ui`):
/// its alias when imported as one, None when not imported.
pub fn local_name(a: &Analysis, module: &str) -> Option<String> {
    a.symbols.iter().find_map(|s| match &s.import {
        Some((m, Some(member))) if m == "wm" && member == module && s.scope == 0 => Some(s.name.clone()),
        _ => None,
    })
}

/// A function name for a provider's stub: `search`, `translate_text`.
pub fn stub_name(tool: &str, id: &str) -> String {
    let base = if id == tool { tool.to_string() } else { format!("{tool}_{id}") };
    base.chars().map(|c| if c.is_ascii_alphanumeric() { c.to_ascii_lowercase() } else { '_' }).collect()
}

/// The text of a provider stub, `{i}` already filled with [indent].
pub fn provider_stub(plugin: &str, tool: &str, id: &str, indent: &str) -> String {
    let t = provider_tool(tool);
    let header = if id == tool { format!("@{plugin}.provider(\"{tool}\")") } else { format!("@{plugin}.provider(\"{tool}\", \"{id}\")") };
    let keys = t.map(|t| t.request.iter().map(|(k, _)| *k).collect::<Vec<_>>().join(", ")).unwrap_or_default();
    let body = t.map(|t| t.body).unwrap_or("{i}    return None").replace("{i}", indent);
    let comment = if keys.is_empty() { String::new() } else { format!("{indent}    # request: {keys}\n") };
    format!("{indent}{header}\n{indent}def {}(request):\n{comment}{body}", stub_name(tool, id))
}
