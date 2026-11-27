//! A downloaded distribution, opened: the importable `.py` files it would add
//! to a plugin, its licence files, and what its core metadata says (name,
//! version, `Requires-Dist`, licence). Nothing here touches the disk; paths
//! inside archives are only ever compared and copied as validated strings.

use std::collections::{BTreeMap, BTreeSet};
use std::io::{Cursor, Read};

use super::marker::Requirement;
use super::version::normalize;

/// The most an archive may unpack to.
const MAX_UNPACKED: u64 = 256 << 20;

/// Top-level folders that are never the package itself.
const NOT_PACKAGES: &[&str] = &["tests", "test", "testing", "docs", "doc", "examples", "example", "benchmarks", "bench", "scripts", "tools", "build", "dist", "site-packages"];

/// Files at the top of an sdist that are build scripts, not modules.
const NOT_MODULES: &[&str] = &["setup", "conftest", "noxfile", "fabfile", "tasks", "ez_setup", "versioneer", "runtests", "manage", "docs_conf"];

/// What C, Cython, Fortran and Rust sources look like.
const BUILT_SOURCES: &[&str] = &[".c", ".cc", ".cpp", ".cxx", ".pyx", ".pxd", ".f", ".f90", ".rs", ".go", ".m", ".mm"];

#[derive(Clone, Debug, Default)]
pub struct Dist {
    /// PEP 503 name.
    pub key: String,
    /// As the project spells it.
    pub name: String,
    pub version: String,
    pub source: String,
    pub sha256: String,
    /// `.py` files to add, by path in the plugin folder, as UTF-8 text.
    pub modules: BTreeMap<String, String>,
    /// Other files inside the package that matter to the analysis: compiled
    /// code and C sources, by path.
    pub native: Vec<String>,
    /// Its compiled modules, by module name, when they were left out of a
    /// wheel built for a platform ([Dist::leave_out_compiled]).
    pub compiled: Vec<String>,
    /// Licence files: `(file name, bytes)`.
    pub licenses: Vec<(String, Vec<u8>)>,
    pub license: Option<String>,
    pub requires: Vec<Requirement>,
    pub roots: Vec<String>,
    /// Things worth saying: data files left out, an sdist used, ...
    pub notes: Vec<String>,
    /// Why it cannot be used at all, before any analysis.
    pub refused: Option<String>,
}

impl Dist {
    /// The files [wm_python_pip::analyse] reads.
    pub fn analysis_files(&self) -> BTreeMap<String, String> {
        let mut out = self.modules.clone();
        for n in &self.native {
            out.insert(n.clone(), String::new());
        }
        out
    }

    pub fn bytes(&self) -> usize {
        self.modules.values().map(|s| s.len()).sum()
    }

    /// Takes a wheel built for a platform down to its `.py` files: the
    /// compiled modules go, named in [Dist::compiled] so the analysis can
    /// tell an import of one from a missing module. Packages that bring a
    /// Python fallback for their speedups run on that.
    pub fn leave_out_compiled(&mut self, wheel: &str) {
        let (binaries, rest): (Vec<String>, Vec<String>) = std::mem::take(&mut self.native).into_iter().partition(|p| is_binary(p));
        self.native = rest;
        self.compiled = binaries.iter().filter_map(|p| binary_module(p)).collect();
        let shown: Vec<&str> = binaries.iter().map(|p| p.rsplit('/').next().unwrap_or(p)).take(3).collect();
        let more = if binaries.len() > 3 { format!(" and {} more", binaries.len() - 3) } else { String::new() };
        self.notes.insert(
            0,
            format!(
                "there is no pure-Python wheel, so its .py files come from {wheel}, without its compiled parts ({}{more}); it works where it can do without them",
                shown.join(", ")
            ),
        );
    }
}

fn is_binary(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".so") || lower.contains(".so.") || lower.ends_with(".pyd") || lower.ends_with(".dylib") || lower.ends_with(".dll")
}

