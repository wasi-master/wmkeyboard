//! `wmpy fmt`: the plugin's Python files through the formatter the editors'
//! Format uses ([wm_python_tooling::format]), which changes whitespace only.
//!
//! With no paths it formats every `.py` file of the plugin in the folder you
//! are in: the entry script and its modules. Tests (`test.py`, `tests/`) join
//! with `--all` or when named; packages `wmpy pip` vendored are never
//! formatted unless named. A path outside a plugin folder is refused.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use serde_json::json;
use wm_python_tooling::manifest as codec;

use crate::diag::{color_stderr, Diag};
use crate::project;

#[derive(clap::Args, Debug)]
pub struct Args {
    /// Plugin folders, folders inside one, or .py files (the folder you are in when left out)
    pub paths: Vec<PathBuf>,
    /// Change nothing; exit with 1 and list the files that need formatting
    #[arg(long)]
    pub check: bool,
    /// Change nothing; print what formatting would change as a unified diff
    #[arg(long)]
    pub diff: bool,
    /// Format the tests too: test.py and everything under tests/
    #[arg(long)]
    pub all: bool,
    /// Print {"ok", "checked", "changed", "files"} as JSON on stdout
    #[arg(long)]
    pub json: bool,
}

/// One file to format: the plugin folder it is in, and its path there.
#[derive(Clone, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct Target {
    root: PathBuf,
    rel: String,
}

impl Target {
    fn path(&self) -> PathBuf {
        self.root.join(&self.rel)
    }

    /// As people read it: `main.py` in the folder they are in, else with the folder.
    fn shown(&self) -> String {
        if self.root == Path::new(".") { self.rel.clone() } else { self.path().display().to_string() }
    }
}

pub fn run(args: Args) -> u8 {
    let color = color_stderr();
    let paths = if args.paths.is_empty() { vec![PathBuf::from(".")] } else { args.paths.clone() };
    let mut targets: BTreeSet<Target> = BTreeSet::new();
    for p in &paths {
        match targets_of(p, args.all) {
            Ok(found) => targets.extend(found),
            Err(d) => {
                if args.json {
                    println!("{}", json!({"ok": false, "error": d.to_json()}));
                } else {
                    eprint!("{}", d.render(&|_| None, color));
                }
                return 2;
            }
        }
    }

    let write = !args.check && !args.diff;
    let mut files = Vec::new();
    let mut changed: Vec<String> = Vec::new();
    let mut failed = false;
    for t in &targets {
        let text = match std::fs::read_to_string(t.path()) {
            Ok(text) => text,
            Err(e) => {
                let mut d = Diag::error(format!("cannot read {}: {e}", t.shown()));
                d.help = Some("wmpy fmt reads UTF-8 text files".into());
                eprint!("{}", d.render(&|_| None, color));
                failed = true;
                continue;
            }
        };
        let formatted = wm_python_tooling::format::format(&text);
        // The formatter never empties a file that has code in it; if it did, keep the file.
        let same = formatted == text || (formatted.is_empty() && !text.trim().is_empty());
        let shown = t.shown();
        let diff = if same || !args.diff { String::new() } else { unified_diff(&text, &formatted, &t.rel) };
        if !same {
            changed.push(shown.clone());
            if write {
                if let Err(e) = std::fs::write(t.path(), &formatted) {
                    eprintln!("cannot write {shown}: {e}");
                    failed = true;
                    continue;
                }
                if !args.json {
                    eprintln!("formatted {shown}");
                }
            } else if args.diff && !args.json {
                print!("{diff}");
            } else if !args.json {
                eprintln!("would format {shown}");
            }
        }
        let mut entry = json!({"file": shown, "changed": !same, "written": write && !same});
        if args.diff && !same {
            entry["diff"] = json!(diff);
        }
        files.push(entry);
    }

    let checked = targets.len();
    if args.json {
        println!("{}", json!({"ok": !failed && (write || changed.is_empty()), "checked": checked, "changed": changed, "files": files}));
    } else {
        let count = |n: usize| if n == 1 { "1 file".to_string() } else { format!("{n} files") };
        let rest = checked - changed.len();
        let line = match (write, changed.len()) {
            (_, 0) if checked == 0 => "no Python files to format".to_string(),
            (_, 0) => format!("{} already formatted", count(checked)),
            (true, n) => format!("formatted {}, {rest} already formatted", count(n)),
            (false, n) => format!("{} would be formatted, {rest} already formatted", count(n)),
        };
        eprintln!("{line}");
    }
    if failed {
        2
    } else {
        u8::from(!write && !changed.is_empty())
    }
}

