package com.wasimaster.wmkeyboard.core.transliteration

import java.text.Normalizer

/**
 * Unicode to a legacy font whose glyph table is all it takes (#1252): one engine
 * for the many newspaper and DTP fonts of India's scripts, each a [Spec] of
 * [GlyphFontTables].
 *
 * Every syllable is put into the font's visual order first. A vowel sign the
 * font types before its letter (ि, ெ, െ …) goes in front of the cluster, or of
 * its last letter, as [Spec.preBase] says; a sign the table only has in parts
 * (ொ as ெ and ா) is taken apart. The reph goes where [Spec.reph] puts it;
 * Malayalam and some Telugu fonts draw ്ര in front of its letter
 * ([Spec.raSignFirst]); Telugu and Kannada fonts that draw a vowel sign on the
 * first letter and the subjoined letters after it get [Spec.signOnFirst]. Then
 * the longest sequence the table knows is replaced at each place, as in the
 * other encodings here. Digits the font has no glyph for are written as ASCII
 * digits, as these fonts' users type them, and a visarga it lacks as a colon.
 *
 * The tables are data from Padma (Nagarjuna Venna and others), the GPL'd
 * Firefox converter for these fonts, and from the Odia converters of Odia
 * Wikimedia; the options were picked per font by reading the output back with
 * those converters over thousands of words. See [GlyphFontTables].
 */
internal class GlyphTableEncoding(private val spec: Spec) {

    /** Where a vowel sign the font types first goes. */
    enum class PreBase { CLUSTER, LAST_LETTER, NONE }

    /** Where the reph goes, if the script has one: after the cluster, after its vowel signs, after the syllable, in front. */
    enum class Reph { NONE, AFTER_CLUSTER, AFTER_SIGNS, END, FRONT }

    /** Where the reph goes in a syllable with a vowel sign typed first, if not where [Reph] says. */
    enum class RephWithPreBase { SAME, BEFORE_CLUSTER, FRONT }

    class Spec(
        /** The first code point of the script's Unicode block. */
        val block: Int,
        /** Unicode to glyphs, as `key\u0001value` entries separated by `\u0000`. */
        val table: String,
        /** The Unicode pieces the font types before their letter, separated by `\u0000`. */
        val prefix: String,
        val preBase: PreBase,
        val reph: Reph,
        val rephWithPreBase: RephWithPreBase = RephWithPreBase.SAME,
        val signOnFirst: Boolean = false,
        val raSignFirst: Boolean = false,
        val nuktaFirst: Boolean = false,
    )

    private val glyphs: Map<String, String> = HashMap<String, String>().also { map ->
        for (entry in spec.table.split('\u0000')) {
            val cut = entry.indexOf('\u0001')
            if (cut > 0) map[entry.substring(0, cut)] = entry.substring(cut + 1)
        }
    }
    private val prefix: Set<String> = spec.prefix.split('\u0000').filter { it.isNotEmpty() }.toSet()
    private val longest = glyphs.keys.maxOf { it.length }

    /** A colon stands in for a missing visarga only where the font keeps nothing else on it. */
    private val colonFree = glyphs.values.none { ':' in it }

    private val b = spec.block
    private val malayalam = b == 0xD00
    private val virama = (b + 0x4D).toChar()
    private val nukta = (b + 0x3C).toChar()
    private val ra = (b + 0x30).toChar()
    private val hasReph = b in REPH_BLOCKS

    private fun isConsonant(c: Char): Boolean {
        val o = c.code - b
        return o in 0x15..0x39 || (!malayalam && o in 0x58..0x5F)
    }

    private fun isSign(c: Char): Boolean {
        val o = c.code - b
        return o in 0x3E..0x4C || o == 0x4E || o == 0x55 || o == 0x56 || o == 0x57 || o == 0x62 || o == 0x63 ||
            (b == 0xA00 && (o == 0x70 || o == 0x71 || o == 0x75))
    }

    private fun isModifier(c: Char): Boolean = (c.code - b) in 0x00..0x03

    fun convert(text: String): String {
        var w = Normalizer.normalize(text, Normalizer.Form.NFC)
        w = buildString(w.length) {
            for (c in w) {
                val o = c.code - b
                if (o in 0x66..0x6F && c.toString() !in glyphs) append('0' + (o - 0x66)) else append(c)
            }
        }
        val visarga = (b + 3).toChar()
        if (visarga.toString() !in glyphs && colonFree) w = w.replace(visarga, ':')
        if ("\u0972" !in glyphs) w = w.replace("\u0972", "\u0905\u0945")
        if (malayalam) {
            for ((atomic, letter) in CHILLUS) {
                if (atomic.toString() !in glyphs) w = w.replace(atomic.toString(), "$letter\u0D4D\u200D")
            }
            // The au length mark alone, today's spelling of ൌ, is written as the old ൌ.
            if ("\u0D57" !in glyphs && "\u0D4C" in glyphs) w = w.replace('\u0D57', '\u0D4C')
        }
        val visual = StringBuilder(w.length + 8)
        var i = 0
        val n = w.length
        while (i < n) {
            if (!isConsonant(w[i])) {
                visual.append(w[i])
                i++
                continue
            }
            var j = i + 1
            if (j < n && w[j] == nukta) j++
            while (j + 1 < n && w[j] == virama && isConsonant(w[j + 1])) {
                j += 2
                if (j < n && w[j] == nukta) j++
            }
            var k = j
            while (k < n && (isSign(w[k]) || w[k] == virama)) k++
            var m = k
            while (m < n && isModifier(w[m])) m++
            visual.append(syllable(w.substring(i, j), w.substring(j, k), w.substring(k, m)))
            i = m
        }
        return match(visual.toString())
    }

