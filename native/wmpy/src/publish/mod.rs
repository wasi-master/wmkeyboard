//! `wmpy publish`: send a plugin to the community repository, or put it in
//! your own addon repository.
//!
//! ```text
//! wmpy publish [dir]                       to the community repository
//! wmpy publish [dir] --update <id>         a new version of one published before
//! wmpy publish [dir] --repo <path>         into your own addon repository's checkout
//! wmpy publish [dir] --dry-run             show what would be sent, send nothing
//! ```
//!
//! Either way it first runs `wmpy check`, the plugin's tests when it has
//! any, and `wmpy pack`, each as a subprocess of this same binary, and stops
//! on the first that fails.
//!
//! To the community repository it files the issue the plugin form would
//! make (see [form]): through the GitHub REST API when there is a token
//! (GITHUB_TOKEN, GH_TOKEN or `gh auth token`), else by opening the form in
//! the browser, filled in, with the payload in the link, on the clipboard or
//! as a `.zip` to drag in, whichever fits, as the app's Share with everyone
//! does. The bot then checks it again and a maintainer reviews the code.

mod form;
mod github;
mod repo;

use std::io::{BufRead, IsTerminal};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use serde_json::Value as Json;

use form::{Fields, Tier};

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Send it to the community repository (the default)
    #[arg(long, conflicts_with = "repo")]
    pub community: bool,
    /// Put it in your own addon repository instead: the folder holding its wmkeyboard-repo.json
    #[arg(long, value_name = "PATH")]
    pub repo: Option<PathBuf>,
    /// Send a new version of the plugin published under this id
    #[arg(long, value_name = "ID")]
    pub update: Option<String>,
    /// The code's licence, as an SPDX id [default: MIT]
    #[arg(long, visible_alias = "license", value_name = "SPDX")]
    pub licence: Option<String>,
    /// Tags for its card, comma-separated
    #[arg(long, value_delimiter = ',', value_name = "A,B")]
    pub tags: Vec<String>,
    /// The description on its card [default: plugin.json's]
    #[arg(long, value_name = "TEXT")]
    pub description: Option<String>,
    /// The plugin this one is a fork of, as repo-id/addon-id
    #[arg(long, value_name = "REPO/ID")]
    pub based_on: Option<String>,
    /// Show what would be sent, and send nothing
    #[arg(long)]
    pub dry_run: bool,
    /// Send without asking first
    #[arg(long, short)]
    pub yes: bool,
    /// The GitHub API to file through [default: https://api.github.com, or WMPY_GITHUB_API]
    #[arg(long, hide = true)]
    pub api_url: Option<String>,
    /// The community repository as owner/name [default: wasi-master/wmkeyboard-community-addons, or WMPY_COMMUNITY_REPO]
    #[arg(long, hide = true)]
    pub github_repo: Option<String>,
}

const COMMUNITY_REPO: &str = "wasi-master/wmkeyboard-community-addons";
const GITHUB_API: &str = "https://api.github.com";

/// The most the app downloads for a plugin (`AddonType.Plugin.maxBytes`).
const MAX_PLUGIN_BYTES: usize = 1 << 20;

/// What the app tells the user each permission does, and its tier, as `PluginPermission.kt` has them.
const PERMISSIONS: &[(&str, bool, &str)] = &[
    ("storage", false, "Save data on your device"),
    ("reminders", false, "Show reminders at times that you set in it"),
    ("selection", false, "Change text you select, when you tap one of its actions"),
    ("field.write", false, "Type, replace and delete text in the field you are typing in"),
    ("clipboard.write", false, "Copy text to the clipboard"),
    ("sensors", false, "Read motion and light sensors while its panel is open"),
    ("scan", false, "Open the keyboard's QR code and text scanners"),
    ("device", false, "Vibrate, turn on the flashlight and read text aloud"),
    ("share", false, "Share text and open links, after you confirm"),
    ("notifications", false, "Show notifications"),
    ("clock", false, "Set alarms and timers in the keyboard's Clock"),
    ("previews", false, "Read a file you open with it in the keyboard's file manager, to show it"),
    ("network", true, "Connect to the internet, to the websites it lists"),
    ("network.local", true, "Reach the server you type in its settings on your home network"),
    ("ai", true, "Ask the AI model you set up in the keyboard"),
    ("field.read", true, "Read the text around the cursor and the text you selected, when you tap something in its panel"),
    ("clipboard.read", true, "Read the clipboard, when you tap something in its panel"),
    ("location", true, "Find out where your phone is"),
    ("files", true, "Open files you pick and photos you take, and save files where you choose"),
    ("calendar", true, "Read your calendar and add events to it"),
    ("todo", true, "Read and change your To-do list"),
];
const READS: &[&str] = &["selection", "field.read", "clipboard.read", "files", "location", "calendar", "todo"];
const EGRESS: &[&str] = &["network", "network.local", "ai"];

