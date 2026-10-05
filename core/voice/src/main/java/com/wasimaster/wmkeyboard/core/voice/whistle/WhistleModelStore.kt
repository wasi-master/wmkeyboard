package com.wasimaster.wmkeyboard.core.voice.whistle

import java.io.File
import java.security.MessageDigest

/** App-private storage for the single Cactus Whistle model. */
object WhistleModelStore {
    const val MODEL_FILE_NAME = "whistle.cact"
    const val MODEL_BYTES = 16_919_407L
    const val MODEL_SHA256 = "b6e02f048568ac5d01a2042556c658061e699acbc0aa2a1439f52f3d461dffeb"

    fun rootDir(filesDir: File): File = File(filesDir, "whistle")
    fun modelFile(filesDir: File): File = File(rootDir(filesDir), MODEL_FILE_NAME)
    fun partialFile(filesDir: File): File = File(rootDir(filesDir), "$MODEL_FILE_NAME.part")

    fun isDownloaded(filesDir: File): Boolean = modelFile(filesDir).let {
        it.isFile && it.length() == MODEL_BYTES
    }

    fun bytesOnDisk(filesDir: File): Long =
        listOf(modelFile(filesDir), partialFile(filesDir)).sumOf { if (it.isFile) it.length() else 0L }

    fun hasValidChecksum(file: File): Boolean {
        if (!file.isFile || file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == MODEL_SHA256
    }

    fun delete(filesDir: File) {
        rootDir(filesDir).deleteRecursively()
    }

    /** Replaces the active model atomically; an interrupted copy never looks installed. */
    fun install(filesDir: File, source: File): Boolean {
        if (!hasValidChecksum(source)) return false
        val target = modelFile(filesDir)
        target.parentFile?.mkdirs()
        val part = partialFile(filesDir)
        return try {
            source.inputStream().use { input -> part.outputStream().use { output -> input.copyTo(output) } }
            if (target.exists() && !target.delete()) return false
            part.renameTo(target)
        } finally {
            part.delete()
        }
    }
}
