//! `wmpy lsp`: the language server for WM Keyboard's Python plugins, over
//! stdin and stdout (LSP 3.17).
//!
//! It reads a plugin as the keyboard does: the nearest plugin.json above a
//! file decides what the checks know (permissions, hosts, providers,
//! settings), and every `.py` file of the folder is one of the plugin's
//! modules. Positions are UTF-16, the tooling's own unit.
//!
//! Commands (`workspace/executeCommand`), as the editor extensions call them:
//!
//! * `wmpy.preview [folderUri]` starts (or reuses) `wmpy preview` for the
//!   folder and returns `{"url"}`; with `initializationOptions.preview` set to
//!   `"browser"` (the default) it also opens the system browser.
//! * `wmpy.test [folderUri, filter?]` returns `wmpy test --json`'s result.
//! * `wmpy.check [folderUri]` publishes the folder's problems again and
//!   returns their counts.
//! * `wmpy.pack [folderUri]` returns `{"path", "sha256", "size"}`.
//! * `wmpy.run [folderUri]` returns `{"command": [exe, "run", dir]}` for the
//!   client to run in a terminal.

pub(crate) mod commands;
mod features;
mod json;
pub(crate) mod manifest;
pub(crate) mod plugin;
mod text;

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::time::{Duration, Instant};

use lsp_server::{Connection, Message, Notification, Request, RequestId, Response};
use lsp_types::{Position, Range, Uri};
use serde_json::{json, Value};
use wm_python_tooling::diagnostics::{Diagnostic, Severity};
use wm_python_tooling::document::Document;
use wm_python_tooling::navigate;
use wm_python_tooling::text::Span;

use commands::{PreviewMode, Previews};
use features::{Caps, File};
use plugin::{Answered, Global, Plugin, Role};
use text::{path_of_uri_str, path_to_uri, LineIndex};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// Accepted for editors that pass it; stdio is the only transport
    #[arg(long)]
    pub stdio: bool,
}

/// How long typing must pause before a file's problems are worked out again.
const DEBOUNCE: Duration = Duration::from_millis(250);

pub fn run(_args: Args) -> u8 {
    let (connection, io) = Connection::stdio();
    let code = match serve(&connection) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("wmpy lsp: {e}");
            1
        }
    };
    drop(connection);
    let _ = io.join();
    code
}

fn serve(conn: &Connection) -> Result<u8, String> {
    let (id, params) = conn.initialize_start().map_err(|e| e.to_string())?;
    let caps = client_caps(&params);
    let preview = PreviewMode::parse(params.pointer("/initializationOptions/preview").and_then(Value::as_str));
    let mut roots: Vec<PathBuf> = params
        .get("workspaceFolders")
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(|f| f.get("uri").and_then(Value::as_str)).filter_map(path_of_uri_str).collect())
        .unwrap_or_default();
    if roots.is_empty() {
        if let Some(root) = params.get("rootUri").and_then(Value::as_str).and_then(path_of_uri_str) {
            roots.push(root);
        }
    }
    let result = json!({
        "capabilities": capabilities(),
        "serverInfo": {"name": "wmpy", "version": env!("CARGO_PKG_VERSION")},
    });
    conn.initialize_finish(id, result).map_err(|e| e.to_string())?;
    let sender = conn.sender.clone();
    let send: Out = std::sync::Arc::new(move |m: Message| {
        let _ = sender.send(m);
    });
    let mut server = Server {
        send,
        exe: std::env::current_exe().unwrap_or_else(|_| PathBuf::from("wmpy")),
        preview,
        caps,
        roots,
        open: HashMap::new(),
        plugins: HashMap::new(),
        docs: RefCell::new(HashMap::new()),
        pending: HashMap::new(),
        previews: Previews::default(),
        seen: HashSet::new(),
        next_id: 1,
        shut_down: false,
    };
    server.started();
    let code = server.main_loop(conn);
    server.previews.stop_all();
    Ok(code)
}

type Out = std::sync::Arc<dyn Fn(Message) + std::marker::Send + Sync>;

fn client_caps(params: &Value) -> Caps {
    let c = &params["capabilities"];
    let name = params.pointer("/clientInfo/name").and_then(Value::as_str).unwrap_or("").to_lowercase();
    Caps {
        snippets: c.pointer("/textDocument/completion/completionItem/snippetSupport").and_then(Value::as_bool).unwrap_or(false),
        markdown: c
            .pointer("/textDocument/hover/contentFormat")
            .and_then(Value::as_array)
            .is_none_or(|a| a.iter().any(|k| k == "markdown")),
        vscode: name.contains("visual studio code") || name.contains("vscode") || name.contains("code - oss") || name.contains("vscodium"),
        watch_dynamic: c.pointer("/workspace/didChangeWatchedFiles/dynamicRegistration").and_then(Value::as_bool).unwrap_or(false),
    }
}

