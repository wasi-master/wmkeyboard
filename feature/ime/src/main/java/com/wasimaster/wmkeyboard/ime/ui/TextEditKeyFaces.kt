package com.wasimaster.wmkeyboard.ime.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowRight
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.ime.R

/**
 * What a text-editing key ([com.wasimaster.wmkeyboard.core.layout.KeyAction.Edit])
 * wears: its icon, the word for the ones an icon does not explain, and the
 * spoken name TalkBack reads.
 *
 * These used to be the faces of the text-editing panel's own buttons. The panel
 * is a panel layout now (issue #63) and its keys are ordinary keys drawn by the
 * key grid, so the faces moved here for `KeyContent`, the alternates popup and
 * the layout editor's preview to share.
 */

/** The glyph each operation draws, or null for the ones that read better as a word. */
fun textEditIcon(op: TextEditAction): ImageVector? = when (op) {
    TextEditAction.LEFT -> Icons.AutoMirrored.Outlined.KeyboardArrowLeft
    TextEditAction.RIGHT -> Icons.AutoMirrored.Outlined.KeyboardArrowRight
    TextEditAction.UP -> Icons.Outlined.KeyboardArrowUp
    TextEditAction.DOWN -> Icons.Outlined.KeyboardArrowDown
    TextEditAction.WORD_LEFT -> Icons.Outlined.KeyboardDoubleArrowLeft
    TextEditAction.WORD_RIGHT -> Icons.Outlined.KeyboardDoubleArrowRight
    TextEditAction.PAGE_UP -> Icons.Outlined.KeyboardDoubleArrowUp
    TextEditAction.PAGE_DOWN -> Icons.Outlined.KeyboardDoubleArrowDown
    TextEditAction.HOME -> Icons.Outlined.FirstPage
    TextEditAction.END -> Icons.AutoMirrored.Outlined.LastPage
    TextEditAction.SELECT_ALL -> Icons.Outlined.SelectAll
    TextEditAction.SELECT_WORD -> Icons.AutoMirrored.Outlined.ShortText
    TextEditAction.SELECT_LINE -> Icons.AutoMirrored.Outlined.Subject
    TextEditAction.COPY -> Icons.Outlined.ContentCopy
    TextEditAction.PASTE -> Icons.Outlined.ContentPaste
    TextEditAction.BACKSPACE -> Icons.AutoMirrored.Outlined.Backspace
    TextEditAction.DOC_START -> Icons.Outlined.VerticalAlignTop
    TextEditAction.DOC_END -> Icons.Outlined.VerticalAlignBottom
    TextEditAction.CUT -> Icons.Outlined.ContentCut
    // Select is a toggle rather than a move: it reads as a word, and being lit
    // is what says it is on.
    TextEditAction.SELECT -> null
}

/** The word on a key whose operation has no icon. */
@Composable
internal fun textEditLabel(op: TextEditAction): String = when (op) {
    TextEditAction.SELECT -> stringResource(R.string.ime_textedit_select_label)
    TextEditAction.SELECT_ALL -> stringResource(CommonR.string.common_select_all)
    TextEditAction.COPY -> stringResource(CommonR.string.common_copy)
    TextEditAction.PASTE -> stringResource(CommonR.string.common_paste)
    TextEditAction.CUT -> stringResource(CommonR.string.common_cut)
    else -> stringResource(textEditDescription(op))
}

/** The spoken name of each operation. */
@StringRes
internal fun textEditDescription(op: TextEditAction): Int = when (op) {
    TextEditAction.LEFT -> R.string.ime_textedit_left_desc
    TextEditAction.RIGHT -> R.string.ime_textedit_right_desc
    TextEditAction.UP -> R.string.ime_textedit_up_desc
    TextEditAction.DOWN -> R.string.ime_textedit_down_desc
    TextEditAction.WORD_LEFT -> R.string.ime_textedit_word_left_desc
    TextEditAction.WORD_RIGHT -> R.string.ime_textedit_word_right_desc
    TextEditAction.PAGE_UP -> R.string.ime_textedit_page_up_desc
    TextEditAction.PAGE_DOWN -> R.string.ime_textedit_page_down_desc
    TextEditAction.HOME -> R.string.ime_textedit_home_desc
    TextEditAction.END -> R.string.ime_textedit_end_desc
    TextEditAction.SELECT -> R.string.ime_textedit_select_desc
    TextEditAction.SELECT_ALL -> CommonR.string.common_select_all
    TextEditAction.SELECT_WORD -> R.string.ime_textedit_select_word_desc
    TextEditAction.SELECT_LINE -> R.string.ime_textedit_select_line_desc
    TextEditAction.COPY -> CommonR.string.common_copy
    TextEditAction.PASTE -> CommonR.string.common_paste
    TextEditAction.BACKSPACE -> CommonR.string.common_delete
    TextEditAction.DOC_START -> R.string.ime_textedit_doc_start_desc
    TextEditAction.DOC_END -> R.string.ime_textedit_doc_end_desc
    TextEditAction.CUT -> CommonR.string.common_cut
}
