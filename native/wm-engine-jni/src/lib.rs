//! JNI bridge exposing wm-engine to the Android app.
//!
//! One module per Kotlin class it serves:
//!
//! - [`transliteration`]: `EngineNative` (core/language), the phonetic
//!   engines, romanizers, Bijoy and graphemes.
//! - [`kernels`]: `NativeEngine` (core/language), CPU features, the
//!   scalar switch and the data-parallel kernels.
//! - [`dict`]: `PredictionNative` (core/prediction), the mapped `.wmdict`
//!   dictionaries and `.wmng` n-gram packs.
//! - [`fuzzy`]: `PredictionNative` again, the fuzzy correction walk over
//!   those dictionaries and the user lexicon.
//! - [`glide`]: `PredictionNative` again, the glide decoder behind
//!   `GlideBeam`.
//! - [`lexicon`]: `PredictionNative` again, the user lexicon behind
//!   `UserLexicon`.
//! - [`rerank`]: `PredictionNative` again, the context reranker behind
//!   `NgramReranker`.
//! - [`lm`]: `PredictionNative` again, the neural context model behind
//!   `ContextModel`.
//! - [`corrections`]: `PredictionNative` again, the stores autocorrect
//!   learns from, behind `CorrectionMemory` and `CorrectionStats`.
//! - [`words`]: `PredictionNative` again, the word key, the learnability
//!   gate and the word segmenter for Thai, Lao, Khmer and Burmese.
//! - [`gif`]: `GifNative` (core/content), the GIF encoder behind
//!   `GifEncoder`.
//! - [`cjk`]: `CjkNative` (core/input), the conversion decoder and its
//!   tables behind `Lattice`, `ConversionDictionary` and `CjkNgrams`.
//!
//! The rules every entry point follows (native/RUST_NEON_PLAN.md):
//!
//! - Its body runs inside [`guard::guarded`], so a panic becomes a Java
//!   exception, never an abort of the keyboard.
//! - Work is batched: one crossing per query or per array, never one per
//!   element. Primitive arrays are pinned (`GetPrimitiveArrayCritical`) or
//!   copied once; strings cross as UTF-16 ([`guard::read_units`]).
//! - Data that outlives a call (a mapped dictionary, a decoder) stays here
//!   behind a [`handles::Registry`] handle.

mod cjk;
mod corrections;
mod dict;
mod fuzzy;
mod gif;
mod glide;
mod guard;
mod handles;
mod kernels;
mod lexicon;
mod lm;
mod rerank;
mod sources;
mod transliteration;
mod words;
