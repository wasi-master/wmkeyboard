//! `.wmdict` dictionaries and `.wmng` n-gram packs, mapped here and read in
//! place: `PredictionNative` in `core/prediction`, which `MappedTrie` and
//! `MappedNgramPack` call.
//!
//! A file is mapped read-only and validated once at open; Kotlin holds the
//! handle. The app replaces these files by writing a new one and renaming it
//! over the old, so a mapping keeps reading the inode it opened; a file
//! truncated in place under a live mapping would fault, here as it would in
//! Kotlin's `MappedByteBuffer`.

use std::cell::RefCell;
use std::fs::File;
use std::ptr::null_mut;
use std::sync::LazyLock;

use jni::objects::{JByteArray, JClass, JIntArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray, jstring};
use jni::JNIEnv;
use memmap2::Mmap;

use wm_engine::wmdict::Dict;
use wm_engine::wmng::Pack;

use crate::guard::{give_all_units, give_ints, give_units, guarded, object, read, read_units};
use crate::handles::Registry;

/// A dictionary's bytes: a mapped file, or a copy of a heap trie the app
/// built (`PackedTrie`), serialised by the Kotlin codec.
pub enum DictBytes {
    Mapped(Mmap),
    Owned(Vec<u8>),
}

impl AsRef<[u8]> for DictBytes {
    fn as_ref(&self) -> &[u8] {
        match self {
            DictBytes::Mapped(map) => map,
            DictBytes::Owned(bytes) => bytes,
        }
    }
}

pub static DICTS: LazyLock<Registry<Dict<DictBytes>>> = LazyLock::new(Registry::new);
pub static PACKS: LazyLock<Registry<Pack<Mmap>>> = LazyLock::new(Registry::new);

thread_local! {
    /// Per-thread scratch for the strings a call reads, so a lookup on the
    /// keystroke path allocates nothing.
    static UNITS: RefCell<[Vec<u16>; 3]> = RefCell::new([Vec::new(), Vec::new(), Vec::new()]);
    static BYTES: RefCell<[Vec<u8>; 3]> = RefCell::new([Vec::new(), Vec::new(), Vec::new()]);
}

fn map(path: &str) -> Option<Mmap> {
    let file = File::open(path).ok()?;
    // SAFETY: read-only, and the app never truncates these files in place
    // (see the module comment).
    unsafe { Mmap::map(&file) }.ok()
}

/// UTF-16 to UTF-8 as Java's `String.toByteArray(UTF_8)` does it: a lone
/// surrogate becomes `?`.
pub fn java_utf8(units: &[u16], out: &mut Vec<u8>) {
    out.clear();
    let mut buf = [0u8; 4];
    for c in char::decode_utf16(units.iter().copied()) {
        match c {
            Ok(c) => out.extend_from_slice(c.encode_utf8(&mut buf).as_bytes()),
            Err(_) => out.push(b'?'),
        }
    }
}

// ---- .wmdict ----------------------------------------------------------------

/// `dictOpen(path)`: a handle, or 0 when the file is missing or is not a
/// `.wmdict` this reader can trust.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let path = read(env, &path)?;
        let bytes = map(&path).map(DictBytes::Mapped);
        Some(bytes.and_then(Dict::open).map_or(0, |dict| DICTS.insert(dict)))
    })
}

/// `dictFromBytes(bytes)`: a handle on a copy of `.wmdict` bytes the app
/// wrote in memory, or 0 when they are not a file this reader trusts.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictFromBytes(
    mut env: JNIEnv,
    _class: JClass,
    bytes: JByteArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let bytes = env.convert_byte_array(&bytes).ok()?;
        Some(Dict::open(DictBytes::Owned(bytes)).map_or(0, |dict| DICTS.insert(dict)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        DICTS.remove(handle);
        // The decoder's rank table for it is dead weight now; left, it stayed
        // until 48 had piled up (a dictionary is reopened at every reload).
        crate::decoder::forget_ranks(handle);
        Some(())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictFrequencyOf(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
) -> jint {
    guarded(&mut env, 0, |env| {
        let dict = object!(env, DICTS, handle);
        UNITS.with_borrow_mut(|[units, ..]| {
            read_units(env, &word, units)?;
            Some(dict.frequency_of_units(units))
        })
    })
}

/// `dictComplete(handle, prefix, limit, frequencies)`: the completions, best
/// first, with each one's frequency written to `frequencies` at the same
/// index; no more of them than `frequencies` holds.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictComplete(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    prefix: JString,
    limit: jint,
    frequencies: JIntArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let dict = object!(env, DICTS, handle);
        // Never more than the caller left room for.
        let limit = (limit.max(0) as usize).min(env.get_array_length(&frequencies).ok()?.max(0) as usize);
        let found = UNITS.with_borrow_mut(|[units, ..]| {
            read_units(env, &prefix, units)?;
            Some(dict.complete_units(units, limit))
        })?;
        let counts: Vec<i32> = found.iter().map(|(_, f)| *f).collect();
        env.set_int_array_region(&frequencies, 0, &counts).ok()?;
        give_all_units(env, found.iter().map(|(text, _)| text.as_slice()))
    })
}

