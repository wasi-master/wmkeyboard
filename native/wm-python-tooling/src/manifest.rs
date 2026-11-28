//! `plugin.json` as the app's importer reads it, and the archive around it.
//!
//! [check] ports `PluginManifestCodec.read` and `problems`
//! (core/plugins/src/main/java/com/wasimaster/wmkeyboard/core/plugins/PluginManifest.kt),
//! with `PluginPermission.kt`, `PluginPreviewRules` (PluginPreviewSpecs.kt) and
//! `PluginSyntax.compile`. Where the app stops at the first reason and words
//! it for someone installing, this reports every problem, each with the JSON
//! path it is about and its place in the text, so the CLI, the language
//! server and the web editor can point at it. An [Error] is something the app
//! refuses the install for; a [Warning] installs but not as written (a name
//! cut short, an icon the keyboard cannot find).
//!
//! [archive_problems] ports `PluginFile.read` (PluginFile.kt): the entry
//! script, the other modules, `assets/` and `locales/` (PluginLocales.kt), and
//! the archive's caps. [packed_role] says which files of a plugin folder go
//! into the `.wmplugin` at all.
//!
//! Pure: no I/O. Offsets are UTF-16 code units into the manifest text, like
//! the rest of this crate.
//!
//! [Error]: Severity::Error
//! [Warning]: Severity::Warning

use serde_json::{json, Map, Value};

use crate::diagnostics::Severity;
use crate::text::Span;

/// `PluginManifestCodec.FORMAT`: the one tag that makes a JSON file a plugin manifest.
pub const FORMAT: &str = "wmkeyboard-plugin";
/// `PluginManifestCodec.API_VERSION`: the `wm` API level this build of the app provides.
pub const API_VERSION: i64 = 5;
/// `PluginManifestCodec.PACKAGE_API`: the level of the `wm` package.
pub const PACKAGE_API: i64 = 5;
/// `PluginManifestCodec.PYTHON_ONLY_API`: from here on a manifest is Python unless it says otherwise.
pub const PYTHON_ONLY_API: i64 = 4;
pub const DEFAULT_ENTRY: &str = "main.py";

pub const MAX_NAME: usize = 40;
const MAX_VERSION: usize = 32;
const MAX_AUTHOR: usize = 64;
const MAX_DESCRIPTION: usize = 280;
const MAX_ENTRY: usize = 64;
const MAX_PERMISSIONS: usize = 24;
pub const MAX_HOSTS: usize = 16;
const MAX_HOST_LENGTH: usize = 253;
pub const MAX_SETTINGS: usize = 24;
const MAX_SETTING_OPTIONS: usize = 24;
const MAX_SETTING_TEXT: usize = 64;
const MAX_SETTING_DEFAULT: usize = 256;
pub const MAX_ACTIONS: usize = 16;
const MAX_ACTION_NAME: usize = 24;
const MAX_ACTION_SUMMARY: usize = 80;
pub const MAX_PROVIDERS: usize = 8;
const MAX_TOOL_HEIGHT: i64 = 600;
const MAX_PREVIEWS: usize = 16;
const MAX_SYNTAX: usize = 16;
const MAX_PREVIEW_EXTENSIONS: usize = 64;
const MAX_SYNTAX_EXTENSIONS: usize = 32;
const MAX_NAMES: usize = 16;
const MAX_MIMES: usize = 16;
const DEFAULT_MAX_BYTES: i64 = 4 * 1024 * 1024;
const MAX_MAX_BYTES: i64 = 16 * 1024 * 1024;
const MAX_GRAMMAR_RULES: usize = 200;
const MAX_GRAMMAR_WORDS: usize = 2000;
const MAX_GRAMMAR_PATTERN: usize = 512;
const MAX_GRAMMAR_MARKER: usize = 8;

/// `PluginFile`: the archive's caps.
pub const MAX_SCRIPT_BYTES: usize = 1024 * 1024;
/// Folders and file in a module's path, as `PluginFile.MAX_MODULE_PARTS`.
pub const MAX_MODULE_PARTS: usize = 8;
pub const MAX_ENTRIES: usize = 64;
pub const MAX_TOTAL_BYTES: usize = 4 * 1024 * 1024;
pub const MAX_ASSET_BYTES: usize = 512 * 1024;
/// `PluginLocales.MAX_BYTES`.
pub const MAX_LOCALE_BYTES: usize = 64 * 1024;

/// Where the plugin docs live, for the help lines.
pub const DOCS: &str = "https://wmkeyboard.pages.dev";

/// One permission a manifest may declare: `PluginPermission.all`.
pub struct Permission {
    pub wire: &'static str,
    /// The API level that introduced it.
    pub since_api: i64,
    /// Off until the user grants it, behind a warning (`Tier.RISKY`).
    pub risky: bool,
}

const fn perm(wire: &'static str, since_api: i64, risky: bool) -> Permission {
    Permission { wire, since_api, risky }
}

/// Every permission the app understands, in the order its lists show them.
pub const PERMISSIONS: &[Permission] = &[
    perm("storage", 1, false),
    perm("reminders", 1, false),
    perm("selection", 1, false),
    perm("field.write", 5, false),
    perm("clipboard.write", 5, false),
    perm("sensors", 5, false),
    perm("scan", 5, false),
    perm("device", 5, false),
    perm("share", 5, false),
    perm("notifications", 5, false),
    perm("clock", 5, false),
    perm("previews", 5, false),
    perm("network", 5, true),
    perm("network.local", 5, true),
    perm("ai", 5, true),
    perm("field.read", 5, true),
    perm("clipboard.read", 5, true),
    perm("location", 5, true),
    perm("files", 5, true),
    perm("calendar", 5, true),
    perm("todo", 5, true),
];

/// The permission [wire] names (trimmed), or None when the app does not know it.
pub fn permission(wire: &str) -> Option<&'static Permission> {
    let wire = wire.trim();
    PERMISSIONS.iter().find(|p| p.wire == wire)
}

/// `PluginProviderSpec.TOOLS`: the provider lists a plugin may answer for.
pub const PROVIDER_TOOLS: &[&str] = &[
    "search", "translate", "dictionary", "synonyms", "vocab_autofill", "currency_fiat", "currency_crypto", "grammar", "gif", "sticker", "ocr", "music",
    "voice",
];

/// `PluginSettingSpec.TYPES`.
pub const SETTING_TYPES: &[&str] = &["switch", "text", "number", "choice", "host", "secret"];

/// `PluginActionSpec.INPUTS`.
const ACTION_INPUTS: &[&str] = &["selection", "none"];

/// `PluginSyntax.Kind`: what a grammar rule colours a match as.
const GRAMMAR_KINDS: &[&str] = &[
    "comment", "string", "number", "keyword", "type", "function", "operator", "tag", "attribute", "heading", "link", "emphasis", "constant", "punctuation",
];

// ---- problems ---------------------------------------------------------------

/// One thing wrong with a plugin.
#[derive(Clone, Debug, PartialEq)]
pub struct Problem {
    pub severity: Severity,
    /// A stable snake_case id: `bad_id`, `unknown_permission`, `bad_json`.
    pub code: &'static str,
    /// What is wrong, in plain English, naming the value.
    pub message: String,
    /// The file it is about, relative to the plugin folder: `plugin.json`, `assets/logo.png`.
    pub file: String,
    /// The JSON path in plugin.json: `""` for the whole file, `id`, `permissions[2]`, `settings[0].key`.
    pub path: String,
    /// Where in plugin.json, UTF-16 offsets, when it could be found.
    pub span: Option<Span>,
    /// The app's own words when it refuses the install for this, with the
    /// plugin's name filled in, so the line can be matched to what the phone said.
    pub app_message: Option<String>,
    /// One line on what to do, with a link to the docs where one helps.
    pub help: Option<String>,
}

impl Problem {
    fn new(severity: Severity, code: &'static str, path: impl Into<String>, message: impl Into<String>) -> Problem {
        Problem { severity, code, message: message.into(), file: MANIFEST.to_string(), path: path.into(), span: None, app_message: None, help: None }
    }

    fn app(mut self, text: impl Into<String>) -> Problem {
        self.app_message = Some(text.into());
        self
    }

    fn help(mut self, text: impl Into<String>) -> Problem {
        self.help = Some(text.into());
        self
    }

    fn in_file(mut self, file: impl Into<String>) -> Problem {
        self.file = file.into();
        self
    }
}

pub const MANIFEST: &str = "plugin.json";

fn error(code: &'static str, path: impl Into<String>, message: impl Into<String>) -> Problem {
    Problem::new(Severity::Error, code, path, message)
}

fn warning(code: &'static str, path: impl Into<String>, message: impl Into<String>) -> Problem {
    Problem::new(Severity::Warning, code, path, message)
}

fn docs(page: &str) -> String {
    format!("{DOCS}{page}")
}

// ---- the decoded manifest ---------------------------------------------------

/// `PluginActionSpec`, as written.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Action {
    pub id: String,
    pub name: String,
    pub summary: String,
    pub icon: String,
    pub replaces: bool,
    pub input: String,
}

/// `PluginToolSpec`, as written.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Tool {
    pub icon: String,
    pub tile: bool,
    pub float: bool,
    pub keyword: String,
    pub chip: bool,
    pub height: i64,
    pub color: String,
    pub full_bleed: bool,
}

/// `PluginSettingSpec`, as written; [default] is the text the app reads.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Setting {
    pub key: String,
    pub kind: String,
    pub label: String,
    pub summary: String,
    pub default: String,
    /// Whether the default was written as text, rather than a bare `true` or `12`.
    pub default_is_text: bool,
    pub options: Vec<String>,
}

/// `PluginProviderSpec`, as written.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Provider {
    pub tool: String,
    pub id: String,
    pub name: String,
    pub summary: String,
    pub images: bool,
}

impl Provider {
    /// `providerId`: the id, or the tool's name when blank.
    pub fn provider_id(&self) -> String {
        let id = self.id.trim();
        if id.is_empty() { self.tool.trim().to_string() } else { id.to_string() }
    }
}

/// `PluginPreviewSpec`, as written.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Preview {
    pub id: String,
    pub name: String,
    pub extensions: Vec<String>,
    pub names: Vec<String>,
    pub mimes: Vec<String>,
    pub max_bytes: i64,
    pub thumbnails: bool,
}

/// `PluginSyntaxSpec`, as written.
#[derive(Clone, Debug, PartialEq)]
pub struct Syntax {
    pub id: String,
    pub name: String,
    pub extensions: Vec<String>,
    pub names: Vec<String>,
    pub grammar: Value,
}

/// `PluginManifest`, decoded the way the app decodes it: every field defaulted,
/// strings as written (the app trims and shortens them when it reads them;
/// [Manifest::name] and the like give what it keeps).
#[derive(Clone, Debug, PartialEq)]
pub struct Manifest {
    pub format: String,
    /// The container format version, `1`.
    pub version: i64,
    pub id: String,
    pub raw_name: String,
    pub plugin_version: String,
    pub author: String,
    pub description: String,
    /// The API level, as the app counts it: a missing field is 1, zero or less is 1.
    pub api_version: i64,
    /// Whether the file says `apiVersion` at all.
    pub api_given: bool,
    pub raw_entry: String,
    pub permissions: Vec<String>,
    /// The language, filled in from the API level when the file leaves it out.
    pub language: String,
    pub panel: bool,
    pub actions: Vec<Action>,
    pub hosts: Vec<String>,
    pub tool: Tool,
    pub settings: Vec<Setting>,
    pub providers: Vec<Provider>,
    pub previews: Vec<Preview>,
    pub syntax: Vec<Syntax>,
}

impl Manifest {
    /// The name the app shows: cleaned and cut to 40.
    pub fn name(&self) -> String {
        display_text(&self.raw_name, MAX_NAME)
    }

