//! The tap decoder (`wm-engine`'s `decoder`): `PredictionNative.decoderDecide`,
//! which the Kotlin `SuggestionEngine` asks what a buffer of taps meant when
//! a space or a mark is about to commit it.
//!
//! One crossing per commit. The word lists are the walk's own sources, named
//! by handle and kind as in `fuzzy.rs`; a list's ranks (the prior's input)
//! are worked out once per handle and kept. Context is the corpus pair
//! packs, the bundled seed pairs as two arrays, the user's own pairs from
//! the lexicon among the sources, and the context model's session when one
//! is open.

use std::collections::HashMap;
use std::ptr::null_mut;
use std::sync::{Arc, LazyLock, Mutex};

use jni::objects::{JCharArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray, JObjectArray, JString};
use jni::sys::{jdouble, jint, jlong, jobjectArray};
use jni::JNIEnv;

use wm_engine::decoder::{self, Geometry, Pairs, Params, Ranks, Source, Tap};
use wm_engine::lm::Prefix;

use crate::dict::PACKS;
use crate::guard::{give_all, guarded, object, read, read_units, throw_state};
use crate::lm::SESSIONS;
use crate::sources::{with_walkers_and_lexicons, KIND_DICTIONARY, KIND_LEXICON};

/// Ranks per dictionary handle. A handle's list never changes, so its ranks
/// are read once; the map is dropped whole past a few dozen, which only
/// costs a re-read.
static RANKS: LazyLock<Mutex<HashMap<i64, Arc<Ranks>>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
const RANK_CACHE: usize = 48;

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> Option<Vec<String>> {
    let len = env.get_array_length(array).ok()?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = JString::from(env.get_object_array_element(array, i).ok()?);
        out.push(read(env, &item)?);
        env.delete_local_ref(item).ok()?;
    }
    Some(out)
}

