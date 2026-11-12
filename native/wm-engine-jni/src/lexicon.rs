//! The user lexicon (`wm-engine`'s `user_lexicon`): `PredictionNative.lex*`,
//! behind the Kotlin `UserLexicon`.
//!
//! The Kotlin side folds every word to its key (`WordKey`) and judges
//! learnability before calling; this side holds the store, its learning
//! rules and its file format, and folds and judges on its own when it loads
//! a file (`wm_engine::word_key`, the same rules). A store is shared by the IME's threads, so it
//! sits behind a read-write lock: learning takes it for writing, lookups and
//! the fuzzy walk for reading.

use std::ptr::null_mut;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{LazyLock, RwLock};

use jni::objects::{JClass, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jint, jlong, jobjectArray, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;

use wm_engine::user_lexicon::UserLexicon;

use crate::guard::{give_all_units, give_ints, give_units, guarded, object, read, read_units};
use crate::handles::Registry;

pub struct Store {
    pub lexicon: RwLock<UserLexicon>,
    /// Bumped by every change, while the change still holds the lock, so a
    /// reader holding the read lock sees the count that matches what it
    /// reads; the tap decoder's session (`decoder.rs`) keys on it.
    pub generation: AtomicU64,
}

impl Store {
    pub fn generation(&self) -> u64 {
        self.generation.load(Ordering::Acquire)
    }

    fn changed(&self) {
        self.generation.fetch_add(1, Ordering::AcqRel);
    }
}

pub static LEXICONS: LazyLock<Registry<Store>> = LazyLock::new(Registry::new);

fn string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    let mut units = Vec::new();
    read_units(env, s, &mut units)?;
    Some(String::from_utf16_lossy(&units))
}

fn optional(env: &mut JNIEnv, s: &JString) -> Option<Option<String>> {
    if s.is_null() {
        Some(None)
    } else {
        string(env, s).map(Some)
    }
}

fn give_str(env: &mut JNIEnv, s: &str) -> Option<jstring> {
    give_units(env, &s.encode_utf16().collect::<Vec<u16>>())
}

fn give_strings(env: &mut JNIEnv, items: &[String]) -> Option<jobjectArray> {
    let units: Vec<Vec<u16>> = items.iter().map(|s| s.encode_utf16().collect()).collect();
    give_all_units(env, units.iter().map(Vec::as_slice))
}

fn pair(env: &mut JNIEnv, words: &[String], counts: &[i32]) -> Option<jobjectArray> {
    let words = give_strings(env, words)?;
    // SAFETY: the local reference just returned.
    let words = unsafe { JObject::from_raw(words) };
    let counts = give_ints(env, counts)?;
    let out: JObjectArray = env.new_object_array(2, "java/lang/Object", JObject::null()).ok()?;
    env.set_object_array_element(&out, 0, &words).ok()?;
    env.set_object_array_element(&out, 1, &counts).ok()?;
    Some(out.into_raw())
}

