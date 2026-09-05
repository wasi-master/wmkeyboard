package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule

/**
 * A layer of the keyboard: which grid is on screen. [LETTERS] is the one a
 * layout has to define; the rest are overrides, and a layout that leaves one
 * out inherits the shipped grid rather than showing nothing.
 *
 * [key] rather than the enum name is what goes in the file, so a hand-written
 * layout reads `"symbols2"` the way the shipped grid is already named, and so
 * renaming an entry here is not a format change.
 */
enum class LayoutLayer(val key: String) {
    LETTERS("letters"),
    SYMBOLS("symbols"),
    SYMBOLS_SHIFTED("symbols2"),
    NUMBER("number"),
    PHONE("phone"),
    DATE("date"),
    TIME("time"),
    DATETIME("datetime"),
    FN("fn"),
    ;

    /**
     * Whether the user reaches this layer by pressing a key, rather than by
     * focusing a field of the matching kind.
     *
     * Only the cycled layers need a key back to the letters. The numeric pads
     * deliberately have none — a number field gets its keypad whatever the
     * layout mode says, so there is nothing to leave *to*, and demanding an ABC
     * key on them would put one on five shipped grids that have never had one.
     */
    val isCycled: Boolean
        get() = this == LETTERS || this == SYMBOLS || this == SYMBOLS_SHIFTED || this == FN
}

/**
 * One grid, plus the number row shown above it when that setting is on.
 *
 * Wrapped in an object rather than stored as a bare `List<List<Key>>` because
 * the raw-JSON editor is a supported way to write these, and
 * `{"letters": {"rows": [...]}}` is readable where `{"letters": [[...]]}` is
 * three levels of anonymous brackets.
 *
 * A null [numberRow] takes the digits the layer has always shown — see
 * `KeyRows`, which owns the per-layer defaults.
 */
@Serializable
data class LayerSpec(
    val rows: List<List<Key>>,
    val numberRow: List<Key>? = null,
    /**
     * Optional per-row height multipliers, one per entry in [rows] (index
     * aligned). Null — the normal case — keeps every row at the standard key
     * height; a value of e.g. 1.5 makes that row half again as tall. A short or
     * over-long list is tolerated: rows without an entry use 1.0. Additive and
     * optional, so it needs no format-version bump.
     */
    val rowHeights: List<Float>? = null,
    /**
     * This layer's own label size, replacing the layout's
     * [LayoutAppearance.fontScale] for the keys on this grid. Null — the normal
     * case — takes the layout's, which is what the layout-wide control is for.
     *
     * It replaces rather than multiplies, and that is the whole point of it: the
     * layout-wide size reaches every layer, so a symbol page whose keys are
     * `?123`-sized punctuation had no way to stay put while the letters grew
     * (issue #18). Replacing makes the two independent; multiplying would only
     * have moved the coupling one step away.
     *
     * Bounded by [LayoutFontScaleRange] at draw time, exactly like the
     * layout-wide value it stands in for.
     */
    val fontScale: Float? = null,
)

/**
 * How one layout draws its key labels, over whatever the theme and the global
 * settings already decided.
 *
 * A grid nobody else wrote can need type its author never gets to choose
 * anywhere else: a layout whose keys are labelled with words rather than
 * letters wants smaller text, a phonetic grid may want a face the rest of the
 * keyboard is not set to. Both belong to the *layout*, not to the theme and not
 * to the global settings — switching language must not undo them, and setting
 * them must not follow the user onto every other grid. This is the layout's own
 * slot for them (issue #18).
 *
 * Both fields are null in the normal case, which is the layout saying nothing
 * and taking what it is given.
 *
 * [fontScale] is a **multiplier**, not a replacement. An imported layout that
 * simply overwrote the label size would silently throw away the larger type an
 * accessibility user set on purpose; multiplying keeps their setting and still
 * says "and this grid a fifth smaller than that". [LayoutFontScaleRange] bounds
 * it; the product is left alone, because 0.5 of an already-small setting is
 * exactly what a wordy grid asks for.
 *
 * [fontId] takes the same ids the font settings write — `default`,
 * `google:<Name>`, `installed:<name>`, `custom` — and resolves through
 * `KeyboardFonts.family`, so an id whose font is not on this device falls
 * through to the face that would have been used anyway rather than blanking the
 * keys.
 */
@Serializable
data class LayoutAppearance(
    val fontId: String? = null,
    val fontScale: Float? = null,
) {
    /** Whether this says anything at all; an all-null instance is stored as null. */
    val isEmpty: Boolean get() = fontId == null && fontScale == null
}

