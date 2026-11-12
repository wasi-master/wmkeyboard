//! The tries a walk reads, as Kotlin names them: a handle and a kind per
//! source. Shared by the correction walk (`fuzzy.rs`) and the glide decoders
//! (`glide.rs`), and home to the third kind, a copy of a plain Kotlin `Trie`.

use std::sync::{Arc, LazyLock, RwLockReadGuard};

use jni::objects::{JClass, JIntArray, JObjectArray, JString};
use jni::sys::jlong;
use jni::JNIEnv;

use wm_engine::fuzzy::Walker;
use wm_engine::user_lexicon::{UserLexicon, UserTrie};
use wm_engine::wmdict::Dict;

use crate::dict::{DictBytes, DICTS};
use crate::guard::{guarded, object, read_units, throw_state};
use crate::handles::Registry;
use crate::lexicon::{Store, LEXICONS};

/// A `.wmdict` (mapped, or a `PackedTrie`'s bytes).
pub const KIND_DICTIONARY: i32 = 0;
/// A user lexicon's trie, read-locked for the walk.
pub const KIND_LEXICON: i32 = 1;
/// A plain Kotlin `Trie`'s copy: exact counts, unlike a `.wmdict`.
pub const KIND_TRIE: i32 = 2;

pub static TRIES: LazyLock<Registry<UserTrie>> = LazyLock::new(Registry::new);

/// `f` over the walkers `handles` and `kinds` name, each trie held (and each
/// lexicon read-locked) for the length of the call.
pub fn with_walkers<R>(env: &mut JNIEnv, handles: &[i64], kinds: &[i32], f: impl FnOnce(&[&dyn Walker]) -> R) -> Option<R> {
    with_walkers_and_lexicons(env, handles, kinds, |walkers, _| f(walkers))
}

/// [`with_walkers`], also handing over the read-locked user lexicons among
/// the sources, in order, so a caller that needs their pair counts reads
/// them under the same lock rather than taking a second one.
pub fn with_walkers_and_lexicons<R>(
    env: &mut JNIEnv,
    handles: &[i64],
    kinds: &[i32],
    f: impl FnOnce(&[&dyn Walker], &[&UserLexicon]) -> R,
) -> Option<R> {
    let mut dicts: Vec<Arc<Dict<DictBytes>>> = Vec::new();
    let mut stores: Vec<Arc<Store>> = Vec::new();
    let mut tries: Vec<Arc<UserTrie>> = Vec::new();
    for (&handle, &kind) in handles.iter().zip(kinds) {
        match kind {
            KIND_LEXICON => stores.push(object!(env, LEXICONS, handle)),
            KIND_TRIE => tries.push(object!(env, TRIES, handle)),
            KIND_DICTIONARY | _ => dicts.push(object!(env, DICTS, handle)),
        }
    }
    let guards: Vec<RwLockReadGuard<UserLexicon>> =
        stores.iter().map(|s| s.lexicon.read().unwrap_or_else(|e| e.into_inner())).collect();
    let (mut next_dict, mut next_lexicon, mut next_trie) = (0, 0, 0);
    let mut walkers: Vec<&dyn Walker> = Vec::with_capacity(handles.len());
    for &kind in kinds.iter().take(handles.len()) {
        walkers.push(match kind {
            KIND_LEXICON => {
                next_lexicon += 1;
                guards[next_lexicon - 1].trie()
            }
            KIND_TRIE => {
                next_trie += 1;
                tries[next_trie - 1].as_ref()
            }
            KIND_DICTIONARY | _ => {
                next_dict += 1;
                dicts[next_dict - 1].as_ref()
            }
        });
    }
    let lexicons: Vec<&UserLexicon> = guards.iter().map(|g| &**g).collect();
    Some(f(&walkers, &lexicons))
}

/// `trieFromEntries(words, frequencies)`: a plain `Trie`'s copy, its words
/// inserted in the order given.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_trieFromEntries(
    mut env: JNIEnv,
    _class: JClass,
    words: JObjectArray,
    frequencies: JIntArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let count = env.get_array_length(&words).ok()?;
        if env.get_array_length(&frequencies).ok()? < count {
            return throw_state(env, "wm-engine: a frequency per word");
        }
        let mut counts = vec![0i32; count as usize];
        env.get_int_array_region(&frequencies, 0, &mut counts).ok()?;
        let mut trie = UserTrie::new();
        let mut units = Vec::new();
        for i in 0..count {
            let word = JString::from(env.get_object_array_element(&words, i).ok()?);
            read_units(env, &word, &mut units)?;
            env.delete_local_ref(word).ok()?;
            trie.insert_units(&units, counts[i as usize]);
        }
        Some(TRIES.insert(trie))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_trieClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        TRIES.remove(handle);
        Some(())
    })
}
