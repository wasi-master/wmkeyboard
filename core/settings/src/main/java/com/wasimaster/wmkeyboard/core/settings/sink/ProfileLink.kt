package com.wasimaster.wmkeyboard.core.settings.sink

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The code that pairs two copies of the keyboard on one phone (#1017):
 * twenty characters, 100 random bits, typed or pasted into both. It is the
 * link's whole secret. Long rather than a six-digit PIN because any app on the
 * phone can knock on the port and get one answer, and an answer made with a
 * short code can be guessed against at leisure.
 */
object ProfileCode {

    /** No I, O, 0 or 1: nothing that reads as something else. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val LENGTH = 20
    private const val GROUP = 5

    /**
     * The ports the link may use. Below Linux's ephemeral range, 32768 and
     * up, so an outgoing connection of some other app never holds one.
     */
    private const val PORT_BASE = 20_000
    private const val PORT_SPAN = 12_000

    fun generate(random: SecureRandom = SecureRandom()): String =
        String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** [raw] as a code, case, spaces and dashes aside; null when it is not one. */
    fun normalize(raw: String): String? {
        val code = raw.uppercase(Locale.ROOT).filter { it != '-' && !it.isWhitespace() }
        return code.takeIf { it.length == LENGTH && it.all { c -> c in ALPHABET } }
    }

    /** `ABCDE-FGHJK-…`, for reading across and copying. */
    fun format(code: String): String = code.chunked(GROUP).joinToString("-")

    internal fun key(code: String): ByteArray = digest("wmkeyboard profile link 1 key", code)

    /**
     * The port both copies meet on. Taken from the code rather than fixed, so
     * no other app knows a port to sit on first, and two pairings with
     * different codes on one phone do not meet each other.
     */
    fun port(code: String): Int {
        val d = digest("wmkeyboard profile link 1 port", code)
        val n = ((d[0].toInt() and 0xFF) shl 16) or ((d[1].toInt() and 0xFF) shl 8) or (d[2].toInt() and 0xFF)
        return PORT_BASE + n % PORT_SPAN
    }

    private fun digest(label: String, code: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(label.toByteArray())
            update(0.toByte())
            update(code.toByteArray())
            digest()
        }
}

/**
 * What crosses the link, both ways.
 *
 * The handshake proves that each side holds the code without sending it:
 *
 *     client → server   "WMKP" 1  client nonce (32)
 *     server → client   "WMKP" 1  server nonce (32)  HMAC(key, "server" ‖ nonces)
 *     client → server   HMAC(key, "client" ‖ nonces)
 *
 * Each direction then has its own AES-256-GCM key, an HMAC of the two nonces,
 * and counts its frames for the nonce, so no key and nonce pair is used twice.
 * A frame is a four-byte length and the sealed message. A message is a type
 * byte, and for a file the length of its name (two bytes), the name, and the
 * file's bytes.
 */
internal object ProfileWire {

    private val MAGIC = "WMKP".toByteArray()
    private const val VERSION = 1
    private const val NONCE_BYTES = 32
    private const val MAC_BYTES = 32
    private const val GCM_NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val HEADER = 3
    private const val TYPE_FILE: Byte = 1

    /** Far over any sync file the keyboard writes: a frame larger than this is a broken peer. */
    const val MAX_FRAME = 32 * 1024 * 1024

    /** One side of a link that passed the handshake. [send] from one thread, [receive] from one other. */
    class Link internal constructor(
        private val input: DataInputStream,
        private val output: DataOutputStream,
        seal: ByteArray,
        open: ByteArray,
    ) {
        private val sealKey = SecretKeySpec(seal, "AES")
        private val openKey = SecretKeySpec(open, "AES")
        private var sent = 0L
        private var received = 0L

        fun send(name: String, bytes: ByteArray) {
            val nameBytes = name.toByteArray()
            val plain = ByteArray(HEADER + nameBytes.size + bytes.size)
            plain[0] = TYPE_FILE
            plain[1] = (nameBytes.size shr 8).toByte()
            plain[2] = nameBytes.size.toByte()
            nameBytes.copyInto(plain, HEADER)
            bytes.copyInto(plain, HEADER + nameBytes.size)
            val sealed = cipher(Cipher.ENCRYPT_MODE, sealKey, sent++).doFinal(plain)
            if (sealed.size > MAX_FRAME) throw IOException("sync file too large for the link: ${bytes.size}")
            output.writeInt(sealed.size)
            output.write(sealed)
            output.flush()
        }

        /**
         * The next file from the other side, as its name and bytes. Null for
         * a message of a kind this build does not know, which a newer build
         * may send. Throws when the link ends or anything fails to open.
         */
        fun receive(): Pair<String, ByteArray>? {
            val size = input.readInt()
            if (size < TAG_BYTES || size > MAX_FRAME) throw ProtocolException("frame of $size bytes")
            val sealed = ByteArray(size).also(input::readFully)
            val plain = cipher(Cipher.DECRYPT_MODE, openKey, received++).doFinal(sealed)
            if (plain.isEmpty() || plain[0] != TYPE_FILE) return null
            if (plain.size < HEADER) throw ProtocolException("short file message")
            val nameSize = ((plain[1].toInt() and 0xFF) shl 8) or (plain[2].toInt() and 0xFF)
            if (HEADER + nameSize > plain.size) throw ProtocolException("name runs past the message")
            val name = String(plain, HEADER, nameSize, Charsets.UTF_8)
            return name to plain.copyOfRange(HEADER + nameSize, plain.size)
        }

        private fun cipher(mode: Int, key: SecretKeySpec, counter: Long): Cipher {
            val nonce = ByteArray(GCM_NONCE_BYTES)
            for (i in 0 until Long.SIZE_BYTES) {
                nonce[GCM_NONCE_BYTES - 1 - i] = (counter ushr (Byte.SIZE_BITS * i)).toByte()
            }
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, key, GCMParameterSpec(TAG_BITS, nonce)) }
        }
    }

    /** Runs the handshake as the [client] or the server; throws when the other side does not hold [key]. */
    fun handshake(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        client: Boolean,
        random: SecureRandom = SecureRandom(),
    ): Link {
        val data = DataInputStream(input)
        val out = DataOutputStream(output)
        val clientNonce: ByteArray
        val serverNonce: ByteArray
        if (client) {
            clientNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.write(clientNonce)
            out.flush()
            expectHeader(data)
            serverNonce = ByteArray(NONCE_BYTES).also(data::readFully)
            val proof = ByteArray(MAC_BYTES).also(data::readFully)
            if (!MessageDigest.isEqual(proof, mac(key, "server", clientNonce, serverNonce))) {
                throw ProtocolException("the other side has another code")
            }
            out.write(mac(key, "client", clientNonce, serverNonce))
            out.flush()
        } else {
            expectHeader(data)
            clientNonce = ByteArray(NONCE_BYTES).also(data::readFully)
            serverNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
            out.write(MAGIC)
            out.writeByte(VERSION)
            out.write(serverNonce)
            out.write(mac(key, "server", clientNonce, serverNonce))
            out.flush()
            val proof = ByteArray(MAC_BYTES).also(data::readFully)
            if (!MessageDigest.isEqual(proof, mac(key, "client", clientNonce, serverNonce))) {
                throw ProtocolException("the other side has another code")
            }
        }
        val up = mac(key, "client to server", clientNonce, serverNonce)
        val down = mac(key, "server to client", clientNonce, serverNonce)
        return if (client) Link(data, out, seal = up, open = down) else Link(data, out, seal = down, open = up)
    }

    private fun expectHeader(data: DataInputStream) {
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        val version = data.readUnsignedByte()
        if (!magic.contentEquals(MAGIC) || version != VERSION) throw ProtocolException("not the keyboard's link")
    }

    private fun mac(key: ByteArray, label: String, first: ByteArray, second: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(label.toByteArray())
            update(0.toByte())
            update(first)
            update(second)
            doFinal()
        }
}

