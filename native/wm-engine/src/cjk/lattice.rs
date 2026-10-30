//! The conversion decoder shared by every CJK composer: the Kotlin `Lattice`.
//!
//! A buffer becomes a graph whose nodes are input positions and whose arcs
//! are the readings a stretch of it could spell (a syllable as typed, its
//! fuzzy variants, an abbreviation's expansions, a repaired typo). Words are
//! enumerated by extending a reading one arc at a time and narrowing the
//! dictionary range with it, so a reading no word starts with is pruned at
//! once. A Viterbi search over the words, scored by the word-pair model,
//! finds the best readings; candidates always start at input position 0,
//! since a pick commits a prefix of the buffer.

use super::dict::ConversionDictionary;
use super::ngrams::CjkNgrams;
use crate::strict_math;
use std::collections::{HashMap, HashSet};

/// One reading a stretch of the buffer could spell, to node `to`.
#[derive(Clone, Debug)]
pub struct Arc {
    pub to: usize,
    pub reading: String,
    /// 0 for what was typed, negative for a guess or an abbreviation.
    pub penalty: f64,
    /// A spelling guess, held to [`Opts::max_ambiguous_span`].
    pub guess: bool,
    /// A repair of what was typed; see [`Opts::correction_margin`].
    pub correction: bool,
}

impl Arc {
    pub fn new(to: usize, reading: impl Into<String>, penalty: f64) -> Arc {
        Arc {
            to,
            reading: reading.into(),
            penalty,
            guess: false,
            correction: false,
        }
    }
}

/// Nodes are positions: `consumed[k]` is the input characters node `k` is
/// past. The first arc of a node is its as-typed reading.
#[derive(Clone, Debug, Default)]
pub struct Graph {
    pub consumed: Vec<usize>,
    pub arcs: Vec<Vec<Arc>>,
}

impl Graph {
    pub fn end(&self) -> usize {
        self.consumed.len().saturating_sub(1)
    }
}

/// A buffer whose unit boundaries are fixed: per unit its readings, their
/// penalties, where the abbreviations begin, and readings that merge it with
/// the next unit (jianpin's `z` + `h` as zh…).
pub struct Input {
    pub boundaries: Vec<usize>,
    pub options: Vec<Vec<String>>,
    pub penalties: Vec<Vec<f64>>,
    pub partial_from: Vec<usize>,
    pub merged: Vec<Vec<String>>,
    pub merged_penalties: Vec<Vec<f64>>,
}

impl Input {
    /// Units whose readings are all equally intended.
    pub fn options(options: Vec<Vec<String>>, input_lens: &[usize]) -> Input {
        let n = options.len();
        let mut boundaries = vec![0; n + 1];
        for i in 0..n {
            boundaries[i + 1] = boundaries[i] + input_lens[i];
        }
        Input {
            boundaries,
            penalties: options.iter().map(|o| vec![0.0; o.len()]).collect(),
            partial_from: options.iter().map(Vec::len).collect(),
            merged: vec![Vec::new(); n],
            merged_penalties: vec![Vec::new(); n],
            options,
        }
    }

    pub fn graph(&self) -> Graph {
        let n = self.options.len();
        let mut arcs = Vec::with_capacity(n + 1);
        for i in 0..n {
            let mut out = Vec::new();
            for (oi, reading) in self.options[i].iter().enumerate() {
                let mut a = Arc::new(
                    i + 1,
                    reading.clone(),
                    self.penalties[i].get(oi).copied().unwrap_or(0.0),
                );
                a.guess = oi > 0 && oi < self.partial_from[i];
                out.push(a);
            }
            if i + 1 < n {
                for (mi, reading) in self.merged[i].iter().enumerate() {
                    out.push(Arc::new(
                        i + 2,
                        reading.clone(),
                        self.merged_penalties[i].get(mi).copied().unwrap_or(0.0),
                    ));
                }
            }
            arcs.push(out);
        }
        arcs.push(Vec::new());
        Graph {
            consumed: self.boundaries.clone(),
            arcs,
        }
    }
}

