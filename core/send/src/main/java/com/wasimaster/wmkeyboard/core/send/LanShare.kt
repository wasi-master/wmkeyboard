package com.wasimaster.wmkeyboard.core.send

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** A file going to or coming from a browser, as the panel lists it. */
data class LanTransfer(
    val id: Long,
    val name: String,
    /** Bytes in all; -1 when the file's size was never known. */
    val size: Long,
    val type: String,
    val done: Long,
    /** A browser sent it to this phone. */
    val incoming: Boolean,
    val state: State,
    /** The other device's address on the network. */
    val peer: String,
    /** Where an incoming file was saved, once it was. */
    val location: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED }
}

/**
 * The Browser part of Nearby (#927): a small HTTP server on this phone that
 * any browser on the same Wi-Fi (or on a hotspot this phone opened) can open
 * from a QR code. The page lists [files] to download and, while
 * [uploadsAllowed], takes files back into the [sink].
 *
 * Nothing leaves the network: no STUN, no relay, no server elsewhere. Every
 * path sits under a random [token], so a device on the same Wi-Fi that was not
 * shown the code finds nothing but 404s; the token is the whole lock, as the
 * key in a Send link is. Plain HTTP, since a browser would refuse a
 * certificate this phone made for itself.
 *
 * One request per connection (`Connection: close`), a few connections at a
 * time. No Android classes, so it runs in the JVM tests against a real socket.
 */
