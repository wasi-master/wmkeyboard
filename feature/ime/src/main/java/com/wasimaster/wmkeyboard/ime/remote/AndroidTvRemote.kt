package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.netlog.NetCall
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * One TLS link to an Android TV, client certificate presented, framed as the
 * TV frames everything: a varint length, then a protocol buffers message.
 * Its bytes are counted into the network activity log as one row for as long
 * as the link is up.
 */
internal class AtvLink private constructor(
    private val socket: SSLSocket,
    private val call: NetCall,
    val trust: RemoteLan.PinningTrust,
) {
    private val input: InputStream = call.countIn(socket.inputStream)
    private val output: OutputStream = call.countOut(socket.outputStream)

    fun read(): ProtoMessage? = readFrame(input)?.let { parseProto(it) }

    @Synchronized
    fun write(message: ByteArray) = writeFrame(output, message)

    fun close() {
        runCatching { socket.close() }
        call.end()
    }

    companion object {
        /** Connects and shakes hands; [pin] blank trusts and reports the TV's certificate. */
        fun open(context: Context, host: String, port: Int, pin: String, onSeen: (String) -> Unit, readTimeoutMs: Int): AtvLink {
            val identity = AtvIdentity.get(context)
            val trust = RemoteLan.PinningTrust(pin, onSeen)
            val call = NetLog.callTo(NetSource.REMOTE, method = "TLS", scheme = "tls", host = host, port = port, route = null, live = false)
            try {
                val tls = RemoteLan.tlsContext(identity.keyManagers, trust)
                val socket = tls.socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = readTimeoutMs
                socket.startHandshake()
                return AtvLink(socket, call, trust)
            } catch (t: Throwable) {
                call.fail(t)
                call.end()
                throw t
            }
        }

        private const val CONNECT_TIMEOUT_MS = 4000
    }
}

/**
 * Pairing with an Android TV or Google TV, on port 6467, the "polo" protocol:
 * ask to pair, agree on a six-character hexadecimal code shown on the TV,
 * then prove it. The proof is SHA-256 over both RSA keys' modulus and
 * exponent and the last four characters of the code; its first byte must
 * equal the code's first two, which catches a mistyped code before the TV
 * sees it.
 *
 * Messages (polo.proto): OuterMessage { 1 protocol_version, 2 status,
 * 10 pairing_request, 11 pairing_request_ack, 20 options, 30 configuration,
 * 31 configuration_ack, 40 secret, 41 secret_ack }.
 */
class AndroidTvPairing(private val context: Context, private val host: String) {

    private var link: AtvLink? = null

    /** Opens the link and walks to the point where the TV shows its code. */
    suspend fun start() = withContext(Dispatchers.IO) {
        close()
        val opened = try {
            AtvLink.open(context, host, PAIRING_PORT, pin = "", onSeen = {}, readTimeoutMs = READ_TIMEOUT_MS)
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
        }
        link = opened
        try {
            opened.write(outer { message(10) { string(1, SERVICE_NAME); string(2, CLIENT_NAME) } })
            expect(opened, 11)
            opened.write(
                outer {
                    message(20) {
                        message(1) { varint(1, ENCODING_HEX); varint(2, CODE_LENGTH) }
                        varint(3, ROLE_INPUT)
                    }
                },
            )
            expect(opened, 20)
            opened.write(
                outer {
                    message(30) {
                        message(1) { varint(1, ENCODING_HEX); varint(2, CODE_LENGTH) }
                        varint(2, ROLE_INPUT)
                    }
                },
            )
            expect(opened, 31)
        } catch (e: IOException) {
            close()
            throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
        } catch (e: RemoteException) {
            close()
            throw e
        }
    }

    /**
     * Sends the proof for [code]. Throws REFUSED for a code that cannot be the
     * one on screen, or that the TV turned down.
     */
    suspend fun finish(code: String) = withContext(Dispatchers.IO) {
        val opened = link ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
        val clean = code.trim().uppercase()
        if (clean.length != CODE_LENGTH.toInt() || clean.any { it !in HEX }) throw RemoteException(RemoteException.Reason.REFUSED, "code")
        val server = opened.trust.seen?.publicKey as? RSAPublicKey ?: throw RemoteException(RemoteException.Reason.REFUSED, "server key")
        val client = AtvIdentity.get(context).certificate.publicKey as? RSAPublicKey ?: throw RemoteException(RemoteException.Reason.REFUSED, "client key")
        val secret = secret(client, server, clean)
        if (secret[0] != clean.substring(0, 2).toInt(16).toByte()) throw RemoteException(RemoteException.Reason.REFUSED, "code")
        try {
            opened.write(outer { message(40) { bytes(1, secret) } })
            expect(opened, 41)
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
        } finally {
            close()
        }
    }

    fun close() {
        link?.close()
        link = null
    }

    /** Reads the next message and checks it carries [field] with an OK status. */
    private fun expect(opened: AtvLink, field: Int) {
        val reply = opened.read() ?: throw IOException("closed")
        val status = reply.long(2)
        if (status != null && status != STATUS_OK) {
            throw RemoteException(RemoteException.Reason.REFUSED, "status $status")
        }
        if (!reply.has(field)) throw RemoteException(RemoteException.Reason.REFUSED, "unexpected reply")
    }

    private fun outer(build: ProtoWriter.() -> Unit): ByteArray =
        ProtoWriter().varint(1, PROTOCOL_VERSION).varint(2, STATUS_OK).apply(build).toByteArray()

