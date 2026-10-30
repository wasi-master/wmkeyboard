package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Somebody offering this phone files. [answer] may be called from any thread, once. */
class PeerOffer(
    /** Who is asking, as they name themselves. */
    val from: String,
    val files: List<SendFileEntry>,
    val answer: (Boolean) -> Unit,
) {
    val totalSize: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
}

/** A device a LocalSend app announced on this network. */
data class LocalSendPeer(
    val fingerprint: String,
    val alias: String,
    val deviceModel: String?,
    /** `mobile`, `desktop`, `web`, `headless` or `server`, when the device says. */
    val deviceType: String?,
    val host: String,
    val port: Int,
    val https: Boolean,
    val seenAtMs: Long,
)

/**
 * The self-signed certificate this phone answers LocalSend on. Its SHA-256 is the
 * device's fingerprint, so it is kept: a new one would make the phone a stranger
 * to everyone who remembered it.
 */
class LocalSendIdentity private constructor(internal val keyStore: KeyStore, val certificate: X509Certificate) {

    /** Lowercase hex of the certificate's SHA-256. */
    val fingerprint: String = LocalSendTls.fingerprint(certificate)

    companion object {
        private const val ALIAS = "localsend"
        private val PASSWORD = CharArray(0)

        /** The identity in [file], or a new one written there. A damaged file is replaced. */
        fun load(file: File): LocalSendIdentity {
            runCatching {
                file.inputStream().use { input ->
                    val store = KeyStore.getInstance(STORE_TYPE)
                    store.load(input, PASSWORD)
                    return LocalSendIdentity(store, store.getCertificate(ALIAS) as X509Certificate)
                }
            }
            val fresh = create()
            file.parentFile?.mkdirs()
            file.outputStream().use { fresh.keyStore.store(it, PASSWORD) }
            return fresh
        }

        fun create(): LocalSendIdentity {
            val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            val certificate = LocalSendTls.selfSigned(keys)
            val store = KeyStore.getInstance(STORE_TYPE)
            store.load(null, PASSWORD)
            store.setKeyEntry(ALIAS, keys.private, PASSWORD, arrayOf(certificate))
            return LocalSendIdentity(store, certificate)
        }

        internal fun sslContext(identity: LocalSendIdentity): SSLContext {
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            managers.init(identity.keyStore, PASSWORD)
            return SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        }

        private const val STORE_TYPE = "PKCS12"
        private const val KEY_BITS = 2048
    }
}

/** What a self-signed certificate takes: a little DER, and no certificate library in the APK for it. */
internal object LocalSendTls {

    private const val VALID_YEARS = 20
    private val SHA256_WITH_RSA = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B)
    private val COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun selfSigned(keys: java.security.KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val algorithm = der(0x30, der(0x06, SHA256_WITH_RSA), der(0x05))
        val name = der(0x30, der(0x31, der(0x30, der(0x06, COMMON_NAME), der(0x0C, "LocalSend User".toByteArray()))))
        val validity = der(0x30, time(now - DAY_MS), time(now + VALID_YEARS * YEAR_MS))
        val serial = BigInteger(SERIAL_BITS, SecureRandom()).setBit(SERIAL_BITS).toByteArray()
        val body = der(
            0x30,
            der(0xA0, der(0x02, byteArrayOf(2))), // v3
            der(0x02, serial),
            algorithm,
            name,
            validity,
            name,
            keys.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(body)
        }.sign()
        val bytes = der(0x30, body, algorithm, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }

    /** GeneralizedTime, which holds any year, where UTCTime stops at 2049. */
    private fun time(ms: Long): ByteArray {
        val format = SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(0x18, format.format(Date(ms)).toByteArray())
    }

    private fun der(tag: Int, vararg parts: ByteArray): ByteArray {
        val length = parts.sumOf { it.size }
        val out = ByteArrayOutputStream(length + HEADER_ROOM)
        out.write(tag)
        when {
            length < SHORT_FORM -> out.write(length)
            length < ONE_BYTE -> {
                out.write(SHORT_FORM + 1)
                out.write(length)
            }
            else -> {
                out.write(SHORT_FORM + 2)
                out.write(length shr 8)
                out.write(length and 0xFF)
            }
        }
        parts.forEach(out::write)
        return out.toByteArray()
    }

    private const val DAY_MS = 86_400_000L
    private const val YEAR_MS = 365 * DAY_MS
    private const val SERIAL_BITS = 64
    private const val HEADER_ROOM = 4
    private const val SHORT_FORM = 0x80
    private const val ONE_BYTE = 0x100
}

