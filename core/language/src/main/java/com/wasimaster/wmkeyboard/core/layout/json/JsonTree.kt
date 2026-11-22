package com.wasimaster.wmkeyboard.core.layout.json

/**
 * JSON as the layout editor reads it while it is being typed: every token with
 * its place, and a tree that survives the mistakes a person makes halfway
 * through an edit.
 *
 * kotlinx.serialization's parser answers one question, "does this decode", and
 * stops at the first thing wrong. An editor needs more than that from text that
 * is broken most of the time it is looked at: where the caret is in the
 * document's shape, which keys an object already has, and every problem at
 * once, each at its own characters. So this parser never throws, reports what
 * it had to step over, and keeps going with the structure it can still see.
 */
enum class JsonTokenKind {
    OPEN_OBJECT, CLOSE_OBJECT, OPEN_ARRAY, CLOSE_ARRAY, COLON, COMMA,
    STRING, NUMBER, TRUE, FALSE, NULL,

    /** A run of letters that is not `true`, `false` or `null`: a key or a value someone forgot to quote. */
    WORD,

    /** A character no JSON token starts with. */
    UNKNOWN,
}

/** One token, from [start] to [end]. A string that reaches the end of its line without a closing quote is [unterminated]. */
class JsonToken(val kind: JsonTokenKind, val start: Int, val end: Int, val unterminated: Boolean = false) {
    val isScalar: Boolean
        get() = kind == JsonTokenKind.STRING || kind == JsonTokenKind.NUMBER || kind == JsonTokenKind.TRUE ||
            kind == JsonTokenKind.FALSE || kind == JsonTokenKind.NULL || kind == JsonTokenKind.WORD

    override fun toString(): String = "$kind[$start,$end)"
}

enum class JsonSeverity { ERROR, WARNING, INFO }

/**
 * Every problem the parser and the schema checks can report. The words are the
 * app's, one string per code; [JsonIssue.arg1] and [JsonIssue.arg2] fill it.
 */
enum class JsonIssueCode {
    // Syntax.
    UNCLOSED_STRING, BAD_ESCAPE, UNKNOWN_CHARACTER, UNQUOTED_WORD, EXPECTED_VALUE, EXPECTED_KEY,
    EXPECTED_COLON, EXPECTED_COMMA, TRAILING_COMMA, UNCLOSED_OBJECT, UNCLOSED_ARRAY, STRAY_CLOSER,
    TRAILING_CONTENT,

    // Shape.
    UNKNOWN_PROPERTY, PROPERTY_TYPO, DUPLICATE_PROPERTY, MISSING_PROPERTY,
    EXPECTED_TEXT, EXPECTED_NUMBER, EXPECTED_WHOLE_NUMBER, EXPECTED_BOOLEAN, EXPECTED_LIST, EXPECTED_OBJECT,
    NULL_NOT_ALLOWED, NULL_BECOMES_DEFAULT,
    UNKNOWN_VALUE, VALUE_TYPO, UNKNOWN_VALUE_REQUIRED, UNKNOWN_ACTION, ACTION_TYPO,
    UNKNOWN_MAP_KEY, UNKNOWN_LAYER, LAYER_TYPO, OUT_OF_RANGE,
    UNKNOWN_LANGUAGE, UNKNOWN_ICON, UNKNOWN_THEME, UNKNOWN_SECONDARY_LAYOUT, UNKNOWN_RULE_SET,
    FIELD_OUTSIDE_PANEL, SET_BY_KEYBOARD,
}

/** One problem, over the characters from [start] to [end]. */
data class JsonIssue(
    val start: Int,
    val end: Int,
    val severity: JsonSeverity,
    val code: JsonIssueCode,
    val arg1: String? = null,
    val arg2: String? = null,
)

/** A value in the tree, from [start] to [end]. */
sealed class JsonNode(val start: Int, val end: Int)

class JsonObjectNode(start: Int, end: Int, val members: List<JsonMember>, val closed: Boolean) : JsonNode(start, end) {
    /** The member named [name]; the last one when the name is written twice, which is the one a decoder keeps. */
    fun member(name: String): JsonMember? = members.lastOrNull { it.name == name }
}

