//! JNI surface of divvun-runtime for the keyboard's grammar tool: open a
//! GiellaLT grammar checker bundle (`.drb`), check a text, list the languages
//! its messages come in. Kotlin side:
//! `com.wasimaster.wmkeyboard.core.grammar.DivvunGrammarNative`.
//!
//! Every entry point catches panics, so a broken bundle is a refusal rather
//! than a dead keyboard, and none throws into Java.

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, OnceLock};

use divvun_runtime::bundle::Bundle;
use divvun_runtime::modules::PipelineValue;
use futures_util::StreamExt;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use tokio::runtime::Runtime;

/// One runtime for every bundle: the pipeline's stages run as tasks on it.
/// Two workers, since a check is one short text at a time.
fn runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("divvun-grammar")
            .enable_all()
            .build()
            .expect("tokio runtime")
    })
}

/// An open bundle. Its pipeline is made once per check: a pipeline holds
/// per-run state, and making one costs little beside loading the bundle.
pub struct Checker {
    bundle: Arc<Bundle>,
    lock: Mutex<()>,
}

impl Checker {
    pub fn open(path: &str) -> Result<Checker, String> {
        let path = path.to_string();
        let bundle = runtime()
            .block_on(async move { Bundle::from_bundle(&path).await })
            .map_err(|e| e.to_string())?;
        Ok(Checker { bundle: Arc::new(bundle), lock: Mutex::new(()) })
    }

    /// The grammar checker's JSON for [text]: `{"text", "errors": [{"form",
    /// "start", "end", "error_id", "title", "description", "suggestions"}]}`,
    /// with UTF-16 offsets, its messages in the first of [locales] it has.
    pub fn check(&self, text: &str, locales: &[String]) -> Result<String, String> {
        let _guard = self.lock.lock().map_err(|e| e.to_string())?;
        let suggest = serde_json::json!({ "encoding": "utf-16", "locales": locales });
        // The config reaches each command under its id; the suggest step is
        // "suggestions" in GiellaLT's pipelines, and a bundle that names it
        // otherwise still reads the same keys at the top level.
        let mut config = suggest.clone();
        config["suggestions"] = suggest;
        let bundle = Arc::clone(&self.bundle);
        let input = text.to_string();
        runtime().block_on(async move {
            let mut pipe = bundle.create(config).await.map_err(|e| e.to_string())?;
            let mut stream = pipe.forward(PipelineValue::String(input)).await;
            while let Some(item) = stream.next().await {
                match item.map_err(|e| e.to_string())? {
                    PipelineValue::Json(v) => return serde_json::to_string(&v).map_err(|e| e.to_string()),
                    PipelineValue::String(s) => return Ok(s),
                    PipelineValue::Bytes(b) => return String::from_utf8(b).map_err(|e| e.to_string()),
                    _ => return Err("the pipeline gave no text".into()),
                }
            }
            Err("the pipeline gave no output".into())
        })
    }

    /// The locales the bundle has error messages in, as a JSON array.
    pub fn message_locales(&self) -> Result<String, String> {
        let (_, suggest) = self
            .bundle
            .command::<divvun_runtime::modules::divvun::Suggest>(None)
            .ok_or("the bundle has no grammar checker")?;
        serde_json::to_string(&suggest.message_locales()).map_err(|e| e.to_string())
    }
}

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn out(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|j| j.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// "ok:" or "err:" before the answer, so Kotlin can tell a refusal and say why.
fn tagged(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(s) => out(env, &format!("ok:{s}")),
        Err(e) => out(env, &format!("err:{e}")),
    }
}

fn caught<T>(f: impl FnOnce() -> Result<T, String>) -> Result<T, String> {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or_else(|_| Err("divvun-runtime panicked".into()))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_DivvunGrammarNative_nativeOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let Some(path) = string(&mut env, &path) else { return 0 };
    match caught(|| Checker::open(&path)) {
        Ok(checker) => Box::into_raw(Box::new(checker)) as jlong,
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_DivvunGrammarNative_nativeOpenError(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let Some(path) = string(&mut env, &path) else { return std::ptr::null_mut() };
    match caught(|| Checker::open(&path)) {
        Ok(_) => std::ptr::null_mut(),
        Err(e) => out(&mut env, &e),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_DivvunGrammarNative_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        let _ = catch_unwind(|| drop(unsafe { Box::from_raw(handle as *mut Checker) }));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_DivvunGrammarNative_nativeCheck(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    locales: JString,
) -> jstring {
    if handle == 0 {
        return tagged(&mut env, Err("no bundle".into()));
    }
    let checker = unsafe { &*(handle as *const Checker) };
    let Some(text) = string(&mut env, &text) else { return std::ptr::null_mut() };
    let locales: Vec<String> = string(&mut env, &locales)
        .map(|s| s.split(',').filter(|l| !l.is_empty()).map(str::to_string).collect())
        .unwrap_or_default();
    let result = caught(|| checker.check(&text, &locales));
    tagged(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_grammar_DivvunGrammarNative_nativeMessageLocales(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    if handle == 0 {
        return tagged(&mut env, Err("no bundle".into()));
    }
    let checker = unsafe { &*(handle as *const Checker) };
    let result = caught(|| checker.message_locales());
    tagged(&mut env, result)
}

