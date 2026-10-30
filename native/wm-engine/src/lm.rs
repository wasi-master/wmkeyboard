//! The neural context model: a small word-level transformer, read from a
//! `.wmlm` file (`tools/wmlm/export.py` writes one) and run here in int8.
//!
//! The model answers one question per committed word: given the words
//! before the caret, how likely is each word of its vocabulary to come next.
//! That one distribution serves the whole strip. A candidate the correction
//! walk found is scored by a lookup ([`Session::logprob`]); the next-word
//! strip is the head of the distribution ([`Session::top`]); a phrase to
//! offer is a few greedy steps of it ([`Session::generate`]). So the model
//! runs once per word, never per keystroke, and the per-keystroke cost is a
//! hash lookup per candidate.
//!
//! A word the vocabulary does not hold — a name, a word the user taught the
//! keyboard — gets no score at all (`None`), never a penalty: the lists and
//! the user's own lexicon decide those, as they always did.
//!
//! The arithmetic mirrors `tools/wmlm/model.py`: learned positions over
//! `ctx` tokens, pre-norm blocks of causal self-attention and a GELU (tanh
//! form) feed-forward, a final LayerNorm, logits against the tied embedding
//! table plus a bias. Weights are int8 with one scale per output row;
//! activations are quantised to int8 per vector before each matrix product,
//! so every product is the `dot_i8` kernel, `sdot` on the cores that have
//! it. Everything else is f32.
//!
//! The [`Session`] keeps the attention keys and values of the context it
//! last saw, so a context that grows by one word costs one token's forward
//! pass; a context that changed further back is recomputed from the point
//! it diverged.

use crate::kernels::dot::dot_i8;
use crate::word_key;
use std::collections::HashMap;
use std::sync::Arc;

pub const MAGIC: &[u8; 4] = b"WMLM";
pub const VERSION: u32 = 1;

/// The sixteen tokens every model has first, in this order.
pub const PAD: u32 = 0;
pub const BOS: u32 = 1;
pub const EOS: u32 = 2;
pub const UNK: u32 = 3;
pub const NUM: u32 = 4;
pub const REG_NEUTRAL: u32 = 5;
pub const REG_CASUAL: u32 = 6;
pub const REG_FORMAL: u32 = 7;
pub const FIELD_OTHER: u32 = 8;
pub const FIELD_CHAT: u32 = 9;
pub const FIELD_EMAIL: u32 = 10;
pub const FIELD_SEARCH: u32 = 11;
pub const LANG_0: u32 = 12;
pub const SPECIALS: u32 = 16;
/// The markers subword pieces carry in the vocabulary: a word's first piece
/// and its continuations (`tools/wmlm/common.py`).
pub const PIECE_START: &str = "\u{2581}";
pub const PIECE_CONT: &str = "##";
/// The longest piece, in characters.
pub const MAX_PIECE: usize = 5;
/// The most pieces a word may be scored in; longer ones have no opinion.
pub const MAX_WORD_PIECES: usize = 6;

/// The conditioning tokens in front of a sentence: who is being written to,
/// where, and in which of the model's languages.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub struct Prefix {
    /// 0 neutral, 1 casual, 2 formal.
    pub register: u8,
    /// 0 other, 1 chat, 2 email, 3 search.
    pub field: u8,
    /// Which of the model's `langs` the keyboard is on, 0..3.
    pub lang: u8,
}

struct I8Matrix {
    /// Byte offset of the first weight in the file.
    data: usize,
    rows: usize,
    cols: usize,
    scales: Vec<f32>,
}

struct Layer {
    ln1_w: Vec<f32>,
    ln1_b: Vec<f32>,
    qkv: I8Matrix,
    qkv_b: Vec<f32>,
    o: I8Matrix,
    o_b: Vec<f32>,
    ln2_w: Vec<f32>,
    ln2_b: Vec<f32>,
    w1: I8Matrix,
    w1_b: Vec<f32>,
    w2: I8Matrix,
    w2_b: Vec<f32>,
}

/// A model, read in place from its file's bytes.
pub struct Lm<B: AsRef<[u8]> = Vec<u8>> {
    bytes: B,
    pub d: usize,
    pub layers: usize,
    pub heads: usize,
    pub ff: usize,
    pub ctx: usize,
    pub name: String,
    /// The language ids the model's `<lang:n>` tokens stand for.
    pub langs: Vec<String>,
    vocab: Vec<String>,
    ids: HashMap<String, u32>,
    /// Subword pieces for words the vocabulary lacks (`tools/wmlm/common.py`'s
    /// `Pieces`): a word is one start piece then continuation pieces, greedy
    /// longest match. Empty in a model without pieces, where such a word is
    /// `<unk>`.
    start_pieces: HashMap<String, u32>,
    cont_pieces: HashMap<String, u32>,
    is_piece: Vec<bool>,
    emb: I8Matrix,
    pos: Vec<f32>,
    out_bias: Vec<f32>,
    blocks: Vec<Layer>,
    ln_w: Vec<f32>,
    ln_b: Vec<f32>,
}

