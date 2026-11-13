//! The fuzzy correction walk: `PredictionNative.fuzzySearch`, which the
//! Kotlin `FuzzyBeamSearch.search` calls when every trie it was given has a
//! Rust twin (a dictionary handle from [`crate::dict`]).
//!
//! One crossing per search. The key-proximity model and the learned slip
//! habits are immutable on the Kotlin side, so each gets a handle once and
//! is passed by it; the touch frame and the key sets change per keystroke
//! and cross as arrays.

use std::cell::RefCell;
use std::ptr::null_mut;
use std::sync::LazyLock;

use jni::objects::{JCharArray, JClass, JDoubleArray, JFloatArray, JIntArray, JLongArray, JObjectArray, JString};
use jni::sys::{jdouble, jint, jlong, jobjectArray};
use jni::JNIEnv;

use wm_engine::fuzzy::{self, HabitTables, KeySets, RankScale, Tier, TouchModel, TouchScoring, WalkSource, Workspace};
use wm_engine::proximity::KeyProximity;

use crate::guard::{give_all_units, guarded, object, read_units, throw_state};
use crate::sources::with_walkers;
use crate::handles::Registry;

static PROXIMITIES: LazyLock<Registry<KeyProximity>> = LazyLock::new(Registry::new);
static HABITS: LazyLock<Registry<HabitTables>> = LazyLock::new(Registry::new);

thread_local! {
    static WORKSPACE: RefCell<Workspace> = RefCell::new(Workspace::new());
}

fn strings(env: &mut JNIEnv, array: &JObjectArray) -> Option<Vec<Vec<u16>>> {
    let len = env.get_array_length(array).ok()?;
    let mut out = Vec::with_capacity(len as usize);
    for i in 0..len {
        let item = JString::from(env.get_object_array_element(array, i).ok()?);
        let mut units = Vec::new();
        read_units(env, &item, &mut units)?;
        env.delete_local_ref(item).ok()?;
        out.push(units);
    }
    Some(out)
}

fn chars(env: &mut JNIEnv, array: &JCharArray) -> Option<Vec<u16>> {
    let mut out = vec![0u16; env.get_array_length(array).ok()? as usize];
    env.get_char_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn doubles(env: &mut JNIEnv, array: &JDoubleArray) -> Option<Vec<f64>> {
    let mut out = vec![0f64; env.get_array_length(array).ok()? as usize];
    env.get_double_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn floats(env: &mut JNIEnv, array: &JFloatArray) -> Option<Vec<f32>> {
    let mut out = vec![0f32; env.get_array_length(array).ok()? as usize];
    env.get_float_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

/// `proximityOpen(rows, carried)`: the key-adjacency model of a layout's
/// rows, and the letters its keys carry besides the one they type (#970) as
/// typed, carried pairs of units.
#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_proximityOpen(
    mut env: JNIEnv,
    _class: JClass,
    rows: JObjectArray,
    carried: JCharArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let rows = strings(env, &rows)?;
        let carried = chars(env, &carried)?;
        let pairs = carried.chunks_exact(2).map(|p| (p[0], p[1])).collect();
        Some(PROXIMITIES.insert(KeyProximity::of_rows_units(&rows).with_carried(pairs)))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_proximityClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        PROXIMITIES.remove(handle);
        Some(())
    })
}

/// `habitsOpen(subKeys, subValues, insertKeys, insertValues, deleteKeys,
/// deleteValues)`: `EditHabits`' sorted tables.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_habitsOpen(
    mut env: JNIEnv,
    _class: JClass,
    sub_keys: JIntArray,
    sub_values: JDoubleArray,
    insert_keys: JCharArray,
    insert_values: JDoubleArray,
    delete_keys: JCharArray,
    delete_values: JDoubleArray,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let mut subs = vec![0i32; env.get_array_length(&sub_keys).ok()? as usize];
        env.get_int_array_region(&sub_keys, 0, &mut subs).ok()?;
        Some(HABITS.insert(HabitTables {
            sub_keys: subs,
            sub_values: doubles(env, &sub_values)?,
            insert_keys: chars(env, &insert_keys)?,
            insert_values: doubles(env, &insert_values)?,
            delete_keys: chars(env, &delete_keys)?,
            delete_values: doubles(env, &delete_values)?,
        }))
    })
}

#[no_mangle]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_habitsClose(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    guarded(&mut env, (), |_| {
        HABITS.remove(handle);
        Some(())
    })
}

