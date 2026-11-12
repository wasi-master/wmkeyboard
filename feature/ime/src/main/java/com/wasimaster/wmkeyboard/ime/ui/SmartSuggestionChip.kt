package com.wasimaster.wmkeyboard.ime.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sms
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest
import com.wasimaster.wmkeyboard.ime.R

/**
 * The chip the suggestion strip shows when the text before the cursor is
 * something a tool can answer.
 *
 * Two shapes, one look. An answer chip fills the strip —
 * `[icon] 150 USD → 18,300.00 BDT   [⚙]` — because when you have just typed
 * an amount the conversion beats any word the dictionary could offer; the
 * trailing button hands the same numbers to the full tool. A keyword chip
 * ("wiki" → open Wikipedia) stays narrow and lets the word candidates keep
 * the rest of the strip, since the word being typed may well be a word.
 */
@Composable
internal fun SmartSuggestionChip(
    hit: SmartSuggest.SmartHit,
    reduceMotion: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onAccept: () -> Unit,
    onOpen: () -> Unit,
    /** A hold on the face, for a chip with a second door (the vocabulary swap). */
    onLongPress: (() -> Unit)? = null,
    /**
     * False while [SmartSuggest.SmartHit.tool] is switched off. An answer chip
     * still types its answer, but the gear would open nothing, so it is not
     * drawn (#176).
     */
    canOpen: Boolean = true,
) {
    val kb = LocalKbTheme.current
    val feedback = LocalKeyPressFeedback.current
    // Scale-in on appearance only. The key is the chip's kind, so the
    // animation plays when a chip arrives or changes character and not on
    // every keystroke that merely moves the number.
    val appear = remember(hit.kind) { Animatable(if (reduceMotion) 1f else 0.92f) }
    LaunchedEffect(hit.kind) {
        if (!reduceMotion) {
            appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }
    val tint = kb.accent
    val fill = tint.copy(alpha = if (kb.dark) 0.20f else 0.11f)
    val keyword = hit.kind in SmartSuggest.narrowKinds
    // Narrow, but with a second door: the face calls, the button texts (#656).
    val contact = hit.kind == SmartSuggest.Kind.CONTACT
    // Narrow, with a count that ticks up as the same text is typed again.
    val repeat = hit.kind == SmartSuggest.Kind.REPEAT

    // The accent tint is this chip's identity (it is an *answer*, not a word),
    // so the colours stay accent-based; only the outline follows the theme's
    // chip shape.
    val shape = kb.chipShape()
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
            .background(fill)
            .border(1.dp, tint.copy(alpha = 0.32f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .then(if (keyword) Modifier else Modifier.weight(1f))
                .fillMaxHeight()
                .combinedClickable(
                    onClick = {
                        feedback()
                        onAccept()
                    },
                    onLongClick = onLongPress?.let { hold -> { feedback(); hold() } },
                )
                .padding(start = 6.dp, end = if (keyword) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            if (keyword) {
                Text(
                    text = narrowChipLabel(hit),
                    color = tint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
                if (repeat) RepeatCountPill(hit.result.orEmpty(), tint, reduceMotion)
                if (!contact) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = tint.copy(alpha = 0.7f),
                        modifier = Modifier.size(15.dp),
                    )
                }
            } else if (hit.result == null) {
                // Rates or a forecast still missing. The chip is already up so
                // the strip does not jump when the answer lands. It spins while
                // that is fetched, or asks for the tap that fetches it.
                Text(
                    text = hit.query,
                    color = kb.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Text("→", color = kb.secondaryText, fontSize = 12.sp)
                if (hit.awaitingTap) {
                    Text(
                        text = stringResource(
                            if (hit.kind == SmartSuggest.Kind.WEATHER) {
                                R.string.ime_smart_weather_tap
                            } else {
                                R.string.ime_smart_rates_tap
                            },
                        ),
                        color = tint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.5.dp,
                        color = tint,
                    )
                }
            } else {
                // The hit carries its text in tiers, widest first. Measure
                // each against the space the row actually has and show the
                // richest one that fits — degrading the strings first, then
                // the font, then the spelled-out amount (see ChipTier).
                BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val tiers = remember(hit) {
                        hit.tiers.ifEmpty { listOf(SmartSuggest.ChipTier(hit.query, hit.result.orEmpty())) }
                    }
                    val (tier, small) = remember(tiers, maxWidth) {
                        chooseTier(tiers, measurer, density, maxWidth)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = tier.query,
                            color = kb.secondaryText,
                            fontSize = if (small) 11.sp else 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 160.dp),
                        )
                        Text("→", color = kb.secondaryText, fontSize = if (small) 10.sp else 12.sp)
                        Text(
                            text = tier.result,
                            color = tint,
                            fontSize = if (small) 13.sp else 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        if ((!keyword || contact) && canOpen) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 7.dp)
                    .background(tint.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable {
                        feedback()
                        onOpen()
                    }
                    .padding(horizontal = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (contact) Icons.Outlined.Sms else Icons.Outlined.Tune,
                    contentDescription = if (contact) {
                        stringResource(R.string.ime_smart_contact_message, hit.query)
                    } else {
                        stringResource(R.string.ime_smart_open_tool, toolLabel(hit.tool))
                    },
                    tint = tint.copy(alpha = 0.85f),
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/**
 * What a narrow chip says. A keyword chip names the tool it opens; a lookup
 * chip names the term it will look up; an intent chip names the job — "Check
 * grammar" rather than "Open Grammar", because the chip is answering the
 * text, not labelling a button.
 */
@Composable
private fun narrowChipLabel(hit: SmartSuggest.SmartHit): String = when (hit.kind) {
    // "hate → abhor" for a swap, "abhor ›" when the typed word is the word.
    SmartSuggest.Kind.VOCAB -> {
        val offered = hit.result
        if (offered != null) {
            stringResource(R.string.ime_smart_vocab_offer, hit.query, offered)
        } else {
            stringResource(R.string.ime_smart_vocab_word, hit.query)
        }
    }
    SmartSuggest.Kind.LOOKUP -> stringResource(
        if (hit.tool == ToolbarTool.DICTIONARY) {
            R.string.ime_smart_define_word
        } else {
            R.string.ime_smart_wiki_word
        },
        hit.query,
    )
    SmartSuggest.Kind.INTENT -> stringResource(
        when (hit.tool) {
            ToolbarTool.TRANSLATE -> R.string.ime_smart_translate_offer
            ToolbarTool.GIF, ToolbarTool.STICKER -> R.string.ime_smart_gif_offer
            else -> R.string.ime_smart_open_tool
        },
        toolLabel(hit.tool),
    )
    // The face is the call: the phone icon beside the name says so.
    SmartSuggest.Kind.CONTACT -> hit.query
    // "Repeat “no”", with the count in its own pill after it.
    SmartSuggest.Kind.REPEAT -> stringResource(R.string.ime_smart_repeat_offer, hit.query.replace("\n", "↵"))
    // A share link in the text: the face opens the panel on receiving it (#729).
    SmartSuggest.Kind.SEND_LINK -> stringResource(R.string.ime_smart_send_link)
    // "@gif cats" (#1242): the tool's name and the words it will search for.
    SmartSuggest.Kind.SEARCH -> stringResource(
        R.string.ime_smart_search_offer,
        // "@weather dhaka" names the plugin, not the Plugins tool.
        (hit.prefill as? com.wasimaster.wmkeyboard.core.tools.ToolPrefill.Plugin)?.name ?: toolLabel(hit.tool),
        hit.query,
    )
    else -> stringResource(R.string.ime_smart_open_tool, toolLabel(hit.tool))
}

/**
 * The "×3" beside a repeat chip. The chip itself is keyed on its kind, so a
 * fourth copy does not re-animate the chip; the number ticks over instead,
 * sliding up like an odometer, which is what "again" looks like.
 */
@Composable
private fun RepeatCountPill(count: String, tint: Color, reduceMotion: Boolean) {
    val label: @Composable (String) -> Unit = { shown ->
        Text(
            text = stringResource(R.string.ime_smart_repeat_count, shown),
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (reduceMotion) {
            label(count)
        } else {
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = (targetState.toIntOrNull() ?: 0) >= (initialState.toIntOrNull() ?: 0)
                    val sign = if (up) 1 else -1
                    (slideInVertically(tween(COUNT_TICK_MS)) { sign * it } + fadeIn(tween(COUNT_TICK_MS))) togetherWith
                        (slideOutVertically(tween(COUNT_TICK_MS)) { -sign * it } + fadeOut(tween(COUNT_TICK_MS))) using
                        SizeTransform(clip = false)
                },
                label = "repeatCount",
            ) { shown -> label(shown) }
        }
    }
}

private const val COUNT_TICK_MS = 160

/**
 * The richest (tier, font) pairing that fits in [maxWidth], honouring the
 * degradation order the tiers encode: every ordinary tier at full size, the
 * same tiers again at the smaller font, and only then the last-resort tiers
 * ("1 thousand" flattened to "1000"), largest font first. When nothing fits
 * the narrowest tier ships at the small font and ellipsises like any text.
 *
 * Returns the tier plus whether the small font was needed.
 */
private fun chooseTier(
    tiers: List<SmartSuggest.ChipTier>,
    measurer: TextMeasurer,
    density: Density,
    maxWidth: Dp,
): Pair<SmartSuggest.ChipTier, Boolean> {
    val available = with(density) { maxWidth.toPx() }
    // Two 6dp gaps around the arrow, plus a little slack so a measurement
    // that lands exactly on the edge doesn't ellipsise anyway.
    val fixed = with(density) { 16.dp.toPx() }
    fun width(text: String, size: Int, bold: Boolean): Float {
        val style = TextStyle(
            fontSize = size.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        )
        return measurer.measure(AnnotatedString(text), style, maxLines = 1).size.width.toFloat()
    }
    fun fits(tier: SmartSuggest.ChipTier, small: Boolean): Boolean {
        val needed = width(tier.query, if (small) 11 else 13, bold = false) +
            width("→", if (small) 10 else 12, bold = false) +
            width(tier.result, if (small) 13 else 15, bold = true) +
            fixed
        return needed <= available
    }
    val ordinary = tiers.filterNot { it.lastResort }
    val lastResort = tiers.filter { it.lastResort }
    for (small in booleanArrayOf(false, true)) {
        ordinary.firstOrNull { fits(it, small) }?.let { return it to small }
    }
    for (small in booleanArrayOf(false, true)) {
        lastResort.firstOrNull { fits(it, small) }?.let { return it to small }
    }
    return (lastResort.lastOrNull() ?: tiers.last()) to true
}
