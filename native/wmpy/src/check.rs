//! `wmpy check`: everything the keyboard would refuse or trip over, without
//! running the plugin.
//!
//! * plugin.json, by the app's own importer rules
//!   ([wm_python_tooling::manifest::check]), each problem placed in the file;
//! * the archive the folder would become: the entry script, modules, assets,
//!   translations and the caps ([wm_python_tooling::manifest::archive_problems]);
//! * every `.py` file the plugin ships, through the language service with
//!   what plugin.json declares (permissions, hosts, providers, settings), and
//!   the interpreter's own verdict on whether it loads. Test files and the
//!   packages `wmpy pip` vendored are checked for syntax only.
//!
//! Exit 1 on an error, or on a warning with `--strict`.

use std::collections::HashSet;
use std::path::{Path, PathBuf};

use serde_json::json;
use wm_python_tooling::api_json::host_of_manifest;
use wm_python_tooling::diagnostics::{diagnose, Diagnostic};
use wm_python_tooling::document::{Document, Host, Verdict};
use wm_python_tooling::keyboard;
use wm_python_tooling::manifest::{self as codec, Role};

use crate::diag::{color_stderr, folder_source, Diag, Severity};
use crate::project;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Print {"ok", "errors", "warnings", "problems"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
    /// Fail on warnings too, not only on errors
    #[arg(long)]
    pub strict: bool,
}

/// What checking a plugin folder found.
pub struct Outcome {
    pub problems: Vec<Diag>,
    /// How many `.py` files were checked.
    pub python_files: usize,
}

impl Outcome {
    pub fn errors(&self) -> usize {
        self.problems.iter().filter(|d| d.severity == Severity::Error).count()
    }

    pub fn warnings(&self) -> usize {
        self.problems.iter().filter(|d| d.severity == Severity::Warning).count()
    }

    pub fn json(&self) -> serde_json::Value {
        json!({
            "ok": self.errors() == 0, "errors": self.errors(), "warnings": self.warnings(),
            "problems": self.problems.iter().map(Diag::to_json).collect::<Vec<_>>(),
        })
    }

    /// Prints every problem with its code frame, then the summary line, to stderr.
    pub fn print(&self, dir: &Path) {
        let color = color_stderr();
        let source = folder_source(dir);
        for d in &self.problems {
            eprint!("{}", d.render(&source, color));
            eprintln!();
        }
        eprintln!("{}", self.summary());
    }

    pub fn summary(&self) -> String {
        let count = |n: usize, one: &str, many: &str| format!("{n} {}", if n == 1 { one } else { many });
        let notes = self.problems.iter().filter(|d| d.severity == Severity::Info).count();
        let files = match self.python_files {
            1 => "plugin.json and 1 Python file".to_string(),
            n => format!("plugin.json and {n} Python files"),
        };
        if self.problems.is_empty() {
            return format!("checked {files}: no problems");
        }
        let mut parts = vec![count(self.errors(), "error", "errors"), count(self.warnings(), "warning", "warnings")];
        if notes > 0 {
            parts.push(count(notes, "note", "notes"));
        }
        format!("checked {files}: {}", parts.join(", "))
    }
}

pub fn run(args: Args) -> u8 {
    let dir = match project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            if args.json {
                println!("{}", json!({"ok": false, "errors": 1, "warnings": 0, "problems": [d.to_json()]}));
            } else {
                eprint!("{}", d.render(&|_| None, color_stderr()));
            }
            return 2;
        }
    };
    let outcome = check_dir(&dir);
    if args.json {
        println!("{}", outcome.json());
    } else {
        outcome.print(&dir);
    }
    let failed = outcome.errors() > 0 || (args.strict && outcome.warnings() > 0);
    u8::from(failed)
}

/// Codes the language service gives for text the interpreter cannot read:
/// what a test file or a vendored package is checked for.
const SYNTAX_CODES: &[&str] = &[
    "unknown_character",
    "unclosed_string",
    "unclosed_triple_string",
    "bad_number",
    "unclosed_bracket",
    "stray_closer",
    "wrong_closer",
    "unexpected_indent",
    "inconsistent_dedent",
    "empty_block",
    "syntax_expected",
    "syntax_at_end",
    "unsupported",
    "will_not_run",
];

/// The checks that look across the plugin's modules: a provider, preview,
/// thumbnail or action answered in one module is answered for all of them.
const ANSWERED_CODES: &[&str] = &["provider_unanswered", "preview_unanswered", "thumbnail_unanswered", "action_unanswered"];

