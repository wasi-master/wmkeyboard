package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One text-editing operation: a cursor move, a selection change, or a
 * clipboard operation. The payload of a `KeyAction.Edit` key, and what the
 * toolbar's cursor tools run.
 *
 * Lives here rather than in the ime layer because layouts and settings name
 * these operations, and neither can depend on the keyboard view. The service is
 * the only thing that *runs* them.
 */
@Serializable
enum class TextEditAction {
    UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN,
    WORD_LEFT, WORD_RIGHT, SELECT_WORD, SELECT_LINE,
    SELECT, SELECT_ALL, COPY, PASTE, BACKSPACE,
}

/**
 * The actions whose second press does something the first did not, so holding the
 * key is worth repeating.
 *
 * The moves, and backspace. Home, End and the two selection commands are left out
 * for the reason [HoldRepeatCursorTools] leaves them out of the toolbar's repeat:
 * the caret is already there, so a repeat would buzz away doing nothing. Those are
 * exactly the keys whose hold is free for a second action — see
 * [TextEditKey.longPress].
 */
val RepeatingTextEditActions: Set<TextEditAction> = setOf(
    TextEditAction.UP, TextEditAction.DOWN, TextEditAction.LEFT, TextEditAction.RIGHT,
    TextEditAction.PAGE_UP, TextEditAction.PAGE_DOWN,
    TextEditAction.WORD_LEFT, TextEditAction.WORD_RIGHT,
    TextEditAction.BACKSPACE,
)

/** Whether a hold on a key running [action] repeats it. */
val TextEditAction.repeats: Boolean get() = this in RepeatingTextEditActions

/**
 * One key of the text-editing panel.
 *
 * [width] is in grid units, exactly as [com.wasimaster.wmkeyboard.core.layout.Key]
 * uses them, and [rowSpan] means the same thing too: the key covers that many rows
 * starting with its own, and the rows below flow around the column it holds. The
 * shipped arrangement needs both — its left and right arrows are one column wide
 * and three rows tall.
 *
 * [longPress] is the second action, and it is only ever read on a key whose own
 * action does not repeat: a hold cannot both repeat a move and do something else,
 * and the moves are the keys where repeating is the point. That is the rule rather
 * than a validation error, so turning a key from Home into Left keeps the layout
 * drawable and simply stops reading its hold.
 */
@Serializable
data class TextEditKey(
    val action: TextEditAction,
    val longPress: TextEditAction? = null,
    val width: Float = 1f,
    val rowSpan: Int = 1,
)

/**
 * The text-editing panel's grid as builds before issue #63 stored it, under the
 * `text_edit_layout` preference.
 *
 * **Legacy.** The panel is a panel layout now (`PanelLayoutSpec`, a grid of
 * ordinary keys with `KeyAction.Edit`), and this type survives only so the
 * settings store can read what an older build wrote and fold it into the new
 * form — see `foldLegacyPanelPrefs` / `migrateTextEditLayout` in :core:settings.
 * Nothing draws or edits one any more. Do not add fields.
 *
 * [rowHeights] is index-aligned with [rows] and multiplies that row's share of the
 * panel height; a missing or short entry means 1.
 */
@Serializable
data class TextEditLayout(
    val rows: List<List<TextEditKey>> = emptyList(),
    val rowHeights: List<Float>? = null,
) {
    /** The width every row is laid out against: the widest row, spans included. */
    val gridWeight: Float
        get() = rows.indices.maxOfOrNull { rowWidth(it) } ?: 0f

    /** Row [index]'s width, counting the columns keys above hold over it. */
    fun rowWidth(index: Int): Float {
        var width = rows.getOrNull(index)?.sumOf { it.width.toDouble() }?.toFloat() ?: 0f
        for (r in 0 until index) {
            for (key in rows[r]) {
                if (r + key.rowSpan > index) width += key.width
            }
        }
        return width
    }

    /** This row's height multiplier, or 1 when none is stored. */
    fun rowHeight(index: Int): Float =
        (rowHeights?.getOrNull(index) ?: 1f).coerceIn(MinTextEditRowHeight, MaxTextEditRowHeight)
}

