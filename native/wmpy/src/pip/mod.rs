//! `wmpy pip`: pure-Python packages from PyPI, vendored into a plugin.
//!
//! ```text
//! wmpy pip install <spec>... [-r requirements.txt]... [--dir .] [--no-deps] [--force] [--allow-warnings] [--json]
//! wmpy pip check <spec|folder|wheel>... [--no-deps] [--json]
//! wmpy pip list [--dir .] [--json]
//! wmpy pip remove <name> [--dir .] [--force] [--json]
//! wmpy pip why <name> [--dir .] [--json]
//! wmpy pip update [name] [--latest] [--dir .] [--force] [--allow-warnings] [--json]
//! wmpy pip verify [--dir .] [--json]
//! wmpy pip sync [--dir .] [--all] [--json]
//! ```
//!
//! What was asked for is kept in `wmpy.lock` as typed, and `update` stays
//! inside it (`--latest` leaves it behind and records the bare name);
//! `install` with a new spec replaces it. `verify` compares the folder with
//! the SHA-256 the lock keeps of each file, and `sync` puts back what is
//! missing or changed, at the locked versions, from PyPI.
//!
//! A package goes in as its importable `.py` files at the top of the plugin
//! folder (`humanize/__init__.py`, `six.py`), the way the keyboard loads a
//! plugin's own modules, with its licence files under `licenses/<name>/` and
//! a line in `wmpy.lock` ([lock]). Each one is analysed first
//! ([wm_python_pip::analyse]): an incompatible package is refused unless
//! `--force`, one with warnings unless `--allow-warnings`, and a package
//! whose dependency was refused is refused with it. Only pure wheels
//! (`py3-none-any`) are used, or an sdist's `.py` files when nothing in it
//! needs building, or a compiled wheel's `.py` files when there is neither.
//! Requirements and markers are read as CPython on Linux would read them, at
//! the version the keyboard's Python reports ([wm_python_pip::python_version]);
//! extras only when asked for as `pkg[extra]`.
//!
//! requests and httpx are built into the keyboard ([wm_python_pip::is_built_in]):
//! asking for one, directly or as a dependency, installs nothing and puts
//! nothing in `wmpy.lock`, and says so.

mod dist;
mod index;
mod install;
pub mod lock;
mod marker;
mod version;

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::{Path, PathBuf};

use serde_json::{json, Value as Json};
use wm_python_pip::{limits, Severity as PSeverity, Verdict};

use crate::diag::{color_stderr, Diag, Severity};
use dist::Dist;
use index::Index;
use install::{Pick, Resolver, Source};
use lock::{Entry, Lock};
use marker::Requirement;
use version::normalize;

/// Problems printed per package before the rest are counted.
const SHOWN: usize = 6;

#[derive(clap::Args, Debug)]
pub struct Args {
    #[command(subcommand)]
    pub command: Command,
}

#[derive(clap::Subcommand, Debug)]
pub enum Command {
    /// Add packages from PyPI to the plugin
    Install(InstallArgs),
    /// Download and analyse packages without adding them
    Check(CheckArgs),
    /// List the packages the plugin vendored
    List(DirArgs),
    /// Take a package out of the plugin
    Remove(RemoveArgs),
    /// Say why a package is in the plugin
    Why(WhyArgs),
    /// Move packages to their newest releases, inside what was asked for
    Update(UpdateArgs),
    /// Check that the vendored files are as wmpy wrote them
    Verify(DirArgs),
    /// Put back vendored files that are missing or changed, at the locked versions
    Sync(SyncArgs),
}

