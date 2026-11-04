package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymGroup
import com.wasimaster.wmkeyboard.core.thesaurus.SynonymSource
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.SynonymAction
import com.wasimaster.wmkeyboard.ime.SynonymsSheet
import com.wasimaster.wmkeyboard.ime.SynonymsStatus
import com.wasimaster.wmkeyboard.ime.caseLike
import kotlinx.coroutines.launch

/**
 * The synonyms for a held word (#321): what the held-word menu's Synonyms
 * opens. One chip per synonym, grouped by part of speech and, when the
 * source says, by meaning; tapping a chip puts that word where the held one
 * was, the same way picking it off the strip would, and closes the sheet.
 *
 * A [Popup] over the keyboard with a scrim, like [WordCardPopup] and for the
 * same reasons: the IME cannot raise a Compose Dialog, and the scrim keeps the
 * keys under it from typing while the sheet is up. Unlike the card it is only
 * as tall as its list, capped so a long list scrolls behind a rail.
 *
 * The chips wear the capitals of the word they replace ([SynonymsSheet.caseModel]),
 * so the sheet shows exactly what a tap will type.
 */
@Composable
internal fun SynonymsPopup(sheet: SynonymsSheet?, onAction: (SynonymAction) -> Unit) {
    GrowingPopupHost(sheet) { shown, growth ->
        // On its way out the sheet has already been closed or picked from;
        // nothing on it answers any more.
        SynonymsWindow(shown, growth) { action -> if (!growth.leaving) onAction(action) }
    }
}

