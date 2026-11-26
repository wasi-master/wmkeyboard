//! The glide decoder (`wm-engine`'s `glide`): `PredictionNative.glide*`,
//! which the Kotlin `GlideBeam` calls when every trie it decodes against has
//! a Rust twin.
//!
//! A decode with the user's learned shapes is two calls on one thread: the
//! walk ([`glideDecode`] with `phase` 1), which returns the words the shape
//! channel will score, and [`glideFinish`] with those words' shapes. Without
//! learned shapes it is the one call (`phase` 0). The walk's state between
//! the two stays in this thread's decoder. `ctcDecode` and `ctcFinish` are
//! the same pair for `CtcGlideBeam`.

use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex, TryLockError, Weak};

use jni::objects::{JByteArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jobjectArray};
use jni::JNIEnv;

use wm_engine::fuzzy::{RankScale, Tier};
use wm_engine::glide::ctc::{CtcDecoder, CtcRanked, Scoring, Stroke as CtcStroke, CLASSES};
use wm_engine::glide::duet::{Duet, DuetDecoder, Token};
use wm_engine::glide::keymap::KeyMap;
use wm_engine::glide::workspace::MAX_STATES;
use wm_engine::glide::{Candidate, Decoder, LearnedShapes, Point, Ranked, Source, Tuning, MAX_POPS};

use crate::guard::{give_all_units, give_ints, guarded, object, read_units, throw_state};
use crate::handles::Registry;
use crate::sources::with_walkers;

static KEYMAPS: LazyLock<Registry<KeyMap>> = LazyLock::new(Registry::new);

/// A CTC decode's stroke: the glide tuning its loops are read with, the
/// path and the key map.
type CtcStrokeOf = (Tuning, Vec<Point>, Arc<KeyMap>);

/// One thread's glide decoders and the walks waiting on them. A decoder's
/// workspace keeps the room its longest stroke grew it to, about 5 MB at
/// the most, which [`trim_lanes`] gives back while the keyboard is hidden.
struct Lane {
    decoder: Decoder,
    /// The walk waiting for its shapes, and the key map it ran on.
    pending: Option<(Ranked, Arc<KeyMap>)>,
    ctc: CtcDecoder,
    /// The CTC walk waiting for its shapes, with the stroke it read.
    ctc_pending: Option<(CtcRanked, Option<CtcStrokeOf>, f32)>,
    duet: DuetDecoder,
}

impl Lane {
    fn new() -> Lane {
        Lane {
            decoder: Decoder::new(Tuning::default()),
            pending: None,
            ctc: CtcDecoder::default(),
            ctc_pending: None,
            duet: DuetDecoder::default(),
        }
    }

    /// The decoders back to the room they started with, unless a phase-1
    /// walk is waiting on what its decoder holds. Whether it was done.
    fn trim(&mut self) -> bool {
        if self.pending.is_some() || self.ctc_pending.is_some() {
            return false;
        }
        self.decoder.trim();
        self.ctc.trim();
        self.duet.trim();
        true
    }
}

/// A thread's lane, behind a lock only [`trim_lanes`] ever contends, and
/// the trim it asked for while the lane was busy.
struct LaneSlot {
    lane: Mutex<Lane>,
    trim: AtomicBool,
}

/// Every thread's lane, so a trim on any thread reaches them all: a
/// thread-local alone is out of reach of the thread that trims.
static LANES: Mutex<Vec<Weak<LaneSlot>>> = Mutex::new(Vec::new());

thread_local! {
    static LANE: Arc<LaneSlot> = {
        let slot = Arc::new(LaneSlot { lane: Mutex::new(Lane::new()), trim: AtomicBool::new(false) });
        let mut all = LANES.lock().unwrap_or_else(|e| e.into_inner());
        all.retain(|lane| lane.strong_count() > 0);
        all.push(Arc::downgrade(&slot));
        slot
    };
}

/// `f` over this thread's lane. A trim asked for while it was busy runs
/// when `f` is done, if nothing is left waiting on the decoders.
fn with_lane<R>(f: impl FnOnce(&mut Lane) -> R) -> R {
    LANE.with(|slot| {
        let mut lane = slot.lane.lock().unwrap_or_else(|e| e.into_inner());
        let out = f(&mut lane);
        if slot.trim.load(Ordering::Acquire) && lane.trim() {
            slot.trim.store(false, Ordering::Release);
        }
        out
    })
}

