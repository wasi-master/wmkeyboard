package com.wasimaster.wmkeyboard.core.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionClientTest {

    @Test
    fun `api root gets the transcription endpoint`() {
        assertEquals(
            "http://192.168.1.10:8000/v1/audio/transcriptions",
            TranscriptionClient.endpoint("http://192.168.1.10:8000/v1"),
        )
        assertEquals(
            "https://api.groq.com/openai/v1/audio/transcriptions",
            TranscriptionClient.endpoint("https://api.groq.com/openai/v1/"),
        )
    }

    @Test
    fun `bare host gets the standard v1 root`() {
        assertEquals(
            "http://10.0.2.2:8000/v1/audio/transcriptions",
            TranscriptionClient.endpoint(" http://10.0.2.2:8000/ "),
        )
    }

    @Test
    fun `an address that names an endpoint is kept`() {
        assertEquals(
            "http://host:8080/inference",
            TranscriptionClient.endpoint("http://host:8080/inference"),
        )
        assertEquals(
            "http://host/v1/audio/transcriptions",
            TranscriptionClient.endpoint("http://host/v1/audio/transcriptions/"),
        )
    }

    @Test
    fun `a route of the server's own is added to the address as typed`() {
        assertEquals(
            "http://host:8000/v1/transcribe",
            TranscriptionClient.endpoint("http://host:8000", "/v1/transcribe"),
        )
        assertEquals(
            "http://host:8000/v1/transcribe",
            TranscriptionClient.endpoint("http://host:8000/v1/", "transcribe"),
        )
        assertEquals(
            "https://other/asr",
            TranscriptionClient.endpoint("http://host:8000", " https://other/asr "),
        )
        // Blank, or nothing but a slash, keeps the automatic endpoint.
        assertEquals(
            "http://host:8000/v1/audio/transcriptions",
            TranscriptionClient.endpoint("http://host:8000/v1", " / "),
        )
    }

    @Test
    fun `json and plain replies both yield the text`() {
        assertEquals("hello world", TranscriptionClient.parseText("""{"text":" hello world "}"""))
        assertEquals(
            "hi",
            TranscriptionClient.parseText("""{"text":"hi","model":"ggml-base","language":"en"}"""),
        )
        assertEquals("plain reply", TranscriptionClient.parseText("plain reply\n"))
        assertEquals("", TranscriptionClient.parseText("""{"segments":[]}"""))
    }

    @Test
    fun `whisper cpp route is sent WAV and every other endpoint Opus first`() {
        assertTrue(TranscriptionClient.sendsWav("http://host:8080/inference"))
        assertTrue(TranscriptionClient.sendsWav("http://host:8080/inference/"))
        assertFalse(TranscriptionClient.sendsWav("http://host:8000/v1/audio/transcriptions"))
        assertFalse(TranscriptionClient.sendsWav("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertFalse(TranscriptionClient.sendsWav("http://host:8000/v1/transcribe"))
    }

    @Test
    fun `multipart body carries fields then the file`() {
        val file = byteArrayOf(1, 2, 3)
        val body = ToolHttp.multipartBody(
            "B",
            listOf("model" to "whisper-1", "language" to "bn"),
            ToolHttp.FilePart("file", "speech.wav", "audio/wav", file),
        )
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("--B\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n"))
        assertTrue(text.contains("name=\"language\"\r\n\r\nbn\r\n"))
        val fileHeader = "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n"
        val start = text.indexOf(fileHeader) + fileHeader.length
        assertArrayEquals(file, body.copyOfRange(start, start + file.size))
        assertTrue(text.endsWith("\r\n--B--\r\n"))
    }

    @Test
    fun readsAServersPromptLimit() {
        val groq = ToolHttpException(0, 400, "prompt length must be 896 characters or fewer, but provided prompt contains 1302 characters")
        assertEquals(896, TranscriptionClient.promptLimitOf(groq))
        assertEquals(null, TranscriptionClient.promptLimitOf(ToolHttpException(0, 400, "invalid model")))
        assertEquals(896, TranscriptionClient.promptLimit("https://api.groq.com/openai/v1/audio/transcriptions"))
        assertEquals(null, TranscriptionClient.promptLimit("http://host:8000/v1/audio/transcriptions"))
    }
}
