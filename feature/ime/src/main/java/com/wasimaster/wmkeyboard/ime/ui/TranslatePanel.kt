package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ArrowDropDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Cloud
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DownloadDone
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhoneAndroid
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.TranslateEngine
import com.wasimaster.wmkeyboard.core.theme.FrostSurface
import com.wasimaster.wmkeyboard.core.tools.TranslateClient
import com.wasimaster.wmkeyboard.core.tools.TranslateProviders
import com.wasimaster.wmkeyboard.core.translate.OfflineModelState
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateLanguages
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.TranslateModuleState
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import com.wasimaster.wmkeyboard.ime.FocusRegion
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.PanelMode
import com.wasimaster.wmkeyboard.ime.ProviderPicks
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.TranslateUi
import com.wasimaster.wmkeyboard.core.ui.DisabledReason

/**
 * The translate panel's service callbacks, bundled into one [KeyboardScreen]
 * parameter for the reason [ConverterCallbacks] is: its caller sits against
 * the JVM's 64K method-size ceiling, where every added parameter costs
 * bytecode. This bundle replaced three parameters with one.
 */
data class TranslateCallbacks(
    val onTarget: (String) -> Unit = {},
    /** A picker code, or "" for "detect the language". */
    val onSource: (String) -> Unit = {},
    val onEngine: (TranslateEngine) -> Unit = {},
    val onSwap: () -> Unit = {},
    /** Fetches the models the query is waiting for; cancels while they are coming. */
    val onDownload: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onInsert: () -> Unit = {},
    /** The clipboard's newest text into the text box (#474). */
    val onPaste: () -> Unit = {},
)

/**
 * How tall the panel stands while its text is typed into, keys underneath:
 * 260dp at the default font size.
 *
 * Issue #378. The budget was a flat 260dp, but the rows it pays for — the text
 * box, the language chips, the translation and Replace/Insert — are set in sp
 * and grow with the system font size, while the budget did not. At a large
 * font the fixed rows took it all and the translation, the one row that gives,
 * was left a sliver under the chips. So the part of the budget that is lines
 * of text is asked for in sp, one line at a time: Android 14's nonlinear font
 * scaling grows a 19sp line far more than it would one 91sp block.
 *
 * Issue #713: the text box was paid one line, so while typing it was a single
 * row that scrolled, and a second sentence pushed the first out of sight. It
 * is paid [TranslateQueryLines] now. The box only takes what its text needs,
 * so while the text is short the lines it leaves go to the translation.
 */
@Composable
internal fun translateCompactHeight(): Dp = with(LocalDensity.current) {
    TranslateCompactLines.fold(TranslateCompactChrome) { height, line -> height + line.toDp() }
}

/**
 * The text lines [translateCompactHeight] pays for: the text box's
 * [TranslateQueryLines], a chip's label, two of translation, and a button's
 * label.
 */
private val TranslateCompactLines =
    List(TranslateQueryLines) { 19.sp } + listOf(16.sp, 19.sp, 19.sp, 18.sp)

/** Everything else in the typed-in panel: header, paddings, the suggestion strip's row. */
private val TranslateCompactChrome = 169.dp

/** How many lines of the text box show while it has the keys, before it scrolls. */
private const val TranslateQueryLines = 3

/** The most the text box grows before it scrolls. */
private val TranslateQueryMaxHeight = 96.dp

/** Which of the header's menus is open. One at a time: they share the ring's RESULTS region. */
private enum class TranslateMenu { SOURCE, TARGET, ENGINE }

/** Key of the menu row that shows the languages the short list left out. */
private const val ALL_LANGUAGES_KEY = "\u0000all"

/** One row of a header menu. */
private data class TranslateMenuRow(
    val key: String,
    val label: String,
    val selected: Boolean,
    /** A second line under the label, for the engine menu. */
    val info: String? = null,
    /** A trailing word, for a language the on-device engine does not have. */
    val note: String? = null,
    /** Drawn after the label: the language's model is on the device. */
    val downloaded: Boolean = false,
    val dimmed: Boolean = false,
)

private val TranslateEngine.icon: ImageVector
    get() = when (this) {
        TranslateEngine.ONLINE -> Icons.Outlined.Cloud
        TranslateEngine.ON_DEVICE -> Icons.Outlined.PhoneAndroid
        TranslateEngine.AUTO -> Icons.Outlined.AutoMode
    }

/**
 * Which of the header's menus is open, shared by the panel's two halves: on a
 * short landscape screen the language chips sit in the tool's header and the
 * text in the body, and the ring's RESULTS region is the open menu's rows.
 */
@Stable
private class TranslateMenus {
    private var openMenu by mutableStateOf<TranslateMenu?>(null)

    /** "All languages" opens the rest of one menu, once: the next menu starts short again. */
    var showAll by mutableStateOf(false)

    var open: TranslateMenu?
        get() = openMenu
        set(value) {
            if (value != openMenu) showAll = false
            openMenu = value
        }
}

/**
 * The Translate tool: [TranslatePanel] in its [FullBleedTool] chrome.
 *
 * Upright, the panel stacks its text, its language chips, the result and its
 * actions. On a [shortLandscape] screen, where the panel over the keys is a
 * strip a line or two tall, that stack showed the text box and nothing under
 * it (issue #370), so the chips and actions move up into the header, which
 * has the width for them, and the text and its translation sit side by side.
 */
