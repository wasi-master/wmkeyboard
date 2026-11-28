//! `wmpy permissions`: each permission plugin.json declares or the code
//! needs, with the calls that need it, and each host with the addresses that
//! reach it. The analysis is [wm_python_tooling::usage], which the language
//! server and the MCP server can read too.
//!
//! Exit 1 when the code needs a permission or a host plugin.json leaves
//! out (the call fails on the phone), or with `--strict` when plugin.json
//! declares one nothing uses.

use std::path::PathBuf;

use serde_json::json;
use wm_python_tooling::manifest as codec;
use wm_python_tooling::usage::{self, Address, HostVerdict, Report, Source, Use, Verdict};

use crate::diag::color_stderr;
use crate::project;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// The plugin folder
    #[arg(default_value = ".")]
    pub dir: PathBuf,
    /// Print the report as JSON on stdout
    #[arg(long)]
    pub json: bool,
    /// Fail on a permission or host nothing uses, too
    #[arg(long)]
    pub strict: bool,
}

/// Uses listed under a row before "and N more".
const SHOWN: usize = 6;

pub fn run(args: Args) -> u8 {
    let dir = match project::plugin_dir(&args.dir) {
        Ok(d) => d,
        Err(d) => {
            if args.json {
                println!("{}", json!({"ok": false, "error": d.to_json()}));
            } else {
                eprint!("{}", d.render(&|_| None, color_stderr()));
            }
            return 2;
        }
    };
    let manifest = std::fs::read_to_string(dir.join(codec::MANIFEST)).unwrap_or_default();
    let sources = crate::check::own_sources(&dir);
    let files: Vec<Source> = sources.iter().map(|(name, text, entry)| Source { name, text, entry: *entry }).collect();
    let report = usage::analyse(&manifest, &files);
    if args.json {
        println!("{}", report.json());
    } else {
        print!("{}", render(&report, color_stdout()));
    }
    let failed = report.errors() > 0 || (args.strict && report.suggestions() > 0);
    u8::from(failed)
}

fn color_stdout() -> bool {
    crate::output::stdout()
}

fn tier(known: bool, risky: bool) -> &'static str {
    match (known, risky) {
        (false, _) => "unknown",
        (true, true) => "risky",
        (true, false) => "listed",
    }
}

fn place(u: &Use) -> String {
    format!("{}:{}", u.file, u.line)
}

fn address_place(a: &Address) -> String {
    format!("{}:{}", a.file, a.line)
}

