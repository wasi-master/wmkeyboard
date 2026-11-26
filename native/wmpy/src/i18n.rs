//! `wmpy i18n`: a plugin's translations, from the text its code passes to
//! `_()`, `gettext()` and `ngettext()` and the `@` keys of plugin.json
//! ([wm_python_tooling::i18n]).
//!
//! * `extract` adds what each `locales/<tag>.json` lacks, empty, and keeps
//!   every translation; a key nothing uses any more is reported, and taken
//!   out only with `--prune`. The keyboard shows the English for an empty
//!   translation, so a half-done file is safe to ship.
//! * `check` exits 1 when a file lacks a translation, leaves one empty, puts
//!   `{names}` in it the English does not have (or drops one), or lacks a
//!   plural form its language needs.
//! * `stats` prints how much of each language is done.

use std::path::{Path, PathBuf};

use serde_json::{json, Map, Value};
use wm_python_tooling::i18n::{self as tr, Catalog, LocaleReport};
use wm_python_tooling::manifest as codec;
use wm_python_tooling::usage::Source;

use crate::diag::{color_stderr, folder_source, place, Diag, Severity};
use crate::project;

#[derive(clap::Args, Debug)]
pub struct Args {
    #[command(subcommand)]
    pub action: Action,
}

#[derive(clap::Subcommand, Debug)]
pub enum Action {
    /// Add the text the plugin translates to each locales/<tag>.json, keeping every translation
    Extract(ExtractArgs),
    /// Exit 1 when a translation is missing or empty, or its {names} or plural forms are wrong
    Check(Common),
    /// How much of each language is translated
    Stats(Common),
}

#[derive(clap::Args, Debug)]
pub struct Common {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Only this language (repeat for more): bn, pt-BR. extract starts the file when there is none
    #[arg(long = "lang", value_name = "TAG")]
    pub langs: Vec<String>,
    /// Print the result as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

#[derive(clap::Args, Debug)]
pub struct ExtractArgs {
    #[command(flatten)]
    pub common: Common,
    /// Take out the keys nothing in the plugin uses any more
    #[arg(long)]
    pub prune: bool,
    /// Show what would change and write nothing
    #[arg(long)]
    pub dry_run: bool,
}

pub fn run(args: Args) -> u8 {
    match args.action {
        Action::Extract(a) => extract(&a),
        Action::Check(c) => check(&c, false),
        Action::Stats(c) => check(&c, true),
    }
}

/// The plugin's folder and its catalog, or why not (printed, exit 2).
struct Plugin {
    dir: PathBuf,
    catalog: Catalog,
}

fn fail(json_out: bool, d: Diag) -> u8 {
    if json_out {
        println!("{}", json!({"ok": false, "error": d.to_json()}));
    } else {
        eprint!("{}", d.render(&|_| None, color_stderr()));
    }
    2
}

fn load(c: &Common) -> Result<Plugin, Diag> {
    let dir = project::plugin_dir(&c.dir)?;
    for tag in &c.langs {
        if !codec::is_locale_tag(tag) {
            let mut d = Diag::error(format!("\"{tag}\" is not a language tag the keyboard reads"));
            d.help = Some("write it as bn, pt-BR or es-419: two or three lowercase letters, then a dash and a region or script".into());
            return Err(d);
        }
    }
    let manifest = std::fs::read_to_string(dir.join(codec::MANIFEST)).unwrap_or_default();
    let sources = crate::check::own_sources(&dir);
    let files: Vec<Source> = sources.iter().map(|(name, text, entry)| Source { name, text, entry: *entry }).collect();
    let catalog = tr::extract(&manifest, &files);
    Ok(Plugin { dir, catalog })
}

/// The tags with a file under locales/, sorted.
fn existing(dir: &Path) -> Vec<String> {
    let mut tags: Vec<String> = project::files(dir).iter().filter_map(|f| codec::locale_tag(f).map(String::from)).collect();
    tags.sort();
    tags
}

/// The languages to work on: those asked for, else every file there is.
fn tags(p: &Plugin, c: &Common) -> Vec<String> {
    if c.langs.is_empty() { existing(&p.dir) } else { c.langs.clone() }
}

fn locale_path(tag: &str) -> String {
    format!("locales/{tag}.json")
}

/// A translation file, read: None when there is none, Err when it is not one.
fn read_locale(dir: &Path, tag: &str) -> Result<Option<(String, Map<String, Value>)>, String> {
    let path = dir.join(locale_path(tag));
    let text = match std::fs::read_to_string(&path) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(format!("cannot read {}: {e}", locale_path(tag))),
    };
    match serde_json::from_str::<Value>(&text) {
        Ok(Value::Object(m)) => Ok(Some((text, m))),
        Ok(_) => Err(format!("{} is not a JSON object", locale_path(tag))),
        Err(e) => Err(format!("{} is not valid JSON: {e}", locale_path(tag))),
    }
}