/// Decoder bounds and weights; the defaults suit Chinese.
pub struct Opts<'a> {
    pub max_word_units: usize,
    pub max_ambiguous_span: usize,
    pub span_cand_cap: usize,
    pub beam: usize,
    pub limit: usize,
    /// One character spells one unit (true for Chinese, false for Japanese).
    pub char_per_unit: bool,
    pub over_length_penalty: f64,
    /// Whole-buffer readings of several words offered beyond the best.
    pub stitch: usize,
    pub stitch_bonus: f64,
    /// Order by how much of the buffer each candidate covers.
    pub coverage_order: bool,
    pub paths_per_stretch: usize,
    pub correction_margin: f64,
    pub word_penalty: Option<&'a dyn Fn(&str) -> f64>,
    pub path_margin: f64,
    pub stretch_cap: usize,
    /// The pair's share when interpolating the word pairs, 0 for stupid backoff.
    pub bigram_weight: f64,
}

impl Default for Opts<'_> {
    fn default() -> Self {
        Opts {
            max_word_units: 6,
            max_ambiguous_span: 2,
            span_cand_cap: 16,
            beam: 8,
            limit: 12,
            char_per_unit: true,
            over_length_penalty: -1.2,
            stitch: 0,
            stitch_bonus: 8.0,
            coverage_order: false,
            paths_per_stretch: 4,
            correction_margin: strict_math::log(10.0),
            word_penalty: None,
            path_margin: 5.0,
            stretch_cap: 4,
            bigram_weight: 0.0,
        }
    }
}

/// A decoded candidate: what to commit, how much of the buffer it eats.
#[derive(Clone, Debug, PartialEq)]
pub struct Cand {
    pub text: String,
    pub consumed: usize,
    pub score: f64,
    pub reading: String,
    pub corrected: bool,
}

/// Words never to offer, and steps up or down from the candidate menu.
#[derive(Clone, Debug, Default)]
pub struct WordAdjust {
    pub blocked: HashSet<String>,
    pub bias: HashMap<String, i32>,
}

/// What the decoder knows of this user beyond the dictionary.
pub struct UserWords<'a> {
    /// Whether a phrase the user taught is read starting with this.
    pub has_prefix: &'a dyn Fn(&str) -> bool,
    /// Previous word → word → times written in that order.
    pub pairs: Option<&'a HashMap<String, HashMap<String, i32>>>,
}

/// What the user picked per reading: word → times chosen.
pub type Learned<'h> = dyn Fn(&str) -> Option<&'h HashMap<String, i32>> + 'h;

const BETA: f64 = 0.5;
const LEARN_WEIGHT: f64 = 1.0;
const BIAS_WEIGHT: f64 = 2.0;
const USER_PAIR_WEIGHT: f64 = 1.0;
const USER_WORD_FREQ: i32 = 500;

fn unk_log() -> f64 {
    strict_math::log(1e-9)
}

fn user_word_log() -> f64 {
    strict_math::log(1e-4)
}

#[derive(Clone, Debug)]
struct Edge {
    to: usize,
    text: String,
    reading: String,
    emission: f64,
    freq: i32,
    fallback: bool,
    correction: bool,
    user: bool,
}

struct Scorer<'a> {
    dict: &'a ConversionDictionary,
    ngrams: &'a CjkNgrams,
    pairs: Option<&'a HashMap<String, HashMap<String, i32>>>,
    bigram_weight: f64,
}

impl Scorer<'_> {
    fn lm(&self, previous: Option<&str>, e: &Edge) -> f64 {
        let mut score = self.ngrams.log_probability_mixed(
            previous,
            &e.text,
            e.freq,
            self.dict.total_freq,
            self.bigram_weight,
        );
        if e.user {
            score = score.max(user_word_log());
        }
        if let (Some(prev), Some(pairs)) = (previous, self.pairs) {
            let n = pairs
                .get(prev)
                .and_then(|m| m.get(&e.text))
                .copied()
                .unwrap_or(0);
            if n > 0 {
                score += USER_PAIR_WEIGHT * strict_math::log(1.0 + n as f64);
            }
        }
        score
    }
}

/// A path in the search: its last word, score and where it came from, as an
/// index into the arena.
struct PathNode {
    word: Option<String>,
    score: f64,
    prev: Option<usize>,
    edge: Option<usize>,
    words: usize,
}

