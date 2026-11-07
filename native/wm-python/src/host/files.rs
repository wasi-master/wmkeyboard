//! `wm.storage` and `wm.reminders`: the same JSON files, the same quotas and
//! the same refusal messages as Kotlin's `PluginStorage` and `PluginReminders`.
//!
//! Neither caches across calls. The app's storage inspector edits the same
//! `storage.json` while a plugin runs, and an alarm takes due reminders off
//! `reminders.json`, so every call reads the file and every change writes it
//! straight back, atomically (`<name>.part`, then rename). Lengths are UTF-16
//! units, as Kotlin's `String.length` counts them.
//!
//! Formats, as kotlinx.serialization writes them (compact, insertion order):
//!
//! * `storage.json`: `{"key":"value",...}`. A file that does not decode as an
//!   object of strings reads as empty. Reading keeps at most 128 entries and
//!   skips keys over 64 units and values over 8,192.
//! * `reminders.json`: `[{"id":"a","at":1767225600000,"text":"..."},...]`,
//!   `at` in milliseconds since the epoch. Missing fields default to `""`/`0`
//!   and unknown fields are ignored. Reading drops entries with an empty or
//!   over-long id or over-long text, keeps the first of each id, and stops at
//!   64. An empty list deletes the file.

use std::fs;
use std::path::{Path, PathBuf};

use indexmap::IndexMap;

use crate::interp::{display_text, utf16_len};

pub const MAX_KEY_LENGTH: usize = 64;
pub const MAX_VALUE_LENGTH: usize = 8 * 1024;
pub const MAX_KEYS: usize = 128;
pub const MAX_TOTAL_BYTES: usize = 64 * 1024;

pub const MAX_REMINDERS: usize = 64;
pub const MAX_ID_LENGTH: usize = 64;
pub const MAX_TEXT_LENGTH: usize = 200;
/// Ten years. Far enough for any due date, near enough that the arithmetic stays exact.
pub const MAX_AHEAD_MILLIS: i64 = 10 * 366 * 24 * 60 * 60 * 1000;

