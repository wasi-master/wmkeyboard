package com.wasimaster.wmkeyboard.core.input.composer

import java.text.Normalizer

/**
 * Japanese input: romaji keystrokes compose to hiragana ([composeBuffer]), and
 * the hiragana reading offers kanji/word candidates in the strip
 * ([isConversion] + [candidates]), chosen from the shipped kana→kanji table.
 * With no dictionary match the reading still commits as kana, so it works as a
 * plain kana keyboard on its own.
 *
 * Like [PinyinComposer], this is a *prefix-commit* IME: the kana buffer is split
 * at mora boundaries so a multi-mora reading like `nihon` offers both the whole
 * 日本 and the leading 日/... , each remembering how many *romaji input* chars it
 * consumed ([consumedFor]). The service commits that prefix and re-converts the
 * tail. The catch is that segmentation happens on **kana** while `consumedFor`
 * must report **romaji** length, so the lookup runs over [Kana.transduce], which
 * tracks the romaji span behind every emitted kana unit.
 */
object JapaneseComposer : Composer {

    override val isTransliterating: Boolean get() = true
    override val isConversion: Boolean get() = true
    override val keepsUnconvertedReading: Boolean get() = true
    override val missingPack: String? get() = CjkDictionaries.missingJapanese
    override val readsLatinLetters: Boolean get() = true
    override val glidesKanaReadings: Boolean get() = true

    /**
     * The ゛ and ゜ keys of the JIS kana board: a swipe may pass through them
     * after a kana to draw its mark (#958, [KanaGlide.drawn]).
     */
    override val glideKeys: Set<Int> = setOf(KanaGlide.DAKUTEN.code, KanaGlide.HANDAKUTEN.code)

    /** Reading space for learned picks: kana readings. */
    private const val NAMESPACE = "ja_kana"

    private const val LIMIT = 12

    /** Ranked depth for resolving a tap, so the expanded grid can be tapped too. */
    private const val LOOKUP_LIMIT = 128

    /** Mora count past which the lattice gives way; nobody types this far uncommitted. */
    private const val MAX_LATTICE_UNITS = 24

    /** Multi-word whole-buffer readings offered where no word converts the whole buffer. */
    private const val STITCH = 2

    /**
     * Log-penalty for each kana read with a mark the user did not type.
     *
     * Far smaller than the ln(0.15) a Fuzzy Pinyin guess pays, for two reasons.
     * Leaving marks off is how these layouts get typed, not a slip, so a guess
     * here is much likelier to be right. And `ja_kana` carries `30000 - cost`
     * from Mozc rather than a count, which is already a logarithm: 学校 and the
     * given name 葛洪 sit at 26742 and 23287, 0.14 apart once the lattice takes
     * its own log. Measured against the real pack, ln(0.15) left かつこう
     * answering 葛洪, 勝幸 and buried 学校 off the strip; at this weight it leads
     * with 学校, しゆきよう with 授業, こはん with ご飯, while かき still opens 書き,
     * 描き, 下記 before 鍵 arrives. So: an exact reading wins a near tie and a
     * clearly commoner marked word wins otherwise. A pack rebuilt on real counts
     * needs this re-measured.
     */
    private const val LOOSE_PENALTY = -0.035

    override fun composeBuffer(buffer: String): String = Kana.toHiragana(buffer)

    /**
     * Read off the transduction of the whole buffer rather than of the keys
     * before the caret: `kan` alone is かん, but in `kana` its `n` starts な.
     * A caret inside a kana's keys counts that kana as after it.
     */
    override fun previewOffset(buffer: String, index: Int): Int {
        var keys = 0
        var kana = 0
        for (span in Kana.transduce(buffer)) {
            if (keys + span.romajiLen > index) break
            keys += span.romajiLen
            kana += span.kana.length
        }
        return kana
    }