/**
 * What a [LayoutAppearance.fontScale] is honoured at. Reaches lower than the
 * global `KeyFontScaleRange` on purpose: that floor is a whole-keyboard
 * accessibility one, and this exists for the single grid that needs to go below
 * it.
 */
val LayoutFontScaleRange = 0.5f..2f

/**
 * A complete keyboard layout as the user owns it: the grids for every layer it
 * overrides, plus the language it types in.
 *
 * A custom layout is not a new language — it is an existing one wearing a
 * different key grid. [langId] is what makes that work: the dictionary,
 * autocorrect sources, script rules, handwriting and dictation language and
 * shift behaviour all key off the language (and its script) named here, so
 * "my own Bengali arrangement" inherits everything Bengali knows without
 * restating any of it. [composer] optionally overrides how keystrokes compose —
 * the one thing the language cannot decide alone, since Bengali hosts both the
 * transliterating Avro and the fixed Probhat/Jatiya on one script.
 *
 * [legacyBaseMode] captures the `baseMode` field written by builds before the
 * language registry (a serialized `InputMode` name); [LayoutCodec] migrates it
 * into [langId] on the first read and clears it. Nothing else reads it.
 *
 * [layers] is keyed by [LayoutLayer.key] rather than by the enum, because an
 * enum used as a map *key* is not covered by `coerceInputValues` — a layer name
 * from a newer build would throw and cost the user the whole file, where an
 * unrecognized string is simply ignored.
 */
@Serializable
data class LayoutSpec(
    val id: String,
    val name: String,
    /** Language id ([LanguageDef.id], e.g. "en", "bn", "fr"); blank until migrated. */
    val langId: String = "",
    /** Keystroke composer override; null inherits the script's default composer. */
    val composer: ComposerType? = null,
    @SerialName("baseMode") val legacyBaseMode: String? = null,
    val layers: Map<String, LayerSpec> = emptyMap(),
    /**
     * Typo-proximity rows, one string of committed characters per physical row,
     * for the one thing the grid cannot express on its own: a staggered or split
     * arrangement whose rows do not share a column origin. Null — the normal
     * case — derives them from [LayoutLayer.LETTERS]; see
     * [com.wasimaster.wmkeyboard.core.prediction.KeyProximity.forLayout].
     */
    val proximityRows: List<String>? = null,
    /**
     * Let the tablet expansion widen this layout's grid on a big screen — adding
     * Tab, backslash, caps lock, a mirrored shift, arrows and the rest around the
     * alphabet. See [com.wasimaster.wmkeyboard.core.layout.expandForTablet].
     *
     * True by default, and it has to be: the expansion is automatic, and a file
     * written before this field existed decodes without it, so any other default
     * would silently opt out all 1,256 shipped layouts at once.
     *
     * Off is for a grid the transform cannot help but would still accept — one
     * already laid out wide by hand, or a deliberately minimal board. The gate in
     * `TabletExpansion` already declines the arrangements it plainly cannot
     * handle; this is the author's override for the rest.
     *
     * No format-version bump: a new field carrying a default changes no stored
     * shape and needs no migration, and bumping would rewrite the effective
     * version of every asset and custom layout on read for nothing.
     */
    val tabletExpand: Boolean = true,
    /**
     * The Keyman keyboard whose rules decide what this layout's keys type, for
     * a grid converted from one. Null for every layout of ours.
     *
     * Only the binding travels, never the rules: an exported layout stays a few
     * kilobytes of JSON, and on a device that does not have the rules it
     * resolves to nothing and the grid types its own key caps. That is the
     * designed degradation rather than a failure — the keyboard is still a
     * usable keyboard, it is just no longer the input method its author wrote.
     */
    val keyman: KeymanBinding? = null,
    /**
     * This layout's own key-label font and size, over the theme's and the
     * global settings'. Null — every shipped layout — draws exactly as before.
     *
     * Additive and defaulted, so no format-version bump, for the reason
     * [tabletExpand] gives.
     */
    val appearance: LayoutAppearance? = null,
    /** Format revision, bumped by [LayoutCodec] migrations. */
    val version: Int = CurrentLayoutSpecVersion,
) {
    /** The grid for [layer], or null when this layout does not override it. */
    fun layer(layer: LayoutLayer): LayerSpec? = layers[layer.key]
}

/**
 * The language this layout types, resolved from [LayoutSpec.langId] against the
 * registry. An id this build does not know falls back to a generic Latin
 * language so the keyboard still renders and types.
 */
fun LayoutSpec.language(): LanguageDef = LanguageRegistry.byId(langId)

/** The script [language] writes, carrying its direction, case and font behaviour. */
fun LayoutSpec.script(): ScriptDef = ScriptRegistry[language().script]