/// The module a compiled extension imports as: `yaml._yaml` for
/// `yaml/_yaml.cpython-312-x86_64-linux-gnu.so`. Shared libraries beside them
/// (`.dylib`, `.dll`, `libfoo.so.1`) are not modules.
fn binary_module(path: &str) -> Option<String> {
    let lower = path.to_ascii_lowercase();
    if !(lower.ends_with(".so") || lower.ends_with(".pyd")) {
        return None;
    }
    let (dir, file) = path.rsplit_once('/').map(|(d, f)| (Some(d), f)).unwrap_or((None, path));
    let stem = file.split('.').next()?;
    let valid = |s: &str| s.chars().next().is_some_and(|c| c.is_ascii_alphabetic() || c == '_') && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    if !valid(stem) || dir.is_some_and(|d| !d.split('/').all(valid)) {
        return None;
    }
    Some(match dir {
        Some(d) => format!("{}.{stem}", d.replace('/', ".")),
        None => stem.to_string(),
    })
}

/// Core metadata (METADATA or PKG-INFO): headers, several values per name.
pub struct Metadata {
    fields: Vec<(String, String)>,
}

impl Metadata {
    pub fn parse(text: &str) -> Metadata {
        let mut fields: Vec<(String, String)> = Vec::new();
        for line in text.lines() {
            if line.is_empty() {
                break;
            }
            if line.starts_with([' ', '\t']) {
                if let Some(last) = fields.last_mut() {
                    last.1.push('\n');
                    last.1.push_str(line.trim_start_matches(' ').trim_start_matches('|').trim_start());
                }
                continue;
            }
            if let Some((k, v)) = line.split_once(':') {
                fields.push((k.trim().to_ascii_lowercase(), v.trim().to_string()));
            }
        }
        Metadata { fields }
    }

    pub fn get(&self, key: &str) -> Option<&str> {
        self.fields.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str()).filter(|v| !v.is_empty())
    }

    pub fn all(&self, key: &str) -> Vec<&str> {
        self.fields.iter().filter(|(k, _)| k == key).map(|(_, v)| v.as_str()).collect()
    }

    /// The licence in a few words: `License-Expression`, else the licence
    /// classifiers, else a short `License` field (or its first line).
    pub fn license(&self) -> Option<String> {
        if let Some(e) = self.get("license-expression") {
            return Some(e.to_string());
        }
        let classifiers: Vec<String> = self
            .all("classifier")
            .iter()
            .filter_map(|c| c.strip_prefix("License ::"))
            .map(|c| c.rsplit("::").next().unwrap_or(c).trim().to_string())
            .filter(|c| !c.is_empty() && c != "OSI Approved")
            .collect();
        if !classifiers.is_empty() {
            return Some(classifiers.join(" OR "));
        }
        let l = self.get("license")?;
        if l.eq_ignore_ascii_case("UNKNOWN") {
            return None;
        }
        let first = l.lines().map(str::trim).find(|s| !s.is_empty())?;
        Some(if first.chars().count() > 80 { format!("{}...", first.chars().take(77).collect::<String>()) } else { first.to_string() })
    }

    pub fn requires(&self) -> Vec<Requirement> {
        self.all("requires-dist").iter().filter_map(|r| Requirement::parse(r).ok()).collect()
    }
}

/// Whether [name] looks like a licence file: LICENSE, LICENCE, COPYING, NOTICE, with any suffix.
fn is_license_name(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    ["LICENSE", "LICENCE", "COPYING", "NOTICE", "UNLICENSE"].iter().any(|p| upper.starts_with(p))
}

/// A licence file's name as it will sit in the plugin: letters, digits, `.`, `-`, `_`.
fn safe_name(name: &str) -> String {
    let base = name.rsplit('/').next().unwrap_or(name);
    let s: String = base.chars().map(|c| if c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_') { c } else { '_' }).collect();
    let s = s.trim_start_matches('.').to_string();
    if s.is_empty() { "LICENSE".into() } else { s }
}

/// An archive path that is safe to think about: relative, `/`-separated, no `..`.
fn clean(path: &str) -> Option<String> {
    let p = path.replace('\\', "/");
    if p.starts_with('/') || p.contains(':') {
        return None;
    }
    let parts: Vec<&str> = p.split('/').filter(|s| !s.is_empty() && *s != ".").collect();
    if parts.iter().any(|s| *s == "..") || parts.is_empty() {
        return None;
    }
    Some(parts.join("/"))
}

/// The text of a `.py` file: UTF-8 (a BOM dropped), or Latin-1 turned into UTF-8.
fn py_text(bytes: &[u8], path: &str, notes: &mut Vec<String>) -> String {
    let bytes = bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes);
    match std::str::from_utf8(bytes) {
        Ok(s) => s.to_string(),
        Err(_) => {
            notes.push(format!("{path} was not UTF-8; it was read as Latin-1 and saved as UTF-8"));
            bytes.iter().map(|b| *b as char).collect()
        }
    }
}

