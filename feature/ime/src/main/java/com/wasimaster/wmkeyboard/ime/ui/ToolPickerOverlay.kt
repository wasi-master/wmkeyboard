package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.settings.builtinTools
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.wasimaster.wmkeyboard.core.settings.SuggestionHotkeyMode
import com.wasimaster.wmkeyboard.core.settings.usableTools
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import androidx.compose.material3.Icon
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
import com.wasimaster.wmkeyboard.core.tools.CheatGroup
import com.wasimaster.wmkeyboard.core.tools.CheatRow
import com.wasimaster.wmkeyboard.core.tools.CheatSheetLetter
import com.wasimaster.wmkeyboard.core.tools.DefaultLeader
import com.wasimaster.wmkeyboard.core.tools.cheatSheetRows
import com.wasimaster.wmkeyboard.core.tools.leaderLabel
import com.wasimaster.wmkeyboard.core.tools.parseLeader
import com.wasimaster.wmkeyboard.core.tools.parsePickModeKey
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R

/**
 * The full legend, after the leader and then `?`.
 *
 * There used to be a compact form as well: one line of every bound letter,
 * floated over the top bar the instant the picker armed. It named every binding
 * and pointed at none of them, and it covered the toolbar it was describing.
 * The badges under the buttons replaced it — a key drawn on the thing it opens
 * needs no legend. What is left here is the answer for what the badges cannot
 * show, which is every tool that is not on screen right now.
 *
 * Drawn over the top strip rather than as a [com.wasimaster.wmkeyboard.ime.PanelMode]
 * of its own. A panel replaces the keys, which would hide the very keyboard the
 * legend is documenting, and opening one resets twenty fields of panel state that
 * a read-only list has no business touching.
 */
@Composable
internal fun ToolPickerOverlay(
    state: KeyboardUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val showing = state.toolPicker?.cheatSheet == true
    AnimatedVisibility(visible = showing, modifier = modifier) {
        val kb = LocalKbTheme.current
        val config = state.settings.hardwareKeyboard
        // A chord spells itself, so it arrives with no template around it.
        val leaderParts = leaderLabel(parseLeader(config.leader) ?: DefaultLeader)
        val leader = if (leaderParts.templateRes == 0) {
            leaderParts.text
        } else {
            stringResource(leaderParts.templateRes, leaderParts.text)
        }
        // Spelled the same way as the leader: a chord names itself, a double
        // tap needs its wording around the modifier.
        val pickModeKeyText = parsePickModeKey(config.pickModeKey)?.let { trigger ->
            val parts = leaderLabel(trigger)
            if (parts.templateRes == 0) parts.text else stringResource(parts.templateRes, parts.text)
        }
        Box(
            modifier = Modifier
                .padding(6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(kb.popup)
                .border(1.dp, kb.accent, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            CheatSheet(
                rows = cheatSheetRows(
                    letters = config.toolByLetter,
                    enabled = usableTools(state.settings),
                    toolbarTools = visibleToolbarTools(state).builtinTools(),
                    digitChord = config.toolbarDigitChord,
                    altSuggestionDigits =
                        config.suggestionHotkeys == SuggestionHotkeyMode.ALT_DIGIT,
                    languageSwitchChord = config.languageSwitchChord,
                    keysToggle = hardwareKeysToggleable(state),
                    pickModeKey = pickModeKeyText,
                    pickMode = state.hardwarePickMode,
                    functionKeys = functionKeysPressToolbar(state),
                ),
                leader = leader,
                onClose = onClose,
            )
        }
    }
}

/**
 * The way in to the legend: a small `?` pill at the end of the strip for as long
 * as the picker is armed.
 *
 * The cheat sheet has always been one keypress away and nobody knew, because
 * nothing on screen said so. The badges under the buttons cover the tools you
 * can see; this covers the ones you cannot.
 */
@Composable
internal fun PickerHelpPill(state: KeyboardUiState, modifier: Modifier = Modifier) {
    val picker = state.toolPicker
    val armed = picker != null && !picker.cheatSheet
    AnimatedVisibility(visible = armed, modifier = modifier) {
        val kb = LocalKbTheme.current
        Text(
            stringResource(R.string.ime_tool_picker_help, CheatSheetLetter),
            color = kb.accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .padding(4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(kb.popup)
                .border(1.dp, kb.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Every binding, laid out two columns wide so it fits the keyboard's height. */
@Composable
private fun CheatSheet(rows: List<CheatRow>, leader: String, onClose: () -> Unit) {
    val kb = LocalKbTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
        ) {
            Text(
                stringResource(R.string.ime_tool_picker_leader, leader),
                color = kb.accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            // Escape closes this and so does any bound key, but every one of
            // those needs the keyboard that opened it. Unplug it, or put it
            // down, and the legend is a box on screen with no way out.
            IconHint(stringResource(R.string.ime_tool_picker_close_desc)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.ime_tool_picker_close_desc),
                    tint = kb.secondaryText,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClose)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(rows) { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    // The trigger measures first and never yields: it is the
                    // whole point of the row, and a spelled-out "Shift+Q" was
                    // wrapping to a second line and losing its tail. The tool
                    // name is the one that gives up space, because a clipped
                    // name is still recognisable and half a key name is not.
                    Text(
                        row.trigger,
                        color = kb.toolCircleActiveIcon,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(kb.toolCircleActive)
                            .widthIn(min = 18.dp)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Text(
                        row.tool?.let { toolLabel(it) }
                            ?: row.labelRes.takeIf { it != 0 }?.let { stringResource(it) }.orEmpty(),
                        // The built-in actions are dimmer than the tools: they
                        // are the same four lines every time, and the tools are
                        // what the reader is scanning for.
                        color = if (row.group == CheatGroup.ACTIONS) kb.secondaryText else kb.popupText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}
