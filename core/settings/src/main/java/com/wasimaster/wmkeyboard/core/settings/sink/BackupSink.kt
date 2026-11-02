package com.wasimaster.wmkeyboard.core.settings.sink

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One stored backup, as the sink sees it.
 *
 * [id] is opaque and sink-private: for a folder on disk it is a document id,
 * for something else it might be an object key. Only the sink that produced it
 * may take it back.
 *
 * [name] is authoritative, and is not necessarily the name that was asked for
 * — see [BackupSink.write].
 */
data class SinkEntry(
    val id: String,
    val name: String,
    /** -1 when the sink does not report a size. */
    val sizeBytes: Long,
    /** 0 when the sink does not report a time. Rotation copes; see AutoBackupNaming. */
    val modifiedAtMs: Long,
)

/**
 * Why a sink operation could not happen.
 *
 * The point of the enum is that each case is a different sentence to the user,
 * and two of them are not worth retrying. A destination that has lost its
 * permission will not regain it on a timer; the user has to go and pick the
 * folder again.
 */
enum class SinkError {

    /** No destination chosen yet. Not a failure the user needs told about. */
    NOT_CONFIGURED,

    /** The grant is gone: revoked, cleared with the provider's data, or evicted. */
    PERMISSION_LOST,

    /** The grant is live but the folder was deleted, or its volume is not mounted. */
    TARGET_MISSING,

    /** Out of space, either the device's or the provider's own quota. */
    OUT_OF_SPACE,

    /** Everything else. Assumed transient, so the next run tries again. */
    IO,

    /**
     * This process ran out of heap building or checking the file. Nothing is
     * wrong with the destination, and the older backups were not touched.
     */
    OUT_OF_MEMORY,

    /**
     * The destination answered, and what it showed is not safe to write to: an
     * SFTP server whose host key changed, a public Git repository, an SMB
     * server that cannot encrypt when encryption is required. Not transient,
     * and not a credential problem either.
     */
    UNSAFE,
}

/** The failure a [BackupSink] reports, carrying the [reason] the UI reads. Open for a sink's own detail. */
open class BackupSinkException(
    val reason: SinkError,
    cause: Throwable? = null,
) : IOException(reason.name, cause)

/** The reason a failed run reports for [failure], whichever part of the run threw it. */
fun sinkErrorOf(failure: Throwable): SinkError = when {
    failure is BackupSinkException -> failure.reason
    generateSequence(failure) { it.cause }.take(4).any { it is OutOfMemoryError } -> SinkError.OUT_OF_MEMORY
    else -> SinkError.IO
}

/**
 * Somewhere a backup file can be put, and taken back out again.
 *
 * Five verbs and no more: [write] and [read] are the backup itself, [list] and
 * [delete] are generation rotation, and [readiness] is the question the
 * scheduled job asks before it spends anything building a bundle.
 *
 * Every method returns a [Result] rather than throwing, because every caller is
 * a background job that has to record a failure and carry on rather than
 * unwind. The failure inside is a [BackupSinkException] whenever the sink knows
 * what went wrong.
 */
interface BackupSink {

    /** Stable id, stored with the run record so a changed destination shows up. */
    val id: String

    /**
     * Whether two files can share a name here, so writing a name again adds a
     * file rather than replacing one. Only Google Drive does this. Sync
     * rewrites one file per device and has to clear the older copies itself
     * where this is true.
     */
    val allowsDuplicateNames: Boolean get() = false

    /**
     * Whether a [write] could succeed right now.
     *
     * Cheap by contract: a permission check and at most one metadata query, no
     * transfer. It runs before every backup, and the whole point is to fail
     * before a multi-megabyte bundle has been built.
     */
    suspend fun readiness(): Result<Unit>

    /**
     * Writes one backup and returns the entry it became.
     *
     * [body] is called exactly once with a stream the sink owns and closes. It
     * must write the whole payload or throw; a throw is the sink's signal to
     * delete the partial file and fail. Taking a lambda rather than a
     * [ByteArray] is what keeps a large bundle from being copied twice — the
     * caller can stream its encode straight through, and an encrypting caller
     * can wrap the stream instead of holding a second full-size array.
     *
     * [name] is a *request*. Providers sanitise names, append an extension
     * derived from the MIME type, and rename a collision to `foo (1).json`.
     * Read [SinkEntry.name] for what actually landed.
     */
    suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry>

    /**
     * Every backup this sink holds, unordered, and nothing else that happens to
     * be alongside them. The destination may well be the user's Downloads
     * folder, and [delete] is called against what this returns.
     */
    suspend fun list(): Result<List<SinkEntry>>

    /** Opens [entry] for reading. The caller closes the stream. */
    suspend fun read(entry: SinkEntry): Result<InputStream>

    /** Removes [entry]. Already absent counts as success: rotation must be idempotent. */
    suspend fun delete(entry: SinkEntry): Result<Unit>
}