/// Sorts a package's files into modules, native files and the rest.
fn take_file(dist: &mut Dist, path: &str, bytes: &[u8], skipped: &mut usize, skipped_py: &mut Vec<String>) {
    let parts: Vec<&str> = path.split('/').collect();
    if parts.len() > 1 && NOT_PACKAGES.contains(&parts[0]) {
        return;
    }
    // A package's own test folders are not needed to run it.
    if parts.len() > 2 && parts[1..parts.len() - 1].iter().any(|p| *p == "tests" || *p == "test") {
        return;
    }
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".py") {
        if parts.len() == 1 && NOT_MODULES.contains(&path.trim_end_matches(".py")) {
            return;
        }
        if wm_python_pip::is_importable_path(path) {
            let text = py_text(bytes, path, &mut dist.notes);
            dist.modules.insert(path.to_string(), text);
        } else {
            skipped_py.push(path.to_string());
        }
    } else if lower.ends_with(".so") || lower.contains(".so.") || lower.ends_with(".pyd") || lower.ends_with(".dylib") || lower.ends_with(".dll") {
        dist.native.push(path.to_string());
    } else if BUILT_SOURCES.iter().any(|e| lower.ends_with(e)) || lower.ends_with(".h") {
        dist.native.push(path.to_string());
    } else if !(lower.ends_with(".pyi") || lower.ends_with("py.typed") || lower.ends_with(".pyc")) {
        *skipped += 1;
    }
}

fn finish(dist: &mut Dist, skipped: usize, skipped_py: Vec<String>) {
    let roots: BTreeSet<String> = dist.modules.keys().map(|p| p.split('/').next().unwrap_or(p).trim_end_matches(".py").to_string()).collect();
    dist.roots = roots.into_iter().collect();
    if skipped > 0 {
        dist.notes.push(format!("{skipped} data files were left out: plugins cannot open files beside their code"));
    }
    if !skipped_py.is_empty() {
        dist.notes.push(format!("{} .py files whose paths are not module names were left out ({})", skipped_py.len(), skipped_py.iter().take(3).cloned().collect::<Vec<_>>().join(", ")));
    }
    if dist.licenses.is_empty() {
        dist.notes.push("no licence file came with it".into());
    }
}

/// Opens a wheel.
pub fn from_wheel(bytes: &[u8], source: &str, sha256: &str) -> Result<Dist, String> {
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| format!("not a wheel: {e}"))?;
    let mut entries: Vec<(String, Vec<u8>)> = Vec::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        let mut f = zip.by_index(i).map_err(|e| format!("a broken wheel: {e}"))?;
        if f.is_dir() {
            continue;
        }
        let Some(name) = f.name().ok().and_then(|n| clean(&n)) else { continue };
        total += f.size();
        if total > MAX_UNPACKED {
            return Err("the wheel unpacks to more than 256 MB".into());
        }
        let mut data = Vec::with_capacity(f.size() as usize);
        f.read_to_end(&mut data).map_err(|e| format!("could not read {name} from the wheel: {e}"))?;
        entries.push((name, data));
    }
    let dist_info = entries
        .iter()
        .find_map(|(n, _)| n.split_once('/').filter(|(d, rest)| d.ends_with(".dist-info") && *rest == "METADATA").map(|(d, _)| d.to_string()))
        .ok_or("the wheel has no .dist-info/METADATA")?;
    let mut dist = Dist { source: source.to_string(), sha256: sha256.to_string(), ..Default::default() };
    let mut skipped = 0;
    let mut skipped_py = Vec::new();
    for (name, data) in &entries {
        let (first, rest) = name.split_once('/').unwrap_or((name.as_str(), ""));
        if first == dist_info {
            if rest == "METADATA" {
                let meta = Metadata::parse(&String::from_utf8_lossy(data));
                dist.name = meta.get("name").unwrap_or("").to_string();
                dist.version = meta.get("version").unwrap_or("").to_string();
                dist.license = meta.license();
                dist.requires = meta.requires();
            } else if rest.starts_with("licenses/") || is_license_name(rest) {
                dist.licenses.push((safe_name(rest), data.clone()));
            }
            continue;
        }
        if first.ends_with(".data") {
            for lib in ["purelib/", "platlib/"] {
                if let Some(inner) = rest.strip_prefix(lib) {
                    take_file(&mut dist, inner, data, &mut skipped, &mut skipped_py);
                }
            }
            continue;
        }
        take_file(&mut dist, name, data, &mut skipped, &mut skipped_py);
    }
    dist.key = normalize(&dist.name);
    finish(&mut dist, skipped, skipped_py);
    Ok(dist)
}

