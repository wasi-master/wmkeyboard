package com.wasimaster.wmkeyboard.core.emoji

import com.wasimaster.wmkeyboard.core.emoji.EmojiDefaultFace.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiDefaultFaceTest {

    private val runner = "🏃" // 🏃
    private val runnerWoman = "🏃‍♀️" // 🏃‍♀️
    private val runnerMan = "🏃‍♂️" // 🏃‍♂️
    private val healthWorker = "🧑‍⚕️" // 🧑‍⚕️
    private val healthWoman = "👩‍⚕️" // 👩‍⚕️
    private val healthMan = "👨‍⚕️" // 👨‍⚕️

    private val variants = EmojiVariantIndex.load(
        listOf(
            "$runner\t3\t🏃🏽",
            "$runnerWoman\t3\t🏃🏽‍♀️",
            "$runnerMan\t3\t🏃🏽‍♂️",
        ).joinToString("\n").byteInputStream(),
    )

    @Test
    fun `single people get a gender, couples and families do not`() {
        assertEquals(Gender.WOMAN, EmojiDefaultFace.genderOf(runnerWoman))
        assertEquals(Gender.MAN, EmojiDefaultFace.genderOf(runnerMan))
        assertEquals(Gender.WOMAN, EmojiDefaultFace.genderOf(healthWoman))
        assertEquals(Gender.MAN, EmojiDefaultFace.genderOf(healthMan))
        assertEquals(Gender.MAN, EmojiDefaultFace.genderOf("🧔‍♂️")) // 🧔‍♂️
        assertNull(EmojiDefaultFace.genderOf(runner))
        assertNull(EmojiDefaultFace.genderOf(healthWorker))
        // 👩‍❤️‍👨 and 👨‍👩‍👧: more than one person.
        assertNull(EmojiDefaultFace.genderOf("👩‍❤️‍👨"))
        assertNull(EmojiDefaultFace.genderOf("👨‍👩‍👧"))
    }

    @Test
    fun `woman and man pick the matching child, person keeps the base`() {
        val children = listOf(runnerMan, runnerWoman)
        assertEquals(runner, EmojiDefaultFace.resolve(runner, children, variants, Gender.PERSON, 0))
        assertEquals(runnerWoman, EmojiDefaultFace.resolve(runner, children, variants, Gender.WOMAN, 0))
        assertEquals(runnerMan, EmojiDefaultFace.resolve(runner, children, variants, Gender.MAN, 0))
        val doctors = listOf(healthMan, healthWoman)
        assertEquals(healthWoman, EmojiDefaultFace.resolve(healthWorker, doctors, variants, Gender.WOMAN, 0))
    }

    @Test
    fun `tone goes on top of the gender`() {
        val children = listOf(runnerMan, runnerWoman)
        assertEquals(
            "🏃🏽‍♀️",
            EmojiDefaultFace.resolve(runner, children, variants, Gender.WOMAN, 3),
        )
        // No RGI toned form: the untoned face stays.
        assertEquals(runnerWoman, EmojiDefaultFace.resolve(runner, children, variants, Gender.WOMAN, 5))
    }

    @Test
    fun `an emoji without gendered children keeps its face`() {
        val dog = "🐕"
        assertEquals(dog, EmojiDefaultFace.resolve(dog, emptyList(), variants, Gender.WOMAN, 0))
        assertEquals(dog, EmojiDefaultFace.resolve(dog, emptyList(), variants, Gender.RANDOM, 0))
    }

    @Test
    fun `random is stable per seed and reaches every option`() {
        val children = listOf(runnerMan, runnerWoman)
        val seen = HashSet<String>()
        for (seed in 0 until 200) {
            val a = EmojiDefaultFace.resolve(runner, children, variants, Gender.RANDOM, 0, seed)
            val b = EmojiDefaultFace.resolve(runner, children, variants, Gender.RANDOM, 0, seed)
            assertEquals(a, b)
            seen += a
        }
        assertEquals(setOf(runner, runnerWoman, runnerMan), seen)
    }

    @Test
    fun `random tone always lands on a real tone`() {
        val tones = HashSet<String>()
        for (seed in 0 until 200) {
            val face = EmojiDefaultFace.resolve(runner, emptyList(), variants, Gender.PERSON, EmojiDefaultFace.RANDOM_TONE, seed)
            assertTrue(face == runner || face == "🏃🏽")
            tones += face
        }
        // Only tone 3 has a row in this index; the others fall back to the base.
        assertEquals(2, tones.size)
    }

    @Test
    fun `children are grouped by parent`() {
        val catalog = listOf(
            EmojiEntry(runner, "people", emptyList()),
            EmojiEntry(runnerWoman, "people", emptyList(), parent = runner),
            EmojiEntry(runnerMan, "people", emptyList(), parent = runner),
        )
        assertEquals(listOf(runnerWoman, runnerMan), EmojiDefaultFace.children(catalog)[runner])
    }
}