fn capabilities() -> Value {
    json!({
        "positionEncoding": "utf-16",
        "textDocumentSync": {"openClose": true, "change": 2, "save": {"includeText": false}},
        "completionProvider": {"triggerCharacters": [".", "(", ",", "\"", "'", "@", "="], "resolveProvider": true},
        "hoverProvider": true,
        "signatureHelpProvider": {"triggerCharacters": ["(", ","], "retriggerCharacters": [","]},
        "definitionProvider": true,
        "referencesProvider": true,
        "renameProvider": {"prepareProvider": true},
        "documentFormattingProvider": true,
        "codeActionProvider": {"codeActionKinds": ["quickfix"]},
        "documentSymbolProvider": true,
        "foldingRangeProvider": true,
        "semanticTokensProvider": {
            "legend": {"tokenTypes": features::TOKEN_TYPES, "tokenModifiers": features::TOKEN_MODIFIERS},
            "full": true,
            "range": false,
        },
        "codeLensProvider": {"resolveProvider": false},
        "executeCommandProvider": {"commands": ["wmpy.preview", "wmpy.test", "wmpy.check", "wmpy.pack", "wmpy.run"]},
        "workspace": {"workspaceFolders": {"supported": true, "changeNotifications": true}},
    })
}

struct Open {
    uri: Uri,
    text: String,
    version: i32,
}

struct Server {
    send: Out,
    exe: PathBuf,
    preview: PreviewMode,
    caps: Caps,
    roots: Vec<PathBuf>,
    open: HashMap<PathBuf, Open>,
    /// By plugin folder; dropped when its plugin.json or files change.
    plugins: HashMap<PathBuf, Rc<Plugin>>,
    /// Each file's last document, by its text.
    docs: RefCell<HashMap<PathBuf, (String, Rc<Document>)>>,
    /// Files whose problems are due, and when.
    pending: HashMap<PathBuf, Instant>,
    previews: Previews,
    /// Plugin folders whose every file has had its problems published.
    seen: HashSet<PathBuf>,
    next_id: i32,
    shut_down: bool,
}

/// A request's answer: a result, or an error code and message.
type Answer = Result<Value, (i32, String)>;

const REQUEST_FAILED: i32 = -32803;
const INVALID_PARAMS: i32 = -32602;

impl Server {
    fn notify(&self, method: &str, params: Value) {
        (self.send)(Message::Notification(Notification::new(method.to_string(), params)));
    }

    fn started(&mut self) {
        if self.caps.watch_dynamic {
            let id = self.next_id;
            self.next_id += 1;
            let params = json!({"registrations": [{
                "id": "wmpy-watch",
                "method": "workspace/didChangeWatchedFiles",
                "registerOptions": {"watchers": [{"globPattern": "**/plugin.json"}, {"globPattern": "**/*.py"}]},
            }]});
            (self.send)(Message::Request(Request::new(RequestId::from(id), "client/registerCapability".into(), params)));
        }
        for root in self.roots.clone() {
            for dir in plugin::plugins_under(&root) {
                self.schedule_plugin(&dir, Duration::ZERO);
            }
        }
    }

    fn main_loop(&mut self, conn: &Connection) -> u8 {
        loop {
            let now = Instant::now();
            let wait = self.pending.values().min().map(|t| t.saturating_duration_since(now)).unwrap_or(Duration::from_secs(3600));
            match conn.receiver.recv_timeout(wait) {
                Ok(Message::Request(req)) => {
                    if req.method == "shutdown" {
                        self.shut_down = true;
                        self.previews.stop_all();
                        (self.send)(Message::Response(Response::new_ok(req.id, Value::Null)));
                        continue;
                    }
                    let id = req.id.clone();
                    if self.shut_down {
                        (self.send)(Message::Response(Response::new_err(id, -32600, "the server is shutting down".into())));
                        continue;
                    }
                    if let Some(answer) = self.request(req) {
                        (self.send)(Message::Response(match answer {
                            Ok(v) => Response::new_ok(id, v),
                            Err((code, message)) => Response::new_err(id, code, message),
                        }));
                    }
                }
                Ok(Message::Notification(n)) => {
                    if n.method == "exit" {
                        return if self.shut_down { 0 } else { 1 };
                    }
                    self.notification(n);
                }
                Ok(Message::Response(_)) => {}
                Err(e) if e.is_timeout() => {}
                Err(_) => return if self.shut_down { 0 } else { 1 },
            }
            self.flush();
        }
    }

    // ---- files and plugins -------------------------------------------------------

    fn text_of(&self, path: &Path) -> Option<String> {
        match self.open.get(path) {
            Some(o) => Some(o.text.clone()),
            None => std::fs::read_to_string(path).ok(),
        }
    }