#[derive(clap::Args, Debug)]
pub struct InstallArgs {
    /// Packages, as pip takes them: humanize, "python-dateutil>=2.8", "attrs[tests]"
    #[arg(required_unless_present = "requirements")]
    pub specs: Vec<String>,
    /// Also the packages listed in this file, one a line, as pip's requirements.txt (repeatable)
    #[arg(short = 'r', long = "requirement", value_name = "FILE")]
    pub requirements: Vec<PathBuf>,
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Leave out the packages these need
    #[arg(long)]
    pub no_deps: bool,
    /// Install even what the analysis finds incompatible, and fetch again what is installed
    #[arg(long)]
    pub force: bool,
    /// Install packages whose analysis found warnings
    #[arg(long)]
    pub allow_warnings: bool,
    /// Print the result as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct CheckArgs {
    /// Packages from PyPI, or a .whl, an sdist or a folder of Python files
    #[arg(required = true)]
    pub targets: Vec<String>,
    /// Check only the packages named, not what they need
    #[arg(long)]
    pub no_deps: bool,
    /// Print {"packages": [{"name", "version", "verdict", "problems"}]} on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct DirArgs {
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Print the result as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct RemoveArgs {
    /// The package to take out
    pub name: String,
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Remove it even though another package needs it
    #[arg(long)]
    pub force: bool,
    /// Print what was removed as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct WhyArgs {
    /// The package to explain
    pub name: String,
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Print the chain as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct SyncArgs {
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Fetch every locked package again, not only those with missing or changed files
    #[arg(long)]
    pub all: bool,
    /// Print the result as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct UpdateArgs {
    /// Only this package (and what it newly needs); every package when left out
    pub name: Option<String>,
    /// The newest release even outside what was asked for (`>=2.8,<3`), which then becomes the bare name
    #[arg(long)]
    pub latest: bool,
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
    /// Install even what the analysis finds incompatible
    #[arg(long)]
    pub force: bool,
    /// Install packages whose analysis found warnings
    #[arg(long)]
    pub allow_warnings: bool,
    /// Print the result as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

pub fn run(args: Args) -> u8 {
    match args.command {
        Command::Install(a) => run_install(a),
        Command::Check(a) => run_check(a),
        Command::List(a) => run_list(a),
        Command::Remove(a) => run_remove(a),
        Command::Why(a) => run_why(a),
        Command::Update(a) => run_update(a),
        Command::Verify(a) => run_verify(a),
        Command::Sync(a) => run_sync(a),
    }
}

/// The cache folder wmpy keeps downloads and answers in.
pub fn index_cache_dir() -> Option<PathBuf> {
    index::cache_dir()
}

// ---- printing ---------------------------------------------------------------

fn fail(message: impl Into<String>, help: Option<String>, code: u8) -> u8 {
    let mut d = Diag::error(message);
    d.help = help;
    eprint!("{}", d.render(&|_| None, color_stderr()));
    code
}

fn to_diag(p: &wm_python_pip::Problem) -> Diag {
    let severity = match p.severity {
        PSeverity::Error => Severity::Error,
        PSeverity::Warning => Severity::Warning,
        PSeverity::Info => Severity::Info,
    };
    let mut d = Diag::new(severity, p.message.clone());
    d.code = Some(p.code.to_string());
    d.file = Some(p.file.clone());
    d.line = p.line;
    d.col = p.col;
    d.end_col = p.end_col;
    d.help = p.help.clone();
    d
}

fn size(n: usize) -> String {
    if n < 1024 {
        format!("{n} B")
    } else if n < 1024 * 1024 {
        format!("{} KB", n.div_ceil(1024))
    } else {
        format!("{:.1} MB", n as f64 / (1024.0 * 1024.0))
    }
}

fn table(rows: &[Vec<String>]) -> String {
    let cols = rows.iter().map(Vec::len).max().unwrap_or(0);
    let widths: Vec<usize> = (0..cols).map(|c| rows.iter().map(|r| r.get(c).map_or(0, |s| s.chars().count())).max().unwrap_or(0)).collect();
    let mut out = String::new();
    for r in rows {
        let line: Vec<String> = r.iter().enumerate().map(|(i, s)| if i + 1 == r.len() { s.clone() } else { format!("{s:<w$}", w = widths[i]) }).collect();
        out.push_str(line.join("  ").trim_end());
        out.push('\n');
    }
    out
}

/// The problems worth printing for a package: errors and warnings, the first few.
fn print_problems(name: &str, report: &wm_python_pip::Report, dist: &Dist) {
    let source = |f: &str| dist.modules.get(f).cloned();
    let color = color_stderr();
    let serious: Vec<&wm_python_pip::Problem> = report.problems().filter(|p| p.severity != PSeverity::Info).collect();
    for p in serious.iter().take(SHOWN) {
        eprint!("{}", to_diag(p).render(&source, color));
    }
    if serious.len() > SHOWN {
        eprintln!("... and {} more in {name}: `wmpy pip check {name}` lists them all\n", serious.len() - SHOWN);
    } else if !serious.is_empty() {
        eprintln!();
    }
}

/// How full the plugin is against the keyboard's caps, and warnings past them.
fn caps(dir: &Path) -> (usize, usize, Vec<String>) {
    let Ok(text) = std::fs::read_to_string(dir.join("plugin.json")) else { return (0, 0, Vec::new()) };
    let Some(m) = wm_python_tooling::manifest::check(&text).manifest else { return (0, 0, Vec::new()) };
    let (mut entries, mut total, mut warnings) = (0, 0, Vec::new());
    for f in crate::project::files(dir) {
        // Licence files ship in the archive too, and count against its caps.
        if wm_python_tooling::manifest::packed_role(&f, &m).is_none() && !f.starts_with("licenses/") {
            continue;
        }
        let len = std::fs::metadata(dir.join(&f)).map(|md| md.len() as usize).unwrap_or(0);
        entries += 1;
        total += len;
        if f.ends_with(".py") && len > limits::MAX_SCRIPT_BYTES {
            warnings.push(format!("{f} is {}: the keyboard refuses a .py file over {} KB, and shaking cannot cut it if it is used", size(len), limits::MAX_SCRIPT_BYTES / 1024));
        }
    }
    let shake = "`wmpy pack` shakes the vendored packages and may bring it under; `wmpy pack --shake-symbols` cuts deeper";
    if entries > limits::MAX_ENTRIES {
        warnings.push(format!("the plugin now has {entries} files for its .wmplugin, and the keyboard takes {} at most: {shake}", limits::MAX_ENTRIES));
    }
    if total > limits::MAX_TOTAL_BYTES {
        warnings.push(format!("the plugin's files come to {}, over the keyboard's {} MB: {shake}", size(total), limits::MAX_TOTAL_BYTES >> 20));
    }
    (entries, total, warnings)
}

fn warn(line: &str) {
    let mut d = Diag::new(Severity::Warning, line);
    d.code = Some("plugin-size".into());
    eprint!("{}", d.render(&|_| None, color_stderr()));
}

fn parse_specs(specs: &[String]) -> Result<Vec<Requirement>, String> {
    specs.iter().map(|s| Requirement::parse(s).map_err(|e| format!("cannot read '{s}': {e}"))).collect()
}

// ---- install and update -----------------------------------------------------

#[derive(Clone, Copy, PartialEq, Eq)]
enum Action {
    Installed,
    Kept,
    Refused,
}

impl Action {
    fn word(self) -> &'static str {
        match self {
            Action::Installed => "installed",
            Action::Kept => "kept",
            Action::Refused => "refused",
        }
    }
}

struct Outcome {
    action: Action,
    report: Option<wm_python_pip::Report>,
    reason: Option<String>,
}

fn verdict_of(pick: &Pick, outcome: &Outcome) -> String {
    match (&pick.source, &outcome.report) {
        (Source::New(d), _) if d.refused.is_some() && d.modules.is_empty() => "incompatible".into(),
        (_, Some(r)) => r.verdict.name().into(),
        (Source::Locked(e), None) => e.verdict.clone(),
        _ => "incompatible".into(),
    }
}

/// Analyses the new picks, decides, writes what is accepted and the lock,
/// and prints the result. The shared second half of install and update.
fn apply(dir: &Path, mut lock: Lock, picks: BTreeMap<String, Pick>, notes: Vec<String>, requested: &BTreeMap<String, Option<String>>, force: bool, allow_warnings: bool, json_out: bool) -> u8 {
    let own = install::own_roots(dir, &lock);
    // What everything but the package itself puts in the plugin.
    let beside = |key: &str| -> Vec<String> {
        let mut v: Vec<String> = picks.iter().filter(|(k, _)| k.as_str() != key).flat_map(|(_, p)| p.roots()).collect();
        v.extend(lock.packages.iter().filter(|(k, _)| k.as_str() != key).flat_map(|(_, e)| e.roots.iter().cloned()));
        v.extend(own.iter().cloned());
        v
    };
    let mut outcomes: BTreeMap<String, Outcome> = BTreeMap::new();
    for (key, pick) in &picks {
        let outcome = match &pick.source {
            Source::Locked(_) => Outcome { action: Action::Kept, report: None, reason: None },
            Source::New(d) => {
                let report = (!d.modules.is_empty() || !d.native.is_empty()).then(|| install::analyse(d, &beside(key)));
                let clash = d.roots.iter().find(|r| own.contains(*r)).cloned();
                let (action, reason) = if let Some(r) = clash {
                    (Action::Refused, Some(format!("the plugin has its own '{r}', which this would overwrite")))
                } else if let (Some(why), false) = (&d.refused, force && !d.modules.is_empty()) {
                    (Action::Refused, Some(format!("{why}{}", if d.modules.is_empty() { "" } else { " (--force copies its .py files anyway)" })))
                } else {
                    match report.as_ref().map(|r| r.verdict) {
                        None => (Action::Refused, Some("it has no importable .py files".into())),
                        Some(Verdict::Incompatible) if !force => (Action::Refused, Some("the analysis found errors (--force installs it anyway)".into())),
                        Some(Verdict::Warnings) if !(force || allow_warnings) => (Action::Refused, Some("the analysis found warnings (--allow-warnings installs it anyway)".into())),
                        _ => (Action::Installed, None),
                    }
                };
                Outcome { action, report, reason }
            }
        };
        outcomes.insert(key.clone(), outcome);
    }
    // A package whose dependency stays out stays out too.
    if !force {
        loop {
            let refused: HashSet<String> = outcomes.iter().filter(|(_, o)| o.action == Action::Refused).map(|(k, _)| k.clone()).collect();
            let mut changed = false;
            for (key, pick) in &picks {
                let o = outcomes.get_mut(key).expect("outcome");
                if o.action == Action::Installed {
                    if let Some(dep) = pick.requires.iter().find(|d| refused.contains(*d)) {
                        o.action = Action::Refused;
                        o.reason = Some(format!("it needs {dep}, which was refused"));
                        changed = true;
                    }
                }
            }
            if !changed {
                break;
            }
        }
    }
    // Write.
    let mut written_any = false;
    for (key, pick) in &picks {
        let o = &outcomes[key];
        let Source::New(d) = &pick.source else { continue };
        if o.action != Action::Installed {
            continue;
        }
        let keep: BTreeSet<String> = lock.packages.iter().filter(|(k, _)| *k != key).flat_map(|(_, e)| e.files.iter().cloned()).collect();
        let files = match install::write(dir, d, lock.packages.get(key), &keep) {
            Ok(f) => f,
            Err(e) => return fail(format!("could not write {}: {e}", pick.name), None, 2),
        };
        let report = o.report.as_ref();
        // What was asked for: this run's, else what the lock kept.
        let asked = match requested.get(key) {
            Some(r) => r.clone(),
            None => lock.packages.get(key).and_then(|e| e.requested.clone()),
        };
        let hashes = files.iter().filter_map(|f| std::fs::read(dir.join(f)).ok().map(|b| (f.clone(), index::sha256_hex(&b)))).collect();
        lock.packages.insert(
            key.clone(),
            Entry {
                version: pick.version.clone(),
                roots: d.roots.clone(),
                files,
                source: d.source.clone(),
                sha256: d.sha256.clone(),
                license: d.license.clone(),
                requires: pick.requires.iter().filter(|r| picks.contains_key(*r) || lock.packages.contains_key(*r)).cloned().collect(),
                verdict: report.map_or("incompatible".into(), |r| r.verdict.name().to_string()),
                problems: report.map_or(0, |r| r.problem_count() as u64),
                requested: asked,
                hashes,
            },
        );
        written_any = true;
    }
    // A package already locked that this run asked for by a new spec keeps it.
    for (key, r) in requested {
        if let (Some(e), Some(Source::Locked(_))) = (lock.packages.get_mut(key), picks.get(key).map(|p| &p.source)) {
            if e.requested != *r {
                e.requested = r.clone();
                written_any = true;
            }
        }
    }
    if written_any {
        if let Err(e) = lock.write(dir) {
            return fail(format!("could not write wmpy.lock: {e}"), None, 2);
        }
    }
    let (entries, total, cap_warnings) = caps(dir);
    let any_refused = outcomes.values().any(|o| o.action == Action::Refused);
    if json_out {
        let packages: Vec<Json> = picks
            .iter()
            .map(|(key, p)| {
                let o = &outcomes[key];
                let problems: Vec<Json> = o.report.as_ref().map(|r| r.problems().map(|x| to_diag(x).to_json()).collect()).unwrap_or_default();
                let notes = match &p.source {
                    Source::New(d) => d.notes.clone(),
                    Source::Locked(_) => Vec::new(),
                };
                json!({
                    "name": key, "version": p.version, "previous": p.previous, "action": o.action.word(),
                    "verdict": verdict_of(p, o), "problems": problems, "reason": o.reason, "notes": notes,
                    "roots": p.roots(), "requiredBy": p.required_by,
                })
            })
            .collect();
        let out = json!({
            "packages": packages, "notes": notes,
            "limits": {"entries": entries, "maxEntries": limits::MAX_ENTRIES, "bytes": total, "maxBytes": limits::MAX_TOTAL_BYTES},
            "warnings": cap_warnings,
        });
        println!("{}", serde_json::to_string_pretty(&out).expect("json"));
        return u8::from(any_refused);
    }
    for (key, pick) in &picks {
        if let (Source::New(d), Some(r)) = (&pick.source, &outcomes[key].report) {
            if outcomes[key].action != Action::Kept {
                print_problems(key, r, d);
            }
        }
    }
    let mut rows = vec![vec!["package".into(), "version".into(), "verdict".into(), "problems".into(), "files".into(), "size".into(), "licence".into(), "".into()]];
    for (key, p) in &picks {
        let o = &outcomes[key];
        let version = match &p.previous {
            Some(prev) if o.action == Action::Installed => format!("{prev} -> {}", p.version),
            _ => p.version.clone(),
        };
        let (problems, files, bytes, licence) = match &p.source {
            Source::New(d) => (
                o.report.as_ref().map_or("-".into(), |r| r.problem_count().to_string()),
                (d.modules.len() + d.licenses.len()).to_string(),
                size(d.bytes()),
                d.license.clone().unwrap_or_else(|| "none found".into()),
            ),
            Source::Locked(e) => (e.problems.to_string(), e.files.len().to_string(), "-".into(), e.license.clone().unwrap_or_else(|| "none found".into())),
        };
        let what = match (o.action, &o.reason) {
            (Action::Refused, Some(r)) => format!("refused: {r}"),
            (a, _) if !p.required_by.is_empty() && a != Action::Refused => format!("{} (for {})", a.word(), p.required_by.join(", ")),
            (a, _) => a.word().to_string(),
        };
        rows.push(vec![key.clone(), version, verdict_of(p, o), problems, files, bytes, licence, what]);
    }
    print!("{}", table(&rows));
    for (key, p) in &picks {
        if let Source::New(d) = &p.source {
            if outcomes[key].action == Action::Installed {
                for n in &d.notes {
                    println!("  {key}: {n}");
                }
                if d.license.is_none() && d.licenses.is_empty() {
                    warn(&format!("{key} names no licence: check that you may copy it before you share the plugin"));
                }
            }
        }
    }
    for n in &notes {
        println!("  {n}");
    }
    if written_any {
        println!("wrote wmpy.lock; the plugin has {entries} of {} files and {} of {} MB for the keyboard", limits::MAX_ENTRIES, size(total), limits::MAX_TOTAL_BYTES >> 20);
    }
    for w in &cap_warnings {
        warn(w);
    }
    u8::from(any_refused)
}

fn run_install(args: InstallArgs) -> u8 {
    let dir = match crate::project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            eprint!("{}", d.render(&|_| None, color_stderr()));
            return 2;
        }
    };
    let mut specs = args.specs.clone();
    for file in &args.requirements {
        match requirements_file(file) {
            Ok(mut more) => specs.append(&mut more),
            Err(e) => return fail(e, None, 2),
        }
    }
    let requested = match parse_specs(&specs) {
        Ok(r) => r,
        Err(e) => return fail(e, Some("give packages as pip takes them: humanize, \"tabulate>=0.9\", \"attrs[tests]\"".into()), 2),
    };
    // Each package as asked for, kept in the lock for `update`.
    let asked: BTreeMap<String, Option<String>> = requested.iter().zip(&specs).map(|(r, s)| (r.key(), Some(s.trim().to_string()))).collect();
    let lock = match Lock::read(&dir) {
        Ok(l) => l,
        Err(e) => return fail(e, None, 2),
    };
    let index = Index::new();
    let fresh: HashSet<String> = if args.force { requested.iter().map(Requirement::key).collect() } else { HashSet::new() };
    let mut r = Resolver { index: &index, lock: &lock, fresh, all_fresh: false, no_deps: args.no_deps, picks: BTreeMap::new(), notes: Vec::new(), quiet: args.json };
    if let Err(e) = r.resolve(requested) {
        return fail(e, None, 2);
    }
    let (picks, notes) = (std::mem::take(&mut r.picks), std::mem::take(&mut r.notes));
    if picks.is_empty() {
        // Everything asked for is built in (`wmpy pip install requests`).
        return print_notes_only(&notes, args.json);
    }
    apply(&dir, lock, picks, notes, &asked, args.force, args.allow_warnings, args.json)
}

