//! A package's data files (JSON schemas, word lists, templates, images) as
//! Python modules. A `.wmplugin` holds only Python modules, assets and
//! translations, so the files a package keeps beside its modules travel
//! inside a generated module of that package, `<package>/_wmdata.py`, as
//! string constants. The plugin interpreter's `open()`, `os.stat`,
//! `os.listdir`, `pathlib`, `importlib.resources` and `pkgutil.get_data`
//! read `/plugin/<package>/<file>` from there (its `_plugin_data` module).
//!
//! `wmpy pip` and the web editor both call [modules], so the two write the
//! same files.

use std::collections::{BTreeMap, BTreeSet};

use base64::engine::general_purpose::STANDARD;
use base64::Engine as _;

/// The generated module's name, inside the package: `_wmdata`, then
/// `_wmdata_2`, `_wmdata_3` when one module would be too big.
pub const MODULE: &str = "_wmdata";

/// The most a package's data may take once written out, over all its modules.
pub const MAX_PACKAGE_DATA: usize = 2 << 20;

/// The most one generated module holds, under the keyboard's 1 MB per `.py` file.
const MAX_MODULE: usize = 960 * 1024;

/// What [modules] made.
#[derive(Clone, Debug, Default)]
pub struct DataModules {
    /// The generated modules, by path: `jsonschema_specifications/_wmdata.py`.
    pub modules: BTreeMap<String, String>,
    /// How many data files went in.
    pub kept: usize,
    /// What was left out, and why, one line each.
    pub notes: Vec<String>,
}

/// Whether [path] is a module [modules] makes: `<package>/_wmdata.py` or `<package>/_wmdata_<n>.py`.
pub fn is_data_module(path: &str) -> bool {
    let Some((dir, file)) = path.rsplit_once('/') else { return false };
    if dir.is_empty() {
        return false;
    }
    let Some(stem) = file.strip_suffix(".py") else { return false };
    stem == MODULE || stem.strip_prefix(MODULE).and_then(|r| r.strip_prefix('_')).is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()))
}

/// The modules that carry [data] (path to bytes, the package's own paths:
/// `jsonschema_specifications/schemas/draft7/metaschema.json`). A file goes
/// with the top-level package its path starts in, when [packages] names it
/// (a folder of the package's modules); a file outside any package, a
/// package whose data would pass [MAX_PACKAGE_DATA], and a file too big for
/// one module are left out, and the notes say so.
pub fn modules(data: &BTreeMap<String, Vec<u8>>, packages: &BTreeSet<String>) -> DataModules {
    let mut out = DataModules::default();
    let mut by_root: BTreeMap<&str, Vec<(&str, Entry)>> = BTreeMap::new();
    let mut outside = 0usize;
    for (path, bytes) in data {
        let Some((root, rel)) = path.split_once('/') else {
            outside += 1;
            continue;
        };
        if !packages.contains(root) || rel.is_empty() || is_data_module(path) {
            outside += 1;
            continue;
        }
        by_root.entry(root).or_default().push((rel, Entry::of(bytes)));
    }
    if outside > 0 {
        out.notes.push(format!("{outside} files outside its packages were left out"));
    }
    for (root, entries) in by_root {
        let total: usize = entries.iter().map(|(rel, e)| e.size(rel)).sum();
        if total > MAX_PACKAGE_DATA {
            out.notes.push(format!(
                "the {} data files of '{root}' were left out: written as Python they take {} KB, over the {} KB a package's data may take",
                entries.len(),
                total / 1024,
                MAX_PACKAGE_DATA / 1024
            ));
            continue;
        }
        let mut chunks: Vec<Vec<(&str, &Entry)>> = vec![Vec::new()];
        let mut size = 0usize;
        for (rel, e) in &entries {
            let n = e.size(rel);
            if n > MAX_MODULE {
                out.notes.push(format!("{root}/{rel} was left out: written as Python it takes {} KB, over the 1 MB a module may take", n / 1024));
                continue;
            }
            if size + n > MAX_MODULE && !chunks.last().expect("one").is_empty() {
                chunks.push(Vec::new());
                size = 0;
            }
            size += n;
            chunks.last_mut().expect("one").push((rel, e));
            out.kept += 1;
        }
        for (i, chunk) in chunks.iter().enumerate().filter(|(_, c)| !c.is_empty()) {
            let name = if i == 0 { MODULE.to_string() } else { format!("{MODULE}_{}", i + 1) };
            out.modules.insert(format!("{root}/{name}.py"), module_text(root, chunk));
        }
    }
    out
}