/**
 * The composer that turns keystrokes into text: the layout's explicit
 * [LayoutSpec.composer] override (Avro's transliteration) when set, otherwise the
 * script's default (dead keys for Latin, cluster shaping for Indic).
 */
fun LayoutSpec.composerType(): ComposerType = composer ?: script().composer

/**
 * How many rounds of format changes [LayoutSpec] has been through. Bump it and
 * add a branch to `LayoutCodec.migrateLayout` whenever a stored field changes
 * shape — the same hook `KeyboardModeCodec.migrateMode` provides.
 *
 * v2: `baseMode` (a serialized `InputMode`) became [LayoutSpec.langId] +
 * [LayoutSpec.composer].
 */
const val CurrentLayoutSpecVersion: Int = 2

internal val layoutJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A layout naming an InputMode or ClipboardKeyAction this build does not
    // have coerces to the field's default instead of failing the whole file.
    // The sibling codecs (ThemeCodec, SymbolSetCodec, KeyboardModeCodec) do
    // without this because none of them stores an enum a newer build is likely
    // to grow; a layout stores three.
    coerceInputValues = true
    serializersModule = SerializersModule {
        // An action tag this build does not know decodes to KeyAction.Unknown
        // rather than throwing, so one key written by a newer version cannot
        // cost the user the other forty. `repair` drops them and says so.
        polymorphicDefaultDeserializer(KeyAction::class) { tag ->
            UnknownKeyActionSerializer(tag.orEmpty())
        }
    }
}

/**
 * The same format with the defaults left out, for the one reader that is a
 * person: the raw-JSON editor.
 *
 * Storage keeps `encodeDefaults = true` — every field written every time is the
 * cheapest guarantee that a downgrade reads what an upgrade wrote. Handed to a
 * human it is unusable: a plain four-row QWERTY came to 33 kB, almost all of it
 * `"output":null,"shiftLabel":null,"clipboardAction":null,"role":null,
 * "icon":null,"iconHint":null,"flick":{}` repeated four hundred times, and the
 * Apply button sat forty screens below the field. Omitting them costs nothing to
 * read back — every one of those fields has a default — and shrinks the same
 * layout to about a tenth.
 *
 * Printed one field per line as well. As a single line the document was one
 * unbroken run of text the field could only wrap where it happened to find a
 * break, and the last characters of a long run drew past the field's right
 * edge (#56). Short lines wrap where they should, and the layout reads as
 * rows and keys rather than a wall.
 */
