package com.wasimaster.wmkeyboard.core.emoji

import java.io.File
import java.io.InputStream

/**
 * One imported emoji keyword pack: the emoji this language names, and what it
 * calls them.
 *
 * The bundled catalog carries English and Bengali keywords only, so emoji
 * search in every other language falls back to typing English. A pack fills
 * that gap for whichever language the user has one for — CLDR annotations
 * exported to TSV, a community list, or something hand-written.
 *
 * Format, one row per emoji, tab-separated:
 * ```
 * 🎂	cake,birthday,gâteau	gâteau d'anniversaire
 * ```
 *  - column 1: the emoji itself;
 *  - column 2: comma-separated keywords (lowercased on load);
 *  - column 3 (optional): the display name for the long-press description,
 *    shown while this pack's language is the one being typed.
 *
 * Blank lines and `# ` comments are skipped. Rows naming an emoji the catalog
 * does not carry are kept rather than dropped: the pack is matched against the
 * catalog at merge time, and being strict here would make a pack generated
 * against a newer Unicode version fail rows it shouldn't.
 *
 * [load] also accepts the data repo's JSON dictionaries (see [EmojiDictCodec])
 * so that a file pulled straight out of wmkeyboard-data imports without being
 * converted first — the download path writes TSV, but a user pasting the URL
 * of the `.json` behind it should not meet an error for their trouble. It
 * accepts a Clink language pack's `<code>.emoji.json` too (see
 * [ClinkEmojiNames]), for the same reason.
 */
class EmojiKeywordPack(
    val keywords: Map<String, List<String>>,
    val names: Map<String, String>,
    /**
     * The language this pack names emoji in, when it was loaded from a
     * language folder. Names are only ever shown for the language being typed
     * (see [namesByLanguage]), so a pack that does not know its own language
     * contributes keywords only.
     */
    val langId: String? = null,
) {

    val isEmpty: Boolean get() = keywords.isEmpty() && names.isEmpty()

    /** Emoji covered by this pack — what the settings screen counts. */
    val size: Int get() = (keywords.keys + names.keys).size

    companion object {

        val EMPTY = EmojiKeywordPack(emptyMap(), emptyMap())

        /** Refuse absurd files outright rather than parsing megabytes of junk. */
        const val MAX_BYTES = 8L * 1024 * 1024

        /** How far [load] will look for the first non-blank byte when sniffing. */
        private const val SNIFF_LIMIT = 64

        fun load(stream: InputStream, langId: String? = null): EmojiKeywordPack {
            // Sniff before parsing: a leading "[" is the repo's JSON array,
            // anything else is read as TSV. Buffered so the peeked bytes can
            // be handed back to whichever parser wins.
            val buffered = stream.buffered()
            buffered.mark(SNIFF_LIMIT)
            var first = -1
            var skipped = 0
            while (skipped < SNIFF_LIMIT) {
                val byte = buffered.read()
                if (byte < 0) break
                skipped++
                if (!byte.toChar().isWhitespace()) {
                    first = byte
                    break
                }
            }
            buffered.reset()
            if (first == '['.code) {
                val decoded = EmojiDictCodec.decode(buffered.reader().readText()) ?: return EMPTY
                return EmojiKeywordPack(decoded.keywords, decoded.names, langId)
            }
            // A Clink language pack's emoji names: an object, where the
            // data repo's dictionaries are an array.
            if (first == '{'.code) {
                val decoded = ClinkEmojiNames.decode(buffered.reader().readText()) ?: return EMPTY
                return EmojiKeywordPack(decoded.keywords, decoded.names, langId)
            }
            val keywords = HashMap<String, MutableList<String>>()
            val names = HashMap<String, String>()
            buffered.bufferedReader().useLines { lines ->
                for (line in lines) {
                    // "# " opens a comment, a bare "#" does not — see
                    // [EmojiCatalog], whose keycap row starts with one.
                    if (line.isBlank() || line.startsWith("# ")) continue
                    val parts = line.split('\t')
                    val emoji = parts[0].trim()
                    if (emoji.isEmpty()) continue
                    val words = parts.getOrNull(1)
                        ?.split(',')
                        ?.map { it.trim().lowercase() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    if (words.isNotEmpty()) {
                        keywords.getOrPut(emoji) { ArrayList() }.addAll(words)
                    }
                    parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        names[emoji] = it
                    }
                }
            }
            return EmojiKeywordPack(keywords.mapValues { it.value.distinct() }, names, langId)
        }

        /**
         * Folds [packs] into [catalog], adding each pack's keywords to the
         * entry for that emoji. Packs stack: several languages can name the
         * same emoji, and search should find it under all of them.
         *
         * Names deliberately do *not* stack — they can't, since only one can be
         * shown, and every enabled language gets a pack downloaded for it
         * automatically. Whichever pack happened to be folded in last used to
         * win, which meant a Bengali/English/Hindi user read every emoji name
         * in Hindi. Names are resolved per language at lookup time instead —
         * see [namesByLanguage] and [EmojiNames].
         *
         * Returns [catalog] itself when nothing applies, so the common case
         * (no packs installed) costs one boolean and keeps the identity that
         * [EmojiNames] caches against.
         */
        fun merge(
            catalog: List<EmojiEntry>,
            packs: List<EmojiKeywordPack>,
        ): List<EmojiEntry> {
            val applicable = packs.filter { it.keywords.isNotEmpty() }
            if (applicable.isEmpty()) return catalog
            // One string per distinct keyword across the catalog and every
            // pack, as [EmojiCatalog.load] does for the catalog alone: a pack
            // parses each row's words into strings of its own, and the merged
            // catalog is held for as long as the keyboard runs.
            val pool = EmojiCatalog.StringPool().apply { addAll(catalog) }
            return catalog.map { entry ->
                val extra = ArrayList<String>()
                for (pack in applicable) {
                    pack.keywords[entry.emoji]?.let { words -> words.mapTo(extra, pool::of) }
                }
                if (extra.isEmpty()) {
                    entry
                } else {
                    entry.copy(keywords = (entry.keywords + extra).distinct())
                }
            }
        }

        /**
         * The names [packs] carry, grouped by the language that names them, so
         * the long-press popup can show the name in the language being typed
         * and fall back to the catalog's Unicode name otherwise.
         *
         * Within one language the later pack wins, which puts the user's own
         * import above the automatic download — an import is a deliberate act.
         * Packs with no [langId] name nothing: there is no language to show
         * them under.
         */
        fun namesByLanguage(packs: List<EmojiKeywordPack>): Map<String, Map<String, String>> {
            val byLang = HashMap<String, MutableMap<String, String>>()
            for (pack in packs) {
                val lang = pack.langId ?: continue
                if (pack.names.isEmpty()) continue
                byLang.getOrPut(lang) { HashMap() }.putAll(pack.names)
            }
            return byLang.mapValues { (_, names) -> PackedNames.of(names) }
        }
    }
}

