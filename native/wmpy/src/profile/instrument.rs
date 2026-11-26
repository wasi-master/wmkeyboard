//! A plugin's Python with every `def` (methods and nested functions too)
//! wrapped by `wmpy profile`'s recorder, read with the tooling's parser.
//!
//! A function without decorators gets `@_wmpy_profile.wrap(key)` on the
//! blank or comment line above it when there is one, so the lines below
//! keep their numbers; otherwise the line is added and [Instrumented::lines]
//! maps it back. A decorated function has its innermost decorator `d`
//! turned into `_wmpy_profile.under(d, key)`, so what the decorators
//! register (an action, a keyword) is the wrapped function. The file gets
//! `import _wmpy_profile` at the top the same way.
//!
//! The recorder counts every call and adds up wall time (`time.monotonic()`)
//! around each outermost call of a function (a recursive call's time is
//! inside its caller's already). When the outermost wrapped call returns, it
//! sends what it gathered to the host: in a plugin's session as a
//! `plugin.refresh` call carrying `wmpyProfile`, which wmpy answers itself;
//! in a test's own interpreter through the `_wmpy_sink` module.

use std::collections::HashSet;

use wm_python_tooling::ast::{Stmt, StmtKind};
use wm_python_tooling::document::Document;
use wm_python_tooling::text::Span;

/// The recorder's module name, as plugins import it.
pub const MODULE: &str = "_wmpy_profile";

/// One function the recorder counts.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Site {
    pub key: usize,
    /// `render`, `Fares.total`, `outer.inner`.
    pub name: String,
    pub file: String,
    /// 1-based, in the original file.
    pub line: u32,
}

#[derive(Clone, Debug)]
pub struct Instrumented {
    pub source: String,
    /// For each line of [source] (index 0 is line 1), its line in the original.
    pub lines: Vec<u32>,
    pub sites: Vec<Site>,
}

impl Instrumented {
    /// The original line of [line] (1-based) of the instrumented file.
    pub fn original(&self, line: u32) -> u32 {
        self.lines.get(line.saturating_sub(1) as usize).copied().unwrap_or(line)
    }
}

/// A def found in the tree, and how to wrap it.
struct Def {
    name: String,
    /// 0-based line of `def`.
    line: usize,
    /// The innermost decorator's expression, when there are decorators.
    decorator: Option<Span>,
}

fn collect(stmts: &[Stmt], prefix: &str, units: &[u16], out: &mut Vec<Def>) {
    for (i, stmt) in stmts.iter().enumerate() {
        match &stmt.kind {
            StmtKind::Def { name, body, header, .. } => {
                let decorator = i
                    .checked_sub(1)
                    .and_then(|p| match &stmts[p].kind {
                        StmtKind::Unsupported { what: "decorators", exprs, .. } => exprs.first().map(|e| e.span),
                        _ => None,
                    });
                let qual = format!("{prefix}{name}");
                out.push(Def { name: qual.clone(), line: line_of(units, header.start), decorator });
                collect(body, &format!("{qual}."), units, out);
            }
            StmtKind::Unsupported { what: "class", binds, body, .. } => {
                let class = binds
                    .first()
                    .and_then(|b| match &b.kind {
                        wm_python_tooling::ast::ExprKind::Name(n) => Some(n.clone()),
                        _ => None,
                    })
                    .unwrap_or_else(|| "<class>".into());
                collect(body, &format!("{prefix}{class}."), units, out);
            }
            _ => {
                for block in wm_python_tooling::ast::stmt_blocks(stmt) {
                    collect(block, prefix, units, out);
                }
            }
        }
    }
}

/// 0-based line of the UTF-16 [offset].
fn line_of(units: &[u16], offset: u32) -> usize {
    units[..(offset as usize).min(units.len())].iter().filter(|u| **u == b'\n' as u16).count()
}

/// A line the recorder may take over: blank, or only a comment.
fn free(line: &str) -> bool {
    let t = line.trim();
    t.is_empty() || t.starts_with('#')
}

/// [source] of [file] with each def wrapped; keys are numbered from [next].
/// A file that does not parse is left as it is.
pub fn instrument(file: &str, source: &str, next: &mut usize) -> Instrumented {
    let doc = Document::new(source);
    let unchanged = || Instrumented { source: source.to_string(), lines: (1..=source.split('\n').count() as u32).collect(), sites: Vec::new() };
    if !doc.parses() {
        return unchanged();
    }
    let units: Vec<u16> = doc.units().to_vec();
    let mut defs = Vec::new();
    collect(&doc.module.body, "", &units, &mut defs);
    if defs.is_empty() {
        return unchanged();
    }
    let mut sites = Vec::new();
    // Decorated defs: their innermost decorator wrapped in place, within its own lines.
    let mut edits: Vec<(u32, String)> = Vec::new();
    let mut plain: Vec<(usize, usize)> = Vec::new();
    for d in &defs {
        let key = *next;
        *next += 1;
        sites.push(Site { key, name: d.name.clone(), file: file.to_string(), line: d.line as u32 + 1 });
        match d.decorator {
            Some(span) => {
                edits.push((span.start, format!("{MODULE}.under(")));
                edits.push((span.end, format!(", {key})")));
            }
            None => plain.push((d.line, key)),
        }
    }
    edits.sort_by(|a, b| b.0.cmp(&a.0));
    let mut units = units;
    for (at, text) in edits {
        let at = (at as usize).min(units.len());
        units.splice(at..at, text.encode_utf16());
    }
    let text = String::from_utf16_lossy(&units);
    let mut lines: Vec<String> = text.split('\n').map(String::from).collect();
    let mut used: HashSet<usize> = HashSet::new();
    let mut inserts: Vec<(usize, String)> = Vec::new();
    let import = format!("import {MODULE}");
    if free(&lines[0]) {
        lines[0] = import;
        used.insert(0);
    } else {
        inserts.push((0, import));
    }
    for (line, key) in plain {
        let indent: String = lines[line].chars().take_while(|c| *c == ' ' || *c == '\t').collect();
        let decorator = format!("{indent}@{MODULE}.wrap({key})");
        if line > 0 && !used.contains(&(line - 1)) && free(&lines[line - 1]) {
            lines[line - 1] = decorator;
            used.insert(line - 1);
        } else {
            inserts.push((line, decorator));
        }
    }
    inserts.sort_by_key(|(l, _)| *l);
    let mut out = Vec::with_capacity(lines.len() + inserts.len());
    let mut map = Vec::with_capacity(lines.len() + inserts.len());
    let mut pending = inserts.into_iter().peekable();
    for (i, line) in lines.into_iter().enumerate() {
        while let Some((_, added)) = pending.next_if(|(l, _)| *l == i) {
            out.push(added);
            map.push(i as u32 + 1);
        }
        out.push(line);
        map.push(i as u32 + 1);
    }
    Instrumented { source: out.join("\n"), lines: map, sites }
}