internal val layoutEditorJson = Json(layoutJson) {
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

object LayoutCodec {
    fun encodeList(layouts: List<LayoutSpec>): String = layoutJson.encodeToString(layouts)

    /** [encode], without the fields that hold their default value. */
    fun encodeForEditing(layout: LayoutSpec): String = layoutEditorJson.encodeToString(layout)

    fun decodeList(json: String): List<LayoutSpec> {
        // Blank is the ordinary state — nothing is written here until the user
        // edits or imports a layout — so answer it without handing the parser
        // something it can only reject by throwing.
        if (json.isBlank()) return emptyList()
        return runCatching { layoutJson.decodeFromString<List<LayoutSpec>>(json) }
            .getOrDefault(emptyList())
            .map(::migrateLayout)
    }

    fun encode(layout: LayoutSpec): String = layoutJson.encodeToString(layout)

    fun decode(json: String): LayoutSpec? {
        if (json.isBlank()) return null
        return runCatching { layoutJson.decodeFromString<LayoutSpec>(json.firstJsonDocument()) }
            .getOrNull()
            ?.let(::migrateLayout)
    }

    /**
     * Brings a stored layout up to [CurrentLayoutSpecVersion]. The v1→v2 step
     * converts a pre-registry `baseMode` ([LayoutSpec.legacyBaseMode]) into a
     * [LayoutSpec.langId], moving Avro's transliteration onto [LayoutSpec.composer]
     * so the phonetic-vs-fixed Bengali distinction survives. Gated on a blank
     * langId rather than the version alone, so a hand-written layout that omits
     * the version field still migrates instead of silently defaulting to English.
     */
    private fun migrateLayout(spec: LayoutSpec): LayoutSpec {
        if (spec.langId.isBlank()) {
            val langId = spec.legacyBaseMode?.let { LEGACY_MODE_LANG[it] } ?: "en"
            val composer =
                if (spec.legacyBaseMode == "AVRO") ComposerType.TRANSLITERATE else spec.composer
            return spec.copy(
                langId = langId,
                composer = composer,
                legacyBaseMode = null,
                version = CurrentLayoutSpecVersion,
            )
        }
        return if (spec.version < CurrentLayoutSpecVersion) {
            spec.copy(version = CurrentLayoutSpecVersion)
        } else {
            spec
        }
    }

    /** Exposed for [LayoutFile], which parses the envelope itself. */
    internal val json: Json get() = layoutJson
}

/**
 * Every layout the user has: the shipped ones first in their shipped order — the
 * Kotlin [BuiltInLayouts] then the JSON [AssetLayouts] (empty until loaded off
 * the main thread) — then the user's own. A custom layout whose id matches a
 * shipped one is an *edit* of it: it takes that slot rather than appearing
 * twice, so a reference pinned to "builtin_qwerty" keeps working and deleting
 * the edit restores the shipped grid — which is why the editor's button says
 * "Reset" on a shipped layout and "Delete" on a custom. Same rule as
 * `resolveSymbolSets`.
 */
fun resolveLayouts(custom: List<LayoutSpec>): List<LayoutSpec> {
    val generation = AssetLayouts.generation
    ResolvedLayouts.cache?.let {
        if (it.generation == generation && it.custom === custom) return it.resolved
    }
    val shipped = BuiltInLayouts.all + AssetLayouts.all
    val overrides = custom.associateBy { it.id }
    val resolvedShipped = shipped.map { overrides[it.id] ?: it }
    val shippedIds = shipped.mapTo(HashSet()) { it.id }
    val resolved = resolvedShipped + custom.filter { it.id !in shippedIds }
    ResolvedLayouts.cache = ResolvedLayouts.Entry(custom, generation, resolved)
    return resolved
}

/**
 * One-entry memo for [resolveLayouts].
 *
 * The list it builds is ~1,274 layouts long and building it costs four
 * collections of that size, and the whole thing is a pure function of the
 * user's custom layouts and the shipped set. Callers ask for it far more often
 * than either changes: the settings decode resolves layouts several times per
 * emission, and entering a field resolves one per enabled layout.
 *
 * Keyed on the *identity* of the custom list — the settings object holds one
 * instance for its lifetime, so the hot callers all hit — and on
 * [AssetLayouts.generation], so the empty pre-load shipped set can never be
 * cached past the moment the assets finish parsing. A miss just recomputes;
 * two threads racing both compute the same list and the later write wins.
 */
private object ResolvedLayouts {
    class Entry(
        val custom: List<LayoutSpec>,
        val generation: Int,
        val resolved: List<LayoutSpec>,
    )

    @Volatile
    var cache: Entry? = null
}

/**
 * Names the Keyman keyboard whose rules decide what a converted layout types.
 *
 * Lives here rather than in `:core:keyman` because [LayoutSpec] must not depend
 * on the engine: a layout is data and has to decode on a build where the engine
 * is absent. The engine looks the binding up, never the other way round.
 */
@Serializable
data class KeymanBinding(
    /** Keyboard id as the package names it, e.g. `khmer_angkor`. */
    val keyboardId: String,
    /** Keyboard version the grid was converted from, for a staleness check. */
    val version: String = "",
)

/**
 * The layout [id] names, falling back to the default when it has been deleted
 * out from under a stored reference. Never returns null: a keyboard with no
 * grid has nothing to draw and no way for the user to recover.
 *
 * Answered by lookup rather than by resolving the whole catalogue and scanning
 * it: this is called once per enabled layout when a field is focused, and
 * building 1,274 layouts to pick one out is the sort of thing that shows up as a
 * keyboard that is slow to appear. The branches mirror [resolveLayouts]
 * exactly — a custom layout sharing a shipped id is an edit of it and wins that
 * slot, a custom-only id resolves to itself, anything else is the default.
 */
fun resolveLayout(custom: List<LayoutSpec>, id: String): LayoutSpec {
    // Retired ids (the per-style fancy layouts) fold onto their replacement
    // here too, so readers that bypass resolveLayoutSelection — the per-app
    // remembered layout, a stale active id — keep resolving to Fancy rather
    // than healing to the default.
    @Suppress("NAME_SHADOWING")
    val id = canonicalLayoutId(id, custom)
    val shipped = BuiltInLayouts.byId(id) ?: AssetLayouts.byId(id)
    return if (shipped != null) {
        // `associateBy` keeps the last entry for a repeated key, so an override
        // list with duplicate ids resolves the way it did through the map.
        custom.lastOrNull { it.id == id } ?: shipped
    } else {
        // Not shipped: this is the `custom.filter { it.id !in shippedIds }`
        // tail, which is scanned front to back.
        custom.firstOrNull { it.id == id } ?: BuiltInLayouts.default
    }
}
