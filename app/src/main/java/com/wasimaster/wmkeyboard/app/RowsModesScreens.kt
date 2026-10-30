package com.wasimaster.wmkeyboard.app

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCut
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.core.settings.DefaultBarOrder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.DragHandle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.wasimaster.wmkeyboard.core.settings.EmojiBarMode
import com.wasimaster.wmkeyboard.ime.ui.ModeIcons
import com.wasimaster.wmkeyboard.core.settings.BarRow
import com.wasimaster.wmkeyboard.core.settings.SelectionMacroPlacement
import com.wasimaster.wmkeyboard.core.settings.StickerSuggestStyle
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.layoutAfterFancy
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardModeCodec
import com.wasimaster.wmkeyboard.core.settings.DefaultKeyboardModes
import com.wasimaster.wmkeyboard.core.settings.ManualModeDuration
import com.wasimaster.wmkeyboard.core.settings.RecentWordPlacement
import com.wasimaster.wmkeyboard.core.settings.SymbolRowHeightRange
import com.wasimaster.wmkeyboard.core.settings.SymbolRowLinesRange
import com.wasimaster.wmkeyboard.core.settings.SymbolRowScroll
import com.wasimaster.wmkeyboard.core.tools.sanitizeSymbolPopups
import com.wasimaster.wmkeyboard.core.tools.symbolChipLabel
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.core.settings.isUsableTool
import com.wasimaster.wmkeyboard.core.settings.isOwnRow
import com.wasimaster.wmkeyboard.core.settings.modesOverridingRow
import com.wasimaster.wmkeyboard.core.settings.stripHidden
import com.wasimaster.wmkeyboard.core.settings.AppGroup
import com.wasimaster.wmkeyboard.core.settings.ModeField
import com.wasimaster.wmkeyboard.core.settings.ModeOrientation
import com.wasimaster.wmkeyboard.core.settings.ModeScreenSize
import com.wasimaster.wmkeyboard.core.settings.OneHandedMode
import com.wasimaster.wmkeyboard.core.tools.BuiltInSymbolSets
import com.wasimaster.wmkeyboard.core.tools.resolveSymbolSets
import com.wasimaster.wmkeyboard.core.tools.SymbolSet
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarPlacement
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.PlaylistAdd
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AltRoute
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FindReplace
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Keyboard
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState

/** The line counts the symbol row stepper walks, one press per line. */
private val SymbolRowLinesSteps: List<Int> = SymbolRowLinesRange.toList()

// ---- rows & bars ----

/**
 * The row's name in the order list.
 *
 * The strip's own name depends on what is in it: with the tools sharing it
 * there is one row and one entry for both, and with the tools on a row of
 * their own ("Where the tools go" set to Always or Button) the strip is
 * suggestions and nothing else, so it says so and the tools row is the entry
 * next to it — see [barRowsListed], which is what drops the tools entry back
 * out again when they return to the strip.
 */
@StringRes
private fun barRowTitle(row: BarRow, settings: KeyboardSettings): Int = when (row) {
    BarRow.TOPBAR -> if (settings.toolbarBehavior.placement.isOwnRow) {
        R.string.rows_bar_topbar_suggestions_title
    } else {
        R.string.rows_bar_topbar_title
    }
    BarRow.EMOJI -> R.string.rows_bar_emoji_title
    BarRow.SYMBOL -> R.string.rows_symbol_row_title
    BarRow.FANCY -> R.string.rows_bar_fancy_title
    BarRow.TOOLS -> R.string.rows_bar_tools_title
    BarRow.DICTIONARY -> R.string.rows_dictionary_bar_title
    BarRow.MACROS -> R.string.rows_bar_macros_title
    BarRow.STICKERS -> R.string.rows_bar_stickers_title
    BarRow.RECENT -> R.string.typing_recent_word_bar_title
    BarRow.KEYBOARD -> R.string.rows_bar_keyboard_title
}

/**
 * Why a row is not on screen right now, or where it is instead — null for a
 * row that simply draws in its slot, which the preview under the title
 * already shows. The keys are always there and never say anything.
 */
@StringRes
private fun barRowStatus(row: BarRow, settings: KeyboardSettings): Int? = when (row) {
    BarRow.TOPBAR -> CommonR.string.common_off.takeIf {
        !settings.toolbarBehavior.enabled || settings.toolbarBehavior.stripHidden
    }
    BarRow.EMOJI -> when (settings.emojiBarMode) {
        EmojiBarMode.OFF -> R.string.rows_bar_emoji_off_subtitle
        EmojiBarMode.BUTTON -> R.string.rows_bar_emoji_button_subtitle
        EmojiBarMode.ALWAYS -> null
    }
    BarRow.SYMBOL -> CommonR.string.common_off.takeUnless { settings.symbolRowEnabled }
    BarRow.FANCY -> R.string.rows_bar_fancy_off_subtitle.takeUnless { fancyTextOn(settings) }
    // Never listed with the tools on the strip ([barRowsListed]), so the only
    // things left to say are "the toolbar is off" and "the row is a tap away".
    BarRow.TOOLS -> when {
        !settings.toolbarBehavior.enabled -> CommonR.string.common_off
        settings.toolbarBehavior.placement == ToolbarPlacement.ON_DEMAND_ROW ->
            R.string.rows_bar_tools_button_subtitle
        else -> null
    }
    BarRow.DICTIONARY -> CommonR.string.common_off.takeUnless { settings.rows.dictionaryBarEnabled }
    // The row that arrives with a selection. Its "off" reads two ways: the
    // feature switched off, and the feature on but drawing over the strip
    // instead, where this row has nothing to place.
    BarRow.MACROS -> when {
        !settings.selectionMacros.enabled -> CommonR.string.common_off
        settings.selectionMacros.placement == SelectionMacroPlacement.STRIP ->
            R.string.rows_bar_macros_strip_subtitle
        else -> null
    }
    // Only listed while the feature is on ([barRowsListed]). The two strip
    // styles open it from their chip, like the on-demand tools row.
    BarRow.STICKERS -> if (settings.gif.stickerSuggestStyle == StickerSuggestStyle.TRAY) {
        R.string.rows_bar_stickers_subtitle
    } else {
        R.string.rows_bar_stickers_button_subtitle
    }
    // Only listed while it has a row of its own ([barRowsListed]).
    BarRow.RECENT -> CommonR.string.common_off.takeUnless { settings.suggestionStrip.recentWordBar }
    BarRow.KEYBOARD -> null
}

/**
 * Whether the row draws in its slot under the current settings — what dims
 * its preview. The on-demand tools row counts as drawn: it is a tap away and
 * the slot is its.
 */
private fun barRowShown(row: BarRow, settings: KeyboardSettings): Boolean = when (row) {
    BarRow.TOPBAR -> settings.toolbarBehavior.enabled && !settings.toolbarBehavior.stripHidden
    BarRow.EMOJI -> settings.emojiBarMode == EmojiBarMode.ALWAYS
    BarRow.SYMBOL -> settings.symbolRowEnabled
    BarRow.FANCY -> fancyTextOn(settings)
    BarRow.TOOLS -> settings.toolbarBehavior.enabled && settings.toolbarBehavior.placement.isOwnRow
    BarRow.DICTIONARY -> settings.rows.dictionaryBarEnabled
    BarRow.MACROS -> settings.selectionMacros.enabled &&
        settings.selectionMacros.placement == SelectionMacroPlacement.OWN_ROW
    BarRow.STICKERS -> settings.gif.stickerSuggest
    BarRow.RECENT -> settings.suggestionStrip.recentWordBar
    BarRow.KEYBOARD -> true
}

/**
 * The rows the order list offers, out of the stored order.
 *
 * Everything but the tools row and the sticker tray, always; the tools row
 * only while the tools have a row of their own, and the sticker tray only
 * while stickers are offered as you type (#329). On the strip the tools are
 * part of the strip entry above, and a second entry for a row that cannot be
 * drawn is a slot the user can drag around to no effect.
 */
internal fun barRowsListed(order: List<BarRow>, settings: KeyboardSettings): List<BarRow> =
    order.filter { row ->
        when (row) {
            BarRow.TOOLS -> settings.toolbarBehavior.placement.isOwnRow
            BarRow.STICKERS -> settings.gif.stickerSuggest
            // Inline they are part of the strip's entry, as the tools are.
            BarRow.RECENT -> settings.rows.recentWordPlacement == RecentWordPlacement.OWN_ROW
            else -> true
        }
    }

/**
 * [listed], reordered by the user, merged back into the complete [stored]
 * order — so a row the list is not showing keeps the place it had rather than
 * being dropped or shuffled to an end. It goes back after the nearest row that
 * preceded it in storage and is still present, which is where it was.
 */
internal fun barOrderMerged(listed: List<BarRow>, stored: List<BarRow>): List<BarRow> {
    val out = listed.toMutableList()
    for (row in stored.filter { it !in listed }) {
        val before = stored.take(stored.indexOf(row)).lastOrNull { it in out }
        out.add(before?.let { out.indexOf(it) + 1 } ?: 0, row)
    }
    return out
}

/** Height of a bar-order row: a title, an optional status line and the preview strip. */
private val BarOrderRowHeight = 76.dp

/** Height of the strip that previews what a row looks like on the keyboard. */
private val BarRowPreviewHeight = 22.dp

/**
 * One entry of the row order: its name, why it is not showing (when it is
 * not), and a schematic of the row itself — which is what makes the list
 * scannable, since seven rows named in prose all read alike.
 */
@Composable
private fun BarRowCard(
    title: String,
    status: String?,
    row: BarRow,
    shown: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != null) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        BarRowPreview(row, shown)
    }
}

/**
 * A schematic of the row at strip height: chips and tool circles for the
 * strip, a handful of emoji, symbols, styled letters, and a tiny key grid for
 * the keys. Dimmed while the row is not drawing, so the list reads at a
 * glance which slots are live. The emoji are one Text each: emoji fonts
 * often have no space glyph, and a spaced string of them draws boxes.
 */
@Composable
private fun BarRowPreview(row: BarRow, shown: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarRowPreviewHeight)
            .alpha(if (shown) 1f else DimmedPreviewAlpha)
            .background(scheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (row) {
            BarRow.TOPBAR -> {
                for (width in listOf(34.dp, 26.dp, 30.dp)) PreviewPill(width, scheme.secondaryContainer)
                Spacer(Modifier.weight(1f))
                repeat(3) { PreviewDot(scheme.primaryContainer) }
            }
            BarRow.EMOJI -> for (emoji in PreviewEmoji) PreviewGlyph(emoji)
            BarRow.SYMBOL -> for (symbol in PreviewSymbols) PreviewGlyph(symbol)
            BarRow.FANCY -> for (style in PreviewFancy) PreviewGlyph(style)
            BarRow.TOOLS -> repeat(6) { PreviewDot(scheme.primaryContainer) }
            // Dictionary chips: two on, one off.
            BarRow.DICTIONARY -> for ((width, on) in listOf(30.dp to true, 26.dp to true, 34.dp to false)) {
                PreviewPill(width, if (on) scheme.primaryContainer else scheme.surfaceVariant)
            }
            BarRow.MACROS -> for (icon in listOf(
                Icons.Outlined.ContentCopy, Icons.Outlined.ContentCut, Icons.Outlined.ContentPaste,
            )) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = scheme.onSurfaceVariant)
            }
            // A few sticker-sized squares and the tray's close mark.
            BarRow.STICKERS -> {
                repeat(4) { PreviewPill(16.dp, scheme.tertiaryContainer) }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = scheme.onSurfaceVariant,
                )
            }
            // A word and the chips of what else it could be, twice.
            BarRow.RECENT -> repeat(2) {
                PreviewGlyph("abc")
                PreviewPill(22.dp, scheme.secondaryContainer)
            }
            BarRow.KEYBOARD -> PreviewKeys(scheme.outline)
        }
    }
}