    /// The entry script's name: `main.py` unless the manifest names another.
    pub fn entry(&self) -> String {
        let e = display_text(&self.raw_entry, MAX_ENTRY);
        if e.is_empty() { DEFAULT_ENTRY.to_string() } else { e }
    }

    /// The id as the app stores it: trimmed and lowercase.
    pub fn clean_id(&self) -> String {
        self.id.trim().to_lowercase()
    }

    pub fn has_permission(&self, wire: &str) -> bool {
        self.permissions.iter().any(|p| p.trim() == wire)
    }

    /// The hosts as the app normalises them.
    pub fn clean_hosts(&self) -> Vec<String> {
        let mut out: Vec<String> = Vec::new();
        for h in self.hosts.iter().map(|h| normalise_host(h)) {
            if !out.contains(&h) {
                out.push(h);
            }
        }
        out
    }

    /// Whether the archive takes more than the entry: API 5 and up.
    pub fn package(&self) -> bool {
        self.api_version >= PACKAGE_API
    }

    /// Every file extension and whole file name the plugin's previews and
    /// grammars read, in the order it lists them (the addon repository's
    /// `fileTypes` and `fileNames`).
    pub fn file_types(&self) -> (Vec<String>, Vec<String>) {
        let mut types: Vec<String> = Vec::new();
        let mut names: Vec<String> = Vec::new();
        let lists = self.previews.iter().map(|p| (&p.extensions, &p.names)).chain(self.syntax.iter().map(|s| (&s.extensions, &s.names)));
        for (extensions, file_names) in lists {
            for e in extensions {
                let e = e.trim().to_lowercase();
                let e = e.trim_start_matches('.').to_string();
                if !e.is_empty() && !types.contains(&e) {
                    types.push(e);
                }
            }
            for n in file_names {
                if !names.contains(n) {
                    names.push(n.clone());
                }
            }
        }
        (types, names)
    }
}

/// What [check] found.
#[derive(Clone, Debug)]
pub struct Report {
    /// Every problem, in the order the app looks, with its place filled in.
    pub problems: Vec<Problem>,
    /// The manifest, when the text is a JSON object (even one with problems).
    pub manifest: Option<Manifest>,
}

impl Report {
    pub fn errors(&self) -> usize {
        self.problems.iter().filter(|p| p.severity == Severity::Error).count()
    }

    pub fn warnings(&self) -> usize {
        self.problems.iter().filter(|p| p.severity == Severity::Warning).count()
    }
}

// ---- reading ----------------------------------------------------------------

/// Everything wrong with [text] as a `plugin.json`, and the manifest it holds.
pub fn check(text: &str) -> Report {
    let value: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(e) => {
            let mut p = error("bad_json", "", format!("plugin.json is not valid JSON: {}", json_error_text(&e)))
                .app("This is not a WM Keyboard plugin.")
                .help("check for a missing comma, quote or bracket just before this point");
            p.span = json_error_span(text, &e);
            return Report { problems: vec![p], manifest: None };
        }
    };
    let Some(object) = value.as_object() else {
        let mut p = error("not_an_object", "", "plugin.json must be a JSON object: { \"format\": \"wmkeyboard-plugin\", ... }")
            .app("This is not a WM Keyboard plugin.")
            .help(format!("see the manifest's fields: {}", docs("/plugins/wm-package/#the-manifest")));
        p.span = locate(text, "");
        return Report { problems: vec![p], manifest: None };
    };
    let mut out = Vec::new();
    let manifest = decode(object, &mut out);
    rules(&manifest, object, &mut out);
    for p in &mut out {
        if p.span.is_none() && p.file == MANIFEST {
            p.span = locate(text, &p.path);
        }
    }
    Report { problems: out, manifest: Some(manifest) }
}

/// serde_json's message without its own "at line L column C" tail.
fn json_error_text(e: &serde_json::Error) -> String {
    let text = e.to_string();
    match text.rfind(" at line ") {
        Some(at) => text[..at].to_string(),
        None => text,
    }
}

/// The place serde_json stopped at, as a one-character span.
fn json_error_span(text: &str, e: &serde_json::Error) -> Option<Span> {
    let line = e.line();
    if line == 0 {
        return None;
    }
    let mut offset: u32 = 0;
    for (i, l) in text.split_inclusive('\n').enumerate() {
        if i + 1 == line {
            // serde_json counts the column in bytes, 1-based.
            let byte = e.column().saturating_sub(1).min(l.len());
            let mut b = byte;
            while b > 0 && !l.is_char_boundary(b) {
                b -= 1;
            }
            let units: u32 = l[..b].encode_utf16().count() as u32;
            let start = offset + units;
            return Some(Span::new(start, start + 1));
        }
        offset += l.encode_utf16().count() as u32;
    }
    let end = text.encode_utf16().count() as u32;
    Some(Span::new(end, end))
}

/// The decode: kotlinx.serialization with `ignoreUnknownKeys`, strict about
/// types. A field of the wrong type fails the whole decode, which the app
/// reports as "not a plugin"; here it is one problem, and the field keeps its
/// default so the other rules still run.
fn decode(o: &Map<String, Value>, out: &mut Vec<Problem>) -> Manifest {
    let mut d = Decoder { out };
    let api_given = o.contains_key("apiVersion");
    let api_raw = d.int(o, "apiVersion", "apiVersion").unwrap_or(1);
    let api = if !api_given || api_raw <= 0 { 1 } else { api_raw };
    let language_given = o.contains_key("language");
    let language = if language_given { d.string(o, "language", "language") } else { language_for(api).to_string() };
    let tool = match d.object(o, "tool", "tool") {
        Some(t) => Tool {
            icon: d.string(t, "icon", "tool.icon"),
            tile: d.boolean(t, "tile", "tool.tile").unwrap_or(false),
            float: d.boolean(t, "float", "tool.float").unwrap_or(false),
            keyword: d.string(t, "keyword", "tool.keyword"),
            chip: d.boolean(t, "chip", "tool.chip").unwrap_or(false),
            height: d.int(t, "height", "tool.height").unwrap_or(0),
            color: d.string(t, "color", "tool.color"),
            full_bleed: d.boolean(t, "fullBleed", "tool.fullBleed").unwrap_or(false),
        },
        None => Tool::default(),
    };
    let actions = d
        .objects(o, "actions", "actions")
        .into_iter()
        .map(|(path, a)| Action {
            id: d.string(a, "id", &format!("{path}.id")),
            name: d.string(a, "name", &format!("{path}.name")),
            summary: d.string(a, "summary", &format!("{path}.summary")),
            icon: d.string(a, "icon", &format!("{path}.icon")),
            replaces: d.boolean(a, "replaces", &format!("{path}.replaces")).unwrap_or(true),
            input: if a.contains_key("input") { d.string(a, "input", &format!("{path}.input")) } else { "selection".into() },
        })
        .collect();
    let settings = d
        .objects(o, "settings", "settings")
        .into_iter()
        .map(|(path, s)| {
            let (default, default_is_text) = match s.get("default") {
                None | Some(Value::Null) => (String::new(), true),
                Some(Value::String(t)) => (t.clone(), true),
                Some(Value::Bool(b)) => (b.to_string(), false),
                Some(Value::Number(n)) => (n.to_string(), false),
                Some(_) => {
                    d.wrong(&format!("{path}.default"), "text, such as \"true\" or \"5\"");
                    (String::new(), true)
                }
            };
            Setting {
                key: d.string(s, "key", &format!("{path}.key")),
                kind: if s.contains_key("type") { d.string(s, "type", &format!("{path}.type")) } else { "text".into() },
                label: d.string(s, "label", &format!("{path}.label")),
                summary: d.string(s, "summary", &format!("{path}.summary")),
                default,
                default_is_text,
                options: d.strings(s, "options", &format!("{path}.options")),
            }
        })
        .collect();
    let providers = d
        .objects(o, "providers", "providers")
        .into_iter()
        .map(|(path, p)| Provider {
            tool: d.string(p, "tool", &format!("{path}.tool")),
            id: d.string(p, "id", &format!("{path}.id")),
            name: d.string(p, "name", &format!("{path}.name")),
            summary: d.string(p, "summary", &format!("{path}.summary")),
            images: d.boolean(p, "images", &format!("{path}.images")).unwrap_or(false),
        })
        .collect();
    let previews = d
        .objects(o, "previews", "previews")
        .into_iter()
        .map(|(path, p)| Preview {
            id: d.string(p, "id", &format!("{path}.id")),
            name: d.string(p, "name", &format!("{path}.name")),
            extensions: d.strings(p, "extensions", &format!("{path}.extensions")),
            names: d.strings(p, "names", &format!("{path}.names")),
            mimes: d.strings(p, "mimes", &format!("{path}.mimes")),
            max_bytes: d.int(p, "maxBytes", &format!("{path}.maxBytes")).unwrap_or(DEFAULT_MAX_BYTES),
            thumbnails: d.boolean(p, "thumbnails", &format!("{path}.thumbnails")).unwrap_or(false),
        })
        .collect();
    let syntax = d
        .objects(o, "syntax", "syntax")
        .into_iter()
        .map(|(path, s)| Syntax {
            id: d.string(s, "id", &format!("{path}.id")),
            name: d.string(s, "name", &format!("{path}.name")),
            extensions: d.strings(s, "extensions", &format!("{path}.extensions")),
            names: d.strings(s, "names", &format!("{path}.names")),
            grammar: s.get("grammar").cloned().unwrap_or(Value::Null),
        })
        .collect();
    Manifest {
        format: d.string(o, "format", "format"),
        version: d.int(o, "version", "version").unwrap_or(1),
        id: d.string(o, "id", "id"),
        raw_name: d.string(o, "name", "name"),
        plugin_version: d.string(o, "pluginVersion", "pluginVersion"),
        author: d.string(o, "author", "author"),
        description: d.string(o, "description", "description"),
        api_version: api,
        api_given,
        raw_entry: if o.contains_key("entry") { d.string(o, "entry", "entry") } else { DEFAULT_ENTRY.into() },
        permissions: d.strings(o, "permissions", "permissions"),
        language,
        panel: d.boolean(o, "panel", "panel").unwrap_or(true),
        actions,
        hosts: d.strings(o, "hosts", "hosts"),
        tool,
        settings,
        providers,
        previews,
        syntax,
    }
}

struct Decoder<'a> {
    out: &'a mut Vec<Problem>,
}