fn floats(env: &mut JNIEnv, array: &JFloatArray) -> Option<Vec<f32>> {
    let mut out = vec![0f32; env.get_array_length(array).ok()? as usize];
    env.get_float_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn ints(env: &mut JNIEnv, array: &JIntArray) -> Option<Vec<i32>> {
    let mut out = vec![0i32; env.get_array_length(array).ok()? as usize];
    env.get_int_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn longs(env: &mut JNIEnv, array: &JLongArray) -> Option<Vec<i64>> {
    let mut out = vec![0i64; env.get_array_length(array).ok()? as usize];
    env.get_long_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

/// `decoderDecide(dicts, kinds, typed, touchKeys, touchCenters, touchPoints,
/// space, sigmaScale, before, packs, seedFirst, seedSecond, seedCounts,
/// lmSession, lmWeight, register, field, lang, margin, knownMargin, out)`.
///
/// `dicts`/`kinds` are the walk's sources (0 a dictionary, 1 a user
/// lexicon, 2 a plain trie). The touch frame is absent when `touchKeys` is
/// null; `touchPoints` holds an x, y per typed character (NaN unknown) and
/// `space` the bar as centre x, centre y, half width, half height, or null
/// to place it under the bottom row. `sigmaScale` scales the decoder's tap
/// spread (the mistype tolerance). `before` is the field's last words,
/// oldest first. `packs` are pair-pack handles; the seed pairs cross as
/// three parallel arrays. `lmSession` is 0 for none.
///
/// Returns `[apply, offer]`, each empty for none; `out` receives the best
/// reading's lead over the literal, the number of words it has, and its
/// edits, slips and inserted spaces.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_decoderDecide(
    mut env: JNIEnv,
    _class: JClass,
    dicts: JLongArray,
    kinds: JIntArray,
    typed: JString,
    touch_keys: JCharArray,
    touch_centers: JFloatArray,
    touch_points: JFloatArray,
    space: JFloatArray,
    sigma_scale: jdouble,
    before: JObjectArray,
    packs: JLongArray,
    seed_first: JObjectArray,
    seed_second: JObjectArray,
    seed_counts: JIntArray,
    lm_session: jlong,
    lm_weight: jdouble,
    register: jint,
    field: jint,
    lang: jint,
    margin: jdouble,
    known_margin: jdouble,
    out: JDoubleArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let handles = longs(env, &dicts)?;
        let kind_codes = ints(env, &kinds)?;
        if kind_codes.len() < handles.len() {
            return throw_state(env, "wm-engine: a kind per source");
        }
        let mut typed_units = Vec::new();
        read_units(env, &typed, &mut typed_units)?;
        let typed_text = String::from_utf16_lossy(&typed_units);

        let mut geometry = Geometry::default();
        let mut points: Vec<Option<(f32, f32)>> = vec![None; typed_units.len()];
        if !touch_keys.is_null() {
            let mut keys = vec![0u16; env.get_array_length(&touch_keys).ok()? as usize];
            env.get_char_array_region(&touch_keys, 0, &mut keys).ok()?;
            let centers = floats(env, &touch_centers)?;
            if centers.len() < keys.len() * 2 {
                return throw_state(env, "wm-engine: a touch centre per key");
            }
            geometry.keys = keys.iter().enumerate().map(|(i, &k)| (k, centers[2 * i], centers[2 * i + 1])).collect();
            if !space.is_null() {
                let s = floats(env, &space)?;
                if s.len() >= 4 {
                    geometry.space = Some((s[0], s[1], s[2], s[3]));
                }
            }
            geometry = geometry.with_estimated_space();
            if !touch_points.is_null() {
                let p = floats(env, &touch_points)?;
                for (i, xy) in p.chunks_exact(2).enumerate().take(points.len()) {
                    if !xy[0].is_nan() && !xy[1].is_nan() {
                        points[i] = Some((xy[0], xy[1]));
                    }
                }
            }
        }
        let taps: Vec<Tap> = typed_units.iter().zip(&points).map(|(&c, &at)| Tap { typed: c, at }).collect();

        let before = strings(env, &before)?;
        let before_refs: Vec<&str> = before.iter().map(String::as_str).collect();
        let pack_handles = longs(env, &packs)?;
        let packs: Vec<_> = pack_handles.iter().filter_map(|&h| PACKS.get(h)).collect();
        let mut seeds: HashMap<(String, String), i32> = HashMap::new();
        if !seed_first.is_null() {
            let a = strings(env, &seed_first)?;
            let b = strings(env, &seed_second)?;
            let c = ints(env, &seed_counts)?;
            for ((a, b), c) in a.into_iter().zip(b).zip(c) {
                seeds.insert((a, b), c);
            }
        }

        let mut params = Params::default();
        let scale = if sigma_scale.is_finite() && sigma_scale > 0.0 { sigma_scale } else { 1.0 };
        params.sigma_x *= scale;
        params.sigma_y *= scale;

        // Ranks for each dictionary handle, before the walk takes its locks.
        let mut source_ranks: Vec<Option<Arc<Ranks>>> = vec![None; handles.len()];
        for (i, (&h, &k)) in handles.iter().zip(&kind_codes).enumerate() {
            if k != KIND_DICTIONARY {
                continue;
            }
            let cached = RANKS.lock().unwrap_or_else(|e| e.into_inner()).get(&h).cloned();
            source_ranks[i] = match cached {
                Some(r) => Some(r),
                None => {
                    let dict = object!(env, crate::dict::DICTS, h);
                    let r = Arc::new(Ranks::of(dict.as_ref()));
                    let mut map = RANKS.lock().unwrap_or_else(|e| e.into_inner());
                    if map.len() >= RANK_CACHE {
                        map.clear();
                    }
                    map.insert(h, r.clone());
                    Some(r)
                }
            };
        }
        let list_size = source_ranks.iter().flatten().map(|r| r.size()).max().unwrap_or(10_000);

        let verdict = with_walkers_and_lexicons(env, &handles, &kind_codes, |walkers, lexicons| {
            // Priors: a list's by rank, a learned word's by its count, and a
            // plain trie (tests) ranked on the spot.
            let local: Vec<Arc<Ranks>> = walkers
                .iter()
                .enumerate()
                .map(|(i, w)| match &source_ranks[i] {
                    Some(r) => r.clone(),
                    None => Arc::new(if kind_codes[i] == KIND_LEXICON { Ranks::default() } else { Ranks::of(*w) }),
                })
                .collect();
            let priors: Vec<Box<dyn Fn(i32) -> f64 + '_>> = (0..walkers.len())
                .map(|i| -> Box<dyn Fn(i32) -> f64 + '_> {
                    if kind_codes[i] == KIND_LEXICON {
                        Box::new(move |f: i32| decoder::learned_prior(f, list_size))
                    } else {
                        let r = local[i].clone();
                        Box::new(move |f: i32| r.prior(f))
                    }
                })
                .collect();
            let sources: Vec<Source> = walkers.iter().zip(&priors).map(|(&walker, prior)| Source { walker, prior: prior.as_ref() }).collect();
            let bigram = |a: &str, b: &str| {
                let mut c = packs.iter().map(|p| p.bigram_count(a, b)).max().unwrap_or(0);
                c = c.max(seeds.get(&(a.to_string(), b.to_string())).copied().unwrap_or(0));
                for lex in lexicons {
                    c = c.max(lex.bigram_count(a, b));
                }
                c
            };
            let trigram = |a: &str, b: &str, c: &str| {
                let mut n = packs.iter().map(|p| p.trigram_count(a, b, c)).max().unwrap_or(0);
                for lex in lexicons {
                    n = n.max(lex.trigram_count(a, b, c));
                }
                n
            };
            let context = Pairs {
                bigram: &bigram,
                trigram: &trigram,
                alpha: decoder::PAIR_ALPHA,
                tri: decoder::TRIPLE_ALPHA,
                cap: decoder::PAIR_CAP,
            };
            let adjacent = |_: u16, _: u16| false;
            decoder::decode(&taps, &geometry, &sources, &context, &before_refs, &params, &adjacent, 6)
        });
        let mut decoded = verdict?;

        if lm_session != 0 && lm_weight > 0.0 {
            if let Some(session) = SESSIONS.get(lm_session) {
                let prefix = Prefix { register: register.clamp(0, 2) as u8, field: field.clamp(0, 3) as u8, lang: lang.clamp(0, 3) as u8 };
                let mut lm = session.lock().unwrap_or_else(|e| e.into_inner());
                decoder::blend(&mut decoded, &typed_text, lm_weight, |text| {
                    let mut ctx: Vec<&str> = before_refs.clone();
                    let mut total = 0.0;
                    for w in text.split(' ') {
                        lm.set_context(prefix, &ctx);
                        total += lm.logprob(w)? as f64;
                        ctx.push(w);
                    }
                    Some(total)
                });
                // The session is shared: put back the context it had.
                lm.set_context(prefix, &before_refs);
            }
        }

        let v = decoder::verdict(&decoded, &typed_text, margin, known_margin);
        let best = v.apply.as_ref().or(v.offer.as_ref()).and_then(|t| decoded.readings.iter().find(|r| &r.text == t));
        let stats = [
            v.lead,
            best.map_or(0.0, |r| r.text.split(' ').count() as f64),
            best.map_or(0.0, |r| r.edits as f64),
            best.map_or(0.0, |r| r.slips as f64),
            best.map_or(0.0, |r| r.spaces as f64),
        ];
        if env.get_array_length(&out).ok()? < stats.len() as i32 {
            return throw_state(env, "wm-engine: the out array holds five");
        }
        env.set_double_array_region(&out, 0, &stats).ok()?;
        give_all(env, vec![v.apply.unwrap_or_default(), v.offer.unwrap_or_default()])
    })
}
