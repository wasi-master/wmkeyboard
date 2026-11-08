@file:OptIn(ExperimentalSerializationApi::class)

package com.wasimaster.wmkeyboard.core.layout.json

import com.wasimaster.wmkeyboard.core.layout.AlternateColumnsRange
import com.wasimaster.wmkeyboard.core.layout.CurrentLayoutSpecVersion
import com.wasimaster.wmkeyboard.core.layout.CurrentPanelLayoutVersion
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.KeyHintScaleRange
import com.wasimaster.wmkeyboard.core.layout.KeyLabelScaleRange
import com.wasimaster.wmkeyboard.core.layout.InputMethodBinding
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.layout.LayoutAppearance
import com.wasimaster.wmkeyboard.core.layout.LayoutFontScaleRange
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.MaxKeySpan
import com.wasimaster.wmkeyboard.core.layout.MaxKeyWidth
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.PanelLayoutSpec
import com.wasimaster.wmkeyboard.core.layout.layoutJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What a layout document may hold, for the editor's suggestions, checks and
 * doc strip.
 *
 * Built from the serial descriptors of the model classes themselves rather than
 * written out by hand: the property names, which ones may be left out, every
 * enum value and every action tag come from the same code the decoder runs, so
 * a field added to [Key] is suggested the day it lands and a renamed one cannot
 * linger in a list here. What a descriptor cannot say (the documentation, the
 * values worth offering, the bounds the repair pass holds a value to) is the
 * overlay in [LayoutJsonHints], which `LayoutJsonSchemaDriftTest` holds to the
 * descriptors in both directions.
 */
sealed interface JsonShape

/** An object with named properties. [typeName] is the model class, or `action:<tag>` for one action. */
class ObjectShape(val typeName: String, val properties: List<PropertyShape>) : JsonShape {
    private val byName = properties.associateBy { it.name }

    fun property(name: String): PropertyShape? = byName[name]

    override fun toString(): String = typeName
}

/**
 * One property of an object. [optional] is true when the decoder has a default
 * for it; [nullable] when `null` is a value it takes.
 */
class PropertyShape(
    val owner: String,
    val name: String,
    val shape: JsonShape,
    val optional: Boolean,
    val nullable: Boolean,
) {
    /** The key the documentation and the hints are filed under: `Key.width`, `action:tool.tool`. */
    val docKey: String get() = "$owner.$name"

    val hint: PropertyHint get() = LayoutJsonHints.of(docKey)

    /** The value the decoder uses when the property is left out, as JSON, or null for a required one. */
    val default: String? get() = if (optional) LayoutJsonSchema.defaultOf(docKey) else null

    override fun toString(): String = docKey
}

/** An object whose other properties depend on its [discriminator]: a key's action. */
class VariantShape(val typeName: String, val discriminator: String, val variants: Map<String, ObjectShape>) : JsonShape {
    /** The variant [node] names by its discriminator, or null when it names none this build knows. */
    fun variantOf(node: JsonObjectNode?): ObjectShape? {
        val tag = (node?.member(discriminator)?.value as? JsonScalarNode)?.takeIf { it.kind == JsonTokenKind.STRING } ?: return null
        return variants[tag.text]
    }
}

/** The discriminator's own value: one of [of]'s tags. */
class TagShape(val of: VariantShape) : JsonShape

class ListShape(val item: JsonShape) : JsonShape

/** An object used as a map. [key] is [TextShape] or an [EnumShape]. */
class MapShape(val key: JsonShape, val value: JsonShape) : JsonShape

class EnumShape(val typeName: String, val values: List<String>) : JsonShape

data object TextShape : JsonShape

class NumberShape(val whole: Boolean) : JsonShape

data object BooleanShape : JsonShape

/** Where the values of a free-text property come from, when something on the device knows them. */
enum class JsonValueDomain { LANGUAGE, THEME, FONT, ICON, SECONDARY_LAYOUT }

/** A value worth offering for a property, as JSON, with a few words about it. */
data class JsonValueExample(val value: String, val note: String? = null)

/**
 * What a descriptor cannot say about a property. [hidden] keeps it out of the
 * suggestions without calling it wrong: a field the keyboard sets itself, or one
 * an old build wrote. [range] bounds a number, or each number of a list.
 * [keys] are the keys a map property takes.
 */
