package com.wasimaster.wmkeyboard.core.prediction

import java.io.File
import java.io.InputStream

/**
 * User-supplied word lists, one folder per language.
 *
 * The bundled lists only cover English and Bengali; every other language
 * ships with no dictionary at all. Rather than wait for a curated list per
 * language, this lets anyone drop in their own — a Hunspell `.dic`, a
 * frequency list, or a plain column of words — and get completions and
 * autocorrect for a language the app knows nothing about.
 *
 * Lists live in `filesDir/dictionaries/<langId>/<name>.txt` (langId = the
 * [com.wasimaster.wmkeyboard.core.script.LanguageDef.id], e.g. "en", "bn",
 * "fr") and are additive by default: several lists may sit in one language,
 * and for English and Bengali they stack on top of the bundled list instead of
 * replacing it. A language can be switched the other way, to read these lists
 * and nothing else, by `SuggestionStripSettings.importedOnlyLangs` (issue
 * #28); the IME applies that when it assembles the word sources, so nothing
 * here changes.
 *
 * Format is [DictionaryLoader]'s: `word<space>frequency`, frequency
 * optional. `#` comments and junk lines are skipped, so most word lists
 * found in the wild import as-is.
 */
object CustomDictionaries {

    /** Refuse absurd files outright rather than spending a minute parsing one. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /**
     * Folder names written before languages were keyed by id: the old
     * `KeyboardLanguage.name`. [migrateLegacyFolders] renames them to their
     * langId once so imported lists survive the upgrade.
     */
    private val LEGACY_FOLDER_LANG = mapOf(
        "ENGLISH" to "en", "BANGLA" to "bn", "FRENCH" to "fr", "GERMAN" to "de", "SPANISH" to "es",
    )

    fun root(filesDir: File): File = File(filesDir, "dictionaries")

    fun languageDir(filesDir: File, langId: String): File =
        File(root(filesDir), langId)

    /**
     * What a switched-off list is called. Marked by renaming rather than by a
     * flag in DataStore: the state then travels with the file through backup,
     * restore and a manual copy, and [lists] keeps its one-line definition of
     * "the lists that count".
     */
    const val DISABLED_SUFFIX = ".off"

    /** Imported lists for one language that are switched on, oldest first. */
    fun lists(filesDir: File, langId: String): List<File> =
        languageDir(filesDir, langId)
            .listFiles { f -> f.isFile && f.extension == "txt" }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Every imported list for one language, switched on or not. */
    fun allLists(filesDir: File, langId: String): List<File> =
        languageDir(filesDir, langId)
            .listFiles { f ->
                f.isFile && (f.extension == "txt" || f.name.endsWith(".txt$DISABLED_SUFFIX"))
            }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Every language id with at least one imported list on disk, switched on or
     * not.
     *
     * The settings screen used to walk the *enabled* languages instead, so
     * turning a language off took its word lists out of the only screen that
     * manages them: the files stayed on disk and kept counting against storage,
     * with no way to see or delete them short of re-enabling the language.
     */
    fun languagesWithLists(filesDir: File): List<String> =
        root(filesDir).listFiles { f -> f.isDirectory }
            ?.filter { allLists(filesDir, it.name).isNotEmpty() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun isEnabled(file: File): Boolean = file.extension == "txt"

    /** The list's name without the disabled marker, for showing in settings. */
    fun displayName(file: File): String = file.name.removeSuffix(DISABLED_SUFFIX)

    /**
     * Switches a list on or off, returning its new file. Testing whether a bad
     * import is polluting suggestions used to cost a delete and a re-import.
     */
    fun setEnabled(file: File, enabled: Boolean): File {
        if (isEnabled(file) == enabled) return file
        val target = if (enabled) {
            File(file.parentFile, file.name.removeSuffix(DISABLED_SUFFIX))
        } else {
            File(file.parentFile, file.name + DISABLED_SUFFIX)
        }
        return if (file.renameTo(target)) target else file
    }

    /** Every entry across every list for one language, in file order. */
    fun entries(filesDir: File, langId: String): List<Pair<String, Int>> {
        val all = ArrayList<Pair<String, Int>>()
        for (file in lists(filesDir, langId)) {
            runCatching { file.inputStream().use { all += DictionaryLoader.loadEntries(it) } }
        }
        return all
    }

    fun trie(filesDir: File, langId: String): WordSource =
        PackedTrie.of(entries(filesDir, langId))

    /**
     * Copies [stream] in as a list named after [displayName], returning how
     * many words it parsed to. A file that yields nothing usable is deleted
     * again and reported as 0, so a wrong pick (a PDF, an image) fails
     * visibly instead of sitting in the list contributing nothing.
     */
    fun import(
        filesDir: File,
        langId: String,
        displayName: String,
        stream: InputStream,
    ): Int {
        val dir = languageDir(filesDir, langId).apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        target.outputStream().use { stream.copyTo(it) }
        val count = runCatching {
            target.inputStream().use { DictionaryLoader.loadEntries(it).size }
        }.getOrDefault(0)
        if (count == 0) target.delete()
        return count
    }

    fun remove(file: File): Boolean = file.delete()

    /**
     * Renames the pre-registry per-language folders (ENGLISH → en, BANGLA →
     * bn …) to their langId, so lists imported before the rewrite are not
     * orphaned. Idempotent: once renamed the old folders are gone and this is
     * a no-op. Merges into an existing langId folder without clobbering.
     */
    fun migrateLegacyFolders(filesDir: File) {
        val root = root(filesDir)
        if (!root.isDirectory) return
        for ((old, new) in LEGACY_FOLDER_LANG) {
            val oldDir = File(root, old)
            if (!oldDir.isDirectory) continue
            val newDir = File(root, new)
            if (newDir.exists()) {
                oldDir.listFiles()?.forEach { f ->
                    val dest = File(newDir, f.name)
                    if (!dest.exists()) f.renameTo(dest)
                }
                oldDir.delete()
            } else {
                oldDir.renameTo(newDir)
            }
        }
    }

    /**
     * Picked names arrive straight from the document provider, so strip
     * anything that could climb out of the language folder and settle
     * collisions with a numeric suffix.
     */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .take(48)
            .ifEmpty { "wordlist" }
        var candidate = File(dir, "$base.txt")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).txt")
            n++
        }
        return candidate
    }
}
