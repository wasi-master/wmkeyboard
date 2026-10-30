//! The conversion decoder and its tables: `CjkNative` in `core/input`, which
//! `ConversionDictionary`, `CjkNgrams`, `HanLevels` and `Lattice` call.
//!
//! A pack opened from its TSV is parsed once and written beside it as
//! `<pack>.wmcd`, marked with the pack's length and modification time; every
//! later open maps that file, so a table costs no heap and about ten
//! milliseconds. A failed write only means the next open parses again. Lists
//! of strings cross as one string joined by [`SEP`], so a call is one crossing
//! however many there are.

use std::collections::{HashMap, HashSet};
use std::fs::{self, File};
use std::io::{self, BufWriter, Write};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, LazyLock};
use std::time::UNIX_EPOCH;

use jni::objects::{JByteArray, JClass, JDoubleArray, JIntArray, JString};
use jni::sys::{jdouble, jint, jlong, jstring};
use jni::JNIEnv;
use memmap2::Mmap;

use wm_engine::cjk::dict::{Bytes, ConversionDictionary, Row, Stamp};
use wm_engine::cjk::han::HanLevels;
use wm_engine::cjk::lattice::{self, Arc as Reading, Graph, Opts, UserWords, WordAdjust};
use wm_engine::cjk::ngrams::CjkNgrams;

use crate::guard::{give, guarded, object, read};
use crate::handles::Registry;

const SEP: char = '\u{1}';

static DICTS: LazyLock<Registry<ConversionDictionary>> = LazyLock::new(Registry::new);
static NGRAMS: LazyLock<Registry<CjkNgrams>> = LazyLock::new(Registry::new);
static LEVELS: LazyLock<Registry<HanLevels>> = LazyLock::new(Registry::new);
static USERS: LazyLock<Registry<UserData>> = LazyLock::new(Registry::new);
static NO_DICT: LazyLock<Arc<ConversionDictionary>> = LazyLock::new(Default::default);
static NO_NGRAMS: LazyLock<Arc<CjkNgrams>> = LazyLock::new(Default::default);

/// One reading space's history, as `CjkUserHistory` last had it.
struct UserData {
    picks: HashMap<String, HashMap<String, i32>>,
    /// The readings of everything learned with two characters or more, sorted.
    phrase_readings: Vec<String>,
    pairs: HashMap<String, HashMap<String, i32>>,
    adjust: WordAdjust,
}

impl UserData {
    fn has_phrase_prefix(&self, prefix: &str) -> bool {
        let at = self.phrase_readings.partition_point(|r| r.as_str() < prefix);
        self.phrase_readings.get(at).is_some_and(|r| r.starts_with(prefix))
    }
}

/// The tokens of a joined list; none for the empty string.
fn tokens(joined: &str) -> Vec<&str> {
    if joined.is_empty() {
        Vec::new()
    } else {
        joined.split(SEP).collect()
    }
}

fn ints(env: &mut JNIEnv, array: &JIntArray) -> Option<Vec<i32>> {
    let mut out = vec![0; env.get_array_length(array).ok()? as usize];
    env.get_int_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn doubles(env: &mut JNIEnv, array: &JDoubleArray) -> Option<Vec<f64>> {
    let mut out = vec![0.0; env.get_array_length(array).ok()? as usize];
    env.get_double_array_region(array, 0, &mut out).ok()?;
    Some(out)
}

fn stamp_of_file(path: &str) -> io::Result<Stamp> {
    let meta = fs::metadata(path)?;
    let modified_ms = meta
        .modified()?
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_millis() as i64);
    Ok(Stamp { length: meta.len(), modified_ms })
}

fn map(path: &str) -> io::Result<Mmap> {
    let file = File::open(path)?;
    // SAFETY: the file is only ever replaced by renaming a new one over it
    // (below), so this mapping keeps reading the inode it opened.
    unsafe { Mmap::map(&file) }
}

