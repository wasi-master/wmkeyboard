package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.voice.OpusEncoder
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends a recorded clip to a speech-to-text server the user runs, and returns
 * the text it heard (wasi-master/wmkeyboard#286).
 *
 * There is no protocol of our own. The request is OpenAI's
 * `POST /v1/audio/transcriptions`, which every self-hostable server already
 * speaks: speaches (faster-whisper), LocalAI, whisper.cpp's `whisper-server`
 * (natively on `/inference`, same fields), and the Groq and OpenAI clouds.
 *
 * The clip goes as Ogg Opus ([OpusEncoder]), about a twelfth of the WAV, and
 * as WAV where the server cannot read that: whisper.cpp's server started
 * without `--convert` reads WAV, MP3 and FLAC only.
 *
 * Blocking; call on an IO dispatcher.
 */
object TranscriptionClient {

    private const val READ_TIMEOUT_MS = 60_000

    /**
     * What a server answers an upload it cannot decode with: whisper.cpp's 400
     * ("failed to read audio data"), and the 415 and 422 other frameworks use
     * for a body they refuse. Anything else is not about the format, and a
     * second upload would fail the same way.
     */
    private val UNREADABLE_AUDIO = setOf(400, 415, 422)

    /**
     * Endpoints that refused Ogg Opus and then read WAV, for the life of the
     * process: each costs one small failed upload, once.
     */
    private val wavOnly = ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether [endpoint] is sent WAV without trying Opus: whisper.cpp's own
     * `/inference` route, whose server reads Ogg only when started with
     * `--convert`, and every endpoint that has already refused it.
     */
    internal fun sendsWav(endpoint: String): Boolean =
        endpoint.substringBefore('?').trimEnd('/').endsWith("inference") || endpoint in wavOnly

    /**
     * The address to POST to. The user types the server's API root the way the
     * AI tool's "Other service" asks for it (`http://host:8000/v1`), and the
     * endpoint is added here. An address that already names an endpoint —
     * `…/audio/transcriptions`, or whisper.cpp's own `…/inference` — is used as
     * it stands. A bare `http://host:8000` gets the standard `/v1` root.
     *
     * A server on a route of its own (#388, e.g. `POST /v1/transcribe`) is
     * reached through [path]: when it is filled in, it is added to the address
     * as typed, with no guessing and no `/v1`. A [path] that is a whole
     * address replaces [url] outright.
     */
    fun endpoint(url: String, path: String = ""): String {
        val base = url.trim().trimEnd('/')
        val route = path.trim()
        if (route.contains("://")) return route
        if (route.trim('/').isNotEmpty()) return "$base/${route.trimStart('/')}"
        val path = base.substringAfter("://", base).substringAfter('/', "").substringBefore('?')
        return when {
            path.endsWith("transcriptions") || path.endsWith("inference") -> base
            path.isEmpty() -> "$base/v1/audio/transcriptions"
            else -> "$base/audio/transcriptions"
        }
    }

    /**
     * Uploads [samples] (16 kHz mono, as [com.wasimaster.wmkeyboard.core.voice.WhisperRecorder]
     * records them) and returns the transcript, trimmed. Ogg Opus when the
     * library is here and the server reads it, WAV otherwise; an endpoint that
     * answers the Opus upload with [UNREADABLE_AUDIO] gets the WAV straight
     * after, and from then on. A phone without the library asks for it, so the
     * next clip goes compressed.
     *
     * [model] and [language] (an ISO-639-1 code) are left out of the request
     * when blank, so the server picks its default model and detects the
     * language itself.
     *
     * [prompt] is OpenAI's `prompt` field (#305): text the model treats as
     * what came before the clip, which is how names and jargon get spelled
     * right. Whisper models keep only its last 224 tokens. Left out when null.
     *
     * [path] is the server's own route, when it has one; see [endpoint].
     */
    fun transcribe(
        url: String,
        apiKey: String,
        model: String,
        language: String?,
        samples: FloatArray,
        prompt: String? = null,
        path: String = "",
    ): String {
        val endpoint = endpoint(url, path)
        val fields = buildList {
            if (model.isNotBlank()) add("model" to model.trim())
            if (!language.isNullOrBlank()) add("language" to language)
            if (!prompt.isNullOrBlank()) add("prompt" to prompt)
            add("response_format" to "json")
        }
        val headers = if (apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        } else {
            emptyMap()
        }
        fun send(file: ToolHttp.FilePart, saved: Long = 0): String = parseText(
            ToolHttp.postMultipart(
                url = endpoint,
                fields = fields,
                file = file,
                timeoutMs = READ_TIMEOUT_MS,
                headers = headers,
                source = NetSource.TRANSCRIPTION,
                route = NetLog.pathOf(endpoint),
                savedBytes = saved,
            ),
        )
        fun wav() = ToolHttp.FilePart("file", "speech.wav", "audio/wav", WavEncoder.encode(samples))
        if (sendsWav(endpoint)) return send(wav())
        val opus = OpusEncoder.encode(samples)
        if (opus == null) {
            OpusEncoder.requestLibrary()
            return send(wav())
        }
        try {
            return send(
                ToolHttp.FilePart("file", OpusEncoder.FILE_NAME, OpusEncoder.MIME_TYPE, opus),
                saved = WavEncoder.sizeOf(samples.size) - opus.size,
            )
        } catch (e: ToolHttpException) {
            if (e.status !in UNREADABLE_AUDIO) throw e
        }
        // Remembered only once WAV has worked: a 400 that was about the model
        // or the key fails this upload too and teaches nothing.
        return send(wav()).also { wavOnly += endpoint }
    }

    /**
     * The longest `prompt` each endpoint takes, in characters, once it has
     * said so in a refusal, for the life of the process. Groq's is known
     * before the first request: 896 characters, and it refuses the whole
     * clip rather than cutting the prompt.
     */
    private val promptLimits = ConcurrentHashMap<String, Int>()

    private const val GROQ_PROMPT_LIMIT = 896

    /** The prompt limit known for [endpoint], or null when it has never refused one. */
    fun promptLimit(endpoint: String): Int? = promptLimits[endpoint]
        ?: GROQ_PROMPT_LIMIT.takeIf { endpoint.substringAfter("://").substringBefore('/').endsWith("groq.com") }

    /** Remembers that [endpoint] takes a prompt of at most [limit] characters. */
    fun rememberPromptLimit(endpoint: String, limit: Int) {
        promptLimits[endpoint] = limit
    }

    private val PROMPT_LIMIT_TEXT = Regex(
        """prompt\b[^0-9]{0,60}?(\d+)\s*(?:characters|chars)\s+or\s+(?:fewer|less)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The character limit a server named when it refused a prompt as too
     * long, such as Groq's "prompt length must be 896 characters or fewer,
     * but provided prompt contains 1302 characters", or null when [t] is
     * some other failure.
     */
    fun promptLimitOf(t: Throwable): Int? {
        val message = (t as? ToolHttpException)?.apiMessage ?: return null
        return PROMPT_LIMIT_TEXT.find(message)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
    }

    /**
     * The transcript out of a response body: `{"text": …}` from every
     * OpenAI-shaped server, or the body itself from a server that answered in
     * plain text anyway.
     */
    internal fun parseText(body: String): String {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            Json.parseToJsonElement(trimmed).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull()?.trim().orEmpty()
    }
}
