package com.wasimaster.wmkeyboard.core.prediction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Han candidates over the key their reading continues on (#1028). */
class OctopusConversionTest {

    private fun assign(
        readings: Map<String, Char?>,
        others: List<OctopusWord> = emptyList(),
        limit: Int = 8,
        perKey: Int = 1,
    ): List<OctopusWord> {
        val words = readings.keys.toList()
        return assignConversionOctopus(
            words,
            { readings[words[it]] },
            others,
            { if (it in 'a'.code..'z'.code) it else -1 },
            limit,
            perKey,
        )
    }

    @Test
    fun `a word hangs on the next letter of its reading`() {
        val board = assign(mapOf("你好" to 'a', "尼姑" to 'g'))
        assertEquals(listOf('a'.code to "你好", 'g'.code to "尼姑"), board.map { it.keyCodePoint to it.word })
        assertTrue(board.all { it.typedChars == 0 })
    }

    @Test
    fun `a reading with nothing left floats nowhere`() {
        assertTrue(assign(mapOf("尼" to null)).isEmpty())
    }

    @Test
    fun `the better candidate keeps the key and the limit holds`() {
        val board = assign(mapOf("你好" to 'a', "拟好" to 'a', "尼姑" to 'g'), limit = 1)
        assertEquals(listOf("你好"), board.map { it.word })
        assertEquals(listOf("你好", "尼姑"), assign(mapOf("你好" to 'a', "拟好" to 'a', "尼姑" to 'g')).map { it.word })
    }

    @Test
    fun `an English word comes behind on a key with room`() {
        val note = OctopusWord('a'.code, "nihao", 3, OctopusKind.COMPLETION, 0)
        val full = assign(mapOf("你好" to 'a'), listOf(note))
        assertEquals(listOf("你好"), full.map { it.word })
        val stacked = assign(mapOf("你好" to 'a'), listOf(note), perKey = 2)
        assertEquals(listOf("你好" to 0, "nihao" to 1), stacked.map { it.word to it.tier })
    }
}