/// Every thread's glide decoders back to the room they started with
/// (`NativeEngine.trimIdle`). A lane in use, or holding a phase-1 walk, is
/// trimmed by its own thread once that is done. Returns how many lanes were
/// trimmed now.
pub(crate) fn trim_lanes() -> usize {
    let mut all = LANES.lock().unwrap_or_else(|e| e.into_inner());
    all.retain(|lane| lane.strong_count() > 0);
    let mut trimmed = 0;
    for slot in all.iter().filter_map(Weak::upgrade) {
        // Asked first: a lane finishing now sees it before it lets go.
        slot.trim.store(true, Ordering::Release);
        let lane = match slot.lane.try_lock() {
            Ok(lane) => Some(lane),
            Err(TryLockError::Poisoned(e)) => Some(e.into_inner()),
            Err(TryLockError::WouldBlock) => None,
        };
        if let Some(mut lane) = lane {
            if lane.trim() {
                slot.trim.store(false, Ordering::Release);
                trimmed += 1;
            }
        }
    }
    trimmed
}

fn floats(env: &mut JNIEnv, a: &JFloatArray) -> Option<Vec<f32>> {
    let mut out = vec![0f32; env.get_array_length(a).ok()? as usize];
    env.get_float_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn ints(env: &mut JNIEnv, a: &JIntArray) -> Option<Vec<i32>> {
    let mut out = vec![0i32; env.get_array_length(a).ok()? as usize];
    env.get_int_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn doubles(env: &mut JNIEnv, a: &JDoubleArray) -> Option<Vec<f64>> {
    let mut out = vec![0f64; env.get_array_length(a).ok()? as usize];
    env.get_double_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

fn longs(env: &mut JNIEnv, a: &JLongArray) -> Option<Vec<i64>> {
    let mut out = vec![0i64; env.get_array_length(a).ok()? as usize];
    env.get_long_array_region(a, 0, &mut out).ok()?;
    Some(out)
}

/// The tuning layout `glideTuningVersion` reports and `tuning` reads. 3
/// says the same tuning layout as 2, plus each source's ranking scale
/// appended to the decode's weights ([`SourceSpec::scale`]). 4 takes a 33rd
/// field, `join_link_cost`, in the envelope as in the bare array.
const GLIDE_TUNING_VERSION: jint = 4;

/// The first tuning version with the envelope; `tuning` reads it from any
/// version from here to [`GLIDE_TUNING_VERSION`].
const FIRST_ENVELOPE_VERSION: jint = 2;

/// `Tuning` from the Kotlin order: every field, booleans as 0/1, the
/// floats exactly representable as doubles.
///
/// Three layouts are accepted:
///
/// - a versioned envelope `[version, fieldCount, fields..]`, version
///   [`GLIDE_TUNING_VERSION`] with a field count of 32 (thirty-four values),
///   whose fields are read from `&v[2..]`;
/// - the unversioned 32-value array, the thirty shipped fields plus
///   `max_pops` and `max_states`; or
/// - the old thirty-value array, which reads as the shipped decode.
///
/// The field slice must hold at least the thirty shipped values; the
/// optional slots after them carry the per-decode backstops (`max_pops`,
/// `max_states`) and default when absent. `max_states` is capped at the
/// workspace's documented ceiling.
fn tuning(v: &[f64]) -> Option<Tuning> {
    let fields: &[f64] = if v.len() >= 2
        && (FIRST_ENVELOPE_VERSION..=GLIDE_TUNING_VERSION).contains(&(v[0] as i32))
        && (v[1] as i32 == 32 || v[1] as i32 == 33)
    {
        &v[2..]
    } else {
        v
    };
    if fields.len() < 30 {
        return None;
    }
    Some(Tuning {
        sigma: fields[0] as f32,
        max_point_cost: fields[1] as f32,
        gap_weight: fields[2] as f32,
        gap_window: fields[3] as f32,
        shape_weight: fields[4],
        repeat_cost: fields[5] as f32,
        unlooped_repeat: fields[6] as f32,
        dwell_full: fields[7] as f32,
        unclaimed_dwell: fields[8] as f32,
        loop_extent: fields[9] as f32,
        loop_min_arc: fields[10] as f32,
        loop_radius: fields[11] as f32,
        wiggle_extent: fields[12] as f32,
        wiggle_weight: fields[13] as f32,
        loop_exclusion: fields[14] as f32,
        unclaimed_loop: fields[15] as f32,
        learned_shape_gain: fields[16],
        shape_seeding: fields[17] != 0.0,
        shape_channel: fields[18],
        start_radius: fields[19] as f32,
        end_radius: fields[20] as f32,
        near_radius: fields[21] as f32,
        vocabulary_rank: fields[22] as i32,
        look_ahead_cost: fields[23],
        look_ahead_budget: fields[24] as i32,
        joiner_cost: fields[25] as f32,
        freq_weight: fields[26],
        join_cost: fields[27],
        join_min_part: fields[28] as i32,
        join_part_penalty: fields[29],
        join_link_cost: fields.get(32).copied().unwrap_or(f64::INFINITY),
        max_pops: fields.get(30).map_or(MAX_POPS, |&x| (x as usize).max(1)),
        max_states: fields.get(31).map_or(MAX_STATES, |&x| (x as usize).max(1).min(MAX_STATES)),
    })
}

fn path(env: &mut JNIEnv, xs: &JFloatArray, ys: &JFloatArray, ts: &JLongArray) -> Option<Vec<Point>> {
    let (xs, ys, ts) = (floats(env, xs)?, floats(env, ys)?, longs(env, ts)?);
    Some(xs.iter().zip(&ys).zip(&ts).map(|((&x, &y), &t)| Point { x, y, t }).collect())
}

/// The sources of a decode as Kotlin passes them: handles, kinds and tiers
/// as for `fuzzySearch`, and each source's vocabulary cap as a frequency.
struct SourceSpec {
    handles: Vec<i64>,
    kinds: Vec<i32>,
    weights: Vec<f64>,
    tiers: Vec<i32>,
    floors: Vec<i32>,
}

impl SourceSpec {
    fn read(
        env: &mut JNIEnv,
        dicts: &JLongArray,
        kinds: &JIntArray,
        weights: &JDoubleArray,
        tiers: &JIntArray,
        floors: &JIntArray,
    ) -> Option<SourceSpec> {
        Some(SourceSpec {
            handles: longs(env, dicts)?,
            kinds: ints(env, kinds)?,
            weights: doubles(env, weights)?,
            tiers: ints(env, tiers)?,
            floors: ints(env, floors)?,
        })
    }
}

impl SourceSpec {
    /// Source `i`'s ranking scale. Kotlin appends each source's floor and
    /// stretch to `weights` (three values a source) once the library says it
    /// reads them (`glideTuningVersion` 3); a weights array of one value a
    /// source reads every list as stored.
    fn scale(&self, i: usize) -> RankScale {
        let n = self.handles.len();
        if self.weights.len() >= 3 * n {
            RankScale { floor: self.weights[n + i], per_nat: self.weights[2 * n + i] }
        } else {
            RankScale::RAW
        }
    }
}

/// `f` over the sources `spec` names, each trie held for the length of the call.
fn with_sources<R>(env: &mut JNIEnv, spec: &SourceSpec, f: impl FnOnce(&[Source]) -> R) -> Option<R> {
    with_walkers(env, &spec.handles, &spec.kinds, |walkers| {
        let sources: Vec<Source> = walkers
            .iter()
            .enumerate()
            .map(|(i, &walker)| Source {
                walker,
                log_weight: spec.weights[i],
                tier: if spec.tiers[i] == 1 { Tier::User } else { Tier::Dictionary },
                min_frequency: spec.floors[i],
                scale: spec.scale(i),
            })
            .collect();
        f(&sources)
    })
}

/// `glideKeyMapOpen(keyX, keyY, codePoints, keyOf)`.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideKeyMapOpen(
    mut env: JNIEnv,
    _class: JClass,
    key_x: JFloatArray,
    key_y: JFloatArray,
    code_points: JIntArray,
    key_of: JIntArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let map = KeyMap::new(floats(env, &key_x)?, floats(env, &key_y)?, ints(env, &code_points)?, ints(env, &key_of)?);
        Some(KEYMAPS.insert(map))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideKeyMapClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        KEYMAPS.remove(handle);
        Some(())
    })
}