/** The shortest a text-edit row's height multiplier is honoured at. */
const val MinTextEditRowHeight = 0.4f

/** The tallest a text-edit row's height multiplier is honoured at. */
const val MaxTextEditRowHeight = 2.5f

/** Widest a single text-edit key may be, in grid units. */
const val MaxTextEditKeyWidth = 6f

/** Most rows one text-edit key may cover. */
const val MaxTextEditKeySpan = 4

/** Most rows the panel may have; past this the keys are too short to hit. */
const val MaxTextEditRows = 6

/** Most keys one row may hold. */
const val MaxTextEditKeysPerRow = 10

/**
 * The old panel's shipped arrangement, in the legacy form. Kept as the fixture
 * the migration test checks itself against: migrating this must give exactly
 * `BuiltInPanelLayouts.TEXT_EDIT`. Nothing draws it.
 */
val DefaultTextEditLayout: TextEditLayout = TextEditLayout(
    rows = listOf(
        listOf(
            TextEditKey(TextEditAction.LEFT, width = 0.8f, rowSpan = 3),
            TextEditKey(TextEditAction.UP, width = 1.4f),
            TextEditKey(TextEditAction.RIGHT, width = 0.8f, rowSpan = 3),
            TextEditKey(TextEditAction.SELECT_ALL, width = 1.4f),
        ),
        listOf(
            TextEditKey(TextEditAction.SELECT, width = 1.4f),
            TextEditKey(TextEditAction.COPY, width = 1.4f),
        ),
        listOf(
            TextEditKey(TextEditAction.DOWN, width = 1.4f),
            TextEditKey(TextEditAction.PASTE, width = 1.4f),
        ),
        listOf(
            // Home and End do not repeat, so their holds are free: the shipped
            // pairing sends them to the ends of the *text* rather than the line.
            TextEditKey(TextEditAction.HOME, longPress = TextEditAction.PAGE_UP, width = 1.47f),
            TextEditKey(TextEditAction.END, longPress = TextEditAction.PAGE_DOWN, width = 1.47f),
            TextEditKey(TextEditAction.BACKSPACE, width = 1.46f),
        ),
    ),
)

/**
 * Reads the legacy [TextEditLayout] preference, and repairs what it reads, for
 * the one caller left: the settings store's migration of it into a panel layout.
 * A layout with no usable key at all decodes to null, which the caller reads as
 * "nothing to migrate".
 */
object TextEditLayoutCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        // An action name this build does not have coerces to the field default
        // instead of failing the whole layout.
        coerceInputValues = true
    }

    fun encode(layout: TextEditLayout): String = json.encodeToString(layout)

    fun decode(stored: String?): TextEditLayout? {
        if (stored.isNullOrBlank()) return null
        val decoded = runCatching { json.decodeFromString<TextEditLayout>(stored) }.getOrNull()
            ?: return null
        return repair(decoded)
    }

    /**
     * [layout] with every number inside its bounds, or null when nothing usable is
     * left. Also drops a [TextEditKey.longPress] on a key that repeats, so the
     * stored layout says what the panel does.
     */
    fun repair(layout: TextEditLayout): TextEditLayout? {
        val rows = layout.rows
            .take(MaxTextEditRows)
            .map { row ->
                row.take(MaxTextEditKeysPerRow).map { key ->
                    key.copy(
                        longPress = key.longPress?.takeUnless { key.action.repeats || it == key.action },
                        width = key.width.takeIf { it.isFinite() && it > 0f }
                            ?.coerceAtMost(MaxTextEditKeyWidth)
                            ?: 1f,
                        rowSpan = key.rowSpan.coerceIn(1, MaxTextEditKeySpan),
                    )
                }
            }
            .filter { it.isNotEmpty() }
        if (rows.isEmpty()) return null
        val heights = layout.rowHeights
            ?.take(rows.size)
            ?.map { it.takeIf { h -> h.isFinite() }?.coerceIn(MinTextEditRowHeight, MaxTextEditRowHeight) ?: 1f }
            ?.takeIf { heights -> heights.any { it != 1f } }
        return TextEditLayout(rows, heights)
    }
}
