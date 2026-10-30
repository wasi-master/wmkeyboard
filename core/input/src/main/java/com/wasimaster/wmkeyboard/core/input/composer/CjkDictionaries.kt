package com.wasimaster.wmkeyboard.core.input.composer

/**
 * Holds the conversion tables the Chinese and Japanese composers read. They are
 * loaded from shipped TSV assets off the main thread by the service (the same
 * place it loads its word lists) and swapped in here; until then both are
 * [ConversionDictionary.EMPTY], so the composers still type — Pinyin commits the
 * raw pinyin and Japanese commits the kana — they just offer no character
 * candidates.
 */
object CjkDictionaries {

    /**
     * Bumped whenever anything a composer's ranking depends on changes — a table
     * swapped in below, a [CjkConfig] toggle, a learned pick. The composers cache
     * their last decode against it, so this is what keeps a stale answer from
     * outliving the tables it came from.
     */
    @Volatile
    var generation: Int = 0
        private set

    /** Invalidate every composer's cached ranking. */
    fun invalidate() {
        generation++
    }

    // Each table invalidates on assignment rather than trusting its caller to
    // remember: the tables are swapped in from a background load, from settings,
    // and from tests, and a cache serving answers from a table that is no longer
    // there is silent and very hard to spot.

    @Volatile
    var pinyin: ConversionDictionary = ConversionDictionary.EMPTY
        set(value) { field = value; invalidate() }

    @Volatile
    var japanese: ConversionDictionary = ConversionDictionary.EMPTY
        set(value) { field = value; invalidate() }

    /** Stroke-order → Hanzi table for the 笔画 input method (downloadable pack). */
    @Volatile
    var stroke: CodeTableDictionary = CodeTableDictionary.EMPTY
        set(value) { field = value; invalidate() }

    /** Radical-code → Hanzi table shared by Cangjie 倉頡 and Quick 速成. */
    @Volatile
    var cangjie: CodeTableDictionary = CodeTableDictionary.EMPTY
        set(value) { field = value; invalidate() }

    /** Cantonese Jyutping reading → Hanzi; a table of its own, not shared with pinyin. */
    @Volatile
    var jyutping: ConversionDictionary = ConversionDictionary.EMPTY
        set(value) { field = value; invalidate() }

    /** Hangul reading → Hanja, which [HanjaConversion] reads for the Korean boards. */
    @Volatile
    var hanja: ConversionDictionary = ConversionDictionary.EMPTY
        set(value) { field = value; invalidate() }

    /**
     * Word n-grams for the decoders that have no pack of their own (Japanese,
     * Cantonese, imported methods). [CjkNgrams.EMPTY]: none ships yet.
     */
    @Volatile
    var ngrams: CjkNgrams = CjkNgrams.EMPTY
        set(value) { field = value; invalidate() }

    /**
     * Mandarin word pairs, for everything that converts through [pinyin] —
     * full and T9 pinyin and Zhuyin (#580). Its own field because the words
     * are Simplified Chinese ones: Japanese kanji or Cantonese wording scored
     * by them would be scored by the wrong language.
     */
    @Volatile
    var pinyinNgrams: CjkNgrams = CjkNgrams.EMPTY
        set(value) { field = value; invalidate() }

    // What each composer answers [Composer.missingPack] with: the
    // [CjkDictCatalog] id of the pack behind its table while that table is
    // still empty, else null. Here rather than in the composers so a pack id
    // is spelled once, beside the table it fills — several composers share one
    // table (pinyin serves full pinyin, T9 and Zhuyin; cangjie serves Cangjie
    // and Quick), and a typo in one of them would have gone unnoticed.

    /** Pinyin, T9 pinyin and Zhuyin all convert through [pinyin]. */
    val missingPinyin: String? get() = "pinyin".takeIf { pinyin.isEmpty }

    /**
     * The word-pair pack, while the pinyin table it improves is loaded and it
     * is not (#580): for someone who fetched Pinyin before the pairs existed.
     * Pinyin converts without it, so this is an offer, never a [missingPinyin].
     */
    val missingPinyinNgrams: String? get() = "pinyin_ngrams".takeIf { !pinyin.isEmpty && pinyinNgrams.isEmpty }

    val missingJapanese: String? get() = "ja_kana".takeIf { japanese.isEmpty }

    val missingStroke: String? get() = "stroke".takeIf { stroke.isEmpty }

    /** Cangjie 倉頡 and Quick 速成 share [cangjie]. */
    val missingCangjie: String? get() = "cangjie".takeIf { cangjie.isEmpty }

    val missingJyutping: String? get() = "jyutping".takeIf { jyutping.isEmpty }
}
