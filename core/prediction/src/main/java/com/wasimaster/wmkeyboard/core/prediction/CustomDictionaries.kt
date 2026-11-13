package com.wasimaster.wmkeyboard.core.prediction

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * User-supplied word lists, one folder per language.
 *
 * The bundled lists only cover English and Bengali; every other language
 * ships with no dictionary at all. Rather than wait for a curated list per
 * language, this lets anyone drop in their own — a Hunspell `.aff` and
 * `.dic`, a frequency list, or a plain column of words — and get completions and
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
 *
 * An AOSP dictionary brings more than words, and each extra part sits beside
 * its list under the same name: `<name>.wmng` holds its word pairs as an
 * n-gram pack, `<name>.shortcuts` its shortcuts. Both are read only while the
 * list is switched on and go when it is deleted, so the list stays the one
 * thing a user manages. Which parts are written is the user's choice at
 * import ([inspect], then [write]).
 *
 * A Hunspell dictionary also keeps its own two files beside its list,
 * `<name>.aff` and `<name>.dic`, as they were picked: the list is what the
 * tries read, and the pair is what the Hunspell library checks words against
 * when it is present ([hunspellFiles], [HunspellChecker]). They follow the
 * list the same way.
 */
object CustomDictionaries {

    /** Refuse absurd files outright rather than spending a minute parsing one. */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** A HeliBoard backup is a few megabytes; this is for a zip bomb, not for it. */
    private const val MAX_ZIP_BYTES = 128L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 4096
    private const val COPY_BUFFER = 64 * 1024
    private const val TEXT_SNIFF_BYTES = 4096
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val LINE_BREAKERS = Regex("[\\t\\r\\n]")

    private const val PAIRS_SUFFIX = ".wmng"
    private const val SHORTCUTS_SUFFIX = ".shortcuts"
    private const val AFF_SUFFIX = ".aff"
    private const val DIC_SUFFIX = ".dic"
    private const val HEADER_SUFFIX = ".header"
    private const val BODY_SUFFIX = ".body"

    /** Bumped when what [trie] compiles from a list changes, so old compiled copies are rebuilt. */
    private const val PACKED_VERSION = "packed-1"
    private const val INITIAL_WORDS = 1024

