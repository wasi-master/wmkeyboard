package com.wasimaster.wmkeyboard.core.transliteration

/**
 * Assamese phonetic: roman letters in, Assamese script out, on the Avro
 * engine with three fix-ups of Assamese's own (ৰ for র, "x" as স, ৱ for ওয়
 * inside a word), all in the Rust engine (`native/wm-engine/src/assamese.rs`);
 * this is the call into it.
 */
object AssamesePhonetic {

    /** Transliterates roman Assamese, one word or free text, into the Assamese script. */
    fun transliterate(input: String): String = EngineNative.assamese(input)

    /**
     * Assamese text in the Bangla letters Avro and the Bengali index read: ৰ as
     * র and ৱ as ব. The way into [BengaliPhoneticIndex] and [BengaliRomanizer].
     * Two characters, done here rather than across the bridge, because the
     * index calls it once per word of a 300k-word list.
     */
    fun toBengali(text: String): String = text.replace('ৰ', 'র').replace('ৱ', 'ব')

    /** An Assamese letter or sign: the Bengali block, which holds ৰ and ৱ too. */
    fun isAssamese(c: Char): Boolean = BengaliGraphemes.isBengali(c)
}

/**
 * Reverse-phonetic lookup for Assamese: [BengaliPhoneticIndex] over the list
 * folded in Bangla letters ([AssamesePhonetic.toBengali]), its rows keeping
 * the list's own Assamese spelling, so a lookup answers in it directly.
 */
class AssamesePhoneticIndex(private val table: PhoneticTable) : PhoneticIndex {

    /** An index built on the device over [entries], `word to frequency`. */
    constructor(entries: List<Pair<String, Int>>) : this(table(entries))

    private val inner = BengaliPhoneticIndex(table)

    override val isEmpty: Boolean get() = inner.isEmpty

    /** The list's words in order, for [completions]; null with no list. */
    override val sortedWords: SortedWords? get() = if (inner.isEmpty) null else inner.sortedWords


    override fun lookup(input: String): List<String> = inner.lookup(input.replace('x', 's')).distinct()

    override fun frequencyOf(word: String): Int = table.frequencyOf(word)

    override fun matchStrength(input: String): Int = inner.matchStrength(input.replace('x', 's'))

    override val maxFrequency: Int get() = inner.maxFrequency

    companion object {
        /** Files [entries] into [builder] by the Bengali fold, read in Bangla letters. */
        fun file(builder: PhoneticTableBuilder, entries: List<Pair<String, Int>>) =
            BengaliPhoneticIndex.file(builder, boundedEntries(entries), AssamesePhonetic::toBengali)

        fun table(entries: List<Pair<String, Int>>): PhoneticTable =
            BengaliPhoneticIndex.table(boundedEntries(entries), AssamesePhonetic::toBengali)
    }
}
