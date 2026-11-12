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
}

#[derive(Clone, Debug)]
pub struct Verdict {
    pub message: String,
    pub kind: String,
    /// 1-based, as the interpreter counts lines.
    pub line: Option<usize>,
}
