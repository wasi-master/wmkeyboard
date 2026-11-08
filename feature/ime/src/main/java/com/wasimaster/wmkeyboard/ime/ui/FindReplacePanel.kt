package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.FindOption
import com.wasimaster.wmkeyboard.ime.FindReplaceField
import com.wasimaster.wmkeyboard.ime.FindReplaceUi
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.MatchPreview
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.R

/** What the Find and replace panel hands back; one bundle, see [ToolHoldCallbacks]. */
data class FindReplaceCallbacks(
    val onFocusField: (FindReplaceField) -> Unit = {},
    val onToggle: (FindOption) -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onReplaceAll: () -> Unit = {},
    val onUndo: () -> Unit = {},
    val onHistoryPick: (String) -> Unit = {},
    val onHistoryClear: () -> Unit = {},
    /** A field's ×: empty it. */
    val onClear: (FindReplaceField) -> Unit = {},
)

/**
 * The panel's height: three rows of controls and the line under them that
 * shows the current match, or the recent searches while there is no query.
 */
internal val FindReplacePanelHeight = 164.dp

/**
 * Find and replace over the focused field.
 *
 * Two fake fields (the keys route into whichever is focused, so the platform
 * draws no caret of its own), the toggles, and the buttons. The current match
 * is kept selected in the field itself, and drawn again in its line at the
 * bottom (#627), since the keyboard often covers the part of the field it is
 * in; the count line says which one of how many. With no query that bottom
 * line offers the recent searches instead. Nothing here reads the field: the
 * service extracts it, matches on a background thread and publishes the
 * ranges.
 */
