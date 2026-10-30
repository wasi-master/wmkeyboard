package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.share.RemixCredit
import com.wasimaster.wmkeyboard.core.script.LanguageDef
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptDef
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.script.ScriptRegistry
import com.wasimaster.wmkeyboard.core.util.JsonPretty
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
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
 * A null [numberRow] takes the row the layer has always shown there — see
 * [BuiltInLayouts.defaultNumberRow].
 */
@Serializable
data class LayerSpec(
    val rows: List<List<Key>>,
    val numberRow: List<Key>? = null,
    /**
     * The row drawn in place of this grid's own leading digit row while the
     * number row is on, when that row already shows the same digits one row
     * up. Only the symbols layer does this; the field is ignored elsewhere.
     *
     * Null takes [BuiltInLayouts.SYMBOLS_FILL_ROW]. Stored rather than left
     * built in because the swap put a row on the keyboard that no editor
     * could reach (issue #273).
     */
    val fillRow: List<Key>? = null,
    /**
     * The arrow keys row drawn under this grid while that setting is on
     * (issue #769). Null takes the four arrows in the order the setting gives
     * them. Stored here, like [numberRow], so a layout can put a Word left key
     * or a second Shift on it: the row is under the thumbs, which is where
     * those keys are wanted.
     */
    val arrowRow: List<Key>? = null,
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
    /**
     * Keep this layer on screen when the keyboard closes and reopens, and
     * across a change of field, instead of dropping back to the letters the
     * way a layer normally does (issue #60).
     *
     * A per-layer flag rather than a global "persist if …" setting, and
     * deliberately so: the request was for granularity without a pile of
     * conditions. The one rule is "this layer stays until a key or a tool
     * takes the user somewhere else", so a grid built for one job — a macro
     * pad, a calculator strip — survives the minimise button, and the symbols
     * page of the same layout still springs back the way it always did.
     *
     * Meaningless on the letters layer of an ordinary layout, which is where
     * the keyboard lands anyway; the editor only offers it elsewhere. On a
     * [LayoutSpec.secondary] layout it applies to the one grid it has.
     *
     * Additive and defaulted, so no format-version bump.
     */
    val persistent: Boolean = false,
    /**
     * Draw this grid exactly as it is laid out here, whatever the Bottom row
     * settings would do to it: no 🌐 key hidden, moved to its shared slot or
     * swapped with the comma, and no key turned into the emoji key (issue #420).
     *
     * Those settings act on every layout at draw time, so the editor showed one
     * bottom row and the keyboard drew another, and a key moved in the editor
     * went straight back where the settings put it. The editor turns this on the
     * moment a key the settings had moved or changed is moved or changed by
     * hand, and writes the row as the keyboard was drawing it first, so nothing
     * on screen jumps. See [arrangedBy].
     *
     * Per layer rather than per layout, because that write only touches the
     * layer being edited: a symbols page the layout still inherits keeps
     * following the settings, which is the row the letters were just written
     * to match.
     *
     * Additive and defaulted, so no format-version bump.
     */
    val bottomRowAsLaidOut: Boolean = false,
    /**
     * A theme of this layer's own (a `ThemeSpec.id`), shown while the layer is
     * on screen, over the layout's [LayoutSpec.themeId] and over whatever the
     * settings say (issue #61). Null — the normal case — follows the layout.
     *
     * An id this device does not have is ignored at draw time rather than
     * repaired away, so a layout shared with its theme still remembers the
     * pairing once the theme arrives.
     */
    val themeId: String? = null,
    /**
     * Whether this layer's flick keys draw their arms on the face, over the
     * keyboard-wide setting (`LayoutBehaviorSettings.flickHints`, #588). Null,
     * the normal case, follows the setting: a number pad can keep its arms
     * while the kana pad, whose flicks the hands already know, goes without.
     *
     * Additive and defaulted, so no format-version bump.
     */
    val flickHints: Boolean? = null,
    /**
     * A converted Keyman layer's own word on its space, backspace and enter
     * keys, by Keyman key id (`K_SPACE`, `K_BKSP`, `K_ENTER`): the modifiers
     * the rules see them pressed with, where the key's `layer` says something
     * other than the layer it sits on, and the layer it switches to. The keys
     * themselves stay our space, backspace and enter, so they keep repeating,
     * swiping and naming the language; this is what they add when pressed on
     * a Keyman layout. Null everywhere else.
     */
    val keymanFrames: Map<String, KeymanTarget>? = null,
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
    /**
     * This layout's own key height, key spacing and side padding, over the
     * theme's and the global Layout & size settings (Telegram group): a
     * one-handed grid sized for one thumb no longer resizes QWERTY with it.
     * Each null follows the setting it stands in for.
     */
    val keyHeightDp: Int? = null,
    val keyGapScale: Float? = null,
    val sidePadLeftScale: Float? = null,
    val sidePadRightScale: Float? = null,
) {
    /** Whether this says anything at all; an all-null instance is stored as null. */
    val isEmpty: Boolean
        get() = fontId == null && fontScale == null && keyHeightDp == null && keyGapScale == null &&
            sidePadLeftScale == null && sidePadRightScale == null

    /** Whether this sets any of the board's sizes. */
    val sizesBoard: Boolean
        get() = keyHeightDp != null || keyGapScale != null || sidePadLeftScale != null || sidePadRightScale != null
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
     * would silently opt out all 1,259 shipped layouts at once.
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
     * The layout drawn instead while the screen is sideways, by id: a wider grid
     * for two thumbs, say. Null, or an id no longer around, draws this one. Only
     * its keys are used, so what they type stays this layout's: the editor offers
     * only layouts of the same language and composer.
     */
    val landscapeLayoutId: String? = null,
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
     * The imported input method (a `.cin` table, a Rime schema, an m17n
     * `.mim`) whose files decide what this layout's keys type. Null for every
     * layout that types its own key caps or uses one of this app's composers.
     *
     * Like [keyman], only the binding travels: on a device without the
     * method's files the grid types its own caps. Defaulted, so no
     * format-version bump, for the reason [tabletExpand] gives.
     */
    val inputMethod: InputMethodBinding? = null,
    /**
     * This layout's own key-label font and size, over the theme's and the
     * global settings'. Null — every shipped layout — draws exactly as before.
     *
     * Additive and defaulted, so no format-version bump, for the reason
     * [tabletExpand] gives.
     */
    val appearance: LayoutAppearance? = null,
    /**
     * The script this grid's keys write, when it is not its language's: Klingon
     * is written in Latin letters, but its pIqaD layout types the pIqaD
     * alphabet. Null, every other layout, takes the language's script.
     *
     * The script decides the face the keys draw in, among the rest, so this is
     * what gives pIqaD a font choice of its own beside English's. An id this
     * build does not know decodes as null, and the grid falls back to its
     * language's script. Defaulted, so no format-version bump.
     */
    @SerialName("script") val ownScript: ScriptId? = null,
    /**
     * A grid the user reaches by a key or the toolbar, not by picking a
     * language: a symbols page of their own, a macro pad, a calculator strip
     * (issue #62).
     *
     * A flag on the same type rather than a new one, because everything else
     * about a secondary layout is an ordinary layout: it is stored beside the
     * others, edited in the same editor, exported in the same file, repaired
     * by the same pass. What the flag changes is where it *appears*. It is
     * never a stop in the language cycle, never registered as a subtype, never
     * offered under Languages, and it inherits the language of whatever layout
     * was typing when it opened, so its own [langId] is irrelevant. Only its
     * [LayoutLayer.LETTERS] grid is used; the other layers are ignored.
     *
     * Reached from a [KeyAction.Layout] key naming its [id], or from the
     * Secondary layout toolbar tool. Left by a [KeyAction.Letters] key, a
     * [KeyAction.Symbols] key, the same tool, or a second press of the key
     * that opened it.
     *
     * Additive and defaulted, so no format-version bump, for the reason
     * [tabletExpand] gives.
     */
    val secondary: Boolean = false,
    /**
     * A writing canvas in place of the letter keys, the way Gboard offers
     * handwriting as one of a language's layouts (issue #557). The canvas takes
     * every reserved row above the [LayoutLayer.LETTERS] grid, so that grid is
     * only the bottom row a writer still needs: ?123, the globe, space, delete,
     * enter. The ink is recognised in this layout's language, through the same
     * pipeline as the Handwriting tool, and the symbols layers are the ordinary
     * ones.
     *
     * Additive and defaulted, so no format-version bump, for the reason
     * [tabletExpand] gives.
     */
    val handwriting: Boolean = false,
    /**
     * A theme of this layout's own (a `ThemeSpec.id`), used while any of its
     * layers is on screen, in place of the theme the settings select and of an
     * automatic light/dark pair (issue #61). A layer's [LayerSpec.themeId]
     * beats it. Null follows the settings, which is every shipped layout.
     *
     * The same mechanism a keyboard mode's theme uses, and the same rank
     * order the modes have: a layout is a more specific thing to be typing
     * on than a mode is to be in, so the layout's theme wins.
     */
    val themeId: String? = null,
    /**
     * The repository addon this layout was installed from, so an edited copy
     * still credits it when published; see `ThemeSpec.basedOn`. Defaulted, so
     * no format-version bump, for the reason [tabletExpand] gives.
     */
    val basedOn: RemixCredit? = null,
    /** Format revision, bumped by [LayoutCodec] migrations. */
    val version: Int = CurrentLayoutSpecVersion,
) {
    /** The grid for [layer], or null when this layout does not override it. */
    fun layer(layer: LayoutLayer): LayerSpec? = layers[layer.key]
}