/// `found` as `glideDecode` returns it: `Object[] { String[] words,
/// double[] [score, shapeCost] per word, int[] [user tier, ahead] per word
/// plus `extra` }`, and `drawn` as a fourth element, a `byte[]`, when given.
fn give_candidates(env: &mut JNIEnv, found: &[Candidate], extra: &[i32], drawn: Option<&[i8]>) -> Option<jobjectArray> {
    let reals: Vec<f64> = found.iter().flat_map(|c| [c.score, c.shape_cost]).collect();
    let mut flags: Vec<i32> = found.iter().flat_map(|c| [(c.tier == Tier::User) as i32, c.ahead]).collect();
    flags.extend_from_slice(extra);
    // SAFETY: each is the local reference its constructor just returned.
    let words = unsafe { JObject::from_raw(give_all_units(env, found.iter().map(|c| c.word.as_slice()))?) };
    let doubles = env.new_double_array(reals.len() as i32).ok()?;
    env.set_double_array_region(&doubles, 0, &reals).ok()?;
    let ints = give_ints(env, &flags)?;
    let class = env.find_class("java/lang/Object").ok()?;
    let out = env.new_object_array(if drawn.is_some() { 4 } else { 3 }, &class, JObject::null()).ok()?;
    env.set_object_array_element(&out, 0, &words).ok()?;
    env.set_object_array_element(&out, 1, &doubles).ok()?;
    env.set_object_array_element(&out, 2, &ints).ok()?;
    if let Some(drawn) = drawn {
        let bytes: Vec<u8> = drawn.iter().map(|&b| b as u8).collect();
        let array = env.byte_array_from_slice(&bytes).ok()?;
        env.set_object_array_element(&out, 3, &array).ok()?;
    }
    Some(out.into_raw())
}