/// The binary the steps run: this one, or WMPY_SELF in the tests.
fn wmpy() -> PathBuf {
    std::env::var_os("WMPY_SELF").map(PathBuf::from).or_else(|| std::env::current_exe().ok()).unwrap_or_else(|| PathBuf::from("wmpy"))
}

struct Step {
    code: Option<i32>,
    json: Option<Json>,
    stderr: String,
}

fn step(args: &[&str], dir: &Path) -> Step {
    match Command::new(wmpy()).args(args).arg(dir).stdin(Stdio::null()).output() {
        Ok(out) => Step {
            code: out.status.code(),
            json: serde_json::from_slice(&out.stdout).ok().filter(|j: &Json| j.is_object()),
            stderr: String::from_utf8_lossy(&out.stderr).trim().to_string(),
        },
        Err(e) => Step { code: None, json: None, stderr: e.to_string() },
    }
}

fn first_line(s: &str) -> &str {
    s.lines().next().unwrap_or("no output")
}

fn plural(n: u64, word: &str) -> String {
    format!("{n} {word}{}", if n == 1 { "" } else { "s" })
}

fn size(bytes: usize) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1 << 20 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1 << 20) as f64)
    }
}

/// Whether the folder has tests `wmpy test` runs: test.py, or tests/test_*.py.
fn has_tests(dir: &Path) -> bool {
    dir.join("test.py").is_file()
        || std::fs::read_dir(dir.join("tests")).into_iter().flatten().flatten().any(|e| {
            let n = e.file_name().to_string_lossy().into_owned();
            n.starts_with("test_") && n.ends_with(".py")
        })
}

/// What the checks found, for the summary and the issue's note.
struct Checked {
    warnings: u64,
    tests: Option<u64>,
}

/// `wmpy check`, then the tests, as the bot will run them. None when either failed (already reported).
fn run_checks(dir: &Path) -> Result<Checked, u8> {
    eprintln!("checking {} ...", dir.display());
    let check = step(&["check", "--json"], dir);
    let Some(report) = check.json else {
        eprintln!("error: `wmpy check` could not run: {}", first_line(&check.stderr));
        return Err(2);
    };
    if report.get("ok") != Some(&Json::Bool(true)) {
        // The same check again, for its code frames on the terminal.
        let _ = Command::new(wmpy()).arg("check").arg(dir).stdin(Stdio::null()).stdout(Stdio::null()).status();
        let n = report.get("errors").and_then(|e| e.as_u64()).unwrap_or(1);
        eprintln!("not sent: `wmpy check` found {}. Fix them, then run wmpy publish again.", plural(n, "error"));
        return Err(1);
    }
    let warnings = report.get("warnings").and_then(|w| w.as_u64()).unwrap_or(0);
    let mut tests = None;
    if has_tests(dir) {
        eprintln!("running the tests ...");
        let test = step(&["test", "--json"], dir);
        let Some(t) = test.json else {
            eprintln!("error: `wmpy test` could not run: {}", first_line(&test.stderr));
            return Err(if test.code == Some(1) { 1 } else { 2 });
        };
        let passed = t.get("passed").and_then(|p| p.as_u64()).unwrap_or(0);
        let failed = t.get("failed").and_then(|f| f.as_u64()).unwrap_or(0);
        if t.get("ok") != Some(&Json::Bool(true)) {
            for case in t.get("tests").and_then(|c| c.as_array()).into_iter().flatten().filter(|c| c.get("ok") != Some(&Json::Bool(true))) {
                let s = |v: &Json, k: &str| v.get(k).and_then(|x| x.as_str()).unwrap_or("").to_string();
                let error = case.get("error").cloned().unwrap_or(Json::Null);
                let line = error.get("line").and_then(|l| l.as_u64()).map(|l| format!(":{l}")).unwrap_or_default();
                eprintln!("  FAILED {}::{}: {} ({}{line})", s(case, "file"), s(case, "name"), s(&error, "message"), error.get("file").and_then(|f| f.as_str()).unwrap_or(&s(case, "file")));
            }
            eprintln!("not sent: {} failed, {} passed. `wmpy test` shows them in full.", failed.max(1), passed);
            return Err(1);
        }
        tests = Some(passed);
    }
    Ok(Checked { warnings, tests })
}