/**
 * The link itself, with nothing of Android in it, so a test can run two of
 * them on one machine. [ProfileLink] owns the one this process runs.
 *
 * Whichever copy starts first binds the port and listens; the other finds it
 * taken and connects. When the listening copy goes away (its private space
 * locked), the other one's connection ends, and on its next turn round the
 * loop it binds the port itself, so the copy still running is always the one
 * listening. A connection from anything that does not hold the code fails the
 * handshake and is closed. One from the other copy stays open, and each side
 * sends its own sync file over it at once and after every pass that writes
 * one. A file that arrives goes into [mailbox], and [onArrived] runs a pass.
 *
 * Several copies with one code (a work profile and a private space) work as a
 * star: the one listening reads every other's file, and its own file carries
 * everything it merged from them.
 */
class ProfileLinkEngine(
    val code: String,
    private val mailbox: File,
    private val me: String,
    private val onArrived: suspend () -> Unit,
    private val onLinked: (Boolean) -> Unit = {},
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val key = ProfileCode.key(code)
    private val port = ProfileCode.port(code)
    /**
     * With a handler: this runs in the keyboard's process, and anything that
     * slips past the catches below must cost the link, never the keyboard.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, failure -> log("profile link", failure) },
    )
    private val sessions = CopyOnWriteArrayList<Session>()
    private val arrivals = Channel<Unit>(Channel.CONFLATED)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var stopped = false

    /** Whether the other copy is connected now. */
    val linked: Boolean get() = sessions.isNotEmpty()

    fun start() {
        scope.launch {
            // One pass for any number of files that came while one ran.
            while (true) {
                arrivals.receive()
                try {
                    onArrived()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("profile link: the pass after a file arrived", e)
                }
            }
        }
        scope.launch { loop() }
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        sessions.forEach { it.close() }
        scope.cancel()
    }

    /** Sends this copy's sync file to every copy linked now. A file still waiting to go is replaced. */
    fun send(name: String, bytes: ByteArray) {
        sessions.forEach { it.offer(name, bytes) }
    }

    /** Tries to link now rather than when the current wait ends. */
    fun kick() {
        wake.trySend(Unit)
    }

    private suspend fun loop() {
        var wait = MIN_WAIT_MS
        while (!stopped) {
            val lasted = try {
                turn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("profile link", e)
                -1L
            }
            if (lasted >= STEADY_MS) {
                // It was linked a while and went away: take the port now.
                wait = MIN_WAIT_MS
                continue
            }
            // Nothing that holds the code is there, or something else holds
            // the port. Wait longer each time, up to a quarter of an hour.
            withTimeoutOrNull(wait) { wake.receive() }
            wait = (wait * 2).coerceAtMost(MAX_WAIT_MS)
        }
    }

    /**
     * One turn: listen when the port is free, until [stop]; otherwise
     * connect to whoever has it, most likely the other copy. How long a link
     * that turn made lasted, or -1 when it made none.
     */
    private suspend fun turn(): Long {
        val listening = bind()
        if (listening != null) {
            serve(listening)
            return 0L
        }
        return connect()?.let { session(it, client = true) } ?: -1L
    }

    /** The port, or null when something has it already. Never throws: a build with no internet permission lands here. */
    private fun bind(): ServerSocket? {
        val socket = runCatching { ServerSocket() }.getOrNull() ?: return null
        return try {
            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), BACKLOG)
            socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    private fun connect(): Socket? {
        val socket = runCatching { Socket() }.getOrNull() ?: return null
        return try {
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS)
            socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            null
        }
    }

    /** Accepts until [stop] closes [listening]. One connection that fails ends only itself. */
    private suspend fun serve(listening: ServerSocket) = supervisorScope {
        server = listening
        try {
            while (!stopped) {
                val socket = listening.accept()
                launch { session(socket, client = false) }
            }
        } catch (e: IOException) {
            if (!stopped) log("profile link: stopped listening", e)
        } finally {
            server = null
            runCatching { listening.close() }
        }
    }

    /** One connection, to its end: how long it was linked, or -1 when it was not the other copy. */
    private suspend fun session(socket: Socket, client: Boolean): Long {
        val wire = runCatching {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            ProfileWire.handshake(socket.getInputStream(), socket.getOutputStream(), key, client)
                .also { socket.soTimeout = 0 }
        }.getOrNull()
        if (wire == null) {
            runCatching { socket.close() }
            return -1L
        }
        val session = Session(socket, wire)
        sessions += session
        onLinked(true)
        val since = System.currentTimeMillis()
        try {
            ownFile()?.let { (name, bytes) -> session.offer(name, bytes) }
            coroutineScope {
                launch { session.drain() }
                session.read { name, bytes -> arrive(name, bytes) }
                // Closed first: a write still in flight to a frozen peer
                // would otherwise hold this scope open for good.
                session.close()
                coroutineContext.cancelChildren()
            }
        } finally {
            session.close()
            sessions -= session
            if (sessions.isEmpty()) onLinked(false)
        }
        return System.currentTimeMillis() - since
    }

    /** This copy's newest sync file in the mailbox, the one to hand over on linking. */
    private fun ownFile(): Pair<String, ByteArray>? =
        mailbox.listFiles().orEmpty()
            .filter { it.isFile && SyncNaming.installId(it.name) == me }
            .maxByOrNull { it.lastModified() }
            ?.let { file -> runCatching { file.name to file.readBytes() }.getOrNull() }

    /** The other copy's file: into the mailbox in place of its older ones, then a pass. */
    private fun arrive(name: String, bytes: ByteArray) {
        // A sync file's own name only: nothing that walks out of the mailbox,
        // and nothing that would replace this copy's own file.
        if (name.contains('/') || name.contains('\\') || name.contains("..") || !SyncNaming.isOurs(name)) return
        val owner = SyncNaming.installId(name) ?: return
        if (owner == me) return
        runCatching {
            mailbox.mkdirs()
            val part = File(mailbox, "$name$PART")
            part.writeBytes(bytes)
            mailbox.listFiles().orEmpty()
                .filter { it.name != name && SyncNaming.installId(it.name) == owner }
                .forEach { it.delete() }
            if (!part.renameTo(File(mailbox, name))) {
                part.delete()
                throw IOException("could not move the file into place")
            }
        }.onFailure {
            log("profile link: could not keep the file that arrived", it)
            return
        }
        arrivals.trySend(Unit)
    }

    private class Session(private val socket: Socket, private val wire: ProfileWire.Link) {
        private val outbox = Channel<Pair<String, ByteArray>>(Channel.CONFLATED)

        fun offer(name: String, bytes: ByteArray) {
            outbox.trySend(name to bytes)
        }

        suspend fun drain() {
            for ((name, bytes) in outbox) {
                try {
                    wire.send(name, bytes)
                } catch (e: Exception) {
                    // Gone, or a file too large to send: the link ends here,
                    // and the next one sends the file again.
                    close()
                    return
                }
            }
        }

        /** Hands every file that arrives to [onFile], until the connection ends. */
        fun read(onFile: (String, ByteArray) -> Unit) {
            try {
                while (true) {
                    val (name, bytes) = wire.receive() ?: continue
                    onFile(name, bytes)
                }
            } catch (e: Exception) {
                // The other copy went away, the connection was closed here,
                // or a frame did not open: past that, the link cannot be
                // trusted.
            }
        }

        fun close() {
            outbox.close()
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val BACKLOG = 4
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val MIN_WAIT_MS = 2_000L
        private const val MAX_WAIT_MS = 15L * 60L * 1000L

        /** A link that lasted this long ended because the other copy went, not because it refused. */
        private const val STEADY_MS = 10_000L

        private const val PART = ".part"

        /**
         * Whether a copy holding [code] answers now, with one connection
         * made and closed: for a location not saved yet, which no link runs.
         */
        fun probe(code: String): Boolean {
            val socket = Socket()
            return try {
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), ProfileCode.port(code)),
                    CONNECT_TIMEOUT_MS,
                )
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                ProfileWire.handshake(socket.getInputStream(), socket.getOutputStream(), ProfileCode.key(code), client = true)
                true
            } catch (e: Exception) {
                false
            } finally {
                runCatching { socket.close() }
            }
        }
    }
}