struct Arena {
    paths: Vec<PathNode>,
    edges: Vec<Edge>,
}

impl Arena {
    fn path_of(&self, end: usize) -> Vec<&Edge> {
        let mut out = Vec::new();
        let mut cur = Some(end);
        while let Some(i) = cur {
            let p = &self.paths[i];
            let Some(e) = p.edge else { break };
            out.push(&self.edges[e]);
            cur = p.prev;
        }
        out.reverse();
        out
    }

    fn text_of(&self, end: usize) -> String {
        self.path_of(end).iter().map(|e| e.text.as_str()).collect()
    }
}

/// Decodes `input`, best first.
#[allow(clippy::too_many_arguments)]
pub fn decode_input(
    input: &Input,
    dict: &ConversionDictionary,
    ngrams: &CjkNgrams,
    opts: &Opts,
    learned: &Learned<'_>,
    adjust: Option<&WordAdjust>,
    context: Option<&str>,
    user: &UserWords,
) -> Vec<Cand> {
    if input.options.is_empty() {
        return Vec::new();
    }
    decode(
        &input.graph(),
        dict,
        ngrams,
        opts,
        learned,
        adjust,
        context,
        user,
    )
}

/// Decodes a graph, best first. `context` is the word written just before.
#[allow(clippy::too_many_arguments)]
pub fn decode(
    graph: &Graph,
    dict: &ConversionDictionary,
    ngrams: &CjkNgrams,
    opts: &Opts,
    learned: &Learned<'_>,
    adjust: Option<&WordAdjust>,
    context: Option<&str>,
    user: &UserWords,
) -> Vec<Cand> {
    let n = graph.end();
    if n == 0 {
        return Vec::new();
    }
    let mut arena = Arena {
        paths: Vec::new(),
        edges: Vec::new(),
    };
    let by_node = build_edges(graph, dict, opts, learned, adjust, user, &mut arena.edges);
    let sc = Scorer {
        dict,
        ngrams,
        pairs: user.pairs,
        bigram_weight: opts.bigram_weight,
    };
    let live = viterbi(&by_node, &mut arena, &sc, opts, n, context);
    if opts.coverage_order {
        by_coverage(graph, &by_node, &live, &mut arena, &sc, opts, context)
    } else {
        by_first_word(graph, &by_node, &live, &mut arena, &sc, opts, context)
    }
}

fn build_edges(
    graph: &Graph,
    dict: &ConversionDictionary,
    opts: &Opts,
    learned: &Learned<'_>,
    adjust: Option<&WordAdjust>,
    user: &UserWords,
    edges: &mut Vec<Edge>,
) -> Vec<Vec<usize>> {
    let n = graph.end();
    let mut out: Vec<Vec<usize>> = vec![Vec::new(); n + 1];
    for i in 0..n {
        let Some(first) = graph.arcs[i].first() else {
            edges.push(Edge {
                to: i + 1,
                text: String::new(),
                reading: String::new(),
                emission: unk_log(),
                freq: 0,
                fallback: true,
                correction: false,
                user: false,
            });
            out[i].push(edges.len() - 1);
            continue;
        };
        if !dict.is_empty() || (user.has_prefix)("") {
            let mut walk = Walk {
                graph,
                dict,
                opts,
                learned,
                adjust,
                user,
                edges: &mut *edges,
                out: &mut out,
            };
            walk.extend(i, i, 0, "", 0..dict.len(), 0.0, 0, false);
        }
        edges.push(Edge {
            to: first.to,
            text: first.reading.clone(),
            reading: first.reading.clone(),
            emission: unk_log(),
            freq: 0,
            fallback: true,
            correction: false,
            user: false,
        });
        out[i].push(edges.len() - 1);
    }
    out
}

struct Walk<'a, 'b, 'c, 'd> {
    graph: &'a Graph,
    dict: &'a ConversionDictionary,
    opts: &'a Opts<'b>,
    learned: &'a Learned<'c>,
    adjust: Option<&'a WordAdjust>,
    user: &'a UserWords<'d>,
    edges: &'a mut Vec<Edge>,
    out: &'a mut Vec<Vec<usize>>,
}