fn u32_at(b: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(b.get(at..at + 4)?.try_into().ok()?))
}

fn f32s(b: &[u8], at: usize, n: usize) -> Option<Vec<f32>> {
    let s = b.get(at..at + n * 4)?;
    Some(s.chunks_exact(4).map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]])).collect())
}

fn pad4(n: usize) -> usize {
    (n + 3) & !3
}

impl<B: AsRef<[u8]>> Lm<B> {
    /// Reads a `.wmlm`; `None` when the bytes are not one this reader trusts.
    pub fn open(bytes: B) -> Option<Lm<B>> {
        let b = bytes.as_ref();
        if b.get(0..4)? != MAGIC || u32_at(b, 4)? != VERSION {
            return None;
        }
        let header_len = u32_at(b, 8)? as usize;
        let header_bytes = b.get(12..12 + header_len)?;
        let end = header_bytes.iter().rposition(|&c| c != 0).map_or(0, |i| i + 1);
        let header: serde_json::Value = serde_json::from_slice(&header_bytes[..end]).ok()?;
        let num = |k: &str| header.get(k)?.as_u64().map(|v| v as usize);
        let (d, layers, heads, ff, ctx, vocab_len) =
            (num("d")?, num("layers")?, num("heads")?, num("ff")?, num("ctx")?, num("vocab")?);
        if d == 0 || heads == 0 || d % heads != 0 || layers == 0 || ctx == 0 || vocab_len < SPECIALS as usize {
            return None;
        }
        let name = header.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let langs: Vec<String> = header
            .get("langs")
            .and_then(|v| v.as_array())
            .map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect())
            .unwrap_or_default();

