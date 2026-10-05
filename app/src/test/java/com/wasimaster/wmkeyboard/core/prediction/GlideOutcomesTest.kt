package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [GlideOutcomes], what a glide's strip picks and undos teach the next decode (issue #52). */
class GlideOutcomesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "learning/glide_outcomes.json")

    private fun deltas(store: GlideOutcomes, vararg words: String): DoubleArray? =
        store.view().adjustments(words.toList())

    /** A distinct all-letter word per [i]; the store keeps letters only. */
    private fun filler(i: Int): String = "filler" + ('a' + i / 26) + ('a' + i % 26)

    @Test
    fun aPickLiftsTheChosenWordAndSinksTheOneItBeat() {
        val store = GlideOutcomes(null)
        assertNull(deltas(store, "there", "three"))
        assertTrue(store.observeAlternative("there", "three"))
        val shift = deltas(store, "there", "three")!!
        assertEquals(-GlideOutcomes.ALTERNATIVE_STEP * GlideOutcomes.REJECTED_NATS, shift[0], 1e-9)
        assertEquals(GlideOutcomes.ALTERNATIVE_STEP * GlideOutcomes.CHOSEN_NATS, shift[1], 1e-9)
        // A pair only speaks when both its words are in the pool.
        assertNull(deltas(store, "three", "these"))
        assertNull(deltas(store, "there", "these"))
    }

    @Test
    fun theSwingOfThreePicksClearsACloseCall() {
        val store = GlideOutcomes(null)
        repeat(3) { store.observeAlternative("there", "three") }
        val shift = deltas(store, "there", "three")!!
        assertTrue("swing ${shift[1] - shift[0]}", shift[1] - shift[0] > SuggestionEngine.AMBIGUOUS_MARGIN)
    }

    @Test
    fun aReversePickIsCounterEvidenceNotASecondHabit() {
        val store = GlideOutcomes(null)
        repeat(2) { store.observeAlternative("there", "three") }
        store.observeAlternative("three", "there")
        val shift = deltas(store, "there", "three")!!
        // Both pairs now stand at two: the two words tie.
        assertEquals(shift[0], shift[1], 1e-9)
        store.observeAlternative("there", "three")
        val again = deltas(store, "there", "three")!!
        assertTrue(again[1] > again[0])
    }

    @Test
    fun anUndoIsWeakerThanAPickAndAPickRepaysIt() {
        val store = GlideOutcomes(null)
        store.observeImmediateUndo("there")
        assertEquals(-GlideOutcomes.UNDONE_NATS, deltas(store, "there", "three")!![0], 1e-9)
        val pick = GlideOutcomes(null).also { it.observeAlternative("three", "there") }
        assertTrue(deltas(pick, "there", "three")!![0] > GlideOutcomes.UNDONE_NATS)
        // Choosing the word off the strip repays one strike.
        store.observeAlternative("three", "there")
        val shift = deltas(store, "there", "three")!!
        assertEquals(GlideOutcomes.ALTERNATIVE_STEP * GlideOutcomes.CHOSEN_NATS, shift[0], 1e-9)
    }

    @Test
    fun liftsAndDropsAreClamped() {
        val store = GlideOutcomes(null)
        repeat(12) { store.observeAlternative("there", "three") }
        val shift = deltas(store, "there", "three")!!
        assertEquals(GlideOutcomes.MAX_LIFT_NATS, shift[1], 1e-9)
        assertEquals(-GlideOutcomes.STRENGTH_CAP * GlideOutcomes.REJECTED_NATS, shift[0], 1e-9)
        repeat(12) { store.observeImmediateUndo("these") }
        assertEquals(-GlideOutcomes.MAX_DROP_NATS, deltas(store, "these", "three")!![0], 1e-9)
    }

    @Test
    fun evidenceFadesWithUseNotTime() {
        val store = GlideOutcomes(null)
        store.observeAlternative("there", "three")
        // Two units of strength, one lost every DECAY_INTERVAL observations.
        // Letters only: a filler with a digit in it is not a word and teaches nothing.
        repeat((GlideOutcomes.DECAY_INTERVAL * 2).toInt()) { assertTrue(store.observeImmediateUndo(filler(it))) }
        assertNull(deltas(store, "there", "three"))
    }

    @Test
    fun capacityEvictsTheWeakestFirst() {
        val store = GlideOutcomes(null)
        repeat(4) { store.observeAlternative("there", "three") }
        for (i in 0 until GlideOutcomes.MAX_PAIRS) {
            assertTrue(store.observeAlternative(filler(i), "over" + filler(i)))
        }
        assertTrue(deltas(store, "there", "three")!![1] > 0.0)
        assertNull(deltas(store, filler(0), "over" + filler(0)))
    }

    @Test
    fun clearDeletesTheFile() {
        val store = GlideOutcomes(file())
        store.observeAlternative("there", "three")
        store.save()
        assertTrue(file().exists())
        store.clear()
        assertFalse(file().exists())
        assertTrue(store.isEmpty())
    }

    @Test
    fun theFileHoldsPlainWordsAndComesBackWhole() {
        val store = GlideOutcomes(file())
        repeat(2) { store.observeAlternative("there", "three") }
        store.observeImmediateUndo("these")
        store.save()
        val text = file().readText()
        assertTrue(text.contains("there") && text.contains("three") && text.contains("these"))
        val reopened = GlideOutcomes(file())
        assertEquals(deltas(store, "there", "three", "these")!!.toList(), deltas(reopened, "there", "three", "these")!!.toList())
    }

    @Test
    fun aFileFromAnotherVersionStartsEmpty() {
        file().parentFile?.mkdirs()
        file().writeText("""{"version":1,"epoch":1,"pairs":[{"r":"there","c":"three","s":4,"e":1}]}""")
        assertTrue(GlideOutcomes(file()).isEmpty())
    }

    @Test
    fun onlyWordsAreKept() {
        val store = GlideOutcomes(null)
        assertNull(store.fingerprint("a"))
        assertNull(store.fingerprint("2026"))
        assertNull(store.fingerprint("me@x.io"))
        // Bengali: a vowel sign and a hasant are marks, not letters, and belong to the word.
        assertTrue(store.fingerprint("বাংলা") != null)
        assertTrue(store.fingerprint("হ্যাঁ") != null)
        // The strip shows the contraction the decoder ranked bare, so both file the same.
        assertEquals(store.fingerprint("its"), store.fingerprint("it's"))
        assertFalse(store.observeAlternative("its", "it's"))
    }

    @Test
    fun switchedOffTheStoreAppliesNothingAndKeepsWhatItLearned() {
        val store = GlideOutcomes(null)
        store.observeAlternative("there", "three")
        store.applied = false
        assertNull(deltas(store, "there", "three"))
        assertTrue(store.view().isEmpty)
        store.applied = true
        assertTrue(deltas(store, "there", "three")!![1] > 0.0)
    }
}