/**
 * The user's secondary layouts (see [LayoutSpec.secondary]), in stored order.
 *
 * Only the user's own list is consulted: nothing ships as secondary, and an
 * override of a shipped layout that sets the flag is still an override of a
 * language layout, so it is not one either.
 */
fun secondaryLayouts(custom: List<LayoutSpec>): List<LayoutSpec> =
    custom.filter { it.secondary && !isShippedLayoutId(it.id) }

/**
 * The language this layout types, resolved from [LayoutSpec.langId] against the
 * registry. An id this build does not know falls back to a generic Latin
 * language so the keyboard still renders and types.
 */
fun LayoutSpec.language(): LanguageDef = LanguageRegistry.byId(langId)

/**
 * The script this layout writes, carrying its direction, case and font
 * behaviour: its own [LayoutSpec.ownScript] when it names one, otherwise its
 * [language]'s.
 */
fun LayoutSpec.script(): ScriptDef = ScriptRegistry[ownScript ?: language().script]

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
 * Indenting is [JsonPretty]'s, not this instance's. As a single line the
 * document was one unbroken run of text the field could only wrap where it
 * happened to find a break, and the last characters of a long run drew past the
 * field's right edge (#56); one field per line, which is all `prettyPrint` can
 * do, then turned a three-item list of alternates into five lines and the
 * layout into thousands. `JsonPretty` breaks only what does not fit a line.
 */
