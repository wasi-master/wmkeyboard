//! The search stemmers (`wm-engine`'s `stem`): `NativeEngine.stemText` and
//! `stemTexts` in `core/language`.
//!
//! A search stems its query once and its candidates once each, so the batch
//! form takes every candidate in one crossing. Neither runs on the
//! key-press path.

use std::ptr::null_mut;

use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::{jobjectArray, jsize, jstring};
use jni::JNIEnv;

use wm_engine::stem::{stem_text_into, Context};

use crate::guard::{give_units, guarded, read, read_units};

/// `stemText(languageTag, text)`: the words of `text`, lowercased and
/// stemmed in that language (or their own script's), joined by spaces.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeStemText(
    mut env: JNIEnv,
    _class: JClass,
    tag: JString,
    text: JString,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let ctx = Context::of_tag(&read(env, &tag)?);
        let mut units = Vec::new();
        read_units(env, &text, &mut units)?;
        let mut out = Vec::with_capacity(units.len());
        stem_text_into(&ctx, &units, &mut out);
        give_units(env, &out)
    })
}

/// `stemTexts(languageTag, texts)`: [`stemText`] over every element, in one
/// crossing; a null element comes back as an empty string.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_engine_NativeEngine_nativeStemTexts(
    mut env: JNIEnv,
    _class: JClass,
    tag: JString,
    texts: JObjectArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let ctx = Context::of_tag(&read(env, &tag)?);
        let n = env.get_array_length(&texts).ok()?;
        let class = env.find_class("java/lang/String").ok()?;
        let out: JObjectArray = env.new_object_array(n as jsize, &class, JString::default()).ok()?;
        let mut units = Vec::new();
        let mut stems = Vec::new();
        for i in 0..n {
            let item = JString::from(env.get_object_array_element(&texts, i).ok()?);
            if item.is_null() {
                units.clear();
            } else {
                read_units(env, &item, &mut units)?;
            }
            env.delete_local_ref(item).ok()?;
            stem_text_into(&ctx, &units, &mut stems);
            // SAFETY: `give_units` returns the local reference NewString made.
            let value = unsafe { JString::from_raw(give_units(env, &stems)?) };
            env.set_object_array_element(&out, i, &value).ok()?;
            env.delete_local_ref(value).ok()?;
        }
        Some(out.into_raw())
    })
}