impl Walk<'_, '_, '_, '_> {
    #[allow(clippy::too_many_arguments)]
    fn extend(
        &mut self,
        start: usize,
        node: usize,
        depth: usize,
        prefix: &str,
        range: std::ops::Range<usize>,
        penalty: f64,
        syllables: usize,
        corrected: bool,
    ) {
        if node >= self.graph.end() || syllables >= self.opts.max_word_units {
            return;
        }
        let ambiguous = depth < self.opts.max_ambiguous_span;
        let arcs = &self.graph.arcs[node];
        for arc in arcs {
            if !ambiguous && arc.guess {
                continue;
            }
            let reading = format!("{prefix}{}", arc.reading);
            let sub = self.dict.prefix_range(&reading, range.clone());
            if sub.is_empty() && !(self.user.has_prefix)(&reading) {
                continue;
            }
            let cost = penalty + arc.penalty;
            let fixed = corrected || arc.correction;
            self.emit_span(start, arc.to, &reading, cost, syllables + 1, fixed);
            self.extend(
                start,
                arc.to,
                depth + 1,
                &reading,
                sub,
                cost,
                syllables + 1,
                fixed,
            );
        }
    }

    fn adjustments(&self, word: &str, picks: Option<&HashMap<String, i32>>) -> f64 {
        let mut add = 0.0;
        let chosen = picks.and_then(|p| p.get(word)).copied().unwrap_or(0);
        if chosen > 0 {
            add += LEARN_WEIGHT * strict_math::log(1.0 + chosen as f64);
        }
        let bias = self
            .adjust
            .and_then(|a| a.bias.get(word))
            .copied()
            .unwrap_or(0);
        if bias != 0 {
            add += BIAS_WEIGHT * bias as f64;
        }
        if let Some(p) = self.opts.word_penalty {
            add += p(word);
        }
        add
    }

    fn emit_span(
        &mut self,
        start: usize,
        end: usize,
        reading: &str,
        penalty: f64,
        syllables: usize,
        corrected: bool,
    ) {
        let rows = self.dict.rows_for(reading);
        let picks = (self.learned)(reading);
        if rows.is_empty() && picks.is_none_or(|p| p.is_empty()) {
            return;
        }
        let blocked = self.adjust.map(|a| &a.blocked);
        let mut usable: Vec<usize> = rows
            .filter(|&r| !self.opts.char_per_unit || self.dict.word_length(r) >= syllables)
            .filter(|&r| blocked.is_none_or(|b| !b.contains(self.dict.word(r))))
            .collect();
        let cap = self.opts.span_cand_cap.max(self.opts.limit);
        usable.sort_by_key(|&r| std::cmp::Reverse(self.dict.frequency(r)));
        usable.truncate(cap);
        let max_freq = usable
            .first()
            .map(|&r| self.dict.frequency(r).max(1))
            .unwrap_or(USER_WORD_FREQ);
        let mut seen = HashSet::new();
        for row in usable {
            let freq = self.dict.frequency(row).max(1);
            let mut emission =
                BETA * (strict_math::log(freq as f64) - strict_math::log(max_freq as f64)) + penalty;
            if self.opts.char_per_unit {
                emission += self.opts.over_length_penalty
                    * (self.dict.word_length(row) as f64 - syllables as f64);
            }
            let word = self.dict.word(row).to_string();
            let add = self.adjustments(&word, picks);
            seen.insert(word.clone());
            self.edges.push(Edge {
                to: end,
                text: word,
                reading: reading.to_string(),
                emission: emission + add,
                freq: self.dict.frequency(row),
                fallback: false,
                correction: corrected,
                user: false,
            });
            self.out[start].push(self.edges.len() - 1);
        }
        let Some(picks) = picks else { return };
        let mut taught: Vec<(&String, &i32)> = picks.iter().collect();
        taught.sort();
        for (word, &chosen) in taught {
            if chosen <= 0 || seen.contains(word) || blocked.is_some_and(|b| b.contains(word)) {
                continue;
            }
            if self.opts.char_per_unit && word.chars().count() != syllables {
                continue;
            }
            let add = self.adjustments(word, Some(picks));
            self.edges.push(Edge {
                to: end,
                text: word.clone(),
                reading: reading.to_string(),
                emission: penalty + add,
                freq: USER_WORD_FREQ,
                fallback: false,
                correction: corrected,
                user: true,
            });
            self.out[start].push(self.edges.len() - 1);
        }
    }
}