    fn uri_of(&self, path: &Path) -> Uri {
        match self.open.get(path) {
            Some(o) => o.uri.clone(),
            None => path_to_uri(path),
        }
    }

    fn doc(&self, path: &Path, text: &str) -> Rc<Document> {
        if let Some((t, d)) = self.docs.borrow().get(path) {
            if t == text {
                return d.clone();
            }
        }
        let d = Rc::new(Document::new(text));
        self.docs.borrow_mut().insert(path.to_path_buf(), (text.to_string(), d.clone()));
        d
    }

    fn plugin_at(&mut self, dir: &Path) -> Rc<Plugin> {
        if let Some(p) = self.plugins.get(dir) {
            return p.clone();
        }
        let manifest = self.open.get(&dir.join("plugin.json")).map(|o| o.text.clone());
        let p = Rc::new(Plugin::load(dir, manifest));
        self.plugins.insert(dir.to_path_buf(), p.clone());
        p
    }

    /// The plugin [path] belongs to, and its path inside the folder.
    fn plugin_of(&mut self, path: &Path) -> Option<(Rc<Plugin>, String)> {
        let dir = plugin::plugin_dir_of(path).or_else(|| {
            // A plugin.json open but not saved yet still makes its folder a plugin.
            let parent = path.parent()?;
            self.open.contains_key(&parent.join("plugin.json")).then(|| parent.to_path_buf())
        })?;
        let rel = path.strip_prefix(&dir).ok()?.to_string_lossy().replace('\\', "/");
        Some((self.plugin_at(&dir), rel))
    }

    /// The document of the plugin's file [rel].
    fn plugin_doc(&self, plugin: &Plugin, rel: &str) -> Option<Rc<Document>> {
        let path = plugin.dir.join(rel);
        let text = self.text_of(&path)?;
        Some(self.doc(&path, &text))
    }

    /// [path]'s problems are due after [delay]; a later change pushes them out again.
    fn schedule(&mut self, path: &Path, delay: Duration) {
        self.pending.insert(path.to_path_buf(), Instant::now() + delay);
    }

    fn schedule_plugin(&mut self, dir: &Path, delay: Duration) {
        self.seen.insert(dir.to_path_buf());
        self.plugins.remove(dir);
        let p = self.plugin_at(dir);
        self.schedule(&dir.join("plugin.json"), delay);
        for f in &p.files {
            self.schedule(&dir.join(f), delay);
        }
    }

    fn flush(&mut self) {
        let now = Instant::now();
        let due: Vec<PathBuf> = self.pending.iter().filter(|(_, t)| **t <= now).map(|(p, _)| p.clone()).collect();
        for path in due {
            self.pending.remove(&path);
            self.publish(&path);
        }
    }

    /// The problems of [path], and the document they are about.
    fn problems(&mut self, path: &Path) -> Option<(Vec<Diagnostic>, Rc<Document>)> {
        let text = self.text_of(path)?;
        let doc = self.doc(path, &text);
        let found = self.plugin_of(path);
        let (plugin, rel) = match &found {
            Some((p, rel)) => (Some(p.clone()), rel.clone()),
            None => (None, path.file_name()?.to_string_lossy().to_string()),
        };
        let mut elsewhere = Answered::default();
        if let Some(p) = &plugin {
            for f in &p.files {
                if *f != rel {
                    if let Some(d) = self.plugin_doc(p, f) {
                        elsewhere.add(&d);
                    }
                }
            }
        }
        let found = plugin::diagnose_file(plugin.as_deref(), &rel, &doc, &text, &elsewhere);
        Some((found, doc))
    }

    fn publish(&mut self, path: &Path) {
        let uri = self.uri_of(path);
        let version = self.open.get(path).map(|o| o.version);
        let diagnostics: Vec<Value> = if path.file_name().is_some_and(|n| n == "plugin.json") {
            let Some(text) = self.text_of(path) else {
                self.notify("textDocument/publishDiagnostics", json!({"uri": uri, "diagnostics": []}));
                return;
            };
            let index = LineIndex::new(&text);
            manifest::problems(&text)
                .iter()
                .map(|p| {
                    let range = index.range(text::utf16_of_byte(&text, p.start), text::utf16_of_byte(&text, p.end));
                    let severity = match p.severity {
                        Severity::Error => 1,
                        Severity::Warning => 2,
                        Severity::Info => 3,
                    };
                    let message = match &p.help {
                        Some(h) => format!("{}\n{h}", p.message),
                        None => p.message.clone(),
                    };
                    json!({"range": features::range_json(range), "severity": severity, "code": p.code, "source": "wmpy", "message": message})
                })
                .collect()
        } else if path.extension().is_some_and(|e| e == "py") {
            match self.problems(path) {
                Some((found, doc)) => {
                    let index = LineIndex::of_units(doc.units());
                    found.iter().map(|d| features::diagnostic_json(&index, d)).collect()
                }
                None => Vec::new(),
            }
        } else {
            return;
        };
        let mut params = json!({"uri": uri, "diagnostics": diagnostics});
        if let Some(v) = version {
            params["version"] = json!(v);
        }
        self.notify("textDocument/publishDiagnostics", params);
    }