class PropertyHint(
    val domain: JsonValueDomain? = null,
    val examples: List<JsonValueExample> = emptyList(),
    val range: ClosedFloatingPointRange<Double>? = null,
    val hidden: Boolean = false,
    val keys: List<String> = emptyList(),
) {
    companion object {
        val None = PropertyHint()
    }
}

/** Which document a screen edits. */
/**
 * What the editor is editing: a whole layout, a panel layout, or one row of
 * keys on its own (the keyboard-wide number and arrow rows, a JSON array of
 * keys in the format a layout's rows use).
 */
enum class LayoutJsonRoot { LAYOUT, PANEL, ROW }

object LayoutJsonSchema {

    const val DISCRIMINATOR = "type"

    /** The format tag an exported layout file carries. */
    const val LAYOUT_FILE_FORMAT = "wmkeyboard-layout"

    private val shapes = HashMap<String, JsonShape>()

    val layout: ObjectShape by lazy { shapeOf(LayoutSpec.serializer().descriptor) as ObjectShape }

    val panel: ObjectShape by lazy { shapeOf(PanelLayoutSpec.serializer().descriptor) as ObjectShape }

    val key: ObjectShape by lazy { shapeOf(Key.serializer().descriptor) as ObjectShape }

    /** One row of keys on its own, the document [LayoutJsonRoot.ROW] edits. */
    val row: ListShape by lazy { ListShape(key) }

    val action: VariantShape by lazy { checkNotNull(key.property("action")) { "Key has no action" }.shape as VariantShape }

    /**
     * The file the export writes: the layout inside an envelope. Its class is
     * private to `LayoutFile`, and it is five fields that have not changed since
     * the format began, so it is spelled out here rather than reached for.
     */
    val layoutFile: ObjectShape by lazy {
        ObjectShape(
            "LayoutFile",
            listOf(
                PropertyShape("LayoutFile", "format", TextShape, optional = false, nullable = false),
                PropertyShape("LayoutFile", "version", NumberShape(whole = true), optional = false, nullable = false),
                PropertyShape("LayoutFile", "appVersion", NumberShape(whole = true), optional = true, nullable = false),
                PropertyShape("LayoutFile", "appVersionName", TextShape, optional = true, nullable = false),
                PropertyShape("LayoutFile", "layout", layout, optional = false, nullable = false),
            ),
        )
    }

    /** The shape the whole of [document] should have, for the screen [root] names. */
    fun rootShape(document: JsonDocument, root: LayoutJsonRoot): JsonShape = when (root) {
        LayoutJsonRoot.PANEL -> panel
        LayoutJsonRoot.ROW -> row
        LayoutJsonRoot.LAYOUT -> if ((document.root as? JsonObjectNode)?.member("format") != null) layoutFile else layout
    }

    /** Every object shape reachable from the three roots, variants included, each once. */
    fun reachableObjects(): List<ObjectShape> {
        val seen = LinkedHashMap<String, ObjectShape>()
        fun walk(shape: JsonShape) {
            when (shape) {
                is ObjectShape -> if (seen.put(shape.typeName, shape) == null) shape.properties.forEach { walk(it.shape) }
                is VariantShape -> shape.variants.values.forEach(::walk)
                is ListShape -> walk(shape.item)
                is MapShape -> {
                    walk(shape.key)
                    walk(shape.value)
                }
                else -> Unit
            }
        }
        walk(layoutFile)
        walk(panel)
        return seen.values.toList()
    }