/// The requirement lines of a requirements.txt: comments, blank lines and
/// `\` continuations as pip reads them; option lines (`-e`, `--index-url`)
/// are refused, as wmpy installs from PyPI only.
fn requirements_file(path: &Path) -> Result<Vec<String>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("{}: {e}", path.display()))?;
    let joined = text.replace("\\\r\n", " ").replace("\\\n", " ");
    let mut out = Vec::new();
    for (i, raw) in joined.lines().enumerate() {
        // A comment starts at a # at the line's start or after a space.
        let line = match raw.find(" #") {
            Some(at) => &raw[..at],
            None if raw.trim_start().starts_with('#') => "",
            None => raw,
        };
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if line.starts_with('-') {
            return Err(format!("{}:{}: “{line}”: wmpy reads package lines only, and installs from PyPI", path.display(), i + 1));
        }
        out.push(line.to_string());
    }
    Ok(out)
}

/// The result when nothing was installed or checked: only notes, such as
/// that a package is built into the keyboard.
fn print_notes_only(notes: &[String], json_out: bool) -> u8 {
    if json_out {
        println!("{}", serde_json::to_string_pretty(&json!({"packages": [], "notes": notes})).expect("json"));
    } else {
        for n in notes {
            println!("{n}");
        }
    }
    0
}

