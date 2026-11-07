package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plugin editor's budget meter reads [PluginBudget.Usage], which the
 * interpreter reports with every result ([PythonSession.Result.phases]).
 * These pin what the meter can trust: how a result's phases are read, and
 * that a real session reports each call on its own allowance.
 */
class PluginBudgetUsageTest {

    @Test
    fun `usage is empty before any call`() {
        assertEquals(PluginBudget.Usage(0L, 0L, 0L, 0L, running = false), PluginBudget.Usage.NONE)
    }

    @Test
    fun `a result's phases are read exactly`() {
        val result = PythonSession.decode(
            """
            {"ok": true, "phases": [
              {"phase": "load", "steps": 3000, "stepsLimit": 10000, "elapsedMicros": 7000, "millisLimit": 1000},
              {"phase": "render", "steps": 12, "stepsLimit": 400000, "elapsedMicros": 0, "millisLimit": 500},
              {"steps": 1},
              "junk"
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                PythonSession.Phase("load", PluginBudget.Usage(3_000L, 10_000L, 7_000_000L, 1_000L, running = false)),
                PythonSession.Phase("render", PluginBudget.Usage(12L, 400_000L, 0L, 500L, running = false)),
            ),
            result.phases,
        )
    }

    @Test
    fun `a phase with numbers missing reads as zero rather than failing`() {
        val phase = PythonSession.decode("""{"ok": true, "phases": [{"phase": "event"}]}""").phases.single()
        assertEquals(PythonSession.Phase("event", PluginBudget.Usage.NONE), phase)
    }

    @Test
    fun `an unreadable result is an internal error rather than a crash`() {
        for (text in listOf("", "not json", "[]", "42")) {
            val result = PythonSession.decode(text)
            assertFalse(text, result.ok)
            assertEquals(text, PythonSession.Error.INTERNAL, result.error?.kind)
            assertTrue(text, result.phases.isEmpty())
        }
    }

    @Test
    fun `a result carries its log, input writes, reminders flag and error`() {
        val result = PythonSession.decode(
            """
            {"ok": false, "log": ["one", 2, "three"], "inputs": [["box", "text"], ["bad"], [1, 2]],
             "remindersChanged": true,
             "error": {"kind": "abort", "message": "stopped", "line": 4, "abort": "steps"}}
            """.trimIndent(),
        )
        assertEquals(listOf("one", "2", "three"), result.log)
        assertEquals(listOf("box" to "text", "1" to "2"), result.inputs)
        assertTrue(result.remindersChanged)
        assertEquals(PythonSession.Error(PythonSession.Error.ABORT, "stopped", 4, "steps"), result.error)
        assertNull(result.ui)
        assertNull(result.value)
    }

    @Test
    fun `a transform's value is text or nothing`() {
        assertEquals("out", PythonSession.decode("""{"ok": true, "value": "out"}""").value)
        assertNull(PythonSession.decode("""{"ok": true, "value": null}""").value)
        assertNull(PythonSession.decode("""{"ok": true, "value": 42}""").value)
    }

    @Test
    fun `every reason the library stops a script has its own line`() {
        assertEquals(PluginAbortReason.INSTRUCTIONS, PluginAbortReason.ofWire("steps"))
        assertEquals(PluginAbortReason.DEADLINE, PluginAbortReason.ofWire("deadline"))
        assertEquals(PluginAbortReason.CANCELLED, PluginAbortReason.ofWire("cancelled"))
        assertEquals(PluginAbortReason.RECURSION, PluginAbortReason.ofWire("recursion"))
        assertNull(PluginAbortReason.ofWire("something newer"))
        assertNull(PluginAbortReason.ofWire(null))
        assertEquals(PluginAbortReason.entries.size, PluginAbortReason.entries.map { it.messageRes }.toSet().size)
    }

    @Test
    fun `a real session reports each call on its own allowance`() {
        val config = PythonSession.Config(
            InstalledPlugin(id = "com.example.meter", name = "Meter", version = "1"),
            PythonSession.Config.PANEL,
            storagePath = null,
            remindersPath = null,
            load = PluginLimit(instructions = 100_000, wallMillis = 1_000),
            event = PluginLimit(instructions = 50_000, wallMillis = 700),
            render = PluginLimit(instructions = 20_000, wallMillis = 300),
        )
        val python = requireNotNull(PythonSession.create(config)) { "libwm_python did not open a session" }
        try {
            val loaded = python.load(
                "n = 0\nwhile n < 2000:\n    n += 1\n\ndef on_event(e):\n    pass\n\ndef render():\n    return ui.label(str(n))",
            )
            assertTrue("${loaded.error}", loaded.ok)
            val load = loaded.phases.single { it.phase == "load" }.usage
            assertEquals(100_000L, load.instructionLimit)
            assertEquals(1_000L, load.wallLimitMillis)
            assertTrue("a 2000-round loop took ${load.instructions} steps", load.instructions >= 2_000)
            assertEquals(20_000L, loaded.phases.single { it.phase == "render" }.usage.instructionLimit)

            val handled = python.event(PluginEvent.Click("go"))
            assertTrue("${handled.error}", handled.ok)
            assertEquals(listOf("event", "render"), handled.phases.map { it.phase })
            val event = handled.phases.first().usage
            assertEquals(50_000L, event.instructionLimit)
            assertEquals(700L, event.wallLimitMillis)
            // A fresh call counts from zero: the event spends a handful, not the load's thousands.
            assertTrue("the event took ${event.instructions} steps", event.instructions in 1 until load.instructions)
            assertTrue(handled.phases.all { !it.usage.running && it.usage.elapsedNanos >= 0 })
        } finally {
            python.close()
        }
    }
}
