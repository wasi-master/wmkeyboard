package com.wasimaster.wmkeyboard.core.settings.sink.smb

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** A reply whose NT status was not the one the call wanted. */
internal class SmbStatusException(val status: Long, what: String) :
    IOException("$what: 0x" + java.lang.Long.toHexString(status).padStart(8, '0'))

/**
 * The server cannot give the protection the caller demanded: encryption when
 * [SmbConfig.encrypt][com.wasimaster.wmkeyboard.core.settings.SmbConfig.encrypt]
 * is on, or signing on a guest session.
 */
internal class SmbUnsafeException(what: String) : IOException(what)

/**
 * An SMB 2 and 3 client over direct TCP, with just the commands a backup
 * needs: create, read, write, close, list, rename and delete, one at a time.
 *
 * Hand-written for the same reason as the FTP sink. The libraries that exist
 * bring an event bus that works by reflection and a logging facade, for a
 * protocol whose useful subset is a dozen fixed-layout messages. What is
 * delegated is the cryptography, to Bouncy Castle via [SmbCrypto].
 *
 * Security, in the order it is set up:
 * - Dialects 2.0.2 to 3.1.1. On 3.1.1 the whole negotiation and sign-in are
 *   hashed into the keys (pre-authentication integrity), which is what stops
 *   a machine in the middle from talking both ends down to something weaker.
 * - NTLMv2 inside SPNEGO, with the MIC and mechListMIC Windows and Samba want.
 * - Every request after the sign-in is signed, and a signed reply is checked.
 * - Encryption with AES-128-GCM or AES-128-CCM when the caller asks for it or
 *   the server demands it. An encrypted message is not also signed; its tag is
 *   the integrity.
 *
 * One request in flight at a time, one credit each, and I/O in chunks of at
 * most 64 KiB. Slower than it could be, and a backup does not notice.
 */
