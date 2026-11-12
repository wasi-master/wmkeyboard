package com.wasimaster.wmkeyboard.core.settings.sync

import android.content.Context
import android.util.Base64
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardEntry
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardFile
import com.wasimaster.wmkeyboard.core.clipboard.board.BoardLocal
import com.wasimaster.wmkeyboard.core.clipboard.board.ClipBoard
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.sinkErrorOf
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a board file is called: `wmkeyboard-board_<installId>.wmboard.json`,
 * or `.wmboard.enc` under the backup passphrase. One per device in the
 * board's folder, overwritten in place. Its own prefix, so backup rotation,
 * restore and settings sync never take one for theirs.
 */
object ClipBoardNaming {

    const val PREFIX = "wmkeyboard-board_"
    const val EXTENSION = "wmboard.json"
    const val ENCRYPTED_EXTENSION = "wmboard.enc"

    private val OWNER = Regex("^wmkeyboard-board_([0-9a-f]{8})")

    /** Tolerates a provider's `" (1)"` before the last dot, as [SyncNaming] does. */
    private val ENDING = Regex("\\.wmboard( \\(\\d+\\))?\\.(json|enc)$")

    fun name(installId: String, encrypted: Boolean): String =
        "$PREFIX$installId.${if (encrypted) ENCRYPTED_EXTENSION else EXTENSION}"

    fun isOurs(displayName: String): Boolean = OWNER.containsMatchIn(displayName) && ENDING.containsMatchIn(displayName)

    fun installId(displayName: String): String? =
        if (isOurs(displayName)) OWNER.find(displayName)?.groupValues?.get(1) else null
}

/**
 * The shared clipboard board (#1201) over a backup place: a WebDAV folder the
 * user already set up under Backup & restore. Each device writes its own file
 * there and reads the others' ([ClipBoard] has the rules), so a clip sent
 * from one phone reaches another whenever that one next looks, however long
 * it was offline, and an entry changed on two devices ends up as the later
 * change on both.
 *
 * This device's copy lives in `files/clipboard/board.json`. Every change goes
 * there first, marked pending, and a pass writes it out: a change made with no
 * network waits in that file until a pass gets through. Under the backup
 * passphrase, when there is one, the way sync files are.
 */
object ClipBoardSync {

    private const val LOCAL_FILE = "clipboard/board.json"

    private val mutex = Mutex()

    /** The place the board is on, or null while it is off or that place is gone or not set up. */
    fun location(settings: KeyboardSettings): BackupLocation? {
        val id = settings.clipboard.boardLocation.takeIf { it.isNotEmpty() } ?: return null
        return settings.autoBackup.locations.firstOrNull {
            it.id == id && it.type == BackupDestination.WEBDAV && it.configured
        }
    }

    /** Names the folder a local copy belongs to, so a board moved elsewhere starts afresh. */
    private fun boardKey(location: BackupLocation): String =
        location.id + ":" + location.webDavUrl.trimEnd('/').hashCode().toUInt().toString(HEX)

    private fun file(context: Context) = File(context.filesDir, LOCAL_FILE)

    /** This device's copy of the board at [location]; empty for a board it has not seen. */
    suspend fun load(context: Context, location: BackupLocation): BoardLocal = withContext(Dispatchers.IO) {
        readLocal(context, location)
    }

    private fun readLocal(context: Context, location: BackupLocation): BoardLocal {
        val key = boardKey(location)
        val stored = ClipBoard.decodeLocal(runCatching { file(context).readText() }.getOrNull())
        return stored?.takeIf { it.board == key } ?: BoardLocal(board = key)
    }

    private fun writeLocal(context: Context, local: BoardLocal) {
        val target = file(context)
        runCatching {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(ClipBoard.encodeLocal(local))
            if (!temp.renameTo(target)) {
                target.writeText(ClipBoard.encodeLocal(local))
                temp.delete()
            }
        }.onFailure { BackupLog.w("board: local write failed", it) }
    }

    /**
     * Applies [mutate] to this device's copy and marks it pending; the next
     * pass writes it out. Never touches the network, so it works offline.
     */
    suspend fun change(
        context: Context,
        location: BackupLocation,
        mutate: (List<BoardEntry>) -> List<BoardEntry>,
    ): BoardLocal = mutex.withLock {
        withContext(Dispatchers.IO) {
            val local = readLocal(context, location)
            val next = local.copy(entries = mutate(local.entries), pending = true)
            writeLocal(context, next)
            next
        }
    }