/// `glideTuningVersion()`: the tuning array layout `tuning` reads, so Kotlin
/// can build the versioned envelope instead of guessing the slots.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideTuningVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    GLIDE_TUNING_VERSION
}

/// `glideDecode(tuning, xs, ys, ts, keyMap, keyWidth, dicts, kinds, weights,
/// tiers, minFrequencies, limit, lookAhead, phase, openHead, openTail)`.
///
/// `openHead` and `openTail` are how many leading and trailing samples are
/// the walk from or to the spacebar (#968), 0 for a stroke that began and
/// ended on keys.
///
/// `kinds` and `tiers` as for `fuzzySearch`; `minFrequencies` is each
/// source's vocabulary cap. Phase 0 decodes outright; phase 1 stops before
/// the shape channel and returns every word it will score, readings first
/// (the int array's last slot then holds the reading count) and the drawn
/// stroke quantised for the seeding, for `glideFinish`. Null when the stroke is no decode at all.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideDecode(
    mut env: JNIEnv,
    _class: JClass,
    tuning_values: JDoubleArray,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    dicts: JLongArray,
    kinds: JIntArray,
    weights: JDoubleArray,
    tiers: JIntArray,
    min_frequencies: JIntArray,
    limit: jint,
    look_ahead: jint,
    phase: jint,
    open_head: jint,
    open_tail: jint,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(tuning) = tuning(&doubles(env, &tuning_values)?) else {
            return throw_state(env, "wm-engine: a full glide tuning");
        };
        let path = path(env, &xs, &ys, &ts)?;
        let keys = object!(env, KEYMAPS, keymap);
        let spec = SourceSpec::read(env, &dicts, &kinds, &weights, &tiers, &min_frequencies)?;
        let limit = limit.max(0) as usize;
        let look_ahead = look_ahead.max(0) as usize;
        // The walk and what reads the workspace it leaves, under one hold of
        // the lane, so no trim lands between them.
        let decoded = with_sources(env, &spec, |sources| {
            with_lane(|lane| {
                let decoder = &mut lane.decoder;
                decoder.tuning = tuning;
                decoder.open_head = open_head.max(0) as usize;
                decoder.open_tail = open_tail.max(0) as usize;
                let ranked = decoder.decode_ranked(&path, &keys, key_width, sources, limit, look_ahead)?;
                if phase == 0 {
                    return Some(Ok(decoder.finish(ranked, &keys, None, None)));
                }
                let mut all: Vec<Candidate> = ranked.read.clone();
                all.extend(ranked.guesses.iter().cloned());
                let read_count = ranked.read.len() as i32;
                // The seeding compares the stroke as the walk left the
                // workspace, before the shape channel runs, so it is taken now.
                let drawn = decoder.drawn_shape8();
                lane.pending = Some((ranked, keys.clone()));
                Some(Err((all, read_count, drawn)))
            })
        })?;
        match decoded {
            None => Some(null_mut()),
            Some(Ok(found)) => give_candidates(env, &found, &[], None),
            Some(Err((all, read_count, drawn))) => give_candidates(env, &all, &[read_count], Some(&drawn)),
        }
    })
}

/// `words[i]`'s learned shapes, `shapes[i]` (a `byte[][]`, possibly empty).
fn learned_shapes(env: &mut JNIEnv, words: &JObjectArray, shapes: &JObjectArray) -> Option<LearnedShapes> {
    let mut learned: LearnedShapes = LearnedShapes::new();
    let count = env.get_array_length(words).ok()?;
    for i in 0..count {
        let word = JString::from(env.get_object_array_element(words, i).ok()?);
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        env.delete_local_ref(word).ok()?;
        let list = JObjectArray::from(env.get_object_array_element(shapes, i).ok()?);
        let n = env.get_array_length(&list).ok()?;
        let mut stored = Vec::with_capacity(n as usize);
        for j in 0..n {
            let bytes = JByteArray::from(env.get_object_array_element(&list, j).ok()?);
            let v: Vec<u8> = env.convert_byte_array(&bytes).ok()?;
            stored.push(v.into_iter().map(|b| b as i8).collect());
            env.delete_local_ref(bytes).ok()?;
        }
        env.delete_local_ref(list).ok()?;
        // A word can be both a reading and a guess; only the reading
        // carries its shapes, and it comes first.
        learned.entry(units).or_insert(stored);
    }
    Some(learned)
}

