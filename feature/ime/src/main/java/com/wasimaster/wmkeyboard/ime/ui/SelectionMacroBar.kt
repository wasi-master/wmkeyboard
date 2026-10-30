package com.wasimaster.wmkeyboard.ime.ui

import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.VolumeUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDownward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowUpward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Checklist
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowOutward
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoFixHigh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Call
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Chat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CleaningServices
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Code
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DataObject
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DeleteOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.EditNote
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Event
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FilterList
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindInPage
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatBold
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatItalic
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListBulleted
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatListNumbered
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FormatStrikethrough
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.HourglassEmpty
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Language
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LinkOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LocationOn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.LockOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Mail
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInNew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Palette
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PersonAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Pin
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.QrCode2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SelectAll
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Repeat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StickyNote2
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Sms
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SortByAlpha
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Spellcheck
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.StopCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.TextFormat
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.selection.Colour
import com.wasimaster.wmkeyboard.core.selection.ColourCodes
import com.wasimaster.wmkeyboard.core.selection.DateTimeHit
import com.wasimaster.wmkeyboard.core.selection.DateTimes
import com.wasimaster.wmkeyboard.core.selection.SelectionKind
import com.wasimaster.wmkeyboard.core.selection.SelectionMacro
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.effectiveTimeZones
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.AiActionSpec
import com.wasimaster.wmkeyboard.core.tools.BuiltInAiActions
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.WordCardAction
import java.util.TimeZone

/**
 * What the selection bar hands back. One bundle, because the screen that
 * hosts the bar sits against the JVM's 64K method-size ceiling and cannot
 * grow a parameter per gesture.
 */
data class SelectionMacroCallbacks(
    /** A chip was tapped: every action, Undo included. Doors to ladders never reach this. */
    val onMacro: (SelectionMacro) -> Unit = {},
    /**
     * A pick on a ladder: the macro that opened it, what was picked (a style
     * id, a colour form, a zone id) and the selection as it stood when the
     * ladder opened. Every pick converts from that original rather than from
     * the previous pick's output, so picking again replaces the last choice.
     */
    val onPick: (SelectionMacro, String, String) -> Unit = { _, _, _ -> },
    /** One of the AI buttons drawn after the AI chip: the action's id. */
    val onAiAction: (String) -> Unit = {},
    /** Something on the word card Edit word opened was pressed, as on the strip's own card. */
    val onWordCard: (WordCardAction) -> Unit = {},
)

/** Which sub-row has replaced the bar, if any. */
private sealed interface MacroLadder {
    data object Case : MacroLadder
    data class Fancy(val source: String) : MacroLadder
    data class Colour(val source: String, val choices: List<Pair<String, String>>) : MacroLadder
    data class Zones(val source: String, val choices: List<Pair<String, String>>) : MacroLadder
}

/** One thing on the row: a macro chip, or an AI action drawn as a chip of its own. */
private sealed interface BarItem {
    val key: String

    data class Macro(val macro: SelectionMacro) : BarItem {
        override val key: String get() = macro.name
    }

    data class Ai(val spec: AiActionSpec) : BarItem {
        override val key: String get() = "ai:" + spec.id
    }
}

/**
 * The selection macro bar: what the keyboard offers to do with what is
 * selected.
 *
 * Drawn either on a row of its own ([com.wasimaster.wmkeyboard.core.settings.BarRow.MACROS])
 * or over the suggestion strip, which is why it is a plain `RowScope`-free
 * composable taking the state and one bundle of callbacks. The two placements
 * share every pixel of it, ladders included, so a user who moves the bar from
 * one to the other finds the same bar.
 */
