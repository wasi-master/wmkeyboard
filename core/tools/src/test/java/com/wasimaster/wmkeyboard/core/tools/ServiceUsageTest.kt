package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

/** The usage counts and the quota readers behind the providers' Usage group (#1135). */
class ServiceUsageTest {

    private val utc = ZoneOffset.UTC

    /** 2026-11-15T00:00Z and 2026-12-01T00:00Z. */
    private val november = 1_794_700_800_000L
    private val december = 1_796_083_200_000L

    @Before
    fun clean() = ServiceUsage.clearForTest()

    @Test
    fun `requests, tokens and credits add up within a month`() {
        ServiceUsage.record("tavily", credits = 2, nowMs = november, zone = utc)
        ServiceUsage.record("tavily", credits = 1, nowMs = november, zone = utc)
        ServiceUsage.record("anthropic", inputTokens = 100, outputTokens = 40, nowMs = november, zone = utc)
        ServiceUsage.record("anthropic", inputTokens = 10, outputTokens = 5, nowMs = november, zone = utc)
        val tavily = ServiceUsage.get("tavily", november, utc)
        assertEquals(2L, tavily.requests)
        assertEquals(3L, tavily.credits)
        val anthropic = ServiceUsage.get("anthropic", november, utc)
        assertEquals(110L, anthropic.inputTokens)
        assertEquals(45L, anthropic.outputTokens)
        assertEquals("2026-11", anthropic.month)
    }

    @Test
    fun `a new month starts the count again but keeps what the service said`() {
        ServiceUsage.record("brave", nowMs = november, zone = utc)
        ServiceUsage.report("brave", ServiceUsage.Reported(ServiceUsage.Measure.REQUESTS, remaining = 5.0, atMs = november), utc)
        val next = ServiceUsage.get("brave", december, utc)
        assertEquals(0L, next.requests)
        assertEquals("2026-12", next.month)
        assertEquals(5.0, next.reported!!.remaining!!, 0.0)
    }

    @Test
    fun `Brave's headers give the month's entry, the last in each list`() {
        val reported = ServiceUsage.braveHeaders(
            mapOf("X-RateLimit-Limit" to "1, 2000", "X-RateLimit-Remaining" to "1, 1987", "X-RateLimit-Reset" to "1, 3600"),
            november,
        )!!
        assertEquals(1987.0, reported.remaining!!, 0.0)
        assertEquals(2000.0, reported.limit!!, 0.0)
        assertEquals(november + 3_600_000, reported.resetAtMs)
        assertEquals(ServiceUsage.Window.MONTH, reported.window)
        assertNull(ServiceUsage.braveHeaders(emptyMap(), november))
    }

    @Test
    fun `an AI service's rate-limit headers read as this minute's tokens`() {
        val anthropic = ServiceUsage.aiHeaders(
            mapOf(
                "anthropic-ratelimit-tokens-remaining" to "79000",
                "anthropic-ratelimit-tokens-limit" to "80000",
                "anthropic-ratelimit-tokens-reset" to "2026-11-15T00:01:00Z",
            ),
            november,
        )!!
        assertEquals(79000.0, anthropic.remaining!!, 0.0)
        assertEquals(november + 60_000, anthropic.resetAtMs)
        assertEquals(ServiceUsage.Window.MINUTE, anthropic.window)
        val openAi = ServiceUsage.aiHeaders(
            mapOf("x-ratelimit-remaining-tokens" to "149984", "x-ratelimit-limit-tokens" to "150000", "x-ratelimit-reset-tokens" to "6m0s"),
            november,
        )!!
        assertEquals(149984.0, openAi.remaining!!, 0.0)
        assertEquals(november + 360_000, openAi.resetAtMs)
    }

    @Test
    fun `durations read the way OpenAI writes them`() {
        assertEquals(360_000L, ServiceUsage.parseDuration("6m0s"))
        assertEquals(1500L, ServiceUsage.parseDuration("1.5s"))
        assertEquals(20L, ServiceUsage.parseDuration("20ms"))
        assertNull(ServiceUsage.parseDuration("soon"))
    }

    @Test
    fun `a row with an address counts apart from its kind`() {
        assertEquals("anthropic", ServiceUsage.key("anthropic"))
        assertEquals("openai_compatible@openrouter.ai", ServiceUsage.key("openai_compatible", "https://openrouter.ai/api/v1"))
        assertEquals("ollama@192.168.0.10", ServiceUsage.key("ollama", "192.168.0.10:11434"))
    }

    @Test
    fun `the panel shows a month's allowance, never a minute's`() {
        ServiceUsage.report(
            "tavily",
            ServiceUsage.Reported(ServiceUsage.Measure.CREDITS, remaining = 812.0, window = ServiceUsage.Window.MONTH, atMs = november),
            utc,
        )
        assertEquals(812L, ServiceUsage.allowanceLeft("tavily", november))
        ServiceUsage.report(
            "openai",
            ServiceUsage.Reported(ServiceUsage.Measure.TOKENS, remaining = 5.0, window = ServiceUsage.Window.MINUTE, atMs = november),
            utc,
        )
        assertNull(ServiceUsage.allowanceLeft("openai", november))
        // Forty days on, what Tavily said is too old to show.
        assertNull(ServiceUsage.allowanceLeft("tavily", november + 40L * 24 * 3_600_000))
    }
}