/// What `glideFinish` reads off its arguments: the learned shapes and the
/// seeds, or `None` (with a Java exception pending) when a read failed.
fn glide_finish_inputs(
    env: &mut JNIEnv,
    words: &JObjectArray,
    shapes: &JObjectArray,
    seeds: &JObjectArray,
) -> Option<(LearnedShapes, Option<Vec<Vec<u16>>>)> {
    let learned = learned_shapes(env, words, shapes)?;
    let near = if seeds.is_null() {
        None
    } else {
        let n = env.get_array_length(seeds).ok()?;
        let mut near = Vec::with_capacity(n as usize);
        for i in 0..n {
            let word = JString::from(env.get_object_array_element(seeds, i).ok()?);
            let mut units = Vec::new();
            read_units(env, &word, &mut units)?;
            env.delete_local_ref(word).ok()?;
            near.push(units);
        }
        Some(near)
    };
    Some((learned, near))
}

/// `glideFinish(words, shapes, seeds)`: the last phase-1 decode on this
/// thread, finished with each of `words`' learned shapes (`shapes[i]`, a
/// `byte[][]`, possibly empty) and, when seeding is on, the words whose
/// stored shapes sit near the stroke (`seeds`, else null).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideFinish(
    mut env: JNIEnv,
    _class: JClass,
    words: JObjectArray,
    shapes: JObjectArray,
    seeds: JObjectArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        if !with_lane(|lane| lane.pending.is_some()) {
            return throw_state(env, "wm-engine: glideFinish without a phase-1 glideDecode on this thread");
        }
        // The walk is spent either way, as when it was taken before these
        // were read.
        let read = glide_finish_inputs(env, &words, &shapes, &seeds);
        let found = with_lane(|lane| {
            let (ranked, keys) = lane.pending.take()?;
            let (learned, near) = read?;
            Some(lane.decoder.finish(ranked, &keys, Some(&learned), near.as_deref()))
        })?;
        give_candidates(env, &found, &[], None)
    })
}

/// `glideAlign(tuning, word, xs, ys, ts, keyMap, keyWidth)`: `[keys, x, y,
/// samples, chars, sampleCount]` flattened as `IntArray keys + samples +
/// chars + [sampleCount]` and the coordinates through `floatsOut`, or null.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideAlign(
    mut env: JNIEnv,
    _class: JClass,
    tuning_values: JDoubleArray,
    word: JString,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    floats_out: JFloatArray,
) -> jni::sys::jintArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(tuning) = tuning(&doubles(env, &tuning_values)?) else {
            return throw_state(env, "wm-engine: a full glide tuning");
        };
        let mut units = Vec::new();
        read_units(env, &word, &mut units)?;
        let path = path(env, &xs, &ys, &ts)?;
        let keys = object!(env, KEYMAPS, keymap);
        let aligned = with_lane(|lane| {
            lane.decoder.tuning = tuning;
            lane.decoder.align(&units, &path, &keys, key_width)
        });
        let Some(a) = aligned else { return Some(null_mut()) };
        let mut coords = a.x.clone();
        coords.extend_from_slice(&a.y);
        if env.get_array_length(&floats_out).ok()? < coords.len() as i32 {
            return throw_state(env, "wm-engine: the out arrays are too short");
        }
        env.set_float_array_region(&floats_out, 0, &coords).ok()?;
        let mut flat = a.keys.clone();
        flat.extend_from_slice(&a.samples);
        flat.extend_from_slice(&a.chars);
        flat.push(a.sample_count);
        give_ints(env, &flat).map(|a| a.into_raw())
    })
}

/// `glideSampleShape(xs, ys, ts, keyWidth)`: the stroke as the shape store
/// keeps it, or null.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_glideSampleShape(
    mut env: JNIEnv,
    _class: JClass,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    key_width: jfloat,
) -> jni::sys::jbyteArray {
    guarded(&mut env, null_mut(), |env| {
        let path = path(env, &xs, &ys, &ts)?;
        let shape = with_lane(|lane| lane.decoder.sample_shape(&path, key_width));
        let Some(shape) = shape else { return Some(null_mut()) };
        let bytes: Vec<u8> = shape.into_iter().map(|b| b as u8).collect();
        env.byte_array_from_slice(&bytes).ok().map(|a| a.into_raw())
    })
}

