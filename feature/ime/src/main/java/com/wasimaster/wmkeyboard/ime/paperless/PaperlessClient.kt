package com.wasimaster.wmkeyboard.ime.paperless

import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The calls the document scanner makes to the user's Paperless-ngx server
 * (#1198): the tag, correspondent and document type lists to pick from, and
 * the upload, `POST /api/documents/post_document/`.
 *
 * The token goes in `Authorization: Token …`, never in the address. Every
 * request is in the network activity log as [NetSource.PAPERLESS].
 */
class PaperlessClient(server: String, private val token: String) {

    /** `https://paperless.example.com/api/`, whichever way the address was typed. */
    val base: HttpUrl? = apiBase(server)

    /** A reply that was not a success. [permanent] when trying again would get the same answer. */
    class Failure(val code: Int, message: String) : IOException(message) {
        val refused: Boolean get() = code == 401 || code == 403
        val permanent: Boolean get() = code in 400..499 && code != 408 && code != 429
    }

    /** One entry of a server list: a tag, a correspondent or a document type. */
    data class Named(val id: Int, val name: String)

    data class Lists(val tags: List<Named>, val correspondents: List<Named>, val documentTypes: List<Named>)

    /** Everything the scan card and the settings page offer, each list by name. */
    fun lists(): Lists = Lists(all("tags/"), all("correspondents/"), all("document_types/"))

    /** Every entry of one list, following its pages. */
    fun all(path: String): List<Named> {
        val out = ArrayList<Named>()
        var url: HttpUrl? = url(path).newBuilder().addQueryParameter("page_size", PAGE_SIZE.toString()).build()
        var guard = 0
        while (url != null && guard++ < MAX_PAGES) {
            val o = JSONObject(text(get(url)))
            val results = o.optJSONArray("results")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val id = r.optInt("id", 0)
                    if (id > 0) out += Named(id, r.optString("name"))
                }
            }
            // The next page, only while it is still on the same server.
            url = o.optString("next").takeIf { it.isNotBlank() && it != "null" }?.toHttpUrlOrNull()
                ?.takeIf { it.host == base?.host }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Uploads [file] as one document. Paperless answers with the id of the
     * task that will read it, and reads it in the background; the document
     * appears in its inbox a little later.
     */
    fun upload(file: File, mime: String, fileName: String, title: String?, tags: Collection<Int>, correspondent: Int, documentType: Int): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("document", fileName, file.asRequestBody(mime.toMediaTypeOrNull()))
            .apply {
                if (!title.isNullOrBlank()) addFormDataPart("title", title)
                if (correspondent > 0) addFormDataPart("correspondent", correspondent.toString())
                if (documentType > 0) addFormDataPart("document_type", documentType.toString())
                tags.filter { it > 0 }.forEach { addFormDataPart("tags", it.toString()) }
            }
            .build()
        val r = call(authed(url("documents/post_document/")).post(body).build())
        return text(r).trim().trim('"')
    }

    private fun url(path: String): HttpUrl =
        (base ?: throw IOException("no server")).newBuilder().addPathSegments(path).build()

    private fun authed(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Token $token").header("Accept", "application/json")

    private fun get(url: HttpUrl): Response = call(authed(url).build())

    private fun call(request: Request): Response {
        val r = http.newCall(request).execute()
        if (!r.isSuccessful) {
            val code = r.code
            val detail = runCatching { r.body?.string()?.take(DETAIL_MAX) }.getOrNull().orEmpty()
            r.close()
            throw Failure(code, "Paperless answered $code ${detail.trim()}".trim())
        }
        return r
    }

    private fun text(r: Response): String = r.use { it.body?.string().orEmpty() }

    companion object {
        private const val PAGE_SIZE = 250
        private const val MAX_PAGES = 40
        private const val DETAIL_MAX = 200

        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(InternetGate)
                .addNetworkInterceptor(NetLogInterceptor(NetSource.PAPERLESS, route = { "/" + it.pathSegments.joinToString("/") }))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(180, TimeUnit.SECONDS)
                .build()
        }

        /** The API's root for what the user typed, ending in `/api/`. */
        fun apiBase(server: String): HttpUrl? {
            var s = server.trim().trimEnd('/')
            if (s.isEmpty()) return null
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
            val url = s.toHttpUrlOrNull() ?: return null
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            val root = if (segments.lastOrNull() == "api") url else url.newBuilder().addPathSegment("api").build()
            // A trailing slash: Django answers the bare path with a redirect, and a redirected POST loses its body.
            return root.newBuilder().addPathSegment("").build()
        }
    }
}
