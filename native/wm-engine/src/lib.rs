//! WM Keyboard's text engines, written once.
//!
//! The Android app has run these engines in Kotlin under `core/language` and
//! `core/prediction`. WMK Desktop needs the same engines on macOS, Windows and
//! Linux, and a second implementation would drift from the first, so they
//! move here one at a time: the Rust port is checked against the same
//! conformance fixtures as the Kotlin original, the Android app switches to
//! it through JNI once that corpus is green, and the Kotlin copy is deleted.
//!
//! Ported so far:
//!
//! - [`khipro`]: the Khipro m17n interpreter (Bengali, compositional).
//! - [`avro`]: the keyboard's own Avro-style phonetic transliterator.
//! - [`avro_desktop`]: desktop Avro's grammars and lookups out of ibus-avro's
//!   data (readings, autocorrect, suffixes, the search expression).
//! - [`hindi`]: Hindi phonetic, into Devanagari.
//! - [`arabic_script`] with [`urdu`], [`arabic`] and [`persian`]: the shared
//!   roman-to-Arabic-script walk and its three tables.
//! - [`indic`] with [`indic_script`], [`indic_profiles`] and one module per
//!   language (Marathi, Gujarati, Punjabi, Odia, Tamil, Telugu, Kannada,
//!   Malayalam): the shared Indic engine and its profiles.
//!
//! - [`assamese`] on the Avro engine, [`bengali_graphemes`] for cluster-aware
//!   editing, and the romanizers [`bengali_romanizer`],
//!   [`devanagari_romanizer`] and [`urdu_romanizer`].
//!
//! - [`bijoy`]: Unicode Bengali to the Bijoy ANSI font encodings, with the
//!   generated [`bijoy_tables`]; [`khipro_romanizer`]: Khipro run backwards;
//!   [`phonetic_key_map`]: the key maps the settings pages draw.
//!
//! - [`zawgyi`]: Burmese in the Zawgyi font encoding, detected and converted
//!   to and from Unicode, a port of google/myanmar-tools with its rules in
//!   the generated [`zawgyi_rules`].
//!
//! - [`phonetic_index`] and the reverse-phonetic indexes over word lists:
//!   [`bengali_index`], [`hindi_index`], [`indic_index`], [`urdu_index`]
//!   (with the Arabic-script wrapper), and the Assamese one in [`assamese`].
//!
//! That is the whole of the Kotlin `core/transliteration` package.
//!
//! - [`wmdict`] and [`wmng`]: the binary dictionary and n-gram pack, read in
//!   place, with the builders and writers that make them; [`wordlist`] reads
//!   the text lists they are compiled from; [`freq_codec`] is the minifloat
//!   both store counts in.
//!
//! - [`fuzzy`]: the trie-guided correction and completion walk, with the
//!   key [`proximity`] model and the [`accents`] fold it prices edits by.
//!
//! - [`predict`]: the typed path of the suggestion engine on top of it:
//!   strip ranking with context, next words, the autocorrect decision with
//!   its offer, certainty and complexity, missing-space splits, and an
//!   in-memory user lexicon.
//!
//! - [`word_key`]: the key every store files a word under and the gate on
//!   which words may be learned, as Android's runtime folds them; with
//!   [`segment`], the words inside a run of Thai, Lao, Khmer or Burmese.
//!   Both on ICU4X.
//!
//! - [`cjk`]: Chinese Pinyin (full, Double, Jianpin, fuzzy), Cantonese
//!   Jyutping and Japanese romaji/kana conversion over one lattice decoder,
//!   with the Simplified → Traditional tables and the user's pick history.
//!
//! - [`kernels`]: the data-parallel loops, each a scalar reference plus an
//!   aarch64 NEON version picked at run time, with the parity tests between
//!   them.
//!
//! Still Kotlin: Avro's dictionary walk, the phonetic strips, touch scoring
//! and glide.
//!
//! The crate does no I/O. Its tables are compiled in from the Android app's
//! own resource and asset files, so the two never disagree about which
//! upstream revision they run.

pub mod accents;
pub mod apostrophes;
pub mod arabic;
pub mod arabic_script;
pub mod assamese;
pub mod avro;
pub mod avro_desktop;
pub mod bengali_graphemes;
pub mod bengali_index;
pub mod bengali_romanizer;
pub mod bijoy;
pub mod bijoy_tables;
pub mod cjk;
pub mod completer;
pub mod confidence;
pub mod correction_memory;
pub mod correction_stats;
pub mod devanagari_romanizer;
pub mod freq_codec;
pub mod fuzzy;
pub mod gif;
pub mod glide;
pub mod gujarati;
pub mod hindi;
pub mod hindi_index;
pub mod indic;
pub mod indic_index;
pub mod indic_profiles;
pub mod indic_script;
pub mod java_heap;
pub mod kannada;
pub mod kernels;
pub mod khipro;
pub mod khipro_romanizer;
pub mod language_mix;
pub mod lm;
pub mod malayalam;
pub mod marathi;
pub mod odia;
pub mod minor_phonetic;
pub mod persian;
pub mod phonetic_index;
pub mod phonetic_key_map;
pub mod predict;
pub mod proximity;
pub mod punjabi;
pub mod rerank;
pub mod segment;
pub mod sexp;
pub mod sort;
pub mod strict_math;
pub mod tamil;
pub mod telugu;
pub mod unicode;
pub mod urdu;
pub mod urdu_index;
pub mod urdu_romanizer;
pub mod user_lexicon;
pub mod wmdict;
pub mod word_key;
pub mod wmng;
pub mod wordlist;
pub mod zawgyi;
pub mod zawgyi_rules;