@Composable
internal fun TranslateTool(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    onClose: () -> Unit,
    /** Starts typing into the text box: the media-search key reroute. */
    onQueryTap: () -> Unit,
) {
    val menus = remember { TranslateMenus() }
    val sideBySide = shortLandscape()
    // Issue #695: sideways, with the keys back under the panel, the panel is
    // cut down to its header and the text box below it got no height at all,
    // so what was typed never showed. While typing, the header carries the
    // text and its translation instead, and the language chips move down into
    // whatever room the body has left.
    val typingSideways = sideBySide && state.mediaSearchActive
    FullBleedTool(
        state,
        title = if (sideBySide) "" else stringResource(R.string.ime_tool_translate),
        onClose = onClose,
        compact = state.mediaSearchActive,
        // Translations run long, and the text being translated is a box of
        // several lines in the panel itself rather than the header's one-line
        // search bar: room for both over the keys.
        compactHeight = translateCompactHeight(),
        headerActions = if (typingSideways) {
            {
                TranslateQueryBox(
                    state = state,
                    focused = state.focusedIndex(FocusRegion.SEARCH) == 0,
                    onQueryTap = onQueryTap,
                    onPaste = callbacks.onPaste,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = FullBleedHeaderHeight),
                    singleLine = true,
                )
                TranslateResult(
                    state,
                    Modifier
                        .weight(1f)
                        .heightIn(max = FullBleedHeaderHeight)
                        .padding(start = 8.dp),
                )
                TranslateActionRow(
                    state,
                    callbacks,
                    Modifier.padding(start = 8.dp),
                    actionModifier = Modifier.widthIn(min = TranslateHeaderActionMinWidth),
                )
            }
        } else if (sideBySide) {
            {
                TranslateLanguageRow(state, callbacks, menus, Modifier.weight(1f))
                TranslateActionRow(
                    state,
                    callbacks,
                    Modifier.padding(start = 8.dp),
                    actionModifier = Modifier.widthIn(min = TranslateHeaderActionMinWidth),
                )
            }
        } else {
            null
        },
    ) {
        if (typingSideways) {
            TranslateLanguageRow(state, callbacks, menus, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        } else {
            TranslatePanel(state, callbacks, onQueryTap, menus, sideBySide)
        }
    }
}

/** How narrow Replace and Insert may get when they share the header with the chips. */
private val TranslateHeaderActionMinWidth = 96.dp

/**
 * Translation window: the query types into the panel's own search bar
 * (media-search key rerouting — the focused field is never read) and the
 * result follows live. Insert types the translation at the cursor; Replace
 * swaps the whole field for it.
 *
 * The header reads left to right as the sentence it is: *from* this language,
 * *to* that one, *using* this engine. The source chip starts on "detect" and
 * shows what was detected; the engine chip is only there in a build that has
 * an on-device engine to choose. When that engine needs a model it does not
 * have, the result area becomes the offer to download it, and the action row
 * becomes the button.
 *
 * [sideBySide] leaves the chips and the actions to the tool's header and puts
 * the text beside the result; see [TranslateTool].
 */