fn truth(b: bool) -> jboolean {
    if b {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

macro_rules! lexicon_fn {
    // A read of the store.
    (read $name:ident ($($arg:ident : $ty:ty),*) -> $ret:ty, $fallback:expr, |$env:ident, $lex:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _class: JClass, handle: jlong, $($arg: $ty),*) -> $ret {
            guarded(&mut env, $fallback, |$env| {
                let store = object!($env, LEXICONS, handle);
                let $lex = store.lexicon.read().unwrap_or_else(|e| e.into_inner());
                $body
            })
        }
    };
    // A change to the store.
    (write $name:ident ($($arg:ident : $ty:ty),*) -> $ret:ty, $fallback:expr, |$env:ident, $lex:ident| $body:expr) => {
        #[no_mangle]
        pub extern "system" fn $name(mut env: JNIEnv, _class: JClass, handle: jlong, $($arg: $ty),*) -> $ret {
            guarded(&mut env, $fallback, |$env| {
                let store = object!($env, LEXICONS, handle);
                let mut $lex = store.lexicon.write().unwrap_or_else(|e| e.into_inner());
                store.changed();
                $body
            })
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexNew(
    mut env: JNIEnv,
    _class: JClass,
) -> jlong {
    guarded(&mut env, 0, |_| {
        Some(LEXICONS.insert(Store { lexicon: RwLock::new(UserLexicon::new()), generation: AtomicU64::new(0) }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        LEXICONS.remove(handle);
        Some(())
    })
}

/// `lexLoad(handle, json)`: reads a saved file into the store; false when
/// the text is not a snapshot, and nothing is loaded.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexLoad(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    json: JString,
) -> jboolean {
    guarded(&mut env, JNI_FALSE, |env| {
        let store = object!(env, LEXICONS, handle);
        let json = read(env, &json)?;
        let loaded = {
            let mut lex = store.lexicon.write().unwrap_or_else(|e| e.into_inner());
            store.changed();
            lex.load(&json)
        };
        Some(if loaded { JNI_TRUE } else { JNI_FALSE })
    })
}

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexResetForReload() -> (), (), |_env, lex| {
    lex.reset_for_reload();
    Some(())
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexFinishReload() -> (), (), |_env, lex| {
    lex.finish_reload();
    Some(())
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexLearnWord(
    key: JString, vote: JString, count: jint, lang: JString, listed: jboolean
) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    let vote = optional(env, &vote)?;
    let lang = string(env, &lang)?;
    Some(truth(lex.learn_word(&key, vote.as_deref(), count, &lang, listed == JNI_TRUE)))
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexAddWord(
    key: JString, case_evidence: jboolean, surface: JString, boost: jint, pin: jboolean
) -> (), (), |env, lex| {
    let key = string(env, &key)?;
    let surface = optional(env, &surface)?;
    lex.add_word(&key, case_evidence == JNI_TRUE, surface.as_deref(), boost, pin == JNI_TRUE);
    Some(())
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexLanguageOf(key: JString) -> jstring, null_mut(), |env, lex| {
    let key = string(env, &key)?;
    match lex.language_of(&key) {
        None => Some(null_mut()),
        Some(lang) => give_str(env, lang),
    }
});

// `lang` empty files the word under every language.
lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexSetLanguage(key: JString, lang: JString) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    let lang = string(env, &lang)?;
    Some(truth(lex.set_language(&key, &lang)))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexIsLanguagePinned(key: JString) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.is_language_pinned(&key)))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexDisplayOf(key: JString) -> jstring, null_mut(), |env, lex| {
    let key = string(env, &key)?;
    match lex.display_of(&key) {
        None => Some(null_mut()),
        Some(shape) => give_str(env, shape),
    }
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexIsAddedByHand(key: JString) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.is_added_by_hand(&key)))
});

// `Long.MIN_VALUE` for a word the store does not have.
lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexAddedGeneration(key: JString) -> jlong, i64::MIN, |env, lex| {
    let key = string(env, &key)?;
    Some(lex.added_generation(&key).unwrap_or(i64::MIN))
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexRename(
    old_key: JString, new_key: JString, surface: JString, surface_folds: jboolean
) -> jboolean, JNI_FALSE, |env, lex| {
    let old_key = string(env, &old_key)?;
    let new_key = string(env, &new_key)?;
    let surface = string(env, &surface)?;
    Some(truth(lex.rename(&old_key, &new_key, &surface, surface_folds == JNI_TRUE)))
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexSetCount(key: JString, count: jint) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.set_count(&key, count)))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexIsCasePinned(key: JString) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.is_case_pinned(&key)))
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexPinCase(key: JString, pinned: jboolean) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.pin_case(&key, pinned == JNI_TRUE)))
});

// `lexLearnNgram(handle, kind, a, b, c)`: kind 0 bigram (a b), 1 trigram
// (a b c), 2 one-skip (a b), 3 two-skip (a b); keys already folded.
lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexLearnNgram(
    kind: jint, a: JString, b: JString, c: JString
) -> (), (), |env, lex| {
    let a = string(env, &a)?;
    let b = string(env, &b)?;
    match kind {
        0 => lex.learn_bigram(&a, &b),
        1 => {
            let c = string(env, &c)?;
            lex.learn_trigram(&a, &b, &c)
        }
        2 => lex.learn_skip1gram(&a, &b),
        _ => lex.learn_skip2gram(&a, &b),
    }
    Some(())
});

// `lexNgramCount(handle, kind, a, b, c)`, kinds as for `lexLearnNgram`.
lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexNgramCount(
    kind: jint, a: JString, b: JString, c: JString
) -> jint, 0, |env, lex| {
    let a = string(env, &a)?;
    let b = string(env, &b)?;
    Some(match kind {
        0 => lex.bigram_count(&a, &b),
        1 => {
            let c = string(env, &c)?;
            lex.trigram_count(&a, &b, &c)
        }
        2 => lex.skip1gram_count(&a, &b),
        _ => lex.skip2gram_count(&a, &b),
    })
});

// `lexFollowers(handle, kind, a, b, limit)`: kind 0 after a, 1 after a b,
// 2 one word after a, 3 two words after a. Best first.
lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexFollowers(
    kind: jint, a: JString, b: JString, limit: jint
) -> jobjectArray, null_mut(), |env, lex| {
    let a = string(env, &a)?;
    let limit = limit.max(0) as usize;
    let words = match kind {
        0 => lex.next_words(&a, limit),
        1 => {
            let b = string(env, &b)?;
            lex.next_words_after(&a, &b, limit)
        }
        2 => lex.skip1_followers(&a, limit),
        _ => lex.skip2_followers(&a, limit),
    };
    give_strings(env, &words)
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexFollowerCounts(key: JString) -> jobjectArray, null_mut(), |env, lex| {
    let key = string(env, &key)?;
    let (words, counts): (Vec<String>, Vec<i32>) = lex.follower_counts(&key).into_iter().unzip();
    pair(env, &words, &counts)
});