impl Decoder<'_> {
    fn wrong(&mut self, path: &str, what: &str) {
        self.out.push(
            error("wrong_type", path, format!("{path} must be {what}"))
                .app("This is not a WM Keyboard plugin.")
                .help(format!("the app cannot read a manifest with a field of the wrong type: {}", docs("/plugins/wm-package/#the-manifest"))),
        );
    }

    fn string(&mut self, o: &Map<String, Value>, key: &str, path: &str) -> String {
        match o.get(key) {
            None => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(_) => {
                self.wrong(path, "text, in quotes");
                String::new()
            }
        }
    }

    fn int(&mut self, o: &Map<String, Value>, key: &str, path: &str) -> Option<i64> {
        match o.get(key) {
            None => None,
            Some(Value::Number(n)) if n.as_i64().is_some() => n.as_i64(),
            Some(_) => {
                self.wrong(path, "a whole number, without quotes");
                None
            }
        }
    }

    fn boolean(&mut self, o: &Map<String, Value>, key: &str, path: &str) -> Option<bool> {
        match o.get(key) {
            None => None,
            Some(Value::Bool(b)) => Some(*b),
            Some(_) => {
                self.wrong(path, "true or false, without quotes");
                None
            }
        }
    }

    fn strings(&mut self, o: &Map<String, Value>, key: &str, path: &str) -> Vec<String> {
        match o.get(key) {
            None => Vec::new(),
            Some(Value::Array(items)) => {
                let mut out = Vec::new();
                for (i, item) in items.iter().enumerate() {
                    match item {
                        Value::String(s) => out.push(s.clone()),
                        _ => self.wrong(&format!("{path}[{i}]"), "text, in quotes"),
                    }
                }
                out
            }
            Some(_) => {
                self.wrong(path, "a list: [\"...\", \"...\"]");
                Vec::new()
            }
        }
    }

    fn object<'v>(&mut self, o: &'v Map<String, Value>, key: &str, path: &str) -> Option<&'v Map<String, Value>> {
        match o.get(key) {
            None => None,
            Some(Value::Object(m)) => Some(m),
            Some(_) => {
                self.wrong(path, "an object: { ... }");
                None
            }
        }
    }

    fn objects<'v>(&mut self, o: &'v Map<String, Value>, key: &str, path: &str) -> Vec<(String, &'v Map<String, Value>)> {
        match o.get(key) {
            None => Vec::new(),
            Some(Value::Array(items)) => {
                let mut out = Vec::new();
                for (i, item) in items.iter().enumerate() {
                    match item {
                        Value::Object(m) => out.push((format!("{path}[{i}]"), m)),
                        _ => self.wrong(&format!("{path}[{i}]"), "an object: { ... }"),
                    }
                }
                out
            }
            Some(_) => {
                self.wrong(path, "a list of objects: [{ ... }]");
                Vec::new()
            }
        }
    }
}

/// `PluginManifestCodec.languageFor`.
pub fn language_for(api: i64) -> &'static str {
    if api >= PYTHON_ONLY_API { "python" } else { "lua" }
}

/// The rules of `PluginManifestCodec.problems`, `actionProblems` and
/// `packageProblems`, in the order the app checks them, one problem per value.
fn rules(m: &Manifest, o: &Map<String, Value>, out: &mut Vec<Problem>) {
    let name = m.name();
    let shown = if name.is_empty() { "This plugin".to_string() } else { format!("“{name}”") };
    let quoted = |s: &str| format!("“{s}”");
    let manifest_help = || docs("/plugins/wm-package/#the-manifest");

    if m.format.trim() != FORMAT {
        let what = if m.format.is_empty() { "plugin.json has no \"format\"".to_string() } else { format!("\"format\" is \"{}\"", m.format) };
        out.push(
            error("not_a_plugin", if o.contains_key("format") { "format" } else { "" }, format!("{what}; it must be \"{FORMAT}\""))
                .app("This is not a WM Keyboard plugin.")
                .help(format!("add \"format\": \"{FORMAT}\" at the top: {}", manifest_help())),
        );
    }

    // ---- the API level -------------------------------------------------------
    if m.api_version > API_VERSION {
        out.push(
            error("newer_api", "apiVersion", format!("apiVersion {} is newer than this version of WM Keyboard runs (it runs up to {API_VERSION})", m.api_version))
                .app("This plugin needs a newer version of WM Keyboard.")
                .help(format!("write \"apiVersion\": {API_VERSION}")),
        );
    }

    // ---- id, name, version ------------------------------------------------
    let id = m.clean_id();
    if !is_valid_id(&id) {
        let what = if id.is_empty() { "plugin.json has no \"id\"".to_string() } else { format!("the id \"{}\" is not valid", m.id) };
        out.push(
            error("bad_id", if o.contains_key("id") { "id" } else { "" }, format!("{what}: an id is 3 to 64 characters of a-z, 0-9, dots, dashes and underscores, starting with a letter or digit"))
                .app("This plugin has an id that is not valid. An id is 3 to 64 characters long. An id can contain lowercase letters, numbers, dots, dashes and underscores.")
                .help("use reverse-domain style, such as \"com.yourname.tool\""),
        );
    } else if m.id != id {
        out.push(warning("id_case", "id", format!("the id is read as \"{id}\": the app trims it and makes it lowercase")).help(format!("write \"id\": \"{id}\"")));
    }
    if name.is_empty() {
        out.push(
            error("no_name", if o.contains_key("name") { "name" } else { "" }, "plugin.json has no \"name\"")
                .app("This plugin does not have a name.")
                .help("add a name of up to 40 characters"),
        );
    } else {
        cut_warning(out, "name", &m.raw_name, MAX_NAME);
    }
    if display_text(&m.plugin_version, MAX_VERSION).is_empty() {
        let mut p = error("no_version", if o.contains_key("pluginVersion") { "pluginVersion" } else { "" }, "plugin.json has no \"pluginVersion\"")
            .app(format!("{shown} does not say which version it is."))
            .help("add \"pluginVersion\": \"1.0.0\"");
        if o.get("version").is_some_and(Value::is_string) {
            p.help = Some("\"version\" is the file format's number (1); the plugin's own version goes in \"pluginVersion\"".into());
        }
        out.push(p);
    } else {
        cut_warning(out, "pluginVersion", &m.plugin_version, MAX_VERSION);
    }
    cut_warning(out, "author", &m.author, MAX_AUTHOR);
    cut_warning(out, "description", &m.description, MAX_DESCRIPTION);
    let entry = m.entry();
    if !entry.ends_with(".py") {
        out.push(warning("entry_not_python", "entry", format!("the entry \"{entry}\" is not a .py file")).help("plugins are Python: the entry is usually main.py"));
    }

    // ---- permissions -------------------------------------------------------
    let mut seen: Vec<&str> = Vec::new();
    for (i, p) in m.permissions.iter().enumerate() {
        let path = format!("permissions[{i}]");
        if i >= MAX_PERMISSIONS {
            out.push(warning("too_many_permissions", path, format!("the app reads the first {MAX_PERMISSIONS} permissions and ignores the rest")));
            continue;
        }
        match permission(p) {
            None => {
                let near = crate::text::closest(p.trim(), PERMISSIONS.iter().map(|x| x.wire));
                let mut problem = error("unknown_permission", path, format!("WM Keyboard does not know the permission \"{}\"", p.trim()))
                    .app(format!("This version of WM Keyboard does not know the permission {}. The app cannot tell you what {shown} is allowed to do.", quoted(&display_text(p, 32))));
                problem.help = Some(match near {
                    Some(n) => format!("did you mean \"{n}\"? The permissions: {}", docs("/plugins/permissions/")),
                    None => format!("check the spelling against {}", docs("/plugins/permissions/")),
                });
                out.push(problem);
            }
            Some(known) => {
                if seen.contains(&known.wire) {
                    out.push(warning("duplicate_permission", path, format!("\"{}\" is listed twice", known.wire)));
                }
                seen.push(known.wire);
            }
        }
    }

    // ---- language and selection actions (actionProblems) --------------------------------
    match m.language.trim() {
        "python" => {}
        "lua" => {
            let (path, message) = if !m.api_given {
                ("", "plugin.json has no \"apiVersion\", so the app reads it as an old Lua plugin".to_string())
            } else if o.contains_key("language") {
                ("language", "\"language\": \"lua\": Lua plugins no longer run".to_string())
            } else {
                ("apiVersion", format!("apiVersion {} is a Lua plugin's level; Python plugins are 4 or 5", m.api_version))
            };
            out.push(
                error("lua", path, message)
                    .app(format!("{shown} is written in Lua, which WM Keyboard no longer runs. Look for its Python version."))
                    .help(format!("write \"apiVersion\": {API_VERSION}")),
            );
        }
        other => out.push(
            error("unknown_language", "language", format!("the language \"{other}\" does not run; plugins are Python"))
                .app(format!("{shown} is written in a language this version of WM Keyboard cannot run. Plugins are written in Python."))
                .help("leave \"language\" out, or write \"python\""),
        ),
    }
    if m.language.trim() == "python" && m.api_version < PYTHON_ONLY_API && m.api_given {
        out.push(warning("old_api", "apiVersion", format!("apiVersion {} is from before Python plugins; new plugins say {API_VERSION}", m.api_version)).help(docs("/plugins/help/#api-4-or-api-5")));
    }
    let selection = m.has_permission("selection");
    let actions_help = || docs("/plugins/guides/text-and-selection/#buttons-over-selected-text");
    if !m.actions.is_empty() && !selection {
        out.push(
            error("actions_need_selection", "actions", "plugin.json lists actions but not the \"selection\" permission")
                .app(format!("{shown} adds actions for selected text but does not ask permission to change it."))
                .help(format!("add \"selection\" to the permissions: {}", actions_help())),
        );
    } else if m.actions.is_empty() && selection {
        let at = m.permissions.iter().position(|p| p.trim() == "selection").unwrap_or(0);
        out.push(
            error("selection_without_actions", format!("permissions[{at}]"), "the \"selection\" permission is only for selection actions, and plugin.json lists none")
                .app(format!("{shown} asks to change text you select but does not add any actions."))
                .help(format!("add \"actions\", or drop \"selection\": {}", actions_help())),
        );
    } else if m.actions.is_empty() && m.providers.is_empty() && m.previews.is_empty() && m.syntax.is_empty() && !m.panel {
        out.push(
            error("nothing_to_show", "panel", "\"panel\": false, and there are no actions, providers, previews or grammars: nothing would show")
                .app(format!("{shown} has no panel and no actions, so there is nothing for it to do."))
                .help("leave \"panel\" out for a plugin that draws a panel"),
        );
    } else if m.actions.len() > MAX_ACTIONS {
        out.push(
            error("too_many_actions", format!("actions[{MAX_ACTIONS}]"), format!("{} actions; at most {MAX_ACTIONS}", m.actions.len()))
                .app(format!("{shown} adds more than 16 actions.")),
        );
    }
    let mut ids: Vec<String> = Vec::new();
    for (i, a) in m.actions.iter().enumerate() {
        let id = a.id.trim().to_string();
        let path = format!("actions[{i}]");
        let app_bad_id = format!("{shown} has an action whose id is missing, repeated, or not valid. An action id is up to 32 lowercase letters, numbers, dashes and underscores.");
        if !is_action_id(&id) {
            let what = if id.is_empty() { "has no id".to_string() } else { format!("has the id \"{id}\", which is not valid") };
            out.push(
                error("bad_action_id", if a.id.is_empty() { path.clone() } else { format!("{path}.id") }, format!("action {} {what}: up to 32 of a-z, 0-9, - and _", i + 1))
                    .app(app_bad_id),
            );
        } else if ids.contains(&id) {
            out.push(error("duplicate_action_id", format!("{path}.id"), format!("two actions have the id \"{id}\"")).app(app_bad_id));
        } else if display_text(&a.name, MAX_ACTION_NAME).is_empty() {
            out.push(
                error("action_no_name", path.clone(), format!("the action \"{id}\" has no name; the name is what its chip says"))
                    .app(format!("{shown} has an action without a name.")),
            );
        } else if !ACTION_INPUTS.contains(&a.input.trim()) {
            out.push(
                error("action_input", format!("{path}.input"), format!("the action \"{id}\" asks for the input \"{}\"; it is \"selection\" or \"none\"", a.input.trim()))
                    .app(format!("{shown} has an action that asks for text this version of WM Keyboard cannot give it.")),
            );
        } else {
            cut_warning(out, &format!("{path}.name"), &a.name, MAX_ACTION_NAME);
            cut_warning(out, &format!("{path}.summary"), &a.summary, MAX_ACTION_SUMMARY);
        }
        ids.push(id);
    }

    // ---- the API 5 fields (packageProblems) ---------------------------------------
    let level = m.api_version;
    let needs_5 = |what: &str| format!("{shown} asks for “{what}”, which only plugins written for API 5 can use.");
    for (i, p) in m.permissions.iter().enumerate() {
        if let Some(known) = permission(p) {
            if known.since_api > level {
                out.push(
                    error("permission_needs_api_5", format!("permissions[{i}]"), format!("\"{}\" is an API 5 permission, and this plugin says apiVersion {level}", known.wire))
                        .app(needs_5(known.wire))
                        .help(format!("write \"apiVersion\": 5: {}", docs("/plugins/help/#api-4-or-api-5"))),
                );
            }
        }
    }
    let hosts: Vec<String> = m.hosts.iter().map(|h| normalise_host(h)).collect();
    let host_setting = m.settings.iter().any(|s| s.kind.trim() == "host");
    let network = m.has_permission("network");
    let hosts_help = || docs("/plugins/permissions/#hosts");
    if network && hosts.is_empty() && !host_setting {
        let at = m.permissions.iter().position(|p| p.trim() == "network").unwrap_or(0);
        out.push(
            error("network_without_hosts", if o.contains_key("hosts") { "hosts".to_string() } else { format!("permissions[{at}]") }, "the \"network\" permission needs \"hosts\": the websites the plugin may reach")
                .app(format!("{shown} asks to connect to the internet but does not list any websites."))
                .help(format!("add \"hosts\": [\"api.example.com\"]: {}", hosts_help())),
        );
    }
    let app_bad_host = |h: &str| format!("{shown} lists a website this version of WM Keyboard cannot allow: {}. Websites are names like api.example.com or *.example.com.", quoted(&display_text(h, 32)));
    for (i, (raw, h)) in m.hosts.iter().zip(&hosts).enumerate() {
        let path = format!("hosts[{i}]");
        if !is_valid_host(h) {
            let mut p = error("bad_host", path, format!("\"{}\" is not a host name the app can allow", raw.trim())).app(app_bad_host(h));
            p.help = Some(host_hint(raw));
            out.push(p);
        } else if i >= MAX_HOSTS {
            out.push(error("too_many_hosts", path, format!("{} hosts; at most {MAX_HOSTS}", hosts.len())).app(app_bad_host(h)).help(format!("use one *.example.com entry for a site's subdomains: {}", hosts_help())));
        } else if raw.trim() != h {
            out.push(warning("host_written_long", path, format!("\"{}\" is read as \"{h}\"", raw.trim())).help(format!("write \"{h}\"")));
        }
    }
    if m.has_permission("network.local") && (!network || !host_setting) {
        let at = m.permissions.iter().position(|p| p.trim() == "network.local").unwrap_or(0);
        out.push(
            warning("network_local_unused", format!("permissions[{at}]"), "\"network.local\" widens a \"host\" setting only; it needs \"network\" and a setting of type \"host\"")
                .help(docs("/plugins/permissions/#networklocal")),
        );
    }
    let t = &m.tool;
    let app_bad_tool = format!("{shown} has a tool keyword that is not valid. A keyword is up to 16 lowercase letters and numbers.");
    if !t.keyword.trim().is_empty() && !is_keyword(t.keyword.trim()) {
        out.push(error("bad_keyword", "tool.keyword", format!("the keyword \"{}\" is not valid: up to 16 lowercase letters and digits", t.keyword.trim())).app(app_bad_tool.clone()));
    }
    if !is_icon(t.icon.trim()) {
        out.push(
            error("bad_icon", "tool.icon", format!("the icon \"{}\" is not valid: a Material Symbols name such as \"cloud\", or \"assets/icon.svg\"", t.icon.trim()))
                .app(app_bad_tool)
                .help(docs("/plugins/guides/pages-and-timers/#your-icon")),
        );
    }
    if !t.color.trim().is_empty() && !is_hex_color(t.color.trim()) {
        out.push(warning("bad_color", "tool.color", format!("the colour \"{}\" is not #RRGGBB, so the keyboard uses the theme's icon colour", t.color.trim())));
    }
    if t.height < 0 || t.height > MAX_TOOL_HEIGHT {
        out.push(warning("tool_height", "tool.height", format!("the height {} is read as {} (0 to {MAX_TOOL_HEIGHT} dp)", t.height, t.height.clamp(0, MAX_TOOL_HEIGHT))));
    }

    settings_rules(m, &shown, out);
    provider_rules(m, &shown, out);
    preview_rules(m, &shown, out);
    syntax_rules(m, &shown, out);

    let icon_only = Tool { icon: String::new(), color: String::new(), full_bleed: false, ..t.clone() } == Tool::default() && !t.icon.trim().starts_with("assets/");
    let fields: Vec<&str> = [
        (!hosts.is_empty(), "hosts"),
        (!m.settings.is_empty(), "settings"),
        (!m.providers.is_empty(), "providers"),
        (!m.previews.is_empty(), "previews"),
        (!m.syntax.is_empty(), "syntax"),
        (*t != Tool::default() && !icon_only, "tool"),
    ]
    .iter()
    .filter(|(used, _)| *used)
    .map(|(_, f)| *f)
    .collect();
    if !fields.is_empty() && level < PACKAGE_API {
        out.push(
            error("needs_api_5", fields[0], format!("{} {} API 5, and this plugin says apiVersion {level}", fields.join(", "), if fields.len() == 1 { "is" } else { "are" }))
                .app(needs_5("hosts/tool/settings/providers/previews/syntax"))
                .help(format!("write \"apiVersion\": 5: {}", docs("/plugins/help/#api-4-or-api-5"))),
        );
    }
}

