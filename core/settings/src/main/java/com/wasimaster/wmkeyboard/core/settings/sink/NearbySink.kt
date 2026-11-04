package com.wasimaster.wmkeyboard.core.settings.sink

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How sync files reach a nearby device: KDE Connect, which lives in the
 * keyboard and registers itself here when it starts. Null while it is not
 * running, and then a write stays in the mailbox until the device is in
 * reach and asks for it.
 */
object NearbySync {

    fun interface Transport {
        /** Sends [bytes] as [fileName] to [deviceId]. False when it is out of reach. */
        fun send(deviceId: String, fileName: String, bytes: ByteArray): Boolean
    }

    @Volatile
    var transport: Transport? = null

    /** The mailbox for [deviceId]: its files arrive here, ours are written here. */
    fun dir(context: Context, deviceId: String): File? {
        val safe = deviceId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safe.isEmpty()) return null
        return File(context.noBackupFilesDir, "nearby/$safe").apply { mkdirs() }
    }
}

/**
 * A location that is a link to one other copy of the keyboard rather than a
 * place: a Nearby device (a computer with WM Keyboard, paired over KDE
 * Connect), or this keyboard in another profile of the phone
 * ([ProfileLink]). The sink is a mailbox folder on this phone. The other
 * copy's sync file lands in it when it sends one; writing ours puts it there
 * and hands it to [send] to go across. The sync pass reads and writes it like
 * any folder, so nothing else about sync needs to know the other copy is not
 * a server.
 *
 * Only sync files go through it: a link is a sync location, never a backup
 * destination.
 */
class MailboxSink(
    private val dir: File,
    override val id: String,
    private val send: (name: String, bytes: ByteArray) -> Unit,
) : BackupSink {

    override suspend fun readiness(): Result<Unit> =
        if (dir.isDirectory || dir.mkdirs()) Result.success(Unit)
        else Result.failure(BackupSinkException(SinkError.TARGET_MISSING))

    override suspend fun write(name: String, mimeType: String, body: (OutputStream) -> Unit): Result<SinkEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming.isOurs(name)) {
                    throw BackupSinkException(SinkError.UNSAFE)
                }
                val bytes = ByteArrayOutputStream().also(body).toByteArray()
                val file = File(dir, name)
                // Whole or not at all: the link hands this file over as it
                // finds it, and half of one is no sync file.
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                if (!part.renameTo(file)) {
                    part.delete()
                    throw BackupSinkException(SinkError.IO)
                }
                // Out of reach is not a failure: the file waits here, and goes
                // when the other copy is back.
                send(name, bytes)
                entry(file)
            }.recoverCatching { throw if (it is BackupSinkException) it else BackupSinkException(SinkError.IO, it) }
        }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            dir.listFiles().orEmpty()
                .filter { it.isFile && com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming.isOurs(it.name) }
                .map(::entry)
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCatching { File(dir, entry.name).inputStream() as InputStream }
            .recoverCatching { throw BackupSinkException(SinkError.TARGET_MISSING, it) }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { File(dir, entry.name).delete(); Unit }
    }

    private fun entry(file: File) = SinkEntry(file.name, file.name, file.length(), file.lastModified())
}