    /**
     * One pass: every other device's newest file read, merged with this
     * device's copy, and this device's file written when it says something
     * the folder's copy does not. A failure leaves the copy as it was, still
     * pending, with the reason, for the panel to show and the next pass to retry.
     */
    suspend fun sync(
        context: Context,
        location: BackupLocation,
        auto: AutoBackupSettings,
        nowMs: Long = System.currentTimeMillis(),
    ): BoardLocal = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val local = readLocal(app, location)
            // The stores behind the lock are not there yet; nothing to merge into.
            if (!DirectBoot.isUserUnlocked(app)) return@withContext local
            val sink = AutoBackupRunner.sinkFor(app, location)
            val next = if (sink == null) {
                local.copy(error = SinkError.NOT_CONFIGURED.name)
            } else {
                runCancellable { pass(app, sink, local, auto, nowMs) }
                    .getOrElse { failure ->
                        val reason = sinkErrorOf(failure).name
                        BackupLog.w("board: pass failed: $reason", failure)
                        local.copy(error = reason)
                    }
            }
            writeLocal(app, next)
            next
        }
    }

    private suspend fun pass(
        context: Context,
        sink: BackupSink,
        local: BoardLocal,
        auto: AutoBackupSettings,
        nowMs: Long,
    ): BoardLocal {
        val me = BackupInstall.id(context)
        val passphrase = auto.passphrase.takeIf { auto.encrypt && it.isNotEmpty() && auto.kdfSalt.isNotEmpty() }
        sink.readiness().getOrThrow()
        val listed = sink.list().getOrThrow().filter { ClipBoardNaming.isOurs(it.name) }
        val ownName = ClipBoardNaming.name(me, encrypted = passphrase != null)
        val others = listed
            .filter { ClipBoardNaming.installId(it.name).let { id -> id != null && id != me } }
            .groupBy { ClipBoardNaming.installId(it.name) }
            .map { (_, files) -> files.maxBy { it.modifiedAtMs } }
        val remote = others.mapNotNull { read(sink, it, passphrase) }
        val ownEntry = listed.firstOrNull { it.name == ownName }
        val ownRemote = ownEntry?.let { read(sink, it, passphrase) }
        val merged = ClipBoard.prune(
            ClipBoard.merge(listOf(local.entries) + remote.map { it.entries } + listOfNotNull(ownRemote?.entries)),
            me,
            nowMs,
        )
        val stale = ownRemote == null || ownRemote.entries.toSet() != merged.toSet()
        if (local.pending || stale) {
            val file = BoardFile(installId = me, device = BackupInstall.deviceLabel(context), writtenAtMs = nowMs, entries = merged)
            write(sink, ownName, file, passphrase, auto).getOrThrow()
            // A file of this device's under another name (the passphrase came
            // or went, or a provider renamed a clash) is an older copy.
            sink.list().getOrNull().orEmpty()
                .filter { ClipBoardNaming.installId(it.name) == me && it.name != ownName }
                .forEach { sink.delete(it) }
        }
        BackupLog.d("board: devices=${remote.size} entries=${merged.size} wrote=${local.pending || stale}")
        return local.copy(entries = merged, pending = false, syncedAtMs = nowMs, error = "")
    }

    /** Another device's board file, or null when it will not open (another passphrase, or not one). */
    private suspend fun read(sink: BackupSink, entry: SinkEntry, passphrase: String?): BoardFile? {
        val bytes = sink.read(entry).getOrNull()?.use { it.readBytes() } ?: return null
        val head = bytes.copyOf(BackupCrypto.MAGIC_SIZE.coerceAtMost(bytes.size))
        val text = if (BackupCrypto.looksEncrypted(head)) {
            val key = passphrase ?: return null
            BackupCrypto.decrypting(ByteArrayInputStream(bytes), key.toCharArray())?.use { it.readBytes() }
                ?.decodeToString() ?: return null
        } else {
            bytes.decodeToString()
        }
        return ClipBoard.decode(text)
    }

    private suspend fun write(
        sink: BackupSink,
        name: String,
        file: BoardFile,
        passphrase: String?,
        auto: AutoBackupSettings,
    ): Result<SinkEntry> {
        val plain = ClipBoard.encode(file).encodeToByteArray()
        val bytes = if (passphrase == null) {
            plain
        } else {
            ByteArrayOutputStream().also { buffer ->
                BackupCrypto.encrypting(
                    out = buffer,
                    passphrase = passphrase.toCharArray(),
                    salt = Base64.decode(auto.kdfSalt, Base64.NO_WRAP),
                ).use { it.write(plain) }
            }.toByteArray()
        }
        val mime = if (passphrase == null) ConfigBackup.MIME_TYPE else ConfigBackup.ENCRYPTED_MIME_TYPE
        return sink.write(name, mime) { out -> out.write(bytes) }
    }

    private const val HEX = 16
}
