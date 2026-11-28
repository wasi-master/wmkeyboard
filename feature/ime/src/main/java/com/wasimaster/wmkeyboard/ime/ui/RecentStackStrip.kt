package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.ArrowForward
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.stripHidden
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.RecentBarWord
import com.wasimaster.wmkeyboard.ime.WordMenuAction
import com.wasimaster.wmkeyboard.ime.cycleCaseForShift
import com.wasimaster.wmkeyboard.ime.displayCaseForShift
import com.wasimaster.wmkeyboard.ime.shiftCyclesCase
import com.wasimaster.wmkeyboard.ime.shiftForGlide

/** Whether the recent words are stacked in the strip (see [RecentStackStrip]). */
internal fun recentStackOn(settings: KeyboardSettings): Boolean =
    settings.suggestionStrip.recentWordBar &&
        settings.rows.recentWordPlacement == RecentWordPlacement.STACKED

/** [recentStackOn] for the keyboard's state. */
internal fun recentStackOn(state: KeyboardUiState): Boolean = recentStackOn(state.settings)

/**
 * Whether the stacked strip has words written to show. They hold the strip
 * over the tools the way a chip does: the word that needs fixing is often
 * noticed after its suggestions have gone.
 */
internal fun recentStackShown(state: KeyboardUiState): Boolean =
    recentStackOn(state) &&
        (state.recentBar.isNotEmpty() || state.recentBarAfter.isNotEmpty() || state.recentSelected != null)

/**
 * What the stacked strip adds to the bar's height for its three lines, a
 * word with one reading above and one below it. Nothing in the other
 * placements, or with the strip given up for the tools.
 */
internal fun recentStackExtra(settings: KeyboardSettings): Dp =
    if (recentStackOn(settings) && !settings.toolbarBehavior.stripHidden) RecentStackExtra else 0.dp

/** See [recentStackExtra]. */
internal val RecentStackExtra = 16.dp

/** The stacked strip's word size when the theme names none; the strip's own. */
private val StackFontSize = 16.sp

/** The readings above and below a word, against the word's own size. */
private const val StackAltScale = 0.72f

/** The narrowest column: a short word is still a whole touch target. */
private val StackColumnMinWidth = 44.dp

/** Columns of later words kept in view right of the word being typed, when there are any. */
private const val StackColumnsAfterLive = 2

/** The two text styles of a column: the word, and a reading above or below it. */
private class StackStyles(val word: TextStyle, val alt: TextStyle)

/**
 * The suggestion strip with the recent words stacked in it, Nintype style.
 * Every word written in the field is a column, its other readings above and
 * below it; the word being typed is a column too, its best suggestion in the
 * middle on a pill and the next two above and below. The newest word is on
 * the right and older ones scroll away to the left.
 *
 * A reading above or below a word replaces that word where it stands. The
 * word itself selects it in the field, so whatever is typed, swiped or
 * picked next goes in its place; its column is then the live one, and the
 * words after it stay on the strip to its right, with an arrow at the end
 * that takes the caret back to where the writing left off.
 *
 * [live] is the strip's own word list, the same one the slots would show,
 * and taps on it go to [onSuggestion] like a slot's. Holding one of those
 * opens its word card.
 */
