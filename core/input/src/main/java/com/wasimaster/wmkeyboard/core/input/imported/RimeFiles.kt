package com.wasimaster.wmkeyboard.core.input.imported

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Rime's files: `<id>.schema.yaml` (how keys become syllables), and
 * `<name>.dict.yaml` (what syllables spell), plus the shared YAML files a
 * schema borrows rules from (`pinyin.yaml`, `zhuyin.yaml`).
 *
 * Read from the format as Rime documents it and as the published schemas use
 * it. Rime's configuration language has a few merge directives that the
 * shipped schemas depend on, and they are honoured here: `__include` (take
 * that node), `__patch` (apply those changes), `__append` and `__merge`. A
 * reference is `file:/path` or, for the same file, just `path`. Anything a
 * reference names that was not among the files given is reported back.
 */
object RimeFiles {

    /** Whether [name] or [text] is a Rime dictionary: `.dict.yaml`, or a YAML header closed by `...` with rows after. */
    fun looksLikeDict(name: String, text: String): Boolean =
        name.endsWith(".dict.yaml", ignoreCase = true) ||
            (headerEnd(text) >= 0 && text.lineSequence().take(SNIFF_LINES).any { it.trim().startsWith("name:") })

    fun looksLikeSchema(name: String, text: String): Boolean =
        name.endsWith(".schema.yaml", ignoreCase = true) ||
            (!name.endsWith(".dict.yaml", ignoreCase = true) && SchemaKey.containsMatchIn(text) && text.contains("schema_id:"))

    /** A top-level `schema:` key, as a schema has and a dictionary's comments may merely mention. */
    private val SchemaKey = Regex("(?m)^schema:")

    fun isYaml(name: String): Boolean =
        name.endsWith(".yaml", ignoreCase = true) || name.endsWith(".yml", ignoreCase = true)

    /** A file's key for references: `pinyin` for `pinyin.yaml`, `luna_pinyin.schema` for a schema. */
    fun refName(fileName: String): String =
        fileName.substringAfterLast('/').removeSuffix(".yaml").removeSuffix(".yml")

    /** Where a dictionary's YAML header ends: the index of its `...` line, or -1. */
    private fun headerEnd(text: String): Int {
        var offset = 0
        for (line in text.lineSequence()) {
            if (line.trimEnd() == "...") return offset
            offset += line.length + 1
            if (offset > MAX_HEADER) return -1
        }
        return -1
    }

    // ---- YAML ----

    /** A YAML document as maps, lists and scalars, or null when it does not parse. */
    fun loadYaml(text: String): Any? {
        val options = LoaderOptions().apply {
            codePointLimit = MAX_YAML_CHARS
            maxAliasesForCollections = MAX_ALIASES
            setAllowRecursiveKeys(false)
            nestingDepthLimit = MAX_DEPTH
        }
        // SafeConstructor, as everywhere else this app reads YAML: the default
        // one builds whatever class a `!!` tag names, and these files were
        // written by somebody else.
        return runCatching { Yaml(SafeConstructor(options)).load<Any?>(yaml12Escapes(text)) }.getOrNull()
    }