/// Opens an sdist (`.tar.gz` or `.zip`) and copies its `.py` files, or says
/// why not when something in it has to be built.
pub fn from_sdist(bytes: &[u8], filename: &str, source: &str, sha256: &str) -> Result<Dist, String> {
    let mut files: BTreeMap<String, Vec<u8>> = BTreeMap::new();
    let mut total = 0u64;
    if filename.ends_with(".zip") {
        let mut zip = zip::ZipArchive::new(Cursor::new(bytes)).map_err(|e| format!("not a zip: {e}"))?;
        for i in 0..zip.len() {
            let mut f = zip.by_index(i).map_err(|e| format!("a broken zip: {e}"))?;
            if f.is_dir() {
                continue;
            }
            let Some(name) = f.name().ok().and_then(|n| clean(&n)) else { continue };
            total += f.size();
            if total > MAX_UNPACKED {
                return Err("the sdist unpacks to more than 256 MB".into());
            }
            let mut data = Vec::new();
            f.read_to_end(&mut data).map_err(|e| format!("could not read {name}: {e}"))?;
            files.insert(name, data);
        }
    } else {
        let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(Cursor::new(bytes)));
        for entry in tar.entries().map_err(|e| format!("not a .tar.gz: {e}"))? {
            let mut e = entry.map_err(|e| format!("a broken .tar.gz: {e}"))?;
            if !e.header().entry_type().is_file() {
                continue;
            }
            let Some(name) = e.path().ok().and_then(|p| clean(&p.to_string_lossy())) else { continue };
            total += e.size();
            if total > MAX_UNPACKED {
                return Err("the sdist unpacks to more than 256 MB".into());
            }
            let mut data = Vec::new();
            e.read_to_end(&mut data).map_err(|e| format!("could not read {name}: {e}"))?;
            files.insert(name, data);
        }
    }
    // Everything sits in one `name-version/` folder.
    let mut stripped = BTreeMap::new();
    for (name, data) in files {
        if let Some((_, rest)) = name.split_once('/') {
            stripped.insert(rest.to_string(), data);
        }
    }
    let mut dist = from_tree(stripped, source, sha256);
    dist.notes.insert(0, "there is no pure-Python wheel, so its .py files were taken from the source archive".into());
    Ok(dist)
}

