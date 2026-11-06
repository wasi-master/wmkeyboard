package com.wasimaster.wmkeyboard.core.dictionaries

import java.io.File
import java.security.MessageDigest

/** Exact size and SHA-256 checks for a catalogued context model. */
object ContextModelIntegrity {

    private const val BUFFER = 64 * 1024

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** True only when `file` has the catalogued exact size and digest. */
    fun matches(file: File, entry: ContextModelEntry): Boolean {
        if (!file.isFile || file.length() != entry.bytes) return false
        return MessageDigest.isEqual(hex(entry.sha256), hex(sha256(file)))
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
