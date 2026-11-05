package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutFinding
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutCodec
import com.wasimaster.wmkeyboard.core.layout.validateLayout
import com.wasimaster.wmkeyboard.core.layout.validatePanelLayout
import java.util.Locale

/** A finding of the layout's own checks, placed on the characters it is about. */
data class JsonFinding(val start: Int, val end: Int, val finding: LayoutFinding)

/**
 * Everything wrong with a layout document, each problem on its own characters.
 *
 * Two passes. The shape pass walks the tree against [LayoutJsonSchema] and says
 * what the decoder would reject, quietly drop or quietly change: a property the
 * model does not have, a value of the wrong type, an action or an enum value this
 * build does not know. Its severities follow the decoder: what makes Apply fail
 * is an error, and what Apply survives by ignoring or coercing is a warning.
 *
 * The layout pass, [findings], runs only on text that decodes, and is the grid
 * editor's own `validateLayout`, so the two editors never disagree about what a
 * layout lacks.
 */
object LayoutJsonCheck {

    fun check(document: JsonDocument, root: LayoutJsonRoot, values: JsonValueSource = JsonValueSource.Default): List<JsonIssue> {
        val issues = ArrayList(document.syntax)
        val node = document.root ?: return issues
        val shape = LayoutJsonSchema.rootShape(document, root)
        Walker(document, root, values, issues).value(node, shape, property = null, name = null, coercible = false)
        return issues.sortedWith(compareBy({ it.start }, { it.severity }))
    }

    /**
     * The layout's own checks, for text that decodes, placed on the layer they
     * are about or on the document's first bracket. Empty for text that does
     * not decode: the shape pass has already said why.
     */
    fun findings(document: JsonDocument, root: LayoutJsonRoot): List<JsonFinding> {
        if (!document.parses) return emptyList()
        val top = document.root as? JsonObjectNode ?: return emptyList()
        val whole = JsonSpan(top.start, top.start + 1)
        return when (root) {
            LayoutJsonRoot.PANEL -> {
                val spec = PanelLayoutCodec.decode(document.source) ?: return emptyList()
                val grid = top.member("grid")?.key?.let { JsonSpan(it.start, it.end) } ?: whole
                validatePanelLayout(spec).map { JsonFinding(grid.start, grid.end, it) }
            }
            // A row has no layout-level checks: the shape pass covers every key.
            LayoutJsonRoot.ROW -> emptyList()
            LayoutJsonRoot.LAYOUT -> {
                val wrapped = top.member("format") != null
                val spec = (if (wrapped) LayoutFile.unwrap(document.source) else LayoutCodec.decode(document.source)) ?: return emptyList()
                val layoutNode = if (wrapped) top.member("layout")?.value as? JsonObjectNode ?: top else top
                val layers = layoutNode.member("layers")?.value as? JsonObjectNode
                validateLayout(spec).map { finding ->
                    val key = finding.layer?.let { layers?.member(it.key)?.key }
                    if (key != null) JsonFinding(key.start, key.end, finding) else JsonFinding(whole.start, whole.end, finding)
                }
            }
        }
    }

    /** How a layout finding ranks against a JSON problem. */
    fun severityOf(finding: LayoutFinding): JsonSeverity =
        if (finding.severity == LayoutSeverity.BLOCKING) JsonSeverity.ERROR else JsonSeverity.WARNING
}

/** A stretch of the document. */
data class JsonSpan(val start: Int, val end: Int)