@Composable
private fun TranslatePanel(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    onQueryTap: () -> Unit,
    menus: TranslateMenus,
    sideBySide: Boolean,
) {
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.SEARCH,
        count = 1,
        columns = 1,
        onActivate = { onQueryTap() },
    )
    val queryFocused = state.focusedIndex(FocusRegion.SEARCH) == 0
    // The panel is its own translation window: the text types into the box
    // at its top (field text is never read). The FullBleedTool wrapper
    // collapses the panel while typing — the keys sit right below and the
    // live result still fits above them.
    if (sideBySide) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TranslateQueryBox(
                state = state,
                focused = queryFocused,
                onQueryTap = onQueryTap,
                onPaste = callbacks.onPaste,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            TranslateResult(
                state,
                Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
        return
    }
    TranslateStack(
        query = {
            TranslateQueryBox(
                state = state,
                focused = queryFocused,
                onQueryTap = onQueryTap,
                onPaste = callbacks.onPaste,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        languages = { TranslateLanguageRow(state, callbacks, menus) },
        result = { TranslateResult(state, Modifier.fillMaxSize()) },
        actions = {
            TranslateActionRow(
                state,
                callbacks,
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                shareRow = true,
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
    )
}

/**
 * The upright panel, top to bottom: the text, the chips, the result, the
 * actions. The chips and the actions are measured first, so a long text never
 * pushes Replace and Insert off the bottom edge (#501). The text takes what it
 * needs of the rest, short of [TranslateResultFloorLines] kept for the result,
 * and scrolls inside that; the result gets everything the text left.
 *
 * A Column's weights could not do the last part (#713): each weighted child
 * gets its share of the room whether it uses it or not, so the text was held
 * to half of it however short the translation, and a short text left its half
 * empty instead of handing it to the translation.
 */
@Composable
private fun TranslateStack(
    query: @Composable () -> Unit,
    languages: @Composable () -> Unit,
    result: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier,
) {
    Layout(contents = listOf(query, languages, result, actions), modifier = modifier) { slots, constraints ->
        val (queryNodes, languageNodes, resultNodes, actionNodes) = slots
        val width = constraints.maxWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val loose = Constraints(maxWidth = width)
        fun Constraints.upTo(px: Int) = copy(maxHeight = px.coerceAtLeast(0))
        val languageRow = languageNodes.first().measure(loose.upTo(height))
        val actionRow = actionNodes.first().measure(loose.upTo(height - languageRow.height))
        val rest = (height - languageRow.height - actionRow.height).coerceAtLeast(0)
        val floor = minOf(TranslateResultFloorLines * 19.sp.roundToPx() + 8.dp.roundToPx(), rest / 2)
        val box = queryNodes.first().measure(loose.upTo(rest - floor))
        val resultHeight = (rest - box.height).coerceAtLeast(0)
        val resultBox = resultNodes.first().measure(Constraints.fixed(width, resultHeight))
        layout(width, height) {
            var y = 0
            for (placeable in listOf(box, languageRow, resultBox, actionRow)) {
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

/** Lines of translation the text box always leaves room for, while the panel has them. */
private const val TranslateResultFloorLines = 2

/** Whether the on-device engine is short of something, so the result area offers the download. */
private fun TranslateUi.offersDownload(): Boolean = missingModels.isNotEmpty() || moduleMissing

/**
 * The language chips — from, swap, to, and the engine — with their menus, and
 * the ring's CHIPS and RESULTS regions that walk them.
 */
@Composable
private fun TranslateLanguageRow(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    menus: TranslateMenus,
    modifier: Modifier = Modifier,
) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    val target = state.settings.translateTargetLang
    val engine = when {
        !OfflineTranslation.available -> TranslateEngine.ONLINE
        !InternetPermission.granted -> TranslateEngine.ON_DEVICE
        else -> state.settings.translate.engine
    }
    // Pick mode (#727): a chooser of the providers that are on takes the
    // engine chip's place, since choosing the phone is choosing the engine.
    val providers = rememberProviderChoices(ProviderTool.TRANSLATE, state.settings)
    val engineChip = OfflineTranslation.available && InternetPermission.granted && providers == null
    val menu = menus.open
    val rows = menu?.let { translateMenuRows(it, translate, target, engine, state.settings, menus.showAll) }.orEmpty()
    val pick: (TranslateMenu, String) -> Unit = { which, key ->
        if (key == ALL_LANGUAGES_KEY) {
            menus.showAll = true
        } else {
            menus.open = null
            when (which) {
                TranslateMenu.SOURCE -> callbacks.onSource(key)
                TranslateMenu.TARGET -> callbacks.onTarget(key)
                TranslateMenu.ENGINE ->
                    TranslateEngine.entries.firstOrNull { it.name == key }?.let(callbacks.onEngine)
            }
        }
    }

    // The ring's regions. CHIPS is the header, in reading order; RESULTS is
    // the open menu's rows (zero rows closed, so Tab skips it); ACTIONS is
    // Replace/Insert (see [TranslateActionRow]).
    // Known v1 gap: Esc while a menu Popup is open closes the whole panel
    // (the open flag is composable-local, invisible to the service); Enter
    // on the CHIPS region reopens it cheaply.
    val headerChips = buildList {
        add(TranslateMenu.SOURCE)
        add(null) // swap
        add(TranslateMenu.TARGET)
        if (engineChip) add(TranslateMenu.ENGINE)
    }
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.CHIPS,
        count = headerChips.size,
        columns = headerChips.size,
        onActivate = { index ->
            val opens = headerChips.getOrNull(index)
            if (opens != null) menus.open = opens else callbacks.onSwap()
        },
    )
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.RESULTS,
        count = rows.size,
        columns = 1,
        onActivate = { index ->
            val open = menus.open
            val row = rows.getOrNull(index)
            if (open != null && row != null && !row.dimmed) pick(open, row.key)
        },
    )

    val focusedChip = state.focusedIndex(FocusRegion.CHIPS)
    val focusedRow = state.focusedIndex(FocusRegion.RESULTS)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f, fill = false)) {
            TranslateChip(
                label = sourceLabel(translate),
                strong = translate.sourceOverride.isNotBlank(),
                focused = focusedChip == headerChips.indexOf(TranslateMenu.SOURCE),
                description = stringResource(R.string.ime_translate_select_source_desc),
            ) { menus.open = TranslateMenu.SOURCE }
            if (menu == TranslateMenu.SOURCE) {
                TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.SOURCE, it) }) { menus.open = null }
            }
        }
        val canSwap = translate.translated.isNotEmpty() &&
            TranslateClient.pickerCode(translate.detectedSource)
                ?.equals(target, ignoreCase = true) == false
        // Half a turn per swap, opt-in: the arrows going round is the two
        // languages trading places.
        val animate = kb.motion.toolResults
        var swapTurns by remember { mutableIntStateOf(0) }
        val swapTurn = animateFloatAsState(
            targetValue = swapTurns * 180f,
            animationSpec = if (animate) tween(TranslateSwapMs, easing = FastOutSlowInEasing) else tween(0),
            label = "translateSwap",
        )
        IconHint(
            stringResource(R.string.ime_translate_swap_desc),
            disabledReason = if (canSwap) {
                null
            } else {
                DisabledReason(
                    stringResource(
                        if (translate.translated.isEmpty()) {
                            R.string.ime_disabled_translate_nothing
                        } else {
                            R.string.ime_disabled_translate_same_language
                        },
                    ),
                )
            },
        ) {
            Icon(
                Icons.Outlined.SwapHoriz,
                contentDescription = stringResource(R.string.ime_translate_swap_desc),
                tint = if (canSwap) kb.toolbarIcon else kb.secondaryText.copy(alpha = 0.4f),
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clip(kb.chipShape())
                    .focusRing(focusedChip == 1, kb.chipShape())
                    .clickable(enabled = canSwap) {
                        if (animate) swapTurns++
                        callbacks.onSwap()
                    }
                    .padding(4.dp)
                    .size(18.dp)
                    .graphicsLayer { rotationZ = swapTurn.value },
            )
        }
        Box(modifier = Modifier.weight(1f, fill = false)) {
            TranslateChip(
                label = TranslateClient.languageName(target),
                strong = true,
                focused = focusedChip == headerChips.indexOf(TranslateMenu.TARGET),
                description = stringResource(R.string.ime_translate_select_language_desc),
            ) { menus.open = TranslateMenu.TARGET }
            if (menu == TranslateMenu.TARGET) {
                TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.TARGET, it) }) { menus.open = null }
            }
        }
        Spacer(Modifier.weight(1f))
        // Says which service answered only when it is the one the user
        // brought (#331): DeepL falls back to the usual service for a
        // language it does not have, and that switch should not be silent.
        // The user's own server (#435) never falls back, but the engine chip
        // still reads "Online", so it says where the text went.
        // The on-device engine standing in while offline (#452) says so too:
        // the chip still reads "Online".
        val via = when {
            translate.translated.isEmpty() || providers != null -> null
            translate.offlineStandIn -> R.string.ime_translate_offline_standin_label
            translate.viaServer -> R.string.ime_translate_server_label
            translate.viaDeepL -> R.string.ime_translate_deepl_label
            else -> null
        }
        if (via != null) {
            Text(
                stringResource(via),
                color = kb.secondaryText,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (translate.translating) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(14.dp),
                strokeWidth = 2.dp,
                color = kb.accent,
            )
        }
        if (providers != null) {
            ProviderChooser(providers, onPick = { ProviderPicks.choose(ProviderTool.TRANSLATE, it) })
        } else if (engineChip) {
            Box {
                TranslateChip(
                    label = stringResource(engine.labelRes),
                    strong = false,
                    focused = focusedChip == headerChips.indexOf(TranslateMenu.ENGINE),
                    description = stringResource(R.string.ime_translate_select_engine_desc),
                    // On Automatic the icon answers "which one was it this
                    // time?", which is the only thing the mode leaves open.
                    icon = when {
                        engine != TranslateEngine.AUTO || translate.translated.isEmpty() -> engine.icon
                        translate.onDevice -> TranslateEngine.ON_DEVICE.icon
                        else -> TranslateEngine.ONLINE.icon
                    },
                ) { menus.open = TranslateMenu.ENGINE }
                if (menu == TranslateMenu.ENGINE) {
                    TranslateMenuPopup(rows, focusedRow, { pick(TranslateMenu.ENGINE, it) }) { menus.open = null }
                }
            }
        }
    }
}

