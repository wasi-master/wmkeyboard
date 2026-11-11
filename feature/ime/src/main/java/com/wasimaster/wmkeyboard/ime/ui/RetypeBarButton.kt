package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.settings.RetypeButtonPlace
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * Whether the Retype button (#1189) is set to sit on [place] in this field.
 * Never in a password field, where the bars keep nothing of what was typed.
 * Whether its bar is up at all is the bar's own question.
 */
internal fun retypeButtonOn(state: KeyboardUiState, place: RetypeButtonPlace): Boolean =
    state.settings.suggestionStrip.retypeButton == place && !state.secureField

/**
 * The Retype button (#1189), at the logical end of the bar that holds it:
 * the right in a left-to-right layout, the left in a right-to-left one.
 * It always acts on the word at the cursor, never on a word shown on its
 * bar. An icon rather than the word (#1231), the same notepad-and-pencil
 * the Retype key wears, so it costs the bar one touch target; the word is
 * its spoken name.
 */
@Composable
internal fun RetypeBarButton(hold: SuggestionHoldCallbacks, modifier: Modifier = Modifier) {
    val kb = LocalKbTheme.current
    val action = stringResource(R.string.ime_retype_button_desc)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 48.dp)
            // The whole slot takes the touch, not just the chip drawn in it.
            .clickable(role = Role.Button, onClickLabel = action) { hold.onRetype() }
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(kb.chip)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.EditNote,
            contentDescription = stringResource(R.string.ime_retype_button),
            tint = kb.chipText,
            modifier = Modifier.size(22.dp),
        )
    }
}
