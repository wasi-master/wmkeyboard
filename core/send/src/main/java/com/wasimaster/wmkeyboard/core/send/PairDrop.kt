package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** A browser (or app) a PairDrop server says is on this network. */
data class PairDropPeer(val id: String, val name: String, val rtc: Boolean, val model: String = "")

/**
 * The data channel to one PairDrop peer. WebRTC is the normal way and needs a native
 * library this module cannot hold, so it arrives through this interface;
 * [PairDropClient] uses the server's relay when there is none and the server allows it.
 */
interface PairDropRtc {

    /** Opens a connection to [peerId]. A [caller] makes the offer, and the channel. */
    fun connect(peerId: String, caller: Boolean, iceServers: JsonElement?, events: Events): Connection

    interface Connection {
        /** An `sdp` or `ice` object the other side sent through the server. */
        fun onSignal(signal: JsonObject)
        fun sendText(text: String)
        fun sendBytes(bytes: ByteArray, length: Int)
        fun close()
    }

    interface Events {
        /** An `sdp` or `ice` object for the other side. */
        fun onSignal(signal: JsonObject)
        fun onOpen()
        fun onText(text: String)
        fun onBytes(bytes: ByteArray)
        fun onClosed()
    }
}

/**
 * PairDrop's protocol, so the phone can trade files with any browser that has a
 * PairDrop page open on the same network. Written from what the protocol sends;
 * PairDrop's own code is GPL-3.0 and none of it is in this MIT project.
 *
 * A WebSocket to the server (`/server`) says who else shares this public address and
 * passes signalling between them. The files then go peer to peer: a `request` listing
 * them, `files-transfer-response`, and for each a `header`, its bytes in chunks of
 * 64,000, a `partition` every million bytes which the receiver answers before more
 * comes, and `file-transfer-complete`. Nothing is written without [Listener.onOffer]
 * being answered yes.
 *
 * Paired devices and public rooms are not here: only the room of devices on one
 * network. Off unless switched on, since a server the user may not run sees this
 * phone's address and name.
 */
