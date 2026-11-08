package com.wasimaster.wmkeyboard.core.voice.parakeet

import org.junit.Assert.assertEquals
import org.junit.Test

class ParakeetStreamTest {

    /** A transcriber that says how many samples it was given, and costs [costMs] of clock. */
    private class Fake(var costMs: Long = 0) {
        var clock = 0L
        val passes = ArrayList<Int>()
        fun transcribe(pcm: FloatArray, count: Int): String {
            passes += count
            clock += costMs
            return "n=$count first=${pcm[0]}"
        }
    }

    private fun stream(fake: Fake, max: Int = 1_000_000, step: Int = 100, pace: Int = 3) =
        ParakeetStream(max, fake::transcribe, now = { fake.clock }, minStepSamples = step, paceFactor = pace)

    @Test
    fun `nothing is transcribed before a step of audio`() {
        val fake = Fake()
        val s = stream(fake)
        s.feed(FloatArray(60) { 1f })
        assertEquals("", s.text())
        assertEquals(emptyList<Int>(), fake.passes)
    }

    @Test
    fun `a pass covers the whole clip so far`() {
        val fake = Fake()
        val s = stream(fake)
        s.feed(FloatArray(60) { 1f })
        s.feed(FloatArray(60) { 2f })
        assertEquals(listOf(120), fake.passes)
        assertEquals("n=120 first=1.0", s.text())
    }

    @Test
    fun `slow passes space themselves out`() {
        // 10 ms a pass, 3 times over: 30 ms of audio, 480 samples, between passes.
        val fake = Fake(costMs = 10)
        val s = stream(fake)
        s.feed(FloatArray(100))
        assertEquals(480, s.stepSamples())
        s.feed(FloatArray(400))
        assertEquals(listOf(100), fake.passes)
        s.feed(FloatArray(80))
        assertEquals(listOf(100, 580), fake.passes)
    }

    @Test
    fun `finish passes over what the last pass missed, once`() {
        val fake = Fake()
        val s = stream(fake)
        s.feed(FloatArray(150))
        s.feed(FloatArray(30))
        assertEquals("n=180 first=0.0", s.finish())
        assertEquals(listOf(150, 180), fake.passes)
    }

    @Test
    fun `finish after an up to date pass does not run another`() {
        val fake = Fake()
        val s = stream(fake)
        s.feed(FloatArray(200))
        s.finish()
        assertEquals(listOf(200), fake.passes)
    }

    @Test
    fun `a short clip that never reached a step is still transcribed at the end`() {
        val fake = Fake()
        val s = stream(fake)
        s.feed(FloatArray(40))
        assertEquals("n=40 first=0.0", s.finish())
    }

    @Test
    fun `audio past the longest clip is dropped`() {
        val fake = Fake()
        val s = stream(fake, max = 250)
        repeat(10) { s.feed(FloatArray(100)) }
        s.finish()
        assertEquals(250, fake.passes.last())
    }

    @Test
    fun `an empty clip finishes empty without a pass`() {
        val fake = Fake()
        assertEquals("", stream(fake).finish())
        assertEquals(emptyList<Int>(), fake.passes)
    }
}
