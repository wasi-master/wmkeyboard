//! Small changes to popular packages so they run in a plugin, the way
//! Pyodide keeps patches for the packages it ships. Each project's changes
//! are a file in `patches/` (see `patches/README.md`): edits that replace an
//! exact piece of text in one of its files, each with the reason.
//!
//! An edit whose text is not in the file (a release that changed it) is
//! skipped and said so, rather than guessed at. The interpreter is the
//! better place for a fix that more than one package needs; a patch is for
//! what only one package does.

use std::collections::BTreeMap;

use serde_json::Value as Json;

/// `(project, the patch file's JSON)`, one per patched project, by PEP 503 name.
const PATCHES: &[(&str, &str)] = &[("click", include_str!("../patches/click.json"))];

/// One replacement in one file.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Edit {
    pub file: String,
    pub find: String,
    pub replace: String,
    pub why: String,
}

/// What a project's patch file says.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Patch {
    pub project: String,
    pub edits: Vec<Edit>,
}

fn parse(project: &str, text: &str) -> Result<Patch, String> {
    let v: Json = serde_json::from_str(text).map_err(|e| format!("patches/{project}.json: {e}"))?;
    let s = |o: &Json, k: &str| o.get(k).and_then(Json::as_str).map(String::from).ok_or_else(|| format!("patches/{project}.json: an edit has no \"{k}\""));
    let mut edits = Vec::new();
    for e in v.get("edits").and_then(Json::as_array).ok_or_else(|| format!("patches/{project}.json: no \"edits\""))? {
        edits.push(Edit { file: s(e, "file")?, find: s(e, "find")?, replace: s(e, "replace")?, why: s(e, "why")? });
    }
    Ok(Patch { project: project.to_string(), edits })
}

/// Every patch this build carries.
pub fn all() -> Vec<Patch> {
    PATCHES.iter().filter_map(|(p, text)| parse(p, text).ok()).collect()
}

/// The patch for [project] (a PEP 503 name: `click`, `python-dateutil`), if there is one.
pub fn for_project(project: &str) -> Option<Patch> {
    PATCHES.iter().find(|(p, _)| *p == project).and_then(|(p, text)| parse(p, text).ok())
}

/// Applies [project]'s patch to its files (path to source), and says what
/// it did: one line per edit made or skipped.
pub fn apply(project: &str, files: &mut BTreeMap<String, String>) -> Vec<String> {
    let Some(patch) = for_project(project) else { return Vec::new() };
    let mut notes = Vec::new();
    for e in &patch.edits {
        match files.get_mut(&e.file) {
            Some(text) if text.contains(e.find.as_str()) => {
                *text = text.replacen(e.find.as_str(), &e.replace, 1);
                notes.push(format!("patched {} for the keyboard: {}", e.file, e.why));
            }
            Some(_) => notes.push(format!("a patch for {} no longer fits this release, so it was left out ({})", e.file, e.why)),
            None => {}
        }
    }
    notes
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_patch_file_reads() {
        for (p, text) in PATCHES {
            let patch = parse(p, text).unwrap_or_else(|e| panic!("{e}"));
            assert!(!patch.edits.is_empty(), "{p}");
            for e in &patch.edits {
                assert!(!e.find.is_empty() && e.find != e.replace && !e.why.is_empty(), "{p}: {e:?}");
            }
        }
    }

    #[test]
    fn an_edit_applies_once_and_a_stale_one_is_named() {
        let patch = for_project("click").expect("click patch");
        let e = &patch.edits[0];
        let mut files = BTreeMap::new();
        files.insert(e.file.clone(), format!("a\n{}\nb\n", e.find));
        let notes = apply("click", &mut files);
        assert!(files[&e.file].contains(&e.replace) && !files[&e.file].contains(&e.find), "{files:?}");
        assert!(notes[0].starts_with("patched"), "{notes:?}");
        let mut stale = BTreeMap::new();
        stale.insert(e.file.clone(), "nothing here\n".to_string());
        assert!(apply("click", &mut stale)[0].contains("no longer fits"));
        assert!(apply("not-patched", &mut stale).is_empty());
    }
}