/// The `.wmplugin`, packed as `wmpy pack` packs it: its path, bytes and repository entry.
fn run_pack(dir: &Path) -> Result<(PathBuf, Vec<u8>, Json), u8> {
    let pack = step(&["pack", "--json", "--no-check"], dir);
    let Some(report) = pack.json else {
        eprintln!("error: `wmpy pack` could not run: {}", first_line(&pack.stderr));
        return Err(if pack.code == Some(1) { 1 } else { 2 });
    };
    let Some(path) = report.get("path").and_then(|p| p.as_str()).map(PathBuf::from) else {
        eprintln!("error: `wmpy pack` did not say where it wrote the archive");
        return Err(2);
    };
    match std::fs::read(&path) {
        Ok(bytes) => Ok((path, bytes, report.get("entry").cloned().unwrap_or(Json::Null))),
        Err(e) => {
            eprintln!("error: cannot read {}: {e}", path.display());
            Err(2)
        }
    }
}

/// The lines of the summary about what the plugin asks for.
fn asks(manifest: &Json) -> Vec<String> {
    let list = |k: &str| -> Vec<String> { manifest.get(k).and_then(|v| v.as_array()).into_iter().flatten().filter_map(|x| x.as_str()).map(String::from).collect() };
    let declared = list("permissions");
    let mut out = Vec::new();
    if declared.is_empty() {
        out.push("  permissions: none".to_string());
    } else {
        out.push("  permissions:".to_string());
        for p in &declared {
            match PERMISSIONS.iter().find(|(w, _, _)| w == p) {
                Some((w, true, label)) => out.push(format!("    ! {w:<16} risky   {label}; off until the user grants it")),
                Some((w, false, label)) => out.push(format!("      {w:<16} listed  {label}")),
                None => out.push(format!("    ? {p:<16} not a permission this wmpy knows")),
            }
        }
    }
    let hosts = list("hosts");
    if !hosts.is_empty() {
        out.push(format!("  hosts: {}", hosts.join(", ")));
    }
    if declared.iter().any(|p| READS.contains(&p.as_str())) && declared.iter().any(|p| EGRESS.contains(&p.as_str())) {
        out.push("  ! it can read what the user has and send data off the phone; the app warns about that pair, and so will the review".to_string());
    }
    out
}

fn confirm(question: &str) -> bool {
    eprint!("{question} [y/N] ");
    let mut answer = String::new();
    if std::io::stdin().lock().read_line(&mut answer).is_err() {
        return false;
    }
    if !std::io::stdin().is_terminal() {
        eprintln!();
    }
    matches!(answer.trim().to_lowercase().as_str(), "y" | "yes")
}

fn next_steps(id_hint: &str, updating: bool) {
    eprintln!();
    eprintln!("What happens next:");
    eprintln!("  1. Within a few minutes the bot posts one comment on the issue: wmpy check and the tests,");
    eprintln!("     the permissions and hosts, the code, and a link to try a trial copy on your phone.");
    eprintln!("  2. If it needs a fix, edit the issue (the pencil on the first post). Every edit is checked again.");
    eprintln!("  3. A maintainer reads the code and publishes it with /approve. It then shows in WM Keyboard");
    eprintln!("     under Settings > Addons > WM Keyboard Community.");
    if updating {
        eprintln!("  Later versions: raise pluginVersion and run `wmpy publish --update {id_hint}` again.");
    } else {
        eprintln!("  Later versions: raise pluginVersion and run `wmpy publish --update <id>`, with the id the bot gives it (likely {id_hint}).");
    }
}

