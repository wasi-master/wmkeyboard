package com.wasimaster.wmkeyboard.core.voice.vosk

import com.wasimaster.wmkeyboard.core.voice.VoiceChoices
import com.wasimaster.wmkeyboard.core.voice.executorch.EtCatalog
import com.wasimaster.wmkeyboard.core.voice.executorch.EtEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vosk's parts that need no library (#1165): reading its JSON, keeping the
 * utterances it hands over one by one, and the catalogue. The library itself
 * was not run here; see native/vosk-jni.
 */
class VoskTest {

    // ---- what Vosk writes ----

    @Test fun `text and partial come out of their own fields`() {
        assertEquals("hello world", VoskText.text("""{"text" : "hello world"}"""))
        assertEquals("hello", VoskText.partial("""{"partial" : "hello"}"""))
        assertEquals("", VoskText.text("""{"partial" : "hello"}"""))
        assertEquals("", VoskText.text("""{"text" : ""}"""))
    }

    @Test fun `anything unreadable is no words, not a crash`() {
        assertEquals("", VoskText.text(""))
        assertEquals("", VoskText.text("not json"))
        assertEquals("", VoskText.text("""["text"]"""))
        assertEquals("", VoskText.text("""{"text": {"nested": 1}}"""))
    }

    @Test fun `results with alternatives or word timings keep their text`() {
        val raw = """{"result": [{"conf": 1.0, "word": "guten", "start": 0.1, "end": 0.4}], "text": "guten tag"}"""
        assertEquals("guten tag", VoskText.text(raw))
    }

    @Test fun `chinese and japanese lose the spaces vosk puts between words`() {
        assertEquals("你好世界", VoskText.tidy("你好 世界"))
        assertEquals("今日はいい天気です", VoskText.tidy("今日 は いい 天気 です"))
        // A Latin word beside them keeps its space.
        assertEquals("用 iPhone 拍照", VoskText.tidy("用 iPhone 拍照"))
    }

    @Test fun `korean and every spaced script keep their spaces`() {
        assertEquals("안녕 하세요", VoskText.tidy("안녕 하세요"))
        assertEquals("привет мир", VoskText.tidy("  привет   мир "))
    }

    @Test fun `utterances join with one space, empty ones dropped`() {
        assertEquals("one two three", VoskText.join(listOf("one", "", "two"), "three"))
        assertEquals("one", VoskText.join(listOf("one"), ""))
        assertEquals("", VoskText.join(emptyList(), ""))
        assertEquals("你好世界", VoskText.join(listOf("你好"), "世界"))
    }

    // ---- one phrase ----

    /** Ends an utterance on every chunk listed in [endsAt], 1-based, with the words given there. */
    private class FakeDecoder(private val endsAt: Map<Int, String>, private val partial: String, private val last: String) : VoskDecoder {
        var chunks = 0
        var freed = 0
        private var ended: String? = null

        override fun accept(samples: FloatArray, count: Int): Int {
            chunks++
            ended = endsAt[chunks]
            return if (ended != null) 1 else 0
        }

        override fun result(): String = """{"text": "${ended.orEmpty()}"}"""
        override fun partial(): String = """{"partial": "$partial"}"""
        override fun final(): String = """{"text": "$last"}"""
        override fun free() {
            freed++
        }
    }

    @Test fun `a phrase keeps every utterance vosk hands over, then the last`() {
        val decoder = FakeDecoder(mapOf(2 to "good morning", 4 to "how are you"), partial = "i am", last = "i am fine")
        var closed = 0
        val stream = VoskStream(decoder) { closed++ }
        repeat(3) { stream.feed(FloatArray(1600)) }
        assertEquals("good morning i am", stream.text())
        stream.feed(FloatArray(1600))
        assertEquals("good morning how are you i am", stream.text())
        assertEquals("good morning how are you i am fine", stream.finish())
        assertEquals(1, decoder.freed)
        assertEquals(1, closed)
    }

    @Test fun `a closed phrase frees once and answers with what it had`() {
        val decoder = FakeDecoder(mapOf(1 to "hello"), partial = "", last = "")
        var closed = 0
        val stream = VoskStream(decoder) { closed++ }
        stream.feed(FloatArray(10))
        stream.close()
        stream.close()
        stream.feed(FloatArray(10))
        assertEquals("hello", stream.text())
        assertEquals("hello", stream.finish())
        assertEquals(1, decoder.freed)
        assertEquals(1, closed)
    }

    @Test fun `an empty chunk is not fed`() {
        val decoder = FakeDecoder(emptyMap(), partial = "", last = "")
        VoskStream(decoder) {}.feed(FloatArray(10), 0)
        assertEquals(0, decoder.chunks)
    }

    @Test fun `vosk never ends a phrase by itself, the service's detector does`() {
        assertFalse(VoskStream(FakeDecoder(emptyMap(), "", "")) {}.endpoint)
    }

    // ---- the catalogue ----

    private val models = EtCatalog.forEngine(EtEngine.VOSK)

    @Test fun `every vosk model is one pinned zip from alphacephei`() {
        assertTrue(models.size >= 29)
        for (model in models) {
            val zip = model.files.single()
            assertTrue(model.id, zip.archive)
            assertTrue(model.id, zip.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(model.id, zip.bytes > 20_000_000L)
            assertEquals(model.id, "https://alphacephei.com/vosk/models/${zip.path}", model.url(zip))
            assertFalse(model.id, model.writesPunctuation)
            assertTrue(model.id, model.id.startsWith("vosk-"))
        }
        assertEquals(models.size, models.map { it.id }.distinct().size)
        assertEquals(models.size, models.map { it.dirName }.distinct().size)
    }

    @Test fun `every language the engine claims has a model, and no model claims more`() {
        val covered = models.flatMap { it.languages.orEmpty() }.toSet()
        assertEquals(EtEngine.VOSK.languages, covered)
    }

    @Test fun `the engine is live, on its own thread, and offered per language`() {
        assertTrue(EtEngine.VOSK.live)
        assertFalse(EtEngine.VOSK.onnx)
        assertTrue(EtEngine.VOSK.supports("de_AT"))
        assertTrue(EtEngine.VOSK.supports("uk"))
        assertFalse(EtEngine.VOSK.supports("bn"))
        assertTrue("vosk" in VoiceChoices.ENGINE_ORDER)
        val german = EtCatalog.byId("vosk-de")!!
        val choices = VoiceChoices.forLanguage("de", false, emptyList(), listOf(german), false)
        assertEquals(listOf("system", "vosk:vosk-de"), choices.map { it.key })
        assertFalse(VoiceChoices.forLanguage("fr", false, emptyList(), listOf(german), false).any { it.engine == "vosk" })
    }

    @Test fun `of two english models the american one is used`() {
        val english = models.filter { "en" in it.languages.orEmpty() }
        assertEquals("vosk-en-us", english.maxWithOrNull(com.wasimaster.wmkeyboard.core.voice.executorch.EtModel.ORDER)?.id)
    }
}