/** The sheet's window, growing in from the strip and folding back ([growth]). */
@Composable
private fun SynonymsWindow(sheet: SynonymsSheet, growth: PopupGrowth, onAction: (SynonymAction) -> Unit) {
    val kb = LocalKbTheme.current
    val dismiss = { onAction(SynonymAction.Dismiss) }

    Popup(onDismissRequest = dismiss) {
        IconHintHost {
            GlassPopupBackdrop(LocalKbTheme.current, FrostSurface.MENUS)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .popupScrim(growth)
                    .pointerInput(Unit) { detectTapGestures { dismiss() } },
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = kb.menuShape(),
                    color = kb.popupFill(FrostSurface.MENUS),
                    border = kb.popupSurfaceBorder(),
                    shadowElevation = elevationFor(kb.menuShapeKind, 8.dp),
                    modifier = Modifier
                        .padding(12.dp)
                        .popupGrow(growth)
                        // As tall as its list, and no taller than the keyboard:
                        // the box it is centred in is the keyboard window.
                        .fillMaxWidth(0.94f)
                        // Taps inside the sheet must not reach the scrim.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .frostGlass(kb, kb.menuShape(), FrostSurface.MENUS),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.ime_synonyms_title, sheet.word),
                                style = MaterialTheme.typography.titleMedium,
                                color = kb.popupText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            IconHint(stringResource(R.string.ime_synonyms_close_desc)) {
                                IconButton(onClick = dismiss, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        Icons.Outlined.Close,
                                        contentDescription = stringResource(R.string.ime_synonyms_close_desc),
                                        tint = kb.popupText,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        when (val status = sheet.status) {
                            SynonymsStatus.Loading -> Loading(kb.popupText)
                            is SynonymsStatus.Found -> Found(sheet, status, kb, onAction)
                            SynonymsStatus.NotFound ->
                                Message(stringResource(R.string.ime_synonyms_not_found, sheet.word), kb.popupText)
                            SynonymsStatus.Failed -> {
                                Message(stringResource(OfflineBuild.network(R.string.ime_synonyms_failed)), kb.popupText)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { onAction(SynonymAction.Retry) }) {
                                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.ime_synonyms_retry))
                                    }
                                }
                            }
                            SynonymsStatus.NoSources ->
                                Message(stringResource(R.string.ime_synonyms_no_sources), kb.popupText)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
    ) {
        CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.ime_synonyms_loading), style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun ColumnScope.Found(
    sheet: SynonymsSheet,
    status: SynonymsStatus.Found,
    kb: KbTheme,
    onAction: (SynonymAction) -> Unit,
) {
    val color = kb.popupText
    if (status.source == SynonymSource.SIMILAR_WORDS) {
        Text(
            text = stringResource(R.string.ime_synonyms_similar_note),
            fontSize = 12.sp,
            color = color.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
    val rail = rememberScrollRailState(rememberScrollState())
    // One clock for every chip, so a long list costs one animation and not
    // one per chip; each chip reads its own slice of it in its layer.
    val motion = LocalKbTheme.current.motion.any
    val arrival = remember(status) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(arrival) { arrival.animateTo(1f, tween(ChipArrivalMs, easing = LinearEasing)) }
    ScrollRail(
        state = rail,
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        fadeColor = kb.popup,
        colors = kbRailColors(kb),
    ) {
        var first = 0
        for (group in status.groups) {
            Group(group, sheet.caseModel, kb, ChipArrival(arrival, first, motion), onAction)
            first += group.words.size
        }
    }
    Text(
        text = stringResource(R.string.ime_synonyms_from, stringResource(status.source.labelRes)),
        fontSize = 12.sp,
        color = color.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
}

/**
 * Where the chips are in arriving: [clock] runs 0 to 1 over [ChipArrivalMs],
 * and the chip at [first] plus n starts its own short rise a stagger after the
 * one before it, so the list fills in from the top rather than landing at once.
 */
@Stable
private class ChipArrival(
    private val clock: Animatable<Float, AnimationVector1D>,
    private val first: Int,
    val motion: Boolean,
) {
    /** How far chip [index] of its group is in, 0 to 1. */
    fun of(index: Int): Float {
        val ms = clock.value * ChipArrivalMs
        val start = minOf(first + index, ChipStaggerCap) * ChipStaggerMs
        return FastOutSlowInEasing.transform(((ms - start) / ChipRiseMs).coerceIn(0f, 1f))
    }
}

/** A part of speech, the meaning when the source gave one, and its synonyms as chips. */
@Composable
private fun Group(
    group: SynonymGroup,
    caseModel: String,
    kb: KbTheme,
    arrival: ChipArrival,
    onAction: (SynonymAction) -> Unit,
) {
    val color = kb.popupText
    val heading = listOfNotNull(group.pos.takeIf { it.isNotEmpty() }, group.sense).joinToString(" · ")
    if (heading.isNotEmpty()) {
        Text(
            text = heading,
            style = MaterialTheme.typography.labelMedium,
            color = color.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        group.words.forEachIndexed { index, word ->
            SynonymChip(word, caseModel, color, arrival, index, onAction)
        }
    }
}

/**
 * One synonym. A tap picks it at once, and the chip pulses while the sheet
 * folds away around it, so the eye lands on what was put in; the pick never
 * waits for the pulse.
 */
@Composable
private fun SynonymChip(
    word: String,
    caseModel: String,
    color: Color,
    arrival: ChipArrival,
    index: Int,
    onAction: (SynonymAction) -> Unit,
) {
    val shown = caseLike(word, caseModel)
    val label = stringResource(R.string.ime_synonyms_pick_desc, shown)
    val pulse = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val rise = with(LocalDensity.current) { ChipRise.toPx() }
    Text(
        text = shown,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .graphicsLayer {
                val p = arrival.of(index)
                val grow = 1f + ChipPulseGrow * pulse.value
                alpha = p
                translationY = (1f - p) * rise
                scaleX = grow
                scaleY = grow
            }
            .clip(ChipShape)
            .background(color.copy(alpha = 0.10f))
            .clickable {
                onAction(SynonymAction.Pick(word))
                if (arrival.motion) {
                    scope.launch {
                        pulse.animateTo(1f, tween(ChipPulseMs, easing = FastOutSlowInEasing))
                        pulse.animateTo(0f, tween(ChipPulseMs, easing = FastOutSlowInEasing))
                    }
                }
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

private val ChipShape = RoundedCornerShape(16.dp)

/** The chips' arrival: each rises over [ChipRiseMs], the next a stagger later, the whole well under 200 ms. */
private const val ChipRiseMs = 100f
private const val ChipStaggerMs = 12f
private const val ChipStaggerCap = 5
private const val ChipArrivalMs = 160

/** How far a chip lifts as it arrives. */
private val ChipRise = 6.dp

/** The picked chip's pulse: out and back in two halves, under the sheet's own way out. */
private const val ChipPulseMs = 65
private const val ChipPulseGrow = 0.08f
