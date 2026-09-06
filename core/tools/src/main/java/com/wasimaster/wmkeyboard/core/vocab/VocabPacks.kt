package com.wasimaster.wmkeyboard.core.vocab

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.json.Json

/**
 * Where vocabulary packs live on disk: `filesDir/vocab/<langId>/`, one
 * `<packId>.wmvocab.json` per pack, whether downloaded from the catalogue,
 * installed from an addon repository, imported from a file, or made in the
 * app (`user_<millis>`). Beside a pack sit its translation sidecars,
 * `<packId>.tr.<code>.json`, one per language downloaded.
 *
 * Same shape as `EmojiKeywordPacks` and `CustomDictionaries`: plain files,
 * a `.off` suffix to disable without deleting, and an import that enforces
 * its size cap while copying rather than trusting a size the caller was
 * told. The learning progress is not here — [VocabProgress] keeps it in one
 * file above the language folders, keyed by word, so it survives a pack
 * being deleted and downloaded again.
 */
object VocabPacks {

    const val DIR_NAME = "vocab"
    const val DISABLED_SUFFIX = ".off"

    /** The list "Add to my list" fills; created the first time it is needed. */
    const val MY_WORDS_ID = "user_mywords"

    private const val PART_SUFFIX = ".part"
    private const val USER_PREFIX = "user_"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun root(filesDir: File): File = File(filesDir, DIR_NAME)

    fun languageDir(filesDir: File, langId: String): File = File(root(filesDir), langId)

    fun packFile(filesDir: File, langId: String, packId: String): File =
        File(languageDir(filesDir, langId), "$packId.${VocabPackFile.FILE_EXTENSION}")

    fun partFile(filesDir: File, langId: String, packId: String): File =
        File(languageDir(filesDir, langId), "$packId.${VocabPackFile.FILE_EXTENSION}$PART_SUFFIX")

    fun translationFile(filesDir: File, langId: String, packId: String, code: String): File =
        File(languageDir(filesDir, langId), "$packId.tr.$code.json")

    fun translationPartFile(filesDir: File, langId: String, packId: String, code: String): File =
        File(languageDir(filesDir, langId), "$packId.tr.$code.json$PART_SUFFIX")

    /** Whether [file] is a pack (enabled or not), by name. */
    fun isPackFile(file: File): Boolean =
        file.isFile && (
            file.name.endsWith(".${VocabPackFile.FILE_EXTENSION}") ||
                file.name.endsWith(".${VocabPackFile.FILE_EXTENSION}$DISABLED_SUFFIX")
            )

