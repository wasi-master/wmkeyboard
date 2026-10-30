//! The neural context model (`wm-engine`'s `lm`): `PredictionNative.lm*`,
//! behind the Kotlin `ContextModel`.
//!
//! A model is mapped from its `.wmlm` file and held behind a handle; a
//! session (one per engine) keeps the context it last read and the
//! distribution over the next word. Every call below crosses once, with
//! arrays.

use std::fs::File;
use std::ptr::null_mut;
use std::sync::{Arc, LazyLock, Mutex};

use jni::objects::{JClass, JFloatArray, JObject, JObjectArray, JString};
use jni::sys::{jfloat, jfloatArray, jint, jlong, jobjectArray, jboolean};
use jni::JNIEnv;
use memmap2::Mmap;

use wm_engine::lm::{Lm, Prefix, Session};

use crate::guard::{give_all, guarded, object, read, throw_state};
use crate::handles::Registry;

pub static MODELS: LazyLock<Registry<Lm<Mmap>>> = LazyLock::new(Registry::new);
pub static SESSIONS: LazyLock<Registry<Mutex<Session<Mmap>>>> = LazyLock::new(Registry::new);

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> Option<Vec<String>> {
    let count = env.get_array_length(array).ok()? as usize;
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let item = JString::from(env.get_object_array_element(array, i as i32).ok()?);
        out.push(read(env, &item)?);
        env.delete_local_ref(item).ok()?;
    }
    Some(out)
}

fn give_floats(env: &mut JNIEnv, values: &[f32]) -> Option<jfloatArray> {
    let array = env.new_float_array(values.len() as i32).ok()?;
    env.set_float_array_region(&array, 0, values).ok()?;
    Some(array.into_raw())
}

/// `lmOpen(path)`: a handle, or 0 when the file is missing or not a
/// `.wmlm` this reader trusts.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let path = read(env, &path)?;
        let file = File::open(path).ok();
        // SAFETY: read-only, and the app replaces the file by rename, never
        // in place.
        let map = file.and_then(|f| unsafe { Mmap::map(&f) }.ok());
        Some(map.and_then(Lm::open).map_or(0, |lm| MODELS.insert(lm)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        MODELS.remove(handle);
        Some(())
    })
}

/// `lmInfo(handle)`: `[name, languages joined by commas, vocabulary size, context length]`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let lm = object!(env, MODELS, handle);
        give_all(env, vec![lm.name.clone(), lm.langs.join(","), lm.vocab_size().to_string(), lm.ctx.to_string()])
    })
}

/// `lmKnows(handle, word)`: whether the vocabulary holds the word.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmKnows(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
) -> jboolean {
    guarded(&mut env, 0, |env| {
        let lm = object!(env, MODELS, handle);
        let word = read(env, &word)?;
        Some(wm_engine::lm::token_key(&word).and_then(|k| lm.id_of(&k)).is_some() as jboolean)
    })
}

/// `lmSessionOpen(model)`: a session on the model, or 0.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmSessionOpen(
    mut env: JNIEnv,
    _class: JClass,
    model: jlong,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let lm: Arc<Lm<Mmap>> = object!(env, MODELS, model);
        Some(SESSIONS.insert(Mutex::new(Session::new(lm))))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmSessionClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        SESSIONS.remove(handle);
        Some(())
    })
}

/// `lmSetContext(session, register, field, lang, words)`: the words before
/// the caret, oldest first, under the conditioning prefix.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmSetContext(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    register: jint,
    field: jint,
    lang: jint,
    words: JObjectArray,
) {
    guarded(&mut env, (), |env| {
        let session = object!(env, SESSIONS, session);
        let words = strings(env, &words)?;
        let refs: Vec<&str> = words.iter().map(String::as_str).collect();
        let prefix = Prefix { register: register.clamp(0, 2) as u8, field: field.clamp(0, 3) as u8, lang: lang.clamp(0, 3) as u8 };
        session.lock().unwrap_or_else(|e| e.into_inner()).set_context(prefix, &refs);
        Some(())
    })
}

/// `lmScores(session, words)`: ln P(word next) per listed word, NaN for a
/// word the vocabulary does not hold as one token (no piece spelling here:
/// this runs per keystroke over the whole strip).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmScores(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    words: JObjectArray,
) -> jfloatArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let words = strings(env, &words)?;
        let mut s = session.lock().unwrap_or_else(|e| e.into_inner());
        let scores: Vec<f32> = words.iter().map(|w| s.logprob_listed(w).unwrap_or(f32::NAN)).collect();
        give_floats(env, &scores)
    })
}