/// `ctcDecode(logProbs, steps, dicts, kinds, weights, tiers,
/// minFrequencies, limit, width, scoring, tuning, xs, ys, ts, keyMap,
/// keyWidth, lookAhead, phase)`: `CtcGlideBeam.decode`. `scoring` is
/// `[lengthNorm, lengthBonus, freqWeight, pruneNorm, pruneBonus,
/// lookAheadCost, loopWeight, shapeWeight]`. The stroke (`tuning`, the path,
/// `keyMap`, `keyWidth`) is what the loops are read from and the learned
/// shapes compared with; an empty `tuning` or a `keyMap` of 0 decodes the
/// emissions alone. Phases as `glideDecode`'s: phase 1 returns every reading,
/// then the guesses, with the reading count in the last int, for
/// `ctcFinish` on the same thread.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ctcDecode(
    mut env: JNIEnv,
    _class: JClass,
    log_probs: JFloatArray,
    steps: jint,
    dicts: JLongArray,
    kinds: JIntArray,
    weights: JDoubleArray,
    tiers: JIntArray,
    min_frequencies: JIntArray,
    limit: jint,
    width: jint,
    scoring: JDoubleArray,
    tuning_values: JDoubleArray,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    look_ahead: jint,
    phase: jint,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let lp = floats(env, &log_probs)?;
        let steps = steps.max(0) as usize;
        if lp.len() < steps * CLASSES {
            return throw_state(env, "wm-engine: fewer emissions than steps");
        }
        let weights_in = doubles(env, &scoring)?;
        if weights_in.len() < 8 {
            return throw_state(env, "wm-engine: a full CTC scoring");
        }
        let tuning_in = doubles(env, &tuning_values)?;
        let stroke = if tuning_in.is_empty() || keymap == 0 {
            None
        } else {
            let Some(tuning) = tuning(&tuning_in) else {
                return throw_state(env, "wm-engine: a full glide tuning");
            };
            Some((tuning, path(env, &xs, &ys, &ts)?, object!(env, KEYMAPS, keymap)))
        };
        let spec = SourceSpec::read(env, &dicts, &kinds, &weights, &tiers, &min_frequencies)?;
        let (limit, width) = (limit.max(0) as usize, width.max(0) as usize);
        let decoded = with_sources(env, &spec, |sources| {
            with_lane(|lane| {
                let marks = stroke.as_ref().and_then(|(tuning, path, keys)| {
                    lane.decoder.tuning = tuning.clone();
                    lane.decoder.marks(path, keys, key_width)
                });
                let ctc = &mut lane.ctc;
                ctc.scoring = Scoring {
                    length_norm: weights_in[0] as f32,
                    length_bonus: weights_in[1] as f32,
                    freq_weight: weights_in[2] as f32,
                    prune_norm: weights_in[3] as f32,
                    prune_bonus: weights_in[4] as f32,
                    width,
                    look_ahead_cost: weights_in[5] as f32,
                    loop_weight: weights_in[6] as f32,
                    shape_weight: weights_in[7] as f32,
                };
                let keys = stroke.as_ref().map(|(_, _, keys)| keys.as_ref());
                let stroke_in = CtcStroke {
                    look_ahead: look_ahead.max(0) as usize,
                    marks: marks.zip(keys),
                };
                let ranked = ctc.walk(&lp, steps, sources, limit, width, &stroke_in);
                if phase == 0 || stroke.is_none() {
                    return Ok(ctc.finish(ranked, &mut |_| 0.0));
                }
                let mut all: Vec<Candidate> = ranked.read.clone();
                all.extend(ranked.guesses.iter().cloned());
                let read_count = ranked.read.len() as i32;
                lane.ctc_pending = Some((ranked, stroke, key_width));
                Err((all, read_count))
            })
        })?;
        match decoded {
            Ok(found) => give_candidates(env, &found, &[], None),
            Err((all, read_count)) => give_candidates(env, &all, &[read_count], None),
        }
    })
}