/// A warning when [raw] is longer than the app keeps, or loses characters it cannot show.
fn cut_warning(out: &mut Vec<Problem>, path: &str, raw: &str, max: usize) {
    let kept = display_text(raw, max);
    let trimmed = raw.trim();
    if kept == trimmed {
        return;
    }
    let clean: String = trimmed.chars().filter(|c| displayable(*c)).collect();
    let message = if clean.trim() == kept {
        format!("{path} has characters the app drops (control or formatting characters), so it shows \"{kept}\"")
    } else {
        format!("{path} is longer than the app keeps ({max} characters): it shows \"{kept}\"")
    };
    out.push(warning("cut_short", path, message));
}

fn host_hint(raw: &str) -> String {
    let t = raw.trim().to_lowercase();
    if t.starts_with("http://") || t.contains("://") {
        "write the host alone, without http:// (https is the only scheme): \"api.example.com\"".into()
    } else if t.contains('/') || t.contains('?') {
        "write the host alone, without a path: \"api.example.com\"".into()
    } else if t.contains(':') {
        "write the host alone, without a port; a server the user runs goes in a setting of type \"host\"".into()
    } else if t == "localhost" || t.split('.').all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_digit())) {
        "IP addresses and localhost are not allowed; a server on the user's network goes in a setting of type \"host\"".into()
    } else {
        format!("a host is a name like api.example.com or *.example.com: {}", docs("/plugins/permissions/#hosts"))
    }
}

fn settings_rules(m: &Manifest, shown: &str, out: &mut Vec<Problem>) {
    let app = format!("{shown} has a setting that is missing its key or label, repeats a key, or has a type this version cannot draw.");
    let help = || docs("/plugins/guides/saving-and-settings/#a-settings-page");
    if m.settings.len() > MAX_SETTINGS {
        out.push(error("too_many_settings", format!("settings[{MAX_SETTINGS}]"), format!("{} settings; at most {MAX_SETTINGS}", m.settings.len())).app(app.clone()));
    }
    let storage = m.has_permission("storage");
    let mut keys: Vec<String> = Vec::new();
    for (i, s) in m.settings.iter().enumerate() {
        let path = format!("settings[{i}]");
        let key = s.key.trim().to_string();
        let kind = s.kind.trim();
        let mut bad = |code: &'static str, field: &str, message: String| {
            let p = if field.is_empty() { path.clone() } else { format!("{path}.{field}") };
            out.push(error(code, p, message).app(app.clone()).help(help()));
        };
        if !is_setting_key(&key) {
            let what = if key.is_empty() { "has no key".to_string() } else { format!("has the key \"{key}\", which is not valid") };
            bad("bad_setting_key", if s.key.is_empty() { "" } else { "key" }, format!("setting {} {what}: a lowercase letter or _, then up to 31 of a-z, 0-9 and _", i + 1));
        } else if keys.contains(&key) {
            bad("duplicate_setting_key", "key", format!("two settings have the key \"{key}\""));
        }
        if display_text(&s.label, MAX_SETTING_TEXT).is_empty() {
            bad("setting_no_label", "", format!("the setting \"{key}\" has no label; the label is what its row says"));
        }
        if !SETTING_TYPES.contains(&kind) {
            let near = crate::text::closest(kind, SETTING_TYPES.iter().copied());
            let hint = near.map(|n| format!(" Did you mean \"{n}\"?")).unwrap_or_default();
            bad("bad_setting_type", "type", format!("the setting \"{key}\" has the type \"{kind}\"; the types are {}.{hint}", SETTING_TYPES.join(", ")));
        } else if kind == "choice" && s.options.is_empty() {
            bad("choice_without_options", "", format!("the choice \"{key}\" has no options"));
        } else if kind == "choice" && s.options.len() > MAX_SETTING_OPTIONS {
            bad("too_many_options", "options", format!("the choice \"{key}\" has {} options; at most {MAX_SETTING_OPTIONS}", s.options.len()));
        } else if kind == "secret" && !storage {
            bad("secret_needs_storage", "type", format!("the secret \"{key}\" is kept with the plugin's secrets, which need the \"storage\" permission"));
        }
        keys.push(key.clone());
        // What the app does not refuse, but reads differently than written.
        let default_path = format!("{path}.default");
        if !s.default_is_text {
            out.push(warning("setting_default_not_text", default_path.clone(), format!("the default of \"{key}\" is read as the text \"{}\"; older versions of the app refuse a bare value", s.default)).help(format!("write \"default\": \"{}\"", s.default)));
        }
        let default = display_text(&s.default, MAX_SETTING_DEFAULT);
        if default.is_empty() {
            continue;
        }
        let fits = match kind {
            "switch" => default == "true" || default == "false",
            "number" => default.parse::<f64>().is_ok_and(f64::is_finite),
            "choice" => s.options.iter().any(|o| display_text(o, MAX_SETTING_TEXT) == default),
            "secret" => {
                out.push(warning("secret_default", default_path.clone(), format!("a secret's default is ignored: \"{key}\" starts unset")));
                true
            }
            _ => true,
        };
        if !fits {
            let what = match kind {
                "switch" => "\"true\" or \"false\"".to_string(),
                "number" => "a number, such as \"5\"".to_string(),
                _ => format!("one of the options: {}", s.options.iter().map(|o| format!("\"{o}\"")).collect::<Vec<_>>().join(", ")),
            };
            out.push(warning("setting_default", default_path, format!("the default \"{default}\" of the {kind} \"{key}\" is not {what}")));
        }
    }
}

fn provider_rules(m: &Manifest, shown: &str, out: &mut Vec<Problem>) {
    let app = format!("{shown} offers a provider for a list this version does not know, repeats one, or offers more than eight.");
    let help = || docs("/plugins/guides/providers/");
    if m.providers.len() > MAX_PROVIDERS {
        out.push(error("too_many_providers", format!("providers[{MAX_PROVIDERS}]"), format!("{} providers; at most {MAX_PROVIDERS}", m.providers.len())).app(app.clone()));
    }
    let mut keys: Vec<String> = Vec::new();
    for (i, p) in m.providers.iter().enumerate() {
        let path = format!("providers[{i}]");
        let tool = p.tool.trim();
        let id = p.provider_id();
        if !PROVIDER_TOOLS.contains(&tool) {
            let near = crate::text::closest(tool, PROVIDER_TOOLS.iter().copied());
            let hint = match near {
                Some(n) => format!("did you mean \"{n}\"? The lists: {}", help()),
                None => format!("the lists are {}", PROVIDER_TOOLS.join(", ")),
            };
            let what = if tool.is_empty() { "has no tool".to_string() } else { format!("answers \"{tool}\", which is not a provider list") };
            out.push(error("unknown_provider_tool", if p.tool.is_empty() { path.clone() } else { format!("{path}.tool") }, format!("provider {} {what}", i + 1)).app(app.clone()).help(hint));
        } else if !is_action_id(&id) {
            out.push(error("bad_provider_id", format!("{path}.id"), format!("the provider id \"{id}\" is not valid: up to 32 of a-z, 0-9, - and _")).app(app.clone()));
        } else {
            let key = format!("{tool}/{id}");
            if keys.contains(&key) {
                out.push(error("duplicate_provider", path.clone(), format!("two providers are \"{key}\"; give one an \"id\"")).app(app.clone()));
            }
            keys.push(key);
        }
        if p.images && tool != "search" {
            out.push(warning("provider_images", format!("{path}.images"), "\"images\" is only for a search provider; the app ignores it here"));
        }
    }
}