/// Writes through a temporary name, so a reader never sees half a table.
fn write_atomic(path: &str, write: impl FnOnce(&mut BufWriter<File>) -> io::Result<()>) -> io::Result<()> {
    static NEXT: AtomicU32 = AtomicU32::new(0);
    let temp = format!("{path}.{}.{}.tmp", std::process::id(), NEXT.fetch_add(1, Ordering::Relaxed));
    let done = (|| {
        let mut out = BufWriter::new(File::create(&temp)?);
        write(&mut out)?;
        out.flush()?;
        fs::rename(&temp, path)
    })();
    if done.is_err() {
        let _ = fs::remove_file(&temp);
    }
    done
}

/// `pack`'s table: the mapped `<pack>.wmcd` if it was built from this pack,
/// else parsed, written there and mapped.
fn open_cached<T>(
    pack: &str,
    stamp_of: fn(&[u8]) -> Stamp,
    from_bytes: fn(Bytes) -> Option<T>,
    parse: fn(&str) -> T,
    write_to: fn(&T, &mut BufWriter<File>, Stamp) -> io::Result<()>,
) -> io::Result<T> {
    let stamp = stamp_of_file(pack)?;
    let cache = format!("{pack}.wmcd");
    if let Ok(mapped) = map(&cache) {
        if stamp_of(&mapped) == stamp {
            if let Some(table) = from_bytes(Arc::new(mapped)) {
                return Ok(table);
            }
        }
    }
    let bytes = fs::read(pack)?;
    let table = parse(&String::from_utf8_lossy(&bytes));
    drop(bytes);
    if write_atomic(&cache, |out| write_to(&table, out, stamp)).is_ok() {
        if let Some(mapped) = map(&cache).ok().and_then(|m| from_bytes(Arc::new(m))) {
            return Ok(mapped);
        }
    }
    Ok(table)
}

/// `lo << 32 | hi`, for a row range in one `long`.
fn range(r: std::ops::Range<usize>) -> jlong {
    ((r.start as i64) << 32) | (r.end as i64 & 0xFFFF_FFFF)
}

fn pairs_of(text: &str, counts: &[i32]) -> HashMap<String, HashMap<String, i32>> {
    let mut out: HashMap<String, HashMap<String, i32>> = HashMap::new();
    for (pair, &count) in tokens(text).chunks_exact(2).zip(counts) {
        out.entry(pair[0].to_string()).or_default().insert(pair[1].to_string(), count);
    }
    out
}

/// Words and their counts into one string, the counts into `counts`.
fn give_counted(env: &mut JNIEnv, found: Vec<(String, i32)>, counts: &JIntArray) -> Option<jstring> {
    let values: Vec<i32> = found.iter().map(|f| f.1).collect();
    let room = env.get_array_length(counts).ok()? as usize;
    env.set_int_array_region(counts, 0, &values[..values.len().min(room)]).ok()?;
    let words: Vec<String> = found.into_iter().map(|f| f.0).collect();
    give(env, words.join("\u{1}"))
}

macro_rules! entry {
    ($name:ident ( $($arg:ident : $ty:ty),* ) -> $ret:ty, $fallback:expr, |$env:ident| $body:block) => {
        #[no_mangle]
        pub extern "system" fn $name<'l>(
            mut env: JNIEnv<'l>,
            _: JClass<'l>,
            $($arg: $ty),*
        ) -> $ret {
            guarded(&mut env, $fallback, |$env| $body)
        }
    };
}