    @Synchronized
    private fun shapeOf(descriptor: SerialDescriptor): JsonShape {
        val name = descriptor.serialName.removeSuffix("?")
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT ->
                shapes[name] ?: objectShape(simpleName(name), descriptor).also { shapes[name] = it }
            StructureKind.LIST -> ListShape(shapeOf(descriptor.getElementDescriptor(0)))
            StructureKind.MAP -> MapShape(shapeOf(descriptor.getElementDescriptor(0)), shapeOf(descriptor.getElementDescriptor(1)))
            SerialKind.ENUM -> EnumShape(simpleName(name), descriptor.elementNames.toList())
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> TextShape
            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> NumberShape(whole = true)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> NumberShape(whole = false)
            PrimitiveKind.BOOLEAN -> BooleanShape
            PolymorphicKind.SEALED -> shapes[name] ?: variantShape(name, descriptor).also { shapes[name] = it }
            else -> TextShape
        }
    }

    private fun objectShape(owner: String, descriptor: SerialDescriptor): ObjectShape = ObjectShape(
        owner,
        (0 until descriptor.elementsCount).map { index ->
            val element = descriptor.getElementDescriptor(index)
            PropertyShape(
                owner = owner,
                name = descriptor.getElementName(index),
                shape = shapeOf(element),
                optional = descriptor.isElementOptional(index),
                nullable = element.isNullable,
            )
        },
    )

    /**
     * A sealed class's descriptor holds the discriminator and a second element
     * whose own elements are the subclasses, each named by its `@SerialName`.
     */
    private fun variantShape(name: String, descriptor: SerialDescriptor): VariantShape {
        val prefix = variantPrefix(simpleName(name))
        val subclasses = descriptor.getElementDescriptor(1)
        val variants = LinkedHashMap<String, ObjectShape>()
        for (index in 0 until subclasses.elementsCount) {
            val subclass = subclasses.getElementDescriptor(index)
            variants[subclass.serialName] = objectShape("$prefix:${subclass.serialName}", subclass)
        }
        return VariantShape(simpleName(name), descriptor.getElementName(0).takeIf { it.isNotEmpty() } ?: DISCRIMINATOR, variants)
    }

    /** The doc key prefix of a sealed type's variants: `action` for `KeyAction`. */
    fun variantPrefix(typeName: String): String = if (typeName == "KeyAction") "action" else typeName.replaceFirstChar { it.lowercase() }

    private fun simpleName(serialName: String): String = serialName.substringAfterLast('.')

    /**
     * Every optional property's default, as JSON, read by encoding a value made
     * with nothing but its required fields. Written out by the decoder's own
     * configuration, so a changed default changes here too.
     */
    private val defaults: Map<String, String> by lazy {
        val found = HashMap<String, String>()
        fun take(owner: String, element: JsonElement) {
            (element as? JsonObject)?.forEach { (property, value) ->
                if (property != DISCRIMINATOR) found["$owner.$property"] = value.toString()
            }
        }
        take("LayoutSpec", layoutJson.encodeToJsonElement(LayoutSpec.serializer(), LayoutSpec(id = "", name = "")))
        take("LayerSpec", layoutJson.encodeToJsonElement(LayerSpec.serializer(), LayerSpec(rows = emptyList())))
        take("Key", layoutJson.encodeToJsonElement(Key.serializer(), Key(label = "")))
        take("KeyAlternate", layoutJson.encodeToJsonElement(KeyAlternate.serializer(), KeyAlternate(KeyAction.Text)))
        take("LayoutAppearance", layoutJson.encodeToJsonElement(LayoutAppearance.serializer(), LayoutAppearance()))
        take("KeymanBinding", layoutJson.encodeToJsonElement(KeymanBinding.serializer(), KeymanBinding(keyboardId = "")))
        take(
            "InputMethodBinding",
            layoutJson.encodeToJsonElement(InputMethodBinding.serializer(), InputMethodBinding(id = "")),
        )
        take(
            "PanelLayoutSpec",
            layoutJson.encodeToJsonElement(PanelLayoutSpec.serializer(), PanelLayoutSpec(PanelKind.EMOJI, LayerSpec(emptyList()))),
        )
        val samples = listOf(
            KeyAction.Tool(), KeyAction.Layout(), KeyAction.LayerSwitch(), KeyAction.Mode(), KeyAction.SwitchInputMethod(),
            KeyAction.SendKey(keyCode = 0), KeyAction.BrailleDot(dot = 1),
            KeyAction.KeymanKey(vkey = 0), KeyAction.Field(),
        )
        for (sample in samples) {
            val element = layoutJson.encodeToJsonElement(KeyAction.serializer(), sample) as JsonObject
            val tag = element[DISCRIMINATOR].toString().trim('"')
            take("action:$tag", element)
        }
        found["LayoutFile.appVersion"] = "0"
        found["LayoutFile.appVersionName"] = "\"\""
        found
    }

    internal fun defaultOf(docKey: String): String? = defaults[docKey]
}

/**
 * Everything about a property the model's descriptors cannot say, filed by doc
 * key. Numbers come from the constants the repair pass clamps with, so the check
 * and the repair agree on what is out of range.
 */
object LayoutJsonHints {

    private val scale = listOf(JsonValueExample("0.8"), JsonValueExample("0.9"), JsonValueExample("1.1"), JsonValueExample("1.2"))

    /** The layer names a layout's `layers` map takes: the typing layers, then each shipped panel. */
    val layerNames: List<String> by lazy {
        LayoutLayer.entries.map { it.key } + PanelKind.entries.filter { it.shipped }.map { it.layerKey }
    }