        // The vocabulary.
        let mut at = 12 + header_len;
        let count = u32_at(b, at)? as usize;
        if count != vocab_len {
            return None;
        }
        at += 4;
        let mut vocab = Vec::with_capacity(count);
        let mut ids = HashMap::with_capacity(count * 2);
        let mut start_pieces = HashMap::new();
        let mut cont_pieces = HashMap::new();
        let mut is_piece = vec![false; count];
        for i in 0..count {
            let len = u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?) as usize;
            at += 2;
            let word = std::str::from_utf8(b.get(at..at + len)?).ok()?.to_string();
            at += len;
            if let Some(rest) = word.strip_prefix(PIECE_START) {
                start_pieces.insert(rest.to_string(), i as u32);
                is_piece[i] = true;
            } else if let Some(rest) = word.strip_prefix(PIECE_CONT) {
                cont_pieces.insert(rest.to_string(), i as u32);
                is_piece[i] = true;
            } else {
                ids.entry(word.clone()).or_insert(i as u32);
            }
            vocab.push(word);
        }
        let data_start = pad4(at);

        // The tensors, by name.
        let tensors = header.get("tensors")?.as_array()?;
        let mut by_name: HashMap<&str, (&serde_json::Value, usize, usize)> = HashMap::new();
        for t in tensors {
            let tname = t.get("name")?.as_str()?;
            let offset = t.get("offset")?.as_u64()? as usize;
            let len = t.get("bytes")?.as_u64()? as usize;
            by_name.insert(tname, (t, data_start + offset, len));
        }
        let shape = |t: &serde_json::Value| -> Option<Vec<usize>> {
            t.get("shape")?.as_array()?.iter().map(|v| v.as_u64().map(|v| v as usize)).collect()
        };
        let f32_tensor = |tname: &str, n: usize| -> Option<Vec<f32>> {
            let (t, at, len) = *by_name.get(tname)?;
            if t.get("dtype")?.as_str()? != "f32" || len != n * 4 {
                return None;
            }
            f32s(b, at, n)
        };
        let i8_matrix = |tname: &str, rows: usize, cols: usize| -> Option<I8Matrix> {
            let (t, at, len) = *by_name.get(tname)?;
            if t.get("dtype")?.as_str()? != "i8" {
                return None;
            }
            let s = shape(t)?;
            let (r, c) = if s.len() == 2 { (s[0], s[1]) } else { return None };
            if r != rows || c != cols || len != rows * cols + rows * 4 || at + len > b.len() {
                return None;
            }
            Some(I8Matrix { data: at, rows, cols, scales: f32s(b, at + rows * cols, rows)? })
        };
        let emb = i8_matrix("emb", vocab_len, d)?;
        let pos = f32_tensor("pos", ctx * d)?;
        let out_bias = f32_tensor("out_bias", vocab_len)?;
        let mut blocks = Vec::with_capacity(layers);
        for i in 0..layers {
            blocks.push(Layer {
                ln1_w: f32_tensor(&format!("l{i}.ln1.w"), d)?,
                ln1_b: f32_tensor(&format!("l{i}.ln1.b"), d)?,
                qkv: i8_matrix(&format!("l{i}.qkv.w"), 3 * d, d)?,
                qkv_b: f32_tensor(&format!("l{i}.qkv.b"), 3 * d)?,
                o: i8_matrix(&format!("l{i}.o.w"), d, d)?,
                o_b: f32_tensor(&format!("l{i}.o.b"), d)?,
                ln2_w: f32_tensor(&format!("l{i}.ln2.w"), d)?,
                ln2_b: f32_tensor(&format!("l{i}.ln2.b"), d)?,
                w1: i8_matrix(&format!("l{i}.w1.w"), ff, d)?,
                w1_b: f32_tensor(&format!("l{i}.w1.b"), ff)?,
                w2: i8_matrix(&format!("l{i}.w2.w"), d, ff)?,
                w2_b: f32_tensor(&format!("l{i}.w2.b"), d)?,
            });
        }
        let ln_w = f32_tensor("ln.w", d)?;
        let ln_b = f32_tensor("ln.b", d)?;
        Some(Lm { bytes, d, layers, heads, ff, ctx, name, langs, vocab, ids, start_pieces, cont_pieces, is_piece, emb, pos, out_bias, blocks, ln_w, ln_b })
    }

    pub fn vocab_size(&self) -> usize {
        self.vocab.len()
    }

    /// The word token `id` stands for.
    pub fn word(&self, id: u32) -> Option<&str> {
        self.vocab.get(id as usize).map(String::as_str)
    }

    /// The id of exactly `key` (a word as [`token_key`] leaves it), if the
    /// vocabulary holds it.
    pub fn id_of(&self, key: &str) -> Option<u32> {
        self.ids.get(key).copied()
    }

    /// The token a word before the caret becomes: its id, `<num>` for a
    /// number, `<unk>` for anything the vocabulary lacks.
    pub fn token_of(&self, word: &str) -> u32 {
        match token_key(word) {
            None => UNK,
            Some(key) => self.ids.get(key.as_str()).copied().unwrap_or(UNK),
        }
    }

    /// The tokens a word becomes: its own id, or its pieces when the model
    /// has them and can spell it, else `<unk>`.
    pub fn tokens_of(&self, word: &str) -> Vec<u32> {
        let Some(key) = token_key(word) else { return vec![UNK] };
        if let Some(&id) = self.ids.get(key.as_str()) {
            return vec![id];
        }
        self.pieces_of(&key).unwrap_or_else(|| vec![UNK])
    }

    /// `key` spelled in pieces, greedy longest match from the left, or
    /// `None` when a letter has no piece (or the model has none).
    pub fn pieces_of(&self, key: &str) -> Option<Vec<u32>> {
        if self.start_pieces.is_empty() || key == "<num>" {
            return None;
        }
        let chars: Vec<char> = key.chars().collect();
        let mut out = Vec::new();
        let mut i = 0;
        while i < chars.len() {
            let table = if i == 0 { &self.start_pieces } else { &self.cont_pieces };
            let mut taken = 0;
            for j in (i + 1..=chars.len().min(i + MAX_PIECE)).rev() {
                let piece: String = chars[i..j].iter().collect();
                if let Some(&id) = table.get(&piece) {
                    out.push(id);
                    taken = j;
                    break;
                }
            }
            if taken == 0 {
                return None;
            }
            i = taken;
        }
        Some(out)
    }

    /// Whether token `id` is a subword piece rather than a word.
    pub fn is_piece(&self, id: u32) -> bool {
        self.is_piece.get(id as usize).copied().unwrap_or(false)
    }

    pub fn has_pieces(&self) -> bool {
        !self.start_pieces.is_empty()
    }

    /// How many tokens of context the model reads: everything after the
    /// prefix and the sentence start, leaving room for the word predicted.
    pub fn max_words(&self) -> usize {
        self.ctx.saturating_sub(5)
    }


    fn row(&self, m: &I8Matrix, r: usize) -> &[i8] {
        let b = self.bytes.as_ref();
        let s = &b[m.data + r * m.cols..m.data + (r + 1) * m.cols];
        // SAFETY: i8 and u8 share size and alignment; the slice is the
        // matrix's own bytes.
        unsafe { std::slice::from_raw_parts(s.as_ptr() as *const i8, s.len()) }
    }

    /// `out = W x + b`, with `x` quantised to int8 once.
    fn matvec(&self, m: &I8Matrix, bias: &[f32], x: &[f32], xq: &mut Vec<i8>, out: &mut [f32]) {
        let sx = quantize(x, xq);
        for r in 0..m.rows {
            let acc = dot_i8(self.row(m, r), xq) as f32;
            out[r] = acc * sx * m.scales[r] + bias.get(r).copied().unwrap_or(0.0);
        }
    }

    /// The embedding of token `id`, dequantised.
    fn embed(&self, id: u32, out: &mut [f32]) {
        let r = (id as usize).min(self.emb.rows - 1);
        let row = self.row(&self.emb, r);
        let s = self.emb.scales[r];
        for (o, &w) in out.iter_mut().zip(row) {
            *o = w as f32 * s;
        }
    }
}