internal class Smb2Client private constructor(
    private val socket: Socket,
    private val host: String,
    private val traffic: (sent: Long, received: Long) -> Unit,
) : Closeable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream().buffered()
    private val random = SecureRandom()

    private var messageId = 0L
    private var sessionId = 0L
    private var treeId = 0L

    var dialect = 0
        private set
    private var serverSecurityMode = 0
    private var serverCapabilities = 0L
    private var maxRead = CHUNK
    private var maxWrite = CHUNK
    private var maxTransact = CHUNK

    /** 3.1.1 only: the running SHA-512 over negotiate and session setup. */
    private var preauthHash = ByteArray(PREAUTH_BYTES)
    private var cipher: SmbCrypto.Cipher? = null

    private var signingKey: ByteArray? = null
    private var encryptKey: ByteArray? = null
    private var decryptKey: ByteArray? = null
    private var encrypting = false

    /** Signed in: from here every reply must be signed or sealed. */
    private var established = false

    val is3: Boolean get() = dialect >= DIALECT_300

    // ---- Connection set-up ----

    fun negotiate() {
        val dialects = intArrayOf(DIALECT_202, DIALECT_210, DIALECT_300, DIALECT_302, DIALECT_311)
        val body = LeWriter()
        body.u16(36).u16(dialects.size).u16(SECURITY_SIGNING_ENABLED).u16(0)
        body.u32(CAP_ENCRYPTION)
        body.bytes(ByteArray(16).also(random::nextBytes)) // ClientGuid
        val contextOffsetAt = body.size
        body.u32(0).u16(2).u16(0) // NegotiateContextOffset, Count, Reserved2
        for (d in dialects) body.u16(d)
        // Contexts start 8-aligned from the start of the SMB2 header.
        while ((HEADER_BYTES + body.size) % 8 != 0) body.u8(0)
        body.putU32(contextOffsetAt, HEADER_BYTES + body.size)
        // Pre-authentication integrity: SHA-512 and a fresh salt.
        body.u16(CONTEXT_PREAUTH).u16(38).u32(0)
        body.u16(1).u16(32).u16(HASH_SHA512).bytes(ByteArray(32).also(random::nextBytes))
        body.align(8)
        // Encryption: GCM first, CCM for servers that have nothing newer.
        body.u16(CONTEXT_ENCRYPTION).u16(6).u32(0)
        body.u16(2).u16(SmbCrypto.Cipher.AES_128_GCM.id).u16(SmbCrypto.Cipher.AES_128_CCM.id)

        val request = message(COMMAND_NEGOTIATE, body.toByteArray(), creditCharge = 0)
        writeFrame(request)
        val response = receive(request.u64(24))
        response.expect(STATUS_SUCCESS, "negotiate")
        val b = response.bytes
        dialect = b.u16(HEADER_BYTES + 4)
        serverSecurityMode = b.u16(HEADER_BYTES + 2)
        serverCapabilities = b.u32(HEADER_BYTES + 24)
        maxTransact = minOf(CHUNK.toLong(), b.u32(HEADER_BYTES + 28)).toInt()
        maxRead = minOf(CHUNK.toLong(), b.u32(HEADER_BYTES + 32)).toInt()
        maxWrite = minOf(CHUNK.toLong(), b.u32(HEADER_BYTES + 36)).toInt()
        if (dialect !in intArrayOf(DIALECT_202, DIALECT_210, DIALECT_300, DIALECT_302, DIALECT_311)) {
            throw SmbStatusException(STATUS_NOT_SUPPORTED, "negotiate: dialect ${Integer.toHexString(dialect)}")
        }
        when {
            dialect == DIALECT_311 -> {
                preauthHash = SmbCrypto.sha512(preauthHash, request)
                preauthHash = SmbCrypto.sha512(preauthHash, b)
                cipher = negotiatedCipher(b)
            }
            dialect >= DIALECT_300 && serverCapabilities and CAP_ENCRYPTION != 0L ->
                cipher = SmbCrypto.Cipher.AES_128_CCM
        }
    }

    /** The cipher the server picked from our encryption context, 3.1.1 only. */
    private fun negotiatedCipher(b: ByteArray): SmbCrypto.Cipher? {
        val count = b.u16(HEADER_BYTES + 6)
        var at = b.u32(HEADER_BYTES + 60).toInt()
        repeat(count) {
            if (at + 8 > b.size) return null
            val type = b.u16(at)
            val len = b.u16(at + 2)
            if (type == CONTEXT_ENCRYPTION && len >= 4 && b.u16(at + 8) >= 1) {
                return SmbCrypto.Cipher.of(b.u16(at + 10))
            }
            at += 8 + len
            at = (at + 7) and 7.inv()
        }
        return null
    }

    /**
     * NTLM in SPNEGO, two round trips. Afterwards every request is signed,
     * and encrypted when [requireEncryption] is set or the server says so.
     */
    fun login(user: String, password: String, domain: String, requireEncryption: Boolean) {
        val ntlm = Ntlm(user, password, domain)
        var sessionHash = preauthHash

        val first = sessionSetup(Spnego.init(ntlm.negotiate()))
        if (dialect == DIALECT_311) {
            sessionHash = SmbCrypto.sha512(sessionHash, first.request)
            sessionHash = SmbCrypto.sha512(sessionHash, first.response.bytes)
        }
        if (first.response.status != STATUS_MORE_PROCESSING) {
            throw SmbStatusException(first.response.status, "session setup")
        }
        sessionId = first.response.bytes.u64(40)
        val challenge = Spnego.responseToken(first.response.securityBuffer())
            ?: throw SmbStatusException(STATUS_LOGON_FAILURE, "session setup: no NTLM challenge")

        val authenticate = ntlm.authenticate(challenge)
        val token = Spnego.response(authenticate, ntlm.sign(Spnego.mechTypeList))
        val secondRequest = sessionSetupRequest(token)
        if (dialect == DIALECT_311) sessionHash = SmbCrypto.sha512(sessionHash, secondRequest)
        deriveKeys(ntlm.exportedSessionKey.copyOf(SmbCrypto.KEY_BYTES), sessionHash)
        writeFrame(secondRequest)
        val second = receive(secondRequest.u64(24))
        second.expect(STATUS_SUCCESS, "session setup")

        val flags = second.bytes.u16(HEADER_BYTES + 2)
        if (flags and (SESSION_GUEST or SESSION_NULL) != 0) {
            // Samba's "map to guest = bad user" answers a wrong password with
            // a guest session instead of a refusal. Backing up into whatever
            // guests may write is not what the user set up, and a guest has
            // no key to sign with besides.
            throw SmbStatusException(STATUS_LOGON_FAILURE, "session setup: signed in as a guest")
        }
        if (requireEncryption || flags and SESSION_ENCRYPT_DATA != 0) startEncryption()
        established = true
    }

    private fun startEncryption() {
        if (cipher == null || encryptKey == null) {
            throw SmbUnsafeException("server cannot encrypt (dialect ${Integer.toHexString(dialect)})")
        }
        encrypting = true
    }

    private fun deriveKeys(sessionKey: ByteArray, sessionHash: ByteArray) {
        when {
            dialect == DIALECT_311 -> {
                signingKey = SmbCrypto.kdf(sessionKey, label("SMBSigningKey"), sessionHash)
                encryptKey = SmbCrypto.kdf(sessionKey, label("SMBC2SCipherKey"), sessionHash)
                decryptKey = SmbCrypto.kdf(sessionKey, label("SMBS2CCipherKey"), sessionHash)
            }
            dialect >= DIALECT_300 -> {
                signingKey = SmbCrypto.kdf(sessionKey, label("SMB2AESCMAC"), label("SmbSign"))
                encryptKey = SmbCrypto.kdf(sessionKey, label("SMB2AESCCM"), label("ServerIn "))
                decryptKey = SmbCrypto.kdf(sessionKey, label("SMB2AESCCM"), label("ServerOut"))
            }
            else -> signingKey = sessionKey
        }
    }

    private class Exchange(val request: ByteArray, val response: Response)

    private fun sessionSetupRequest(token: ByteArray): ByteArray {
        val body = LeWriter()
        body.u16(25).u8(0).u8(SECURITY_SIGNING_ENABLED).u32(0).u32(0)
        body.u16(HEADER_BYTES + 24).u16(token.size).u64(0)
        body.bytes(token)
        return message(COMMAND_SESSION_SETUP, body.toByteArray())
    }

    private fun sessionSetup(token: ByteArray): Exchange {
        val request = sessionSetupRequest(token)
        writeFrame(request)
        return Exchange(request, receive(request.u64(24)))
    }

    fun treeConnect(share: String) {
        val path = utf16("\\\\$host\\$share")
        val body = LeWriter().u16(9).u16(0).u16(HEADER_BYTES + 8).u16(path.size).bytes(path)
        val response = call(COMMAND_TREE_CONNECT, body.toByteArray())
        response.expect(STATUS_SUCCESS, "tree connect")
        treeId = response.bytes.u32(36)
        val shareFlags = response.bytes.u32(HEADER_BYTES + 4)
        if (shareFlags and SHARE_ENCRYPT_DATA != 0L && !encrypting) startEncryption()
    }

    // ---- Files ----

    class Handle(val id: ByteArray, val size: Long, val modifiedMs: Long, val directory: Boolean = false)

    class DirEntry(val name: String, val size: Long, val modifiedMs: Long, val directory: Boolean)

    fun create(path: String, access: Long, disposition: Int, options: Int, share: Int = SHARE_ALL): Handle {
        val name = utf16(path)
        val body = LeWriter()
        body.u16(57).u8(0).u8(0).u32(IMPERSONATION).u64(0).u64(0)
        body.u32(access).u32(0).u32(share).u32(disposition).u32(options)
        body.u16(HEADER_BYTES + 56).u16(name.size).u32(0).u32(0)
        // The buffer has to hold at least one byte, even for the share root.
        body.bytes(if (name.isEmpty()) ByteArray(1) else name)
        val response = call(COMMAND_CREATE, body.toByteArray())
        response.expect(STATUS_SUCCESS, "create $path")
        val b = response.bytes
        return Handle(
            id = b.slice(HEADER_BYTES + 64, 16),
            size = b.u64(HEADER_BYTES + 48),
            modifiedMs = FileTime.toMillis(b.u64(HEADER_BYTES + 24)),
            directory = b.u32(HEADER_BYTES + 56) and ATTRIBUTE_DIRECTORY != 0L,
        )
    }

    fun close(handle: Handle) {
        val body = LeWriter().u16(24).u16(0).u32(0).bytes(handle.id)
        call(COMMAND_CLOSE, body.toByteArray()).expect(STATUS_SUCCESS, "close")
    }

    fun write(handle: Handle, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val n = minOf(maxWrite, data.size - offset)
            val body = LeWriter()
            body.u16(49).u16(HEADER_BYTES + 48).u32(n).u64(offset.toLong()).bytes(handle.id)
            body.u32(0).u32(0).u16(0).u16(0).u32(0)
            body.bytes(data.copyOfRange(offset, offset + n))
            val response = call(COMMAND_WRITE, body.toByteArray())
            response.expect(STATUS_SUCCESS, "write")
            val written = response.bytes.u32(HEADER_BYTES + 4).toInt()
            if (written <= 0) throw SmbStatusException(STATUS_DISK_FULL, "write: nothing written")
            offset += written
        }
    }

    /**
     * Writes everything [from] holds to [handle] from offset 0, a chunk at a
     * time, for files too big to hold whole. [onSent] hears each chunk.
     */
    fun writeFrom(handle: Handle, from: java.io.InputStream, onSent: (Long) -> Unit = {}) {
        val chunk = ByteArray(maxWrite)
        var offset = 0L
        while (true) {
            var n = 0
            while (n < chunk.size) {
                val r = from.read(chunk, n, chunk.size - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            var sent = 0
            while (sent < n) {
                val body = LeWriter()
                body.u16(49).u16(HEADER_BYTES + 48).u32(n - sent).u64(offset).bytes(handle.id)
                body.u32(0).u32(0).u16(0).u16(0).u32(0)
                body.bytes(chunk.copyOfRange(sent, n))
                val response = call(COMMAND_WRITE, body.toByteArray())
                response.expect(STATUS_SUCCESS, "write")
                val written = response.bytes.u32(HEADER_BYTES + 4).toInt()
                if (written <= 0) throw SmbStatusException(STATUS_DISK_FULL, "write: nothing written")
                sent += written
                offset += written
                onSent(written.toLong())
            }
            if (n < chunk.size) break
        }
    }

    /** Reads [handle] from the start into [to], a chunk at a time. [to] may throw to stop. */
    fun readTo(handle: Handle, to: java.io.OutputStream) {
        var offset = 0L
        while (true) {
            val body = LeWriter()
            body.u16(49).u8(0x50).u8(0).u32(maxRead).u64(offset).bytes(handle.id)
            body.u32(0).u32(0).u32(0).u16(0).u16(0).u8(0)
            val response = call(COMMAND_READ, body.toByteArray())
            if (response.status == STATUS_END_OF_FILE) break
            response.expect(STATUS_SUCCESS, "read")
            val b = response.bytes
            val dataOffset = b.u8(HEADER_BYTES + 2)
            val length = b.u32(HEADER_BYTES + 4).toInt()
            if (length == 0) break
            to.write(b, dataOffset, length)
            offset += length
        }
    }

    fun readAll(handle: Handle): ByteArray {
        val out = java.io.ByteArrayOutputStream(maxOf(0, minOf(handle.size, Int.MAX_VALUE.toLong()).toInt()))
        var offset = 0L
        while (true) {
            val body = LeWriter()
            body.u16(49).u8(0x50).u8(0).u32(maxRead).u64(offset).bytes(handle.id)
            body.u32(0).u32(0).u32(0).u16(0).u16(0).u8(0)
            val response = call(COMMAND_READ, body.toByteArray())
            if (response.status == STATUS_END_OF_FILE) break
            response.expect(STATUS_SUCCESS, "read")
            val b = response.bytes
            val dataOffset = b.u8(HEADER_BYTES + 2)
            val length = b.u32(HEADER_BYTES + 4).toInt()
            if (length == 0) break
            out.write(b, dataOffset, length)
            offset += length
        }
        return out.toByteArray()
    }

    fun list(dir: Handle): List<DirEntry> {
        val out = ArrayList<DirEntry>()
        var first = true
        while (true) {
            val pattern = utf16("*")
            val body = LeWriter()
            body.u16(33).u8(FILE_DIRECTORY_INFORMATION).u8(if (first) QUERY_RESTART else 0).u32(0).bytes(dir.id)
            body.u16(HEADER_BYTES + 32).u16(pattern.size).u32(maxTransact).bytes(pattern)
            first = false
            val response = call(COMMAND_QUERY_DIRECTORY, body.toByteArray())
            if (response.status == STATUS_NO_MORE_FILES) break
            response.expect(STATUS_SUCCESS, "query directory")
            val b = response.bytes
            var at = b.u16(HEADER_BYTES + 2)
            val end = at + b.u32(HEADER_BYTES + 4).toInt()
            while (at < end) {
                val next = b.u32(at).toInt()
                val nameLength = b.u32(at + 60).toInt()
                val name = fromUtf16(b, at + 64, nameLength)
                if (name != "." && name != "..") {
                    out += DirEntry(
                        name = name,
                        size = b.u64(at + 40),
                        modifiedMs = FileTime.toMillis(b.u64(at + 24)),
                        directory = b.u32(at + 56) and ATTRIBUTE_DIRECTORY != 0L,
                    )
                }
                if (next == 0) break
                at += next
            }
        }
        return out
    }

    /** Renames the open [handle] to [target], a path from the share root. */
    fun rename(handle: Handle, target: String, replace: Boolean) {
        val name = utf16(target)
        val info = LeWriter().u8(if (replace) 1 else 0).zeros(7).u64(0).u32(name.size).bytes(name).toByteArray()
        val body = LeWriter()
        body.u16(33).u8(INFO_FILE).u8(FILE_RENAME_INFORMATION).u32(info.size).u16(HEADER_BYTES + 32).u16(0).u32(0)
        body.bytes(handle.id).bytes(info)
        call(COMMAND_SET_INFO, body.toByteArray()).expect(STATUS_SUCCESS, "rename")
    }

    // ---- Messages ----

    class Response(val bytes: ByteArray) {
        val status: Long get() = bytes.u32(8)

        fun expect(wanted: Long, what: String) {
            if (status != wanted) throw SmbStatusException(status, what)
        }

        fun securityBuffer(): ByteArray {
            val off = bytes.u16(HEADER_BYTES + 4)
            val len = bytes.u16(HEADER_BYTES + 6)
            return if (off + len <= bytes.size) bytes.slice(off, len) else ByteArray(0)
        }
    }

    private fun call(command: Int, body: ByteArray): Response {
        val request = message(command, body)
        val id = request.u64(24)
        writeFrame(protect(request))
        return receive(id)
    }

    private fun message(command: Int, body: ByteArray, creditCharge: Int = 1): ByteArray {
        val h = LeWriter(HEADER_BYTES + body.size)
        h.bytes(PROTOCOL_SMB2).u16(HEADER_BYTES)
        h.u16(if (dialect == DIALECT_202 || dialect == 0) 0 else creditCharge)
        h.u32(0).u16(command).u16(CREDITS_ASKED).u32(0).u32(0)
        h.u64(messageId++).u32(0).u32(treeId).u64(sessionId).zeros(16)
        h.bytes(body)
        return h.toByteArray()
    }

    /** Signs or encrypts a finished request, as the session requires. */
    private fun protect(request: ByteArray): ByteArray {
        if (encrypting) return seal(request)
        val key = signingKey ?: return request
        request.putU32(16, request.u32(16) or FLAG_SIGNED)
        System.arraycopy(signature(key, request), 0, request, 48, 16)
        return request
    }

    private fun signature(key: ByteArray, message: ByteArray): ByteArray {
        val copy = message.copyOf()
        java.util.Arrays.fill(copy, 48, 64, 0)
        return if (is3) SmbCrypto.aesCmac(key, copy) else SmbCrypto.hmacSha256(key, copy).copyOf(16)
    }

    private fun seal(plain: ByteArray): ByteArray {
        val c = cipher ?: throw SmbUnsafeException("no cipher")
        val header = LeWriter(TRANSFORM_BYTES)
        header.bytes(PROTOCOL_TRANSFORM).zeros(16)
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        java.util.Arrays.fill(nonce, c.nonceBytes, 16, 0)
        header.bytes(nonce).u32(plain.size).u16(0).u16(1).u64(sessionId)
        val h = header.toByteArray()
        val key = encryptKey ?: throw SmbUnsafeException("no encryption key")
        val sealed = SmbCrypto.seal(c, key, nonce, h.copyOfRange(20, TRANSFORM_BYTES), plain)
        val tagAt = sealed.size - 16
        System.arraycopy(sealed, tagAt, h, 4, 16)
        return h + sealed.copyOf(tagAt)
    }

    private fun unseal(frame: ByteArray): ByteArray {
        val c = cipher ?: throw IOException("encrypted reply on a session without a cipher")
        val key = decryptKey ?: throw IOException("encrypted reply before the keys")
        val tag = frame.slice(4, 16)
        val nonce = frame.slice(20, 16)
        val aad = frame.copyOfRange(20, TRANSFORM_BYTES)
        return SmbCrypto.open(c, key, nonce, aad, frame.copyOfRange(TRANSFORM_BYTES, frame.size) + tag)
    }

    /**
     * The reply to [id], skipping anything else the server sends meanwhile:
     * "still working" interim replies and lease or oplock break notices.
     */
    private fun receive(id: Long): Response {
        while (true) {
            var frame = readFrame()
            val wasSealed = frame.size >= 4 && frame.copyOf(4).contentEquals(PROTOCOL_TRANSFORM)
            if (wasSealed) frame = unseal(frame)
            if (frame.size < HEADER_BYTES || !frame.copyOf(4).contentEquals(PROTOCOL_SMB2)) {
                throw IOException("not an SMB2 reply")
            }
            val response = Response(frame)
            if (frame.u64(24) != id) continue
            val flags = frame.u32(16)
            if (response.status == STATUS_PENDING && flags and FLAG_ASYNC != 0L) continue
            val key = signingKey
            when {
                wasSealed -> Unit
                encrypting && response.status == STATUS_SUCCESS ->
                    // Asked for encryption and answered in the clear: someone
                    // in the middle, or a server ignoring the session's rules.
                    throw SmbUnsafeException("unencrypted reply on an encrypted session")
                key != null && flags and FLAG_SIGNED != 0L -> {
                    if (!SmbCrypto.equal(signature(key, frame), frame.slice(48, 16))) {
                        throw IOException("reply signature does not match")
                    }
                }
                // Every request after the sign-in is signed, and a server must
                // then sign its reply. An unsigned one is a stripped one.
                established && key != null -> throw IOException("unsigned reply on a signed session")
            }
            return response
        }
    }

    // ---- Transport: direct TCP, a 4-byte length in front of every message ----

    private fun writeFrame(message: ByteArray) {
        val n = message.size
        output.write(byteArrayOf(0, (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        output.write(message)
        output.flush()
        traffic(n + 4L, 0L)
    }

    private fun readFrame(): ByteArray {
        val head = ByteArray(4)
        input.readFully(head)
        val n = (head.u8(1) shl 16) or (head.u8(2) shl 8) or head.u8(3)
        if (head[0].toInt() != 0 || n > MAX_FRAME) throw IOException("bad SMB frame")
        return ByteArray(n).also {
            input.readFully(it)
            traffic(0L, n + 4L)
        }
    }

    override fun close() {
        runCatching {
            if (treeId != 0L) call(COMMAND_TREE_DISCONNECT, LeWriter().u16(4).u16(0).toByteArray())
            if (sessionId != 0L) call(COMMAND_LOGOFF, LeWriter().u16(4).u16(0).toByteArray())
        }
        runCatching { socket.close() }
    }

    companion object {
        fun connect(
            host: String,
            port: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            traffic: (sent: Long, received: Long) -> Unit,
        ): Smb2Client {
            val socket = Socket()
            try {
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
            return Smb2Client(socket, host, traffic)
        }

        /** MS-SMB2 writes its KDF labels with the NUL included. */
        private fun label(s: String): ByteArray = (s + "\u0000").toByteArray(Charsets.US_ASCII)

        const val HEADER_BYTES = 64
        private const val TRANSFORM_BYTES = 52
        private const val PREAUTH_BYTES = 64
        private const val CHUNK = 65536
        private const val MAX_FRAME = 16 * 1024 * 1024
        private const val CREDITS_ASKED = 64

        private val PROTOCOL_SMB2 = byteArrayOf(0xFE.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())
        private val PROTOCOL_TRANSFORM = byteArrayOf(0xFD.toByte(), 'S'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte())

        const val DIALECT_202 = 0x0202
        const val DIALECT_210 = 0x0210
        const val DIALECT_300 = 0x0300
        const val DIALECT_302 = 0x0302
        const val DIALECT_311 = 0x0311

        private const val COMMAND_NEGOTIATE = 0
        private const val COMMAND_SESSION_SETUP = 1
        private const val COMMAND_LOGOFF = 2
        private const val COMMAND_TREE_CONNECT = 3
        private const val COMMAND_TREE_DISCONNECT = 4
        private const val COMMAND_CREATE = 5
        private const val COMMAND_CLOSE = 6
        private const val COMMAND_READ = 8
        private const val COMMAND_WRITE = 9
        private const val COMMAND_QUERY_DIRECTORY = 0x0E
        private const val COMMAND_SET_INFO = 0x11

        private const val FLAG_ASYNC = 0x2L
        private const val FLAG_SIGNED = 0x8L
        private const val SECURITY_SIGNING_ENABLED = 0x1
        private const val CAP_ENCRYPTION = 0x40L
        private const val CONTEXT_PREAUTH = 1
        private const val CONTEXT_ENCRYPTION = 2
        private const val HASH_SHA512 = 1
        private const val SESSION_GUEST = 0x1
        private const val SESSION_NULL = 0x2
        private const val SESSION_ENCRYPT_DATA = 0x4
        private const val SHARE_ENCRYPT_DATA = 0x8000L

        private const val IMPERSONATION = 2
        const val SHARE_ALL = 0x7
        private const val INFO_FILE = 1
        private const val FILE_RENAME_INFORMATION = 10
        private const val FILE_DIRECTORY_INFORMATION = 1
        private const val QUERY_RESTART = 1
        private const val ATTRIBUTE_DIRECTORY = 0x10L

        const val ACCESS_READ_DATA = 0x1L
        const val ACCESS_WRITE_DATA = 0x2L
        const val ACCESS_READ_ATTRIBUTES = 0x80L
        const val ACCESS_WRITE_ATTRIBUTES = 0x100L
        const val ACCESS_DELETE = 0x10000L
        const val ACCESS_SYNCHRONIZE = 0x100000L

        const val DISPOSITION_OPEN = 1

        /** Creates, and fails with a name collision when something has the name. */
        const val DISPOSITION_CREATE = 2
        const val DISPOSITION_OPEN_IF = 3
        const val DISPOSITION_OVERWRITE_IF = 5

        const val OPTION_DIRECTORY = 0x1
        const val OPTION_NON_DIRECTORY = 0x40
        const val OPTION_DELETE_ON_CLOSE = 0x1000

        const val STATUS_SUCCESS = 0L
        const val STATUS_PENDING = 0x103L
        const val STATUS_NO_MORE_FILES = 0x80000006L
        const val STATUS_MORE_PROCESSING = 0xC0000016L
        const val STATUS_END_OF_FILE = 0xC0000011L
        const val STATUS_ACCESS_DENIED = 0xC0000022L
        const val STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034L
        const val STATUS_OBJECT_NAME_COLLISION = 0xC0000035L
        const val STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003AL
        const val STATUS_LOGON_FAILURE = 0xC000006DL
        const val STATUS_PASSWORD_EXPIRED = 0xC0000071L
        const val STATUS_ACCOUNT_DISABLED = 0xC0000072L
        const val STATUS_DISK_FULL = 0xC000007FL
        const val STATUS_QUOTA_EXCEEDED = 0xC0000044L
        const val STATUS_NOT_SUPPORTED = 0xC00000BBL
        const val STATUS_BAD_NETWORK_NAME = 0xC00000CCL
        const val STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234L
    }
}