/**
 * The link to this keyboard in another profile of the same phone (#1017): a
 * private space, a work profile. Android keeps each profile's files apart,
 * so no folder can be shared between the two copies, and a folder location
 * set up in one is not writable from the other. The profiles do share one
 * network stack, though, and a connection to 127.0.0.1 from one copy reaches
 * the other. Each copy keeps a mailbox like a Nearby device's
 * ([MailboxSink]): its own sync file is written there and sent across, and
 * the other copy's lands there.
 *
 * Runs while sync goes through an Other profile location, started by
 * [com.wasimaster.wmkeyboard.core.settings.sync.SyncWatcher]. Idle, it is a
 * socket waiting in the kernel: no timer, no wake-up.
 *
 * Android 17 closed the door between profiles for every app. The two copies
 * reach each other there only after [PERMISSION] is granted to each with adb,
 * which [separated] tells the screen to explain.
 */
object ProfileLink {

    private const val MAILBOX = "profile"

    /** Android 17. Named by number: the constant is newer than some of the SDKs this builds against. */
    private const val SEPARATED_FROM_SDK = 37

    /** How a user id is found from a uid: Android's `UserHandle.PER_USER_RANGE`. */
    private const val PER_USER_RANGE = 100_000

    /** How long Test the connection waits for a link this process already runs to come up. */
    private const val PROBE_MS = 4_000L