    /** A caret inside a kana goes in front of it: its keys stay together. */
    override fun bufferIndex(buffer: String, offset: Int): Int {
        var keys = 0
        var kana = 0
        for (span in Kana.transduce(buffer)) {
            if (kana + span.kana.length > offset) break
            keys += span.romajiLen
            kana += span.kana.length
        }
        return keys
    }

    /**
     * Japanese punctuation typed inside a reading joins it (#960), the way a
     * kana IME keeps 、 and 。 in the composition: the conversion stays open
     * over the whole sentence instead of ending at the first mark, and the
     * mark goes in with whatever the reading becomes. Between readings it is
     * ordinary punctuation. The hyphen is the long-vowel mark, as in every
     * romaji IME: `ka-do` is かーど and converts to カード (#596).
     */
    override fun buffersChar(c: Char, composing: CharSequence): Boolean =
        composing.isNotEmpty() && (c == '-' || c in READING_PUNCTUATION)

    /**
     * Marks that join a reading: the full-width ones the Japanese layouts
     * type. Not brackets, which open and close pairs of their own.
     */
    private const val READING_PUNCTUATION = "、。，．！？・…ー〜"


    override fun candidates(buffer: String): List<String> = candidates(buffer, LIMIT)

    override fun candidates(buffer: String, limit: Int): List<String> =
        ranked(buffer).take(limit).map { it.text }

    override fun consumedFor(buffer: String, chosen: String): Int =
        ranked(buffer).firstOrNull { it.text == chosen }?.consumed ?: buffer.length

    /**
     * Resolving by position matters more here than anywhere else: `ja_kana` lists
     * 行 under い, いき, ゆき, こう and ぎょう, so for `ikitai` the same string is a
     * candidate at a one-mora span and a two-mora one. By text, the first wins and
     * silently eats a mora the user never chose.
     */
    override fun consumedForIndex(buffer: String, index: Int): Int =
        ranked(buffer).getOrNull(index)?.consumed ?: buffer.length