/// `fuzzySearch(dicts, kinds, weights, tiers, typed, proximity, limit, maxEdits,
/// habits, touchKeys, touchCenters, sigma, slipRadius, touchPoints, keySets,
/// keySpellings, doublesOut, intsOut)`.
///
/// `kinds` say what each handle is (see `sources`): 0 a dictionary, 1 a user lexicon (its
/// trie). `tiers` are 0 for the dictionary tier, 1 for the user tier. `habits` is
/// 0 for none. The touch frame is absent when `touchKeys` is null: keys
/// with their centres as x, y pairs in the model's order, and a tap per
/// typed character as x, y with NaN for an unknown one; `slipRadius` is the
/// slip reach in key widths, 0 for none. `keySets` is null
/// on an ordinary board; `keySpellings[pos]` may be null.
///
/// Returns the words, best first; for word `i`, `doublesOut[4i..4i+4]` are
/// score, editCost, dictScore, userScore and `intsOut[5i..5i+5]` are edits,
/// completedChars, tier, accents, slips. `doublesOut` must hold
/// `4 * max(limit * 2, 8)` and `intsOut` five per word.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_wasimaster_wmkeyboard_core_prediction_PredictionNative_fuzzySearch(
    mut env: JNIEnv,
    _class: JClass,
    dicts: JLongArray,
    kinds: JIntArray,
    weights: JDoubleArray,
    scale_floors: JDoubleArray,
    scale_per_nats: JDoubleArray,
    tiers: JIntArray,
    typed: JString,
    proximity: jlong,
    limit: jint,
    max_edits: jint,
    habits: jlong,
    touch_keys: JCharArray,
    touch_centers: JFloatArray,
    sigma: jdouble,
    slip_radius: jdouble,
    touch_points: JFloatArray,
    key_sets: JObjectArray,
    key_spellings: JObjectArray,
    doubles_out: JDoubleArray,
    ints_out: JIntArray,
) -> jobjectArray {
    guarded(&mut env, null_mut(), |env| {
        let count = env.get_array_length(&dicts).ok()? as usize;
        let mut handles = vec![0i64; count];
        env.get_long_array_region(&dicts, 0, &mut handles).ok()?;
        let weights = doubles(env, &weights)?;
        let floors = doubles(env, &scale_floors)?;
        let per_nats = doubles(env, &scale_per_nats)?;
        if floors.len() < count || per_nats.len() < count || weights.len() < count {
            return throw_state(env, "wm-engine: a weight and a rank scale per source");
        }
        let mut tier_codes = vec![0i32; count];
        env.get_int_array_region(&tiers, 0, &mut tier_codes).ok()?;
        let mut kind_codes = vec![0i32; count];
        env.get_int_array_region(&kinds, 0, &mut kind_codes).ok()?;
        let proximity = object!(env, PROXIMITIES, proximity);
        let habits = if habits == 0 { None } else { Some(object!(env, HABITS, habits)) };
        let mut typed_units = Vec::new();
        read_units(env, &typed, &mut typed_units)?;

        let model = if touch_keys.is_null() {
            None
        } else {
            let keys = chars(env, &touch_keys)?;
            let centers = floats(env, &touch_centers)?;
            if centers.len() < keys.len() * 2 {
                return throw_state(env, "wm-engine: a touch centre per key");
            }
            Some(TouchModel::new(
                keys.iter().enumerate().map(|(i, &k)| (k, centers[2 * i], centers[2 * i + 1])).collect(),
                sigma,
            )
            .with_slip_radius(slip_radius))
        };
        let points = if touch_points.is_null() { Vec::new() } else { floats(env, &touch_points)? };
        let touch = model.as_ref().map(|model| {
            let taps = points
                .chunks_exact(2)
                .map(|p| if p[0].is_nan() || p[1].is_nan() { None } else { Some((p[0], p[1])) })
                .collect();
            TouchScoring::new(model, taps)
        });

        let keys = if key_sets.is_null() {
            None
        } else {
            let sets = strings(env, &key_sets)?;
            let mut spellings = Vec::with_capacity(sets.len());
            if !key_spellings.is_null() {
                let len = env.get_array_length(&key_spellings).ok()?;
                for i in 0..len {
                    let item = JObjectArray::from(env.get_object_array_element(&key_spellings, i).ok()?);
                    spellings.push(if item.is_null() { None } else { Some(strings(env, &item)?) });
                    env.delete_local_ref(item).ok()?;
                }
            }
            Some(KeySets::new(sets, spellings))
        };

        let found = with_walkers(env, &handles, &kind_codes, |walkers| {
            let sources: Vec<WalkSource> = walkers
                .iter()
                .enumerate()
                .map(|(i, &walker)| WalkSource {
                    walker,
                    log_weight: weights[i],
                    tier: if tier_codes[i] == 1 { Tier::User } else { Tier::Dictionary },
                    scale: RankScale { floor: floors[i], per_nat: per_nats[i] },
                })
                .collect();
            WORKSPACE.with_borrow_mut(|ws| {
                fuzzy::search_units(
                    &sources,
                    &typed_units,
                    &proximity,
                    limit.max(0) as usize,
                    ws,
                    max_edits.max(0) as u32,
                    touch.as_ref(),
                    habits.as_deref().map_or(&fuzzy::NO_HABITS as &dyn fuzzy::EditHabits, |h| h),
                    keys.as_ref(),
                )
            })
        })?;

        let mut reals = Vec::with_capacity(found.len() * 4);
        let mut ints = Vec::with_capacity(found.len() * 5);
        for c in &found {
            reals.extend_from_slice(&[c.score, c.edit_cost, c.dict_score, c.user_score]);
            ints.extend_from_slice(&[
                c.edits as i32,
                c.completed_chars as i32,
                (c.tier == Tier::User) as i32,
                c.accents as i32,
                c.slips as i32,
            ]);
        }
        if env.get_array_length(&doubles_out).ok()? < reals.len() as i32 || env.get_array_length(&ints_out).ok()? < ints.len() as i32 {
            return throw_state(env, "wm-engine: the out arrays are too short");
        }
        env.set_double_array_region(&doubles_out, 0, &reals).ok()?;
        env.set_int_array_region(&ints_out, 0, &ints).ok()?;
        give_all_units(env, found.iter().map(|c| c.word.as_slice()))
    })
}