/// The key a word is scored under: the engine's fold ([`word_key::of_str`])
/// with the edges trimmed of punctuation, as `tools/wmlm/common.py` does it.
/// `None` for something that is not a word; a word with a digit in it is
/// `<num>`'s key.
pub fn token_key(word: &str) -> Option<String> {
    let folded = word_key::of_str(word).replace('\u{2019}', "'");
    let keep = |c: char| c.is_alphanumeric() || c == '\'' || c == '-' || c == '_';
    let trimmed = folded.trim_matches(|c: char| !keep(c));
    if trimmed.is_empty() || trimmed.chars().any(|c| !keep(c)) {
        return None;
    }
    if trimmed.chars().any(|c| c.is_ascii_digit() || c.is_numeric()) {
        return Some("<num>".to_string());
    }
    if trimmed.chars().all(|c| c == '\'' || c == '-') {
        return None;
    }
    Some(trimmed.to_string())
}

/// `x` as int8 into `out`; returns the scale (`x ≈ out * scale`).
fn quantize(x: &[f32], out: &mut Vec<i8>) -> f32 {
    out.clear();
    let max = x.iter().fold(0.0f32, |m, v| m.max(v.abs()));
    if max == 0.0 {
        out.resize(x.len(), 0);
        return 1.0;
    }
    let scale = max / 127.0;
    let inv = 1.0 / scale;
    out.extend(x.iter().map(|v| (v * inv).round().clamp(-127.0, 127.0) as i8));
    scale
}

fn layer_norm(x: &[f32], w: &[f32], b: &[f32], out: &mut [f32]) {
    let n = x.len() as f32;
    let mean = x.iter().sum::<f32>() / n;
    let var = x.iter().map(|v| (v - mean) * (v - mean)).sum::<f32>() / n;
    let inv = 1.0 / (var + 1e-5).sqrt();
    for i in 0..x.len() {
        out[i] = (x[i] - mean) * inv * w[i] + b[i];
    }
}

fn gelu(x: f32) -> f32 {
    const C: f32 = 0.797_884_56; // sqrt(2 / pi)
    0.5 * x * (1.0 + (C * (x + 0.044715 * x * x * x)).tanh())
}

/// A context read so far and the model's distribution over the word after
/// it. One per thread of use; it holds the attention cache.
pub struct Session<B: AsRef<[u8]> = Vec<u8>> {
    lm: Arc<Lm<B>>,
    tokens: Vec<u32>,
    /// Per layer, `T * d` keys and values of the tokens read.
    keys: Vec<Vec<f32>>,
    values: Vec<Vec<f32>>,
    /// The hidden state of the last token read, after the final norm.
    last: Vec<f32>,
    /// Log-probabilities over the vocabulary for the next token, once asked.
    dist: Option<Vec<f32>>,
    // Scratch.
    x: Vec<f32>,
    h: Vec<f32>,
    qkv: Vec<f32>,
    attn: Vec<f32>,
    ff: Vec<f32>,
    tmp: Vec<f32>,
    xq: Vec<i8>,
}

impl<B: AsRef<[u8]>> Session<B> {
    pub fn new(lm: Arc<Lm<B>>) -> Session<B> {
        let d = lm.d;
        let layers = lm.layers;
        let ff = lm.ff;
        Session {
            lm,
            tokens: Vec::new(),
            keys: vec![Vec::new(); layers],
            values: vec![Vec::new(); layers],
            last: vec![0.0; d],
            dist: None,
            x: vec![0.0; d],
            h: vec![0.0; d],
            qkv: vec![0.0; 3 * d],
            attn: vec![0.0; d],
            ff: vec![0.0; ff],
            tmp: vec![0.0; d],
            xq: Vec::new(),
        }
    }

    pub fn lm(&self) -> &Lm<B> {
        &self.lm
    }

    /// The tokens a context is read as: the prefix, `<s>`, then the last
    /// words that fit. A leading sentence sentinel is dropped (the `<s>` is
    /// always there); a word the vocabulary lacks is `<unk>`.
    pub fn tokens_for(&self, prefix: Prefix, words: &[&str]) -> Vec<u32> {
        let lm = &self.lm;
        let mut out = Vec::with_capacity(words.len() + 4);
        out.push(REG_NEUTRAL + (prefix.register.min(2) as u32));
        out.push(FIELD_OTHER + (prefix.field.min(3) as u32));
        out.push(LANG_0 + (prefix.lang.min(3) as u32));
        out.push(BOS);
        let mut body: Vec<u32> = Vec::with_capacity(words.len());
        for w in words.iter().copied().filter(|w| !w.starts_with('\u{1}') && !w.is_empty()) {
            body.extend(lm.tokens_of(w));
        }
        // The last tokens that fit; an unknown word spelled in pieces costs a
        // few of them.
        let keep = lm.max_words();
        let start = body.len().saturating_sub(keep);
        out.extend_from_slice(&body[start..]);
        out
    }

    /// Reads `words` (nearest the caret last) under `prefix`, reusing
    /// whatever of the previous context still stands.
    pub fn set_context(&mut self, prefix: Prefix, words: &[&str]) {
        let tokens = self.tokens_for(prefix, words);
        self.set_tokens(&tokens);
    }

