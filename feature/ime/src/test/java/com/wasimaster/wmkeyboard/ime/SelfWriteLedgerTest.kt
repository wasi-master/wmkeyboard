package com.wasimaster.wmkeyboard.ime

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-shot tokens the copy listener lets the keyboard's own writes go by with (#1215). */
class SelfWriteLedgerTest {
    @After
    fun reset() = SelfWriteLedger.clear()

    @Test
    fun `a token is let go by once`() {
        SelfWriteLedger.record("a", now = 1_000)
        assertTrue(SelfWriteLedger.consume("a", now = 1_100))
        assertFalse(SelfWriteLedger.consume("a", now = 1_200))
    }

    @Test
    fun `a stale token no longer hides a copy`() {
        SelfWriteLedger.record("a", now = 1_000)
        assertFalse(SelfWriteLedger.consume("a", now = 1_000 + SelfWriteLedger.TTL_MILLIS + 1))
    }

    @Test
    fun `no token, or one never recorded, is not a self-write`() {
        assertFalse(SelfWriteLedger.consume(null))
        assertFalse(SelfWriteLedger.consume(""))
        assertFalse(SelfWriteLedger.consume("never"))
    }

    @Test
    fun `a burst of writes keeps the newest tokens`() {
        repeat(100) { SelfWriteLedger.record("t$it", now = 1_000L + it) }
        assertTrue(SelfWriteLedger.consume("t99", now = 1_200))
        assertFalse(SelfWriteLedger.consume("t0", now = 1_200))
    }
}
