package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.FtpConfig
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupSink] over FTP, with TLS unless the user turns it off.
 *
 * Written against sockets rather than a library, deliberately: the alternative
 * was a dependency for a protocol whose control channel is line-based text.
 * What that costs is that every awkward corner is handled here — passive mode,
 * TLS session reuse on the data channel, and the fact that `LIST` output has no
 * defined format. [FtpListing] holds the parsing, and is tested.
 *
 * **On plain FTP.** With [FtpConfig.secure] off, the password crosses the
 * network as text. This app refuses that for WebDAV and for S3, and allows it
 * here only because a lot of old NAS boxes offer nothing else, and because the
 * screen says plainly what it costs. Encrypting the backup itself is the
 * mitigation worth having.
 */
class FtpSink(private val config: FtpConfig) : BackupSink {

    override val id: String get() = ID

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (config.host.isEmpty() || config.user.isEmpty()) {
                throw BackupSinkException(SinkError.NOT_CONFIGURED)
            }
            connect().use { it.changeToBackupDirectory() }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            connect().use { session ->
                session.changeToBackupDirectory()
                val partName = name + AutoBackupNaming.PART_SUFFIX
                session.store(partName, bytes)
                // FTP has a real rename, and unlike SAF every server has it.
                // A stale target would make RNTO fail, so it goes first.
                runCatching { session.command("DELE $name") }
                session.rename(partName, name)
            }
            SinkEntry(id = name, name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            connect().use { session ->
                session.changeToBackupDirectory()
                session.listing()
                    .filter { !it.isDirectory && AutoBackupNaming.isListed(it.name) }
                    .map {
                        SinkEntry(
                            id = it.name,
                            name = it.name,
                            sizeBytes = it.sizeBytes,
                            modifiedAtMs = it.modifiedAtMs,
                        )
                    }
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCancellable {
                connect().use { session ->
                    session.changeToBackupDirectory()
                    session.retrieve(entry.name).inputStream()
                }
            }
        }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        // Explicitly Unit: already gone is success, so the discarded result
        // of the delete below must not become the return value.
        runCancellable<Unit> {
            connect().use { session ->
                session.changeToBackupDirectory()
                runCatching { session.command("DELE ${entry.name}") }
            }
        }
    }

    private fun connect(): Session = Session(config)

    /**
     * One control connection, and whatever data connections it opens.
     *
     * Short-lived on purpose: a backup runs once a day, and a socket held open
     * between runs is a socket that has silently died since. Internal so the
     * Files tool's FTP place (`FtpFs`) speaks through the same login, TLS and
     * passive-mode code rather than a second copy of it.
     */
    internal class Session(private val config: FtpConfig) : Closeable {

        private val control: Socket
        private var reader: BufferedReader
        private var writer: OutputStream
        private var sslFactory: SSLSocketFactory? = null

        /**
         * One network activity log row for the whole session. The files carry
         * the bytes worth counting; the control chatter is left out.
         */
        private val netCall = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "FTP",
            scheme = if (config.secure) "ftps" else "ftp",
            host = config.host,
            port = config.port,
            route = config.path.trim().ifEmpty { null },
            background = BackupTraffic.unattended,
        )

        /** The first failing reply of the session, which becomes the row's status. */
        private var failedCode = 0

        init {
            control = try {
                Socket().apply {
                    soTimeout = READ_TIMEOUT_MS
                    connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
                }
            } catch (failure: Throwable) {
                netCall.fail(failure)
                netCall.end()
                throw BackupSinkException(SinkError.IO, failure)
            }
            reader = control.getInputStream().bufferedReader()
            writer = control.getOutputStream()

            try {
                expect(readReply(), 220)
                if (config.secure) upgradeToTls()
                login()
                // Binary. The default is ASCII, which on some servers rewrites line
                // endings inside the file and quietly corrupts an encrypted bundle.
                command("TYPE I")
            } catch (failure: Throwable) {
                // Nobody will close a session that never finished opening.
                runCatching { control.close() }
                netCall.status = failedCode
                netCall.fail(failure)
                netCall.end()
                throw failure
            }
        }

