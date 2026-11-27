//! `wmpy pip`: pure-Python packages from PyPI, vendored into a plugin.
//!
//! ```text
//! wmpy pip install <spec>... [--dir .] [--no-deps] [--force] [--allow-warnings] [--json]
//! wmpy pip check <spec|folder|wheel>... [--no-deps] [--json]
//! wmpy pip list [--dir .] [--json]
//! wmpy pip remove <name> [--dir .] [--force]
//! wmpy pip why <name> [--dir .]
//! wmpy pip update [name] [--dir .] [--force] [--allow-warnings] [--json]
//! ```
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
    /// Move packages to their newest releases
    Update(UpdateArgs),
}

#[derive(clap::Args, Debug)]
pub struct InstallArgs {
    /// Packages, as pip takes them: humanize, "python-dateutil>=2.8", "attrs[tests]"
    #[arg(required = true)]
    pub specs: Vec<String>,
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
}

#[derive(clap::Args, Debug)]
pub struct WhyArgs {
    /// The package to explain
    pub name: String,
    /// The plugin folder
    #[arg(long, default_value = ".")]
    pub dir: PathBuf,
}

#[derive(clap::Args, Debug)]
pub struct UpdateArgs {
    /// Only this package (and what it newly needs); every package when left out
    pub name: Option<String>,
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
    }
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
fn apply(dir: &Path, mut lock: Lock, picks: BTreeMap<String, Pick>, notes: Vec<String>, force: bool, allow_warnings: bool, json_out: bool) -> u8 {
    let own = install::own_roots(dir, &lock);
    let mut available: Vec<String> = picks.values().flat_map(Pick::roots).collect();
    available.extend(lock.packages.values().flat_map(|e| e.roots.iter().cloned()));
    available.extend(own.iter().cloned());
    let mut outcomes: BTreeMap<String, Outcome> = BTreeMap::new();
    for (key, pick) in &picks {
        let outcome = match &pick.source {
            Source::Locked(_) => Outcome { action: Action::Kept, report: None, reason: None },
            Source::New(d) => {
                let report = (!d.modules.is_empty() || !d.native.is_empty()).then(|| install::analyse(d, &available));
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
            },
        );
        written_any = true;
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
    let requested = match parse_specs(&args.specs) {
        Ok(r) => r,
        Err(e) => return fail(e, Some("give packages as pip takes them: humanize, \"tabulate>=0.9\", \"attrs[tests]\"".into()), 2),
    };
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
    apply(&dir, lock, picks, notes, args.force, args.allow_warnings, args.json)
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
    let (requested, fresh, all_fresh): (Vec<String>, HashSet<String>, bool) = match &args.name {
        Some(name) => {
            let key = normalize(name);
            if !lock.packages.contains_key(&key) {
                return fail(format!("{name} is not installed in this plugin"), Some(format!("wmpy pip install {name}")), 1);
            }
            (vec![key.clone()], [key].into_iter().collect(), false)
        }
        // What nothing else needs; the rest follows from it.
        None => (lock.packages.keys().filter(|k| lock.dependents(k).is_empty()).cloned().collect(), HashSet::new(), true),
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
    apply(&dir, lock, picks, notes, args.force, args.allow_warnings, args.json)
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
        let roots: Vec<String> = r.picks.values().flat_map(Pick::roots).collect();
        notes = std::mem::take(&mut r.notes);
        for (_, p) in std::mem::take(&mut r.picks) {
            if let Source::New(d) = p.source {
                let mut deps = roots.clone();
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
            "roots": d.roots, "license": d.license, "notes": d.notes, "bytes": d.bytes(),
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
        return fail(format!("{} is not installed in this plugin", args.name), None, 1);
    };
    println!("{key} {}", entry.version);
    let mut seen = HashSet::new();
    fn walk(lock: &Lock, key: &str, depth: usize, seen: &mut HashSet<String>) {
        let by = lock.dependents(key);
        if by.is_empty() {
            println!("{}installed directly: nothing else here needs it", "  ".repeat(depth));
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
    println!("removed {key} {} ({} files{})", entry.version, gone.len(), if kept > 0 { format!("; {kept} another package also lists stay") } else { String::new() });
    for dep in &entry.requires {
        if lock.packages.contains_key(dep) && lock.dependents(dep).is_empty() {
            println!("  nothing here needs {dep} any more: `wmpy pip remove {dep}` takes it out too");
        }
    }
    if !by.is_empty() {
        warn(&format!("{} still needs {key}, so it may fail to import", by.join(", ")));
    }
    0
}