/// Viterbi carrying the previous word; the surviving paths at every node.
fn viterbi(
    by_node: &[Vec<usize>],
    arena: &mut Arena,
    sc: &Scorer,
    opts: &Opts,
    n: usize,
    context: Option<&str>,
) -> Vec<Vec<usize>> {
    let mut live: Vec<Vec<usize>> = vec![Vec::new(); n + 1];
    arena.paths.push(PathNode {
        word: context.map(str::to_string),
        score: 0.0,
        prev: None,
        edge: None,
        words: 0,
    });
    live[0].push(arena.paths.len() - 1);
    for p in 0..=n {
        if live[p].is_empty() {
            continue;
        }
        // One path per distinct last word: only it conditions what follows.
        // A buffer ending in a bare initial reaches a node by thousands of paths,
        // so this has to be a map: a scan of `best` took 425 ms on `yizh`.
        let mut best: Vec<usize> = Vec::new();
        let mut slot: HashMap<Option<&str>, usize> = HashMap::with_capacity(live[p].len());
        for &path in &live[p] {
            let w = arena.paths[path].word.as_deref();
            match slot.get(&w) {
                Some(&k) => {
                    if arena.paths[path].score > arena.paths[best[k]].score {
                        best[k] = path;
                    }
                }
                None => {
                    slot.insert(w, best.len());
                    best.push(path);
                }
            }
        }
        drop(slot);
        best.sort_by(|&a, &b| arena.paths[b].score.total_cmp(&arena.paths[a].score));
        best.truncate(opts.beam);
        live[p] = best.clone();
        if p == n {
            break;
        }
        for path in best {
            for &e in &by_node[p] {
                let edge = &arena.edges[e];
                let score = arena.paths[path].score
                    + sc.lm(arena.paths[path].word.as_deref(), edge)
                    + edge.emission;
                let to = edge.to;
                let words = arena.paths[path].words + 1;
                arena.paths.push(PathNode {
                    word: Some(edge.text.clone()),
                    score,
                    prev: Some(path),
                    edge: Some(e),
                    words,
                });
                live[to].push(arena.paths.len() - 1);
            }
        }
    }
    live
}

fn suffix_scores(by_node: &[Vec<usize>], arena: &Arena, sc: &Scorer, n: usize) -> Vec<f64> {
    let mut best = vec![f64::NEG_INFINITY; n + 1];
    best[n] = 0.0;
    for p in (0..n).rev() {
        for &e in &by_node[p] {
            let edge = &arena.edges[e];
            let rest = best[edge.to];
            if rest == f64::NEG_INFINITY {
                continue;
            }
            let score = sc.lm(None, edge) + edge.emission + rest;
            if score > best[p] {
                best[p] = score;
            }
        }
    }
    best
}

