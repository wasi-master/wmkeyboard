//! A plugin folder as the language server reads it: its plugin.json, its
//! `.py` files and what each file is for, the Host the checks get for each,
//! and the names that reach from one module into another.
//!
//! The MCP server's checks run through here too, so an agent sees the same
//! problems an editor shows.

use std::collections::HashSet;
use std::path::{Path, PathBuf};
use std::rc::Rc;

use serde_json::Value;
use wm_python_tooling::analysis::Target;
use wm_python_tooling::api_json::host_of_manifest;
use wm_python_tooling::ast::StmtKind;
use wm_python_tooling::diagnostics::{diagnose, Code, Diagnostic};
use wm_python_tooling::document::{Document, Host, Verdict};
use wm_python_tooling::keyboard;
use wm_python_tooling::lexer::TokenKind;
use wm_python_tooling::text::Span;

/// Most `.py` files read from one plugin folder.
const MAX_FILES: usize = 400;
/// How deep under the plugin folder files are looked for.
const MAX_DEPTH: usize = 6;

pub struct Plugin {
    pub dir: PathBuf,
    /// plugin.json as text, when it could be read.
    pub manifest_text: Option<String>,
    /// plugin.json parsed, when it is valid JSON with an object at the top.
    pub manifest: Option<Value>,
    pub entry: String,
    /// Every `.py` file, relative with `/`, sorted.
    pub files: Vec<String>,
    /// Top-level names `wmpy pip` vendored, which the checks treat as modules.
    pub vendored: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Role {
    /// The script the keyboard runs.
    Entry,
    /// Another module of the plugin.
    Module,
    /// test.py, or tests/test_*.py.
    Test,
}

impl Plugin {
    /// The plugin in [dir], with plugin.json given as [manifest_text] (read
    /// from disk when None).
    pub fn load(dir: &Path, manifest_text: Option<String>) -> Plugin {
        let manifest_text = manifest_text.or_else(|| std::fs::read_to_string(dir.join("plugin.json")).ok());
        let manifest = manifest_text.as_deref().and_then(|t| serde_json::from_str::<Value>(t).ok()).filter(Value::is_object);
        let entry = manifest
            .as_ref()
            .and_then(|m| m.get("entry"))
            .and_then(Value::as_str)
            .map(|e| e.trim().to_string())
            .filter(|e| e.ends_with(".py"))
            .unwrap_or_else(|| "main.py".into());
        let vendored = crate::pip::lock::vendored_roots(dir);
        let files = py_files(dir, &vendored);
        Plugin { dir: dir.to_path_buf(), manifest_text, manifest, entry, files, vendored }
    }

    pub fn role(&self, rel: &str) -> Role {
        if rel == self.entry {
            Role::Entry
        } else if is_test_file(rel) {
            Role::Test
        } else {
            Role::Module
        }
    }

    /// The plugin's own modules by dotted name, the entry left out, and what
    /// `wmpy pip` vendored.
    pub fn module_names(&self) -> Vec<String> {
        let mut out: Vec<String> = self
            .files
            .iter()
            .filter(|f| **f != self.entry && !is_test_file(f))
            .flat_map(|f| {
                let name = module_name(f);
                // `pkg/util.py` also makes `pkg` importable.
                let mut names = vec![name.clone()];
                if let Some((root, _)) = name.split_once('.') {
                    names.push(root.to_string());
                }
                names
            })
            .chain(self.vendored.iter().cloned())
            .collect();
        out.sort();
        out.dedup();
        out
    }

    /// The file a dotted module name is, if it is one of the plugin's.
    pub fn file_of_module(&self, module: &str) -> Option<&str> {
        self.files.iter().map(String::as_str).find(|f| module_name(f) == module)
    }

