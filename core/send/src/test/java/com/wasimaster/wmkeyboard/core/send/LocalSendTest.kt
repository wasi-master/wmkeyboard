package com.wasimaster.wmkeyboard.core.send

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class LocalSendTest {

    private class MemorySink : SendSink {
        val saved = HashMap<String, ByteArray>()

        override fun create(name: String, size: Long, type: String): SendSinkFile {
            val bytes = ByteArrayOutputStream()
            return object : SendSinkFile {
                override val stream: OutputStream = bytes

                override fun finish(success: Boolean): String? {
                    if (!success) return null
                    saved[name] = bytes.toByteArray()
                    return "memory://$name"
                }
            }
        }
    }

    private class Recorder(val yes: Boolean) : LocalSendNode.Listener {
        val offers = ArrayList<PeerOffer>()
        val transfers = ArrayList<LanTransfer>()

        override fun onPeers(peers: List<LocalSendPeer>) = Unit

        override fun onTransfer(transfer: LanTransfer) {
            transfers += transfer
        }

        override fun onOffer(offer: PeerOffer) {
            offers += offer
            offer.answer(yes)
        }
    }

    private val client = OkHttpClient()
    private val nodes = ArrayList<LocalSendNode>()

    @After
    fun tearDown() = nodes.forEach { it.stop() }

    private fun node(alias: String, sink: SendSink, listener: LocalSendNode.Listener): LocalSendNode =
        LocalSendNode(LocalSendIdentity.create(), alias, "test", sink, client, listener).also {
            it.start()
            nodes += it
        }

    /** A client that takes any certificate, for talking to a node as a stranger would. */
    private fun trustingClient(): OkHttpClient {
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trust), SecureRandom()) }
        return client.newBuilder().sslSocketFactory(context.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }

    private fun peerOf(node: LocalSendNode) = LocalSendPeer(
        node.fingerprint, "peer", null, "mobile", InetAddress.getLoopbackAddress().hostAddress!!, node.port, https = true, seenAtMs = System.currentTimeMillis(),
    )

    private fun register(on: LocalSendNode, about: LocalSendNode) {
        val body = """{"alias":"b","version":"2.1","fingerprint":"${about.fingerprint}","port":${about.port},"protocol":"https"}"""
        val request = Request.Builder().url("https://127.0.0.1:${on.port}/api/localsend/v2/register")
            .post(body.toRequestBody("application/json".toMediaType())).build()
        trustingClient().newCall(request).execute().use { assertEquals(200, it.code) }
    }

    @Test
    fun `the certificate parses and hashes to the fingerprint`() {
        val identity = LocalSendIdentity.create()
        identity.certificate.checkValidity()
        identity.certificate.verify(identity.certificate.publicKey)
        assertEquals(64, identity.fingerprint.length)
        assertEquals(LocalSendTls.fingerprint(identity.certificate), identity.fingerprint)
    }

    @Test
    fun `an identity survives being written and read back`() {
        val file = File.createTempFile("localsend", ".p12").apply { delete() }
        try {
            val first = LocalSendIdentity.load(file)
            assertEquals(first.fingerprint, LocalSendIdentity.load(file).fingerprint)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `an accepted transfer lands whole`() {
        val sink = MemorySink()
        val receiver = Recorder(yes = true)
        val to = node("to", sink, receiver)
        val from = node("from", MemorySink(), Recorder(yes = false))
        register(from, to)
        val bytes = ByteArray(300_000) { it.toByte() }

        val result = from.send(peerOf(to), listOf(SendFile("a.bin", bytes.size.toLong()) { ByteArrayInputStream(bytes) }))

        assertEquals(LocalSendNode.SendResult.SENT, result)
        assertEquals("from", receiver.offers.single().from)
        assertTrue(bytes.contentEquals(sink.saved["a.bin"]))
        assertEquals(LanTransfer.State.DONE, receiver.transfers.last().state)
    }

    @Test
    fun `a declined transfer writes nothing`() {
        val sink = MemorySink()
        val to = node("to", sink, Recorder(yes = false))
        val from = node("from", MemorySink(), Recorder(yes = true))
        register(from, to)

        val result = from.send(peerOf(to), listOf(SendFile("a.bin", 1) { ByteArrayInputStream(byteArrayOf(1)) }))

        assertEquals(LocalSendNode.SendResult.DECLINED, result)
        assertTrue(sink.saved.isEmpty())
    }

    @Test
    fun `an upload without the session's token is refused`() {
        val sink = MemorySink()
        val to = node("to", sink, Recorder(yes = true))
        val request = Request.Builder()
            .url("https://127.0.0.1:${to.port}/api/localsend/v2/upload?sessionId=x&fileId=y&token=z")
            .post(byteArrayOf(1).toRequestBody()).build()
        trustingClient().newCall(request).execute().use { assertEquals(403, it.code) }
        assertFalse(sink.saved.isNotEmpty())
    }

    @Test
    fun `a certificate nobody announced is refused`() {
        val to = node("to", MemorySink(), Recorder(yes = true))
        val from = node("from", MemorySink(), Recorder(yes = true))
        // `from` never heard of `to`, so its certificate matches nothing.
        val result = from.send(peerOf(to), listOf(SendFile("a.bin", 1) { ByteArrayInputStream(byteArrayOf(1)) }))
        assertEquals(LocalSendNode.SendResult.FAILED, result)
    }
}