    override fun learnChoice(buffer: String, index: Int) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.learn(NAMESPACE, cand.reading, cand.text)
    }

    override val adjustsChoices: Boolean get() = true

    override fun adjustChoice(buffer: String, index: Int, adjustment: CandidateAdjustment) {
        val cand = ranked(buffer).getOrNull(index) ?: return
        CjkLearning.adjust(NAMESPACE, cand.reading, cand.text, cand.text, adjustment)
    }


    /** A candidate word, the romaji input chars it covers, and its kana reading. */
    private data class Cand(val text: String, val consumed: Int, val reading: String)

    /**
     * Candidates for [buffer], longest kana reading first so a whole-phrase entry
     * (日本) outranks its leading-mora pieces (日), each tagged with its consumed
     * *romaji* length. The plain hiragana, katakana and half-width katakana forms
     * of the whole reading follow as always-available trailing choices, each
     * consuming the entire buffer.
     */
    private fun ranked(buffer: String): List<Cand> {
        if (buffer.isEmpty()) return emptyList()
        return cache.get(buffer) { rank(buffer) }
    }

    private fun rank(buffer: String): List<Cand> {
        val spans = Kana.transduce(buffer)
        if (spans.isEmpty()) return emptyList()
        val whole = spans.joinToString("") { it.kana }
        val romaji = spans.sumOf { it.romajiLen }
        val out = LinkedHashMap<String, Cand>()
        if (spans.size <= MAX_LATTICE_UNITS) {
            // One unit per transducer step, so a consumed *kana* prefix maps back
            // to consumed *romaji* by the spans the transducer already tracked.
            // charPerUnit is off: a kanji takes however many mora it takes, so the
            // one-character-per-unit rule that sorts Chinese out is simply false.
            val input = latticeInput(buffer, spans)
            // A missing mark can sit on any kana of a word, not just its first
            // two, and leaving it off is how the pad is typed rather than a slip
            // — so the guess cap that suits Fuzzy Pinyin is lifted, and the
            // dictionary prune bounds the cost the way it does for T9.
            val loose = input.options.any { it.size > 1 }
            val decoded = Lattice.decode(
                input,
                CjkDictionaries.japanese,
                CjkDictionaries.ngrams,
                Lattice.Opts(
                    limit = LOOKUP_LIMIT,
                    charPerUnit = false,
                    // Phrases the pack spells only in kana (またあした) still
                    // need their kanji reading (また明日) offered.
                    stitch = STITCH,
                    maxAmbiguousSpan = if (loose) Int.MAX_VALUE else 2,
                ),
                user = CjkLearning.userData(NAMESPACE),
            )
            for (cand in decoded) out.getOrPut(cand.text) { Cand(cand.text, cand.consumed, cand.reading) }
        }
        // Trailing plain-kana choices for the whole buffer, always available so
        // this works as a kana keyboard with no conversion pack at all.
        out.getOrPut(whole) { Cand(whole, romaji, whole) }
        val kata = Kana.toKatakana(whole)
        if (kata != whole) out.getOrPut(kata) { Cand(kata, romaji, whole) }
        val half = Kana.toHalfWidthKatakana(kata)
        if (half != kata) out.getOrPut(half) { Cand(half, romaji, whole) }
        return CjkLearning.rank(NAMESPACE, out.values.toList(), { it.text }, { it.reading })
        // Stretch (not shipped): light okurigana / verb-conjugation would fold a
        // trailing kana inflection (…って, …した) back onto a stem reading before
        // dict lookup here, so 食べた converts from `tabeta`. Needs a conjugation
        // table + stem index in the pack.
    }

    /**
     * The lattice input for [spans]: one unit per transducer step, and — with
     * [CjkConfig.looseKanaMarks] on — each plain kana also offered as the forms
     * the 小゛゜ key would have made of it (か as が, つ as っ or づ, や as ゃ),
     * behind [LOOSE_PENALTY], so かつこう still finds 学校 under がっこう.
     *
     * Only a kana the user typed *as kana* is widened. On the flick pad and the
     * JIS kana layout a mark is an extra key, which is what makes skipping it
     * worth anything; in romaji `ga` costs exactly what `ka` does, so reading `ka`
     * as が would only crowd the strip. An identity span is how the two are told
     * apart: kana passes through [Kana.transduce] unchanged, one char for one.
     *
     * And only in that direction. A が or a っ was asked for by name, and is
     * never read back as か or つ.
     */
    private fun latticeInput(buffer: String, spans: List<Kana.KanaSpan>): Lattice.Input {
        val readings = spans.map { it.kana }
        val inputLens = spans.map { it.romajiLen }
        if (!CjkConfig.looseKanaMarks) return Lattice.input(readings, inputLens)
        val options = ArrayList<Array<String>>(spans.size)
        var pos = 0
        for (span in spans) {
            val typedAsKana = span.romajiLen == 1 && span.kana.length == 1 && buffer[pos] == span.kana[0]
            val marked = if (typedAsKana) Kana.markedForms(span.kana[0]) else ""
            options.add(Array(1 + marked.length) { o -> if (o == 0) span.kana else marked[o - 1].toString() })
            pos += span.romajiLen
        }
        val input = Lattice.options(options, inputLens)
        for (unit in input.penalties) for (o in 1 until unit.size) unit[o] = LOOSE_PENALTY
        return input
    }

    private val cache = RankCache<List<Cand>>()
}

/** Romaji↔kana transliteration (Hepburn/wāpuro), longest-match. */
object Kana {

    /** One transduction step: the kana it produced and the romaji chars it ate. */
    data class KanaSpan(val kana: String, val romajiLen: Int)