/// Whole-buffer paths of two or more words, keyed by their text so the list
/// stays diverse, searched with the stitch bonus per word.
fn stitched_paths(
    by_node: &[Vec<usize>],
    arena: &mut Arena,
    sc: &Scorer,
    opts: &Opts,
    n: usize,
    context: Option<&str>,
) -> Vec<usize> {
    let mut live: Vec<Vec<usize>> = vec![Vec::new(); n + 1];
    arena.paths.push(PathNode {
        word: context.map(str::to_string),
        score: 0.0,
        prev: None,
        edge: None,
        words: 0,
    });
    live[0].push(arena.paths.len() - 1);
    for p in 0..=n {
        if live[p].is_empty() {
            continue;
        }
        let mut pruned: Vec<usize> = Vec::new();
        let mut slot: HashMap<String, usize> = HashMap::with_capacity(live[p].len());
        for &path in &live[p] {
            let key = arena.text_of(path);
            match slot.get(&key) {
                Some(&k) => {
                    if arena.paths[path].score > arena.paths[pruned[k]].score {
                        pruned[k] = path;
                    }
                }
                None => {
                    slot.insert(key, pruned.len());
                    pruned.push(path);
                }
            }
        }
        pruned.sort_by(|&a, &b| arena.paths[b].score.total_cmp(&arena.paths[a].score));
        pruned.truncate(opts.beam);
        live[p] = pruned.clone();
        if p == n {
            break;
        }
        for path in pruned {
            for &e in &by_node[p] {
                if arena.edges[e].fallback {
                    continue;
                }
                let edge = &arena.edges[e];
                let score = arena.paths[path].score
                    + sc.lm(arena.paths[path].word.as_deref(), edge)
                    + edge.emission
                    + opts.stitch_bonus;
                let to = edge.to;
                let words = arena.paths[path].words + 1;
                arena.paths.push(PathNode {
                    word: Some(edge.text.clone()),
                    score,
                    prev: Some(path),
                    edge: Some(e),
                    words,
                });
                live[to].push(arena.paths.len() - 1);
            }
        }
    }
    let mut ends = live[n].clone();
    ends.sort_by(|&a, &b| arena.paths[b].score.total_cmp(&arena.paths[a].score));
    ends.into_iter()
        .filter(|&e| arena.path_of(e).len() >= 2)
        .take(opts.stitch)
        .collect()
}

fn push_unique(out: &mut Vec<Cand>, c: Cand) -> bool {
    if out.iter().any(|o| o.text == c.text) {
        return false;
    }
    out.push(c);
    true
}

/// Stitched whole-buffer readings, unless one word already covers the buffer.
fn stitched(
    graph: &Graph,
    by_node: &[Vec<usize>],
    arena: &mut Arena,
    sc: &Scorer,
    opts: &Opts,
    context: Option<&str>,
) -> Vec<Cand> {
    let n = graph.end();
    // A row whose word is its own reading converts nothing: ja_kana files
    // またあした under またあした, and that must not keep また明日 off the strip.
    let whole = by_node[0].iter().any(|&e| {
        let edge = &arena.edges[e];
        !edge.fallback && edge.to == n && edge.text != edge.reading
    });
    if opts.stitch == 0 || whole {
        return Vec::new();
    }
    stitched_paths(by_node, arena, sc, opts, n, context)
        .into_iter()
        .map(|end| {
            let path = arena.path_of(end);
            Cand {
                text: path.iter().map(|e| e.text.as_str()).collect(),
                consumed: graph.consumed[n],
                score: path.iter().map(|e| e.emission).sum(),
                reading: path.iter().map(|e| e.reading.as_str()).collect(),
                corrected: path.iter().any(|e| e.correction),
            }
        })
        .collect()
}

fn insert_all(out: &mut Vec<Cand>, cands: &[Cand], limit: usize) {
    for c in cands {
        if out.len() >= limit {
            break;
        }
        push_unique(out, c.clone());
    }
}

/// The first of several highest-scoring paths, as Kotlin's `maxByOrNull` picks.
fn best_path(paths: &[usize], arena: &Arena) -> Option<usize> {
    let mut best: Option<usize> = None;
    for &p in paths {
        if best.is_none_or(|b| arena.paths[p].score > arena.paths[b].score) {
            best = Some(p);
        }
    }
    best
}

fn path_cand(path: &[&Edge], consumed: usize, score: f64) -> Cand {
    Cand {
        text: path.iter().map(|e| e.text.as_str()).collect(),
        consumed,
        score,
        reading: path.iter().map(|e| e.reading.as_str()).collect(),
        corrected: path.iter().any(|e| e.correction),
    }
}