/**
 * The LocalSend protocol, version 2: so the phone can trade files with the LocalSend
 * apps on any computer or phone on the same network, with no account and no server.
 * Written from the protocol's published specification; no LocalSend code is in here.
 *
 * Devices multicast who they are, and answer by `POST register` on the announcer.
 * A transfer is `prepare-upload` (the file list, answered by a token for each file
 * the receiver wants) and one `upload` per file with the raw bytes. Everything but
 * the multicast goes over TLS with a self-signed certificate; a peer's certificate
 * has to hash to the fingerprint it announced.
 *
 * One node runs while the panel is on Nearby and stops when it leaves. A file only
 * lands after [Listener.onOffer] was answered yes. No Android classes, so it runs in
 * the JVM tests, two nodes talking over a real socket. The multicast lock Android
 * wants is the caller's to hold.
 */
class LocalSendNode(
    private val identity: LocalSendIdentity,
    private val alias: String,
    private val deviceModel: String,
    private val sink: SendSink,
    private val http: OkHttpClient,
    private val listener: Listener,
    private val deviceType: String = "mobile",
) {

    interface Listener {
        /** The devices in reach changed. */
        fun onPeers(peers: List<LocalSendPeer>)

        /** A transfer started, moved on, or ended. Called on its own thread. */
        fun onTransfer(transfer: LanTransfer)

        /** Somebody wants to send files. Unanswered after [OFFER_TIMEOUT_SEC] counts as no. */
        fun onOffer(offer: PeerOffer)
    }

    val fingerprint: String get() = identity.fingerprint

    private val peers = ConcurrentHashMap<String, LocalSendPeer>()
    private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS) { r ->
        Thread(r, "localsend").apply { isDaemon = true }
    }
    private val open: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private val nextId = AtomicLong(1)
    private val stopped = AtomicBoolean(false)

    @Volatile private var server: ServerSocket? = null

    @Volatile private var multicast: MulticastSocket? = null

    @Volatile private var inbound: Inbound? = null

    /** The port the server took: LocalSend's own, or any free one when that is busy. */
    val port: Int get() = server?.localPort ?: -1

    private val pinned: OkHttpClient by lazy { pinnedClient(http) }

    /** Opens the server, joins the multicast group and announces this device. */
    @Throws(IOException::class)
    fun start() {
        val factory = LocalSendIdentity.sslContext(identity).serverSocketFactory
        val socket = try {
            factory.createServerSocket(DEFAULT_PORT, BACKLOG)
        } catch (e: IOException) {
            factory.createServerSocket(0, BACKLOG)
        }
        server = socket
        thread("localsend-accept") { acceptLoop(socket) }
        // Discovery failing leaves a working server: another device can still
        // find this one by scanning the network, as LocalSend does when multicast is blocked.
        runCatching { openMulticast() }
        thread("localsend-announce") { announceLoop() }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { multicast?.close() }
        runCatching { server?.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
        pool.shutdownNow()
        peers.clear()
    }

    /** Asks everyone in reach to answer again. */
    fun refresh() {
        thread("localsend-announce-now") { announce() }
    }

    // ---- discovery --------------------------------------------------------

    private fun lanInterfaces(): List<NetworkInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nic ->
            nic.isUp && !nic.isLoopback && LanShare.rank(nic.name) != null &&
                nic.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
        }
    }.getOrDefault(emptyList())

    private fun openMulticast() {
        val socket = MulticastSocket(InetSocketAddress(DEFAULT_PORT))
        val group = InetAddress.getByName(MULTICAST_GROUP)
        lanInterfaces().forEach { nic -> runCatching { socket.joinGroup(InetSocketAddress(group, DEFAULT_PORT), nic) } }
        multicast = socket
        thread("localsend-multicast") { multicastLoop(socket) }
    }

    private fun multicastLoop(socket: MulticastSocket) {
        val buffer = ByteArray(MAX_DATAGRAM)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: IOException) {
                break
            }
            val body = runCatching { JSON.parseToJsonElement(String(packet.data, 0, packet.length)).jsonObject }.getOrNull() ?: continue
            val peer = peerFrom(body, packet.address.hostAddress.orEmpty()) ?: continue
            remember(peer)
            if (body.bool("announce") == true) thread("localsend-register") { answerAnnouncement(peer) }
        }
    }

    private fun announceLoop() {
        for (wait in ANNOUNCE_BURST_MS) {
            if (stopped.get()) return
            announce()
            Thread.sleep(wait)
        }
        while (!stopped.get()) {
            runCatching { Thread.sleep(ANNOUNCE_EVERY_MS) }.onFailure { return }
            announce()
            forgetSilent()
        }
    }

    private fun announce() {
        val socket = multicast ?: return
        val bytes = selfJson(announce = true).toByteArray()
        val group = InetAddress.getByName(MULTICAST_GROUP)
        lanInterfaces().forEach { nic ->
            runCatching {
                // The socket is shared with the receive thread; only this setting and the send are guarded.
                synchronized(socket) {
                    socket.networkInterface = nic
                    socket.send(DatagramPacket(bytes, bytes.size, group, DEFAULT_PORT))
                }
            }
        }
    }

    private fun answerAnnouncement(peer: LocalSendPeer) {
        val answered = runCatching {
            val request = Request.Builder().url(url(peer, "register")).post(selfJson().toRequestBody(JSON_TYPE)).build()
            pinned.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        if (answered) return
        // The specification's fallback when the announcer's server cannot be reached.
        val socket = multicast ?: return
        runCatching {
            val bytes = selfJson(announce = false).toByteArray()
            synchronized(socket) { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(MULTICAST_GROUP), DEFAULT_PORT)) }
        }
    }

    private fun remember(peer: LocalSendPeer) {
        val known = peers.put(peer.fingerprint, peer)
        if (known == null || known.alias != peer.alias || known.host != peer.host) publishPeers()
    }

    private fun forgetSilent() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        if (peers.values.removeAll { it.seenAtMs < cutoff }) publishPeers()
    }

    private fun publishPeers() = listener.onPeers(peers.values.sortedBy { it.alias.lowercase(Locale.ROOT) })

    private fun peerFrom(body: JsonObject, host: String): LocalSendPeer? {
        val fingerprint = body.text("fingerprint") ?: return null
        if (fingerprint.equals(identity.fingerprint, ignoreCase = true) || host.isEmpty()) return null
        return LocalSendPeer(
            fingerprint = fingerprint,
            alias = body.text("alias").orEmpty().take(MAX_ALIAS).ifBlank { host },
            deviceModel = body.text("deviceModel"),
            deviceType = body.text("deviceType"),
            host = host,
            port = body.int("port") ?: DEFAULT_PORT,
            https = body.text("protocol") != "http",
            seenAtMs = System.currentTimeMillis(),
        )
    }

    /** Who this device is, in the shape every message carries. */
    private fun selfJson(announce: Boolean? = null): String = buildJsonObject {
        put("alias", alias)
        put("version", PROTOCOL_VERSION)
        put("deviceModel", deviceModel)
        put("deviceType", deviceType)
        put("fingerprint", identity.fingerprint)
        put("port", port)
        put("protocol", "https")
        put("download", false)
        if (announce != null) put("announce", announce)
    }.toString()

    private fun url(peer: LocalSendPeer, route: String, query: String = ""): String {
        val host = if (peer.host.contains(':')) "[${peer.host}]" else peer.host
        val scheme = if (peer.https) "https" else "http"
        return "$scheme://$host:${peer.port}$API/$route$query"
    }

    // ---- sending ----------------------------------------------------------

    /** Why a send ended. */
    enum class SendResult { SENT, DECLINED, BUSY, FAILED }

    /** Sends [files] to [peer], blocking until the last one is through or something refused. */
    fun send(peer: LocalSendPeer, files: List<SendFile>): SendResult {
        val ids = files.map { UUID.randomUUID().toString() }
        val body = buildJsonObject {
            put("info", JSON.parseToJsonElement(selfJson()))
            putJsonObject("files") {
                files.forEachIndexed { i, file ->
                    putJsonObject(ids[i]) {
                        put("id", ids[i])
                        put("fileName", file.name)
                        put("size", file.size.coerceAtLeast(0))
                        put("fileType", file.type)
                    }
                }
            }
        }.toString()
        val prepared = try {
            val request = Request.Builder().url(url(peer, "prepare-upload")).post(body.toRequestBody(JSON_TYPE)).build()
            // The other side is waiting on a person, so no ordinary read timeout applies.
            pinned.newBuilder().readTimeout(OFFER_TIMEOUT_SEC + 5, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                when (r.code) {
                    200 -> JSON.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                    204 -> return SendResult.SENT // Nothing wanted: it took the text from the preview.
                    403 -> return SendResult.DECLINED
                    409, 429 -> return SendResult.BUSY
                    else -> return SendResult.FAILED
                }
            }
        } catch (e: IOException) {
            return SendResult.FAILED
        } catch (e: IllegalArgumentException) {
            return SendResult.FAILED
        }
        val sessionId = prepared.text("sessionId") ?: return SendResult.FAILED
        val tokens = prepared["files"] as? JsonObject ?: return SendResult.FAILED
        files.forEachIndexed { i, file ->
            val token = (tokens[ids[i]] as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            if (!upload(peer, sessionId, ids[i], token, file)) {
                cancel(peer, sessionId)
                return SendResult.FAILED
            }
        }
        return SendResult.SENT
    }

    private fun upload(peer: LocalSendPeer, sessionId: String, fileId: String, token: String, file: SendFile): Boolean {
        val id = nextId.getAndIncrement()
        var done = 0L
        var lastReport = 0L
        fun report(state: LanTransfer.State) = listener.onTransfer(
            LanTransfer(id, file.name, file.size, file.type, done, incoming = false, state = state, peer = peer.alias),
        )
        report(LanTransfer.State.RUNNING)
        val requestBody = object : RequestBody() {
            override fun contentType() = (file.type.ifBlank { OCTET_STREAM }).toMediaType()
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                file.open().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        sink.write(buffer, 0, n)
                        done += n
                        if (done - lastReport >= REPORT_EVERY) {
                            lastReport = done
                            report(LanTransfer.State.RUNNING)
                        }
                    }
                }
            }
        }
        val query = "?sessionId=${enc(sessionId)}&fileId=${enc(fileId)}&token=${enc(token)}"
        val ok = runCatching {
            val request = Request.Builder().url(url(peer, "upload", query)).post(requestBody).build()
            pinned.newBuilder().readTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS).writeTimeout(UPLOAD_TIMEOUT_SEC, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
        report(if (ok) LanTransfer.State.DONE else LanTransfer.State.FAILED)
        return ok
    }

    private fun cancel(peer: LocalSendPeer, sessionId: String) {
        runCatching {
            val request = Request.Builder().url(url(peer, "cancel", "?sessionId=${enc(sessionId)}")).post(ByteArray(0).toRequestBody()).build()
            pinned.newCall(request).execute().close()
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

    /** The caller's client, trusting a certificate only by the fingerprint its owner announced. */
    private fun pinnedClient(base: OkHttpClient): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw CertificateException("Not a server")

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val presented = chain?.firstOrNull()?.let(LocalSendTls::fingerprint) ?: throw CertificateException("No certificate")
                if (peers.values.none { it.fingerprint.equals(presented, ignoreCase = true) }) {
                    throw CertificateException("Not a certificate any device announced")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            // The name in the certificate means nothing here; the fingerprint is the check.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    // ---- receiving --------------------------------------------------------

    /** The one transfer being received: tokens by file, what was promised for each, and whose it is. */
    private class Inbound(
        val sessionId: String,
        val peerAddress: String,
        val alias: String,
        val files: Map<String, InboundFile>,
    ) {
        val left: MutableSet<String> = Collections.synchronizedSet(files.keys.toMutableSet())
    }

    private class InboundFile(val token: String, val name: String, val size: Long, val type: String, val sha256: String?)

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
                val request = LanShare.readRequest(input)
                if (request == null) {
                    respond(out, 400)
                } else {
                    route(request, input, out, s.inetAddress?.hostAddress.orEmpty())
                }
                out.flush()
            }
        } catch (e: IOException) {
            // The other device went away; an upload it was on already said so.
        } finally {
            open -= client
        }
    }

    private fun route(request: LanShare.Request, input: InputStream, out: OutputStream, address: String) {
        val route = request.path.removePrefix(API).trim('/')
        when {
            !request.path.startsWith(API) -> respond(out, 404)
            route == "info" && request.method == "GET" -> respond(out, 200, selfJson())
            request.method != "POST" -> respond(out, 405)
            route == "register" -> register(request, input, out, address)
            route == "prepare-upload" -> prepareUpload(request, input, out, address)
            route == "upload" -> receive(request, input, out, address)
            route == "cancel" -> {
                val running = inbound
                if (running != null && running.sessionId == request.query["sessionId"] && running.peerAddress == address) inbound = null
                respond(out, 200)
            }
            else -> respond(out, 404)
        }
    }

    private fun register(request: LanShare.Request, input: InputStream, out: OutputStream, address: String) {
        val body = readJson(request, input)
        if (body == null) {
            respond(out, 400)
            return
        }
        peerFrom(body, address)?.let(::remember)
        respond(out, 200, selfJson())
    }

    private fun prepareUpload(request: LanShare.Request, input: InputStream, out: OutputStream, address: String) {
        val body = readJson(request, input)
        val info = body?.get("info") as? JsonObject
        val listed = body?.get("files") as? JsonObject
        if (info == null || listed == null) {
            respond(out, 400)
            return
        }
        if (inbound != null) {
            respond(out, 409)
            return
        }
        val alias = info.text("alias").orEmpty().take(MAX_ALIAS).ifBlank { address }
        val files = listed.entries.take(MAX_FILES).mapNotNull { (id, value) ->
            val file = value as? JsonObject ?: return@mapNotNull null
            val size = file.long("size") ?: return@mapNotNull null
            id to InboundFile(
                token = LanShare.newToken(),
                name = LanShare.safeName(file.text("fileName").orEmpty()),
                size = size,
                type = file.text("fileType").orEmpty().ifBlank { OCTET_STREAM },
                sha256 = file.text("sha256"),
            )
        }.toMap()
        if (files.isEmpty()) {
            respond(out, 204)
            return
        }
        // Someone on the network may not put a file on this phone without a yes.
        val answered = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        listener.onOffer(
            PeerOffer(alias, files.values.map { SendFileEntry(it.name, it.size, it.type) }) { yes ->
                accepted.set(yes)
                answered.countDown()
            },
        )
        val answeredInTime = runCatching { answered.await(OFFER_TIMEOUT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!answeredInTime || !accepted.get() || stopped.get()) {
            respond(out, 403)
            return
        }
        val session = Inbound(LanShare.newToken(), address, alias, files)
        inbound = session
        respond(
            out,
            200,
            buildJsonObject {
                put("sessionId", session.sessionId)
                putJsonObject("files") { files.forEach { (id, file) -> put(id, file.token) } }
            }.toString(),
        )
    }

    private fun receive(request: LanShare.Request, input: InputStream, out: OutputStream, address: String) {
        val session = inbound
        val fileId = request.query["fileId"].orEmpty()
        val file = session?.files?.get(fileId)
        when {
            request.query["sessionId"].isNullOrEmpty() || fileId.isEmpty() || request.query["token"].isNullOrEmpty() -> {
                respond(out, 400)
                return
            }
            session == null || file == null || session.sessionId != request.query["sessionId"] ||
                file.token != request.query["token"] || session.peerAddress != address || fileId !in session.left -> {
                respond(out, 403)
                return
            }
        }
        val target = sink.create(file!!.name, file.size, file.type)
        if (target == null) {
            respond(out, 500)
            return
        }
        val id = nextId.getAndIncrement()
        var done = 0L
        fun report(state: LanTransfer.State, location: String? = null) = listener.onTransfer(
            LanTransfer(id, file.name, file.size, file.type, done, incoming = true, state = state, peer = session!!.alias, location = location),
        )
        report(LanTransfer.State.RUNNING)
        val digest = MessageDigest.getInstance("SHA-256")
        val ok = try {
            val buffer = ByteArray(BUFFER)
            var lastReport = 0L
            while (done < file.size) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), file.size - done).toInt())
                if (n < 0) break
                target.stream.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                done += n
                if (done - lastReport >= REPORT_EVERY) {
                    lastReport = done
                    report(LanTransfer.State.RUNNING)
                }
            }
            done == file.size
        } catch (e: IOException) {
            false
        }
        val hashOk = file.sha256 == null || digest.digest().joinToString("") { "%02x".format(it) }.equals(file.sha256, ignoreCase = true)
        val location = runCatching { target.finish(ok && hashOk) }.getOrNull()
        session!!.left.remove(fileId)
        if (session.left.isEmpty() && inbound === session) inbound = null
        if (ok && hashOk && location != null) {
            report(LanTransfer.State.DONE, location)
            respond(out, 200)
        } else {
            report(LanTransfer.State.FAILED)
            respond(out, if (ok && !hashOk) 422 else 500)
        }
    }

    private fun readJson(request: LanShare.Request, input: InputStream): JsonObject? {
        val length = request.headers["content-length"]?.trim()?.toIntOrNull() ?: return null
        if (length !in 0..MAX_JSON) return null
        val bytes = ByteArray(length)
        var at = 0
        while (at < length) {
            val n = input.read(bytes, at, length - at)
            if (n < 0) return null
            at += n
        }
        return runCatching { JSON.parseToJsonElement(String(bytes)).jsonObject }.getOrNull()
    }

    private fun respond(out: OutputStream, status: Int, body: String? = null) {
        val bytes = body?.toByteArray() ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(if (status in 200..299) "OK" else "Error").append("\r\n")
            if (body != null) append("Content-Type: application/json\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray())
        out.write(bytes)
    }

    private fun thread(name: String, block: () -> Unit) {
        Thread({ runCatching(block) }, name).apply { isDaemon = true }.start()
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val OFFER_TIMEOUT_SEC = 60L
        internal const val DEFAULT_PORT = 53317
        private const val MULTICAST_GROUP = "224.0.0.167"
        private const val PROTOCOL_VERSION = "2.1"
        private const val API = "/api/localsend/v2"
        private val JSON_TYPE = "application/json".toMediaType()
        private val JSON = Json { ignoreUnknownKeys = true }
        private val ANNOUNCE_BURST_MS = longArrayOf(100, 400, 1_500)
        private const val ANNOUNCE_EVERY_MS = 30_000L
        private const val PEER_TTL_MS = 100_000L
        private const val BACKLOG = 16
        private const val MAX_CONNECTIONS = 8
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 256 * 1024L
        private const val MAX_DATAGRAM = 8 * 1024
        private const val MAX_JSON = 4 * 1024 * 1024
        private const val MAX_FILES = 1_000
        private const val MAX_ALIAS = 80
        private const val UPLOAD_TIMEOUT_SEC = 120L
    }
}