        private fun upgradeToTls() {
            expect(command("AUTH TLS"), 234)
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            sslFactory = factory
            val secure = factory.createSocket(control, config.host, config.port, false) as SSLSocket
            secure.useClientMode = true
            secure.startHandshake()
            reader = secure.inputStream.bufferedReader()
            writer = secure.outputStream
            // Protect the data channel too. Without these two the file itself
            // would cross in the clear while only the password was encrypted.
            expect(command("PBSZ 0"), 200)
            expect(command("PROT P"), 200)
        }

        private fun login() {
            val userReply = command("USER ${config.user}")
            val code = codeOf(userReply)
            if (code == 331) {
                val passReply = command("PASS ${config.password}")
                if (codeOf(passReply) !in 200..299) {
                    throw BackupSinkException(SinkError.PERMISSION_LOST)
                }
            } else if (code !in 200..299) {
                throw BackupSinkException(SinkError.PERMISSION_LOST)
            }
        }

        fun changeToBackupDirectory() {
            val path = config.path.trim().trim('/')
            if (path.isEmpty()) return
            val reply = command("CWD $path")
            if (codeOf(reply) !in 200..299) throw BackupSinkException(SinkError.TARGET_MISSING)
        }

        fun listing(): List<FtpListing.Entry> {
            // MLSD first: it is the only listing with a defined format. LIST is
            // the fallback, and its output is prose.
            val machine = runCatching { dataCommand("MLSD") }.getOrNull()
            if (machine != null) {
                val parsed = FtpListing.parseMlsd(machine.toString(Charsets.UTF_8).lines())
                if (parsed.isNotEmpty()) return parsed
            }
            val human = dataCommand("LIST")
            return FtpListing.parseList(
                human.toString(Charsets.UTF_8).lines(),
                System.currentTimeMillis(),
            )
        }

        fun retrieve(name: String): ByteArray = dataCommand("RETR $name")

        fun store(name: String, bytes: ByteArray) {
            openDataConnection().use { data ->
                val reply = command("STOR $name")
                if (codeOf(reply) !in 100..199) throw BackupSinkException(statusError(reply))
                data.getOutputStream().use { it.write(bytes) }
                netCall.sent(bytes.size.toLong())
            }
            val done = readReply()
            if (codeOf(done) !in 200..299) throw BackupSinkException(statusError(done))
        }

        fun rename(from: String, to: String) {
            expect(command("RNFR $from"), 350)
            val reply = command("RNTO $to")
            if (codeOf(reply) !in 200..299) throw BackupSinkException(statusError(reply))
        }

        /**
         * RETR [name], streamed into [out] rather than held, for files of any
         * size. [out] may throw to stop early; the session is then mid-transfer
         * and must be closed, not reused. Returns the bytes received.
         */
        fun retrieveTo(name: String, out: OutputStream): Long {
            var total = 0L
            try {
                openDataConnection().use { data ->
                    val reply = command("RETR $name")
                    if (codeOf(reply) !in 100..199) throw BackupSinkException(statusError(reply))
                    data.getInputStream().use { input ->
                        val buffer = ByteArray(STREAM_BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                        }
                    }
                }
            } finally {
                netCall.received(total)
            }
            val done = readReply()
            if (codeOf(done) !in 200..299) throw BackupSinkException(statusError(done))
            return total
        }

