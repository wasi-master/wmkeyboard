package com.wasimaster.wmkeyboard.ime.gallery.immich

import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * The few calls the gallery makes to the user's own Immich server (#1205):
 * the timeline and albums to browse, a picture's thumbnail and its original to
 * send, and an upload of a phone picture the server does not have yet.
 *
 * Every request carries the API key in `x-api-key`, never in the address, and
 * goes through the network activity log as [NetSource.IMMICH] with the asset
 * and album ids written as placeholders.
 */
class ImmichClient(server: String, private val apiKey: String) {

    /** `https://photos.example.com/api`, whichever way the address was typed. */
    val base: HttpUrl? = apiBase(server)

    /** A server reply that was not a success, with the status it gave. */
    class Failure(val code: Int, message: String) : IOException(message) {
        /** The key is wrong, or lacks the permission this call needs. */
        val refused: Boolean get() = code == 401 || code == 403
    }

    /** One month of the timeline: its key (`2026-11-01T00:00:00.000Z`) and how many assets it holds. */
    data class Bucket(val key: String, val count: Int)

    /** One asset of a bucket, as the timeline lists it. */
    data class Asset(val id: String, val image: Boolean, val ratio: Float, val createdMs: Long, val trashed: Boolean)

    data class Album(val id: String, val name: String, val count: Int, val coverId: String?)

    /** Who the key belongs to, for the settings page's check. */
    data class Me(val name: String, val email: String)

    /** What the server said to an upload: [duplicate] when it had the picture already. */
    data class Uploaded(val id: String, val duplicate: Boolean)

    /** A downloaded original: its type and the name it had when it was taken. */
    data class Original(val mime: String, val name: String?)

    fun me(): Me {
        val o = JSONObject(text(get("users/me")))
        return Me(o.optString("name"), o.optString("email"))
    }