class JsonArrayNode(start: Int, end: Int, val items: List<JsonNode>, val closed: Boolean) : JsonNode(start, end)

/** A string, number, `true`, `false`, `null` or an unquoted word. [text] is a string's decoded content, or the token as written. */
class JsonScalarNode(val token: JsonToken, val text: String) : JsonNode(token.start, token.end) {
    val kind: JsonTokenKind get() = token.kind
}

/** One `"key": value` of an object. [colon] is -1 when the colon is missing, [value] null when the value is. */
class JsonMember(val key: JsonToken, val name: String, val colon: Int, val value: JsonNode?)

/** A parsed document: its tokens, its tree, and the syntax problems found on the way. */
class JsonDocument(
    val source: String,
    val tokens: List<JsonToken>,
    val root: JsonNode?,
    val syntax: List<JsonIssue>,
    private val containers: Map<Int, JsonNode>,
) {
    /** The object or array whose opening bracket is at [start]. */
    fun containerAt(start: Int): JsonNode? = containers[start]

    /** Every object and array, in document order. */
    val allContainers: Collection<JsonNode> get() = containers.values

    /** Whether the text has no syntax errors, so a decoder will read it. */
    val parses: Boolean get() = root != null && syntax.none { it.severity == JsonSeverity.ERROR }
}

object JsonTree {

    /** The one document last parsed, so the checks, the suggestions and the doc strip share a parse. */
    @Volatile
    private var cached: JsonDocument? = null

    fun of(source: String): JsonDocument {
        // String equality answers at once for the same instance, which is the
        // case this cache is for: the same text asked about by several readers.
        cached?.let { if (it.source == source) return it }
        return parse(source).also { cached = it }
    }

    fun parse(source: String): JsonDocument {
        val tokens = lex(source)
        return Parser(source, tokens).run()
    }

    fun lex(source: String): List<JsonToken> {
        val tokens = ArrayList<JsonToken>()
        var index = 0
        val length = source.length
        while (index < length) {
            val character = source[index]
            when {
                character.isWhitespace() || character == '﻿' -> index++
                character == '{' -> tokens += JsonToken(JsonTokenKind.OPEN_OBJECT, index, ++index)
                character == '}' -> tokens += JsonToken(JsonTokenKind.CLOSE_OBJECT, index, ++index)
                character == '[' -> tokens += JsonToken(JsonTokenKind.OPEN_ARRAY, index, ++index)
                character == ']' -> tokens += JsonToken(JsonTokenKind.CLOSE_ARRAY, index, ++index)
                character == ':' -> tokens += JsonToken(JsonTokenKind.COLON, index, ++index)
                character == ',' -> tokens += JsonToken(JsonTokenKind.COMMA, index, ++index)
                character == '"' -> {
                    val start = index
                    index++
                    var closed = false
                    while (index < length) {
                        val here = source[index]
                        if (here == '\\') {
                            index = minOf(index + 2, length)
                            continue
                        }
                        // A string cannot hold a raw line break, so one ends it: an
                        // unclosed quote colours and breaks its own line, not the file.
                        if (here == '\n' || here == '\r') break
                        index++
                        if (here == '"') {
                            closed = true
                            break
                        }
                    }
                    tokens += JsonToken(JsonTokenKind.STRING, start, index, unterminated = !closed)
                }
                character == '-' || character.isDigit() -> {
                    val start = index
                    index++
                    while (index < length && (source[index].isDigit() || source[index] in NUMBER_CHARACTERS)) index++
                    tokens += JsonToken(JsonTokenKind.NUMBER, start, index)
                }
                character.isLetter() || character == '_' -> {
                    val start = index
                    while (index < length && (source[index].isLetterOrDigit() || source[index] == '_')) index++
                    val kind = when (source.substring(start, index)) {
                        "true" -> JsonTokenKind.TRUE
                        "false" -> JsonTokenKind.FALSE
                        "null" -> JsonTokenKind.NULL
                        else -> JsonTokenKind.WORD
                    }
                    tokens += JsonToken(kind, start, index)
                }
                else -> {
                    val width = if (Character.isHighSurrogate(character) && index + 1 < length) 2 else 1
                    tokens += JsonToken(JsonTokenKind.UNKNOWN, index, index + width)
                    index += width
                }
            }
        }
        return tokens
    }