fn run_update(args: UpdateArgs) -> u8 {
    let dir = match crate::project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            eprint!("{}", d.render(&|_| None, color_stderr()));
            return 2;
        }
    };
    let lock = match Lock::read(&dir) {
        Ok(l) => l,
        Err(e) => return fail(e, None, 2),
    };
    if lock.packages.is_empty() {
        println!("nothing to update: the plugin has no wmpy.lock");
        return 0;
    }
    // Each package as it was asked for, unless --latest.
    let spec_of = |key: &str| -> String {
        match (args.latest, lock.packages.get(key).and_then(|e| e.requested.clone())) {
            (false, Some(r)) => r,
            _ => key.to_string(),
        }
    };
    let (requested, fresh, all_fresh): (Vec<String>, HashSet<String>, bool) = match &args.name {
        Some(name) => {
            let key = normalize(name);
            if !lock.packages.contains_key(&key) {
                return fail(format!("{name} is not installed in this plugin"), Some(format!("wmpy pip install {name}")), 1);
            }
            (vec![spec_of(&key)], [key].into_iter().collect(), false)
        }
        // What was asked for by name; the rest follows from it.
        None => (lock.direct().iter().map(|k| spec_of(k)).collect(), HashSet::new(), true),
    };
    // --latest: what was asked for becomes the bare name.
    let asked: BTreeMap<String, Option<String>> = if args.latest {
        lock.packages.iter().filter(|(k, e)| e.requested.is_some() && args.name.as_deref().is_none_or(|n| normalize(n) == **k)).map(|(k, _)| (k.clone(), Some(k.clone()))).collect()
    } else {
        BTreeMap::new()
    };
    let reqs = match parse_specs(&requested) {
        Ok(r) => r,
        Err(e) => return fail(e, None, 2),
    };
    let index = Index::new();
    let mut r = Resolver { index: &index, lock: &lock, fresh, all_fresh, no_deps: false, picks: BTreeMap::new(), notes: Vec::new(), quiet: args.json };
    if let Err(e) = r.resolve(reqs) {
        return fail(e, None, 2);
    }
    let (picks, mut notes) = (std::mem::take(&mut r.picks), std::mem::take(&mut r.notes));
    // A package fetched again at the version already installed changes nothing.
    let picks: BTreeMap<String, Pick> = picks
        .into_iter()
        .map(|(k, mut p)| {
            if let (Source::New(_), Some(e)) = (&p.source, lock.packages.get(&k)) {
                if e.version == p.version && !args.force {
                    p.source = Source::Locked(e.clone());
                }
            }
            (k, p)
        })
        .collect();
    if args.name.is_none() {
        for k in lock.packages.keys().filter(|k| !picks.contains_key(*k)) {
            notes.push(format!("nothing needs {k} any more: `wmpy pip remove {k}` takes it out"));
        }
    }
    apply(&dir, lock, picks, notes, &asked, args.force, args.allow_warnings, args.json)
}

