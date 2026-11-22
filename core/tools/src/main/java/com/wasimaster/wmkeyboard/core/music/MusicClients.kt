package com.wasimaster.wmkeyboard.core.music

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import com.wasimaster.wmkeyboard.core.voice.OpusEncoder
import com.wasimaster.wmkeyboard.core.voice.WavEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One service the Music ID tool can ask what a clip is. [key] is the
 * provider row's key, [typeId] its kind; [recognize] blocks, so it runs on an
 * IO dispatcher, and answers with what the service said rather than
 * throwing: a provider that could not be reached is [MusicOutcome.Failed],
 * which the fallback walk steps past.
 */
interface MusicClient {
    val key: String
    val typeId: String

    /** [samples] are mono in [-1, 1] at [sampleRate] (the microphone's 16 kHz). */
    fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome
}

/**
 * The clip as a file: Ogg Opus where the library is here ([OpusEncoder]),
 * about a twelfth of the WAV, else 16-bit WAV. Endpoints that refused the
 * Opus upload are sent WAV from then on, as `TranscriptionClient` does.
 */
internal object MusicUpload {

    private val UNREADABLE_AUDIO = setOf(400, 415, 422)
    private val wavOnly = ConcurrentHashMap.newKeySet<String>()

    fun send(
        endpoint: String,
        fields: List<Pair<String, String>>,
        fileField: String,
        samples: FloatArray,
        sampleRate: Int,
        headers: Map<String, String>,
        source: NetSource,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): String {
        fun post(file: ToolHttp.FilePart, saved: Long = 0): String = ToolHttp.postMultipart(
            url = endpoint,
            fields = fields,
            file = file,
            timeoutMs = timeoutMs,
            headers = headers,
            source = source,
            route = NetLog.pathOf(endpoint),
            savedBytes = saved,
        )
        fun wav() = ToolHttp.FilePart(fileField, "clip.wav", "audio/wav", WavEncoder.encode(samples, sampleRate))
        if (endpoint in wavOnly) return post(wav())
        val opus = OpusEncoder.encode(samples, sampleRate)
        if (opus == null) {
            OpusEncoder.requestLibrary()
            return post(wav())
        }
        try {
            return post(
                ToolHttp.FilePart(fileField, "clip.ogg", OpusEncoder.MIME_TYPE, opus),
                saved = WavEncoder.sizeOf(samples.size) - opus.size,
            )
        } catch (e: ToolHttpException) {
            if (e.status !in UNREADABLE_AUDIO) throw e
        }
        return post(wav()).also { wavOnly += endpoint }
    }

    private const val READ_TIMEOUT_MS = 30_000
}

/** JSON helpers shared by the parsers, each tolerant of a field that is missing or of another shape. */
internal object MusicJson {
    val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray

    /** A primitive's text; JsonNull is a primitive whose content reads "null", so it is never a value. */
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    fun JsonElement?.int(): Int? = str()?.toIntOrNull()
    fun JsonElement?.long(): Long? = str()?.toLongOrNull()
    fun JsonElement?.double(): Double? = str()?.toDoubleOrNull()
    operator fun JsonObject?.get(key: String): JsonElement? = this?.get(key)
    fun JsonObject?.string(key: String): String = this[key].str().orEmpty()

    /** "01:23" or "1:02:03" as milliseconds; 0 for anything else. */
    fun timecodeMs(text: String?): Long {
        val parts = text?.split(':')?.mapNotNull { it.trim().toLongOrNull() } ?: return 0
        if (parts.isEmpty() || parts.size > 3) return 0
        var s = 0L
        for (p in parts) s = s * 60 + p
        return s * 1000
    }
}

/**
 * The AudD music recognition API (`api.audd.io`): the clip as a file with
 * the user's token, and the song back with its stores. The
 * same request and answer shape is what a self-hosted "own server" row
 * speaks, so [MusicServerClient] is this client with another address.
 */
