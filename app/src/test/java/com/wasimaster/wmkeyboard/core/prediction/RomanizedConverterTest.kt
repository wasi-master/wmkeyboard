package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.transliteration.BengaliPhoneticIndex
import com.wasimaster.wmkeyboard.core.transliteration.HindiPhoneticIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomanizedConverterTest {

    private val map = SpellingMap.load(
        """
        tmr	তোমার
        tomar	তোমার
        tumar	তোমার
        ki	কি
        ki	কী
        valo	ভালো
        bhalo	ভালো
        valoo	ভালো
        school	স্কুল
        phone	ফোন
        phn	ফোন
        thankss	ধন্যবাদ
        dhonnobad	ধন্যবাদ
        amk	আমাকে
        monehoy	মনে হয়
        """.trimIndent().byteInputStream(Charsets.UTF_8),
    )

    private val index = BengaliPhoneticIndex(
        listOf(
            "আছি" to 6900, "আসি" to 2300, "থাকে" to 1750, "তাকে" to 4850, "ঠিক" to 4000,
            "আমি" to 9000, "কেমন" to 3000, "আছো" to 2000, "হল" to 1200, "হলো" to 1500,
            "ভালো" to 6500, "ভাল" to 1900,
        ),
    )

    private val converter = RomanizedConverter(PhoneticSchemes.BENGALI, map, index)

    @Test
    fun `the spelling map wins, whatever the case`() {
        assertEquals("তোমার", converter.toNative("tmr"))
        assertEquals("তোমার", converter.toNative("Tmr"))
        assertEquals("তোমার", converter.toNative("TMR"))
    }

    @Test
    fun `the index absorbs a dialect spelling when clearly commoner, the literal stands otherwise`() {
        assertEquals("আছি", converter.toNative("asi"))
        assertEquals("থাকে", converter.toNative("thake"))
        assertEquals("ঠিক", converter.toNative("tik"))
        assertEquals("হল", converter.toNative("holo"))
    }

    @Test
    fun `whole sentences, with digits and punctuation left alone`() {
        assertEquals("আমি ভালো আছি", converter.toNative("ami valo asi"))
        assertEquals("কেমন আছো?", converter.toNative("kemon acho?"))
        assertEquals("নদী", converter.toNative("nodI"))
        val out = converter.toNative("ami 2024 e jabo.")!!
        assertTrue(out, out.startsWith("আমি 2024 ") && out.endsWith("."))
        assertEquals("আমি ভালো", converter.toNative("আমি valo"))
    }

    @Test
    fun `nothing latin means nothing to do`() {
        assertNull(converter.toNative("2024"))
        assertNull(converter.toNative("আমি"))
        assertNull(converter.toNative(""))
    }

    @Test
    fun `back to banglish takes the readable map spelling, then the rules`() {
        assertEquals("tomar", converter.toRoman("তোমার"))
        assertEquals("bhalo", converter.toRoman("ভালো"))
        assertEquals("phone", converter.toRoman("ফোন"))
        assertEquals("school", converter.toRoman("স্কুল"))
        assertEquals("dhonnobad", converter.toRoman("ধন্যবাদ"))
        assertEquals("ki", converter.toRoman("কী"))
        assertEquals("amake", converter.toRoman("আমাকে"))
    }

    @Test
    fun `back to banglish skips shorthand and elongations and keeps loanwords`() {
        // Listed most-typed first, as the real lists are.
        val chat = SpellingMap.load(
            """
            phone	ফোন
            """.trimIndent().byteInputStream(Charsets.UTF_8),
            """
            ami	আমি
            amie	আমি
            tmi	তুমি
            tumi	তুমি
            amr	আমার
            amar	আমার
            amarr	আমার
            ase	আছে
            ache	আছে
            ashche	আছে
            kemon	কেমন
            kmn	কেমন
            """.trimIndent().byteInputStream(Charsets.UTF_8),
            loanwordStreams = 1,
        )
        val back = RomanizedConverter(PhoneticSchemes.BENGALI, chat, index)
        assertEquals("ami tumi amar", back.toRoman("আমি তুমি আমার"))
        assertEquals("kemon ache", back.toRoman("কেমন আছে"))
        assertEquals("phone", back.toRoman("ফোন"))
    }

    @Test
    fun `back to banglish over a sentence`() {
        assertEquals("ami bhalo achhi", converter.toRoman("আমি ভালো আছি"))
        assertEquals("ami OK achhi!", converter.toRoman("আমি OK আছি!"))
        assertEquals("2024 sale", converter.toRoman("২০২৪ সালে"))
        assertEquals("mone hoy", converter.toRoman("মনে হয়"))
        assertEquals("pora.", converter.toRoman("পড়া।"))
        assertNull(converter.toRoman("hello"))
    }

    // ---- Hindi: the same converter over the other scheme ----

    private val hindiMap = SpellingMap.load(
        """
        h	है
        hai	है
        theek	ठीक
        thik	ठीक
        school	स्कूल
        nahi	नहीं
        """.trimIndent().byteInputStream(Charsets.UTF_8),
    )

    private val hindiIndex = HindiPhoneticIndex(
        listOf("करना" to 900, "पानी" to 700, "मुझे" to 800, "बहुत" to 600, "काम" to 500, "कम" to 900),
    )

    private val hindi = RomanizedConverter(PhoneticSchemes.HINDI, hindiMap, hindiIndex)

    @Test
    fun `hinglish to hindi resolves the map, then the index, then the rules`() {
        assertEquals("ठीक", hindi.toNative("theek"))
        assertEquals("पानी", hindi.toNative("pani"))
        // Neither listed nor in the index: the rules' own reading.
        assertEquals("नमस्ते", hindi.toNative("namaste"))
        assertEquals("मुझे बहुत काम है।", hindi.toNative("Mujhe bahut kaam hai।"))
        assertEquals("स्कूल 2024", hindi.toNative("school 2024"))
        assertNull(hindi.toNative("मुझे"))
    }

    @Test
    fun `hindi back to hinglish drops the vowels speech drops`() {
        assertEquals("karna", hindi.toRoman("करना"))
        assertEquals("namaste", hindi.toRoman("नमस्ते"))
        // A known word takes the map's most readable spelling.
        assertEquals("theek", hindi.toRoman("ठीक"))
        assertEquals("school", hindi.toRoman("स्कूल"))
        assertEquals("mujhe bahut kaam hai.", hindi.toRoman("मुझे बहुत काम है।"))
        assertEquals("OK theek", hindi.toRoman("OK ठीक"))
        assertNull(hindi.toRoman("hello"))
        // One scheme's script is not the other's.
        assertNull(hindi.toRoman("আমি"))
        assertNull(converter.toRoman("मुझे"))
    }
}