    /**
     * The content of the string token [token]: its quotes taken off and its
     * escapes read. An escape that is not one JSON has is kept as written, and
     * [onBadEscape] hears where it was.
     */
    fun decodeString(source: String, token: JsonToken, onBadEscape: ((Int, Int) -> Unit)? = null): String {
        val end = if (token.unterminated) token.end else token.end - 1
        val out = StringBuilder(maxOf(0, end - token.start - 1))
        var index = token.start + 1
        while (index < end) {
            val character = source[index]
            if (character != '\\') {
                out.append(character)
                index++
                continue
            }
            val next = source.getOrNull(index + 1)
            when (next) {
                '"', '\\', '/' -> out.append(next)
                'b' -> out.append('\b')
                'f' -> out.append('')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val hex = if (index + 6 <= end) source.substring(index + 2, index + 6) else ""
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        out.append(code.toChar())
                        index += 6
                        continue
                    }
                    onBadEscape?.invoke(index, minOf(end, index + 2))
                    out.append(character)
                    index++
                    continue
                }
                else -> {
                    onBadEscape?.invoke(index, minOf(end, index + 2))
                    out.append(character)
                    index++
                    continue
                }
            }
            index += 2
        }
        return out.toString()
    }

    private const val NUMBER_CHARACTERS = ".eE+-"
}

/**
 * A recursive descent over the tokens that steps over what it cannot use. A
 * closing bracket that belongs to an outer container ends the inner one rather
 * than being thrown away, so one missing `}` is one problem, not the shape of
 * the rest of the file.
 */
private class Parser(private val source: String, private val tokens: List<JsonToken>) {
    private var position = 0
    private val issues = ArrayList<JsonIssue>()
    private val closers = ArrayList<JsonTokenKind>()
    private val containers = LinkedHashMap<Int, JsonNode>()

    fun run(): JsonDocument {
        val root = if (tokens.isEmpty()) null else parseValue() ?: skipToValue()
        if (root != null && position < tokens.size) {
            issue(tokens[position].start, tokens.last().end, JsonSeverity.WARNING, JsonIssueCode.TRAILING_CONTENT)
        }
        return JsonDocument(source, tokens, root, issues, containers)
    }

    /** A document that opens with a stray token still gets its value read, after a note about the token. */
    private fun skipToValue(): JsonNode? {
        while (position < tokens.size) {
            val token = tokens[position]
            if (isValueStart(token.kind)) return parseValue()
            problemToken(token)
            position++
        }
        return null
    }

    private fun peek(): JsonToken? = tokens.getOrNull(position)

    private fun issue(start: Int, end: Int, severity: JsonSeverity, code: JsonIssueCode, arg1: String? = null) {
        issues += JsonIssue(start, maxOf(end, start), severity, code, arg1)
    }

    private fun isValueStart(kind: JsonTokenKind): Boolean = when (kind) {
        JsonTokenKind.OPEN_OBJECT, JsonTokenKind.OPEN_ARRAY, JsonTokenKind.STRING, JsonTokenKind.NUMBER,
        JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL, JsonTokenKind.WORD,
        -> true
        else -> false
    }