/// `dictEntries(handle)`: `[String[] words, int[] frequencies]`, depth first.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_dictEntries(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let dict = object!(env, DICTS, handle);
        // One flat buffer and the word boundaries, rather than a Vec per word.
        let mut text: Vec<u16> = Vec::new();
        let mut ends: Vec<usize> = Vec::with_capacity(dict.word_count() as usize);
        let mut counts: Vec<i32> = Vec::with_capacity(dict.word_count() as usize);
        dict.for_each_entry(|word, frequency| {
            text.extend_from_slice(word);
            ends.push(text.len());
            counts.push(frequency);
        });
        let words = (0..ends.len()).map(|i| &text[if i == 0 { 0 } else { ends[i - 1] }..ends[i]]);
        let words = give_all_units(env, words)?;
        // SAFETY: the array NewObjectArray just returned, as a local reference.
        let words = unsafe { JObject::from_raw(words) };
        let counts = give_ints(env, &counts)?;
        let pair: JObjectArray = env.new_object_array(2, "java/lang/Object", JObject::null()).ok()?;
        env.set_object_array_element(&pair, 0, &words).ok()?;
        env.set_object_array_element(&pair, 1, &counts).ok()?;
        Some(pair.into_raw())
    })
}

// ---- .wmng ------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramOpen(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let path = read(env, &path)?;
        Some(map(&path).and_then(Pack::open).map_or(0, |pack| PACKS.insert(pack)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        PACKS.remove(handle);
        Some(())
    })
}

/// Reads up to three Java strings as Java-style UTF-8 into the thread's
/// scratch and runs `f` over them.
fn with_words<R>(
    env: &mut JNIEnv,
    words: &[&JString],
    f: impl FnOnce(&[Vec<u8>; 3]) -> R,
) -> Option<R> {
    UNITS.with_borrow_mut(|units| {
        BYTES.with_borrow_mut(|bytes| {
            for (i, word) in words.iter().enumerate() {
                read_units(env, word, &mut units[i])?;
                java_utf8(&units[i], &mut bytes[i]);
            }
            Some(f(bytes))
        })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramBigramCount(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: JString,
    second: JString,
) -> jint {
    guarded(&mut env, 0, |env| {
        let pack = object!(env, PACKS, handle);
        with_words(env, &[&first, &second], |w| pack.bigram_count_bytes(&w[0], &w[1]))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramTrigramCount(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: JString,
    second: JString,
    third: JString,
) -> jint {
    guarded(&mut env, 0, |env| {
        let pack = object!(env, PACKS, handle);
        with_words(env, &[&first, &second, &third], |w| pack.trigram_count_bytes(&w[0], &w[1], &w[2]))
    })
}

fn give_words(env: &mut JNIEnv, words: Vec<String>) -> Option<jobjectArray> {
    let units: Vec<Vec<u16>> = words.iter().map(|w| w.encode_utf16().collect()).collect();
    give_all_units(env, units.iter().map(Vec::as_slice))
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramNextWords(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: JString,
    limit: jint,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let pack = object!(env, PACKS, handle);
        let words = with_words(env, &[&first], |w| pack.next_words_bytes(&w[0], limit.max(0) as usize))?;
        give_words(env, words)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramNextWordsAfter(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    first: JString,
    second: JString,
    limit: jint,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let pack = object!(env, PACKS, handle);
        let words = with_words(env, &[&first, &second], |w| {
            pack.next_words_after_bytes(&w[0], &w[1], limit.max(0) as usize)
        })?;
        give_words(env, words)
    })
}

/// `ngramWord(handle, id)`: the word, or null for an id the pack does not hold.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramWord(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    id: jint,
) -> jstring {
    guarded(&mut env, null_mut(), |env| {
        let pack = object!(env, PACKS, handle);
        match usize::try_from(id).ok().and_then(|id| pack.word(id)) {
            None => Some(null_mut()),
            Some(word) => give_units(env, &word.encode_utf16().collect::<Vec<u16>>()),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ngramIdOf(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    word: JString,
) -> jint {
    guarded(&mut env, -1, |env| {
        let pack = object!(env, PACKS, handle);
        with_words(env, &[&word], |w| pack.id_of_bytes(&w[0]).map_or(-1, |id| id as jint))
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utf8_matches_javas_encoder() {
        let mut out = Vec::new();
        java_utf8(&"শব্দ😀a".encode_utf16().collect::<Vec<u16>>(), &mut out);
        assert_eq!("শব্দ😀a".as_bytes(), &out[..]);
        // A lone high and a lone low surrogate: one `?` each.
        java_utf8(&[0x61, 0xD83D, 0x62, 0xDE00], &mut out);
        assert_eq!(b"a?b?", &out[..]);
    }
}