    /**
     * Rime's files are YAML 1.2, which lets a double-quoted string escape a
     * slash (`"[a-z;,.\/]"`); the parser here reads YAML 1.1, which refuses it
     * and with it the whole schema. Inside double quotes only, `\/` becomes `/`,
     * which is what it means in either version.
     */
    internal fun yaml12Escapes(text: String): String {
        if (!text.contains("\\/")) return text
        val out = StringBuilder(text.length)
        var quoted = false
        var comment = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    comment = false
                    out.append(c)
                }
                comment -> out.append(c)
                quoted && c == '\\' && i + 1 < text.length -> {
                    val next = text[i + 1]
                    if (next == '/') out.append('/') else out.append(c).append(next)
                    i++
                }
                c == '"' -> {
                    quoted = !quoted
                    out.append(c)
                }
                !quoted && c == '#' && (i == 0 || text[i - 1].isWhitespace()) -> {
                    comment = true
                    out.append(c)
                }
                !quoted && c == '\'' -> {
                    // A single-quoted scalar runs to the next lone quote; nothing in it is an escape.
                    val end = singleQuotedEnd(text, i)
                    out.append(text, i, end)
                    i = end - 1
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** Where the single-quoted scalar opening at [start] ends (exclusive), `''` being a quote inside it. */
    private fun singleQuotedEnd(text: String, start: Int): Int {
        // An apostrophe inside a plain word (`don't`) does not open a scalar.
        if (start > 0 && text[start - 1].isLetterOrDigit()) return start + 1
        var j = start + 1
        while (j < text.length) {
            if (text[j] == '\n') return j
            if (text[j] == '\'') {
                if (j + 1 < text.length && text[j + 1] == '\'') {
                    j += 2
                    continue
                }
                return j + 1
            }
            j++
        }
        return j
    }

    /**
     * Rime's directives resolved over a set of YAML documents, keyed by
     * [refName].
     */
    class Config(private val documents: Map<String, Any?>) {

        /** References that named a file or node not among [documents]. */
        val missing = LinkedHashSet<String>()

        private var depth = 0

        /** The node at [path] (`speller/algebra`) of document [file], with every directive applied. */
        fun get(file: String, path: String): Any? = expand(walk(documents[file], path), file)

        private fun walk(root: Any?, path: String): Any? {
            var node = root
            for (part in path.split('/').filter { it.isNotEmpty() }) {
                node = (node as? Map<*, *>)?.get(part) ?: return null
            }
            return node
        }

        /** `file:/a/b` or `a/b` (in [from]), as a node. */
        private fun resolve(ref: String, from: String): Pair<Any?, String>? {
            // A trailing `?` marks a reference Rime may skip when its target
            // is absent, such as a user's own `.custom.yaml`.
            val optional = ref.trim().endsWith('?')
            val trimmed = ref.trim().removeSuffix("?")
            // Some schemas name the file with its extension (`array30.schema.yaml:/…`).
            val file = if (':' in trimmed) trimmed.substringBefore(':').removeSuffix(".yaml") else from
            val path = if (':' in trimmed) trimmed.substringAfter(':') else trimmed
            val doc = documents[file] ?: documents["$file.schema"]
            if (doc == null) {
                if (!optional) missing += "$file.yaml"
                return null
            }
            val node = walk(doc, path)
            if (node == null) {
                if (!optional) missing += trimmed
                return null
            }
            return node to (if (documents.containsKey(file)) file else "$file.schema")
        }

        /** [node] with its directives applied, as plain maps and lists. */
        fun expand(node: Any?, file: String): Any? {
            if (++depth > MAX_DEPTH) {
                depth--
                return null
            }
            try {
                return when (node) {
                    is Map<*, *> -> expandMap(node, file)
                    is List<*> -> node.map { expand(it, file) }
                    else -> node
                }
            } finally {
                depth--
            }
        }

        private fun expandMap(node: Map<*, *>, file: String): Any? {
            // Start from what __include names, then lay this map's own keys over it.
            var base: Any? = null
            node["__include"]?.let { ref ->
                resolve(ref.toString(), file)?.let { (target, inFile) -> base = expand(target, inFile) }
            }
            val own = LinkedHashMap<String, Any?>()
            for ((k, v) in node) {
                val key = k?.toString() ?: continue
                if (key.startsWith("__")) continue
                own[key] = expand(v, file)
            }
            var result: Any? = when {
                own.isEmpty() -> base
                base is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
                    for ((k, v) in base as Map<*, *>) put(k.toString(), v)
                    putAll(own)
                }
                else -> own
            }
            node["__append"]?.let { result = append(result, expand(it, file)) }
            node["__merge"]?.let { result = merge(result, expand(it, file)) }
            val patches = when (val p = node["__patch"]) {
                null -> emptyList()
                is List<*> -> p
                else -> listOf(p)
            }
            for (patch in patches) {
                val content = when (patch) {
                    is String -> resolve(patch, file)?.let { (target, inFile) -> expand(target, inFile) }
                    else -> expand(patch, file)
                }
                result = applyPatch(result, content)
            }
            return result
        }

        /**
         * A patch over [target]. The patches schemas actually use are
         * `{__append: [...]}` (already folded into a list by [expand]), a plain
         * list to append, and `{path: value}` replacements.
         */
        private fun applyPatch(target: Any?, patch: Any?): Any? = when (patch) {
            null -> target
            is List<*> -> append(target, patch)
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                (target as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v }
                for ((k, v) in patch) {
                    val key = k.toString()
                    when {
                        key.endsWith("/+") -> out[key.removeSuffix("/+")] =
                            if (v is List<*>) append(out[key.removeSuffix("/+")], v) else merge(out[key.removeSuffix("/+")], v)
                        else -> out[key] = v
                    }
                }
                out
            }
            else -> target
        }

