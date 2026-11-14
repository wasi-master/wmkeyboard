package com.wasimaster.wmkeyboard.core.input.composer

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * A Hunspell `.aff` and `.dic` the keyboard downloads for a language as one zip from the
 * catalog, so the language is checked by its rules rather than by a word list. The library
 * reading the pair is the `spell` gate's, and it reads the pair as it is: the pair never
 * goes through import's unfolding, which stops at 600,000 forms.
 */
abstract class HunspellRulesPack(
    /** The [CjkDictCatalog] pack holding the zip. */
    val packId: String,
    /** The language whose words the pair checks. */
    val langId: String,
    private val aff: String,
    private val dic: String,
) {

    /** The `.aff` and `.dic`, unpacked if they weren't yet, or null without the pack. Reads files: off the main thread. */
    fun files(filesDir: File): Pair<File, File>? {
        val pack = CjkDictStore.downloadedFileFor(filesDir, packId) ?: return null
        val dir = File(pack.parentFile, DIR)
        return runCatching {
            if (!File(dir, DONE_MARKER).isFile) unpack(pack, dir)
            File(dir, aff) to File(dir, dic)
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
                if (name != aff && name != dic) continue
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

    companion object {
        private const val DIR = "unpacked"
        private const val DONE_MARKER = ".unpacked"
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

        /** Every language with a pair to download. A getter: the objects extend this class. */
        val all: List<HunspellRulesPack> get() = listOf(TurkishSpelling, ArabicSpelling)

        /** [langId]'s pair, or null for a language without one. */
        fun forLang(langId: String): HunspellRulesPack? = all.firstOrNull { it.langId == langId }
    }
}
