package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.repeats

/**
 * The panel layouts the keyboard ships with: what every panel draws until the
 * user edits it, and what the editor's Reset goes back to.
 *
 * Each is the panel as it looked before it became a layout, written down. The
 * emoji panel's bottom row of abc / space / backspace was a hand-built row of
 * three cells at 1.5 / 7 / 1.5 of a ten-wide grid; that is the row here, and
 * it is now three ordinary keys, which is the whole point.
 *
 * Grid weight is 10 throughout so a panel's rows centre the way a typing
 * grid's do and a key here is the same width as a key on the board.
 */
object BuiltInPanelLayouts {

    private fun field(kind: PanelFieldKind, width: Float, rowSpan: Int = 1) =
        Key(label = "", action = KeyAction.Field(kind), width = width, rowSpan = rowSpan)

    private fun edit(
        op: TextEditAction,
        width: Float,
        rowSpan: Int = 1,
        hold: TextEditAction? = null,
    ): Key {
        // A hold on a repeating key repeats; an alternate there would never open.
        val alternates = hold?.takeUnless { op.repeats }
            ?.let { listOf(KeyAlternate(KeyAction.Edit(it))) }
            .orEmpty()
        return Key(
            label = "",
            action = KeyAction.Edit(op),
            width = width,
            rowSpan = rowSpan,
            actionAlternates = alternates,
        )
    }

    /** abc / space / backspace, as the emoji panel has always ended. */
    val bottomRow: List<Key> = listOf(
        Key("ABC", action = KeyAction.Letters, width = 1.5f),
        Key(" ", action = KeyAction.Space, width = 7f),
        Key("⌫", action = KeyAction.Delete, width = 1.5f),
    )

    /**
     * Search pill and category tabs across the top, the grid, then the bottom
     * row. The top two rows flex 0.9 : 3 over whatever the fixed bottom row
     * leaves, which puts the tab strip at about a key's height.
     */
    val EMOJI: PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.EMOJI,
        grid = LayerSpec(
            rows = listOf(
                listOf(field(PanelFieldKind.EMOJI_SEARCH, 1f), field(PanelFieldKind.EMOJI_TABS, 9f)),
                listOf(field(PanelFieldKind.EMOJI_GRID, 10f)),
                bottomRow,
            ),
            rowHeights = listOf(0.9f, 3f, 1f),
        ),
    )

    /**
     * The clipboard: search pill, the fragment chips pulled out of the
     * history, the history itself. [bottomRow] adds the abc / space / backspace
     * row the old `clipboard.bottomRow` setting used to switch on; the
     * settings store seeds a user's layout from this when that flag was set.
     */
    fun clipboard(bottomRow: Boolean = false): PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.CLIPBOARD,
        grid = LayerSpec(
            rows = buildList {
                add(listOf(field(PanelFieldKind.CLIPBOARD_SEARCH, 10f)))
                add(listOf(field(PanelFieldKind.CLIPBOARD_ENTITIES, 10f)))
                add(listOf(field(PanelFieldKind.CLIPBOARD_LIST, 10f)))
                if (bottomRow) add(this@BuiltInPanelLayouts.bottomRow)
            },
            rowHeights = buildList {
                add(0.8f)
                add(0.7f)
                add(3f)
                if (bottomRow) add(1f)
            },
        ),
    )

    val CLIPBOARD: PanelLayoutSpec = clipboard()

    /**
     * The text-editing pad as it has always looked — Gboard's cluster: tall
     * left and right arrows either side of up / Select / down, the clipboard
     * three stacked on the right, home / end / backspace along the bottom.
     * Widths add up to 4.4 on every row, the proportion the hand-built pad
     * used. No field, so every row flexes and the pad fills the key area.
     *
     * Home and End do not repeat, so their holds are free: the shipped pairing
     * sends them to the ends of the *text* rather than the line.
     */
    val TEXT_EDIT: PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.TEXT_EDIT,
        grid = LayerSpec(
            rows = listOf(
                listOf(
                    edit(TextEditAction.LEFT, width = 0.8f, rowSpan = 3),
                    edit(TextEditAction.UP, width = 1.4f),
                    edit(TextEditAction.RIGHT, width = 0.8f, rowSpan = 3),
                    edit(TextEditAction.SELECT_ALL, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.SELECT, width = 1.4f),
                    edit(TextEditAction.COPY, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.DOWN, width = 1.4f),
                    edit(TextEditAction.PASTE, width = 1.4f),
                ),
                listOf(
                    edit(TextEditAction.HOME, width = 1.47f, hold = TextEditAction.PAGE_UP),
                    edit(TextEditAction.END, width = 1.47f, hold = TextEditAction.PAGE_DOWN),
                    edit(TextEditAction.BACKSPACE, width = 1.46f),
                ),
            ),
        ),
    )

    /** The trackpad surface over a bottom row; a placeholder until issue #39 lands. */
    val TRACKPAD: PanelLayoutSpec = PanelLayoutSpec(
        panel = PanelKind.TRACKPAD,
        grid = LayerSpec(
            rows = listOf(
                listOf(field(PanelFieldKind.TRACKPAD, 10f)),
                bottomRow,
            ),
            rowHeights = listOf(3f, 1f),
        ),
    )

    val byKind: Map<PanelKind, PanelLayoutSpec> = mapOf(
        PanelKind.EMOJI to EMOJI,
        PanelKind.CLIPBOARD to CLIPBOARD,
        PanelKind.TEXT_EDIT to TEXT_EDIT,
        PanelKind.TRACKPAD to TRACKPAD,
    )

    fun default(kind: PanelKind): PanelLayoutSpec = byKind.getValue(kind)
}
