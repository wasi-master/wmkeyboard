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
//! API 5 host calls go to the function given to [`PySession::set_bridge`],
//! called synchronously as `bridge(op, argsJson) -> replyJson`; the editor
//! runs sessions in a Worker so that function may block on a fetch. With
//! none set, calls are listed in the result's `calls`.
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

    /// Hand an event to the plugin, then render (see `wm_python::host`).
    pub fn event(&mut self, event_json: &str) -> String {
        self.inner.event(event_json)
    }

    /// Run the plugin's `transform` on a selection.
    pub fn transform(&mut self, text: &str, action_id: &str) -> String {
        self.inner.transform(text, action_id)
    }

    /// A file preview from the plugin's `@plugin.preview(id)`: [request] is
    /// `{"id", "name", "extension", "mime", "size", "modified", "truncated"}`.
    pub fn preview(&mut self, request: &str, data: &[u8]) -> String {
        self.inner.preview(request, data)
    }

    /// A grid thumbnail: `value` is the picture in base64, or null.
    pub fn thumbnail(&mut self, request: &str, data: &[u8]) -> String {
        self.inner.thumbnail(request, data)
    }

    /// Where API 5 host calls go: `(op, argsJson) => replyJson`, or null for none.
    #[wasm_bindgen(js_name = setBridge)]
    pub fn set_bridge(&mut self, bridge: Option<js_sys::Function>) {
        self.inner.set_bridge(bridge.map(|f| {
            Box::new(move |op: &str, args: &str| {
                let reply = f.call2(&JsValue::NULL, &JsValue::from_str(op), &JsValue::from_str(args));
                match reply.ok().and_then(|v| v.as_string()) {
                    Some(text) => text,
                    None => json!({"error": {"kind": "RuntimeError", "message": "the editor could not answer"}}).to_string(),
                }
            }) as wm_python::host::Bridge
        }));
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

/// Signature help at the caret; see `api_json::signature`.
#[wasm_bindgen]
pub fn signature(source: &str, caret: i32, host: &str) -> String {
    wm_python_tooling::api_json::signature(source, i64::from(caret), host)
}

/// What the name at an offset is; see `api_json::hover`.
#[wasm_bindgen]
pub fn hover(source: &str, offset: i32, host: &str) -> String {
    wm_python_tooling::api_json::hover(source, i64::from(offset), host)
}

/// The provider lists a plugin can answer, with a sample request each.
#[wasm_bindgen(js_name = providerTools)]
pub fn provider_tools() -> String {
    wm_python_tooling::api_json::provider_tools()
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

// ---- vendored packages (wm-python-pip) ---------------------------------------

/// `{"path": "source"}` as a map; a value that is not a string counts as an empty file.
fn files_arg(files_json: &str) -> Result<std::collections::BTreeMap<String, String>, String> {
    let v: Json = serde_json::from_str(files_json).map_err(|e| format!("files are not JSON: {e}"))?;
    let map = v.as_object().ok_or("files must be a JSON object of path to source")?;
    Ok(map.iter().map(|(k, v)| (k.clone(), v.as_str().unwrap_or("").to_string())).collect())
}

/// Whether a package can run in a plugin. [files_json] is `{"path": "source"}`
/// for the package's files (non-`.py` files may map to ""), [options_json]
/// `{"package": name, "available": [top-level names beside it], "compiled": [compiled modules left out]}`. Answers
/// `{"verdict": "ok"|"warnings"|"incompatible", "problems": [{"severity",
/// "file", "line", "col", "code", "message", "endCol"?, "help"?}], "imports":
/// {"stdlib", "internal", "missing", "wm", "available"}, "roots", "files"}`,
/// or `{"error"}` for arguments it cannot read.
#[wasm_bindgen]
pub fn pip_analyse(files_json: &str, options_json: &str) -> String {
    let files = match files_arg(files_json) {
        Ok(f) => f,
        Err(e) => return json!({ "error": e }).to_string(),
    };
    let o: Json = serde_json::from_str(options_json).unwrap_or(Json::Null);
    let opts = wm_python_pip::AnalyseOptions {
        package: o.get("package").and_then(Json::as_str).unwrap_or("").to_string(),
        available: o.get("available").and_then(Json::as_array).map(|a| a.iter().filter_map(Json::as_str).map(String::from).collect()).unwrap_or_default(),
        compiled: o.get("compiled").and_then(Json::as_array).map(|a| a.iter().filter_map(Json::as_str).map(String::from).collect()).unwrap_or_default(),
    };
    wm_python_pip::analyse(&files, &opts).to_json().to_string()
}

/// The Python version the interpreter gives as `sys.version_info`, such as
/// `"3.12.0"`: the web editor reads PyPI markers and `Requires-Python` against it.
#[wasm_bindgen]
pub fn python_version() -> String {
    let (a, b, c) = wm_python_pip::python_version();
    format!("{a}.{b}.{c}")
}

/// Applies the keyboard's patch for [project] (its PEP 503 name), if it has
/// one, to [files_json] (`{"path": "source"}`, the package's files), as
/// `wmpy pip install` does. Answers `{"files": {path: source}, "notes":
/// [what was patched or left out]}`, or `{"error"}`.
#[wasm_bindgen]
pub fn pip_patch(project: &str, files_json: &str) -> String {
    let mut files = match files_arg(files_json) {
        Ok(f) => f,
        Err(e) => return json!({ "error": e }).to_string(),
    };
    let notes = wm_python_pip::patches::apply(project, &mut files);
    json!({ "files": files, "notes": notes }).to_string()
}

/// The keyboard's pure-Python stand-in for [project] (a PEP 503 name) at
/// release [version], which `wmpy pip install` puts in when that release has
/// no pure-Python wheel. Answers `{"files": {path: source}, "license": text,
/// "roots": [str], "source": url, "note": str}`, or `{}` when it has none.
#[wasm_bindgen]
pub fn pip_stand_in(project: &str, name: &str, version: &str) -> String {
    match wm_python_pip::replacements::for_project(project) {
        Some(r) => json!({
            "files": r.sources(), "license": wm_python_pip::replacements::LICENSE, "roots": r.roots(),
            "source": r.source(), "note": r.note(name, version),
        })
        .to_string(),
        None => "{}".to_string(),
    }
}

/// The modules that carry a package's data files into a plugin, as `wmpy
/// pip install` writes them ([wm_python_pip::data]). [data_json] is
/// `{"path": base64}` for the files beside the package's modules,
/// [packages_json] the top-level packages (folders of its modules). Answers
/// `{"modules": {path: source}, "kept": n, "notes": [str]}`, or `{"error"}`.
#[wasm_bindgen]
pub fn pip_data_modules(data_json: &str, packages_json: &str) -> String {
    let data: std::collections::BTreeMap<String, String> = match serde_json::from_str(data_json) {
        Ok(d) => d,
        Err(e) => return json!({ "error": format!("data: {e}") }).to_string(),
    };
    let packages: std::collections::BTreeSet<String> = serde_json::from_str(packages_json).unwrap_or_default();
    let made = wm_python_pip::data::modules_from_base64(&data, &packages);
    json!({ "modules": made.modules, "kept": made.kept, "notes": made.notes }).to_string()
}

/// Tree-shakes the vendored packages ([roots_json], their top-level names)
/// among [files_json] (every `.py` file of the plugin), reached from [entry]
/// and the plugin's own modules; [symbols] also drops unused top-level
/// definitions. Answers `{"files", "removedFiles", "removedSymbols": [[file,
/// name]], "bytesBefore", "bytesAfter", "notes"}`, or `{"error"}`.
#[wasm_bindgen]
pub fn pip_shake(files_json: &str, entry: &str, roots_json: &str, symbols: bool) -> String {
    let files = match files_arg(files_json) {
        Ok(f) => f,
        Err(e) => return json!({ "error": e }).to_string(),
    };
    let roots: Vec<String> = serde_json::from_str(roots_json).unwrap_or_default();
    wm_python_pip::shake(&files, entry, &roots, &wm_python_pip::ShakeOptions { symbols }).to_json().to_string()
}