/** The translation, the error in its place, or the offer of the models it needs. */
@Composable
private fun TranslateResult(state: KeyboardUiState, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    val animate = kb.motion.toolResults
    MotionCrossfade(
        target = translate.offersDownload(),
        animate = animate,
        modifier = modifier,
        label = "translateResult",
    ) { offer ->
        if (offer) {
            TranslateDownloadOffer(translate = translate, modifier = Modifier.fillMaxSize(), animate = animate)
        } else {
            TranslateText(state, animate, Modifier.fillMaxSize())
        }
    }
}

/** The translation, or the line standing in for it, fading in where it changed. */
@Composable
private fun TranslateText(state: KeyboardUiState, animate: Boolean, modifier: Modifier) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    RevealedText(
        text = when {
            translate.error != null -> translate.error
            translate.translated.isNotEmpty() -> translate.translated
            translate.translating -> stringResource(R.string.ime_translate_progress)
            else -> stringResource(R.string.ime_translate_idle)
        },
        color = when {
            translate.error != null -> kb.accent
            translate.translated.isEmpty() -> kb.secondaryText
            else -> kb.suggestionText
        },
        fontSize = if (state.mediaSearchActive) 14.sp else 16.sp,
        animate = animate,
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
    )
}

/**
 * [text], with what changed since it was last drawn fading in. The result
 * follows the text box live, so a translation mostly grows or changes at its
 * end: the words that stayed hold still and only the rest arrives, from the
 * start of the first word that differs. A script with no spaces fades in
 * whole, which is also what a first translation does.
 *
 * Reads the fade in composition, so only this Text redraws for its length.
 */
