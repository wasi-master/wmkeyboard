package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A word's rank in a small circle at its chip's corner (#1249): the steps the
 * user moved it, signed. Drawn in the strip's secondary text on its chip
 * colour, small enough to sit in the slot's padding without touching the word.
 */
@Composable
internal fun RankMark(steps: Int, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    Box(
        modifier = modifier
            .padding(top = 2.dp, end = 2.dp)
            .size(RankMarkSize)
            .clip(CircleShape)
            .background(kb.chip),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            rankMarkLabel(steps),
            color = kb.secondarySuggestionText,
            fontSize = 8.sp,
            lineHeight = 8.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/** "+2", "−1": the sign says which way, a true minus rather than a hyphen. */
internal fun rankMarkLabel(steps: Int): String = if (steps > 0) "+$steps" else "−${-steps}"

private val RankMarkSize = 14.dp
