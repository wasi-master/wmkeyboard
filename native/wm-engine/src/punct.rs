//! The smart punctuation key's model: a small bidirectional transformer
//! encoder read from a `.wmpt` file (`tools/wmpunct/export.py` writes one)
//! and run here in int8.
//!
//! The model answers one question about the sentence before the caret: is
//! it heading for a full stop, a question mark, an exclamation mark, or a
//! comma (a clause the writer goes on from)? The keyboard asks on every
//! keystroke while a sentence is being typed, and draws the likeliest end
//! mark on the key beside the spacebar; a hold on that key still offers the
//! others.
//!
//! The arithmetic mirrors `tools/wmpunct/model.py`: a factorised embedding
//! (`e` wide, projected to `d`), learned positions over `ctx` tokens,
//! pre-norm blocks of full self-attention and a GELU (tanh form)
//! feed-forward, a final LayerNorm on the `<s>` position and a linear head
//! over the classes. Weights are int8 with one scale per output row, the
//! activations quantised per vector before each product (`dot_i8`), the rest
//! f32: the same kernels as the context model in [`crate::lm`], whose
//! container format and word tokenisation this shares. One language's
//! sentences and another's are told apart from their words: the model
//! carries no language token.
//!
//! The whole sentence is a few dozen tokens, so there is no cache: every
//! call is one pass, a couple of milliseconds, off the keystroke's thread.

use crate::kernels::dot::dot_i8;
use crate::lm::{f32s, gelu, layer_norm, pad4, pieces_greedy, quantize, token_key, u32_at, I8Matrix, PIECE_CONT, PIECE_START};
use std::collections::HashMap;

pub const MAGIC: &[u8; 4] = b"WMPT";
pub const VERSION: u32 = 1;

/// The four tokens every model has first, in this order.
pub const PAD: u32 = 0;
pub const BOS: u32 = 1;
pub const UNK: u32 = 2;
pub const NUM: u32 = 3;
pub const SPECIALS: u32 = 4;

/// The classes, in the order the head gives them; the file's header lists
/// the same marks and the reader refuses one that does not.
pub const LABELS: [&str; 4] = [".", "?", "!", ","];
pub const PERIOD: usize = 0;
pub const QUESTION: usize = 1;
pub const EXCLAIM: usize = 2;
pub const COMMA: usize = 3;

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
pub struct Punct<B: AsRef<[u8]> = Vec<u8>> {
    bytes: B,
    pub e: usize,
    pub d: usize,
    pub layers: usize,
    pub heads: usize,
    pub ff: usize,
    pub ctx: usize,
    pub classes: usize,
    pub name: String,
    /// The language ids the model was trained on, for the catalogue and
    /// the Resources screen; it reads any of them without being told which.
    pub langs: Vec<String>,
    vocab_len: usize,
    ids: HashMap<String, u32>,
    start_pieces: HashMap<String, u32>,
    cont_pieces: HashMap<String, u32>,
    emb: I8Matrix,
    proj: I8Matrix,
    proj_b: Vec<f32>,
    pos: Vec<f32>,
    blocks: Vec<Layer>,
    ln_w: Vec<f32>,
    ln_b: Vec<f32>,
    head_w: Vec<f32>,
    head_b: Vec<f32>,
}

impl<B: AsRef<[u8]>> Punct<B> {
    /// Reads a `.wmpt`; `None` when the bytes are not one this reader trusts.
    pub fn open(bytes: B) -> Option<Punct<B>> {
        let b = bytes.as_ref();
        if b.get(0..4)? != MAGIC || u32_at(b, 4)? != VERSION {
            return None;
        }
        let header_len = u32_at(b, 8)? as usize;
        let header_bytes = b.get(12..12 + header_len)?;
        let end = header_bytes.iter().rposition(|&c| c != 0).map_or(0, |i| i + 1);
        let header: serde_json::Value = serde_json::from_slice(&header_bytes[..end]).ok()?;
        let num = |k: &str| header.get(k)?.as_u64().map(|v| v as usize);
        let (e, d, layers, heads, ff, ctx, vocab_len, classes) = (
            num("e")?,
            num("d")?,
            num("layers")?,
            num("heads")?,
            num("ff")?,
            num("ctx")?,
            num("vocab")?,
            num("classes")?,
        );
        if e == 0 || d == 0 || heads == 0 || d % heads != 0 || layers == 0 || ctx < 2 || vocab_len < SPECIALS as usize {
            return None;
        }
        if classes != LABELS.len() {
            return None;
        }
        let labels: Vec<&str> = header.get("labels")?.as_array()?.iter().filter_map(|v| v.as_str()).collect();
        if labels != LABELS {
            return None;
        }
        let name = header.get("name").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let langs: Vec<String> = header
            .get("langs")
            .and_then(|v| v.as_array())
            .map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect())
            .unwrap_or_default();

