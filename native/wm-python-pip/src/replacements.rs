//! Pure-Python stand-ins for small packages that PyPI ships only as compiled
//! code (`orjson`, `rpds-py`): the same import names and API, written for
//! the keyboard, the way Pyodide builds such packages for the browser. Each
//! is a folder in `replacements/` (see `replacements/README.md`).
//!
//! `wmpy pip install` and the web editor put a stand-in in when the release
//! they chose has no pure-Python wheel, and say so; a release that gains one
//! is installed as it is. A stand-in is slower than the compiled original
//! and leaves out what plugins cannot use (numpy support in orjson).

use std::collections::BTreeMap;

/// The licence every stand-in ships under, as `licenses/<project>/LICENSE`.
pub const LICENSE: &str = include_str!("../replacements/LICENSE");

/// Where the stand-ins' sources are, for a lock's `source`.
const SOURCE: &str = "https://github.com/wasi-master/wmkeyboard/tree/main/native/wm-python-pip/replacements";

/// One stand-in.
#[derive(Debug)]
pub struct Replacement {
    /// The PyPI project it stands in for, by its PEP 503 name.
    pub project: &'static str,
    /// The release whose API it was written against.
    pub follows: &'static str,
    /// What the compiled original has that it does not, for the note.
    pub leaves_out: &'static str,
    /// Its files, by path in the plugin folder.
    pub files: &'static [(&'static str, &'static str)],
}

macro_rules! file {
    ($project:literal, $path:literal) => {
        ($path, include_str!(concat!("../replacements/", $project, "/", $path)))
    };
}

const REPLACEMENTS: &[Replacement] = &[
    Replacement {
        project: "fastuuid",
        follows: "0.14.0",
        leaves_out: "",
        files: &[file!("fastuuid", "fastuuid/__init__.py"), file!("fastuuid", "fastuuid/fastuuid.py")],
    },
    Replacement {
        project: "jiter",
        follows: "0.19.0",
        leaves_out: "its string cache",
        files: &[file!("jiter", "jiter/__init__.py")],
    },
    Replacement {
        project: "orjson",
        follows: "3.13.0",
        leaves_out: "numpy arrays",
        files: &[file!("orjson", "orjson/__init__.py")],
    },
    Replacement {
        project: "rpds-py",
        follows: "2026.9.1",
        leaves_out: "",
        files: &[file!("rpds-py", "rpds/__init__.py")],
    },
    Replacement {
        project: "uuid-utils",
        follows: "1.3.0",
        leaves_out: "",
        files: &[file!("uuid-utils", "uuid_utils/__init__.py"), file!("uuid-utils", "uuid_utils/compat/__init__.py")],
    },
];

impl Replacement {
    /// Its files as `path -> source`.
    pub fn sources(&self) -> BTreeMap<String, String> {
        self.files.iter().map(|(p, s)| ((*p).to_string(), (*s).to_string())).collect()
    }

    /// Its top-level module and package names.
    pub fn roots(&self) -> Vec<String> {
        let mut out: Vec<String> = self.files.iter().map(|(p, _)| p.split('/').next().unwrap_or(p).trim_end_matches(".py").to_string()).collect();
        out.dedup();
        out
    }

    /// The URL of its sources.
    pub fn source(&self) -> String {
        format!("{SOURCE}/{}", self.project)
    }

    /// What to tell the person installing [name] [version] in its place.
    pub fn note(&self, name: &str, version: &str) -> String {
        let mut s = format!("{name} {version} is compiled code, so the keyboard's pure-Python stand-in went in its place: the same API");
        if version != self.follows {
            s.push_str(&format!(" (written against {name} {})", self.follows));
        }
        s.push_str(", slower");
        if !self.leaves_out.is_empty() {
            s.push_str(", without ");
            s.push_str(self.leaves_out);
        }
        s
    }
}

/// Every stand-in this build carries.
pub fn all() -> &'static [Replacement] {
    REPLACEMENTS
}

/// The stand-in for [project] (a PEP 503 name), if there is one.
pub fn for_project(project: &str) -> Option<&'static Replacement> {
    REPLACEMENTS.iter().find(|r| r.project == project)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_stand_in_parses_and_names_its_roots() {
        for r in all() {
            assert!(!r.files.is_empty(), "{}", r.project);
            for (path, source) in r.files {
                assert!(crate::is_importable_path(path), "{}: {path}", r.project);
                assert!(!source.trim().is_empty(), "{}: {path}", r.project);
            }
            assert!(!r.roots().is_empty() && !r.follows.is_empty(), "{}", r.project);
        }
        assert_eq!(for_project("rpds-py").map(|r| r.roots()), Some(vec!["rpds".to_string()]));
        assert_eq!(for_project("uuid-utils").map(|r| r.roots()), Some(vec!["uuid_utils".to_string()]));
        assert!(for_project("numpy").is_none());
        assert!(LICENSE.starts_with("MIT License"));
    }

    #[test]
    fn every_stand_in_passes_the_compatibility_check() {
        for r in all() {
            let report = crate::analyse(&r.sources(), &crate::AnalyseOptions { package: r.project.to_string(), ..Default::default() });
            let errors: Vec<String> = report.problems().filter(|p| p.severity == crate::Severity::Error).map(|p| format!("{}:{:?} {}", p.file, p.line, p.message)).collect();
            assert!(errors.is_empty(), "{}: {errors:?}", r.project);
        }
    }
}
