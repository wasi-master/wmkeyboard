package com.wasimaster.wmkeyboard.core.settings.remote

import android.content.Context
import com.jcraft.jsch.ChannelSubsystem
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.wasimaster.wmkeyboard.core.files.RemoteEntry
import com.wasimaster.wmkeyboard.core.files.RemoteFs
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.sink.SftpSink
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpAlgorithms
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpClient
import com.wasimaster.wmkeyboard.core.settings.sink.sftp.SftpStatusException
import java.io.File
import java.io.OutputStream

/**
 * An SFTP server's files, from the backup location's host, user, password or
 * key, held to the host key the backup recorded (a changed key refuses, as
 * for the backup; the first one seen is recorded the same way).
 *
 * The top is where the login lands, the account's home. One SSH session
 * stays open between calls until [close]. Uploads open with `EXCL` and
 * renames use plain `RENAME`, both of which the server refuses over an
 * existing name, so nothing here can write over a file.
 */
class SftpFs(private val context: Context, private val location: BackupLocation) : RemoteFs {

    private val config = location.sftp
    private var session: Session? = null
    private var channel: ChannelSubsystem? = null
    private var client: SftpClient? = null
    private var home: String = ""

    private fun sftp(): SftpClient {
        client?.let { return it }
        val jsch = JSch()
        jsch.hostKeyRepository = SftpSink.TofuKeys(config.hostKey)
        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "wmkeyboard-files",
                config.privateKey.trim().toByteArray(Charsets.UTF_8),
                null,
                config.keyPassphrase.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
            )
        }
        val opened = jsch.getSession(config.user, config.host.trim(), config.port).apply {
            for ((key, value) in SftpAlgorithms.config(config.legacyAlgorithms)) setConfig(key, value)
            setConfig("StrictHostKeyChecking", if (config.hostKey.isEmpty()) "no" else "yes")
            if (config.password.isNotEmpty()) setPassword(config.password.toByteArray(Charsets.UTF_8))
            userInfo = SftpSink.Answers(config.password)
            timeout = READ_TIMEOUT_MS
            connect(CONNECT_TIMEOUT_MS)
        }
        session = opened
        if (config.hostKey.isEmpty()) {
            opened.hostKey?.let { AutoBackupRunner.storeHostKey(context, location.id, "${it.type} ${it.key}") }
        }
        val sub = (opened.openChannel("subsystem") as ChannelSubsystem).apply { setSubsystem("sftp") }
        channel = sub
        val made = SftpClient(sub.inputStream, sub.outputStream)
        sub.connect(CONNECT_TIMEOUT_MS)
        made.init()
        home = made.realpath(".").trimEnd('/')
        client = made
        return made
    }

    /** The server's path for [names] under the home folder. */
    private fun at(names: List<String>): String {
        names.forEach { require(it.isNotEmpty() && '/' !in it && it != "." && it != "..") { "bad name" } }
        sftp()
        return (listOf(home) + names).joinToString("/").ifEmpty { "/" }
    }

    override fun list(dir: List<String>): List<RemoteEntry> =
        sftp().list(at(dir)).map { RemoteEntry(it.name, it.attrs.directory, if (it.attrs.directory) -1 else it.attrs.size, it.attrs.mtimeMs) }

    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) return RemoteEntry("", true)
        val attrs = sftp().stat(at(path)) ?: return null
        return RemoteEntry(path.last(), attrs.directory, if (attrs.directory) -1 else attrs.size, attrs.mtimeMs)
    }

    override fun download(path: List<String>, to: OutputStream) {
        sftp().download(at(path), to)
    }

    override fun upload(dir: List<String>, name: String, from: File, onSent: (Long) -> Unit) {
        from.inputStream().buffered().use { sftp().upload(at(dir + name), it, onSent) }
    }

    override fun mkdir(dir: List<String>, name: String) {
        sftp().mkdir(at(dir + name))
    }

    override fun delete(path: List<String>, isDirectory: Boolean) {
        try {
            if (isDirectory) sftp().rmdir(at(path)) else sftp().remove(at(path))
        } catch (missing: SftpStatusException) {
            if (missing.code != SftpClient.FX_NO_SUCH_FILE) throw missing
        }
    }

    override fun rename(path: List<String>, newName: String) {
        sftp().renameStrict(at(path), at(path.dropLast(1) + newName))
    }

    override fun move(path: List<String>, toDir: List<String>): Boolean {
        sftp().renameStrict(at(path), at(toDir + path.last()))
        return true
    }

    override fun close() {
        runCatching { channel?.disconnect() }
        runCatching { session?.disconnect() }
        client = null
        channel = null
        session = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