@Composable
internal fun FindReplacePanel(state: KeyboardUiState, callbacks: FindReplaceCallbacks) {
    val fr = state.findReplace ?: return
    val kb = LocalKbTheme.current
    val chipShape = RoundedCornerShape(12.dp)
    val focusedField = state.focusedIndex(FocusRegion.SEARCH)
    val focusedToggle = state.focusedIndex(FocusRegion.CHIPS)
    val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
    val canStep = fr.matches.isNotEmpty() && fr.addressable
    val canReplace = fr.current >= 0 && fr.addressable
    val canReplaceAll = fr.matches.isNotEmpty() && !fr.truncated
    val motion = kb.motion.any

    // Which way the last step went, so the match line slides the way the
    // arrows point; a wrap from the last match to the first still reads as
    // "next". Replace All bumps [replaceAllPulse] to pulse the count once.
    var stepDirection by remember { mutableIntStateOf(1) }
    var replaceAllPulse by remember { mutableIntStateOf(0) }
    val countScale = remember { Animatable(1f) }
    LaunchedEffect(replaceAllPulse) {
        if (replaceAllPulse == 0 || !motion) return@LaunchedEffect
        countScale.snapTo(1f)
        countScale.animateTo(COUNT_PULSE_SCALE, tween(COUNT_PULSE_UP_MS))
        countScale.animateTo(1f, tween(COUNT_PULSE_DOWN_MS))
    }
    val previous = {
        stepDirection = -1
        callbacks.onPrevious()
    }
    val next = {
        stepDirection = 1
        callbacks.onNext()
    }
    val replaceAll = {
        replaceAllPulse++
        callbacks.onReplaceAll()
    }

    PanelFocusTarget(panel = PanelMode.FIND_REPLACE, region = FocusRegion.SEARCH, count = 2, columns = 2) { index ->
        callbacks.onFocusField(if (index == 0) FindReplaceField.FIND else FindReplaceField.REPLACE)
    }
    PanelFocusTarget(
        panel = PanelMode.FIND_REPLACE,
        region = FocusRegion.CHIPS,
        count = FindOption.entries.size,
        columns = FindOption.entries.size,
    ) { index ->
        callbacks.onToggle(FindOption.entries[index])
    }
    PanelFocusTarget(panel = PanelMode.FIND_REPLACE, region = FocusRegion.ACTIONS, count = 5, columns = 5) { index ->
        when (index) {
            0 -> if (canStep) previous()
            1 -> if (canStep) next()
            2 -> if (canReplace) callbacks.onReplace()
            3 -> if (canReplaceAll) replaceAll()
            else -> if (fr.undoDepth > 0) callbacks.onUndo()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Never less than its rows need (#688). Taking the field's own
            // suggestion strip (#161) out of this height kept the window from
            // growing a row, but cut the toggles and the match line in half;
            // a larger font or display size cut them off outright.
            .heightIn(min = FindReplacePanelHeight)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueryPill(
                text = fr.query,
                highlighted = FindReplaceField.FIND in fr.remembered,
                placeholder = stringResource(R.string.ime_find_hint),
                active = fr.focused == FindReplaceField.FIND,
                ringed = focusedField == 0,
                modifier = Modifier.weight(1f),
                onClear = { callbacks.onClear(FindReplaceField.FIND) },
            ) { callbacks.onFocusField(FindReplaceField.FIND) }
            Spacer(Modifier.width(6.dp))
            val previousDesc = stringResource(R.string.ime_find_prev_desc)
            val nextDesc = stringResource(R.string.ime_find_next_desc)
            ToolPanelChip(
                "‹",
                enabled = canStep,
                modifier = Modifier
                    .focusRing(focusedAction == 0, chipShape)
                    .semantics { contentDescription = previousDesc },
            ) { previous() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                "›",
                enabled = canStep,
                modifier = Modifier
                    .focusRing(focusedAction == 1, chipShape)
                    .semantics { contentDescription = nextDesc },
            ) { next() }
            Spacer(Modifier.width(8.dp))
            val count = FindCount(
                text = when {
                    fr.searching -> stringResource(R.string.ime_find_searching)
                    fr.query.isEmpty() -> ""
                    fr.matches.isEmpty() -> stringResource(R.string.ime_find_no_matches)
                    fr.current >= 0 -> stringResource(R.string.ime_find_count, fr.current + 1, fr.matches.size)
                    else -> stringResource(R.string.ime_find_count_none_selected, fr.matches.size)
                },
                rank = fr.current,
            )
            val countText: @Composable (FindCount) -> Unit = {
                Text(
                    text = it.text,
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (motion) {
                // The count ticks over like an odometer: up as the match
                // moves on, down as it moves back.
                AnimatedContent(
                    targetState = count,
                    transitionSpec = { countTick() },
                    modifier = Modifier.graphicsLayer {
                        scaleX = countScale.value
                        scaleY = countScale.value
                    },
                    label = "findCount",
                ) { countText(it) }
            } else {
                countText(count)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            QueryPill(
                text = fr.replacement,
                highlighted = FindReplaceField.REPLACE in fr.remembered,
                placeholder = stringResource(R.string.ime_replace_hint),
                active = fr.focused == FindReplaceField.REPLACE,
                ringed = focusedField == 1,
                modifier = Modifier.weight(1f),
                onClear = { callbacks.onClear(FindReplaceField.REPLACE) },
            ) { callbacks.onFocusField(FindReplaceField.REPLACE) }
            Spacer(Modifier.width(6.dp))
            ToolPanelChip(
                stringResource(R.string.ime_find_replace_action),
                enabled = canReplace,
                modifier = Modifier.focusRing(focusedAction == 2, chipShape),
            ) { callbacks.onReplace() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(R.string.ime_find_replace_all_action),
                enabled = canReplaceAll,
                modifier = Modifier.focusRing(focusedAction == 3, chipShape),
            ) { replaceAll() }
            Spacer(Modifier.width(4.dp))
            ToolPanelChip(
                stringResource(com.wasimaster.wmkeyboard.common.R.string.common_undo),
                enabled = fr.undoDepth > 0,
                modifier = Modifier.focusRing(focusedAction == 4, chipShape),
            ) { callbacks.onUndo() }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Toggle(R.string.ime_find_case_label, R.string.ime_find_case_desc, fr.caseSensitive, focusedToggle == 0) {
                callbacks.onToggle(FindOption.CASE)
            }
            Spacer(Modifier.width(4.dp))
            Toggle(R.string.ime_find_whole_word_label, R.string.ime_find_whole_word_desc, fr.wholeWord, focusedToggle == 1) {
                callbacks.onToggle(FindOption.WHOLE_WORD)
            }
            Spacer(Modifier.width(4.dp))
            Toggle(R.string.ime_find_regex_label, R.string.ime_find_regex_desc, fr.regex, focusedToggle == 2) {
                callbacks.onToggle(FindOption.REGEX)
            }
            Spacer(Modifier.width(4.dp))
            Toggle(
                R.string.ime_find_preserve_case_label,
                R.string.ime_find_preserve_case_desc,
                fr.preserveCase,
                focusedToggle == 3,
            ) {
                callbacks.onToggle(FindOption.PRESERVE_CASE)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = fr.error ?: if (fr.truncated) stringResource(R.string.ime_find_too_many, fr.matches.size) else "",
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        when {
            (fr.query.isEmpty() || FindReplaceField.FIND in fr.remembered) && fr.history.isNotEmpty() ->
                HistoryRow(fr.history, callbacks)
            !motion -> fr.preview?.let { MatchLine(it) }
            // Keyed on which match it is, not on its text: a query edited
            // around the same match updates the line in place, a step to
            // another match slides the line across.
            else -> AnimatedContent(
                targetState = MatchSlide(fr.preview, fr.current),
                transitionSpec = { matchSlide(stepDirection) },
                contentKey = { it.current },
                label = "findMatch",
            ) { slide -> slide.preview?.let { MatchLine(it) } }
        }
    }
}

/** The count line's text, and which match it names so a step knows its direction. */
private data class FindCount(val text: String, val rank: Int)

/** The match line's content, keyed by the match's index. */
private data class MatchSlide(val preview: MatchPreview?, val current: Int)

private fun AnimatedContentTransitionScope<FindCount>.countTick(): ContentTransform {
    val sign = if (targetState.rank >= initialState.rank) 1 else -1
    return (slideInVertically(tween(COUNT_TICK_MS)) { sign * it / 2 } + fadeIn(tween(COUNT_TICK_MS))) togetherWith
        (slideOutVertically(tween(COUNT_TICK_OUT_MS)) { -sign * it / 2 } + fadeOut(tween(COUNT_TICK_OUT_MS))) using
        SizeTransform(clip = false)
}

private fun AnimatedContentTransitionScope<MatchSlide>.matchSlide(direction: Int): ContentTransform {
    val sign = if (direction >= 0) 1 else -1
    return (slideInHorizontally(tween(MATCH_SLIDE_MS)) { sign * it / 4 } + fadeIn(tween(MATCH_SLIDE_MS))) togetherWith
        (slideOutHorizontally(tween(MATCH_SLIDE_OUT_MS)) { -sign * it / 4 } + fadeOut(tween(MATCH_SLIDE_OUT_MS))) using
        null
}

private const val COUNT_TICK_MS = 160
private const val COUNT_TICK_OUT_MS = 120
private const val MATCH_SLIDE_MS = 180
private const val MATCH_SLIDE_OUT_MS = 130
private const val COUNT_PULSE_SCALE = 1.2f
private const val COUNT_PULSE_UP_MS = 90
private const val COUNT_PULSE_DOWN_MS = 140

/** The current match in its line, the match itself marked the way a selection is. */
@Composable
private fun MatchLine(preview: MatchPreview) {
    val kb = LocalKbTheme.current
    val text = remember(preview, kb.accent) {
        buildAnnotatedString {
            append(preview.text)
            addStyle(
                SpanStyle(background = kb.accent.copy(alpha = 0.3f), fontWeight = FontWeight.SemiBold),
                preview.match.first,
                preview.match.last + 1,
            )
        }
    }
    Text(
        text = text,
        color = kb.suggestionText,
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

/** Recent searches as chips, newest first, and one that forgets them. */
@Composable
private fun HistoryRow(history: List<String>, callbacks: FindReplaceCallbacks) {
    val description = stringResource(R.string.ime_find_history_desc)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        history.forEach { query ->
            ToolPanelChip(
                query,
                modifier = Modifier.semantics { contentDescription = "$description: $query" },
            ) { callbacks.onHistoryPick(query) }
        }
        ToolPanelChip(stringResource(R.string.ime_find_history_clear)) { callbacks.onHistoryClear() }
    }
}

@Composable
private fun QueryPill(
    text: String,
    highlighted: Boolean = false,
    placeholder: String,
    active: Boolean,
    ringed: Boolean,
    modifier: Modifier = Modifier,
    onClear: () -> Unit,
    onTap: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .focusRing(ringed, shape)
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchQueryText(
            query = text.replace("\n", " "),
            placeholder = placeholder,
            active = active,
            textColor = kb.suggestionText,
            placeholderColor = kb.secondaryText,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            selectedAll = highlighted,
        )
        // Every key edits the field one character at a time, so a long query
        // was a long hold on backspace; the × empties it in one tap.
        if (text.isNotEmpty()) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.ime_find_clear_desc, placeholder),
                tint = kb.secondaryText,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear),
            )
        }
    }
}

@Composable
private fun Toggle(labelRes: Int, descRes: Int, on: Boolean, ringed: Boolean, onClick: () -> Unit) {
    val description = stringResource(descRes)
    ToolPanelChip(
        stringResource(labelRes),
        selected = on,
        modifier = Modifier
            .focusRing(ringed, RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
        onClick = onClick,
    )
}