/// A source tree (an unpacked sdist, or a folder for `wmpy pip check`):
/// finds the package's modules and reads PKG-INFO.
pub fn from_tree(files: BTreeMap<String, Vec<u8>>, source: &str, sha256: &str) -> Dist {
    let mut dist = Dist { source: source.to_string(), sha256: sha256.to_string(), ..Default::default() };
    let text = |p: &str| files.get(p).map(|b| String::from_utf8_lossy(b).into_owned());
    if let Some(info) = text("PKG-INFO") {
        let meta = Metadata::parse(&info);
        dist.name = meta.get("name").unwrap_or("").to_string();
        dist.version = meta.get("version").unwrap_or("").to_string();
        dist.license = meta.license();
        dist.requires = meta.requires();
    }
    // What has to be built.
    let mut why = Vec::new();
    for name in files.keys() {
        let first = name.split('/').next().unwrap_or("");
        if NOT_PACKAGES.contains(&first) {
            continue;
        }
        let lower = name.to_ascii_lowercase();
        if BUILT_SOURCES.iter().any(|e| lower.ends_with(e)) {
            why.push(format!("it has {name} to compile"));
            break;
        }
    }
    if let Some(setup) = text("setup.py") {
        for sign in ["ext_modules", "Extension(", "cffi_modules", "rust_extensions", "cythonize"] {
            if setup.contains(sign) {
                why.push(format!("setup.py builds extensions ({sign})"));
                break;
            }
        }
    }
    if let Some(pyproject) = text("pyproject.toml") {
        for backend in ["maturin", "scikit_build", "scikit-build", "mesonpy", "meson-python", "setuptools_rust", "setuptools-rust", "cmake"] {
            if pyproject.contains(backend) {
                why.push(format!("pyproject.toml builds with {backend}"));
                break;
            }
        }
    }
    if !why.is_empty() {
        dist.refused = Some(format!("it needs building: {}", why.join("; ")));
    }
    // Where the modules are: `src/` or the top.
    let has_py_under = |prefix: &str| files.keys().any(|k| k.starts_with(prefix) && k.ends_with(".py"));
    let base = if has_py_under("src/") { "src/" } else { "" };
    let top_level: Vec<String> = files
        .iter()
        .find(|(k, _)| k.ends_with(".egg-info/top_level.txt"))
        .map(|(_, v)| String::from_utf8_lossy(v).lines().map(|l| l.trim().replace('/', ".")).filter(|l| !l.is_empty()).collect())
        .unwrap_or_default();
    let mut packages: BTreeSet<String> = BTreeSet::new();
    let mut modules: BTreeSet<String> = BTreeSet::new();
    for name in files.keys() {
        let Some(rel) = name.strip_prefix(base) else { continue };
        match rel.split_once('/') {
            Some((dir, rest)) if rest == "__init__.py" && !NOT_PACKAGES.contains(&dir) => {
                packages.insert(dir.to_string());
            }
            None if rel.ends_with(".py") => {
                let stem = rel.trim_end_matches(".py");
                if !NOT_MODULES.contains(&stem) && !stem.starts_with("test_") && !stem.ends_with("_test") && !stem.starts_with("setup") {
                    modules.insert(stem.to_string());
                }
            }
            _ => {}
        }
    }
    let wanted: BTreeSet<String> = if !top_level.is_empty() {
        top_level.into_iter().map(|t| t.split('.').next().unwrap_or(&t).to_string()).filter(|t| packages.contains(t) || modules.contains(t)).collect()
    } else if !packages.is_empty() {
        let key = normalize(&dist.name).replace('-', "_");
        packages.iter().cloned().chain(modules.iter().filter(|m| m.as_str() == key).cloned()).collect()
    } else {
        modules.clone()
    };
    let mut skipped = 0;
    let mut skipped_py = Vec::new();
    for (name, data) in &files {
        let Some(rel) = name.strip_prefix(base) else { continue };
        let root = rel.split('/').next().unwrap_or(rel).trim_end_matches(".py");
        if !wanted.contains(root) {
            continue;
        }
        take_file(&mut dist, rel, data, &mut skipped, &mut skipped_py);
    }
    for (name, data) in &files {
        if !name.contains('/') && is_license_name(name) {
            dist.licenses.push((safe_name(name), data.clone()));
        }
    }
    if dist.requires.is_empty() {
        if let Some((_, req)) = files.iter().find(|(k, _)| k.ends_with(".egg-info/requires.txt")) {
            dist.requires = requires_txt(&String::from_utf8_lossy(req));
        }
    }
    dist.key = normalize(&dist.name);
    if dist.modules.is_empty() && dist.refused.is_none() {
        dist.refused = Some("no importable .py files were found in it".into());
    }
    finish(&mut dist, skipped, skipped_py);
    dist
}

