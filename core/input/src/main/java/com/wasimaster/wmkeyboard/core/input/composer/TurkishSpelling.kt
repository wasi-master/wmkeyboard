package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * The Hunspell dictionary that lets Turkish be checked by its rules rather than its word
 * list, since no list holds every suffix a Turkish word can take: hunspell-tr from Turkish
 * Data Depository (MPL-2.0), the [PACK_ID] pack, 14.5 MB, 38 MB unpacked. The library
 * reading it is the `spell` gate's. On a Mac it opened in 0.6 seconds and took 32 MB.
 */
object TurkishSpelling {

    const val PACK_ID = "hunspell_tr"

    private const val AFF = "tr_TR.aff"
    private const val DIC = "tr_TR.dic"
    private const val DIR = "unpacked"
    private const val DONE_MARKER = ".unpacked"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** The `.aff` and `.dic`, unpacked if they weren't yet, or null without the pack. Reads files: off the main thread. */
    fun files(filesDir: File): Pair<File, File>? {
        val pack = CjkDictStore.downloadedFileFor(filesDir, PACK_ID) ?: return null
        val dir = File(pack.parentFile, DIR)
        return runCatching {
            if (!File(dir, DONE_MARKER).isFile) unpack(pack, dir)
            File(dir, AFF) to File(dir, DIC)
        }.getOrNull()
    }

    @Throws(IOException::class)
    private fun unpack(pack: File, dir: File) {
        dir.deleteRecursively()
        dir.mkdirs()
        ZipInputStream(pack.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (name != AFF && name != DIC) continue
                File(dir, name).outputStream().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_ENTRY_BYTES) throw IOException("$name is too big")
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
        File(dir, DONE_MARKER).createNewFile()
    }
}