    private val hints: Map<String, PropertyHint> by lazy {
        mapOf(
            "LayoutSpec.langId" to PropertyHint(domain = JsonValueDomain.LANGUAGE),
            "LayoutSpec.themeId" to PropertyHint(domain = JsonValueDomain.THEME),
            "LayoutSpec.baseMode" to PropertyHint(hidden = true),
            "LayoutSpec.version" to PropertyHint(hidden = true, examples = listOf(JsonValueExample(CurrentLayoutSpecVersion.toString()))),
            // Written by the addon installer, never typed.
            "LayoutSpec.basedOn" to PropertyHint(hidden = true),
            "LayoutSpec.layers" to PropertyHint(keys = layerNames),
            "LayerSpec.themeId" to PropertyHint(domain = JsonValueDomain.THEME),
            "LayerSpec.fontScale" to PropertyHint(examples = scale, range = LayoutFontScaleRange.toDoubles()),
            "LayerSpec.rowHeights" to PropertyHint(
                examples = listOf(JsonValueExample("0.8"), JsonValueExample("1"), JsonValueExample("1.2"), JsonValueExample("1.5")),
                range = MinRowHeightScale.exact()..MaxRowHeightScale.exact(),
            ),
            "LayoutAppearance.fontId" to PropertyHint(domain = JsonValueDomain.FONT),
            "LayoutAppearance.fontScale" to PropertyHint(examples = scale, range = LayoutFontScaleRange.toDoubles()),
            "Key.width" to PropertyHint(
                examples = listOf(
                    JsonValueExample("1"), JsonValueExample("1.25"), JsonValueExample("1.5"), JsonValueExample("2"), JsonValueExample("4"),
                ),
                range = MIN_KEY_WIDTH..MaxKeyWidth.toDouble(),
            ),
            "Key.rowSpan" to PropertyHint(
                examples = listOf(JsonValueExample("1"), JsonValueExample("2"), JsonValueExample("3")),
                range = 1.0..MaxKeySpan.toDouble(),
            ),
            "Key.alternateColumns" to PropertyHint(
                examples = listOf(JsonValueExample("0"), JsonValueExample("3"), JsonValueExample("4"), JsonValueExample("5")),
                range = 0.0..AlternateColumnsRange.last.toDouble(),
            ),
            "Key.labelScale" to PropertyHint(
                examples = listOf(JsonValueExample("0.8"), JsonValueExample("1"), JsonValueExample("1.2")),
                range = KeyLabelScaleRange.toDoubles(),
            ),
            "Key.flickScale" to PropertyHint(
                examples = listOf(JsonValueExample("0.8"), JsonValueExample("1.5")),
                range = KeyHintScaleRange.toDoubles(),
            ),
            "Key.flickInsetDp" to PropertyHint(examples = listOf(JsonValueExample("2"), JsonValueExample("-1"))),
            "Key.hintShiftXDp" to PropertyHint(examples = listOf(JsonValueExample("2"), JsonValueExample("-2"))),
            "Key.hintShiftYDp" to PropertyHint(examples = listOf(JsonValueExample("2"), JsonValueExample("-2"))),
            "Key.hintScale" to PropertyHint(
                examples = listOf(JsonValueExample("1.5"), JsonValueExample("2")),
                range = KeyHintScaleRange.toDoubles(),
            ),
            "Key.icon" to PropertyHint(domain = JsonValueDomain.ICON),
            "Key.iconHint" to PropertyHint(domain = JsonValueDomain.ICON),
            "Key.actionAlternatesFirst" to PropertyHint(hidden = true),
            // No ceiling: an index past the characters means "after them", which
            // is a reading rather than an error.
            "Key.actionAlternatesAt" to PropertyHint(
                examples = listOf(JsonValueExample("0"), JsonValueExample("1"), JsonValueExample("2")),
            ),
            "KeyAlternate.icon" to PropertyHint(domain = JsonValueDomain.ICON),
            "PanelLayoutSpec.version" to PropertyHint(
                hidden = true,
                examples = listOf(JsonValueExample(CurrentPanelLayoutVersion.toString())),
            ),
            "LayoutFile.format" to PropertyHint(examples = listOf(JsonValueExample("\"${LayoutJsonSchema.LAYOUT_FILE_FORMAT}\""))),
            "LayoutFile.appVersion" to PropertyHint(hidden = true),
            "LayoutFile.appVersionName" to PropertyHint(hidden = true),
            "action:layout.id" to PropertyHint(domain = JsonValueDomain.SECONDARY_LAYOUT),
            "action:send_key.keyCode" to PropertyHint(
                examples = listOf(
                    JsonValueExample("61", "Tab"), JsonValueExample("111", "Esc"), JsonValueExample("19", "↑"),
                    JsonValueExample("20", "↓"), JsonValueExample("21", "←"), JsonValueExample("22", "→"),
                    JsonValueExample("122", "Home"), JsonValueExample("123", "End"), JsonValueExample("92", "PgUp"),
                    JsonValueExample("93", "PgDn"), JsonValueExample("66", "Enter"), JsonValueExample("67", "⌫"),
                ),
            ),
            "action:send_key.meta" to PropertyHint(
                examples = listOf(
                    JsonValueExample("4096", "Ctrl"), JsonValueExample("2", "Alt"), JsonValueExample("1", "Shift"),
                    JsonValueExample("65536", "Meta"), JsonValueExample("4097", "Ctrl+Shift"),
                ),
            ),
            "action:braille_dot.dot" to PropertyHint(examples = (1..6).map { JsonValueExample(it.toString()) }, range = 1.0..6.0),
            "action:braille_dot.release" to PropertyHint(hidden = true),
        )
    }

