package com.wasimaster.wmkeyboard.core.gesture

import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stroke that strays into the number row is read as the top row it was
 * reaching for (#970). The geometry is the one `GlideOverhang` was measured on:
 * keys 60 px wide on an 84 px row pitch, so a digit's centre is 1.4 key widths
 * above its letter's, and 1.2 rows up is past the decoder's 1.6 start radius.
 */
class GlideOverhangTest {

    private val keyWidth = 60f
    private val pitch = 84f

    private val keys: List<KeyCenter> = buildList {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, c -> add(KeyCenter(c, (offset + i + 0.5f) * keyWidth, (row + 0.5f) * pitch)) }
        }
    }
    private val centers = keys.associateBy { it.codePoint }
    private val grid = GlideKeyMap.of(keys, keyWidth)

    private val sources: List<FuzzyBeamSearch.WalkSource> = Trie().apply {
        listOf(
            "the" to 1000, "then" to 700, "there" to 650, "they" to 800, "type" to 300, "rope" to 120,
            "tour" to 150, "with" to 900, "what" to 850, "were" to 700, "your" to 750, "yet" to 300,
        ).forEach { (word, frequency) -> insert(word, frequency) }
    }.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }

    private fun decode(path: List<GesturePoint>): List<String> =
        GlideBeam().decode(path, grid, keyWidth, sources, GlideWorkspace(), 4).map { it.word }

    /** [word] drawn centre to centre, its first letter lifted [rows] row pitches. */
    private fun stroke(word: String, rows: Float = 0f): List<GesturePoint> {
        val anchors = word.mapIndexed { i, c ->
            val key = centers.getValue(c.code)
            if (i == 0) key.x to key.y - rows * pitch else key.x to key.y
        }
        val out = ArrayList<GesturePoint>()
        var clock = 0L
        for ((a, b) in anchors.zipWithNext()) {
            for (step in 0 until 12) {
                val t = step / 12f
                out.add(GesturePoint(a.first + t * (b.first - a.first), a.second + t * (b.second - a.second), clock))
                clock += 4
            }
        }
        anchors.last().let { out.add(GesturePoint(it.first, it.second, clock)) }
        return out
    }

    @Test
    fun `the top edge is half a row pitch above the top row`() {
        assertEquals(0f, grid.topEdge, 1e-4f)
        assertTrue(GlideKeyMap.of(keys.take(10), keyWidth).topEdge.isNaN())
    }

    @Test
    fun `a stroke on the keys is handed back untouched`() {
        val path = stroke("type")
        assertSame(path, GlideOverhang.fold(path, grid, keyWidth))
    }

    @Test
    fun `samples above the edge drop onto it and keep their x and time`() {
        val path = stroke("then", rows = 1.2f)
        val folded = GlideOverhang.fold(path, grid, keyWidth)
        assertEquals(path.size, folded.size)
        for ((before, after) in path.zip(folded)) {
            assertEquals(before.x, after.x, 0f)
            assertEquals(before.t, after.t)
            assertEquals(maxOf(before.y, 0f), after.y, 1e-4f)
        }
    }

    @Test
    fun `a word started on the digit above its first letter decodes once folded`() {
        for (word in listOf("the", "then", "with", "were", "your")) {
            val path = stroke(word, rows = 1.2f)
            assertNotEquals("$word unfolded starts past every key's radius", word, decode(path).firstOrNull())
            assertEquals(word, decode(GlideOverhang.fold(path, grid, keyWidth)).firstOrNull())
        }
    }
}
