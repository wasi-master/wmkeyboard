package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.ime.R

/** The AI continuation chip's two doors (#944), carried on [ToolHoldCallbacks]. */
data class AiSuggestCallbacks(
    /** The face tapped: the continuation goes into the field. */
    val onTap: () -> Unit = {},
    /** The face held: the chip goes, and stays gone in this field. */
    val onHold: () -> Unit = {},
    /** The AI panel's model name pressed: ask the provider what it offers. */
    val onLoadModels: () -> Unit = {},
    /** One of those picked: the provider in use answers with it from now on. */
    val onPickModelName: (String) -> Unit = {},
)

/**
 * The AI continuation in the suggestion strip (#944): `[✦] the next few words`.
 *
 * Narrow, in front of the word candidates, and marked with the AI tool's
 * sparkle and an accent outline, so it never reads as a dictionary word: it
 * came from a model, cost a request, and may be wrong.
 */
@Composable
internal fun AiSuggestionChip(
    text: String,
    reduceMotion: Boolean,
    onAccept: () -> Unit,
    onSuppress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // Scale-in on arrival only; a new continuation replaces the text in place.
    val appear = remember { Animatable(if (reduceMotion) 1f else 0.92f) }
    LaunchedEffect(Unit) {
        if (!reduceMotion) appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val tint = kb.accent
    val shape = kb.chipShape()
    val description = stringResource(R.string.ime_ai_suggest_desc, text)
    Row(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 5.dp)
            .graphicsLayer {
                scaleX = appear.value
                scaleY = appear.value
                alpha = appear.value
            }
            .clip(shape)
            .background(tint.copy(alpha = if (kb.dark) 0.14f else 0.07f))
            .border(1.dp, tint.copy(alpha = 0.45f), shape)
            .combinedClickable(
                onClick = {
                    feedback()
                    onAccept()
                },
                onLongClick = {
                    feedback()
                    onSuppress()
                },
            )
            .semantics { contentDescription = description }
            .padding(start = 7.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            color = tint,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}
