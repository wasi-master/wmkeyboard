//! JNI bridge from the Android app to `wm-python` (the plugin interpreter and
//! its host) and `wm-python-tooling` (the plugin editor's language service).
//!
//! Kotlin side: `com.wasimaster.wmkeyboard.core.plugins.PythonNative`. Every
//! call takes and returns plain strings (JSON where structured), so there are
//! no callbacks into the JVM and no Java objects held across calls.
//!
//! A session is a `Box<Entry>` behind a `jlong`. The interpreter itself sits
//! in a `Mutex`, used by one plugin thread at a time; the cancel flag sits
//! beside it, outside the lock, so `nativeCancel` from the watchdog never
//! waits on the script it is stopping. Kotlin guarantees `nativeCancel` and
//! `nativeClose` never overlap (`PythonSession.handleLock`), and that close
//! waits for any call in flight.
//!
//! Every entry point catches panics. A bug in the interpreter answers with an
//! `internal` error instead of taking the keyboard's process down.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};
use wm_python::host::Session;
use wm_python::interp::{check, Clock, SystemClock};

struct Entry {
    session: Mutex<Session>,
    cancel: Arc<AtomicBool>,
}

/// The device's zone, as the offsets Kotlin read from `TimeZone`: each
/// `(from_unix_seconds, offset_seconds)`, sorted, the first one covering
/// everything before it.
struct ZoneClock {
    system: SystemClock,
    offsets: Vec<(i64, i32)>,
}

impl Clock for ZoneClock {
    fn now(&self) -> f64 {
        self.system.now()
    }

    fn monotonic(&self) -> f64 {
        self.system.monotonic()
    }

    fn utc_offset(&self, unix_seconds: i64) -> i32 {
        match self.offsets.partition_point(|(from, _)| *from <= unix_seconds) {
            0 => self.offsets.first().map_or(0, |o| o.1),
            n => self.offsets[n - 1].1,
        }
    }
}

fn zone_of(config: &str) -> Vec<(i64, i32)> {
    let parsed: Value = serde_json::from_str(config).unwrap_or(Value::Null);
    let mut offsets: Vec<(i64, i32)> = parsed
        .get("zone")
        .and_then(Value::as_array)
        .map(|rows| {
            rows.iter()
                .filter_map(|row| {
                    let from = row.get(0)?.as_i64()?;
                    let offset = i32::try_from(row.get(1)?.as_i64()?).ok()?;
                    Some((from, offset))
                })
                .collect()
        })
        .unwrap_or_default();
    offsets.sort_by_key(|o| o.0);
    offsets
}

fn internal(message: &str) -> String {
    json!({
        "ok": false,
        "error": {"kind": "internal", "message": message, "line": null, "abort": null},
        "log": [], "inputs": [], "remindersChanged": false, "phases": []
    })
    .to_string()
}

fn text(env: &mut JNIEnv, value: &JString) -> String {
    if value.is_null() {
        return String::new();
    }
    env.get_string(value).map(Into::into).unwrap_or_default()
}

fn out(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// Runs [body] on the session behind [handle], catching any panic.
fn with_session(handle: jlong, body: impl FnOnce(&mut Session) -> String) -> String {
    if handle == 0 {
        return internal("session closed");
    }
    // SAFETY: the handle came from nativeCreate and Kotlin frees it only once,
    // after every call on it has returned.
    let entry = unsafe { &*(handle as *const Entry) };
    let result = catch_unwind(AssertUnwindSafe(|| {
        let mut session = match entry.session.lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        };
        body(&mut session)
    }));
    result.unwrap_or_else(|_| internal("the interpreter failed"))
}

macro_rules! guarded {
    ($env:ident, $body:expr) => {{
        let value: String = catch_unwind(AssertUnwindSafe(|| $body)).unwrap_or_else(|_| String::new());
        out(&mut $env, &value)
    }};
}

// ---- sessions -------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    config: JString,
) -> jlong {
    let config = text(&mut env, &config);
    catch_unwind(|| {
        let clock = ZoneClock { system: SystemClock::default(), offsets: zone_of(&config) };
        match Session::with_clock(&config, Box::new(clock)) {
            Ok(session) => {
                let cancel = session.cancel_handle();
                Box::into_raw(Box::new(Entry { session: Mutex::new(session), cancel })) as jlong
            }
            Err(_) => 0,
        }
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    source: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let result = with_session(handle, |s| s.load(&source));
    out(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeRender(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = with_session(handle, |s| s.render());
    out(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeEvent(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    event: JString,
) -> jstring {
    let event = text(&mut env, &event);
    let result = with_session(handle, |s| s.event(&event));
    out(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeTransform(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    input: JString,
    action_id: JString,
) -> jstring {
    let input = text(&mut env, &input);
    let action_id = text(&mut env, &action_id);
    let result = with_session(handle, |s| s.transform(&input, &action_id));
    out(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeUsage(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = with_session(handle, |s| s.usage());
    out(&mut env, &result)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: as in with_session; Kotlin never cancels a freed handle.
    let entry = unsafe { &*(handle as *const Entry) };
    entry.cancel.store(true, Ordering::SeqCst);
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the one place a handle is freed, once, with no call in flight.
    let _ = catch_unwind(|| drop(unsafe { Box::from_raw(handle as *mut Entry) }));
}

// ---- the editor's tooling -------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let verdict = catch_unwind(|| match check(&source) {
        Ok(()) => None,
        Err(e) => Some(json!({"message": e.message, "kind": e.kind, "line": e.line}).to_string()),
    })
    .unwrap_or(None);
    match verdict {
        Some(v) => out(&mut env, &v),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeTokens(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jintArray {
    let source = text(&mut env, &source);
    let packed = catch_unwind(|| wm_python_tooling::api_json::tokens(&source)).unwrap_or_default();
    let Ok(array) = env.new_int_array(packed.len() as jint) else { return std::ptr::null_mut() };
    if env.set_int_array_region(&array, 0, &packed).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeAnalyse(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    host: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let host = text(&mut env, &host);
    guarded!(env, wm_python_tooling::api_json::analyse(&source, &host))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeComplete(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    caret: jint,
    explicit: jboolean,
    host: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let host = text(&mut env, &host);
    guarded!(env, wm_python_tooling::api_json::complete(&source, i64::from(caret), explicit == JNI_TRUE, &host))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeFormat(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let formatted = catch_unwind(AssertUnwindSafe(|| wm_python_tooling::api_json::format(&source)))
        .unwrap_or_else(|_| source.clone());
    out(&mut env, &formatted)
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeDefinition(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    caret: jint,
) -> jstring {
    let source = text(&mut env, &source);
    guarded!(env, wm_python_tooling::api_json::definition(&source, i64::from(caret)))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeReferences(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    caret: jint,
) -> jstring {
    let source = text(&mut env, &source);
    guarded!(env, wm_python_tooling::api_json::references(&source, i64::from(caret)))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeRename(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    caret: jint,
    new_name: JString,
) -> jstring {
    let source = text(&mut env, &source);
    let new_name = text(&mut env, &new_name);
    guarded!(env, wm_python_tooling::api_json::rename(&source, i64::from(caret), &new_name))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeApiAt(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    caret: jint,
) -> jstring {
    let source = text(&mut env, &source);
    guarded!(env, wm_python_tooling::api_json::api_at(&source, i64::from(caret)))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_plugins_PythonNative_nativeApiEntries(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guarded!(env, wm_python_tooling::api_json::api_entries())
}