    private fun problemToken(token: JsonToken) {
        when (token.kind) {
            JsonTokenKind.UNKNOWN -> {
                val character = source.substring(token.start, token.end)
                issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.UNKNOWN_CHARACTER, character)
            }
            JsonTokenKind.CLOSE_OBJECT, JsonTokenKind.CLOSE_ARRAY ->
                issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.STRAY_CLOSER, source.substring(token.start, token.end))
            else -> issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_VALUE)
        }
    }

    private fun parseValue(): JsonNode? {
        val token = peek() ?: return null
        return when (token.kind) {
            JsonTokenKind.OPEN_OBJECT -> parseObject()
            JsonTokenKind.OPEN_ARRAY -> parseArray()
            JsonTokenKind.STRING -> {
                position++
                scalarString(token)
            }
            JsonTokenKind.NUMBER, JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL -> {
                position++
                JsonScalarNode(token, source.substring(token.start, token.end))
            }
            JsonTokenKind.WORD -> {
                position++
                val word = source.substring(token.start, token.end)
                issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.UNQUOTED_WORD, word)
                JsonScalarNode(token, word)
            }
            else -> null
        }
    }

    private fun scalarString(token: JsonToken): JsonScalarNode {
        if (token.unterminated) issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.UNCLOSED_STRING)
        val text = JsonTree.decodeString(source, token) { start, end ->
            issue(start, end, JsonSeverity.ERROR, JsonIssueCode.BAD_ESCAPE, source.substring(start, end))
        }
        return JsonScalarNode(token, text)
    }

    /** Whether [kind] closes a container outside the one being read, which then ends here unclosed. */
    private fun closesOuter(kind: JsonTokenKind): Boolean = closers.dropLast(1).contains(kind)

    private fun parseObject(): JsonObjectNode {
        val open = tokens[position++]
        closers += JsonTokenKind.CLOSE_OBJECT
        val members = ArrayList<JsonMember>()
        var closed = false
        var end = open.end
        var wantMember = true
        var lastComma: JsonToken? = null
        loop@ while (true) {
            val token = peek()
            if (token == null) {
                issue(open.start, open.end, JsonSeverity.ERROR, JsonIssueCode.UNCLOSED_OBJECT)
                break
            }
            when (token.kind) {
                JsonTokenKind.CLOSE_OBJECT -> {
                    position++
                    closed = true
                    end = token.end
                    if (lastComma != null && wantMember) {
                        issue(lastComma.start, lastComma.end, JsonSeverity.ERROR, JsonIssueCode.TRAILING_COMMA)
                    }
                    break@loop
                }
                JsonTokenKind.CLOSE_ARRAY -> {
                    if (closesOuter(JsonTokenKind.CLOSE_ARRAY)) {
                        issue(open.start, open.end, JsonSeverity.ERROR, JsonIssueCode.UNCLOSED_OBJECT)
                        break@loop
                    }
                    problemToken(token)
                    position++
                }
                JsonTokenKind.COMMA -> {
                    if (wantMember) issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_KEY)
                    position++
                    lastComma = token
                    wantMember = true
                }
                JsonTokenKind.STRING, JsonTokenKind.WORD -> {
                    if (!wantMember) issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_COMMA)
                    position++
                    val name = if (token.kind == JsonTokenKind.STRING) {
                        scalarString(token).text
                    } else {
                        source.substring(token.start, token.end).also {
                            issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.UNQUOTED_WORD, it)
                        }
                    }
                    var colon = -1
                    var value: JsonNode? = null
                    val next = peek()
                    when {
                        next?.kind == JsonTokenKind.COLON -> {
                            colon = next.start
                            position++
                            val start = peek()
                            if (start != null && isValueStart(start.kind) && !looksLikeNextKey(start)) {
                                value = parseValue()
                            } else {
                                issue(next.start, next.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_VALUE, name)
                            }
                        }
                        next != null && isValueStart(next.kind) && next.kind != JsonTokenKind.STRING && next.kind != JsonTokenKind.WORD -> {
                            issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_COLON, name)
                            value = parseValue()
                        }
                        else -> issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_COLON, name)
                    }
                    members += JsonMember(token, name, colon, value)
                    end = value?.end ?: if (colon >= 0) colon + 1 else token.end
                    wantMember = false
                    lastComma = null
                }
                JsonTokenKind.OPEN_OBJECT, JsonTokenKind.OPEN_ARRAY, JsonTokenKind.NUMBER,
                JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL,
                -> {
                    issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_KEY)
                    parseValue()?.let { end = it.end }
                    wantMember = false
                }
                JsonTokenKind.COLON -> {
                    issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_KEY)
                    position++
                }
                JsonTokenKind.UNKNOWN -> {
                    problemToken(token)
                    position++
                }
            }
        }
        closers.removeAt(closers.lastIndex)
        return JsonObjectNode(open.start, end, members, closed).also { containers[open.start] = it }
    }

    /**
     * Whether a string after a colon is really the next member's key: `"a": \n "b": 1`
     * lost its value, and reading `"b"` as that value would misplace everything after it.
     */
    private fun looksLikeNextKey(token: JsonToken): Boolean {
        if (token.kind != JsonTokenKind.STRING && token.kind != JsonTokenKind.WORD) return false
        val after = tokens.getOrNull(position + 1) ?: return false
        if (after.kind != JsonTokenKind.COLON) return false
        // Only when the string sits on a later line than the colon before it.
        val colon = tokens.getOrNull(position - 1) ?: return false
        return source.indexOf('\n', colon.end).let { it in colon.end until token.start }
    }

    private fun parseArray(): JsonArrayNode {
        val open = tokens[position++]
        closers += JsonTokenKind.CLOSE_ARRAY
        val items = ArrayList<JsonNode>()
        var closed = false
        var end = open.end
        var wantItem = true
        var lastComma: JsonToken? = null
        loop@ while (true) {
            val token = peek()
            if (token == null) {
                issue(open.start, open.end, JsonSeverity.ERROR, JsonIssueCode.UNCLOSED_ARRAY)
                break
            }
            when (token.kind) {
                JsonTokenKind.CLOSE_ARRAY -> {
                    position++
                    closed = true
                    end = token.end
                    if (lastComma != null && wantItem) {
                        issue(lastComma.start, lastComma.end, JsonSeverity.ERROR, JsonIssueCode.TRAILING_COMMA)
                    }
                    break@loop
                }
                JsonTokenKind.CLOSE_OBJECT -> {
                    if (closesOuter(JsonTokenKind.CLOSE_OBJECT)) {
                        issue(open.start, open.end, JsonSeverity.ERROR, JsonIssueCode.UNCLOSED_ARRAY)
                        break@loop
                    }
                    problemToken(token)
                    position++
                }
                JsonTokenKind.COMMA -> {
                    if (wantItem) issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_VALUE)
                    position++
                    lastComma = token
                    wantItem = true
                }
                JsonTokenKind.COLON, JsonTokenKind.UNKNOWN -> {
                    if (token.kind == JsonTokenKind.UNKNOWN) {
                        problemToken(token)
                    } else {
                        issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_VALUE)
                    }
                    position++
                }
                else -> {
                    if (!wantItem) issue(token.start, token.end, JsonSeverity.ERROR, JsonIssueCode.EXPECTED_COMMA)
                    val item = parseValue()
                    if (item == null) {
                        position++
                    } else {
                        items += item
                        end = item.end
                    }
                    wantItem = false
                    lastComma = null
                }
            }
        }
        closers.removeAt(closers.lastIndex)
        return JsonArrayNode(open.start, end, items, closed).also { containers[open.start] = it }
    }
}