    companion object {
        const val PAIRING_PORT = 6467
        private const val SERVICE_NAME = "atvremote"
        private const val PROTOCOL_VERSION = 2L
        private const val STATUS_OK = 200L
        private const val ENCODING_HEX = 3L
        private const val CODE_LENGTH = 6L
        private const val ROLE_INPUT = 1L
        private const val READ_TIMEOUT_MS = 10_000
        private const val HEX = "0123456789ABCDEF"

        internal fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(unsigned(client.modulus))
            digest.update(unsigned(client.publicExponent))
            digest.update(unsigned(server.modulus))
            digest.update(unsigned(server.publicExponent))
            digest.update(hexBytes(code.substring(2)))
            return digest.digest()
        }

        /** Big-endian bytes without the sign byte Java adds. */
        internal fun unsigned(value: BigInteger): ByteArray {
            val bytes = value.toByteArray()
            return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }

        private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * The remote service on port 6466 once paired (remotemessage.proto):
 * RemoteMessage { 1 remote_configure, 2 remote_set_active, 8 ping_request,
 * 9 ping_response, 10 remote_key_inject, 40 remote_start }. The TV opens with
 * a configure, which is answered with the features wanted; a set-active is
 * answered the same way; pings are answered at once, or the TV hangs up after
 * three. Keys are Android key codes, sent as a short press.
 */
class AndroidTvRemote(private val context: Context, private val device: RemoteDevice, private val learn: RemoteLearn) : RemoteSession {

    override val keys: Set<RemoteKey> = RemoteKey.entries.toSet()

    @Volatile private var link: AtvLink? = null
    private var reader: Thread? = null

    override suspend fun open() = withContext(Dispatchers.IO) {
        close()
        val ready = CompletableDeferred<Unit>()
        val opened = try {
            AtvLink.open(
                context,
                device.host,
                REMOTE_PORT,
                pin = device.certSha256,
                // The remote port's certificate, pinned the first time it is seen.
                onSeen = { sha -> if (device.certSha256.isEmpty()) learn { it.copy(certSha256 = sha) } },
                readTimeoutMs = 0,
            )
        } catch (e: javax.net.ssl.SSLException) {
            // A TV that does not know this certificate ends the handshake.
            throw RemoteException(RemoteException.Reason.NOT_PAIRED, cause = e)
        } catch (e: IOException) {
            throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
        }
        link = opened
        reader = Thread({ loop(opened, ready) }, "wmkb-atv-remote").apply {
            isDaemon = true
            start()
        }
        try {
            withTimeout(READY_TIMEOUT_MS) { ready.await() }
        } catch (e: TimeoutCancellationException) {
            close()
            throw RemoteException(RemoteException.Reason.NOT_PAIRED, cause = e)
        } catch (e: RemoteException) {
            close()
            throw e
        }
    }

    private fun loop(opened: AtvLink, ready: CompletableDeferred<Unit>) {
        try {
            while (true) {
                val incoming = opened.read() ?: break
                when {
                    incoming.has(1) -> {
                        val supported = incoming.message(1)?.long(1) ?: FEATURES
                        opened.write(
                            ProtoWriter().message(1) {
                                varint(1, FEATURES and supported)
                                message(2) {
                                    varint(3, 1)
                                    string(4, "1")
                                    string(5, PACKAGE)
                                    string(6, VERSION)
                                }
                            }.toByteArray(),
                        )
                    }
                    incoming.has(2) -> {
                        opened.write(ProtoWriter().message(2) { varint(1, FEATURES) }.toByteArray())
                        ready.complete(Unit)
                    }
                    incoming.has(8) -> {
                        val value = incoming.message(8)?.long(1) ?: 0L
                        opened.write(ProtoWriter().message(9) { varint(1, value) }.toByteArray())
                    }
                    incoming.has(40) -> ready.complete(Unit)
                }
            }
            ready.completeExceptionally(RemoteException(RemoteException.Reason.NOT_PAIRED))
        } catch (e: IOException) {
            ready.completeExceptionally(RemoteException(RemoteException.Reason.UNREACHABLE, cause = e))
        } finally {
            if (link === opened) link = null
            opened.close()
        }
    }

    override suspend fun press(key: RemoteKey) {
        if (link == null) open()
        val live = link ?: throw RemoteException(RemoteException.Reason.UNREACHABLE)
        withContext(Dispatchers.IO) {
            try {
                live.write(ProtoWriter().message(10) { varint(1, keyCode(key).toLong()); varint(2, DIRECTION_SHORT) }.toByteArray())
            } catch (e: IOException) {
                link = null
                throw RemoteException(RemoteException.Reason.UNREACHABLE, cause = e)
            }
        }
    }

    override fun close() {
        link?.close()
        link = null
        reader = null
    }

    companion object {
        const val REMOTE_PORT = 6466
        private const val READY_TIMEOUT_MS = 8_000L
        private const val DIRECTION_SHORT = 3L
        private const val PACKAGE = "atvremote"
        private const val VERSION = "1.0.0"

        /** Ping, keys, power and volume: 1 | 2 | 32 | 64. */
        private const val FEATURES = 99L

        /** Android's own key codes, the ones the TV's input system takes. */
        fun keyCode(key: RemoteKey): Int = when (key) {
            RemoteKey.POWER -> 26
            RemoteKey.VOL_UP -> 24
            RemoteKey.VOL_DOWN -> 25
            RemoteKey.MUTE -> 164
            RemoteKey.CH_UP -> 166
            RemoteKey.CH_DOWN -> 167
            RemoteKey.INPUT -> 178
            RemoteKey.UP -> 19
            RemoteKey.DOWN -> 20
            RemoteKey.LEFT -> 21
            RemoteKey.RIGHT -> 22
            RemoteKey.OK -> 23
            RemoteKey.BACK -> 4
            RemoteKey.HOME -> 3
            RemoteKey.MENU -> 82
        }
    }
}