/// The original order: the best sentence, stitched readings, then first words.
fn by_first_word(
    graph: &Graph,
    by_node: &[Vec<usize>],
    live: &[Vec<usize>],
    arena: &mut Arena,
    sc: &Scorer,
    opts: &Opts,
    context: Option<&str>,
) -> Vec<Cand> {
    let n = graph.end();
    let suffix = suffix_scores(by_node, arena, sc, n);
    let mut out: Vec<Cand> = Vec::new();
    // Rank 0 is the sentence, only when every step of it is a real word.
    if let Some(best) = best_path(&live[n], arena) {
        let path = arena.path_of(best);
        if path.len() >= 2 && path.iter().all(|e| !e.fallback) {
            let score = path.iter().map(|e| e.emission).sum();
            out.push(path_cand(&path, graph.consumed[n], score));
        }
    }
    let stitches = stitched(graph, by_node, arena, sc, opts, context);
    insert_all(&mut out, &stitches, opts.limit);
    let mut firsts: Vec<(usize, f64)> = by_node[0]
        .iter()
        .filter(|&&e| !arena.edges[e].fallback)
        .map(|&e| {
            let edge = &arena.edges[e];
            (e, sc.lm(context, edge) + edge.emission + suffix[edge.to])
        })
        .collect();
    firsts.sort_by(|a, b| b.1.total_cmp(&a.1));
    for (e, score) in firsts {
        if out.len() >= opts.limit {
            break;
        }
        let edge = &arena.edges[e];
        push_unique(
            &mut out,
            Cand {
                text: edge.text.clone(),
                consumed: graph.consumed[edge.to],
                score,
                reading: edge.reading.clone(),
                corrected: edge.correction,
            },
        );
    }
    out
}

fn path_lm(path: &[&Edge], sc: &Scorer, context: Option<&str>) -> f64 {
    let mut prev: Option<&str> = context;
    let mut sum = 0.0;
    for e in path {
        sum += sc.lm(prev, e);
        prev = Some(&e.text);
    }
    sum
}

struct Coverage<'a, 'b> {
    graph: &'a Graph,
    live: &'a [Vec<usize>],
    arena: &'a Arena,
    sc: &'a Scorer<'a>,
    opts: &'a Opts<'b>,
    context: Option<&'a str>,
    ends: HashMap<usize, Vec<usize>>,
}

impl Coverage<'_, '_> {
    /// The best readings of the stretch ending at `node` into `out`, at most
    /// `cap` of them; `after_first` runs once the first is in (or none was).
    fn stretch(
        &self,
        node: usize,
        cap: usize,
        out: &mut Vec<Cand>,
        after_first: &mut dyn FnMut(&mut Vec<Cand>),
    ) {
        let (arena, sc, opts) = (self.arena, self.sc, self.opts);
        let consumed = self.graph.consumed[node];
        let mut found: Vec<Cand> = Vec::new();
        for &e in self.ends.get(&node).map(Vec::as_slice).unwrap_or(&[]) {
            let edge = &arena.edges[e];
            found.push(Cand {
                text: edge.text.clone(),
                consumed,
                score: sc.lm(self.context, edge) + edge.emission,
                reading: edge.reading.clone(),
                corrected: edge.correction,
            });
        }
        let mut multi: Vec<usize> = self.live[node]
            .iter()
            .copied()
            .filter(|&p| arena.paths[p].words >= 2)
            .collect();
        multi.sort_by(|&a, &b| arena.paths[b].score.total_cmp(&arena.paths[a].score));
        let paths: Vec<Cand> = multi
            .into_iter()
            .map(|p| arena.path_of(p))
            .filter(|path| path.iter().all(|e| !e.fallback))
            .take(opts.paths_per_stretch)
            .map(|path| {
                let score =
                    path.iter().map(|e| e.emission).sum::<f64>() + path_lm(&path, sc, self.context);
                path_cand(&path, consumed, score)
            })
            .collect();
        // A path has to beat every single word for the stretch.
        let best = found
            .iter()
            .chain(&paths)
            .map(|c| c.score)
            .fold(f64::NEG_INFINITY, f64::max);
        let best_word = found
            .iter()
            .map(|c| c.score)
            .fold(f64::NEG_INFINITY, f64::max);
        let floor = best_word.max(best - opts.path_margin);
        found.extend(paths.into_iter().filter(|c| c.score >= floor));
        // A repaired key has to read nearly as well as what was typed.
        let clean = found
            .iter()
            .filter(|c| !c.corrected)
            .map(|c| c.score)
            .fold(f64::NEG_INFINITY, f64::max);
        if clean > f64::NEG_INFINITY {
            found.retain(|c| !(c.corrected && c.score < clean - opts.correction_margin));
        }
        found.sort_by(|a, b| b.score.total_cmp(&a.score));
        let mut added = 0;
        for c in found {
            if out.len() >= opts.limit || added >= cap {
                break;
            }
            if push_unique(out, c) {
                added += 1;
                if added == 1 {
                    after_first(out);
                }
            }
        }
        if added == 0 {
            after_first(out);
        }
    }
}