/** One step of a path into the document: an object's key, or an array's index. */
sealed interface JsonPathStep {
    data class Key(val name: String) : JsonPathStep
    data class Index(val index: Int) : JsonPathStep
}

/** What the caret is about to write. */
enum class JsonSlot {
    /** The document's own value: the text is empty, or the caret is in its only scalar. */
    ROOT,

    /** A key of the object at [JsonLocation.container]. */
    KEY,

    /** The value of the key [JsonLocation.key] of the object at [JsonLocation.container]. */
    VALUE,

    /** An item of the array at [JsonLocation.container]. */
    ITEM,

    /** Past a finished value, where only a comma or a closer fits. */
    AFTER,
}

/**
 * Where the caret is in the document's shape.
 *
 * [path] leads to the value being written: to the object for [JsonSlot.KEY],
 * to the member's value for [JsonSlot.VALUE], to the item for [JsonSlot.ITEM].
 * [token] is the scalar the caret is inside or at the end of, which a chosen
 * suggestion replaces.
 */
data class JsonLocation(
    val slot: JsonSlot,
    val path: List<JsonPathStep>,
    val container: Int,
    val key: String?,
    val token: JsonToken?,
)

/**
 * The caret's place, read from the tokens in front of it rather than from the
 * tree, so it is right in text the parser had to recover from: a scan that
 * keeps a stack of open containers, the key each object is on, and the index
 * each array is at.
 */