class AuddClient(
    override val key: String,
    override val typeId: String,
    private val token: String,
    private val baseUrl: String = ServiceEndpoints.base(ServiceEndpoint.AUDD),
    private val source: NetSource = NetSource.MUSIC_AUDD,
) : MusicClient {

    override fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome {
        val fields = buildList {
            if (token.isNotBlank()) add("api_token" to token.trim())
            add("return" to RETURN)
        }
        val body = try {
            MusicUpload.send(endpoint(baseUrl), fields, "file", samples, sampleRate, emptyMap(), source)
        } catch (e: ToolHttpException) {
            return MusicOutcome.Failed(e.apiMessage ?: "HTTP ${e.status}", e)
        }
        return parse(body)
    }

    companion object {
        private const val RETURN = "apple_music,spotify,deezer,musicbrainz"

        /** AudD answers at its root; a server named with a path answers there. */
        fun endpoint(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            return if (trimmed.isEmpty()) ServiceEndpoint.AUDD.default + "/" else "$trimmed/"
        }

        /** The AudD response shape, as the service and a server of the same shape answer it. */
        fun parse(body: String, via: String = "audd"): MusicOutcome {
            val root = MusicJson.parse(body) as? JsonObject
                ?: return MusicOutcome.Failed("The server did not answer with JSON")
            with(MusicJson) {
                val status = root.string("status")
                if (status == "error") {
                    val error = root["error"].obj()
                    val code = error["error_code"].int()
                    val message = error.string("error_message").ifEmpty { "AudD error #$code" }
                    return MusicOutcome.Failed(message)
                }
                val result = root["result"].obj() ?: return MusicOutcome.NoMatch
                val title = result.string("title")
                val artist = result.string("artist")
                if (title.isEmpty() && artist.isEmpty()) return MusicOutcome.NoMatch
                val apple = result["apple_music"].obj()
                val spotify = result["spotify"].obj()
                val deezer = result["deezer"].obj()
                val links = buildMap {
                    result.string("song_link").takeIf { it.isNotEmpty() }?.let { put(MusicLink.SONG_LINK.id, it) }
                    apple.string("url").takeIf { it.isNotEmpty() }?.let { put(MusicLink.APPLE_MUSIC.id, it) }
                    spotify["external_urls"].obj().string("spotify").takeIf { it.isNotEmpty() }?.let { put(MusicLink.SPOTIFY.id, it) }
                    deezer.string("link").takeIf { it.isNotEmpty() }?.let { put(MusicLink.DEEZER.id, it) }
                    result["musicbrainz"].arr()?.firstOrNull().obj().string("id").takeIf { it.isNotEmpty() }
                        ?.let { put(MusicLink.MUSICBRAINZ.id, "https://musicbrainz.org/recording/$it") }
                }
                val art = apple["artwork"].obj().string("url").takeIf { it.isNotEmpty() }
                    ?.replace("{w}", "600")?.replace("{h}", "600")
                    ?: spotify["album"].obj()["images"].arr()?.firstOrNull().obj().string("url").takeIf { it.isNotEmpty() }
                    ?: deezer["album"].obj().string("cover_big").takeIf { it.isNotEmpty() }
                val durationMs = apple.string("durationInMillis").toLongOrNull()
                    ?: spotify["duration_ms"].long()
                    ?: (deezer["duration"].long()?.times(1000))
                    ?: 0L
                val isrc = apple.string("isrc").ifEmpty { spotify["external_ids"].obj().string("isrc") }
                return MusicOutcome.Match(
                    SongMatch(
                        title = title,
                        artist = artist,
                        album = result.string("album"),
                        year = SongMatch.yearOf(result.string("release_date")),
                        label = result.string("label"),
                        durationMs = durationMs,
                        offsetMs = timecodeMs(result.string("timecode")),
                        links = links,
                        artUrl = art,
                        isrc = isrc,
                        via = via,
                    ),
                )
            }
        }
    }
}

