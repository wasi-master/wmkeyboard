package com.wasimaster.wmkeyboard.core.tools

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.voice.OpusEncoder
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Google's Gemini 3.5 Transcribe, the voice list's Gemini row: the speech
 * model behind the kind of dictation Gboard calls Rambler, reached through
 * the public Gemini API with the user's own key.
 *
 * Two ways in, picked by the row's model:
 *
 * - [MODEL], a whole clip at a time: `POST /v1beta/interactions` with the
 *   clip inline, after the user stops or pauses. Cheaper, and the default.
 * - [LIVE_MODEL], the Live API over a WebSocket ([LiveSession]): the clip
 *   is streamed while it is said and the words come back as they are heard.
 *
 * [Request.smart] is the API's smart transcription: filler words, stutters
 * and false starts go, a correction said aloud ("Tuesday, no, Wednesday")
 * keeps only the correction, and numbers, dates and lists are written the
 * way they are read. With [Request.sourceText] the model also takes the
 * phrase as an edit of that text ("make it more formal", "delete the last
 * sentence"), and what comes back is the whole text after the edit, which
 * the caller swaps in for the old. Off, the words come back verbatim.
 *
 * Blocking; call on an IO dispatcher.
 */
object GeminiTranscribe {

    const val MODEL = "gemini-3.5-transcribe"
    const val LIVE_MODEL = "gemini-3.5-transcribe-live"

    /** The API takes 1,000 words to listen for, and reads the first hundred or so best. */
    const val MAX_VOCABULARY = 100

    /**
     * The longest [Request.sourceText] sent, in characters. The API's limit
     * is 64 KiB; four bytes a character at worst keeps well inside it.
     */
    const val MAX_SOURCE_CHARS = 16_000

    private const val READ_TIMEOUT_MS = 60_000
    private const val SAMPLE_RATE = 16_000
    private const val INTERACTIONS = "/v1beta/interactions"
    private const val LIVE_ROUTE = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    /**
     * What a clip is transcribed with. [languages] are BCP-47 tags (`bn-BD`);
     * empty lets the model tell the language itself, which it also does
     * mid-sentence. [vocabulary] is the words to listen for, the user's first.
     */
    data class Request(
        val languages: List<String> = emptyList(),
        val vocabulary: List<String> = emptyList(),
        val smart: Boolean = true,
        val sourceText: String = "",
    )

    /** [model] as the API names it, without `models/`; blank is [MODEL]. */
    fun modelId(model: String): String = model.trim().removePrefix("models/").trim().ifEmpty { MODEL }

    /** Whether [model] is a Live API model, which streams the clip as it is said. */
    fun isLive(model: String): Boolean = modelId(model).endsWith("-live")

    /** The whole-clip model behind [model]: the one a clip the stream could not finish goes to. */
    fun clipModel(model: String): String = modelId(model).removeSuffix("-live")

    /** The streaming model behind [model]. */
    fun liveModel(model: String): String = modelId(model).let { if (it.endsWith("-live")) it else "$it-live" }

    /**
     * Sends [samples] (16 kHz mono) as one clip and returns the transcript,
     * trimmed. Ogg Opus where the library is here, WAV otherwise. A request
     * the API refuses with a 400 while naming languages is sent once more
     * naming none: a keyboard language the model does not list is refused
     * outright, and the model tells languages apart well by itself.
     */
    fun transcribe(apiKey: String, model: String, samples: FloatArray, request: Request): String {
        val opus = OpusEncoder.encode(samples)
        if (opus == null) OpusEncoder.requestLibrary()
        val audio = opus ?: WavEncoder.encode(samples)
        val mimeType = if (opus != null) OpusEncoder.MIME_TYPE else "audio/wav"
        val base64 = java.util.Base64.getEncoder().encodeToString(audio)
        val url = ServiceEndpoints.base(ServiceEndpoint.GEMINI) + INTERACTIONS
        fun send(asked: Request): String = parseText(
            ToolHttp.postJson(
                url = url,
                body = requestBody(clipModel(model), base64, mimeType, asked),
                timeoutMs = READ_TIMEOUT_MS,
                headers = mapOf("x-goog-api-key" to apiKey.trim()),
                source = NetSource.TRANSCRIPTION,
                route = INTERACTIONS,
            ),
        )
        return try {
            send(request)
        } catch (e: ToolHttpException) {
            if (e.status != 400 || request.languages.isEmpty()) throw e
            send(request.copy(languages = emptyList()))
        }
    }

    /** The JSON body of one whole-clip request. */
    internal fun requestBody(model: String, audioBase64: String, mimeType: String, request: Request): String =
        buildJsonObject {
            put("model", "models/$model")
            putJsonArray("input") {
                addJsonObject {
                    put("type", "audio")
                    put("data", audioBase64)
                    put("mime_type", mimeType)
                }
            }
            putJsonObject("generation_config") {
                putJsonObject("transcription_config") {
                    if (request.languages.isNotEmpty()) putJsonArray("language_codes") { request.languages.forEach { add(it) } }
                    val words = vocabulary(request.vocabulary)
                    if (words.isNotEmpty()) putJsonArray("custom_vocabulary") { words.forEach { add(it) } }
                    val source = source(request)
                    when {
                        !request.smart -> put("mode", "verbatim")
                        source.isEmpty() -> put("mode", "smart")
                        else -> putJsonObject("mode") {
                            put("type", "smart")
                            put("source_text", source)
                        }
                    }
                }
            }
        }.toString()

    /**
     * The transcript out of an interaction: `output_text` where the answer
     * carries it, else the text parts of its model output steps, else of its
     * `outputs`. Wrapped in `interaction` or not. Empty for none.
     */
    internal fun parseText(body: String): String {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return ""
        val interaction = root["interaction"] as? JsonObject ?: root
        interaction.str("output_text")?.let { return it.trim() }
        val steps = (interaction["steps"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it.str("type") == null || it.str("type") == "model_output" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
        val fromSteps = textOf(steps)
        if (fromSteps.isNotEmpty()) return fromSteps.trim()
        return textOf((interaction["outputs"] as? JsonArray).orEmpty()).trim()
    }

    private fun textOf(parts: List<JsonElement>): String = parts.mapNotNull { it as? JsonObject }
        .filter { it.str("type") == null || it.str("type") == "text" }
        .mapNotNull { it.str("text") }
        .joinToString("")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun vocabulary(words: List<String>): List<String> =
        words.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_VOCABULARY)

    /** The text a phrase may edit, or none when it is over [MAX_SOURCE_CHARS]: half of it would be the wrong text to edit. */
    private fun source(request: Request): String =
        if (!request.smart) "" else request.sourceText.trim().takeIf { it.length <= MAX_SOURCE_CHARS }.orEmpty()

    /**
     * Whether [after], the model's answer to a phrase sent with [before] as
     * its source, reads as the whole text again rather than only the new
     * words. The API answers with the whole text; this is the guard that
     * keeps an answer of the other kind from replacing what was dictated
     * before it: the answer has to start with the text's first word, or be
     * at least half as long.
     */
    fun rewritesWhole(before: String, after: String): Boolean {
        val old = before.trim()
        val new = after.trim()
        if (old.isEmpty()) return false
        if (new.length * 2 >= old.length) return true
        val first = old.substringBefore(' ').trimEnd { !it.isLetterOrDigit() }
        return first.isNotEmpty() && new.startsWith(first, ignoreCase = true)
    }

    /**
     * What [after] says beyond [before]: the words that follow [before], or
     * follow its last few words where the start of it was edited. For a text
     * that was typed over while the phrase was out, so only the new words go
     * in. The whole of [after] when nothing of [before] can be found in it.
     */
    fun addedPart(before: String, after: String): String {
        val old = before.trim()
        val new = after.trim()
        if (old.isEmpty()) return new
        if (new.startsWith(old)) return new.substring(old.length).trim()
        val words = old.split(WHITESPACE).filter { it.isNotEmpty() }
        for (size in minOf(TAIL_WORDS, words.size) downTo 1) {
            val tail = words.takeLast(size).joinToString(" ")
            val at = new.lastIndexOf(tail)
            if (at >= 0) return new.substring(at + tail.length).trim()
        }
        return new
    }

    private val WHITESPACE = Regex("\\s+")

    /** The most of the text's last words [addedPart] looks for. */
    private const val TAIL_WORDS = 12

    // ---- the Live API ----

    private val sockets: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun liveUrl(apiKey: String): String {
        val base = ServiceEndpoints.base(ServiceEndpoint.GEMINI)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base$LIVE_ROUTE?key=" + URLEncoder.encode(apiKey.trim(), "UTF-8")
    }

    /**
     * The setup message of a live session. The phrase is marked by the
     * keyboard ([LiveSession] sends its start and end), not by the server's
     * own voice detection: the keyboard already ends a clip at the user's
     * pause, and its end is what asks for the finished text.
     */
    internal fun liveSetup(model: String, request: Request): String = buildJsonObject {
        putJsonObject("setup") {
            put("model", "models/" + liveModel(model))
            putJsonObject("generationConfig") { putJsonArray("responseModalities") { add("TEXT") } }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") { put("disabled", true) }
            }
            putJsonObject("inputAudioTranscription") {
                if (request.languages.isNotEmpty()) putJsonArray("languageCodes") { request.languages.forEach { add(it) } }
                val words = vocabulary(request.vocabulary)
                if (words.isNotEmpty()) putJsonArray("customVocabulary") { words.forEach { add(it) } }
                put("mode", if (request.smart) "SMART" else "VERBATIM")
                val source = source(request)
                if (source.isNotEmpty()) put("sourceText", source)
            }
        }
    }.toString()

    /** One chunk of the clip, as the Live API takes it: 16-bit PCM at 16 kHz, in base64. */
    internal fun liveAudio(samples: FloatArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", java.util.Base64.getEncoder().encodeToString(PluginReplies.pcm16(samples)))
                put("mimeType", "audio/pcm;rate=$SAMPLE_RATE")
            }
        }
    }.toString()

    private val ACTIVITY_START = """{"realtimeInput":{"activityStart":{}}}"""
    private val ACTIVITY_END = """{"realtimeInput":{"activityEnd":{}}}"""

    /** What one message of the server said. */
    internal data class LiveEvent(
        val setupComplete: Boolean = false,
        val interim: String? = null,
        val final: String? = null,
        val turnComplete: Boolean = false,
    )

    internal fun parseLive(message: String): LiveEvent {
        val root = runCatching { Json.parseToJsonElement(message) as? JsonObject }.getOrNull() ?: return LiveEvent()
        val content = root["serverContent"] as? JsonObject
        fun text(key: String) = (content?.get(key) as? JsonObject)?.str("text")
        fun flag(key: String) = (content?.get(key) as? JsonPrimitive)?.contentOrNull == "true"
        return LiveEvent(
            setupComplete = root.containsKey("setupComplete"),
            interim = text("interimInputTranscription"),
            final = text("inputTranscription"),
            turnComplete = flag("turnComplete") || flag("generationComplete"),
        )
    }

    /**
     * One phrase streamed to the Live API while it is said. [send] the clip
     * as the microphone delivers it, from one thread; [finish] when the clip
     * ends, which asks for the finished text and waits for it; [close] in
     * every case. What the server hears so far reaches [onInterim] on the
     * socket's own thread.
     *
     * In smart mode each finished text the server sends is the whole of it
     * so far, the source text included, and replaces the last; verbatim, each
     * is the next piece and is added to it.
     */
    class LiveSession private constructor(
        private val smart: Boolean,
        private val onInterim: (String) -> Unit,
    ) : AutoCloseable {
        private val lock = Any()
        private var socket: WebSocket? = null
        private var call: NetCall? = null
        private var ready = false
        private val pending = ArrayList<String>()
        private var failure: Throwable? = null
        private var heard = ""
        private var endSent = false
        private var finalAfterEnd = false
        private var closed = false
        private val done = CountDownLatch(1)

        /** The clip's next samples, held until the server has taken the setup. */
        fun send(samples: FloatArray) {
            if (samples.isEmpty()) return
            enqueue(liveAudio(samples))
        }

        /**
         * Ends the phrase and waits up to [timeoutMs] for its finished text.
         * Null when the stream failed or no finished text came, for the
         * caller to send the clip whole instead.
         */
        fun finish(timeoutMs: Long): String? {
            synchronized(lock) {
                if (failure != null || closed) return null
                endSent = true
            }
            enqueue(ACTIVITY_END)
            val arrived = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            synchronized(lock) {
                if (failure != null) return null
                return if (arrived || finalAfterEnd) heard.trim() else null
            }
        }

        override fun close() {
            val (open, logged) = synchronized(lock) {
                if (closed) return
                closed = true
                pending.clear()
                socket to call
            }
            open?.close(NORMAL_CLOSURE, null)
            logged?.end()
            done.countDown()
        }

        private fun enqueue(message: String) {
            val now = synchronized(lock) {
                if (closed || failure != null) return
                if (!ready) {
                    pending += message
                    return
                }
                socket
            } ?: return
            if (now.send(message)) call?.sent(message.length.toLong())
        }

        private fun start(url: String, setup: String) {
            val parsed = runCatching { java.net.URI(url) }.getOrNull()
            call = NetLog.callTo(
                source = NetSource.TRANSCRIPTION,
                method = "GET",
                scheme = parsed?.scheme ?: "wss",
                host = parsed?.host.orEmpty(),
                port = parsed?.port ?: -1,
                route = LIVE_ROUTE,
            )
            val request = okhttp3.Request.Builder().url(url).build()
            socket = sockets.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (webSocket.send(setup)) call?.sent(setup.length.toLong())
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) = onServer(webSocket, text)

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onServer(webSocket, bytes.utf8())

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        fail(IOException("Gemini closed the stream: $code $reason".trim()))
                        webSocket.close(NORMAL_CLOSURE, null)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = fail(t)
                },
            )
        }

        private fun onServer(webSocket: WebSocket, message: String) {
            call?.received(message.length.toLong())
            val event = parseLive(message)
            if (event.setupComplete) {
                val queued = synchronized(lock) {
                    ready = true
                    ArrayList(pending).also { pending.clear() }
                }
                for (next in listOf(ACTIVITY_START) + queued) {
                    if (webSocket.send(next)) call?.sent(next.length.toLong())
                }
            }
            event.interim?.takeIf { it.isNotBlank() }?.let(onInterim)
            synchronized(lock) {
                event.final?.let { text ->
                    heard = if (smart) text else listOf(heard.trim(), text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
                    if (endSent) finalAfterEnd = true
                }
                if (event.turnComplete && endSent) done.countDown()
            }
        }

        private fun fail(t: Throwable) {
            val logged = synchronized(lock) {
                // A close after the finished text is just the end.
                if (closed || done.count == 0L) return
                failure = t
                call
            }
            logged?.fail(t)
            done.countDown()
        }

        companion object {
            private const val NORMAL_CLOSURE = 1000

            /**
             * Opens a session on [model] (a live model, or the live one
             * behind a clip model) for one phrase of [request]. Returns at
             * once: the clip can be sent from the first moment, and goes once
             * the server has taken the setup.
             */
            fun open(apiKey: String, model: String, request: Request, onInterim: (String) -> Unit): LiveSession =
                LiveSession(request.smart, onInterim).also { it.start(liveUrl(apiKey), liveSetup(model, request)) }
        }
    }
}