    /** What a list holds in place of words when only its pairs or shortcuts were imported. */
    private const val NO_WORDS_LINE = "# Only word pairs or shortcuts were imported with this list.\n"

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
        for (file in lists(filesDir, langId)) all += wordsOf(file)
        // A Romanian list found in the wild spells its comma-below letters
        // with a cedilla as often as the downloadable ones did, and the
        // spelling a list carries is the one autocorrect commits
        // ([RomanianSpelling]). Read here rather than rewritten at import, so
        // a list already on disk reads right without being imported again,
        // and the file the user manages stays the file they chose.
        if (!RomanianSpelling.appliesTo(langId)) return all
        return all.map { (word, frequency) -> RomanianSpelling.canonical(word) to frequency }
    }

    /**
     * The words of one list, switched on or not; none for a file that is not
     * text.
     *
     * The one reader of a list on disk, for the tries and for the count the
     * settings screen shows. [DictionaryLoader] takes any line as a word, so a
     * binary put through it does not fail: a compiled dictionary comes back as
     * a hundred thousand words nobody can type, which then complete, correct
     * and vote (#288). [inspect] has always refused such a file at the door;
     * this is the same test for the files that got in before there was one
     * ([repairUnreadImports]).
     */
    fun wordsOf(list: File): List<Pair<String, Int>> {
        val head = head(list) ?: return emptyList()
        if (!readsAsList(head)) return emptyList()
        return runCatching { list.inputStream().use { DictionaryLoader.loadEntries(it) } }
            .getOrDefault(emptyList())
    }

    /**
     * One language's switched-on lists as a single word source.
     *
     * Compiled once into a `.wmdict` and memory-mapped after that, the way a
     * downloaded list is, so a keyboard start costs one `mmap` and no heap.
     * It used to read every list into pairs and pack them at each start: a
     * list of some 800,000 words was then a hundred megabytes of short-lived
     * objects every time the keyboard came up, and on a 256 MB heap that ran
     * out of memory at every start (#693).
     *
     * The compiled copy is rebuilt when the lists change, which is when any
     * list's name, size or modification time does. It lives outside [root],
     * so backups, the lists screen and the import never see it.
     */
    fun trie(filesDir: File, langId: String): WordSource {
        val lists = lists(filesDir, langId)
        val packed = packedFile(filesDir, langId)
        val signatureFile = signatureFile(filesDir, langId)
        if (lists.isEmpty()) {
            dropPacked(filesDir, langId)
            return PackedTrie.EMPTY
        }
        val signature = signatureOf(lists)
        if (runCatching { signatureFile.readText() }.getOrNull() == signature) {
            MappedTrie.open(packed)?.let { return it }
        }
        val trie = build(lists, langId)
        // A cache that cannot be written leaves the built trie to serve this
        // start; the next one tries again.
        runCatching {
            packed.parentFile?.mkdirs()
            signatureFile.delete()
            // Renamed into place, never written over: the keyboard may still
            // have the old file mapped, and a mapped file cut short under its
            // reader faults. Named per call, so two builds never share a part.
            val part = File(packed.parentFile, "${packed.name}.${System.nanoTime()}.part")
            try {
                part.outputStream().buffered().use { PackedTrieCodec.write(trie, it) }
                if (!part.renameTo(packed)) return@runCatching
            } finally {
                part.delete()
            }
            signatureFile.writeText(signature)
        }
        // The mapped copy, when there is one, so the arrays just built can go.
        return MappedTrie.open(packed)?.takeIf { signatureFile.isFile } ?: trie
    }

    /**
     * Forgets [langId]'s compiled lists, for an edit that might leave a list
     * the same size within the same second as before it.
     */
    fun dropPacked(filesDir: File, langId: String) {
        signatureFile(filesDir, langId).delete()
        packedFile(filesDir, langId).delete()
    }

    private fun packedDir(filesDir: File): File = File(filesDir, "dictionary_cache")

    private fun packedFile(filesDir: File, langId: String): File = File(packedDir(filesDir), "$langId.wmdict")

    private fun signatureFile(filesDir: File, langId: String): File = File(packedDir(filesDir), "$langId.sig")

    private fun signatureOf(lists: List<File>): String =
        lists.joinToString("\n", prefix = "$PACKED_VERSION\n") { "${it.name}\t${it.length()}\t${it.lastModified()}" }

    /**
     * [lists] packed straight from their lines into two parallel arrays,
     * which is what [PackedTrie.of] takes for big lists. No pair per word.
     */
    private fun build(lists: List<File>, langId: String): PackedTrie {
        val respell = RomanianSpelling.appliesTo(langId)
        val words = WordArrays()
        for (list in lists) {
            val head = head(list) ?: continue
            if (!readsAsList(head)) continue
            try {
                list.inputStream().use { input ->
                    DictionaryLoader.forEachEntry(input) { word, frequency ->
                        words.add(if (respell) RomanianSpelling.canonical(word) else word, frequency)
                    }
                }
            } catch (_: java.io.IOException) {
                // What was read of it stays. An out-of-memory error is not
                // caught here: the keyboard skips the language over it.
            }
        }
        return PackedTrie.of(words.words, words.frequencies, words.count)
    }

    /** Growable `word`, `frequency` arrays for [build]. */
    private class WordArrays {
        var words = arrayOfNulls<String>(INITIAL_WORDS)
        var frequencies = IntArray(INITIAL_WORDS)
        var count = 0

        fun add(word: String, frequency: Int) {
            if (count == words.size) {
                val size = words.size * 2
                words = words.copyOf(size)
                frequencies = frequencies.copyOf(size)
            }
            words[count] = word
            frequencies[count] = frequency
            count++
        }
    }

    /**
     * Imports everything in [stream] as a list named after [displayName], and
     * returns how many entries landed: words, word pairs and shortcuts
     * together. The route for callers with no one to ask (an addon install); the
     * settings screen asks first, through [inspect] and [write].
     *
     * A file that yields nothing usable writes nothing and reports 0, so a
     * wrong pick (a PDF, an image) fails visibly instead of sitting in the list
     * contributing nothing.
     */
    fun import(
        filesDir: File,
        langId: String,
        displayName: String,
        stream: InputStream,
    ): Int {
        val bytes = readCapped(stream) ?: return 0
        val found = inspect(listOf(ImportFile(displayName, bytes))) as? Inspection.Found ?: return 0
        return found.dictionaries.sumOf { write(filesDir, langId, it, ImportParts.ALL).total }
    }

    // ---- importing, in two steps ----

    /** One file the user picked, read into memory. */
    class ImportFile(val name: String, val bytes: ByteArray)

    /**
     * One dictionary found in the picked files, before anything is written.
     * [words] are on this app's scale. A plain word list keeps its own bytes,
     * so its comments and spacing survive the import, and a Hunspell
     * dictionary keeps its two files in [hunspell].
     */
    class ImportCandidate(
        val name: String,
        val words: List<Pair<String, Int>>,
        val ngrams: List<AospDictionary.Ngram>,
        val shortcuts: List<AospDictionary.Shortcut>,
        val locale: String?,
        val description: String?,
        internal val verbatim: ByteArray? = null,
        internal val hunspell: HunspellSource? = null,
    ) {
        /** Whether there is anything to ask about beyond the words. */
        val hasExtras: Boolean get() = ngrams.isNotEmpty() || shortcuts.isNotEmpty()
    }

    /** A Hunspell dictionary's own files, written beside its list. */
    class HunspellSource(val aff: ByteArray, val dic: ByteArray)

    /** A list's Hunspell pair on disk. */
    data class HunspellFiles(val aff: File, val dic: File)

    /** Which parts of a candidate to write. */
    data class ImportParts(val words: Boolean, val pairs: Boolean, val shortcuts: Boolean) {
        companion object {
            val ALL = ImportParts(words = true, pairs = true, shortcuts = true)
        }
    }

    /** What [write] put on disk. */
    data class Written(val words: Int, val pairs: Int, val shortcuts: Int) {
        val total: Int get() = words + pairs + shortcuts
    }

    /** Why [inspect] found nothing to import. */
    sealed interface Refusal {
        /** Nothing in the files reads as a word list or a dictionary. */
        data object NothingReadable : Refusal

        /** A file is larger than [MAX_BYTES]. */
        data object TooLarge : Refusal

        /** A version 4 `.header` came without the `.body` that holds its words. */
        data object MissingBody : Refusal

        /** A version 4 `.body` came without its `.header`. */
        data object MissingHeader : Refusal

        /** A dictionary in a revision the reader does not know. */
        data class UnsupportedVersion(val version: Int) : Refusal

        /** A Hunspell `.dic` came without the `.aff` whose rules spell its words. */
        data object MissingAff : Refusal

        /** A Hunspell `.aff` came without its `.dic`. */
        data object MissingDic : Refusal
    }

    sealed interface Inspection {
        data class Found(val dictionaries: List<ImportCandidate>) : Inspection
        data class Refused(val reason: Refusal) : Inspection
    }

    /**
     * Reads [files] and says what they hold, writing nothing.
     *
     * What counts as a file:
     *
     * - a word list, in this app's format or AOSP's `.combined`;
     * - a compiled `.dict` (version 2);
     * - a version 4 dictionary, which is two files: its `.header` and its
     *   `.body`, picked together;
     * - a Hunspell dictionary, which is two files too: its `.aff` and its
     *   `.dic`, unfolded into every word they spell ([Hunspell]);
     * - a `.zip` holding any number of `.dict` files, version 4 pairs and
     *   Hunspell pairs, which is what a HeliBoard backup, a LibreOffice `.oxt`
     *   and a Firefox `.xpi` dictionary are. Only dictionaries are taken out of a zip:
     *   the other text files in a backup (layouts, blacklists) would read as
     *   word lists, and are not taken.
     */
    fun inspect(files: List<ImportFile>): Inspection {
        val picked = ArrayList<ImportFile>()
        val unzipped = ArrayList<ImportFile>()
        for (file in files) {
            if (file.bytes.size > MAX_BYTES) return Inspection.Refused(Refusal.TooLarge)
            if (isZip(file.bytes)) {
                unzipped.addAll(unzip(file.bytes) ?: return Inspection.Refused(Refusal.TooLarge))
            } else {
                picked.add(file)
            }
        }
        val items = picked + unzipped
        val bodies = items.filter { it.name.endsWith(BODY_SUFFIX) }
            .associateBy { it.name.removeSuffix(BODY_SUFFIX) }
        val usedBodies = HashSet<String>()
        val affs = items.filter { Hunspell.isAff(it.name) }
        val affsByStem = affs.associateBy { Hunspell.stem(it.name) }
        val usedAffs = HashSet<ImportFile>()
        val candidates = ArrayList<ImportCandidate>()
        var refusal: Refusal? = null
        for (item in items) {
            if (item.name.endsWith(BODY_SUFFIX) || Hunspell.isAff(item.name)) continue
            // A Sogou cell file, read as the Chinese word list it holds.
            if (SogouScel.looksLikeScel(item.bytes)) {
                scelCandidate(item)?.let(candidates::add)
                continue
            }
            // A Clink language pack's lexicon, read as the word list it holds.
            if (ClinkLexicon.looksLikeClex(item.bytes)) {
                clexCandidate(item)?.let(candidates::add)
                continue
            }
            if (Hunspell.looksLikeDic(item.name, item.bytes)) {
                // Paired by name, or, when only one of each was picked, with
                // each other: a download often names the two differently.
                val aff = affsByStem[Hunspell.stem(item.name)] ?: affs.singleOrNull()
                if (aff == null) {
                    refusal = refusal ?: Refusal.MissingAff
                    continue
                }
                usedAffs.add(aff)
                hunspellCandidate(item, aff)?.let(candidates::add)
                continue
            }
            if (!AospDictionary.looksLikeDictionary(item.bytes)) {
                // Inside a zip only a combined list is a dictionary; a picked
                // file may be any word list.
                val candidate = if (item in picked) textCandidate(item) else combinedCandidate(item)
                candidate?.let(candidates::add)
                continue
            }
            when (val result = AospDictionary.read(item.bytes)) {
                is AospDictionary.Result.Contents -> candidates.add(candidate(item.name, result))
                is AospDictionary.Result.HeaderOnly -> {
                    val stem = item.name.removeSuffix(HEADER_SUFFIX)
                    val body = bodies[stem]
                    if (body == null) {
                        refusal = refusal ?: Refusal.MissingBody
                        continue
                    }
                    usedBodies.add(stem)
                    when (val v4 = AospDictionaryV4.read(item.bytes, body.bytes)) {
                        is AospDictionary.Result.Contents -> candidates.add(candidate(stem, v4))
                        is AospDictionary.Result.Unsupported -> refusal = Refusal.UnsupportedVersion(v4.version)
                        else -> Unit
                    }
                }
                is AospDictionary.Result.Unsupported -> refusal = Refusal.UnsupportedVersion(result.version)
                AospDictionary.Result.NotADictionary -> Unit
            }
        }
        if (refusal == null && bodies.keys.any { it !in usedBodies }) refusal = Refusal.MissingHeader
        if (refusal == null && affs.any { it !in usedAffs }) refusal = Refusal.MissingDic
        val found = candidates.filter { it.words.isNotEmpty() || it.hasExtras }
        return if (found.isNotEmpty()) {
            Inspection.Found(found)
        } else {
            Inspection.Refused(refusal ?: Refusal.NothingReadable)
        }
    }

    private fun candidate(name: String, contents: AospDictionary.Result.Contents) = ImportCandidate(
        name = name,
        words = contents.words,
        ngrams = contents.ngrams,
        shortcuts = contents.shortcuts,
        locale = contents.attributes["locale"]?.takeIf { it.isNotBlank() },
        description = contents.attributes["description"]?.takeIf { it.isNotBlank() },
    )

    private fun hunspellCandidate(dic: ImportFile, aff: ImportFile): ImportCandidate? {
        val words = runCatching { Hunspell.expand(aff.bytes, dic.bytes) }.getOrDefault(emptyList())
        if (words.isEmpty()) return null
        val name = dic.name.substringAfterLast('/')
        return ImportCandidate(
            name = name,
            words = words,
            ngrams = emptyList(),
            shortcuts = emptyList(),
            locale = Hunspell.language(aff.bytes) ?: name.substringBeforeLast('.'),
            description = null,
            hunspell = HunspellSource(aff.bytes, dic.bytes),
        )
    }

    private fun combinedCandidate(item: ImportFile): ImportCandidate? {
        val first = firstLine(item.bytes) ?: return null
        if (!AospCombined.looksLikeCombined(first)) return null
        val result = AospCombined.read(item.bytes.inputStream())
        return (result as? AospDictionary.Result.Contents)?.let { candidate(item.name, it) }
    }

    /** A `.scel` file's words, as a Chinese list whose lines carry their pinyin. */
    private fun scelCandidate(item: ImportFile): ImportCandidate? {
        val entries = SogouScel.read(item.bytes)
        if (entries.isEmpty()) return null
        val text = SogouScel.toText(item.bytes)
        val name = item.name.substringBeforeLast('.') + ".txt"
        return ImportCandidate(
            name, entries.map { it.word to it.count }, emptyList(), emptyList(), "zh", null,
            verbatim = text.toByteArray(),
        )
    }

    private fun clexCandidate(item: ImportFile): ImportCandidate? {
        val words = ClinkLexicon.read(item.bytes)
        if (words.isEmpty()) return null
        val name = item.name.substringBeforeLast('.') + ".txt"
        return ImportCandidate(
            name, words, emptyList(), emptyList(), null, null,
            verbatim = ClinkLexicon.toText(item.bytes).toByteArray(),
        )
    }

    private fun textCandidate(item: ImportFile): ImportCandidate? {
        combinedCandidate(item)?.let { return it }
        // UTF-16 (Microsoft IME's export) and Shift-JIS (ATOK) as UTF-8, the
        // way every list is stored and read back; see [ImportText].
        val bytes = ImportText.toUtf8(item.bytes)
        if (!looksLikeText(bytes)) return null
        val words = runCatching { DictionaryLoader.loadEntries(bytes.inputStream()) }.getOrDefault(emptyList())
        if (words.isEmpty()) return null
        if (isSwiftKeyVocabulary(item.name)) {
            return ImportCandidate(
                "$SWIFTKEY_WORDS.txt", words, emptyList(), emptyList(), null, SWIFTKEY_WORDS, verbatim = bytes,
            )
        }
        return ImportCandidate(item.name, words, emptyList(), emptyList(), null, null, verbatim = bytes)
    }

    /**
     * Whether [name] is the file SwiftKey keeps the words it learned in, on the
     * OneDrive its sync uses. A list named for what it holds is one a person can
     * find again among their dictionaries, and "vocabulary" says nothing.
     */
    fun isSwiftKeyVocabulary(name: String): Boolean = SWIFTKEY_VOCABULARY.matches(name)

    private val SWIFTKEY_VOCABULARY = Regex("""vocabulary( ?\(\d+\))?\.txt""", RegexOption.IGNORE_CASE)
    private const val SWIFTKEY_WORDS = "SwiftKey learned words"

    /**
     * Writes the [parts] of [candidate] as one list in [langId], and says what
     * landed. Nothing is left behind when nothing was chosen, or when nothing
     * of what was chosen exists.
     *
     * The list's text file is written even when its words were not chosen, as
     * a single comment line: it is what the settings screen lists, switches
     * and deletes, and the pairs and shortcuts beside it follow it.
     */
    fun write(filesDir: File, langId: String, candidate: ImportCandidate, parts: ImportParts): Written {
        val dir = languageDir(filesDir, langId).apply { mkdirs() }
        return writeParts(uniqueFile(dir, candidate.name), candidate, parts)
    }

    /** [write], to a list file already chosen: a new one, or one being read again in place. */
    private fun writeParts(target: File, candidate: ImportCandidate, parts: ImportParts): Written {
        val words = if (parts.words) candidate.words.size else 0
        val pairs = if (parts.pairs) writePairs(pairsFile(target), candidate.ngrams) else 0
        val shortcuts = if (parts.shortcuts) writeShortcuts(shortcutsFile(target), candidate.shortcuts) else 0
        val wrote = words + pairs + shortcuts > 0 && writeAtomically(target) { out ->
            val verbatim = candidate.verbatim
            when {
                words == 0 -> out.write(NO_WORDS_LINE.toByteArray())
                verbatim != null -> out.write(verbatim)
                else -> {
                    val writer = out.bufferedWriter()
                    for ((word, frequency) in candidate.words) {
                        writer.write(word)
                        writer.write(" ")
                        writer.write(frequency.toString())
                        writer.newLine()
                    }
                    writer.flush()
                }
            }
        }
        // The rules the words were unfolded from, for the Hunspell library.
        // Only with the words: a list imported for its pairs alone has
        // nothing for Hunspell to stand behind.
        val hunspell = candidate.hunspell
        val keptRules = wrote && words > 0 && hunspell != null &&
            writeAtomically(affFile(target)) { it.write(hunspell.aff) } &&
            writeAtomically(dicFile(target)) { it.write(hunspell.dic) }
        if (!keptRules) {
            affFile(target).delete()
            dicFile(target).delete()
        }
        if (!wrote) {
            pairsFile(target).delete()
            shortcutsFile(target).delete()
            return Written(0, 0, 0)
        }
        return Written(words, pairs, shortcuts)
    }

    /**
     * The pairs and triples as a `.wmng` pack, keyed the way every pack is
     * ([WordKey]). A pair listed twice keeps its stronger count.
     */
    private fun writePairs(file: File, ngrams: List<AospDictionary.Ngram>): Int {
        if (ngrams.isEmpty()) return 0
        val bigrams = HashMap<Pair<String, String>, Int>()
        val trigrams = HashMap<Triple<String, String, String>, Int>()
        for (ngram in ngrams) {
            val word = WordKey.of(ngram.word)
            val context = ngram.context.map(WordKey::of)
            if (word.isEmpty() || context.any { it.isEmpty() }) continue
            when (context.size) {
                1 -> bigrams.merge(context[0] to word, ngram.count, ::maxOf)
                2 -> trigrams.merge(Triple(context[0], context[1], word), ngram.count, ::maxOf)
                else -> Unit
            }
        }
        if (bigrams.isEmpty() && trigrams.isEmpty()) return 0
        val builder = NgramPackBuilder()
        for ((key, count) in bigrams) builder.addBigram(key.first, key.second, count)
        for ((key, count) in trigrams) builder.addTrigram(key.first, key.second, key.third, count)
        val data = runCatching { builder.build() }.getOrNull() ?: return 0
        val wrote = writeAtomically(file) { NgramPackCodec.write(data, it) }
        return if (wrote) bigrams.size + trigrams.size else 0
    }

    /**
     * One `trigger<TAB>expansion` line each. A trigger listed more than once
     * keeps the one the source keyboard applied on its own, then the first.
     */
    private fun writeShortcuts(file: File, shortcuts: List<AospDictionary.Shortcut>): Int {
        val chosen = LinkedHashMap<String, AospDictionary.Shortcut>()
        for (shortcut in shortcuts) {
            val trigger = clean(shortcut.trigger)
            val expansion = clean(shortcut.expansion)
            if (trigger.isEmpty() || expansion.isEmpty()) continue
            val key = trigger.lowercase()
            val held = chosen[key]
            if (held == null || (shortcut.whitelist && !held.whitelist)) {
                chosen[key] = AospDictionary.Shortcut(trigger, expansion, shortcut.whitelist)
            }
        }
        if (chosen.isEmpty()) return 0
        val wrote = writeAtomically(file) { out ->
            val writer = out.bufferedWriter()
            for (shortcut in chosen.values) {
                writer.write(shortcut.trigger)
                writer.write("\t")
                writer.write(shortcut.expansion)
                writer.newLine()
            }
            writer.flush()
        }
        return if (wrote) chosen.size else 0
    }

    /** A tab or a line break inside either half would break the line format. */
    private fun clean(text: String): String = text.replace(LINE_BREAKERS, " ").trim()

    /** Written beside the target and renamed over it, so a crash leaves nothing half written. */
    private fun writeAtomically(target: File, body: (OutputStream) -> Unit): Boolean {
        val temp = File(target.parentFile, target.name + ".tmp")
        val ok = runCatching { temp.outputStream().use(body) }.isSuccess
        if (ok && temp.renameTo(target)) return true
        temp.delete()
        return false
    }

    // ---- what sits beside a list ----

    /**
     * The file holding a list's word pairs: `<name>.wmng` beside `<name>.txt`.
     * Read only while the list is switched on, and deleted with it.
     */
    fun pairsFile(list: File): File = File(list.parentFile, baseName(list) + PAIRS_SUFFIX)

    /** The file holding a list's shortcuts, one `trigger<TAB>expansion` a line. */
    fun shortcutsFile(list: File): File = File(list.parentFile, baseName(list) + SHORTCUTS_SUFFIX)

    /** The `.aff` an imported Hunspell dictionary kept beside [list]. */
    fun affFile(list: File): File = File(list.parentFile, baseName(list) + AFF_SUFFIX)

    /** The `.dic` an imported Hunspell dictionary kept beside [list]. */
    fun dicFile(list: File): File = File(list.parentFile, baseName(list) + DIC_SUFFIX)

    /** [list]'s Hunspell pair, when its import kept one. */
    fun hunspellFilesOf(list: File): HunspellFiles? {
        val aff = affFile(list)
        val dic = dicFile(list)
        return if (aff.isFile && dic.isFile) HunspellFiles(aff, dic) else null
    }

    /**
     * The Hunspell pairs of the switched-on lists for [langId], oldest first:
     * what the Hunspell library checks that language's words against. Empty
     * for every list imported before the pairs were kept, which has to be
     * imported again to gain one.
     */
    fun hunspellFiles(filesDir: File, langId: String): List<HunspellFiles> =
        lists(filesDir, langId).mapNotNull(::hunspellFilesOf)

    /** Whether any list for [langId], switched on or off, kept a Hunspell pair. */
    fun hasHunspell(filesDir: File, langId: String): Boolean =
        allLists(filesDir, langId).any { hunspellFilesOf(it) != null }

    /** The list and whatever sits beside it, for the storage screen. */
    fun filesOf(list: File): List<File> =
        listOf(list, pairsFile(list), shortcutsFile(list), affFile(list), dicFile(list)).filter { it.exists() }

    private fun baseName(list: File): String = displayName(list).removeSuffix(".txt")

    /** The word-pair packs of the switched-on lists for [langId]. */
    fun pairPacks(filesDir: File, langId: String): List<File> =
        lists(filesDir, langId).map(::pairsFile).filter { it.isFile }

    /**
     * Shortcuts from the switched-on lists for [langId], keyed by the
     * lowercased trigger. An older list wins a trigger two lists share, the
     * same order [lists] reads them in.
     */
    fun shortcuts(filesDir: File, langId: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (list in lists(filesDir, langId)) {
            val file = shortcutsFile(list)
            if (!file.isFile) continue
            runCatching {
                file.forEachLine { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEachLine
                    val trigger = line.substring(0, tab).trim().lowercase()
                    val expansion = line.substring(tab + 1).trim()
                    if (trigger.isNotEmpty() && expansion.isNotEmpty()) out.putIfAbsent(trigger, expansion)
                }
            }
        }
        return out
    }

    /** How many pairs and triples [list] carries, read off its pack's header. */
    fun pairCount(list: File): Int = NgramPackCodec.entryCount(pairsFile(list))

    /** How many shortcuts [list] carries. */
    fun shortcutCount(list: File): Int {
        val file = shortcutsFile(list)
        if (!file.isFile) return 0
        return runCatching { file.useLines { lines -> lines.count { '\t' in it } } }.getOrDefault(0)
    }

    // ---- reading what was picked ----

    /** [stream] whole, or null past [MAX_BYTES]. */
    fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

    /**
     * The dictionary-shaped entries of a zip, named by their full path so a
     * `.header` still finds the `.body` beside it. Null when the zip unpacks
     * past [MAX_ZIP_BYTES] in total, which is the only defence a phone has
     * against a zip bomb.
     */
    private fun unzip(bytes: ByteArray): List<ImportFile>? {
        val out = ArrayList<ImportFile>()
        var total = 0L
        val complete = runCatching {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entries = 0
                while (entries++ < MAX_ZIP_ENTRIES) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !looksLikeDictionaryEntry(entry.name)) continue
                    val data = readCapped(zip) ?: return@runCatching false
                    total += data.size
                    if (total > MAX_ZIP_BYTES) return@runCatching false
                    out.add(ImportFile(entry.name, data))
                }
            }
            true
        }.getOrDefault(true)
        return if (complete) out else null
    }

    /** Names worth unpacking: a backup also holds images, fonts and settings. */
    private fun looksLikeDictionaryEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".dict") || lower.endsWith(HEADER_SUFFIX) || lower.endsWith(BODY_SUFFIX) ||
            lower.endsWith(".combined") || lower.endsWith(".aff") || lower.endsWith(".dic")
    }

    private fun firstLine(bytes: ByteArray): String? = runCatching {
        bytes.inputStream().bufferedReader().useLines { lines ->
            lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        }
    }.getOrNull()

    /**
     * A plain list is text. The loader takes any line as a word, so without
     * this a picked image would import as a list of binary junk.
     */
    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.size.coerceAtMost(TEXT_SNIFF_BYTES)
        for (i in 0 until sample) if (bytes[i] == 0.toByte()) return false
        return true
    }

    /** The same question of a list already on disk, asked of its [head]. */
    private fun readsAsList(head: ByteArray): Boolean =
        looksLikeText(head) && !AospDictionary.looksLikeDictionary(head)

    /** As much of [file] as the sniffs look at, or null when it cannot be read. */
    private fun head(file: File): ByteArray? = runCatching {
        file.inputStream().use { stream ->
            val buffer = ByteArray(TEXT_SNIFF_BYTES)
            var filled = 0
            while (filled < buffer.size) {
                val n = stream.read(buffer, filled, buffer.size - filled)
                if (n < 0) break
                filled += n
            }
            buffer.copyOf(filled)
        }
    }.getOrNull()

    /** Deletes a list and the pairs, shortcuts and Hunspell files beside it. */
    fun remove(file: File): Boolean {
        pairsFile(file).delete()
        shortcutsFile(file).delete()
        affFile(file).delete()
        dicFile(file).delete()
        return file.delete()
    }

    /**
     * Takes [word] out of every imported list for [langId], switched on or
     * off, and says whether any file changed. The strip's delete action is
     * the caller (#190): the lists are the user's own text files, so unlike a
     * downloaded `.wmdict` a word can be unlisted from them for good rather
     * than blacklisted around.
     *
     * Matches the way [DictionaryLoader] reads a line — case-folded and
     * composition-folded through [WordKey], so "Boston", "boston" and a
     * decomposed spelling all go. Comments, blank lines and every other entry
     * are copied through untouched; the file is rewritten beside itself and
     * swapped in, so a crash mid-way leaves the old list whole. A list the
     * word is not in is not rewritten at all.
     */
    fun removeWord(filesDir: File, langId: String, word: String): Boolean {
        val key = WordKey.of(word.trim())
        if (key.isEmpty()) return false
        var changed = false
        for (file in allLists(filesDir, langId)) {
            if (removeWordFrom(file, key)) changed = true
        }
        if (changed) dropPacked(filesDir, langId)
        return changed
    }

    private fun removeWordFrom(file: File, key: String): Boolean {
        val temp = File(file.parentFile, file.name + ".tmp")
        var dropped = 0
        val ok = runCatching {
            file.bufferedReader().use { reader ->
                temp.bufferedWriter().use { writer ->
                    reader.forEachLine { line ->
                        if (entryOf(line) == key) {
                            dropped++
                        } else {
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
            }
        }.isSuccess
        if (!ok || dropped == 0) {
            temp.delete()
            return false
        }
        if (temp.renameTo(file)) return true
        temp.delete()
        return false
    }

    /**
     * The word a line contributes, keyed as the tries key it, or null for a
     * comment or a blank. Mirrors [DictionaryLoader.loadEntries] exactly: the
     * text before the last space, or the whole line when there is none.
     */
    private fun entryOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val separator = trimmed.lastIndexOf(' ')
        val word = if (separator <= 0) trimmed else trimmed.substring(0, separator).trim()
        return WordKey.of(word)
    }

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

    /** What [repairUnreadImports] did: lists read again as text, and lists switched off. */
    data class Repaired(val unpacked: Int, val switchedOff: Int)

    /**
     * Reads the lists an older version copied in without reading them.
     *
     * Up to 0.5.9 an import was a byte-for-byte copy of whatever was picked,
     * named `<name>.txt`. A compiled `.dict` from HeliBoard or FUTO therefore
     * sits in the language folder as the binary it is, and updating does not
     * change that: only a new import is unpacked. Such a file is unpacked here
     * the way [import] would have, in place, so it keeps its name and whether
     * it was switched on, and gains the pairs and shortcuts beside it.
     *
     * A file that is not text and cannot be unpacked, one half of a version 4
     * dictionary or a revision the reader does not know, is switched off.
     * [wordsOf] already reads nothing out of it; off is what says so on the
     * settings screen, where the list can be deleted and imported again.
     *
     * Runs on every load rather than once behind a flag: a backup made on the
     * old version restores the old files. A folder of text lists costs one
     * short read a file, and nothing is written.
     */
    @Synchronized
    fun repairUnreadImports(filesDir: File): Repaired {
        var unpacked = 0
        var switchedOff = 0
        for (langId in languagesWithLists(filesDir)) {
            for (list in allLists(filesDir, langId)) {
                val head = head(list) ?: continue
                if (readsAsList(head)) continue
                if (unpackInPlace(list, head)) {
                    unpacked++
                } else if (isEnabled(list) && setEnabled(list, false) != list) {
                    switchedOff++
                }
            }
        }
        return Repaired(unpacked, switchedOff)
    }

    private fun unpackInPlace(list: File, head: ByteArray): Boolean {
        if (!AospDictionary.looksLikeDictionary(head) || list.length() > MAX_BYTES) return false
        val bytes = runCatching { list.readBytes() }.getOrNull() ?: return false
        val contents = AospDictionary.read(bytes) as? AospDictionary.Result.Contents ?: return false
        return writeParts(list, candidate(baseName(list), contents), ImportParts.ALL).total > 0
    }

    /**
     * Picked names arrive straight from the document provider, so strip
     * anything that could climb out of the language folder and settle
     * collisions with a numeric suffix.
     */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName
            .substringAfterLast('/')
            .removeSuffix(HEADER_SUFFIX)
            .removeSuffix(BODY_SUFFIX)
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .take(48)
            .ifEmpty { "wordlist" }
        // A name is taken while anything of a list by that name is there: a
        // switched-off list, or the pairs of one, would otherwise be adopted.
        fun taken(name: String) =
            listOf(".txt", ".txt$DISABLED_SUFFIX", PAIRS_SUFFIX, SHORTCUTS_SUFFIX, AFF_SUFFIX, DIC_SUFFIX)
                .any { File(dir, name + it).exists() }
        var name = base
        var n = 2
        while (taken(name)) {
            name = "$base ($n)"
            n++
        }
        return File(dir, "$name.txt")
    }
}