/// A JSON string the way kotlinx.serialization quotes it: `"` `\` and the C0
/// controls escaped (`\b \t \n \f \r`, else `\u00XX` in lowercase hex), the
/// rest written as is.
pub fn kotlin_quoted(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\u{8}' => out.push_str("\\b"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\u{c}' => out.push_str("\\f"),
            '\r' => out.push_str("\\r"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Write [text] to [target] through `<name>.part` and a rename.
fn write_atomically(target: &Path, text: &str) {
    if let Some(parent) = target.parent() {
        let _ = fs::create_dir_all(parent);
    }
    let mut part = target.as_os_str().to_owned();
    part.push(".part");
    let part = PathBuf::from(part);
    if fs::write(&part, text).is_ok() && fs::rename(&part, target).is_err() {
        let _ = fs::remove_file(target);
        let _ = fs::rename(&part, target);
    }
}

// MARK: - storage

/// A plugin's string-to-string store, on disk or (with no path) in memory.
pub struct Storage {
    path: Option<PathBuf>,
    memory: IndexMap<String, String>,
}

impl Storage {
    pub fn new(path: Option<PathBuf>) -> Storage {
        Storage { path, memory: IndexMap::new() }
    }

    fn load(&self) -> IndexMap<String, String> {
        let Some(path) = &self.path else { return self.memory.clone() };
        let mut out = IndexMap::new();
        let Ok(text) = fs::read_to_string(path) else { return out };
        let Ok(serde_json::Value::Object(map)) = serde_json::from_str::<serde_json::Value>(&text) else { return out };
        if !map.values().all(|v| v.is_string()) {
            return out;
        }
        for (k, v) in map {
            if out.len() >= MAX_KEYS {
                break;
            }
            let v = v.as_str().unwrap().to_string();
            if utf16_len(&k) <= MAX_KEY_LENGTH && utf16_len(&v) <= MAX_VALUE_LENGTH {
                out.insert(k, v);
            }
        }
        out
    }

    fn save(&mut self, entries: IndexMap<String, String>) {
        match &self.path {
            None => self.memory = entries,
            Some(path) => {
                let body: Vec<String> =
                    entries.iter().map(|(k, v)| format!("{}:{}", kotlin_quoted(k), kotlin_quoted(v))).collect();
                write_atomically(path, &format!("{{{}}}", body.join(",")));
            }
        }
    }

    fn used(entries: &IndexMap<String, String>) -> usize {
        entries.iter().map(|(k, v)| utf16_len(k) + utf16_len(v)).sum()
    }

    /// Why a write was refused, or Ok when it went through.
    pub fn set(&mut self, key: &str, value: &str) -> Result<(), String> {
        let mut entries = self.load();
        if key.is_empty() {
            return Err("storage keys can't be empty".into());
        }
        if utf16_len(key) > MAX_KEY_LENGTH {
            return Err(format!("storage keys can't be longer than {MAX_KEY_LENGTH} characters"));
        }
        if utf16_len(value) > MAX_VALUE_LENGTH {
            return Err(format!("that value is larger than the {} KB limit for one key", MAX_VALUE_LENGTH / 1024));
        }
        if !entries.contains_key(key) && entries.len() >= MAX_KEYS {
            return Err(format!("this plugin already has the maximum of {MAX_KEYS} stored keys"));
        }
        // A new key costs its name as well as its value; overwriting one only
        // costs the difference between the old value and the new.
        let delta = match entries.get(key) {
            None => (utf16_len(key) + utf16_len(value)) as i64,
            Some(old) => utf16_len(value) as i64 - utf16_len(old) as i64,
        };
        if Self::used(&entries) as i64 + delta > MAX_TOTAL_BYTES as i64 {
            return Err(format!("this plugin has used all {} KB of its storage", MAX_TOTAL_BYTES / 1024));
        }
        entries.insert(key.to_string(), value.to_string());
        self.save(entries);
        Ok(())
    }

    pub fn get(&self, key: &str) -> Option<String> {
        self.load().get(key).cloned()
    }

    pub fn remove(&mut self, key: &str) {
        let mut entries = self.load();
        if entries.shift_remove(key).is_some() {
            self.save(entries);
        }
    }

    pub fn keys(&self) -> Vec<String> {
        self.load().keys().cloned().collect()
    }

    pub fn used_bytes(&self) -> usize {
        Self::used(&self.load())
    }
}

// MARK: - reminders

/// One pending reminder. [at] is milliseconds since the epoch.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Reminder {
    pub id: String,
    pub at: i64,
    pub text: String,
}

/// The reminders one plugin asked for, on disk or (with no path) in memory.
pub struct Reminders {
    path: Option<PathBuf>,
    memory: Vec<Reminder>,
}

impl Reminders {
    pub fn new(path: Option<PathBuf>) -> Reminders {
        Reminders { path, memory: Vec::new() }
    }

    fn read(&self) -> Vec<Reminder> {
        let Some(path) = &self.path else { return self.memory.clone() };
        let Ok(text) = fs::read_to_string(path) else { return Vec::new() };
        let Ok(serde_json::Value::Array(items)) = serde_json::from_str::<serde_json::Value>(&text) else {
            return Vec::new();
        };
        let mut decoded = Vec::new();
        for item in items {
            let serde_json::Value::Object(o) = item else { return Vec::new() };
            let id = match o.get("id") {
                None => String::new(),
                Some(serde_json::Value::String(s)) => s.clone(),
                Some(_) => return Vec::new(),
            };
            let at = match o.get("at") {
                None => 0,
                Some(v) => match v.as_i64() {
                    Some(n) => n,
                    None => return Vec::new(),
                },
            };
            let text = match o.get("text") {
                None => String::new(),
                Some(serde_json::Value::String(s)) => s.clone(),
                Some(_) => return Vec::new(),
            };
            decoded.push(Reminder { id, at, text });
        }
        // A file edited by hand, or written by a later version, is read as far
        // as it makes sense and no further.
        let mut out: Vec<Reminder> = Vec::new();
        for r in decoded {
            if r.id.is_empty() || utf16_len(&r.id) > MAX_ID_LENGTH || utf16_len(&r.text) > MAX_TEXT_LENGTH {
                continue;
            }
            if out.iter().any(|o| o.id == r.id) {
                continue;
            }
            if out.len() >= MAX_REMINDERS {
                break;
            }
            out.push(r);
        }
        out
    }

    fn write(&mut self, list: Vec<Reminder>) {
        let Some(path) = &self.path else {
            self.memory = list;
            return;
        };
        if list.is_empty() {
            let _ = fs::remove_file(path);
            return;
        }
        let body: Vec<String> = list
            .iter()
            .map(|r| format!("{{\"id\":{},\"at\":{},\"text\":{}}}", kotlin_quoted(&r.id), r.at, kotlin_quoted(&r.text)))
            .collect();
        write_atomically(path, &format!("[{}]", body.join(",")));
    }

    /// Sets or replaces the reminder [id], to show [text] at [at_millis].
    /// Returns why it was refused, in words for the script.
    pub fn set(&mut self, id: &str, at_millis: i64, text: &str, now_millis: i64) -> Result<(), String> {
        if id.is_empty() {
            return Err("a reminder id can't be empty".into());
        }
        if utf16_len(id) > MAX_ID_LENGTH {
            return Err(format!("reminder ids can't be longer than {MAX_ID_LENGTH} characters"));
        }
        // Cleaned the way a plugin's name is, because it is drawn in the shade
        // under that name: no control characters, no direction overrides.
        let shown = display_text(text, MAX_TEXT_LENGTH);
        if shown.is_empty() {
            return Err("a reminder needs some text to show".into());
        }
        if at_millis <= now_millis {
            return Err("that time has already passed".into());
        }
        if at_millis.saturating_sub(now_millis) > MAX_AHEAD_MILLIS {
            return Err("a reminder can't be more than ten years ahead".into());
        }
        let mut list = self.read();
        let existing = list.iter().position(|r| r.id == id);
        if existing.is_none() && list.len() >= MAX_REMINDERS {
            return Err(format!("this plugin already has the maximum of {MAX_REMINDERS} reminders"));
        }
        let reminder = Reminder { id: id.to_string(), at: at_millis, text: shown };
        match existing {
            Some(i) => list[i] = reminder,
            None => list.push(reminder),
        }
        self.write(list);
        Ok(())
    }

    /// Takes the reminder [id] off the list. True when there was one.
    pub fn cancel(&mut self, id: &str) -> bool {
        let mut list = self.read();
        let before = list.len();
        list.retain(|r| r.id != id);
        let removed = list.len() != before;
        if removed {
            self.write(list);
        }
        removed
    }

    /// Every pending reminder, soonest first.
    pub fn list(&self) -> Vec<Reminder> {
        let mut l = self.read();
        l.sort_by_key(|r| r.at);
        l
    }
}