    /// What the checks of file [rel] know about the plugin.
    pub fn host(&self, rel: &str, verdict: Option<Verdict>) -> Host {
        let mut host = match &self.manifest {
            Some(m) => host_of_manifest(m),
            None => Host::default(),
        };
        let mut modules = self.module_names();
        match self.role(rel) {
            Role::Entry => {}
            Role::Module => {
                // The contract (render, transform, unanswered providers) is the entry's.
                host.panel = None;
            }
            Role::Test => {
                // A test runs with every permission, imports the entry, and has plugin_test.
                if let Some(stem) = self.entry.strip_suffix(".py") {
                    modules.push(stem.replace('/', "."));
                }
                modules.push("plugin_test".into());
                host = Host { api: host.api, ..Host::default() };
            }
        }
        modules.retain(|m| m != &module_name(rel));
        host.modules = Some(modules);
        host.verdict = verdict;
        host
    }
}

/// The plugin folder [file] belongs to: the nearest folder above it with a plugin.json.
pub fn plugin_dir_of(file: &Path) -> Option<PathBuf> {
    let mut at = if file.is_dir() { Some(file) } else { file.parent() };
    while let Some(dir) = at {
        if dir.join("plugin.json").is_file() {
            return Some(dir.to_path_buf());
        }
        at = dir.parent();
    }
    None
}

/// Plugin folders at or under [root], for a workspace folder.
pub fn plugins_under(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut stack = vec![(root.to_path_buf(), 0usize)];
    while let Some((dir, depth)) = stack.pop() {
        if dir.join("plugin.json").is_file() {
            out.push(dir.clone());
            // A plugin's own folders hold no other plugins.
            continue;
        }
        if depth >= 4 || out.len() >= 64 {
            continue;
        }
        let Ok(read) = std::fs::read_dir(&dir) else { continue };
        for item in read.flatten() {
            let name = item.file_name().to_string_lossy().to_string();
            if skipped_dir(&name) {
                continue;
            }
            if item.file_type().is_ok_and(|t| t.is_dir()) {
                stack.push((item.path(), depth + 1));
            }
        }
    }
    out.sort();
    out
}

fn skipped_dir(name: &str) -> bool {
    name.starts_with('.') || matches!(name, "node_modules" | "target" | "build" | "__pycache__" | "dist" | "venv")
}

/// Every `.py` file under [dir] as a relative path, but in vendored packages.
pub fn py_files(dir: &Path, vendored: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    let mut stack = vec![(dir.to_path_buf(), 0usize)];
    while let Some((d, depth)) = stack.pop() {
        let Ok(read) = std::fs::read_dir(&d) else { continue };
        for item in read.flatten() {
            let path = item.path();
            let Ok(rel) = path.strip_prefix(dir) else { continue };
            let rel = rel.to_string_lossy().replace('\\', "/");
            let name = item.file_name().to_string_lossy().to_string();
            let first = rel.split('/').next().unwrap_or("").trim_end_matches(".py").to_string();
            if vendored.contains(&first) {
                continue;
            }
            match item.file_type() {
                Ok(t) if t.is_dir() => {
                    if !skipped_dir(&name) && depth < MAX_DEPTH {
                        stack.push((path, depth + 1));
                    }
                }
                Ok(_) if name.ends_with(".py") && !name.starts_with('.') => out.push(rel),
                _ => {}
            }
            if out.len() >= MAX_FILES {
                break;
            }
        }
    }
    out.sort();
    out
}

pub fn is_test_file(rel: &str) -> bool {
    let name = rel.rsplit('/').next().unwrap_or(rel);
    rel == "test.py" || (rel.starts_with("tests/") && name.starts_with("test") && name.ends_with(".py"))
}

/// `pkg/util.py` as `pkg.util`, `pkg/__init__.py` as `pkg`.
pub fn module_name(rel: &str) -> String {
    let stem = rel.strip_suffix(".py").unwrap_or(rel);
    let stem = stem.strip_suffix("/__init__").unwrap_or(stem);
    stem.replace('/', ".")
}

/// The interpreter's verdict on [source]: why it would refuse to load it.
pub fn verdict(source: &str) -> Option<Verdict> {
    wm_python::interp::check(source).err().map(|e| Verdict { message: e.message, kind: e.kind, line: e.line.map(|l| l as usize) })
}

// ---- the checks, with the plugin's other files in mind ---------------------

/// The providers, actions and previews some file of the plugin answers.
#[derive(Default)]
pub struct Answered {
    pub providers: HashSet<(String, String)>,
    pub actions: HashSet<String>,
    pub previews: HashSet<String>,
    pub thumbnails: HashSet<String>,
}

impl Answered {
    pub fn add(&mut self, doc: &Document) {
        for d in keyboard::decorated(&doc.module, &doc.analysis) {
            let id = d.literal(0, "id").map(|(v, _)| v.to_string());
            match d.what.as_str() {
                "plugin.provider" => {
                    if let Some((tool, id, _)) = d.provider() {
                        self.providers.insert((tool, id));
                    }
                }
                "plugin.action" => self.actions.extend(id),
                "plugin.preview" => self.previews.extend(id),
                "plugin.thumbnail" => self.thumbnails.extend(id),
                _ => {}
            }
        }
    }
}

/// The diagnostics of file [rel], whose document is [doc]. [elsewhere] is
/// what the plugin's other files answer.
pub fn diagnose_file(plugin: Option<&Plugin>, rel: &str, doc: &Document, source: &str, elsewhere: &Answered) -> Vec<Diagnostic> {
    let verdict = verdict(source);
    let Some(plugin) = plugin else {
        let host = Host { verdict, ..Host::default() };
        return diagnose(doc, &host).diagnostics;
    };
    let role = plugin.role(rel);
    let host = plugin.host(rel, verdict);
    let mut out = diagnose(doc, &host).diagnostics;
    let arg = |d: &Diagnostic, i: usize| d.args.get(i).cloned().unwrap_or_default();
    out.retain(|d| match role {
        Role::Entry => match d.code {
            Code::ProviderUnanswered => !elsewhere.providers.contains(&(arg(d, 0), arg(d, 1))),
            Code::ActionUnanswered => !elsewhere.actions.contains(&arg(d, 0)),
            Code::PreviewUnanswered => !elsewhere.previews.contains(&arg(d, 0)),
            Code::ThumbnailUnanswered => !elsewhere.thumbnails.contains(&arg(d, 0)),
            _ => true,
        },
        Role::Module => !matches!(
            d.code,
            Code::MissingRender
                | Code::MissingTransform
                | Code::ProviderUnanswered
                | Code::ActionUnanswered
                | Code::PreviewUnanswered
                | Code::ThumbnailUnanswered
        ),
        Role::Test => !(matches!(d.code, Code::UndefinedName | Code::NameTypo) && arg(d, 0) == "plugin_test"),
    });
    out
}

/// One problem as wmpy's Diag JSON: 1-based line and character columns.
pub fn diag_json(rel: &str, source: &str, d: &Diagnostic) -> Value {
    let units: Vec<u16> = source.encode_utf16().collect();
    let index = super::text::LineIndex::of_units(&units);
    let start = index.position(d.span.start);
    let end = index.position(d.span.end);
    let line_start = index.line_start(start.line as usize) as usize;
    let chars = |from: usize, to: usize| String::from_utf16_lossy(&units[from.min(units.len())..to.min(units.len())]).chars().count() as u32;
    let col = chars(line_start, line_start + start.character as usize) + 1;
    let end_col = if end.line == start.line { chars(line_start, line_start + end.character as usize) + 1 } else { col + 1 };
    let severity = d.severity().name();
    let help = d.fix.as_ref().map(fix_help);
    serde_json::json!({
        "severity": severity, "code": d.code.name(), "message": d.text(), "file": rel,
        "line": start.line + 1, "col": col, "endCol": end_col.max(col + 1),
        "traceback": [], "notes": [], "help": help,
    })
}

pub use crate::diag::fix_help;

/// Every problem in the plugin in [dir], its plugin.json's included, as Diag JSON.
pub fn check_folder(dir: &Path) -> Vec<Value> {
    let plugin = Plugin::load(dir, None);
    let mut out = super::manifest::diagnostics_json(&plugin);
    let sources: Vec<(String, String)> = plugin.files.iter().filter_map(|f| std::fs::read_to_string(dir.join(f)).ok().map(|t| (f.clone(), t))).collect();
    let docs: Vec<Rc<Document>> = sources.iter().map(|(_, t)| Rc::new(Document::new(t))).collect();
    for (i, (rel, text)) in sources.iter().enumerate() {
        let mut elsewhere = Answered::default();
        for (j, d) in docs.iter().enumerate() {
            if j != i {
                elsewhere.add(d);
            }
        }
        for d in diagnose_file(Some(&plugin), rel, &docs[i], text, &elsewhere) {
            out.push(diag_json(rel, text, &d));
        }
    }
    out
}

// ---- names that reach across modules ---------------------------------------

/// A top-level name of one of the plugin's files.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Global {
    /// `name` as file `rel` binds it at the top.
    Top { rel: String, name: String },
    /// A module of the plugin, imported whole.
    Module { rel: String },
}

