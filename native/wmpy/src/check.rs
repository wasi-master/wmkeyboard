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
//!
//! `--fix` makes the changes the editors' quick fixes make, round after round
//! until none is left: a misspelt name, a missing import, a stub for what
//! plugin.json declares, and what plugin.json is missing (a permission, a
//! host, a provider, an action, a keyword, a setting) in the file's own
//! layout. A risky permission or a new host is a choice the author makes
//! knowingly, so those wait for `--fix-risky`.

use std::collections::{BTreeMap, HashSet};
use std::path::{Path, PathBuf};

use serde_json::{json, Value};
use wm_python_tooling::api_json::host_of_manifest;
use wm_python_tooling::diagnostics::{diagnose, Diagnostic, Fix};
use wm_python_tooling::document::{Document, Host, Verdict};
use wm_python_tooling::keyboard;
use wm_python_tooling::manifest::{self as codec, Role};
use wm_python_tooling::text::Span;

use crate::diag::{color_stderr, fix_help, folder_source, Diag, Severity};
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
    /// Make the fixes the editors offer, then check again
    #[arg(long)]
    pub fix: bool,
    /// With --fix: print the changes as a unified diff and write nothing
    #[arg(long, requires = "fix")]
    pub dry_run: bool,
    /// With --fix: also add the risky permissions and the hosts the code needs
    #[arg(long, requires = "fix")]
    pub fix_risky: bool,
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
        self.print_with(&folder_source(dir));
    }

    fn print_with(&self, source: &dyn Fn(&str) -> Option<String>) {
        let color = color_stderr();
        for d in &self.problems {
            eprint!("{}", d.render(source, color));
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
    if args.fix {
        return fix(&dir, &args);
    }
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

/// Texts that stand in for files of the folder: what `--fix` has changed so far.
type Overlay = BTreeMap<String, String>;

/// A file's text: from [overlay] when it has one, else from the folder.
fn read(dir: &Path, overlay: &Overlay, file: &str) -> Option<String> {
    match overlay.get(file) {
        Some(t) => Some(t.clone()),
        None => std::fs::read_to_string(dir.join(file)).ok(),
    }
}

/// The plugin's own Python files, as the keyboard loads them: the entry
/// script first, then its modules, in order; packages `wmpy pip` vendored,
/// tests and dev files left out. `(file, text, is_entry)`.
pub fn own_sources(dir: &Path) -> Vec<(String, String, bool)> {
    let text = std::fs::read_to_string(dir.join(codec::MANIFEST)).unwrap_or_default();
    let layout = Layout::of(dir, &text);
    let mut out = Vec::new();
    if let Ok(t) = std::fs::read_to_string(dir.join(&layout.entry)) {
        out.push((layout.entry.clone(), t, true));
    }
    if layout.package {
        for f in layout.modules.iter().filter(|f| !layout.is_vendored(f)) {
            if let Ok(t) = std::fs::read_to_string(dir.join(f)) {
                out.push((f.clone(), t, false));
            }
        }
    }
    out
}

/// Which files of the folder are what.
struct Layout {
    report: codec::Report,
    files: Vec<String>,
    entry: String,
    /// Whether the plugin is an API 5 package, whose other `.py` files are modules.
    package: bool,
    modules: Vec<String>,
    vendored: Vec<String>,
}

impl Layout {
    fn of(dir: &Path, manifest: &str) -> Layout {
        let report = codec::check(manifest);
        let files = project::files(dir);
        let entry = report.manifest.as_ref().map(|m| m.entry()).unwrap_or_else(|| codec::DEFAULT_ENTRY.to_string());
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
        let vendored = crate::pip::lock::vendored_roots(dir);
        Layout { report, files, entry, package, modules, vendored }
    }

    fn is_vendored(&self, f: &str) -> bool {
        let top = f.split('/').next().unwrap_or(f);
        let top = top.strip_suffix(".py").unwrap_or(top);
        self.vendored.iter().any(|r| r == top)
    }
}

/// A fix the checks offer: in which file, for which problem, and where.
#[derive(Clone, Debug)]
struct Fixable {
    /// The file whose problem it settles (the change may be to plugin.json).
    file: String,
    code: String,
    /// 1-based, in [file].
    line: u32,
    /// The problem's span, UTF-16, in [file]; what a Replace replaces.
    span: Span,
    fix: Fix,
}

/// Everything wrong with the plugin in [dir] (a folder that holds plugin.json).
pub fn check_dir(dir: &Path) -> Outcome {
    check_with(dir, &Overlay::new()).0
}

/// [check_dir], reading [overlay]'s texts in place of the folder's, and the fixes on offer.
fn check_with(dir: &Path, overlay: &Overlay) -> (Outcome, Vec<Fixable>) {
    let mut problems: Vec<Diag> = Vec::new();
    let mut fixes: Vec<Fixable> = Vec::new();
    let text = read(dir, overlay, codec::MANIFEST).unwrap_or_default();
    let layout = Layout::of(dir, &text);
    let report = &layout.report;
    for p in &report.problems {
        let d = Diag::from_manifest(p, &text);
        // The manifest's own rules that one addition settles.
        let fix = match p.code {
            "actions_need_selection" => Some(Fix::Permission("selection".into())),
            "previews_need_permission" => Some(Fix::Permission("previews".into())),
            _ => None,
        };
        if let Some(fix) = fix {
            fixes.push(Fixable { file: codec::MANIFEST.into(), code: p.code.into(), line: d.line.unwrap_or(1), span: Span::default(), fix });
        }
        problems.push(d);
    }

    let entry = layout.entry.clone();
    if let Some(m) = &report.manifest {
        let packed: Vec<(String, Vec<u8>)> = layout
            .files
            .iter()
            .filter(|f| codec::packed_role(f, m).is_some())
            .filter_map(|f| match overlay.get(f) {
                Some(t) => Some((f.clone(), t.clone().into_bytes())),
                None => std::fs::read(dir.join(f)).ok().map(|b| (f.clone(), b)),
            })
            .collect();
        let refs: Vec<(&str, &[u8])> = packed.iter().map(|(p, b)| (p.as_str(), b.as_slice())).collect();
        problems.extend(codec::archive_problems(m, &refs).iter().map(|p| Diag::from_manifest(p, &text)));
    } else if !dir.join(&entry).is_file() {
        let mut d = Diag::error(format!("there is no {entry} beside plugin.json"));
        d.file = Some(entry.clone());
        problems.push(d);
    }

    // The Python files: the entry, then the modules the archive takes.
    let package = layout.package;
    let manifest_json: serde_json::Value = serde_json::from_str(&text).unwrap_or(serde_json::Value::Null);
    let mut host = if manifest_json.is_object() { host_of_manifest(&manifest_json) } else { Host::default() };
    if package {
        host.modules = Some(layout.modules.iter().map(|f| dotted(f)).collect());
    }

    let mut keep = |d: &Diagnostic, file: &str, source: &str, problems: &mut Vec<Diag>| {
        let diag = Diag::from_tooling(d, file, source);
        if let Some(fix) = &d.fix {
            fixes.push(Fixable { file: file.to_string(), code: d.code.name().to_string(), line: diag.line.unwrap_or(1), span: d.span, fix: fix.clone() });
        }
        problems.push(diag);
    };
    let mut python_files = 0;
    let mut answered: HashSet<(String, String)> = HashSet::new();
    let mut entry_found: Vec<Diagnostic> = Vec::new();
    let mut entry_source = String::new();
    let mut later: Vec<(Diagnostic, String, String)> = Vec::new();
    if let Some(source) = read(dir, overlay, &entry) {
        python_files += 1;
        entry_found = diagnostics(&source, &host);
        entry_source = source;
    }
    if package {
        for file in &layout.modules {
            let Some(source) = read(dir, overlay, file) else { continue };
            python_files += 1;
            if layout.is_vendored(file) {
                let found = diagnostics(&source, &Host::default());
                later.extend(found.into_iter().filter(|d| SYNTAX_CODES.contains(&d.code.name())).map(|d| (d, file.clone(), source.clone())));
                continue;
            }
            let doc = Document::new(&source);
            answered.extend(answers(&doc));
            let module_host = Host { panel: Some(false), ..host.clone() };
            let found = diagnostics(&source, &module_host);
            later.extend(found.into_iter().filter(|d| !ANSWERED_CODES.contains(&d.code.name())).map(|d| (d, file.clone(), source.clone())));
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
        keep(d, &entry, &entry_source, &mut problems);
    }
    for (d, file, source) in &later {
        keep(d, file, source, &mut problems);
    }

    // Tests run in the test runner, not in the keyboard: only whether they read.
    for file in project::test_files(dir) {
        let Some(source) = read(dir, overlay, &file) else { continue };
        python_files += 1;
        let found = diagnostics(&source, &Host::default());
        problems.extend(found.iter().filter(|d| SYNTAX_CODES.contains(&d.code.name())).map(|d| Diag::from_tooling(d, &file, &source)));
    }

    (Outcome { problems, python_files }, fixes)
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

// ---- --fix --------------------------------------------------------------------

/// Rounds of fixing before giving up: a fix that makes another possible is
/// made in the next round.
const MAX_ROUNDS: usize = 5;

/// What [fix] does, in words.
fn describe(fix: &Fix) -> String {
    match fix {
        // `, http` at the end of a `from wm import` line.
        Fix::Insert(_, t) if t.starts_with(", ") => format!("add “{}” to the import", t.trim_start_matches(", ").trim()),
        other => fix_help(other),
    }
}

/// A change made.
struct Made {
    round: usize,
    /// The file changed.
    file: String,
    /// The problem it settles, `main.py:3`.
    problem: String,
    code: String,
    what: String,
}

/// A fix left for the author.
struct Left {
    problem: String,
    code: String,
    what: String,
    why: String,
}

/// Byte offset of UTF-16 offset [at] in [text].
fn byte_of(text: &str, at: u32) -> usize {
    let mut units = 0u32;
    for (i, c) in text.char_indices() {
        if units >= at {
            return i;
        }
        units += c.len_utf16() as u32;
    }
    text.len()
}

/// [text] with each `(start, end, new)` byte edit made; none may overlap.
fn apply_edits(text: &str, mut edits: Vec<(usize, usize, String)>) -> String {
    edits.sort_by_key(|e| std::cmp::Reverse((e.0, e.1)));
    let mut out = text.to_string();
    for (start, end, new) in edits {
        out.replace_range(start..end, &new);
    }
    out
}

/// Why [fix] waits for the author, when it does.
fn held_back(fix: &Fix, manifest: &str, risky_ok: bool) -> Option<String> {
    match fix {
        Fix::Permission(p) if !risky_ok && codec::permission(p).is_some_and(|x| x.risky) => {
            Some(format!("“{p}” is a risky permission: users turn it on after a warning, so add it knowingly (or run with --fix-risky)"))
        }
        Fix::Host(h) if !risky_ok => Some(format!("a new host is a choice users see before they install: add “{h}” knowingly (or run with --fix-risky)")),
        Fix::Keyword(k) => {
            let v: Value = serde_json::from_str(manifest).unwrap_or(Value::Null);
            let current = v.pointer("/tool/keyword").and_then(Value::as_str).unwrap_or("").trim().to_string();
            (!current.is_empty() && current != *k).then(|| format!("tool.keyword is “{current}”; change it to “{k}” yourself if this one is meant"))
        }
        _ => None,
    }
}

fn fix(dir: &Path, args: &Args) -> u8 {
    let color = color_stderr();
    let mut overlay = Overlay::new();
    let mut made: Vec<Made> = Vec::new();
    let mut left: Vec<Left> = Vec::new();
    let mut seen: HashSet<Overlay> = HashSet::new();
    for round in 0..MAX_ROUNDS {
        let (_, fixes) = check_with(dir, &overlay);
        let manifest = read(dir, &overlay, codec::MANIFEST).unwrap_or_default();
        let mut text_edits: BTreeMap<String, Vec<(usize, usize, String, &Fixable)>> = BTreeMap::new();
        let mut manifest_fixes: Vec<&Fixable> = Vec::new();
        for f in &fixes {
            let problem = format!("{}:{}", f.file, f.line);
            if let Some(why) = held_back(&f.fix, &manifest, args.fix_risky) {
                if !left.iter().any(|l| l.what == describe(&f.fix)) {
                    left.push(Left { problem, code: f.code.clone(), what: describe(&f.fix), why });
                }
                continue;
            }
            match &f.fix {
                Fix::Replace(new) => {
                    let Some(source) = read(dir, &overlay, &f.file) else { continue };
                    let (s, e) = (byte_of(&source, f.span.start), byte_of(&source, f.span.end));
                    text_edits.entry(f.file.clone()).or_default().push((s, e, new.clone(), f));
                }
                Fix::Insert(at, new) => {
                    let Some(source) = read(dir, &overlay, &f.file) else { continue };
                    let s = byte_of(&source, *at);
                    text_edits.entry(f.file.clone()).or_default().push((s, s, new.clone(), f));
                }
                _ => manifest_fixes.push(f),
            }
        }
        let mut changed = false;
        for (file, mut edits) in text_edits {
            let Some(source) = read(dir, &overlay, &file) else { continue };
            edits.sort_by(|x, y| (x.0, x.1, &x.2).cmp(&(y.0, y.1, &y.2)));
            edits.dedup_by(|x, y| (x.0, x.1, &x.2) == (y.0, y.1, &y.2));
            // Two edits that touch the same text, or start at one place, wait a round each.
            let mut taken: Vec<(usize, usize, String, &Fixable)> = Vec::new();
            for e in edits {
                let clash = taken.last().is_some_and(|t| e.0 < t.1 || e.0 == t.0);
                let no_op = source.get(e.0..e.1) == Some(e.2.as_str());
                if !clash && !no_op {
                    taken.push(e);
                }
            }
            if taken.is_empty() {
                continue;
            }
            for t in &taken {
                made.push(Made { round, file: file.clone(), problem: format!("{}:{}", t.3.file, t.3.line), code: t.3.code.clone(), what: describe(&t.3.fix) });
            }
            let new = apply_edits(&source, taken.into_iter().map(|t| (t.0, t.1, t.2)).collect());
            if new != source {
                overlay.insert(file, new);
                changed = true;
            }
        }
        let mut text = manifest.clone();
        for f in manifest_fixes {
            let Some(edits) = crate::lsp::manifest::fix_edits(&text, &f.fix) else { continue };
            let edits: Vec<(usize, usize, String)> = edits.into_iter().map(|e| (e.start, e.end, e.text)).collect();
            let next = apply_edits(&text, edits);
            if next != text {
                made.push(Made { round, file: codec::MANIFEST.into(), problem: format!("{}:{}", f.file, f.line), code: f.code.clone(), what: describe(&f.fix) });
                text = next;
            }
        }
        if text != manifest {
            overlay.insert(codec::MANIFEST.into(), text);
            changed = true;
        }
        if !changed || !seen.insert(overlay.clone()) {
            break;
        }
    }

    // Only files whose text ends up different count as changed.
    let originals: BTreeMap<String, String> = overlay.keys().filter_map(|f| std::fs::read_to_string(dir.join(f)).ok().map(|t| (f.clone(), t))).collect();
    overlay.retain(|f, t| originals.get(f) != Some(t));
    made.retain(|m| overlay.contains_key(&m.file));
    made.sort_by_key(|m| (m.round, m.problem.split(':').nth(1).and_then(|l| l.parse::<u32>().ok()).unwrap_or(0)));
    let (outcome, _) = check_with(dir, &overlay);
    let failed = outcome.errors() > 0 || (args.strict && outcome.warnings() > 0);

    if args.dry_run {
        let diffs: Vec<(String, String)> =
            overlay.iter().map(|(f, t)| (f.clone(), crate::fmt::unified_diff(originals.get(f).map_or("", String::as_str), t, f))).collect();
        if args.json {
            let mut v = outcome.json();
            v["fixed"] = made_json(&made);
            v["left"] = left_json(&left);
            v["diffs"] = diffs.iter().map(|(f, d)| json!({"file": f, "diff": d})).collect();
            v["written"] = json!(false);
            println!("{v}");
        } else {
            for (_, d) in &diffs {
                print!("{d}");
            }
            eprintln!("{}", would_line(&made, overlay.len(), true));
            print_left(&left, color);
        }
        return u8::from(failed);
    }

    let mut write_failed = false;
    for (f, t) in &overlay {
        if let Err(e) = std::fs::write(dir.join(f), t) {
            eprintln!("cannot write {f}: {e}");
            write_failed = true;
        }
    }
    if args.json {
        let mut v = outcome.json();
        v["fixed"] = made_json(&made);
        v["left"] = left_json(&left);
        v["written"] = json!(!write_failed);
        println!("{v}");
    } else {
        let paint = |code: &str, text: &str| if color { format!("\x1b[{code}m{text}\x1b[0m") } else { text.to_string() };
        for file in overlay.keys() {
            let here: Vec<&Made> = made.iter().filter(|m| m.file == *file).collect();
            eprintln!("{} {file}: {}", paint("1;32", "fixed"), if here.len() == 1 { "1 change".to_string() } else { format!("{} changes", here.len()) });
            for m in here {
                eprintln!("  {}  {} [{}]", m.problem, m.what, m.code);
            }
        }
        eprintln!("{}", would_line(&made, overlay.len(), false));
        print_left(&left, color);
        eprintln!();
        outcome.print(dir);
    }
    if write_failed {
        2
    } else {
        u8::from(failed)
    }
}

fn would_line(made: &[Made], files: usize, dry: bool) -> String {
    let n = made.len();
    let fixes = if n == 1 { "1 fix".to_string() } else { format!("{n} fixes") };
    let in_files = if files == 1 { "1 file".to_string() } else { format!("{files} files") };
    match (n, dry) {
        (0, _) => "nothing to fix".into(),
        (_, true) => format!("would make {fixes} in {in_files}"),
        (_, false) => format!("made {fixes} in {in_files}"),
    }
}

fn print_left(left: &[Left], color: bool) {
    for l in left {
        let mut d = Diag::new(Severity::Info, format!("not fixed: {}", l.what));
        d.code = Some(l.code.clone());
        d.notes.push(format!("for {}", l.problem));
        d.help = Some(l.why.clone());
        eprint!("{}", d.render(&|_| None, color));
    }
}

fn made_json(made: &[Made]) -> Value {
    made.iter().map(|m| json!({"file": m.file, "problem": m.problem, "code": m.code, "fix": m.what})).collect()
}

fn left_json(left: &[Left]) -> Value {
    left.iter().map(|l| json!({"problem": l.problem, "code": l.code, "fix": l.what, "why": l.why})).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf16_offsets_to_bytes() {
        let text = "a = “x”\nb";
        assert_eq!(byte_of(text, 4), 4);
        assert_eq!(byte_of(text, 5), 7, "after the 3-byte quote");
        assert_eq!(byte_of(text, 100), text.len());
    }

    #[test]
    fn edits_apply_from_the_end() {
        let out = apply_edits("import x\nprint(cunt)\n", vec![(0, 0, "import math\n".into()), (15, 19, "count".into())]);
        assert_eq!(out, "import math\nimport x\nprint(count)\n");
    }

    #[test]
    fn risky_permissions_and_hosts_wait() {
        let m = "{\"tool\": {\"keyword\": \"look\"}}";
        assert!(held_back(&Fix::Permission("network".into()), m, false).is_some());
        assert!(held_back(&Fix::Permission("network".into()), m, true).is_none());
        assert!(held_back(&Fix::Permission("storage".into()), m, false).is_none());
        assert!(held_back(&Fix::Host("api.example.com".into()), m, false).is_some());
        assert!(held_back(&Fix::Keyword("define".into()), m, true).is_some(), "an existing keyword is not replaced");
        assert!(held_back(&Fix::Keyword("look".into()), m, false).is_none());
        assert!(held_back(&Fix::Keyword("define".into()), "{}", false).is_none());
    }
}