/// The plugin folder [path] is in or is: the nearest folder at or above it with plugin.json.
fn plugin_root(path: &Path) -> Option<PathBuf> {
    let start = if path.is_file() { path.parent().map(Path::to_path_buf).unwrap_or_default() } else { path.to_path_buf() };
    let start = if start.as_os_str().is_empty() { PathBuf::from(".") } else { start };
    let absolute = start.canonicalize().ok()?;
    let mut at = Some(absolute.as_path());
    let mut ups = 0;
    while let Some(dir) = at {
        if dir.join(codec::MANIFEST).is_file() {
            // Keep the path the way it was given: `.`, `my-tool`, `../my-tool`.
            let mut root = start.clone();
            for _ in 0..ups {
                root = root.join("..");
            }
            return Some(normalise(&root));
        }
        at = dir.parent();
        ups += 1;
    }
    None
}

/// `a/b/..` as `a`, `./x` as `x`, leaving a leading `..` alone.
fn normalise(path: &Path) -> PathBuf {
    let mut parts: Vec<std::path::Component> = Vec::new();
    for c in path.components() {
        match c {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir if matches!(parts.last(), Some(std::path::Component::Normal(_))) => {
                parts.pop();
            }
            other => parts.push(other),
        }
    }
    let out: PathBuf = parts.iter().collect();
    if out.as_os_str().is_empty() { PathBuf::from(".") } else { out }
}

fn is_test(rel: &str) -> bool {
    rel == "test.py" || rel.starts_with("tests/")
}

/// The files [path] names, or why it names none wmpy may touch.
fn targets_of(path: &Path, all: bool) -> Result<Vec<Target>, Diag> {
    if !path.exists() {
        return Err(Diag::error(format!("there is no {}", path.display())));
    }
    let Some(root) = plugin_root(path) else {
        let mut d = Diag::error(format!("{} is not in a plugin folder", path.display()));
        d.help = Some("wmpy fmt formats the Python files of a plugin: give its folder, the one that holds plugin.json, or a file in it".into());
        return Err(d);
    };
    let canon_root = root.canonicalize().map_err(|e| Diag::error(format!("{}: {e}", root.display())))?;
    let canon = path.canonicalize().map_err(|e| Diag::error(format!("{}: {e}", path.display())))?;
    let rel = canon.strip_prefix(&canon_root).map(|r| r.to_string_lossy().replace('\\', "/")).unwrap_or_default();
    if path.is_file() {
        if !rel.ends_with(".py") {
            return Err(Diag::error(format!("{} is not a Python file", path.display())));
        }
        project::inside(&root, &rel).map_err(Diag::error)?;
        return Ok(vec![Target { root, rel }]);
    }
    let whole = rel.is_empty();
    let vendored = crate::pip::lock::vendored_roots(&root);
    let is_vendored = |f: &str| {
        let top = f.split('/').next().unwrap_or(f);
        let top = top.strip_suffix(".py").unwrap_or(top);
        vendored.iter().any(|r| r == top)
    };
    let prefix = if whole { String::new() } else { format!("{rel}/") };
    // Tests join when asked for, or when the folder given is tests/ or inside it.
    let tests_given = all || is_test(&prefix);
    let mut out = Vec::new();
    for f in project::files(&root) {
        if !f.ends_with(".py") || !f.starts_with(&prefix) || f.ends_with(".pyi") {
            continue;
        }
        if is_test(&f) && !tests_given {
            continue;
        }
        if whole && is_vendored(&f) {
            continue;
        }
        // A symlink that leads out of the folder is not the plugin's to change.
        if project::inside(&root, &f).is_err() {
            continue;
        }
        out.push(Target { root: root.clone(), rel: f });
    }
    Ok(out)
}