        private fun append(target: Any?, items: Any?): Any? {
            val list = items as? List<*> ?: return target
            val base = (target as? List<*>).orEmpty()
            return base + list
        }

        private fun merge(target: Any?, items: Any?): Any? {
            val map = items as? Map<*, *> ?: return target
            val out = LinkedHashMap<String, Any?>()
            (target as? Map<*, *>)?.forEach { (k, v) -> out[k.toString()] = v }
            map.forEach { (k, v) -> out[k.toString()] = v }
            return out
        }
    }

    // ---- schema ----

    /** What this app reads out of a `.schema.yaml`. */
    class Schema(
        val id: String,
        val name: String,
        /** The dictionary the translator reads (`translator/dictionary`). */
        val dictionary: String?,
        /** Syllables are converted as phrases (`script_translator`) rather than looked up as codes. */
        val scriptTranslator: Boolean,
        val alphabet: String,
        val delimiters: String,
        val algebra: List<String>,
        val preeditFormat: List<String>,
        val commentFormat: List<String>,
    )

    /** Reads the schema in [refName], resolving its directives through [config]. */
    fun schema(config: Config, refName: String): Schema? {
        val root = config.get(refName, "") as? Map<*, *> ?: return null
        val info = root["schema"] as? Map<*, *>
        val translators = (root["engine"] as? Map<*, *>)?.get("translators") as? List<*>
        val translator = root["translator"] as? Map<*, *>
        val speller = root["speller"] as? Map<*, *>
        // The schema's own translator is the one without an `@name`; the named
        // ones serve reverse lookup and side tables (Array 30 looks words up
        // in pinyin through a `script_translator@luna_pinyin`).
        val main = translators?.map { it.toString() }?.filter { '@' !in it }.orEmpty()
        val script = when {
            "script_translator" in main -> true
            "table_translator" in main -> false
            else -> translator?.get("prism") != null
        }
        return Schema(
            id = info?.get("schema_id")?.toString() ?: refName.removeSuffix(".schema"),
            name = info?.get("name")?.toString() ?: refName.removeSuffix(".schema"),
            dictionary = translator?.get("dictionary")?.toString()?.takeIf { it.isNotBlank() },
            scriptTranslator = script,
            alphabet = speller?.get("alphabet")?.toString() ?: DEFAULT_ALPHABET,
            delimiters = speller?.get("delimiter")?.toString() ?: " ",
            algebra = strings(speller?.get("algebra")),
            preeditFormat = strings(translator?.get("preedit_format")),
            commentFormat = strings(translator?.get("comment_format")),
        )
    }

    private fun strings(value: Any?): List<String> = (value as? List<*>)?.mapNotNull { it as? String }.orEmpty()

    // ---- dictionary ----

    /** One `.dict.yaml`: its header and its rows. */
    class Dict(
        val name: String,
        val importTables: List<String>,
        val rows: List<Row>,
        val truncated: Boolean,
        /**
         * The word list ranking comes from when rows state no weight, and the
         * source of phrases a reading dictionary does not list itself: Rime's
         * `essay`, or whatever `vocabulary:` names. Null when the dictionary
         * uses none.
         */
        val vocabulary: String?,
        /** Phrases longer than this are not taken from the vocabulary; 0 for no limit. */
        val maxPhraseLength: Int,
        /** Phrases rarer than this are not taken from the vocabulary; 0 for no limit. */
        val minPhraseWeight: Double,
        /** How a code table spells a phrase from its characters' codes; null for none. */
        val encoder: RimeTableEncoder? = null,
        /** Rows that give a phrase and no code, for [encoder] to spell: text and stated weight. */
        val uncoded: List<Pair<String, Long?>> = emptyList(),
    )

    /**
     * A row. [weight] is the stated count, [percent] a share of the same text's
     * other readings (Rime writes `了 le 99%`, `了 liao 1%`), and both are null
     * when the row states neither.
     */
    class Row(val text: String, val code: String, val weight: Long?, val percent: Double?)

    /** Reads a `.dict.yaml`, or null when it is not one. [fileName] names it when its header does not. */
    fun dict(fileName: String, text: String): Dict? {
        val end = headerEnd(text)
        if (end < 0) return null
        val header = loadYaml(text.substring(0, end)) as? Map<*, *> ?: emptyMap<String, Any?>()
        val name = header["name"]?.toString() ?: refName(fileName).removeSuffix(".dict")
        val imports = (header["import_tables"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
        // `vocabulary:` alone switches the vocabulary on, as it does in Rime.
        val vocabulary = header["vocabulary"]?.toString()?.takeIf { it.isNotBlank() }
            ?: DEFAULT_VOCABULARY.takeIf { header["use_preset_vocabulary"] == true }
        val columns = (header["columns"] as? List<*>)?.map { it.toString() }
            ?: listOf("text", "code", "weight", "stem")
        val textAt = columns.indexOf("text")
        val codeAt = columns.indexOf("code")
        val weightAt = columns.indexOf("weight")
        if (textAt < 0) return null
        val rows = ArrayList<Row>()
        val uncoded = ArrayList<Pair<String, Long?>>()
        var truncated = false
        val body = text.substring(end).substringAfter('\n', "")
        for (raw in body.lineSequence()) {
            if (raw.isBlank() || raw.startsWith("#")) continue
            if (rows.size >= MAX_ROWS) {
                truncated = true
                break
            }
            val cells = raw.split('\t')
            val value = cells.getOrNull(textAt)?.trim().orEmpty()
            val code = if (codeAt >= 0) cells.getOrNull(codeAt)?.trim().orEmpty() else ""
            if (value.isEmpty()) continue
            val weightCell = if (weightAt >= 0) cells.getOrNull(weightAt)?.trim().orEmpty() else ""
            val percent = weightCell.takeIf { it.endsWith('%') }?.dropLast(1)?.toDoubleOrNull()
            val weight = if (percent == null) weightCell.toDoubleOrNull()?.toLong() else null
            // A phrase with no code is the encoder's to spell (Wubi lists its
            // words that way); with no encoder it has nothing to be typed with.
            if (code.isEmpty()) {
                if (uncoded.size < MAX_ROWS) uncoded += value to weight
                continue
            }
            rows += Row(value, code, weight, percent)
        }
        return Dict(
            name,
            imports,
            rows,
            truncated,
            vocabulary,
            maxPhraseLength = (header["max_phrase_length"] as? Number)?.toInt() ?: 0,
            minPhraseWeight = (header["min_phrase_weight"] as? Number)?.toDouble() ?: 0.0,
            encoder = RimeTableEncoder.of(header["encoder"]),
            uncoded = uncoded,
        )
    }

    /**
     * A vocabulary file (`essay.txt`): `text<TAB>weight` per line, Rime's
     * shared list of how common each word and phrase is.
     */
    fun vocabulary(text: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith('#')) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val weight = line.substring(tab + 1).trim().toDoubleOrNull()?.toLong() ?: continue
            val word = line.substring(0, tab)
            if (weight > (out[word] ?: -1L)) out[word] = weight
            if (out.size >= MAX_ROWS) break
        }
        return out
    }

    private const val SNIFF_LINES = 60
    private const val MAX_HEADER = 256 * 1024
    private const val MAX_YAML_CHARS = 8 * 1024 * 1024
    private const val MAX_ALIASES = 50
    private const val MAX_DEPTH = 64
    private const val DEFAULT_ALPHABET = "zyxwvutsrqponmlkjihgfedcba"
    private const val DEFAULT_VOCABULARY = "essay"

    /** The rows across one import, every table it pulls in included. */
    const val MAX_ROWS = 1_500_000
}