    pub fn set_tokens(&mut self, tokens: &[u32]) {
        let tokens = &tokens[..tokens.len().min(self.lm.ctx)];
        let common = self.tokens.iter().zip(tokens).take_while(|(a, b)| a == b).count();
        // The last position's hidden state is only kept for the last token,
        // so a context equal to the old one is reused whole; otherwise the
        // common prefix's keys and values are kept and the rest recomputed.
        if common == tokens.len() && common == self.tokens.len() && self.dist.is_some() {
            return;
        }
        let d = self.lm.d;
        let keep = common.min(tokens.len().saturating_sub(1));
        self.tokens.truncate(keep);
        for l in 0..self.lm.layers {
            self.keys[l].truncate(keep * d);
            self.values[l].truncate(keep * d);
        }
        for &t in &tokens[keep..] {
            self.step(t);
        }
        self.dist = None;
    }

    /// One token through the network; its keys and values join the cache.
    fn step(&mut self, token: u32) {
        let lm = self.lm.clone();
        let d = lm.d;
        let pos = self.tokens.len();
        self.tokens.push(token);
        lm.embed(token, &mut self.x);
        for i in 0..d {
            self.x[i] += lm.pos[pos * d + i];
        }
        let heads = lm.heads;
        let dh = d / heads;
        let scale = 1.0 / (dh as f32).sqrt();
        for (l, layer) in lm.blocks.iter().enumerate() {
            layer_norm(&self.x, &layer.ln1_w, &layer.ln1_b, &mut self.h);
            lm.matvec(&layer.qkv, &layer.qkv_b, &self.h, &mut self.xq, &mut self.qkv);
            let (q, kv) = self.qkv.split_at(d);
            let (k, v) = kv.split_at(d);
            self.keys[l].extend_from_slice(k);
            self.values[l].extend_from_slice(v);
            let t = pos + 1;
            let keys = &self.keys[l];
            let values = &self.values[l];
            // Causal attention of the new token over everything so far.
            for hd in 0..heads {
                let q = &q[hd * dh..(hd + 1) * dh];
                let mut scores: Vec<f32> = (0..t)
                    .map(|j| {
                        let kj = &keys[j * d + hd * dh..j * d + (hd + 1) * dh];
                        q.iter().zip(kj).map(|(a, b)| a * b).sum::<f32>() * scale
                    })
                    .collect();
                let max = scores.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
                let mut sum = 0.0;
                for s in scores.iter_mut() {
                    *s = (*s - max).exp();
                    sum += *s;
                }
                let out = &mut self.attn[hd * dh..(hd + 1) * dh];
                out.iter_mut().for_each(|o| *o = 0.0);
                for (j, w) in scores.iter().enumerate() {
                    let w = w / sum;
                    let vj = &values[j * d + hd * dh..j * d + (hd + 1) * dh];
                    for (o, v) in out.iter_mut().zip(vj) {
                        *o += w * v;
                    }
                }
            }
            lm.matvec(&layer.o, &layer.o_b, &self.attn, &mut self.xq, &mut self.tmp);
            for i in 0..d {
                self.x[i] += self.tmp[i];
            }
            layer_norm(&self.x, &layer.ln2_w, &layer.ln2_b, &mut self.h);
            lm.matvec(&layer.w1, &layer.w1_b, &self.h, &mut self.xq, &mut self.ff);
            for v in self.ff.iter_mut() {
                *v = gelu(*v);
            }
            lm.matvec(&layer.w2, &layer.w2_b, &self.ff, &mut self.xq, &mut self.tmp);
            for i in 0..d {
                self.x[i] += self.tmp[i];
            }
        }
        layer_norm(&self.x, &lm.ln_w, &lm.ln_b, &mut self.last);
    }

    /// The log-probability of every token coming next, computed once per
    /// context.
    pub fn distribution(&mut self) -> &[f32] {
        if self.dist.is_none() {
            let lm = self.lm.clone();
            let mut logits = vec![0.0f32; lm.vocab_size()];
            if self.tokens.is_empty() {
                // Nothing read: an empty prefix with a sentence start.
                let t = self.tokens_for(Prefix::default(), &[]);
                for &tok in &t {
                    self.step(tok);
                }
            }
            lm.matvec(&lm.emb, &lm.out_bias, &self.last, &mut self.xq, &mut logits);
            // Padding is never the next word.
            logits[PAD as usize] = f32::NEG_INFINITY;
            let max = logits.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
            let sum: f32 = logits.iter().map(|v| (v - max).exp()).sum();
            let log_z = max + sum.ln();
            for v in logits.iter_mut() {
                *v -= log_z;
            }
            self.dist = Some(logits);
        }
        self.dist.as_deref().unwrap_or(&[])
    }

    /// ln P(`id` next | context).
    pub fn logprob_id(&mut self, id: u32) -> f32 {
        let dist = self.distribution();
        dist.get(id as usize).copied().unwrap_or(f32::NEG_INFINITY)
    }

    /// ln P(`word` next | context): a lookup for a word the vocabulary holds,
    /// the product over its pieces for one it can spell (each piece scored
    /// after the ones before it, the context then put back), and `None` when
    /// it can do neither: the model has no opinion about a word it cannot
    /// read.
    pub fn logprob(&mut self, word: &str) -> Option<f32> {
        let key = token_key(word)?;
        if key == "<num>" {
            return None;
        }
        if let Some(id) = self.lm.id_of(&key) {
            return Some(self.logprob_id(id));
        }
        self.logprob_pieces(&key)
    }

