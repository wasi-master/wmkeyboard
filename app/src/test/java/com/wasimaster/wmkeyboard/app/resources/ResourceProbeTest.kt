package com.wasimaster.wmkeyboard.app.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure halves of [ResourceProbe] (#1163): reading `/proc` lines and folding them. */
class ResourceProbeTest {

    @Test
    fun `a task stat line gives the name and user plus system time`() {
        val line = "4242 (RenderThread) S 4100 4100 0 0 -1 4194368 900 0 0 0 1234 56 0 0 10 -10 40 0 123"
        assertEquals("RenderThread" to 1290L, parseTaskStat(line))
    }

    @Test
    fun `a name with spaces and parentheses is read up to the last one`() {
        val line = "77 (Jit (thread) pool) S 1 1 0 0 -1 0 0 0 0 0 7 3 0 0 20 0 1 0 5"
        assertEquals("Jit (thread) pool" to 10L, parseTaskStat(line))
    }

    @Test
    fun `a line that is not a stat line gives nothing`() {
        assertNull(parseTaskStat(""))
        assertNull(parseTaskStat("12 (short) S 1 2"))
    }

    @Test
    fun `threads are grouped by what their names say`() {
        assertEquals(ThreadGroup.MAIN, threadGroupOf("wasimaster.wmke", isMain = true))
        assertEquals(ThreadGroup.DRAWING, threadGroupOf("RenderThread"))
        assertEquals(ThreadGroup.DRAWING, threadGroupOf("hwuiTask1"))
        assertEquals(ThreadGroup.MODELS, threadGroupOf("whisper-cpp"))
        assertEquals(ThreadGroup.MODELS, threadGroupOf("llama-worker"))
        assertEquals(ThreadGroup.MODELS, threadGroupOf("sherpa"))
        assertEquals(ThreadGroup.BACKGROUND, threadGroupOf("DefaultDispatch"))
        assertEquals(ThreadGroup.NETWORK, threadGroupOf("OkHttp Dispatch"))
        assertEquals(ThreadGroup.RUNTIME, threadGroupOf("Binder:4100_3"))
        assertEquals(ThreadGroup.RUNTIME, threadGroupOf("HeapTaskDaemon"))
        assertEquals(ThreadGroup.OTHER, threadGroupOf("Thread-12"))
    }

    @Test
    fun `the first look has totals and no live share`() {
        val now = listOf(
            ResourceProbe.ThreadTime(100, "wasimaster.wmke", 300),
            ResourceProbe.ThreadTime(101, "RenderThread", 100),
        )
        val groups = groupCpu(now, previous = null, intervalMs = 0, ticksPerSecond = 100, mainTid = 100)
        assertEquals(listOf(ThreadGroup.MAIN, ThreadGroup.DRAWING), groups.map { it.group })
        assertEquals(3000L, groups[0].totalMs)
        assertNull(groups[0].livePercent)
    }

    @Test
    fun `the live share is time used over the interval, against one core`() {
        val before = listOf(
            ResourceProbe.ThreadTime(100, "main", 300),
            ResourceProbe.ThreadTime(102, "whisper", 0),
        )
        val now = listOf(
            ResourceProbe.ThreadTime(100, "main", 310),
            // 100 ticks in 2 s at 100 Hz: half a core.
            ResourceProbe.ThreadTime(102, "whisper", 100),
            // New since the last look: all of its time is new.
            ResourceProbe.ThreadTime(103, "whisper", 20),
        )
        val groups = groupCpu(now, before, intervalMs = 2000, ticksPerSecond = 100, mainTid = 100)
        val models = groups.first { it.group == ThreadGroup.MODELS }
        assertEquals(60f, models.livePercent!!, 0.01f)
        assertEquals(listOf("whisper"), models.topThreads)
        assertEquals(5f, groups.first { it.group == ThreadGroup.MAIN }.livePercent!!, 0.01f)
    }

    @Test
    fun `service class names read as words`() {
        assertEquals("Screen context", humanServiceName("com.example.core.ScreenContextService"))
        assertEquals("Harper spell checker", humanServiceName("com.example.spell.HarperSpellCheckerService"))
        assertEquals("WM keyboard", humanServiceName("com.example.ime.WMKeyboardService"))
        assertEquals("Clock ring", humanServiceName("ClockRingService"))
    }
}