    /** The months, newest first; of [albumId] when given, else of the whole timeline. */
    fun buckets(albumId: String?): List<Bucket> {
        val url = url("timeline/buckets") {
            if (albumId != null) addQueryParameter("albumId", albumId)
            addQueryParameter("order", "desc")
        }
        val arr = JSONArray(text(request(url)))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Bucket(o.optString("timeBucket"), o.optInt("count"))
        }.filter { it.key.isNotEmpty() && it.count > 0 }
    }

    /** The assets of one month, newest first, read from the timeline's column-per-field answer. */
    fun bucket(key: String, albumId: String?): List<Asset> {
        val url = url("timeline/bucket") {
            addQueryParameter("timeBucket", key)
            if (albumId != null) addQueryParameter("albumId", albumId)
            addQueryParameter("order", "desc")
        }
        val o = JSONObject(text(request(url)))
        val ids = o.optJSONArray("id") ?: return emptyList()
        val images = o.optJSONArray("isImage")
        val ratios = o.optJSONArray("ratio")
        val trashed = o.optJSONArray("isTrashed")
        val created = o.optJSONArray("fileCreatedAt")
        val month = parseTime(key)
        return (0 until ids.length()).map { i ->
            Asset(
                id = ids.optString(i),
                image = images?.optBoolean(i, true) ?: true,
                ratio = ratios?.optDouble(i, 1.0)?.toFloat()?.takeIf { it > 0f && !it.isNaN() } ?: 1f,
                createdMs = created?.optString(i)?.let(::parseTime)?.takeIf { it > 0 } ?: month,
                trashed = trashed?.optBoolean(i, false) ?: false,
            )
        }.filter { it.id.isNotEmpty() }
    }

    /** The albums the key can read: the user's own and those shared with them. */
    fun albums(): List<Album> {
        val arr = JSONArray(text(get("albums")))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Album(
                id = o.optString("id"),
                name = o.optString("albumName"),
                count = o.optInt("assetCount"),
                coverId = o.optString("albumThumbnailAssetId").takeIf { it.isNotBlank() && it != "null" },
            )
        }.filter { it.id.isNotEmpty() }
    }

    /** The server's own small picture of [id]: `thumbnail` (about 250 px) or `preview` (about 1440 px). */
    fun thumbnail(id: String, preview: Boolean): ByteArray {
        val url = url("assets/$id/thumbnail") { addQueryParameter("size", if (preview) "preview" else "thumbnail") }
        return request(url).use { r -> r.body?.bytes() ?: throw IOException("empty thumbnail") }
    }

    /** The original file of [id], written to [into]. */
    fun download(id: String, into: File): Original {
        request(url("assets/$id/original")).use { r ->
            val body = r.body ?: throw IOException("empty download")
            into.outputStream().use { out -> body.byteStream().copyTo(out) }
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            return Original(mime, fileNameOf(r.header("Content-Disposition")))
        }
    }

    /**
     * Uploads one picture. [open] is called once per attempt and must give
     * the file's bytes from the start; [sha1] (hex) lets the server answer
     * "already have it" without the body mattering.
     */
    fun upload(
        open: () -> InputStream,
        length: Long,
        mime: String,
        fileName: String,
        createdMs: Long,
        modifiedMs: Long,
        deviceAssetId: String,
        sha1: String?,
    ): Uploaded {
        val file = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = if (length > 0) length else -1L
            override fun writeTo(sink: BufferedSink) {
                open().use { sink.writeAll(it.source()) }
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("assetData", fileName, file)
            .addFormDataPart("fileCreatedAt", iso(createdMs))
            .addFormDataPart("fileModifiedAt", iso(modifiedMs))
            .addFormDataPart("filename", fileName)
            // Servers before 2.0 still ask for these two; later ones ignore them.
            .addFormDataPart("deviceAssetId", deviceAssetId)
            .addFormDataPart("deviceId", DEVICE_ID)
            .build()
        val url = base?.newBuilder()?.addPathSegments("assets")?.build() ?: throw IOException("no server")
        val req = Request.Builder().url(url).post(body).header("x-api-key", apiKey).header("Accept", "application/json")
        if (sha1 != null) req.header("x-immich-checksum", sha1)
        val o = JSONObject(text(call(req.build())))
        return Uploaded(o.optString("id"), o.optString("status") == "duplicate")
    }

    // ---- Plumbing -----------------------------------------------------------

    private fun url(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        (base ?: throw IOException("no server")).newBuilder().addPathSegments(path).apply(query).build()

    private fun get(path: String): Response = request(url(path))

    private fun request(url: HttpUrl): Response =
        call(Request.Builder().url(url).header("x-api-key", apiKey).header("Accept", "application/json").build())

    private fun call(request: Request): Response {
        val r = http.newCall(request).execute()
        if (!r.isSuccessful) {
            val code = r.code
            r.close()
            throw Failure(code, "Immich answered $code")
        }
        return r
    }

    private fun text(r: Response): String = r.use { it.body?.string().orEmpty() }

    companion object {
        /** What uploads name as the device; the server lists it on the asset. */
        const val DEVICE_ID = "WM Keyboard"

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(InternetGate)
                .addNetworkInterceptor(NetLogInterceptor(NetSource.IMMICH, route = ::route))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }

        private val UUID_SEGMENT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

        /** `/api/assets/{id}/thumbnail`: the call's shape, with no asset or album named. */
        fun route(url: HttpUrl): String =
            "/" + url.pathSegments.joinToString("/") { if (UUID_SEGMENT.matches(it)) "{id}" else it }

        /** The API's root for what the user typed: the server's address, with or without `/api`. */
        fun apiBase(server: String): HttpUrl? {
            var s = server.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val last = url.pathSegments.lastOrNull { it.isNotEmpty() }
            return if (last == "api") url else url.newBuilder().addPathSegment("api").build()
        }

        fun iso(ms: Long): String =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date(ms))

        /**
         * Epoch milliseconds of an ISO time the server wrote, or 0. By hand
         * rather than java.time, which Android 7 lacks.
         */
        fun parseTime(s: String): Long {
            val t = s.trim()
            if (t.isEmpty()) return 0L
            for (pattern in TIME_PATTERNS) {
                val f = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                runCatching { f.parse(t)?.time }.getOrNull()?.let { return it }
            }
            return 0L
        }

        private val TIME_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd",
        )

        /** The file name in a `Content-Disposition` header, either spelling. */
        fun fileNameOf(header: String?): String? {
            if (header.isNullOrBlank()) return null
            Regex("""filename\*=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE).find(header)?.let {
                return runCatching { java.net.URLDecoder.decode(it.groupValues[1].trim('"', ' '), "UTF-8") }.getOrNull()
            }
            return Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()
        }
    }
}