// ---- verify and sync --------------------------------------------------------

/// How a locked package's files stand against the lock.
struct Standing {
    missing: Vec<String>,
    changed: Vec<String>,
    /// The lock keeps no SHA-256s for it (written before they were kept).
    unhashed: bool,
}

fn standing(dir: &Path, e: &Entry) -> Standing {
    let mut s = Standing { missing: Vec::new(), changed: Vec::new(), unhashed: e.hashes.is_empty() };
    for f in &e.files {
        match std::fs::read(dir.join(f)) {
            Err(_) => s.missing.push(f.clone()),
            Ok(bytes) => {
                if let Some(want) = e.hashes.get(f) {
                    if index::sha256_hex(&bytes) != *want {
                        s.changed.push(f.clone());
                    }
                }
            }
        }
    }
    s
}

fn run_verify(args: DirArgs) -> u8 {
    let (dir, lock) = match open_lock(&args.dir) {
        Ok(x) => x,
        Err(c) => return c,
    };
    let mut bad = false;
    let mut rows = Vec::new();
    for (k, e) in &lock.packages {
        let s = standing(&dir, e);
        bad |= !s.missing.is_empty() || !s.changed.is_empty();
        rows.push((k.clone(), e.version.clone(), s));
    }
    if args.json {
        let list: Vec<Json> = rows
            .iter()
            .map(|(k, v, s)| json!({"name": k, "version": v, "ok": s.missing.is_empty() && s.changed.is_empty(), "missing": s.missing, "changed": s.changed, "hashed": !s.unhashed}))
            .collect();
        println!("{}", serde_json::to_string_pretty(&json!({"ok": !bad, "packages": list})).expect("json"));
        return u8::from(bad);
    }
    if rows.is_empty() {
        println!("no packages: the plugin has no wmpy.lock");
        return 0;
    }
    let color = crate::output::stdout();
    for (k, v, s) in &rows {
        let state = if !s.missing.is_empty() || !s.changed.is_empty() {
            crate::output::paint(color, "31", &format!("{} missing, {} changed", s.missing.len(), s.changed.len()))
        } else if s.unhashed {
            crate::output::paint(color, "33", "present (the lock keeps no SHA-256s: `wmpy pip sync` records them)")
        } else {
            crate::output::paint(color, "32", "as installed")
        };
        println!("{k} {v}: {state}");
        for f in s.missing.iter().take(SHOWN) {
            println!("  missing  {f}");
        }
        for f in s.changed.iter().take(SHOWN) {
            println!("  changed  {f}");
        }
    }
    if bad {
        crate::note!("`wmpy pip sync` puts them back from PyPI at the locked versions");
    }
    u8::from(bad)
}