/// What the name at [caret] of file [rel] is, when it is a top-level name of
/// one of the plugin's files (this one included) or one of its modules.
pub fn global_at(plugin: &Plugin, rel: &str, doc: &Document, caret: u32) -> Option<Global> {
    let a = &doc.analysis;
    if let Some(o) = a.occurrence_at(caret) {
        if let Target::Symbol(id) = o.target {
            let s = &a.symbols[id];
            return match &s.import {
                Some((module, Some(member))) => plugin.file_of_module(module).map(|f| Global::Top { rel: f.to_string(), name: member.clone() }),
                Some((module, None)) => plugin.file_of_module(module).map(|f| Global::Module { rel: f.to_string() }),
                None if s.scope == 0 => Some(Global::Top { rel: rel.to_string(), name: s.name.clone() }),
                None => None,
            };
        }
    }
    // `helpers.name`: an attribute of a module of the plugin.
    let t = &doc.tokens;
    let i = t.index_at(caret).filter(|i| t.kind(*i) == TokenKind::Name).or_else(|| {
        let i = t.index_at(caret.checked_sub(1)?)?;
        (t.kind(i) == TokenKind::Name).then_some(i)
    })?;
    let module = module_before_dot(plugin, doc, i)?;
    Some(Global::Top { rel: module, name: t.text(i) })
}