    /** Not granted at install, nor asked for: only adb can grant it. See [separated]. */
    const val PERMISSION = "android.permission.INTERACT_ACROSS_USERS"

    @Volatile
    private var engine: ProfileLinkEngine? = null

    private val _linked = MutableStateFlow<String?>(null)

    /** The code of the link that is up now, the other copy connected; null while none is. */
    val linked: StateFlow<String?> = _linked.asStateFlow()

    /** Where this copy's sync file and the other copy's are kept. */
    fun mailbox(context: Context): File = File(context.noBackupFilesDir, MAILBOX).apply { mkdirs() }

    /**
     * Runs the link for [code], or none for null. A new code ends the old
     * link first. [onArrived] runs a sync pass.
     */
    @Synchronized
    fun update(context: Context, code: String?, onArrived: suspend () -> Unit) {
        if (engine?.code == code) return
        engine?.stop()
        engine = null
        _linked.value = null
        if (code == null) return
        val app = context.applicationContext
        engine = ProfileLinkEngine(
            code = code,
            mailbox = mailbox(app),
            me = BackupInstall.id(app),
            onArrived = onArrived,
            onLinked = { up -> if (up) _linked.value = code else _linked.compareAndSet(code, null) },
            log = { message, failure -> BackupLog.w(message, failure) },
        ).also { it.start() }
    }

    /** Sends this copy's sync file across, when the other copy is linked. */
    fun send(name: String, bytes: ByteArray) {
        engine?.send(name, bytes)
    }

    /**
     * Whether the other copy answers now, for Test the connection. Asks the
     * link this process runs when it runs one for [code], and otherwise makes
     * one connection of its own.
     */
    suspend fun probe(code: String): Boolean {
        val running = engine?.takeIf { it.code == code }
        if (running != null) {
            if (running.linked) return true
            running.kick()
            return withTimeoutOrNull(PROBE_MS) { linked.first { it == code } } != null
        }
        return withContext(Dispatchers.IO) { ProfileLinkEngine.probe(code) }
    }

    /** Whether Android keeps the copies apart: Android 17 and later, until [PERMISSION] is granted with adb. */
    fun separated(context: Context): Boolean =
        Build.VERSION.SDK_INT >= SEPARATED_FROM_SDK &&
            context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED

    /** The adb command that lets this copy through, for this profile. The other copy shows its own. */
    fun grantCommand(context: Context): String =
        "adb shell pm grant --user ${android.os.Process.myUid() / PER_USER_RANGE} ${context.packageName} $PERMISSION"
}