/// Everything wrong with the plugin in [dir] (a folder that holds plugin.json).
pub fn check_dir(dir: &Path) -> Outcome {
    let mut problems: Vec<Diag> = Vec::new();
    let text = std::fs::read_to_string(dir.join(codec::MANIFEST)).unwrap_or_default();
    let report = codec::check(&text);
    problems.extend(report.problems.iter().map(|p| Diag::from_manifest(p, &text)));

    let files = project::files(dir);
    let entry = report.manifest.as_ref().map(|m| m.entry()).unwrap_or_else(|| codec::DEFAULT_ENTRY.to_string());
    if let Some(m) = &report.manifest {
        let packed: Vec<(String, Vec<u8>)> = files
            .iter()
            .filter(|f| codec::packed_role(f, m).is_some())
            .filter_map(|f| std::fs::read(dir.join(f)).ok().map(|b| (f.clone(), b)))
            .collect();
        let refs: Vec<(&str, &[u8])> = packed.iter().map(|(p, b)| (p.as_str(), b.as_slice())).collect();
        problems.extend(codec::archive_problems(m, &refs).iter().map(|p| Diag::from_manifest(p, &text)));
    } else if !dir.join(&entry).is_file() {
        let mut d = Diag::error(format!("there is no {entry} beside plugin.json"));
        d.file = Some(entry.clone());
        problems.push(d);
    }

    // The Python files: the entry, then the modules the archive takes.
    let package = report.manifest.as_ref().is_none_or(|m| m.package());
    let modules: Vec<String> = files
        .iter()
        .filter(|f| f.ends_with(".py") && **f != entry && !codec::is_dev_file(f))
        .filter(|f| match &report.manifest {
            Some(m) => codec::packed_role(f, m) == Some(Role::Module),
            None => true,
        })
        .cloned()
        .collect();
    let vendored: Vec<String> = crate::pip::lock::vendored_roots(dir);
    let is_vendored = |f: &str| {
        let top = f.split('/').next().unwrap_or(f);
        let top = top.strip_suffix(".py").unwrap_or(top);
        vendored.iter().any(|r| r == top)
    };
    let manifest_json: serde_json::Value = serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
    let mut host = if manifest_json.is_object() { host_of_manifest(&manifest_json) } else { Host::default() };
    if package {
        host.modules = Some(modules.iter().map(|f| dotted(f)).collect());
    }

    let mut python_files = 0;
    let mut answered: HashSet<(String, String)> = HashSet::new();
    let mut entry_found: Vec<Diagnostic> = Vec::new();
    let mut entry_source = String::new();
    let mut later: Vec<Diag> = Vec::new();
    if let Ok(source) = std::fs::read_to_string(dir.join(&entry)) {
        python_files += 1;
        entry_found = diagnostics(&source, &host);
        entry_source = source;
    }
    if package {
        for file in &modules {
            let Ok(source) = std::fs::read_to_string(dir.join(file)) else { continue };
            python_files += 1;
            if is_vendored(file) {
                let found = diagnostics(&source, &Host::default());
                later.extend(found.iter().filter(|d| SYNTAX_CODES.contains(&d.code.name())).map(|d| Diag::from_tooling(d, file, &source)));
                continue;
            }
            let doc = Document::new(&source);
            answered.extend(answers(&doc));
            let module_host = Host { panel: Some(false), ..host.clone() };
            let found = diagnostics(&source, &module_host);
            later.extend(found.iter().filter(|d| !ANSWERED_CODES.contains(&d.code.name())).map(|d| Diag::from_tooling(d, file, &source)));
        }
    }
    for d in &entry_found {
        let args = (d.args.first().cloned().unwrap_or_default(), d.args.get(1).cloned().unwrap_or_default());
        let key = match d.code.name() {
            "provider_unanswered" => Some(("provider:".to_string() + &args.0, args.1.clone())),
            "preview_unanswered" => Some(("preview".to_string(), args.0.clone())),
            "thumbnail_unanswered" => Some(("thumbnail".to_string(), args.0.clone())),
            "action_unanswered" => Some(("action".to_string(), args.0.clone())),
            _ => None,
        };
        if key.is_some_and(|k| answered.contains(&k)) {
            continue;
        }
        problems.push(Diag::from_tooling(d, &entry, &entry_source));
    }
    problems.extend(later);

    // Tests run in the test runner, not in the keyboard: only whether they read.
    for file in project::test_files(dir) {
        let Ok(source) = std::fs::read_to_string(dir.join(&file)) else { continue };
        python_files += 1;
        let found = diagnostics(&source, &Host::default());
        problems.extend(found.iter().filter(|d| SYNTAX_CODES.contains(&d.code.name())).map(|d| Diag::from_tooling(d, &file, &source)));
    }

    Outcome { problems, python_files }
}

/// The language service's problems with [source], the interpreter's verdict among them.
fn diagnostics(source: &str, host: &Host) -> Vec<Diagnostic> {
    let verdict = wm_python::interp::check(source).err().map(|e| Verdict { message: e.message, kind: e.kind, line: e.line.map(|l| l as usize) });
    let host = Host { verdict, ..host.clone() };
    let doc = Document::new(source);
    diagnose(&doc, &host).diagnostics
}

/// What a module's decorators answer: `(provider:<tool>, id)`, `(preview, id)`,
/// `(thumbnail, id)` and `(action, id)`.
fn answers(doc: &Document) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for d in keyboard::decorated(&doc.module, &doc.analysis) {
        match d.what.as_str() {
            "plugin.provider" => {
                if let Some((tool, id, _)) = d.provider() {
                    out.push((format!("provider:{tool}"), id));
                }
            }
            "plugin.preview" | "plugin.thumbnail" | "plugin.action" => {
                if let Some((id, _)) = d.literal(0, "id") {
                    out.push((d.what.trim_start_matches("plugin.").to_string(), id.to_string()));
                }
            }
            _ => {}
        }
    }
    out
}

/// `pkg/util.py` as `pkg.util`; a package's `__init__.py` as the package.
fn dotted(file: &str) -> String {
    let stem = file.strip_suffix(".py").unwrap_or(file);
    let stem = stem.strip_suffix("/__init__").unwrap_or(stem);
    stem.replace('/', ".")
}