fn run_sync(args: SyncArgs) -> u8 {
    let (dir, mut lock) = match open_lock(&args.dir) {
        Ok(x) => x,
        Err(c) => return c,
    };
    if lock.packages.is_empty() {
        println!("nothing to sync: the plugin has no wmpy.lock");
        return 0;
    }
    let mut repair: Vec<String> = Vec::new();
    let mut recorded = false;
    let keys: Vec<String> = lock.packages.keys().cloned().collect();
    for k in keys {
        let e = lock.packages.get_mut(&k).expect("listed");
        let s = standing(&dir, e);
        if args.all || !s.missing.is_empty() || !s.changed.is_empty() {
            repair.push(k);
        } else if s.unhashed {
            // Every file is there: what is there is taken as installed.
            e.hashes = e.files.iter().filter_map(|f| std::fs::read(dir.join(f)).ok().map(|b| (f.clone(), index::sha256_hex(&b)))).collect();
            recorded = true;
        }
    }
    if repair.is_empty() {
        if recorded {
            if let Err(e) = lock.write(&dir) {
                return fail(e, None, 2);
            }
            crate::note!("every vendored file is there; wrote their SHA-256s into wmpy.lock");
        }
        if args.json {
            println!("{}", serde_json::to_string_pretty(&json!({"packages": [], "notes": [], "recorded": recorded})).expect("json"));
        } else {
            println!("every vendored file is as installed");
        }
        return 0;
    }
    let specs: Vec<String> = repair.iter().map(|k| format!("{k}=={}", lock.packages[k].version)).collect();
    let reqs = match parse_specs(&specs) {
        Ok(r) => r,
        Err(e) => return fail(e, None, 2),
    };
    let index = Index::new();
    let fresh: HashSet<String> = repair.iter().cloned().collect();
    let mut r = Resolver { index: &index, lock: &lock, fresh, all_fresh: false, no_deps: true, picks: BTreeMap::new(), notes: Vec::new(), quiet: args.json || crate::output::quiet() };
    if let Err(e) = r.resolve(reqs) {
        return fail(e, None, 2);
    }
    let (picks, notes) = (std::mem::take(&mut r.picks), std::mem::take(&mut r.notes));
    // The lock already holds the decision to install each one: no analysis veto.
    apply(&dir, lock, picks, notes, &BTreeMap::new(), true, true, args.json)
}

// ---- check ------------------------------------------------------------------

