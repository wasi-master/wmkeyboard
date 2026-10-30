package com.wasimaster.wmkeyboard.core.send

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Two clients over a fake PairDrop server that does what the real one is documented
 * to: names the peers, and relays by `to` with a `sender` added.
 */
class PairDropTest {

    private class MemorySink : SendSink {
        val saved = HashMap<String, ByteArray>()
        val arrived = CountDownLatch(1)

        override fun create(name: String, size: Long, type: String): SendSinkFile {
            val bytes = ByteArrayOutputStream()
            return object : SendSinkFile {
                override val stream: OutputStream = bytes

                override fun finish(success: Boolean): String? {
                    if (!success) return null
                    saved[name] = bytes.toByteArray()
                    arrived.countDown()
                    return "memory://$name"
                }
            }
        }
    }

    private class Recorder(val yes: Boolean) : PairDropClient.Listener {
        val peers = AtomicReference<List<PairDropPeer>>(emptyList())
        val seen = CountDownLatch(1)
        val offers = ArrayList<PeerOffer>()

        override fun onPeers(peers: List<PairDropPeer>) {
            this.peers.set(peers)
            if (peers.isNotEmpty()) seen.countDown()
        }

        override fun onTransfer(transfer: LanTransfer) = Unit

        override fun onOffer(offer: PeerOffer) {
            offers += offer
            offer.answer(yes)
        }
    }

    private val server = MockWebServer()
    private val sockets = LinkedHashMap<String, WebSocket>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun peerJson(id: String) = """{"id":"$id","name":{"displayName":"name-$id","deviceName":"model"},"rtcSupported":false}"""

    /** The next connection's half of the server: `first` finds an empty room, `second` finds `first`. */
    private fun enqueueServerSide(id: String, others: List<String>) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sockets[id] = webSocket
                        webSocket.send("""{"type":"ws-config","wsConfig":{"rtcConfig":{"iceServers":[]},"wsFallback":true}}""")
                        webSocket.send("""{"type":"display-name","displayName":"$id","deviceName":"model","peerId":"$id","peerIdHash":"h"}""")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val message = json.parseToJsonElement(text).jsonObject
                        val type = (message["type"] as? JsonPrimitive)?.contentOrNull
                        when {
                            type == "join-ip-room" -> {
                                webSocket.send("""{"type":"peers","peers":[${others.joinToString(",") { peerJson(it) }}],"roomType":"ip","roomId":"room"}""")
                                others.forEach { sockets[it]?.send("""{"type":"peer-joined","peer":${peerJson(id)},"roomType":"ip","roomId":"room"}""") }
                            }
                            message["to"] != null -> {
                                val to = (message["to"] as JsonPrimitive).content
                                val relayed = JsonObject(
                                    message.filterKeys { it != "to" } + (
                                        "sender" to (json.parseToJsonElement("""{"id":"$id","rtcSupported":false}""") as JsonElement)
                                        ),
                                )
                                sockets[to]?.send(relayed.toString())
                            }
                        }
                    }
                },
            ),
        )
    }

    private val clients = ArrayList<PairDropClient>()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        sockets.values.forEach { it.close(1000, null) }
        runCatching { server.shutdown() }
    }

    private fun client(listener: PairDropClient.Listener, sink: SendSink): PairDropClient =
        PairDropClient(server.url("/").toString(), OkHttpClient(), sink, rtc = null, displayName = "phone", listener = listener).also {
            clients += it
            it.start()
        }

    private fun connectPair(receiver: Recorder, sink: MemorySink): Pair<PairDropClient, String> {
        enqueueServerSide("first", emptyList())
        client(receiver, sink)
        Thread.sleep(SETTLE_MS)
        enqueueServerSide("second", listOf("first"))
        val senderEvents = Recorder(yes = false)
        val sender = client(senderEvents, MemorySink())
        assertTrue(senderEvents.seen.await(WAIT_SEC, TimeUnit.SECONDS))
        assertEquals("name-first", senderEvents.peers.get().single().name)
        return sender to "first"
    }

    @Test
    fun `a file crosses the relay in partitions`() {
        val sink = MemorySink()
        val receiver = Recorder(yes = true)
        val (sender, to) = connectPair(receiver, sink)
        val bytes = ByteArray(2_100_000) { (it * 7).toByte() }

        val result = sender.send(to, listOf(SendFile("big.bin", bytes.size.toLong()) { ByteArrayInputStream(bytes) }))

        assertEquals(PairDropClient.SendResult.SENT, result)
        assertTrue(sink.arrived.await(WAIT_SEC, TimeUnit.SECONDS))
        assertTrue(bytes.contentEquals(sink.saved["big.bin"]))
        assertEquals(1, receiver.offers.size)
    }

    @Test
    fun `a declined request writes nothing`() {
        val sink = MemorySink()
        val (sender, to) = connectPair(Recorder(yes = false), sink)

        val result = sender.send(to, listOf(SendFile("a.bin", 1) { ByteArrayInputStream(byteArrayOf(1)) }))

        assertEquals(PairDropClient.SendResult.DECLINED, result)
        assertTrue(sink.saved.isEmpty())
    }

    private companion object {
        const val WAIT_SEC = 10L
        const val SETTLE_MS = 300L
    }
}
