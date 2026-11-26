//! Choosing releases, analysing them, and writing the chosen ones into a
//! plugin folder: what `install`, `update` and `check` share.
//!
//! Resolution is first come, first served, as pip's old resolver was: each
//! requirement takes the newest release that fits everything asked of that
//! package so far, and a later requirement it does not fit is a conflict
//! to report, not to backtrack over. Packages already in `wmpy.lock` that fit
//! stay as they are unless the command is refreshing them.

use std::collections::{BTreeMap, BTreeSet, HashSet, VecDeque};
use std::path::Path;

use super::dist::{self, Dist};
use super::index::{FileInfo, Index, Project};
use super::lock::{Entry, Lock};
use super::marker::Requirement;
use super::version::{Specifier, Version};

/// An sdist this big next to compiled wheels is not worth fetching to learn it needs building.
const MAX_SDIST_PROBE: u64 = 10 << 20;

pub enum Source {
    New(Box<Dist>),
    Locked(Entry),
}

pub struct Pick {
    pub name: String,
    pub version: String,
    pub extras: Vec<String>,
    pub source: Source,
    /// Normalized names of the dependencies that apply.
    pub requires: Vec<String>,
    pub required_by: Vec<String>,
    pub requested: bool,
    /// The locked version this replaces.
    pub previous: Option<String>,
}

impl Pick {
    pub fn roots(&self) -> Vec<String> {
        match &self.source {
            Source::New(d) => d.roots.clone(),
            Source::Locked(e) => e.roots.clone(),
        }
    }
}

pub struct Resolver<'a> {
    pub index: &'a Index,
    pub lock: &'a Lock,
    /// Fetch these again even when the lock has a fitting version.
    pub fresh: HashSet<String>,
    pub all_fresh: bool,
    pub no_deps: bool,
    pub picks: BTreeMap<String, Pick>,
    pub notes: Vec<String>,
    /// Progress lines go to stderr unless quiet.
    pub quiet: bool,
}

