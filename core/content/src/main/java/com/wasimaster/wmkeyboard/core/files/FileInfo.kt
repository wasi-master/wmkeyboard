package com.wasimaster.wmkeyboard.core.files

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The facts a Properties card works out rather than reads: what a folder
 * holds and how big it is, broken down by kind, and a file's SHA-256. Over
 * plain files and streams, so it runs on the JVM in tests; [DocFiles] walks a
 * document folder the same way.
 */
object FileInfo {

    private const val BUFFER = 64 * 1024

    /** What a walk has counted so far. */
    data class Tally(
        val bytes: Long = 0,
        val files: Int = 0,
        val folders: Int = 0,
        /** Bytes by kind, for the "what takes the space" bars. */
        val byKind: Map<FileKind, Long> = emptyMap(),
    ) {
        fun plus(kind: FileKind, size: Long): Tally = copy(
            bytes = bytes + size,
            files = files + 1,
            byKind = byKind + (kind to (byKind[kind] ?: 0L) + size),
        )
    }

    /**
     * Counts everything under [root], a folder at a time, links not followed.
     * [onProgress] hears the running total after each folder, so a card can
     * fill in while a big folder is still being walked.
     */
    suspend fun tally(root: File, mimeOf: (String) -> String? = { null }, onProgress: (Tally) -> Unit = {}): Tally {
        val context = currentCoroutineContext()
        var tally = Tally()
        val pending = ArrayDeque<File>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            context.ensureActive()
            val dir = pending.removeFirst()
            for (child in dir.listFiles().orEmpty()) {
                if (FileOps.isSymlink(child)) continue
                tally = if (child.isDirectory) {
                    pending.addLast(child)
                    tally.copy(folders = tally.folders + 1)
                } else {
                    tally.plus(FileKinds.of(child.name, mimeOf(child.name).orEmpty()), child.length())
                }
            }
            onProgress(tally)
        }
        return tally
    }

    /** The SHA-256 of what [input] holds, in lower-case hex. */
    suspend fun sha256(input: InputStream): String {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            context.ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
