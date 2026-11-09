//! The smart punctuation model (`wm-engine`'s `punct`):
//! `PredictionNative.punct*`, behind the Kotlin `PunctuationModel`.
//!
//! A model is mapped from its `.wmpt` file and held behind a handle. It
//! keeps no state between calls, so one handle serves every thread; each
//! prediction crosses once, with the words as an array, and comes back as
//! the probability of each mark.

use std::fs::File;
use std::ptr::null_mut;
use std::sync::LazyLock;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jboolean, jfloatArray, jlong, jobjectArray};
use jni::JNIEnv;
use memmap2::Mmap;

use wm_engine::punct::{Punct, LABELS};

use crate::guard::{give_all, guarded, object, read};
use crate::handles::Registry;

pub static MODELS: LazyLock<Registry<Punct<Mmap>>> = LazyLock::new(Registry::new);

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

/// `punctOpen(path)`: a handle, or 0 when the file is missing or not a
/// `.wmpt` this reader trusts.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_punctOpen(
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
        Some(map.and_then(Punct::open).map_or(0, |m| MODELS.insert(m)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_punctClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        MODELS.remove(handle);
        Some(())
    })
}

/// `punctInfo(handle)`: `[name, languages joined by commas, marks joined by
/// nothing (".?!,"), vocabulary size, context length, "1" when the model
/// reads a language token]`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_punctInfo(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let m = object!(env, MODELS, handle);
        give_all(
            env,
            vec![
                m.name.clone(),
                m.langs.join(","),
                LABELS.concat(),
                m.vocab_size().to_string(),
                m.ctx.to_string(),
                if m.has_language_tokens() { "1" } else { "0" }.to_string(),
            ],
        )
    })
}

/// `punctKnows(handle, word)`: whether the vocabulary holds the word as one token.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_punctKnows(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
) -> jboolean {
    guarded(&mut env, 0, |env| {
        let m = object!(env, MODELS, handle);
        let word = read(env, &word)?;
        Some(m.knows(&word) as jboolean)
    })
}

/// `punctPredict(handle, lang, words)`: the probability of each mark, in the
/// order `punctInfo` names them, for the sentence so far (oldest word first)
/// typed in the keyboard's language `lang` (empty for none; read only by a
/// model with language tokens).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_punctPredict(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    lang: JString,
    words: JObjectArray,
) -> jfloatArray {
    guarded(&mut env, null_mut(), |env| {
        let m = object!(env, MODELS, handle);
        let lang = read(env, &lang)?;
        let words = strings(env, &words)?;
        let refs: Vec<&str> = words.iter().map(String::as_str).collect();
        give_floats(env, &m.predict(&refs, Some(lang.as_str()).filter(|l| !l.is_empty())))
    })
}