/** A server of the user's own that speaks AudD's request and answer: the same client, another address. */
class MusicServerClient(key: String, url: String, token: String) : MusicClient by AuddClient(
    key = key,
    typeId = "music_server",
    token = token,
    baseUrl = url,
    source = NetSource.MUSIC_SERVER,
)

/**
 * ACRCloud's identification API: a signed multipart upload to the host the
 * user's project was given (`identify-eu-west-1.acrcloud.com` and the like),
 * with the access key as the key and the access secret beside it.
 */
class AcrCloudClient(
    override val key: String,
    override val typeId: String,
    private val host: String,
    private val accessKey: String,
    private val accessSecret: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) : MusicClient {

    override fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome {
        if (accessSecret.isBlank()) return MusicOutcome.Failed("ACRCloud needs the access secret as well as the key")
        val timestamp = (now() / 1000).toString()
        val signature = sign(accessKey, accessSecret, timestamp)
        val wav = WavEncoder.encode(samples, sampleRate)
        val endpoint = endpoint(host)
        val fields = listOf(
            "access_key" to accessKey.trim(),
            "data_type" to "audio",
            "signature_version" to "1",
            "signature" to signature,
            "sample_bytes" to wav.size.toString(),
            "timestamp" to timestamp,
        )
        val body = try {
            ToolHttp.postMultipart(
                url = endpoint,
                fields = fields,
                file = ToolHttp.FilePart("sample", "clip.wav", "audio/wav", wav),
                timeoutMs = 30_000,
                source = NetSource.MUSIC_ACRCLOUD,
                route = NetLog.pathOf(endpoint),
            )
        } catch (e: ToolHttpException) {
            return MusicOutcome.Failed(e.apiMessage ?: "HTTP ${e.status}", e)
        }
        return parse(body)
    }

    companion object {
        private const val PATH = "/v1/identify"

        /** `host` typed with or without a scheme or a path: the identify endpoint on it. */
        fun endpoint(host: String): String {
            val trimmed = host.trim().trimEnd('/')
            val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
            return if (withScheme.endsWith(PATH)) withScheme else withScheme.substringBefore("/v1") + PATH
        }

        /** The string ACRCloud signs, HMAC-SHA1 with the secret, base64: method, path, key, type, version, timestamp. */
        fun sign(accessKey: String, secret: String, timestamp: String): String {
            val toSign = listOf("POST", PATH, accessKey.trim(), "audio", "1", timestamp).joinToString("\n")
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return MusicBase64.encode(mac.doFinal(toSign.toByteArray(Charsets.UTF_8)))
        }

        fun parse(body: String): MusicOutcome {
            val root = MusicJson.parse(body) as? JsonObject
                ?: return MusicOutcome.Failed("ACRCloud did not answer with JSON")
            with(MusicJson) {
                val status = root["status"].obj()
                val code = status["code"].int() ?: -1
                when (code) {
                    0 -> Unit
                    NO_RESULT, NO_FINGERPRINT -> return MusicOutcome.NoMatch
                    else -> return MusicOutcome.Failed(status.string("msg").ifEmpty { "ACRCloud error $code" })
                }
                val music = root["metadata"].obj()["music"].arr()?.firstOrNull().obj() ?: return MusicOutcome.NoMatch
                val title = music.string("title")
                val artist = music["artists"].arr()?.mapNotNull { it.obj().string("name").takeIf { n -> n.isNotEmpty() } }
                    ?.joinToString(", ").orEmpty()
                if (title.isEmpty()) return MusicOutcome.NoMatch
                val external = music["external_metadata"].obj()
                val links = buildMap {
                    external["spotify"].obj()["track"].obj().string("id").takeIf { it.isNotEmpty() }
                        ?.let { put(MusicLink.SPOTIFY.id, "https://open.spotify.com/track/$it") }
                    external["deezer"].obj()["track"].obj().string("id").takeIf { it.isNotEmpty() }
                        ?.let { put(MusicLink.DEEZER.id, "https://www.deezer.com/track/$it") }
                    external["youtube"].obj().string("vid").takeIf { it.isNotEmpty() }
                        ?.let { put(MusicLink.YOUTUBE.id, "https://www.youtube.com/watch?v=$it") }
                }
                return MusicOutcome.Match(
                    SongMatch(
                        title = title,
                        artist = artist,
                        album = music["album"].obj().string("name"),
                        year = SongMatch.yearOf(music.string("release_date")),
                        label = music.string("label"),
                        durationMs = music["duration_ms"].long() ?: 0,
                        offsetMs = music["play_offset_ms"].long() ?: 0,
                        score = music["score"].double()?.toInt() ?: 0,
                        links = links,
                        isrc = music["external_ids"].obj().string("isrc"),
                        via = "acrcloud",
                    ),
                )
            }
        }

        private const val NO_RESULT = 1001
        private const val NO_FINGERPRINT = 2004
    }
}

