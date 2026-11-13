package com.wasimaster.wmkeyboard.core.gesture.latinime

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.gesture.eval.GlideGrid
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversions between this keyboard's glide model and the arrays Google's
 * gesture library takes through AOSP LatinIME's JNI. The library itself is
 * proprietary and Android-only, so nothing here runs it: these hold the
 * arrays to the shapes and invariants its native code expects (the sizes it
 * checks, the coordinate space its ProximityInfo builds, the order its
 * results come in). Whether it then reads strokes well on this keyboard is
 * unmeasured.
 */
class LatinImeGeometryTest {

    private val keyWidth = 60f

    private fun qwerty(): GlideKeyMap {
        val keys = GlideGrid.of(BuiltInLayouts.QWERTY).keyCenters(keyWidth)
        return GlideKeyMap.of(keys, keyWidth)
    }

    private fun board() = assertNotNullAnd(LatinImeGeometry.keyboard(qwerty(), keyWidth))

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun keyOf(board: LatinImeKeyboard, ch: Char): Int = board.codes.indexOf(ch.code)

    @Test
    fun everyLetterIsOneKeyWithOneCode() {
        val board = board()
        for (ch in 'a'..'z') assertTrue("$ch placed", keyOf(board, ch) >= 0)
        assertEquals("no code twice", board.codes.size, board.codes.toSet().size)
        assertEquals(board.keyCount, board.keyX.size)
        assertEquals(board.keyCount, board.keyWidths.size)
        assertEquals(board.keyCount, board.keyHeights.size)
    }

    @Test
    fun keysSitInsideTheBoardFromTheOrigin() {
        val board = board()
        assertEquals("leftmost key starts at 0", 0, board.keyX.min())
        assertEquals("top row starts at 0", 0, board.keyY.min())
        for (i in 0 until board.keyCount) {
            assertTrue(board.keyX[i] + board.keyWidths[i] <= board.width)
            assertTrue(board.keyY[i] + board.keyHeights[i] <= board.height)
        }
        assertEquals(keyWidth.toInt(), board.keyWidth)
    }

    @Test
    fun rowsAreOnePitchApartAndInQwertyOrder() {
        val board = board()
        val q = keyOf(board, 'q')
        val a = keyOf(board, 'a')
        val z = keyOf(board, 'z')
        assertNear(board.keyHeight, board.keyY[a] - board.keyY[q], 1)
        assertNear(board.keyHeight, board.keyY[z] - board.keyY[a], 1)
        assertTrue(board.keyX[keyOf(board, 'w')] > board.keyX[q])
        // AOSP sorts its keys top to bottom, then left to right.
        assertTrue(q < a && a < z)
    }

    @Test
    fun theProximityTableIsTheSizeTheNativeCodeReads() {
        val board = board()
        assertEquals(
            LatinImeGeometry.GRID_WIDTH * LatinImeGeometry.GRID_HEIGHT * LatinImeGeometry.MAX_PROXIMITY_CHARS,
            board.proximityChars.size,
        )
        assertEquals(32, board.gridWidth)
        assertEquals(16, board.gridHeight)
    }

    @Test
    fun aCellOverAKeyListsItAndItsNeighboursOnly() {
        val board = board()
        val g = keyOf(board, 'g')
        val cx = board.keyX[g] + board.keyWidths[g] / 2
        val cy = board.keyY[g] + board.keyHeights[g] / 2
        val cellWidth = (board.width + board.gridWidth - 1) / board.gridWidth
        val cellHeight = (board.height + board.gridHeight - 1) / board.gridHeight
        val cell = (cy / cellHeight) * board.gridWidth + (cx / cellWidth)
        val from = cell * LatinImeGeometry.MAX_PROXIMITY_CHARS
        val near = board.proximityChars.copyOfRange(from, from + LatinImeGeometry.MAX_PROXIMITY_CHARS)
            .takeWhile { it != LatinImeGeometry.NOT_A_CODE }
            .map { it.toChar() }
        assertTrue("g near itself: $near", 'g' in near)
        assertTrue("f and h beside it: $near", 'f' in near && 'h' in near)
        assertFalse("p is across the board: $near", 'p' in near)
        assertFalse("q is across the board: $near", 'q' in near)
    }