fn preview_rules(m: &Manifest, shown: &str, out: &mut Vec<Problem>) {
    let app = format!("{shown} lists a file preview without an id or file types, repeats one, or lists more than 16.");
    let help = || docs("/plugins/guides/file-previews/#declaring-a-preview");
    if m.previews.len() > MAX_PREVIEWS {
        out.push(error("too_many_previews", format!("previews[{MAX_PREVIEWS}]"), format!("{} previews; at most {MAX_PREVIEWS}", m.previews.len())).app(app.clone()));
    }
    let mut ids: Vec<String> = Vec::new();
    for (i, p) in m.previews.iter().enumerate() {
        let path = format!("previews[{i}]");
        let id = p.id.trim().to_string();
        let mut bad = |code: &'static str, field: String, message: String| out.push(error(code, field, message).app(app.clone()).help(help()));
        if !is_action_id(&id) {
            bad("bad_preview_id", format!("{path}.id"), format!("preview {} has the id \"{id}\": up to 32 of a-z, 0-9, - and _", i + 1));
        } else if ids.contains(&id) {
            bad("duplicate_preview", format!("{path}.id"), format!("two previews have the id \"{id}\""));
        }
        ids.push(id.clone());
        file_type_rules(&path, &p.extensions, &p.names, MAX_PREVIEW_EXTENSIONS, &mut bad);
        if p.extensions.is_empty() && p.names.is_empty() && p.mimes.is_empty() {
            bad("preview_no_types", path.clone(), format!("the preview \"{id}\" lists no extensions, names or mimes"));
        }
        if p.mimes.len() > MAX_MIMES {
            bad("too_many_mimes", format!("{path}.mimes"), format!("{} mimes; at most {MAX_MIMES}", p.mimes.len()));
        }
        for (j, mime) in p.mimes.iter().enumerate() {
            let clean = mime.trim().to_lowercase();
            if !is_mime(&clean) {
                bad("bad_mime", format!("{path}.mimes[{j}]"), format!("\"{}\" is not a MIME type such as \"text/csv\" or \"audio/*\"", mime.trim()));
            }
        }
        if p.max_bytes < 1 || p.max_bytes > MAX_MAX_BYTES {
            out.push(warning("max_bytes", format!("{path}.maxBytes"), format!("maxBytes {} is read as {} (1 byte to 16 MB)", p.max_bytes, p.max_bytes.clamp(1, MAX_MAX_BYTES))));
        }
    }
    let declares = m.has_permission("previews");
    if !m.previews.is_empty() && !declares {
        out.push(
            error("previews_need_permission", "previews", "plugin.json lists previews but not the \"previews\" permission")
                .app(format!("{shown} shows file previews but does not ask for the previews permission."))
                .help("add \"previews\" to the permissions"),
        );
    } else if m.previews.is_empty() && declares {
        let at = m.permissions.iter().position(|p| p.trim() == "previews").unwrap_or(0);
        out.push(
            error("previews_permission_unused", format!("permissions[{at}]"), "the \"previews\" permission is only for file previews, and plugin.json lists none")
                .app(format!("{shown} asks for the previews permission but lists no file previews."))
                .help(help()),
        );
    }
}

/// The extension and name rules previews and grammars share.
fn file_type_rules(path: &str, extensions: &[String], names: &[String], max_extensions: usize, bad: &mut dyn FnMut(&'static str, String, String)) {
    if extensions.len() > max_extensions {
        bad("too_many_extensions", format!("{path}.extensions"), format!("{} extensions; at most {max_extensions}", extensions.len()));
    }
    for (j, e) in extensions.iter().enumerate() {
        let clean = e.trim().to_lowercase();
        let clean = clean.strip_prefix('.').unwrap_or(&clean);
        if !is_extension(clean) {
            bad("bad_extension", format!("{path}.extensions[{j}]"), format!("\"{}\" is not an extension: lowercase, no dot, up to 16 characters, such as \"csv\" or \"tar.gz\"", e.trim()));
        }
    }
    if names.len() > MAX_NAMES {
        bad("too_many_names", format!("{path}.names"), format!("{} names; at most {MAX_NAMES}", names.len()));
    }
    for (j, n) in names.iter().enumerate() {
        if !is_file_name(n.trim()) {
            bad("bad_file_name", format!("{path}.names[{j}]"), format!("\"{}\" is not a whole file name such as \"Dockerfile\": up to 40 of A-Z, a-z, 0-9, . _ + -", n.trim()));
        }
    }
}

fn syntax_rules(m: &Manifest, shown: &str, out: &mut Vec<Problem>) {
    let app = format!("{shown} adds syntax colouring this version cannot read: a grammar without file types, an expression that does not compile, or more than 16.");
    let help = || docs("/plugins/guides/file-previews/#syntax-colours");
    if m.syntax.len() > MAX_SYNTAX {
        out.push(error("too_many_grammars", format!("syntax[{MAX_SYNTAX}]"), format!("{} grammars; at most {MAX_SYNTAX}", m.syntax.len())).app(app.clone()));
    }
    let mut ids: Vec<String> = Vec::new();
    for (i, s) in m.syntax.iter().enumerate() {
        let path = format!("syntax[{i}]");
        let id = s.id.trim().to_string();
        let mut bad = |code: &'static str, field: String, message: String| out.push(error(code, field, message).app(app.clone()).help(help()));
        if !is_action_id(&id) {
            bad("bad_grammar_id", format!("{path}.id"), format!("grammar {} has the id \"{id}\": up to 32 of a-z, 0-9, - and _", i + 1));
        } else if ids.contains(&id) {
            bad("duplicate_grammar", format!("{path}.id"), format!("two grammars have the id \"{id}\""));
        }
        ids.push(id.clone());
        if s.extensions.is_empty() && s.names.is_empty() {
            bad("grammar_no_types", path.clone(), format!("the grammar \"{id}\" lists no extensions or names"));
        }
        file_type_rules(&path, &s.extensions, &s.names, MAX_SYNTAX_EXTENSIONS, &mut bad);
        match &s.grammar {
            Value::Object(g) => {
                if let Some((field, why)) = grammar_problem(g) {
                    bad("bad_grammar", format!("{path}.grammar{field}"), format!("the grammar \"{id}\": {why}"));
                }
            }
            Value::String(asset) if asset.starts_with("assets/") => {
                let name = &asset["assets/".len()..];
                if !is_asset_name(name) || !name.ends_with(".json") {
                    bad("bad_grammar", format!("{path}.grammar"), format!("\"{asset}\" is not a grammar file: assets/<name>.json, a flat lowercase name"));
                }
            }
            _ => bad("bad_grammar", format!("{path}.grammar"), format!("the grammar \"{id}\" is neither a grammar object nor \"assets/<name>.json\"")),
        }
    }
}

/// `PluginSyntax.compile` without the regex compiler: the shape and the caps.
/// Whether each `match` compiles is the phone's Java regex engine's to say.
fn grammar_problem(g: &Map<String, Value>) -> Option<(String, String)> {
    let content = |v: &Value| -> Option<String> {
        match v {
            Value::String(s) => Some(s.clone()),
            Value::Number(n) => Some(n.to_string()),
            Value::Bool(b) => Some(b.to_string()),
            _ => None,
        }
    };
    let units = |s: &str| s.encode_utf16().count();
    let rules = g.get("rules").and_then(Value::as_array).map(Vec::as_slice).unwrap_or(&[]);
    if rules.len() > MAX_GRAMMAR_RULES {
        return Some((".rules".into(), format!("a grammar has at most {MAX_GRAMMAR_RULES} rules")));
    }
    for (i, rule) in rules.iter().enumerate() {
        let at = format!(".rules[{i}]");
        let Some(rule) = rule.as_object() else { return Some((at, format!("rule {} is not an object", i + 1))) };
        let Some(pattern) = rule.get("match").and_then(content) else { return Some((at, format!("rule {} has no match", i + 1))) };
        if pattern.is_empty() || units(&pattern) > MAX_GRAMMAR_PATTERN {
            return Some((format!("{at}.match"), format!("rule {}: a match is 1 to {MAX_GRAMMAR_PATTERN} characters", i + 1)));
        }
        let kind = rule.get("kind").and_then(content).unwrap_or_default();
        if !GRAMMAR_KINDS.contains(&kind.trim().to_lowercase().as_str()) {
            return Some((format!("{at}.kind"), format!("rule {}: no kind '{kind}'; the kinds are {}", i + 1, GRAMMAR_KINDS.join(", "))));
        }
    }
    for key in ["keywords", "types", "constants"] {
        let n = g.get(key).and_then(Value::as_array).map(|a| a.iter().filter(|v| content(v).is_some()).count()).unwrap_or(0);
        if n > MAX_GRAMMAR_WORDS {
            return Some((format!(".{key}"), format!("{key} has at most {MAX_GRAMMAR_WORDS} words")));
        }
    }
    for key in ["lineComment", "strings"] {
        let markers: Vec<String> = match g.get(key) {
            Some(Value::Array(a)) => a.iter().filter_map(content).collect(),
            Some(v) => content(v).into_iter().collect(),
            None => Vec::new(),
        };
        if markers.iter().any(|m| units(m) > MAX_GRAMMAR_MARKER) {
            return Some((format!(".{key}"), format!("{key} markers are at most {MAX_GRAMMAR_MARKER} characters")));
        }
    }
    if let Some(Value::Array(v)) = g.get("blockComment") {
        let pairs: Vec<&[Value]> = if v.iter().all(|x| !x.is_array() && !x.is_object()) { vec![v.as_slice()] } else { v.iter().filter_map(|x| x.as_array().map(Vec::as_slice)).collect() };
        for pair in pairs {
            let (Some(open), Some(close)) = (pair.first().and_then(content), pair.get(1).and_then(content)) else { continue };
            if open.is_empty() || close.is_empty() || units(&open) > MAX_GRAMMAR_MARKER || units(&close) > MAX_GRAMMAR_MARKER {
                return Some((".blockComment".into(), format!("blockComment markers are 1 to {MAX_GRAMMAR_MARKER} characters")));
            }
        }
    }
    None
}

// ---- the archive ------------------------------------------------------------

/// What a file of a plugin folder is inside the `.wmplugin`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    Manifest,
    Entry,
    /// Another `.py` file: API 5.
    Module,
    /// `assets/<name>`: API 5.
    Asset,
    /// `locales/<tag>.json`: API 5.
    Locale,
    /// `licenses/**`: the licence texts of the packages `wmpy pip` vendored.
    /// `PluginFile.read` takes the archive with them and leaves them out of
    /// the install ("anything else ... is left out"), so they travel with the
    /// file people share, and count toward its 64 entries and 4 MB.
    License,
}