/**
 * Shazam through the unofficial RapidAPI listing (`shazam.p.rapidapi.com`):
 * the raw clip, base64, at 44.1 kHz. Not Apple's own SDK, which an open
 * source keyboard cannot ship, and the listing's request shape is the
 * listing's to change: the body and the song fields below follow its
 * `songs/v2/detect` endpoint as documented in 2026, and a change there
 * reads as "no match" rather than a crash.
 */
class RapidShazamClient(
    override val key: String,
    override val typeId: String,
    private val apiKey: String,
    private val baseUrl: String = ServiceEndpoints.base(ServiceEndpoint.RAPIDAPI_SHAZAM),
) : MusicClient {

    override fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome {
        // The listing caps the body at about half a megabyte: the last five seconds at 44.1 kHz.
        val tail = samples.copyOfRange((samples.size - CLIP_SECONDS * sampleRate).coerceAtLeast(0), samples.size)
        val pcm = MusicClip.pcm16(MusicClip.upsample(tail, sampleRate, RATE))
        val endpoint = baseUrl.trim().trimEnd('/') + "/songs/v2/detect?timezone=UTC&locale=en-US"
        val host = runCatching { java.net.URL(endpoint).host }.getOrDefault("shazam.p.rapidapi.com")
        val response = try {
            ToolHttp.bytes(
                url = endpoint,
                method = "POST",
                body = MusicBase64.encode(pcm).toByteArray(Charsets.US_ASCII),
                contentType = "text/plain",
                headers = mapOf("x-rapidapi-key" to apiKey.trim(), "x-rapidapi-host" to host),
                timeoutMs = 30_000,
                source = NetSource.MUSIC_RAPIDAPI,
                route = NetLog.pathOf(endpoint),
            )
        } catch (e: ToolHttpException) {
            return MusicOutcome.Failed(e.apiMessage ?: "HTTP ${e.status}", e)
        }
        return parse(String(response.body, Charsets.UTF_8))
    }

    companion object {
        const val RATE = 44_100
        private const val CLIP_SECONDS = 5

        fun parse(body: String): MusicOutcome {
            val root = MusicJson.parse(body) as? JsonObject
                ?: return MusicOutcome.Failed("The Shazam API did not answer with JSON")
            with(MusicJson) {
                root.string("message").takeIf { it.isNotEmpty() && root["track"] == null }?.let { return MusicOutcome.Failed(it) }
                val track = root["track"].obj() ?: return MusicOutcome.NoMatch
                val title = track.string("title")
                if (title.isEmpty()) return MusicOutcome.NoMatch
                var album = ""
                var label = ""
                var year = ""
                track["sections"].arr()?.forEach { section ->
                    section.obj()["metadata"].arr()?.forEach { meta ->
                        val m = meta.obj()
                        when (m.string("title").lowercase()) {
                            "album" -> album = m.string("text")
                            "label" -> label = m.string("text")
                            "released" -> year = SongMatch.yearOf(m.string("text"))
                        }
                    }
                }
                val links = buildMap {
                    track.string("url").takeIf { it.isNotEmpty() }?.let { put(MusicLink.SHAZAM.id, it) }
                    track["hub"].obj()["options"].arr()?.forEach { option ->
                        option.obj()["actions"].arr()?.forEach { action ->
                            val uri = action.obj().string("uri")
                            if (uri.startsWith("https://music.apple.com")) put(MusicLink.APPLE_MUSIC.id, uri)
                        }
                    }
                    track["hub"].obj()["providers"].arr()?.forEach { provider ->
                        val p = provider.obj()
                        val uri = p["actions"].arr()?.firstOrNull().obj().string("uri")
                        when (p.string("type").uppercase()) {
                            "SPOTIFY" -> if (uri.startsWith("spotify:")) put(MusicLink.SPOTIFY.id, spotifySearch(uri))
                            "DEEZER" -> if (uri.isNotEmpty()) put(MusicLink.DEEZER.id, deezerSearch(uri))
                        }
                    }
                }
                val images = track["images"].obj()
                return MusicOutcome.Match(
                    SongMatch(
                        title = title,
                        artist = track.string("subtitle"),
                        album = album,
                        year = year,
                        label = label,
                        links = links,
                        artUrl = images.string("coverarthq").ifEmpty { images.string("coverart") }.takeIf { it.isNotEmpty() },
                        isrc = track.string("isrc"),
                        via = "rapid_shazam",
                    ),
                )
            }
        }

        /** `spotify:search:Title Artist` as a web link that opens the same search. */
        private fun spotifySearch(uri: String): String =
            "https://open.spotify.com/search/" + java.net.URLEncoder.encode(uri.removePrefix("spotify:search:"), "UTF-8")

        private fun deezerSearch(uri: String): String =
            "https://www.deezer.com/search/" + java.net.URLEncoder.encode(uri.substringAfter("deezer-query://www.deezer.com/play?query=", uri), "UTF-8")
    }
}

