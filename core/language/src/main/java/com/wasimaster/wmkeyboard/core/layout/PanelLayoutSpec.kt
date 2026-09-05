package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray

/**
 * A tool panel whose grid the user can lay out: the surfaces that take the key
 * area over and used to be fixed structures.
 *
 * One layout per panel, edited in place and reset to the shipped one — the
 * semantics editing a built-in typing layout already has. Serialized by the
 * lowercase name so a stored list reads `"panel":"emoji"`.
 *
 * [shipped] is whether the panel's tool exists in this build: the trackpad's
 * layout is defined ahead of its tool (issue #39) so the field system has a
 * seam to plug into, and the gallery hides it until then.
 */
@Serializable
enum class PanelKind(val shipped: Boolean = true) {
    @SerialName("emoji") EMOJI,
    @SerialName("clipboard") CLIPBOARD,
    @SerialName("text_edit") TEXT_EDIT,
    @SerialName("trackpad") TRACKPAD(shipped = false),
    ;

    /**
     * The one component a layout of this panel cannot do without — the panel
     * *is* that component — or null for a panel that is only keys.
     */
    val requiredField: PanelFieldKind?
        get() = when (this) {
            EMOJI -> PanelFieldKind.EMOJI_GRID
            CLIPBOARD -> PanelFieldKind.CLIPBOARD_LIST
            TRACKPAD -> PanelFieldKind.TRACKPAD
            TEXT_EDIT -> null
        }
}

/**
 * The layout of one tool panel: a grid of ordinary keys plus the
 * [KeyAction.Field] cells that host the panel's components.
 *
 * Its own type rather than a [LayoutSpec] wearing a marker. A typing layout
 * is a language plus layers plus a place in the enabled list, and every reader
 * of one — the registry, the compiler's letters fallback, the enable gate, the
 * gallery, the tablet expansion, proximity — would have needed a guard. What a
 * panel shares with a typing layout is exactly [LayerSpec]: rows of [Key],
 * per-row heights, a label size. So that is what it holds.
 *
 * [grid]'s `numberRow` is meaningless here and ignored. Rows holding a field
 * that starts in them are *flex* rows and share the panel's spare height; rows
 * of plain keys stay at key height — see `PanelRows.kt`. When no row holds a
 * field (the text-editing pad) every row flexes, which is how that pad has
 * always been drawn.
 */
@Serializable
data class PanelLayoutSpec(
    val panel: PanelKind,
    val grid: LayerSpec,
    val appearance: LayoutAppearance? = null,
    val version: Int = CurrentPanelLayoutVersion,
)

/** Format revision of [PanelLayoutSpec]; there has only been one. */
const val CurrentPanelLayoutVersion: Int = 1

/**
 * Reads and writes the user's panel layouts. The same JSON configuration the
 * typing layouts use, so a field key or an edit key means the same thing in
 * both, and an action from a newer build decodes to [KeyAction.Unknown] here too.
 */
object PanelLayoutCodec {

    fun encodeList(layouts: List<PanelLayoutSpec>): String = layoutJson.encodeToString(layouts)

    /**
     * Decoded one element at a time, on purpose: a [PanelKind] this build does
     * not have is an enum *value*, not a field, so `coerceInputValues` cannot
     * save it — the element throws. Decoding the array in one call would let
     * one such element cost the user the other three panels.
     */
    fun decodeList(json: String): List<PanelLayoutSpec> {
        if (json.isBlank()) return emptyList()
        val array: JsonArray = runCatching { layoutJson.parseToJsonElement(json).jsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { layoutJson.decodeFromJsonElement(PanelLayoutSpec.serializer(), element) }
                .getOrNull()
        }
    }

    fun encode(layout: PanelLayoutSpec): String = layoutJson.encodeToString(layout)

    /** [encode], without the fields that hold their default value; for the raw-JSON editor. */
    fun encodeForEditing(layout: PanelLayoutSpec): String = layoutEditorJson.encodeToString(layout)

    fun decode(json: String): PanelLayoutSpec? {
        if (json.isBlank()) return null
        return runCatching {
            layoutJson.decodeFromString<PanelLayoutSpec>(json.firstJsonDocument())
        }.getOrNull()
    }
}

/**
 * The layout the keyboard draws for [kind]: the user's own when they have one,
 * the shipped grid otherwise. The last stored one wins, so a duplicate written
 * by two racing edits resolves to the newer.
 */
fun resolvePanelLayout(kind: PanelKind, custom: List<PanelLayoutSpec>): PanelLayoutSpec =
    custom.lastOrNull { it.panel == kind } ?: BuiltInPanelLayouts.default(kind)

/** [resolvePanelLayout] for every panel at once. */
fun resolvePanelLayouts(custom: List<PanelLayoutSpec>): Map<PanelKind, PanelLayoutSpec> =
    PanelKind.entries.associateWith { resolvePanelLayout(it, custom) }
