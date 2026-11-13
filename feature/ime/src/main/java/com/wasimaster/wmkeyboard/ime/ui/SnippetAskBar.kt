package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The fill-in row of a snippet with `{ask:Name}` fields, in the strip's place
 * while it waits for answers.
 *
 * One field at a time: its name, what has been typed for it, and a button that
 * moves on to the next field or, on the last, inserts the snippet. Enter does
 * the same. The ✕ inserts nothing. The keys type here through the capture
 * ladder ([com.wasimaster.wmkeyboard.ime.CaptureTarget.SNIPPET_ASK]), the way
 * they type into the panels' search pills, so the caret, selection, glide and
 * backspace all behave as they do there.
 */
@Composable
internal fun SnippetAskBar(
    state: KeyboardUiState,
    onNext: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ask = state.toolText.snippetAsk ?: return
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    val caret = state.captureCaret
    // The default arrives selected, so the first key types over it, as a
    // field shows text it is about to replace.
    val selectedAll = ask.text.isNotEmpty() && caret != null &&
        caret.text == ask.text && minOf(caret.at, caret.anchor) == 0 && maxOf(caret.at, caret.anchor) == ask.text.length
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings).coerceAtLeast(SNIPPET_ASK_MIN_HEIGHT))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconHint(stringResource(R.string.ime_snippet_ask_cancel_desc)) {
            IconButton(
                onClick = {
                    feedback()
                    onCancel()
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_snippet_ask_cancel_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // The field's name, and how far through the questions this is when
        // there are several.
        Text(
            text = if (ask.fields.size > 1) {
                stringResource(R.string.ime_snippet_ask_step_label, ask.field.name, ask.index + 1, ask.fields.size)
            } else {
                ask.field.name
            },
            color = kb.suggestionText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(kb.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchQueryText(
                query = ask.text,
                placeholder = stringResource(R.string.ime_snippet_ask_hint, ask.field.name),
                active = true,
                textColor = kb.modifierKeyText,
                placeholderColor = kb.toolbarIcon,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                selectedAll = selectedAll,
            )
        }
        val done = ask.last
        val action = stringResource(if (done) R.string.ime_snippet_ask_done_desc else R.string.ime_snippet_ask_next_desc)
        IconHint(action) {
            IconButton(
                onClick = {
                    feedback()
                    onNext()
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(kb.accent.copy(alpha = if (kb.dark) 0.28f else 0.16f)),
            ) {
                Icon(
                    if (done) Icons.Outlined.Check else Icons.Outlined.ChevronRight,
                    contentDescription = action,
                    tint = kb.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Tall enough for a finger on both buttons even when the strip is set small. */
private val SNIPPET_ASK_MIN_HEIGHT = 40.dp