    /** Every pack file for one language, by name; disabled ones included unless [enabledOnly]. */
    fun files(filesDir: File, langId: String, enabledOnly: Boolean = false): List<File> =
        languageDir(filesDir, langId)
            .listFiles { f -> isPackFile(f) && (!enabledOnly || isEnabled(f)) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Every language with at least one pack on disk. */
    fun languages(filesDir: File): List<String> =
        root(filesDir)
            .listFiles { f -> f.isDirectory }
            ?.filter { files(filesDir, it.name).isNotEmpty() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun isEnabled(file: File): Boolean = !file.name.endsWith(DISABLED_SUFFIX)

    /** Renames the pack on or off; returns the file it now lives at. */
    fun setEnabled(file: File, enabled: Boolean): File {
        if (isEnabled(file) == enabled) return file
        val target = if (enabled) {
            File(file.parentFile, file.name.removeSuffix(DISABLED_SUFFIX))
        } else {
            File(file.parentFile, file.name + DISABLED_SUFFIX)
        }
        return if (file.renameTo(target)) target else file
    }

    /** The pack id a file name carries, which is also the catalogue id for a download. */
    fun packIdOf(file: File): String =
        file.name
            .removeSuffix(DISABLED_SUFFIX)
            .removeSuffix(".${VocabPackFile.FILE_EXTENSION}")

    fun isUserPack(file: File): Boolean = packIdOf(file).startsWith(USER_PREFIX)

    /** Whether the pack at [file] is one the catalogue can download again. */
    fun isCatalogPack(file: File): Boolean = VocabCatalog.byId(packIdOf(file)) != null

    /** Language codes with a translation sidecar on disk for [packId]. */
    fun translationCodes(filesDir: File, langId: String, packId: String): List<String> {
        val prefix = "$packId.tr."
        return languageDir(filesDir, langId)
            .listFiles { f -> f.isFile && f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.map { it.name.removePrefix(prefix).removeSuffix(".json") }
            ?.sorted()
            .orEmpty()
    }

    /** The packs of one language, sidecars folded in, in file order. */
    fun load(filesDir: File, langId: String, enabledOnly: Boolean = true): List<VocabPack> =
        files(filesDir, langId, enabledOnly).mapNotNull { loadFile(it) }

    /** One pack with its translation sidecars folded in, or null when unreadable. */
    fun loadFile(file: File): VocabPack? {
        val pack = runCatching { file.inputStream().use { VocabPackFile.decode(it) } }.getOrNull()
            ?: return null
        val enabled = isEnabled(file)
        val packId = packIdOf(file)
        val meta = if (pack.meta.id.isEmpty()) pack.meta.copy(id = packId) else pack.meta
        val dir = file.parentFile ?: return pack.copy(meta = meta, file = file, enabled = enabled)
        val sidecars = dir.listFiles { f -> f.isFile && f.name.startsWith("$packId.tr.") && f.name.endsWith(".json") }
            .orEmpty()
        if (sidecars.isEmpty()) return pack.copy(meta = meta, file = file, enabled = enabled)
        val overlays = HashMap<String, MutableMap<String, VocabTranslation>>()
        for (sidecar in sidecars.sortedBy { it.name }) {
            val code = sidecar.name.removePrefix("$packId.tr.").removeSuffix(".json")
            val table = runCatching {
                json.decodeFromString<Map<String, VocabTranslation>>(sidecar.readText())
            }.getOrNull() ?: continue
            for ((word, translation) in table) {
                overlays.getOrPut(word) { HashMap() }[code] = translation
            }
        }
        if (overlays.isEmpty()) return pack.copy(meta = meta, file = file, enabled = enabled)
        val words = pack.words.map { word ->
            val extra = overlays[word.word] ?: return@map word
            word.copy(translations = word.translations + extra)
        }
        return pack.copy(meta = meta, words = words, file = file, enabled = enabled)
    }

    sealed interface ImportResult {
        data class Imported(val file: File, val wordCount: Int) : ImportResult
        data object TooLarge : ImportResult
        data object NotAPack : ImportResult
        data object Empty : ImportResult
    }

    /**
     * Copies [stream] in as a pack named after [displayName].
     *
     * The size cap is enforced during the copy: a document provider may
     * report an unknown size and a URL may lie about one, but neither can
     * push more bytes through this. A file that parses to nothing is deleted
     * again, so a wrong pick fails visibly instead of sitting in the list.
     * The pack's own id is not trusted for the file name — two imports of
     * the same pack would otherwise overwrite each other.
     */
    fun import(filesDir: File, langId: String, displayName: String, stream: InputStream): ImportResult {
        val dir = languageDir(filesDir, langId).apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        val part = File(dir, target.name + PART_SUFFIX)
        var copied = 0L
        var overflowed = false
        part.outputStream().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                copied += read
                if (copied > VocabPackFile.MAX_BYTES) {
                    overflowed = true
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        if (overflowed) {
            part.delete()
            return ImportResult.TooLarge
        }
        val pack = runCatching { part.inputStream().use { VocabPackFile.decode(it) } }.getOrNull()
        if (pack == null) {
            part.delete()
            return ImportResult.NotAPack
        }
        if (pack.words.isEmpty()) {
            part.delete()
            return ImportResult.Empty
        }
        // Re-encode inflated and with the file's id set to its name, so every
        // later reader does one parse and the id on disk matches the id inside.
        val normalized = pack.copy(meta = pack.meta.copy(id = packIdOf(target)))
        part.writeText(VocabPackFile.encode(normalized, appVersion = 0, appVersionName = ""))
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return ImportResult.NotAPack
        }
        return ImportResult.Imported(target, pack.words.size)
    }

    /**
     * Writes [pack] under its own id, replacing what was there: the way a
     * user-made list is created and every later edit is saved. Goes through
     * a `.part` file and a rename so a crash mid-write leaves the old pack.
     */
    fun write(filesDir: File, pack: VocabPack, appVersion: Int, appVersionName: String): File {
        val dir = languageDir(filesDir, pack.langId).apply { mkdirs() }
        val existing = pack.file?.takeIf { it.parentFile == dir }
        val enabled = existing?.let { isEnabled(it) } ?: pack.enabled
        val base = packFile(filesDir, pack.langId, pack.id)
        val target = if (enabled) base else File(dir, base.name + DISABLED_SUFFIX)
        val part = File(dir, base.name + PART_SUFFIX)
        part.writeText(VocabPackFile.encode(pack, appVersion, appVersionName))
        if (existing != null && existing != target) existing.delete()
        target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            return existing ?: target
        }
        return target
    }

    /** Deletes the pack and its sidecars; the progress for its words stays. */
    fun remove(file: File): Boolean {
        val packId = packIdOf(file)
        file.parentFile
            ?.listFiles { f -> f.isFile && f.name.startsWith("$packId.tr.") }
            ?.forEach { it.delete() }
        return file.delete()
    }

    /** Copies the pack's bytes out, for a share or a CreateDocument export. */
    fun export(file: File, out: OutputStream) {
        file.inputStream().use { it.copyTo(out) }
    }

    /**
     * A number that changes whenever any pack or sidecar file does — the
     * keyboard compares it to decide whether to rebuild [VocabIndex] after
     * the settings app downloaded, imported, edited or removed something.
     */
    fun stateToken(filesDir: File): Int {
        var hash = 0
        val languages = root(filesDir).listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()
        for (dir in languages) {
            val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(PART_SUFFIX) }?.sortedBy { it.name }.orEmpty()
            for (file in files) {
                hash = hash * 31 + file.name.hashCode()
                hash = hash * 31 + file.length().toInt()
                hash = hash * 31 + file.lastModified().toInt()
            }
        }
        return hash
    }

    fun newUserPack(name: String, langId: String, nowMillis: Long = System.currentTimeMillis()): VocabPackMeta =
        VocabPackMeta(
            id = "$USER_PREFIX$nowMillis",
            name = name.trim().take(MAX_NAME_LENGTH).ifEmpty { "Vocabulary" },
            langId = langId,
            userCreated = true,
        )

    /** The "My words" list, made on first use. */
    fun myWords(filesDir: File, langId: String, name: String): VocabPack {
        val enabledFile = packFile(filesDir, langId, MY_WORDS_ID)
        val disabledFile = File(enabledFile.parentFile, enabledFile.name + DISABLED_SUFFIX)
        val file = listOf(enabledFile, disabledFile).firstOrNull { it.isFile }
        return file?.let { loadFile(it) }
            ?: VocabPack(
                meta = VocabPackMeta(id = MY_WORDS_ID, name = name, langId = langId, userCreated = true),
                words = emptyList(),
            )
    }

    /**
     * Adds [word] to the "My words" list, creating the list if needed.
     * Returns false when the word was already there.
     */
    fun addToMyWords(
        filesDir: File,
        langId: String,
        word: VocabWord,
        listName: String,
        appVersion: Int,
        appVersionName: String,
    ): Boolean {
        val pack = myWords(filesDir, langId, listName)
        if (pack.words.any { it.word == word.word }) return false
        write(filesDir, pack.copy(words = pack.words + word.copy(triggers = word.triggers)), appVersion, appVersionName)
        return true
    }

    /** Whether "My words" already holds [lemma]. */
    fun isInMyWords(filesDir: File, langId: String, lemma: String): Boolean {
        val file = listOf(
            packFile(filesDir, langId, MY_WORDS_ID),
            File(languageDir(filesDir, langId), "$MY_WORDS_ID.${VocabPackFile.FILE_EXTENSION}$DISABLED_SUFFIX"),
        ).firstOrNull { it.isFile } ?: return false
        return loadFile(file)?.words?.any { it.word == lemma } == true
    }

    private const val MAX_NAME_LENGTH = 60

    /**
     * Picked names arrive straight from the document provider or a URL's last
     * path segment, so strip anything that could climb out of the language
     * folder and settle collisions with a numeric suffix.
     */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName
            .substringAfterLast('/')
            .removeSuffix(".gz")
            .removeSuffix(".json")
            .removeSuffix(".wmvocab")
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .take(48)
            .ifEmpty { "vocabulary" }
        val extension = ".${VocabPackFile.FILE_EXTENSION}"
        var candidate = File(dir, "$base$extension")
        var n = 2
        while (candidate.exists() || File(dir, candidate.name + DISABLED_SUFFIX).exists()) {
            candidate = File(dir, "$base ($n)$extension")
            n++
        }
        return candidate
    }
}