/// [modules] for data handed in as base64 text (the web editor's wasm call):
/// a value that is not base64 is left out.
pub fn modules_from_base64(data: &BTreeMap<String, String>, packages: &BTreeSet<String>) -> DataModules {
    let decoded: BTreeMap<String, Vec<u8>> = data.iter().filter_map(|(p, b)| STANDARD.decode(b).ok().map(|v| (p.clone(), v))).collect();
    modules(&decoded, packages)
}

/// One data file as it is written: text as a string, anything else in base64.
enum Entry {
    Text(String),
    Bytes(String),
}

impl Entry {
    fn of(bytes: &[u8]) -> Entry {
        match std::str::from_utf8(bytes) {
            Ok(s) if !s.contains('\0') => Entry::Text(py_str(s)),
            _ => Entry::Bytes(STANDARD.encode(bytes)),
        }
    }

    /// Its line in the module, roughly.
    fn size(&self, rel: &str) -> usize {
        rel.len()
            + 12
            + match self {
                Entry::Text(s) => s.len(),
                Entry::Bytes(s) => s.len() + 2,
            }
    }
}

fn module_text(root: &str, entries: &[(&str, &Entry)]) -> String {
    let mut s = format!(
        "# The data files of the {root} package, which a plugin keeps here: the\n\
         # keyboard has no files beside a plugin's modules. open(), importlib.resources\n\
         # and pkgutil.get_data read them by their paths. Written by wmpy pip; do not edit.\n\
         TEXT = {{\n"
    );
    for (rel, e) in entries {
        if let Entry::Text(t) = e {
            s.push_str(&format!("    {}: {t},\n", py_str(rel)));
        }
    }
    s.push_str("}\nDATA = {\n");
    for (rel, e) in entries {
        if let Entry::Bytes(b) = e {
            s.push_str(&format!("    {}: \"{b}\",\n", py_str(rel)));
        }
    }
    s.push_str("}\n");
    s
}

/// [s] as a Python string literal.
fn py_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 || c == '\u{7f}' => out.push_str(&format!("\\x{:02x}", c as u32)),
            // Line and paragraph separators end a line for some readers of source.
            '\u{2028}' | '\u{2029}' | '\u{85}' => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn data(files: &[(&str, &[u8])]) -> BTreeMap<String, Vec<u8>> {
        files.iter().map(|(p, b)| (p.to_string(), b.to_vec())).collect()
    }

    #[test]
    fn text_and_bytes_go_in_a_module_of_their_package() {
        let pkgs: BTreeSet<String> = ["schemas".to_string()].into();
        let d = modules(&data(&[("schemas/draft/meta.json", b"{\"a\": \"x\\\\y\"}\n"), ("schemas/logo.png", b"\x89PNG\0\x01"), ("README.txt", b"hi"), ("other/x.txt", b"x")]), &pkgs);
        assert_eq!(d.kept, 2);
        let text = &d.modules["schemas/_wmdata.py"];
        assert!(text.contains(r#""draft/meta.json": "{\"a\": \"x\\\\y\"}\n","#), "{text}");
        assert!(text.contains(&format!("\"logo.png\": \"{}\"", STANDARD.encode(b"\x89PNG\0\x01"))), "{text}");
        assert!(d.notes[0].starts_with("2 files outside"), "{:?}", d.notes);
        // The module is Python the keyboard's parser takes.
        assert!(crate::is_importable_path("schemas/_wmdata.py"));
        assert!(is_data_module("schemas/_wmdata.py") && is_data_module("a/b/_wmdata_2.py") && !is_data_module("_wmdata.py") && !is_data_module("a/_wmdata_x.py"));
    }

    #[test]
    fn big_data_splits_and_too_big_is_left_out() {
        let pkgs: BTreeSet<String> = ["p".to_string(), "q".to_string()].into();
        let half = vec![b'a'; 600 * 1024];
        let d = modules(&data(&[("p/a.txt", &half), ("p/b.txt", &half), ("q/c.txt", &vec![b'b'; 1000 * 1024])]), &pkgs);
        assert_eq!(d.kept, 2, "{:?}", d.notes);
        assert!(d.modules.contains_key("p/_wmdata.py") && d.modules.contains_key("p/_wmdata_2.py"), "{:?}", d.modules.keys());
        assert!(d.notes.iter().any(|n| n.starts_with("q/c.txt was left out")), "{:?}", d.notes);
        let big = vec![b'a'; 900 * 1024];
        let d = modules(&data(&[("q/1.txt", &big), ("q/2.txt", &big), ("q/3.txt", &big)]), &pkgs);
        assert!(d.modules.is_empty() && d.kept == 0 && d.notes[0].contains("over the 2048 KB"), "{:?}", d.notes);
    }
}