/// The recorder's source for a plugin's session ([sink] false), or for a
/// test's own interpreter, which has `_wmpy_sink`.
pub fn recorder(sink: bool) -> String {
    let (import, flush) = if sink { ("import _wmpy_sink", "_wmpy_sink.add(rows)") } else { ("", "_wm.call(\"plugin.refresh\", {\"wmpyProfile\": rows})") };
    RECORDER.replace("{IMPORT}", import).replace("{FLUSH}", flush)
}

const RECORDER: &str = r#""""wmpy profile's recorder: calls and wall time for each wrapped function.
wmpy adds this file to the copy it profiles; it is never in your plugin."""
import time
import _wm
{IMPORT}

_dirty = {}
_active = {}
_depth = [0]


def _flush():
    if not _dirty:
        return
    rows = [[key, _dirty[key][0], _dirty[key][1]] for key in _dirty]
    _dirty.clear()
    try:
        {FLUSH}
    except Exception:
        pass


def _wrap(fn, key):
    # The keyboard calls a callback with fewer arguments when it takes fewer.
    n = _wm.arity(fn)

    def profiled(*args, **kwargs):
        if n >= 0 and len(args) > n:
            args = args[:n]
        _depth[0] += 1
        _active[key] = _active.get(key, 0) + 1
        start = time.monotonic()
        try:
            return fn(*args, **kwargs)
        finally:
            took = time.monotonic() - start
            _active[key] -= 1
            s = _dirty.get(key)
            if s is None:
                s = [0, 0.0]
                _dirty[key] = s
            s[0] += 1
            if _active[key] == 0:
                s[1] += took
            _depth[0] -= 1
            if _depth[0] == 0:
                _flush()

    return profiled


def wrap(key):
    def decorate(fn):
        return _wrap(fn, key)

    return decorate


def under(decorator, key):
    def decorate(fn):
        return decorator(_wrap(fn, key))

    return decorate
"#;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_defs_take_the_line_above_and_keep_numbers() {
        let src = "# helpers\nfrom wm import ui\n\ndef render():\n    ui.label(total([1, 2]))\n\ndef total(xs):\n    return sum(xs)\n";
        let mut next = 0;
        let i = instrument("main.py", src, &mut next);
        assert_eq!(i.source.lines().count(), src.lines().count(), "{}", i.source);
        assert!(i.source.starts_with("import _wmpy_profile\nfrom wm import ui\n@_wmpy_profile.wrap(0)\ndef render():"), "{}", i.source);
        assert!(i.source.contains("@_wmpy_profile.wrap(1)\ndef total(xs):"), "{}", i.source);
        assert_eq!(i.sites[1], Site { key: 1, name: "total".into(), file: "main.py".into(), line: 7 });
        assert_eq!(i.original(7), 7);
    }

    #[test]
    fn methods_nested_defs_and_decorators() {
        let src = "from wm import plugin\nclass Fares:\n    def total(self, xs):\n        def add(a, b):\n            return a + b\n        return add(xs[0], xs[1])\n@plugin.action(\"up\")\ndef up(text):\n    return text.upper()\n";
        let mut next = 5;
        let i = instrument("fares.py", src, &mut next);
        let names: Vec<&str> = i.sites.iter().map(|s| s.name.as_str()).collect();
        assert_eq!(names, vec!["Fares.total", "Fares.total.add", "up"]);
        assert_eq!(next, 8);
        assert!(i.source.contains("@_wmpy_profile.under(plugin.action(\"up\"), 7)\ndef up(text):"), "{}", i.source);
        // No free line above the method or the nested def: lines were added and map back.
        assert_eq!(i.source.lines().count(), src.lines().count() + 3, "{}", i.source);
        let def_add = i.source.lines().position(|l| l.trim_start().starts_with("def add")).unwrap() as u32 + 1;
        assert_eq!(i.original(def_add), 4);
        assert!(wm_python::interp::check(&i.source).is_ok(), "{}", i.source);
    }

    #[test]
    fn a_file_that_does_not_parse_is_left_alone() {
        let mut next = 0;
        let i = instrument("main.py", "def render(:\n", &mut next);
        assert_eq!(i.source, "def render(:\n");
        assert!(i.sites.is_empty());
    }
}