        // The vocabulary: words by id, pieces into their two tables.
        let mut at = 12 + header_len;
        let count = u32_at(b, at)? as usize;
        if count != vocab_len {
            return None;
        }
        at += 4;
        let mut ids = HashMap::with_capacity(count * 2);
        let mut start_pieces = HashMap::new();
        let mut cont_pieces = HashMap::new();
        for i in 0..count {
            let len = u16::from_le_bytes(b.get(at..at + 2)?.try_into().ok()?) as usize;
            at += 2;
            let word = std::str::from_utf8(b.get(at..at + len)?).ok()?;
            at += len;
            if let Some(rest) = word.strip_prefix(PIECE_START) {
                start_pieces.insert(rest.to_string(), i as u32);
            } else if let Some(rest) = word.strip_prefix(PIECE_CONT) {
                cont_pieces.insert(rest.to_string(), i as u32);
            } else {
                ids.entry(word.to_string()).or_insert(i as u32);
            }
        }
        let data_start = pad4(at);

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
        let emb = i8_matrix("emb", vocab_len, e)?;
        let proj = i8_matrix("proj.w", d, e)?;
        let proj_b = f32_tensor("proj.b", d)?;
        let pos = f32_tensor("pos", ctx * d)?;
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
        let head_w = f32_tensor("head.w", classes * d)?;
        let head_b = f32_tensor("head.b", classes)?;
        Some(Punct {
            bytes,
            e,
            d,
            layers,
            heads,
            ff,
            ctx,
            classes,
            name,
            langs,
            vocab_len,
            ids,
            start_pieces,
            cont_pieces,
            emb,
            proj,
            proj_b,
            pos,
            blocks,
            ln_w,
            ln_b,
            head_w,
            head_b,
        })
    }

    pub fn vocab_size(&self) -> usize {
        self.vocab_len
    }

    /// Whether the vocabulary holds `word` as one token.
    pub fn knows(&self, word: &str) -> bool {
        token_key(word).and_then(|k| self.ids.get(&k)).is_some()
    }

    /// The tokens a word becomes: its own id, `<num>` for a number, its
    /// pieces when the model can spell it, else `<unk>`.
    pub fn tokens_of(&self, word: &str) -> Vec<u32> {
        let Some(key) = token_key(word) else { return vec![UNK] };
        if key == "<num>" {
            return vec![NUM];
        }
        if let Some(&id) = self.ids.get(key.as_str()) {
            return vec![id];
        }
        pieces_greedy(&self.start_pieces, &self.cont_pieces, &key).unwrap_or_else(|| vec![UNK])
    }

    /// The tokens a sentence so far is read as: `<s>` then the words, cut
    /// from the middle to `ctx` when a sentence spelled in pieces runs over
    /// (`tools/wmpunct/prep.py` cuts the same way), so both its first words
    /// and its last survive. A word that is not one (the sentence sentinel,
    /// an empty string) is skipped.
    pub fn tokens_for(&self, words: &[&str]) -> Vec<u32> {
        let mut seq = Vec::with_capacity(words.len() + 1);
        seq.push(BOS);
        for w in words.iter().copied().filter(|w| !w.starts_with('\u{1}') && !w.is_empty()) {
            seq.extend(self.tokens_of(w));
        }
        if seq.len() > self.ctx {
            let head = self.ctx / 3;
            let tail = self.ctx - head;
            let mut cut = Vec::with_capacity(self.ctx);
            cut.extend_from_slice(&seq[..head]);
            cut.extend_from_slice(&seq[seq.len() - tail..]);
            seq = cut;
        }
        seq
    }

    /// The probability of each mark ([`LABELS`] order) for the sentence so
    /// far, words oldest first. An empty sentence is read as just `<s>`.
    pub fn predict(&self, words: &[&str]) -> Vec<f32> {
        let tokens = self.tokens_for(words);
        self.predict_tokens(&tokens)
    }

    /// [`Punct::predict`] over tokens already read (`tools/wmpunct/check.py`
    /// prints the same sequence).
    pub fn predict_tokens(&self, tokens: &[u32]) -> Vec<f32> {
        let d = self.d;
        let t = tokens.len().clamp(1, self.ctx);
        let tokens = &tokens[..t];
        let heads = self.heads;
        let dh = d / heads;
        let scale = 1.0 / (dh as f32).sqrt();
        let mut xq: Vec<i8> = Vec::new();
        // Embedding: the e-wide row, dequantised, projected to d, plus the
        // position.
        let mut x = vec![0.0f32; t * d];
        let mut row_e = vec![0.0f32; self.e];
        let mut projected = vec![0.0f32; d];
        for (i, &tok) in tokens.iter().enumerate() {
            self.embed(tok, &mut row_e);
            self.matvec(&self.proj, &self.proj_b, &row_e, &mut xq, &mut projected);
            let xi = &mut x[i * d..(i + 1) * d];
            for j in 0..d {
                xi[j] = projected[j] + self.pos[i * d + j];
            }
        }
        let mut h = vec![0.0f32; d];
        let mut qkv = vec![0.0f32; t * 3 * d];
        let mut attn = vec![0.0f32; d];
        let mut tmp = vec![0.0f32; d];
        let mut ffv = vec![0.0f32; self.ff];
        let mut scores = vec![0.0f32; t];
        for layer in &self.blocks {
            // Every token's query, key and value from the normed input.
            for i in 0..t {
                layer_norm(&x[i * d..(i + 1) * d], &layer.ln1_w, &layer.ln1_b, &mut h);
                self.matvec(&layer.qkv, &layer.qkv_b, &h, &mut xq, &mut qkv[i * 3 * d..(i + 1) * 3 * d]);
            }
            // Full attention: each token over every token.
            for i in 0..t {
                let q_all = &qkv[i * 3 * d..i * 3 * d + d];
                for hd in 0..heads {
                    let q = &q_all[hd * dh..(hd + 1) * dh];
                    let mut max = f32::NEG_INFINITY;
                    for j in 0..t {
                        let k = &qkv[j * 3 * d + d + hd * dh..j * 3 * d + d + (hd + 1) * dh];
                        let s = q.iter().zip(k).map(|(a, b)| a * b).sum::<f32>() * scale;
                        scores[j] = s;
                        if s > max {
                            max = s;
                        }
                    }
                    let mut sum = 0.0;
                    for s in scores.iter_mut() {
                        *s = (*s - max).exp();
                        sum += *s;
                    }
                    let out = &mut attn[hd * dh..(hd + 1) * dh];
                    out.iter_mut().for_each(|o| *o = 0.0);
                    for j in 0..t {
                        let w = scores[j] / sum;
                        let v = &qkv[j * 3 * d + 2 * d + hd * dh..j * 3 * d + 2 * d + (hd + 1) * dh];
                        for (o, vj) in out.iter_mut().zip(v) {
                            *o += w * vj;
                        }
                    }
                }
                self.matvec(&layer.o, &layer.o_b, &attn, &mut xq, &mut tmp);
                let xi = &mut x[i * d..(i + 1) * d];
                for j in 0..d {
                    xi[j] += tmp[j];
                }
            }
            // Feed-forward, token by token.
            for i in 0..t {
                let xi = &x[i * d..(i + 1) * d];
                layer_norm(xi, &layer.ln2_w, &layer.ln2_b, &mut h);
                self.matvec(&layer.w1, &layer.w1_b, &h, &mut xq, &mut ffv);
                for v in ffv.iter_mut() {
                    *v = gelu(*v);
                }
                self.matvec(&layer.w2, &layer.w2_b, &ffv, &mut xq, &mut tmp);
                let xi = &mut x[i * d..(i + 1) * d];
                for j in 0..d {
                    xi[j] += tmp[j];
                }
            }
        }
        // The `<s>` position, normed, through the head; softmax.
        layer_norm(&x[..d], &self.ln_w, &self.ln_b, &mut h);
        let mut logits = vec![0.0f32; self.classes];
        for c in 0..self.classes {
            let w = &self.head_w[c * d..(c + 1) * d];
            logits[c] = w.iter().zip(&h).map(|(a, b)| a * b).sum::<f32>() + self.head_b[c];
        }
        let max = logits.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
        let sum: f32 = logits.iter().map(|v| (v - max).exp()).sum();
        for v in logits.iter_mut() {
            *v = (*v - max).exp() / sum;
        }
        logits
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

    fn embed(&self, id: u32, out: &mut [f32]) {
        let r = (id as usize).min(self.emb.rows - 1);
        let row = self.row(&self.emb, r);
        let s = self.emb.scales[r];
        for (o, &w) in out.iter_mut().zip(row) {
            *o = w as f32 * s;
        }
    }
}