/**
 * AudioTag.info's API: an upload that answers with a job token, then a poll
 * for the result. The free personal key allows a few requests a day.
 */
class AudioTagClient(
    override val key: String,
    override val typeId: String,
    private val apiKey: String,
    private val baseUrl: String = ServiceEndpoints.base(ServiceEndpoint.AUDIOTAG),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : MusicClient {

    override fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome {
        val endpoint = baseUrl.trim().trimEnd('/') + "/api"
        val first = try {
            MusicUpload.send(
                endpoint, listOf("action" to "identify", "apikey" to apiKey.trim()), "file",
                samples, sampleRate, emptyMap(), NetSource.MUSIC_AUDIOTAG,
            )
        } catch (e: ToolHttpException) {
            return MusicOutcome.Failed(e.apiMessage ?: "HTTP ${e.status}", e)
        }
        val token = parseToken(first).getOrElse { return MusicOutcome.Failed(it.message ?: "AudioTag refused the clip") }
        repeat(POLLS) {
            sleep(POLL_MS)
            val body = try {
                ToolHttp.postForm(
                    endpoint,
                    mapOf("action" to "get_result", "token" to token, "apikey" to apiKey.trim()),
                    source = NetSource.MUSIC_AUDIOTAG,
                    route = NetLog.pathOf(endpoint),
                )
            } catch (e: ToolHttpException) {
                return MusicOutcome.Failed(e.apiMessage ?: "HTTP ${e.status}", e)
            }
            parseResult(body)?.let { return it }
        }
        return MusicOutcome.Failed("AudioTag took too long to answer")
    }

    companion object {
        private const val POLLS = 12
        private const val POLL_MS = 1500L

        fun parseToken(body: String): Result<String> {
            val root = MusicJson.parse(body) as? JsonObject ?: return Result.failure(MusicProviderException("AudioTag did not answer with JSON"))
            with(MusicJson) {
                if (root["success"].str() == "false") return Result.failure(MusicProviderException(root.string("error").ifEmpty { "AudioTag refused the request" }))
                val token = root.string("token")
                return if (token.isEmpty()) Result.failure(MusicProviderException("AudioTag gave no job token")) else Result.success(token)
            }
        }

        /** The poll's answer: null while the job is still running. */
        fun parseResult(body: String): MusicOutcome? {
            val root = MusicJson.parse(body) as? JsonObject ?: return MusicOutcome.Failed("AudioTag did not answer with JSON")
            with(MusicJson) {
                if (root["success"].str() == "false") return MusicOutcome.Failed(root.string("error").ifEmpty { "AudioTag refused the request" })
                return when (root.string("result")) {
                    "wait" -> null
                    "not found" -> MusicOutcome.NoMatch
                    "found" -> {
                        val first = root["data"].arr()?.firstOrNull().obj()
                        val track = first["tracks"].arr()?.firstOrNull().arr()?.map { it.str().orEmpty() } ?: return MusicOutcome.NoMatch
                        val title = track.getOrNull(0).orEmpty()
                        if (title.isEmpty()) return MusicOutcome.NoMatch
                        MusicOutcome.Match(
                            SongMatch(
                                title = title,
                                artist = track.getOrNull(1).orEmpty(),
                                album = track.getOrNull(2).orEmpty(),
                                year = SongMatch.yearOf(track.getOrNull(3)),
                                score = first["confidence"].double()?.toInt() ?: 0,
                                offsetMs = ((first["time"].double() ?: 0.0) * 1000).toLong(),
                                via = "audiotag",
                            ),
                        )
                    }
                    else -> MusicOutcome.Failed("AudioTag answered something unexpected")
                }
            }
        }
    }
}

/** Standard base64, in Kotlin so the signature test needs no Android runtime. */
object MusicBase64 {
    private const val TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(TABLE[b0 shr 2])
            out.append(TABLE[((b0 and 0x03) shl 4) or (if (b1 < 0) 0 else b1 shr 4)])
            out.append(if (b1 < 0) '=' else TABLE[((b1 and 0x0F) shl 2) or (if (b2 < 0) 0 else b2 shr 6)])
            out.append(if (b2 < 0) '=' else TABLE[b2 and 0x3F])
            i += 3
        }
        return out.toString()
    }
}

