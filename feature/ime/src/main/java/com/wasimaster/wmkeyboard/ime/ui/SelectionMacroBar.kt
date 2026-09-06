package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyRowItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.TextFormat
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.selection.SelectionMacros
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The selection macro bar: what the keyboard offers to do with what is
 * selected.
 *
 * Drawn either on a row of its own ([com.wasimaster.wmkeyboard.core.settings.BarRow.MACROS])
 * or over the suggestion strip, which is why it is a plain `RowScope`-free
 * composable taking the state and one callback. The two placements share every
 * pixel of it, including the case ladder below, so a user who moves the bar
 * from one to the other finds the same bar.
 */
@Composable
internal fun SelectionMacroBar(
    state: KeyboardUiState,
    onMacro: (SelectionMacro) -> Unit,
    modifier: Modifier = Modifier,
) {
    val offer = state.selectionMacros ?: return
    // Format on plain prose opens the case ladder instead of rewriting
    // anything, so which face the bar is showing is composition state.
    //
    // Keyed on the *kind* and not on the offer itself. Every case chip rewrites
    // the selection, which republishes a new offer a frame later, so keying on
    // the offer would shut the ladder after each tap and make lower-then-Title
    // two trips instead of two taps. The kind survives a rewrite, and a
    // selection cleared altogether takes the whole bar out of the composition,
    // which is what resets this for the next one.
    var caseOpen by remember(offer.kind) { mutableStateOf(false) }
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (caseOpen) {
            MacroBackButton(onClick = { caseOpen = false })
        }
        val macros = if (caseOpen) SelectionMacros.caseMacros else offer.macros
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            lazyRowItems(macros, key = { it.name }) { macro ->
                MacroChip(
                    macro = macro,
                    onClick = {
                        feedback()
                        // The one tap the service never sees: on prose there is
                        // nothing to reformat, so Format is the door to the
                        // case ladder rather than an action of its own.
                        if (macro == SelectionMacro.FORMAT && offer.kind == SelectionKind.TEXT) {
                            caseOpen = true
                        } else {
                            onMacro(macro)
                        }
                    },
                )
            }
        }
    }
}

/** Back out of the case ladder to the macros the selection was offered. */
@Composable
private fun MacroBackButton(onClick: () -> Unit) {
    val feedback = LocalKeyPressFeedback.current
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
        contentDescription = stringResource(R.string.ime_selection_macro_back_desc),
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable {
                feedback()
                onClick()
            }
            .padding(6.dp)
            .size(20.dp),
        tint = LocalKbTheme.current.suggestionText,
    )
}

/**
 * One macro, drawn as its glyph and its word.
 *
 * The word is always there: the glyphs for Share, Open and Format are close
 * enough to each other that an icon-only bar would be a guessing game, and this
 * bar appears rarely enough that nobody builds muscle memory for it.
 */
@Composable
private fun MacroChip(macro: SelectionMacro, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val label = stringResource(macro.labelRes)
    val icon = macroIcon(macro)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(kb.chipActive)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = kb.chipActiveText,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            fontSize = 14.sp,
            color = kb.chipActiveText,
            maxLines = 1,
        )
    }
}

/**
 * The glyph for a macro, or null for the case options.
 *
 * The four case chips are written in the case they apply ("UPPER CASE",
 * "Sentence case"), which already shows what each one does; an icon beside
 * them would say it a second time in a worse language.
 */
private fun macroIcon(macro: SelectionMacro): ImageVector? = when (macro) {
    SelectionMacro.COPY -> Icons.Outlined.ContentCopy
    SelectionMacro.SHARE -> Icons.Outlined.Share
    SelectionMacro.FORMAT -> Icons.Outlined.TextFormat
    SelectionMacro.SEARCH -> Icons.Outlined.Search
    SelectionMacro.TRANSLATE -> Icons.Outlined.Translate
    SelectionMacro.CALL -> Icons.Outlined.Call
    SelectionMacro.SMS -> Icons.Outlined.Sms
    SelectionMacro.WHATSAPP -> Icons.Outlined.Chat
    SelectionMacro.EMAIL -> Icons.Outlined.Mail
    SelectionMacro.OPEN -> Icons.Outlined.OpenInNew
    SelectionMacro.QR -> Icons.Outlined.QrCode2
    else -> null
}

/**
 * Whether the macro bar has anything to draw right now.
 *
 * Read by both hosts and by the strip, which has to know before it decides
 * which surface owns the row. The offer is only ever published while the
 * feature is on and something is selected, so this is the whole question.
 */
internal fun selectionMacroBarVisible(state: KeyboardUiState): Boolean =
    state.selectionMacros != null