// ---- unified diffs ------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Op {
    /// Old line, new line.
    Same(usize, usize),
    /// Old line, and how many new lines come before it.
    Gone(usize, usize),
    /// How many old lines come before it, and the new line.
    Added(usize, usize),
}

/// [old] against [new] as a unified diff with three lines of context, both
/// named [name] (`--- a/main.py`, `+++ b/main.py`). Empty when they are the same.
pub fn unified_diff(old: &str, new: &str, name: &str) -> String {
    if old == new {
        return String::new();
    }
    let a: Vec<&str> = old.split_inclusive('\n').collect();
    let b: Vec<&str> = new.split_inclusive('\n').collect();
    let ops = diff(&a, &b);
    let mut out = format!("--- a/{name}\n+++ b/{name}\n");
    const CONTEXT: usize = 3;
    let mut i = 0;
    let mut done = 0;
    while i < ops.len() {
        if matches!(ops[i], Op::Same(..)) {
            i += 1;
            continue;
        }
        let start = i.saturating_sub(CONTEXT).max(done);
        let mut last = i;
        let mut j = i;
        while j < ops.len() {
            if !matches!(ops[j], Op::Same(..)) {
                last = j;
            } else if j - last > 2 * CONTEXT {
                break;
            }
            j += 1;
        }
        let end = (last + CONTEXT + 1).min(ops.len());
        let hunk = &ops[start..end];
        let (old_at, new_at) = match hunk[0] {
            Op::Same(o, n) | Op::Gone(o, n) | Op::Added(o, n) => (o, n),
        };
        let old_len = hunk.iter().filter(|o| !matches!(o, Op::Added(..))).count();
        let new_len = hunk.iter().filter(|o| !matches!(o, Op::Gone(..))).count();
        let range = |at: usize, len: usize| match len {
            0 => format!("{at},0"),
            1 => format!("{}", at + 1),
            n => format!("{},{n}", at + 1),
        };
        out.push_str(&format!("@@ -{} +{} @@\n", range(old_at, old_len), range(new_at, new_len)));
        for op in hunk {
            let (mark, line) = match *op {
                Op::Same(o, _) => (' ', a[o]),
                Op::Gone(o, _) => ('-', a[o]),
                Op::Added(_, n) => ('+', b[n]),
            };
            out.push(mark);
            match line.strip_suffix('\n') {
                Some(text) => {
                    out.push_str(text);
                    out.push('\n');
                }
                None => {
                    out.push_str(line);
                    out.push_str("\n\\ No newline at end of file\n");
                }
            }
        }
        done = end;
        i = end;
    }
    out
}

/// The shortest edit script from [a] to [b]: Myers' algorithm in linear space.
fn diff(a: &[&str], b: &[&str]) -> Vec<Op> {
    let mut out = Vec::with_capacity(a.len().max(b.len()));
    let size = 2 * (a.len() + b.len()) + 4;
    let mut vf = vec![0isize; size];
    let mut vb = vec![0isize; size];
    conquer(a, 0, a.len(), b, 0, b.len(), &mut vf, &mut vb, &mut out);
    tidy(out)
}

/// Each run of changes as its removed lines, then its added ones, the way
/// diffs are read, with each op's place counted again.
fn tidy(ops: Vec<Op>) -> Vec<Op> {
    let mut out = Vec::with_capacity(ops.len());
    let (mut gone, mut added) = (Vec::new(), Vec::new());
    for op in ops {
        match op {
            Op::Same(..) => {
                out.append(&mut gone);
                out.append(&mut added);
                out.push(op);
            }
            Op::Gone(..) => gone.push(op),
            Op::Added(..) => added.push(op),
        }
    }
    out.append(&mut gone);
    out.append(&mut added);
    let (mut o, mut n) = (0, 0);
    for op in out.iter_mut() {
        *op = match *op {
            Op::Same(..) => {
                o += 1;
                n += 1;
                Op::Same(o - 1, n - 1)
            }
            Op::Gone(..) => {
                o += 1;
                Op::Gone(o - 1, n)
            }
            Op::Added(..) => {
                n += 1;
                Op::Added(o, n - 1)
            }
        };
    }
    out
}