impl Resolver<'_> {
    fn say(&self, line: &str) {
        if !self.quiet {
            eprintln!("{line}");
        }
    }

    pub fn resolve(&mut self, requested: Vec<Requirement>) -> Result<(), String> {
        let mut queue: VecDeque<(Requirement, Option<String>)> = requested.into_iter().map(|r| (r, None)).collect();
        while let Some((req, parent)) = queue.pop_front() {
            if let Some(url) = &req.url {
                return Err(format!("{} asks for a direct URL ({url}); wmpy installs from PyPI only", req.name));
            }
            let key = req.key();
            // requests, httpx: the keyboard's own, so nothing to fetch or lock.
            if wm_python_pip::is_built_in(&key) {
                let line = match &parent {
                    None => format!("{key} is built into WM Keyboard's plugins: `import {key}` works without installing it"),
                    Some(p) => format!("{p} needs {key}, which is built into WM Keyboard's plugins, so it was not installed"),
                };
                if !self.notes.contains(&line) {
                    self.notes.push(line);
                }
                continue;
            }
            if let Some(p) = self.picks.get_mut(&key) {
                if let Some(parent) = &parent {
                    if !p.required_by.contains(parent) {
                        p.required_by.push(parent.clone());
                    }
                } else {
                    p.requested = true;
                }
                let v = Version::parse(&p.version);
                if !req.specifier.is_empty() && !v.is_some_and(|v| req.specifier.contains(&v)) {
                    let who = parent.as_deref().unwrap_or("you");
                    return Err(format!("{who} asks for {req}, but {} {} is already chosen{}", p.name, p.version, if p.required_by.is_empty() { String::new() } else { format!(" for {}", p.required_by.join(", ")) }));
                }
                // New extras bring more dependencies.
                let added: Vec<String> = req.extras.iter().filter(|e| !p.extras.contains(e)).cloned().collect();
                if !added.is_empty() {
                    p.extras.extend(added.iter().cloned());
                    if let (Source::New(d), false) = (&p.source, self.no_deps) {
                        for r in &d.requires {
                            if !r.applies(&[]).unwrap_or(false) && r.applies(&added).unwrap_or(false) {
                                if !p.requires.contains(&r.key()) {
                                    p.requires.push(r.key());
                                }
                                queue.push_back((r.clone(), Some(key.clone())));
                            }
                        }
                    }
                }
                continue;
            }
            let fresh = self.all_fresh || self.fresh.contains(&key);
            if let (false, Some(entry)) = (fresh, self.lock.packages.get(&key)) {
                if Version::parse(&entry.version).is_some_and(|v| req.specifier.contains(&v)) {
                    if !self.no_deps {
                        for dep in &entry.requires {
                            if let Ok(r) = Requirement::parse(dep) {
                                queue.push_back((r, Some(key.clone())));
                            }
                        }
                    }
                    self.picks.insert(
                        key.clone(),
                        Pick {
                            name: key.clone(),
                            version: entry.version.clone(),
                            extras: req.extras.clone(),
                            requires: entry.requires.clone(),
                            required_by: parent.iter().cloned().collect(),
                            requested: parent.is_none(),
                            previous: None,
                            source: Source::Locked(entry.clone()),
                        },
                    );
                    continue;
                }
            }
            let mut pick = self.fetch(&req)?;
            pick.required_by = parent.iter().cloned().collect();
            pick.requested = parent.is_none();
            pick.previous = self.lock.packages.get(&key).map(|e| e.version.clone()).filter(|v| *v != pick.version);
            if let (Source::New(d), false) = (&pick.source, self.no_deps) {
                for r in &d.requires {
                    match r.applies(&req.extras) {
                        Ok(true) => {
                            if !pick.requires.contains(&r.key()) {
                                pick.requires.push(r.key());
                            }
                            queue.push_back((r.clone(), Some(key.clone())));
                        }
                        Ok(false) => {}
                        Err(e) => self.notes.push(format!("{}: skipped a requirement whose marker could not be read ({e})", pick.name)),
                    }
                }
            }
            self.picks.insert(key, pick);
        }
        Ok(())
    }

    /// The newest release of [req] that fits, downloaded and opened.
    fn fetch(&mut self, req: &Requirement) -> Result<Pick, String> {
        self.say(&format!("looking up {}", req.name));
        let project = self.index.project(&req.name)?;
        if project.stale {
            self.notes.push(format!("{}: PyPI could not be reached, so the cached list of releases was used", project.name));
        }
        let (raw, file, files) = choose(&project, &req.specifier)?;
        let key = req.key();
        let dist = if file.is_pure_wheel() {
            self.say(&format!("downloading {}", file.filename));
            let bytes = self.index.download(&file)?;
            dist::from_wheel(&bytes, &file.url, &file.sha256)?
        } else {
            let compiled: Vec<&FileInfo> = files.iter().filter(|f| f.is_wheel()).collect();
            if !compiled.is_empty() && file.size > MAX_SDIST_PROBE {
                Dist {
                    key: key.clone(),
                    name: project.name.clone(),
                    version: raw.clone(),
                    source: file.url.clone(),
                    sha256: file.sha256.clone(),
                    refused: Some(format!(
                        "it has no pure-Python wheel (only {} built for particular platforms, such as {}), and its {} MB source archive needs building",
                        compiled.len(),
                        compiled[0].filename,
                        file.size >> 20
                    )),
                    ..Default::default()
                }
            } else {
                self.say(&format!("downloading {}", file.filename));
                let bytes = self.index.download(&file)?;
                let mut d = dist::from_sdist(&bytes, &file.filename, &file.url, &file.sha256)?;
                if !compiled.is_empty() && d.refused.is_none() {
                    d.notes.push(format!("{} also ships compiled wheels; their speedups are not used", project.name));
                }
                d.notes.push("dependencies read from a source archive can be incomplete".into());
                d
            }
        };
        let mut dist = dist;
        if dist.version.is_empty() {
            dist.version = raw.clone();
        }
        if dist.name.is_empty() {
            dist.name = project.name.clone();
        }
        Ok(Pick {
            name: dist.name.clone(),
            version: dist.version.clone(),
            extras: req.extras.clone(),
            requires: Vec::new(),
            required_by: Vec::new(),
            requested: false,
            previous: None,
            source: Source::New(Box::new(dist)),
        })
    }
}

