package com.wasimaster.wmkeyboard.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTranscribeTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun config(body: String): JsonObject =
        json(body)["generation_config"]!!.jsonObject["transcription_config"]!!.jsonObject

    @Test
    fun `the model field picks the clip or the live model`() {
        assertEquals("gemini-3.5-transcribe", GeminiTranscribe.modelId(" "))
        assertEquals("gemini-3.5-transcribe", GeminiTranscribe.modelId("models/gemini-3.5-transcribe"))
        assertFalse(GeminiTranscribe.isLive(""))
        assertTrue(GeminiTranscribe.isLive("gemini-3.5-transcribe-live"))
        assertEquals("gemini-3.5-transcribe", GeminiTranscribe.clipModel("models/gemini-3.5-transcribe-live"))
        assertEquals("gemini-3.5-transcribe-live", GeminiTranscribe.liveModel(""))
        assertEquals("gemini-3.5-transcribe-live", GeminiTranscribe.liveModel("gemini-3.5-transcribe-live"))
    }

    @Test
    fun `a clip goes inline with smart mode, its languages and its words`() {
        val body = GeminiTranscribe.requestBody(
            "gemini-3.5-transcribe", "QUJD", "audio/ogg",
            GeminiTranscribe.Request(languages = listOf("bn-BD"), vocabulary = listOf(" Avro ", "", "Avro", "WM")),
        )
        val root = json(body)
        assertEquals("models/gemini-3.5-transcribe", root["model"]!!.jsonPrimitive.content)
        val audio = root["input"]!!.jsonArray.single().jsonObject
        assertEquals("audio", audio["type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", audio["data"]!!.jsonPrimitive.content)
        assertEquals("audio/ogg", audio["mime_type"]!!.jsonPrimitive.content)
        val config = config(body)
        assertEquals("smart", config["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("bn-BD"), config["language_codes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Avro", "WM"), config["custom_vocabulary"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `a phrase with text before it is sent that text to edit`() {
        val config = config(
            GeminiTranscribe.requestBody(
                "gemini-3.5-transcribe", "", "audio/wav",
                GeminiTranscribe.Request(sourceText = "  Let's meet on Tuesday. "),
            ),
        )
        val mode = config["mode"]!!.jsonObject
        assertEquals("smart", mode["type"]!!.jsonPrimitive.content)
        assertEquals("Let's meet on Tuesday.", mode["source_text"]!!.jsonPrimitive.content)
        assertNull(config["language_codes"])
        assertNull(config["custom_vocabulary"])
    }

    @Test
    fun `plain voice typing is verbatim and edits nothing`() {
        val config = config(
            GeminiTranscribe.requestBody(
                "gemini-3.5-transcribe", "", "audio/wav",
                GeminiTranscribe.Request(smart = false, sourceText = "earlier"),
            ),
        )
        assertEquals("verbatim", config["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text past the limit is not sent to edit`() {
        val long = "a".repeat(GeminiTranscribe.MAX_SOURCE_CHARS + 1)
        val config = config(
            GeminiTranscribe.requestBody("m", "", "audio/wav", GeminiTranscribe.Request(sourceText = long)),
        )
        assertEquals("smart", config["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the transcript is read from every shape the answer comes in`() {
        val steps = """
            {"id":"interactions/1","status":"completed","steps":[
              {"type":"user_input","content":[{"type":"text","text":"ignored"}]},
              {"id":"s","type":"model_output","content":[{"type":"text","text":" Hello world ","annotations":[]}]}
            ]}
        """
        assertEquals("Hello world", GeminiTranscribe.parseText(steps))
        assertEquals("Hi", GeminiTranscribe.parseText("""{"output_text":" Hi "}"""))
        assertEquals("Hey", GeminiTranscribe.parseText("""{"interaction":{"output_text":"Hey"}}"""))
        assertEquals("Yo", GeminiTranscribe.parseText("""{"outputs":[{"type":"text","text":"Yo"}]}"""))
        assertEquals("", GeminiTranscribe.parseText("""{"steps":[]}"""))
        assertEquals("", GeminiTranscribe.parseText("not json"))
    }

    @Test
    fun `a live session is set up for the keyboard to mark the phrase`() {
        val setup = json(
            GeminiTranscribe.liveSetup(
                "",
                GeminiTranscribe.Request(languages = listOf("en-US"), vocabulary = listOf("Rambler"), sourceText = "Hi."),
            ),
        )["setup"]!!.jsonObject
        assertEquals("models/gemini-3.5-transcribe-live", setup["model"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            setup["realtimeInputConfig"]!!.jsonObject["automaticActivityDetection"]!!.jsonObject["disabled"]!!.jsonPrimitive.content,
        )
        val transcription = setup["inputAudioTranscription"]!!.jsonObject
        assertEquals("SMART", transcription["mode"]!!.jsonPrimitive.content)
        assertEquals("Hi.", transcription["sourceText"]!!.jsonPrimitive.content)
        assertEquals(listOf("en-US"), transcription["languageCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("Rambler"), transcription["customVocabulary"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `live audio is 16-bit PCM at 16 kHz`() {
        val audio = json(GeminiTranscribe.liveAudio(floatArrayOf(0f, 1f)))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        val bytes = java.util.Base64.getDecoder().decode(audio["data"]!!.jsonPrimitive.content)
        assertEquals(4, bytes.size)
    }

    @Test
    fun `the server's messages are read`() {
        assertTrue(GeminiTranscribe.parseLive("""{"setupComplete":{}}""").setupComplete)
        assertEquals("hel", GeminiTranscribe.parseLive("""{"serverContent":{"interimInputTranscription":{"text":"hel"}}}""").interim)
        val last = GeminiTranscribe.parseLive("""{"serverContent":{"inputTranscription":{"text":"Hello."},"turnComplete":true}}""")
        assertEquals("Hello.", last.final)
        assertTrue(last.turnComplete)
        assertTrue(GeminiTranscribe.parseLive("""{"serverContent":{"generationComplete":true}}""").turnComplete)
        assertFalse(GeminiTranscribe.parseLive("garbage").setupComplete)
    }

    @Test
    fun `only the new words are found when the text was moved under the phrase`() {
        assertEquals("and dogs.", GeminiTranscribe.addedPart(" I like cats. ", "I like cats. and dogs."))
        // The start was edited: the words after its last ones are the new ones.
        assertEquals("And dogs.", GeminiTranscribe.addedPart("I like cats. They purr.", "I love cats. They purr. And dogs."))
        assertEquals("Fresh words.", GeminiTranscribe.addedPart("", "Fresh words."))
        assertEquals("Nothing shared.", GeminiTranscribe.addedPart("old text", "Nothing shared."))
    }

    @Test
    fun `an answer is taken as the whole text only when it reads as one`() {
        assertTrue(GeminiTranscribe.rewritesWhole("Let's meet on Tuesday.", "Let's meet on Wednesday at 2:00 PM."))
        assertTrue(GeminiTranscribe.rewritesWhole("One. Two. Three. Four.", "One."))
        assertFalse(GeminiTranscribe.rewritesWhole("Let's meet on Tuesday at the cafe by the river.", "Sounds good."))
        assertFalse(GeminiTranscribe.rewritesWhole("", "Anything."))
    }
}