private class Walker(
    private val document: JsonDocument,
    private val root: LayoutJsonRoot,
    private val values: JsonValueSource,
    private val issues: MutableList<JsonIssue>,
) {

    private fun issue(start: Int, end: Int, severity: JsonSeverity, code: JsonIssueCode, arg1: String? = null, arg2: String? = null) {
        issues += JsonIssue(start, end, severity, code, arg1, arg2)
    }

    private fun issue(node: JsonNode, severity: JsonSeverity, code: JsonIssueCode, arg1: String? = null, arg2: String? = null) {
        // A container is marked at its opening bracket, not over every line it spans.
        val end = if (node is JsonScalarNode) node.end else node.start + 1
        issue(node.start, end, severity, code, arg1, arg2)
    }

    /**
     * [coercible] is true for a property's own value, where the decoder falls
     * back to the default for a null or an unknown enum value; a list item or a
     * map value has no default to fall back to.
     */
    fun value(node: JsonNode, shape: JsonShape, property: PropertyShape?, name: String?, coercible: Boolean) {
        if (node is JsonScalarNode && node.kind == JsonTokenKind.WORD) return // The syntax pass has it.
        if (node is JsonScalarNode && node.kind == JsonTokenKind.NULL) {
            when {
                property?.nullable == true && coercible -> Unit
                property?.optional == true && coercible -> issue(node, JsonSeverity.WARNING, JsonIssueCode.NULL_BECOMES_DEFAULT, name)
                else -> issue(node, JsonSeverity.ERROR, JsonIssueCode.NULL_NOT_ALLOWED, name)
            }
            return
        }
        when (shape) {
            is ObjectShape -> objectValue(node, shape, name)
            is VariantShape -> variantValue(node, shape, name)
            is TagShape -> Unit
            is ListShape -> {
                val array = node as? JsonArrayNode ?: return wrong(node, JsonIssueCode.EXPECTED_LIST, name)
                array.items.forEach { value(it, shape.item, property, name, coercible = false) }
            }
            is MapShape -> mapValue(node, shape, property, name)
            is EnumShape -> enumValue(node, shape, name, coercible && property?.optional == true)
            TextShape -> textValue(node, property, name)
            is NumberShape -> numberValue(node, shape, property, name)
            BooleanShape -> if (!(node is JsonScalarNode && (node.kind == JsonTokenKind.TRUE || node.kind == JsonTokenKind.FALSE))) {
                wrong(node, JsonIssueCode.EXPECTED_BOOLEAN, name)
            }
        }
    }

    private fun wrong(node: JsonNode, code: JsonIssueCode, name: String?) {
        issue(node, JsonSeverity.ERROR, code, name.orEmpty())
    }

    private fun objectValue(node: JsonNode, shape: ObjectShape, name: String?) {
        val obj = node as? JsonObjectNode ?: return wrong(node, JsonIssueCode.EXPECTED_OBJECT, name)
        members(obj, shape, allowed = null)
    }

    /** The members of [obj] checked against [shape]; [allowed] is an extra name that is not a property, the discriminator. */
    private fun members(obj: JsonObjectNode, shape: ObjectShape, allowed: String?) {
        val seen = HashSet<String>()
        for (member in obj.members) {
            if (!seen.add(member.name)) {
                issue(member.key.start, member.key.end, JsonSeverity.WARNING, JsonIssueCode.DUPLICATE_PROPERTY, member.name)
            }
            if (member.name == allowed) continue
            val property = shape.property(member.name)
            if (property == null) {
                val near = nearestName(member.name, shape.properties.filterNot { it.hint.hidden }.map { it.name })
                if (near != null) {
                    issue(member.key.start, member.key.end, JsonSeverity.WARNING, JsonIssueCode.PROPERTY_TYPO, member.name, near)
                } else {
                    issue(member.key.start, member.key.end, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_PROPERTY, member.name)
                }
                continue
            }
            if (property.docKey in SET_BY_KEYBOARD) {
                issue(member.key.start, member.key.end, JsonSeverity.INFO, JsonIssueCode.SET_BY_KEYBOARD, member.name)
            }
            member.value?.let { value(it, property.shape, property, property.name, coercible = true) }
        }
        if (obj.closed) {
            for (property in shape.properties) {
                if (!property.optional && obj.member(property.name) == null) {
                    issue(obj.start, obj.start + 1, JsonSeverity.ERROR, JsonIssueCode.MISSING_PROPERTY, property.name)
                }
            }
        }
    }

    private fun variantValue(node: JsonNode, shape: VariantShape, name: String?) {
        val obj = node as? JsonObjectNode ?: return wrong(node, JsonIssueCode.EXPECTED_OBJECT, name)
        val typeMember = obj.member(shape.discriminator)
        if (typeMember == null) {
            if (obj.closed) issue(obj.start, obj.start + 1, JsonSeverity.ERROR, JsonIssueCode.MISSING_PROPERTY, shape.discriminator)
            return
        }
        val tagNode = typeMember.value as? JsonScalarNode
        if (tagNode == null || tagNode.kind != JsonTokenKind.STRING) {
            val written = typeMember.value
            if (written != null && (written as? JsonScalarNode)?.kind != JsonTokenKind.WORD) {
                wrong(written, JsonIssueCode.EXPECTED_TEXT, shape.discriminator)
            }
            return
        }
        val tag = tagNode.text
        val variant = shape.variants[tag]
        if (variant == null || tag in LayoutJsonHints.hiddenTags) {
            val near = nearestName(tag, shape.variants.keys.filterNot { it in LayoutJsonHints.hiddenTags })
            if (near != null && near != tag) {
                issue(tagNode, JsonSeverity.WARNING, JsonIssueCode.ACTION_TYPO, tag, near)
            } else {
                issue(tagNode, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_ACTION, tag)
            }
            return
        }
        if (tag == FIELD_TAG && root == LayoutJsonRoot.LAYOUT && !insidePanelLayer(obj)) {
            issue(tagNode, JsonSeverity.WARNING, JsonIssueCode.FIELD_OUTSIDE_PANEL)
        }
        members(obj, variant, allowed = shape.discriminator)
    }

    /** Whether [node] sits inside one of a typing layout's own panel layers, where a field cell belongs. */
    private fun insidePanelLayer(node: JsonNode): Boolean {
        val layers = when (val top = document.root) {
            is JsonObjectNode -> ((top.member("layout")?.value as? JsonObjectNode) ?: top).member("layers")?.value as? JsonObjectNode
            else -> null
        } ?: return false
        return layers.members.any { member ->
            member.name.startsWith(PANEL_LAYER_PREFIX) && member.value.let { it != null && node.start >= it.start && node.end <= it.end }
        }
    }

    private fun mapValue(node: JsonNode, shape: MapShape, property: PropertyShape?, name: String?) {
        val obj = node as? JsonObjectNode ?: return wrong(node, JsonIssueCode.EXPECTED_OBJECT, name)
        val seen = HashSet<String>()
        for (member in obj.members) {
            if (!seen.add(member.name)) {
                issue(member.key.start, member.key.end, JsonSeverity.WARNING, JsonIssueCode.DUPLICATE_PROPERTY, member.name)
            }
            when (val key = shape.key) {
                is EnumShape -> if (member.name !in key.values) {
                    val near = nearestName(member.name, key.values)
                    issue(member.key.start, member.key.end, JsonSeverity.ERROR, JsonIssueCode.UNKNOWN_MAP_KEY, member.name, near)
                }
                else -> {
                    val keys = property?.hint?.keys.orEmpty()
                    if (keys.isNotEmpty() && member.name !in keys) {
                        val near = nearestName(member.name, keys)
                        val code = if (near != null) JsonIssueCode.LAYER_TYPO else JsonIssueCode.UNKNOWN_LAYER
                        issue(member.key.start, member.key.end, JsonSeverity.WARNING, code, member.name, near)
                    }
                }
            }
            member.value?.let { value(it, shape.value, property, member.name, coercible = false) }
        }
    }

    private fun enumValue(node: JsonNode, shape: EnumShape, name: String?, coercible: Boolean) {
        val scalar = node as? JsonScalarNode
        if (scalar == null || scalar.kind != JsonTokenKind.STRING) return wrong(node, JsonIssueCode.EXPECTED_TEXT, name)
        if (scalar.text in shape.values) return
        val near = nearestName(scalar.text, shape.values)
        when {
            !coercible -> issue(scalar, JsonSeverity.ERROR, JsonIssueCode.UNKNOWN_VALUE_REQUIRED, scalar.text, name)
            near != null -> issue(scalar, JsonSeverity.WARNING, JsonIssueCode.VALUE_TYPO, scalar.text, near)
            else -> issue(scalar, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_VALUE, scalar.text, name)
        }
    }

    private fun textValue(node: JsonNode, property: PropertyShape?, name: String?) {
        val scalar = node as? JsonScalarNode
        if (scalar == null || scalar.kind != JsonTokenKind.STRING) return wrong(node, JsonIssueCode.EXPECTED_TEXT, name)
        if (property?.docKey == "LayoutFile.format" && scalar.text != LayoutJsonSchema.LAYOUT_FILE_FORMAT) {
            issue(scalar, JsonSeverity.ERROR, JsonIssueCode.UNKNOWN_VALUE_REQUIRED, scalar.text, name)
            return
        }
        val domain = property?.hint?.domain ?: return
        if (scalar.text.isEmpty()) return
        if (values.knows(domain, scalar.text) != false) return
        when (domain) {
            JsonValueDomain.LANGUAGE -> issue(scalar, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_LANGUAGE, scalar.text)
            JsonValueDomain.ICON -> issue(scalar, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_ICON, scalar.text)
            JsonValueDomain.THEME -> issue(scalar, JsonSeverity.INFO, JsonIssueCode.UNKNOWN_THEME, scalar.text)
            JsonValueDomain.SECONDARY_LAYOUT -> issue(scalar, JsonSeverity.WARNING, JsonIssueCode.UNKNOWN_SECONDARY_LAYOUT, scalar.text)
            JsonValueDomain.FONT -> Unit
        }
    }

    private fun numberValue(node: JsonNode, shape: NumberShape, property: PropertyShape?, name: String?) {
        val scalar = node as? JsonScalarNode
        if (scalar == null || scalar.kind != JsonTokenKind.NUMBER) {
            return wrong(node, if (shape.whole) JsonIssueCode.EXPECTED_WHOLE_NUMBER else JsonIssueCode.EXPECTED_NUMBER, name)
        }
        val number = scalar.text.toDoubleOrNull()
        if (number == null || !JSON_NUMBER.matches(scalar.text)) {
            return wrong(node, if (shape.whole) JsonIssueCode.EXPECTED_WHOLE_NUMBER else JsonIssueCode.EXPECTED_NUMBER, name)
        }
        if (shape.whole && !WHOLE_NUMBER.matches(scalar.text)) return wrong(node, JsonIssueCode.EXPECTED_WHOLE_NUMBER, name)
        val range = property?.hint?.range ?: return
        // A panel's row heights are shares of the panel's height rather than key
        // heights, so a flex row of 3 is ordinary there and nothing bounds it.
        if (property.docKey == ROW_HEIGHTS && (root == LayoutJsonRoot.PANEL || insidePanelLayer(node))) return
        if (number < range.start || number > range.endInclusive) {
            issue(scalar, JsonSeverity.WARNING, JsonIssueCode.OUT_OF_RANGE, name, "${plain(range.start)}..${plain(range.endInclusive)}")
        }
    }

    private companion object {
        val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val WHOLE_NUMBER = Regex("-?(0|[1-9][0-9]*)")
        const val FIELD_TAG = "field"
        const val PANEL_LAYER_PREFIX = "panel_"
        const val ROW_HEIGHTS = "LayerSpec.rowHeights"

        /** Properties the keyboard writes itself; one written by hand is harmless and says nothing. */
        val SET_BY_KEYBOARD = setOf("Key.actionAlternatesFirst", "action:braille_dot.release")

        fun plain(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()
    }
}

/**
 * The one of [candidates] that [name] is most likely a slip for: the same name
 * in other capitals, or one within two edits of a name at least four letters
 * long. Null when nothing is that close, or when two are equally close.
 */
fun nearestName(name: String, candidates: Collection<String>): String? {
    if (name.isEmpty()) return null
    candidates.firstOrNull { it.equals(name, ignoreCase = true) && it != name }?.let { return it }
    if (name.length < MIN_TYPO_LENGTH) return null
    val lower = name.lowercase(Locale.ROOT)
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    var tie = false
    for (candidate in candidates) {
        val distance = editDistance(lower, candidate.lowercase(Locale.ROOT), MAX_TYPO_EDITS)
        if (distance < bestDistance) {
            best = candidate
            bestDistance = distance
            tie = false
        } else if (distance == bestDistance) {
            tie = true
        }
    }
    return best.takeIf { bestDistance in 1..MAX_TYPO_EDITS && !tie }
}

private const val MIN_TYPO_LENGTH = 4
private const val MAX_TYPO_EDITS = 2

/** Levenshtein distance with adjacent swaps counted as one edit, giving up above [limit]. */
private fun editDistance(a: String, b: String, limit: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
    val rows = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) rows[i][0] = i
    for (j in 0..b.length) rows[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            var best = minOf(rows[i - 1][j] + 1, rows[i][j - 1] + 1, rows[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, rows[i - 2][j - 2] + 1)
            rows[i][j] = best
        }
    }
    return rows[a.length][b.length]
}