@Composable
private fun RevealedText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    animate: Boolean,
    modifier: Modifier,
) {
    var settled by remember { mutableStateOf(text) }
    val keep = remember(text) { if (animate) revealStart(settled, text) else text.length }
    val fade = remember(text) { Animatable(if (keep < text.length) 0f else 1f) }
    LaunchedEffect(text) {
        settled = text
        if (fade.value < 1f) fade.animateTo(1f, tween(TranslateRevealMs, easing = FastOutSlowInEasing))
    }
    val p = fade.value
    val shown = if (p >= 1f) {
        AnnotatedString(text)
    } else {
        buildAnnotatedString {
            append(text, 0, keep)
            withStyle(SpanStyle(color = color.copy(alpha = color.alpha * p))) { append(text, keep, text.length) }
        }
    }
    Text(text = shown, color = color, fontSize = fontSize, modifier = modifier)
}

/**
 * Where [new] stops matching [old], pulled back to the start of that word: the
 * part of [new] that [RevealedText] fades in begins here. [new]'s length when
 * nothing changed.
 */
internal fun revealStart(old: String, new: String): Int {
    val shared = minOf(old.length, new.length)
    var i = 0
    while (i < shared && old[i] == new[i]) i++
    if (i == new.length && i == old.length) return i
    while (i > 0 && !new[i - 1].isWhitespace()) i--
    return i
}

private const val TranslateRevealMs = 200
private const val TranslateSwapMs = 260
private const val TranslateProgressMs = 300

/**
 * Replace and Insert, or the one download button while that is the offer,
 * with the ring's ACTIONS region over them. Each button is a share of the row
 * under the result ([shareRow]), or [actionModifier]'s minimum width in the
 * header.
 */
@Composable
private fun TranslateActionRow(
    state: KeyboardUiState,
    callbacks: TranslateCallbacks,
    modifier: Modifier,
    actionModifier: Modifier = Modifier,
    /** Each button takes an equal share of the row's width. */
    shareRow: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val translate = state.translate
    // The offline build's offer says where to import; it has nothing to press.
    val offerDownload = translate.offersDownload() && InternetPermission.granted
    PanelFocusTarget(
        panel = PanelMode.TRANSLATE,
        region = FocusRegion.ACTIONS,
        count = if (offerDownload) 1 else 2,
        columns = if (offerDownload) 1 else 2,
        onActivate = { index ->
            when {
                offerDownload -> callbacks.onDownload()
                translate.translated.isEmpty() -> Unit
                index == 0 -> callbacks.onReplace()
                else -> callbacks.onInsert()
            }
        },
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val focusedAction = state.focusedIndex(FocusRegion.ACTIONS)
        val sized = if (shareRow) actionModifier.weight(1f) else actionModifier
        if (offerDownload) {
            val downloading = translate.missingModels.any { translate.models[it] is OfflineModelState.Downloading }
            // Play cancels a module by session and the panel holds none,
            // so while the module is on its way the button only reports.
            val moduleComing = translate.moduleMissing && translate.module is TranslateModuleState.Installing
            TranslateAction(
                label = stringResource(
                    when {
                        moduleComing -> R.string.ime_translate_module_installing_action
                        downloading -> CommonR.string.common_cancel
                        translate.meteredAsk -> R.string.ime_metered_allow_action
                        translate.licenceAsk -> R.string.ime_translate_nllb_licence_accept_action
                        else -> CommonR.string.common_download
                    },
                ),
                icon = if (downloading || moduleComing) null else Icons.Outlined.Download,
                enabled = !moduleComing,
                modifier = sized.focusRing(focusedAction == 0, kb.chipShape()),
            ) { callbacks.onDownload() }
        } else {
            val nothing = DisabledReason(stringResource(R.string.ime_disabled_translate_nothing))
            TranslateAction(
                label = stringResource(R.string.ime_translate_replace_action),
                icon = Icons.Outlined.SwapVert,
                enabled = translate.translated.isNotEmpty(),
                modifier = sized.focusRing(focusedAction == 0, kb.chipShape()),
                disabledReason = nothing,
            ) { callbacks.onReplace() }
            TranslateAction(
                label = stringResource(R.string.ime_insert_action),
                icon = null,
                enabled = translate.translated.isNotEmpty(),
                modifier = sized.focusRing(focusedAction == 1, kb.chipShape()),
                disabledReason = nothing,
            ) { callbacks.onInsert() }
        }
    }
}

/**
 * The text being translated: a box of several lines, where the header's search
 * bar held one. While it has the keys it is an editor of the capture ladder's
 * buffer, caret and all, the same as the AI chat's composer; otherwise a tap
 * gives it the keys.
 */