    /** Action tags a layout never writes: one the keyboard makes by itself, and the placeholder for a newer build's. */
    val hiddenTags: Set<String> = setOf("numpad", "unknown")

    fun of(docKey: String): PropertyHint = hints[docKey] ?: PropertyHint.None

    /** Every doc key that has a hint, for the drift test. */
    val keys: Set<String> get() = hints.keys

    private const val MIN_KEY_WIDTH = 0.1

    private fun ClosedFloatingPointRange<Float>.toDoubles(): ClosedFloatingPointRange<Double> = start.exact()..endInclusive.exact()

    /**
     * The float as written in the source, not its binary neighbour: 0.4f widened
     * directly is 0.4000000059604645, which is what a problem message would print.
     */
    private fun Float.exact(): Double = toString().toDouble()
}

/**
 * The shape at [path] under [root], and the property whose value it is. A step
 * into an action's own properties reads the action's tag from the tree, so
 * `{"type": "tool", ...}` offers `tool` and nothing else.
 */
fun resolveShape(root: JsonShape, path: List<JsonPathStep>, document: JsonDocument): ResolvedShape? {
    var shape = root
    var property: PropertyShape? = null
    for ((index, step) in path.withIndex()) {
        when (val here = shape) {
            is ObjectShape -> {
                val name = (step as? JsonPathStep.Key)?.name ?: return null
                property = here.property(name) ?: return null
                shape = property.shape
            }
            is VariantShape -> {
                val name = (step as? JsonPathStep.Key)?.name ?: return null
                if (name == here.discriminator) {
                    property = null
                    shape = TagShape(here)
                } else {
                    val variant = here.variantOf(document.nodeAt(path.subList(0, index)) as? JsonObjectNode) ?: return null
                    property = variant.property(name) ?: return null
                    shape = property.shape
                }
            }
            is ListShape -> {
                if (step !is JsonPathStep.Index) return null
                shape = here.item
            }
            is MapShape -> {
                if (step !is JsonPathStep.Key) return null
                shape = here.value
            }
            else -> return null
        }
    }
    return ResolvedShape(shape, property)
}

/** A shape found at a path, with the property it belongs to; a list's items and a map's values keep their list's or map's property. */
data class ResolvedShape(val shape: JsonShape, val property: PropertyShape?)

/**
 * How a shape reads in a suggestion's detail and the doc strip: the words a JSON
 * author already uses. Deliberately untranslated, for the reason the plugin API docs
 * give: these name the types of a programming format.
 */
fun JsonShape.summary(): String = when (this) {
    is ObjectShape -> if (typeName.startsWith("action:")) "action" else typeName
    is VariantShape -> "action"
    is TagShape -> "action type"
    is ListShape -> "list of " + item.summary()
    is MapShape -> "map of " + value.summary()
    is EnumShape -> if (values.size <= ENUM_SUMMARY_VALUES) {
        values.joinToString(" | ") { "\"$it\"" }
    } else {
        typeName
    }
    TextShape -> "string"
    is NumberShape -> if (whole) "integer" else "number"
    BooleanShape -> "boolean"
}

// Eight, so the flick directions still spell themselves out in a hover.
private const val ENUM_SUMMARY_VALUES = 8