/// `ctcFinish(words, shapes)`: the last phase-1 `ctcDecode` on this thread,
/// finished with each of `words`' learned shapes (`shapes[i]`, possibly
/// empty).
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_ctcFinish(
    mut env: JNIEnv,
    _class: JClass,
    words: JObjectArray,
    shapes: JObjectArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        if !with_lane(|lane| lane.ctc_pending.is_some()) {
            return throw_state(env, "wm-engine: ctcFinish without a phase-1 ctcDecode on this thread");
        }
        // The walk is spent either way, as when it was taken before these
        // were read.
        let read = learned_shapes(env, &words, &shapes);
        let found = with_lane(|lane| {
            let (ranked, stroke, key_width) = lane.ctc_pending.take()?;
            let learned = read?;
            Some(match stroke {
                None => lane.ctc.finish(ranked, &mut |_| 0.0),
                Some((tuning, path, keys)) => {
                    let decoder = &mut lane.decoder;
                    decoder.tuning = tuning;
                    let prepared = decoder.prepare_shape(&path, key_width);
                    lane.ctc.finish(ranked, &mut |word| if prepared { decoder.learned_gain(word, &keys, &learned) } else { 0.0 })
                }
            })
        })?;
        give_candidates(env, &found, &[], None)
    })
}

