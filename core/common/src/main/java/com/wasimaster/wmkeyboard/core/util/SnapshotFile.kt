package com.wasimaster.wmkeyboard.core.util

import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * The file behind one of the keyboard's learning stores, written whole, so
 * that the expensive half of a save — encoding and writing — can run on any
 * thread.
 *
 * A store's save used to be one synchronized block on the main thread: copy
 * the maps, encode them to JSON, truncate the file and write it. The keyboard
 * saves a dozen stores every time it hides, and the biggest of them, the
 * learned-words lexicon, runs to megabytes. Split in two, the store takes its
 * snapshot under its own lock and draws a [ticket] with it; the encoding and
 * the write happen here, outside that lock, and the service runs them off the
 * main thread.
 *
 * Two things that were free when every save ran on one thread have to be kept
 * by hand:
 *
 * - **Order.** Two saves of one store on two threads can finish the wrong way
 *   round, and the older snapshot would land last. Tickets are drawn under the
 *   store's lock, so their order is the order of the snapshots, and [write]
 *   drops any ticket older than the last one written.
 * - **Deletes.** A store that is cleared deletes its file. A write drawn
 *   before the clear must not bring the old data back afterwards, so [delete]
 *   and [supersede] retire every ticket drawn so far.
 *
 * Writes are atomic: the text goes to a sibling file first and is renamed over
 * the real one. The old save truncated the file before writing it, so a
 * process killed mid-write — an IME is killed often — left a half-written
 * lexicon, which fails to parse and loads as empty.
 */
class SnapshotFile(val file: File) {

    private val issued = AtomicLong()

    /** The newest ticket written or retired. Guarded by `this`. */
    private var landed = 0L

    /**
     * A place in the write order for a snapshot. Draw it under the same lock
     * the snapshot was taken under.
     */
    fun ticket(): Long = issued.incrementAndGet()

    /**
     * Writes [text] for [ticket], unless a newer snapshot already landed.
     * Returns false only when the write failed, so the store can stay dirty
     * and try again at its next save; a skipped write counts as done.
     */
    @Synchronized
    fun write(ticket: Long, text: () -> String): Boolean {
        if (ticket <= landed) return true
        val written = runCatching { writeAtomically(file, text()) }.isSuccess
        if (written) landed = ticket
        return written
    }

    /**
     * Writes [bytes] for [ticket] the way [write] writes text, synced to disk
     * before the rename, and with the file it replaces kept as [backupOf] for
     * a reader whose live copy turns out to be damaged (issue #1130).
     */
    @Synchronized
    fun writeBytes(ticket: Long, bytes: () -> ByteArray): Boolean {
        if (ticket <= landed) return true
        val written = runCatching { writeAtomically(file, bytes(), keepBackup = true) }.isSuccess
        if (written) landed = ticket
        return written
    }

    /**
     * Deletes the file and its sidecars ([backupOf], [corruptOf], the `.part`
     * a killed write leaves) and retires every ticket drawn so far. Waits for
     * a write already under way, so that write cannot land after the delete.
     * Returns what [File.delete] did for the file itself.
     */
    @Synchronized
    fun delete(): Boolean {
        landed = maxOf(landed, issued.get())
        for (sidecar in sidecarsOf(file)) sidecar.delete()
        return file.delete()
    }

    /**
     * Retires every ticket drawn so far, for a store that has just re-read its
     * file: those snapshots describe memory it has thrown away.
     */
    @Synchronized
    fun supersede() {
        landed = maxOf(landed, issued.get())
    }

    companion object {
        /** The previous generation of [file], kept by [writeBytes]. */
        fun backupOf(file: File): File = File(file.parentFile, file.name + ".bak")

        /** Where a reader moves a copy of [file] it could not trust, for a bug report rather than for reading. */
        fun corruptOf(file: File): File = File(file.parentFile, file.name + ".corrupt")

        /** Every file besides [file] itself that its writes and readers leave. */
        fun sidecarsOf(file: File): List<File> =
            listOf(backupOf(file), corruptOf(file), File(file.parentFile, file.name + ".part"))

        /**
         * Replaces [file] with [bytes] in one step, synced before the rename.
         * With [keepBackup], the file being replaced becomes [backupOf] rather
         * than being dropped; a missing file leaves the backup as it was.
         */
        fun writeAtomically(file: File, bytes: ByteArray, keepBackup: Boolean = false) {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            java.io.FileOutputStream(part).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (keepBackup && file.exists()) {
                val backup = backupOf(file)
                backup.delete()
                file.renameTo(backup)
            }
            if (!part.renameTo(file)) {
                part.delete()
                error("could not replace ${file.name}")
            }
        }

        /**
         * Replaces [file] with [text] in one step: written to a sibling, then
         * renamed over it, which on Android's file systems is atomic.
         */
        fun writeAtomically(file: File, text: String) {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(text)
            if (!part.renameTo(file)) {
                part.delete()
                error("could not replace ${file.name}")
            }
        }
    }
}