        /**
         * STOR [name] from [input], streamed, telling [onSent] each piece's
         * byte count as it goes; [onSent] may throw to stop. STOR replaces a
         * file of that name, so the caller makes sure the name is free.
         */
        fun storeFrom(name: String, input: InputStream, onSent: (Long) -> Unit) {
            var total = 0L
            try {
                openDataConnection().use { data ->
                    val reply = command("STOR $name")
                    if (codeOf(reply) !in 100..199) throw BackupSinkException(statusError(reply))
                    data.getOutputStream().use { output ->
                        val buffer = ByteArray(STREAM_BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            total += n
                            onSent(n.toLong())
                        }
                    }
                }
            } finally {
                netCall.sent(total)
            }
            val done = readReply()
            if (codeOf(done) !in 200..299) throw BackupSinkException(statusError(done))
        }

        /** Runs a command whose answer arrives on a data connection. */
        fun dataCommand(request: String): ByteArray {
            val bytes = openDataConnection().use { data ->
                val reply = command(request)
                if (codeOf(reply) !in 100..199) throw BackupSinkException(statusError(reply))
                data.getInputStream().readBytes().also { netCall.received(it.size.toLong()) }
            }
            val done = readReply()
            if (codeOf(done) !in 200..299) throw BackupSinkException(statusError(done))
            return bytes
        }

        /**
         * A passive-mode data socket.
         *
         * `EPSV` first, then `PASV`. `EPSV` gives only a port, so the data
         * connection goes to the same host as the control one — which is what
         * works through NAT, where `PASV` hands out the server's own private
         * address and nothing can reach it.
         */
        private fun openDataConnection(): Socket {
            val epsv = runCatching { command("EPSV") }.getOrNull()
            val target = epsv
                ?.takeIf { codeOf(it) == 229 }
                ?.let(FtpListing::parseEpsv)
                ?.let { config.host to it }
                ?: FtpListing.parsePasv(command("PASV"))
                ?: throw BackupSinkException(SinkError.IO)

            val plain = try {
                Socket().apply {
                    soTimeout = READ_TIMEOUT_MS
                    connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MS)
                }
            } catch (failure: Throwable) {
                throw BackupSinkException(SinkError.IO, failure)
            }
            val factory = sslFactory ?: return plain
            return (factory.createSocket(plain, target.first, target.second, true) as SSLSocket)
                .apply {
                    useClientMode = true
                    startHandshake()
                }
        }

        fun command(request: String): String {
            writer.write("$request\r\n".toByteArray(Charsets.UTF_8))
            writer.flush()
            return readReply()
        }

        /**
         * One reply, including a multi-line one.
         *
         * A multi-line reply opens with `250-` and ends with a line starting
         * `250 ` — the same code and a space. Reading only the first line would
         * leave the rest in the buffer and desynchronise every command after.
         */
        private fun readReply(): String {
            val first = reader.readLine() ?: throw BackupSinkException(SinkError.IO)
            if (failedCode == 0 && codeOf(first) >= 400) failedCode = codeOf(first)
            if (first.length < 4 || first[3] != '-') return first
            val code = first.substring(0, 3)
            val all = StringBuilder(first)
            while (true) {
                val line = reader.readLine() ?: break
                all.append('\n').append(line)
                if (line.startsWith("$code ")) break
            }
            return all.toString()
        }

        private fun codeOf(reply: String): Int =
            reply.take(3).toIntOrNull() ?: 0

        private fun expect(reply: String, code: Int) {
            if (codeOf(reply) != code) throw BackupSinkException(statusError(reply))
        }

        private fun statusError(reply: String): SinkError = when (codeOf(reply)) {
            530, 532 -> SinkError.PERMISSION_LOST
            550, 553 -> SinkError.TARGET_MISSING
            452, 552 -> SinkError.OUT_OF_SPACE
            else -> SinkError.IO
        }

        override fun close() {
            runCatching { command("QUIT") }
            runCatching { control.close() }
            // FTP's own codes are the status: 226 is "closing, transfer done".
            netCall.status = if (failedCode != 0) failedCode else 226
            netCall.end()
        }
    }

    companion object {
        const val ID = "ftp"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val STREAM_BUFFER = 64 * 1024
    }
}
