package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.panelKindForLayerKey
import java.util.Locale

enum class JsonCompletionKind { PROPERTY, MAP_KEY, VALUE, ENUM, KEYWORD, SNIPPET }

data class JsonCompletionItem(
    val label: String,
    val kind: JsonCompletionKind,
    /** What replaces [JsonCompletions.start] to [JsonCompletions.end]. */
    val insert: String,
    /** Where the caret lands inside [insert]. */
    val caret: Int = insert.length,
    /** The type and default, or what a value means, shown beside the label. */
    val detail: String? = null,
    /** Whether choosing this leaves a blank that has suggestions of its own, so the list opens again there. */
    val reopen: Boolean = false,
)

/** Suggestions for one caret, and the text a chosen one replaces. */
data class JsonCompletions(val start: Int, val end: Int, val items: List<JsonCompletionItem>)

/**
 * What can be typed at the caret of a layout document.
 *
 * Read from the caret's place in the document (a key, a value, a list item) and
 * the schema's shape for that place, so a key object offers the key's own
 * properties, `"type": ` offers the action tags, and `"tool": ` offers the
 * tools. A chosen property writes its key, its colon and a blank of the right
 * kind (`""`, `[]`, `{}`, the boolean that is not the default) with the caret in
 * the blank, and opens the list again when the blank has values to offer, so a
 * whole key can be written by choosing.
 *
 * While typing, a list opens only where it helps: inside a string, on a word
 * typed without quotes, after the colon of a property with a known set of
 * values, and just inside a new object. Anywhere else it waits to be asked.
 */
object LayoutJsonCompletion {

    const val MAX_ITEMS = 60

    fun at(
        document: JsonDocument,
        caret: Int,
        root: LayoutJsonRoot,
        values: JsonValueSource = JsonValueSource.Default,
        explicit: Boolean = false,
    ): JsonCompletions? {
        if (caret < 0 || caret > document.source.length) return null
        return Context(document, caret, root, values, explicit).complete()
    }
}

private class Blank(val text: String, val caret: Int, val reopen: Boolean)

/** An item before ranking: what the typed text is matched against, its place in the natural order, and a boost. */
private class Ranked(val item: JsonCompletionItem, val match: String, val order: Int, val boost: Int = 0, val alsoMatch: String? = null)