// `lexComplete(handle, prefix, limit, frequencies)`: the prefix as typed,
// not folded (Kotlin's `complete` passes it straight to the trie).
lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexComplete(
    prefix: JString, limit: jint, frequencies: JIntArray
) -> jobjectArray, null_mut(), |env, lex| {
    let mut units = Vec::new();
    read_units(env, &prefix, &mut units)?;
    let room = env.get_array_length(&frequencies).ok()?.max(0) as usize;
    let found = lex.trie().complete_units(&units, (limit.max(0) as usize).min(room));
    let counts: Vec<i32> = found.iter().map(|(_, f)| *f).collect();
    env.set_int_array_region(&frequencies, 0, &counts).ok()?;
    give_all_units(env, found.iter().map(|(t, _)| t.as_slice()))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexContains(key: JString) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.contains(&key)))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexIsEstablished(key: JString, min_count: jint) -> jboolean, JNI_FALSE, |env, lex| {
    let key = string(env, &key)?;
    Some(truth(lex.is_established(&key, min_count)))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexFrequencyOf(key: JString) -> jint, 0, |env, lex| {
    let key = string(env, &key)?;
    Some(lex.frequency_of(&key))
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexWordCount() -> jint, 0, |_env, lex| {
    Some(lex.word_count() as jint)
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexAllWords() -> jobjectArray, null_mut(), |env, lex| {
    let (words, counts): (Vec<String>, Vec<i32>) = lex.all_words().into_iter().unzip();
    pair(env, &words, &counts)
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexForgetAll(keys: JObjectArray) -> (), (), |env, lex| {
    let len = env.get_array_length(&keys).ok()?;
    let mut set = std::collections::HashSet::with_capacity(len as usize);
    for i in 0..len {
        let item = JString::from(env.get_object_array_element(&keys, i).ok()?);
        set.insert(string(env, &item)?);
        env.delete_local_ref(item).ok()?;
    }
    lex.forget_all(&set);
    Some(())
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexClear(deleted: jboolean) -> (), (), |_env, lex| {
    lex.clear(deleted == JNI_TRUE);
    Some(())
});

// `lexPrepareSave(handle)`: the file's text, or null when nothing changed.
lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexPrepareSave() -> jstring, null_mut(), |env, lex| {
    match lex.prepare_save() {
        None => Some(null_mut()),
        Some(json) => give_str(env, &json),
    }
});

lexicon_fn!(write Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexMarkUnsaved() -> (), (), |_env, lex| {
    lex.mark_unsaved();
    Some(())
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexMutationCount() -> jlong, 0, |_env, lex| {
    Some(lex.mutation_count())
});

lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexTrieVersion() -> jlong, 0, |_env, lex| {
    Some(lex.trie_version())
});

// `lexExportTrie(handle)`: `[IntArray childStart, CharArray labels,
// IntArray targets, IntArray frequency, IntArray maxSubtree, BooleanArray
// isWord, LongArray version]`, for the Kotlin walks over the lexicon.
lexicon_fn!(read Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_lexExportTrie() -> jobjectArray, null_mut(), |env, lex| {
    let t = lex.trie().export();
    let child_start = give_ints(env, &t.child_start)?;
    let labels = env.new_char_array(t.labels.len() as i32).ok()?;
    env.set_char_array_region(&labels, 0, &t.labels).ok()?;
    let targets = give_ints(env, &t.targets)?;
    let frequency = give_ints(env, &t.frequency)?;
    let max_subtree = give_ints(env, &t.max_subtree)?;
    let is_word = env.new_boolean_array(t.is_word.len() as i32).ok()?;
    let bools: Vec<u8> = t.is_word.iter().map(|&b| b as u8).collect();
    env.set_boolean_array_region(&is_word, 0, &bools).ok()?;
    let version = env.new_long_array(1).ok()?;
    env.set_long_array_region(&version, 0, &[lex.trie_version()]).ok()?;
    let out: JObjectArray = env.new_object_array(7, "java/lang/Object", JObject::null()).ok()?;
    let parts: [&JObject; 7] = [&child_start, &labels, &targets, &frequency, &max_subtree, &is_word, &version];
    for (i, part) in parts.iter().enumerate() {
        env.set_object_array_element(&out, i as i32, *part).ok()?;
    }
    Some(out.into_raw())
});