/// The newest release fitting [spec] with a usable file: a pure wheel if
/// there is one, else the sdist. Pre-releases only when the specifier names
/// one, or nothing else fits.
pub fn choose(project: &Project, spec: &Specifier) -> Result<(String, FileInfo, Vec<FileInfo>), String> {
    let pinned = spec.clauses.iter().any(|c| c.op == "==" || c.op == "===");
    for allow_pre in [spec.mentions_pre(), true] {
        for (v, raw, files) in &project.releases {
            if !spec.contains(v) || (v.is_pre() && !allow_pre) {
                continue;
            }
            let usable: Vec<FileInfo> = files.iter().filter(|f| (!f.yanked || pinned) && f.fits_python()).cloned().collect();
            if let Some(w) = usable.iter().find(|f| f.is_pure_wheel()) {
                return Ok((raw.clone(), w.clone(), usable.clone()));
            }
            if let Some(s) = usable.iter().find(|f| f.is_sdist()) {
                return Ok((raw.clone(), s.clone(), usable.clone()));
            }
            if usable.iter().any(|f| f.is_wheel()) {
                return Err(format!(
                    "{} {raw} has only wheels built for particular platforms ({}) and no source archive, so it is not pure Python",
                    project.name,
                    usable.iter().find(|f| f.is_wheel()).map(|f| f.filename.as_str()).unwrap_or("")
                ));
            }
        }
        if allow_pre {
            break;
        }
    }
    let what = if spec.is_empty() { String::new() } else { format!(" matching {spec}") };
    Err(format!("{} has no release{what} that CPython 3.12 can install", project.name))
}

/// The analysis of a new package, with everything else in the plugin as available.
pub fn analyse(dist: &Dist, available: &[String]) -> wm_python_pip::Report {
    let own: BTreeSet<&String> = dist.roots.iter().collect();
    let others: Vec<String> = available.iter().filter(|r| !own.contains(r)).cloned().collect();
    let opts = wm_python_pip::AnalyseOptions { package: dist.name.clone(), available: others };
    wm_python_pip::analyse(&dist.analysis_files(), &opts)
}

/// Top-level names the plugin's own files take (not the vendored ones).
pub fn own_roots(dir: &Path, lock: &Lock) -> BTreeSet<String> {
    let vendored: BTreeSet<String> = lock.packages.values().flat_map(|e| e.files.iter().cloned()).collect();
    let mut out = BTreeSet::new();
    for f in crate::project::files(dir) {
        if !f.ends_with(".py") || vendored.contains(&f) || wm_python_tooling::manifest::is_dev_file(&f) {
            continue;
        }
        let first = f.split('/').next().unwrap_or(&f);
        out.insert(first.trim_end_matches(".py").to_string());
    }
    out
}

/// The path a licence file of [key] gets in the plugin folder.
pub fn license_path(key: &str, file: &str) -> String {
    format!("licenses/{key}/{file}")
}

/// Writes [dist] into [dir], first removing what [old] put there that the new
/// files do not replace. Returns the files it wrote, sorted.
pub fn write(dir: &Path, dist: &Dist, old: Option<&Entry>, keep: &BTreeSet<String>) -> Result<Vec<String>, String> {
    let mut files: Vec<String> = Vec::new();
    for (path, text) in &dist.modules {
        if !wm_python_pip::is_importable_path(path) {
            continue;
        }
        files.push(path.clone());
        let target = dir.join(path);
        std::fs::create_dir_all(target.parent().expect("parent")).map_err(|e| format!("{}: {e}", target.display()))?;
        std::fs::write(&target, text).map_err(|e| format!("{}: {e}", target.display()))?;
    }
    for (name, bytes) in &dist.licenses {
        let rel = license_path(&dist.key, name);
        let target = dir.join(&rel);
        std::fs::create_dir_all(target.parent().expect("parent")).map_err(|e| format!("{}: {e}", target.display()))?;
        std::fs::write(&target, bytes).map_err(|e| format!("{}: {e}", target.display()))?;
        files.push(rel);
    }
    files.sort();
    if let Some(old) = old {
        let new: BTreeSet<&String> = files.iter().collect();
        let stale: Vec<String> = old.files.iter().filter(|f| !new.contains(f) && !keep.contains(*f)).cloned().collect();
        remove_files(dir, &stale);
    }
    Ok(files)
}

/// Deletes [files] (paths inside [dir] only), then the folders they leave empty.
pub fn remove_files(dir: &Path, files: &[String]) {
    let mut folders = BTreeSet::new();
    for f in files {
        let Ok(path) = crate::project::inside(dir, f) else { continue };
        let _ = std::fs::remove_file(&path);
        let mut p = Path::new(f).parent();
        while let Some(parent) = p.filter(|p| !p.as_os_str().is_empty()) {
            folders.insert(parent.to_path_buf());
            p = parent.parent();
        }
    }
    // Deepest first; remove_dir refuses folders that still hold something.
    let mut folders: Vec<_> = folders.into_iter().collect();
    folders.sort_by_key(|f| std::cmp::Reverse(f.components().count()));
    for f in folders {
        let _ = std::fs::remove_dir(dir.join(f));
    }
}