/// Warnings for the calls whose text no file can translate.
fn unreadable(p: &Plugin) -> Vec<Diag> {
    let source = folder_source(&p.dir);
    p.catalog
        .unreadable
        .iter()
        .map(|u| {
            let mut d = Diag::new(Severity::Warning, format!("{}() is given text that is not a literal, so it cannot be translated", u.call));
            d.code = Some("not_extractable".into());
            d.file = Some(u.place.file.clone());
            let text = source(&u.place.file).unwrap_or_default();
            let (line, col, end) = place(&text, u.place.span.start, u.place.span.end);
            d.line = Some(line);
            d.col = Some(col);
            d.end_col = end;
            d.help = Some(if u.call == "ngettext" {
                "pass the singular and plural as literals: ngettext(\"{n} file\", \"{n} files\", n)".into()
            } else {
                "pass the English as a literal and the values by name: _(\"Hello, {name}\", name=user)".into()
            });
            d
        })
        .collect()
}

fn mixed(p: &Plugin) -> Vec<Diag> {
    p.catalog
        .mixed
        .iter()
        .map(|k| {
            let mut d = Diag::new(Severity::Warning, format!("\"{k}\" is passed both to _() and to ngettext(); one translation cannot be both text and plural forms"));
            d.code = Some("mixed_plural".into());
            d.file = p.catalog.get(k).and_then(|m| m.places.first()).map(|pl| pl.file.clone());
            d.line = p.catalog.get(k).and_then(|m| m.places.first()).map(|pl| pl.line as u32);
            d
        })
        .collect()
}

fn print_diags(p: &Plugin, diags: &[Diag]) {
    let color = color_stderr();
    let source = folder_source(&p.dir);
    for d in diags {
        eprint!("{}", d.render(&source, color));
    }
}

fn quoted(keys: &[String]) -> String {
    const SHOWN: usize = 4;
    let mut out: Vec<String> = keys.iter().take(SHOWN).map(|k| serde_json::to_string(k).unwrap_or_default()).collect();
    if keys.len() > SHOWN {
        out.push(format!("and {} more", keys.len() - SHOWN));
    }
    out.join(", ")
}

