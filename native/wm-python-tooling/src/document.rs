//! One version of a script, read every way the editor needs: tokens, lines,
//! structure, the tolerant tree and its analysis.

use crate::analysis::Analysis;
use crate::ast::Module;
use crate::lexer::{lex, Tokens};
use crate::parser::parse;
use crate::structure::Structure;
use crate::text::{units_of, Lines, Units};

pub struct Document {
    pub tokens: Tokens,
    pub lines: Lines,
    pub structure: Structure,
    pub module: Module,
    pub analysis: Analysis,
}

impl Document {
    pub fn new(source: &str) -> Document {
        Document::from_units(units_of(source))
    }

    pub fn from_units(units: Units) -> Document {
        let lines = Lines::of(&units);
        let len = units.len() as u32;
        let tokens = lex(units);
        let structure = Structure::of(&tokens);
        let module = parse(&tokens, &structure);
        let analysis = Analysis::of(&module, len);
        Document { tokens, lines, structure, module, analysis }
    }

    pub fn units(&self) -> &[u16] {
        &self.tokens.src
    }

    pub fn len(&self) -> u32 {
        self.tokens.src.len() as u32
    }

    pub fn is_empty(&self) -> bool {
        self.tokens.src.is_empty()
    }

    /// Whether the parser read every line.
    pub fn parses(&self) -> bool {
        self.module.errors.is_empty()
    }
}

/// What the manifest being edited declares. A field left as None skips the
/// checks that depend on it.
#[derive(Clone, Debug, Default)]
pub struct Host {
    pub storage: Option<bool>,
    pub reminders: Option<bool>,
    pub selection: Option<bool>,
    pub panel: Option<bool>,
    pub actions: Option<Vec<String>>,
    /// The plugin's own other modules, by dotted name: `helpers`, `pkg.util`.
    pub modules: Option<Vec<String>>,
    /// The interpreter's own verdict on whether the script will run.
    pub verdict: Option<Verdict>,
    /// The manifest's `apiVersion`.
    pub api: Option<i64>,
    /// Every permission the manifest declares, as it spells them: `network`, `clipboard.read`.
    pub permissions: Option<Vec<String>>,
    /// The websites `wm.http` may reach: `api.example.com`, `*.example.org`.
    pub hosts: Option<Vec<String>>,
    /// The manifest's providers, as `(tool, id)` with the id defaulted to the tool.
    pub providers: Option<Vec<(String, String)>>,
    /// The manifest's settings.
    pub settings: Option<Vec<SettingSpec>>,
    /// `tool.keyword`, empty when the plugin has none.
    pub keyword: Option<String>,
}

/// One row of the manifest's settings page.
#[derive(Clone, Debug, Default)]
pub struct SettingSpec {
    pub key: String,
    /// `switch`, `text`, `number`, `choice`, `host` or `secret`.
    pub kind: String,
    pub label: String,
    pub options: Vec<String>,
}

impl Host {
    /// Whether the plugin is written for the API 5 `wm` package.
    pub fn api5(&self) -> bool {
        self.api.is_some_and(|v| v >= 5)
    }

    /// Whether the manifest declares [permission]; None when the list is not known.
    pub fn has_permission(&self, permission: &str) -> Option<bool> {
        self.permissions.as_ref().map(|p| p.iter().any(|x| x == permission))
    }

    /// Whether [host] is one `wm.http` may reach: listed, or under a `*.` entry. None when unknown.
    pub fn allows_host(&self, host: &str) -> Option<bool> {
        let allowed = self.hosts.as_ref()?;
        // A host setting adds whatever server the user types.
        if self.settings.as_ref().is_some_and(|s| s.iter().any(|x| x.kind == "host")) {
            return Some(true);
        }
        let h = host.trim_end_matches('.').to_lowercase();
        Some(allowed.iter().any(|entry| {
            let entry = entry.trim().to_lowercase();
            match entry.strip_prefix("*.") {
                Some(base) => h == base || h.ends_with(&format!(".{base}")),
                None => h == entry,
            }
        }))
    }

    pub fn setting(&self, key: &str) -> Option<&SettingSpec> {
        self.settings.as_ref()?.iter().find(|s| s.key == key)
    }
}

#[derive(Clone, Debug)]
pub struct Verdict {
    pub message: String,
    pub kind: String,
    /// 1-based, as the interpreter counts lines.
    pub line: Option<usize>,
}
