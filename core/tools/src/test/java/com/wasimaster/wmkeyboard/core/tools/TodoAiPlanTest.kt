package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TodoAiPlanTest {

    private val task = "Do my taxes"

    @Test
    fun `a fenced proposal is read and taken out of the reply`() {
        val raw = """
            Here is a plan you can start tonight.

            ```json
            {"rewrite": "File my 2026 tax return", "steps": [{"text": "Gather payslips", "minutes": 20}, {"text": "Fill in the form", "minutes": 45}]}
            ```
        """.trimIndent()
        val plan = TodoAiPlans.parse(raw, task)
        assertEquals("Here is a plan you can start tonight.", plan.reply)
        assertEquals("File my 2026 tax return", plan.rewrite)
        assertEquals(listOf(TodoAiStep("Gather payslips", 20), TodoAiStep("Fill in the form", 45)), plan.steps)
        assertEquals(65, plan.totalMinutes)
        assertTrue(plan.proposes)
    }

    @Test
    fun `a bare object at the end counts, and strings are steps too`() {
        val plan = TodoAiPlans.parse("Try these.\n{\"steps\": [\"Open the portal\", \"- Log in\"]}", task)
        assertEquals("Try these.", plan.reply)
        assertEquals(listOf(TodoAiStep("Open the portal", null), TodoAiStep("Log in", null)), plan.steps)
        assertEquals(2 * TodoAiPlans.DEFAULT_STEP_MINUTES, plan.totalMinutes)
    }

    @Test
    fun `a small model's loose JSON still reads`() {
        val plan = TodoAiPlans.parse("```json\n{steps: [{text: 'Call the bank', minutes: '10'}],}\n```", task)
        assertEquals(listOf(TodoAiStep("Call the bank", 10)), plan.steps)
    }

    @Test
    fun `an answer with no proposal proposes nothing`() {
        val plan = TodoAiPlans.parse("When is it due?", task)
        assertEquals("When is it due?", plan.reply)
        assertFalse(plan.proposes)
    }

    @Test
    fun `the same wording is no rewrite, and a start must be a date`() {
        val plan = TodoAiPlans.parse("{\"rewrite\": \"do my taxes\", \"steps\": [\"a\"], \"start\": \"tonight\"}", task)
        assertNull(plan.rewrite)
        assertNull(plan.start)
        val dated = TodoAiPlans.parse("{\"steps\": [\"a\"], \"start\": \"2026-11-17T09:00\"}", task)
        assertEquals("2026-11-17T09:00", dated.start)
    }

    @Test
    fun `reasoning is dropped and steps are capped and de-duplicated`() {
        val many = (1..30).joinToString(",") { "\"Step $it\"" }
        val plan = TodoAiPlans.parse("<think>hmm</think>Ok.\n{\"steps\": [\"Step 1\", $many]}", task)
        assertEquals("Ok.", plan.reply)
        assertEquals(TodoAiPlans.MAX_STEPS, plan.steps.size)
        assertEquals("Step 1", plan.steps.first().text)
        assertEquals("Step 2", plan.steps[1].text)
    }

    @Test
    fun `the streaming text stops where the JSON starts`() {
        assertEquals("Sure, here goes", TodoAiPlans.visible("Sure, here goes\n```json\n{\"st"))
        assertEquals("Sure", TodoAiPlans.visible("Sure\n{\"steps\": ["))
        assertEquals("No JSON yet", TodoAiPlans.visible("No JSON yet"))
    }

    @Test
    fun `steps are booked back to back`() {
        val steps = listOf(TodoAiStep("a", 10), TodoAiStep("b", null))
        val slots = TodoAiPlans.schedule(steps, 1_000L)
        assertEquals(1_000L to 601_000L, slots[0])
        assertEquals(601_000L to 601_000L + TodoAiPlans.DEFAULT_STEP_MINUTES * 60_000L, slots[1])
    }

    @Test
    fun `a plan starts on the next quarter hour, in the phone's zone`() {
        val utc = TimeZone.getTimeZone("UTC")
        val tenPast = 10 * 60_000L
        assertEquals(15 * 60_000L, TodoAiPlans.nextQuarter(tenPast, utc))
        assertEquals(30 * 60_000L, TodoAiPlans.nextQuarter(15 * 60_000L, utc))
        assertEquals("1970-01-01T00:15", TodoAiPlans.moment(15 * 60_000L, utc))
    }

    @Test
    fun `the prompt names the one task and its steps`() {
        val system = TodoAiPlans.system(task, listOf("Find the forms"), "2026-11-16, Monday")
        assertTrue(system.contains("\"Do my taxes\""))
        assertTrue(system.contains("- Find the forms"))
        assertTrue(system.contains("2026-11-16, Monday"))
        assertTrue(system.contains("Never mention, change or plan any other task."))
    }
}