private const val DimmedPreviewAlpha = 0.38f
private val PreviewEmoji = listOf("\uD83D\uDE00", "\uD83D\uDE02", "\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDD25")
private val PreviewSymbols = listOf("@", "#", "$", "%", "&", "(", ")", "/")
private val PreviewFancy = listOf("\uD835\uDC00\uD835\uDC1A", "\uD835\uDE08\uD835\uDE22", "\uD835\uDC9C\uD835\uDCB6", "\u24B6\u24D0")
/** Keys per row of the mini grid, top to bottom. */
private val PreviewKeyRows = listOf(10, 9, 7)

@Composable
private fun PreviewPill(width: Dp, color: Color) {
    Box(
        Modifier
            .width(width)
            .height(12.dp)
            .background(color, RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun PreviewDot(color: Color) {
    Box(
        Modifier
            .size(14.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun PreviewGlyph(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreviewKeys(color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (count in PreviewKeyRows) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(count) {
                    Box(
                        Modifier
                            .width(6.dp)
                            .height(5.dp)
                            .background(color, RoundedCornerShape(1.dp)),
                    )
                }
            }
        }
    }
}

/**
 * Whether the keyboard is typing in Fancy Text right now — what the toggle
 * on the Rows screen reads, and the condition under which the style strip
 * takes its row. The stored active layout is the whole truth from here: the
 * service's per-app memory can put another layout on screen, but that is
 * the app's choice, not the user's setting.
 */
private fun fancyTextOn(settings: KeyboardSettings): Boolean =
    settings.activeLayoutId == AssetLayouts.FANCY_ID

/**
 * The Rows-screen twin of the toolbar's Fancy tool. On: the fancy layout
 * joins the enabled cycle (if it wasn't there) and becomes the active one.
 * Off: the keyboard returns to the first other enabled layout, and the fancy
 * layout leaves the cycle unless the tool's "keep it" option says otherwise
 * — the same two rules the tool follows, so the two switches never disagree
 * about what "off" leaves behind.
 */
private suspend fun setFancyTextOn(repository: SettingsRepository, settings: KeyboardSettings, on: Boolean) {
    val enabled = settings.enabledLayoutIds
    if (on) {
        if (AssetLayouts.FANCY_ID !in enabled) {
            repository.setEnabledLayoutIds(enabled + AssetLayouts.FANCY_ID)
        }
        repository.setActiveLayoutId(AssetLayouts.FANCY_ID)
    } else {
        val remaining = enabled.filter { it != AssetLayouts.FANCY_ID }
        if (!settings.layoutBehavior.fancyToolKeepsLanguage && remaining.isNotEmpty()) {
            repository.setEnabledLayoutIds(remaining)
        }
        repository.setActiveLayoutId(layoutAfterFancy(null, remaining))
    }
}
/** Row layout above the keys: symbol row, row order and symbol sets. */
@Composable
internal fun RowsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val symbolRowOverridden = settings.watch { it.modesOverridingRow(BarRow.SYMBOL).isNotEmpty() }
    // Every entry moves, the keys included (issue #83): a row dragged above
    // the Keyboard entry sits over the keys, one dragged below it sits under.
    SettingsGroup(
        stringResource(R.string.rows_row_order_title),
        info = stringResource(R.string.rows_row_order_caption),
        // Reorders one row until an optional one is switched on (the audit).
        level = SettingLevel.STANDARD,
        action = {
            if (settings.watch { it.barOrder != DefaultBarOrder }) {
                TextButton(onClick = { scope.launch { repository.setBarOrder(DefaultBarOrder) } }) {
                    Text(stringResource(CommonR.string.common_reset))
                }
            }
        },
    ) {
        item {
            // Resolved before the list: the drag list takes a plain
            // (T) -> String.
            val order = settings.watch { barRowsListed(it.barOrder, it) }
            val titles = settings.watch { s -> order.associateWith { barRowTitle(it, s) } }
            val rowNames = order.associateWith { stringResource(titles.getValue(it)) }
            ReorderableColumn(
                order,
                label = { rowNames[it].orEmpty() },
                onReorder = { next ->
                    scope.launch {
                        repository.setBarOrder(barOrderMerged(next, settings.value.barOrder))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
                rowHeight = BarOrderRowHeight,
            ) { row ->
                val status = settings.watch { barRowStatus(row, it) }?.let { stringResource(it) }
                // The global switch is not the whole story when a mode draws the
                // row its own way; say so, and the Emoji / Symbol row page names them.
                val overrides = settings.watch { it.modesOverridingRow(row).size }
                val modeNote = if (overrides > 0) {
                    pluralStringResource(R.plurals.rows_bar_mode_overrides, overrides, overrides)
                } else {
                    null
                }
                BarRowCard(
                    title = rowNames[row].orEmpty(),
                    status = listOfNotNull(modeNote, status).joinToString(" · ").ifEmpty { null },
                    row = row,
                    shown = settings.watch { barRowShown(row, it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.rows_fancy_title)) {
        item {
            ToggleSetting(
                R.string.rows_fancy_title,
                stringResource(R.string.rows_fancy_subtitle),
                settings.watch { fancyTextOn(it) },
                info = stringResource(R.string.rows_fancy_info),
            ) { on -> scope.launch { setFancyTextOn(repository, settings.value, on) } }
        }
    }
    // The recent words (#894): the switch the Suggestions screen also has,
    // and where they go. A row of their own is listed in the order above.
    val recentOn = settings.watch { it.suggestionStrip.recentWordBar }
    val recentOwnRow = settings.watch { it.rows.recentWordPlacement == RecentWordPlacement.OWN_ROW }
    SettingsGroup(stringResource(R.string.typing_recent_word_bar_title)) {
        item {
            ToggleSetting(
                R.string.typing_recent_word_bar_title,
                stringResource(R.string.typing_recent_word_bar_subtitle),
                settings.watch { it.suggestionStrip.recentWordBar },
                info = stringResource(R.string.typing_recent_word_bar_info),
                default = SettingsDefaults.suggestionStrip.recentWordBar,
            ) { scope.launch { repository.setRecentWordBar(it) } }
        }
        item(visible = recentOn, level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.rows_recent_words_placement_title,
                info = stringResource(R.string.rows_recent_words_placement_info),
                options = listOf(
                    RecentWordPlacement.OWN_ROW to stringResource(R.string.rows_recent_words_placement_row),
                    RecentWordPlacement.INLINE to stringResource(R.string.rows_recent_words_placement_inline),
                ),
                selected = settings.watch { it.rows.recentWordPlacement },
                default = SettingsDefaults.rows.recentWordPlacement,
            ) { scope.launch { repository.setRecentWordPlacement(it) } }
        }
        item(visible = recentOn && recentOwnRow, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.rows_recent_words_steady_title,
                stringResource(R.string.rows_recent_words_steady_subtitle),
                settings.watch { it.rows.recentWordSteady },
                info = stringResource(R.string.rows_recent_words_steady_info),
                default = SettingsDefaults.rows.recentWordSteady,
            ) { scope.launch { repository.setRecentWordSteady(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.rows_dictionary_bar_title)) {
        item {
            ToggleSetting(
                R.string.rows_dictionary_bar_title,
                stringResource(R.string.rows_dictionary_bar_subtitle),
                settings.watch { it.rows.dictionaryBarEnabled },
                info = stringResource(R.string.rows_dictionary_bar_info),
                default = SettingsDefaults.rows.dictionaryBarEnabled,
            ) { scope.launch { repository.setDictionaryBarEnabled(it) } }
        }
    }
    // The switch stays here, beside the other rows' switches; everything else
    // about the symbol row is a page of its own behind it (#136).
    SettingsGroup(stringResource(R.string.rows_symbol_row_title)) {
        item {
            ToggleNavRow(
                R.string.rows_symbol_row_title,
                stringResource(R.string.rows_symbol_row_subtitle),
                settings.watch { it.symbolRowEnabled },
                route = "rows/symbol",
                info = stringResource(R.string.rows_symbol_row_info),
                default = SettingsDefaults.symbolRowEnabled,
                onChange = { scope.launch { repository.setSymbolRowEnabled(it) } },
            ) { onNavigate("rows/symbol") }
        }
        item(visible = symbolRowOverridden) {
            ModeRowOverrides(BarRow.SYMBOL, settings, onNavigate)
        }
    }
}

/**
 * The keyboard modes that draw [row] their own way, under the row's global
 * setting: one chip per mode with the value it sets, each opening that mode's
 * editor. A global switch a mode quietly overrides reads as a switch that does
 * not work — the symbol row switched off still turns up in every email box,
 * because the Email mode turns it on — so the row says which modes, and the
 * chip is the way to change them. Draws nothing when no mode overrides it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeRowOverrides(row: BarRow, settings: LiveSettings, onNavigate: (String) -> Unit) {
    val modes = settings.watch { it.modesOverridingRow(row) }
    if (modes.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.rows_mode_overrides_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (mode in modes) {
                val value = stringResource(modeRowValueLabel(row, mode))
                AssistChip(
                    onClick = { onNavigate(modeEditRoute(mode.id)) },
                    label = { Text(stringResource(R.string.rows_mode_override_chip, mode.name, value)) },
                    leadingIcon = {
                        Icon(
                            ModeIcons.icon(mode.icon),
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

/** What [mode] sets [row] to, in the words the row's own setting uses. */
@StringRes
private fun modeRowValueLabel(row: BarRow, mode: KeyboardMode): Int = when (row) {
    BarRow.EMOJI -> when (mode.emojiBarMode) {
        EmojiBarMode.BUTTON -> R.string.langemoji_emoji_bar_button_label
        EmojiBarMode.ALWAYS -> R.string.langemoji_emoji_bar_always_label
        else -> CommonR.string.common_off
    }
    else -> if (mode.symbolRowEnabled == true) CommonR.string.common_on else CommonR.string.common_off
}

/**
 * The symbol row's own page (#136): its switch again, so the page can be used
 * without going back, then the row's shape and the sets it offers. Reached
 * from the switch row on Rows & bars, whose switch flies into this one.
 */
@Composable
internal fun SymbolRowSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val symbolRowOn = settings.watch { it.symbolRowEnabled }
    val stacked = settings.watch { it.rows.symbolRowLines > 1 }
    val customSets = settings.watch { it.customSymbolSets }
    val symbolRowOverridden = settings.watch { it.modesOverridingRow(BarRow.SYMBOL).isNotEmpty() }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.rows_symbol_row_title,
                stringResource(R.string.rows_symbol_row_subtitle),
                symbolRowOn,
                info = stringResource(R.string.rows_symbol_row_info),
                switchKey = landingKey("switch"),
                default = SettingsDefaults.symbolRowEnabled,
            ) { scope.launch { repository.setSymbolRowEnabled(it) } }
        }
        item(visible = symbolRowOverridden) {
            ModeRowOverrides(BarRow.SYMBOL, settings, onNavigate)
        }
        if (symbolRowOn) {
            item(level = SettingLevel.STANDARD) {
                val dpFormat = stringResource(R.string.typing_value_dp)
                SliderSetting(
                    R.string.rows_symbol_row_height_title,
                    subtitle = stringResource(R.string.rows_symbol_row_height_subtitle),
                    value = settings.watch { it.rows.symbolRowHeightDp }.toFloat(),
                    range = SymbolRowHeightRange.first.toFloat()..SymbolRowHeightRange.last.toFloat(),
                    display = { dpFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.rows_symbol_row_height_info),
                    default = SettingsDefaults.rows.symbolRowHeightDp.toFloat(),
                ) { scope.launch { repository.setSymbolRowHeightDp(it.roundToInt()) } }
            }
            item(level = SettingLevel.STANDARD) {
                StepperSetting(
                    R.string.rows_symbol_row_lines_title,
                    subtitle = stringResource(R.string.rows_symbol_row_lines_subtitle),
                    value = settings.watch { it.rows.symbolRowLines },
                    range = SymbolRowLinesSteps,
                    display = { it.toString() },
                    info = stringResource(R.string.rows_symbol_row_lines_info),
                    default = SettingsDefaults.rows.symbolRowLines,
                ) { scope.launch { repository.setSymbolRowLines(it) } }
            }
            // Only a stack has a way to scroll; one line scrolls the one way.
            item(visible = stacked, level = SettingLevel.ADVANCED) {
                ChoiceSetting(
                    R.string.rows_symbol_row_scroll_title,
                    subtitle = stringResource(R.string.rows_symbol_row_scroll_subtitle),
                    options = listOf(
                        SymbolRowScroll.TOGETHER to
                            stringResource(R.string.rows_symbol_row_scroll_together_label),
                        SymbolRowScroll.SEPARATE to
                            stringResource(R.string.rows_symbol_row_scroll_separate_label),
                    ),
                    selected = settings.watch { it.rows.symbolRowScroll },
                    default = SettingsDefaults.rows.symbolRowScroll,
                    detail = { scroll ->
                        ChoiceDetail(
                            stringResource(
                                if (scroll == SymbolRowScroll.SEPARATE) {
                                    R.string.rows_symbol_row_scroll_separate_desc
                                } else {
                                    R.string.rows_symbol_row_scroll_together_desc
                                },
                            ),
                        )
                    },
                ) { scope.launch { repository.setSymbolRowScroll(it) } }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.rows_symbol_sets_title),
        info = stringResource(R.string.rows_symbol_sets_caption),
    ) {
        val allSets = resolveSymbolSets(customSets)
        for (set in allSets) {
            item(key = set.id) {
                val enabled = settings.watch { set.id in it.symbolRowSetIds }
                val edited = settings.watch { s -> s.customSymbolSets.any { it.id == set.id } }
                val builtIn = BuiltInSymbolSets.byId(set.id) != null
                // A shipped set the user has not renamed draws its translated
                // name; anything the user named draws that name as typed.
                val shippedNameRes = BuiltInSymbolSets.nameRes(set)
                val setName = if (shippedNameRes != null) {
                    stringResource(shippedNameRes)
                } else {
                    set.name
                }
                WmRow(
                    title = setName,
                    supporting = {
                        Text(
                            set.chars.take(8).joinToString(" ") + if (set.chars.size > 8) " …" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leading = {
                        Checkbox(
                            checked = enabled,
                            onCheckedChange = { on ->
                                val setIds = settings.value.symbolRowSetIds
                                val next = if (on) {
                                    setIds + set.id
                                } else {
                                    setIds - set.id
                                }
                                // At least one set stays enabled — an empty row
                                // would have nothing to show.
                                if (next.isNotEmpty()) {
                                    scope.launch { repository.setSymbolRowSetIds(next) }
                                }
                            },
                        )
                    },
                    // Delete for a set of your own, Reset for a shipped one you
                    // changed: the editor's button, without opening the editor.
                    swipeActions = buildList {
                        add(
                            SwipeAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit)) {
                                onNavigate(symbolSetEditRoute(set.id))
                            },
                        )
                        if (edited) {
                            val remove = { scope.launch { repository.deleteSymbolSet(set.id) }; Unit }
                            add(
                                if (builtIn) {
                                    SwipeAction(
                                        Icons.Outlined.Refresh,
                                        stringResource(R.string.rows_symbol_set_reset_action),
                                        onClick = remove,
                                    )
                                } else {
                                    deleteSwipeAction(stringResource(R.string.rows_symbol_set_delete_action), remove)
                                },
                            )
                        }
                    },
                    // Every set is editable now, built-ins included: editing
                    // one stores an override under the same id, so modes that
                    // reference it keep working and "Reset" brings it back.
                    trailing = {
                        IconButton(onClick = { onNavigate(symbolSetEditRoute(set.id)) }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(
                                    if (builtIn && !edited) {
                                        R.string.rows_symbol_set_edit_builtin_desc
                                    } else {
                                        R.string.rows_symbol_set_edit_desc
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
    RegisterAddFab(stringResource(R.string.rows_symbol_set_new_title)) {
        onNavigate(symbolSetEditRoute("custom_${System.currentTimeMillis()}"))
    }
}
/**
 * Create or edit one symbol set, built-ins included. Editing a built-in
 * saves an override stored under the same id, so anything referencing that
 * id (a mode's pinned sets, the row's active set) keeps pointing at it and
 * "Reset" simply drops the override to bring the shipped set back.
 */
@Composable
internal fun SymbolSetEditor(
    repository: SettingsRepository,
    settings: LiveSettings,
    setId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val override = settings.watch { s -> s.customSymbolSets.firstOrNull { it.id == setId } }
    val builtIn = BuiltInSymbolSets.byId(setId)
    val existing = override ?: builtIn
    var name by remember(setId) { mutableStateOf(existing?.name.orEmpty()) }
    var charsText by remember(setId) { mutableStateOf(existing?.chars?.joinToString(" ").orEmpty()) }
    // The popups as typed, one text per entry, keyed by the entry so an edit
    // to the list above that moves an entry keeps its popup with it. Only
    // what is still an entry at save time is kept (issue #83).
    val popupTexts = remember(setId) {
        mutableStateMapOf<String, String>().apply {
            existing?.popups?.forEach { (entry, alternates) -> put(entry, alternates.joinToString(" ")) }
        }
    }
    var popupEntry by remember(setId) { mutableStateOf<String?>(null) }
    if (builtIn != null) {
        // The stored English name is what a shipped set is keyed on, so only
        // the drawn name is resolved here. Nothing writes it back.
        val shippedNameRes = BuiltInSymbolSets.nameRes(builtIn)
        val shippedName = if (shippedNameRes != null) {
            stringResource(shippedNameRes)
        } else {
            builtIn.name
        }
        StateBanner(stringResource(R.string.rows_symbol_set_builtin_caption, shippedName))
    }
    val defaultSetName = stringResource(R.string.rows_symbol_set_default_name)
    SettingsGroup {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.rows_symbol_set_name_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            OutlinedTextField(
                value = charsText,
                onValueChange = { charsText = it },
                label = { Text(stringResource(R.string.rows_symbol_set_chars_label)) },
                supportingText = {
                    Text(stringResource(R.string.rows_symbol_set_chars_hint))
                },
                minLines = 3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    SymbolPopupsEditor(
        entries = splitSymbolEntries(charsText).distinct(),
        popupTexts = popupTexts,
        selected = popupEntry,
        onSelect = { popupEntry = it },
    )
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        // Only an existing stored set can be removed — and for a built-in
        // that removal is a reset, not a delete.
        if (override != null) {
            TextButton(onClick = {
                scope.launch {
                    repository.deleteSymbolSet(setId)
                }
                onDone()
            }) {
                Icon(
                    if (builtIn != null) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    stringResource(
                        if (builtIn != null) {
                            R.string.rows_symbol_set_reset_action
                        } else {
                            R.string.rows_symbol_set_delete_action
                        },
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            enabled = charsText.isNotBlank(),
            onClick = {
                val chars = splitSymbolEntries(charsText)
                val popups = sanitizeSymbolPopups(
                    chars,
                    popupTexts.mapValues { (_, text) -> splitSymbolEntries(text) },
                )
                scope.launch {
                    repository.upsertSymbolSet(
                        SymbolSet(
                            setId,
                            name.trim().ifEmpty { builtIn?.name ?: defaultSetName },
                            chars,
                            popups,
                        ),
                    )
                    // A new set should show up in the row right away.
                    val setIds = settings.value.symbolRowSetIds
                    if (setId !in setIds) {
                        repository.setSymbolRowSetIds(setIds + setId)
                    }
                }
                onDone()
            },
        ) { Text(stringResource(CommonR.string.common_save)) }
    }
}
/** The entries in a symbol set text box: whitespace-separated, blanks dropped. */
private fun splitSymbolEntries(text: String): List<String> =
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * The press-and-hold popups of the set being edited (issue #83): one chip per
 * entry of the characters box, and a text box for the selected chip's popup.
 * The chips follow the box above live, so a new entry can be given a popup
 * before the set is saved, and an entry removed above takes its chip away.
 * The text it had stays in [popupTexts] until the save drops it, so a typo
 * fixed above brings the popup back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolPopupsEditor(
    entries: List<String>,
    popupTexts: MutableMap<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    // A chip only selects an entry that still exists.
    val current = selected?.takeIf { it in entries }
    SettingsGroup(
        stringResource(R.string.rows_symbol_set_popups_title),
        info = stringResource(R.string.rows_symbol_set_popups_caption),
    ) {
        item {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.rows_symbol_set_popups_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (entry in entries) {
                        val count = splitSymbolEntries(popupTexts[entry].orEmpty())
                            .filter { it != entry }.distinct().size
                        FilterChip(
                            selected = entry == current,
                            onClick = { onSelect(if (entry == current) null else entry) },
                            label = {
                                Text(
                                    if (count > 0) "${symbolChipLabel(entry)}  $count" else symbolChipLabel(entry),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
        if (current != null) {
            item {
                OutlinedTextField(
                    value = popupTexts[current].orEmpty(),
                    onValueChange = { popupTexts[current] = it },
                    label = {
                        Text(stringResource(R.string.rows_symbol_set_popup_label, symbolChipLabel(current)))
                    },
                    supportingText = { Text(stringResource(R.string.rows_symbol_set_popup_hint)) },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * One symbol set's editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`symbol_set_edit/{setId}`) is the same string for every
 * set and would hang one key on all of them.
 */
internal fun symbolSetEditRoute(setId: String): String = "symbol_set_edit/$setId"

/**
 * One mode's editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`mode_edit/{modeId}`) is the same string for every
 * mode and would hang one key on all of them.
 */
internal fun modeEditRoute(modeId: String): String = "mode_edit/$modeId"

/**
 * The name the mode editor's heading wears: the mode as the user named it.
 *
 * Read by the nav graph, which owns the heading. Null for a mode that is not
 * there yet — the Add button navigates to an id nothing has saved — and the
 * frame falls back to the generic title.
 */
internal fun modeEditTitle(settings: KeyboardSettings, modeId: String): String? =
    settings.keyboardModes.find { it.id == modeId }?.name?.takeIf { it.isNotBlank() }

/**
 * The mode's glyph at heading size, or null for a mode that is not saved yet.
 * The list row draws the same one, so the two ends of the flight match.
 */
@Composable
internal fun modeEditIcon(settings: LiveSettings, modeId: String): (@Composable () -> Unit)? {
    val mode = settings.watch { s -> s.keyboardModes.find { it.id == modeId } } ?: return null
    return { Icon(ModeIcons.icon(mode.icon), contentDescription = null) }
}

// ---- keyboard modes ----

/**
 * One-line recap of a mode's bindings for the list screen.
 *
 * The parts are joined, so nothing here may be re-cased afterwards: the first
 * letter of a translated word is not ours to change. The lower-case field
 * names are their own resources for the same reason.
 */
/** What the time condition starts on, a night: 22:00 to 07:00. */
private const val MODE_NIGHT_FROM = 22 * 60
private const val MODE_NIGHT_TO = 7 * 60

private fun appGroupLabel(group: AppGroup): Int = when (group) {
    AppGroup.BROWSER -> R.string.modes_group_browser
    AppGroup.TERMINAL -> R.string.modes_group_terminal
    AppGroup.MESSENGER -> R.string.modes_group_messenger
    AppGroup.MAIL -> R.string.modes_group_mail
}

@Composable
private fun modeBindingsSummary(mode: KeyboardMode): String {
    val resources = LocalContext.current.resources
    val parts = mutableListOf<String>()
    if (mode.apps.isNotEmpty()) {
        parts += resources.getQuantityString(
            R.plurals.rows_mode_bindings_apps, mode.apps.size, mode.apps.size,
        )
    }
    if (mode.fieldKinds.isNotEmpty()) {
        parts += resources.getString(
            R.string.rows_mode_bindings_fields,
            mode.fieldKinds.joinToString(", ") {
                resources.getString(modeFieldLowercaseLabel(it))
            },
        )
    }
    if (mode.hints.isNotEmpty()) {
        parts += resources.getQuantityString(
            R.plurals.rows_mode_bindings_hints, mode.hints.size, mode.hints.size,
        )
    }
    if (mode.appGroups.isNotEmpty()) {
        parts += mode.appGroups.joinToString(", ") { resources.getString(appGroupLabel(it)) }
    }
    if (mode.hasDeviceCondition) parts += resources.getString(R.string.rows_mode_bindings_device)
    // " + " rather than " · ": with several set, all have to match.
    return if (parts.isEmpty()) {
        resources.getString(R.string.rows_mode_bindings_manual)
    } else {
        resources.getString(R.string.rows_mode_bindings_auto, parts.joinToString(" + "))
    }
}
@Composable
private fun modeFieldLabel(field: ModeField): String = stringResource(
    when (field) {
        ModeField.PASSWORD -> R.string.rows_mode_field_password_label
        ModeField.EMAIL -> R.string.rows_mode_field_email_label
        ModeField.URL -> R.string.rows_mode_field_url_label
        ModeField.NUMBER -> R.string.rows_mode_field_number_label
        ModeField.PHONE -> R.string.rows_mode_field_phone_label
        ModeField.TEXT -> R.string.rows_mode_field_text_label
        ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_label
        ModeField.MULTILINE -> R.string.rows_mode_field_multiline_label
        ModeField.SINGLE_LINE -> R.string.rows_mode_field_single_line_label
        ModeField.NO_SUGGESTIONS -> R.string.rows_mode_field_no_suggestions_label
        ModeField.SEND_KEY -> R.string.rows_mode_field_send_key_label
        ModeField.SEARCH_KEY -> R.string.rows_mode_field_search_key_label
        ModeField.GO_KEY -> R.string.rows_mode_field_go_key_label
        ModeField.DONE_KEY -> R.string.rows_mode_field_done_key_label
    },
)
/** The same names, written the way they read inside a sentence. */
@StringRes
private fun modeFieldLowercaseLabel(field: ModeField): Int = when (field) {
    ModeField.PASSWORD -> R.string.rows_mode_field_password_lowercase_label
    ModeField.EMAIL -> R.string.rows_mode_field_email_lowercase_label
    ModeField.URL -> R.string.rows_mode_field_url_lowercase_label
    ModeField.NUMBER -> R.string.rows_mode_field_number_lowercase_label
    ModeField.PHONE -> R.string.rows_mode_field_phone_lowercase_label
    ModeField.TEXT -> R.string.rows_mode_field_text_lowercase_label
    ModeField.NOTIFICATION_REPLY -> R.string.rows_mode_field_notification_reply_lowercase_label
    ModeField.MULTILINE -> R.string.rows_mode_field_multiline_lowercase_label
    ModeField.SINGLE_LINE -> R.string.rows_mode_field_single_line_lowercase_label
    ModeField.NO_SUGGESTIONS -> R.string.rows_mode_field_no_suggestions_lowercase_label
    ModeField.SEND_KEY -> R.string.rows_mode_field_send_key_lowercase_label
    ModeField.SEARCH_KEY -> R.string.rows_mode_field_search_key_lowercase_label
    ModeField.GO_KEY -> R.string.rows_mode_field_go_key_lowercase_label
    ModeField.DONE_KEY -> R.string.rows_mode_field_done_key_lowercase_label
}
/** Row height inside [ReorderableColumn] — fixed, so drags map to index shifts. */
private val ReorderRowHeight = 52.dp

/** How far a row settles into its slot after a swap or a drop — a short spring. */
private val ReorderSettleSpring = spring<Float>(stiffness = Spring.StiffnessMediumLow)

/**
 * A list the user drags into order, in place. Rows carry a handle on the
 * right; dragging one past half the next row's height moves it a slot, and
 * every row it passes settles into the slot it gave up. The caller hears the
 * whole new order once, through [onReorder], when the row is dropped.
 *
 * The order on screen is this column's own copy for the length of a drag,
 * moved synchronously under the finger. Routing every swap through the caller
 * was the jitter: the store echoed the new list a frame or more later, and
 * until it did the slot the dragged row had moved into still held its
 * neighbour — which drew translated under the finger for that frame, then
 * snapped back. A fast drag computed its next swap from a list the store had
 * not caught up with either, so swaps were dropped or applied twice. Outside a
 * drag the copy follows [items].
 *
 * Deliberately not a LazyColumn: every row has to stay composed for a drag
 * to pass it, and these lists are short enough that laying them all out is
 * free. Rows are keyed by item, so a row keeps its node — and the gesture
 * that is driving it — as it moves.
 *
 * [onDelete] adds a bin to each row's left, for the lists where the same
 * pencil that opens the reorder is also the way out of the list — removing a
 * language, say. Null (every other caller) draws no bin, and the last item is
 * never removable unless [keepLast] says otherwise: a bar's rows have to keep
 * one, while a list of snippet folders is perfectly good empty.
 *
 * [content] replaces the one-line label with the caller's own row body (the
 * bar order draws a preview of each row); [label] still names the row for
 * the handle's description. A taller body passes its [rowHeight].
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    label: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: ((T) -> Unit)? = null,
    keepLast: Boolean = true,
    rowHeight: Dp = ReorderRowHeight,
    content: (@Composable RowScope.(T) -> Unit)? = null,
) {
    var working by remember { mutableStateOf(items) }
    // -1 = nothing being dragged. The index is in [working].
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Per row, how far it still has to travel to the slot it is drawn in: set
    // in the same snapshot as the move, so the frame that shows the new order
    // also shows every displaced row exactly where it was, and played out to
    // zero by the row's own [ReorderRow] spring.
    val settling = remember { mutableStateMapOf<Any, Float>() }
    // Adopt the caller's list whenever it changes while nothing is in flight;
    // mid-drag the finger's order wins and reaches the caller on the drop.
    LaunchedEffect(items) {
        if (dragIndex < 0) working = items
    }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    // The drag gesture is keyed once per row and outlives every recomposition,
    // so it reads the caller's lambda through the latest snapshot rather than
    // closing over the one it was composed with.
    val currentItems by rememberUpdatedState(items)
    val currentOnReorder by rememberUpdatedState(onReorder)
    val finishDrag = {
        val from = dragIndex
        if (from in working.indices) {
            // At most half a slot from home: let the row settle rather than snap.
            if (dragOffset != 0f) {
                val key = reorderKey(working, from)
                settling[key] = (settling[key] ?: 0f) + dragOffset
            }
            if (working != currentItems) currentOnReorder(working)
        }
        dragIndex = -1
        dragOffset = 0f
    }
    Column(modifier = modifier) {
        working.forEachIndexed { index, item ->
            val rowKey = reorderKey(working, index)
            key(rowKey) {
                val liveIndex by rememberUpdatedState(index)
                ReorderRow(
                    dragging = index == dragIndex,
                    dragOffset = dragOffset,
                    settleTo = settling[rowKey] ?: 0f,
                    onSettled = { settling.remove(rowKey) },
                    rowHeight = rowHeight,
                    position = index + 1,
                    label = label(item),
                    onDelete = onDelete?.let { delete -> { delete(item) } },
                    deletable = !keepLast || working.size > 1,
                    handle = Modifier.pointerInput(rowPx) {
                        detectDragGestures(
                            onDragStart = {
                                dragIndex = liveIndex
                                dragOffset = 0f
                            },
                            onDragEnd = finishDrag,
                            onDragCancel = finishDrag,
                        ) { change, drag ->
                            change.consume()
                            val list = working
                            val from = dragIndex
                            if (from !in list.indices) return@detectDragGestures
                            // Clamped to the list: past either end the row
                            // stops at the edge instead of leaving the column.
                            dragOffset = (dragOffset + drag.y)
                                .coerceIn(-from * rowPx, (list.lastIndex - from) * rowPx)
                            val to = (from + (dragOffset / rowPx).roundToInt()).coerceIn(list.indices)
                            if (to == from) return@detectDragGestures
                            // A fast drag can cross several rows in one event:
                            // each of them shifts one slot the other way and
                            // is told how far it has to settle.
                            val passed = if (to > from) (from + 1)..to else to until from
                            val back = if (to > from) rowPx else -rowPx
                            for (i in passed) {
                                val key = reorderKey(list, i)
                                settling[key] = (settling[key] ?: 0f) + back
                            }
                            working = list.toMutableList().apply { add(to, removeAt(from)) }
                            dragIndex = to
                            // Keep the offset relative to the row's new home,
                            // or the row would jump a full slot.
                            dragOffset -= (to - from) * rowPx
                        }
                    },
                ) {
                    if (content != null) {
                        content(item)
                    } else {
                        Text(
                            label(item),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The key a row keeps as it moves: the item, plus which occurrence of it
 * this is, so a list with two equal entries still keys every row uniquely.
 */
private fun <T> reorderKey(list: List<T>, index: Int): Any {
    val item = list[index]
    var nth = 0
    for (i in 0 until index) if (list[i] == item) nth++
    return item to nth
}

/**
 * One row of a [ReorderableColumn]: position, the caller's body, the
 * optional bin and the handle. While dragged it rides above its neighbours
 * on a raised card at [dragOffset]; otherwise it draws at [settleTo] and
 * springs to zero, so a row that just changed slots slides into it rather
 * than appearing there.
 */
@Composable
private fun ReorderRow(
    dragging: Boolean,
    dragOffset: Float,
    settleTo: Float,
    onSettled: () -> Unit,
    rowHeight: Dp,
    position: Int,
    label: String,
    onDelete: (() -> Unit)?,
    deletable: Boolean,
    handle: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val settle = remember { Animatable(0f) }
    // The part of [settleTo] already folded into the spring. Until the spring
    // has started, the row draws the raw offset, so the frame that shows the
    // new order never shows a jump; once it runs, the spring's value is the
    // whole story, and a further shift mid-spring adds on from wherever it is.
    var applied by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(settleTo) {
        if (settleTo == 0f) {
            applied = 0f
            return@LaunchedEffect
        }
        settle.snapTo(settle.value + (settleTo - applied))
        applied = settleTo
        settle.animateTo(0f, ReorderSettleSpring)
        onSettled()
        applied = 0f
    }
    val rowShape = MaterialTheme.shapes.medium
    val lift = MaterialTheme.colorScheme.surfaceContainerHigh
    val removeDesc = stringResource(R.string.rows_reorder_remove_desc, label)
    // The bin, a swipe away as well as at the row's end. Enabled on what the
    // row is rather than on whether it is being dragged: flipping it mid-drag
    // would rebuild the row and drop the grip's gesture under the finger.
    SwipeActionsRow(
        actions = if (onDelete != null) listOf(deleteSwipeAction(removeDesc, onDelete)) else emptyList(),
        enabled = deletable,
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            // The dragged row rides above its neighbours.
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = if (dragging) dragOffset else settle.value + (settleTo - applied)
                shadowElevation = if (dragging) 6.dp.toPx() else 0f
                shape = rowShape
            }
            .background(if (dragging) lift else Color.Transparent, rowShape),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
        ) {
            Text(
                stringResource(R.string.rows_reorder_position_label, position),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            content()
            if (onDelete != null) {
                IconButton(onClick = onDelete, enabled = deletable) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.rows_reorder_remove_desc, label),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Icon(
                Icons.Outlined.DragHandle,
                contentDescription = stringResource(R.string.rows_reorder_handle_desc, label),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(28.dp)
                    .then(handle),
            )
        }
    }
}

/**
 * [ReorderableColumn] in a dialog, for the callers that still open one. The
 * working copy only reaches the caller through [onConfirm] — backing out
 * leaves the stored order alone.
 */
@Composable
internal fun <T> ReorderDialog(
    title: String,
    items: List<T>,
    label: (T) -> String,
    onConfirm: (List<T>) -> Unit,
    onDismiss: () -> Unit,
) {
    var working by remember { mutableStateOf(items) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CaptionText(stringResource(R.string.rows_reorder_caption))
                ReorderableColumn(working, label, onReorder = { working = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(working) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * A "Reorder…" row that opens a [ReorderDialog]. Disabled with a nudge when
 * there is nothing to reorder yet.
 */
@Composable
internal fun <T> ReorderSetting(
    title: String,
    dialogTitle: String,
    items: List<T>,
    label: (T) -> String,
    icon: ImageVector? = null,
    onReordered: (List<T>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val enabled = items.size > 1
    WmRow(
        title = title,
        icon = icon,
        subtitle = if (enabled) {
                items.joinToString(" · ", limit = 4) { label(it) }
            } else {
                stringResource(R.string.rows_reorder_empty_subtitle)
            },
        trailing = { Icon(Icons.Outlined.DragHandle, contentDescription = null) },
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        ReorderDialog(
            title = dialogTitle,
            items = items,
            label = label,
            onConfirm = {
                open = false
                onReordered(it)
            },
            onDismiss = { open = false },
        )
    }
}
/** A wrapping row of tool chips, used for a mode's pins and toolbox order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolChips(
    tools: List<ToolbarTool>,
    selected: List<ToolbarTool>,
    onToggle: (ToolbarTool) -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tool in tools) {
            FilterChip(
                selected = tool in selected,
                onClick = { onToggle(tool) },
                label = { Text(stringResource(toolTitle(tool)), maxLines = 1) },
            )
        }
    }
}
/** The modes list: tap to edit, plus creating a new mode. */
@Composable
internal fun ModesSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // What decides which rows the groups hold; each row reads its own value.
    val modesOn = settings.watch { it.modesEnabled }
    val modeIds = settings.watch { s -> s.keyboardModes.map { it.id } }
    // Deleted built-ins had no way back short of clearing the app's data,
    // and a list that empties itself (an unreadable store) left the screen
    // with nothing to tap at all.
    val missing = settings.watch { s ->
        KeyboardModeCodec.missingDefaults(s.keyboardModes).map { it.name }
    }
    val deleteModeDesc = stringResource(R.string.modes_delete_action)
    // A mode is a screenful of bindings and overrides that took real effort to
    // set up, and the delete button sits on the row you tap to open it. Both
    // delete paths ask first; the editor's own button does the same below.
    var confirmDelete by remember { mutableStateOf<KeyboardMode?>(null) }
    SettingsGroup {
        item {
            ToggleSetting(
                R.string.modes_enabled_title,
                stringResource(R.string.modes_enabled_subtitle),
                modesOn,
                info = stringResource(R.string.modes_intro_body) + "\n\n" +
                    stringResource(R.string.modes_enabled_info),
                default = SettingsDefaults.modesEnabled,
            ) { scope.launch { repository.setModesEnabled(it) } }
        }
    }
    // The rest of the screen is what modes do, so it is only worth drawing
    // while they are on. The list itself stays: switching the feature off
    // keeps every mode, and hiding them would read as having deleted them.
    if (!modesOn) {
        StateBanner(
            stringResource(R.string.modes_disabled_body),
            action = stringResource(CommonR.string.common_enable),
        ) { scope.launch { repository.setModesEnabled(true) } }
    }
    SettingsGroup {
        if (!modesOn) return@SettingsGroup
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.modes_manual_duration_title,
                subtitle = stringResource(R.string.modes_manual_duration_subtitle),
                options = listOf(
                    ManualModeDuration.UNTIL_APP_CHANGES to
                        stringResource(R.string.modes_manual_duration_app_label),
                    ManualModeDuration.UNTIL_CHANGED to
                        stringResource(R.string.modes_manual_duration_changed_label),
                    ManualModeDuration.FOR_EACH_APP to
                        stringResource(R.string.modes_manual_duration_each_app_label),
                ),
                selected = settings.watch { it.rows.manualModeDuration },
                info = stringResource(R.string.modes_manual_duration_info),
                default = SettingsDefaults.rows.manualModeDuration,
                detail = { duration ->
                    ChoiceDetail(
                        stringResource(
                            when (duration) {
                                ManualModeDuration.UNTIL_CHANGED -> R.string.modes_manual_duration_changed_desc
                                ManualModeDuration.FOR_EACH_APP -> R.string.modes_manual_duration_each_app_desc
                                ManualModeDuration.UNTIL_APP_CHANGES -> R.string.modes_manual_duration_app_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setManualModeDuration(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.modes_group_title)) {
        for (modeId in modeIds) {
            item(key = modeId) {
                val mode = settings.watch { s -> s.keyboardModes.firstOrNull { it.id == modeId } }
                    ?: return@item
                // The switch is the mode's own on/off (#152): off, it stays in
                // the list with everything it holds, and the keyboard simply
                // never sees it. Deleting used to be the only way to silence
                // one, and a mode is too much setup to throw away for a week
                // without it.
                val useModeDesc = stringResource(R.string.modes_use_action, mode.name)
                WmRow(
                    title = mode.name,
                    subtitle = if (mode.enabled) {
                        modeBindingsSummary(mode)
                    } else {
                        stringResource(R.string.modes_row_off_subtitle)
                    },
                    leading = {
                        // The mode's glyph, which is also the glyph its editor
                        // wears in the heading — so it flies rather than being
                        // drawn twice.
                        Icon(
                            ModeIcons.icon(mode.icon),
                            contentDescription = null,
                            modifier = Modifier.wmSharedElement(
                                takeOffKey("icon", modeEditRoute(mode.id)),
                            ),
                        )
                    },
                    swipeActions = listOf(
                        SwipeAction(Icons.Outlined.Edit, stringResource(CommonR.string.common_edit)) {
                            onNavigate(modeEditRoute(mode.id))
                        },
                        deleteSwipeAction(deleteModeDesc) { confirmDelete = mode },
                    ),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = mode.enabled,
                                onCheckedChange = { on ->
                                    scope.launch {
                                        repository.upsertKeyboardMode(mode.copy(enabled = on))
                                    }
                                },
                                modifier = Modifier.semantics { contentDescription = useModeDesc },
                            )
                            IconButton(onClick = { confirmDelete = mode }) {
                                Icon(Icons.Outlined.Delete, contentDescription = deleteModeDesc)
                            }
                        }
                    },
                    flightTo = modeEditRoute(mode.id),
                    onClick = { onNavigate(modeEditRoute(mode.id)) },
                )
            }
        }
        if (missing.isNotEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.modes_restore_defaults_title),
                    subtitle = pluralStringResource(
                        R.plurals.modes_restore_defaults_subtitle,
                        missing.size,
                        missing.joinToString(", "),
                    ),
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = { scope.launch { repository.restoreMissingDefaultModes() } },
                )
            }
        }
    }
    RegisterAddFab(stringResource(R.string.modes_new_title)) {
        onNavigate(modeEditRoute("mode_custom_${System.currentTimeMillis()}"))
    }
    SettingsGroup(stringResource(R.string.modes_rearrange_group_title), level = SettingLevel.ADVANCED) {
        if (!modesOn) return@SettingsGroup
        item {
            ToggleSetting(
                R.string.modes_drag_edits_title,
                stringResource(R.string.modes_drag_edits_subtitle),
                settings.watch { it.modeToolOrderEdits },
                info = stringResource(R.string.modes_drag_edits_info) + "\n\n" +
                    stringResource(R.string.modes_tool_order_body),
                default = SettingsDefaults.modeToolOrderEdits,
            ) { scope.launch { repository.setModeToolOrderEdits(it) } }
        }
    }
    confirmDelete?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, mode.name)) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { repository.deleteKeyboardMode(mode.id) }
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/** Everything one mode overrides, and when it activates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModeEditor(
    repository: SettingsRepository,
    settings: LiveSettings,
    modeId: String,
    onDeleted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    // Resolved up here: the name lands in stored settings from a plain lambda,
    // which is no place for stringResource().
    val newModeName = stringResource(R.string.modes_new_default_name)
    val unnamedModeName = stringResource(R.string.modes_unnamed_name)
    // The mode as stored, or a fresh one for an id nothing has saved yet. Rows
    // read their own field of it with `settings.watch { modeIn(it).x }`, and a
    // write starts from [current], the mode as it is at the moment of the write.
    fun modeIn(s: KeyboardSettings): KeyboardMode =
        s.keyboardModes.firstOrNull { it.id == modeId } ?: KeyboardMode(modeId, newModeName)
    fun current(): KeyboardMode = modeIn(settings.value)
    // A brand-new mode is only persisted on its first edit — backing out of
    // an untouched editor leaves nothing behind.
    val save: (KeyboardMode) -> Unit = { scope.launch { repository.upsertKeyboardMode(it) } }
    // Only the shipped modes can be reset — a user-made mode has no default to
    // fall back to. Matched by id so an edited built-in still offers it.
    val builtInDefault = DefaultKeyboardModes.firstOrNull { it.id == modeId }
    var confirmReset by remember { mutableStateOf(false) }
    // Deleting a mode throws away a screenful of bindings and overrides, and
    // the button sits beside the reset one, which does not. Ask first.
    var confirmDelete by remember { mutableStateOf(false) }

    SettingsGroup {
        item {
            ToggleSetting(
                R.string.modes_use_title,
                stringResource(R.string.modes_use_subtitle),
                settings.watch { modeIn(it).enabled },
                info = stringResource(R.string.modes_use_info),
            ) { save(current().copy(enabled = it)) }
        }
        item {
            TextFieldSetting(
                label = stringResource(R.string.modes_name_label),
                value = settings.watch { modeIn(it).name },
                hint = stringResource(R.string.modes_name_hint),
            ) {
                repository.upsertKeyboardMode(
                    current().copy(name = it.trim().ifEmpty { unnamedModeName }),
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            val icon = settings.watch { modeIn(it).icon }
            WmRow(
                title = stringResource(R.string.modes_icon_title),
                subtitle = stringResource(R.string.modes_icon_subtitle),
                leading = {
                    Icon(ModeIcons.icon(icon), contentDescription = null)
                },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                ModeIconPickerDialog(
                    selected = icon,
                    onPick = { id ->
                        pickerOpen = false
                        save(current().copy(icon = id))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
    }
    // What decides which rows the groups hold and what their "?" says; each
    // row reads its own field of the mode.
    val hasThemeOverride = settings.watch { modeIn(it).themeId != null }
    val pinned = settings.watch { modeIn(it).toolbarTools }
    val order = settings.watch { modeIn(it).toolboxOrder }
    val symbolRowPossible = settings.watch { modeIn(it).symbolRowEnabled != false }
    val modeSets = settings.watch { s -> modeIn(s).let { m -> m.symbolSetIds.takeIf { m.symbolRowEnabled != false } } }
    val hints = settings.watch { modeIn(it).hints }
    val apps = settings.watch { modeIn(it).apps }
    val hasFieldKinds = settings.watch { modeIn(it).fieldKinds.isNotEmpty() }
    val themeOverrideNote = stringResource(R.string.modes_theme_override_body).takeIf { hasThemeOverride }
    SettingsGroup(
        stringResource(R.string.modes_changes_group_title),
        info = listOfNotNull(
            themeOverrideNote,
            stringResource(R.string.modes_toolbox_order_body),
            stringResource(R.string.modes_symbol_set_order_body),
        ).joinToString("\n\n"),
    ) {
        item {
            ChoiceSetting(
                title = R.string.modes_emoji_row_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    EmojiBarMode.OFF to stringResource(CommonR.string.common_off),
                    EmojiBarMode.BUTTON to stringResource(R.string.modes_emoji_row_button_label),
                    EmojiBarMode.ALWAYS to stringResource(R.string.modes_emoji_row_row_label),
                ),
                selected = settings.watch { modeIn(it).emojiBarMode },
                detail = { barMode -> ChoiceDetail(stringResource(modeEmojiRowDescRes(barMode))) },
            ) { save(current().copy(emojiBarMode = it)) }
        }
        item {
            ChoiceSetting(
                title = R.string.modes_symbol_row_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).symbolRowEnabled },
                detail = inheritDetail(),
            ) { save(current().copy(symbolRowEnabled = it)) }
        }
        // Typing behaviour. A mode dressed the keyboard but never changed what
        // it did to the text, so Coding mode still corrected identifiers into
        // English words — the one thing a mode for a code editor is for.
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocorrect_title,
                subtitle = stringResource(R.string.modes_active_subtitle),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).autocorrect },
                detail = inheritDetail(),
            ) { save(current().copy(autocorrect = it)) }
        }
        item(level = SettingLevel.STANDARD) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autocapitalize_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).autoCapitalize },
                detail = inheritDetail(),
            ) { save(current().copy(autoCapitalize = it)) }
        }
        item {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_suggestions_title,
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).suggestions },
                detail = inheritDetail(),
            ) { save(current().copy(suggestions = it)) }
        }
        // The spaces nobody pressed for, as one question: a field either wants
        // them or it does not, and which of the three put the space there is
        // not something the person renaming a file extension is thinking about.
        item(level = SettingLevel.STANDARD) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_autospace_title,
                subtitle = stringResource(R.string.modes_autospace_subtitle),
                info = stringResource(R.string.modes_autospace_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).autoSpace },
                detail = inheritDetail(),
            ) { save(current().copy(autoSpace = it)) }
        }
        // The swipes, which every switch above left alone: with them on, a
        // drifting tap could still start one, so no mode typed exactly the
        // keys pecked (#753).
        item(level = SettingLevel.STANDARD) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_glide_title,
                info = stringResource(R.string.modes_glide_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).glide },
                detail = inheritDetail(),
            ) { save(current().copy(glide = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_two_thumb_title,
                info = stringResource(R.string.modes_two_thumb_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).twoThumb },
                detail = inheritDetail(),
            ) { save(current().copy(twoThumb = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_commit_as_typed_title,
                info = stringResource(R.string.modes_commit_as_typed_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).commitAsTyped },
                detail = inheritDetail(),
            ) { save(current().copy(commitAsTyped = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_incognito_title,
                info = stringResource(R.string.modes_incognito_info),
                options = listOf(
                    false to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                ),
                selected = settings.watch { modeIn(it).incognito },
                default = false,
            ) { save(current().copy(incognito = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_key_sound_title,
                info = stringResource(R.string.modes_key_sound_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).keySound },
                detail = inheritDetail(),
            ) { save(current().copy(keySound = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            val inherit = stringResource(R.string.modes_inherit_label)
            val on = stringResource(CommonR.string.common_on)
            val off = stringResource(CommonR.string.common_off)
            ChoiceSetting(
                title = R.string.modes_key_haptics_title,
                info = stringResource(R.string.modes_key_haptics_info),
                options = listOf(null to inherit, true to on, false to off),
                selected = settings.watch { modeIn(it).keyHaptics },
                detail = inheritDetail(),
            ) { save(current().copy(keyHaptics = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_enter_newline_title,
                info = stringResource(R.string.modes_enter_newline_info),
                options = listOf(
                    false to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                ),
                selected = settings.watch { modeIn(it).enterNewline },
                default = false,
            ) { save(current().copy(enterNewline = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_number_row_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).numberRow },
                detail = inheritDetail(),
            ) { save(current().copy(numberRow = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_one_handed_override_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    OneHandedMode.OFF to stringResource(CommonR.string.common_off),
                    OneHandedMode.LEFT to stringResource(R.string.modes_one_handed_left),
                    OneHandedMode.RIGHT to stringResource(R.string.modes_one_handed_right),
                ),
                selected = settings.watch { modeIn(it).oneHandedOverride },
                detail = inheritDetail(),
            ) { save(current().copy(oneHandedOverride = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_snippets_title,
                info = stringResource(R.string.modes_snippets_info),
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).snippets },
                detail = inheritDetail(),
            ) { save(current().copy(snippets = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_key_height_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    80 to "80%",
                    90 to "90%",
                    110 to "110%",
                    120 to "120%",
                ),
                selected = settings.watch { modeIn(it).keyHeightPercent },
                detail = inheritDetail(),
            ) { save(current().copy(keyHeightPercent = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_key_preview_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).keyPreview },
                detail = inheritDetail(),
            ) { save(current().copy(keyPreview = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_long_press_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    150 to "150 ms",
                    200 to "200 ms",
                    300 to "300 ms",
                    400 to "400 ms",
                    500 to "500 ms",
                ),
                selected = settings.watch { modeIn(it).longPressDelayMs },
                detail = inheritDetail(),
            ) { save(current().copy(longPressDelayMs = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_clipboard_chip_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).clipboardChip },
                detail = inheritDetail(),
            ) { save(current().copy(clipboardChip = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_key_swipes_title,
                options = listOf(
                    null to stringResource(R.string.modes_inherit_label),
                    true to stringResource(CommonR.string.common_on),
                    false to stringResource(CommonR.string.common_off),
                ),
                selected = settings.watch { modeIn(it).keySwipes },
                detail = inheritDetail(),
            ) { save(current().copy(keySwipes = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_languages_title,
                R.string.modes_languages_subtitle,
                settings.watch { modeIn(it).languages },
            ) { save(current().copy(languages = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_voice_languages_title,
                R.string.modes_voice_languages_subtitle,
                settings.watch { modeIn(it).voiceLanguages },
            ) { save(current().copy(voiceLanguages = it)) }
        }
        // Only the layouts the user actually has switched on: a mode naming one
        // they have since removed would pin the keyboard to something that
        // cannot be drawn, which applyMode also guards against at read time.
        item {
            val enabledIds = settings.watch { it.enabledLayoutIds }
            val customLayouts = settings.watch { it.customLayouts }
            val layoutOptions = listOf(
                null to stringResource(R.string.modes_inherit_label),
            ) + enabledIds.map { id ->
                id to resolveLayout(customLayouts, id).name
            }
            ChoiceSetting(
                title = R.string.modes_layout_title,
                subtitle = stringResource(R.string.modes_layout_subtitle),
                options = layoutOptions,
                selected = settings.watch { modeIn(it).layoutId }?.takeIf { it in enabledIds },
                info = stringResource(R.string.modes_layout_info),
                detail = inheritDetail(),
            ) { save(current().copy(layoutId = it)) }
        }
        item {
            var themePickerOpen by remember { mutableStateOf(false) }
            val themeId = settings.watch { modeIn(it).themeId }
            WmRow(
                title = stringResource(R.string.modes_theme_title),
                icon = SettingsRowIcons[R.string.modes_theme_title],
                subtitle = themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.modes_theme_inherit_subtitle),
                trailing = {
                    if (themeId != null) {
                        TextButton(onClick = { save(current().copy(themeId = null)) }) {
                            Text(stringResource(CommonR.string.common_clear))
                        }
                    }
                },
                onClick = { themePickerOpen = true },
            )
            if (themePickerOpen) {
                ModeThemePickerDialog(
                    settings = settings,
                    selectedId = themeId,
                    onPick = { id ->
                        themePickerOpen = false
                        save(current().copy(themeId = id))
                    },
                    onDismiss = { themePickerOpen = false },
                )
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.modes_pinned_tools_title,
                stringResource(R.string.modes_pinned_tools_subtitle),
                pinned != null,
            ) { on ->
                val mode = current()
                save(
                    mode.copy(
                        // Appending starts from nothing (the user's own pins
                        // are already there); replacing starts from a copy of
                        // the current toolbar to edit down.
                        toolbarTools = if (on) {
                            if (mode.toolbarToolsAppend) emptyList() else settings.value.toolbarTools
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        if (pinned != null) {
            item(level = SettingLevel.ADVANCED) {
                val appends = settings.watch { modeIn(it).toolbarToolsAppend }
                ChoiceSetting(
                    title = R.string.modes_pinned_behaviour_title,
                    subtitle = if (appends) {
                        stringResource(R.string.modes_pinned_behaviour_append_subtitle)
                    } else {
                        stringResource(R.string.modes_pinned_behaviour_replace_subtitle)
                    },
                    options = listOf(
                        true to stringResource(R.string.modes_pinned_behaviour_append_label),
                        false to stringResource(R.string.modes_pinned_behaviour_replace_label),
                    ),
                    selected = appends,
                    detail = { append ->
                        ChoiceDetail(
                            stringResource(
                                if (append) {
                                    R.string.modes_pinned_behaviour_append_desc
                                } else {
                                    R.string.modes_pinned_behaviour_replace_desc
                                },
                            ),
                            if (append) Icons.AutoMirrored.Outlined.PlaylistAdd
                            else Icons.Outlined.FindReplace,
                        )
                    },
                ) { append ->
                    // Switching to append: the copied-in global pins would
                    // duplicate what is already on the toolbar, so drop them.
                    save(
                        current().copy(
                            toolbarToolsAppend = append,
                            toolbarTools = if (append) pinned - settings.value.toolbarTools.toSet() else pinned,
                        ),
                    )
                }
            }
            item(level = SettingLevel.STANDARD) {
                ToolChips(
                    tools = settings.watch { s ->
                        ToolbarTool.entries.filter {
                            it in s.enabledTools && isSupportedTool(it) &&
                                isUsableTool(it, s)
                        }
                    },
                    selected = pinned,
                ) { tool ->
                    save(
                        current().copy(
                            toolbarTools = if (tool in pinned) pinned - tool else pinned + tool,
                        ),
                    )
                }
            }
            item(level = SettingLevel.STANDARD) {
                // toolTitle() hands back a resource id, and the reorder dialog
                // takes a plain (T) -> String, so the names are resolved here.
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in pinned) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderableColumn(
                    pinned,
                    label = { toolNames[it].orEmpty() },
                    onReorder = { save(current().copy(toolbarTools = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.modes_toolbox_order_title,
                stringResource(R.string.modes_toolbox_order_subtitle),
                order != null,
            ) { on ->
                save(current().copy(toolboxOrder = if (on) emptyList() else null))
            }
        }
        if (order != null) {
            item(level = SettingLevel.ADVANCED) {
                ToolChips(
                    tools = settings.watch { s ->
                        s.toolboxOrder.filter {
                            it in s.enabledTools && isSupportedTool(it) &&
                                isUsableTool(it, s)
                        }
                    },
                    selected = order,
                ) { tool ->
                    save(
                        current().copy(
                            toolboxOrder = if (tool in order) order - tool else order + tool,
                        ),
                    )
                }
            }
            item(level = SettingLevel.ADVANCED) {
                val toolNames = mutableMapOf<ToolbarTool, String>()
                for (tool in order) {
                    toolNames[tool] = stringResource(toolTitle(tool))
                }
                ReorderableColumn(
                    order,
                    label = { toolNames[it].orEmpty() },
                    onReorder = { save(current().copy(toolboxOrder = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        // A mode that switches the symbol row off has no row for its own sets
        // to appear on. Inherit and "on" both leave one that may be drawn.
        if (symbolRowPossible) item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.modes_symbol_sets_title,
                stringResource(R.string.modes_symbol_sets_subtitle),
                settings.watch { modeIn(it).symbolSetIds != null },
            ) { on ->
                save(
                    current().copy(
                        symbolSetIds = if (on) {
                            settings.value.symbolRowSetIds.ifEmpty { BuiltInSymbolSets.defaultEnabledIds }
                        } else {
                            null
                        },
                    ),
                )
            }
        }
        if (modeSets != null) {
            item(level = SettingLevel.ADVANCED) {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (set in resolveSymbolSets(settings.watch { it.customSymbolSets })) {
                        // A shipped set that still carries its shipped name is
                        // drawn from resources; a renamed one keeps the name
                        // the user typed.
                        val setLabel = BuiltInSymbolSets.nameRes(set)
                            ?.let { stringResource(it) } ?: set.name
                        FilterChip(
                            selected = set.id in modeSets,
                            onClick = {
                                val next =
                                    if (set.id in modeSets) modeSets - set.id else modeSets + set.id
                                if (next.isNotEmpty()) save(current().copy(symbolSetIds = next))
                            },
                            label = { Text(setLabel, maxLines = 1) },
                        )
                    }
                }
            }
            item(level = SettingLevel.ADVANCED) {
                val setNames = mutableMapOf<String, String>()
                for (set in resolveSymbolSets(settings.watch { it.customSymbolSets })) {
                    setNames[set.id] = BuiltInSymbolSets.nameRes(set)
                        ?.let { stringResource(it) } ?: set.name
                }
                val setName = { id: String -> setNames[id] ?: id }
                ReorderableColumn(
                    modeSets,
                    label = setName,
                    onReorder = { save(current().copy(symbolSetIds = it)) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
    // Two of the three conditions set: "both"; all three: "all".
    val conditions = listOf(apps.isNotEmpty(), hasFieldKinds, hints.isNotEmpty()).count { it }
    val bothMatchNote = when {
        conditions == 2 && hints.isEmpty() ->
            stringResource(R.string.modes_auto_both_match_body)
        conditions >= 2 -> stringResource(R.string.modes_auto_all_match_body)
        else -> null
    }
    SettingsGroup(
        stringResource(R.string.modes_auto_group_title),
        info = listOfNotNull(stringResource(R.string.modes_matching_body), bothMatchNote)
            .joinToString("\n\n"),
    ) {
        item {
            val fieldKinds = settings.watch { modeIn(it).fieldKinds }
            ControlSetting(R.string.modes_field_types_title) {
                FlowRow(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (field in ModeField.entries) {
                        FilterChip(
                            selected = field in fieldKinds,
                            onClick = {
                                val mode = current()
                                save(
                                    mode.copy(
                                        fieldKinds =
                                            if (field in mode.fieldKinds) mode.fieldKinds - field
                                            else mode.fieldKinds + field,
                                    ),
                                )
                            },
                            label = { Text(modeFieldLabel(field), maxLines = 1) },
                        )
                    }
                }
            }
        }
        item {
            // The heading of the hint rows under it, which is why it is a row
            // with nothing to press rather than a control.
            WmRow(
                title = stringResource(R.string.modes_field_hints_title),
                subtitle = stringResource(R.string.modes_field_hints_body),
                icon = SettingsRowIcons[R.string.modes_field_hints_title],
            )
        }
        for (hint in hints) {
            item(key = hint) {
                WmRow(
                    title = hint,
                    swipeActions = listOf(
                        deleteSwipeAction(stringResource(R.string.modes_hint_remove_desc)) {
                            val mode = current()
                            save(mode.copy(hints = mode.hints - hint))
                        },
                    ),
                    trailing = {
                        IconButton(onClick = {
                            val mode = current()
                            save(mode.copy(hints = mode.hints - hint))
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.modes_hint_remove_desc),
                            )
                        }
                    },
                )
            }
        }
        item {
            var editorOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_add_hint_title),
                subtitle = stringResource(R.string.modes_add_hint_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { editorOpen = true },
            )
            if (editorOpen) {
                HintEditorDialog(
                    onAdd = { hint ->
                        editorOpen = false
                        val mode = current()
                        if (hint !in mode.hints) save(mode.copy(hints = mode.hints + hint))
                    },
                    onDismiss = { editorOpen = false },
                )
            }
        }
        for (pkg in apps) {
            item(key = pkg) {
                val context = LocalContext.current
                val label = remember(pkg) {
                    runCatching {
                        context.packageManager.getApplicationLabel(
                            context.packageManager.getApplicationInfo(pkg, 0),
                        ).toString()
                    }.getOrDefault(pkg)
                }
                WmRow(
                    title = label,
                    supporting = if (label != pkg) {
                        { Text(pkg) }
                    } else null,
                    swipeActions = listOf(
                        deleteSwipeAction(stringResource(R.string.modes_app_remove_desc)) {
                            val mode = current()
                            save(mode.copy(apps = mode.apps - pkg))
                        },
                    ),
                    trailing = {
                        IconButton(onClick = {
                            val mode = current()
                            save(mode.copy(apps = mode.apps - pkg))
                        }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.modes_app_remove_desc),
                            )
                        }
                    },
                )
            }
        }
        item {
            var pickerOpen by remember { mutableStateOf(false) }
            WmRow(
                title = stringResource(R.string.modes_add_app_title),
                subtitle = if (!hasFieldKinds) {
                        stringResource(R.string.modes_add_app_subtitle_any)
                    } else {
                        stringResource(R.string.modes_add_app_subtitle_fields)
                    },
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = { pickerOpen = true },
            )
            if (pickerOpen) {
                AppPickerDialog(
                    exclude = apps,
                    onPick = { pkg ->
                        pickerOpen = false
                        val mode = current()
                        save(mode.copy(apps = mode.apps + pkg))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
        // Everything else matches and these still don't (#1025). One item, since
        // the list is read inside it rather than in the group's builder.
        item(level = SettingLevel.ADVANCED) {
            val context = LocalContext.current
            val excluded = settings.watch { modeIn(it).excludedApps }
            var pickerOpen by remember { mutableStateOf(false) }
            Column {
                for (pkg in excluded) {
                    val label = remember(pkg) {
                        runCatching {
                            context.packageManager.getApplicationLabel(
                                context.packageManager.getApplicationInfo(pkg, 0),
                            ).toString()
                        }.getOrDefault(pkg)
                    }
                    WmRow(
                        title = label,
                        trailing = {
                            IconButton(onClick = {
                                val mode = current()
                                save(mode.copy(excludedApps = mode.excludedApps - pkg))
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.modes_app_remove_desc),
                                )
                            }
                        },
                    )
                }
                WmRow(
                    title = stringResource(R.string.modes_exclude_app_title),
                    subtitle = stringResource(R.string.modes_exclude_app_subtitle),
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = { pickerOpen = true },
                )
            }
            if (pickerOpen) {
                AppPickerDialog(
                    exclude = excluded,
                    onPick = { pkg ->
                        pickerOpen = false
                        val mode = current()
                        save(mode.copy(excludedApps = mode.excludedApps + pkg))
                    },
                    onDismiss = { pickerOpen = false },
                )
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_priority_title,
                info = stringResource(R.string.modes_priority_info),
                options = listOf(
                    -1 to stringResource(R.string.modes_priority_low),
                    0 to stringResource(R.string.modes_priority_normal),
                    1 to stringResource(R.string.modes_priority_high),
                ),
                selected = settings.watch { modeIn(it).priority },
                default = 0,
            ) { save(current().copy(priority = it)) }
        }
        // Kinds of app, on the same terms as the list above (#1025).
        for (group in AppGroup.entries) {
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    appGroupLabel(group),
                    stringResource(R.string.modes_group_subtitle),
                    settings.watch { group in modeIn(it).appGroups },
                ) { on ->
                    val mode = current()
                    save(mode.copy(appGroups = if (on) mode.appGroups + group else mode.appGroups - group))
                }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_orientation_title,
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    ModeOrientation.PORTRAIT to stringResource(R.string.modes_orientation_portrait),
                    ModeOrientation.LANDSCAPE to stringResource(R.string.modes_orientation_landscape),
                ),
                selected = settings.watch { modeIn(it).orientation },
                default = null,
            ) { save(current().copy(orientation = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_hardware_keyboard_title,
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    true to stringResource(R.string.modes_hardware_keyboard_on),
                    false to stringResource(R.string.modes_hardware_keyboard_off),
                ),
                selected = settings.watch { modeIn(it).hardwareKeyboard },
                default = null,
            ) { save(current().copy(hardwareKeyboard = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_screen_size_title,
                info = stringResource(R.string.modes_screen_size_info),
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    ModeScreenSize.PHONE to stringResource(R.string.modes_screen_size_phone),
                    ModeScreenSize.TABLET to stringResource(R.string.modes_screen_size_tablet),
                ),
                selected = settings.watch { modeIn(it).screenSize },
                default = null,
            ) { save(current().copy(screenSize = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_one_handed_title,
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    true to stringResource(R.string.modes_one_handed_on),
                    false to stringResource(R.string.modes_one_handed_off),
                ),
                selected = settings.watch { modeIn(it).oneHanded },
                default = null,
            ) { save(current().copy(oneHanded = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_bluetooth_title,
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    true to stringResource(R.string.modes_bluetooth_on),
                    false to stringResource(R.string.modes_bluetooth_off),
                ),
                selected = settings.watch { modeIn(it).bluetoothAudio },
                default = null,
            ) { save(current().copy(bluetoothAudio = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_bluetooth_names_title,
                R.string.modes_bluetooth_names_subtitle,
                settings.watch { modeIn(it).bluetoothNames },
            ) { save(current().copy(bluetoothNames = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_field_names_title,
                R.string.modes_field_names_subtitle,
                settings.watch { modeIn(it).fieldNames },
            ) { save(current().copy(fieldNames = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_field_languages_title,
                R.string.modes_field_languages_subtitle,
                settings.watch { modeIn(it).fieldLanguages },
            ) { save(current().copy(fieldLanguages = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ChoiceSetting(
                title = R.string.modes_field_empty_title,
                options = listOf(
                    null to stringResource(R.string.modes_condition_any),
                    true to stringResource(R.string.modes_field_empty_on),
                    false to stringResource(R.string.modes_field_empty_off),
                ),
                selected = settings.watch { modeIn(it).fieldEmpty },
                default = null,
            ) { save(current().copy(fieldEmpty = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            ModeListSetting(
                R.string.modes_text_starts_title,
                R.string.modes_text_starts_subtitle,
                settings.watch { modeIn(it).textStarts },
            ) { save(current().copy(textStarts = it)) }
        }
        item(level = SettingLevel.ADVANCED) {
            val context = LocalContext.current
            val from = settings.watch { modeIn(it).timeFrom }
            val to = settings.watch { modeIn(it).timeTo }
            fun pick(minute: Int, onPick: (Int) -> Unit) {
                android.app.TimePickerDialog(
                    context,
                    { _, hour, min -> onPick(hour * 60 + min) },
                    minute / 60,
                    minute % 60,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            }
            fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
            Column {
                ToggleSetting(
                    R.string.modes_time_title,
                    stringResource(R.string.modes_time_subtitle),
                    from != null && to != null,
                ) { on ->
                    save(
                        if (on) {
                            current().copy(timeFrom = MODE_NIGHT_FROM, timeTo = MODE_NIGHT_TO)
                        } else {
                            current().copy(timeFrom = null, timeTo = null)
                        },
                    )
                }
                if (from != null && to != null) {
                    WmRow(
                        title = stringResource(R.string.modes_time_from),
                        subtitle = clock(from),
                        onClick = { pick(from) { save(current().copy(timeFrom = it)) } },
                    )
                    WmRow(
                        title = stringResource(R.string.modes_time_to),
                        subtitle = clock(to),
                        onClick = { pick(to) { save(current().copy(timeTo = it)) } },
                    )
                }
            }
        }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (builtInDefault != null) {
            TextButton(onClick = { confirmReset = true }) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.modes_reset_default_action))
            }
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = { confirmDelete = true }) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.modes_delete_action))
        }
    }
    if (confirmReset && builtInDefault != null) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = {
                Text(stringResource(R.string.modes_reset_confirm_title, builtInDefault.name))
            },
            text = { Text(stringResource(R.string.modes_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch { repository.resetKeyboardModeToDefault(modeId) }
                }) { Text(stringResource(CommonR.string.common_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.modes_delete_confirm_title, settings.watch { modeIn(it).name })) },
            text = { Text(stringResource(R.string.modes_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { repository.deleteKeyboardMode(modeId) }
                    onDeleted()
                }) { Text(stringResource(CommonR.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}
/**
 * Picks an icon from [ModeIcons.catalog], as a grid of named cells — the
 * same cell every icon picker in the app draws, so a glyph always says what
 * it is called.
 *
 * Shared with the snippet folders, which wear the same catalogue — hence
 * [title], the one thing the two callers disagree about.
 */
@Composable
internal fun ModeIconPickerDialog(
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.modes_icon_picker_title),
    clearLabel: String? = null,
) {
    val iconGrid = rememberLazyGridState()
    val iconRail = rememberScrollRailState(iconGrid)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The way back out, for the callers that have a drawing of
                // their own to fall back to — a snippet folder draws a folder.
                // A mode has no such thing, so it passes no label and the chip
                // is not there.
                if (clearLabel != null) {
                    FilterChip(
                        selected = selected == null,
                        onClick = { onPick(null) },
                        label = { Text(clearLabel) },
                    )
                }
                ScrollRailBox(
                    state = iconRail,
                    modifier = Modifier.heightIn(max = 380.dp),
                ) { cells ->
                    LazyVerticalGrid(
                        state = iconGrid,
                        columns = GridCells.Adaptive(IconGridCellMinWidth),
                        modifier = cells,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(ModeIcons.catalog, key = { it.first }) { (id, vector) ->
                            IconGridCell(
                                vector = vector,
                                name = id,
                                selected = id == selected,
                                onClick = { onPick(id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** Picks one installed app (launcher activities): a mode binding, an incognito app. */
@Composable
internal fun AppPickerDialog(
    exclude: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }
    val shown = apps.filter { (pkg, label) ->
        pkg !in exclude &&
            (query.isBlank() || label.contains(query, ignoreCase = true) ||
                pkg.contains(query, ignoreCase = true))
    }
    val appList = rememberLazyListState()
    val appRail = rememberScrollRailState(appList)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_app_picker_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(CommonR.string.common_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ScrollRailBox(state = appRail, modifier = Modifier.heightIn(max = 380.dp)) { rows ->
                    LazyColumn(state = appList, modifier = rows) {
                        items(shown, key = { it.first }) { (pkg, label) ->
                            ListItem(
                                headlineContent = { Text(label) },
                                supportingContent = { Text(pkg) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(pkg) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * What a mode does to the emoji row, one line, for the picker sheet. Null is
 * the "inherit" option: the mode says nothing and the Emoji screen decides.
 */
private fun modeEmojiRowDescRes(mode: EmojiBarMode?): Int = when (mode) {
    null -> R.string.modes_emoji_row_inherit_desc
    EmojiBarMode.OFF -> R.string.modes_emoji_row_off_desc
    EmojiBarMode.BUTTON -> R.string.modes_emoji_row_button_desc
    EmojiBarMode.ALWAYS -> R.string.modes_emoji_row_row_desc
}

/**
 * The description for the null option every per-mode setting starts with.
 * "Inherit" is a word about the settings tree rather than about the keyboard,
 * so it is the one option of the three whose name says nothing. On/Off need
 * no line, and returning null for them leaves those rows as they were.
 *
 * Generic because the option type differs per row: `Boolean?` for the
 * switches, `String?` for the layout picker.
 */
private fun <T> inheritDetail(): @Composable (T) -> ChoiceDetail? = { value ->
    when (value) {
        null -> ChoiceDetail(stringResource(R.string.modes_inherit_desc), Icons.Outlined.AltRoute)
        true -> ChoiceDetail(icon = Icons.Outlined.CheckCircle)
        false -> ChoiceDetail(icon = Icons.Outlined.Block)
        // A layout id, on the one row of the five whose options are names.
        else -> ChoiceDetail(icon = Icons.Outlined.Keyboard)
    }
}

/**
 * Asks for one hint text to bind a mode to. Matching is a case-ignored
 * "contains", so a word is enough — "extension" catches "Extension" and
 * "File extension" alike. Whitespace around it is not part of the match.
 */
/** A mode's list of words or codes, edited as one comma-separated line; empty is the [subtitle]. */
@Composable
private fun ModeListSetting(
    @StringRes title: Int,
    @StringRes subtitle: Int,
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    WmRow(
        title = stringResource(title),
        subtitle = values.joinToString(", ").ifEmpty { stringResource(subtitle) },
        onClick = { editing = true },
    )
    if (!editing) return
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(stringResource(title)) },
        text = {
            Column {
                Text(stringResource(subtitle))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = false
                onChange(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct())
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun HintEditorDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val trimmed = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.modes_add_hint_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.modes_hint_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onAdd(trimmed) }, enabled = trimmed.isNotEmpty()) {
                Text(stringResource(CommonR.string.common_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