fn run_check(args: CheckArgs) -> u8 {
    let index = Index::new();
    let empty = Lock::default();
    let mut dists: Vec<(Dist, Vec<String>)> = Vec::new();
    let mut specs = Vec::new();
    for t in &args.targets {
        let path = Path::new(t);
        if path.exists() {
            match local(path) {
                Ok(d) => {
                    // Its dependencies are not fetched: their likely import names count as present.
                    let deps: Vec<String> = d.requires.iter().filter(|r| r.applies(&[]).unwrap_or(false)).map(|r| r.key().replace('-', "_")).collect();
                    dists.push((d, deps));
                }
                Err(e) => return fail(e, None, 2),
            }
        } else {
            specs.push(t.clone());
        }
    }
    let mut notes = Vec::new();
    if !specs.is_empty() {
        let reqs = match parse_specs(&specs) {
            Ok(r) => r,
            Err(e) => return fail(e, None, 2),
        };
        let mut r = Resolver { index: &index, lock: &empty, fresh: HashSet::new(), all_fresh: true, no_deps: args.no_deps, picks: BTreeMap::new(), notes: Vec::new(), quiet: args.json };
        if let Err(e) = r.resolve(reqs) {
            return fail(e, None, 2);
        }
        let roots: Vec<(String, Vec<String>)> = r.picks.iter().map(|(k, p)| (k.clone(), p.roots())).collect();
        notes = std::mem::take(&mut r.notes);
        for (key, p) in std::mem::take(&mut r.picks) {
            if let Source::New(d) = p.source {
                let mut deps: Vec<String> = roots.iter().filter(|(k, _)| *k != key).flat_map(|(_, r)| r.iter().cloned()).collect();
                // With --no-deps the dependencies' import names are only guessed.
                deps.extend(p.requires.iter().map(|k| k.replace('-', "_")));
                dists.push((*d, deps));
            }
        }
    }
    if dists.is_empty() {
        return print_notes_only(&notes, args.json);
    }
    let mut packages = Vec::new();
    let mut incompatible = false;
    let mut rows = vec![vec!["package".into(), "version".into(), "verdict".into(), "errors".into(), "warnings".into(), "files".into(), "size".into(), "licence".into()]];
    for (d, available) in &dists {
        let report = (!d.modules.is_empty() || !d.native.is_empty()).then(|| install::analyse(d, available));
        let mut problems: Vec<Json> = report.as_ref().map(|r| r.problems().map(|p| to_diag(p).to_json()).collect()).unwrap_or_default();
        let mut verdict = report.as_ref().map_or(Verdict::Incompatible, |r| r.verdict);
        if let Some(why) = &d.refused {
            let mut diag = Diag::error(format!("{} cannot be vendored: {why}", d.name));
            diag.code = Some("needs-build".into());
            problems.insert(0, diag.to_json());
            verdict = Verdict::Incompatible;
        }
        incompatible |= verdict == Verdict::Incompatible;
        let count = |s: PSeverity| report.as_ref().map_or(0, |r| r.problems().filter(|p| p.severity == s).count());
        rows.push(vec![
            d.key.clone(),
            d.version.clone(),
            verdict.name().into(),
            (count(PSeverity::Error) + usize::from(d.refused.is_some())).to_string(),
            count(PSeverity::Warning).to_string(),
            d.modules.len().to_string(),
            size(d.bytes()),
            d.license.clone().unwrap_or_else(|| "none found".into()),
        ]);
        if !args.json {
            if let Some(why) = &d.refused {
                let mut diag = Diag::error(format!("{} cannot be vendored: {why}", d.name));
                diag.code = Some("needs-build".into());
                eprint!("{}", diag.render(&|_| None, color_stderr()));
            }
            if let Some(r) = &report {
                print_problems(&d.key, r, d);
            }
        }
        packages.push(json!({
            "name": d.key, "version": d.version, "verdict": verdict.name(), "problems": problems,
            "roots": d.roots, "license": d.license, "notes": d.notes, "bytes": d.bytes(), "standIn": d.stand_in,
        }));
    }
    if args.json {
        println!("{}", serde_json::to_string_pretty(&json!({"packages": packages, "notes": notes})).expect("json"));
    } else {
        print!("{}", table(&rows));
        for (d, _) in &dists {
            for n in &d.notes {
                println!("  {}: {n}", d.key);
            }
        }
        for n in &notes {
            println!("  {n}");
        }
    }
    u8::from(incompatible)
}

/// A wheel, an sdist or a folder named on the command line.
fn local(path: &Path) -> Result<Dist, String> {
    if path.is_dir() {
        let mut files = BTreeMap::new();
        let package = path.join("__init__.py").is_file();
        let name = path.canonicalize().ok().and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned())).unwrap_or_default();
        for f in crate::project::files(path) {
            let rel = if package { format!("{name}/{f}") } else { f.clone() };
            if let Ok(bytes) = std::fs::read(path.join(&f)) {
                files.insert(rel, bytes);
            }
        }
        let mut d = dist::from_tree(files, &path.display().to_string(), "");
        if d.name.is_empty() {
            d.name = name.clone();
            d.key = normalize(&name);
        }
        return Ok(d);
    }
    let (info, bytes) = index::read_local(path)?;
    if info.is_wheel() {
        dist::from_wheel(&bytes, &info.url, &info.sha256)
    } else {
        dist::from_sdist(&bytes, &info.filename, &info.url, &info.sha256)
    }
}

// ---- list, why, remove ------------------------------------------------------

fn open_lock(dir: &Path) -> Result<(PathBuf, Lock), u8> {
    let dir = crate::project::plugin_dir(dir).map_err(|d| {
        eprint!("{}", d.render(&|_| None, color_stderr()));
        2
    })?;
    let lock = Lock::read(&dir).map_err(|e| fail(e, None, 2))?;
    Ok((dir, lock))
}