fn extract(a: &ExtractArgs) -> u8 {
    let c = &a.common;
    let p = match load(c) {
        Ok(p) => p,
        Err(d) => return fail(c.json, d),
    };
    let warnings: Vec<Diag> = unreadable(&p).into_iter().chain(mixed(&p)).collect();
    let (labels, texts, plurals) = tr::counts(&p.catalog);
    let tags = tags(&p, c);
    let mut results = Vec::new();
    let mut broken = false;
    let mut lines: Vec<String> = Vec::new();
    for tag in &tags {
        let path = locale_path(tag);
        let (old_text, old) = match read_locale(&p.dir, tag) {
            Ok(Some((t, m))) => (Some(t), m),
            Ok(None) => (None, Map::new()),
            Err(why) => {
                lines.push(format!("{why}; left as it is"));
                results.push(json!({"tag": tag, "file": path, "error": why}));
                broken = true;
                continue;
            }
        };
        let (new, u) = tr::update_locale(&p.catalog, tag, &old, a.prune);
        let text = tr::write_locale(&new, old_text.as_deref());
        let write = (u.changed() || old_text.is_none()) && !a.dry_run;
        if write {
            let full = p.dir.join(&path);
            let made = full.parent().map_or(Ok(()), std::fs::create_dir_all).and_then(|_| std::fs::write(&full, &text));
            if let Err(e) = made {
                lines.push(format!("{path}: cannot write: {e}"));
                broken = true;
                continue;
            }
        }
        let report = tr::check_locale(&p.catalog, tag, &new);
        let todo = report.total - report.translated;
        let verb = if a.dry_run { "would add" } else { "added" };
        let mut said = match (old_text.is_none(), u.added.len(), u.forms_added.len()) {
            (true, n, _) => format!("{path}: {} with {n} {}", if a.dry_run { "would start it" } else { "started" }, if n == 1 { "key" } else { "keys" }),
            (false, 0, 0) => format!("{path}: up to date"),
            (false, n, 0) => format!("{path}: {verb} {n} {}", if n == 1 { "key" } else { "keys" }),
            (false, n, f) => format!("{path}: {verb} {n} {} and {f} plural {}", if n == 1 { "key" } else { "keys" }, if f == 1 { "form" } else { "forms" }),
        };
        if todo > 0 {
            said.push_str(&format!(", {todo} of {} still to translate", report.total));
        }
        lines.push(said);
        if !u.pruned.is_empty() {
            lines.push(format!("  {} {}: {}", if a.dry_run { "would take out" } else { "took out" }, u.pruned.len(), quoted(&u.pruned)));
        }
        if !u.unused.is_empty() {
            lines.push(format!("  {} nothing uses, kept: {} (--prune takes them out)", count(u.unused.len(), "key", "keys"), quoted(&u.unused)));
        }
        results.push(json!({
            "tag": tag, "file": path, "created": old_text.is_none(), "written": write,
            "added": u.added, "formsAdded": u.forms_added.iter().map(|(k, f)| json!({"key": k, "form": f})).collect::<Vec<_>>(),
            "pruned": u.pruned, "unused": u.unused, "untranslated": todo,
        }));
    }
    if c.json {
        let mut v = p.catalog.json();
        v["ok"] = json!(!broken);
        v["locales"] = json!(results);
        v["warnings"] = json!(warnings.iter().map(Diag::to_json).collect::<Vec<_>>());
        println!("{v}");
    } else {
        print_diags(&p, &warnings);
        let plural = if plurals == 0 { String::new() } else { format!(" ({plurals} of them plural)") };
        eprintln!("found {} to translate: {labels} in plugin.json, {} in the code{plural}", count(p.catalog.messages.len(), "text", "texts"), texts + plurals);
        for l in &lines {
            eprintln!("{l}");
        }
        if tags.is_empty() {
            eprintln!("no translations yet: wmpy i18n extract --lang bn starts locales/bn.json");
        }
    }
    if broken {
        2
    } else {
        0
    }
}

fn count(n: usize, one: &str, many: &str) -> String {
    format!("{n} {}", if n == 1 { one } else { many })
}

fn check(c: &Common, stats: bool) -> u8 {
    let p = match load(c) {
        Ok(p) => p,
        Err(d) => return fail(c.json, d),
    };
    let warnings: Vec<Diag> = unreadable(&p).into_iter().chain(mixed(&p)).collect();
    let tags = tags(&p, c);
    let mut reports: Vec<LocaleReport> = Vec::new();
    let mut unreadable_files: Vec<(String, String)> = Vec::new();
    for tag in &tags {
        match read_locale(&p.dir, tag) {
            Ok(Some((_, m))) => reports.push(tr::check_locale(&p.catalog, tag, &m)),
            Ok(None) => reports.push(tr::check_locale(&p.catalog, tag, &Map::new())),
            Err(why) => unreadable_files.push((tag.clone(), why)),
        }
    }
    let ok = unreadable_files.is_empty() && reports.iter().all(LocaleReport::complete);
    if c.json {
        let v = json!({
            "ok": ok, "total": p.catalog.messages.len(),
            "locales": reports.iter().map(LocaleReport::json).collect::<Vec<_>>(),
            "unreadable": unreadable_files.iter().map(|(t, w)| json!({"tag": t, "file": locale_path(t), "error": w})).collect::<Vec<_>>(),
            "warnings": warnings.iter().map(Diag::to_json).collect::<Vec<_>>(),
        });
        println!("{v}");
    } else {
        print_diags(&p, &warnings);
        if stats {
            print!("{}", stats_table(&reports, p.catalog.messages.len()));
        } else {
            let color = color_stderr();
            for r in &reports {
                for d in locale_diags(&p, r) {
                    eprint!("{}", d.render(&|_| None, color));
                }
            }
        }
        for (tag, why) in &unreadable_files {
            eprintln!("{}: {why}", locale_path(tag));
        }
        if tags.is_empty() {
            eprintln!("no translations yet: wmpy i18n extract --lang bn starts locales/bn.json");
        } else if !stats {
            let done = reports.iter().filter(|r| r.complete()).count();
            eprintln!("checked {} against {}: {done} complete", count(reports.len(), "language", "languages"), count(p.catalog.messages.len(), "text", "texts"));
        }
    }
    if stats {
        return u8::from(!unreadable_files.is_empty()) * 2;
    }
    u8::from(!ok)
}