    private fun splitSign(v: String): List<String> {
        if (v in glyphs && (v !in prefix || spec.preBase == PreBase.NONE)) return listOf(v)
        EXTRA_SPLIT[v]?.let { parts -> if (v !in glyphs) return parts.map { it.toString() }.flatMap { splitSign(it) } }
        val d = Normalizer.normalize(v, Normalizer.Form.NFD)
        if (d.length > 1 && d != v) return d.map { it.toString() }.flatMap { splitSign(it) }
        return listOf(v)
    }

    private fun syllable(cluster: String, signs: String, mods: String): String {
        var cl = cluster
        var reph = ""
        if (spec.reph != Reph.NONE && hasReph && cl.length > 2 && cl[0] == ra && cl[1] == virama && isConsonant(cl[2])) {
            reph = cl.substring(0, 2)
            cl = cl.substring(2)
        }
        val pieces = signs.map { it.toString() }.flatMap { splitSign(it) }
        val prefixed = if (spec.preBase == PreBase.NONE) emptySet() else prefix
        val pre = pieces.filter { it in prefixed }.joinToString("")
        val post = pieces.filter { it !in prefixed }.joinToString("")
        var head = ""
        var tail = cl
        if (spec.preBase == PreBase.LAST_LETTER && pre.isNotEmpty()) {
            val last = LAST_LETTER.matchEntire(cl.replace(virama, VIRAMA).replace(nukta, NUKTA))
            if (last != null) {
                head = last.groupValues[1].replace(VIRAMA, virama).replace(NUKTA, nukta)
                tail = last.groupValues[2].replace(VIRAMA, virama).replace(NUKTA, nukta)
            }
        }
        if (spec.raSignFirst) {
            val sign = "$virama$ra"
            tail = buildString {
                var p = 0
                while (p < tail.length) {
                    if (tail[p] != virama && tail.startsWith(sign, p + 1)) {
                        append(sign).append(tail[p])
                        p += 3
                    } else {
                        append(tail[p])
                        p++
                    }
                }
            }
        }
        val core = if (spec.signOnFirst && tail.length > 1 && virama in tail) {
            val first = if (tail[1] == nukta) tail.substring(0, 2) else tail.substring(0, 1)
            first + post + tail.substring(first.length)
        } else {
            tail + post
        }
        var s = head + pre + core
        if (spec.nuktaFirst && nukta in s) {
            val chars = s.toCharArray()
            for (p in 1 until chars.size) if (chars[p] == nukta) { chars[p] = chars[p - 1]; chars[p - 1] = nukta }
            s = String(chars)
        }
        if (reph.isNotEmpty() && pre.isNotEmpty()) {
            when (spec.rephWithPreBase) {
                RephWithPreBase.BEFORE_CLUSTER -> return head + pre + reph + core + mods
                RephWithPreBase.FRONT -> return reph + head + pre + core + mods
                RephWithPreBase.SAME -> Unit
            }
        }
        if (reph.isNotEmpty()) {
            when (spec.reph) {
                Reph.AFTER_CLUSTER -> s = head + pre + tail + reph + post
                Reph.AFTER_SIGNS -> s += reph
                Reph.FRONT -> s = reph + s
                Reph.END -> return s + mods + reph
                Reph.NONE -> Unit
            }
        }
        return s + mods
    }

    private fun match(s: String): String {
        val out = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            var matched = false
            for (length in minOf(longest, s.length - i) downTo 1) {
                val g = glyphs[s.substring(i, i + length)] ?: continue
                out.append(g)
                i += length
                matched = true
                break
            }
            if (!matched) {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    private companion object {
        /** Devanagari, Bengali, Gujarati, Odia, Telugu and Kannada: the blocks a reph is looked for in. */
        val REPH_BLOCKS = setOf(0x900, 0x980, 0xA80, 0xB00, 0xC00, 0xC80)

        /** Signs a font builds from two others that Unicode does not decompose: ॉ as ा and ॅ, ॊ as ा and ॆ. */
        val EXTRA_SPLIT = mapOf("\u0949" to "\u093E\u0945", "\u094A" to "\u093E\u0946")

        val CHILLUS = listOf('\u0D7A' to '\u0D23', '\u0D7B' to '\u0D28', '\u0D7C' to '\u0D30', '\u0D7D' to '\u0D32', '\u0D7E' to '\u0D33', '\u0D7F' to '\u0D15')

        // The cluster's last letter, with its nukta, after the virama and nukta are swapped for these stand-ins.
        const val VIRAMA = '\uE010'
        const val NUKTA = '\uE011'
        val LAST_LETTER = Regex("^(.*?)([^\\uE010\\uE011]\\uE011?)$")
    }
}