    /// After [path] changed: its problems, and those of files that read it.
    fn changed(&mut self, path: &Path, delay: Duration) {
        if path.file_name().is_some_and(|n| n == "plugin.json") {
            if let Some(dir) = path.parent() {
                let dir = dir.to_path_buf();
                self.schedule_plugin(&dir, delay);
            }
            return;
        }
        self.schedule(path, delay);
        if let Some((p, rel)) = self.plugin_of(path) {
            // The entry's "nothing answers this provider" depends on every module.
            if rel != p.entry {
                let entry = p.dir.join(&p.entry);
                self.schedule(&entry, delay * 2);
            }
        }
    }

    // ---- notifications -----------------------------------------------------------

    fn notification(&mut self, n: Notification) {
        let p = &n.params;
        let path_of = |p: &Value| p.pointer("/textDocument/uri").and_then(Value::as_str).and_then(path_of_uri_str);
        match n.method.as_str() {
            "textDocument/didOpen" => {
                let Some(path) = path_of(p) else { return };
                let Ok(uri) = serde_json::from_value::<Uri>(p["textDocument"]["uri"].clone()) else { return };
                let text = p["textDocument"]["text"].as_str().unwrap_or("").to_string();
                let version = p["textDocument"]["version"].as_i64().unwrap_or(0) as i32;
                self.open.insert(path.clone(), Open { uri, text, version });
                if path.file_name().is_some_and(|f| f == "plugin.json") {
                    self.changed(&path, Duration::ZERO);
                } else if let Some((plugin, _)) = self.plugin_of(&path) {
                    // The first file of a plugin opened shows the problems of all of it.
                    let dir = plugin.dir.clone();
                    if !self.seen.contains(&dir) {
                        self.schedule_plugin(&dir, Duration::ZERO);
                    }
                    self.schedule(&path, Duration::ZERO);
                } else {
                    self.schedule(&path, Duration::ZERO);
                }
            }
            "textDocument/didChange" => {
                let Some(path) = path_of(p) else { return };
                let Some(open) = self.open.get_mut(&path) else { return };
                open.version = p["textDocument"]["version"].as_i64().unwrap_or(open.version as i64) as i32;
                for change in p["contentChanges"].as_array().into_iter().flatten() {
                    let new = change["text"].as_str().unwrap_or("");
                    match serde_json::from_value::<Range>(change["range"].clone()) {
                        Ok(range) => text::apply_change(&mut open.text, range, new),
                        Err(_) => open.text = new.to_string(),
                    }
                }
                self.changed(&path, DEBOUNCE);
            }
            "textDocument/didSave" => {
                if let Some(path) = path_of(p) {
                    self.changed(&path, Duration::ZERO);
                }
            }
            "textDocument/didClose" => {
                let Some(path) = path_of(p) else { return };
                let uri = self.open.remove(&path).map(|o| o.uri);
                if path.file_name().is_some_and(|f| f == "plugin.json") {
                    self.changed(&path, Duration::ZERO);
                } else if self.plugin_of(&path).is_none() {
                    // Outside a plugin, nothing keeps its problems on show.
                    if let Some(uri) = uri {
                        self.notify("textDocument/publishDiagnostics", json!({"uri": uri, "diagnostics": []}));
                    }
                } else {
                    self.schedule(&path, Duration::ZERO);
                }
            }
            "workspace/didChangeWatchedFiles" => {
                for change in p["changes"].as_array().into_iter().flatten() {
                    let Some(path) = change["uri"].as_str().and_then(path_of_uri_str) else { continue };
                    let kind = change["type"].as_i64().unwrap_or(2);
                    let is_manifest = path.file_name().is_some_and(|f| f == "plugin.json");
                    if is_manifest || kind != 2 {
                        // A file made or deleted changes the plugin's modules.
                        let dir = if is_manifest { path.parent().map(Path::to_path_buf) } else { plugin::plugin_dir_of(&path) };
                        if let Some(dir) = dir {
                            if dir.join("plugin.json").is_file() {
                                self.schedule_plugin(&dir, DEBOUNCE);
                            } else {
                                self.plugins.remove(&dir);
                            }
                        }
                        if kind == 3 {
                            self.notify("textDocument/publishDiagnostics", json!({"uri": change["uri"], "diagnostics": []}));
                            self.pending.remove(&path);
                        }
                    } else if !self.open.contains_key(&path) {
                        self.changed(&path, DEBOUNCE);
                    }
                }
            }
            "workspace/didChangeWorkspaceFolders" => {
                for added in p.pointer("/event/added").and_then(Value::as_array).into_iter().flatten() {
                    if let Some(root) = added["uri"].as_str().and_then(path_of_uri_str) {
                        for dir in plugin::plugins_under(&root) {
                            self.schedule_plugin(&dir, Duration::ZERO);
                        }
                        self.roots.push(root);
                    }
                }
                for removed in p.pointer("/event/removed").and_then(Value::as_array).into_iter().flatten() {
                    if let Some(root) = removed["uri"].as_str().and_then(path_of_uri_str) {
                        self.roots.retain(|r| *r != root);
                    }
                }
            }
            _ => {}
        }
    }