internal val layoutEditorJson = Json(layoutJson) {
    encodeDefaults = false
}

object LayoutCodec {
    fun encodeList(layouts: List<LayoutSpec>): String = layoutJson.encodeToString(layouts)

    /**
     * [encode], without the fields that hold their default value, and printed
     * for a person rather than for a parser.
     *
     * Through a `JsonElement` rather than straight to text: the shape is what
     * decides where the lines break, and only the element tree has it.
     */
    fun encodeForEditing(layout: LayoutSpec): String =
        JsonPretty.print(layoutEditorJson.encodeToJsonElement(layout))

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

/** Whether [id] names a layout this build ships — built-in or JSON asset. Never parses. */
fun isShippedLayoutId(id: String): Boolean =
    BuiltInLayouts.byId(id) != null || AssetLayouts.isShipped(id)

/**
 * The layout [id] names among everything the user has, or null when nothing
 * does. The shipped layouts come first — a custom layout whose id matches a
 * shipped one is an *edit* of it and takes its place, so a reference pinned to
 * "builtin_qwerty" keeps working and deleting the edit restores the shipped
 * grid, which is why the editor's button says "Reset" on a shipped layout and
 * "Delete" on a custom. Same rule as `resolveSymbolSets`.
 *
 * Deliberately one layout rather than the whole catalogue: the catalogue is
 * over sixteen hundred grids, the JSON ones are parsed only when asked for
 * (see [AssetLayouts]), and no screen needs more than the few it shows.
 * [resolveLayout] is the same lookup for callers that need a grid whatever
 * happens.
 */
fun findLayout(custom: List<LayoutSpec>, id: String): LayoutSpec? {
    return if (isShippedLayoutId(id)) {
        // `associateBy` semantics: the last edit of a shipped id wins.
        custom.lastOrNull { it.id == id } ?: BuiltInLayouts.byId(id) ?: AssetLayouts.byId(id)
    } else {
        custom.firstOrNull { it.id == id }
    }
}

/**
 * Where [id] sits in the shipped order — the built-ins first in their compiled
 * order, then the asset layouts in file order — or [Int.MAX_VALUE] when it is
 * not shipped. For lists that show shipped layouts in the order they always
 * have, without building every one of them to find out.
 */
fun shippedLayoutRank(id: String): Int {
    val builtIn = BuiltInLayouts.all.indexOfFirst { it.id == id }
    if (builtIn >= 0) return builtIn
    val asset = AssetLayouts.index.indexOfFirst { it.id == id }
    return if (asset >= 0) BuiltInLayouts.all.size + asset else Int.MAX_VALUE
}

/**
 * The index entry [resolveLayout] would answer from for [id], when the answer
 * is a JSON asset layout the user has not edited. Null means "resolve it":
 * a built-in, an edit, a custom layout, or an index that was never read.
 */
private fun untouchedAssetEntry(custom: List<LayoutSpec>, id: String): AssetLayouts.Entry? {
    if (BuiltInLayouts.byId(id) != null) return null
    val entry = AssetLayouts.entry(id) ?: return null
    return entry.takeIf { custom.none { it.id == id } }
}

/**
 * [resolveLayout]'s name, without parsing a JSON layout only to name it: the
 * lists and summaries that show a layout's name show a great many of them.
 */
fun resolveLayoutName(custom: List<LayoutSpec>, id: String): String {
    val canonical = canonicalLayoutId(id, custom)
    return untouchedAssetEntry(custom, canonical)?.name ?: resolveLayout(custom, canonical).name
}

/** [resolveLayout]'s language, without parsing a JSON layout to find it. */
fun resolveLayoutLanguage(custom: List<LayoutSpec>, id: String): LanguageDef {
    val canonical = canonicalLayoutId(id, custom)
    return untouchedAssetEntry(custom, canonical)?.let { LanguageRegistry.byId(it.langId) }
        ?: resolveLayout(custom, canonical).language()
}

/** [resolveLayout]'s Keyman binding, without parsing a JSON layout to find it. */
fun resolveLayoutKeyman(custom: List<LayoutSpec>, id: String): KeymanBinding? {
    val canonical = canonicalLayoutId(id, custom)
    val entry = untouchedAssetEntry(custom, canonical)
    return if (entry != null) entry.keyman else resolveLayout(custom, canonical).keyman
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
 * building 1,277 layouts to pick one out is the sort of thing that shows up as a
 * keyboard that is slow to appear. The branches mirror [findLayout] exactly —
 * a custom layout sharing a shipped id is an edit of it and wins that slot, a
 * custom-only id resolves to itself, anything else is the default.
 */
fun resolveLayout(custom: List<LayoutSpec>, id: String): LayoutSpec {
    // Retired ids (the per-style fancy layouts) fold onto their replacement
    // here too, so readers that bypass resolveLayoutSelection — the per-app
    // remembered layout, a stale active id — keep resolving to Fancy rather
    // than healing to the default.
    @Suppress("NAME_SHADOWING")
    val id = canonicalLayoutId(id, custom)
    return if (isShippedLayoutId(id)) {
        // `associateBy` keeps the last entry for a repeated key, so an override
        // list with duplicate ids resolves the way it did through the map. The
        // edit is looked for first: it wins, and finding it costs no parse.
        custom.lastOrNull { it.id == id }
            ?: BuiltInLayouts.byId(id)
            ?: AssetLayouts.byId(id)
            ?: BuiltInLayouts.default
    } else {
        // Not shipped: this is the `custom.filter { it.id !in shippedIds }`
        // tail, which is scanned front to back.
        custom.firstOrNull { it.id == id } ?: BuiltInLayouts.default
    }
}