/// What is wrong with one language, as problems in its file.
fn locale_diags(p: &Plugin, r: &LocaleReport) -> Vec<Diag> {
    let file = locale_path(&r.tag);
    let text = std::fs::read_to_string(p.dir.join(&file)).unwrap_or_default();
    let line_of = |key: &str| -> Option<u32> {
        let q = serde_json::to_string(key).ok()?;
        text.find(&q).map(|at| text[..at].matches('\n').count() as u32 + 1)
    };
    let mut out = Vec::new();
    if !r.missing.is_empty() {
        let mut d = Diag::error(format!("{} {} no translation: {}", count(r.missing.len(), "text", "texts"), if r.missing.len() == 1 { "has" } else { "have" }, quoted(&r.missing)));
        d.code = Some("missing_translation".into());
        d.file = Some(file.clone());
        d.help = Some(format!("wmpy i18n extract --lang {} adds them to the file, empty", r.tag));
        out.push(d);
    }
    if !r.empty.is_empty() {
        let mut d = Diag::error(format!("{} {} empty: {}", count(r.empty.len(), "translation", "translations"), if r.empty.len() == 1 { "is" } else { "are" }, quoted(&r.empty)));
        d.code = Some("empty_translation".into());
        d.file = Some(file.clone());
        d.line = r.empty.first().and_then(|k| line_of(k));
        d.notes.push("the keyboard shows the English for these".into());
        out.push(d);
    }
    for pr in &r.problems {
        let key = serde_json::to_string(&pr.key).unwrap_or_default();
        let form = pr.form.as_ref().map(|f| format!(" ({f})")).unwrap_or_default();
        let mut d = Diag::error(format!("{key}{form} {}", pr.message));
        d.code = Some("bad_translation".into());
        d.file = Some(file.clone());
        d.line = line_of(&pr.key);
        if let Some(m) = p.catalog.get(&pr.key) {
            d.notes.push(match &m.plural {
                Some(pl) => format!("the English: {} / {}", serde_json::to_string(&m.source).unwrap_or_default(), serde_json::to_string(pl).unwrap_or_default()),
                None => format!("the English: {}", serde_json::to_string(&m.source).unwrap_or_default()),
            });
        }
        out.push(d);
    }
    if !r.unused.is_empty() {
        let mut d = Diag::new(Severity::Warning, format!("{} nothing uses: {}", count(r.unused.len(), "key", "keys"), quoted(&r.unused)));
        d.code = Some("unused_translation".into());
        d.file = Some(file);
        d.help = Some("wmpy i18n extract --prune takes them out".into());
        out.push(d);
    }
    out
}

/// One row a language: how much is translated, and what is left.
fn stats_table(reports: &[LocaleReport], total: usize) -> String {
    let mut out = String::new();
    if reports.is_empty() {
        return out;
    }
    let width = reports.iter().map(|r| r.tag.len()).max().unwrap_or(4).max(8);
    out.push_str(&format!("{:<width$}  {:>11}  {:>5}  {:>7}  {:>5}  {:>6}  {:>8}\n", "language", "translated", "%", "missing", "empty", "unused", "problems"));
    for r in reports {
        let percent = if total == 0 { 100 } else { r.translated * 100 / total };
        out.push_str(&format!(
            "{:<width$}  {:>11}  {:>4}%  {:>7}  {:>5}  {:>6}  {:>8}\n",
            r.tag,
            format!("{}/{}", r.translated, r.total),
            percent,
            r.missing.len(),
            r.empty.len(),
            r.unused.len(),
            r.problems.len()
        ));
    }
    out
}
