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

/** The battery half (#1163): reading Android's own estimate out of `dumpsys batterystats`. */
class BatteryStatsParseTest {

    @Test
    fun `an app uid is named the way batterystats names it`() {
        assertEquals("u0a123", batteryStatsUidLabel(10_123))
        assertEquals("u10a5", batteryStatsUidLabel(1_010_005))
        assertNull("a system uid has no app name", batteryStatsUidLabel(1_000))
    }

    @Test
    fun `the Android 12 format gives the total, the drain and the parts`() {
        val out = """
            Estimated power use (mAh):
              Capacity: 4500, Computed drain: 1200.5, actual drain: 1000-1100
              UID u0a123: 30.0 fg: 25.0 bg: 5.0 ( cpu=20.0 (12m 3s) wifi=6.5 screen=0.00 cpu=1.0 )
              UID u0a99: 300 ( cpu=300 )
        """.trimIndent()
        val estimate = parseBatteryStats(out, "u0a123")!!
        assertEquals(30.0, estimate.appMah, 0.0)
        assertEquals(1200.5, estimate.computedDrainMah!!, 0.0)
        assertEquals(4500.0, estimate.capacityMah!!, 0.0)
        assertEquals(listOf("cpu" to 21.0, "wifi" to 6.5), estimate.parts)
        assertEquals(30.0 / 1200.5, estimate.share!!, 1e-9)
    }

    @Test
    fun `the older format stops before the smeared shares`() {
        val out = "    Capacity: 3000, Computed drain: 900\n" +
            "    Uid u0a7: 12.5 ( cpu=10.0 wakelock=2.5 ) Including smearing: 40.0 ( screen=27.5 )"
        val estimate = parseBatteryStats(out, "u0a7")!!
        assertEquals(12.5, estimate.appMah, 0.0)
        assertEquals(listOf("cpu" to 10.0, "wakelock" to 2.5), estimate.parts)
    }

    @Test
    fun `a uid without a line has no estimate`() {
        assertNull(parseBatteryStats("    Capacity: 3000, Computed drain: 900\n", "u0a7"))
        assertNull("u0a1 is not u0a12", parseBatteryStats("    UID u0a12: 5.0 ( cpu=5 )", "u0a1"))
    }
}
