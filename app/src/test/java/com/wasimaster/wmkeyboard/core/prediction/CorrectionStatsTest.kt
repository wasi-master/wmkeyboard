package com.wasimaster.wmkeyboard.core.prediction

import com.wasimaster.wmkeyboard.core.prediction.CorrectionStats.Penalty
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CorrectionStatsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "learning/correction_stats.json")

    @Test
    fun sessionRevertBlocksImmediately() {
        val stats = CorrectionStats(null)
        assertEquals(Penalty.NONE, stats.penalty("teh", "the"))
        stats.recordRevert("teh", "the")
        assertEquals(Penalty.BLOCKED, stats.penalty("teh", "the"))
        // A different correction of the same typo is untouched.
        assertEquals(Penalty.NONE, stats.penalty("teh", "ten"))
    }

    @Test
    fun persistedSingleRevertPenalizesAndSecondBlocks() {
        val f = file()
        CorrectionStats(f).apply {
            recordRevert("teh", "the")
            save()
        }
        val nextSession = CorrectionStats(f)
        assertEquals(Penalty.PENALIZED, nextSession.penalty("teh", "the"))
        nextSession.recordRevert("teh", "the")
        nextSession.save()
        assertEquals(Penalty.BLOCKED, CorrectionStats(f).penalty("teh", "the"))
    }

    @Test
    fun penaltiesExpireAcrossManyQuietSessions() {
        val f = file()
        CorrectionStats(f).apply {
            recordRevert("teh", "the")
            save()
        }
        // 181 dirty save-generations with unrelated activity age the pair out.
        repeat(181) {
            CorrectionStats(f).apply {
                recordKept("unrelated", "activity")
                save()
            }
        }
        assertEquals(Penalty.NONE, CorrectionStats(f).penalty("teh", "the"))
    }

    @Test
    fun multiplierColdStartAndBounds() {
        val stats = CorrectionStats(null)
        assertEquals(1.0, stats.confidenceMultiplier(), 1e-9)
        // Below the sample floor nothing moves.
        repeat(10) { stats.recordKept("clean$it", "fix$it") }
        assertEquals(1.0, stats.confidenceMultiplier(), 1e-9)
        // A very bad run rails at the ceiling.
        repeat(30) { stats.recordRevert("w$it", "f$it") }
        assertEquals(2.5, stats.confidenceMultiplier(), 1e-9)
        // A long clean run drifts down and rails at the floor.
        repeat(400) { stats.recordKept("clean$it", "fix$it") }
        assertTrue(stats.confidenceMultiplier() in 0.85..1.0)
    }

    @Test
    fun halvingWindowForgetsAncientHistory() {
        val stats = CorrectionStats(null)
        repeat(30) { stats.recordRevert("w$it", "f$it") }
        assertEquals(2.5, stats.confidenceMultiplier(), 1e-9)
        // Hundreds of clean corrections later the old reverts stop dominating.
        repeat(600) { stats.recordKept("clean$it", "fix$it") }
        assertTrue(stats.confidenceMultiplier() < 1.5)
    }

    @Test
    fun pairTableIsCapped() {
        val stats = CorrectionStats(null)
        repeat(600) { stats.recordRevert("typed$it", "fix$it") }
        // No direct size accessor; the observable contract is that the most
        // recent pairs are still known.
        assertEquals(Penalty.BLOCKED, stats.penalty("typed599", "fix599"))
    }

    @Test
    fun clearWipesEverything() {
        val f = file()
        val stats = CorrectionStats(f)
        stats.recordRevert("teh", "the")
        stats.save()
        stats.clear()
        assertEquals(Penalty.NONE, CorrectionStats(f).penalty("teh", "the"))
    }

    @Test
    fun acceptsBuyBackARejectedPair() {
        val f = file()
        CorrectionStats(f).apply {
            recordRevert("teh", "the")
            save()
        }
        val next = CorrectionStats(f)
        assertEquals(Penalty.PENALIZED, next.penalty("teh", "the"))
        repeat(2) { next.recordKept("teh", "the") }
        assertEquals(Penalty.PENALIZED, next.penalty("teh", "the"))
        next.recordKept("teh", "the")
        assertEquals(Penalty.NONE, next.penalty("teh", "the"))
    }

    @Test
    fun twiceRejectedPairNeedsTwiceAsManyAccepts() {
        val f = file()
        repeat(2) {
            CorrectionStats(f).apply {
                recordRevert("teh", "the")
                save()
            }
        }
        val next = CorrectionStats(f)
        assertEquals(Penalty.BLOCKED, next.penalty("teh", "the"))
        repeat(3) { next.recordKept("teh", "the") }
        assertEquals(Penalty.PENALIZED, next.penalty("teh", "the"))
        repeat(3) { next.recordKept("teh", "the") }
        assertEquals(Penalty.NONE, next.penalty("teh", "the"))
    }

    @Test
    fun acceptProgressSurvivesASave() {
        val f = file()
        CorrectionStats(f).apply {
            recordRevert("teh", "the")
            save()
        }
        CorrectionStats(f).apply {
            repeat(2) { recordKept("teh", "the") }
            save()
        }
        val third = CorrectionStats(f)
        third.recordKept("teh", "the")
        assertEquals(Penalty.NONE, third.penalty("teh", "the"))
    }

    @Test
    fun acceptsNeverUnblockWithinTheSessionThatRejected() {
        val stats = CorrectionStats(null)
        stats.recordRevert("teh", "the")
        repeat(5) { stats.recordKept("teh", "the") }
        assertEquals(Penalty.BLOCKED, stats.penalty("teh", "the"))
    }

    @Test
    fun acceptsNeverCreateAPair() {
        val stats = CorrectionStats(null)
        repeat(10) { stats.recordKept("teh", "the") }
        assertEquals(Penalty.NONE, stats.penalty("teh", "the"))
    }

    @Test
    fun acceptsAndRejectsBothCountTowardsTheRatio() {
        val stats = CorrectionStats(null)
        // Ten accepted and ten rejected is a 50% revert rate, well past the
        // 3% the gate aims for, so it rails at the ceiling.
        repeat(10) { stats.recordKept("k$it", "f$it") }
        repeat(10) { stats.recordRevert("w$it", "f$it") }
        assertEquals(2.5, stats.confidenceMultiplier(), 1e-9)
    }

    @Test
    fun anIndirectUndoDoesNotBlockTheRestOfTheRun() {
        val stats = CorrectionStats(null)
        // Read off the settled field rather than pressed on the correction.
        // Weaker evidence, so it handicaps the pair without retiring it.
        stats.recordRevert("teh", "the", deliberate = false)
        assertEquals(Penalty.PENALIZED, stats.penalty("teh", "the"))
    }

    @Test
    fun theInProcessBlockExpiresAndHandsOverToTheCounts() {
        val stats = CorrectionStats(null)
        stats.recordRevert("teh", "the")
        assertEquals(Penalty.BLOCKED, stats.penalty("teh", "the"))
        // Twenty further verdicts and the run that earned the block is over.
        // What is left is the one persisted rejection.
        repeat(20) { stats.recordKept("clean$it", "fix$it") }
        assertEquals(Penalty.PENALIZED, stats.penalty("teh", "the"))
    }

    @Test
    fun endFieldSessionDropsTheInProcessBlock() {
        val stats = CorrectionStats(null)
        stats.recordRevert("teh", "the")
        assertEquals(Penalty.BLOCKED, stats.penalty("teh", "the"))
        stats.endFieldSession()
        assertEquals(Penalty.PENALIZED, stats.penalty("teh", "the"))
    }

    @Test
    fun aRetiredPairIsAskedAboutAgainAfterAQuietSpell() {
        val f = file()
        repeat(2) {
            CorrectionStats(f).apply {
                recordRevert("teh", "the")
                save()
            }
        }
        assertEquals(Penalty.BLOCKED, CorrectionStats(f).penalty("teh", "the"))
        // Forty-one quiet save-generations of unrelated activity let the pair
        // out to be offered, long before the expiry clock would decay it.
        repeat(41) {
            CorrectionStats(f).apply {
                recordKept("unrelated", "activity")
                save()
            }
        }
        assertEquals(Penalty.PROBATION, CorrectionStats(f).penalty("teh", "the"))
    }

    @Test
    fun lightNeverRetiresAPair() {
        val stats = CorrectionStats(null).apply { memory = UndoMemory.LIGHT }
        repeat(3) { stats.recordRevert("teh", "the") }
        stats.endFieldSession()
        assertEquals(Penalty.PENALIZED, stats.penalty("teh", "the"))
    }

    @Test
    fun strictRetiresOnTheFirstUndoHoweverItWasRead() {
        val f = file()
        CorrectionStats(f).apply {
            memory = UndoMemory.STRICT
            recordRevert("teh", "the", deliberate = false)
            save()
        }
        val next = CorrectionStats(f).apply { memory = UndoMemory.STRICT }
        assertEquals(Penalty.BLOCKED, next.penalty("teh", "the"))
    }

    @Test
    fun strictNeverLetsARetiredPairOutOnProbation() {
        val f = file()
        CorrectionStats(f).apply {
            memory = UndoMemory.STRICT
            recordRevert("teh", "the")
            save()
        }
        repeat(41) {
            CorrectionStats(f).apply {
                recordKept("unrelated", "activity")
                save()
            }
        }
        val next = CorrectionStats(f).apply { memory = UndoMemory.STRICT }
        assertEquals(Penalty.BLOCKED, next.penalty("teh", "the"))
    }

    @Test
    fun offRemembersNoPairButStillCountsTheRate() {
        val stats = CorrectionStats(null).apply { memory = UndoMemory.OFF }
        repeat(20) { stats.recordRevert("w$it", "f$it") }
        assertEquals(Penalty.NONE, stats.penalty("w0", "f0"))
        // The adaptive gate is its own setting and still sees the truth.
        assertEquals(2.5, stats.confidenceMultiplier(), 1e-9)
    }

    @Test
    fun nullFileModeIsSessionOnly() {
        val stats = CorrectionStats(null)
        stats.recordRevert("teh", "the")
        stats.save() // no-op, no crash
        assertEquals(Penalty.BLOCKED, stats.penalty("teh", "the"))
    }
}
