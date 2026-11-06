package com.wasimaster.wmkeyboard.core.plugins.lua

enum class LuaCompletionKind { LOCAL, PARAMETER, GLOBAL, FUNCTION, TABLE, CONSTANT, FIELD, KEYWORD, SNIPPET, VALUE }

data class LuaCompletionItem(
    val label: String,
    val kind: LuaCompletionKind,
    /** What replaces [LuaCompletions.replace]. */
    val insert: String,
    /** Where the caret lands inside [insert]. */
    val caret: Int = insert.length,
    /** How it is called, or what it holds, shown beside the label. */
    val detail: String? = null,
    /** The [LuaApi] path whose paragraph in [LuaApiDocs] explains it. */
    val docPath: String? = null,
)

data class LuaCompletions(
    /** The text a chosen item replaces: the word the caret is in, or nothing at the caret. */
    val replace: LuaSpan,
    val items: List<LuaCompletionItem>,
)

/**
 * What can be typed at the caret of a plugin script.
 *
 * Read from tokens, because the script is broken Lua while it is being typed:
 * a member list after `wm.` or the fields of `ui.button {` must work in a file
 * that does not parse. Locals come from an analysis when one is at hand, even
 * one a few keystrokes old, and from a scan of `local`, `for` and parameter
 * lists when not.
 */
object LuaCompletion {
    const val MAX_ITEMS = 50
    private const val MAX_BACK_TOKENS = 4000

    private val KEYWORDS = listOf(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto", "if", "in",
        "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while",
    )

    private val EVENT_FIELDS = listOf("type" to "string", "id" to "string", "value" to "string or boolean", "index" to "number")

    /** Snippet label, template with `|` where the caret goes, and whether it is for a file that lacks it. */
    private val SNIPPETS = listOf(
        "function render()" to "function render()\n  return ui.column {\n    |\n  }\nend",
        "function on_event(e)" to "function on_event(e)\n  if e.type == \"click\" then\n    |\n  end\nend",
        "function transform(text, action)" to "function transform(text, action)\n  return |text\nend",
        "local function" to "local function |()\n  \nend",
        "for i = 1, n" to "for i = 1, | do\n  \nend",
        "for key, value in pairs" to "for key, value in pairs(|) do\n  \nend",
        "for index, value in ipairs" to "for index, value in ipairs(|) do\n  \nend",
        "if ... then" to "if | then\n  \nend",
        "while ... do" to "while | do\n  \nend",
        "repeat ... until" to "repeat\n  \nuntil |",
    )

    /**
     * [explicit] is true when the author asked for suggestions. Then an empty
     * word lists everything that fits; while typing, a name list waits for the
     * first letter, and only `.`, `:` and a string with known values open one
     * straight away.
     */
    fun at(
        document: LuaDocument,
        caret: Int,
        host: LuaHostShape? = null,
        explicit: Boolean = false,
        analysis: LuaAnalysis? = document.analysis,
    ): LuaCompletions? = at(document.tokens, caret, host, explicit, analysis)

    /** From tokens alone, for an editor that has lexed the text to colour it and must not wait for a parse. */
    fun at(
        tokens: LuaTokens,
        caret: Int,
        host: LuaHostShape? = null,
        explicit: Boolean = false,
        analysis: LuaAnalysis? = null,
    ): LuaCompletions? {
        if (caret < 0 || caret > tokens.source.length) return null
        return Context(tokens, caret, host, explicit, analysis).complete()
    }