private class Context(
    private val document: JsonDocument,
    private val caret: Int,
    private val root: LayoutJsonRoot,
    private val values: JsonValueSource,
    private val explicit: Boolean,
) {
    private val source = document.source
    private val tokens = document.tokens
    private val location = document.locationAt(caret)
    private val token = location.token
    private val rootShape = LayoutJsonSchema.rootShape(document, root)

    private val replaceStart = token?.start ?: caret
    private val replaceEnd = token?.end ?: caret

    /** What has been typed of the name or value, without its opening quote. */
    private val prefix: String = when {
        token == null -> ""
        token.kind == JsonTokenKind.STRING -> source.substring(minOf(token.start + 1, caret), caret)
        else -> source.substring(token.start, caret)
    }

    private val previousCharacter: Char? = run {
        var at = replaceStart - 1
        while (at >= 0 && source[at].isWhitespace()) at--
        source.getOrNull(at)
    }

    fun complete(): JsonCompletions? {
        val typedScalar = token != null && (token.kind == JsonTokenKind.STRING || token.kind == JsonTokenKind.WORD)
        return when (location.slot) {
            JsonSlot.ROOT -> rootItems()
            JsonSlot.KEY -> if (explicit || typedScalar || token == null && previousCharacter == '{') keyItems() else null
            JsonSlot.VALUE -> if (explicit || typedScalar || token == null && previousCharacter == ':') valueItems() else null
            JsonSlot.ITEM -> if (explicit || token?.kind == JsonTokenKind.WORD || token?.kind == JsonTokenKind.STRING) itemItems() else null
            JsonSlot.AFTER -> null
        }
    }

    // -----------------------------------------------------------------------
    // Keys
    // -----------------------------------------------------------------------

    private fun keyItems(): JsonCompletions? {
        if (token != null && token.kind != JsonTokenKind.STRING && token.kind != JsonTokenKind.WORD) return null
        val container = document.containerAt(location.container) as? JsonObjectNode
        val resolved = resolveShape(rootShape, location.path, document) ?: return null
        val present = container?.members.orEmpty().filter { it.key.start != token?.start }.mapTo(HashSet()) { it.name }
        val ranked = ArrayList<Ranked>()
        when (val shape = resolved.shape) {
            is ObjectShape -> shape.properties.forEachIndexed { index, property ->
                if (property.name !in present && !property.hint.hidden) ranked += propertyItem(property, index)
            }
            is VariantShape -> {
                if (shape.discriminator !in present) {
                    ranked += Ranked(
                        JsonCompletionItem(
                            label = shape.discriminator,
                            kind = JsonCompletionKind.PROPERTY,
                            insert = keyInsert(shape.discriminator, Blank("\"\"", 1, reopen = true)).first,
                            caret = keyInsert(shape.discriminator, Blank("\"\"", 1, reopen = true)).second,
                            detail = TagShape(shape).summary(),
                            reopen = !keyHasColon(),
                        ),
                        shape.discriminator,
                        order = -1,
                        boost = 2,
                    )
                }
                shape.variantOf(container)?.properties?.forEachIndexed { index, property ->
                    if (property.name !in present && !property.hint.hidden) ranked += propertyItem(property, index)
                }
            }
            is MapShape -> mapKeyItems(shape, resolved.property, present, ranked)
            else -> return null
        }
        return finish(ranked, leadComma = true)
    }

    private fun propertyItem(property: PropertyShape, order: Int): Ranked {
        val blank = blankFor(property)
        val (insert, caretAt) = keyInsert(property.name, blank)
        val default = property.default?.let { " = $it" }.orEmpty()
        val nullable = if (property.nullable) " | null" else ""
        return Ranked(
            JsonCompletionItem(
                label = property.name,
                kind = JsonCompletionKind.PROPERTY,
                insert = insert,
                caret = caretAt,
                detail = property.shape.summary() + nullable + default,
                reopen = blank.reopen && !keyHasColon(),
            ),
            property.name,
            order,
            // A required property that is missing is what the author most needs next.
            boost = if (property.optional) 0 else 1,
        )
    }

    private fun mapKeyItems(shape: MapShape, property: PropertyShape?, present: Set<String>, ranked: MutableList<Ranked>) {
        val names = when (val key = shape.key) {
            is EnumShape -> key.values
            else -> property?.hint?.keys.orEmpty()
        }
        names.forEachIndexed { index, name ->
            if (name in present) return@forEachIndexed
            val blank = when (val value = shape.value) {
                is ObjectShape -> if (value.typeName == LAYER) {
                    Blank(LAYER_SNIPPET, LAYER_SNIPPET.indexOf("[[") + 2, reopen = true)
                } else {
                    Blank("{}", 1, reopen = true)
                }
                TextShape -> Blank("\"\"", 1, reopen = false)
                else -> blankFor(value, null)
            }
            val (insert, caretAt) = keyInsert(name, blank)
            ranked += Ranked(
                JsonCompletionItem(
                    name,
                    JsonCompletionKind.MAP_KEY,
                    insert,
                    caretAt,
                    shape.value.summary(),
                    blank.reopen && !keyHasColon(),
                ),
                name,
                index,
            )
        }
    }

    /** The key [name] with its colon and [blank], or the key alone when it already has a colon. With the caret. */
    private fun keyInsert(name: String, blank: Blank): Pair<String, Int> {
        val key = quote(name)
        if (keyHasColon()) return key to key.length
        var insert = "$key: ${blank.text}"
        val caretAt = key.length + 2 + blank.caret
        if (nextTokenKind(replaceEnd).let { it == JsonTokenKind.STRING || it == JsonTokenKind.WORD }) insert += ","
        return insert to caretAt
    }

    private fun keyHasColon(): Boolean = token != null && nextTokenKind(token.end) == JsonTokenKind.COLON

    // -----------------------------------------------------------------------
    // Values
    // -----------------------------------------------------------------------

    private fun valueItems(): JsonCompletions? {
        if (token != null && token.kind != JsonTokenKind.STRING && token.kind != JsonTokenKind.WORD && !explicit) return null
        val resolved = resolveShape(rootShape, location.path, document) ?: return null
        val property = resolved.property
        val ranked = ArrayList<Ranked>()
        val finite = when (val shape = resolved.shape) {
            is TagShape -> {
                tagItems(shape.of, ranked)
                true
            }
            is VariantShape -> {
                actionSnippets(shape, ranked)
                true
            }
            is EnumShape -> {
                enumItems(shape, ranked)
                true
            }
            BooleanShape -> {
                booleanItems(property, ranked)
                true
            }
            TextShape -> textItems(property, ranked)
            is NumberShape -> exampleItems(property, ranked)
            is ListShape, is MapShape, is ObjectShape -> {
                if (explicit) blankItem(resolved.shape, ranked)
                false
            }
        }
        if (!finite && ranked.isEmpty()) return null
        // Null clears a nullable property, which leaving it out does as well, so it waits to be asked for.
        if (property?.nullable == true && explicit) {
            ranked += Ranked(JsonCompletionItem("null", JsonCompletionKind.KEYWORD, "null"), "null", order = Int.MAX_VALUE)
        }
        return finish(ranked, leadComma = false)
    }

    private fun tagItems(shape: VariantShape, ranked: MutableList<Ranked>) {
        val container = document.containerAt(location.container) as? JsonObjectNode
        val present = container?.members.orEmpty().mapTo(HashSet()) { it.name }
        orderedTags(shape).forEachIndexed { index, tag ->
            val variant = shape.variants.getValue(tag)
            val missing = fieldsWorthWriting(variant).filter { it.name !in present }
            val text = StringBuilder(quote(tag))
            var caretAt = text.length
            var reopen = false
            missing.forEachIndexed { at, field ->
                val blank = blankFor(field)
                text.append(", ").append(quote(field.name)).append(": ")
                if (at == 0) {
                    caretAt = text.length + blank.caret
                    reopen = blank.reopen
                }
                text.append(blank.text)
            }
            ranked += Ranked(
                JsonCompletionItem(
                    tag,
                    JsonCompletionKind.ENUM,
                    text.toString(),
                    caretAt,
                    missing.joinToString { it.name }.ifEmpty { null },
                    reopen,
                ),
                tag,
                index,
            )
        }
    }

    private fun actionSnippets(shape: VariantShape, ranked: MutableList<Ranked>) {
        orderedTags(shape).forEachIndexed { index, tag ->
            val variant = shape.variants.getValue(tag)
            val required = fieldsWorthWriting(variant)
            val text = StringBuilder("{").append(quote(shape.discriminator)).append(": ").append(quote(tag))
            var caretAt = -1
            var reopen = false
            required.forEachIndexed { at, field ->
                val blank = blankFor(field)
                text.append(", ").append(quote(field.name)).append(": ")
                if (at == 0) {
                    caretAt = text.length + blank.caret
                    reopen = blank.reopen
                }
                text.append(blank.text)
            }
            text.append('}')
            if (caretAt < 0) caretAt = text.length
            ranked += Ranked(
                JsonCompletionItem(
                    tag,
                    JsonCompletionKind.SNIPPET,
                    text.toString(),
                    caretAt,
                    required.joinToString { it.name }.ifEmpty { null },
                    reopen,
                ),
                tag,
                index,
            )
        }
    }

    private fun enumItems(shape: EnumShape, ranked: MutableList<Ranked>) {
        val panel = if (shape.typeName == FIELD_KIND) editedPanel() else null
        shape.values.forEachIndexed { index, value ->
            if (shape.typeName == FIELD_KIND) {
                if (value == UNKNOWN) return@forEachIndexed
                if (panel != null && fieldKindOf(value)?.isOn(panel) != true) return@forEachIndexed
            }
            val label = values.enumLabel(shape.typeName, value)
            val item = JsonCompletionItem(value, JsonCompletionKind.ENUM, quote(value), detail = label)
            ranked += Ranked(item, value, index, alsoMatch = label)
        }
    }

    private fun booleanItems(property: PropertyShape?, ranked: MutableList<Ranked>) {
        // The value that is not the default first: leaving a property at its default is what leaving it out does.
        val first = if (property?.default == "true") "false" else "true"
        val second = if (first == "true") "false" else "true"
        ranked += Ranked(JsonCompletionItem(first, JsonCompletionKind.KEYWORD, first), first, 0)
        // The second is the default, and says so the way a property's detail does.
        val secondItem = JsonCompletionItem(second, JsonCompletionKind.KEYWORD, second, detail = property?.default?.let { "= $it" })
        ranked += Ranked(secondItem, second, 1)
    }

    /** True when the property has a known set of values, so a list is worth opening even with nothing typed. */
    private fun textItems(property: PropertyShape?, ranked: MutableList<Ranked>): Boolean {
        val hint = property?.hint ?: return false
        var order = 0
        hint.domain?.let { domain ->
            for (value in values.values(domain)) {
                val item = JsonCompletionItem(value.value, JsonCompletionKind.VALUE, quote(value.value), detail = value.label)
                ranked += Ranked(item, value.value, order++, alsoMatch = value.label)
            }
        }
        for (example in hint.examples) {
            val bare = example.value.trim('"')
            ranked += Ranked(JsonCompletionItem(bare, JsonCompletionKind.VALUE, example.value, detail = example.note), bare, order++)
        }
        return ranked.isNotEmpty()
    }

    private fun exampleItems(property: PropertyShape?, ranked: MutableList<Ranked>): Boolean {
        val examples = property?.hint?.examples.orEmpty()
        examples.forEachIndexed { index, example ->
            val item = JsonCompletionItem(example.value, JsonCompletionKind.VALUE, example.value, detail = example.note)
            ranked += Ranked(item, example.value, index, alsoMatch = example.note)
        }
        return examples.isNotEmpty()
    }

    private fun blankItem(shape: JsonShape, ranked: MutableList<Ranked>) {
        val blank = blankFor(shape, null)
        val item = JsonCompletionItem(blank.text, JsonCompletionKind.SNIPPET, blank.text, blank.caret, shape.summary(), blank.reopen)
        ranked += Ranked(item, blank.text, 0)
    }

    // -----------------------------------------------------------------------
    // List items and the root
    // -----------------------------------------------------------------------

    private fun itemItems(): JsonCompletions? {
        val resolved = resolveShape(rootShape, location.path, document) ?: return null
        val ranked = ArrayList<Ranked>()
        when (val shape = resolved.shape) {
            is ObjectShape -> when (shape.typeName) {
                KEY -> {
                    val inPanel = root == LayoutJsonRoot.PANEL || editedPanel() != null
                    snippets(if (inPanel) PANEL_KEY_SNIPPETS + KEY_SNIPPETS else KEY_SNIPPETS, ranked)
                }
                ALTERNATE -> snippets(ALTERNATE_SNIPPETS, ranked)
                else -> blankItem(shape, ranked)
            }
            is ListShape -> if ((shape.item as? ObjectShape)?.typeName == KEY) snippets(ROW_SNIPPETS, ranked) else blankItem(shape, ranked)
            is NumberShape -> exampleItems(resolved.property, ranked)
            is EnumShape -> enumItems(shape, ranked)
            TextShape -> if (explicit) blankItem(shape, ranked)
            else -> Unit
        }
        return finish(ranked, leadComma = true, itemCommas = true)
    }

    private fun snippets(list: List<Snippet>, ranked: MutableList<Ranked>) {
        list.forEachIndexed { index, snippet ->
            val caretAt = snippet.template.indexOf('|').let { if (it < 0) snippet.template.length else it }
            val text = snippet.template.replace("|", "")
            ranked += Ranked(
                JsonCompletionItem(snippet.label, JsonCompletionKind.SNIPPET, text, caretAt, text, snippet.reopen),
                snippet.match,
                index,
            )
        }
    }

    private fun rootItems(): JsonCompletions? {
        if (tokens.any { it !== token }) return null
        if (!explicit && token == null) return null
        val template = if (root == LayoutJsonRoot.PANEL) PANEL_SKELETON else LAYOUT_SKELETON
        val caretAt = template.indexOf('|')
        val text = template.replace("|", "")
        val label = if (root == LayoutJsonRoot.PANEL) "PanelLayoutSpec" else "LayoutSpec"
        return JsonCompletions(
            replaceStart,
            replaceEnd,
            listOf(
                JsonCompletionItem(
                    label = label,
                    kind = JsonCompletionKind.SNIPPET,
                    insert = text,
                    caret = caretAt,
                    detail = rootShape.summary(),
                    reopen = root == LayoutJsonRoot.PANEL,
                ),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    /**
     * Ranks by how well the typed text matches, then by what the author needs,
     * then in the order the model declares; drops what does not match. A list
     * whose only item is exactly what is typed is no help, so it stays closed.
     */
    private fun finish(ranked: List<Ranked>, leadComma: Boolean, itemCommas: Boolean = false): JsonCompletions? {
        val scored = ranked.mapNotNull { entry ->
            val score = maxOf(matchScore(entry.match, prefix) ?: -1, (entry.alsoMatch?.let { matchScore(it, prefix) } ?: -1) - 1)
            if (score < 0) null else entry to score
        }
        if (scored.isEmpty()) return null
        if (!explicit && scored.size == 1 && scored[0].first.match == prefix && prefix.isNotEmpty()) return null
        var start = replaceStart
        var lead = ""
        val previous = previousTokenBefore(replaceStart)
        if (leadComma && previous != null && endsValue(previous.kind)) {
            lead = "," + source.substring(previous.end, replaceStart)
            start = previous.end
        }
        val trail = itemCommas && nextTokenKind(replaceEnd).let { it != null && startsValue(it) }
        val items = scored
            .sortedWith(compareByDescending<Pair<Ranked, Int>> { it.second }.thenByDescending { it.first.boost }.thenBy { it.first.order })
            .take(LayoutJsonCompletion.MAX_ITEMS)
            .map { (entry, _) ->
                val item = entry.item
                item.copy(
                    insert = lead + item.insert + if (trail) "," else "",
                    caret = lead.length + item.caret,
                )
            }
        return JsonCompletions(start, replaceEnd, items)
    }

    private fun blankFor(property: PropertyShape): Blank = when (property.shape) {
        TextShape -> Blank("\"\"", 1, reopen = property.hint.domain != null || property.hint.examples.isNotEmpty())
        is NumberShape -> Blank("", 0, reopen = property.hint.examples.isNotEmpty())
        BooleanShape -> (if (property.default == "true") "false" else "true").let { Blank(it, it.length, reopen = false) }
        else -> blankFor(property.shape, property)
    }

    private fun blankFor(shape: JsonShape, property: PropertyShape?): Blank = when (shape) {
        TextShape -> Blank("\"\"", 1, reopen = false)
        is EnumShape, is TagShape -> Blank("\"\"", 1, reopen = true)
        is NumberShape -> Blank("", 0, reopen = false)
        BooleanShape -> Blank("true", 4, reopen = false)
        is ListShape -> when (shape.item) {
            is ObjectShape, is ListShape, is VariantShape -> Blank("[]", 1, reopen = true)
            TextShape -> Blank("[\"\"]", 2, reopen = false)
            is NumberShape -> Blank("[]", 1, reopen = property?.hint?.examples?.isNotEmpty() == true)
            else -> Blank("[]", 1, reopen = false)
        }
        is MapShape, is ObjectShape -> Blank("{}", 1, reopen = true)
        is VariantShape -> ACTION_BLANK.let { Blank(it, it.indexOf("\"\"") + 1, reopen = true) }
    }

    /**
     * The fields a chosen action is written with: the ones it cannot do without,
     * or, for an action whose one field has a default, that field anyway. A tool
     * key that says no tool is not a key anyone meant to write, even though the
     * decoder can fill the gap.
     */
    private fun fieldsWorthWriting(variant: ObjectShape): List<PropertyShape> {
        val required = variant.properties.filter { !it.optional }
        return required.ifEmpty { variant.properties.filterNot { it.hint.hidden }.take(1) }
    }

    /** The tags in the order an author reaches for them, the ones a layout never writes left out. */
    private fun orderedTags(shape: VariantShape): List<String> {
        val preferred = if (root == LayoutJsonRoot.PANEL || editedPanel() != null) PANEL_TAG_ORDER else TAG_ORDER
        val known = shape.variants.keys.filterNot { it in LayoutJsonHints.hiddenTags }
        return preferred.filter { it in known } + known.filterNot { it in preferred }
    }

    /** The panel being written: the panel layout's own, or the typing layout's panel layer the caret is in. */
    private fun editedPanel(): PanelKind? {
        if (root == LayoutJsonRoot.PANEL) {
            val name = ((document.root as? JsonObjectNode)?.member("panel")?.value as? JsonScalarNode)?.text ?: return null
            return PanelKind.entries.getOrNull(PanelKind.serializer().descriptor.getElementIndex(name).coerceAtLeast(-1))
        }
        return location.path.firstNotNullOfOrNull { step -> (step as? JsonPathStep.Key)?.name?.let(::panelKindForLayerKey) }
    }

    private fun fieldKindOf(value: String): PanelFieldKind? =
        PanelFieldKind.entries.getOrNull(PanelFieldKind.serializer().descriptor.getElementIndex(value))

    private fun nextTokenKind(offset: Int): JsonTokenKind? = tokens.firstOrNull { it.start >= offset }?.kind

    private fun previousTokenBefore(offset: Int): JsonToken? = tokens.lastOrNull { it.end <= offset }

    private fun endsValue(kind: JsonTokenKind) = when (kind) {
        JsonTokenKind.STRING, JsonTokenKind.NUMBER, JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL,
        JsonTokenKind.CLOSE_OBJECT, JsonTokenKind.CLOSE_ARRAY,
        -> true
        else -> false
    }

    private fun startsValue(kind: JsonTokenKind) = when (kind) {
        JsonTokenKind.OPEN_OBJECT, JsonTokenKind.OPEN_ARRAY, JsonTokenKind.STRING, JsonTokenKind.NUMBER,
        JsonTokenKind.TRUE, JsonTokenKind.FALSE, JsonTokenKind.NULL, JsonTokenKind.WORD,
        -> true
        else -> false
    }

    private fun quote(text: String): String = buildString {
        append('"')
        for (character in text) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(character)
            }
        }
        append('"')
    }

    private class Snippet(val label: String, val match: String, val template: String, val reopen: Boolean = false)

    private companion object {
        const val KEY = "Key"
        const val ALTERNATE = "KeyAlternate"
        const val LAYER = "LayerSpec"
        const val FIELD_KIND = "PanelFieldKind"
        const val UNKNOWN = "unknown"
        const val ACTION_BLANK = "{\"type\": \"\"}"
        const val LAYER_SNIPPET = "{\"rows\": [[]]}"

        val TAG_ORDER = listOf(
            "shift", "delete", "enter", "space", "symbols", "letters", "language_switch", "emoji", "tool", "toolbox", "send_key", "mod",
            "edit", "edit_sequence", "newline", "caps_lock", "forward_delete", "layout", "fn", "none", "input_method_picker", "broadcast",
            "text", "kana_variant", "add_stroke", "double_consonant", "reading_column", "han_script", "braille_dot", "morse_dot", "morse_dash", "keyman_key", "field",
        )
        val PANEL_TAG_ORDER = listOf("field", "edit", "tool", "letters", "delete", "space", "enter", "send_key", "mod", "none", "text")

        val KEY_SNIPPETS = listOf(
            Snippet("{label}", "label", "{\"label\": \"|\"}"),
            Snippet("{label, longPress}", "longPress", "{\"label\": \"|\", \"longPress\": [\"\"]}"),
            Snippet("{shift}", "shift", "{\"label\": \"⇧\", \"action\": {\"type\": \"shift\"}, \"width\": 1.5}|"),
            Snippet("{delete}", "delete", "{\"label\": \"⌫\", \"action\": {\"type\": \"delete\"}, \"width\": 1.5}|"),
            Snippet("{space}", "space", "{\"label\": \" \", \"action\": {\"type\": \"space\"}, \"width\": 4}|"),
            Snippet("{enter}", "enter", "{\"label\": \"⏎\", \"action\": {\"type\": \"enter\"}, \"width\": 1.5}|"),
            Snippet("{symbols}", "symbols", "{\"label\": \"?123\", \"action\": {\"type\": \"symbols\"}, \"width\": 1.5}|"),
            Snippet("{letters}", "letters", "{\"label\": \"ABC\", \"action\": {\"type\": \"letters\"}, \"width\": 1.5}|"),
            Snippet("{language_switch}", "language_switch", "{\"label\": \"🌐\", \"action\": {\"type\": \"language_switch\"}}|"),
            Snippet("{emoji}", "emoji", "{\"label\": \"☺\", \"action\": {\"type\": \"emoji\"}}|"),
            Snippet("{tool}", "tool", "{\"label\": \"\", \"action\": {\"type\": \"tool\", \"tool\": \"|\"}}", reopen = true),
            Snippet("{send_key: Tab}", "tab send_key", "{\"label\": \"⇥\", \"action\": {\"type\": \"send_key\", \"keyCode\": 61}}|"),
            Snippet("{mod: CTRL}", "ctrl mod", "{\"label\": \"Ctrl\", \"action\": {\"type\": \"mod\", \"key\": \"CTRL\"}}|"),
            Snippet("{edit: LEFT}", "arrow edit", "{\"label\": \"\", \"action\": {\"type\": \"edit\", \"op\": \"|\"}}", reopen = true),
            Snippet(
                "{flick}",
                "flick",
                "{\"label\": \"|\", \"flick\": {\"left\": \"\", \"up\": \"\", \"right\": \"\", \"down\": \"\", " +
                    "\"up_left\": \"\", \"up_right\": \"\", \"down_left\": \"\", \"down_right\": \"\"}}",
            ),
            Snippet("{none}", "none gap", "{\"label\": \"\", \"action\": {\"type\": \"none\"}}|"),
        )
        val PANEL_KEY_SNIPPETS = listOf(
            Snippet(
                "{field}",
                "field",
                "{\"label\": \"\", \"action\": {\"type\": \"field\", \"kind\": \"|\"}, \"width\": 10}",
                reopen = true,
            ),
        )
        val ALTERNATE_SNIPPETS = listOf(
            Snippet("{action}", "action", "{\"action\": {\"type\": \"|\"}}", reopen = true),
            Snippet("{action: tool}", "tool", "{\"action\": {\"type\": \"tool\", \"tool\": \"|\"}}", reopen = true),
        )
        val ROW_SNIPPETS = listOf(Snippet("[{label}]", "row", "[{\"label\": \"|\"}]"))

        const val LAYOUT_SKELETON = "{\n  \"id\": \"\",\n  \"name\": \"|\",\n  \"langId\": \"en\",\n" +
            "  \"layers\": {\n    \"letters\": {\"rows\": [[]]}\n  }\n}"
        const val PANEL_SKELETON = "{\"panel\": \"|\", \"grid\": {\"rows\": [[]]}}"
    }
}

/**
 * How well [candidate] matches what was typed: a prefix beats the capitals of a
 * camel-case name (`lP` for `longPress`), which beats a run inside the name,
 * which beats the letters in order with gaps. Null for no match at all.
 */
internal fun matchScore(candidate: String, typed: String): Int? {
    if (typed.isEmpty()) return 1
    val lowerCandidate = candidate.lowercase(Locale.ROOT)
    val lowerTyped = typed.lowercase(Locale.ROOT)
    return when {
        candidate.startsWith(typed) -> 6
        lowerCandidate.startsWith(lowerTyped) -> 5
        humps(candidate).startsWith(lowerTyped) -> 4
        lowerCandidate.split(' ', '_').any { it.startsWith(lowerTyped) } -> 4
        lowerCandidate.contains(lowerTyped) -> 3
        inOrder(lowerCandidate, lowerTyped) -> 2
        else -> null
    }
}

/** The first letter and every capital or letter after an underscore: `lp` for `longPress`, `ls` for `language_switch`. */
private fun humps(name: String): String = buildString {
    name.forEachIndexed { index, character ->
        if (index == 0 || character.isUpperCase() || name.getOrNull(index - 1) == '_') append(character.lowercaseChar())
    }
}

private fun inOrder(candidate: String, typed: String): Boolean {
    var at = 0
    for (character in typed) {
        at = candidate.indexOf(character, at)
        if (at < 0) return false
        at++
    }
    return true
}