    // ---- requests ----------------------------------------------------------------

    /// The answer to [req], or None when a worker thread sends it later.
    fn request(&mut self, req: Request) -> Option<Answer> {
        let p = req.params.clone();
        let method = req.method.as_str();
        if method == "workspace/executeCommand" {
            return self.command(req);
        }
        if method == "completionItem/resolve" {
            return Some(Ok(features::completion_resolve(p, self.caps)));
        }
        let Some(path) = p.pointer("/textDocument/uri").and_then(Value::as_str).and_then(path_of_uri_str) else {
            return Some(Err((-32601, format!("wmpy lsp does not answer {method}"))));
        };
        let pos: Option<Position> = serde_json::from_value(p["position"].clone()).ok();
        if path.file_name().is_some_and(|f| f == "plugin.json") {
            return Some(Ok(self.manifest_request(method, &path, pos)));
        }
        if !path.extension().is_some_and(|e| e == "py") {
            // Another JSON or text file an editor attached the server to.
            return Some(Ok(match method {
                "textDocument/completion" => json!({"isIncomplete": false, "items": []}),
                "textDocument/semanticTokens/full" => json!({"data": []}),
                "textDocument/hover" | "textDocument/signatureHelp" | "textDocument/definition" | "textDocument/prepareRename" | "textDocument/rename" => Value::Null,
                _ => json!([]),
            }));
        }
        let Some(text) = self.text_of(&path) else { return Some(Ok(Value::Null)) };
        let doc = self.doc(&path, &text);
        let found = self.plugin_of(&path);
        let host = match &found {
            Some((plugin, rel)) => plugin.host(rel, plugin::verdict(&text)),
            None => Default::default(),
        };
        let f = File::new(&text, &doc, host);
        let caps = self.caps;
        let need_pos = || pos.ok_or((INVALID_PARAMS, "no position".to_string()));
        let answer = match method {
            "textDocument/completion" => {
                let explicit = p.pointer("/context/triggerKind").and_then(Value::as_i64).is_none_or(|k| k == 1);
                need_pos().map(|pos| features::completion(&f, pos, explicit, caps))
            }
            "textDocument/hover" => need_pos().map(|pos| features::hover(&f, pos, caps)),
            "textDocument/signatureHelp" => need_pos().map(|pos| features::signature_help(&f, pos, caps)),
            "textDocument/documentSymbol" => Ok(features::document_symbols(&f)),
            "textDocument/foldingRange" => Ok(features::folding_ranges(&f)),
            "textDocument/semanticTokens/full" => Ok(features::semantic_tokens(&f)),
            "textDocument/formatting" => Ok(features::formatting(&f)),
            "textDocument/codeLens" => Ok(match &found {
                Some((plugin, rel)) => {
                    let folder = self.uri_of(&plugin.dir);
                    let role = plugin.role(rel);
                    if role == Role::Module && f.doc.analysis.top_function("render").is_none() {
                        json!([])
                    } else {
                        features::code_lenses(&f, folder.as_str(), role == Role::Test)
                    }
                }
                None => json!([]),
            }),
            "textDocument/codeAction" => {
                let range: Range = serde_json::from_value(p["range"].clone()).unwrap_or_default();
                let uri = self.uri_of(&path);
                let problems = self.problems(&path).map(|(d, ..)| d).unwrap_or_default();
                let manifest_path = found.as_ref().map(|(plugin, _)| plugin.dir.join("plugin.json"));
                let manifest_text = manifest_path.as_ref().and_then(|m| self.text_of(m));
                let manifest_uri = manifest_path.as_ref().map(|m| self.uri_of(m));
                let manifest = match (&manifest_uri, &manifest_text) {
                    (Some(uri), Some(text)) => Some(features::ManifestFile { uri, text }),
                    _ => None,
                };
                Ok(features::code_actions(&f, &uri, range, &problems, manifest))
            }
            "textDocument/definition" => need_pos().map(|pos| self.definition(&path, &f, found.as_ref(), pos)),
            "textDocument/references" => need_pos().map(|pos| {
                let declaration = p.pointer("/context/includeDeclaration").and_then(Value::as_bool).unwrap_or(true);
                self.references(&path, &f, found.as_ref(), pos, declaration)
            }),
            "textDocument/prepareRename" => need_pos().and_then(|pos| self.prepare_rename(&f, found.as_ref(), pos)),
            "textDocument/rename" => {
                let new_name = p["newName"].as_str().unwrap_or("").to_string();
                need_pos().and_then(|pos| self.rename(&path, &f, found.as_ref(), pos, &new_name))
            }
            _ => Err((-32601, format!("wmpy lsp does not answer {method}"))),
        };
        Some(answer)
    }