#[allow(clippy::too_many_arguments)]
fn conquer(a: &[&str], mut a0: usize, mut a1: usize, b: &[&str], mut b0: usize, mut b1: usize, vf: &mut [isize], vb: &mut [isize], out: &mut Vec<Op>) {
    while a0 < a1 && b0 < b1 && a[a0] == b[b0] {
        out.push(Op::Same(a0, b0));
        a0 += 1;
        b0 += 1;
    }
    let mut tail = 0;
    while a1 > a0 && b1 > b0 && a[a1 - 1] == b[b1 - 1] {
        a1 -= 1;
        b1 -= 1;
        tail += 1;
    }
    if a0 == a1 {
        out.extend((b0..b1).map(|n| Op::Added(a0, n)));
    } else if b0 == b1 {
        out.extend((a0..a1).map(|o| Op::Gone(o, b0)));
    } else {
        let (x, y) = middle_snake(a, a0, a1, b, b0, b1, vf, vb);
        conquer(a, a0, x, b, b0, y, vf, vb, out);
        conquer(a, x, a1, b, y, b1, vf, vb, out);
    }
    out.extend((0..tail).map(|k| Op::Same(a1 + k, b1 + k)));
}

/// Where a shortest path from (a0, b0) to (a1, b1) crosses its middle: the
/// point to split the problem at. Both ranges are non-empty and differ at
/// their first and last lines.
#[allow(clippy::too_many_arguments)]
fn middle_snake(a: &[&str], a0: usize, a1: usize, b: &[&str], b0: usize, b1: usize, vf: &mut [isize], vb: &mut [isize]) -> (usize, usize) {
    let n = (a1 - a0) as isize;
    let m = (b1 - b0) as isize;
    let delta = n - m;
    let odd = delta & 1 == 1;
    let offset = (n + m + 1) as usize;
    let at = |k: isize| (k + offset as isize) as usize;
    let max = (n + m + 1) / 2;
    vf[at(1)] = 0;
    vb[at(1)] = 0;
    for d in 0..=max {
        let mut k = -d;
        while k <= d {
            let mut x = if k == -d || (k != d && vf[at(k - 1)] < vf[at(k + 1)]) { vf[at(k + 1)] } else { vf[at(k - 1)] + 1 };
            let mut y = x - k;
            let (x0, y0) = (x, y);
            while x < n && y < m && a[a0 + x as usize] == b[b0 + y as usize] {
                x += 1;
                y += 1;
            }
            vf[at(k)] = x;
            let c = k - delta;
            if odd && c >= -(d - 1) && c <= d - 1 && vf[at(k)] + vb[at(-c)] >= n {
                return (a0 + x0 as usize, b0 + y0 as usize);
            }
            k += 2;
        }
        let mut k = -d;
        while k <= d {
            let mut x = if k == -d || (k != d && vb[at(k - 1)] < vb[at(k + 1)]) { vb[at(k + 1)] } else { vb[at(k - 1)] + 1 };
            let mut y = x - k;
            while x < n && y < m && a[a1 - 1 - x as usize] == b[b1 - 1 - y as usize] {
                x += 1;
                y += 1;
            }
            vb[at(k)] = x;
            let c = k - delta;
            if !odd && c >= -d && c <= d && vb[at(k)] + vf[at(-c)] >= n {
                return (a1 - x as usize, b1 - y as usize);
            }
            k += 2;
        }
    }
    // Not reached for two non-empty ranges; split in the middle to be safe.
    (a0 + (n as usize) / 2, b0 + (m as usize) / 2)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// [old] with [diff] applied, as `patch` would.
    fn apply(old: &str, diff: &str) -> String {
        let a: Vec<&str> = old.split_inclusive('\n').collect();
        let mut out = String::new();
        let mut at = 0;
        let lines: Vec<&str> = diff.split_inclusive('\n').collect();
        let mut i = 2;
        while i < lines.len() {
            let head = lines[i];
            let old_start: usize = head[4..].split([',', ' ']).next().unwrap().parse().unwrap();
            let old_len_zero = head.split(' ').nth(1).unwrap().ends_with(",0");
            let start = if old_len_zero { old_start } else { old_start - 1 };
            while at < start {
                out.push_str(a[at]);
                at += 1;
            }
            i += 1;
            while i < lines.len() && !lines[i].starts_with("@@") {
                let l = lines[i];
                let no_newline = lines.get(i + 1).is_some_and(|n| n.starts_with('\\'));
                let body = if no_newline { l.strip_suffix('\n').unwrap() } else { &l[1..] };
                let body = if no_newline { &body[1..] } else { body };
                match l.as_bytes()[0] {
                    b' ' => {
                        out.push_str(body);
                        at += 1;
                    }
                    b'-' => at += 1,
                    b'+' => out.push_str(body),
                    _ => {}
                }
                i += if no_newline { 2 } else { 1 };
            }
        }
        while at < a.len() {
            out.push_str(a[at]);
            at += 1;
        }
        out
    }

    #[test]
    fn a_diff_applies_back() {
        let cases = [
            ("a\nb\nc\n", "a\nB\nc\n"),
            ("a\nb\nc\n", "a\nc\n"),
            ("a\nc\n", "x\na\nb\nc\ny\n"),
            ("", "x\n"),
            ("x", "x\n"),
            ("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15\n16\n", "1\n2x\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n13\n14\n15x\n16\n"),
            ("def f():\n\treturn 1\n\n\n\n\nx=1\n", "def f():\n    return 1\n\n\nx = 1\n"),
        ];
        for (old, new) in cases {
            let d = unified_diff(old, new, "main.py");
            assert!(d.starts_with("--- a/main.py\n+++ b/main.py\n@@ -"), "{d}");
            assert_eq!(apply(old, &d), new, "{old:?} -> {new:?}:\n{d}");
        }
        assert_eq!(unified_diff("same\n", "same\n", "main.py"), "");
    }

    #[test]
    fn far_apart_changes_make_two_hunks() {
        let old: String = (1..=20).map(|i| format!("{i}\n")).collect();
        let new: String = (1..=20).map(|i| match i {
            2 => "two\n".to_string(),
            19 => "nineteen\n".to_string(),
            i => format!("{i}\n"),
        }).collect();
        let d = unified_diff(&old, &new, "m.py");
        assert_eq!(d.matches("@@ -").count(), 2, "{d}");
        assert!(d.contains("@@ -16,5 +16,5 @@\n 16\n 17\n 18\n-19\n+nineteen\n 20\n"), "{d}");
        assert!(d.contains("@@ -1,5 +1,5 @@\n 1\n-2\n+two\n 3\n 4\n 5\n"), "{d}");
    }

    #[test]
    fn removed_lines_come_before_added_ones() {
        let d = unified_diff("from wm import ui\n\nx = 1\n", "from wm import ui, http\nimport math\n\nx = 1\n", "main.py");
        assert!(d.contains("@@ -1,3 +1,4 @@\n-from wm import ui\n+from wm import ui, http\n+import math\n \n x = 1\n"), "{d}");
    }

    #[test]
    fn the_script_is_the_shortest() {
        let a: Vec<&str> = "a b c a b b a".split(' ').collect();
        let b: Vec<&str> = "c b a b a c".split(' ').collect();
        let ops = diff(&a, &b);
        let edits = ops.iter().filter(|o| !matches!(o, Op::Same(..))).count();
        assert_eq!(edits, 5, "{ops:?}");
    }
}