/// `lmScoresSpelled(session, words)`: [`lmScores`], with a word the vocabulary
/// does not hold spelled through its pieces: up to six forward steps a word, so
/// for a handful of candidates, never the whole strip.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmScoresSpelled(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    words: JObjectArray,
) -> jfloatArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let words = strings(env, &words)?;
        let mut s = session.lock().unwrap_or_else(|e| e.into_inner());
        let scores: Vec<f32> = words.iter().map(|w| s.logprob(w).unwrap_or(f32::NAN)).collect();
        give_floats(env, &scores)
    })
}

/// `lmGateView(session, typed)`: `[ln P(typed) if listed, rank of typed,
/// entropy, ln P(typed) spelled in pieces]` for the learned gate; the
/// first two NaN when the vocabulary does not list the word, the last NaN
/// when it does (or the model has no pieces).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmGateView(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    typed: JString,
) -> jfloatArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let typed = read(env, &typed)?;
        let mut s = session.lock().unwrap_or_else(|e| e.into_inner());
        let listed = s.logprob_listed(&typed);
        let pieces = if listed.is_none() { s.logprob(&typed) } else { None };
        let out = [
            listed.unwrap_or(f32::NAN),
            s.rank_of(&typed).map_or(f32::NAN, |r| r as f32),
            s.entropy(),
            pieces.unwrap_or(f32::NAN),
        ];
        give_floats(env, &out)
    })
}

/// `lmTop(session, k, minLogprob)`: `[String[] words, float[] logprobs]`,
/// the likeliest next words, best first.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmTop(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    k: jint,
    min_logprob: jfloat,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let top = session.lock().unwrap_or_else(|e| e.into_inner()).top(k.max(0) as usize, min_logprob);
        let words: Vec<String> = top.iter().map(|(w, _)| w.clone()).collect();
        let scores: Vec<f32> = top.iter().map(|(_, lp)| *lp).collect();
        pair(env, words, &scores)
    })
}

/// `lmGenerate(session, n, minProb)`: up to `n` words the model would write
/// next, greedily, each at least `minProb` likely; the context is put back
/// afterwards. `[String[] words, float[] {joint log-probability}]`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmGenerate(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    n: jint,
    min_prob: jfloat,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let mut s = session.lock().unwrap_or_else(|e| e.into_inner());
        let before = s.tokens().to_vec();
        let (words, lp) = s.generate(n.max(0) as usize, min_prob);
        s.set_tokens(&before);
        pair(env, words, &[lp])
    })
}

/// `lmRevise(session, register, field, lang, context, candidates, followers)`:
/// each candidate's fit where the word before `followers` stands, every
/// follower in sight, NaN where the model cannot read a word.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lmRevise(
    mut env: JNIEnv,
    _class: JClass,
    session: jlong,
    register: jint,
    field: jint,
    lang: jint,
    context: JObjectArray,
    candidates: JObjectArray,
    followers: JObjectArray,
) -> jfloatArray {
    guarded(&mut env, null_mut(), |env| {
        let session = object!(env, SESSIONS, session);
        let context = strings(env, &context)?;
        let candidates = strings(env, &candidates)?;
        let followers = strings(env, &followers)?;
        let prefix = Prefix { register: register.clamp(0, 2) as u8, field: field.clamp(0, 3) as u8, lang: lang.clamp(0, 3) as u8 };
        let ctx: Vec<&str> = context.iter().map(String::as_str).collect();
        let cands: Vec<&str> = candidates.iter().map(String::as_str).collect();
        let after: Vec<&str> = followers.iter().map(String::as_str).collect();
        let scores: Vec<f32> = session
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .revise_all(prefix, &ctx, &cands, &after)
            .into_iter()
            .map(|s| s.unwrap_or(f32::NAN))
            .collect();
        give_floats(env, &scores)
    })
}

/// `[String[], float[]]` as an `Object[]`.
fn pair(env: &mut JNIEnv, words: Vec<String>, scores: &[f32]) -> Option<jobjectArray> {
    let class = env.find_class("java/lang/Object").ok()?;
    let out = env.new_object_array(2, class, JObject::null()).ok()?;
    let words = give_all(env, words)?;
    // SAFETY: the local references just made.
    let words = unsafe { JObjectArray::from_raw(words) };
    env.set_object_array_element(&out, 0, &words).ok()?;
    let floats = give_floats(env, scores)?;
    let floats = unsafe { JFloatArray::from_raw(floats) };
    env.set_object_array_element(&out, 1, &floats).ok()?;
    Some(out.into_raw())
}

#[allow(dead_code)]
fn _unused(env: &mut JNIEnv) -> Option<()> {
    throw_state(env, "")
}