/**
 * A read-only name table packed into two strings: the emoji back to back,
 * sorted, and their names in the same order, each found by its offsets.
 *
 * What [EmojiKeywordPack.namesByLanguage] hands the keyboard, which holds it
 * for as long as it runs so the long-press popup can name an emoji in the
 * language being typed. A downloaded pack names about 3,940 emoji, skin
 * tones included; as a `HashMap<String, String>` that was 1.37 MB for English
 * and Bengali and 2.7 MB with Banglish and Hindi too, packed 570 KB and
 * 1.2 MB (measured on a desktop JVM, 2026-11-26).
 *
 * [get] is a binary search and makes the name's string on each call, which
 * only the popup does. Iteration builds its entries as it goes.
 */
internal class PackedNames private constructor(
    private val keyText: String,
    private val keyStart: IntArray,
    private val valueText: String,
    private val valueStart: IntArray,
) : AbstractMap<String, String>() {

    override val size: Int get() = keyStart.size - 1

    override fun isEmpty(): Boolean = size == 0

    override fun containsKey(key: String): Boolean = slot(key) >= 0

    override fun get(key: String): String? {
        val i = slot(key)
        return if (i < 0) null else valueText.substring(valueStart[i], valueStart[i + 1])
    }

    override val entries: Set<Map.Entry<String, String>>
        get() = object : AbstractSet<Map.Entry<String, String>>() {
            override val size: Int get() = this@PackedNames.size

            override fun iterator(): Iterator<Map.Entry<String, String>> = object : Iterator<Map.Entry<String, String>> {
                private var next = 0

                override fun hasNext(): Boolean = next < this@PackedNames.size

                override fun next(): Map.Entry<String, String> {
                    if (!hasNext()) throw NoSuchElementException()
                    val i = next++
                    return java.util.AbstractMap.SimpleImmutableEntry(
                        keyText.substring(keyStart[i], keyStart[i + 1]),
                        valueText.substring(valueStart[i], valueStart[i + 1]),
                    )
                }
            }
        }

    /** [key]'s slot, or -1. */
    private fun slot(key: String): Int {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = compareKey(mid, key)
            when {
                cmp < 0 -> low = mid + 1
                cmp > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    /** Key [i] against [other], as [String.compareTo] would order them. */
    private fun compareKey(i: Int, other: String): Int {
        val from = keyStart[i]
        val len = keyStart[i + 1] - from
        val n = minOf(len, other.length)
        for (k in 0 until n) {
            val a = keyText[from + k]
            val b = other[k]
            if (a != b) return a - b
        }
        return len - other.length
    }

    companion object {
        fun of(names: Map<String, String>): PackedNames {
            val keys = names.keys.sorted()
            val keyText = StringBuilder()
            val valueText = StringBuilder()
            val keyStart = IntArray(keys.size + 1)
            val valueStart = IntArray(keys.size + 1)
            for ((i, key) in keys.withIndex()) {
                keyStart[i] = keyText.length
                valueStart[i] = valueText.length
                keyText.append(key)
                valueText.append(names.getValue(key))
            }
            keyStart[keys.size] = keyText.length
            valueStart[keys.size] = valueText.length
            return PackedNames(keyText.toString(), keyStart, valueText.toString(), valueStart)
        }
    }
}

/**
 * User-imported emoji keyword packs, one folder per language.
 *
 * Laid out like [com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries]
 * — `filesDir/emoji_keywords/<langId>/<name>.tsv`, additive, several packs per
 * language allowed — because it is the same shape of thing: content the app
 * cannot ship for every language, imported from a file or a URL.
 */
object EmojiKeywordPacks {

    fun root(filesDir: File): File = File(filesDir, "emoji_keywords")

    fun languageDir(filesDir: File, langId: String): File =
        File(root(filesDir), langId)

    /** Imported packs for one language, by name. */
    fun packs(filesDir: File, langId: String): List<File> =
        languageDir(filesDir, langId)
            .listFiles { f -> f.isFile && f.extension == "tsv" }
            ?.sortedBy { it.name }
            .orEmpty()

    /** Every language with at least one imported pack. */
    fun languages(filesDir: File): List<String> =
        root(filesDir)
            .listFiles { f -> f.isDirectory }
            ?.filter { packs(filesDir, it.name).isNotEmpty() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    /** The loaded packs for one language, in file order. */
    fun load(filesDir: File, langId: String): List<EmojiKeywordPack> =
        packs(filesDir, langId).mapNotNull { file ->
            runCatching { file.inputStream().use { EmojiKeywordPack.load(it, langId) } }
                .getOrNull()
                ?.takeUnless { it.isEmpty }
        }

    /**
     * Copies [stream] in as a pack named after [displayName], returning how
     * many emoji it parsed to — or [TOO_LARGE] if it ran past
     * [EmojiKeywordPack.MAX_BYTES].
     *
     * A file that yields nothing usable is deleted again and reported as 0, so
     * a wrong pick fails visibly instead of sitting in the list contributing
     * nothing. The size cap is enforced here rather than trusted from the
     * caller: a document provider is free to report an unknown size, and a URL
     * is free to lie about one, but neither can push more bytes through this.
     */
    fun import(
        filesDir: File,
        langId: String,
        displayName: String,
        stream: InputStream,
    ): Int {
        val dir = languageDir(filesDir, langId).apply { mkdirs() }
        val target = uniqueFile(dir, displayName)
        var copied = 0L
        var overflowed = false
        target.outputStream().use { out ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                copied += read
                if (copied > EmojiKeywordPack.MAX_BYTES) {
                    overflowed = true
                    break
                }
                out.write(buffer, 0, read)
            }
        }
        if (overflowed) {
            target.delete()
            return TOO_LARGE
        }
        val count = runCatching {
            target.inputStream().use { EmojiKeywordPack.load(it).size }
        }.getOrDefault(0)
        if (count == 0) target.delete()
        return count
    }

    /** [import] refused the stream: it ran past [EmojiKeywordPack.MAX_BYTES]. */
    const val TOO_LARGE = -1

    fun remove(file: File): Boolean = file.delete()

    /**
     * Picked names arrive straight from the document provider or a URL's last
     * path segment, so strip anything that could climb out of the language
     * folder and settle collisions with a numeric suffix.
     */
    private fun uniqueFile(dir: File, displayName: String): File {
        val base = displayName
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
            .trim()
            .take(48)
            .ifEmpty { "emoji" }
        var candidate = File(dir, "$base.tsv")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n).tsv")
            n++
        }
        return candidate
    }
}