/// Files a plugin folder holds for its author, never for the keyboard: tests,
/// dotfiles and dot folders, `__pycache__`, the editor's stubs and settings,
/// built archives and the package lock. The CLI's `pack` and the web editor's
/// export leave them out.
pub fn is_dev_file(path: &str) -> bool {
    let parts: Vec<&str> = path.split('/').collect();
    let file = parts.last().copied().unwrap_or("");
    path == "test.py"
        || parts.first() == Some(&"tests")
        || parts.iter().any(|p| p.starts_with('.') || *p == "__pycache__" || *p == "node_modules" || *p == "venv")
        || file.ends_with(".wmplugin")
        || file.ends_with(".pyc")
        || file.ends_with(".pyi")
        || matches!(file, "wmpy.lock" | "wmpy.toml" | "pyproject.toml" | "pyrightconfig.json")
}

/// What [path] (relative, with `/`) becomes in the archive of [m], or None
/// when the archive leaves it out: a dev file ([is_dev_file]), and for API 4
/// everything but the manifest, the entry and `licenses/`, as
/// `PluginFile.read` reads only those two. Under API 5 a file in `assets/`
/// or `locales/` always gets its role, valid or not, because the app refuses
/// an archive with a bad one rather than skipping it; [archive_problems]
/// says which are bad.
pub fn packed_role(path: &str, m: &Manifest) -> Option<Role> {
    if path == MANIFEST {
        return Some(Role::Manifest);
    }
    if is_dev_file(path) {
        return None;
    }
    if path == m.entry() {
        return Some(Role::Entry);
    }
    if path.starts_with("licenses/") {
        return Some(Role::License);
    }
    if !m.package() {
        return None;
    }
    if path.starts_with("assets/") {
        Some(Role::Asset)
    } else if path.starts_with("locales/") {
        Some(Role::Locale)
    } else if path.ends_with(".py") {
        Some(Role::Module)
    } else {
        None
    }
}

/// Everything `PluginFile.read` would refuse about the archive made of
/// [files] (path and bytes, the manifest included or not), and what the
/// keyboard would not find: the entry script, the other modules, assets,
/// translations, the caps, and the files the manifest names (`tool.icon`,
/// grammar assets).
pub fn archive_problems(m: &Manifest, files: &[(&str, &[u8])]) -> Vec<Problem> {
    let mut out = Vec::new();
    let name = m.name();
    let shown = if name.is_empty() { "This plugin".to_string() } else { format!("“{name}”") };
    let entry = m.entry();
    // PluginFile.Unpacked.lookUp: the exact name, then any file with the same last part.
    let tail = entry.rsplit(['/', '\\']).next().unwrap_or(&entry).to_string();
    let found = files.iter().find(|(p, _)| *p == entry).or_else(|| files.iter().find(|(p, _)| p.rsplit('/').next() == Some(tail.as_str())));
    match found {
        None => out.push(
            error("missing_script", "entry", format!("there is no {entry}, the script plugin.json names"))
                .app(format!("{shown} does not have its script file ({entry})."))
                .help("make it, or name the right file in \"entry\""),
        ),
        Some((file, bytes)) => {
            let file = file.to_string();
            if bytes.is_empty() {
                out.push(error("empty_script", "", format!("{file} is empty")).in_file(&file).app(format!("{shown} has an empty script.")));
            } else if bytes.len() > MAX_SCRIPT_BYTES {
                out.push(
                    error("script_too_large", "", format!("{file} is {} KB; a script is at most {} KB", bytes.len() / 1024, MAX_SCRIPT_BYTES / 1024))
                        .in_file(&file)
                        .app(format!("{shown} has a script larger than {} KB.", MAX_SCRIPT_BYTES / 1024))
                        .help("move part of it into another module: API 5 plugins import their own .py files"),
                );
            } else if std::str::from_utf8(bytes).is_err() {
                out.push(error("bad_encoding", "", format!("{file} is not UTF-8 text")).in_file(&file).app(format!("{shown} has a script that is not valid UTF-8 text.")).help("save it as UTF-8"));
            }
        }
    }
    if !m.package() {
        return out;
    }
    let app_bad_file = format!("{shown} has a file this version of WM Keyboard cannot load.");
    let mut count = 0usize;
    let mut total = 0usize;
    for (path, bytes) in files {
        count += 1;
        if *path == MANIFEST {
            continue;
        }
        total += bytes.len();
        let is_entry = found.is_some_and(|(p, _)| p == path);
        if is_entry {
            continue;
        }
        if let Some(asset) = path.strip_prefix("assets/") {
            if !is_asset_name(asset) {
                out.push(
                    error("bad_asset", "", format!("{path} cannot be an asset: an asset is one flat, lowercase file name ending .png, .webp, .jpg, .jpeg, .svg, .json or .txt"))
                        .in_file(*path)
                        .app(app_bad_file.clone()),
                );
            } else if bytes.len() > MAX_ASSET_BYTES {
                out.push(error("asset_too_large", "", format!("{path} is {} KB; an asset is at most 512 KB", bytes.len() / 1024)).in_file(*path).app(app_bad_file.clone()));
            }
        } else if path.starts_with("locales/") {
            let why = match locale_tag(path) {
                None => Some("the file name is not a language tag such as bn.json or pt-BR.json".to_string()),
                Some(_) if bytes.len() > MAX_LOCALE_BYTES => Some(format!("it is {} KB; a translation is at most 64 KB", bytes.len() / 1024)),
                Some(_) => match std::str::from_utf8(bytes) {
                    Err(_) => Some("it is not UTF-8 text".into()),
                    Ok(text) => locale_problem(text),
                },
            };
            if let Some(why) = why {
                out.push(
                    error("bad_locale", "", format!("{path}: {why}"))
                        .in_file(*path)
                        .app(format!("{shown} has a translation file the keyboard cannot read: {path}. Each file under locales/ must be a language tag such as bn.json or pt-BR.json, holding a JSON object of text, at most 64 KB."))
                        .help(docs("/plugins/guides/translations/")),
                );
            }
        } else if path.ends_with(".py") {
            let why = if !is_module_name(path) {
                Some(format!("its path is not a module `import` can reach: up to {MAX_MODULE_PARTS} Python names between slashes, ending .py"))
            } else if bytes.len() > MAX_SCRIPT_BYTES {
                Some(format!("it is {} KB; a module is at most {} KB", bytes.len() / 1024, MAX_SCRIPT_BYTES / 1024))
            } else if std::str::from_utf8(bytes).is_err() {
                Some("it is not UTF-8 text".into())
            } else {
                None
            };
            if let Some(why) = why {
                out.push(error("bad_module", "", format!("{path}: {why}")).in_file(*path).app(app_bad_file.clone()));
            } else if *path != entry.as_str() {
                // `import queue` finds the keyboard's own module first, so this file never loads.
                let top = path.split('/').next().unwrap_or(path).trim_end_matches(".py");
                let reserved = matches!(top, "wm" | "_wm" | "_native")
                    || crate::api::MODULES.contains(&top)
                    || crate::api::LIBRARY.iter().any(|l| l.split('.').next() == Some(top));
                if reserved {
                    out.push(
                        warning("shadowed_module", "", format!("{path}: the keyboard has its own `{top}` module, so `import {top}` finds that one and this file never loads"))
                            .in_file(*path)
                            .help("rename the file (and its imports), such as to my_{top}.py"),
                    );
                }
            }
        }
    }
    if count > MAX_ENTRIES {
        out.push(error("too_many_files", "", format!("the archive would hold {count} files; at most {MAX_ENTRIES}")).app(format!("{shown} is larger than {} KB.", MAX_TOTAL_BYTES / 1024)));
    }
    if total > MAX_TOTAL_BYTES {
        out.push(
            error("archive_too_large", "", format!("the files come to {} KB; a plugin is at most {} KB unpacked", total / 1024, MAX_TOTAL_BYTES / 1024))
                .app(format!("{shown} is larger than {} KB.", MAX_TOTAL_BYTES / 1024)),
        );
    }
    let has = |p: &str| files.iter().any(|(f, _)| *f == p);
    let icon = m.tool.icon.trim();
    if icon.starts_with("assets/") && is_icon(icon) && !has(icon) {
        out.push(warning("missing_icon", "tool.icon", format!("there is no {icon}; the keyboard draws the jigsaw instead")).help(docs("/plugins/guides/pages-and-timers/#your-icon")));
    }
    for (i, s) in m.syntax.iter().enumerate() {
        if let Value::String(asset) = &s.grammar {
            if asset.starts_with("assets/") && !has(asset) {
                out.push(warning("missing_grammar", format!("syntax[{i}].grammar"), format!("there is no {asset}, so the grammar \"{}\" colours nothing", s.id.trim())));
            }
        }
    }
    out
}

/// `PluginLocales.parse`: why [text] is not a translation file, or None.
fn locale_problem(text: &str) -> Option<String> {
    const FORMS: &[&str] = &["zero", "one", "two", "few", "many", "other"];
    let value: Value = match serde_json::from_str(text) {
        Ok(v) => v,
        Err(e) => return Some(format!("it is not valid JSON ({})", json_error_text(&e))),
    };
    let Some(root) = value.as_object() else { return Some("it is not a JSON object".into()) };
    for (key, v) in root {
        let ok = match v {
            Value::String(_) => true,
            Value::Object(forms) => !forms.is_empty() && forms.keys().all(|k| FORMS.contains(&k.as_str())) && forms.values().all(Value::is_string),
            _ => false,
        };
        if !ok {
            return Some(format!("\"{key}\" is neither text nor plural forms (zero, one, two, few, many, other) of text"));
        }
    }
    None
}

/// `PluginLocales.tagOf`: the language tag of `locales/<tag>.json`, or None.
pub fn locale_tag(path: &str) -> Option<&str> {
    let tag = path.strip_prefix("locales/")?.strip_suffix(".json")?;
    is_locale_tag(tag).then_some(tag)
}

// ---- names and patterns -----------------------------------------------------

/// `ID_PATTERN`: `^[a-z0-9][a-z0-9._-]{2,63}$`.
pub fn is_valid_id(id: &str) -> bool {
    let b = id.as_bytes();
    (3..=64).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'_' | b'-'))
}

/// `ACTION_ID`, also the provider, preview and grammar ids: `^[a-z0-9_-]{1,32}$`.
pub fn is_action_id(id: &str) -> bool {
    (1..=32).contains(&id.len()) && id.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_' || c == b'-')
}

/// `SETTING_KEY`: `^[a-z_][a-z0-9_]{0,31}$`.
pub fn is_setting_key(key: &str) -> bool {
    let b = key.as_bytes();
    (1..=32).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0] == b'_') && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'_')
}

/// `KEYWORD`: `^[a-z0-9]{1,16}$`.
pub fn is_keyword(k: &str) -> bool {
    (1..=16).contains(&k.len()) && k.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit())
}

/// `ICON`: `^([a-z0-9_]{0,48}|assets/[a-z0-9][a-z0-9_.-]{0,59}\.svg)$`.
pub fn is_icon(icon: &str) -> bool {
    if icon.len() <= 48 && icon.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'_') {
        return true;
    }
    let Some(stem) = icon.strip_prefix("assets/").and_then(|r| r.strip_suffix(".svg")) else { return false };
    let b = stem.as_bytes();
    (1..=60).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'_' | b'.' | b'-'))
}

fn is_hex_color(c: &str) -> bool {
    c.len() == 7 && c.starts_with('#') && c[1..].bytes().all(|b| b.is_ascii_hexdigit())
}

/// `normaliseHost`: lowercase, trimmed, without `https://` or a trailing dot or slash.
pub fn normalise_host(raw: &str) -> String {
    let h = raw.trim().to_lowercase();
    let h = h.strip_prefix("https://").unwrap_or(&h);
    h.trim_end_matches(['/', '.']).to_string()
}

