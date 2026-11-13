//! The plugin editor's language service for WM Keyboard's Python subset
//! (PyMini): a lossless lexer, indentation structure, a tolerant parser,
//! scope analysis, diagnostics, completion, a formatter, navigation, folding,
//! highlighting data and the plugin API model with its docs.
//!
//! Every position is a UTF-16 code-unit offset into the source, and every span
//! is half-open, because both consumers (Kotlin on Android, JavaScript on the
//! web) index their strings in UTF-16. Every public function is total: no input
//! makes it panic.
//!
//! The JSON entry points the JNI and wasm wrappers call are in [`api_json`].
//! This crate does not depend on the interpreter: whether a script will run is
//! the interpreter's verdict, passed in.

pub mod analysis;
pub mod api;
pub mod api_json;
pub mod ast;
mod checks;
pub mod complete;
pub mod diagnostics;
pub mod document;
pub mod format;
pub mod highlight;
mod infer;
pub mod lexer;
pub mod navigate;
pub mod package;
pub mod parser;
pub mod structure;
pub mod text;

pub use document::{Document, Host, Verdict};
