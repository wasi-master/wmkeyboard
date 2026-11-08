//! WM Keyboard's plugin language.
//!
//! * [`interp`]: a port of PyMini 2.0.1 (MIT, Copyright (c) 2026 Anti Limited,
//!   <https://github.com/anti-ltd/PyMini>), the small Python interpreter Clink
//!   runs its keyboard actions on. Semantics follow PyMini, not CPython.
//! * [`host`]: what a plugin can reach beyond its own arithmetic: the `wm` and
//!   `ui` globals, storage and reminders files, and the widget-tree codec.
//!
//! The editor's language service lives in the sibling crate `wm-python-tooling`,
//! which does not depend on this one: front ends hand it this crate's
//! [`interp::check`] verdict.
//!
//! Every boundary out of this crate is plain data (strings, JSON, UTF-16
//! offsets), so the JNI, wasm and CLI front ends stay thin.

pub mod host;
pub mod interp;