    /// [`Session::logprob`] for a word the vocabulary holds as one token,
    /// `None` for any other: one lookup, never a forward step. The strip
    /// asks this on every keystroke for every candidate; spelling each
    /// unknown one in pieces there cost six forward steps per candidate
    /// (decide time rose six-fold on the harness), so pieces are kept for
    /// the gate's one typed word and the revision's few candidates.
    pub fn logprob_listed(&mut self, word: &str) -> Option<f32> {
        let key = token_key(word)?;
        if key == "<num>" {
            return None;
        }
        let id = self.lm.id_of(&key)?;
        Some(self.logprob_id(id))
    }

    fn logprob_pieces(&mut self, key: &str) -> Option<f32> {
        let lm = self.lm.clone();
        let pieces = lm.pieces_of(key)?;
        if pieces.len() > MAX_WORD_PIECES || self.tokens.len() + pieces.len() > lm.ctx {
            return None;
        }
        let mut total = self.logprob_id(pieces[0]);
        if pieces.len() > 1 {
            let before = self.tokens.clone();
            let dist_before = self.dist.take();
            for w in pieces.windows(2) {
                self.step(w[0]);
                self.dist = None;
                total += self.logprob_id(w[1]);
            }
            self.set_tokens(&before);
            self.dist = dist_before;
        }
        Some(total)
    }

    /// How many words the model would put before `word` here: 0 when it is
    /// the likeliest next word. `None` for a word the vocabulary does not
    /// hold as one token. One pass over the vocabulary.
    pub fn rank_of(&mut self, word: &str) -> Option<u32> {
        let key = token_key(word)?;
        let id = self.lm.id_of(&key)?;
        let lm = self.lm.clone();
        let dist = self.distribution();
        let own = dist[id as usize];
        let rank = dist
            .iter()
            .enumerate()
            .skip(SPECIALS as usize)
            .filter(|(i, &lp)| lp > own && !lm.is_piece(*i as u32))
            .count();
        Some(rank as u32)
    }

    /// The entropy of the next-word distribution, in nats: low when the
    /// model is sure what comes next, high when anything could.
    pub fn entropy(&mut self) -> f32 {
        let dist = self.distribution();
        -dist.iter().filter(|lp| lp.is_finite()).map(|&lp| lp.exp() * lp).sum::<f32>()
    }

    /// The `k` likeliest next words (specials left out) above `min_logprob`,
    /// best first.
    pub fn top(&mut self, k: usize, min_logprob: f32) -> Vec<(String, f32)> {
        let dist = self.distribution().to_vec();
        let lm = self.lm.clone();
        let mut best: Vec<(u32, f32)> = Vec::with_capacity(k + 1);
        for (id, &lp) in dist.iter().enumerate().skip(SPECIALS as usize) {
            if lp < min_logprob || lm.is_piece(id as u32) {
                continue;
            }
            if best.len() < k || lp > best[best.len() - 1].1 {
                let at = best.partition_point(|(_, b)| *b >= lp);
                best.insert(at, (id as u32, lp));
                if best.len() > k {
                    best.pop();
                }
            }
        }
        best.into_iter().filter_map(|(id, lp)| self.lm.word(id).map(|w| (w.to_string(), lp))).collect()
    }

    /// Whether the model thinks the sentence is over: `</s>` leads the
    /// distribution.
    pub fn sentence_ends(&mut self) -> bool {
        let dist = self.distribution();
        let eos = dist[EOS as usize];
        dist.iter().enumerate().all(|(i, &v)| i == EOS as usize || v <= eos)
    }

    /// Up to `n` words the model would write next, greedily, each at least
    /// `min_prob` likely at its step, stopping at a sentence end. Returns the
    /// words and the joint log-probability. The context is left extended by
    /// them; call [`Session::set_context`] to go back.
    pub fn generate(&mut self, n: usize, min_prob: f32) -> (Vec<String>, f32) {
        let mut out = Vec::new();
        let mut total = 0.0;
        let min_lp = min_prob.ln();
        for _ in 0..n {
            if self.tokens.len() >= self.lm.ctx {
                break;
            }
            let lm = self.lm.clone();
            let dist = self.distribution();
            let mut best = (EOS, f32::NEG_INFINITY);
            for (id, &lp) in dist.iter().enumerate() {
                let id = id as u32;
                if (id < SPECIALS && id != EOS) || id == UNK || lm.is_piece(id) {
                    continue;
                }
                if lp > best.1 {
                    best = (id, lp);
                }
            }
            if best.0 == EOS || best.1 < min_lp {
                break;
            }
            total += best.1;
            out.push(self.lm.word(best.0).unwrap_or("").to_string());
            self.step(best.0);
            self.dist = None;
        }
        (out, total)
    }

    pub fn tokens(&self) -> &[u32] {
        &self.tokens
    }