    /**
     * Transduces [romaji] into kana units, each remembering how many romaji input
     * chars it consumed. This is the single source of truth: [toHiragana] just
     * concatenates the kana, and the composer maps a consumed *kana* prefix back
     * to consumed *romaji* by summing the spans' [KanaSpan.romajiLen]. The sum of
     * all spans' lengths always equals `romaji.length` — every input char is
     * consumed exactly once — so a whole-buffer choice consumes the whole buffer.
     */
    fun transduce(romaji: String): List<KanaSpan> {
        val s = romaji.lowercase()
        val out = ArrayList<KanaSpan>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            // Sokuon: a doubled consonant (kk, tt, ss, …, but not n or a vowel)
            // becomes っ before the next syllable, consuming the first consonant.
            if (i + 1 < s.length && c == s[i + 1] && c !in "aeioun" && c in 'a'..'z') {
                out.add(KanaSpan("っ", 1)); i++; continue
            }
            // Syllabic ん. `n'` is an explicit ん. For `nn`: if a vowel/y follows
            // the second n it starts a な-row syllable (`onna`→おんな, `konnichi`→
            // こんにち), so only the first n is consumed here; otherwise both n's
            // fold to a single ん (`onn`→おん). A lone n before a consonant or the
            // word end is also ん; before a vowel/y it falls through to the table.
            if (c == 'n') {
                if (i + 1 < s.length && s[i + 1] == '\'') { out.add(KanaSpan("ん", 2)); i += 2; continue }
                if (i + 1 < s.length && s[i + 1] == 'n') {
                    val after = s.getOrNull(i + 2)
                    if (after != null && after in "aeiouy") { out.add(KanaSpan("ん", 1)); i++; continue }
                    out.add(KanaSpan("ん", 2)); i += 2; continue
                }
                val nxt = s.getOrNull(i + 1)
                if (nxt == null || nxt !in "aeiouy") { out.add(KanaSpan("ん", 1)); i++; continue }
            }
            var matched = false
            for (len in 3 downTo 1) {
                if (i + len <= s.length) {
                    val v = TABLE[s.substring(i, i + len)]
                    if (v != null) { out.add(KanaSpan(v, len)); i += len; matched = true; break }
                }
            }
            if (!matched) { out.add(KanaSpan(s[i].toString(), 1)); i++ }
        }
        return out
    }

    fun toHiragana(romaji: String): String = buildString {
        for (u in transduce(romaji)) append(u.kana)
    }

    /**
     * The next form of a kana in the flick pad's 小゛゜ cycle: base → small /
     * dakuten / handakuten → base, following the ring the kana sits in
     * (か→が→か, は→ば→ぱ→は, つ→っ→づ→つ, う→ぅ→ゔ→う). Katakana cycles the same
     * rings (エ→ェ→エ, ハ→バ→パ→ハ), because a layout can type kana straight in
     * either script. A kana with no variant (な, ん, …) maps to itself, so
     * cycling it is a no-op.
     */
    fun cycleVariant(kana: Char): Char = VARIANT_NEXT[kana] ?: kana

    /**
     * The small, dakuten and handakuten forms of a plain kana — everything the
     * 小゛゜ key would cycle it through (か→"が", は→"ばぱ", つ→"っづ") — or empty
     * when [kana] has none or already carries a mark of its own.
     */
    fun markedForms(kana: Char): String = MARKED_FORMS[kana].orEmpty()

    /** Hiragana → katakana (the two blocks differ by a fixed 0x60 offset). */
    fun toKatakana(hiragana: String): String = buildString {
        for (c in hiragana) {
            if (c.code in 0x3041..0x3096) append((c.code + 0x60).toChar()) else append(c)
        }
    }

    /**
     * (Full-width) katakana → half-width katakana (半角カナ). Dakuten/handakuten
     * forms have no single half-width code point, so they decompose via NFD to
     * base + combining mark first (ガ → カ゛ → ｶﾞ, ヴ → ウ゛ → ｳﾞ); anything with
     * no half-width equivalent passes through unchanged.
     */
    fun toHalfWidthKatakana(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return buildString {
            for (c in nfd) when (c) {
                '゙' -> append('ﾞ') // combining dakuten → halfwidth ﾞ
                '゚' -> append('ﾟ') // combining handakuten → halfwidth ﾟ
                else -> append(HALF_KATAKANA[c] ?: c)
            }
        }
    }

    private val TABLE: Map<String, String> = buildMap {
        put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")
        put("ka", "か"); put("ki", "き"); put("ku", "く"); put("ke", "け"); put("ko", "こ")
        put("ga", "が"); put("gi", "ぎ"); put("gu", "ぐ"); put("ge", "げ"); put("go", "ご")
        put("sa", "さ"); put("shi", "し"); put("si", "し"); put("su", "す"); put("se", "せ"); put("so", "そ")
        put("za", "ざ"); put("ji", "じ"); put("zi", "じ"); put("zu", "ず"); put("ze", "ぜ"); put("zo", "ぞ")
        put("ta", "た"); put("chi", "ち"); put("ti", "ち"); put("tsu", "つ"); put("tu", "つ"); put("te", "て"); put("to", "と")
        put("da", "だ"); put("di", "ぢ"); put("du", "づ"); put("de", "で"); put("do", "ど")
        put("na", "な"); put("ni", "に"); put("nu", "ぬ"); put("ne", "ね"); put("no", "の")
        put("ha", "は"); put("hi", "ひ"); put("fu", "ふ"); put("hu", "ふ"); put("he", "へ"); put("ho", "ほ")
        put("ba", "ば"); put("bi", "び"); put("bu", "ぶ"); put("be", "べ"); put("bo", "ぼ")
        put("pa", "ぱ"); put("pi", "ぴ"); put("pu", "ぷ"); put("pe", "ぺ"); put("po", "ぽ")
        put("ma", "ま"); put("mi", "み"); put("mu", "む"); put("me", "め"); put("mo", "も")
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ")
        put("ra", "ら"); put("ri", "り"); put("ru", "る"); put("re", "れ"); put("ro", "ろ")
        put("wa", "わ"); put("wo", "を"); put("wi", "うぃ"); put("we", "うぇ")
        put("vu", "ゔ")
        // Yōon (palatalised)
        put("kya", "きゃ"); put("kyu", "きゅ"); put("kyo", "きょ")
        put("gya", "ぎゃ"); put("gyu", "ぎゅ"); put("gyo", "ぎょ")
        put("sha", "しゃ"); put("shu", "しゅ"); put("sho", "しょ")
        put("sya", "しゃ"); put("syu", "しゅ"); put("syo", "しょ")
        put("ja", "じゃ"); put("ju", "じゅ"); put("jo", "じょ")
        put("jya", "じゃ"); put("jyu", "じゅ"); put("jyo", "じょ")
        put("zya", "じゃ"); put("zyu", "じゅ"); put("zyo", "じょ")
        put("cha", "ちゃ"); put("chu", "ちゅ"); put("cho", "ちょ")
        put("tya", "ちゃ"); put("tyu", "ちゅ"); put("tyo", "ちょ")
        put("nya", "にゃ"); put("nyu", "にゅ"); put("nyo", "にょ")
        put("hya", "ひゃ"); put("hyu", "ひゅ"); put("hyo", "ひょ")
        put("bya", "びゃ"); put("byu", "びゅ"); put("byo", "びょ")
        put("pya", "ぴゃ"); put("pyu", "ぴゅ"); put("pyo", "ぴょ")
        put("mya", "みゃ"); put("myu", "みゅ"); put("myo", "みょ")
        put("rya", "りゃ"); put("ryu", "りゅ"); put("ryo", "りょ")
        // Foreign-sound kana
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("che", "ちぇ"); put("she", "しぇ"); put("je", "じぇ")
        // Small kana (l-/x- prefix)
        put("xa", "ぁ"); put("xi", "ぃ"); put("xu", "ぅ"); put("xe", "ぇ"); put("xo", "ぉ")
        put("la", "ぁ"); put("li", "ぃ"); put("lu", "ぅ"); put("le", "ぇ"); put("lo", "ぉ")
        put("xtu", "っ"); put("ltu", "っ"); put("xya", "ゃ"); put("xyu", "ゅ"); put("xyo", "ょ")
        // Long vowel + punctuation the composer may see
        put("-", "ー")
    }

    /**
     * Each string is a cycle ring for the flick pad's 小゛゜ key: char *i* advances
     * to char *i+1*, and the last wraps back to the first. Only kana with a small,
     * dakuten or handakuten form appear; everything else cycles to itself.
     *
     * Written in hiragana; [VARIANT_NEXT] derives the katakana rings from these.
     */
    private val VARIANT_CYCLES = listOf(
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ",
        "かが", "きぎ", "くぐ", "けげ", "こご",
        "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "やゃ", "ゆゅ", "よょ",
        "わゎ",
    )

    /**
     * Flattened [VARIANT_CYCLES]: kana → its next form in the ring, in **both**
     * scripts — every ring above has a katakana twin one 0x60 offset away, and a
     * layout is free to put katakana on its keys (issue #526), in which case the
     * kana reaches the buffer as katakana and the key has to cycle it there.
     */
    private val VARIANT_NEXT: Map<Char, Char> = buildMap {
        for (hiragana in VARIANT_CYCLES) {
            for (ring in listOf(hiragana, toKatakana(hiragana))) {
                for (i in ring.indices) put(ring[i], ring[(i + 1) % ring.length])
            }
        }
    }

    /**
     * [VARIANT_CYCLES] keyed by each ring's plain kana: か → "が", は → "ばぱ".
     *
     * Hiragana only, unlike [VARIANT_NEXT]: this one feeds the loose-mark lattice
     * lookup, and `ja_kana` files its readings in hiragana, so a katakana ring
     * would widen the lattice for nothing.
     */
    private val MARKED_FORMS: Map<Char, String> =
        VARIANT_CYCLES.associate { ring -> ring[0] to ring.substring(1) }

    /** Base (undakuten) full-width katakana → half-width; dakuten handled via NFD. */
    private val HALF_KATAKANA: Map<Char, Char> = buildMap {
        put('ア', 'ｱ'); put('イ', 'ｲ'); put('ウ', 'ｳ'); put('エ', 'ｴ'); put('オ', 'ｵ')
        put('カ', 'ｶ'); put('キ', 'ｷ'); put('ク', 'ｸ'); put('ケ', 'ｹ'); put('コ', 'ｺ')
        put('サ', 'ｻ'); put('シ', 'ｼ'); put('ス', 'ｽ'); put('セ', 'ｾ'); put('ソ', 'ｿ')
        put('タ', 'ﾀ'); put('チ', 'ﾁ'); put('ツ', 'ﾂ'); put('テ', 'ﾃ'); put('ト', 'ﾄ')
        put('ナ', 'ﾅ'); put('ニ', 'ﾆ'); put('ヌ', 'ﾇ'); put('ネ', 'ﾈ'); put('ノ', 'ﾉ')
        put('ハ', 'ﾊ'); put('ヒ', 'ﾋ'); put('フ', 'ﾌ'); put('ヘ', 'ﾍ'); put('ホ', 'ﾎ')
        put('マ', 'ﾏ'); put('ミ', 'ﾐ'); put('ム', 'ﾑ'); put('メ', 'ﾒ'); put('モ', 'ﾓ')
        put('ヤ', 'ﾔ'); put('ユ', 'ﾕ'); put('ヨ', 'ﾖ')
        put('ラ', 'ﾗ'); put('リ', 'ﾘ'); put('ル', 'ﾙ'); put('レ', 'ﾚ'); put('ロ', 'ﾛ')
        put('ワ', 'ﾜ'); put('ヲ', 'ｦ'); put('ン', 'ﾝ')
        // Small kana
        put('ァ', 'ｧ'); put('ィ', 'ｨ'); put('ゥ', 'ｩ'); put('ェ', 'ｪ'); put('ォ', 'ｫ')
        put('ッ', 'ｯ'); put('ャ', 'ｬ'); put('ュ', 'ｭ'); put('ョ', 'ｮ')
        // Punctuation
        put('ー', 'ｰ'); put('、', '､'); put('。', '｡'); put('・', '･')
    }
}