/// `duetDecode(tuning, duet, tokenKinds, tokenSizes, xs, ys, ts, keyMap,
/// keyWidth, dicts, kinds, weights, tiers, minFrequencies, limit, partial)`:
/// `DuetBeam.decode`. Each token is a tap (kind 0, one point) or a swipe
/// (kind 1, its whole path); their points run on in `xs`, `ys` and `ts`,
/// `tokenSizes[i]` of them for token `i`. `duet` is `[orderSlackMs,
/// tapWindowMs, tapSigma, tapNearRadius, tapMaxCost, tapRepeatCost,
/// samplesPerKey, minSamples]`. The result is shaped as `glideDecode`'s.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_duetDecode(
    mut env: JNIEnv,
    _class: JClass,
    tuning_values: JDoubleArray,
    duet_values: JDoubleArray,
    token_kinds: JIntArray,
    token_sizes: JIntArray,
    xs: JFloatArray,
    ys: JFloatArray,
    ts: JLongArray,
    keymap: jlong,
    key_width: jfloat,
    dicts: JLongArray,
    kinds: JIntArray,
    weights: JDoubleArray,
    tiers: JIntArray,
    min_frequencies: JIntArray,
    limit: jint,
    partial: jboolean,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let Some(tuning) = tuning(&doubles(env, &tuning_values)?) else {
            return throw_state(env, "wm-engine: a full glide tuning");
        };
        let d = doubles(env, &duet_values)?;
        if d.len() < 8 {
            return throw_state(env, "wm-engine: a full duet tuning");
        }
        let duet = Duet {
            order_slack_ms: d[0] as i64,
            tap_window_ms: d[1] as i64,
            tap_sigma: d[2] as f32,
            tap_near_radius: d[3] as f32,
            tap_max_cost: d[4] as f32,
            tap_repeat_cost: d[5] as f32,
            samples_per_key: d[6] as f32,
            min_samples: d[7] as i32,
        };
        let token_kinds = ints(env, &token_kinds)?;
        let token_sizes = ints(env, &token_sizes)?;
        let points = path(env, &xs, &ys, &ts)?;
        let mut tokens = Vec::with_capacity(token_kinds.len());
        let mut at = 0usize;
        for (&kind, &size) in token_kinds.iter().zip(&token_sizes) {
            let size = size.max(0) as usize;
            if size == 0 || at + size > points.len() {
                return throw_state(env, "wm-engine: a token without its points");
            }
            tokens.push(if kind == 1 {
                Token::Swipe(points[at..at + size].to_vec())
            } else {
                let p = points[at];
                Token::Tap { x: p.x, y: p.y, t: p.t }
            });
            at += size;
        }
        let keys = object!(env, KEYMAPS, keymap);
        let spec = SourceSpec::read(env, &dicts, &kinds, &weights, &tiers, &min_frequencies)?;
        let limit = limit.max(0) as usize;
        let found = with_sources(env, &spec, |sources| {
            with_lane(|lane| {
                let decoder = &mut lane.duet;
                decoder.tuning = tuning;
                decoder.duet = duet;
                decoder.decode(&tokens, &keys, key_width, sources, limit, partial != 0)
            })
        })?;
        give_candidates(env, &found, &[], None)
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// `NativeGlide.tuningArray`'s first thirty slots: the shipped tuning.
    fn shipped_tuning() -> Vec<f64> {
        vec![
            0.42, 18.0, 1.33, 32.0, 0.5, // sigma, maxPointCost, gapWeight, gapWindow, shapeWeight
            0.8, 0.1, 2.0, 0.9, 0.8, // repeatCost, unloopedRepeat, dwellFull, unclaimedDwell, loopExtent
            1.0, 0.6, 0.0, 0.0, 1.5, // loopMinArc, loopRadius, wiggleExtent, wiggleWeight, loopExclusion
            1.1, 0.15, 0.0, 6.0, 1.6, // unclaimedLoop, learnedShapeGain, shapeSeeding, shapeChannel, startRadius
            2.0, 1.9, 0.0, 1.4, 48.0, // endRadius, nearRadius, vocabularyRank, lookAheadCost, lookAheadBudget
            0.2, 1.0, f64::INFINITY, 3.0, 0.0, // joinerCost, freqWeight, joinCost, joinMinPart, joinPartPenalty
        ]
    }

    #[test]
    fn thirty_value_tuning_defaults_the_backstops() {
        let t = tuning(&shipped_tuning()).expect("a valid tuning");
        assert_eq!(0.42, t.sigma);
        assert_eq!(MAX_POPS, t.max_pops);
        assert_eq!(MAX_STATES, t.max_states);
    }

    #[test]
    fn thirty_two_value_tuning_reads_the_backstops() {
        let mut v = shipped_tuning();
        v.extend_from_slice(&[2000.0, 8192.0]);
        let t = tuning(&v).expect("a valid tuning");
        assert_eq!(2000, t.max_pops);
        assert_eq!(8192, t.max_states);
    }

    #[test]
    fn the_link_cost_is_the_33rd_field_and_off_without_it() {
        let mut v = vec![GLIDE_TUNING_VERSION as f64, 33.0];
        v.extend_from_slice(&shipped_tuning());
        v.extend_from_slice(&[2000.0, 8192.0, 0.5]);
        assert_eq!(0.5, tuning(&v).expect("a valid tuning").join_link_cost);
        // An envelope of the version-2 shape, and the bare 32 values, read none.
        let mut old = vec![2.0, 32.0];
        old.extend_from_slice(&shipped_tuning());
        old.extend_from_slice(&[2000.0, 8192.0]);
        assert!(tuning(&old).expect("a valid tuning").join_link_cost.is_infinite());
        assert!(tuning(&old[2..]).expect("a valid tuning").join_link_cost.is_infinite());
    }

    #[test]
    fn versioned_tuning_reads_the_field_slice() {
        let mut v = vec![GLIDE_TUNING_VERSION as f64, 32.0];
        v.extend_from_slice(&shipped_tuning());
        v.extend_from_slice(&[2000.0, 8192.0]);
        let t = tuning(&v).expect("a valid tuning");
        assert_eq!(0.42, t.sigma);
        assert_eq!(2000, t.max_pops);
        assert_eq!(8192, t.max_states);
    }

    fn asked(slot: &LaneSlot) -> bool {
        slot.trim.load(Ordering::Acquire)
    }

    #[test]
    fn a_trim_reaches_other_threads_lanes_now_or_when_they_are_done() {
        use std::sync::mpsc::channel;
        // Idle on its own thread: trimmed from this one, at once.
        let (ready, wait) = channel();
        let (go, hold) = channel::<()>();
        let idle = std::thread::spawn(move || {
            with_lane(|_| ());
            ready.send(()).unwrap();
            hold.recv().unwrap();
            LANE.with(|slot| asked(slot))
        });
        wait.recv().unwrap();
        assert!(trim_lanes() >= 1);
        go.send(()).unwrap();
        assert!(!idle.join().unwrap(), "trimmed now, nothing left asked");

        // Busy: asked, and trimmed by its own thread when the call is done.
        let (inside, wait) = channel();
        let (go, hold) = channel::<()>();
        let busy = std::thread::spawn(move || {
            with_lane(|_| {
                inside.send(()).unwrap();
                hold.recv().unwrap();
                LANE.with(|slot| assert!(asked(slot), "the trim asked while busy"));
            });
            LANE.with(|slot| asked(slot))
        });
        wait.recv().unwrap();
        trim_lanes();
        go.send(()).unwrap();
        assert!(!busy.join().unwrap(), "trimmed by its own thread once done");

        // A phase-1 walk waiting: left alone until it is finished.
        let waiting = std::thread::spawn(|| {
            let ranked = Ranked { read: Vec::new(), guesses: Vec::new(), look_ahead: 0, limit: 1 };
            let keys = Arc::new(KeyMap::new(vec![0.0], vec![0.0], vec!['a' as i32], vec![0]));
            with_lane(|lane| lane.pending = Some((ranked, keys)));
            let mine = LANE.with(Arc::clone);
            let still = {
                let mut lane = mine.lane.lock().unwrap();
                !lane.trim()
            };
            mine.trim.store(true, Ordering::Release);
            with_lane(|lane| lane.pending.take());
            (still, asked(&mine))
        });
        let (still, left) = waiting.join().unwrap();
        assert!(still, "a waiting walk keeps its workspace");
        assert!(!left, "trimmed once the walk was finished");
    }
}
