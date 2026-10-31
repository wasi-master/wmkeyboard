//! The two stores autocorrect learns from (`wm-engine`'s `correction_memory`
//! and `correction_stats`): `PredictionNative.cm*` behind the Kotlin
//! `CorrectionMemory`, `PredictionNative.cs*` behind `CorrectionStats`.
//!
//! As for the lexicon, the Kotlin side folds the words (`WordKey`, or the
//! stats' own lower-casing) and keeps the file; this side holds the store,
//! its rules and its format. Each store sits behind a mutex: they are read
//! and written on the commit path, never walked.

use std::ptr::null_mut;
use std::sync::{LazyLock, Mutex};

use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jdouble, jint, jlong, jobjectArray, jstring, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::correction_memory::{CorrectionMemory, Kind};
use wm_engine::correction_stats::{CorrectionStats, Penalty, UndoMemory};

use crate::guard::{give_ints, give_units, guarded, object, read_units};
use crate::handles::Registry;

static MEMORIES: LazyLock<Registry<Mutex<CorrectionMemory>>> = LazyLock::new(Registry::new);
static STATS: LazyLock<Registry<Mutex<CorrectionStats>>> = LazyLock::new(Registry::new);

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    let mut units = Vec::new();
    read_units(env, s, &mut units)?;
    Some(String::from_utf16_lossy(&units))
}

fn give_str(env: &mut JNIEnv, s: &str) -> Option<jstring> {
    give_units(env, &s.encode_utf16().collect::<Vec<u16>>())
}

fn give_strings(env: &mut JNIEnv, items: &[String]) -> Option<JObject<'static>> {
    let class = env.find_class("java/lang/String").ok()?;
    let array = env.new_object_array(items.len() as i32, &class, JString::default()).ok()?;
    for (i, item) in items.iter().enumerate() {
        let value = give_str(env, item)?;
        // SAFETY: the local reference NewString just returned.
        let value = unsafe { JString::from_raw(value) };
        env.set_object_array_element(&array, i as i32, &value).ok()?;
        env.delete_local_ref(value).ok()?;
    }
    // SAFETY: re-wrapping the same local reference for the caller's frame.
    Some(unsafe { JObject::from_raw(array.into_raw()) })
}

fn objects(env: &mut JNIEnv, parts: &[&JObject]) -> Option<jobjectArray> {
    let out: JObjectArray = env.new_object_array(parts.len() as i32, "java/lang/Object", JObject::null()).ok()?;
    for (i, part) in parts.iter().enumerate() {
        env.set_object_array_element(&out, i as i32, *part).ok()?;
    }
    Some(out.into_raw())
}

/// The Kotlin `CorrectionStats.Penalty` ordinal.
fn penalty_ordinal(penalty: Penalty) -> jint {
    match penalty {
        Penalty::None => 0,
        Penalty::Penalized => 1,
        Penalty::Probation => 2,
        Penalty::Blocked => 3,
    }
}

macro_rules! store_fn {
    ($registry:ident, $name:ident ($($arg:ident : $ty:ty),*) -> $ret:ty, $fallback:expr, |$env:ident, $store:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _class: JClass, handle: jlong, $($arg: $ty),*) -> $ret {
            guarded(&mut env, $fallback, |$env| {
                let held = object!($env, $registry, handle);
                #[allow(unused_mut)]
                let mut $store = held.lock().unwrap_or_else(|e| e.into_inner());
                $body
            })
        }
    };
}