    fn manifest_request(&self, method: &str, path: &Path, pos: Option<Position>) -> Value {
        let Some(text) = self.text_of(path) else { return Value::Null };
        let index = LineIndex::new(&text);
        let Some(pos) = pos else { return if method == "textDocument/documentSymbol" { json!([]) } else { Value::Null } };
        let at = text::byte_of_utf16(&text, index.offset(pos));
        match method {
            "textDocument/completion" => self.manifest_completion(&text, &index, at),
            "textDocument/hover" => match manifest::hover(&text, at) {
                Some((md, s, e)) => json!({
                    "contents": {"kind": "markdown", "value": md},
                    "range": features::range_json(index.range(text::utf16_of_byte(&text, s), text::utf16_of_byte(&text, e))),
                }),
                None => Value::Null,
            },
            _ => Value::Null,
        }
    }

    fn manifest_completion(&self, text: &str, index: &LineIndex, at: usize) -> Value {
        let Some(c) = json::context_at(text, at) else { return json!({"isIncomplete": false, "items": []}) };
        let caret = text::utf16_of_byte(text, at);
        let (start, in_string) = match c.string_start {
            Some(q) => (text::utf16_of_byte(text, q + 1), true),
            None => (caret - c.prefix.encode_utf16().count() as u32, false),
        };
        let range = features::range_json(index.range(start, caret));
        let mut items = Vec::new();
        if c.key {
            for (i, field) in manifest::fields_at(&c.path).unwrap_or(&[]).iter().enumerate() {
                let (new_text, format) = match (in_string, self.caps.snippets) {
                    (true, _) => (field.name.to_string(), 1),
                    (false, true) => (format!("\"{}\": {}", field.name, field.value), 2),
                    (false, false) => (format!("\"{}\": ", field.name), 1),
                };
                items.push(json!({
                    "label": field.name, "kind": 10, "sortText": format!("{i:03}"),
                    "documentation": {"kind": "markdown", "value": field.doc},
                    "textEdit": {"range": range, "newText": new_text}, "insertTextFormat": format,
                }));
            }
        } else {
            for (i, v) in manifest::values_at(&c).into_iter().enumerate() {
                let new_text = if in_string || !v.string { v.label.clone() } else { format!("\"{}\"", v.label) };
                let mut item = json!({
                    "label": v.label, "kind": 12, "sortText": format!("{i:03}"),
                    "textEdit": {"range": range, "newText": new_text},
                });
                if let Some(d) = v.detail {
                    item["detail"] = json!(d);
                }
                if let Some(d) = v.doc {
                    item["documentation"] = json!({"kind": "markdown", "value": d});
                }
                items.push(item);
            }
        }
        json!({"isIncomplete": false, "items": items})
    }

    // ---- navigation --------------------------------------------------------------

    fn location(&self, plugin: &Plugin, rel: &str, span: Span) -> Option<Value> {
        let doc = self.plugin_doc(plugin, rel)?;
        let index = LineIndex::of_units(doc.units());
        Some(json!({"uri": self.uri_of(&plugin.dir.join(rel)), "range": features::range_json(index.range(span.start, span.end))}))
    }

    fn definition(&self, path: &Path, f: &File, found: Option<&(Rc<Plugin>, String)>, pos: Position) -> Value {
        let caret = f.offset(pos);
        if let Some((plugin, rel)) = found {
            if let Some(g) = plugin::global_at(plugin, rel, f.doc, caret) {
                let docs = |r: &str| self.plugin_doc(plugin, r);
                if let Some((file, span)) = plugin::definition_of(plugin, &g, &docs) {
                    return self.location(plugin, &file, span).unwrap_or(Value::Null);
                }
            }
        }
        match navigate::definition(f.doc, caret) {
            Some(span) => json!({"uri": self.uri_of(path), "range": f.range(span.start, span.end)}),
            None => Value::Null,
        }
    }

    /// The top-level name of the plugin at [caret], as the file defining it and the name.
    fn global_name(&self, found: Option<&(Rc<Plugin>, String)>, f: &File, caret: u32) -> Option<(Rc<Plugin>, String, String)> {
        let (plugin, rel) = found?;
        let g = plugin::global_at(plugin, rel, f.doc, caret)?;
        if matches!(g, Global::Module { .. }) {
            return None;
        }
        // Followed through `from a import b` to where b is defined, by its name there.
        let docs = |r: &str| self.plugin_doc(plugin, r);
        let (file, span) = plugin::definition_of(plugin, &g, &docs)?;
        let doc = self.plugin_doc(plugin, &file)?;
        let name = wm_python_tooling::text::slice(doc.units(), span);
        doc.analysis.module_symbol(&name)?;
        Some((plugin.clone(), file, name))
    }