class LanShare(
    private val sink: SendSink,
    private val listener: Listener,
    /** The phone's name, at the top of the page. */
    private val deviceName: String = "",
    val token: String = newToken(),
) {

    interface Listener {
        /** A transfer started, moved on, or ended. Called on the connection's thread. */
        fun onTransfer(transfer: LanTransfer)

        /** A request with the right token came in, from [peer]. */
        fun onVisit(peer: String) = Unit
    }

    /** What the page offers; swapped whole when the draft changes. */
    @Volatile var files: List<SendFile> = emptyList()

    /** The page shows its upload box and `POST upload` is accepted. */
    @Volatile var uploadsAllowed: Boolean = true

    @Volatile private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r ->
        Thread(r, "lan-share").apply { isDaemon = true }
    }
    private val open: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val nextId = AtomicLong(1)

    val port: Int get() = server?.localPort ?: -1

    val running: Boolean get() = server?.isClosed == false

    /** Listens on [port] (any free one by default) on every interface; returns the port. */
    @Throws(IOException::class)
    fun start(port: Int = 0, bind: InetAddress? = null): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bind, port), BACKLOG)
        server = socket
        Thread({ acceptLoop(socket) }, "lan-share-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    /** The page's address on [host]; what the QR code carries. */
    fun url(host: String): String = "http://${if (host.contains(':')) "[$host]" else host}:$port/$token/"

    fun stop() {
        runCatching { server?.close() }
        server = null
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            open += client
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                open -= client
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream(), BUFFER)
                val out = BufferedOutputStream(s.getOutputStream(), BUFFER)
                val request = readRequest(input)
                if (request == null) {
                    respond(out, 400, TEXT, "Bad request")
                } else {
                    route(request, input, out, s.inetAddress?.hostAddress.orEmpty())
                }
                out.flush()
            }
        } catch (e: IOException) {
            // The browser went away mid-answer; the transfer, if any, already said so.
        } finally {
            open -= client
        }
    }

    // ---- routes ----

    private fun route(request: Request, input: InputStream, out: OutputStream, peer: String) {
        val parts = request.path.trim('/').split('/')
        if (parts.firstOrNull() != token) {
            respond(out, 404, TEXT, "Not found")
            return
        }
        listener.onVisit(peer)
        val rest = parts.drop(1)
        when {
            rest.isEmpty() -> when (request.method) {
                "GET" -> respond(out, 200, HTML, LanSharePage.html("/$token/"), PAGE_HEADERS)
                else -> respond(out, 405, TEXT, "Method not allowed")
            }
            rest == listOf("files.json") && request.method == "GET" -> respond(out, 200, JSON, filesJson())
            rest.size == 3 && rest[0] == "f" && request.method == "GET" -> download(rest[1], decode(rest[2]), out, peer)
            rest == listOf("upload") && request.method == "POST" -> upload(request, input, out, peer)
            else -> respond(out, 404, TEXT, "Not found")
        }
    }

    internal fun filesJson(): String = buildString {
        append("{\"name\":").append(jsonString(deviceName))
        append(",\"uploads\":").append(uploadsAllowed)
        append(",\"files\":[")
        files.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append("{\"i\":").append(i)
            append(",\"name\":").append(jsonString(f.name))
            append(",\"size\":").append(f.size)
            append(",\"type\":").append(jsonString(f.type))
            append('}')
        }
        append("]}")
    }

    /** `GET f/<index>/<name>`: the name must still match, so a list that changed under the page cannot hand out the wrong file. */
    private fun download(index: String, name: String, out: OutputStream, peer: String) {
        val file = files.getOrNull(index.toIntOrNull() ?: -1)
        if (file == null || file.name != name) {
            respond(out, 404, TEXT, "This file is no longer shared")
            return
        }
        val stream = try {
            file.open()
        } catch (e: Exception) {
            respond(out, 410, TEXT, "This file can no longer be read")
            return
        }
        val id = nextId.getAndIncrement()
        var done = 0L
        fun report(state: LanTransfer.State) =
            listener.onTransfer(LanTransfer(id, file.name, file.size, file.type, done, incoming = false, state = state, peer = peer))
        report(LanTransfer.State.RUNNING)
        try {
            stream.use { src ->
                val headers = buildList {
                    add("Content-Disposition" to contentDisposition(file.name))
                    if (file.size >= 0) add("Content-Length" to file.size.toString())
                }
                head(out, 200, file.type.ifBlank { OCTET_STREAM }, headers)
                val buffer = ByteArray(BUFFER)
                var sinceReport = 0L
                while (true) {
                    val n = src.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    sinceReport += n
                    if (sinceReport >= REPORT_EVERY) {
                        sinceReport = 0
                        report(LanTransfer.State.RUNNING)
                    }
                }
                out.flush()
            }
            report(LanTransfer.State.DONE)
        } catch (e: IOException) {
            report(LanTransfer.State.FAILED)
        }
    }

    /** `POST upload?name=…` with the file as the raw body, which is what `XMLHttpRequest.send(file)` sends. */
    private fun upload(request: Request, input: InputStream, out: OutputStream, peer: String) {
        if (!uploadsAllowed) {
            respond(out, 403, TEXT, "This phone is not taking files")
            return
        }
        val length = request.headers["content-length"]?.trim()?.toLongOrNull()
        if (length == null || length < 0) {
            respond(out, 411, TEXT, "Length required")
            return
        }
        val name = safeName(request.query["name"].orEmpty())
        val type = request.headers["content-type"]?.substringBefore(';')?.trim()?.ifBlank { null } ?: OCTET_STREAM
        if (request.headers["expect"].equals("100-continue", ignoreCase = true)) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val target = sink.create(name, length, type)
        if (target == null) {
            respond(out, 507, TEXT, "No room to save the file")
            return
        }
        val id = nextId.getAndIncrement()
        var done = 0L
        fun report(state: LanTransfer.State, location: String? = null) =
            listener.onTransfer(LanTransfer(id, name, length, type, done, incoming = true, state = state, peer = peer, location = location))
        report(LanTransfer.State.RUNNING)
        val ok = try {
            val buffer = ByteArray(BUFFER)
            var sinceReport = 0L
            while (done < length) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), length - done).toInt())
                if (n < 0) break
                target.stream.write(buffer, 0, n)
                done += n
                sinceReport += n
                if (sinceReport >= REPORT_EVERY) {
                    sinceReport = 0
                    report(LanTransfer.State.RUNNING)
                }
            }
            done == length
        } catch (e: IOException) {
            false
        }
        val location = runCatching { target.finish(ok) }.getOrNull()
        if (ok && location != null) {
            report(LanTransfer.State.DONE, location)
            respond(out, 200, JSON, "{\"ok\":true}")
        } else {
            report(LanTransfer.State.FAILED)
            respond(out, 500, TEXT, "The file did not arrive whole")
        }
    }

    // ---- HTTP ----

    internal class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun respond(out: OutputStream, status: Int, type: String, body: String, extra: List<Pair<String, String>> = emptyList()) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        head(out, status, type, extra + ("Content-Length" to bytes.size.toString()))
        out.write(bytes)
    }

    private fun head(out: OutputStream, status: Int, type: String, extra: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(REASONS[status] ?: "Error").append("\r\n")
            append("Content-Type: ").append(type).append("\r\n")
            // The token is in the address: it must not leak through a cache or a Referer.
            append("Cache-Control: no-store\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n")
            extra.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        out.write(text.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val BACKLOG = 16
        private const val MAX_CONNECTIONS = 6
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256 * 1024L
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_NAME = 180
        private const val TEXT = "text/plain; charset=utf-8"
        private const val HTML = "text/html; charset=utf-8"
        private const val JSON = "application/json; charset=utf-8"

        private val PAGE_HEADERS = listOf(
            "Content-Security-Policy" to
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src data:; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
        )

        private val REASONS = mapOf(
            100 to "Continue", 200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 410 to "Gone", 411 to "Length Required", 500 to "Internal Server Error",
            507 to "Insufficient Storage",
        )

        private val random = SecureRandom()

        /** The request line and headers, up to the blank line; null for anything that is not HTTP/1.x. */
        internal fun readRequest(input: InputStream): Request? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (++total > MAX_HEAD) return null
                when (b) {
                    '\r'.code -> Unit
                    '\n'.code -> {
                        if (line.isEmpty()) break
                        lines += line.toString()
                        line.setLength(0)
                    }
                    else -> line.append(b.toChar())
                }
            }
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size < 3 || !first[2].startsWith("HTTP/1.")) return null
            val target = first[1]
            val headers = lines.drop(1).mapNotNull { h ->
                val colon = h.indexOf(':')
                if (colon <= 0) null else h.substring(0, colon).trim().lowercase() to h.substring(colon + 1).trim()
            }.toMap()
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }
            return Request(first[0].uppercase(), target.substringBefore('?'), query, headers)
        }

        /** 128 random bits, URL-safe: the page's whole lock. */
        fun newToken(): String = Base64Url.encode(ByteArray(16).also(random::nextBytes))

        /**
         * This phone's best address for a browser next to it: the Wi-Fi it is
         * on, then a hotspot it opened, then a Wi-Fi Direct group, then a USB or
         * Ethernet tether. Private IPv4 only; mobile data and VPNs never count.
         */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to it } }
                .filter { (_, address) -> address.isSiteLocalAddress }
                .mapNotNull { (name, address) -> rank(name)?.let { it to address.hostAddress } }
                .minByOrNull { it.first }
                ?.second
        }.getOrNull()

        internal fun rank(interfaceName: String): Int? {
            val name = interfaceName.lowercase()
            return when {
                name == "wlan0" -> 0
                HOTSPOT_PREFIXES.any { name.startsWith(it) } -> 1
                name.startsWith("wlan") -> 2
                name.startsWith("p2p") -> 3
                TETHER_PREFIXES.any { name.startsWith(it) } -> 4
                else -> null
            }
        }

        private val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap")
        private val TETHER_PREFIXES = listOf("eth", "rndis", "usb", "ncm", "bt-pan")

        /**
         * The Wi-Fi code a phone camera joins: `WIFI:T:WPA;S:…;P:…;;`, with the
         * format's five special characters escaped.
         */
        fun wifiCode(ssid: String, passphrase: String): String {
            fun esc(s: String) = s.replace(Regex("""([\\;,:"])"""), """\\$1""")
            return if (passphrase.isEmpty()) "WIFI:T:nopass;S:${esc(ssid)};;" else "WIFI:T:WPA;S:${esc(ssid)};P:${esc(passphrase)};;"
        }

        /** A name from a browser made safe to save: no folders, no control characters, no leading dot. */
        internal fun safeName(raw: String): String {
            val base = raw.substringAfterLast('/').substringAfterLast('\\')
            val clean = base.filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }.trim().trimStart('.')
            return clean.take(MAX_NAME).ifBlank { "file" }
        }

        /** `filename=` for old browsers, `filename*=` (RFC 6266) for every name that is not plain ASCII. */
        internal fun contentDisposition(name: String): String {
            val ascii = name.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }

        /** Undoes percent-encoding; a literal `+` in a path or name stays a `+`. */
        private fun decode(raw: String): String =
            if (!raw.contains('%')) raw else runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)

        internal fun jsonString(s: String): String = buildString {
            append('"')
            s.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '<' -> append("\\u003c")
                    c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> append(c)
                }
            }
            append('"')
        }
    }
}