    private class Context(
        private val tokens: LuaTokens,
        private val caret: Int,
        @Suppress("unused") private val host: LuaHostShape?,
        private val explicit: Boolean,
        private val analysis: LuaAnalysis?,
    ) {
        private val source = tokens.source
        private val wordStart = run {
            var at = caret
            while (at > 0 && isWordChar(source[at - 1])) at--
            at
        }
        private val wordEnd = run {
            var at = caret
            while (at < source.length && isWordChar(source[at])) at++
            at
        }
        private val prefix = source.substring(wordStart, caret)

        /** Names that are locals here, which rank first whatever kind of value they hold. */
        private val localNames = HashSet<String>()
        private val frequency: Map<String, Int> by lazy {
            val counts = HashMap<String, Int>()
            for (index in 0 until tokens.size) {
                if (tokens.kind(index) == LuaTokenKind.NAME) counts.merge(tokens.text(index), 1, Int::plus)
            }
            counts
        }

        fun complete(): LuaCompletions? {
            if (prefix.firstOrNull()?.isDigit() == true) return null
            if (caret > 0) {
                val token = tokens.indexAt(caret - 1)
                if (token >= 0) {
                    val kind = tokens.kind(token)
                    val start = tokens.start(token)
                    val end = tokens.end(token)
                    val inside = caret > start && (caret < end || tokens.unterminated(token))
                    when (kind) {
                        LuaTokenKind.COMMENT, LuaTokenKind.SHEBANG -> return null
                        LuaTokenKind.LONG_COMMENT, LuaTokenKind.LONG_STRING -> if (inside) return null
                        LuaTokenKind.STRING -> if (inside) return inString(token)
                        else -> Unit
                    }
                }
            }
            val before = codeBefore(wordStart)
            if (before >= 0 && tokens.matches(before, ".")) return members(before)
            if (before >= 0 && tokens.matches(before, ":")) return methods()
            uiKeys(before)?.let { return it }
            if (prefix.isEmpty() && !explicit) return null
            if (before >= 0 && tokens.kind(before) == LuaTokenKind.KEYWORD) {
                when (tokens.text(before)) {
                    "local" -> return finish(listOf(item("function", LuaCompletionKind.KEYWORD)))
                    "function" -> return finish(contractNames())
                    "for", "goto" -> return null
                }
            }
            return finish(names(), ranked = true)
        }

        // ---- contexts ------------------------------------------------------

        /** Inside a string: a label's style, a button's style, a widget type, an event type or a known id. */
        private fun inString(token: Int): LuaCompletions? {
            val start = tokens.start(token) + 1
            var end = caret
            while (end < tokens.end(token) && isWordChar(source[end])) end++
            val typed = source.substring(start, caret)
            val before = tokens.prevCode(token)
            if (before < 0) return null
            val values: List<String> = when {
                tokens.matches(before, "=") -> {
                    val key = tokens.prevCode(before)
                    when {
                        key < 0 || tokens.kind(key) != LuaTokenKind.NAME -> emptyList()
                        tokens.matches(key, "style") -> when (constructorAround(key)) {
                            "label" -> LuaApi.labelStyles
                            "button" -> LuaApi.buttonStyles
                            else -> emptyList()
                        }
                        tokens.matches(key, "type") && isTableKey(key) -> LuaApi.widgetTypes.sorted()
                        else -> emptyList()
                    }
                }
                tokens.matches(before, "==") || tokens.matches(before, "~=") -> {
                    val field = tokens.prevCode(before)
                    val dot = if (field >= 0) tokens.prevCode(field) else -1
                    when {
                        dot < 0 || !tokens.matches(dot, ".") -> emptyList()
                        tokens.matches(field, "type") -> LuaApi.eventTypes
                        tokens.matches(field, "id") -> literalIds()
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
            val items = values.filter { matchClass(it, typed) >= 0 && it != typed }
                .map { LuaCompletionItem(it, LuaCompletionKind.VALUE, it) }
            return if (items.isEmpty()) null else LuaCompletions(LuaSpan(start, end), items.take(MAX_ITEMS))
        }

        /** After a dot: the members of an API table, the fields of an event, or fields the file uses. */
        private fun members(dot: Int): LuaCompletions? {
            val chain = chainBefore(dot)
            val root = chain.firstOrNull()
            val items = ArrayList<LuaCompletionItem>()
            if (root != null && !isLocal(root)) {
                val path = chain.joinToString(".")
                if (LuaApi.find(path)?.kind == LuaApiKind.TABLE) {
                    for (entry in LuaApi.children(path)) items += apiItem(entry)
                }
            }
            if (chain.size == 1 && root == eventParameter()) {
                for ((name, type) in EVENT_FIELDS) items += LuaCompletionItem(name, LuaCompletionKind.FIELD, name, detail = type)
            }
            if (chain.isNotEmpty()) {
                for (name in fieldsUsed(chain)) items += LuaCompletionItem(name, LuaCompletionKind.FIELD, name)
            }
            return finish(items)
        }

        /** After a colon: string methods, and the methods the file calls. */
        private fun methods(): LuaCompletions? {
            val items = ArrayList<LuaCompletionItem>()
            for (entry in LuaApi.children("string")) {
                items += LuaCompletionItem(entry.name, LuaCompletionKind.FUNCTION, entry.name + "()", entry.name.length + 1, entry.signature, entry.path)
            }
            for (index in 0 until tokens.size) {
                if (!tokens.matches(index, ":")) continue
                val name = tokens.nextCode(index)
                if (name >= 0 && tokens.kind(name) == LuaTokenKind.NAME && !isBeingTyped(name)) {
                    val text = tokens.text(name)
                    items += LuaCompletionItem(text, LuaCompletionKind.FUNCTION, "$text()", text.length + 1)
                }
            }
            return finish(items)
        }

        /** At a key inside `ui.button { ... }`: the fields not yet written, and child widgets for a container. */
        private fun uiKeys(before: Int): LuaCompletions? {
            if (before < 0 || !(tokens.matches(before, "{") || tokens.matches(before, ",") || tokens.matches(before, ";"))) return null
            val open = if (tokens.matches(before, "{")) before else openBrace(tokens.prevCode(before))
            if (open < 0) return null
            val shape = LuaApi.uiShapes[constructorBefore(open) ?: return null] ?: return null
            if (prefix.isEmpty() && !explicit) return null
            val written = keysIn(open)
            val items = ArrayList<LuaCompletionItem>()
            for (field in shape.fields) {
                if (field in written) continue
                val template = fieldTemplate(field)
                items += LuaCompletionItem(field, LuaCompletionKind.FIELD, template.replace("|", ""), template.indexOf('|'), docPath = "ui.${shape.constructor}")
            }
            if (shape.takesChildren) {
                for (child in LuaApi.uiShapes.values) {
                    // A tab strip holds pages and nothing else, and a page belongs in nothing else.
                    if ((child.constructor == "page") != (shape.constructor == "tabs")) continue
                    val entry = LuaApi.find("ui.${child.constructor}") ?: continue
                    val template = "ui." + constructorTemplate(child)
                    items += LuaCompletionItem(child.constructor, LuaCompletionKind.FUNCTION, template.replace("|", ""), template.indexOf('|'), entry.signature, entry.path)
                }
            }
            return finish(items)
        }

        /** Every name that fits where a statement or an expression is being typed. */
        private fun names(): List<LuaCompletionItem> {
            val items = ArrayList<LuaCompletionItem>()
            val locals = analysis?.visibleAt(caret)?.map { symbol ->
                symbol.name to when (symbol.kind) {
                    LuaSymbolKind.PARAMETER, LuaSymbolKind.SELF -> LuaCompletionKind.PARAMETER
                    LuaSymbolKind.LOCAL_FUNCTION -> LuaCompletionKind.FUNCTION
                    else -> LuaCompletionKind.LOCAL
                }
            } ?: declaredBefore()
            for ((name, kind) in locals) {
                items += item(name, kind)
                localNames += name
            }
            for (name in scriptGlobals()) items += item(name, LuaCompletionKind.GLOBAL)
            for (entry in LuaApi.children("")) items += apiItem(entry)
            for (keyword in KEYWORDS) items += item(keyword, LuaCompletionKind.KEYWORD)
            val indent = indentAt(caret)
            val defined = definedFunctions()
            for ((label, template) in SNIPPETS) {
                if (label == "function render()" && "render" in defined) continue
                if (label == "function on_event(e)" && "on_event" in defined) continue
                if (label == "function transform(text, action)" && "transform" in defined) continue
                if (prefix.isEmpty() || !label.substringBefore(' ').startsWith(prefix, ignoreCase = true)) continue
                val indented = template.replace("\n", "\n$indent")
                items += LuaCompletionItem(label, LuaCompletionKind.SNIPPET, indented.replace("|", ""), indented.indexOf('|'))
            }
            return items
        }

        /** `render` and `on_event` after `function`, when the file has not defined them. */
        private fun contractNames(): List<LuaCompletionItem> {
            val defined = definedFunctions()
            return buildList {
                if ("render" !in defined) add(LuaCompletionItem("render", LuaCompletionKind.FUNCTION, "render()", 7))
                if ("on_event" !in defined) add(LuaCompletionItem("on_event", LuaCompletionKind.FUNCTION, "on_event(e)", 11))
            }
        }

        // ---- ranking -------------------------------------------------------

        private fun finish(items: List<LuaCompletionItem>, ranked: Boolean = false): LuaCompletions? {
            val best = LinkedHashMap<String, Pair<LuaCompletionItem, Int>>()
            for (candidate in items) {
                val match = if (candidate.kind == LuaCompletionKind.SNIPPET) 0 else matchClass(candidate.label, prefix)
                if (match < 0) continue
                if (candidate.label == prefix && candidate.insert == prefix) continue
                val score = match * 10 + if (ranked) rankOf(candidate) else 0
                val held = best[candidate.label]
                if (held == null || score < held.second) best[candidate.label] = candidate to score
            }
            if (best.isEmpty()) return null
            val sorted = best.values.sortedWith(
                compareBy<Pair<LuaCompletionItem, Int>>({ it.second })
                    .thenByDescending { frequency[it.first.label] ?: 0 }
                    .thenBy { it.first.label.length }
                    .thenBy { it.first.label },
            )
            return LuaCompletions(LuaSpan(wordStart, wordEnd), sorted.take(MAX_ITEMS).map { it.first })
        }

        /** 0 for a prefix in the same case, 1 in any case, 2 for the letters in order, -1 for no match. */
        private fun matchClass(label: String, typed: String): Int {
            if (typed.isEmpty() || label.startsWith(typed)) return 0
            if (label.startsWith(typed, ignoreCase = true)) return 1
            if (!label[0].equals(typed[0], ignoreCase = true)) return -1
            var at = 0
            for (character in label) {
                if (at < typed.length && character.equals(typed[at], ignoreCase = true)) at++
            }
            return if (at == typed.length) 2 else -1
        }

        private fun rankOf(item: LuaCompletionItem): Int =
            if (item.label in localNames && item.kind != LuaCompletionKind.KEYWORD && item.kind != LuaCompletionKind.SNIPPET) 0 else kindRank(item.kind)

        private fun kindRank(kind: LuaCompletionKind): Int = when (kind) {
            LuaCompletionKind.LOCAL, LuaCompletionKind.PARAMETER -> 0
            LuaCompletionKind.GLOBAL -> 1
            LuaCompletionKind.FUNCTION, LuaCompletionKind.TABLE, LuaCompletionKind.CONSTANT, LuaCompletionKind.FIELD -> 2
            LuaCompletionKind.KEYWORD, LuaCompletionKind.VALUE -> 3
            LuaCompletionKind.SNIPPET -> 4
        }

        // ---- items ---------------------------------------------------------

        private fun item(name: String, kind: LuaCompletionKind) = LuaCompletionItem(name, kind, name)

        private fun apiItem(entry: LuaApiEntry): LuaCompletionItem {
            val kind = when (entry.kind) {
                LuaApiKind.FUNCTION -> LuaCompletionKind.FUNCTION
                LuaApiKind.TABLE -> LuaCompletionKind.TABLE
                LuaApiKind.CONSTANT -> LuaCompletionKind.CONSTANT
            }
            val shape = if (entry.parent == "ui") LuaApi.uiShapes[entry.name] else null
            val template = when {
                shape != null -> constructorTemplate(shape)
                entry.kind != LuaApiKind.FUNCTION -> entry.name + "|"
                entry.signature?.endsWith("()") == true -> entry.name + "()|"
                else -> entry.name + "(|)"
            }
            return LuaCompletionItem(entry.name, kind, template.replace("|", ""), template.indexOf('|'), entry.signature, entry.path)
        }

        private fun constructorTemplate(shape: LuaUiShape): String {
            val name = shape.constructor
            val wanted = when (name) {
                "button" -> listOf("id", "text")
                "toggle", "input" -> listOf("id", "label")
                "label", "output" -> listOf("text")
                "tabs" -> listOf("id")
                "page" -> listOf("title")
                "spacer" -> return "spacer { height = 8 }|"
                else -> emptyList()
            }
            if (wanted.isEmpty()) return if (shape.takesChildren) "$name { | }" else "$name()|"
            val fields = wanted.joinToString(", ") { fieldTemplate(it) }
            // The caret goes to the first gap; the markers of the others are dropped.
            val first = fields.indexOf('|')
            return "$name { " + fields.substring(0, first + 1) + fields.substring(first + 1).replace("|", "") + " }"
        }

        private fun fieldTemplate(field: String): String = when (field) {
            "mono", "insertable", "copyable", "enabled", "checked" -> "$field = |"
            "height" -> "$field = |8"
            else -> "$field = \"|\""
        }

        // ---- reading the file ----------------------------------------------

        /** The code token that ends at or before [offset], or -1. */
        private fun codeBefore(offset: Int): Int {
            if (offset <= 0) return -1
            val token = tokens.indexAt(offset - 1)
            if (token < 0) return -1
            return if (tokens.kind(token).isCode) token else tokens.prevCode(token)
        }

        /** The names of `a.b.c` before a dot, root first; empty when the chain does not start with a name. */
        private fun chainBefore(dot: Int): List<String> {
            val parts = ArrayList<String>()
            var at = tokens.prevCode(dot)
            while (at >= 0 && tokens.kind(at) == LuaTokenKind.NAME) {
                parts += tokens.text(at)
                val previous = tokens.prevCode(at)
                if (previous < 0 || !tokens.matches(previous, ".")) break
                at = tokens.prevCode(previous)
            }
            if (at >= 0 && tokens.kind(at) != LuaTokenKind.NAME) return emptyList()
            return parts.asReversed()
        }

        private fun isLocal(name: String): Boolean =
            analysis?.visibleAt(caret)?.any { it.name == name } ?: declaredBefore().any { it.first == name }

        /** Locals, parameters and loop variables written before the caret, for a file with no analysis. */
        private fun declaredBefore(): List<Pair<String, LuaCompletionKind>> {
            val found = ArrayList<Pair<String, LuaCompletionKind>>()
            for (index in 0 until tokens.size) {
                if (tokens.start(index) >= wordStart) break
                if (tokens.kind(index) != LuaTokenKind.KEYWORD) continue
                when (tokens.text(index)) {
                    "local", "for" -> {
                        var at = tokens.nextCode(index)
                        if (at >= 0 && tokens.matches(at, "function")) {
                            val name = tokens.nextCode(at)
                            if (name >= 0 && tokens.kind(name) == LuaTokenKind.NAME) found += tokens.text(name) to LuaCompletionKind.FUNCTION
                            continue
                        }
                        while (at >= 0 && tokens.kind(at) == LuaTokenKind.NAME && tokens.start(at) < wordStart) {
                            found += tokens.text(at) to LuaCompletionKind.LOCAL
                            val comma = tokens.nextCode(at)
                            if (comma < 0 || !tokens.matches(comma, ",")) break
                            at = tokens.nextCode(comma)
                        }
                    }
                    "function" -> {
                        var at = tokens.nextCode(index)
                        while (at >= 0 && !tokens.matches(at, "(") && tokens.start(at) < wordStart) at = tokens.nextCode(at)
                        if (at < 0) continue
                        at = tokens.nextCode(at)
                        while (at >= 0 && tokens.kind(at) == LuaTokenKind.NAME && tokens.start(at) < wordStart) {
                            found += tokens.text(at) to LuaCompletionKind.PARAMETER
                            val comma = tokens.nextCode(at)
                            if (comma < 0 || !tokens.matches(comma, ",")) break
                            at = tokens.nextCode(comma)
                        }
                    }
                }
            }
            return found
        }

        /** Globals the file assigns: from the analysis, or `name =` at the start of a line and `function name`. */
        private fun scriptGlobals(): Set<String> {
            analysis?.let { found -> return found.globals.filter { it.write }.mapTo(LinkedHashSet()) { it.name } }
            val names = LinkedHashSet<String>()
            for (index in 0 until tokens.size) {
                if (tokens.kind(index) != LuaTokenKind.NAME) continue
                val previous = tokens.prevCode(index)
                val next = tokens.nextCode(index)
                val atLineStart = previous < 0 || source.lastIndexOf('\n', tokens.start(index)) > tokens.end(previous) - 1
                val assigned = next >= 0 && tokens.matches(next, "=") && atLineStart
                val declared = previous >= 0 && tokens.matches(previous, "function") && !(next >= 0 && tokens.matches(next, "."))
                if ((assigned || declared) && !isBeingTyped(index)) names += tokens.text(index)
            }
            return names
        }

        private fun definedFunctions(): Set<String> {
            val names = HashSet<String>()
            for (index in 0 until tokens.size) {
                if (!tokens.matches(index, "function")) continue
                val name = tokens.nextCode(index)
                if (name >= 0 && tokens.kind(name) == LuaTokenKind.NAME && !isBeingTyped(name)) names += tokens.text(name)
            }
            return names
        }

        /** The first parameter of `function on_event`, or null. */
        private fun eventParameter(): String? {
            for (index in 0 until tokens.size) {
                if (!tokens.matches(index, "function")) continue
                val name = tokens.nextCode(index)
                if (name < 0 || !tokens.matches(name, "on_event")) continue
                val open = tokens.nextCode(name)
                val first = if (open >= 0 && tokens.matches(open, "(")) tokens.nextCode(open) else -1
                return if (first >= 0 && tokens.kind(first) == LuaTokenKind.NAME) tokens.text(first) else null
            }
            return null
        }

        /** Field names the file writes after this chain, and the keys of a table assigned to it. */
        private fun fieldsUsed(chain: List<String>): Set<String> {
            val names = LinkedHashSet<String>()
            for (index in 0 until tokens.size) {
                if (tokens.kind(index) != LuaTokenKind.NAME || !tokens.matches(index, chain.last())) continue
                if (chainEndingAt(index) != chain) continue
                val next = tokens.nextCode(index)
                if (next >= 0 && tokens.matches(next, ".")) {
                    val field = tokens.nextCode(next)
                    if (field >= 0 && tokens.kind(field) == LuaTokenKind.NAME && !isBeingTyped(field)) names += tokens.text(field)
                } else if (next >= 0 && tokens.matches(next, "=")) {
                    val open = tokens.nextCode(next)
                    if (open >= 0 && tokens.matches(open, "{")) names += keysIn(open)
                }
            }
            return names
        }

        private fun chainEndingAt(last: Int): List<String> {
            val parts = ArrayList<String>()
            var at = last
            while (at >= 0 && tokens.kind(at) == LuaTokenKind.NAME) {
                parts += tokens.text(at)
                val previous = tokens.prevCode(at)
                if (previous < 0 || !tokens.matches(previous, ".")) break
                at = tokens.prevCode(previous)
            }
            return parts.asReversed()
        }

        /** The `{` whose table holds [token], or -1 when a `(` or `[` is nearer. */
        private fun openBrace(token: Int): Int {
            var depth = 0
            var at = token
            var steps = 0
            while (at >= 0 && steps++ < MAX_BACK_TOKENS) {
                if (tokens.kind(at) == LuaTokenKind.OPERATOR) {
                    when {
                        tokens.matches(at, ")") || tokens.matches(at, "}") || tokens.matches(at, "]") -> depth++
                        tokens.matches(at, "(") || tokens.matches(at, "{") || tokens.matches(at, "[") -> {
                            if (depth == 0) return if (tokens.matches(at, "{")) at else -1
                            depth--
                        }
                    }
                }
                at = tokens.prevCode(at)
            }
            return -1
        }

        /** `button` for a `{` written as `ui.button {` or `ui.button({`, or null. */
        private fun constructorBefore(open: Int): String? {
            var name = tokens.prevCode(open)
            if (name >= 0 && tokens.matches(name, "(")) name = tokens.prevCode(name)
            if (name < 0 || tokens.kind(name) != LuaTokenKind.NAME) return null
            val dot = tokens.prevCode(name)
            val root = if (dot >= 0 && tokens.matches(dot, ".")) tokens.prevCode(dot) else -1
            return if (root >= 0 && tokens.matches(root, "ui")) tokens.text(name) else null
        }

        private fun constructorAround(key: Int): String? {
            val before = tokens.prevCode(key)
            if (before < 0) return null
            val open = if (tokens.matches(before, "{")) before else openBrace(tokens.prevCode(before))
            return if (open >= 0) constructorBefore(open) else null
        }

        private fun isTableKey(key: Int): Boolean {
            val before = tokens.prevCode(key)
            return before >= 0 && (tokens.matches(before, "{") || tokens.matches(before, ",") || tokens.matches(before, ";"))
        }

        /** The `name =` keys at the top level of the table opening at [open]. */
        private fun keysIn(open: Int): Set<String> {
            val keys = LinkedHashSet<String>()
            var depth = 0
            var at = tokens.nextCode(open)
            while (at >= 0) {
                if (tokens.kind(at) == LuaTokenKind.OPERATOR) {
                    when {
                        tokens.matches(at, "(") || tokens.matches(at, "{") || tokens.matches(at, "[") -> depth++
                        tokens.matches(at, ")") || tokens.matches(at, "}") || tokens.matches(at, "]") -> {
                            if (depth == 0) break
                            depth--
                        }
                    }
                } else if (depth == 0 && tokens.kind(at) == LuaTokenKind.NAME && !isBeingTyped(at)) {
                    val next = tokens.nextCode(at)
                    if (next >= 0 && tokens.matches(next, "=") && isTableKey(at)) keys += tokens.text(at)
                }
                at = tokens.nextCode(at)
            }
            return keys
        }

        private fun literalIds(): List<String> {
            val ids = LinkedHashSet<String>()
            for (index in 0 until tokens.size) {
                if (!tokens.matches(index, "id") || !isTableKey(index)) continue
                val equals = tokens.nextCode(index)
                val value = if (equals >= 0 && tokens.matches(equals, "=")) tokens.nextCode(equals) else -1
                if (value >= 0 && tokens.kind(value) == LuaTokenKind.STRING && !tokens.unterminated(value)) {
                    val text = tokens.text(value)
                    if (text.length >= 2 && '\\' !in text) ids += text.substring(1, text.length - 1)
                }
            }
            return ids.toList()
        }

        private fun indentAt(offset: Int): String {
            var start = offset
            while (start > 0 && source[start - 1] != '\n') start--
            var end = start
            while (end < source.length && (source[end] == ' ' || source[end] == '\t')) end++
            return source.substring(start, minOf(end, offset))
        }

        /** Whether [token] is the word under the caret, which is not yet a name the file uses. */
        private fun isBeingTyped(token: Int): Boolean =
            wordEnd > wordStart && tokens.start(token) == wordStart && tokens.end(token) == wordEnd

        private fun isWordChar(character: Char): Boolean = character == '_' || character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9'
    }
}
