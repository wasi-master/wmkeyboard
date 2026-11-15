package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The row the floating button's Write a clip puts in the strip's place
 * (#1339): a clip being typed by hand, for text on screen that cannot be
 * selected, such as a video's caption or a tooltip that vanishes at a touch.
 *
 * The clip is shown whole, on one line that scrolls sideways to keep the
 * caret in view, rather than cut short as the paste chip cuts a copy. The
 * keys type here through the capture ladder
 * ([com.wasimaster.wmkeyboard.ime.CaptureTarget.CLIP_WRITE]), so the caret,
 * selection, glide and backspace behave as they do in any of the keyboard's
 * own boxes, and nothing reaches the app behind. The tick, Enter or Back
 * keeps the clip; the ✕ throws it away. Nothing else opens over the app.
 */
@Composable
internal fun ClipWriteBar(
    state: KeyboardUiState,
    onDone: (keep: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = state.toolText.clipWrite ?: return
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings).coerceAtLeast(CLIP_WRITE_MIN_HEIGHT))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconHint(stringResource(R.string.ime_clip_write_cancel_desc)) {
            IconButton(
                onClick = {
                    feedback()
                    onDone(false)
                },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_clip_write_cancel_desc),
                    tint = kb.toolbarIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(kb.chip)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Says what the box is for, the way the paste chip's own icon does.
            Icon(
                Icons.Outlined.EditNote,
                contentDescription = null,
                tint = kb.toolbarIcon,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
            )
            SearchQueryText(
                query = text,
                placeholder = stringResource(R.string.ime_clip_write_hint),
                active = true,
                textColor = kb.modifierKeyText,
                placeholderColor = kb.toolbarIcon,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
            )
        }
        val keep = stringResource(R.string.ime_clip_write_keep_desc)
        IconHint(keep) {
            IconButton(
                onClick = {
                    feedback()
                    onDone(true)
                },
                enabled = text.isNotBlank(),
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(kb.accent.copy(alpha = if (kb.dark) 0.28f else 0.16f)),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = keep,
                    tint = if (text.isNotBlank()) kb.accent else kb.toolbarIcon,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Tall enough for a finger on both buttons even when the strip is set small. */
private val CLIP_WRITE_MIN_HEIGHT = 40.dp
