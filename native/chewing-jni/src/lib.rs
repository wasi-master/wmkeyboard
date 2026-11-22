//! JNI bridge to libchewing for WM Keyboard's optional Chewing engine for
//! Zhuyin. Kotlin side: `com.wasimaster.wmkeyboard.core.input.chewing.ChewingNative`.
//! The engine itself is [engine::Engine]; this file only moves strings and
//! ints across, and turns a panic into "no answer".

pub mod engine;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::Path;
use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JClass, JIntArray, JObject, JString};
use jni::sys::{jboolean, jint, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};

use engine::Engine;

/// The one engine in the process. Kotlin serialises calls already
/// (ChewingRuntime's lock); this mutex only makes that a guarantee.
struct Slot(Option<Engine>);

// SAFETY: the editor holds its dictionaries as `Box<dyn Dictionary>`, which
// is not declared `Send`, but every dictionary in it (a `Trie` behind an
// `Arc`, a `TrieBuf`) is plain owned data with no thread affinity, and the
// mutex below gives one thread at a time access to it.
unsafe impl Send for Slot {}

static ENGINE: Mutex<Slot> = Mutex::new(Slot(None));

fn with_engine<T>(f: impl FnOnce(&mut Engine) -> T) -> Option<T> {
    let mut slot = ENGINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    let engine = slot.0.as_mut()?;
    catch_unwind(AssertUnwindSafe(|| f(engine))).ok()
}

fn string(env: &mut JNIEnv<'_>, s: &JString<'_>) -> Option<String> {
    env.get_string(s).ok().map(Into::into)
}

fn jbool(b: bool) -> jboolean {
    if b { JNI_TRUE } else { JNI_FALSE }
}

/// Opens libchewing on the dictionary at `dict` with the learned phrases in
/// `user`. Null on success, else why not.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeStart<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    dict: JString<'l>,
    user: JString<'l>,
) -> jstring {
    let (Some(dict), Some(user)) = (string(&mut env, &dict), string(&mut env, &user)) else {
        return env.new_string("bad arguments").map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut());
    };
    let opened = catch_unwind(|| Engine::open(Path::new(&dict), Path::new(&user)))
        .unwrap_or_else(|_| Err("libchewing panicked while opening".into()));
    match opened {
        Ok(engine) => {
            let mut slot = ENGINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            if let Some(mut old) = slot.0.replace(engine) {
                let _ = catch_unwind(AssertUnwindSafe(|| old.sync()));
            }
            std::ptr::null_mut()
        }
        Err(error) => env.new_string(error).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

/// Writes out what was learned and closes libchewing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeStop<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) {
    let mut slot = ENGINE.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some(mut engine) = slot.0.take() {
        let _ = catch_unwind(AssertUnwindSafe(|| engine.sync()));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeDictVersion<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jstring {
    let version = with_engine(|e| e.version().to_owned()).unwrap_or_default();
    env.new_string(version).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The conversion of `reading`: candidate texts, then the composing text,
/// in the returned array. `info` gets `[count, tail, consumed₀, sentence₀,
/// consumed₁, sentence₁, …]` (see `engine::Menu`); it must hold
/// `2 + 2 * limit` ints. Null while libchewing is closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeConvert<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    reading: JString<'l>,
    limit: jint,
    info: JIntArray<'l>,
) -> jobjectArray {
    let Some(reading) = string(&mut env, &reading) else { return std::ptr::null_mut() };
    let capacity = env.get_array_length(&info).unwrap_or(0).max(0) as usize;
    let limit = (limit.max(0) as usize).min(capacity.saturating_sub(2) / 2);
    let Some(menu) = with_engine(|e| e.convert(&reading, limit)) else { return std::ptr::null_mut() };

    let mut ints = Vec::with_capacity(2 + 2 * menu.texts.len());
    ints.push(menu.texts.len() as jint);
    ints.push(menu.tail as jint);
    for (consumed, sentence) in menu.consumed.iter().zip(&menu.sentence) {
        ints.push(*consumed as jint);
        ints.push(*sentence as jint);
    }
    if env.set_int_array_region(&info, 0, &ints).is_err() {
        return std::ptr::null_mut();
    }
    let Ok(out) = env.new_object_array((menu.texts.len() + 1) as jint, "java/lang/String", JObject::null()) else {
        return std::ptr::null_mut();
    };
    for (i, text) in menu.texts.iter().chain(std::iter::once(&menu.preedit)).enumerate() {
        let Ok(s) = env.new_string(text) else { return std::ptr::null_mut() };
        if env.set_object_array_element(&out, i as jint, &s).is_err() {
            return std::ptr::null_mut();
        }
        let _ = env.delete_local_ref(s);
    }
    out.into_raw()
}

/// Learns that the first `consumed` characters of `reading` mean `phrase`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeLearnPhrase<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    reading: JString<'l>,
    consumed: jint,
    phrase: JString<'l>,
) -> jboolean {
    let (Some(reading), Some(phrase)) = (string(&mut env, &reading), string(&mut env, &phrase)) else {
        return JNI_FALSE;
    };
    jbool(with_engine(|e| e.learn_phrase(&reading, consumed.max(0) as usize, &phrase)).unwrap_or(false))
}

/// Learns the phrases of conversion `sentence` of the whole of `reading`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeLearnSentence<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    reading: JString<'l>,
    sentence: jint,
) -> jboolean {
    let Some(reading) = string(&mut env, &reading) else { return JNI_FALSE };
    jbool(with_engine(|e| e.learn_sentence(&reading, sentence.max(0) as usize)).unwrap_or(false))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeSync<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jboolean {
    jbool(with_engine(|e| e.sync()).unwrap_or(false))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_input_chewing_ChewingNative_nativeClearLearned<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jboolean {
    jbool(with_engine(|e| e.clear_learned()).unwrap_or(false))
}