pub fn run(args: Args) -> u8 {
    let dir = if args.dir.is_file() { args.dir.parent().map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from(".")) } else { args.dir.clone() };
    let manifest: Json = match std::fs::read_to_string(dir.join("plugin.json")).map_err(|e| e.to_string()).and_then(|t| serde_json::from_str(&t).map_err(|e| e.to_string())) {
        Ok(m) => m,
        Err(e) => {
            eprintln!("error: {} has no readable plugin.json ({e})", dir.display());
            eprintln!("  = help: run wmpy publish in the plugin's folder, or give the folder");
            return 2;
        }
    };
    let text = |k: &str| manifest.get(k).and_then(|v| v.as_str()).unwrap_or("").trim().to_string();
    let (plugin_id, name, version) = (text("id"), text("name"), text("pluginVersion"));

    let community = args.repo.is_none();
    let fields = Fields {
        name: name.clone(),
        description: args.description.clone().unwrap_or_else(|| text("description")),
        tags: args.tags.clone(),
        license: args.licence.clone().unwrap_or_else(|| "MIT".into()),
        updates: args.update.clone().unwrap_or_default(),
        based_on: args.based_on.clone().unwrap_or_default(),
    };
    if community {
        let problems = fields.problems();
        if !problems.is_empty() {
            for p in &problems {
                eprintln!("error: {p}");
            }
            return 2;
        }
        let parts: Vec<&str> = version.split('.').collect();
        if parts.len() != 3 || parts.iter().any(|p| p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit())) {
            eprintln!("error: pluginVersion is \"{version}\"; the community repository takes three numbers, such as 1.0.0");
            return 1;
        }
    } else if let Some(l) = &args.licence {
        if l.trim().is_empty() || l.contains(char::is_whitespace) {
            eprintln!("error: --licence takes an SPDX id such as MIT, not \"{l}\"");
            return 2;
        }
    }

    let checked = match run_checks(&dir) {
        Ok(c) => c,
        Err(code) => return code,
    };
    let (archive, bytes, entry) = match run_pack(&dir) {
        Ok(p) => p,
        Err(code) => return code,
    };

    if let Some(root) = &args.repo {
        let tags = fields.clean_tags();
        return repo::publish(
            root,
            &archive,
            &repo::Options { entry: &entry, id: args.update.as_deref(), licence: args.licence.as_deref(), tags: &tags, description: args.description.as_deref(), dry_run: args.dry_run },
        );
    }

    if bytes.len() > MAX_PLUGIN_BYTES {
        eprintln!("error: {} is {}; the app downloads at most 1 MB for a plugin. Move large data out of assets/, or fetch it with http.", archive.display(), size(bytes.len()));
        return 1;
    }
    let slug = std::env::var("WMPY_COMMUNITY_REPO").ok().filter(|s| !s.is_empty());
    let github_repo = args.github_repo.clone().or(slug).unwrap_or_else(|| COMMUNITY_REPO.into());
    let api = args.api_url.clone().or_else(|| std::env::var("WMPY_GITHUB_API").ok().filter(|s| !s.is_empty())).unwrap_or_else(|| GITHUB_API.into());
    let repo_url = format!("https://github.com/{github_repo}");
    let encoded = form::encode_payload(&bytes);
    // What the bot will read back; a mismatch would publish something else.
    if form::decode_payload(&encoded, MAX_PLUGIN_BYTES).as_deref() != Some(bytes.as_slice()) {
        eprintln!("error: the encoded plugin does not decode to the file; this is a bug in wmpy");
        return 2;
    }
    let tests = match checked.tests {
        Some(n) => format!("its tests pass ({n})"),
        None => "it has no tests".to_string(),
    };
    let note = format!(
        "Sent with wmpy {}: `wmpy check` found no errors{}, and {tests}. The bot checks it again.",
        env!("CARGO_PKG_VERSION"),
        if checked.warnings > 0 { format!(" ({})", plural(checked.warnings, "warning")) } else { String::new() }
    );
    let body = form::issue_body(&fields, &encoded, &note);
    let token = github::token();
    let through_api = token.is_some() && body.len() <= form::BODY_LIMIT;
    let tier = form::tier(&repo_url, &fields, &encoded);
    let id_hint = if fields.updates.is_empty() { form::slugify(&name) } else { fields.updates.clone() };

    // The summary.
    eprintln!();
    eprintln!("{name} {version} ({plugin_id}), {} packed", size(bytes.len()));
    eprintln!("  wmpy check: no errors{}; {tests}", if checked.warnings > 0 { format!(", {}", plural(checked.warnings, "warning")) } else { String::new() });
    for line in asks(&manifest) {
        eprintln!("{line}");
    }
    eprintln!();
    eprintln!("To the community repository, {repo_url}");
    if fields.updates.is_empty() {
        eprintln!("  a new plugin; the bot names it from its name ({id_hint})");
    } else {
        eprintln!("  a new version of {}", fields.updates);
    }
    let tags = fields.clean_tags();
    eprintln!("  licence {}{}", fields.license, if tags.is_empty() { String::new() } else { format!(", tags {}", tags.join(", ")) });
    eprintln!(
        "  {}",
        if through_api {
            "filed as an issue through the GitHub API, with your token".to_string()
        } else {
            match tier {
                Tier::Link => "opened in your browser as a filled-in form, for you to submit".to_string(),
                Tier::Paste => "opened in your browser as a form; the plugin goes on the clipboard, to paste".to_string(),
                Tier::Attach => "opened in your browser as a form; you drag in the plugin, saved as a .zip".to_string(),
            }
        }
    );

    if args.dry_run {
        eprintln!();
        eprintln!("dry run: nothing sent. The issue would be:");
        println!("{}", fields.title());
        println!();
        print!("{body}");
        if !through_api {
            println!();
            println!("{}", form::issue_url(&repo_url, &fields, if tier == Tier::Link { Some(&encoded) } else { None }));
        }
        return 0;
    }

    eprintln!();
    eprintln!("By sending it you confirm: {}", form::RIGHTS);
    if !args.yes && !confirm("Send it?") {
        eprintln!("not sent");
        return 1;
    }

    if let (true, Some(token)) = (through_api, token.as_deref()) {
        match github::create_issue(&api, &github_repo, token, &fields.title(), &body, &["submission", "type:plugin"]) {
            Ok(filed) => {
                println!("{}", filed.url);
                eprintln!("filed issue #{}: {}", filed.number, filed.url);
                next_steps(&id_hint, !fields.updates.is_empty());
                return 0;
            }
            Err(e) => eprintln!("could not file it through the API: {e}. Opening the form instead."),
        }
    }

    let url = form::issue_url(&repo_url, &fields, if tier == Tier::Link { Some(&encoded) } else { None });
    match tier {
        Tier::Link => {
            eprintln!("The form has everything filled in: check it, then submit it.");
        }
        Tier::Paste => {
            if github::copy_to_clipboard(&encoded) {
                eprintln!("The plugin is on the clipboard as a wmkb:1: text. Paste it into the Plugin box, then submit the form.");
            } else {
                let saved = archive.with_extension("wmkb.txt");
                if let Err(e) = std::fs::write(&saved, &encoded) {
                    eprintln!("error: cannot write {}: {e}", saved.display());
                    return 2;
                }
                eprintln!("No clipboard here: the wmkb:1: text is in {}. Paste all of it into the Plugin box, then submit the form.", saved.display());
            }
        }
        Tier::Attach => {
            let zip = archive.with_extension("zip");
            if let Err(e) = std::fs::write(&zip, &bytes) {
                eprintln!("error: cannot write {}: {e}", zip.display());
                return 2;
            }
            eprintln!("Drag {} into the Plugin box (GitHub takes a .zip, not a .wmplugin), then submit the form.", zip.display());
        }
    }
    if github::open_browser(&url) {
        eprintln!("opened the form in your browser");
    } else {
        eprintln!("open this link to the form:");
    }
    println!("{url}");
    next_steps(&id_hint, !fields.updates.is_empty());
    0
}