/// An egg-info `requires.txt`: requirement lines, with `[extra]`,
/// `[:marker]` and `[extra:marker]` sections.
fn requires_txt(text: &str) -> Vec<Requirement> {
    let mut out = Vec::new();
    let mut section: Option<String> = None;
    for line in text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
        if let Some(s) = line.strip_prefix('[').and_then(|l| l.strip_suffix(']')) {
            let (extra, marker) = s.split_once(':').unwrap_or((s, ""));
            let mut parts = Vec::new();
            if !extra.trim().is_empty() {
                parts.push(format!("extra == \"{}\"", extra.trim()));
            }
            if !marker.trim().is_empty() {
                parts.push(format!("({})", marker.trim()));
            }
            section = (!parts.is_empty()).then(|| parts.join(" and "));
            continue;
        }
        let full = match &section {
            Some(m) => format!("{line}; {m}"),
            None => line.to_string(),
        };
        if let Ok(r) = Requirement::parse(&full) {
            out.push(r);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn metadata_and_licences() {
        let m = Metadata::parse("Metadata-Version: 2.1\nName: Foo_Bar\nVersion: 1.0\nLicense: MIT License\n        Copyright (c) someone\nClassifier: Topic :: Utilities\nRequires-Dist: six (>=1.5)\nRequires-Dist: tzdata ; extra == 'tz'\n\nbody text: not a header\n");
        assert_eq!(m.get("name"), Some("Foo_Bar"));
        assert_eq!(m.license().as_deref(), Some("MIT License"));
        assert_eq!(m.requires().len(), 2);
        let m = Metadata::parse("Name: x\nClassifier: License :: OSI Approved :: BSD License\nClassifier: License :: OSI Approved :: Apache Software License\n");
        assert_eq!(m.license().as_deref(), Some("BSD License OR Apache Software License"));
        assert!(is_license_name("LICENSE.txt") && is_license_name("COPYING") && !is_license_name("README.md"));
        assert_eq!(safe_name("licenses/LICEN SE.md"), "LICEN_SE.md");
        assert_eq!(clean("../etc/passwd"), None);
        assert_eq!(clean("/abs"), None);
        assert_eq!(clean("a/./b.py").as_deref(), Some("a/b.py"));
    }

    #[test]
    fn compiled_parts_are_named_by_module() {
        assert_eq!(binary_module("yaml/_yaml.cpython-312-x86_64-linux-gnu.so").as_deref(), Some("yaml._yaml"));
        assert_eq!(binary_module("_cffi_backend.cpython-312-darwin.so").as_deref(), Some("_cffi_backend"));
        assert_eq!(binary_module("markupsafe/_speedups.pyd").as_deref(), Some("markupsafe._speedups"));
        assert_eq!(binary_module("numpy/.dylibs/libgfortran.5.dylib"), None);
        let mut d = Dist { native: vec!["yaml/_yaml.cpython-312-x86_64-linux-gnu.so".into(), "yaml/_yaml.h".into()], ..Default::default() };
        d.leave_out_compiled("PyYAML-6.0.2-cp312-cp312-manylinux_2_17_x86_64.whl");
        assert_eq!((d.compiled, d.native), (vec!["yaml._yaml".to_string()], vec!["yaml/_yaml.h".to_string()]));
        assert!(d.notes[0].contains("_yaml.cpython-312-x86_64-linux-gnu.so"));
    }

    #[test]
    fn sdist_trees() {
        let mut files = BTreeMap::new();
        files.insert("PKG-INFO".to_string(), b"Name: tidy\nVersion: 1.0\nLicense: MIT\n".to_vec());
        files.insert("setup.py".to_string(), b"from setuptools import setup\nsetup()\n".to_vec());
        files.insert("src/tidy/__init__.py".to_string(), b"X = 1\n".to_vec());
        files.insert("src/tidy/tests/test_x.py".to_string(), b"".to_vec());
        files.insert("src/tidy/data.json".to_string(), b"{}".to_vec());
        files.insert("tests/test_tidy.py".to_string(), b"".to_vec());
        files.insert("LICENSE".to_string(), b"MIT".to_vec());
        let d = from_tree(files.clone(), "x", "y");
        assert!(d.refused.is_none(), "{:?}", d.refused);
        assert_eq!(d.modules.keys().collect::<Vec<_>>(), vec!["tidy/__init__.py"]);
        assert_eq!(d.roots, vec!["tidy"]);
        assert_eq!(d.licenses.len(), 1);
        files.insert("src/tidy/_speed.c".to_string(), b"int x;".to_vec());
        assert!(from_tree(files, "x", "y").refused.unwrap().contains("_speed.c"));
        let r = requires_txt("six>=1.5\n\n[tz]\ntzdata\n\n[:python_version < \"3.9\"]\nbackports.zoneinfo\n");
        assert_eq!(r.len(), 3);
        assert!(!r[1].applies(&[]).unwrap() && r[1].applies(&["tz".into()]).unwrap());
        assert!(!r[2].applies(&[]).unwrap());
    }
}
