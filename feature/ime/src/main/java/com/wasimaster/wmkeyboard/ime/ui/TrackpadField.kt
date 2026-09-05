package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.ime.R

/**
 * The trackpad component's cell, ahead of the trackpad tool itself (issue #39).
 *
 * The panel layout system defines the seam now — the kind, the shipped grid, this
 * cell — so the tool can be built against it. Until then the cell is a dimmed
 * surface that says so and takes no input.
 */
@Composable
internal fun TrackpadField() {
    val kb = LocalKbTheme.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(kb.modifierKey.copy(alpha = 0.35f), kb.keyShape()),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.OpenWith,
                contentDescription = null,
                tint = kb.modifierKeyText.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(R.string.ime_trackpad_coming_soon),
                color = kb.modifierKeyText.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
        }
    }
}