// ---- CorrectionMemory ----------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmNew(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    guarded(&mut env, 0, |_| Some(MEMORIES.insert(Mutex::new(CorrectionMemory::new()))))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        MEMORIES.remove(handle);
        Some(())
    })
}

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmLoad(json: JString) -> (), (), |env, m| {
    let json = string(env, &json)?;
    m.load_json(&json);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmResetForReload() -> (), (), |_env, m| {
    m.reset_for_reload();
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmFinishReload() -> (), (), |_env, m| {
    m.finish_reload();
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmTeach(
    typed: JString, fixed: JString, layout: JString, habits: jboolean
) -> (), (), |env, m| {
    let typed = string(env, &typed)?;
    let fixed = string(env, &fixed)?;
    let layout = string(env, &layout)?;
    let kind = if habits == JNI_TRUE { Kind::PairAndHabits } else { Kind::PairOnly };
    m.teach_keys(&typed, &fixed, &layout, kind);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmUnteach(typed: JString, fixed: JString) -> (), (), |env, m| {
    let typed = string(env, &typed)?;
    let fixed = string(env, &fixed)?;
    m.unteach_keys(&typed, &fixed);
    Some(())
});

// `cmFixFor(handle, typed)`: `[String fixed, IntArray(count)]`, or null.
store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmFixFor(typed: JString) -> jobjectArray, null_mut(), |env, m| {
    let typed = string(env, &typed)?;
    let Some(taught) = m.fix_for_key(&typed) else { return Some(null_mut()) };
    drop(m);
    let fixed = give_str(env, &taught.fixed)?;
    // SAFETY: the local reference just returned.
    let fixed = unsafe { JObject::from_raw(fixed) };
    let count = give_ints(env, &[taught.count])?;
    objects(env, &[&fixed, &count])
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmForget(typed: JString) -> (), (), |env, m| {
    let typed = string(env, &typed)?;
    m.forget_key(&typed);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmSetPair(typed: JString, fixed: JString, count: jint) -> (), (), |env, m| {
    let typed = string(env, &typed)?;
    let fixed = string(env, &fixed)?;
    m.set_pair_keys(&typed, &fixed, count);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmRemovePair(typed: JString, fixed: JString) -> (), (), |env, m| {
    let typed = string(env, &typed)?;
    let fixed = string(env, &fixed)?;
    m.remove_pair_keys(&typed, &fixed);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmSetHabit(layout: JString, key: JString, count: jint) -> (), (), |env, m| {
    let layout = string(env, &layout)?;
    let key = string(env, &key)?;
    m.set_habit(&layout, &key, count);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmHabitLayouts() -> jobjectArray, null_mut(), |env, m| {
    let layouts = m.habit_layouts();
    drop(m);
    Some(give_strings(env, &layouts)?.into_raw())
});

// `cmPairs(handle)`: `[Array<String> typed, Array<String> fixed, IntArray counts,
// IntArray byHand (0 or 1)]`, most used first.
store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmPairs() -> jobjectArray, null_mut(), |env, m| {
    let pairs = m.pairs();
    drop(m);
    let typed: Vec<String> = pairs.iter().map(|(t, _)| t.clone()).collect();
    let fixed: Vec<String> = pairs.iter().map(|(_, f)| f.fixed.clone()).collect();
    let counts: Vec<i32> = pairs.iter().map(|(_, f)| f.count).collect();
    let by_hand: Vec<i32> = pairs.iter().map(|(_, f)| i32::from(f.by_hand)).collect();
    let typed = give_strings(env, &typed)?;
    let fixed = give_strings(env, &fixed)?;
    let counts = give_ints(env, &counts)?;
    let by_hand = give_ints(env, &by_hand)?;
    objects(env, &[&typed, &fixed, &counts, &by_hand])
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmPairCount() -> jint, 0, |_env, m| {
    Some(m.pair_count() as jint)
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmVersion() -> jint, 0, |_env, m| {
    Some(m.version())
});

// `cmHabits(handle, layout)`: `[IntArray subKeys, DoubleArray subValues,
// CharArray insertKeys, DoubleArray, CharArray deleteKeys, DoubleArray,
// CharArray spaceKeys, DoubleArray]`, all empty before the layout warms up.
store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmHabits(layout: JString) -> jobjectArray, null_mut(), |env, m| {
    let layout = string(env, &layout)?;
    let h = m.habit_export(&layout);
    drop(m);
    let sub_keys = give_ints(env, &h.sub_keys)?;
    let mut parts: Vec<JObject> = Vec::new();
    for (keys, values) in [
        (None, &h.sub_values),
        (Some(&h.insert_keys), &h.insert_values),
        (Some(&h.delete_keys), &h.delete_values),
        (Some(&h.space_keys), &h.space_values),
    ] {
        if let Some(keys) = keys {
            let array = env.new_char_array(keys.len() as i32).ok()?;
            env.set_char_array_region(&array, 0, keys).ok()?;
            parts.push(array.into());
        }
        let array = env.new_double_array(values.len() as i32).ok()?;
        env.set_double_array_region(&array, 0, values).ok()?;
        parts.push(array.into());
    }
    let mut all: Vec<&JObject> = vec![&sub_keys];
    all.extend(parts.iter());
    objects(env, &all)
});

// `cmHabitCounts(handle, layout)`: `[Array<String> keys, IntArray counts]`
// for one layout, or summed over all when `layout` is null.
store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmHabitCounts(layout: JString) -> jobjectArray, null_mut(), |env, m| {
    let layout = if layout.is_null() { None } else { Some(string(env, &layout)?) };
    let counts = m.habit_counts(layout.as_deref());
    drop(m);
    let keys: Vec<String> = counts.iter().map(|(k, _)| k.clone()).collect();
    let values: Vec<i32> = counts.iter().map(|(_, n)| *n).collect();
    let keys = give_strings(env, &keys)?;
    let values = give_ints(env, &values)?;
    objects(env, &[&keys, &values])
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmClear(deleted: jboolean) -> (), (), |_env, m| {
    m.clear_with(deleted == JNI_TRUE);
    Some(())
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmPrepareSave() -> jstring, null_mut(), |env, m| {
    match m.prepare_save() {
        None => Some(null_mut()),
        Some(json) => {
            drop(m);
            give_str(env, &json)
        }
    }
});

store_fn!(MEMORIES, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_cmMarkUnsaved() -> (), (), |_env, m| {
    m.mark_unsaved();
    Some(())
});

// ---- CorrectionStats -----------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csNew(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    guarded(&mut env, 0, |_| Some(STATS.insert(Mutex::new(CorrectionStats::new()))))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        STATS.remove(handle);
        Some(())
    })
}

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csLoad(json: JString) -> (), (), |env, s| {
    let json = string(env, &json)?;
    s.load_json(&json);
    Some(())
});

// `csReload(handle, json)`: `json` empty when the file is gone.
store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csReload(json: JString) -> (), (), |env, s| {
    let json = string(env, &json)?;
    s.reload_json(&json);
    Some(())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csSetMemory(ordinal: jint) -> (), (), |_env, s| {
    s.memory = match ordinal {
        0 => UndoMemory::Off,
        1 => UndoMemory::Light,
        3 => UndoMemory::Strict,
        _ => UndoMemory::Normal,
    };
    Some(())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csRecordRevert(key: JString, deliberate: jboolean) -> (), (), |env, s| {
    let key = string(env, &key)?;
    s.record_revert_key(&key, deliberate == JNI_TRUE);
    Some(())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csEndFieldSession() -> (), (), |_env, s| {
    s.end_field_session();
    Some(())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csRecordKept(key: JString) -> (), (), |env, s| {
    let key = string(env, &key)?;
    s.record_kept_key(&key);
    Some(())
});

// `csPenalty(handle, key)`: the `Penalty` ordinal.
store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csPenalty(key: JString) -> jint, 0, |env, s| {
    let key = string(env, &key)?;
    Some(penalty_ordinal(s.penalty_key(&key)))
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csSetPair(key: JString, count: jint, held: jboolean) -> (), (), |env, s| {
    let key = string(env, &key)?;
    s.set_pair_key(&key, count, held == JNI_TRUE);
    Some(())
});

// `csPairs(handle)`: `[Array<String> typed, Array<String> fixed, IntArray counts,
// IntArray penalties, IntArray held (0 or 1)]`, in key order.
store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csPairs() -> jobjectArray, null_mut(), |env, s| {
    let pairs = s.pairs();
    drop(s);
    let typed: Vec<String> = pairs.iter().map(|p| p.typed.clone()).collect();
    let fixed: Vec<String> = pairs.iter().map(|p| p.fixed.clone()).collect();
    let counts: Vec<i32> = pairs.iter().map(|p| p.count).collect();
    let penalties: Vec<i32> = pairs.iter().map(|p| penalty_ordinal(p.penalty)).collect();
    let held: Vec<i32> = pairs.iter().map(|p| i32::from(p.held)).collect();
    let typed = give_strings(env, &typed)?;
    let fixed = give_strings(env, &fixed)?;
    let counts = give_ints(env, &counts)?;
    let penalties = give_ints(env, &penalties)?;
    let held = give_ints(env, &held)?;
    objects(env, &[&typed, &fixed, &counts, &penalties, &held])
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csConfidenceMultiplier() -> jdouble, 1.0, |_env, s| {
    Some(s.multiplier())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csClear(deleted: jboolean) -> (), (), |_env, s| {
    s.clear_with(deleted == JNI_TRUE);
    Some(())
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csPrepareSave() -> jstring, null_mut(), |env, s| {
    match s.prepare_save() {
        None => Some(null_mut()),
        Some(json) => {
            drop(s);
            give_str(env, &json)
        }
    }
});

store_fn!(STATS, Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_csMarkUnsaved() -> (), (), |_env, s| {
    s.mark_unsaved();
    Some(())
});