@Composable
private fun TranslateQueryBox(
    state: KeyboardUiState,
    focused: Boolean,
    onQueryTap: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier,
    /** In the header's one row: no room for more than a line, nor for the margins. */
    singleLine: Boolean = false,
) {
    val kb = LocalKbTheme.current
    val shape = kb.cardShape()
    val hint = stringResource(R.string.ime_translate_hint)
    Box(
        modifier = modifier
            .padding(top = if (singleLine) 4.dp else 2.dp, bottom = if (singleLine) 4.dp else 6.dp)
            .clip(shape)
            .frostGlass(kb, shape, FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, shape)
            .focusRing(focused, shape)
            .clickable(enabled = !state.mediaSearchActive) { onQueryTap() }
            // Room on the right for the paste button below.
            .padding(
                start = 12.dp,
                end = 12.dp + TranslatePasteSize,
                top = if (singleLine) 4.dp else 8.dp,
                bottom = if (singleLine) 4.dp else 8.dp,
            ),
    ) {
        if (state.mediaSearchActive) {
            ClipEditText(
                text = state.mediaQuery,
                placeholder = hint,
                textColor = kb.chipText,
                placeholderColor = kb.secondaryText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 20.dp, max = if (singleLine) 28.dp else TranslateQueryMaxHeight),
            )
        } else {
            Text(
                state.mediaQuery.ifEmpty { hint },
                color = if (state.mediaQuery.isEmpty()) kb.secondaryText else kb.chipText,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = if (singleLine) 1 else TranslateQueryIdleLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Issue #474: text to translate is very often text just copied out of
        // another app, and without this it took the clipboard panel, which
        // closes this one.
        Icon(
            Icons.Outlined.ContentPaste,
            contentDescription = stringResource(CommonR.string.common_paste),
            tint = kb.secondaryText,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = TranslatePasteSize)
                .size(TranslatePasteSize)
                .clip(CircleShape)
                .clickable(onClick = onPaste)
                .padding(4.dp),
        )
    }
}

/**
 * How many lines of the text box show while the keys are away, about
 * [TranslateQueryMaxHeight]'s worth: the panel is at full height then, and
 * the result keeps the rest.
 */
private const val TranslateQueryIdleLines = 5

/** The paste button in the text box's corner, padding included. */
private val TranslatePasteSize = 28.dp

/**
 * What the source chip says: the user's pick, or what was detected, or the
 * invitation to let it detect. A guess off the keyboard's language carries a
 * "?", because that is what it is, and tapping the chip is how to correct it.
 */
@Composable
private fun sourceLabel(translate: TranslateUi): String = when {
    translate.sourceOverride.isNotBlank() -> TranslateClient.languageName(translate.sourceOverride)
    translate.detectedSource.isBlank() ||
        (translate.translated.isEmpty() && translate.missingModels.isEmpty()) ->
        stringResource(R.string.ime_translate_auto_detect_label)
    translate.sourceGuessed -> stringResource(
        R.string.ime_translate_source_guessed_label,
        TranslateClient.languageName(translate.detectedSource),
    )
    else -> TranslateClient.languageName(translate.detectedSource)
}

/**
 * The languages of one menu, as picker codes, in the order they are drawn.
 * Kept free of Compose so the rules can be tested alone.
 *
 * [ready] is a language the engine can take without a new download: its model
 * is on the device or on its way. [downloadedFirst] puts those at the top,
 * each group still in picker order. [shortList] leaves out the rest, except
 * what [keep] holds on to (the selection, the languages the user types in).
 */
internal fun arrangeTranslateLanguages(
    codes: List<String>,
    ready: (String) -> Boolean,
    keep: (String) -> Boolean,
    downloadedFirst: Boolean,
    shortList: Boolean,
): List<String> {
    val shown = if (shortList) codes.filter { ready(it) || keep(it) } else codes
    if (!downloadedFirst) return shown
    val (top, rest) = shown.partition(ready)
    return top + rest
}

@Composable
private fun translateMenuRows(
    menu: TranslateMenu,
    translate: TranslateUi,
    target: String,
    engine: TranslateEngine,
    settings: KeyboardSettings,
    showAll: Boolean,
): List<TranslateMenuRow> {
    if (menu == TranslateMenu.ENGINE) {
        return TranslateEngine.entries.map { entry ->
            TranslateMenuRow(
                key = entry.name,
                label = stringResource(entry.labelRes),
                selected = entry == engine,
                info = stringResource(
                    when (entry) {
                        TranslateEngine.ONLINE -> R.string.ime_translate_engine_online_info
                        TranslateEngine.ON_DEVICE -> R.string.ime_translate_engine_on_device_info
                        TranslateEngine.AUTO -> R.string.ime_translate_engine_auto_info
                    },
                ),
            )
        }
    }
    // The model marks only mean something where the on-device engine is in
    // play; on Online they would be noise about a thing the user is not using.
    val showModels = engine != TranslateEngine.ONLINE
    val onlineOnly = stringResource(R.string.ime_translate_online_only_label)
    val selected = if (menu == TranslateMenu.SOURCE) translate.sourceOverride else target
    val names = TranslateClient.languages.toMap()
    // The engines on the translate list: ML Kit, Bergamot, or both. Each
    // reads its own set of languages and keeps its own models.
    val engines = remember(settings) {
        TranslateProviders.rows(settings, TranslateEngine.ON_DEVICE)
            .mapNotNullTo(mutableSetOf()) { OfflineTranslation.engineOf(it.typeId) }
    }
    val supported: (String) -> Boolean = { code -> OfflineTranslation.supports(code, engines) }
    val downloaded: (String) -> Boolean = { code -> OfflineTranslation.ready(code, engines, translate.models) }
    val typed = settings.enabledLanguages.mapNotNullTo(mutableSetOf()) {
        TranslateClient.pickerCode(it.id.replace('_', '-'))?.lowercase()
    }
    // An empty map is "not looked yet", not "nothing downloaded": a short
    // list drawn off it would be English alone.
    val shortList = engine == TranslateEngine.ON_DEVICE && settings.translate.onlyDownloaded &&
        !showAll && translate.models.isNotEmpty()
    val codes = TranslateClient.languages.map { it.first }
    val arranged = arrangeTranslateLanguages(
        codes = codes,
        ready = { code -> downloaded(code) || OfflineTranslation.coming(code, engines, translate.models) },
        keep = { code -> code.equals(selected, ignoreCase = true) || code.lowercase() in typed },
        downloadedFirst = showModels && settings.translate.downloadedFirst,
        shortList = shortList,
    )
    val languages = arranged.map { code ->
        val onDevice = supported(code)
        TranslateMenuRow(
            key = code,
            label = names[code] ?: code,
            selected = code.equals(selected, ignoreCase = true),
            note = onlineOnly.takeIf { showModels && !onDevice },
            downloaded = showModels && onDevice && downloaded(code),
            // Only a dead end when nothing else can take it: Automatic hands
            // these to the online service.
            dimmed = engine == TranslateEngine.ON_DEVICE && !onDevice,
        )
    } + listOfNotNull(
        TranslateMenuRow(
            key = ALL_LANGUAGES_KEY,
            label = stringResource(R.string.ime_translate_all_languages_label),
            selected = false,
            info = stringResource(R.string.ime_translate_all_languages_info),
        ).takeIf { arranged.size < codes.size },
    )
    if (menu == TranslateMenu.TARGET) return languages
    val detect = TranslateMenuRow(
        key = "",
        label = stringResource(R.string.ime_translate_auto_detect_label),
        selected = translate.sourceOverride.isBlank(),
    )
    return listOf(detect) + languages
}

