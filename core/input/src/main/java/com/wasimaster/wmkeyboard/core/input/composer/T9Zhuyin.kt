package com.wasimaster.wmkeyboard.core.input.composer

/**
 * 12-key Zhuyin: the keypad code of every bopomofo syllable, on Samsung's ten
 * groups. The same derive-don't-ship trick as [T9Pinyin], over the table
 * [ZhuyinSyllables] already keeps: each syllable is encoded once, and the digits
 * typed are then segmented against the codes. No keystroke is ever expanded into
 * its symbols.
 */
object T9Zhuyin {

    private val DIGIT: Map<Char, Char> = buildMap {
        fun group(digit: Char, symbols: String) { for (c in symbols) put(c, digit) }
        group('1', "ㄅㄉㄚ"); group('2', "ㄍㄐㄞㄧ"); group('3', "ㄓㄗㄢㄦ")
        group('4', "ㄆㄊㄛ"); group('5', "ㄎㄑㄟㄨ"); group('6', "ㄔㄘㄣ")
        group('7', "ㄇㄋㄜㄝ"); group('8', "ㄏㄒㄠㄩ"); group('9', "ㄕㄙㄤㄥ")
        group('0', "ㄈㄌㄡㄖ")
    }

    /** The digits that spell [bopomofo], or an empty string for a symbol no key has. */
    fun encode(bopomofo: String): String {
        val out = StringBuilder(bopomofo.length)
        for (c in bopomofo) out.append(DIGIT[c] ?: return "")
        return out.toString()
    }

    /** Digits → the toneless pinyin of each syllable they spell, from a bopomofo → pinyin [table]. */
    fun buildIndex(table: Map<String, String>): Map<String, List<String>> {
        val acc = HashMap<String, MutableList<String>>()
        for ((bopomofo, pinyin) in table) {
            val code = encode(bopomofo)
            if (code.isNotEmpty()) acc.getOrPut(code) { mutableListOf() }.add(pinyin)
        }
        return acc.mapValues { (_, v) -> v.distinct().sorted() }
    }
}