/// `isValidHost`: a host name, or `*.` and one: dot-separated labels of
/// letters, digits and dashes, at least two, the last starting with a letter.
/// No IP addresses, ports, paths or `localhost`.
pub fn is_valid_host(host: &str) -> bool {
    if host.len() > MAX_HOST_LENGTH {
        return false;
    }
    let name = host.strip_prefix("*.").unwrap_or(host);
    let labels: Vec<&str> = name.split('.').collect();
    if labels.len() < 2 {
        return false;
    }
    let label_ok = |l: &str| {
        let b = l.as_bytes();
        (1..=63).contains(&b.len())
            && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
            && b[0] != b'-'
            && b[b.len() - 1] != b'-'
    };
    labels.iter().all(|l| label_ok(l)) && labels.last().is_some_and(|l| l.as_bytes()[0].is_ascii_lowercase())
}

/// `PluginPreviewRules.EXTENSION`: `^[a-z0-9][a-z0-9._+-]{0,15}$`.
fn is_extension(e: &str) -> bool {
    let b = e.as_bytes();
    (1..=16).contains(&b.len()) && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'_' | b'+' | b'-'))
}

/// `FILE_NAME`: `^[A-Za-z0-9._+-]{1,40}$`.
fn is_file_name(n: &str) -> bool {
    (1..=40).contains(&n.len()) && n.bytes().all(|c| c.is_ascii_alphanumeric() || matches!(c, b'.' | b'_' | b'+' | b'-'))
}

/// `MIME`: `^[a-z0-9][a-z0-9.+-]*/([a-z0-9][a-z0-9.+_-]*\*?|\*)$`.
fn is_mime(m: &str) -> bool {
    let Some((kind, sub)) = m.split_once('/') else { return false };
    let first_ok = |s: &str| s.as_bytes().first().is_some_and(|c| c.is_ascii_lowercase() || c.is_ascii_digit());
    if !first_ok(kind) || !kind.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'+' | b'-')) {
        return false;
    }
    if sub == "*" {
        return true;
    }
    let body = sub.strip_suffix('*').unwrap_or(sub);
    first_ok(body) && body.bytes().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'.' | b'+' | b'_' | b'-'))
}

/// `PluginFile.isAssetName`: `^[a-z0-9][a-z0-9_.-]{0,63}\.(png|webp|jpg|jpeg|svg|json|txt)$`, no `..`.
pub fn is_asset_name(name: &str) -> bool {
    const TYPES: &[&str] = &["png", "webp", "jpg", "jpeg", "svg", "json", "txt"];
    let Some((stem, ext)) = name.rsplit_once('.') else { return false };
    let b = stem.as_bytes();
    !name.contains("..")
        && TYPES.contains(&ext)
        && (1..=64).contains(&b.len())
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, b'_' | b'.' | b'-'))
}

/// `PluginFile.isModuleName`: identifiers between slashes, at most
/// [MAX_MODULE_PARTS], ending `.py`.
pub fn is_module_name(entry: &str) -> bool {
    let Some(stem) = entry.strip_suffix(".py") else { return false };
    let parts: Vec<&str> = stem.split('/').collect();
    parts.len() <= MAX_MODULE_PARTS
        && parts.iter().all(|p| {
            let b = p.as_bytes();
            (1..=64).contains(&b.len()) && (b[0].is_ascii_alphabetic() || b[0] == b'_') && b.iter().all(|c| c.is_ascii_alphanumeric() || *c == b'_')
        })
}

/// `PluginLocales.TAG`: `^[a-z]{2,3}(-[A-Za-z0-9]{2,8})?$`.
pub fn is_locale_tag(tag: &str) -> bool {
    let (lang, region) = match tag.split_once('-') {
        Some((l, r)) => (l, Some(r)),
        None => (tag, None),
    };
    (2..=3).contains(&lang.len())
        && lang.bytes().all(|c| c.is_ascii_lowercase())
        && region.is_none_or(|r| (2..=8).contains(&r.len()) && r.bytes().all(|c| c.is_ascii_alphanumeric()))
}

/// `PluginManifestCodec.displayText`: [raw] trimmed, without control, format,
/// surrogate and private-use characters, cut to [max] UTF-16 units, trimmed again.
pub fn display_text(raw: &str, max: usize) -> String {
    let mut out = String::new();
    let mut units = 0;
    for c in raw.trim().chars() {
        if !displayable(c) {
            continue;
        }
        if units + c.len_utf16() > max {
            break;
        }
        units += c.len_utf16();
        out.push(c);
    }
    out.trim().to_string()
}

/// Not Cc, Cf or Co (Rust strings hold no lone surrogates).
fn displayable(c: char) -> bool {
    let u = c as u32;
    let control = u < 0x20 || (0x7f..=0x9f).contains(&u);
    let private = (0xe000..=0xf8ff).contains(&u) || (0xf0000..=0xffffd).contains(&u) || (0x100000..=0x10fffd).contains(&u);
    let format = matches!(u,
        0x00ad | 0x0600..=0x0605 | 0x061c | 0x06dd | 0x070f | 0x0890..=0x0891 | 0x08e2 | 0x180e | 0x200b..=0x200f | 0x202a..=0x202e
        | 0x2060..=0x2064 | 0x2066..=0x206f | 0xfeff | 0xfff9..=0xfffb | 0x110bd | 0x110cd | 0x13430..=0x1343f | 0x1bca0..=0x1bca3
        | 0x1d173..=0x1d17a | 0xe0001 | 0xe0020..=0xe007f);
    !control && !private && !format
}

/// `PluginFile.fileName`: `Demo-1.2.wmplugin`, the name (or the id) with only
/// letters, digits, spaces, dashes and underscores, and the version after it.
pub fn file_name(m: &Manifest) -> String {
    let name = m.name();
    let base = if name.trim().is_empty() { m.clean_id() } else { name };
    let stem: String = base.chars().filter(|c| c.is_alphanumeric() || matches!(c, ' ' | '_' | '-')).collect();
    let stem = stem.trim();
    let stem = if stem.is_empty() { "plugin" } else { stem };
    let version: String = display_text(&m.plugin_version, MAX_VERSION).chars().filter(|c| c.is_alphanumeric() || matches!(c, ' ' | '.' | '_' | '-')).collect();
    let version = version.trim_matches(['.', ' ']);
    if version.is_empty() { format!("{stem}.wmplugin") } else { format!("{stem}-{version}.wmplugin") }
}

/// `uniquePluginId` (PluginIdeState.kt): `my.` and the name in small letters,
/// digits and dashes, numbered until nothing in [taken] has it.
pub fn unique_id(name: &str, taken: &[String]) -> String {
    let lower = name.to_lowercase();
    let mut slug = String::new();
    let mut dash = false;
    for c in lower.chars() {
        if c.is_ascii_lowercase() || c.is_ascii_digit() {
            slug.push(c);
            dash = false;
        } else if !dash {
            slug.push('-');
            dash = true;
        }
    }
    let slug: String = slug.trim_matches('-').chars().take(40).collect();
    let slug = slug.trim_matches('-');
    let slug = if slug.is_empty() { "plugin" } else { slug };
    let base = format!("my.{slug}");
    if !taken.contains(&base) {
        return base;
    }
    let mut n = 2;
    while taken.contains(&format!("{base}-{n}")) {
        n += 1;
    }
    format!("{base}-{n}")
}

// ---- positions --------------------------------------------------------------

/// The span of the value at [path] (`settings[0].key`) in [text], or of the
/// nearest part of it that exists (the object's key, or its opening brace).
pub fn locate(text: &str, path: &str) -> Option<Span> {
    let units: Vec<u16> = text.encode_utf16().collect();
    let mut p = JsonSpans { u: &units, i: 0 };
    p.ws();
    let root = p.value(0)?;
    let mut node = &root;
    let mut best = Span::new(root.span.start, root.span.start + 1);
    for seg in segments(path) {
        let next = match (&node.kind, seg) {
            (NodeKind::Object(members), Seg::Key(k)) => members.iter().find(|(name, _, _)| name == k).map(|(_, key, v)| (Some(*key), v)),
            (NodeKind::Array(items), Seg::Index(i)) => items.get(i).map(|v| (None, v)),
            _ => None,
        };
        match next {
            Some((key, v)) => {
                // A plain value is marked whole; an object or a list by its key, or its opening bracket.
                best = if matches!(v.kind, NodeKind::Scalar) { v.span } else { key.unwrap_or(Span::new(v.span.start, v.span.start + 1)) };
                node = v;
            }
            None => return Some(best),
        }
    }
    Some(best)
}

/// 1-based line and column (in UTF-16 units) of [offset] in [text].
pub fn line_col(text: &str, offset: u32) -> (u32, u32) {
    let mut line = 1;
    let mut col = 1;
    let mut at = 0u32;
    for c in text.chars() {
        if at >= offset {
            break;
        }
        at += c.len_utf16() as u32;
        if c == '\n' {
            line += 1;
            col = 1;
        } else {
            col += c.len_utf16() as u32;
        }
    }
    (line, col)
}

enum Seg<'a> {
    Key(&'a str),
    Index(usize),
}

fn segments(path: &str) -> Vec<Seg<'_>> {
    let mut out = Vec::new();
    for part in path.split('.').filter(|p| !p.is_empty()) {
        let (key, rest) = match part.find('[') {
            Some(at) => (&part[..at], &part[at..]),
            None => (part, ""),
        };
        if !key.is_empty() {
            out.push(Seg::Key(key));
        }
        for idx in rest.split('[').filter(|s| !s.is_empty()) {
            if let Ok(i) = idx.trim_end_matches(']').parse() {
                out.push(Seg::Index(i));
            }
        }
    }
    out
}

struct Node {
    span: Span,
    kind: NodeKind,
}

enum NodeKind {
    /// Members: name, the key's span, the value.
    Object(Vec<(String, Span, Node)>),
    Array(Vec<Node>),
    Scalar,
}

/// A JSON reader that keeps where each value is; it only runs on text
/// serde_json already accepted, so it gives up rather than reports.
struct JsonSpans<'a> {
    u: &'a [u16],
    i: usize,
}

impl JsonSpans<'_> {
    fn ws(&mut self) {
        while self.i < self.u.len() && matches!(self.u[self.i], 0x20 | 0x09 | 0x0a | 0x0d) {
            self.i += 1;
        }
    }

    fn value(&mut self, depth: u32) -> Option<Node> {
        if depth > 128 {
            return None;
        }
        let start = self.i as u32;
        match *self.u.get(self.i)? {
            0x7b => {
                self.i += 1;
                let mut members = Vec::new();
                loop {
                    self.ws();
                    match *self.u.get(self.i)? {
                        0x7d => {
                            self.i += 1;
                            break;
                        }
                        0x2c => {
                            self.i += 1;
                            continue;
                        }
                        _ => {}
                    }
                    let key_start = self.i as u32;
                    let name = self.string()?;
                    let key = Span::new(key_start, self.i as u32);
                    self.ws();
                    if self.u.get(self.i) != Some(&0x3a) {
                        return None;
                    }
                    self.i += 1;
                    self.ws();
                    let v = self.value(depth + 1)?;
                    members.push((name, key, v));
                }
                Some(Node { span: Span::new(start, self.i as u32), kind: NodeKind::Object(members) })
            }
            0x5b => {
                self.i += 1;
                let mut items = Vec::new();
                loop {
                    self.ws();
                    match *self.u.get(self.i)? {
                        0x5d => {
                            self.i += 1;
                            break;
                        }
                        0x2c => {
                            self.i += 1;
                            continue;
                        }
                        _ => items.push(self.value(depth + 1)?),
                    }
                }
                Some(Node { span: Span::new(start, self.i as u32), kind: NodeKind::Array(items) })
            }
            0x22 => {
                self.string()?;
                Some(Node { span: Span::new(start, self.i as u32), kind: NodeKind::Scalar })
            }
            _ => {
                while self.i < self.u.len() && !matches!(self.u[self.i], 0x2c | 0x7d | 0x5d | 0x20 | 0x09 | 0x0a | 0x0d) {
                    self.i += 1;
                }
                Some(Node { span: Span::new(start, self.i as u32), kind: NodeKind::Scalar })
            }
        }
    }

    /// A string at the cursor, its escapes read.
    fn string(&mut self) -> Option<String> {
        if self.u.get(self.i) != Some(&0x22) {
            return None;
        }
        let start = self.i;
        self.i += 1;
        while self.i < self.u.len() {
            match self.u[self.i] {
                0x5c => self.i += 2,
                0x22 => {
                    self.i += 1;
                    let raw = String::from_utf16_lossy(&self.u[start..self.i]);
                    return serde_json::from_str(&raw).ok();
                }
                _ => self.i += 1,
            }
        }
        None
    }
}