    /// How well each of `candidates` fits where the word before `follower`
    /// stands, after `context`: ln P(candidate | context) + ln P(follower |
    /// context, candidate). `None` for a candidate or follower the model does
    /// not know. The context the session held is put back afterwards.
    pub fn revise(&mut self, prefix: Prefix, context: &[&str], candidates: &[&str], follower: &str) -> Vec<Option<f32>> {
        self.revise_all(prefix, context, candidates, &[follower])
    }

    /// [`Session::revise`] with every word after the judged one in sight:
    /// ln P(candidate | context) + Σ ln P(follower_i | context, candidate,
    /// followers before it). `None` where a word cannot be read.
    pub fn revise_all(&mut self, prefix: Prefix, context: &[&str], candidates: &[&str], followers: &[&str]) -> Vec<Option<f32>> {
        let before = self.tokens.clone();
        let mut out = Vec::with_capacity(candidates.len());
        for candidate in candidates {
            self.set_context(prefix, context);
            let mut score = self.logprob(candidate);
            let mut with: Vec<&str> = context.to_vec();
            with.push(candidate);
            for follower in followers {
                if score.is_none() {
                    break;
                }
                self.set_context(prefix, &with);
                score = match (score, self.logprob(follower)) {
                    (Some(s), Some(f)) => Some(s + f),
                    _ => None,
                };
                with.push(follower);
            }
            out.push(score);
        }
        self.set_tokens(&before);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_keys_follow_the_python_rules() {
        assert_eq!(Some("hello".to_string()), token_key("Hello,"));
        assert_eq!(Some("don't".to_string()), token_key("“Don’t”"));
        assert_eq!(Some("well-paid".to_string()), token_key("well-paid"));
        assert_eq!(Some("<num>".to_string()), token_key("2nd"));
        assert_eq!(None, token_key("..."));
        assert_eq!(None, token_key("--"));
        assert_eq!(None, token_key("a.b"));
    }

    #[test]
    fn quantisation_round_trips_within_a_step() {
        let x = [0.5f32, -1.0, 0.25, 3.0];
        let mut q = Vec::new();
        let s = quantize(&x, &mut q);
        for (v, &qv) in x.iter().zip(&q) {
            assert!((v - qv as f32 * s).abs() <= s / 2.0 + 1e-6);
        }
    }

    /// A tiny model written the way export.py writes one: d 4, one layer,
    /// two heads, ff 8, ctx 8, the sixteen specials plus two words.
    fn tiny_model() -> Vec<u8> {
        tiny_model_with(&[])
    }

    /// [`tiny_model`] with `extra` after its two words, for pieces.
    fn tiny_model_with(extra: &[&str]) -> Vec<u8> {
        let (d, ff, ctx, vocab) = (4usize, 8usize, 8usize, 18 + extra.len());
        let mut tensors = Vec::new();
        let mut data: Vec<u8> = Vec::new();
        let mut add = |name: &str, shape: &[usize], dtype: &str, data: &mut Vec<u8>| {
            let n: usize = shape.iter().product();
            let offset = data.len();
            let bytes = if dtype == "i8" {
                let rows = shape[0];
                let mut b: Vec<u8> = (0..n).map(|i| ((i * 7 % 13) as i8 - 6) as u8).collect();
                for _ in 0..rows {
                    b.extend_from_slice(&0.05f32.to_le_bytes());
                }
                b
            } else {
                let mut b = Vec::new();
                for i in 0..n {
                    let v = if name.ends_with(".w") || name == "ln.w" { 1.0f32 } else { (i % 3) as f32 * 0.01 };
                    b.extend_from_slice(&v.to_le_bytes());
                }
                b
            };
            let len = bytes.len();
            data.extend_from_slice(&bytes);
            while data.len() % 4 != 0 {
                data.push(0);
            }
            let shape_s: Vec<String> = shape.iter().map(|v| v.to_string()).collect();
            tensors.push(format!(
                "{{\"name\":\"{name}\",\"shape\":[{}],\"dtype\":\"{dtype}\",\"offset\":{offset},\"bytes\":{len}}}",
                shape_s.join(",")
            ));
        };
        add("emb", &[vocab, d], "i8", &mut data);
        add("pos", &[ctx, d], "f32", &mut data);
        add("out_bias", &[vocab], "f32", &mut data);
        add("l0.ln1.w", &[d], "f32", &mut data);
        add("l0.ln1.b", &[d], "f32", &mut data);
        add("l0.qkv.w", &[3 * d, d], "i8", &mut data);
        add("l0.qkv.b", &[3 * d], "f32", &mut data);
        add("l0.o.w", &[d, d], "i8", &mut data);
        add("l0.o.b", &[d], "f32", &mut data);
        add("l0.ln2.w", &[d], "f32", &mut data);
        add("l0.ln2.b", &[d], "f32", &mut data);
        add("l0.w1.w", &[ff, d], "i8", &mut data);
        add("l0.w1.b", &[ff], "f32", &mut data);
        add("l0.w2.w", &[d, ff], "i8", &mut data);
        add("l0.w2.b", &[d], "f32", &mut data);
        add("ln.w", &[d], "f32", &mut data);
        add("ln.b", &[d], "f32", &mut data);
        let header = format!(
            "{{\"d\":{d},\"layers\":1,\"heads\":2,\"ff\":{ff},\"ctx\":{ctx},\"vocab\":{vocab},\"name\":\"tiny\",\"langs\":[\"en\"],\"tensors\":[{}]}}",
            tensors.join(",")
        );
        let mut header_bytes = header.into_bytes();
        while header_bytes.len() % 4 != 0 {
            header_bytes.push(0);
        }
        let mut out = Vec::new();
        out.extend_from_slice(MAGIC);
        out.extend_from_slice(&VERSION.to_le_bytes());
        out.extend_from_slice(&(header_bytes.len() as u32).to_le_bytes());
        out.extend_from_slice(&header_bytes);
        let mut words = vec![
            "<pad>", "<s>", "</s>", "<unk>", "<num>", "<reg:neutral>", "<reg:casual>", "<reg:formal>", "<field:other>",
            "<field:chat>", "<field:email>", "<field:search>", "<lang:0>", "<lang:1>", "<lang:2>", "<lang:3>", "hello", "world",
        ];
        words.extend_from_slice(extra);
        out.extend_from_slice(&(words.len() as u32).to_le_bytes());
        for w in words {
            out.extend_from_slice(&(w.len() as u16).to_le_bytes());
            out.extend_from_slice(w.as_bytes());
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out.extend_from_slice(&data);
        out
    }

    #[test]
    fn a_tiny_model_opens_scores_and_reuses_its_context() {
        let lm = Arc::new(Lm::open(tiny_model()).expect("the tiny model opens"));
        assert_eq!(18, lm.vocab_size());
        assert_eq!(Some(16), lm.id_of("hello"));
        assert_eq!(UNK, lm.token_of("nothing"));
        let mut s = Session::new(lm.clone());
        s.set_context(Prefix::default(), &["hello"]);
        let dist = s.distribution().to_vec();
        let total: f64 = dist.iter().filter(|v| v.is_finite()).map(|v| (*v as f64).exp()).sum();
        assert!((total - 1.0).abs() < 1e-3, "a distribution sums to one: {total}");
        assert!(s.logprob("world").is_some());
        assert!(s.logprob("nothing").is_none());
        assert!(s.logprob("42").is_none());
        let tokens = s.tokens().to_vec();
        s.set_context(Prefix::default(), &["hello", "world"]);
        assert_eq!(tokens.len() + 1, s.tokens().len());
        let longer = s.tokens().to_vec();
        // Back to the shorter context: the common prefix is kept, the
        // distribution recomputed.
        s.set_context(Prefix::default(), &["hello"]);
        assert_eq!(tokens, s.tokens());
        let again = s.distribution().to_vec();
        assert_eq!(dist, again);
        // Revising puts the context back.
        s.set_tokens(&longer);
        let scores = s.revise(Prefix::default(), &["hello"], &["world", "hello", "nothing"], "world");
        assert!(scores[0].is_some() && scores[1].is_some() && scores[2].is_none());
        assert_eq!(longer, s.tokens());
        assert!(s.top(3, f32::NEG_INFINITY).len() == 2);
    }

    #[test]
    fn a_model_with_pieces_spells_the_words_it_does_not_list() {
        let lm = Arc::new(
            Lm::open(tiny_model_with(&["\u{2581}qu", "##ick", "\u{2581}a", "##a"])).expect("the tiny model opens"),
        );
        let (qu, ick) = (18, 19);
        assert_eq!(Some(vec![qu, ick]), lm.pieces_of("quick"));
        assert_eq!(vec![qu, ick], lm.tokens_of("quick"));
        // A word the vocabulary holds stays one token, and a letter with no
        // piece leaves the word unspellable.
        assert_eq!(vec![16], lm.tokens_of("hello"));
        assert_eq!(None, lm.pieces_of("zebra"));
        assert_eq!(vec![UNK], lm.tokens_of("zebra"));
        assert!(lm.is_piece(qu) && lm.is_piece(ick) && !lm.is_piece(16));

        let mut s = Session::new(lm.clone());
        s.set_context(Prefix::default(), &["hello"]);
        let tokens = s.tokens().to_vec();
        let dist = s.distribution().to_vec();
        let first = s.logprob_id(qu);
        let spelled = s.logprob("quick").expect("two pieces fit");
        assert!(spelled.is_finite() && spelled < first, "{spelled} costs more than its first piece {first}");
        // Spelling walks the session forward and has to put it back.
        assert_eq!(tokens, s.tokens());
        assert_eq!(dist, s.distribution().to_vec());
        assert_eq!(None, s.logprob_listed("quick"));
        // Past MAX_WORD_PIECES the model has no opinion.
        assert_eq!(None, s.logprob("aaaaaaa"));
        // Pieces are never offered as next words.
        assert_eq!(2, s.top(8, f32::NEG_INFINITY).len());
    }

    #[test]
    fn a_model_the_reader_does_not_know_is_refused() {
        assert!(Lm::open(b"WMLX\0\0\0\0".to_vec()).is_none());
        assert!(Lm::open(Vec::new()).is_none());
    }
}
