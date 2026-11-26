package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavListing
import com.wasimaster.wmkeyboard.core.settings.sink.WebDavSink
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * A WebDAV server's files (Nextcloud, ownCloud, Apache, Taildrive), from the
 * same address, user and password as the backup location.
 *
 * The top is the account's, not the backup's folder: where the location
 * came from a preset with a folder (Nextcloud's `…/files/<user>/Backups`),
 * that folder is taken off the address, so the whole account shows.
 *
 * Nothing here can write over a file: uploads say `If-None-Match: *`, and
 * `MOVE` says `Overwrite: F`, so a server that finds the name taken refuses
 * rather than replacing. HTTPS only, as for the backup, except on a tailnet.
 */
class WebDavFs(context: Context, location: BackupLocation) : RemoteFs {

    private val user = location.webDavUser
    private val password = location.webDavPassword
    private val root: HttpUrl = rootOf(location) ?: throw IOException("not a WebDAV address")

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.BACKUP, NetLogInterceptor.PATH) { false })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // A redirect would carry the password elsewhere, and turn a MOVE into a GET.
            .followRedirects(false)
            .build()
    }

    init {
        // Loopback never leaves the phone (a server app on it, Termux's rclone), so plain HTTP is fine there too.
        val loopback = root.host == "127.0.0.1" || root.host == "localhost" || root.host == "::1"
        if (root.scheme != "https" && !WebDavSink.isTailnet(root.toString()) && !loopback) {
            throw IOException("WebDAV over plain HTTP sends the password in the clear")
        }
    }

    /** The address of [path]; a folder's ends in a slash, as WebDAV wants a collection's. */
    private fun url(path: List<String>, folder: Boolean): HttpUrl {
        val builder = root.newBuilder()
        // The root ends in a slash, which leaves one empty segment to fill.
        if (root.pathSegments.lastOrNull() == "") builder.removePathSegment(root.pathSegments.size - 1)
        path.forEach { builder.addPathSegment(it) }
        if (folder) builder.addPathSegment("")
        return builder.build()
    }

    private fun request(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", "WMKeyboard")
        .apply { if (user.isNotEmpty() || password.isNotEmpty()) header("Authorization", Credentials.basic(user, password)) }

    override fun list(dir: List<String>): List<RemoteEntry> {
        val target = url(dir, folder = true)
        val xml = execute(
            request(target).header("Depth", "1").method("PROPFIND", PROPS_XML.toRequestBody(XML)).build(),
        ) { it.body?.string().orEmpty() } ?: throw IOException("the folder is not there")
        val self = decodedPath(target)
        return WebDavListing.parse(xml)
            .filter { decodedPath(it.href) != self }
            .filter { it.name.isNotEmpty() }
            .map { RemoteEntry(it.name, it.isCollection, if (it.isCollection) -1 else it.sizeBytes, it.modifiedAtMs) }
    }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) return RemoteEntry("", true)
        val xml = try {
            propfindSelf(url(path, folder = false))
        } catch (moved: Moved) {
            // Apache sends a folder asked for without its slash to the address with one.
            propfindSelf(url(path, folder = true))
        } ?: return null
        val entry = WebDavListing.parse(xml).firstOrNull() ?: return null
        return RemoteEntry(path.last(), entry.isCollection, if (entry.isCollection) -1 else entry.sizeBytes, entry.modifiedAtMs)
    }

    private class Moved : IOException("moved")

    private fun propfindSelf(target: HttpUrl): String? {
        val request = request(target).header("Depth", "0").method("PROPFIND", PROPS_XML.toRequestBody(XML)).build()
        client.newCall(request).execute().use { response ->
            return when {
                response.isSuccessful || response.code == MULTI_STATUS -> response.body?.string().orEmpty()
                response.code == NOT_FOUND -> null
                response.code in REDIRECTS -> throw Moved()
                else -> throw IOException("WebDAV PROPFIND: HTTP ${response.code}")
            }
        }
    }

    override fun download(path: List<String>, to: OutputStream) {
        execute(request(url(path, folder = false)).get().build()) { response ->
            val body = response.body ?: throw IOException("no body")
            body.byteStream().use { it.copyTo(to, BUFFER) }
        } ?: throw IOException("the file is not there")
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        execute(
            request(url(dir + name, folder = false))
                // Refuses, with 412, rather than replace a file that took the name meanwhile.
                .header("If-None-Match", "*")
                .put(FileBody(from, OCTET, onSent))
                .build(),
        ) { } ?: throw IOException("the folder is not there")
    }

    override fun mkdir(dir: List<String>, name: String) {
        execute(request(url(dir + name, folder = true)).method("MKCOL", null).build()) { }
            ?: throw IOException("the folder above is not there")
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        execute(request(url(path, folder = isDirectory)).delete().build(), missingOk = true) { }
    }

    override fun rename(path: List<String>, newName: String) {
        moveTo(path, path.dropLast(1) + newName)
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        moveTo(path, toDir + path.last())
        return true
    }

    private fun moveTo(from: List<String>, to: List<String>) {
        val folder = stat(from)?.isDirectory ?: throw IOException("nothing to move")
        execute(
            request(url(from, folder))
                .header("Destination", url(to, folder).toString())
                // Never replace: a taken name answers 412 Precondition Failed.
                .header("Overwrite", "F")
                .method("MOVE", null)
                .build(),
        ) { } ?: throw IOException("nothing to move")
    }

    /**
     * Runs [request]; [read] on success. Null for a 404 when [missingOk], or
     * for a 404 or 409 (a missing parent) otherwise turned into a throw by
     * the caller. Any other refusal throws with the status.
     */
    private fun <T> execute(request: Request, missingOk: Boolean = false, read: (Response) -> T): T? {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == MULTI_STATUS) return read(response)
            if (response.code == NOT_FOUND && missingOk) return null
            if (response.code == NOT_FOUND || response.code == CONFLICT) return null
            throw IOException("WebDAV ${request.method}: HTTP ${response.code}")
        }
    }

    /** A file as a request body, counted as it goes so the panel can show progress. */
    private class FileBody(private val file: File, private val type: MediaType?, private val onSent: (Long) -> Unit) : RequestBody() {
        override fun contentType(): MediaType? = type

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            file.source().use { source ->
                val buffer = okio.Buffer()
                while (true) {
                    val n = source.read(buffer, BUFFER.toLong())
                    if (n < 0) break
                    sink.write(buffer, n)
                    onSent(n)
                }
            }
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MULTI_STATUS = 207
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private val REDIRECTS = setOf(301, 302, 307, 308)
        private val XML = "application/xml; charset=utf-8".toMediaTypeOrNull()
        private val OCTET = "application/octet-stream".toMediaTypeOrNull()

        private val PROPS_XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <d:resourcetype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()

        /** A path as names, so an absolute href and a relative one compare equal. */
        private fun decodedPath(href: String): String {
            val path = href.toHttpUrlOrNull()?.encodedPath ?: href.substringBefore('?')
            return path.trimEnd('/').split('/').joinToString("/") { WebDavListing.nameOf(it) }
        }

        private fun decodedPath(url: HttpUrl): String = decodedPath(url.toString())

        /**
         * The account's top: the location's address, less the folder a preset
         * added for backups. Always ends in a slash.
         */
        fun rootOf(location: BackupLocation): HttpUrl? {
            val raw = location.webDavUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
            val url = raw.toHttpUrlOrNull() ?: return null
            val folder = location.webDavFolder.trim().trim('/')
            if (location.webDavPreset.isEmpty() || folder.isEmpty()) return url
            val names = folder.split('/').filter { it.isNotEmpty() }
            val segments = url.pathSegments.filter { it.isNotEmpty() }
            if (segments.size < names.size || segments.takeLast(names.size) != names) return url
            val builder = url.newBuilder()
            repeat(url.pathSegments.size) { builder.removePathSegment(0) }
            segments.dropLast(names.size).forEach { builder.addPathSegment(it) }
            builder.addPathSegment("")
            return builder.build()
        }
    }
}