@Composable
private fun TranslateChip(
    label: String,
    strong: Boolean,
    focused: Boolean,
    description: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    Row(
        modifier = Modifier
            .clip(kb.chipShape())
            .frostGlass(kb, kb.chipShape(), FrostSurface.CHIPS)
            .background(kb.chip)
            .chipBorder(kb, kb.chipShape())
            .focusRing(focused, kb.chipShape())
            .clickable { onClick() }
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = kb.toolbarIcon)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            label,
            color = kb.chipText,
            fontSize = 12.sp,
            fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            Icons.Outlined.ArrowDropDown,
            contentDescription = description,
            modifier = Modifier.size(18.dp),
            tint = kb.toolbarIcon,
        )
    }
}

/**
 * The result area while the on-device engine is short of a model: what it
 * needs and what that costs, then the download as it runs. The button itself
 * is the panel's action row, so it sits where Replace and Insert will be.
 */
@Composable
private fun TranslateDownloadOffer(translate: TranslateUi, modifier: Modifier = Modifier, animate: Boolean = false) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    if (translate.moduleMissing) {
        TranslateModuleOffer(translate, modifier, animate)
        return
    }
    val states = translate.missingModels.map { translate.models[it] }
    val running = states.filterIsInstance<OfflineModelState.Downloading>()
    val waiting = translate.missingModels.filter { translate.models[it] !is OfflineModelState.Downloaded }
    val engineName = stringResource(R.string.ime_translate_bergamot_engine_name)
    val names = waiting.joinToString(", ") { OfflineTranslation.label(it, TranslateClient::languageName, engineName) }
    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (running.isNotEmpty()) {
            Text(
                stringResource(R.string.ime_translate_downloading_progress, names),
                color = kb.suggestionText,
                fontSize = 14.sp,
            )
            // One bar for the lot: two models are one wait, as far as the
            // person watching is concerned.
            val bytes = running.sumOf { it.bytes }
            val total = running.sumOf { it.totalBytes }
            if (total > 0L && running.all { it.totalBytes > 0L }) {
                val progress = smoothProgress((bytes.toFloat() / total).coerceIn(0f, 1f), animate)
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                    color = kb.accent,
                    trackColor = kb.chip,
                )
                Text(
                    stringResource(
                        R.string.ime_translate_download_of_total_progress,
                        Formatter.formatShortFileSize(context, bytes),
                        Formatter.formatShortFileSize(context, total),
                    ),
                    color = kb.secondaryText,
                    fontSize = 12.sp,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = kb.accent,
                    trackColor = kb.chip,
                )
            }
            return@Column
        }
        translate.error?.let { Text(it, color = kb.accent, fontSize = 13.sp) }
        if (!InternetPermission.granted) {
            Text(
                stringResource(R.string.ime_translate_offline_import_body, names),
                color = kb.suggestionText,
                fontSize = 14.sp,
            )
            return@Column
        }
        Text(
            stringResource(
                R.string.ime_translate_need_models_body,
                names,
                Formatter.formatShortFileSize(
                    context,
                    waiting.sumOf { OfflineTranslation.downloadBytes(it) }
                        .takeIf { it > 0L } ?: OfflineTranslateLanguages.APPROX_MODEL_BYTES,
                ),
            ),
            color = kb.suggestionText,
            fontSize = 14.sp,
        )
        when {
            translate.meteredAsk -> Text(
                stringResource(R.string.ime_metered_ask_body),
                color = kb.accent,
                fontSize = 13.sp,
            )
            translate.licenceAsk -> Text(
                stringResource(R.string.ime_translate_nllb_licence_body),
                color = kb.accent,
                fontSize = 13.sp,
            )
            states.any { (it as? OfflineModelState.Missing)?.failed == true } -> Text(
                stringResource(R.string.ime_translate_download_failed_error),
                color = kb.accent,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * The same offer one step earlier, on a Play install: the engine is an
 * on-demand module and has not been fetched. Play reports the real size once
 * the fetch starts, so none is promised before that.
 */
@Composable
private fun TranslateModuleOffer(translate: TranslateUi, modifier: Modifier = Modifier, animate: Boolean = false) {
    val kb = LocalKbTheme.current
    val context = LocalContext.current
    val module = translate.module
    Column(
        modifier = modifier
            .padding(vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        translate.error?.let { Text(it, color = kb.accent, fontSize = 13.sp) }
        Text(
            stringResource(R.string.ime_translate_module_missing_body),
            color = kb.suggestionText,
            fontSize = 14.sp,
        )
        when {
            module is TranslateModuleState.Installing -> {
                val fraction = if (module.totalBytes > 0L) {
                    (module.bytes.toFloat() / module.totalBytes).coerceIn(0f, 1f)
                } else {
                    null
                }
                if (fraction != null) {
                    val progress = smoothProgress(fraction, animate)
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth(),
                        color = kb.accent,
                        trackColor = kb.chip,
                    )
                    Text(
                        stringResource(
                            R.string.ime_translate_download_of_total_progress,
                            Formatter.formatShortFileSize(context, module.bytes),
                            Formatter.formatShortFileSize(context, module.totalBytes),
                        ),
                        color = kb.secondaryText,
                        fontSize = 12.sp,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = kb.accent,
                        trackColor = kb.chip,
                    )
                }
            }
            translate.meteredAsk -> Text(
                stringResource(R.string.ime_metered_ask_body),
                color = kb.accent,
                fontSize = 13.sp,
            )
            (module as? TranslateModuleState.Missing)?.failed == true -> Text(
                stringResource(R.string.ime_translate_module_failed_error),
                color = kb.accent,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * A download's progress for a bar to read at draw time: eased between the
 * updates when [animate], which arrive in steps of a few hundred kilobytes,
 * and the raw figure otherwise.
 */
@Composable
private fun smoothProgress(target: Float, animate: Boolean): () -> Float {
    if (!animate) return { target }
    val eased = animateFloatAsState(target, tween(TranslateProgressMs), label = "translateProgress")
    return { eased.value }
}

@Composable
private fun TranslateAction(
    label: String,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** What holding the button says while it is greyed out; see [IconHint]. */
    disabledReason: DisabledReason? = null,
    onClick: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val shape = kb.chipShape()
    IconHint(null, modifier, disabledReason = disabledReason.takeUnless { enabled }) {
        Row(
            modifier = Modifier
                .clip(shape)
                .frostGlass(kb, shape, FrostSurface.CHIPS)
                .background(if (enabled) kb.chipActive else kb.chip)
                .chipBorder(kb, shape)
                .clickable(enabled = enabled) { onClick() }
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (enabled) kb.chipActiveText else kb.secondaryText,
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                label,
                color = if (enabled) kb.chipActiveText else kb.secondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TranslateMenuPopup(
    rows: List<TranslateMenuRow>,
    focused: Int?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kb = LocalKbTheme.current
    val scroll = rememberScrollState()
    // Sixty languages behind a 260 dp window: the rail is the only thing
    // saying the list runs past the third one.
    val rail = rememberScrollRailState(scroll)
    val downloadedDesc = stringResource(R.string.ime_translate_downloaded_desc)
    Popup(onDismissRequest = onDismiss) {
        ScrollRail(
            state = rail,
            modifier = Modifier
                .widthIn(min = 200.dp, max = 260.dp)
                .heightIn(max = 260.dp)
                .clip(kb.menuShape())
                .background(kb.popup)
                .popupBorder(kb, kb.menuShape())
                .padding(vertical = 4.dp),
            fadeColor = kb.popup,
            colors = kbRailColors(kb),
        ) {
            for ((index, row) in rows.withIndex()) {
                val textColor = when {
                    row.selected -> kb.accent
                    row.dimmed -> kb.popupText.copy(alpha = 0.4f)
                    else -> kb.popupText
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRing(index == focused, RoundedCornerShape(0.dp))
                        .clickable(enabled = !row.dimmed) { onPick(row.key) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            row.label,
                            color = textColor,
                            fontWeight = if (row.selected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        row.info?.let { Text(it, color = kb.secondaryText, fontSize = 11.sp) }
                    }
                    when {
                        row.downloaded -> Icon(
                            Icons.Outlined.DownloadDone,
                            contentDescription = downloadedDesc,
                            modifier = Modifier.size(16.dp),
                            tint = kb.secondaryText,
                        )
                        row.note != null -> Text(
                            row.note,
                            color = kb.secondaryText.copy(alpha = if (row.dimmed) 0.6f else 1f),
                            fontSize = 11.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        // Rows in one menu are one height, so the ring's offset is index arithmetic.
        ScrollFocusIntoView(focused) { index ->
            val row = if (rows.isEmpty()) 0 else scroll.maxValue / rows.size + 1
            scroll.animateScrollTo((index * row - row).coerceAtLeast(0))
        }
    }
}