    fn references(&self, path: &Path, f: &File, found: Option<&(Rc<Plugin>, String)>, pos: Position, declaration: bool) -> Value {
        let caret = f.offset(pos);
        if let Some((plugin, file, name)) = self.global_name(found, f, caret) {
            let docs = |r: &str| self.plugin_doc(&plugin, r);
            let def = plugin::definition_of(&plugin, &Global::Top { rel: file.clone(), name: name.clone() }, &docs);
            let out: Vec<Value> = plugin::references_of(&plugin, &file, &name, &docs)
                .into_iter()
                .filter(|(r, s)| declaration || def.as_ref() != Some(&(r.clone(), *s)))
                .filter_map(|(r, s)| self.location(&plugin, &r, s))
                .collect();
            return Value::Array(out);
        }
        let def = navigate::definition(f.doc, caret);
        let uri = self.uri_of(path);
        let out: Vec<Value> = navigate::references(f.doc, caret)
            .into_iter()
            .filter(|s| declaration || Some(*s) != def)
            .map(|s| json!({"uri": uri, "range": f.range(s.start, s.end)}))
            .collect();
        Value::Array(out)
    }

    /// The rename plan of a top-level name where it is defined, checked there.
    fn global_rename_problem(&self, plugin: &Plugin, file: &str, name: &str, new_name: &str) -> Result<(), (i32, String)> {
        let def_doc = self.plugin_doc(plugin, file).ok_or((REQUEST_FAILED, format!("cannot read {file}")))?;
        let at = def_doc.analysis.module_symbol(name).map(|s| s.declaration.start).unwrap_or(0);
        match navigate::rename(&def_doc, at, new_name).and_then(|p| p.problem) {
            Some(problem) => Err((REQUEST_FAILED, rename_words(problem.name()).to_string())),
            None => Ok(()),
        }
    }

    fn prepare_rename(&self, f: &File, found: Option<&(Rc<Plugin>, String)>, pos: Position) -> Answer {
        let caret = f.offset(pos);
        let word = |span: Span| json!({"range": f.range(span.start, span.end), "placeholder": wm_python_tooling::text::slice(f.doc.units(), span)});
        if let Some((plugin, file, name)) = self.global_name(found, f, caret) {
            self.global_rename_problem(&plugin, &file, &name, "")?;
            let t = &f.doc.tokens;
            let token = t.index_at(caret).filter(|i| t.kind(*i) == wm_python_tooling::lexer::TokenKind::Name).or_else(|| t.index_at(caret.checked_sub(1)?));
            if let Some(i) = token {
                return Ok(word(t.span(i)));
            }
        }
        let Some(plan) = navigate::rename(f.doc, caret, "") else { return Ok(Value::Null) };
        if let Some(problem) = plan.problem {
            return Err((REQUEST_FAILED, rename_words(problem.name()).to_string()));
        }
        match plan.spans.iter().find(|s| s.touches(caret)) {
            Some(span) => Ok(word(*span)),
            None => Ok(Value::Null),
        }
    }

    fn rename(&self, path: &Path, f: &File, found: Option<&(Rc<Plugin>, String)>, pos: Position, new_name: &str) -> Answer {
        let caret = f.offset(pos);
        let mut changes: HashMap<String, Vec<Value>> = HashMap::new();
        if let Some((plugin, file, name)) = self.global_name(found, f, caret) {
            // Checked where it is defined, then renamed in every file that reads it.
            self.global_rename_problem(&plugin, &file, &name, new_name)?;
            let docs = |r: &str| self.plugin_doc(&plugin, r);
            for (r, s) in plugin::references_of(&plugin, &file, &name, &docs) {
                let Some(loc) = self.location(&plugin, &r, s) else { continue };
                let uri = loc["uri"].as_str().unwrap_or("").to_string();
                changes.entry(uri).or_default().push(json!({"range": loc["range"], "newText": new_name}));
            }
            return Ok(json!({"changes": changes}));
        }
        let Some(plan) = navigate::rename(f.doc, caret, new_name) else { return Err((REQUEST_FAILED, "There is no name here to rename.".into())) };
        if let Some(problem) = plan.problem {
            return Err((REQUEST_FAILED, rename_words(problem.name()).to_string()));
        }
        let uri = self.uri_of(path).as_str().to_string();
        let edits = plan.spans.iter().map(|s| json!({"range": f.range(s.start, s.end), "newText": new_name})).collect();
        changes.insert(uri, edits);
        Ok(json!({"changes": changes}))
    }