// ---- JSON for the language server and the web editor ------------------------

/// A problem as JSON: `{"severity", "code", "message", "file", "path",
/// "start", "end", "line", "col", "appMessage", "help"}`; positions are
/// UTF-16, line and col 1-based, null when not known.
pub fn problem_json(p: &Problem, text: &str) -> Value {
    let (line, col) = match (p.span, p.file == MANIFEST) {
        (Some(s), true) => {
            let (l, c) = line_col(text, s.start);
            (Some(l), Some(c))
        }
        _ => (None, None),
    };
    json!({
        "severity": p.severity.name(), "code": p.code, "message": p.message, "file": p.file, "path": p.path,
        "start": p.span.map(|s| s.start), "end": p.span.map(|s| s.end), "line": line, "col": col,
        "appMessage": p.app_message, "help": p.help,
    })
}

/// [check] as JSON text: `{"ok": bool, "errors": n, "warnings": n, "problems": [...]}`.
pub fn check_json(text: &str) -> String {
    let report = check(text);
    let problems: Vec<Value> = report.problems.iter().map(|p| problem_json(p, text)).collect();
    json!({"ok": report.errors() == 0, "errors": report.errors(), "warnings": report.warnings(), "problems": problems}).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn codes(text: &str) -> Vec<&'static str> {
        check(text).problems.iter().map(|p| p.code).collect()
    }

    const GOOD: &str = r#"{
  "format": "wmkeyboard-plugin",
  "id": "com.example.weather",
  "name": "Weather",
  "pluginVersion": "1.0.0",
  "apiVersion": 5,
  "permissions": ["network", "storage"],
  "hosts": ["api.open-meteo.com"],
  "tool": {"icon": "cloud", "tile": true, "keyword": "weather", "chip": true},
  "settings": [{"key": "units", "type": "choice", "label": "Units", "default": "metric", "options": ["metric", "imperial"]}]
}"#;

    #[test]
    fn the_docs_example_is_clean() {
        assert_eq!(codes(GOOD), Vec::<&str>::new());
    }

    #[test]
    fn bad_json_points_at_the_mistake() {
        let text = "{\n  \"id\": \"a.b.c\"\n  \"name\": \"x\"\n}";
        let r = check(text);
        assert_eq!(r.problems[0].code, "bad_json");
        let span = r.problems[0].span.unwrap();
        assert_eq!(line_col(text, span.start).0, 3);
    }

    #[test]
    fn a_manifest_without_api_version_reads_as_lua() {
        let text = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "X", "pluginVersion": "1"}"#;
        assert_eq!(codes(text), vec!["lua"]);
    }

    #[test]
    fn every_unknown_permission_is_named_and_placed() {
        let text = "{\"format\": \"wmkeyboard-plugin\", \"id\": \"a.b.c\", \"name\": \"X\", \"pluginVersion\": \"1\", \"apiVersion\": 5,\n \"permissions\": [\"storage\", \"netwrok\", \"bogus\"]}";
        let r = check(text);
        let unknown: Vec<&Problem> = r.problems.iter().filter(|p| p.code == "unknown_permission").collect();
        assert_eq!(unknown.len(), 2);
        assert_eq!(unknown[0].path, "permissions[1]");
        assert!(unknown[0].help.as_deref().unwrap().contains("\"network\""));
        let span = unknown[0].span.unwrap();
        assert_eq!(&text.encode_utf16().skip(span.start as usize).take((span.end - span.start) as usize).collect::<Vec<_>>(), &"\"netwrok\"".encode_utf16().collect::<Vec<_>>());
        assert_eq!(line_col(text, span.start).0, 2);
    }

    #[test]
    fn api_5_rules() {
        let base = |extra: &str| format!(r#"{{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "X", "pluginVersion": "1", {extra}}}"#);
        assert_eq!(codes(&base(r#""apiVersion": 4, "permissions": ["network"], "hosts": ["a.com"]"#)), vec!["permission_needs_api_5", "needs_api_5"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "permissions": ["network"]"#)), vec!["network_without_hosts"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "permissions": ["network"], "settings": [{"key": "server", "type": "host", "label": "Server"}]"#)), Vec::<&str>::new());
        assert_eq!(codes(&base(r#""apiVersion": 5, "permissions": ["network"], "hosts": ["http://a.com", "localhost", "1.2.3.4", "*.ok.org", "https://Fine.com/"]"#)), vec!["bad_host", "bad_host", "bad_host", "host_written_long"]);
        let many: Vec<String> = (0..17).map(|i| format!("\"h{i}.com\"")).collect();
        assert_eq!(codes(&base(&format!(r#""apiVersion": 5, "permissions": ["network"], "hosts": [{}]"#, many.join(",")))), vec!["too_many_hosts"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "settings": [{"key": "Key", "label": ""}, {"key": "k", "type": "secret", "label": "K"}, {"key": "c", "type": "choice", "label": "C"}]"#)), vec![
            "bad_setting_key",
            "setting_no_label",
            "secret_needs_storage",
            "choice_without_options"
        ]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "settings": [{"key": "on", "type": "switch", "label": "On", "default": "yes"}, {"key": "n", "type": "number", "label": "N", "default": 5}]"#)), vec!["setting_default", "setting_default_not_text"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "providers": [{"tool": "serch"}, {"tool": "search"}, {"tool": "search"}]"#)), vec!["unknown_provider_tool", "duplicate_provider"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "permissions": ["previews"]"#)), vec!["previews_permission_unused"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "previews": [{"id": "csv", "extensions": [".CSV"], "mimes": ["text"]}]"#)), vec!["bad_mime", "previews_need_permission"]);
        assert_eq!(codes(&base(r#""apiVersion": 5, "tool": {"keyword": "Weather!", "icon": "assets/Icon.png"}"#)), vec!["bad_keyword", "bad_icon"]);
        assert_eq!(codes(&base(r##""apiVersion": 4, "tool": {"icon": "cloud", "color": "#112233"}"##)), Vec::<&str>::new());
        assert_eq!(codes(&base(r#""apiVersion": 5, "syntax": [{"id": "ini", "extensions": ["ini"], "grammar": {"rules": [{"match": "x", "kind": "colour"}]}}]"#)), vec!["bad_grammar"]);
    }

    #[test]
    fn actions_and_selection() {
        let base = |extra: &str| format!(r#"{{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "X", "pluginVersion": "1", "apiVersion": 4, {extra}}}"#);
        assert_eq!(codes(&base(r#""actions": [{"id": "rot13", "name": "ROT13"}]"#)), vec!["actions_need_selection"]);
        assert_eq!(codes(&base(r#""permissions": ["selection"]"#)), vec!["selection_without_actions"]);
        assert_eq!(codes(&base(r#""panel": false"#)), vec!["nothing_to_show"]);
        assert_eq!(codes(&base(r#""permissions": ["selection"], "actions": [{"id": "A"}, {"id": "b"}, {"id": "b", "name": "B"}, {"id": "c", "name": "C", "input": "all"}]"#)), vec![
            "bad_action_id",
            "action_no_name",
            "duplicate_action_id",
            "action_input"
        ]);
    }

    #[test]
    fn wrong_types_are_one_problem_each() {
        let text = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": 5, "pluginVersion": "1", "apiVersion": "5", "permissions": "storage", "panel": "yes"}"#;
        let r = check(text);
        let wrong: Vec<&str> = r.problems.iter().filter(|p| p.code == "wrong_type").map(|p| p.path.as_str()).collect();
        assert_eq!(wrong, vec!["apiVersion", "name", "permissions", "panel"]);
    }

    #[test]
    fn display_text_is_the_apps() {
        assert_eq!(display_text("  Hi\u{202e}there\u{0007} ", 40), "Hithere");
        assert_eq!(display_text("😀😀", 3), "😀");
        assert_eq!(display_text(&"x".repeat(50), 40).len(), 40);
    }

    #[test]
    fn names_and_ids() {
        assert_eq!(unique_id("Cipher Tool!", &[]), "my.cipher-tool");
        assert_eq!(unique_id("বাংলা", &[]), "my.plugin");
        assert_eq!(unique_id("A", &["my.a".to_string()]), "my.a-2");
        let r = check(GOOD);
        assert_eq!(file_name(r.manifest.as_ref().unwrap()), "Weather-1.0.0.wmplugin");
        assert!(is_valid_host("*.example.co.uk") && !is_valid_host("example") && !is_valid_host("ex_ample.com") && !is_valid_host("a.123"));
        assert!(is_module_name("pkg/util.py") && !is_module_name("my-lib/x.py") && is_module_name("a/b/c/d/e/f/g/h.py") && !is_module_name("a/b/c/d/e/f/g/h/i.py"));
        assert!(is_locale_tag("pt-BR") && is_locale_tag("bn") && !is_locale_tag("english"));
        assert!(is_asset_name("logo.png") && !is_asset_name("Logo.png") && !is_asset_name("sub/logo.png") && !is_asset_name("a.gif"));
    }

    #[test]
    fn the_archive() {
        let r = check(GOOD);
        let m = r.manifest.unwrap();
        assert_eq!(packed_role("main.py", &m), Some(Role::Entry));
        assert_eq!(packed_role("helpers.py", &m), Some(Role::Module));
        assert_eq!(packed_role("test.py", &m), None);
        assert_eq!(packed_role("tests/test_x.py", &m), None);
        assert_eq!(packed_role(".wmpy/stubs/wm/ui.pyi", &m), None);
        assert_eq!(packed_role("README.md", &m), None);
        assert_eq!(packed_role("assets/Logo.PNG", &m), Some(Role::Asset));
        assert_eq!(packed_role("licenses/requests/LICENSE", &m), Some(Role::License));
        let files: Vec<(&str, &[u8])> = vec![
            ("plugin.json", b"{}"),
            ("main.py", b""),
            ("my-lib/x.py", b"x = 1"),
            ("assets/Logo.PNG", b"..."),
            ("locales/english.json", b"{}"),
            ("locales/bn.json", b"{\"a\": 1}"),
            ("queue.py", b"x = 1"),
        ];
        let codes: Vec<&str> = archive_problems(&m, &files).iter().map(|p| p.code).collect();
        assert_eq!(codes, vec!["empty_script", "bad_module", "bad_asset", "bad_locale", "bad_locale", "shadowed_module"]);
        let none: Vec<(&str, &[u8])> = vec![("plugin.json", b"{}")];
        assert_eq!(archive_problems(&m, &none)[0].code, "missing_script");
    }

    #[test]
    fn json_shape() {
        let v: Value = serde_json::from_str(&check_json(r#"{"format": "x"}"#)).unwrap();
        assert_eq!(v["ok"], false);
        assert!(v["problems"][0]["line"].is_number());
    }
}