    @Test
    fun aKeysShiftedAndHeldCharactersDoNotBecomeKeys() {
        val keys = listOf(
            KeyCenter('e', 30f, 30f), KeyCenter('E', 30f, 30f), KeyCenter('é', 30f, 30f),
            KeyCenter('r', 90f, 30f),
        )
        val board = assertNotNullAnd(LatinImeGeometry.keyboard(GlideKeyMap.of(keys, keyWidth), keyWidth))
        assertArrayEquals(intArrayOf('e'.code, 'r'.code), board.codes)
    }

    @Test
    fun aGridOfOneKeyIsNoKeyboard() {
        assertNull(LatinImeGeometry.keyboard(GlideKeyMap.of(listOf(KeyCenter('a', 30f, 30f)), keyWidth), keyWidth))
    }

    @Test
    fun theTraceMovesOntoTheBoardAndKeepsItsClock() {
        val keys = qwerty()
        val board = board()
        val q = keyOf(board, 'q')
        val p = keyOf(board, 'p')
        val qx = keys.keyX[keys.keyIndex('q')] * keyWidth
        val qy = keys.keyY[keys.keyIndex('q')] * keyWidth
        val px = keys.keyX[keys.keyIndex('p')] * keyWidth
        val path = listOf(GesturePoint(qx, qy, 1000L), GesturePoint(px, qy, 1250L))
        val trace = assertNotNullAnd(LatinImeGeometry.trace(path, board))
        assertEquals(2, trace.size)
        assertNear(board.keyX[q] + board.keyWidths[q] / 2, trace.xs[0], 1)
        assertNear(board.keyY[q] + board.keyHeights[q] / 2, trace.ys[0], 1)
        assertNear(board.keyX[p] + board.keyWidths[p] / 2, trace.xs[1], 1)
        assertArrayEquals(intArrayOf(0, 250), trace.times)
        assertArrayEquals(intArrayOf(0, 0), trace.pointerIds)
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected ± $tolerance, got $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    @Test
    fun aPointOffTheBoardIsClampedToItsEdge() {
        val board = board()
        val trace = assertNotNullAnd(
            LatinImeGeometry.trace(listOf(GesturePoint(-500f, -500f, 0L), GesturePoint(10_000f, 10_000f, 100L)), board),
        )
        assertEquals(0, trace.xs[0])
        assertEquals(0, trace.ys[0])
        assertEquals(board.width - 1, trace.xs[1])
        assertEquals(board.height - 1, trace.ys[1])
    }

    @Test
    fun repeatsAreDroppedAndTimeNeverRunsBackwards() {
        val board = board()
        val path = listOf(
            GesturePoint(100f, 100f, 0L),
            GesturePoint(100f, 100f, 5L),
            GesturePoint(200f, 100f, 20L),
            GesturePoint(300f, 100f, 15L),
        )
        val trace = assertNotNullAnd(LatinImeGeometry.trace(path, board))
        assertEquals(3, trace.size)
        for (i in 1 until trace.size) assertTrue(trace.times[i] >= trace.times[i - 1])
    }

    @Test
    fun aStrokeWithNoClockGetsAnEvenOne() {
        val board = board()
        val path = (0 until 5).map { GesturePoint(100f + it * 40f, 100f) }
        val trace = assertNotNullAnd(LatinImeGeometry.trace(path, board))
        assertEquals(5, trace.size)
        val step = trace.times[1] - trace.times[0]
        assertTrue(step > 0)
        for (i in 1 until trace.size) assertEquals(step, trace.times[i] - trace.times[i - 1])
    }

    @Test
    fun aLongStrokeIsThinnedKeepingBothEnds() {
        val board = board()
        val path = (0 until 2000).map { GesturePoint(it * 0.3f, 100f + (it % 7), it.toLong()) }
        val trace = assertNotNullAnd(LatinImeGeometry.trace(path, board))
        assertEquals(LatinImeGeometry.MAX_POINTS, trace.size)
        assertEquals(0, trace.times.first())
        assertEquals(1999, trace.times.last())
        val picks = LatinImeGeometry.thin(2000, LatinImeGeometry.MAX_POINTS)
        assertEquals(0, picks.first())
        assertEquals(1999, picks.last())
        assertTrue((1 until picks.size).all { picks[it] > picks[it - 1] })
    }

    @Test
    fun candidatesComeOutBestFirstInNatsBelowTheTop() {
        val width = 48
        val out = IntArray(width * 18)
        fun put(slot: Int, word: String) = word.codePoints().toArray().copyInto(out, slot * width)
        put(0, "hello")
        put(1, "jello")
        put(2, "hel lo")
        put(3, "hello")
        val scores = IntArray(18).also {
            it[0] = 3_000_000
            it[1] = 4_500_000
            it[2] = 2_000_000
            it[3] = 1_000_000
        }
        val read = LatinImeGeometry.candidates(out, scores, 4, width)
        assertEquals(listOf("jello", "hello"), read.map { it.first })
        assertEquals(0.0, read[0].second, 1e-9)
        assertEquals(-1.5, read[1].second, 1e-9)
    }

    @Test
    fun aWordOutsideTheBmpSurvivesTheRoundTrip() {
        val width = 48
        val word = "𐒰𐒱" // two Osage letters
        val out = IntArray(width * 18)
        word.codePoints().toArray().copyInto(out, 0)
        val read = LatinImeGeometry.candidates(out, IntArray(18) { 1 }, 1, width)
        assertEquals(word, read.single().first)
    }

    @Test
    fun probabilitiesFollowAospsLogScale() {
        val top = kotlin.math.ln(1000.0)
        assertEquals(255, LatinImeLexicon.probability(top, top))
        assertEquals(255 - 9, LatinImeLexicon.probability(kotlin.math.ln(500.0), top))
        assertEquals(1, LatinImeLexicon.probability(kotlin.math.ln(1e-30), top))
    }

    @Test
    fun theDictionaryTakesTheListsAtTheirBestAndLeavesTheUsersWordsOut() {
        val main = Trie().apply {
            insert("hello", 1000)
            insert("help", 500)
            insert("two words", 900)
        }
        val second = Trie().apply { insert("help", 1000) }
        val mine = Trie().apply { insert("wmkb", 1) }
        val sources = main.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) } +
            second.walkers().map { FuzzyBeamSearch.WalkSource(it, -0.5, FuzzyBeamSearch.Tier.DICTIONARY) } +
            mine.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.USER) }
        val words = LatinImeLexicon.dictionaryWords(sources)
        assertEquals(listOf("hello", "help"), words.map { it.word })
        assertEquals(255, words[0].probability)
        // ln(1000) - 0.5 beats ln(500): the second list's help counts.
        assertTrue(words[1].probability > LatinImeLexicon.probability(kotlin.math.ln(500.0), kotlin.math.ln(1000.0)))
        assertEquals(setOf("wmkb"), LatinImeLexicon.personalWords(sources))
    }

    @Test
    fun theFingerprintMovesWithTheWords() {
        val a = listOf(LatinImeWord("hello", 255), LatinImeWord("help", 240))
        val b = listOf(LatinImeWord("hello", 255), LatinImeWord("help", 241))
        assertEquals(LatinImeLexicon.fingerprint("en", a), LatinImeLexicon.fingerprint("en", a))
        assertFalse(LatinImeLexicon.fingerprint("en", a) == LatinImeLexicon.fingerprint("en", b))
        assertFalse(LatinImeLexicon.fingerprint("en", a) == LatinImeLexicon.fingerprint("de", a))
    }

    @Test
    fun languageIdsBecomeAospLocales() {
        assertEquals("en", LatinImeGlide.aospLocale("en"))
        assertEquals("pt_BR", LatinImeGlide.aospLocale("pt_br"))
        assertEquals("sr", LatinImeGlide.aospLocale("sr-Latn"))
        assertEquals("es_419", LatinImeGlide.aospLocale("es-419"))
    }

    @Test
    fun pinsCoverTheTwoAbisAndAreLowercaseSha256() {
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), LatinImePins.byAbi.keys)
        for (hash in LatinImePins.byAbi.values) assertTrue(hash, Regex("[0-9a-f]{64}").matches(hash))
        assertEquals("arm64-v8a", LatinImePins.abiOf(LatinImePins.byAbi.getValue("arm64-v8a").uppercase()))
        assertNull(LatinImePins.abiOf("0".repeat(64)))
        assertTrue(LatinImePins.isLibraryName("libjni_latinimegoogle.so"))
    }
}