/**
 * A plugin's provider for the Music ID list: the clip as 16-bit PCM, the
 * song back as `{title, artist, album?, year?, label?, art?, isrc?,
 * links: {"spotify": url, ...}}`, or None for no match.
 */
class PluginMusicClient(override val key: String, private val name: String) : MusicClient {
    override val typeId: String = com.wasimaster.wmkeyboard.core.providers.ProviderTypes.PLUGIN.id

    override fun recognize(samples: FloatArray, sampleRate: Int): MusicOutcome {
        val reply = try {
            com.wasimaster.wmkeyboard.core.providers.PluginProviders.call(
                key,
                com.wasimaster.wmkeyboard.core.tools.PluginReplies.audioRequest(samples, sampleRate),
            )
        } catch (e: java.io.IOException) {
            return MusicOutcome.Failed(e.message.orEmpty(), e)
        }
        val o = reply as? kotlinx.serialization.json.JsonObject ?: return MusicOutcome.NoMatch
        val title = o.pluginText("title")
        if (title.isEmpty()) return MusicOutcome.NoMatch
        val links = (o["links"] as? kotlinx.serialization.json.JsonObject).orEmpty().mapNotNull { (kind, url) ->
            (url as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { com.wasimaster.wmkeyboard.core.providers.webUrl(it) }?.let { kind to it }
        }.toMap()
        return MusicOutcome.Match(
            SongMatch(
                title = title,
                artist = o.pluginText("artist"),
                album = o.pluginText("album"),
                year = o.pluginText("year"),
                label = o.pluginText("label"),
                links = links,
                artUrl = com.wasimaster.wmkeyboard.core.providers.webUrl(o.pluginText("art")),
                isrc = o.pluginText("isrc"),
                via = name,
            ),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.pluginText(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.trim().orEmpty()
}