class PairDropClient(
    private val baseUrl: String,
    private val http: OkHttpClient,
    private val sink: SendSink,
    private val rtc: PairDropRtc?,
    private val displayName: String,
    private val listener: Listener,
) {

    interface Listener {
        fun onPeers(peers: List<PairDropPeer>)
        fun onTransfer(transfer: LanTransfer)
        fun onOffer(offer: PeerOffer)

        /** Whether the server is reachable, and whether it can carry files with no WebRTC here. */
        fun onConnection(connected: Boolean, relay: Boolean) = Unit
    }

    /** One peer: its link, the files it is promised or sending, and who it says it is. */
    private inner class Session(val peerId: String, var roomType: String, var roomId: String?) {
        @Volatile var connection: PairDropRtc.Connection? = null

        @Volatile var open = false

        @Volatile var name: String = ""

        @Volatile var answer: CountDownLatch? = null

        @Volatile var accepted = false

        @Volatile var partition: CountDownLatch? = null

        @Volatile var fileDone: CountDownLatch? = null

        var promised = ArrayDeque<SendFileEntry>()
        var requestPending = false
        var incoming: Incoming? = null

        fun sendText(body: JsonObject) {
            val link = connection
            if (link != null) {
                link.sendText(body.toString())
            } else {
                signalling(relayed(this, body))
            }
        }

        fun sendBytes(bytes: ByteArray, length: Int) {
            val link = connection
            if (link != null) {
                link.sendBytes(bytes, length)
            } else {
                val chunk = bytes.toByteString(0, length).base64()
                signalling(relayed(this, buildJsonObject { put("type", "ws-chunk"); put("chunk", chunk) }))
            }
        }

        fun close() {
            open = false
            runCatching { connection?.close() }
            connection = null
            incoming?.fail()
            incoming = null
            answer?.countDown()
            partition?.countDown()
            fileDone?.countDown()
        }
    }

    private inner class Incoming(val session: Session, val entry: SendFileEntry) {
        val id = nextId.getAndIncrement()
        val target = sink.create(LanShare.safeName(entry.name), entry.size, entry.type.ifBlank { OCTET_STREAM })
        var done = 0L
        var lastReport = 0L
        var lastProgress = 0.0

        fun report(state: LanTransfer.State, location: String? = null) = listener.onTransfer(
            LanTransfer(id, entry.name, entry.size, entry.type, done, incoming = true, state = state, peer = session.name.ifBlank { session.peerId }, location = location),
        )

        fun fail() {
            runCatching { target?.finish(false) }
            report(LanTransfer.State.FAILED)
        }
    }

    private val peers = ConcurrentHashMap<String, PairDropPeer>()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextId = AtomicLong(1)

    /** Bytes and messages of a peer land in the order they came, off the socket's own thread. */
    private val inbox: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "pairdrop").apply { isDaemon = true } }

    @Volatile private var socket: WebSocket? = null

    @Volatile private var iceServers: JsonElement? = null

    @Volatile private var relay = false

    @Volatile private var stopped = false

    /** Connects; the server's list of peers arrives through [Listener.onPeers]. */
    fun start() {
        val root = baseUrl.trim().trimEnd('/')
        val ws = root.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val request = Request.Builder().url("$ws/server?webrtc_supported=${rtc != null}").build()
        socket = http.newWebSocket(request, serverListener)
    }

    fun stop() {
        stopped = true
        sessions.values.forEach { it.close() }
        sessions.clear()
        peers.clear()
        runCatching { signalling(buildJsonObject { put("type", "disconnect") }) }
        runCatching { socket?.close(NORMAL_CLOSURE, null) }
        socket = null
        inbox.shutdown()
    }

    private fun signalling(message: JsonObject) {
        socket?.send(message.toString())
    }

    private fun relayed(session: Session, body: JsonObject): JsonObject = buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("to", session.peerId)
        put("roomType", session.roomType)
        session.roomId?.let { put("roomId", it) }
    }

    private val serverListener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            enqueue { onServerMessage(message) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            enqueue { onServerGone() }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            enqueue { onServerGone() }
        }
    }

    private fun enqueue(work: () -> Unit) {
        try {
            inbox.execute { runCatching(work) }
        } catch (e: RejectedExecutionException) {
            // Stopped: nothing is listening any more.
        }
    }

    private fun onServerGone() {
        listener.onConnection(connected = false, relay = false)
        sessions.values.forEach { it.close() }
        sessions.clear()
        peers.clear()
        listener.onPeers(emptyList())
    }

    private fun onServerMessage(message: JsonObject) {
        when (message.text("type")) {
            "ws-config" -> {
                val config = message["wsConfig"] as? JsonObject
                iceServers = (config?.get("rtcConfig") as? JsonObject)?.get("iceServers")
                relay = config?.bool("wsFallback") == true
                listener.onConnection(connected = true, relay = relay)
            }
            "display-name" -> signalling(buildJsonObject { put("type", "join-ip-room") })
            "ping" -> signalling(buildJsonObject { put("type", "pong") })
            "peers" -> {
                val roomType = message.text("roomType") ?: IP_ROOM
                val roomId = message.text("roomId")
                (message["peers"] as? JsonArray).orEmpty().forEach { element ->
                    val peer = peerFrom(element) ?: return@forEach
                    peers[peer.id] = peer
                    // Whoever was here first waits to be called: that is how each
                    // side knows which of them makes the offer.
                    sessionFor(peer.id, roomType, roomId, caller = true)
                }
                publishPeers()
            }
            "peer-joined" -> {
                val peer = peerFrom(message["peer"]) ?: return
                peers[peer.id] = peer
                publishPeers()
            }
            "peer-left" -> {
                val id = message.text("peerId") ?: return
                peers.remove(id)
                sessions.remove(id)?.close()
                publishPeers()
            }
            "signal" -> onSignal(message)
            else -> onRelayed(message)
        }
    }

    private fun peerFrom(element: JsonElement?): PairDropPeer? {
        val peer = element as? JsonObject ?: return null
        val id = peer.text("id") ?: return null
        val name = peer["name"] as? JsonObject
        return PairDropPeer(
            id = id,
            name = name?.text("displayName").orEmpty().take(MAX_NAME).ifBlank { name?.text("deviceName").orEmpty().ifBlank { id } },
            rtc = peer.bool("rtcSupported") == true,
            model = name?.text("deviceName").orEmpty().take(MAX_NAME),
        )
    }

    private fun publishPeers() = listener.onPeers(peers.values.sortedBy { it.name.lowercase(Locale.ROOT) })

    private fun usesRtc(peerRtc: Boolean) = rtc != null && peerRtc

    private fun sessionFor(id: String, roomType: String, roomId: String?, caller: Boolean, peerRtc: Boolean? = null): Session {
        sessions[id]?.let { known ->
            known.roomType = roomType
            known.roomId = roomId
            return known
        }
        val session = Session(id, roomType, roomId)
        sessions[id] = session
        if (usesRtc(peerRtc ?: peers[id]?.rtc == true)) {
            session.connection = rtc!!.connect(id, caller, iceServers, rtcEvents(session))
        } else if (relay) {
            if (caller) signalling(relayed(session, buildJsonObject { put("type", "signal"); put("connected", false) }))
        }
        return session
    }

    private fun rtcEvents(session: Session) = object : PairDropRtc.Events {
        override fun onSignal(signal: JsonObject) {
            signalling(
                relayed(
                    session,
                    buildJsonObject {
                        signal.forEach { (key, value) -> put(key, value) }
                        put("type", "signal")
                    },
                ),
            )
        }

        override fun onOpen() {
            session.open = true
            runCatching {
                session.sendText(buildJsonObject { put("type", "display-name-changed"); put("displayName", displayName) })
            }
        }

        override fun onText(text: String) {
            val message = runCatching { JSON.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return
            enqueue { onPeerMessage(session, message) }
        }

        override fun onBytes(bytes: ByteArray) {
            enqueue { onChunk(session, bytes) }
        }

        override fun onClosed() {
            session.open = false
        }
    }

    private fun onSignal(message: JsonObject) {
        val sender = message["sender"] as? JsonObject ?: return
        val id = sender.text("id") ?: return
        val peerRtc = sender.bool("rtcSupported") == true
        val session = sessionFor(id, message.text("roomType") ?: IP_ROOM, message.text("roomId"), caller = false, peerRtc = peerRtc)
        val link = session.connection
        if (link != null) {
            link.onSignal(message)
        } else if (relay) {
            // The relay has no handshake to speak of: this answer is all it wants.
            session.open = true
            if (message.bool("connected") != true) {
                signalling(relayed(session, buildJsonObject { put("type", "signal"); put("connected", true) }))
            }
        }
    }

    /** A transfer message the server passed along, on a server that relays. */
    private fun onRelayed(message: JsonObject) {
        if (!relay) return
        val sender = message["sender"] as? JsonObject ?: return
        val session = sessions[sender.text("id") ?: return] ?: return
        if (message.text("type") == "ws-chunk") {
            val chunk = message.text("chunk")?.decodeBase64()?.toByteArray() ?: return
            onChunk(session, chunk)
        } else {
            onPeerMessage(session, message)
        }
    }

    // ---- receiving --------------------------------------------------------

    private fun onPeerMessage(session: Session, message: JsonObject) {
        when (message.text("type")) {
            "display-name-changed" -> message.text("displayName")?.take(MAX_NAME)?.let { name ->
                session.name = name
                peers[session.peerId]?.let { peers[session.peerId] = it.copy(name = name) }
                publishPeers()
            }
            "request" -> onRequest(session, message)
            "header" -> onHeader(session, message)
            "partition" -> session.sendText(buildJsonObject { put("type", "partition-received"); put("offset", message.long("offset") ?: 0) })
            "files-transfer-response" -> {
                session.accepted = message.bool("accepted") == true
                session.answer?.countDown()
            }
            "partition-received" -> session.partition?.countDown()
            "file-transfer-complete" -> session.fileDone?.countDown()
            else -> Unit
        }
    }

    private fun onRequest(session: Session, message: JsonObject) {
        if (session.requestPending || session.incoming != null) {
            session.sendText(buildJsonObject { put("type", "files-transfer-response"); put("accepted", false) })
            return
        }
        val files = (message["header"] as? JsonArray).orEmpty().take(MAX_FILES).mapNotNull { element ->
            val file = element as? JsonObject ?: return@mapNotNull null
            SendFileEntry(file.text("name").orEmpty(), file.long("size") ?: return@mapNotNull null, file.text("mime").orEmpty())
        }
        if (files.isEmpty()) {
            session.sendText(buildJsonObject { put("type", "files-transfer-response"); put("accepted", false) })
            return
        }
        session.requestPending = true
        val name = session.name.ifBlank { peers[session.peerId]?.name ?: session.peerId }
        listener.onOffer(
            PeerOffer(name, files) { yes ->
                enqueue {
                    session.requestPending = false
                    if (yes) session.promised = ArrayDeque(files)
                    session.sendText(buildJsonObject { put("type", "files-transfer-response"); put("accepted", yes) })
                }
            },
        )
    }

    private fun onHeader(session: Session, message: JsonObject) {
        val expected = session.promised.firstOrNull() ?: return
        // What was agreed to is the only thing that may be written.
        if (expected.name != message.text("name") || expected.size != message.long("size")) {
            session.promised.clear()
            return
        }
        session.promised.removeFirst()
        val incoming = Incoming(session, expected)
        if (incoming.target == null) {
            session.promised.clear()
            return
        }
        session.incoming = incoming
        incoming.report(LanTransfer.State.RUNNING)
        if (expected.size == 0L) finishIncoming(session, incoming)
    }

    private fun onChunk(session: Session, bytes: ByteArray) {
        val incoming = session.incoming ?: return
        val stream = incoming.target?.stream ?: return
        if (incoming.done + bytes.size > incoming.entry.size) {
            session.incoming = null
            incoming.fail()
            session.promised.clear()
            return
        }
        try {
            stream.write(bytes)
        } catch (e: IOException) {
            session.incoming = null
            incoming.fail()
            session.promised.clear()
            return
        }
        incoming.done += bytes.size
        if (incoming.done - incoming.lastReport >= REPORT_EVERY) {
            incoming.lastReport = incoming.done
            incoming.report(LanTransfer.State.RUNNING)
        }
        val progress = incoming.done.toDouble() / incoming.entry.size
        if (progress - incoming.lastProgress >= PROGRESS_STEP) {
            incoming.lastProgress = progress
            session.sendText(buildJsonObject { put("type", "progress"); put("progress", progress) })
        }
        if (incoming.done == incoming.entry.size) finishIncoming(session, incoming)
    }

    private fun finishIncoming(session: Session, incoming: Incoming) {
        session.incoming = null
        val location = runCatching { incoming.target?.finish(true) }.getOrNull()
        if (location != null) incoming.report(LanTransfer.State.DONE, location) else incoming.report(LanTransfer.State.FAILED)
        session.sendText(buildJsonObject { put("type", "file-transfer-complete") })
    }

    // ---- sending ----------------------------------------------------------

    /** Why a send ended. */
    enum class SendResult { SENT, DECLINED, UNREACHABLE, FAILED }

    /** Sends [files] to [peerId], blocking until the last one is through or something refused. */
    fun send(peerId: String, files: List<SendFile>): SendResult {
        val session = sessions[peerId] ?: return SendResult.UNREACHABLE
        if (!waitOpen(session)) return SendResult.UNREACHABLE
        val answer = CountDownLatch(1)
        session.answer = answer
        session.accepted = false
        session.sendText(
            buildJsonObject {
                put("type", "request")
                putJsonArray("header") {
                    files.forEach { file ->
                        addJsonObject {
                            put("name", file.name)
                            put("mime", file.type)
                            put("size", file.size)
                        }
                    }
                }
                put("totalSize", files.sumOf { it.size })
                put("imagesOnly", files.all { it.type.startsWith("image/") })
                put("thumbnailDataUrl", "")
            },
        )
        if (!answer.await(ANSWER_TIMEOUT_SEC, TimeUnit.SECONDS)) return SendResult.DECLINED
        if (!session.accepted) return SendResult.DECLINED
        for (file in files) {
            if (!sendFile(session, file)) return SendResult.FAILED
        }
        return SendResult.SENT
    }

    private fun waitOpen(session: Session): Boolean {
        var waited = 0L
        while (!session.open && waited < OPEN_TIMEOUT_MS) {
            Thread.sleep(OPEN_POLL_MS)
            waited += OPEN_POLL_MS
        }
        return session.open
    }

    private fun sendFile(session: Session, file: SendFile): Boolean {
        val id = nextId.getAndIncrement()
        var done = 0L
        var lastReport = 0L
        fun report(state: LanTransfer.State) = listener.onTransfer(
            LanTransfer(id, file.name, file.size, file.type, done, incoming = false, state = state, peer = session.name.ifBlank { peers[session.peerId]?.name.orEmpty() }),
        )
        report(LanTransfer.State.RUNNING)
        val complete = CountDownLatch(1)
        session.fileDone = complete
        val ok = runCatching {
            session.sendText(
                buildJsonObject {
                    put("type", "header")
                    put("size", file.size)
                    put("name", file.name)
                    put("mime", file.type)
                },
            )
            file.open().use { input ->
                val chunk = ByteArray(CHUNK)
                var inPartition = 0L
                while (true) {
                    // Filled whole: a short read would make a short chunk the receiver counts all the same.
                    var n = 0
                    while (n < chunk.size) {
                        val read = input.read(chunk, n, chunk.size - n)
                        if (read < 0) break
                        n += read
                    }
                    if (n == 0) break
                    session.sendBytes(chunk, n)
                    done += n
                    inPartition += n
                    if (done - lastReport >= REPORT_EVERY) {
                        lastReport = done
                        report(LanTransfer.State.RUNNING)
                    }
                    if (inPartition >= PARTITION && done < file.size) {
                        inPartition = 0
                        val received = CountDownLatch(1)
                        session.partition = received
                        session.sendText(buildJsonObject { put("type", "partition"); put("offset", done) })
                        if (!received.await(STEP_TIMEOUT_SEC, TimeUnit.SECONDS) || !session.open) return@runCatching false
                    }
                }
            }
            complete.await(STEP_TIMEOUT_SEC, TimeUnit.SECONDS) && session.open
        }.getOrDefault(false)
        report(if (ok) LanTransfer.State.DONE else LanTransfer.State.FAILED)
        return ok
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    /** An empty array in place of a missing one. */
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    companion object {
        const val DEFAULT_SERVER = "https://pairdrop.net"
        private const val IP_ROOM = "ip"
        private const val CHUNK = 64_000
        private const val PARTITION = 1_000_000L
        private const val REPORT_EVERY = 256 * 1024L
        private const val PROGRESS_STEP = 0.02
        private const val ANSWER_TIMEOUT_SEC = 120L
        private const val STEP_TIMEOUT_SEC = 60L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val OPEN_POLL_MS = 100L
        private const val MAX_FILES = 1_000
        private const val MAX_NAME = 80
        private const val NORMAL_CLOSURE = 1000
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** Reads what [PairDropClient] hands a WebRTC bridge out of a `signal`: an SDP's type and text. */
fun JsonObject.pairDropSdp(): Pair<String, String>? {
    val sdp = get("sdp") as? JsonObject ?: return null
    val type = (sdp["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    val text = (sdp["sdp"] as? JsonPrimitive)?.contentOrNull ?: return null
    return type to text
}

/** A signal's ICE candidate: its `sdpMid`, `sdpMLineIndex` and text. */
fun JsonObject.pairDropIce(): Triple<String?, Int, String>? {
    val ice = get("ice") as? JsonObject ?: return null
    val candidate = (ice["candidate"] as? JsonPrimitive)?.contentOrNull ?: return null
    val index = (ice["sdpMLineIndex"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
    return Triple((ice["sdpMid"] as? JsonPrimitive)?.contentOrNull, index, candidate)
}
