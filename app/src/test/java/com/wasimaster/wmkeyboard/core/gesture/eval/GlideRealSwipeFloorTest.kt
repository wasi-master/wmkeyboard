// The harness runs on the JVM only and stdout IS its report channel — the
// device-invisibility rationale behind the println ban does not apply here.
@file:Suppress("ForbiddenMethodCall")

package com.wasimaster.wmkeyboard.core.gesture.eval

import com.wasimaster.wmkeyboard.core.gesture.GesturePoint
import com.wasimaster.wmkeyboard.core.gesture.GlideBeam
import com.wasimaster.wmkeyboard.core.gesture.GlideKeyMap
import com.wasimaster.wmkeyboard.core.gesture.GlideWorkspace
import com.wasimaster.wmkeyboard.core.gesture.KeyCenter
import com.wasimaster.wmkeyboard.core.prediction.DictionaryLoader
import com.wasimaster.wmkeyboard.core.prediction.FuzzyBeamSearch
import com.wasimaster.wmkeyboard.core.prediction.Trie
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlideBeam] at its shipped tuning on 2,000 real swipes, held to a floor.
 *
 * [GestureEvalTest]'s floors are measured on [SwipeCorpus], which draws its
 * strokes from a model of a finger, and a model of a finger flatters the
 * decoder: on the corpus English reads .95 top-1, on real strokes .78. These
 * are real ones, every 13th in-vocabulary stroke of FUTO's swipe.futo.org test
 * split (MIT, `glide/futo-swipes.LICENSE`), drawn by people on their own
 * phones, so a change that helps the model and hurts the hand fails here.
 *
 * The strokes come from the split the decoder's weights were *not* fitted on
 * (they were fitted on the train split; see `GlideBeam.Tuning`), and from
 * `native/bench/futo-swipe/make_fixture.py`: `word width height` and then
 * `x,y,dt` per point, x and y in thousandths of the canvas, the first line the
 * dataset's own key centres. `native/bench/futo-swipe/README.md` has the
 * download and the larger measurement this is a slice of.
 *
 * A change that raises the numbers should raise the floors in the same commit.
 */
class GlideRealSwipeFloorTest {

    private class Stroke(val word: String, val width: Float, val height: Float, val points: List<GesturePoint>)

    @Test
    fun realSwipesHoldTheirFloor() {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream("glide/futo-swipes.txt.gz")) {
            "glide/futo-swipes.txt.gz is missing from the test resources"
        }
        val lines = GZIPInputStream(stream).bufferedReader().readLines()
        val centres = lines.first().removePrefix("#keys ").split(' ').map { entry ->
            val (letter, at) = entry.split(':')
            val (x, y) = at.split(',').map { it.toFloat() / PER_MILLE }
            Triple(letter[0].code, x, y)
        }
        val strokes = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split(' ')
            val width = fields[1].toFloat()
            val height = fields[2].toFloat()
            var t = 0L
            val points = fields.drop(HEADER_FIELDS).map {
                val (x, y, dt) = it.split(',')
                t += dt.toLong()
                GesturePoint(x.toFloat() / PER_MILLE * width, y.toFloat() / PER_MILLE * height, t)
            }
            Stroke(fields[0], width, height, points)
        }

        val list = listOf(File("dictionaries-src/en.txt"), File("app/dictionaries-src/en.txt")).first { it.exists() }
        val entries = list.inputStream().use { DictionaryLoader.loadEntries(it) }
        val trie = Trie().apply { entries.forEach { (word, frequency) -> insert(word, frequency) } }
        val sources = trie.walkers().map { FuzzyBeamSearch.WalkSource(it, 0.0, FuzzyBeamSearch.Tier.DICTIONARY) }
        val beam = GlideBeam()
        val workspace = GlideWorkspace()

        var top1 = 0
        var top4 = 0
        var shortTotal = 0
        var shortTop1 = 0
        for (stroke in strokes) {
            val keyWidth = stroke.width * KEY_WIDTH_SHARE
            val keys = GlideKeyMap.of(
                centres.map { (cp, x, y) -> KeyCenter(cp, x * stroke.width, y * stroke.height) },
                keyWidth,
            )
            val words = beam.decode(stroke.points, keys, keyWidth, sources, workspace, LIMIT).map { it.word.lowercase() }
            val hit = words.firstOrNull() == stroke.word
            if (hit) top1++
            if (stroke.word in words) top4++
            if (stroke.word.length <= SHORT && (1 until stroke.word.length).any { stroke.word[it] == stroke.word[it - 1] }) {
                shortTotal++
                if (hit) shortTop1++
            }
        }
        val n = strokes.size.toDouble()
        val measuredTop1 = top1 / n
        val measuredTop4 = top4 / n
        println(
            String.format(
                Locale.ROOT,
                "real swipes n=%d  top1 %.4f (floor %.4f)  top4 %.4f (floor %.4f)  short doubled %d/%d",
                strokes.size, measuredTop1, TOP1_FLOOR, measuredTop4, TOP4_FLOOR, shortTop1, shortTotal,
            ),
        )
        assertTrue("top-1 on real swipes fell to $measuredTop1, floor $TOP1_FLOOR", measuredTop1 >= TOP1_FLOOR - TOLERANCE)
        assertTrue("top-4 on real swipes fell to $measuredTop4, floor $TOP4_FLOOR", measuredTop4 >= TOP4_FLOOR - TOLERANCE)
    }

    private companion object {
        /**
         * Measured on this fixture over the bundled en.txt. History:
         *
         * 2026-10-22, first floor, the tuning fitted on the train split:
         * see `GlideBeam.Tuning` for what moved.
         */
        const val TOP1_FLOOR = 0.8735
        const val TOP4_FLOOR = 0.9630

        /** The decoder is deterministic; this only absorbs a word-list edit's nudge. */
        const val TOLERANCE = 0.005

        const val PER_MILLE = 1000f

        /** The dataset's keys are a tenth of the canvas wide. */
        const val KEY_WIDTH_SHARE = 0.1f
        const val HEADER_FIELDS = 3
        const val LIMIT = 4
        const val SHORT = 4
    }
}