fun JsonDocument.locationAt(caret: Int): JsonLocation {
    val at = caret.coerceIn(0, source.length)
    val inside = tokens.firstOrNull { token ->
        token.start < at && when (token.kind) {
            JsonTokenKind.STRING -> at < token.end || token.unterminated && at <= token.end
            JsonTokenKind.NUMBER, JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL, JsonTokenKind.WORD -> at <= token.end
            else -> false
        }
    }
    val frames = ArrayList<Frame>()
    for (token in tokens) {
        if (token === inside || token.start >= at) break
        val top = frames.lastOrNull()
        when (token.kind) {
            JsonTokenKind.OPEN_OBJECT, JsonTokenKind.OPEN_ARRAY -> {
                val path = top?.let { it.path + it.step() }.orEmpty()
                frames += Frame(token.kind == JsonTokenKind.OPEN_OBJECT, token.start, path)
            }
            JsonTokenKind.CLOSE_OBJECT, JsonTokenKind.CLOSE_ARRAY -> {
                if (frames.isNotEmpty()) frames.removeAt(frames.lastIndex)
                frames.lastOrNull()?.done = true
            }
            JsonTokenKind.COLON -> if (top != null && top.isObject) {
                top.wantKey = false
                top.done = false
            }
            JsonTokenKind.COMMA -> if (top != null) {
                if (top.isObject) {
                    top.wantKey = true
                    top.key = null
                } else {
                    top.index++
                }
                top.done = false
            }
            JsonTokenKind.STRING, JsonTokenKind.WORD -> if (top != null && top.isObject && top.wantKey) {
                top.key = if (token.kind == JsonTokenKind.STRING) {
                    JsonTree.decodeString(source, token)
                } else {
                    source.substring(token.start, token.end)
                }
                top.done = true
            } else if (top != null) {
                top.done = true
            }
            JsonTokenKind.NUMBER, JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL -> top?.done = true
            JsonTokenKind.UNKNOWN -> Unit
        }
    }
    val top = frames.lastOrNull()
    return when {
        top == null -> {
            val anythingBefore = tokens.any { it !== inside && it.end <= at }
            JsonLocation(if (anythingBefore) JsonSlot.AFTER else JsonSlot.ROOT, emptyList(), -1, null, inside)
        }
        top.isObject && top.wantKey -> {
            // A key typed without its colon yet is still the key being written.
            val slot = if (top.done && inside == null) JsonSlot.AFTER else JsonSlot.KEY
            JsonLocation(slot, top.path, top.start, null, inside)
        }
        // A string or a word right after a finished value, with no comma between, is
        // the next key being typed: the comma is what the author has not written yet.
        top.isObject && top.done && inside != null && (inside.kind == JsonTokenKind.STRING || inside.kind == JsonTokenKind.WORD) ->
            JsonLocation(JsonSlot.KEY, top.path, top.start, null, inside)
        top.isObject -> {
            val slot = if (top.done && inside == null) JsonSlot.AFTER else JsonSlot.VALUE
            JsonLocation(slot, top.path + JsonPathStep.Key(top.key.orEmpty()), top.start, top.key, inside)
        }
        else -> {
            val slot = if (top.done && inside == null) JsonSlot.AFTER else JsonSlot.ITEM
            JsonLocation(slot, top.path + JsonPathStep.Index(top.index), top.start, null, inside)
        }
    }
}

private class Frame(val isObject: Boolean, val start: Int, val path: List<JsonPathStep>) {
    var wantKey = true
    var key: String? = null
    var index = 0

    /** Whether the key or item this frame is on has already been written. */
    var done = false

    fun step(): JsonPathStep = if (isObject) JsonPathStep.Key(key.orEmpty()) else JsonPathStep.Index(index)
}

/** The node at [path] from the root, following keys and indices through the tree as parsed. */
fun JsonDocument.nodeAt(path: List<JsonPathStep>): JsonNode? {
    var node = root
    for (step in path) {
        node = when (step) {
            is JsonPathStep.Key -> (node as? JsonObjectNode)?.member(step.name)?.value
            is JsonPathStep.Index -> (node as? JsonArrayNode)?.items?.getOrNull(step.index)
        } ?: return null
    }
    return node
}