// ---- ConversionDictionary -------------------------------------------------

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictOpen(path: JString<'l>) -> jlong, 0, |env| {
    let path = read(env, &path)?;
    let opened = open_cached(
        &path,
        ConversionDictionary::stamp_of,
        ConversionDictionary::from_bytes,
        ConversionDictionary::parse,
        |t, out, stamp| t.write_to(out, stamp),
    );
    match opened {
        Ok(table) => Some(DICTS.insert(table)),
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", format!("{path}: {e}"));
            None
        }
    }
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictParse(text: JString<'l>) -> jlong, 0, |env| {
    let text = read(env, &text)?;
    Some(DICTS.insert(ConversionDictionary::parse(&text)))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictClose(handle: jlong) -> (), (), |_env| {
    DICTS.remove(handle);
    Some(())
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictSize(handle: jlong) -> jint, 0, |env| {
    Some(object!(env, DICTS, handle).len() as jint)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictTotalFreq(handle: jlong) -> jlong, 0, |env| {
    Some(object!(env, DICTS, handle).total_freq)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictExact(handle: jlong, reading: JString<'l>) -> jstring, null_mut(), |env| {
    let dict = object!(env, DICTS, handle);
    let reading = read(env, &reading)?;
    give(env, dict.exact(&reading).join("\u{1}"))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictCandidates(handle: jlong, reading: JString<'l>, limit: jint) -> jstring, null_mut(), |env| {
    let dict = object!(env, DICTS, handle);
    let reading = read(env, &reading)?;
    give(env, dict.candidates(&reading, limit.max(0) as usize).join("\u{1}"))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictRowsFor(handle: jlong, reading: JString<'l>) -> jlong, 0, |env| {
    let dict = object!(env, DICTS, handle);
    let reading = read(env, &reading)?;
    Some(range(dict.rows_for(&reading)))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictPrefixRange(handle: jlong, prefix: JString<'l>, lo: jint, hi: jint) -> jlong, 0, |env| {
    let dict = object!(env, DICTS, handle);
    let prefix = read(env, &prefix)?;
    Some(range(dict.prefix_range(&prefix, lo.max(0) as usize..hi.max(0) as usize)))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictWord(handle: jlong, row: jint) -> jstring, null_mut(), |env| {
    let dict = object!(env, DICTS, handle);
    give(env, dict.word(row as usize).to_string())
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictReading(handle: jlong, row: jint) -> jstring, null_mut(), |env| {
    let dict = object!(env, DICTS, handle);
    give(env, dict.reading(row as usize).to_string())
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictWordLength(handle: jlong, row: jint) -> jint, 0, |env| {
    Some(object!(env, DICTS, handle).word_length(row as usize) as jint)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictFrequency(handle: jlong, row: jint) -> jint, 0, |env| {
    Some(object!(env, DICTS, handle).frequency(row as usize))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictBestFrequency(handle: jlong, reading: JString<'l>, length: jint) -> jint, 0, |env| {
    let dict = object!(env, DICTS, handle);
    let reading = read(env, &reading)?;
    Some(dict.best_frequency(&reading, length.max(0) as usize))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictQuantile(handle: jlong, quantile: jdouble) -> jint, 0, |env| {
    Some(object!(env, DICTS, handle).frequency_quantile(quantile))
});

// From `start`, the rows at `floor` or above in one crossing, as many as
// `frequencies` holds: the readings joined, the frequencies and word lengths
// filled in, `i32::MIN` after the last, and the row to go on from in `next[0]`.
// For the glide tables, which read every row of a million-row pack.
entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictRows(handle: jlong, start: jint, floor: jint, frequencies: JIntArray<'l>, lengths: JIntArray<'l>, next: JIntArray<'l>) -> jstring, null_mut(), |env| {
    let dict = object!(env, DICTS, handle);
    let room = env.get_array_length(&frequencies).ok()? as usize;
    let mut freq = Vec::with_capacity(room);
    let mut units = Vec::with_capacity(room);
    let mut text = String::new();
    let mut row = start.max(0) as usize;
    while row < dict.len() && freq.len() < room {
        let f = dict.frequency(row);
        if f >= floor {
            if !freq.is_empty() {
                text.push(SEP);
            }
            text.push_str(dict.reading(row));
            freq.push(f);
            units.push(dict.word_length(row) as i32);
        }
        row += 1;
    }
    if freq.len() < room {
        freq.push(i32::MIN);
        units.push(0);
    }
    env.set_int_array_region(&frequencies, 0, &freq).ok()?;
    env.set_int_array_region(&lengths, 0, &units).ok()?;
    env.set_int_array_region(&next, 0, &[row as i32]).ok()?;
    give(env, text)
});

// A handle on the merged table; 0 when nothing changed. `handle` may be 0.
entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_dictPlus(handle: jlong, readings: JString<'l>, words: JString<'l>, frequencies: JIntArray<'l>, raises: JIntArray<'l>) -> jlong, 0, |env| {
    let dict = if handle == 0 { NO_DICT.clone() } else { object!(env, DICTS, handle) };
    let readings = read(env, &readings)?;
    let words = read(env, &words)?;
    let frequencies = ints(env, &frequencies)?;
    let raises = ints(env, &raises)?;
    let rows: Vec<Row> = tokens(&readings)
        .into_iter()
        .zip(tokens(&words))
        .zip(frequencies.iter().zip(&raises))
        .map(|((reading, word), (&frequency, &raises))| Row {
            reading: reading.to_string(),
            word: word.to_string(),
            frequency,
            raises: raises != 0,
        })
        .collect();
    let merged = dict.plus(&rows);
    Some(if merged.same_table(&dict) { 0 } else { DICTS.insert(merged) })
});

// ---- CjkNgrams ------------------------------------------------------------

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramOpen(path: JString<'l>) -> jlong, 0, |env| {
    let path = read(env, &path)?;
    let opened = open_cached(
        &path,
        CjkNgrams::stamp_of,
        CjkNgrams::from_bytes,
        CjkNgrams::parse,
        |t, out, stamp| t.write_to(out, stamp),
    );
    match opened {
        Ok(table) => Some(NGRAMS.insert(table)),
        Err(e) => {
            let _ = env.throw_new("java/io/IOException", format!("{path}: {e}"));
            None
        }
    }
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramParse(text: JString<'l>) -> jlong, 0, |env| {
    let text = read(env, &text)?;
    Some(NGRAMS.insert(CjkNgrams::parse(&text)))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramClose(handle: jlong) -> (), (), |_env| {
    NGRAMS.remove(handle);
    Some(())
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramIsEmpty(handle: jlong) -> jint, 1, |env| {
    Some(object!(env, NGRAMS, handle).is_empty() as jint)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramUnigram(handle: jlong, word: JString<'l>) -> jint, 0, |env| {
    let ngrams = object!(env, NGRAMS, handle);
    let word = read(env, &word)?;
    Some(ngrams.unigram(&word))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramBigram(handle: jlong, previous: JString<'l>, word: JString<'l>) -> jint, 0, |env| {
    let ngrams = object!(env, NGRAMS, handle);
    let previous = read(env, &previous)?;
    let word = read(env, &word)?;
    Some(ngrams.bigram(&previous, &word))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramSuccessors(handle: jlong, word: JString<'l>, limit: jint, counts: JIntArray<'l>) -> jstring, null_mut(), |env| {
    let ngrams = object!(env, NGRAMS, handle);
    let word = read(env, &word)?;
    let mut found = ngrams.successors(&word);
    found.truncate(limit.max(0) as usize);
    give_counted(env, found, &counts)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramCompletions(handle: jlong, prefix: JString<'l>, limit: jint, counts: JIntArray<'l>) -> jstring, null_mut(), |env| {
    let ngrams = object!(env, NGRAMS, handle);
    let prefix = read(env, &prefix)?;
    let found = ngrams.completions(&prefix, limit.max(0) as usize);
    give_counted(env, found, &counts)
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_ngramLogProbability(handle: jlong, previous: JString<'l>, word: JString<'l>, pack_freq: jint, pack_total: jlong, weight: jdouble) -> jdouble, 0.0, |env| {
    let ngrams = if handle == 0 { NO_NGRAMS.clone() } else { object!(env, NGRAMS, handle) };
    let previous = if previous.is_null() { None } else { Some(read(env, &previous)?) };
    let word = read(env, &word)?;
    Some(ngrams.log_probability_mixed(previous.as_deref(), &word, pack_freq, pack_total, weight))
});

// ---- HanLevels and the user's history -------------------------------------

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_levelsOpen(points: JIntArray<'l>, levels: JByteArray<'l>) -> jlong, 0, |env| {
    let points = ints(env, &points)?;
    let mut values = vec![0i8; env.get_array_length(&levels).ok()? as usize];
    env.get_byte_array_region(&levels, 0, &mut values).ok()?;
    Some(LEVELS.insert(HanLevels::from_points(&points, &values)))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_levelsClose(handle: jlong) -> (), (), |_env| {
    LEVELS.remove(handle);
    Some(())
});

// `picks` is reading, word, reading, word...; `pairs` is previous, word...
entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_userOpen(picks: JString<'l>, pick_counts: JIntArray<'l>, pairs: JString<'l>, pair_counts: JIntArray<'l>, blocked: JString<'l>, bias: JString<'l>, bias_steps: JIntArray<'l>) -> jlong, 0, |env| {
    let picks = read(env, &picks)?;
    let pick_counts = ints(env, &pick_counts)?;
    let pairs = read(env, &pairs)?;
    let pair_counts = ints(env, &pair_counts)?;
    let blocked = read(env, &blocked)?;
    let bias = read(env, &bias)?;
    let bias_steps = ints(env, &bias_steps)?;
    let picks = pairs_of(&picks, &pick_counts);
    let mut phrase_readings: Vec<String> = picks
        .iter()
        .filter(|(_, words)| words.keys().any(|w| w.chars().count() >= 2))
        .map(|(reading, _)| reading.clone())
        .collect();
    phrase_readings.sort();
    let adjust = WordAdjust {
        blocked: tokens(&blocked).into_iter().map(str::to_string).collect::<HashSet<_>>(),
        bias: tokens(&bias).into_iter().map(str::to_string).zip(bias_steps).collect(),
    };
    Some(USERS.insert(UserData { picks, phrase_readings, pairs: pairs_of(&pairs, &pair_counts), adjust }))
});

entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_userClose(handle: jlong) -> (), (), |_env| {
    USERS.remove(handle);
    Some(())
});

// ---- Lattice --------------------------------------------------------------

const ARC_GUESS: i32 = 1;
const ARC_CORRECTION: i32 = 2;
const SCOPE_PICKS: i32 = 1;
const SCOPE_WORDS: i32 = 2;

// `arc_start` has a slot per node and one past the last. `ints` and `reals` are
// Lattice.Opts in the order of `CjkNative.decode`'s comment. Each candidate's
// text and reading go in the joined result; its consumed length, doubled and
// with the low bit set if repaired, goes in `consumed_out` and its score in
// `score_out`.
entry!(Java_com_wasimaster_wmkeyboard_core_input_composer_CjkNative_decode(dict: jlong, ngrams: jlong, levels: jlong, user: jlong, scope: jint, consumed: JIntArray<'l>, arc_start: JIntArray<'l>, arc_to: JIntArray<'l>, arc_flags: JIntArray<'l>, arc_penalty: JDoubleArray<'l>, arc_reading: JString<'l>, ints_in: JIntArray<'l>, reals_in: JDoubleArray<'l>, context: JString<'l>, consumed_out: JIntArray<'l>, score_out: JDoubleArray<'l>) -> jstring, null_mut(), |env| {
    let dict = if dict == 0 { NO_DICT.clone() } else { object!(env, DICTS, dict) };
    let ngrams = if ngrams == 0 { NO_NGRAMS.clone() } else { object!(env, NGRAMS, ngrams) };
    let levels = if levels == 0 { None } else { Some(object!(env, LEVELS, levels)) };
    let user = if user == 0 { None } else { Some(object!(env, USERS, user)) };
    let consumed = ints(env, &consumed)?;
    let arc_start = ints(env, &arc_start)?;
    let arc_to = ints(env, &arc_to)?;
    let arc_flags = ints(env, &arc_flags)?;
    let arc_penalty = doubles(env, &arc_penalty)?;
    let arc_reading = read(env, &arc_reading)?;
    let opts_ints = ints(env, &ints_in)?;
    let reals = doubles(env, &reals_in)?;
    let context = if context.is_null() { None } else { Some(read(env, &context)?) };

    let readings = tokens(&arc_reading);
    let mut arcs = Vec::with_capacity(arc_start.len().saturating_sub(1));
    for node in arc_start.windows(2) {
        let mut out = Vec::with_capacity((node[1] - node[0]) as usize);
        for a in node[0] as usize..node[1] as usize {
            out.push(Reading {
                to: arc_to[a] as usize,
                reading: readings.get(a).copied().unwrap_or("").to_string(),
                penalty: arc_penalty[a],
                guess: arc_flags[a] & ARC_GUESS != 0,
                correction: arc_flags[a] & ARC_CORRECTION != 0,
            });
        }
        arcs.push(out);
    }
    let graph = Graph { consumed: consumed.iter().map(|&c| c as usize).collect(), arcs };

    let penalty = levels.as_ref().map(|l| move |word: &str| l.penalty(word));
    let opts = Opts {
        max_word_units: opts_ints[0].max(0) as usize,
        max_ambiguous_span: opts_ints[1].max(0) as usize,
        span_cand_cap: opts_ints[2].max(0) as usize,
        beam: opts_ints[3].max(0) as usize,
        limit: opts_ints[4].max(0) as usize,
        char_per_unit: opts_ints[5] != 0,
        stitch: opts_ints[6].max(0) as usize,
        coverage_order: opts_ints[7] != 0,
        paths_per_stretch: opts_ints[8].max(0) as usize,
        stretch_cap: opts_ints[9].max(0) as usize,
        over_length_penalty: reals[0],
        stitch_bonus: reals[1],
        correction_margin: reals[2],
        path_margin: reals[3],
        bigram_weight: reals[4],
        word_penalty: penalty.as_ref().map(|p| p as &dyn Fn(&str) -> f64),
    };
    let data = user.as_deref();
    let picks = data.filter(|_| scope & SCOPE_PICKS != 0);
    let words = data.filter(|_| scope & SCOPE_WORDS != 0);
    let learned = |reading: &str| picks.and_then(|u| u.picks.get(reading));
    let has_prefix = |prefix: &str| words.is_some_and(|u| u.has_phrase_prefix(prefix));
    let user_words = UserWords { has_prefix: &has_prefix, pairs: words.map(|u| &u.pairs) };
    let found = lattice::decode(
        &graph,
        &dict,
        &ngrams,
        &opts,
        &learned,
        data.map(|u| &u.adjust),
        context.as_deref(),
        &user_words,
    );

    let room = env.get_array_length(&consumed_out).ok()? as usize;
    let kept = &found[..found.len().min(room)];
    let lengths: Vec<i32> = kept.iter().map(|c| (c.consumed as i32) << 1 | c.corrected as i32).collect();
    let scores: Vec<f64> = kept.iter().map(|c| c.score).collect();
    env.set_int_array_region(&consumed_out, 0, &lengths).ok()?;
    env.set_double_array_region(&score_out, 0, &scores).ok()?;
    let mut text = String::new();
    for (i, c) in kept.iter().enumerate() {
        if i > 0 {
            text.push(SEP);
        }
        text.push_str(&c.text);
        text.push(SEP);
        text.push_str(&c.reading);
    }
    give(env, text)
});