@Composable
internal fun RowScope.RecentStackStrip(
    state: KeyboardUiState,
    live: List<String>,
    enabled: Boolean,
    alpha: () -> Float,
    weight: Float,
    textScale: Float,
    onSuggestion: (String) -> Unit,
    hold: SuggestionHoldCallbacks,
) {
    val kb = LocalKbTheme.current
    val baseStyle = LocalTextStyle.current.inRoleFont(LocalBoardFonts.current.strip)
    val size: TextUnit = (if (baseStyle.fontSize.isSpecified) baseStyle.fontSize else StackFontSize) * textScale
    val styles = remember(baseStyle, size) {
        StackStyles(
            word = baseStyle.merge(TextStyle(fontSize = size, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)),
            alt = baseStyle.merge(TextStyle(fontSize = size * StackAltScale, textAlign = TextAlign.Center)),
        )
    }
    val before = state.recentBar
    val after = state.recentBarAfter
    // The word the strip selected is the live column while nothing is typed
    // over it; the first letter or stroke hands the column to the strip's words.
    val selected = state.recentSelected?.takeIf { state.composingPreview.isEmpty() && state.glideWord == null }
    // Cased as the slots would case them: a glide's own capitals, then a
    // shift that cycles the case, then the shift the next letter takes.
    val shift = shiftForGlide(state.shiftState, state.glideCase)
    val cycles = state.shiftCyclesCase()
    val display: (String) -> String = { word ->
        state.glideCased[word] ?: if (cycles) cycleCaseForShift(word, state.shiftState) else displayCaseForShift(word, shift)
    }
    val listState = rememberLazyListState()
    // Back to the word being typed whenever a word is written or the caret
    // jumps: the strip is laid out from its right end (newest first), so the
    // live column is item [after.size], with the later words right of it.
    LaunchedEffect(before.lastOrNull()?.start, after.size, selected?.start) {
        listState.scrollToItem((after.size - StackColumnsAfterLive).coerceAtLeast(0))
    }
    Row(
        modifier = Modifier.weight(weight).fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Keyed by place and side: two copies of a word are two columns,
            // and a pick must reach the one it was drawn for.
            items(after.asReversed(), key = { "a${it.start}" }) { word ->
                HistoryColumn(word, live = false, styles = styles, hold = hold)
            }
            item(key = "live") {
                if (selected != null) {
                    HistoryColumn(selected, live = true, styles = styles, hold = hold)
                } else if (live.isNotEmpty()) {
                    StackColumn(
                        words = listOf(live.getOrNull(1), live[0], live.getOrNull(2)),
                        display = display,
                        pill = kb.primarySuggestionPill,
                        styles = styles,
                        labels = listOf(null, null, null),
                        enabled = enabled,
                        modifier = Modifier.graphicsLayer { this.alpha = alpha() },
                        onTap = { _, word -> onSuggestion(word) },
                        onHold = { word -> hold.onMenu(WordMenuAction.Open(word)) },
                    )
                }
            }
            items(before.asReversed(), key = { "b${it.start}" }) { word ->
                HistoryColumn(word, live = false, styles = styles, hold = hold)
            }
        }
        if (after.isNotEmpty()) {
            val back = stringResource(R.string.ime_recent_return_desc)
            IconHint(back) {
                IconButton(onClick = hold.onRecentReturn) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        contentDescription = back,
                        tint = kb.suggestionText,
                    )
                }
            }
        }
    }
}

/**
 * A word written earlier, as a column: its readings above and below replace
 * it, and the word itself selects it. As the [live] column (the word the
 * strip selected) it sits on the pill and the word takes no tap, since it is
 * already selected.
 */
@Composable
private fun HistoryColumn(
    word: RecentBarWord,
    live: Boolean,
    styles: StackStyles,
    hold: SuggestionHoldCallbacks,
) {
    val above = word.offers.getOrNull(0)
    val below = word.offers.getOrNull(1)
    val labels = listOf(
        above?.let { stringResource(R.string.ime_recent_word_replace_desc, word.word, it) },
        if (live) null else stringResource(R.string.ime_recent_word_select_desc, word.word),
        below?.let { stringResource(R.string.ime_recent_word_replace_desc, word.word, it) },
    )
    StackColumn(
        words = listOf(above, word.word, below),
        display = { it },
        pill = if (live) LocalKbTheme.current.primarySuggestionPill else null,
        styles = styles,
        labels = labels,
        enabled = true,
        wordTaps = !live,
        onTap = { slot, picked ->
            if (slot == 1) {
                hold.onRecentSelect(word.start, word.word)
            } else {
                hold.onRecentPick(word.start, word.word, picked)
            }
        },
    )
}

/**
 * One column of the stacked strip: [words] is the reading above, the word,
 * and the reading below, each a touch target of its own, null where there is
 * none. The middle one sits on [pill] when the column is the live one.
 */
@Composable
private fun StackColumn(
    words: List<String?>,
    display: (String) -> String,
    pill: Color?,
    styles: StackStyles,
    labels: List<String?>,
    enabled: Boolean,
    onTap: (slot: Int, word: String) -> Unit,
    modifier: Modifier = Modifier,
    wordTaps: Boolean = true,
    onHold: ((String) -> Unit)? = null,
) {
    val kb = LocalKbTheme.current
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(IntrinsicSize.Max)
            .widthIn(min = StackColumnMinWidth)
            .padding(horizontal = 2.dp),
    ) {
        for (slot in 0..2) {
            val word = words.getOrNull(slot)
            val middle = slot == 1
            val taps = word != null && (!middle || wordTaps)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (middle) 1.3f else 1f)
                    .then(
                        if (middle && pill != null) {
                            Modifier.padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(pill)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (taps && word != null) {
                            Modifier.combinedClickable(
                                enabled = enabled,
                                onClickLabel = labels.getOrNull(slot),
                                onLongClick = onHold?.let { held -> { held(word) } },
                            ) { onTap(slot, word) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (word != null) {
                    CachedTextLine(
                        text = AnnotatedString(display(word)),
                        style = if (middle) styles.word else styles.alt,
                        color = if (middle) kb.suggestionText else kb.secondarySuggestionText,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