/// Every reading of the whole buffer, then each shorter stretch from the
/// start, longest first: `wbxh` offers 我不喜欢, then 我不想, 我不, 我.
fn by_coverage(
    graph: &Graph,
    by_node: &[Vec<usize>],
    live: &[Vec<usize>],
    arena: &mut Arena,
    sc: &Scorer,
    opts: &Opts,
    context: Option<&str>,
) -> Vec<Cand> {
    let n = graph.end();
    let stitches = stitched(graph, by_node, arena, sc, opts, context);
    let mut ends: HashMap<usize, Vec<usize>> = HashMap::new();
    for &e in &by_node[0] {
        if !arena.edges[e].fallback {
            ends.entry(arena.edges[e].to).or_default().push(e);
        }
    }
    let cov = Coverage {
        graph,
        live,
        arena,
        sc,
        opts,
        context,
        ends,
    };
    let mut out: Vec<Cand> = Vec::new();
    // The stitched readings go straight after the best one.
    cov.stretch(n, usize::MAX, &mut out, &mut |out| {
        insert_all(out, &stitches, opts.limit)
    });
    let mut shorter: Vec<usize> = (1..n)
        .filter(|&node| {
            cov.ends.contains_key(&node) || live[node].iter().any(|&p| arena.paths[p].words >= 2)
        })
        .collect();
    shorter.sort_by(|a, b| graph.consumed[*b].cmp(&graph.consumed[*a]));
    let last = shorter.len().saturating_sub(1);
    for (i, node) in shorter.into_iter().enumerate() {
        if out.len() >= opts.limit {
            break;
        }
        let cap = if i == last {
            usize::MAX
        } else {
            opts.stretch_cap
        };
        cov.stretch(node, cap, &mut out, &mut |_| {});
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn none(_: &str) -> Option<&'static HashMap<String, i32>> {
        None
    }

    #[test]
    fn decodes_a_sentence_and_its_first_words() {
        let dict = ConversionDictionary::parse("ni\t你\t900\nni\t尼\t100\nhao\t好\t800\nhao\t号\t300\nnihao\t你好\t700\nxian\t先\t500\nxian\t现\t600\nxian\t西安\t400\nxi\t西\t300\nan\t安\t300\n");
        let ng = CjkNgrams::default();
        let no = |_: &str| false;
        let user = UserWords {
            has_prefix: &no,
            pairs: None,
        };
        let input = Input::options(vec![vec!["ni".into()], vec!["hao".into()]], &[2, 3]);
        let out = decode_input(
            &input,
            &dict,
            &ng,
            &Opts::default(),
            &none,
            None,
            None,
            &user,
        );
        assert_eq!("你好", out[0].text);
        assert_eq!(5, out[0].consumed);
        assert!(out.iter().any(|c| c.text == "你" && c.consumed == 2));
        // `xi'an` spelled out as two units: one character cannot cover both.
        let two = Input::options(vec![vec!["xi".into()], vec!["an".into()]], &[2, 3]);
        let out = decode_input(
            &two,
            &dict,
            &ng,
            &Opts {
                coverage_order: true,
                ..Opts::default()
            },
            &none,
            None,
            None,
            &user,
        );
        assert_eq!("西安", out[0].text);
        assert!(!out.iter().any(|c| c.text == "现"));
        // A blocked word is not a word.
        let mut adj = WordAdjust::default();
        adj.blocked.insert("你".into());
        let out = decode_input(
            &input,
            &dict,
            &ng,
            &Opts::default(),
            &none,
            Some(&adj),
            None,
            &user,
        );
        assert!(!out.iter().any(|c| c.text == "你"));
        assert!(out.iter().any(|c| c.text == "尼"));
    }
}
