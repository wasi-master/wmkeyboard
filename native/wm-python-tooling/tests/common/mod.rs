#![allow(dead_code)]

use wm_python_tooling::diagnostics::{diagnose, Diagnostic, Severity};
use wm_python_tooling::document::{Document, Host, SettingSpec};

pub const FIXTURES: &[&str] = &["cipher", "todo", "text_actions", "text_tools", "kitchen_sink", "text_generator", "weather"];

pub fn fixture(name: &str) -> String {
    std::fs::read_to_string(format!("{}/tests/fixtures/{name}.py", env!("CARGO_MANIFEST_DIR"))).unwrap()
}

/// The host each fixture's plugin.json describes.
pub fn fixture_host(name: &str) -> Host {
    let mut host = Host { storage: Some(false), reminders: Some(false), selection: Some(false), panel: Some(true), actions: Some(vec![]), api: Some(4), ..Host::default() };
    match name {
        "weather" => {
            host.storage = Some(true);
            host.api = Some(5);
            host.permissions = Some(vec!["network".into(), "storage".into()]);
            host.hosts = Some(vec!["api.open-meteo.com".into(), "geocoding-api.open-meteo.com".into()]);
            host.providers = Some(vec![]);
            host.keyword = Some(String::new());
            host.settings = Some(vec![SettingSpec { key: "units".into(), kind: "choice".into(), label: "Units".into(), options: vec!["metric".into(), "imperial".into()] }]);
        }
        "text_generator" => {
            host.api = Some(5);
            host.permissions = Some(vec![]);
            host.hosts = Some(vec![]);
            host.providers = Some(vec![]);
            host.settings = Some(vec![]);
            host.keyword = Some(String::new());
        }
        "todo" => {
            host.storage = Some(true);
            host.reminders = Some(true);
        }
        "text_actions" => {
            host.storage = Some(true);
            host.selection = Some(true);
            host.actions = Some(vec!["rot13".into(), "quote".into()]);
        }
        _ => {}
    }
    host
}

/// The source with its `|` taken out, and the UTF-16 offset where it was.
pub fn caret(marked: &str) -> (String, u32) {
    let at = marked.find('|').expect("a | caret marker");
    let source = format!("{}{}", &marked[..at], &marked[at + 1..]);
    (source, marked[..at].encode_utf16().count() as u32)
}

pub fn utf16_slice(source: &str, start: u32, end: u32) -> String {
    let units: Vec<u16> = source.encode_utf16().collect();
    String::from_utf16_lossy(&units[start as usize..end as usize])
}

pub fn diagnostics(source: &str, host: &Host) -> Vec<Diagnostic> {
    diagnose(&Document::new(source), host).diagnostics
}

pub fn codes(source: &str, host: &Host) -> Vec<&'static str> {
    diagnostics(source, host).iter().map(|d| d.code.name()).collect()
}

pub fn serious(source: &str, host: &Host) -> Vec<String> {
    diagnostics(source, host)
        .iter()
        .filter(|d| d.severity() != Severity::Info)
        .map(|d| format!("{} {:?} at {}..{} {:?}", d.code.name(), d.args, d.span.start, d.span.end, utf16_slice(source, d.span.start, d.span.end)))
        .collect()
}

/// A tiny deterministic generator, for fuzz-style tests that must not flake.
pub struct Lcg(pub u64);

impl Lcg {
    pub fn next(&mut self) -> u64 {
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0 >> 33
    }

    pub fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
}