/// The class with the highest probability among the first `among` entries
/// of `probs` (so `3` judges the three end marks with the comma folded
/// away), or 0 for an empty slice.
pub fn argmax(probs: &[f32], among: usize) -> usize {
    let mut best = 0;
    for (i, &p) in probs.iter().enumerate().take(among.min(probs.len())) {
        if p > probs[best] {
            best = i;
        }
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A tiny model written the way export.py writes one: e 4, d 4, one
    /// layer, two heads, ff 8, ctx 8, the four specials plus words and pieces.
    fn tiny_model(words: &[&str]) -> Vec<u8> {
        let (e, d, ff, ctx, classes) = (4usize, 4usize, 8usize, 8usize, 4usize);
        let vocab = 4 + words.len();
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
                    let v = if name.ends_with(".w") && !name.starts_with("head") || name == "ln.w" {
                        1.0f32
                    } else {
                        ((i % 5) as f32 - 2.0) * 0.1
                    };
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
        add("emb", &[vocab, e], "i8", &mut data);
        add("proj.w", &[d, e], "i8", &mut data);
        add("proj.b", &[d], "f32", &mut data);
        add("pos", &[ctx, d], "f32", &mut data);
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
        add("head.w", &[classes, d], "f32", &mut data);
        add("head.b", &[classes], "f32", &mut data);
        let header = format!(
            "{{\"e\":{e},\"d\":{d},\"layers\":1,\"heads\":2,\"ff\":{ff},\"ctx\":{ctx},\"vocab\":{vocab},\"classes\":{classes},\"labels\":[\".\",\"?\",\"!\",\",\"],\"name\":\"tiny\",\"langs\":[\"en\"],\"tensors\":[{}]}}",
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
        let mut all = vec!["<pad>", "<s>", "<unk>", "<num>"];
        all.extend_from_slice(words);
        out.extend_from_slice(&(all.len() as u32).to_le_bytes());
        for w in all {
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
    fn a_tiny_model_opens_reads_words_and_answers_a_distribution() {
        let m = Punct::open(tiny_model(&["how", "are", "you", "\u{2581}qu", "##ick"])).expect("the tiny model opens");
        assert_eq!(9, m.vocab_size());
        assert_eq!(vec![4], m.tokens_of("How,"));
        assert_eq!(vec![NUM], m.tokens_of("42"));
        assert_eq!(vec![UNK], m.tokens_of("zebra"));
        assert_eq!(vec![7, 8], m.tokens_of("quick"));
        assert!(m.knows("you") && !m.knows("quick"));
        let p = m.predict(&["how", "are", "you"]);
        assert_eq!(4, p.len());
        let total: f32 = p.iter().sum();
        assert!((total - 1.0).abs() < 1e-4, "a distribution sums to one: {total}");
        assert!(p.iter().all(|v| v.is_finite() && *v >= 0.0));
        // The same words give the same answer; different words another.
        assert_eq!(p, m.predict(&["how", "are", "you"]));
        assert_ne!(p, m.predict(&["you"]));
        // An empty sentence is read as <s> alone.
        assert_eq!(vec![BOS], m.tokens_for(&[]));
        assert_eq!(4, m.predict(&[]).len());
    }

    #[test]
    fn a_long_sentence_is_cut_from_the_middle_to_ctx() {
        let m = Punct::open(tiny_model(&["how", "are", "you"])).expect("opens");
        let words = ["how"; 20];
        let t = m.tokens_for(&words);
        assert_eq!(8, t.len());
        assert_eq!(BOS, t[0]);
        assert_eq!(4, m.predict(&words).len());
    }

    #[test]
    fn argmax_judges_among_the_first_classes() {
        assert_eq!(QUESTION, argmax(&[0.3, 0.4, 0.1, 0.2], 3));
        assert_eq!(COMMA, argmax(&[0.3, 0.2, 0.1, 0.4], 4));
        assert_eq!(PERIOD, argmax(&[0.3, 0.2, 0.1, 0.4], 3));
        assert_eq!(0, argmax(&[], 3));
    }

    #[test]
    fn a_model_the_reader_does_not_know_is_refused() {
        assert!(Punct::open(b"WMPX\0\0\0\0".to_vec()).is_none());
        assert!(Punct::open(Vec::new()).is_none());
        // The context model's magic is not this reader's.
        assert!(Punct::open(b"WMLM\x01\0\0\0".to_vec()).is_none());
    }
}