    // ---- commands ----------------------------------------------------------------

    /// The plugin folder a command's first argument names (a file inside one works too).
    fn command_dir(&self, args: &[Value]) -> Result<PathBuf, (i32, String)> {
        let given = args.first().and_then(Value::as_str).ok_or((INVALID_PARAMS, "the command needs the plugin folder's URI".to_string()))?;
        let path = path_of_uri_str(given).unwrap_or_else(|| PathBuf::from(given));
        plugin::plugin_dir_of(&path).ok_or((REQUEST_FAILED, format!("no plugin.json in or above {}", path.display())))
    }

    fn command(&mut self, req: Request) -> Option<Answer> {
        let name = req.params["command"].as_str().unwrap_or("").to_string();
        let args: Vec<Value> = req.params["arguments"].as_array().cloned().unwrap_or_default();
        let dir = match self.command_dir(&args) {
            Ok(d) => d,
            Err(e) => return Some(Err(e)),
        };
        let exe = self.exe.clone();
        let send = self.send.clone();
        let id = req.id.clone();
        let reply = move |answer: Answer| {
            send(Message::Response(match answer {
                Ok(v) => Response::new_ok(id, v),
                Err((code, message)) => Response::new_err(id, code, message),
            }));
        };
        let show = {
            let send = self.send.clone();
            move |error: bool, text: String| send(Message::Notification(Notification::new("window/showMessage".into(), json!({"type": if error { 1 } else { 3 }, "message": text}))))
        };
        match name.as_str() {
            "wmpy.check" => {
                self.schedule_plugin(&dir, Duration::ZERO);
                let plugin = self.plugin_at(&dir);
                let mut counts = [0u32; 3];
                let mut paths: Vec<PathBuf> = plugin.files.iter().map(|f| dir.join(f)).collect();
                paths.push(dir.join("plugin.json"));
                for path in &paths {
                    self.pending.remove(path);
                    self.publish(path);
                }
                for path in plugin.files.iter().map(|f| dir.join(f)) {
                    for d in self.problems(&path).map(|(d, ..)| d).unwrap_or_default() {
                        counts[d.severity() as usize] += 1;
                    }
                }
                if let Some(text) = &plugin.manifest_text {
                    for p in manifest::problems(text) {
                        counts[p.severity as usize] += 1;
                    }
                }
                Some(Ok(json!({"errors": counts[0], "warnings": counts[1], "infos": counts[2], "files": plugin.files.len()})))
            }
            "wmpy.run" => Some(Ok(json!({"command": [exe.to_string_lossy(), "run", dir.to_string_lossy()]}))),
            "wmpy.preview" => {
                let previews = self.previews.clone();
                let browser = self.preview == PreviewMode::Browser;
                std::thread::spawn(move || {
                    let answer = previews.start(&exe, &dir).map(|url| {
                        if browser {
                            if let Err(e) = commands::open_browser(&url) {
                                show(true, e);
                            }
                        }
                        json!({"url": url})
                    });
                    if let Err(e) = &answer {
                        show(true, e.clone());
                    }
                    reply(answer.map_err(|e| (REQUEST_FAILED, e)));
                });
                None
            }
            "wmpy.test" => {
                let filter = args.get(1).and_then(Value::as_str).map(str::to_string);
                std::thread::spawn(move || {
                    let answer = commands::test(&exe, &dir, filter.as_deref(), None);
                    match &answer {
                        Ok(v) => {
                            if let Some(summary) = commands::test_summary(v) {
                                show(true, summary);
                            }
                        }
                        Err(e) => show(true, e.clone()),
                    }
                    reply(answer.map_err(|e| (REQUEST_FAILED, e)));
                });
                None
            }
            "wmpy.pack" => {
                std::thread::spawn(move || {
                    let answer = commands::pack(&exe, &dir);
                    match &answer {
                        Ok(v) => show(false, format!("Packed {}", v["path"].as_str().unwrap_or("the plugin"))),
                        Err(e) => show(true, e.clone()),
                    }
                    reply(answer.map_err(|e| (REQUEST_FAILED, e)));
                });
                None
            }
            other => Some(Err((-32601, format!("wmpy lsp has no command {other}")))),
        }
    }
}

/// Why a rename cannot go ahead, in words.
fn rename_words(problem: &str) -> &'static str {
    match problem {
        "not_a_name" => "That is not a Python name.",
        "keyword" => "That is a Python keyword.",
        "builtin" => "That is a builtin's name, or the new name would hide one this file uses.",
        "taken" => "Another name of that spelling is already seen there.",
        "api" => "That is the plugin API or a module, which keeps its name.",
        "hook" => "The keyboard calls this function by its name, so it keeps it.",
        _ => "This cannot be renamed.",
    }
}