fn run_list(args: DirArgs) -> u8 {
    let (dir, lock) = match open_lock(&args.dir) {
        Ok(x) => x,
        Err(c) => return c,
    };
    let on_disk = |e: &Entry| e.files.iter().filter_map(|f| std::fs::metadata(dir.join(f)).ok()).map(|m| m.len() as usize).sum::<usize>();
    if args.json {
        let list: Vec<Json> = lock
            .packages
            .iter()
            .map(|(k, e)| {
                json!({"name": k, "version": e.version, "verdict": e.verdict, "problems": e.problems, "roots": e.roots,
                    "files": e.files.len(), "bytes": on_disk(e), "license": e.license, "requires": e.requires, "requiredBy": lock.dependents(k)})
            })
            .collect();
        println!("{}", serde_json::to_string_pretty(&json!({"packages": list})).expect("json"));
        return 0;
    }
    if lock.packages.is_empty() {
        println!("no packages: the plugin has no wmpy.lock (add one with `wmpy pip install <name>`)");
        return 0;
    }
    let mut rows = vec![vec!["package".into(), "version".into(), "verdict".into(), "problems".into(), "files".into(), "size".into(), "licence".into(), "needed by".into()]];
    for (k, e) in &lock.packages {
        let by = lock.dependents(k);
        rows.push(vec![
            k.clone(),
            e.version.clone(),
            e.verdict.clone(),
            e.problems.to_string(),
            e.files.len().to_string(),
            size(on_disk(e)),
            e.license.clone().unwrap_or_else(|| "none found".into()),
            if by.is_empty() { "-".into() } else { by.join(", ") },
        ]);
    }
    print!("{}", table(&rows));
    let missing: Vec<&String> = lock.packages.values().flat_map(|e| e.files.iter()).filter(|f| !dir.join(f).is_file()).collect();
    if !missing.is_empty() {
        warn(&format!("{} files in wmpy.lock are gone from the folder, such as {}: `wmpy pip install --force <name>` puts them back", missing.len(), missing[0]));
    }
    0
}

fn run_why(args: WhyArgs) -> u8 {
    let (_, lock) = match open_lock(&args.dir) {
        Ok(x) => x,
        Err(c) => return c,
    };
    let key = normalize(&args.name);
    let Some(entry) = lock.packages.get(&key) else {
        if args.json {
            println!("{}", json!({"ok": false, "error": format!("{} is not installed in this plugin", args.name)}));
            return 1;
        }
        return fail(format!("{} is not installed in this plugin", args.name), None, 1);
    };
    if args.json {
        // Every chain from something asked for by name down to it.
        fn chains(lock: &Lock, key: &str, seen: &mut Vec<String>) -> Vec<Vec<String>> {
            let by = lock.dependents(key);
            if by.is_empty() || seen.len() > 32 {
                return vec![vec![key.to_string()]];
            }
            let mut out = Vec::new();
            for d in by {
                if seen.contains(&d) {
                    continue;
                }
                seen.push(d.clone());
                for mut c in chains(lock, &d, seen) {
                    c.push(key.to_string());
                    out.push(c);
                }
                seen.pop();
            }
            if out.is_empty() {
                out.push(vec![key.to_string()]);
            }
            out
        }
        let direct = entry.requested.is_some() || lock.dependents(&key).is_empty();
        println!(
            "{}",
            json!({"name": key, "version": entry.version, "requested": entry.requested, "direct": direct, "neededBy": lock.dependents(&key), "chains": chains(&lock, &key, &mut Vec::new())})
        );
        return 0;
    }
    println!("{key} {}", entry.version);
    if let Some(r) = &entry.requested {
        println!("  asked for directly, as {r}");
    }
    let mut seen = HashSet::new();
    fn walk(lock: &Lock, key: &str, depth: usize, seen: &mut HashSet<String>) {
        let by = lock.dependents(key);
        if by.is_empty() {
            if lock.packages.get(key).is_none_or(|e| e.requested.is_none()) {
                println!("{}installed directly: nothing else here needs it", "  ".repeat(depth));
            }
            return;
        }
        for d in by {
            let v = lock.packages.get(&d).map(|e| e.version.as_str()).unwrap_or("?");
            println!("{}needed by {d} {v}", "  ".repeat(depth));
            if seen.insert(d.clone()) {
                walk(lock, &d, depth + 1, seen);
            }
        }
    }
    walk(&lock, &key, 1, &mut seen);
    0
}

fn run_remove(args: RemoveArgs) -> u8 {
    let (dir, mut lock) = match open_lock(&args.dir) {
        Ok(x) => x,
        Err(c) => return c,
    };
    let key = normalize(&args.name);
    let Some(entry) = lock.packages.get(&key).cloned() else {
        return fail(format!("{} is not installed in this plugin", args.name), Some("`wmpy pip list` shows what is".into()), 1);
    };
    let by = lock.dependents(&key);
    if !by.is_empty() && !args.force {
        return fail(format!("{key} is needed by {}", by.join(", ")), Some(format!("remove {} first, or pass --force", by.join(" and "))), 1);
    }
    let keep: BTreeSet<String> = lock.packages.iter().filter(|(k, _)| **k != key).flat_map(|(_, e)| e.files.iter().cloned()).collect();
    let gone: Vec<String> = entry.files.iter().filter(|f| !keep.contains(*f)).cloned().collect();
    install::remove_files(&dir, &gone);
    lock.packages.remove(&key);
    if let Err(e) = lock.write(&dir) {
        return fail(e, None, 2);
    }
    let kept = entry.files.len() - gone.len();
    let orphans: Vec<&String> = entry.requires.iter().filter(|dep| lock.packages.contains_key(*dep) && lock.dependents(dep).is_empty() && lock.packages[*dep].requested.is_none()).collect();
    if args.json {
        println!("{}", json!({"removed": key, "version": entry.version, "files": gone, "kept": kept, "unneeded": orphans, "stillNeededBy": by}));
        return 0;
    }
    println!("removed {key} {} ({} files{})", entry.version, gone.len(), if kept > 0 { format!("; {kept} another package also lists stay") } else { String::new() });
    for dep in orphans {
        println!("  nothing here needs {dep} any more: `wmpy pip remove {dep}` takes it out too");
    }
    if !by.is_empty() {
        warn(&format!("{} still needs {key}, so it may fail to import", by.join(", ")));
    }
    0
}
