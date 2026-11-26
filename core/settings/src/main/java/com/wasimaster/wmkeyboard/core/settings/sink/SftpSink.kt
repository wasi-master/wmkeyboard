package com.wasimaster.wmkeyboard.core.settings.sink

import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchChangedHostKeyException
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.SftpConfig
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpAlgorithms
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpClient
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpStatusException
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupSink] over SFTP: JSch for the SSH connection, [SftpClient] for the
 * file protocol on top.
 *
 * **Host keys.** The first connection records the server's key through
 * [onHostKey]; every later one must see the same key or it refuses with
 * [SinkError.UNSAFE]. That is trust on first use, the same as `ssh` asking
 * "are you sure you want to continue connecting" and remembering a yes, minus
 * the question: the Test button in the location sheet is that first use.
 *
 * A backup goes to a `.part` name and is renamed into place, atomically on
 * OpenSSH through its `posix-rename` extension.
 */
class SftpSink(
    private val config: SftpConfig,
    /** Called with `<type> <base64>` when a connection saw a key and none was stored. */
    private val onHostKey: (String) -> Unit = {},
) : BackupSink {

    override val id: String get() = ID

    private val folder: String = config.path.trim().trimEnd('/')

    private fun pathOf(name: String): String = when {
        folder.isEmpty() -> name
        else -> "$folder/$name"
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            session { sftp, _ ->
                // Makes the folder when it is missing, a level at a time.
                if (folder.isNotEmpty() && sftp.stat(folder) == null) {
                    var at = if (folder.startsWith("/")) "" else "."
                    for (part in folder.split('/').filter { it.isNotEmpty() }) {
                        at = if (at == ".") part else "$at/$part"
                        if (sftp.stat(at) == null) sftp.mkdir(at)
                    }
                }
            }
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            session { sftp, call ->
                val part = pathOf(name + AutoBackupNaming.PART_SUFFIX)
                try {
                    sftp.put(part, bytes, call::sent)
                    sftp.renameReplacing(part, pathOf(name))
                } catch (failure: Throwable) {
                    runCatching { sftp.remove(part) }
                    throw failure
                }
            }
            SinkEntry(id = name, name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            session { sftp, _ ->
                sftp.list(folder.ifEmpty { "." })
                    .filter { !it.attrs.directory && AutoBackupNaming.isListed(it.name) }
                    .map { SinkEntry(id = it.name, name = it.name, sizeBytes = it.attrs.size, modifiedAtMs = it.attrs.mtimeMs) }
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            session { sftp, call -> sftp.get(pathOf(entry.name), call::received).inputStream() }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable<Unit> {
            session { sftp, _ ->
                try {
                    sftp.remove(pathOf(entry.name))
                } catch (missing: SftpStatusException) {
                    if (missing.code != SftpClient.FX_NO_SUCH_FILE) throw missing
                }
            }
        }
    }

    /**
     * Connects, checks the host key, signs in, opens the SFTP subsystem, runs
     * [block], and turns every failure into a [SinkError].
     */
    private fun <T> session(block: (SftpClient, com.wasimaster.wmkeyboard.core.netlog.NetCall) -> T): T {
        val call = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "SFTP",
            scheme = "sftp",
            host = config.host,
            port = config.port,
            route = folder.ifEmpty { null },
            background = BackupTraffic.unattended,
        )
        var session: Session? = null
        var channel: ChannelSubsystem? = null
        try {
            val jsch = JSch()
            val keys = TofuKeys(config.hostKey)
            jsch.hostKeyRepository = keys
            if (config.privateKey.isNotBlank()) {
                jsch.addIdentity(
                    "wmkeyboard-backup",
                    config.privateKey.trim().toByteArray(Charsets.UTF_8),
                    null,
                    config.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
                )
            }
            session = jsch.getSession(config.user, config.host.trim(), config.port).apply {
                for ((key, value) in SftpAlgorithms.config(config.legacyAlgorithms)) setConfig(key, value)
                // No stored key: take the first one, then hold the server to it.
                setConfig("StrictHostKeyChecking", if (config.hostKey.isEmpty()) "no" else "yes")
                if (config.password.isNotEmpty()) setPassword(config.password.toByteArray(Charsets.UTF_8))
                userInfo = Answers(config.password)
                timeout = READ_TIMEOUT_MS
                connect(CONNECT_TIMEOUT_MS)
            }
            if (config.hostKey.isEmpty()) {
                session.hostKey?.let { onHostKey("${it.type} ${it.key}") }
            }
            channel = (session.openChannel("subsystem") as ChannelSubsystem).apply { setSubsystem("sftp") }
            val sftp = SftpClient(channel.inputStream, channel.outputStream)
            channel.connect(CONNECT_TIMEOUT_MS)
            sftp.init()
            return block(sftp, call)
        } catch (failure: Throwable) {
            val error = errorOf(failure)
            BackupLog.w("sftp failed: ${failure.message.orEmpty()} => $error", failure)
            call.fail(failure)
            throw BackupSinkException(error, failure)
        } finally {
            runCatching { channel?.disconnect() }
            runCatching { session?.disconnect() }
            call.end()
        }
    }

    /**
     * The one host key this location trusts. JSch asks it about each key the
     * server shows; with nothing stored it says "not included" and, host key
     * checking being off for that first connection, JSch goes on and adds it.
     */
    internal class TofuKeys(stored: String) : HostKeyRepository {
        private val trusted: ByteArray? = stored.substringAfter(' ', "").takeIf { it.isNotEmpty() }
            ?.let { runCatching { JavaBase64Compat.decode(it) }.getOrNull() }

        override fun check(host: String?, key: ByteArray?): Int = when {
            trusted == null -> HostKeyRepository.NOT_INCLUDED
            key != null && trusted.contentEquals(key) -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit

        override fun remove(host: String?, type: String?) = Unit

        override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

        override fun getKnownHostsRepositoryID(): String = "wmkeyboard"

        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers a password prompt, including keyboard-interactive ones, and nothing else. */
    internal class Answers(private val password: String) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password.ifEmpty { null }
        override fun promptPassword(message: String?): Boolean = password.isNotEmpty()
        override fun promptPassphrase(message: String?): Boolean = false
        override fun promptYesNo(message: String?): Boolean = false
        override fun showMessage(message: String?) = Unit

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            if (password.isEmpty() || prompt == null) return null
            // A hidden prompt is the password; anything shown in the clear is a
            // question this app cannot answer, so the sign-in stops there.
            if (echo != null && echo.any { it }) return null
            return Array(prompt.size) { password }
        }
    }

    companion object {
        const val ID = "sftp"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /** What an SFTP failure means to the user. Public for the tests. */
        fun errorOf(failure: Throwable): SinkError = when (failure) {
            is BackupSinkException -> failure.reason
            is JSchChangedHostKeyException -> SinkError.UNSAFE
            is SftpStatusException -> when (failure.code) {
                SftpClient.FX_PERMISSION_DENIED -> SinkError.PERMISSION_LOST
                SftpClient.FX_NO_SUCH_FILE -> SinkError.TARGET_MISSING
                else -> SinkError.IO
            }
            is JSchException -> {
                val message = failure.message.orEmpty()
                when {
                    message.contains("Auth fail", ignoreCase = true) ||
                        message.contains("Auth cancel", ignoreCase = true) ||
                        message.contains("invalid privatekey", ignoreCase = true) -> SinkError.PERMISSION_LOST
                    message.contains("HostKey has been changed", ignoreCase = true) -> SinkError.UNSAFE
                    else -> SinkError.IO
                }
            }
            else -> SinkError.IO
        }

        /** Fingerprint of a stored `<type> <base64>` key, as `ssh-keygen -l` prints it. */
        fun fingerprint(hostKey: String): String? {
            val blob = hostKey.substringAfter(' ', "").takeIf { it.isNotEmpty() }
                ?.let { runCatching { JavaBase64Compat.decode(it) }.getOrNull() } ?: return null
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + JavaBase64Compat.encodeNoPad(digest)
        }
    }
}

/**
 * Base64 without `java.util.Base64`, which is API 26. Android's own
 * `android.util.Base64` is not on a plain JVM, where the tests run.
 */
internal object JavaBase64Compat {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }.trimEnd('=')
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "not base64" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun encode(b: ByteArray): String {
        val bare = encodeNoPad(b)
        return bare + "=".repeat((4 - bare.length % 4) % 4)
    }

    fun encodeNoPad(b: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < b.size) {
            val n = minOf(3, b.size - i)
            var chunk = 0
            for (k in 0 until 3) chunk = (chunk shl 8) or (if (k < n) b[i + k].toInt() and 0xFF else 0)
            for (k in 0..n) sb.append(ALPHABET[(chunk shr (18 - 6 * k)) and 0x3F])
            i += 3
        }
        return sb.toString()
    }
}