/// The report as tables, the way a person reads it.
pub fn render(r: &Report, color: bool) -> String {
    let paint = |code: &str, text: &str| if color { format!("\x1b[{code}m{text}\x1b[0m") } else { text.to_string() };
    let mut out = String::new();
    let mut line = |s: String| {
        out.push_str(&s);
        out.push('\n');
    };

    // ---- permissions ----
    if r.permissions.is_empty() {
        line("permissions: plugin.json declares none, and the code needs none".into());
    } else {
        let width = r.permissions.iter().map(|p| p.name.len()).max().unwrap_or(10).max(10);
        line(paint("1", &format!("{:<width$}  {:<7}  {:<8}  verdict", "permission", "tier", "declared")));
        for p in &r.permissions {
            let (word, colour, why) = match p.verdict {
                Verdict::Needed => ("needed", "32", String::new()),
                Verdict::ImportedOnly if p.declared => ("unused", "33", ": imported, never called; remove the import and the permission".into()),
                Verdict::ImportedOnly => ("not needed", "36", ": imported, never called; remove the import".into()),
                Verdict::Unused => ("unused", "33", ": nothing uses it; remove it from plugin.json".into()),
                Verdict::Missing => ("missing", "31", format!(": add \"{}\" to plugin.json's permissions, or these calls fail", p.name)),
                Verdict::Unknown => ("unknown", "31", ": the app does not know it (wmpy check says more)".into()),
            };
            let declared = match (p.declared, p.declared_line) {
                (true, Some(l)) => format!("line {l}"),
                (true, None) => "yes".into(),
                (false, _) => "no".into(),
            };
            line(format!("{:<width$}  {:<7}  {:<8}  {}{why}", p.name, tier(p.known, p.risky), declared, paint(colour, word)));
            for u in p.uses.iter().take(SHOWN) {
                line(format!("    {:<16} {}", place(u), u.what));
            }
            if p.uses.len() > SHOWN {
                line(format!("    and {} more", p.uses.len() - SHOWN));
            }
        }
    }
    line(String::new());

    // ---- hosts ----
    let h = &r.hosts;
    if h.declared.is_empty() && h.missing.is_empty() && h.unlisted.is_empty() {
        line(match &h.host_setting {
            Some(key) => format!("hosts: none listed; the host setting \"{key}\" names the server"),
            None if h.dynamic.is_empty() => "hosts: plugin.json lists none, and no request names one".into(),
            None => "hosts: plugin.json lists none".into(),
        });
    } else {
        let names = h.declared.iter().map(|d| d.entry.len()).chain(h.missing.iter().chain(&h.unlisted).map(|m| m.host.len()));
        let width = names.max().unwrap_or(4).max(4);
        line(paint("1", &format!("{:<width$}  {:<8}  verdict", "host", "declared")));
        let uses = |list: &[Address], line: &mut dyn FnMut(String)| {
            for a in list.iter().take(SHOWN) {
                let via = if a.call.is_empty() { String::new() } else { format!("{}  ", a.call) };
                line(format!("    {:<16} {via}{}", address_place(a), a.url));
            }
            if list.len() > SHOWN {
                line(format!("    and {} more", list.len() - SHOWN));
            }
        };
        for d in &h.declared {
            let (word, colour, why) = match &d.verdict {
                HostVerdict::Used => ("used", "32", String::new()),
                HostVerdict::Mentioned => ("mentioned", "32", ": an address in the code names it, though no request takes it straight".into()),
                HostVerdict::Unknown => ("unknown", "36", ": no address names it, but some requests build theirs at run time".into()),
                HostVerdict::Unused => ("unused", "33", ": nothing reaches it; remove it from plugin.json".into()),
                HostVerdict::Covered(by) => ("covered", "33", format!(": \"{by}\" already covers it; remove this one")),
            };
            let declared = d.line.map_or("yes".to_string(), |l| format!("line {l}"));
            line(format!("{:<width$}  {:<8}  {}{why}", d.entry, declared, paint(colour, word)));
            uses(&d.uses, &mut line);
        }
        for m in &h.missing {
            line(format!("{:<width$}  {:<8}  {}: add \"{}\" to plugin.json's hosts, or these requests are refused", m.host, "no", paint("31", "missing"), m.host));
            uses(&m.uses, &mut line);
        }
        for m in &h.unlisted {
            line(format!("{:<width$}  {:<8}  {}: written in the code, not requested straight; add it if a request reaches it", m.host, "no", paint("36", "not listed")));
            uses(&m.uses, &mut line);
        }
        if let Some(key) = &h.host_setting {
            line(format!("the host setting \"{key}\" lets the user name any server, so no host is missing"));
        }
    }
    if !h.dynamic.is_empty() {
        let n = h.dynamic.len();
        line(format!("{} build{} the address at run time, so its host is not checked:", if n == 1 { "1 request".to_string() } else { format!("{n} requests") }, if n == 1 { "s" } else { "" }));
        for u in h.dynamic.iter().take(SHOWN) {
            line(format!("    {:<16} {}", place(u), u.what));
        }
        if n > SHOWN {
            line(format!("    and {} more", n - SHOWN));
        }
    }
    line(String::new());
    let errors = r.errors();
    let suggestions = r.suggestions();
    line(match (errors, suggestions) {
        (0, 0) => "plugin.json asks for what the code needs, and no more".to_string(),
        (0, s) => format!("nothing missing; {} plugin.json could drop", if s == 1 { "1 entry".to_string() } else { format!("{s} entries") }),
        (e, s) => format!("{} missing; {} plugin.json could drop", if e == 1 { "1 entry".to_string() } else { format!("{e} entries") }, s),
    });
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_table_names_each_verdict() {
        let manifest = r#"{"format": "wmkeyboard-plugin", "id": "a.b.c", "name": "A", "pluginVersion": "1", "apiVersion": 5,
  "permissions": ["network", "storage"],
  "hosts": ["api.example.com", "old.example.com"]}"#;
        let main = "from wm import http, clipboard\n\n\ndef render():\n    http.get(\"https://api.example.com/x\")\n    clipboard.get()\n";
        let r = usage::analyse(manifest, &[Source { name: "main.py", text: main, entry: true }]);
        let text = render(&r, false);
        let row = |first: &str| -> Vec<String> {
            let line = text.lines().find(|l| l.split_whitespace().next() == Some(first)).unwrap_or_else(|| panic!("no {first} row:\n{text}"));
            line.split_whitespace().take(5).map(|w| w.trim_end_matches(':').to_string()).collect()
        };
        assert_eq!(row("network"), ["network", "risky", "line", "2", "needed"], "{text}");
        assert_eq!(row("storage"), ["storage", "listed", "line", "2", "unused"], "{text}");
        assert_eq!(row("clipboard.read"), ["clipboard.read", "risky", "no", "missing", "add"], "{text}");
        assert_eq!(row("old.example.com"), ["old.example.com", "line", "3", "unused", "nothing"], "{text}");
        assert!(text.contains("    main.py:6        clipboard.get"), "{text}");
        assert!(text.contains("main.py:5        http.get  https://api.example.com/x"), "{text}");
        assert!(text.ends_with("1 entry missing; 2 plugin.json could drop\n"), "{text}");
    }
}