@Composable
internal fun SelectionMacroBar(
    state: KeyboardUiState,
    callbacks: SelectionMacroCallbacks,
    modifier: Modifier = Modifier,
) {
    val offer = state.selectionMacros ?: return
    // The card Edit word opens. The strip hosts the card it opens itself, but
    // with text selected the strip may not be on screen at all (the strip
    // placement draws this bar in its place), so the bar hosts its own. The
    // service closes it once the selection moves off the word.
    WordCardPopup(
        card = state.wordCard?.takeIf { it.fromSelection && state.wordSpell == null },
        onAction = callbacks.onWordCard,
    )
    // A door chip (Format on prose, Fancy, Colour, Zones) opens a ladder in
    // place of the row, so which face the bar shows is composition state.
    //
    // Keyed on the *kind* and not on the offer itself. Every ladder pick
    // rewrites the selection, which republishes a new offer a frame later, so
    // keying on the offer would shut the ladder after each tap and make
    // lower-then-Title two trips instead of two taps. The kind survives a
    // rewrite, and a selection cleared altogether takes the whole bar out of
    // the composition, which is what resets this for the next one.
    //
    // Each ladder also carries the selection as it stood when it opened:
    // every pick converts from that, because the previous pick's output may
    // no longer read as anything (𝐛 is not a key in the fancy tables).
    var ladder by remember(offer.kind) { mutableStateOf<MacroLadder?>(null) }
    val feedback = LocalKeyPressFeedback.current
    val context = LocalContext.current
    val linkApp = rememberMacroLinkApp(offer.kind, offer.text, wanted = SelectionMacro.OPEN in offer.macros)
    // Accessibility › Animate text selection. The bar slides up into its row
    // when a selection first offers it, and each face of it (the macros, or a
    // ladder) brings its chips in one after another. Keyed like the ladder: a
    // pick that rewrites the selection keeps the bar where it is.
    val motion = LocalKbTheme.current.motion.selection
    val arrival = remember(offer.kind) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(arrival) { arrival.animateTo(1f, tween(BarArrivalMs, easing = FastOutSlowInEasing)) }
    val chips = remember(offer.kind, ladder) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(chips) { chips.animateTo(1f, tween(ChipClockMs.toInt(), easing = LinearEasing)) }
    val rise = with(LocalDensity.current) { BarRise.toPx() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(topBarHeight(state.settings))
            .toolbarPadding(state.settings)
            .graphicsLayer {
                val p = arrival.value
                alpha = p
                translationY = (1f - p) * rise
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val open = ladder
        if (open != null) MacroBackButton(onClick = { ladder = null })
        when (open) {
            is MacroLadder.Fancy -> {
                FancyStyleLadder(chips, modifier = Modifier.weight(1f)) { id ->
                    feedback()
                    callbacks.onPick(SelectionMacro.FANCY, id, open.source)
                }
                return@Row
            }
            is MacroLadder.Colour -> {
                PickLadder(open.choices, chips, modifier = Modifier.weight(1f)) { arg ->
                    feedback()
                    callbacks.onPick(SelectionMacro.COLOUR, arg, open.source)
                }
                return@Row
            }
            is MacroLadder.Zones -> {
                PickLadder(open.choices, chips, modifier = Modifier.weight(1f)) { arg ->
                    feedback()
                    callbacks.onPick(SelectionMacro.TIME_ZONES, arg, open.source)
                }
                return@Row
            }
            MacroLadder.Case, null -> {}
        }
        val items: List<BarItem> = if (open == MacroLadder.Case) {
            offer.caseLadder.map { BarItem.Macro(it) }
        } else {
            barItems(offer.macros, offer.aiDirect)
        }
        LazyRow(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                Box(Modifier.staggerIn(chips, index)) {
                    when (item) {
                        is BarItem.Ai -> AiChip(item.spec) {
                            feedback()
                            callbacks.onAiAction(item.spec.id)
                        }
                        is BarItem.Macro -> when (val macro = item.macro) {
                            SelectionMacro.UNDO -> UndoChip {
                                feedback()
                                callbacks.onMacro(SelectionMacro.UNDO)
                            }
                            else -> MacroChip(
                                macro = macro,
                                busy = offer.busy == macro,
                                speaking = macro == SelectionMacro.READ_ALOUD && offer.speaking,
                                swatch = if (macro == SelectionMacro.COLOUR) offer.content.colour?.let { Color(it.argb) } else null,
                                reduceMotion = !motion,
                                app = if (macro == SelectionMacro.OPEN) linkApp else null,
                                iconOnly = macro in state.settings.selectionMacros.iconOnly,
                                customLabel = state.settings.selectionMacros.labels[macro],
                                onClick = {
                                    feedback()
                                    // The doors the service never sees: on prose
                                    // there is nothing to reformat, and a colour,
                                    // a style or a zone is a pick, not an action.
                                    when {
                                        macro == SelectionMacro.FORMAT && offer.kind == SelectionKind.TEXT -> ladder = MacroLadder.Case
                                        macro == SelectionMacro.FANCY -> ladder = MacroLadder.Fancy(offer.text)
                                        macro == SelectionMacro.COLOUR -> offer.content.colour?.let { colour ->
                                            ladder = MacroLadder.Colour(offer.text, colourChoices(colour))
                                        }
                                        macro == SelectionMacro.TIME_ZONES -> offer.content.dateTime?.let { hit ->
                                            ladder = MacroLadder.Zones(
                                                offer.text,
                                                zoneChoices(hit, state.settings, DateFormat.is24HourFormat(context)),
                                            )
                                        }
                                        else -> callbacks.onMacro(macro)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The row: every macro in order, with the AI buttons right after the AI chip. */
private fun barItems(macros: List<SelectionMacro>, aiDirect: List<AiActionSpec>): List<BarItem> =
    macros.flatMap { macro ->
        if (macro == SelectionMacro.AI) listOf(BarItem.Macro(macro)) + aiDirect.map { BarItem.Ai(it) } else listOf(BarItem.Macro(macro))
    }

/** Each other spelling of the colour, written out, so the chip shows what it will commit. */
private fun colourChoices(colour: Colour): List<Pair<String, String>> =
    ColourCodes.ladder(colour).map { form -> form.name to ColourCodes.render(colour, form) }

/**
 * The moment in each of the user's zones, minus the one it was read in: in
 * their order, or earliest there first when they asked for that (#970).
 */
private fun zoneChoices(hit: DateTimeHit, settings: KeyboardSettings, hour24: Boolean): List<Pair<String, String>> {
    val now = System.currentTimeMillis()
    val zones = settings.selectionMacros.effectiveTimeZones(TimeZone.getDefault().id)
        .filter { it != hit.zoneId }
    val ordered = if (settings.selectionMacros.sortZonesByTime) {
        zones.sortedBy { TimeZone.getTimeZone(it).getOffset(hit.startMillis) }
    } else {
        zones
    }
    return ordered.map { id -> id to DateTimes.renderInZone(hit.startMillis, id, hit.zoneId, hit.hasDate, hour24, now) }
}

/**
 * The ladder behind the Fancy chip: every style, written in itself.
 *
 * The same WYSIWYG chips the fancy layout's own strip uses, and for the same
 * reason — a style is a look, and its name is a poor description of one.
 *
 * Nothing is drawn as selected. The ladder asks what to make *this* selection,
 * which has nothing to do with the style the fancy keyboard is set to, and
 * marking one would suggest the two are the same setting.
 */
@Composable
private fun FancyStyleLadder(
    chips: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit,
) {
    // Latin glyphs whatever the locale, so mirroring the ladder under RTL
    // would put the list order at odds with the layout — as on the fancy strip.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        PickLadder(
            FancyStyles.all.map { it.id to it.sample },
            chips,
            modifier = modifier,
            // The samples are astral soup to TalkBack; speak the plain name instead.
            describe = { id -> FancyStyles.byId(id)?.name ?: id },
            onPick = onPick,
        )
    }
}

/** A row of plain chips, each committing its label: colour spellings, zones, fancy samples. */
@Composable
private fun PickLadder(
    choices: List<Pair<String, String>>,
    chips: Animatable<Float, AnimationVector1D>,
    modifier: Modifier = Modifier,
    describe: (String) -> String = { _ -> "" },
    onPick: (String) -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    LazyRow(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
    ) {
        itemsIndexed(choices, key = { _, choice -> choice.first }) { index, (arg, label) ->
            val description = describe(arg).ifEmpty { label }
            Text(
                text = label,
                modifier = Modifier
                    .staggerIn(chips, index)
                    .clip(shape)
                    .frostGlass(kb, shape, FrostSurface.CHIPS)
                    .background(kb.chipActive)
                    .chipBorder(kb, shape)
                    .clickable { onPick(arg) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics { contentDescription = description },
                fontSize = 14.sp,
                color = kb.chipActiveText,
                maxLines = 1,
            )
        }
    }
}

/** The bar's way in: a short rise and fade, over a few pixels. */
private const val BarArrivalMs = 140
private val BarRise = 6.dp

/**
 * The chips' way in: each rises over [ChipInMs], the next [ChipStaggerMs]
 * after the one before, the stagger capped so the whole row is in by
 * [ChipClockMs] however long it is. Chips scrolled into view later find the
 * clock run out and arrive in place.
 */
private const val ChipInMs = 90f
private const val ChipStaggerMs = 15f
private const val ChipStaggerCap = 4
private const val ChipClockMs = ChipInMs + ChipStaggerMs * ChipStaggerCap
private val ChipRise = 4.dp

/** Chip [index]'s share of [clock], read in its layer so the row never recomposes for it. */
private fun Modifier.staggerIn(clock: Animatable<Float, AnimationVector1D>, index: Int): Modifier = graphicsLayer {
    val ms = clock.value * ChipClockMs
    val start = minOf(index, ChipStaggerCap) * ChipStaggerMs
    val p = FastOutSlowInEasing.transform(((ms - start) / ChipInMs).coerceIn(0f, 1f))
    alpha = p
    translationY = (1f - p) * ChipRise.toPx()
}

/** Back out of a ladder to the macros the selection was offered. */
@Composable
private fun MacroBackButton(onClick: () -> Unit) {
    val feedback = LocalKeyPressFeedback.current
    IconHint(stringResource(R.string.ime_selection_macro_back_desc)) {
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.ime_selection_macro_back_desc),
            modifier = Modifier
                .padding(start = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    feedback()
                    onClick()
                }
                .padding(6.dp)
                .size(20.dp),
            tint = LocalKbTheme.current.suggestionText,
        )
    }
}

/**
 * One macro, drawn as its glyph and its word.
 *
 * The word is always there: the glyphs for Share, Open and Format are close
 * enough to each other that an icon-only bar would be a guessing game, and this
 * bar appears rarely enough that nobody builds muscle memory for it. A chip
 * whose work is still running ([busy]) shows a spinner in the glyph's place
 * and takes no taps; the colour chip wears a puck of its colour.
 *
 * Open wears the icon of the [app] its link lands in, when that is one app
 * and not a browser, with an outward arrow after the word: the tap leaves
 * the keyboard for that app, and the arrow says so where the app's own icon
 * cannot.
 */
@Composable
private fun MacroChip(
    macro: SelectionMacro,
    busy: Boolean,
    speaking: Boolean,
    swatch: Color?,
    reduceMotion: Boolean,
    app: MacroLinkApp? = null,
    iconOnly: Boolean = false,
    /** The user's own name for it (#970), or null for the shipped word. */
    customLabel: String? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val label = if (speaking) {
        stringResource(R.string.ime_selection_macro_stop_label)
    } else {
        customLabel ?: stringResource(macro.labelRes)
    }
    val icon = if (speaking) Icons.Outlined.StopCircle else selectionMacroIcon(macro)
    // Its icon alone when asked for (#679), so long as it has something to
    // show in place of the word; the description still names it.
    val bare = iconOnly && (busy || swatch != null || app != null || icon != null)
    val description = when {
        busy -> stringResource(R.string.ime_selection_macro_busy_desc)
        app != null -> stringResource(R.string.ime_selection_macro_open_in_desc, app.name)
        else -> label
    }
    // A bare icon is named by a hold, the way the toolbar's are (#970); a chip
    // that wears its word has nothing to add.
    IconHint(description.takeIf { bare }) {
        Row(
            modifier = Modifier
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(kb.chipActive)
                .chipBorder(kb, shape)
                .clickable(enabled = !busy, onClick = onClick)
                // A bare icon stands as tall as a worded chip beside it.
                .padding(horizontal = 10.dp, vertical = if (bare) 8.dp else 6.dp)
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                busy && reduceMotion -> Icon(
                    Icons.Outlined.HourglassEmpty,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = kb.chipActiveText,
                )
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = kb.chipActiveText,
                )
                swatch != null -> Spacer(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, kb.chipActiveText.copy(alpha = 0.4f), CircleShape),
                )
                app != null -> Image(
                    bitmap = app.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                icon != null -> Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = kb.chipActiveText,
                )
            }
            if (!bare) {
                if (busy || swatch != null || app != null || icon != null) Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    fontSize = 14.sp,
                    color = kb.chipActiveText,
                    maxLines = 1,
                )
            }
            if (app != null && !busy) {
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Outlined.ArrowOutward,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = kb.chipActiveText.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Undo, drawn in the strip's accent-tinted offer style rather than as one more
 * action: putting something back is a different kind of thing from doing
 * something, and it is always first, so it wants to read apart from the row.
 */
@Composable
private fun UndoChip(onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val tint = kb.accent
    val label = stringResource(SelectionMacro.UNDO.labelRes)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(tint.copy(alpha = if (kb.dark) 0.20f else 0.11f))
            .border(1.dp, tint.copy(alpha = 0.32f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Undo,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 14.sp, color = tint, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/**
 * One AI action as a chip of its own, named as the AI panel names it. Except
 * Translate: on this row it can sit beside the Translate chip that opens the
 * Translate tool, so it says which of the two it is (#434).
 */
@Composable
private fun AiChip(spec: AiActionSpec, onClick: () -> Unit) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    val label = if (spec.id == BuiltInAiActions.TRANSLATE_ID) {
        stringResource(R.string.ime_selection_macro_ai_translate_label)
    } else {
        BuiltInAiActions.labelRes(spec)?.let { stringResource(it) } ?: spec.name
    }
    Row(
        modifier = Modifier
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chipActive)
            .chipBorder(kb, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.AutoFixHigh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = kb.chipActiveText,
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, fontSize = 14.sp, color = kb.chipActiveText, maxLines = 1)
    }
}

/**
 * The glyph for a macro, or null for the case options. Public so the
 * settings list of these actions can wear the same glyphs as the bar.
 *
 * The case chips are written in the case they apply ("UPPER CASE",
 * "snake_case"), which already shows what each one does; an icon beside them
 * would say it a second time in a worse language. Exhaustive on purpose: a
 * new member without a glyph is a compile error, not an iconless chip.
 */
fun selectionMacroIcon(macro: SelectionMacro): ImageVector? = when (macro) {
    SelectionMacro.UNDO -> Icons.AutoMirrored.Outlined.Undo
    SelectionMacro.SELECT_ALL -> Icons.Outlined.SelectAll
    SelectionMacro.COPY -> Icons.Outlined.ContentCopy
    SelectionMacro.CUT -> Icons.Outlined.ContentCut
    SelectionMacro.PASTE -> Icons.Outlined.ContentPaste
    SelectionMacro.DELETE -> Icons.Outlined.DeleteOutline
    SelectionMacro.SHARE -> Icons.Outlined.Share
    SelectionMacro.SAVE_NOTE -> Icons.Outlined.StickyNote2
    SelectionMacro.REPEAT -> Icons.Outlined.Repeat
    SelectionMacro.SNIPPET -> Icons.Outlined.Bolt
    SelectionMacro.FORMAT -> Icons.Outlined.TextFormat
    SelectionMacro.FIND -> Icons.Outlined.FindInPage
    SelectionMacro.REPLACE -> Icons.Outlined.FindReplace
    SelectionMacro.LINES_SORT -> Icons.Outlined.SortByAlpha
    SelectionMacro.LINES_DEDUPE -> Icons.Outlined.FilterList
    SelectionMacro.LINES_NUMBER -> Icons.Outlined.FormatListNumbered
    SelectionMacro.LINES_BULLET -> Icons.Outlined.FormatListBulleted
    SelectionMacro.LINES_CHECKBOX -> Icons.Outlined.Checklist
    SelectionMacro.LINES_UP -> Icons.Outlined.ArrowUpward
    SelectionMacro.LINES_DOWN -> Icons.Outlined.ArrowDownward
    SelectionMacro.SEARCH -> Icons.Outlined.Search
    SelectionMacro.TRANSLATE -> Icons.Outlined.Translate
    SelectionMacro.GRAMMAR_FIX -> Icons.Outlined.Spellcheck
    SelectionMacro.DEEPL_WRITE -> Icons.Outlined.EditNote
    SelectionMacro.AI -> Icons.Outlined.AutoFixHigh
    SelectionMacro.EDIT_WORD -> Icons.Outlined.Edit
    SelectionMacro.TO_BANGLA, SelectionMacro.TO_BANGLISH,
    SelectionMacro.TO_HINDI, SelectionMacro.TO_HINGLISH,
    -> Icons.Outlined.Language
    SelectionMacro.ZAWGYI -> Icons.Outlined.SwapHoriz
    SelectionMacro.DIGITS_LATIN -> Icons.Outlined.Pin
    SelectionMacro.COLOUR -> Icons.Outlined.Palette
    SelectionMacro.JSON_FORMAT -> Icons.Outlined.DataObject
    SelectionMacro.BASE64_DECODE -> Icons.Outlined.LockOpen
    SelectionMacro.URL_DECODE -> Icons.Outlined.LinkOff
    SelectionMacro.STRIP_TRACKERS -> Icons.Outlined.CleaningServices
    SelectionMacro.CHAT_BOLD -> Icons.Outlined.FormatBold
    SelectionMacro.CHAT_ITALIC -> Icons.Outlined.FormatItalic
    SelectionMacro.CHAT_STRIKE -> Icons.Outlined.FormatStrikethrough
    SelectionMacro.CHAT_MONO -> Icons.Outlined.Code
    SelectionMacro.READ_ALOUD -> Icons.AutoMirrored.Outlined.VolumeUp
    SelectionMacro.TIME_ZONES -> Icons.Outlined.Public
    SelectionMacro.CALL -> Icons.Outlined.Call
    SelectionMacro.SMS -> Icons.Outlined.Sms
    SelectionMacro.WHATSAPP -> Icons.Outlined.Chat
    SelectionMacro.EMAIL -> Icons.Outlined.Mail
    SelectionMacro.OPEN -> Icons.Outlined.OpenInNew
    SelectionMacro.QR -> Icons.Outlined.QrCode2
    SelectionMacro.ADD_CONTACT -> Icons.Outlined.PersonAdd
    SelectionMacro.MAP -> Icons.Outlined.LocationOn
    SelectionMacro.CALENDAR -> Icons.Outlined.Event
    // Not TextFormat, which Format already wears: the two sit side by side on
    // a plain-text selection, both open a ladder, and must not read as the
    // same chip twice.
    SelectionMacro.FANCY -> Icons.Outlined.AutoAwesome
    SelectionMacro.CASE_LOWER, SelectionMacro.CASE_TITLE, SelectionMacro.CASE_UPPER, SelectionMacro.CASE_SENTENCE,
    SelectionMacro.CASE_CAMEL, SelectionMacro.CASE_SNAKE, SelectionMacro.CASE_KEBAB, SelectionMacro.CASE_CONSTANT -> null
}

/**
 * Whether the macro bar has anything to draw right now.
 *
 * Read by both hosts and by the strip, which has to know before it decides
 * which surface owns the row. The offer is only ever published while the
 * feature is on and something is selected, so that is most of the question.
 *
 * The rest is the Selection mode tool's hold (#136): while a finger is holding
 * that tool down to select, the bar stays away. It would otherwise arrive the
 * moment the selection did — a row pushing the toolbar under the held finger,
 * or the strip placement swapping the toolbar out from under it — and the
 * finger came up over a different button than it went down on. The selection
 * outlives the hold, so the bar appears on the lift, where it can be used.
 */
internal fun selectionMacroBarVisible(state: KeyboardUiState): Boolean =
    state.selectionMacros != null && !state.selectionHold
