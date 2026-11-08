//! The web plugin editor's half of `wm-python` and `wm-python-tooling`: the
//! same surface `wm-python-jni` gives the app, through wasm-bindgen.
//!
//! JS side: `docs/src/store/lib/python.ts`, over the `--target web` glue that
//! `build.sh` writes to `docs/src/store/wasm/`. Every call takes and returns
//! plain strings (JSON where structured); offsets are UTF-16 code units, which
//! is what JS strings index by.
//!
//! [`PySession`] wraps `wm_python::host::Session` and answers with its result
//! JSON (see the module docs there). The web keeps a plugin's storage in the
//! page: the editor seeds it from localStorage (`"storage"` in the config),
//! reads it back after every call ([`PySession::storage`]) and edits it from
//! its storage pane ([`PySession::storage_set`]). Storage and reminders always
//! live in memory; the config's paths are dropped.
//!
//! A browser has no `Instant`, so a call has no wall-clock deadline here: the
//! step budget of each phase is what stops a runaway plugin. The time a phase
//! took is still measured and reported.
//!
//! A panic traps (`panic = "abort"`): the JS call throws, and the caller must
//! drop the session, since its interpreter may be half way through a change.

use std::sync::atomic::Ordering;

use serde_json::{json, Value as Json};
use wasm_bindgen::prelude::*;
use wm_python::host::Session;
use wm_python::interp::Clock;

#[wasm_bindgen]
extern "C" {
    #[wasm_bindgen(js_namespace = performance, js_name = now)]
    fn performance_now() -> f64;
}

/// The page's clock and the browser's time zone.
struct WebClock;

impl Clock for WebClock {
    fn now(&self) -> f64 {
        js_sys::Date::now() / 1000.0
    }

    fn monotonic(&self) -> f64 {
        performance_now() / 1000.0
    }

    fn utc_offset(&self, unix_seconds: i64) -> i32 {
        let date = js_sys::Date::new(&JsValue::from_f64(unix_seconds as f64 * 1000.0));
        let minutes = date.get_timezone_offset();
        if minutes.is_finite() {
            (-minutes * 60.0) as i32
        } else {
            0
        }
    }
}

/// A running plugin: `wm_python::host::Session`, with the result JSON its
/// docs describe, plus `"storage": {"key": "value", ...}` in the config to
/// start the in-memory store with.
#[wasm_bindgen]
pub struct PySession {
    inner: Session,
}

#[wasm_bindgen]
impl PySession {
    #[wasm_bindgen(constructor)]
    pub fn new(config_json: &str) -> Result<PySession, JsError> {
        // Storage and reminders always live in memory here.
        let mut config: Json = serde_json::from_str(config_json).map_err(|e| JsError::new(&e.to_string()))?;
        if let Some(object) = config.as_object_mut() {
            object.remove("storagePath");
            object.remove("remindersPath");
        }
        let inner = Session::with_clock(&config.to_string(), Box::new(WebClock)).map_err(|e| JsError::new(&e))?;
        Ok(PySession { inner })
    }

    /// Load the plugin's source: panel mode runs the top level, then renders.
    pub fn load(&mut self, source: &str) -> String {
        self.inner.load(source)
    }

    /// Render the panel again.
    pub fn render(&mut self) -> String {
        self.inner.render()
    }

    /// Hand `{"type", "id", "value"?, "index"?}` to `on_event`, then render.
    pub fn event(&mut self, event_json: &str) -> String {
        self.inner.event(event_json)
    }

    /// Run the plugin's `transform` on a selection.
    pub fn transform(&mut self, text: &str, action_id: &str) -> String {
        self.inner.transform(text, action_id)
    }

    /// The latest phase's usage.
    pub fn usage(&self) -> String {
        self.inner.usage()
    }

    /// Stops the call in flight. Only a host that runs the session off the
    /// page's thread (a worker) can call it mid-call.
    pub fn cancel(&self) {
        self.inner.cancel_handle().store(true, Ordering::SeqCst);
    }

    /// The plugin's store as `{"key": "value", ...}`, in insertion order;
    /// `{}` without the storage permission.
    pub fn storage(&self) -> String {
        self.inner.storage_json()
    }

    /// Write one key between calls, under the plugin's quotas. Returns why it
    /// was refused, or nothing when it went through.
    #[wasm_bindgen(js_name = storageSet)]
    pub fn storage_set(&mut self, key: &str, value: &str) -> Option<String> {
        self.inner.storage_set(key, value).err()
    }

    #[wasm_bindgen(js_name = storageRemove)]
    pub fn storage_remove(&mut self, key: &str) {
        self.inner.storage_remove(key);
    }
}

// ---- the editor's tooling -------------------------------------------------

/// The interpreter's "will it run" verdict: nothing, or `{"message", "kind", "line"}`.
#[wasm_bindgen]
pub fn check(source: &str) -> Option<String> {
    wm_python::interp::check(source).err().map(|e| json!({"message": e.message, "kind": e.kind, "line": e.line}).to_string())
}

/// Triples `word, start, end`; `word = kind | unterminated << 8 | name_style << 12`.
#[wasm_bindgen]
pub fn tokens(source: &str) -> Vec<i32> {
    wm_python_tooling::api_json::tokens(source)
}

#[wasm_bindgen]
pub fn analyse(source: &str, host: &str) -> String {
    wm_python_tooling::api_json::analyse(source, host)
}

#[wasm_bindgen]
pub fn complete(source: &str, caret: i32, explicit: bool, host: &str) -> String {
    wm_python_tooling::api_json::complete(source, i64::from(caret), explicit, host)
}

#[wasm_bindgen]
pub fn format(source: &str) -> String {
    wm_python_tooling::api_json::format(source)
}

#[wasm_bindgen]
pub fn definition(source: &str, caret: i32) -> String {
    wm_python_tooling::api_json::definition(source, i64::from(caret))
}

#[wasm_bindgen]
pub fn references(source: &str, caret: i32) -> String {
    wm_python_tooling::api_json::references(source, i64::from(caret))
}

#[wasm_bindgen]
pub fn rename(source: &str, caret: i32, new_name: &str) -> String {
    wm_python_tooling::api_json::rename(source, i64::from(caret), new_name)
}

#[wasm_bindgen(js_name = apiAt)]
pub fn api_at(source: &str, caret: i32) -> String {
    wm_python_tooling::api_json::api_at(source, i64::from(caret))
}

#[wasm_bindgen(js_name = apiEntries)]
pub fn api_entries() -> String {
    wm_python_tooling::api_json::api_entries()
}

#[wasm_bindgen(js_name = inInert)]
pub fn in_inert(source: &str, offset: i32) -> bool {
    wm_python_tooling::api_json::in_inert(source, i64::from(offset))
}

#[wasm_bindgen(js_name = opensBlock)]
pub fn opens_block(line: &str) -> bool {
    wm_python_tooling::api_json::opens_block(line)
}

#[wasm_bindgen(js_name = diagnosticCodes)]
pub fn diagnostic_codes() -> String {
    wm_python_tooling::api_json::diagnostic_codes()
}
