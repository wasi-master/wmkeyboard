package com.wasimaster.wmkeyboard.ime.sysvoice

import com.wasimaster.wmkeyboard.ime.sysvoice.AppDictationPlan.Route
import org.junit.Assert.assertEquals
import org.junit.Test

class AppDictationPlanTest {

    private fun route(
        engine: String,
        onDevice: Boolean = false,
        fallback: Boolean = false,
        server: Boolean = false,
        forward: Boolean = false,
    ) = AppDictationPlan.route(engine, onDevice, fallback, server, forward)

    @Test
    fun `an on-device engine with its model runs on the phone`() {
        assertEquals(Route.ON_DEVICE, route("whisper", onDevice = true, server = true, forward = true))
    }

    @Test
    fun `an on-device engine without a model never sends the audio away`() {
        assertEquals(Route.NONE, route("whisper", server = true, forward = true))
        assertEquals(Route.ON_DEVICE, route("sherpa", fallback = true, forward = true))
    }

    @Test
    fun `the server engine sends to the server, or stays on the phone without an address`() {
        assertEquals(Route.SERVER, route("server", server = true, fallback = true))
        assertEquals(Route.ON_DEVICE, route("server", fallback = true, forward = true))
        assertEquals(Route.NONE, route("server", forward = true))
    }

    @Test
    fun `the phone's recognizer is passed on, or falls back to a model on the phone`() {
        assertEquals(Route.FORWARD, route("system", forward = true, fallback = true))
        assertEquals(Route.ON_DEVICE, route("system", fallback = true, server = true))
        assertEquals(Route.NONE, route("system", server = true))
    }

    private val tags = mapOf("fr-FR" to "fr", "en-US" to "en", "hi-IN" to "hi")
    private val romanized = mapOf("hi_rom" to "hi")

    private fun languages(requested: String?, picked: List<String>, keyboard: String) =
        AppDictationPlan.languages(
            requestedTag = requested,
            languageForTag = tags::get,
            picked = picked,
            keyboard = keyboard,
            heard = { ids -> ids.map { romanized[it] ?: it }.distinct() },
        )

    @Test
    fun `a language the calling app names wins when it is known`() {
        assertEquals(listOf("fr"), languages("fr-FR", picked = listOf("en", "bn"), keyboard = "en"))
    }

    @Test
    fun `an unknown requested language falls back to the keyboard's choice`() {
        assertEquals(listOf("en", "bn"), languages("xx-YY", picked = listOf("en", "bn"), keyboard = "en"))
        assertEquals(listOf("bn"), languages("", picked = emptyList(), keyboard = "bn"))
    }

    @Test
    fun `the keyboard's language leads a picked set that holds it`() {
        assertEquals(listOf("bn", "en"), languages(null, picked = listOf("en", "bn"), keyboard = "bn"))
        assertEquals(listOf("de", "fr"), languages(null, picked = listOf("de", "fr"), keyboard = "en"))
    }

    @Test
    fun `a romanized language is heard as the language it is spoken as`() {
        assertEquals(listOf("hi"), languages(null, picked = emptyList(), keyboard = "hi_rom"))
    }

    @Test
    fun `a pause the app asks for wins, within bounds`() {
        assertEquals(2_000, AppDictationPlan.pauseMs(settingMs = 750, requestedMs = 2_000L))
        assertEquals(AppDictationPlan.MIN_PAUSE_MS, AppDictationPlan.pauseMs(settingMs = 750, requestedMs = 10L))
        assertEquals(AppDictationPlan.MAX_PAUSE_MS, AppDictationPlan.pauseMs(settingMs = 750, requestedMs = 60_000L))
    }

    @Test
    fun `the keyboard's pause stop is used, and off reads as the default`() {
        assertEquals(750, AppDictationPlan.pauseMs(settingMs = 750, requestedMs = null))
        assertEquals(AppDictationPlan.DEFAULT_PAUSE_MS, AppDictationPlan.pauseMs(settingMs = 0, requestedMs = null))
        assertEquals(AppDictationPlan.DEFAULT_PAUSE_MS, AppDictationPlan.pauseMs(settingMs = 0, requestedMs = -1L))
    }

    @Test
    fun `nothing heard reads as a timeout before speech and no match after it`() {
        assertEquals(
            RecognitionErrors.ERROR_SPEECH_TIMEOUT,
            RecognitionErrors.code(AppDictation.Error.NO_SPEECH, spoke = false, sdk = 34),
        )
        assertEquals(
            RecognitionErrors.ERROR_NO_MATCH,
            RecognitionErrors.code(AppDictation.Error.NO_SPEECH, spoke = true, sdk = 34),
        )
    }

    @Test
    fun `no engine is language unavailable only where Android has the code`() {
        assertEquals(
            RecognitionErrors.ERROR_LANGUAGE_UNAVAILABLE,
            RecognitionErrors.code(AppDictation.Error.NO_ENGINE, spoke = false, sdk = 31),
        )
        assertEquals(
            RecognitionErrors.ERROR_CLIENT,
            RecognitionErrors.code(AppDictation.Error.NO_ENGINE, spoke = false, sdk = 30),
        )
    }

    @Test
    fun `the level maps back onto the recognizer's decibel range`() {
        assertEquals(-2f, RecognitionErrors.rmsDb(0f), 0.001f)
        assertEquals(10f, RecognitionErrors.rmsDb(1f), 0.001f)
        assertEquals(10f, RecognitionErrors.rmsDb(3f), 0.001f)
    }
}