/// The plugin file a `module.` before token [i] names, when it does.
fn module_before_dot(plugin: &Plugin, doc: &Document, i: usize) -> Option<String> {
    let t = &doc.tokens;
    let dot = t.prev_code(i).filter(|d| t.is_op(*d, "."))?;
    let root = t.prev_code(dot).filter(|r| t.kind(*r) == TokenKind::Name)?;
    let o = doc.analysis.occurrence_at(t.start(root)).filter(|o| o.span == t.span(root))?;
    let Target::Symbol(id) = o.target else { return None };
    match &doc.analysis.symbols[id].import {
        Some((module, None)) => plugin.file_of_module(module).map(str::to_string),
        _ => None,
    }
}

/// Where [g] is defined: a file and a span in it. A name a file imports
/// from another of the plugin's modules is followed there.
pub fn definition_of(plugin: &Plugin, g: &Global, docs: &dyn Fn(&str) -> Option<Rc<Document>>) -> Option<(String, Span)> {
    let mut at = g.clone();
    for _ in 0..8 {
        match at {
            Global::Module { rel } => return Some((rel, Span::new(0, 0))),
            Global::Top { ref rel, ref name } => {
                let doc = docs(rel)?;
                let s = doc.analysis.module_symbol(name)?;
                match &s.import {
                    Some((module, member)) => {
                        let Some(file) = plugin.file_of_module(module) else { return Some((rel.clone(), s.declaration)) };
                        at = match member {
                            Some(m) => Global::Top { rel: file.to_string(), name: m.clone() },
                            None => Global::Module { rel: file.to_string() },
                        };
                    }
                    None => return Some((rel.clone(), s.declaration)),
                }
            }
        }
    }
    None
}

/// Every place across the plugin that means the top-level [name] of file
/// [rel]: its own references, `from module import name` and what that bound,
/// and `module.name` wherever the module is imported whole.
pub fn references_of(plugin: &Plugin, rel: &str, name: &str, docs: &dyn Fn(&str) -> Option<Rc<Document>>) -> Vec<(String, Span)> {
    let mut out = Vec::new();
    if let Some(doc) = docs(rel) {
        if let Some(s) = doc.analysis.module_symbol(name) {
            out.extend(s.references().into_iter().map(|span| (rel.to_string(), span)));
        }
    }
    let module = module_name(rel);
    for file in &plugin.files {
        if file == rel {
            continue;
        }
        let Some(doc) = docs(file) else { continue };
        let a = &doc.analysis;
        for s in &a.symbols {
            match &s.import {
                Some((m, Some(member))) if *m == module && member == name => {
                    if s.name == *name {
                        out.extend(s.references().into_iter().map(|span| (file.clone(), span)));
                    } else if let Some(span) = imported_member_span(&doc, &module, name) {
                        // `from m import name as other`: only the imported name is this one.
                        out.push((file.clone(), span));
                    }
                }
                Some((m, None)) if *m == module => {
                    let t = &doc.tokens;
                    for i in 0..t.len() {
                        if t.kind(i) == TokenKind::Name && t.text(i) == name && module_before_dot(plugin, &doc, i).as_deref() == Some(rel) {
                            out.push((file.clone(), t.span(i)));
                        }
                    }
                }
                _ => {}
            }
        }
    }
    out.sort();
    out.dedup();
    out
}

fn imported_member_span(doc: &Document, module: &str, name: &str) -> Option<Span> {
    doc.module.body.iter().find_map(|stmt| match &stmt.kind {
        StmtKind::ImportFrom { module: m, names, .. } if m == module => names.iter().find(|n| n.name == name).map(|n| n.span),
        _ => None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn module_names_and_tests() {
        assert_eq!(module_name("pkg/util.py"), "pkg.util");
        assert_eq!(module_name("pkg/__init__.py"), "pkg");
        assert!(is_test_file("test.py"));
        assert!(is_test_file("tests/test_fares.py"));
        assert!(!is_test_file("helpers.py"));
    }
}
