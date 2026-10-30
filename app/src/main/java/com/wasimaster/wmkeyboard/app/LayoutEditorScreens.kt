package com.wasimaster.wmkeyboard.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowLeft
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Redo
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.Undo
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowDown
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardArrowUp
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Remove
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import com.wasimaster.wmkeyboard.core.layout.BottomRowRule
import com.wasimaster.wmkeyboard.core.layout.BottomRowRules
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.FlickArm
import com.wasimaster.wmkeyboard.core.layout.flickArm
import com.wasimaster.wmkeyboard.core.layout.hasFlicks
import com.wasimaster.wmkeyboard.core.layout.isSwipeOnly
import com.wasimaster.wmkeyboard.core.layout.takesFlickActions
import com.wasimaster.wmkeyboard.core.layout.arrangedBy
import com.wasimaster.wmkeyboard.core.layout.FlickDirection
import com.wasimaster.wmkeyboard.core.layout.KanaVariantKeyLabel
import com.wasimaster.wmkeyboard.core.layout.LayerFile
import com.wasimaster.wmkeyboard.core.layout.KeyFile
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.ui.WmSlider
import com.wasimaster.wmkeyboard.core.util.readTextCapped
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.repairAsLayer
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.settings.ACTION_POSITION_LAST
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileUpload
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.addons.AddonType
import androidx.compose.foundation.lazy.LazyColumn
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.layout.ConvertedLayout
import com.wasimaster.wmkeyboard.core.keyman.KeymanImport
import com.wasimaster.wmkeyboard.core.layout.DesktopLayouts
import com.wasimaster.wmkeyboard.core.layout.ForeignLayouts
import com.wasimaster.wmkeyboard.core.layout.FutoLayouts
import com.wasimaster.wmkeyboard.core.layout.ForeignSource
import com.wasimaster.wmkeyboard.core.layout.KeysCafeLayouts
import com.wasimaster.wmkeyboard.core.layout.MultilingLayouts
import com.wasimaster.wmkeyboard.core.layout.UnexpectedLayouts
import com.wasimaster.wmkeyboard.core.layout.ImportedLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.LayoutMessage
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Close
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentPaste
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandLess
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ExpandMore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreVert
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.PanelKind
import com.wasimaster.wmkeyboard.core.layout.panelKindForLayerKey
import com.wasimaster.wmkeyboard.core.layout.panelLayer
import com.wasimaster.wmkeyboard.core.layout.resolvePanelLayout
import com.wasimaster.wmkeyboard.core.layout.KeyScreen
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.leadsWithDigitRow
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.json.LayoutJsonRoot
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.compile
import com.wasimaster.wmkeyboard.ime.ui.BuiltinIcons
import com.wasimaster.wmkeyboard.ime.ui.IconDefaults
import com.wasimaster.wmkeyboard.ime.ui.KeyIcons
import com.wasimaster.wmkeyboard.ime.ui.textEditIcon
import com.wasimaster.wmkeyboard.core.layout.GridUnitStep
import com.wasimaster.wmkeyboard.core.layout.KeyLabelScaleRange
import com.wasimaster.wmkeyboard.core.layout.LayoutAppearance
import com.wasimaster.wmkeyboard.core.layout.LayoutFontScaleRange
import com.wasimaster.wmkeyboard.core.layout.MaxKeyWidth
import com.wasimaster.wmkeyboard.core.layout.MaxRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.MinRowHeightScale
import com.wasimaster.wmkeyboard.core.layout.canHoldAlternates
import com.wasimaster.wmkeyboard.core.layout.canHoldAction
import com.wasimaster.wmkeyboard.core.layout.canRepeatOnHold
import com.wasimaster.wmkeyboard.core.layout.isAmbiguous
import com.wasimaster.wmkeyboard.core.layout.withLetters
import com.wasimaster.wmkeyboard.core.layout.drawnFontScale
import com.wasimaster.wmkeyboard.core.layout.drawnLabel
import com.wasimaster.wmkeyboard.core.layout.drawnLabelScale
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
import com.wasimaster.wmkeyboard.core.layout.AlternateEntry
import com.wasimaster.wmkeyboard.core.layout.alternateEntries
import com.wasimaster.wmkeyboard.core.layout.script
import com.wasimaster.wmkeyboard.core.layout.secondaryLayouts
import com.wasimaster.wmkeyboard.core.settings.applyLayoutTheme
import com.wasimaster.wmkeyboard.core.layout.fitRowToGrid
import com.wasimaster.wmkeyboard.core.layout.gridWeightOf
import com.wasimaster.wmkeyboard.core.layout.hasRowSpans
import com.wasimaster.wmkeyboard.core.layout.KeySlot
import com.wasimaster.wmkeyboard.core.layout.roundGridUnit
import com.wasimaster.wmkeyboard.core.layout.rowScaledKeyHeight
import com.wasimaster.wmkeyboard.core.layout.fallbackLabel
import com.wasimaster.wmkeyboard.core.layout.shiftLabelReplacesIcon
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.composerType
import com.wasimaster.wmkeyboard.core.layout.findLayout
import com.wasimaster.wmkeyboard.core.layout.isShippedLayoutId
import com.wasimaster.wmkeyboard.core.layout.shippedLayoutRank
import com.wasimaster.wmkeyboard.core.layout.sidePadFor
import com.wasimaster.wmkeyboard.core.layout.spanBands
import com.wasimaster.wmkeyboard.core.layout.spanRowWidths
import com.wasimaster.wmkeyboard.core.layout.spanSlots
import com.wasimaster.wmkeyboard.core.layout.validateLayout
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.settings.KeyboardMode
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.SidePadScaleRange
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.keyShape
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon
import com.wasimaster.wmkeyboard.ime.ui.arrowRowKey
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoMode
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Block
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Bolt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Lock
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FiberManualRecord
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.KeyboardAlt
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MoreHoriz
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Public
import com.wasimaster.wmkeyboard.core.ui.DensityScaled
import com.wasimaster.wmkeyboard.core.ui.ScalablePreview
import com.wasimaster.wmkeyboard.core.ui.ScrollRailBox
import com.wasimaster.wmkeyboard.core.ui.rememberScrollRailState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.wasimaster.wmkeyboard.core.ui.ScrollRail
import com.wasimaster.wmkeyboard.core.input.imported.Conversion
import com.wasimaster.wmkeyboard.core.input.imported.ConvertedMethod
import com.wasimaster.wmkeyboard.core.input.imported.ImportRefusal
import com.wasimaster.wmkeyboard.core.input.imported.InputMethodImport
import com.wasimaster.wmkeyboard.core.input.imported.SourceFile

/**
 * One key layout's editor, as flights name it.
 *
 * The navigation route with its argument filled in, which is what a flight is
 * keyed on: the pattern (`keymap_edit/{layoutId}`) is the same string for every
 * layout and would hang one key on all of them.
 */
internal fun keyLayoutEditRoute(layoutId: String, layer: String? = null): String =
    if (layer.isNullOrEmpty()) "keymap_edit/$layoutId" else "keymap_edit/$layoutId?layer=$layer"

// ---------------------------------------------------------------------------
// Gallery
// ---------------------------------------------------------------------------

/**
 * Every layout the user has: the shipped ones, then their own.
 *
 * A list rather than the two-per-row card grid the themes gallery uses. A
 * theme's whole identity is a colour swatch and reads fine at 150dp; a layout's
 * identity is its key arrangement, and a ten-column grid in a half-width card
 * gives about 17dp per key. A full-width row with a one-line shape summary
 * carries more than a shrunken grid would.
 */
/** [ReturnAnchor] key for the key-layouts list. */
private const val KEYMAPS_ANCHOR = "keymaps"

/**
 * What the "import from another keyboard" picker offers.
 *
 * Wider than the native layout picker's list because these files are somebody
 * else's: HeliBoard writes `.txt` and `.json`, and providers report both as
 * anything from `text/plain` to `application/octet-stream`. Nothing is decided
 * from the MIME type — the converter reads the file and says whether it is one.
 */
private val FOREIGN_LAYOUT_MIME_TYPES = arrayOf(
    "application/json",
    "text/plain",
    "application/octet-stream",
    // FUTO's layouts are YAML, which providers report under three spellings
    // and, as often as not, as one of the three above.
    "application/yaml",
    "text/yaml",
    "text/x-yaml",
    // Unexpected Keyboard and CleverKeys layouts are XML.
    "text/xml",
    "application/xml",
)

/** Largest foreign layout worth reading. The whole file is decoded as one string. */
private const val MAX_FOREIGN_LAYOUT_BYTES = 4 * 1024 * 1024

/**
 * The whole stream, or null once it runs past [max] bytes. Grows as it reads,
 * unlike [readBytes], which sizes its buffer for the cap up front: the cap
 * here is tens of megabytes and the usual file a few hundred kilobytes.
 */
private fun java.io.InputStream.readCapped(max: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buffer)
        if (n <= 0) break
        total += n
        if (total > max) return null
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * Reads at most [max] bytes. A declared length is never trusted; the count comes
 * from what was actually read, the same way the archive importers do it.
 */
private fun java.io.InputStream.readBytes(max: Int): ByteArray {
    val out = ByteArray(max)
    var filled = 0
    val buffer = ByteArray(8 * 1024)
    while (filled < max) {
        val n = read(buffer, 0, minOf(buffer.size, max - filled))
        if (n <= 0) break
        System.arraycopy(buffer, 0, out, filled, n)
        filled += n
    }
    return out.copyOf(filled)
}

/**
 * Picks the language a converted layout types in.
 *
 * Its own dialog rather than a list inside the confirmation: the registry holds
 * over three hundred languages, so this needs a search field, and the search
 * field needs the room. Seeded from the character-set guess, which the caller
 * has already put in front of the user as a guess.
 */
/** Renames a layout. Blank is rejected rather than saved as an unnamed row. */
@Composable
private fun LayoutNameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val trimmed = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.layout_editor_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = { onConfirm(trimmed) },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * A composer's name for the override row. Deliberately descriptive rather than
 * the enum name: "PINYIN" says nothing to someone choosing between a phonetic
 * and a direct grid.
 */
@Composable
private fun composerLabel(type: ComposerType): String = stringResource(
    when (type) {
        ComposerType.NONE -> R.string.layout_editor_composer_none
        ComposerType.DEAD_KEY -> R.string.layout_editor_composer_dead_key
        ComposerType.TRANSLITERATE -> R.string.layout_editor_composer_transliterate
        ComposerType.INDIC_CLUSTER -> R.string.layout_editor_composer_indic
        ComposerType.HANGUL -> R.string.layout_editor_composer_hangul
        ComposerType.TELEX -> R.string.layout_editor_composer_telex
        ComposerType.VNI -> R.string.layout_editor_composer_vni
        ComposerType.ROMAJI -> R.string.layout_editor_composer_romaji
        ComposerType.PINYIN -> R.string.layout_editor_composer_pinyin
        ComposerType.STROKE -> R.string.layout_editor_composer_stroke
        ComposerType.T9_PINYIN -> R.string.layout_editor_composer_t9_pinyin
        ComposerType.ZHUYIN -> R.string.layout_editor_composer_zhuyin
        ComposerType.T9_ZHUYIN -> R.string.layout_editor_composer_t9_zhuyin
        ComposerType.CANGJIE -> R.string.layout_editor_composer_cangjie
        ComposerType.CANGJIE_QUICK -> R.string.layout_editor_composer_cangjie_quick
        ComposerType.JYUTPING -> R.string.layout_editor_composer_jyutping
        ComposerType.BORNO_PHONETIC -> R.string.layout_editor_composer_borno
        ComposerType.KHIPRO -> R.string.layout_editor_composer_khipro
        ComposerType.KHIPRO_DARK -> R.string.layout_editor_composer_khipro_dark
        ComposerType.CHEONJIIN -> R.string.layout_editor_composer_cheonjiin
        ComposerType.NARATGEUL -> R.string.layout_editor_composer_naratgeul
        ComposerType.VEGA -> R.string.layout_editor_composer_vega
        ComposerType.MOAKEY -> R.string.layout_editor_composer_moakey
    },
)

/**
 * What you actually press under each typing method, one line, for the picker
 * sheet. Null is the "follow the language" option. The names are the names the
 * methods are known by, which help only a reader who already knows them.
 */
private fun composerDescRes(type: ComposerType?): Int = when (type) {
    null -> R.string.layout_editor_composer_inherit_desc
    ComposerType.NONE -> R.string.layout_editor_composer_none_desc
    ComposerType.DEAD_KEY -> R.string.layout_editor_composer_dead_key_desc
    ComposerType.TRANSLITERATE -> R.string.layout_editor_composer_transliterate_desc
    ComposerType.INDIC_CLUSTER -> R.string.layout_editor_composer_indic_desc
    ComposerType.HANGUL -> R.string.layout_editor_composer_hangul_desc
    ComposerType.TELEX -> R.string.layout_editor_composer_telex_desc
    ComposerType.VNI -> R.string.layout_editor_composer_vni_desc
    ComposerType.ROMAJI -> R.string.layout_editor_composer_romaji_desc
    ComposerType.PINYIN -> R.string.layout_editor_composer_pinyin_desc
    ComposerType.STROKE -> R.string.layout_editor_composer_stroke_desc
    ComposerType.T9_PINYIN -> R.string.layout_editor_composer_t9_pinyin_desc
    ComposerType.ZHUYIN -> R.string.layout_editor_composer_zhuyin_desc
    ComposerType.T9_ZHUYIN -> R.string.layout_editor_composer_t9_zhuyin_desc
    ComposerType.CANGJIE -> R.string.layout_editor_composer_cangjie_desc
    ComposerType.CANGJIE_QUICK -> R.string.layout_editor_composer_cangjie_quick_desc
    ComposerType.JYUTPING -> R.string.layout_editor_composer_jyutping_desc
    ComposerType.BORNO_PHONETIC -> R.string.layout_editor_composer_borno_desc
    ComposerType.KHIPRO -> R.string.layout_editor_composer_khipro_desc
    ComposerType.KHIPRO_DARK -> R.string.layout_editor_composer_khipro_dark_desc
    ComposerType.CHEONJIIN -> R.string.layout_editor_composer_cheonjiin_desc
    ComposerType.NARATGEUL -> R.string.layout_editor_composer_naratgeul_desc
    ComposerType.VEGA -> R.string.layout_editor_composer_vega_desc
    ComposerType.MOAKEY -> R.string.layout_editor_composer_moakey_desc
}

/**
 * The language picker a converted foreign layout has to go through.
 *
 * Internal rather than private: the file-association import dialog shows the
 * same step for a layout opened from a file manager, and two pickers that could
 * drift apart is how the two paths end up disagreeing about what a language is.
 */
@Composable
internal fun ForeignLanguageDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Remembered on the query: this recomposes on every letter typed, and
    // re-running the filter over the whole registry per keystroke is what makes
    // a search field feel heavy.
    val results = remember(query) { searchLanguages(query.trim().lowercase()) }
    val reduceMotion = LocalReduceMotion.current
    val list = rememberLazyListState()
    val rail = rememberScrollRailState(list)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_foreign_language_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.layout_editor_foreign_language_search)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                ScrollRailBox(state = rail, modifier = Modifier.heightIn(max = 320.dp)) { rows ->
                    LazyColumn(state = list, modifier = rows) {
                        items(results, key = { it.id }) { language ->
                            // Narrowing the search slides the matches together
                            // instead of redrawing the list in its new order.
                            WmRow(
                                modifier = listItemMotion(reduceMotion),
                                title = language.displayName,
                                trailing = if (language.id == selected) {
                                    { Icon(Icons.Outlined.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = { onPick(language.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
internal fun KeyLayoutsScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf<LayoutSpec?>(null) }
    var confirmImport by remember { mutableStateOf<ImportedLayout?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    // A layout converted from another keyboard, waiting on a language. Neither
    // FlorisBoard nor HeliBoard states one in a layout file, and a layout stored
    // without one is silently read back as English — with an English dictionary
    // and Latin shift behaviour on, say, a Georgian grid.
    var confirmForeign by remember { mutableStateOf<ConvertedLayout?>(null) }
    // A Multiling O settings export: every layout its user made, each filed
    // under the language it was made for, so there is no language to ask.
    var confirmMultiling by remember { mutableStateOf<List<ConvertedLayout>?>(null) }
    var foreignLangId by remember { mutableStateOf("") }
    var pickingLanguage by remember { mutableStateOf(false) }

    // An input method read out of another IME's files (.cin, Rime, m17n),
    // waiting on a language like a foreign layout does: it becomes a layout
    // too, and a layout stored without a language is read back as English.
    var confirmMethod by remember { mutableStateOf<ConvertedMethod?>(null) }
    var methodLangId by remember { mutableStateOf("") }
    var pickingMethodLanguage by remember { mutableStateOf(false) }
    var methodBusy by remember { mutableStateOf(false) }

    // CreateDocument cannot carry a payload, so the layout waiting to be written
    // is parked here between launching the picker and its result.
    var pendingExport by remember { mutableStateOf<LayoutSpec?>(null) }
    // The layout the community publish dialog is open for.
    var publishing by remember { mutableStateOf<LayoutSpec?>(null) }
    publishing?.let { PublishLayoutDialog(it) { publishing = null } }
    val exportLauncher = rememberLauncherForActivityResult(
        CreateDocumentAt(LayoutFile.MIME_TYPE, PickerPlaces.LAYOUT_EXPORT),
    ) { uri ->
        val layout = pendingExport
        pendingExport = null
        if (uri == null || layout == null) return@rememberLauncherForActivityResult
        PickerPlaces.remember(context, PickerPlaces.LAYOUT_EXPORT, uri)
        scope.launch {
            val ok = runCancellable {
                val text = LayoutFile.encode(
                    layout,
                    appVersion = BuildConfig.VERSION_CODE,
                    appVersionName = BuildConfig.VERSION_NAME,
                )
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(text.toByteArray())
                    }
                }
            }.isSuccess
            // Reported either way. The theme export swallows its failures, and
            // the exported file may be the only copy of an hour's work.
            message = if (ok) {
                context.getString(R.string.layout_editor_export_done_message, layout.name)
            } else {
                context.getString(R.string.layout_editor_export_error)
            }
        }
    }

    // Every layout in use, in one file (#970), parked like [pendingExport].
    var pendingBundle by remember { mutableStateOf<List<LayoutSpec>?>(null) }
    val bundleLauncher = rememberLauncherForActivityResult(
        CreateDocumentAt(LayoutFile.MIME_TYPE, PickerPlaces.LAYOUT_EXPORT),
    ) { uri ->
        val bundle = pendingBundle
        pendingBundle = null
        if (uri == null || bundle == null) return@rememberLauncherForActivityResult
        PickerPlaces.remember(context, PickerPlaces.LAYOUT_EXPORT, uri)
        scope.launch {
            val ok = runCancellable {
                val text = LayoutFile.encodeBundle(bundle, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
                withContext(Dispatchers.IO) {
                    context.contentResolver.requireOutputStream(uri).use { it.write(text.toByteArray()) }
                }
            }.isSuccess
            message = if (ok) {
                context.resources.getQuantityString(R.plurals.layout_editor_bundle_done_message, bundle.size, bundle.size)
            } else {
                context.getString(R.string.layout_editor_export_error)
            }
        }
    }
    // A file of several layouts, read and waiting on the user's answer.
    var confirmBundle by remember { mutableStateOf<List<ImportedLayout>?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        OpenDocumentAt(PickerPlaces.LAYOUT_IMPORT),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PickerPlaces.remember(context, PickerPlaces.LAYOUT_IMPORT, uri)
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.readTextCapped(uri)
                }.getOrNull()
            }
            val parsed = text?.let { LayoutFile.decode(it) }
            if (parsed == null) {
                // Or a file of several, from Export layouts in use (#970).
                val bundle = text?.let { LayoutFile.decodeBundle(it) }
                if (bundle != null) {
                    confirmBundle = bundle
                } else {
                    message = context.getString(R.string.layout_editor_import_wrong_file_error)
                }
                return@launch
            }
            // Read first, ask, then write. Importing a layout is not something
            // to discover you have done — and it never activates it either.
            confirmImport = parsed
        }
    }

    val foreignLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = WMFileTypes.displayName(context, uri)
            val converted = withContext(Dispatchers.IO) {
                runCatching {
                    // Capped, unlike the native import: this file was written by
                    // another app and picked by extension, so it can be anything
                    // the picker let through. The cap matches what an add-on
                    // repository will download for a layout, and no real grid is
                    // within two orders of magnitude of it.
                    val bytes = context.contentResolver.requireInputStream(uri).use { input ->
                        input.readBytes(MAX_FOREIGN_LAYOUT_BYTES + 1)
                    }
                    if (bytes.size > MAX_FOREIGN_LAYOUT_BYTES) {
                        emptyList<ConvertedLayout>()
                    } else {
                        // Keyman first: its files are JSON too, and the
                        // FlorisBoard reader would take one and return null
                        // rather than deferring, so order is the dispatch.
                        // UTF-16 is read as such: MSKLC saves every `.klc` that way.
                        val text = DesktopLayouts.decodeText(bytes)
                        // Multiling O first: its markers and its `onScreen`
                        // object are its own, and a settings export can hold
                        // a dozen layouts where every other format holds one.
                        if (MultilingLayouts.looksLikeMultiling(text)) {
                            return@runCatching MultilingLayouts.convertAll(text, name)
                        }
                        // A computer keyboard's layout (.klc, .keylayout, XKB),
                        // before the Unexpected Keyboard reader below, whose
                        // XML test a .keylayout passes too. An XKB file holds
                        // a layout per variant.
                        if (DesktopLayouts.looksLikeDesktopLayout(text)) {
                            return@runCatching DesktopLayouts.convertAll(text, name)
                        }
                        // Keys Cafe before everything: its file is base64 text,
                        // which none of the readers below could take for theirs.
                        // The sniff can pass a HeliBoard text layout of plain
                        // letters by accident, so a refusal falls through.
                        val single = KeysCafeLayouts.takeIf { it.looksLikeKcf(text) }?.convert(text, name) ?: when {
                            // Unexpected Keyboard and CleverKeys write XML,
                            // which none of the readers below would take.
                            UnexpectedLayouts.looksLikeUnexpected(text) ->
                                UnexpectedLayouts.convert(text, name)
                            KeymanImport.looksLikeTouchLayout(text) ->
                                KeymanImport.convert(text, name)
                            // FUTO before the JSON reader for the same reason:
                            // a YAML parser accepts a JSON document happily, so
                            // asking it last would be fine but asking it first
                            // would swallow every FlorisBoard layout. This test
                            // is on the document's own shape, not the parser.
                            FutoLayouts.looksLikeFutoLayout(text) ->
                                FutoLayouts.convert(text, name)
                            else -> ForeignLayouts.convert(text, name)
                        }
                        listOfNotNull(single)
                    }
                }.getOrNull().orEmpty()
            }
            if (converted.isEmpty()) {
                message = context.getString(R.string.layout_editor_foreign_wrong_file_error)
                return@launch
            }
            if (converted.size > 1) {
                confirmMultiling = converted
                return@launch
            }
            foreignLangId = converted.single().guessedLangId
            confirmForeign = converted.single()
        }
    }

    // Several files at once: a Rime schema names its dictionary, which names
    // the tables it imports, and an m17n method may include another.
    val methodLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        methodBusy = true
        scope.launch {
            val conversion = withContext(Dispatchers.IO) {
                runCatching {
                    var total = 0L
                    val files = uris.map { uri ->
                        val bytes = context.contentResolver.requireInputStream(uri).use { input ->
                            input.readCapped(InputMethodImport.MAX_FILE_BYTES)
                        } ?: return@runCatching null
                        total += bytes.size
                        if (total > InputMethodImport.MAX_TOTAL_BYTES) return@runCatching null
                        SourceFile(WMFileTypes.displayName(context, uri), bytes)
                    }
                    InputMethodImport.read(files)
                }.getOrElse { Conversion.Refused(ImportRefusal.BROKEN, it.message.orEmpty()) }
            }
            methodBusy = false
            when (conversion) {
                null -> message = context.getString(R.string.layout_editor_im_too_large)
                is Conversion.Refused -> message = InputMethodImport.refusal(conversion).format(context.resources)
                is Conversion.Ready -> {
                    methodLangId = InputMethodImport.guessLanguage(conversion.method)
                    confirmMethod = conversion.method
                }
            }
        }
    }

    // The layout the editor was last opened on. A grid takes a while to build,
    // and coming out of one to hunt for it again in a list of every layout you
    // have made and every shipped one you have on is the sort of small tax that
    // makes an editor tiring to use.
    val returnTo = remember { ReturnAnchor.take(KEYMAPS_ANCHOR) }
    // What this screen lists and nothing more: the shipped layouts that are
    // switched on, in shipped order, then the user's own grids. Not the whole
    // catalogue — that is over sixteen hundred layouts, and the JSON ones are
    // only parsed when something asks for them (see AssetLayouts).
    // What decides which rows the groups hold; each row reads its own state.
    val enabledIds = settings.watch { it.enabledLayoutIds }
    val customLayouts = settings.watch { it.customLayouts }
    // The switch on each row: the one place a layout you just made or imported
    // can be turned on without leaving the screen it was made on. Same gate
    // and same last-layout refusal as the cards under Languages.
    val toggle = rememberLayoutToggle(settings, repository, scope) {}
    val layouts = enabledIds
        .filter(::isShippedLayoutId)
        .distinct()
        .sortedBy(::shippedLayoutRank)
        .mapNotNull { findLayout(customLayouts, it) } +
        customLayouts.filter { !isShippedLayoutId(it.id) }
    val customIds = customLayouts.map { it.id }.toSet()
    // "Shipped" is both the compiled built-ins and the JSON asset layouts.
    // Testing only BuiltInLayouts put every asset layout in neither group — an
    // enabled Français BÉPO was invisible here — and made an *edit* of one look
    // like a layout of the user's own, offering Delete where it should offer
    // Reset. isShippedLayoutId treats the two the same way.
    val shippedIds = remember(layouts) {
        layouts.mapNotNullTo(HashSet()) { layout -> layout.id.takeIf(::isShippedLayoutId) }
    }
    // Layouts that arrived from an addon repository rather than from this
    // screen. They live under Languages → Your layouts, which is where the
    // switch that turns one on is, and are removed from the Addons screen.
    val addonStore = remember { AddonStore.get(context) }
    val addonRevision by addonStore.revision.collectAsStateWithLifecycle()
    val addonLayoutIds = remember(addonRevision) {
        addonStore.installed().values
            .filter { it.type == AddonType.Layout }
            .mapTo(HashSet()) { it.localRef }
    }


    /**
     * Copies a layout and opens the copy.
     *
     * Deliberately does not activate it, unlike the themes gallery, which
     * applies a duplicate before navigating. A layout owns delete, enter and
     * space, and a half-built copy becoming the live keyboard mid-edit is how
     * someone ends up unable to type well enough to undo it. Custom layouts go
     * live only from their toggle under Languages, and only once they validate.
     */
    /**
     * Opens the editor, and remembers the row to come back to. [layerKey] is a
     * typing layer's key or a panel's `layerKey`, for a row of the expanded
     * layer list; null opens on the letters, as the layout's own row does.
     */
    fun openEditor(id: String, layerKey: String? = null) {
        ReturnAnchor.arm(KEYMAPS_ANCHOR, id)
        onNavigate(if (layerKey == null) "keymap_edit/$id" else "keymap_edit/$id?layer=$layerKey")
    }

    // Which layouts have their layer list open (cinnabar777's suggestion on
    // #63): every sub-layout of a layout, a row each, right on this screen the
    // way the panel layouts are. Collapsed by default — thirteen rows a layout
    // would bury the list — and remembered across rotation, not across visits.
    var expandedLayouts by rememberSaveable { mutableStateOf(setOf<String>()) }
    fun toggleLayers(id: String) {
        expandedLayouts = if (id in expandedLayouts) expandedLayouts - id else expandedLayouts + id
    }

    fun duplicateAndEdit(base: LayoutSpec) {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val name = context.getString(R.string.layout_editor_duplicate_name_format, base.name)
            repository.upsertCustomLayout(base.copy(id = id, name = name))
            openEditor(id)
        }
    }

    /**
     * A new layout from a four-row skeleton rather than from a copy.
     *
     * The empty state said "Copy a layout below", which is fine advice and was
     * also the only route: building something that is not a rearranged QWERTY
     * meant duplicating one and deleting thirty keys first. The skeleton is the
     * default letters grid's shape with blank keys, so the row structure and
     * the bottom row are already right.
     */
    fun createBlankAndEdit() {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val name = context.getString(R.string.layout_editor_new_layout_name)
            val letters = BuiltInLayouts.default.compile(LayoutLayer.LETTERS)
            val blankRows = letters.rows.map { row ->
                row.map { key ->
                    // Only the plain character keys are emptied. Enter, shift,
                    // space and delete are what make the grid usable while it
                    // is being filled in, and nobody wants to re-add them.
                    if (key.action == KeyAction.Text) key.copy(label = "", output = "") else key
                }
            }
            repository.upsertCustomLayout(
                LayoutSpec(
                    id = id,
                    name = name,
                    layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(rows = blankRows)),
                ),
            )
            openEditor(id)
        }
    }

    /**
     * A new secondary layout (issue #62): a grid reached by a key or the
     * toolbar rather than by picking a language. Three rows of blank keys to
     * fill in, and an ABC key so there is a way back from the first save —
     * nothing else, because "from scratch" was the whole request.
     */
    fun createSecondaryAndEdit() {
        scope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val name = context.getString(R.string.layout_editor_new_secondary_name)
            val rows = List(SecondarySkeletonRows) { List(SecondarySkeletonColumns) { Key("", output = "") } } +
                listOf(listOf(Key("ABC", action = KeyAction.Letters, width = 1.5f)))
            repository.upsertCustomLayout(
                LayoutSpec(
                    id = id,
                    name = name,
                    secondary = true,
                    layers = mapOf(LayoutLayer.LETTERS.key to LayerSpec(rows = rows)),
                ),
            )
            openEditor(id)
        }
    }

    // Two kinds of grid can be made here, so the FAB opens a two-line menu
    // rather than guessing which one the press meant.
    RegisterFab {
        var open by remember { mutableStateOf(false) }
        Box {
            FloatingActionButton(onClick = { open = true }) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.layout_editor_new_layout_title))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layout_editor_new_layout_title)) },
                    onClick = { open = false; createBlankAndEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layout_editor_new_secondary_title)) },
                    onClick = { open = false; createSecondaryAndEdit() },
                )
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.layout_editor_your_layouts_title),
        info = stringResource(R.string.layout_editor_gallery_caption),
    ) {
        // Every grid the user made, on or off. Filtering to the enabled ones
        // made the two buttons on this very screen — Duplicate and Import —
        // produce a layout that then vanished from it: neither turns its result
        // on, so a copy opened the editor, and coming back said "No layouts of
        // your own yet". Whether a layout is on is a word in its subtitle, not
        // a reason to hide the thing you just made.
        //
        // Addon layouts are left out entirely. This group is "grids you made",
        // and an installed one is neither made here nor managed here — it is
        // switched on under Languages and removed from Addons, and editing it
        // would only produce changes the next update silently discards.
        //
        // Secondary layouts have a group of their own below: they are not
        // languages and cannot be switched on, so a row here promising an
        // on/off state would be wrong twice.
        val customs = layouts.filter {
            it.id in customIds &&
                it.id !in shippedIds &&
                it.id !in addonLayoutIds &&
                !it.secondary
        }
        if (customs.isEmpty()) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_editor_empty_title),
                    subtitle = stringResource(R.string.layout_editor_empty_subtitle),
                )
            }
        }
        for (layout in customs) {
            item(key = layout.id) {
                ScrollAnchor(layout.id == returnTo) {
                    LayoutRow(
                        layout = layout,
                        enabled = settings.watch { layout.id in it.enabledLayoutIds },
                        onToggle = { on -> toggle(layout.id, on) },
                        onEdit = { openEditor(layout.id) },
                        onExport = {
                            pendingExport = layout
                            exportLauncher.launch(LayoutFile.fileName(layout, System.currentTimeMillis()))
                        },
                        onDuplicate = { duplicateAndEdit(layout) },
                        onDelete = { confirmDelete = layout },
                        deleteIsReset = false,
                        onPublish = { publishing = layout },
                        expanded = layout.id in expandedLayouts,
                        onToggleLayers = { toggleLayers(layout.id) },
                    )
                }
            }
            if (layout.id in expandedLayouts) {
                item { LayerRows(layout) { key -> openEditor(layout.id, key) } }
            }
        }
    }

    // Grids reached by a key or the toolbar rather than by picking a language
    // (issue #62). Their own group because nothing about "on" applies to them.
    val secondaries = layouts.filter { it.id in customIds && it.id !in shippedIds && it.secondary }
    SettingsGroup(
        stringResource(R.string.layout_editor_secondary_title),
        info = stringResource(R.string.layout_editor_secondary_group_caption),
    ) {
        for (layout in secondaries) {
            item(key = layout.id) {
                ScrollAnchor(layout.id == returnTo) {
                    LayoutRow(
                        layout = layout,
                        enabled = false,
                        onEdit = { openEditor(layout.id) },
                        onExport = {
                            pendingExport = layout
                            exportLauncher.launch(LayoutFile.fileName(layout, System.currentTimeMillis()))
                        },
                        onDuplicate = { duplicateAndEdit(layout) },
                        onDelete = { confirmDelete = layout },
                        deleteIsReset = false,
                        onPublish = { publishing = layout },
                    )
                }
            }
        }
    }

    // The panels that are layouts now (issue #63), under the user's own grids:
    // one row per panel, edited in place, reset to the shipped one.
    val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
    PanelLayoutsGroup(customPanels, onNavigate)

    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_import_title),
                subtitle = stringResource(R.string.layout_editor_import_subtitle),
                leading = { Icon(Icons.Outlined.FileOpen, contentDescription = null) },
                onClick = {
                    importLauncher.launch(LayoutFile.IMPORT_MIME_TYPES)
                },
            )
        }
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.layout_editor_bundle_title),
                subtitle = stringResource(R.string.layout_editor_bundle_subtitle),
                leading = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                onClick = {
                    pendingBundle = layouts
                    bundleLauncher.launch(LayoutFile.bundleFileName(System.currentTimeMillis()))
                },
            )
        }
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.layout_editor_foreign_title),
                subtitle = stringResource(R.string.layout_editor_foreign_subtitle),
                leading = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                onClick = { foreignLauncher.launch(FOREIGN_LAYOUT_MIME_TYPES) },
            )
        }
        item(level = SettingLevel.ADVANCED) {
            WmRow(
                title = stringResource(R.string.layout_editor_im_title),
                subtitle = stringResource(R.string.layout_editor_im_subtitle),
                leading = { Icon(Icons.Outlined.KeyboardAlt, contentDescription = null) },
                enabled = !methodBusy,
                onClick = { methodLauncher.launch(InputMethodImport.MIME_TYPES) },
            )
        }
    }

    val builtIns = layouts.filter {
        it.id in shippedIds && it.id in enabledIds
    }
    if (builtIns.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.layout_editor_built_in_title)) {
            for (layout in builtIns) {
                item(key = layout.id) {
                    ScrollAnchor(layout.id == returnTo) {
                        LayoutRow(
                            layout = layout,
                            enabled = settings.watch { layout.id in it.enabledLayoutIds },
                            onToggle = { on -> toggle(layout.id, on) },
                            onEdit = { openEditor(layout.id) },
                            onExport = {
                                pendingExport = layout
                                exportLauncher.launch(LayoutFile.fileName(layout, System.currentTimeMillis()))
                            },
                            onDuplicate = { duplicateAndEdit(layout) },
                            // An edited built-in is stored as an override under the same
                            // id, so removing it restores the shipped grid rather than
                            // deleting anything — hence Reset, not Delete.
                            onDelete = if (layout.id in customIds) {
                                { confirmDelete = layout }
                            } else {
                                null
                            },
                            deleteIsReset = true,
                            expanded = layout.id in expandedLayouts,
                            onToggleLayers = { toggleLayers(layout.id) },
                        )
                    }
                }
                if (layout.id in expandedLayouts) {
                    item { LayerRows(layout) { key -> openEditor(layout.id, key) } }
                }
            }
        }
    }

    confirmImport?.let { imported ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = {
                Text(
                    stringResource(
                        R.string.layout_editor_import_confirm_title,
                        imported.layout.name,
                    ),
                )
            },
            text = {
                Column {
                    Text(stringResource(R.string.layout_editor_import_confirm_body))
                    if (imported.repairNotes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.layout_editor_import_changes_title),
                            fontWeight = FontWeight.Medium,
                        )
                        for (note in imported.repairNotes) {
                            Text(
                                stringResource(
                                    R.string.layout_editor_repair_note,
                                    note.format(context.resources),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // Two ways in: added and left off, or added and switched on in
                // the same breath, since "import, then go and find the switch"
                // was the step people got lost on.
                fun import(turnOn: Boolean) {
                    val id = "custom_${System.currentTimeMillis()}"
                    val name = imported.layout.name
                    scope.launch {
                        repository.upsertCustomLayout(imported.layout.copy(id = id))
                        if (turnOn) {
                            repository.setEnabledLayoutIds((settings.value.enabledLayoutIds + id).distinct())
                        }
                        message = context.getString(
                            if (turnOn) R.string.layout_editor_import_done_on_message
                            else R.string.layout_editor_import_done_message,
                            name,
                        )
                    }
                    confirmImport = null
                }
                Row {
                    TextButton(onClick = { import(turnOn = false) }) {
                        Text(stringResource(CommonR.string.common_import))
                    }
                    TextButton(onClick = { import(turnOn = true) }) {
                        Text(stringResource(R.string.layout_editor_import_and_enable))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    confirmBundle?.let { bundle ->
        AlertDialog(
            onDismissRequest = { confirmBundle = null },
            title = {
                Text(pluralStringResource(R.plurals.layout_editor_bundle_confirm_title, bundle.size, bundle.size))
            },
            text = {
                Column {
                    Text(stringResource(R.string.layout_editor_bundle_confirm_body))
                    Spacer(Modifier.height(8.dp))
                    for (imported in bundle) Text("• " + imported.layout.name)
                }
            },
            confirmButton = {
                // A layout keeps its id when nothing here has it yet, so an
                // "open a layout" key in one still opens the other. A clash
                // (a built-in, or the same file twice) gets a new id instead.
                fun import(turnOn: Boolean) {
                    scope.launch {
                        val taken = settings.value.customLayouts.mapTo(HashSet()) { it.id }
                        val now = System.currentTimeMillis()
                        val ids = bundle.mapIndexed { i, imported ->
                            val id = imported.layout.id.takeIf {
                                it.isNotBlank() && it !in taken && !isShippedLayoutId(it)
                            } ?: "custom_${now + i}"
                            repository.upsertCustomLayout(imported.layout.copy(id = id))
                            id to imported.layout.secondary
                        }
                        if (turnOn) {
                            val languages = ids.filterNot { it.second }.map { it.first }
                            repository.setEnabledLayoutIds((settings.value.enabledLayoutIds + languages).distinct())
                        }
                        message = context.resources.getQuantityString(
                            R.plurals.layout_editor_bundle_imported_message, bundle.size, bundle.size,
                        )
                    }
                    confirmBundle = null
                }
                Row {
                    TextButton(onClick = { import(turnOn = false) }) {
                        Text(stringResource(CommonR.string.common_import))
                    }
                    TextButton(onClick = { import(turnOn = true) }) {
                        Text(stringResource(R.string.layout_editor_import_and_enable))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmBundle = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    confirmMultiling?.let { layouts ->
        // The other file that holds several layouts: an XKB file, one per variant.
        val desktop = layouts.first().source == ForeignSource.XKB_SYMBOLS
        AlertDialog(
            onDismissRequest = { confirmMultiling = null },
            title = {
                Text(stringResource(if (desktop) R.string.import_desktop_title else R.string.import_multiling_title))
            },
            text = {
                Column {
                    Text(
                        pluralStringResource(
                            if (desktop) R.plurals.import_desktop_layouts_body else R.plurals.import_multiling_layouts_body,
                            layouts.size,
                            layouts.size,
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    for (layout in layouts) {
                        Text("• ${layout.layout.name} (${LanguageRegistry.byId(layout.guessedLangId).displayName})")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // One stamp for the batch with the position after it: a
                        // loop is faster than the clock, and two layouts saved
                        // under one id would leave only the second.
                        val stamp = System.currentTimeMillis()
                        layouts.forEachIndexed { index, converted ->
                            repository.upsertCustomLayout(
                                converted.withLanguage(converted.guessedLangId).copy(id = "custom_${stamp}_$index"),
                            )
                        }
                        message = context.resources.getQuantityString(
                            R.plurals.import_multiling_layouts_done,
                            layouts.size,
                            layouts.size,
                        )
                    }
                    confirmMultiling = null
                }) { Text(stringResource(CommonR.string.common_import)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmMultiling = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    confirmForeign?.let { converted ->
        AlertDialog(
            onDismissRequest = { confirmForeign = null },
            title = {
                Text(stringResource(R.string.layout_editor_foreign_confirm_title, converted.layout.name))
            },
            text = {
                Column {
                    Text(
                        stringResource(
                            when (converted.source) {
                                ForeignSource.FLORIS_JSON -> R.string.layout_editor_foreign_from_json
                                ForeignSource.HELIBOARD_TEXT -> R.string.layout_editor_foreign_from_text
                                ForeignSource.FUTO_YAML -> R.string.layout_editor_foreign_from_futo
                                ForeignSource.KEYMAN_TOUCH_LAYOUT ->
                                    R.string.layout_editor_foreign_from_keyman
                                ForeignSource.KEYS_CAFE -> R.string.layout_editor_foreign_from_keyscafe
                                ForeignSource.MULTILING -> R.string.layout_editor_foreign_from_multiling
                                ForeignSource.UNEXPECTED -> R.string.layout_editor_foreign_from_unexpected
                                ForeignSource.WINDOWS_KLC -> R.string.layout_editor_foreign_from_klc
                                ForeignSource.MAC_KEYLAYOUT -> R.string.layout_editor_foreign_from_keylayout
                                ForeignSource.XKB_SYMBOLS -> R.string.layout_editor_foreign_from_xkb
                            },
                        ),
                    )
                    Spacer(Modifier.height(8.dp))
                    // The language is a step rather than a guess applied
                    // silently: it decides the dictionary, the autocorrect, the
                    // script rules, dictation and how shift behaves, and no
                    // foreign layout file states one.
                    WmRow(
                        title = stringResource(R.string.layout_editor_foreign_language_title),
                        subtitle = LanguageRegistry.byId(foreignLangId).displayName,
                        onClick = { pickingLanguage = true },
                    )
                    if (converted.notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.layout_editor_import_changes_title),
                            fontWeight = FontWeight.Medium,
                        )
                        for (note in converted.notes) {
                            Text(
                                stringResource(
                                    R.string.layout_editor_repair_note,
                                    note.format(context.resources),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = "custom_${System.currentTimeMillis()}"
                    val name = converted.layout.name
                    val langId = foreignLangId
                    scope.launch {
                        // withLanguage is the only supported way out of a
                        // conversion, for the reason its own comment gives.
                        repository.upsertCustomLayout(converted.withLanguage(langId).copy(id = id))
                        message =
                            context.getString(R.string.layout_editor_import_done_message, name)
                    }
                    confirmForeign = null
                }) { Text(stringResource(CommonR.string.common_import)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForeign = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    confirmMethod?.let { method ->
        AlertDialog(
            onDismissRequest = { confirmMethod = null },
            title = {
                Text(stringResource(R.string.layout_editor_foreign_confirm_title, method.name))
            },
            text = {
                Column {
                    Text(InputMethodImport.summary(method).format(context.resources))
                    Spacer(Modifier.height(8.dp))
                    // As for a foreign layout: the language decides the
                    // dictionary, the script rules and how shift behaves. An
                    // m17n file names one; the others are guessed from the text.
                    WmRow(
                        title = stringResource(R.string.layout_editor_foreign_language_title),
                        subtitle = LanguageRegistry.byId(methodLangId).displayName,
                        onClick = { pickingMethodLanguage = true },
                    )
                    val notes = InputMethodImport.notes(method)
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.layout_editor_import_changes_title),
                            fontWeight = FontWeight.Medium,
                        )
                        for (note in notes) {
                            Text(
                                stringResource(
                                    R.string.layout_editor_repair_note,
                                    note.format(context.resources),
                                ),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // The same two ways in as a layout file: added and left off, or
                // added and switched on.
                fun import(turnOn: Boolean) {
                    val id = "custom_${System.currentTimeMillis()}"
                    val langId = methodLangId
                    confirmMethod = null
                    methodBusy = true
                    scope.launch {
                        val layout = withContext(Dispatchers.IO) {
                            InputMethodImport.install(context.filesDir, method, langId, id)
                        }
                        methodBusy = false
                        if (layout == null) {
                            message = context.getString(R.string.layout_editor_im_failed)
                            return@launch
                        }
                        repository.upsertCustomLayout(layout)
                        if (turnOn) {
                            repository.setEnabledLayoutIds((settings.value.enabledLayoutIds + id).distinct())
                        }
                        message = context.getString(
                            if (turnOn) R.string.layout_editor_import_done_on_message
                            else R.string.layout_editor_import_done_message,
                            layout.name,
                        )
                    }
                }
                Row {
                    TextButton(onClick = { import(turnOn = false) }) {
                        Text(stringResource(CommonR.string.common_import))
                    }
                    TextButton(onClick = { import(turnOn = true) }) {
                        Text(stringResource(R.string.layout_editor_import_and_enable))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmMethod = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }

    // A Rime dictionary with its vocabulary is half a million rows, which
    // takes a while to read and to write; a row that only greys out would look
    // like a press that did nothing.
    if (methodBusy) {
        AlertDialog(
            onDismissRequest = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(R.string.layout_editor_im_busy))
                }
            },
            confirmButton = {},
        )
    }

    if (pickingMethodLanguage) {
        ForeignLanguageDialog(
            selected = methodLangId,
            onPick = {
                methodLangId = it
                pickingMethodLanguage = false
            },
            onDismiss = { pickingMethodLanguage = false },
        )
    }

    if (pickingLanguage) {
        ForeignLanguageDialog(
            selected = foreignLangId,
            onPick = {
                foreignLangId = it
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    confirmDelete?.let { layout ->
        val reset = layout.id in shippedIds
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(
                    if (reset) {
                        stringResource(R.string.layout_editor_reset_confirm_title, layout.name)
                    } else {
                        stringResource(R.string.layout_editor_delete_confirm_title, layout.name)
                    },
                )
            },
            text = {
                Text(
                    if (reset) {
                        stringResource(R.string.layout_editor_reset_confirm_body)
                    } else {
                        stringResource(R.string.layout_editor_delete_confirm_body)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val others = customLayouts
                    scope.launch {
                        repository.deleteCustomLayout(layout.id)
                        // An imported input method's data goes with the last
                        // layout that types it.
                        withContext(Dispatchers.IO) { InputMethodImport.forget(context.filesDir, layout, others) }
                    }
                    confirmDelete = null
                }) {
                    Text(
                        stringResource(
                            if (reset) CommonR.string.common_reset else CommonR.string.common_delete,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun LayoutRow(
    layout: LayoutSpec,
    enabled: Boolean,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: (() -> Unit)?,
    deleteIsReset: Boolean,
    /** Whether the layer list under this row is open; null for a layout with one grid. */
    expanded: Boolean? = null,
    onToggleLayers: () -> Unit = {},
    /**
     * Switches the layout on or off from its row (the answer is whether the
     * switch flipped; the last layout on refuses). Null draws no switch, for a
     * secondary layout, which cannot be switched on at all.
     */
    onToggle: ((Boolean) -> Boolean)? = null,
    /** Opens the community publish dialog; null for a shipped layout, which is not the user's to publish. */
    onPublish: (() -> Unit)? = null,
) {
    val resources = LocalContext.current.resources
    val exportDesc = stringResource(R.string.layout_editor_export_desc, layout.name)
    val duplicateDesc = stringResource(R.string.layout_editor_duplicate_desc, layout.name)
    val deleteDesc = if (deleteIsReset) {
        stringResource(R.string.layout_editor_reset_desc, layout.name)
    } else {
        stringResource(R.string.layout_editor_delete_desc, layout.name)
    }
    WmRow(
        title = layout.name,
        subtitle = layoutSummary(resources, layout, enabled),
        swipeActions = buildList {
            add(SwipeAction(Icons.Outlined.Share, exportDesc, onClick = onExport))
            add(SwipeAction(Icons.Outlined.ContentCopy, duplicateDesc, onClick = onDuplicate))
            if (onDelete != null) {
                add(
                    if (deleteIsReset) SwipeAction(Icons.Outlined.Refresh, deleteDesc, onClick = onDelete)
                    else deleteSwipeAction(deleteDesc, onDelete),
                )
            }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggle != null && !layout.secondary) {
                    val switchDesc = stringResource(R.string.layout_editor_row_switch_desc, layout.name)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggle(it) },
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .semantics { contentDescription = switchDesc },
                    )
                }
                if (expanded != null) {
                    IconButton(onClick = onToggleLayers) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = stringResource(
                                if (expanded) R.string.layout_editor_layers_collapse_desc
                                else R.string.layout_editor_layers_expand_desc,
                                layout.name,
                            ),
                        )
                    }
                }
                // Export, duplicate and delete share one menu: as three more
                // buttons beside the switch they left the name a column a few
                // letters wide on a phone. The row's swipe has them too.
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = stringResource(R.string.layout_editor_more_desc, layout.name),
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(exportDesc) },
                            leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                            onClick = { menu = false; onExport() },
                        )
                        DropdownMenuItem(
                            text = { Text(duplicateDesc) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        if (onPublish != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.community_publish_layout_desc, layout.name)) },
                                leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
                                onClick = { menu = false; onPublish() },
                            )
                        }
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(deleteDesc) },
                                leadingIcon = {
                                    Icon(
                                        if (deleteIsReset) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                                        contentDescription = null,
                                    )
                                },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                }
            }
        },
        onClick = onEdit,
    )
}

/**
 * The layers of one layout, as rows under it in the gallery: the nine typing
 * layers, then every panel. A pencil marks a layer this layout has authored;
 * the rest say Standard, which is the shipped grid (or, for a panel, the shared
 * panel layout). Tapping a row opens the editor on that tab.
 */
@Composable
private fun LayerRows(layout: LayoutSpec, onOpen: (String) -> Unit) {
    Column {
        for (layer in LayoutLayer.entries) {
            LayerRow(
                title = stringResource(layerTitleRes(layer)),
                authored = layout.layer(layer) != null,
                onClick = { onOpen(layer.key) },
            )
        }
        for (kind in PanelKind.entries.filter { it.shipped }) {
            LayerRow(
                title = stringResource(panelTitleRes(kind)),
                authored = layout.panelLayer(kind) != null,
                onClick = { onOpen(kind.layerKey) },
            )
        }
    }
}

@Composable
private fun LayerRow(title: String, authored: Boolean, onClick: () -> Unit) {
    WmRow(
        title = title,
        modifier = Modifier.padding(start = 24.dp),
        subtitle = stringResource(
            if (authored) R.string.panel_layout_value_custom else R.string.panel_layout_value_default,
        ),
        leading = {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = if (authored) {
                    stringResource(R.string.layout_editor_customised_desc)
                } else {
                    null
                },
                modifier = Modifier.size(18.dp),
                tint = if (authored) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                },
            )
        },
        onClick = onClick,
    )
}

/**
 * One line describing a layout: its language, its shape, and whether it is on.
 *
 * Takes [resources] rather than reading a string itself: the parts are counted
 * words, so each one needs the plural rule of the language on the device now.
 */
internal fun layoutSummary(resources: Resources, layout: LayoutSpec, enabled: Boolean): String {
    val letters = layout.compile(LayoutLayer.LETTERS).rows
    val keyTotal = letters.sumOf { it.size }
    val extras = layout.layers.keys.count { it != LayoutLayer.LETTERS.key }
    // A secondary layout has no on/off and no language of its own; what it
    // does have worth a word is whether it outlives the keyboard closing.
    val parts = if (layout.secondary) {
        mutableListOf(resources.getString(R.string.layout_editor_secondary_summary_label))
            .apply {
                if (layout.layer(LayoutLayer.LETTERS)?.persistent == true) {
                    add(resources.getString(R.string.layout_editor_persistent_summary_label))
                }
            }
    } else {
        mutableListOf(
            resources.getString(if (enabled) CommonR.string.common_on else CommonR.string.common_off),
            baseModeTitle(layout),
        )
    }
    parts += resources.getQuantityString(
        R.plurals.layout_editor_row_count,
        letters.size,
        letters.size,
    )
    parts += resources.getQuantityString(R.plurals.layout_editor_key_count, keyTotal, keyTotal)
    if (extras > 0 && !layout.secondary) {
        parts += resources.getQuantityString(
            R.plurals.layout_editor_custom_layer_count,
            extras,
            extras,
        )
    }
    return parts.joinToString(" · ")
}

/**
 * The catalog's name for a mode, so the subtitle tracks a renamed catalog entry
 * rather than duplicating it.
 */
internal fun baseModeTitle(layout: LayoutSpec): String =
    "${layout.language().displayName} · ${layout.name}"

/**
 * The check that has to pass before a layout may be switched on, as a function
 * the toggles call.
 *
 * The editor has always told the user "You must fix this before you turn this
 * layout on" under every blocking finding, and nothing anywhere enforced it —
 * [canBeEnabled] existed and was called only from tests, so a layout with no
 * delete key, no way back off its symbols layer, or keys from a newer build
 * turned on with no warning at all. Repairing at draw time keeps such a layout
 * *typeable*, but that is a backstop, not permission: the grid the user then
 * types on is not the one they built, and nothing said so.
 *
 * Returns a gate: call it with the layout id and what to do if it passes. A
 * blocking layout opens a dialog naming every reason instead, which is the same
 * list the editor shows, so the two can never disagree.
 */
@Composable
internal fun rememberLayoutEnableGate(
    settings: LiveSettings,
): (String, () -> Unit) -> Unit {
    val resources = LocalContext.current.resources
    var blocked by remember { mutableStateOf<Pair<String, List<LayoutMessage>>?>(null) }

    blocked?.let { (name, reasons) ->
        AlertDialog(
            onDismissRequest = { blocked = null },
            title = { Text(stringResource(R.string.layout_editor_cannot_enable_title, name)) },
            text = {
                Column {
                    Text(stringResource(R.string.layout_editor_cannot_enable_body))
                    Spacer(Modifier.height(8.dp))
                    for (reason in reasons) {
                        Text(
                            stringResource(
                                R.string.layout_editor_repair_note,
                                reason.format(resources),
                            ),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { blocked = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    return { layoutId, enable ->
        val spec = resolveLayout(settings.value.customLayouts, layoutId)
        val reasons = validateLayout(spec)
            .filter { it.severity == LayoutSeverity.BLOCKING }
            .map { it.text }
        if (reasons.isEmpty()) enable() else blocked = spec.name to reasons
    }
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

/** Row and column address of one key in the layer being edited. */
internal data class KeyRef(val row: Int, val col: Int)

/**
 * [rows] with the key at [from] taken out and put back at [to] — the one place
 * a key changes seat, whether an arrow in the sheet or a drag moved it.
 *
 * Guarded rather than trusting its caller. The address is read from this
 * composition's copy of the grid while the transform runs against the store's,
 * which can be a keystroke ahead, so an address that no longer holds a key
 * leaves the grid exactly as it was instead of moving whichever key took its
 * place. Remove-then-insert on one working copy, so no path through here can
 * duplicate a key or lose one.
 */
internal fun moveKeyIn(rows: List<List<Key>>, from: KeyRef, to: KeyRef): List<List<Key>> {
    val key = rows.getOrNull(from.row)?.getOrNull(from.col) ?: return rows
    if (to.row !in rows.indices) return rows
    val out = rows.map { it.toMutableList() }
    out[from.row].removeAt(from.col)
    // The target row is measured *after* the removal, which is what makes the
    // last gap of the key's own row reachable and keeps the index in range.
    out[to.row].add(to.col.coerceIn(0, out[to.row].size), key)
    return out.map { it.toList() }
}

/**
 * Where the key at [from] ends up when it is dropped into the gap that sits
 * before key [gap].col of row [gap].row.
 *
 * A gap is counted against the row as it stands, with the key still in it, so a
 * move rightwards within one row comes back a place: taking the key out first
 * shifts every later gap down one. A gap in another row, or one before the key
 * itself, is already the answer.
 */
internal fun dropLanding(from: KeyRef, gap: KeyRef): KeyRef =
    if (gap.col != NewRowCol && gap.row == from.row && gap.col > from.col) KeyRef(gap.row, gap.col - 1) else gap

/**
 * Where the key at [from] lands when it is pushed [delta] rows up or down, or
 * null at the top and the bottom of the grid.
 *
 * It keeps its column where the row it arrives in is long enough to have one,
 * and joins the end of a shorter row.
 */
internal fun rowMoveTarget(rows: List<List<Key>>, from: KeyRef, delta: Int): KeyRef? {
    val toRow = from.row + delta
    if (toRow !in rows.indices) return null
    return KeyRef(toRow, from.col.coerceAtMost(rows[toRow].size))
}

/**
 * How much of the window the pinned preview may take while it is drawn at the
 * user's real key height. Past this it scrolls inside itself, so a tall key
 * height cannot leave the controls below it off screen.
 */
private const val ActualSizePreviewShare = 0.55f

/**
 * Undo holds whole layouts rather than diffs. A layout is a few kB of data
 * classes, thirty of them cost nothing, and a diff type would need an inverse
 * for every edit the sheet can make — which is exactly the list that keeps
 * growing as actions gain payloads.
 */
internal const val UndoDepth = 30

/**
 * The Bottom row settings as the keyboard applies them to [layer] of a phone
 * layout, so the editor draws the grid the keyboard draws (issue #420).
 *
 * The same exceptions `currentLayout` makes, from the same facts: a secondary
 * layout keeps its 🌐 key where its author put it, only the letters take the
 * comma's emoji key, and the number pads are drawn as they are, since a
 * numeric field shows its pad before any of this runs.
 */
internal fun editorBottomRowRules(
    settings: KeyboardSettings,
    layer: LayoutLayer,
    secondary: Boolean,
): BottomRowRules = if (!layer.isCycled) {
    BottomRowRules()
} else {
    BottomRowRules(
        hideGlobe = !settings.showGlobeKey && !secondary,
        globeInOnePlace = settings.layoutBehavior.globeInOnePlace && !secondary,
        swapCommaAndGlobe = settings.swapCommaAndGlobe,
        globeAsEmoji = settings.globeAsEmoji,
        commaAsEmoji = settings.commaAsEmoji && layer == LayoutLayer.LETTERS && !secondary,
    )
}

/** The setting that each [BottomRowRule] is, by the title its row carries. */
@StringRes
private fun BottomRowRule.titleRes(): Int = when (this) {
    BottomRowRule.HIDE_GLOBE -> R.string.layout_show_globe_title
    BottomRowRule.GLOBE_IN_ONE_PLACE -> R.string.layout_globe_in_one_place_title
    BottomRowRule.SWAP_COMMA_AND_GLOBE -> R.string.layout_swap_comma_globe_title
    BottomRowRule.GLOBE_AS_EMOJI -> R.string.layout_globe_emoji_title
    BottomRowRule.COMMA_AS_EMOJI -> R.string.layout_comma_emoji_title
}

/** What a row reads for the instant between its layout being deleted and the editor closing. */
private val GoneLayout = LayoutSpec(id = "", name = "")

/**
 * The layout the key layout editor has open, read a field at a time.
 *
 * Every edit rewrites the stored layout, so read as one object it was new on
 * every write and every row of the editor recomposed to find its own field
 * unchanged. Through [watch] a row recomposes when what it draws changes and
 * not otherwise.
 */
@Stable
private class OpenLayout(private val settings: LiveSettings, val id: String) {
    /** The layout as stored now, for a write or an undo step; null once deleted. */
    fun now(): LayoutSpec? = findLayout(settings.value.customLayouts, id)

    /** [pick] of the layout, recomposing the caller only when that changes. */
    @Composable
    fun <R> watch(pick: (LayoutSpec) -> R): R =
        settings.watch { pick(findLayout(it.customLayouts, id) ?: GoneLayout) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun KeyLayoutEditorScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    layoutId: String,
    /**
     * The tab to open on: a typing layer's key or a panel's `layerKey`, from a
     * row of the gallery's layer list. Null or unknown opens on the letters.
     */
    initialLayer: String? = null,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Only whether the layout is there is read here. What decides the screen's
    // structure is read below, a piece at a time, and every row reads its own
    // field of the layout through [edited].
    val found = settings.watch { findLayout(it.customLayouts, layoutId) != null }
    if (!found) {
        Text(
            stringResource(R.string.layout_editor_missing_layout_message),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // A secondary layout (issue #62) is one grid: no language, no layer chips,
    // no tablet widening, and it is never "on" — a key or the toolbar shows it.
    val edited = remember(settings, layoutId) { OpenLayout(settings, layoutId) }
    val secondary = edited.watch { it.secondary }
    var layer by rememberSaveable(layoutId) {
        mutableStateOf(LayoutLayer.entries.firstOrNull { it.key == initialLayer } ?: LayoutLayer.LETTERS)
    }
    var selection by remember(layoutId, layer) { mutableStateOf<KeyRef?>(null) }
    // Issue #63, the per-layout half: the four panels are tabs of this editor
    // too. A panel tab set aside the typing layer; the grid, its selection and
    // its sheet are then the panel's, edited into this layout's own copy of
    // the panel grid over the user's shared one.
    var panelTab by rememberSaveable(layoutId) {
        mutableStateOf(initialLayer?.let { panelKindForLayerKey(it) }?.takeIf { it.shipped })
    }
    var panelSelection by remember(layoutId, panelTab) { mutableStateOf<KeyRef?>(null) }
    val customPanels by repository.customPanelLayouts.collectAsStateWithLifecycle(emptyList())
    var showShift by rememberSaveable(layoutId) { mutableStateOf(false) }
    // Draw the preview at the user's real key height instead of the clamped
    // one. Off by default because a tall setting pushes the grid off screen.
    var actualSize by rememberSaveable(layoutId) { mutableStateOf(false) }
    // A key on one of the rows drawn around the grid (issue #769), which
    // [selection] cannot address: those rows are not rows of the layer.
    var extraSel by remember(layoutId, layer) { mutableStateOf<ExtraRef?>(null) }
    // The kind of field the preview draws the keyboard for (issue #769).
    var previewField by rememberSaveable(layoutId) { mutableStateOf(EditorPreviewField.TEXT) }
    // Several keys picked at once (issue #769), in the grid's own coordinates;
    // null while the grid picks one key at a time.
    var multi by remember(layoutId, layer) { mutableStateOf<Set<KeyRef>?>(null) }
    // Where the selection goes once an edit that moves the selected keys comes
    // back from the store (issue #769). Moved at once, the selection pointed at
    // the moved key's new seat while the grid on screen still had its
    // neighbour there, and the key's settings filled with the neighbour's.
    var pendingSelect by remember(layoutId, layer) { mutableStateOf<PendingSelect?>(null) }
    val specNow = edited.watch { it }
    pendingSelect?.let { pending ->
        if (specNow != pending.before) {
            pendingSelect = null
            pending.onLanded()
        }
    }
    // An edit that changed nothing never comes back; let the selection go.
    LaunchedEffect(pendingSelect) {
        if (pendingSelect != null) {
            kotlinx.coroutines.delay(PendingSelectTimeoutMs)
            pendingSelect = null
        }
    }
    /** Runs [then] once the edit just sent has reached the layout on screen. */
    fun selectAfterEdit(then: () -> Unit) {
        pendingSelect = PendingSelect(specNow, then)
    }
    // What a row pressed below has to say: a copied layer or key, a paste.
    var message by remember(layoutId) { mutableStateOf<String?>(null) }

    // Session-scoped on purpose. Persisting it would mean a second serialized
    // document per layout, for a benefit — "undo what I did last Tuesday" —
    // that nobody expects from an editor.
    var undo by remember(layoutId) { mutableStateOf(emptyList<LayoutSpec>()) }
    var redo by remember(layoutId) { mutableStateOf(emptyList<LayoutSpec>()) }
    var stepPushed by remember(layoutId) { mutableStateOf(false) }

    // Editing an inherited layer authors this layout's own copy of it. Until
    // then the grid shows the built-in, which is what makes "replaces
    // everything" survivable: moving one letter must not cost you a phone pad.
    fun withLayerRows(spec: LayoutSpec, rows: List<List<Key>>): LayoutSpec {
        val existing = spec.layer(layer) ?: LayerSpec(rows)
        return spec.copy(layers = spec.layers + (layer.key to existing.copy(rows = rows)))
    }

    /**
     * The layer an edit starts from: this layout's own, the shipped one it
     * inherits, or an empty grid.
     *
     * The empty case is Fn, the one layer nothing ships. `compile`'s fallback
     * chain ends at the default *letters* grid, so without this every edit path
     * on the Fn tab — add a row, reorder, touch a key — would author Fn as a
     * second copy of the alphabet.
     */
    fun baseLayerOf(spec: LayoutSpec): LayerSpec {
        spec.layer(layer)?.let { return it }
        if (BuiltInLayouts.default.layer(layer) == null) return LayerSpec(rows = emptyList())
        val compiled = spec.compile(layer)
        return LayerSpec(rows = compiled.rows, rowHeights = compiled.rowHeights)
    }

    fun push() {
        val current = edited.now() ?: return
        undo = (undo + current).takeLast(UndoDepth)
        redo = emptyList()
    }

    /** Restores a whole layout, for undo and redo. */
    fun save(next: LayoutSpec) {
        scope.launch { repository.upsertCustomLayout(next) }
    }

    /**
     * Applies an edit through the repository rather than to the layout held
     * here. This screen saves on every keystroke and its copy of the layout
     * comes from the settings flow, which lags the write it just made — so two
     * edits landing within a frame would see the same stale copy and the second
     * would undo the first.
     */
    fun apply(transform: (LayoutSpec) -> LayoutSpec) {
        scope.launch { repository.updateCustomLayout(layoutId, transform) }
    }

    /** One discrete edit: one undo step. */
    fun edit(transform: (LayoutSpec) -> LayoutSpec) {
        push()
        stepPushed = true
        apply(transform)
    }

    /**
     * An edit inside one key-sheet session. Pushing per keystroke would flush
     * thirty slots typing "https://", and "undo my edit to that key" is the step
     * users actually have in mind.
     */
    fun editCoalesced(transform: (LayoutSpec) -> LayoutSpec) {
        if (!stepPushed) push()
        stepPushed = true
        apply(transform)
    }

    // Every transform below derives its rows from the spec it is handed, never
    // from the copy this composition is holding, for the staleness reason above.
    fun editRows(transform: (List<List<Key>>) -> List<List<Key>>) {
        edit { spec -> withLayerRows(spec, transform(baseLayerOf(spec).rows)) }
    }

    /**
     * [spec] with this layer written down as the keyboard draws it, and marked
     * as laid out by hand so the Bottom row settings leave it there (#420).
     *
     * What an edit to a key those settings moved or changed goes through first.
     * The edit is made on the grid the user is looking at, so it has to land on
     * that grid: written into the row as authored, the settings would move it
     * again, and the key the user just placed would jump somewhere else. A
     * layer already laid out by hand, or one the settings do not change, comes
     * back as it is, so a second edit a frame later does no harm.
     */
    fun laidOutAsDrawn(spec: LayoutSpec): LayoutSpec {
        val base = baseLayerOf(spec)
        if (base.bottomRowAsLaidOut || base.rows.isEmpty()) return spec
        val arranged = spec.compile(layer).arrangedBy(editorBottomRowRules(settings.value, layer, spec.secondary))
        if (arranged.applied.isEmpty()) return spec
        val laidOut = base.copy(rows = arranged.layout.rows, bottomRowAsLaidOut = true)
        return spec.copy(layers = spec.layers + (layer.key to laidOut))
    }

    /**
     * [spec] with this layer handed back to the Bottom row settings: the flag
     * off, and, where the layer is nothing but the inherited grid written down
     * as drawn, the layer copy dropped (#769). Turning the switch on and off
     * again on a layer nobody had otherwise touched used to leave that copy
     * behind, a grid the settings no longer recognised as theirs to change.
     */
    fun notLaidOut(spec: LayoutSpec): LayoutSpec {
        val own = spec.layer(layer) ?: return spec
        val cleared = own.copy(bottomRowAsLaidOut = false)
        val without = spec.copy(layers = spec.layers - layer.key)
        if (BuiltInLayouts.default.layer(layer) != null) {
            val inherited = without.compile(layer)
            val drawn = inherited.arrangedBy(editorBottomRowRules(settings.value, layer, spec.secondary)).layout
            if (cleared == LayerSpec(rows = drawn.rows, rowHeights = inherited.rowHeights)) return without
        }
        return spec.copy(layers = spec.layers + (layer.key to cleared))
    }

    /** [editRows], on this layer as drawn when [asDrawn] says the edit needs it. */
    fun editDrawnRows(asDrawn: Boolean, transform: (List<List<Key>>) -> List<List<Key>>) {
        edit { spec ->
            val from = if (asDrawn) laidOutAsDrawn(spec) else spec
            withLayerRows(from, transform(baseLayerOf(from).rows))
        }
    }

    /** [editDrawnRows] for an edit that adds or drops rows, so the row heights follow them. */
    fun editDrawnLayer(asDrawn: Boolean, transform: (LayerSpec) -> LayerSpec) {
        edit { spec ->
            val from = if (asDrawn) laidOutAsDrawn(spec) else spec
            from.copy(layers = from.layers + (layer.key to transform(baseLayerOf(from))))
        }
    }

    // Whole-layer edit, so a structural change to the rows can keep the parallel
    // per-row heights aligned. Authoring an inherited layer copies the built-in's
    // compiled grid (heights and all) first.
    fun editLayer(transform: (LayerSpec) -> LayerSpec) {
        edit { spec -> spec.copy(layers = spec.layers + (layer.key to transform(baseLayerOf(spec)))) }
    }

    // Reindexes per-row heights by source row index so they follow the rows
    // through a reorder/duplicate/delete. Stays null while every row is the
    // default height, so untouched layouts never grow the field.
    fun pickHeights(heights: List<Float>?, sourceIndices: List<Int>): List<Float>? =
        heights?.let { h -> sourceIndices.map { h.getOrNull(it) ?: 1f } }

    // One drag of the row-height slider is one undo step, not one per frame it
    // emits. Same rule and same latch as the key sheet: everything done to one
    // selected row coalesces, and selecting another row starts a new step.
    fun editLayerCoalesced(transform: (LayerSpec) -> LayerSpec) {
        if (!stepPushed) push()
        stepPushed = true
        apply { spec -> spec.copy(layers = spec.layers + (layer.key to transform(baseLayerOf(spec)))) }
    }

    fun setRowHeight(rowIndex: Int, value: Float) {
        editLayerCoalesced { ls ->
            val list = MutableList(ls.rows.size) { ls.rowHeights?.getOrNull(it) ?: 1f }
            if (rowIndex in list.indices) list[rowIndex] = value
            ls.copy(rowHeights = if (list.all { it == 1f }) null else list.toList())
        }
    }

    // Fn is the one layer nothing ships, so `compile` runs off the end of its
    // fallback chain and hands back the *letters* grid. Drawn, that stand-in
    // invited a tap, and a tap on an unauthored layer authors it from whatever
    // the grid is showing — so one keystroke on the empty Fn tab wrote a second
    // copy of QWERTY into the Fn layer and took the template row away. An empty
    // grid here leaves "Add an Fn layer" as the only way in, which is what the
    // caption below already says it is.
    val panelKind = panelTab
    val sharedPanelGrid = panelKind?.let { resolvePanelLayout(it, customPanels).grid }
    // What the grid, its row tools and the captions around it are drawn from;
    // each row of the groups below reads its own field instead.
    val ownPanelGrid = edited.watch { spec -> panelKind?.let { spec.panelLayer(it) } }
    val panelGrid = panelKind?.let { ownPanelGrid ?: sharedPanelGrid }
    val appearance = edited.watch { it.appearance }
    val layoutThemeId = edited.watch { it.themeId }
    val keyHeightDp = settings.watch { it.keyHeightDp }
    val panelPreviewPair = if (panelKind != null && panelGrid != null) {
        remember(panelKind, panelGrid, appearance, keyHeightDp, actualSize, layoutThemeId) {
            // The panel's own theme, else the layout's: what the board draws.
            panelPreview(panelKind, panelGrid, appearance, keyHeightDp, actualSize, panelGrid.themeId ?: layoutThemeId)
        }
    } else {
        null
    }

    val hasOwnLayer = edited.watch { it.layer(layer) != null }
    val layerHasFlicks = edited.watch { it.layer(layer)?.rows.orEmpty().any { row -> row.any { k -> k.hasFlicks() } } }
    val compiled = edited.watch { spec ->
        val inheritable = spec.layer(layer) != null || BuiltInLayouts.default.layer(layer) != null
        if (inheritable) {
            spec.compile(layer)
        } else {
            KeyboardLayout(name = "$layoutId/${layer.key}", rows = emptyList())
        }
    }
    val rows = compiled.rows
    val rowHeights = compiled.rowHeights
    // Issue #420: the grid as the keyboard draws it, the Bottom row settings
    // applied. The preview shows this one, and selection and every edit address
    // it; [arranged] says where each drawn key is in the grid as stored.
    val bottomRules = settings.watch { editorBottomRowRules(it, layer, secondary) }
    val arranged = remember(compiled, bottomRules) { compiled.arrangedBy(bottomRules) }
    val drawnRows = arranged.layout.rows
    val laidOut = compiled.bottomRowAsLaidOut
    val selectedKey = selection?.let { drawnRows.getOrNull(it.row)?.getOrNull(it.col) }

    /** Where a key drawn at [ref] is stored. */
    fun storedRef(ref: KeyRef): KeyRef = KeyRef(ref.row, arranged.sourceColumn(ref.row, ref.col) ?: ref.col)

    // Issue #769: the layer as the keyboard draws it, through the keyboard's
    // own pass over it, in the field the preview is set to. The preview draws
    // these keys, so the corner hints, the popups the key section lists and an
    // email field's @ are the board's own rather than the editor's guess.
    val fullSettings = settings.watch { it }
    val board = remember(fullSettings, specNow, layer, compiled, previewField, panelKind) {
        if (panelKind != null) null else editorBoardView(fullSettings, specNow, layer, compiled, previewField)
    }

    // Issue #273, then #769: the rows the keyboard draws around this grid
    // rather than in it, drawn in the preview where the keyboard draws them
    // and edited there. They used to be two grids of their own at the foot of
    // the page, which nobody found: a number row switched on in the settings
    // was missing from the preview, and was added by hand a second time. Not
    // on an unauthored Fn layer: there is no grid there to sit above, and the
    // first edit would author an empty one.
    val extraRows = panelKind == null && !secondary && layer.isCycled &&
        (layer != LayoutLayer.FN || hasOwnLayer)
    val storedNumberRow = edited.watch { it.layer(layer)?.numberRow }
    val storedFillRow = edited.watch { it.layer(layer)?.fillRow }
    val storedArrowRow = edited.watch { it.layer(layer)?.arrowRow }
    val arrowOrder = settings.watch { it.layoutBehavior.arrowRowOrder }
    val defaultArrowRow = remember(arrowOrder) { arrowOrder.map(::arrowRowKey) }
    fun extraDefault(band: ExtraBand): List<Key> = when (band) {
        ExtraBand.NUMBER -> BuiltInLayouts.defaultNumberRow(layer)
        ExtraBand.FILL -> BuiltInLayouts.SYMBOLS_FILL_ROW
        ExtraBand.ARROW -> defaultArrowRow
    }
    fun storedExtra(band: ExtraBand): List<Key>? = when (band) {
        ExtraBand.NUMBER -> storedNumberRow
        ExtraBand.FILL -> storedFillRow
        ExtraBand.ARROW -> storedArrowRow
    }
    val shownBands = buildSet {
        if (extraRows && board?.numberRowShown == true) add(ExtraBand.NUMBER)
        if (extraRows && board?.fillRowShown == true && drawnRows.isNotEmpty()) add(ExtraBand.FILL)
        if (extraRows && board?.arrowRowShown == true) add(ExtraBand.ARROW)
    }
    fun bandRow(band: ExtraBand): List<Key>? = if (band in shownBands) storedExtra(band) ?: extraDefault(band) else null
    val numberRowKeys = bandRow(ExtraBand.NUMBER)
    val fillRowKeys = bandRow(ExtraBand.FILL)
    val arrowRowKeys = bandRow(ExtraBand.ARROW)
    // The board's keys where its row lines up with the editor's, which is
    // every row but one the symbols pages' 1234 key was added to.
    val shownMain = drawnRows.mapIndexed { r, row ->
        board?.rows?.getOrNull(r)?.takeIf { it.size == row.size } ?: row
    }.let { main -> if (fillRowKeys != null) listOf(fillRowKeys) + main.drop(1) else main }
    val topOffset = if (numberRowKeys != null) 1 else 0
    val displayRows = listOfNotNull(numberRowKeys) + shownMain + listOfNotNull(arrowRowKeys)
    val numberRowHeightDp = settings.watch { it.numberRowHeightDp }
    val extraScale = numberRowHeightDp.toFloat() / keyHeightDp.coerceAtLeast(1)
    val displayHeights = if (numberRowKeys == null && arrowRowKeys == null) {
        rowHeights
    } else {
        listOfNotNull(extraScale.takeIf { numberRowKeys != null }) +
            List(drawnRows.size) { rowHeights?.getOrNull(it) ?: 1f } +
            listOfNotNull(extraScale.takeIf { arrowRowKeys != null })
    }
    val displayLayout = arranged.layout.copy(rows = displayRows, rowHeights = displayHeights)

    /** Which row of the layer, or which row around it, a cell of the preview is on. */
    fun fromDisplay(ref: KeyRef): Pair<ExtraRef?, KeyRef?> = when {
        numberRowKeys != null && ref.row == 0 -> ExtraRef(ExtraBand.NUMBER, ref.col) to null
        arrowRowKeys != null && ref.row == displayRows.lastIndex -> ExtraRef(ExtraBand.ARROW, ref.col) to null
        fillRowKeys != null && ref.row == topOffset -> ExtraRef(ExtraBand.FILL, ref.col) to null
        else -> null to KeyRef(ref.row - topOffset, ref.col)
    }
    val shownExtraSel = extraSel?.takeIf { it.band in shownBands }
    val displaySelection = shownExtraSel?.let { sel ->
        when (sel.band) {
            ExtraBand.NUMBER -> KeyRef(0, sel.col)
            ExtraBand.FILL -> KeyRef(topOffset, sel.col)
            ExtraBand.ARROW -> KeyRef(displayRows.lastIndex, sel.col)
        }
    } ?: selection?.let { KeyRef(it.row + topOffset, it.col) }

    fun editExtraRow(band: ExtraBand, transform: (List<Key>) -> List<Key>) {
        editLayer { it.withExtraRow(band, transform(it.extraRow(band) ?: extraDefault(band))) }
    }

    fun editExtraRowCoalesced(band: ExtraBand, transform: (List<Key>) -> List<Key>) {
        editLayerCoalesced { it.withExtraRow(band, transform(it.extraRow(band) ?: extraDefault(band))) }
    }

    // Putting a row back to the standard one also drops the layer copy the
    // first edit made, when nothing else in it differs from what it
    // inherits, so the layer follows the built-in grid again.
    fun resetExtraRow(band: ExtraBand) {
        edit { spec ->
            val current = spec.layer(layer) ?: return@edit spec
            val cleared = current.withExtraRow(band, null)
            val without = spec.copy(layers = spec.layers - layer.key)
            val inherited = without.compile(layer)
            if (cleared == LayerSpec(rows = inherited.rows, rowHeights = inherited.rowHeights)) {
                without
            } else {
                spec.copy(layers = spec.layers + (layer.key to cleared))
            }
        }
    }

    // An email box and a browser's address bar change keys on the letters
    // (issue #769): the comma, the full stop and enter. Drawn on request, so
    // the key that changes can be found and told to stay put. The chips ride
    // with the grid, above it; their caption is here.
    val fieldPreview = panelKind == null && !secondary && layer == LayoutLayer.LETTERS
    if (fieldPreview) {
        if (previewField != EditorPreviewField.TEXT) {
            CaptionText(
                stringResource(
                    if (previewField == EditorPreviewField.EMAIL) {
                        R.string.layout_editor_preview_field_email_caption
                    } else {
                        R.string.layout_editor_preview_field_web_caption
                    },
                ),
            )
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = undo.isNotEmpty(),
            onClick = {
                val previous = undo.last()
                undo = undo.dropLast(1)
                redo = redo + listOfNotNull(edited.now())
                stepPushed = false
                save(previous)
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Undo,
                contentDescription = stringResource(R.string.layout_editor_undo_desc),
            )
        }
        IconButton(
            enabled = redo.isNotEmpty(),
            onClick = {
                val next = redo.last()
                redo = redo.dropLast(1)
                undo = undo + listOfNotNull(edited.now())
                stepPushed = false
                save(next)
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Redo,
                contentDescription = stringResource(R.string.layout_editor_redo_desc),
            )
        }
        // Several keys at once (issue #769): to move them together, or to
        // set the same size or corner hint on all of them in one go.
        if (panelKind == null) {
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = multi != null,
                onClick = {
                    stepPushed = false
                    pendingSelect = null
                    if (multi == null) {
                        multi = setOfNotNull(selection?.takeIf { it.col >= 0 })
                        selection = null
                        extraSel = null
                    } else {
                        multi = null
                    }
                },
                label = { Text(stringResource(R.string.layout_editor_multi_chip)) },
                leadingIcon = if (multi != null) {
                    { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else {
                    null
                },
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.layout_editor_autosave_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The one line saying the keys can be pressed, while none is (issue #139):
    // once one is, the key's own section takes its place.
    if (multi?.isEmpty() == true) {
        CaptionText(stringResource(R.string.layout_editor_multi_caption))
    } else if (multi == null && selection == null && shownExtraSel == null &&
        (panelKind == null || panelSelection == null)
    ) {
        CaptionText(stringResource(R.string.layout_editor_drag_caption))
    }

    // Keys onto the clipboard and back (issue #769), whole: flicks, icon,
    // corner hint, alternates and size go with them, to another place in this
    // layer, another layer, or another layout.
    val keyClipLabel = stringResource(R.string.layout_editor_key_clip_label)
    val copyFailed = stringResource(R.string.layout_editor_copy_layer_error)
    val pasteKeysWrong = stringResource(R.string.layout_editor_paste_keys_wrong_clip_error)
    fun copyKeys(keys: List<Key>) {
        val text = KeyFile.encode(keys, BuildConfig.VERSION_CODE)
        message = if (putOnClipboard(context, keyClipLabel, text)) {
            context.resources.getQuantityString(R.plurals.layout_editor_copy_keys_done, keys.size, keys.size)
        } else {
            copyFailed
        }
    }
    /** The keys on the clipboard, or null after saying there are none. */
    fun clipKeys(): List<Key>? = clipboardText(context)?.let { KeyFile.decode(it) }.also {
        if (it == null) message = pasteKeysWrong
    }
    var pickingSpecialKey by remember(layoutId, layer) { mutableStateOf(false) }

    // A key dragged in the preview, dropped: one edit on whichever grid the tab
    // is showing, through the same helper the key section's arrows move a key
    // with. Landed here rather than in the grid because only this screen knows
    // which of them — the typing layer, a row around it, or this layout's copy
    // of a panel — the preview is drawing, and all go out through one undo stack.
    val onKeyDragged: (KeyRef, KeyRef) -> Unit = { fromCell, toCell ->
        val kind = panelKind
        if (kind == null) {
            val (fromExtra, from) = fromDisplay(fromCell)
            val (toExtra, to) = if (toCell.col == NewRowCol) null to null else fromDisplay(toCell)
            // The picked keys go together when the one dragged is among them
            // and they sit side by side in one row (issue #769).
            val run = multi?.let { picked -> from?.let { runContaining(picked, it) } }
            when {
                // Dropped between two rows, or above or below them all: a row
                // of its own there (issue #769).
                from != null && toCell.col == NewRowCol -> {
                    val keys = multi?.takeIf { from in it }?.sortedWith(compareBy({ it.row }, { it.col }))
                        ?: listOf(from)
                    val floor = if (fillRowKeys != null) 1 else 0
                    val at = (toCell.row - topOffset).coerceIn(floor, drawnRows.size)
                    editDrawnLayer(keys.any { arranged.rowChanged(it.row) }) { it.withKeysInNewRow(keys, at) }
                    extraSel = null
                    stepPushed = false
                    selectAfterEdit {
                        val landed = newRowLanding(drawnRows, keys, at)
                        if (multi != null) multi = landed.toSet() else selection = landed.firstOrNull()
                    }
                }
                from != null && to != null && run != null -> {
                    // The drop's gap, as the grid counted it before it took
                    // one key's place off for [dropLanding].
                    val gap = if (to.row == from.row && to.col >= from.col) to.col + 1 else to.col
                    val target = runDropTarget(run, KeyRef(to.row, gap))
                    if (target != null) {
                        editDrawnRows(arranged.rowChanged(run.row) || arranged.rowChanged(target.row)) {
                            moveRunIn(it, run, target)
                        }
                        stepPushed = false
                        selectAfterEdit { multi = run.movedTo(target).toSet() }
                    }
                }
                from != null && to != null -> {
                    // A move in a row the settings rearranged is made on the
                    // row as drawn, which is the only order the drop makes
                    // sense in (#420).
                    editDrawnRows(arranged.rowChanged(from.row) || arranged.rowChanged(to.row)) {
                        moveKeyIn(it, from, to)
                    }
                    extraSel = null
                    stepPushed = false
                    selectAfterEdit { if (multi == null) selection = to else multi = setOf(to) }
                }
                // Along one of the rows around the grid, never off it: those
                // rows are not rows of the layer, so a key cannot change seat
                // between them and the grid.
                fromExtra != null && toExtra != null && fromExtra.band == toExtra.band -> {
                    editExtraRow(fromExtra.band) { row ->
                        moveKeyIn(listOf(row), KeyRef(0, fromExtra.col), KeyRef(0, toExtra.col)).first()
                    }
                    selection = null
                    stepPushed = false
                    selectAfterEdit { extraSel = toExtra }
                }
                else -> Unit
            }
        } else {
            val shared = requireNotNull(sharedPanelGrid)
            edit { spec ->
                val base = spec.panelLayer(kind) ?: shared
                spec.copy(
                    layers = spec.layers +
                        (kind.layerKey to base.copy(rows = moveKeyIn(base.rows, fromCell, toCell))),
                )
            }
            stepPushed = false
            selectAfterEdit { panelSelection = toCell }
        }
    }

    // The grid stays under the bar while the controls further down scroll
    // (#43) — at actual size as well. It used to join the body there, on the
    // grounds that a real key height can be taller than the viewport, which
    // cost a scroll back up to the top after every edit. It keeps its place
    // under the bar now and takes a ceiling instead: over that it scrolls
    // within itself, and the page below never moves.
    // The layer tabs ride in the pinned strip with the grid, above it (issue
    // #769). On the page under a grid whose height changes with the layer,
    // the next tab moved out from under the finger every time one was picked.
    //
    // Not while the keyboard is up. Tabs, chips and grid take most of a phone's
    // height on their own, and with the keyboard under them the page below was
    // left no room at all: a key's Label field, once focused, sat behind the
    // keyboard, and the window panned up onto an empty grey strip where the
    // field and the key's settings should have been (#614). The grid comes
    // back the moment the keyboard goes.
    val imeUp = WindowInsets.isImeVisible
    val previewScale = rememberPreviewScale(PreviewScaleSlot.LAYOUT_EDITOR)
    if (!imeUp) RegisterPinned {
        Column {
            if (!secondary) {
                LayerChips(
                    edited = edited,
                    selected = layer,
                    selectedPanel = panelTab,
                    onSelect = { layer = it; panelTab = null; selection = null; extraSel = null },
                    onSelectPanel = { panelTab = it; selection = null; extraSel = null },
                )
            }
            if (fieldPreview) PreviewFieldChips(previewField) { previewField = it }
            val gridScroll = rememberScrollState()
            val ceiling = LocalConfiguration.current.screenHeightDp.dp * ActualSizePreviewShare
            // Grips under the grid's corners make it, keys, labels and all,
            // smaller (#967): laid out at a smaller density, so a key's drag
            // and the cells it reads back stay in one set of coordinates.
            ScalablePreview(
                scale = previewScale.floatValue,
                onScale = { previewScale.floatValue = it },
                onScaleSettled = {
                    previewScale.floatValue = it
                    storePreviewScale(context, PreviewScaleSlot.LAYOUT_EDITOR, it)
                },
            ) {
                Box(
                    modifier = if (actualSize) {
                        Modifier.heightIn(max = ceiling).verticalScroll(gridScroll)
                    } else {
                        Modifier
                    },
                ) {
                    DensityScaled(previewScale.floatValue) {
                        EditorGrid(
                            layout = panelPreviewPair?.first ?: displayLayout,
                            settings = settings,
                            selection = if (panelKind != null) panelSelection else displaySelection,
                            showShift = showShift && panelKind == null,
                            actualSize = actualSize,
                            onSelect = { cell ->
                                stepPushed = false
                                pendingSelect = null
                                if (panelKind != null) {
                                    panelSelection = cell
                                } else {
                                    val (extra, main) = fromDisplay(cell)
                                    val picked = multi
                                    if (picked != null) {
                                        // Picking several: a tap adds a key or takes it
                                        // back out. Only keys of the layer itself.
                                        if (main != null && main.col >= 0) {
                                            multi = if (main in picked) picked - main else picked + main
                                        }
                                    } else {
                                        extraSel = extra
                                        selection = main
                                    }
                                }
                            },
                            rowHeightsDp = panelPreviewPair?.second,
                            onKeyDragged = onKeyDragged,
                            enterSlot = board?.enterSlot ?: IconSlots.KEY_ENTER,
                            spaceText = board?.spaceText,
                            allowNewRow = panelKind == null,
                            multiSelection = if (panelKind == null) {
                                multi.orEmpty().mapTo(HashSet()) { KeyRef(it.row + topOffset, it.col) }
                            } else {
                                emptySet()
                            },
                        )
                    }
                }
            }
        }
    }

    if (panelKind != null) {
        val shared = requireNotNull(sharedPanelGrid)
        val panelName = stringResource(panelTitleRes(panelKind))
        // Every edit lands on this layout's own copy, forked from the shared
        // panel layout on the first touch — the same rule an inherited
        // symbols page follows — and through the same undo stack as the keys.
        fun panelBase(spec: LayoutSpec): LayerSpec = spec.panelLayer(panelKind) ?: shared
        if (ownPanelGrid == null) {
            CaptionText(
                stringResource(R.string.layout_editor_panel_inherited_caption, panelName),
            )
        }
        PanelEditorBody(
            kind = panelKind,
            grid = requireNotNull(panelGrid),
            selection = panelSelection,
            onSelectionChange = { panelSelection = it },
            actualSize = actualSize,
            onActualSizeChange = { actualSize = it },
            editGrid = { transform ->
                edit { spec -> spec.copy(layers = spec.layers + (panelKind.layerKey to transform(panelBase(spec)))) }
            },
            editGridCoalesced = { transform ->
                editCoalesced { spec ->
                    spec.copy(layers = spec.layers + (panelKind.layerKey to transform(panelBase(spec))))
                }
            },
            jsonRoute = "keymap_json/$layoutId",
            onNavigate = onNavigate,
            settings = settings,
            reset = ownPanelGrid?.let {
                ResetRow(
                    R.string.layout_editor_reset_panel_title,
                    stringResource(R.string.layout_editor_reset_panel_subtitle, panelName),
                ) {
                    edit { spec -> spec.copy(layers = spec.layers - panelKind.layerKey) }
                    panelSelection = null
                }
            },
        )
    }

    // The selected key's own settings, on the page under the pinned grid
    // (issue #769). They were a sheet pulled up over the page, which covered
    // the grid it was editing; here the key and the grid are on screen together.
    val popupColumns = settings.watch { it.popup.alternatesColumns }
    val popupNearestFirst = settings.watch { it.popup.alternatesNearestFirst }
    val secondaryLayoutList = secondaryLayouts(settings.watch { it.customLayouts })
    val keyboardModeList = settings.watch { it.keyboardModes }
    val ref = selection
    if (panelKind == null && ref != null && selectedKey != null) {
        // Issue #420: the section edits the key as drawn. One the Bottom row
        // settings moved or changed is written down as drawn first, row and
        // all, and the layer then keeps it; any other key is edited where it
        // is stored, and the settings go on arranging the row around it.
        val asDrawn = arranged.rewritten(ref.row, ref.col)
        val at = if (asDrawn) ref else storedRef(ref)
        KeyEditSheet(
            key = selectedKey,
            ref = ref,
            rowSize = drawnRows[ref.row].size,
            rowCount = drawnRows.size,
            gridWeight = gridWeightOf(drawnRows),
            // Counting the columns a spanning key above holds over this row, so
            // "Fill the row" offers the width that is genuinely left.
            otherWidthsInRow = spanRowWidths(drawnRows)[ref.row] - selectedKey.width,
            // A change to *one field* of the key, never a whole key built here.
            // The section's copy of the key comes from the settings flow, which
            // lags the write it just made, so a control that handed back
            // `key.copy(…)` was also handing back every other field as it stood
            // a frame or more ago: type a label, then nudge the width, and the
            // width edit wrote the pre-label key straight back over it. Applying
            // the change to the key inside the same store edit is the same rule
            // `updateCustomLayout` already follows for the layout as a whole.
            onChange = { change ->
                editCoalesced { spec ->
                    val from = if (asDrawn) laidOutAsDrawn(spec) else spec
                    withLayerRows(
                        from,
                        baseLayerOf(from).rows.mapIndexed { r, row ->
                            if (r != at.row) {
                                row
                            } else {
                                row.mapIndexed { c, k -> if (c == at.col) change(k) else k }
                            }
                        },
                    )
                }
            },
            onMove = { delta ->
                val target = ref.col + delta
                if (target in drawnRows[ref.row].indices) {
                    editDrawnRows(arranged.rowChanged(ref.row)) { r ->
                        r.mapIndexed { i, row ->
                            if (i != ref.row || ref.col !in row.indices || target !in row.indices) {
                                row
                            } else {
                                row.toMutableList().apply { add(target, removeAt(ref.col)) }
                            }
                        }
                    }
                    selectAfterEdit { selection = ref.copy(col = target) }
                }
            },
            onMoveRow = { delta ->
                // Through the same guarded helper the preview's drag lands
                // through, so the two ways of moving a key cannot disagree.
                rowMoveTarget(drawnRows, ref, delta)?.let { to ->
                    editDrawnRows(arranged.rowChanged(ref.row) || arranged.rowChanged(to.row)) {
                        moveKeyIn(it, ref, to)
                    }
                    selectAfterEdit { selection = to }
                }
            },
            onDuplicate = {
                editDrawnRows(asDrawn) { r ->
                    r.mapIndexed { i, row ->
                        if (i != at.row || at.col !in row.indices) {
                            row
                        } else {
                            row.subList(0, at.col + 1) + row[at.col] + row.drop(at.col + 1)
                        }
                    }
                }
            },
            onInsertAfter = {
                editDrawnRows(asDrawn) { r ->
                    r.mapIndexed { i, row ->
                        if (i != at.row) row else row.take(at.col + 1) + Key("new") + row.drop(at.col + 1)
                    }
                }
                selection = ref.copy(col = ref.col + 1)
                stepPushed = false
            },
            onDelete = {
                editDrawnRows(asDrawn) { r ->
                    r.mapIndexed { i, row ->
                        if (i != at.row) row else row.filterIndexed { c, _ -> c != at.col }
                    }
                }
                selection = null
            },
            onDismiss = { selection = null },
            onCopy = { copyKeys(listOf(selectedKey)) },
            onPaste = {
                clipKeys()?.let { pasted ->
                    editDrawnRows(asDrawn) { r ->
                        r.mapIndexed { i, row ->
                            if (i != at.row) row else row.take(at.col + 1) + pasted + row.drop(at.col + 1)
                        }
                    }
                    stepPushed = false
                    selectAfterEdit { selection = ref.copy(col = ref.col + 1) }
                }
            },
            secondaryLayouts = secondaryLayoutList,
            keyboardModes = keyboardModeList,
            kanaPad = !secondary && edited.watch { it.language().id == "ja" },
            boardKey = board?.rows?.getOrNull(ref.row)
                ?.takeIf { it.size == drawnRows[ref.row].size }
                ?.getOrNull(ref.col),
            popupColumns = popupColumns,
            popupNearestFirst = popupNearestFirst,
        )
    }

    // A key on one of the rows around the grid, edited like any other key, and
    // the tools for its row (issue #769, after #273).
    val extra = shownExtraSel
    if (panelKind == null && extra != null) {
        val band = extra.band
        val rowKeys = bandRow(band).orEmpty()
        val key = rowKeys.getOrNull(extra.col)
        val rowWidth = rowKeys.sumOf { it.width.toDouble() }.toFloat()
        if (key != null) {
            KeyEditSheet(
                key = key,
                ref = KeyRef(0, extra.col),
                rowSize = rowKeys.size,
                rowCount = 1,
                gridWeight = rowWidth,
                otherWidthsInRow = rowWidth - key.width,
                title = stringResource(band.keyTitleRes(), extra.col + 1),
                onChange = { change ->
                    editExtraRowCoalesced(band) { stored ->
                        stored.mapIndexed { c, k -> if (c == extra.col) change(k) else k }
                    }
                },
                onMove = { delta ->
                    val target = extra.col + delta
                    if (target in rowKeys.indices) {
                        editExtraRow(band) { stored ->
                            if (extra.col in stored.indices && target in stored.indices) {
                                stored.toMutableList().apply { add(target, removeAt(extra.col)) }
                            } else {
                                stored
                            }
                        }
                        selectAfterEdit { extraSel = extra.copy(col = target) }
                    }
                },
                // One row: the section's up and down arrows are off at rowCount 1.
                onMoveRow = {},
                onDuplicate = {
                    editExtraRow(band) { stored ->
                        if (extra.col in stored.indices) {
                            stored.subList(0, extra.col + 1) + stored[extra.col] + stored.drop(extra.col + 1)
                        } else {
                            stored
                        }
                    }
                },
                onInsertAfter = {
                    editExtraRow(band) { stored -> stored.take(extra.col + 1) + Key("new") + stored.drop(extra.col + 1) }
                    extraSel = extra.copy(col = extra.col + 1)
                    stepPushed = false
                },
                onDelete = {
                    editExtraRow(band) { stored -> stored.filterIndexed { c, _ -> c != extra.col } }
                    extraSel = null
                },
                onDismiss = { extraSel = null },
                onCopy = { copyKeys(listOf(key)) },
                onPaste = {
                    clipKeys()?.let { pasted ->
                        editExtraRow(band) { stored -> stored.take(extra.col + 1) + pasted + stored.drop(extra.col + 1) }
                        stepPushed = false
                        selectAfterEdit { extraSel = extra.copy(col = extra.col + 1) }
                    }
                },
                secondaryLayouts = secondaryLayoutList,
                keyboardModes = keyboardModeList,
                popupColumns = popupColumns,
                popupNearestFirst = popupNearestFirst,
            )
        }
        SettingsGroup(stringResource(band.titleRes()), info = stringResource(band.captionRes())) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_editor_extra_row_add_key_desc),
                    leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    onClick = {
                        editExtraRow(band) { it + Key("new") }
                        extraSel = ExtraRef(band, rowKeys.size)
                    },
                )
            }
            item(visible = storedExtra(band) != null) {
                WmRow(
                    title = stringResource(R.string.layout_editor_extra_row_reset),
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        resetExtraRow(band)
                        extraSel = null
                    },
                )
            }
        }
    }

    // Several keys at once (issue #769).
    val picked = multi?.filterTo(HashSet()) { drawnRows.getOrNull(it.row)?.getOrNull(it.col) != null }
    if (panelKind == null && !picked.isNullOrEmpty()) {
        val order = picked.sortedWith(compareBy({ it.row }, { it.col }))
        val pickedKeys = order.map { drawnRows[it.row][it.col] }
        val run = runOf(picked)
        fun moveRun(target: KeyRef) {
            val r = run ?: return
            editDrawnRows(arranged.rowChanged(r.row) || arranged.rowChanged(target.row)) { moveRunIn(it, r, target) }
            stepPushed = false
            selectAfterEdit { multi = r.movedTo(target).toSet() }
        }
        MultiKeySection(
            keys = pickedKeys,
            canMoveLeft = run != null && run.start > 0,
            canMoveRight = run != null && run.start + run.len < drawnRows[run.row].size,
            canMoveUp = run != null && run.row > 0,
            canMoveDown = run != null && run.row < drawnRows.lastIndex,
            onMove = { delta -> run?.let { moveRun(KeyRef(it.row, it.start + delta)) } },
            onMoveRow = { delta ->
                run?.let { r ->
                    val toRow = r.row + delta
                    if (toRow in drawnRows.indices) moveRun(KeyRef(toRow, r.start.coerceAtMost(drawnRows[toRow].size)))
                }
            },
            onCopy = { copyKeys(pickedKeys) },
            onPaste = {
                clipKeys()?.let { pasted ->
                    val last = order.last()
                    editDrawnRows(arranged.rowChanged(last.row)) { r ->
                        r.mapIndexed { i, row ->
                            if (i != last.row) row else row.take(last.col + 1) + pasted + row.drop(last.col + 1)
                        }
                    }
                    stepPushed = false
                    selectAfterEdit { multi = pasted.indices.mapTo(HashSet()) { KeyRef(last.row, last.col + 1 + it) } }
                }
            },
            onDelete = {
                editDrawnRows(picked.any { arranged.rowChanged(it.row) }) { r ->
                    r.mapIndexed { i, row -> row.filterIndexed { c, _ -> KeyRef(i, c) !in picked } }
                }
                multi = emptySet()
            },
            onDone = { multi = null },
            onChange = { change ->
                // As the single key's section does it: a key the Bottom row
                // settings rewrote is changed as drawn, any other where stored.
                val asDrawn = picked.any { arranged.rewritten(it.row, it.col) }
                val at = if (asDrawn) picked else picked.mapTo(HashSet(), ::storedRef)
                editCoalesced { spec ->
                    val from = if (asDrawn) laidOutAsDrawn(spec) else spec
                    withLayerRows(
                        from,
                        baseLayerOf(from).rows.mapIndexed { r, row ->
                            row.mapIndexed { c, k -> if (KeyRef(r, c) in at) change(k) else k }
                        },
                    )
                }
            },
        )
    }

    if (panelKind == null) selection?.let { sel ->
        if (sel.row in rows.indices) {
            // A row is as wide as its own keys plus the columns a spanning key
            // above holds over it — that is the number the keyboard centres it
            // on, so it is the number the mismatch warning has to judge.
            val spanWidth = spanRowWidths(rows)[sel.row]
            RowActionBar(
                rowIndex = sel.row,
                rowCount = rows.size,
                rowWidth = spanWidth,
                gridWeight = gridWeightOf(rows),
                onAddKey = {
                    editRows { r ->
                        r.mapIndexed { i, row ->
                            if (i == sel.row) row + Key("new") else row
                        }
                    }
                    selection = KeyRef(sel.row, rows[sel.row].size)
                    stepPushed = false
                },
                onDuplicateRow = {
                    editLayer { ls ->
                        val src = (0..sel.row) + sel.row + (sel.row + 1 until ls.rows.size)
                        ls.copy(
                            rows = src.map { ls.rows[it] },
                            rowHeights = pickHeights(ls.rowHeights, src),
                        )
                    }
                },
                onDeleteRow = {
                    editLayer { ls ->
                        val src = ls.rows.indices.filter { it != sel.row }
                        ls.copy(
                            rows = src.map { ls.rows[it] },
                            rowHeights = pickHeights(ls.rowHeights, src),
                        )
                    }
                    selection = null
                },
            )
            RowFitRow(
                rowWidth = spanWidth,
                gridWeight = gridWeightOf(rows),
            ) {
                editRows { r ->
                    // The row's own keys have to fill what the spanning key above
                    // is not already standing in, so the fit targets the grid
                    // less the columns held over this row.
                    val held = spanRowWidths(r)[sel.row] -
                        r[sel.row].sumOf { it.width.toDouble() }.toFloat()
                    r.mapIndexed { i, row ->
                        if (i == sel.row) fitRowToGrid(row, gridWeightOf(r) - held) else row
                    }
                }
            }
            // The row's own width (issue #769): narrower than the grid
            // centres it, the way QWERTY's second row is drawn.
            RowWidthRow(
                rowIndex = sel.row,
                width = rows[sel.row].sumOf { it.width.toDouble() }.toFloat(),
                gridWeight = gridWeightOf(rows),
            ) { target ->
                editCoalesced { spec ->
                    withLayerRows(
                        spec,
                        baseLayerOf(spec).rows.mapIndexed { i, row -> if (i == sel.row) fitRowToGrid(row, target) else row },
                    )
                }
            }
            RowHeightRow(
                rowIndex = sel.row,
                height = rowHeights?.getOrNull(sel.row) ?: 1f,
            ) { setRowHeight(sel.row, it) }
            // Ctrl, Alt, Tab, the arrows and the rest, made in one step with
            // the right action and the label the keyboard draws for it.
            OutlinedButton(
                onClick = { pickingSpecialKey = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_editor_special_key_action))
            }
            if (pickingSpecialKey) {
                KeyActionPickerDialog(
                    current = KeyAction.None,
                    options = SpecialKeyPresets,
                    onDismiss = { pickingSpecialKey = false },
                    onPick = { action ->
                        pickingSpecialKey = false
                        val col = sel.col.coerceAtLeast(-1)
                        editDrawnRows(arranged.rowChanged(sel.row)) { r ->
                            r.mapIndexed { i, row ->
                                if (i != sel.row) row else row.take(col + 1) + Key("", action = action) + row.drop(col + 1)
                            }
                        }
                        stepPushed = false
                        selectAfterEdit { selection = KeyRef(sel.row, col + 1) }
                    },
                )
            }
        }
    }

    // Issue #420: say so when the keyboard draws this layer's bottom row other
    // than as it is stored, name the settings doing it, and offer the switch
    // that stops them. Shown while any of those settings is on for this layer,
    // not only while one of them changes it (#769). Turned on, the switch
    // writes the row down as drawn, after which no rule has anything left to
    // change; keyed on "changes something", the switch vanished the moment it
    // was turned off again and could not be found to turn back on.
    val anyBottomRule = bottomRules != BottomRowRules()
    if (panelKind == null && (arranged.applied.isNotEmpty() || laidOut || anyBottomRule)) {
        if (!laidOut && arranged.applied.isNotEmpty()) {
            val names = BottomRowRule.entries.filter { it in arranged.applied }.map { rule ->
                val title = stringResource(rule.titleRes())
                if (rule == BottomRowRule.HIDE_GLOBE) {
                    stringResource(R.string.layout_editor_bottom_row_setting_off, title)
                } else {
                    title
                }
            }
            val note = stringResource(R.string.layout_editor_repair_note)
            CaptionText(
                stringResource(R.string.layout_editor_bottom_row_caption) +
                    names.joinToString("") { "\n" + note.format(it) },
            )
        }
        SettingsGroup {
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.layout_editor_bottom_row_laid_out_title,
                    stringResource(
                        if (laidOut) {
                            R.string.layout_editor_bottom_row_laid_out_on_subtitle
                        } else {
                            R.string.layout_editor_bottom_row_laid_out_off_subtitle
                        },
                    ),
                    laidOut,
                    info = stringResource(R.string.layout_editor_bottom_row_laid_out_info),
                ) { on ->
                    if (on) edit { laidOutAsDrawn(it) } else edit { notLaidOut(it) }
                }
            }
            item(level = SettingLevel.STANDARD) {
                WmRow(
                    title = stringResource(R.string.layout_bottom_row_keys_title),
                    icon = SettingsRowIcons[R.string.layout_bottom_row_keys_title],
                    subtitle = stringResource(R.string.layout_editor_bottom_row_settings_subtitle),
                    onClick = {
                        // The first setting changing this row, so the page
                        // opens on it rather than at the top.
                        val rule = BottomRowRule.entries.firstOrNull { it in arranged.applied }
                        SettingsHighlight.request(rule?.titleRes() ?: R.string.layout_globe_emoji_title)
                        onNavigate("layout")
                    },
                )
            }
        }
    }

    // A layout's identity: its name, the language it counts as, and the
    // composer it types through. All three were reachable only by hand-editing
    // the JSON — the editor printed the name as a header and nothing else, so
    // every copy of a copy read "X copy copy", and a duplicate of QWERTY could
    // never be re-languaged even though langId decides its dictionary,
    // autocorrect, shift behaviour and dictation.
    var renaming by remember(layoutId) { mutableStateOf(false) }
    var languagePickerOpen by remember(layoutId) { mutableStateOf(false) }
    var fontPickerOpen by remember(layoutId) { mutableStateOf(false) }
    var themePickerOpen by remember(layoutId) { mutableStateOf(false) }
    var layerThemePickerOpen by remember(layoutId, layer) { mutableStateOf(false) }
    val clearLabel = stringResource(CommonR.string.common_clear)

    // What the copy and paste rows below have to say when they are pressed
    // (issue #105). Every word of it is resolved here because a click lambda is
    // a plain lambda, and because a note about a pasted grid names the layer it
    // landed in, which is a translated word.
    val layerName = stringResource(layerTitleRes(layer))
    val layerNames = LayoutLayer.entries.associate { it.key to stringResource(layerTitleRes(it)) }
    val clipLabel = stringResource(R.string.layout_editor_layer_clip_label)
    val copyDoneFormat = stringResource(R.string.layout_editor_copy_layer_done_message)
    val copyFailedError = stringResource(R.string.layout_editor_copy_layer_error)
    val pasteDoneFormat = stringResource(R.string.layout_editor_paste_layer_done_message)
    val pasteDoneUnnamedFormat =
        stringResource(R.string.layout_editor_paste_layer_done_unnamed_message)
    val pasteWrongClipError = stringResource(R.string.layout_editor_paste_layer_wrong_clip_error)
    val pasteEmptyError = stringResource(R.string.layout_editor_paste_layer_empty_error)
    val pasteChangesTitle = stringResource(R.string.layout_editor_paste_changes_title)
    val noteFormat = stringResource(R.string.layout_editor_repair_note)

    if (panelKind == null && !hasOwnLayer) {
        if (layer == LayoutLayer.FN) {
            // Nothing ships an Fn layer, so there is no built-in to inherit and
            // the grid above is a stand-in. Offer the template instead.
            CaptionText(stringResource(R.string.layout_editor_fn_missing_caption))
            SettingsGroup {
                item {
                    WmRow(
                        title = stringResource(R.string.layout_editor_add_fn_title),
                        subtitle = stringResource(R.string.layout_editor_add_fn_subtitle),
                        leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                        onClick = {
                            edit { it.copy(layers = it.layers + (layer.key to BuiltInLayouts.FN_DEFAULT)) }
                        },
                    )
                }
            }
        } else {
            CaptionText(
                stringResource(
                    R.string.layout_editor_inherited_layer_caption,
                    stringResource(layerTitleRes(layer)),
                ),
            )
        }
    }

    // Everything about the layer on screen, under a heading that names it, so
    // its label size and theme read as this layer's and not as a repeat of the
    // layout's further down (issue #769).
    if (panelKind == null) SettingsGroup(stringResource(R.string.layout_editor_layer_section_title, layerName)) {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_add_row_title),
                subtitle = stringResource(R.string.layout_editor_add_row_subtitle),
                leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
                onClick = {
                    editLayer { ls ->
                        ls.copy(
                            rows = ls.rows + listOf(listOf(Key("new"))),
                            rowHeights = ls.rowHeights?.plus(1f),
                        )
                    }
                },
            )
        }
        item {
            ReorderSetting(
                title = stringResource(R.string.layout_editor_reorder_rows_title),
                icon = SettingsRowIcons[R.string.layout_editor_reorder_rows_title],
                dialogTitle = stringResource(R.string.layout_editor_row_order_dialog_title),
                items = rows.indices.toList(),
                label = { i -> rowReorderLabel(context, i + 1, rows[i].size) },
            ) { order ->
                editLayer { ls ->
                    ls.copy(
                        rows = order.map { ls.rows[it] },
                        rowHeights = pickHeights(ls.rowHeights, order),
                    )
                }
            }
        }
        selection?.let { sel ->
            item(visible = sel.row in drawnRows.indices && drawnRows[sel.row].size > 1) {
                ReorderSetting(
                    title = stringResource(
                        R.string.layout_editor_reorder_keys_title,
                        sel.row + 1,
                    ),
                    icon = SettingsRowIcons[R.string.layout_editor_reorder_keys_title],
                    dialogTitle = stringResource(R.string.layout_editor_key_order_dialog_title),
                    // Positions, not the keys themselves — the same shape the
                    // row reorder above uses, and for the stronger of its two
                    // reasons: a list of keys carries this composition's copy
                    // of them, so writing it back would put a key edited a
                    // frame ago back the way it was. It also disambiguates a
                    // row holding two identical keys.
                    items = drawnRows[sel.row].indices.toList(),
                    label = { keyReorderLabel(context, drawnRows[sel.row][it]) },
                ) { order ->
                    editDrawnRows(arranged.rowChanged(sel.row)) { r ->
                        r.mapIndexed { i, row ->
                            // Guarded because the stored row may have gained
                            // or lost a key since the dialog opened; a
                            // permutation that no longer fits it is dropped
                            // rather than allowed to delete keys.
                            if (i == sel.row && order.size == row.size) {
                                order.map { row[it] }
                            } else {
                                row
                            }
                        }
                    }
                    selection = null
                }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_editor_show_shift_title,
                stringResource(R.string.layout_editor_show_shift_subtitle),
                showShift,
            ) { showShift = it }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.layout_editor_actual_size_title,
                stringResource(R.string.layout_editor_actual_size_subtitle),
                actualSize,
                info = stringResource(R.string.layout_editor_actual_size_info),
            ) { actualSize = it }
        }
        // Issue #60: keep this layer up across a close and reopen. Offered on
        // every layer but the letters of an ordinary layout, which is where the
        // keyboard lands anyway. The subtitle carries the warning the issue
        // asked for, and the Problems list repeats it while the flag is on.
        // `edit`, not coalesced: one deliberate flip is one undo step.
        item(visible = layer != LayoutLayer.LETTERS || secondary, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.layout_editor_persist_title,
                stringResource(R.string.layout_editor_persist_subtitle),
                edited.watch { it.layer(layer)?.persistent ?: false },
                info = stringResource(R.string.layout_editor_persist_info),
            ) { on -> editLayer { it.copy(persistent = on) } }
        }
        // Issue #588: this layer's flick arms, over the keyboard-wide switch.
        // Only on a layer that has flick keys to draw them on.
        item(visible = layerHasFlicks, level = SettingLevel.ADVANCED) {
            val global = settings.watch { it.layoutBehavior.flickHints }
            ToggleSetting(
                R.string.layout_editor_flick_hints_title,
                stringResource(R.string.layout_editor_flick_hints_subtitle),
                edited.watch { it.layer(layer)?.flickHints } ?: global,
                info = stringResource(R.string.layout_editor_flick_hints_info),
            ) { on -> editLayer { it.copy(flickHints = on.takeIf { value -> value != global }) } }
        }
        // Issue #61 again, one layer down: a symbols page in its own colours.
        // Not on a secondary layout, whose one grid is the layout. A theme of
        // the user's own opens in the theme editor from here (issue #769): the
        // row named a theme and gave no way to change it.
        item(visible = !secondary, level = SettingLevel.ADVANCED) {
            val layerThemeId = edited.watch { it.layer(layer)?.themeId }
            val editable = layerThemeId != null && settings.watch { s ->
                s.customThemes.flattenedThemes().any { it.id == layerThemeId }
            }
            WmRow(
                title = stringResource(R.string.layout_editor_layer_theme_title, layerName),
                icon = SettingsRowIcons[R.string.layout_editor_layer_theme_title],
                subtitle = layerThemeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.layout_editor_layer_theme_inherit_subtitle),
                trailing = {
                    Row {
                        if (editable && layerThemeId != null) {
                            TextButton(onClick = { onNavigate(themeEditRoute(layerThemeId)) }) {
                                Text(stringResource(R.string.layout_editor_theme_edit_action))
                            }
                        }
                        if (layerThemeId != null) {
                            TextButton(onClick = { editLayer { it.copy(themeId = null) } }) {
                                Text(clearLabel)
                            }
                        }
                    }
                },
                onClick = { layerThemePickerOpen = true },
            )
        }
        item(level = SettingLevel.ADVANCED) {
            // Layer-scoped: the layout-wide size further down reaches every
            // layer, so a symbol page whose keys are punctuation had no way to
            // stay put while the letters grew. Null here follows the layout's,
            // which is what almost every layer wants and what the caption says.
            val layerFontScale = edited.watch { it.layer(layer)?.fontScale }
            LayoutFontScaleRow(
                scale = layerFontScale,
                title = stringResource(
                    R.string.layout_editor_layer_font_scale_label,
                    layerFontScale ?: 1f,
                ),
                autoTitle = stringResource(R.string.layout_editor_layer_font_scale_auto_label),
                hint = stringResource(
                    R.string.layout_editor_layer_font_scale_hint,
                    layerName,
                ),
                onChange = { value -> editLayerCoalesced { it.copy(fontScale = value) } },
            )
        }
        // Issue #105: one layer at a time, into another layer of this layout,
        // into another layout, or off the device entirely. The system clipboard
        // rather than a slot of the app's own is what makes the last two work:
        // the editor is one screen per layout, and a document that any app can
        // carry is also the export and the backup the issue asked for. What it
        // holds is the same JSON the raw editor prints, wrapped in the tag that
        // lets Paste tell a layer from a shopping list.
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.layout_editor_copy_layer_title),
                subtitle = stringResource(R.string.layout_editor_copy_layer_subtitle, layerName),
                leading = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                // An unauthored Fn layer has no grid to copy, and the stand-in
                // the tab draws is the letters. See the note on `baseLayerOf`.
                enabled = rows.isNotEmpty(),
                onClick = {
                    val current = edited.now() ?: return@WmRow
                    val text = LayerFile.encode(
                        layerKey = layer.key,
                        // The layer as it is drawn: this layout's own grid, or
                        // the built-in one it still inherits. Copying an
                        // inherited layer is the whole of use case 3 in the
                        // issue, where a layout modifies the letters alone.
                        spec = baseLayerOf(current),
                        appVersion = BuildConfig.VERSION_CODE,
                        appVersionName = BuildConfig.VERSION_NAME,
                    )
                    message = if (putOnClipboard(context, clipLabel, text)) {
                        copyDoneFormat.format(layerName)
                    } else {
                        copyFailedError
                    }
                },
            )
        }
        item(level = SettingLevel.STANDARD) {
            WmRow(
                title = stringResource(R.string.layout_editor_paste_layer_title),
                subtitle = stringResource(R.string.layout_editor_paste_layer_subtitle, layerName),
                leading = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                onClick = {
                    // Read at the press and never before it. Android 12 and
                    // later tells the user every time an app reads the
                    // clipboard, so a row that checked what was on it to decide
                    // whether to enable itself would say so on every
                    // recomposition. The row is always live instead, and says
                    // what it found.
                    val imported = clipboardText(context)?.let { LayerFile.decode(it) }
                    if (imported == null) {
                        message = pasteWrongClipError
                        return@WmRow
                    }
                    // Through the same repair pass an imported layout gets,
                    // against the layer it is landing in rather than the one it
                    // came from: a grid copied off another device can name a key
                    // action this build does not have.
                    val repaired = imported.spec.repairAsLayer(layer.key)
                    val pasted = repaired.spec
                    if (pasted == null) {
                        message = pasteEmptyError
                        return@WmRow
                    }
                    edit { it.copy(layers = it.layers + (layer.key to pasted)) }
                    selection = null
                    extraSel = null
                    val from = layerNames[imported.layerKey]
                    message = buildString {
                        append(
                            if (from == null) {
                                pasteDoneUnnamedFormat.format(layerName)
                            } else {
                                pasteDoneFormat.format(from, layerName)
                            },
                        )
                        if (repaired.repairNotes.isNotEmpty()) {
                            append("\n\n")
                            append(pasteChangesTitle)
                            for (note in repaired.repairNotes) {
                                append("\n")
                                append(noteFormat.format(note.format(context.resources)))
                            }
                        }
                    }
                },
            )
        }
        if (hasOwnLayer) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_editor_reset_layer_title),
                    subtitle = stringResource(
                        R.string.layout_editor_reset_layer_subtitle,
                        layerName,
                    ),
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        edit { it.copy(layers = it.layers - layer.key) }
                        selection = null
                        extraSel = null
                    },
                )
            }
        }
    }

    // The layout as a whole, after the layer: its name, theme, language,
    // typing method, font, and the rows that reach every layer at once.
    SettingsGroup(stringResource(R.string.layout_editor_layout_section_title)) {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_name_title),
                icon = SettingsRowIcons[R.string.layout_editor_name_title],
                subtitle = edited.watch { it.name },
                onClick = { renaming = true },
            )
        }
        item(level = SettingLevel.STANDARD) {
            // Issue #61: a theme of the layout's own, the same override a
            // keyboard mode carries and the same picker, customs included.
            val themeId = edited.watch { it.themeId }
            val editable = themeId != null && settings.watch { s ->
                s.customThemes.flattenedThemes().any { it.id == themeId }
            }
            WmRow(
                title = stringResource(R.string.layout_editor_theme_title),
                icon = SettingsRowIcons[R.string.layout_editor_theme_title],
                subtitle = themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.layout_editor_theme_inherit_subtitle),
                trailing = {
                    Row {
                        if (editable && themeId != null) {
                            TextButton(onClick = { onNavigate(themeEditRoute(themeId)) }) {
                                Text(stringResource(R.string.layout_editor_theme_edit_action))
                            }
                        }
                        if (themeId != null) {
                            TextButton(onClick = { edit { it.copy(themeId = null) } }) {
                                Text(clearLabel)
                            }
                        }
                    }
                },
                onClick = { themePickerOpen = true },
            )
        }
        // A secondary layout types with the language of the layout under it,
        // so neither of these means anything on one.
        item(visible = !secondary) {
            WmRow(
                title = stringResource(R.string.layout_editor_language_title),
                icon = SettingsRowIcons[R.string.layout_editor_language_title],
                subtitle = edited.watch { it.langId }.takeIf { it.isNotBlank() }
                    ?.let { LanguageRegistry.byId(it).displayName }
                    ?: stringResource(R.string.layout_editor_language_unset),
                onClick = { languagePickerOpen = true },
            )
        }
        item(visible = !secondary, level = SettingLevel.ADVANCED) {
            // Null is "whatever this script normally uses", which is the right
            // answer for almost every layout; the override exists because a
            // phonetic and a direct grid for the same language differ only here.
            val inheritLabel = stringResource(R.string.layout_editor_composer_inherit)
            ChoiceSetting(
                title = R.string.layout_editor_composer_title,
                subtitle = stringResource(R.string.layout_editor_composer_subtitle),
                options = listOf<Pair<ComposerType?, String>>(null to inheritLabel) +
                    ComposerType.entries.map { it to composerLabel(it) },
                selected = edited.watch { it.composer },
                info = stringResource(R.string.layout_editor_composer_info),
                detail = { type -> ChoiceDetail(stringResource(composerDescRes(type))) },
            ) { chosen -> edit { it.copy(composer = chosen) } }
        }
        item(level = SettingLevel.STANDARD) {
            // Layout-wide, like the tablet row and the JSON row below it. The
            // font and the size are what issue #18 asked for: a grid whose keys
            // are labelled with words, or drawn in a script the global font does
            // not suit, needs type of its own, and neither the theme nor the
            // global settings can hold an answer for one layout.
            val fontId = edited.watch { it.appearance?.fontId }
            val customFontName = settings.watch { it.customFontName }
            WmRow(
                title = stringResource(R.string.layout_editor_font_title),
                icon = SettingsRowIcons[R.string.layout_editor_font_title],
                subtitle = fontId?.let {
                    KeyboardFonts.displayName(context, it, customFontName)
                } ?: stringResource(R.string.layout_editor_font_inherit),
                onClick = { fontPickerOpen = true },
            )
        }
        item(level = SettingLevel.STANDARD) {
            val fontScale = edited.watch { it.appearance?.fontScale }
            LayoutFontScaleRow(
                scale = fontScale,
                title = stringResource(
                    R.string.layout_editor_font_scale_label,
                    fontScale ?: 1f,
                ),
                autoTitle = stringResource(R.string.layout_editor_font_scale_auto_label),
                hint = stringResource(R.string.layout_editor_font_scale_hint),
                // Coalesced: one drag of the slider is one undo step, the same
                // rule the row-height slider follows.
                onChange = { value -> editCoalesced { it.withAppearance(fontScale = value) } },
            )
        }
        // Telegram group: a one-handed grid sized for one thumb resized QWERTY
        // with it, since every size was global. Each of these is this layout's
        // own, over the theme and Layout & size; "Same as everywhere" drops it.
        item(level = SettingLevel.STANDARD) {
            val own = edited.watch { it.appearance?.keyHeightDp }
            val global = settings.watch { it.keyHeightDp }
            LayoutSizeOverrideRow(
                title = stringResource(R.string.layout_editor_size_key_height_title),
                value = own?.toFloat(),
                global = global.toFloat(),
                range = SettingsRepository.KEY_HEIGHT_MIN_DP.toFloat()..SettingsRepository.KEY_HEIGHT_MAX_DP.toFloat(),
                display = { "${it.roundToInt()} dp" },
            ) { value -> edit { s -> s.withSizing { it.copy(keyHeightDp = value?.roundToInt()) } } }
        }
        item(level = SettingLevel.STANDARD) {
            val own = edited.watch { it.appearance?.keyGapScale }
            val global = settings.watch { it.keyGapScale }
            LayoutSizeOverrideRow(
                title = stringResource(R.string.layout_editor_size_key_gap_title),
                value = own,
                global = global,
                range = 0f..2f,
                display = { "×%.2f".format(it) },
            ) { value -> edit { s -> s.withSizing { it.copy(keyGapScale = value) } } }
        }
        item(level = SettingLevel.STANDARD) {
            val own = edited.watch { it.appearance?.sidePadLeftScale }
            val global = settings.watch { it.layoutBehavior.sidePadLeftScale }
            LayoutSizeOverrideRow(
                title = stringResource(R.string.layout_editor_size_side_left_title),
                value = own,
                global = global,
                range = SidePadScaleRange,
                display = { "${(it * 100).roundToInt()}%" },
            ) { value -> edit { s -> s.withSizing { it.copy(sidePadLeftScale = value) } } }
        }
        item(level = SettingLevel.STANDARD) {
            val own = edited.watch { it.appearance?.sidePadRightScale }
            val global = settings.watch { it.layoutBehavior.sidePadRightScale }
            LayoutSizeOverrideRow(
                title = stringResource(R.string.layout_editor_size_side_right_title),
                value = own,
                global = global,
                range = SidePadScaleRange,
                display = { "${(it * 100).roundToInt()}%" },
            ) { value -> edit { s -> s.withSizing { it.copy(sidePadRightScale = value) } } }
        }
        if (!secondary) item(level = SettingLevel.STANDARD) {
            // Layouts that type the same way: only the grid changes sideways.
            val current = settings.watch { it.customLayouts }
            val spec = edited.watch { it }
            val others = remember(current, spec.langId, spec.composerType()) {
                val shipped = LanguageRegistry.byId(spec.langId).layoutIds.mapNotNull { findLayout(current, it) }
                (shipped + current.filter { it.langId == spec.langId })
                    .distinctBy { it.id }
                    .filter { it.id != spec.id && it.composerType() == spec.composerType() }
            }
            ChoiceSetting(
                title = R.string.layout_editor_landscape_layout_title,
                subtitle = stringResource(R.string.layout_editor_landscape_layout_subtitle),
                options = listOf<Pair<String?, String>>(
                    null to stringResource(R.string.layout_editor_landscape_layout_same),
                ) + others.map { it.id to it.name },
                selected = spec.landscapeLayoutId,
            ) { chosen -> edit { it.copy(landscapeLayoutId = chosen) } }
        }
        if (!secondary) item(level = SettingLevel.ADVANCED) {
            // `edit`, not `editCoalesced`, because one deliberate flip deserves
            // one undo step.
            //
            // Whether the tablet expansion would actually do anything here, so its
            // toggle can say so. Asked of the letters layer on the widest form — the
            // gate is a property of the layout, not of whichever layer is on screen —
            // and only to word a subtitle, never to gate the toggle: a layout that the
            // transform declines today should still keep the author's answer on file.
            val tabletExpandApplies = edited.watch {
                tabletGridWidth(it.compile(LayoutLayer.LETTERS), DeviceForm.LARGE_TABLET) != null
            }
            ToggleSetting(
                R.string.layout_editor_tablet_expand_title,
                stringResource(
                    if (tabletExpandApplies) {
                        R.string.layout_editor_tablet_expand_subtitle
                    } else {
                        R.string.layout_editor_tablet_expand_subtitle_na
                    },
                ),
                edited.watch { it.tabletExpand },
            ) { on -> edit { it.copy(tabletExpand = on) } }
        }
        item(level = SettingLevel.ADVANCED) {
            NavRow(
                R.string.layout_editor_json_title,
                subtitle = stringResource(R.string.layout_editor_json_subtitle),
            ) { onNavigate("keymap_json/$layoutId") }
        }
    }

    if (edited.watch { it.themeId != null || it.layer(layer)?.themeId != null }) {
        CaptionText(stringResource(R.string.layout_editor_theme_override_body))
    }

    if (renaming) {
        LayoutNameDialog(
            initial = edited.watch { it.name },
            onDismiss = { renaming = false },
            onConfirm = { typed ->
                renaming = false
                edit { it.copy(name = typed) }
            },
        )
    }

    if (themePickerOpen) {
        ModeThemePickerDialog(
            settings = settings,
            selectedId = layoutThemeId,
            title = stringResource(R.string.layout_editor_theme_picker_title),
            onPick = { id ->
                themePickerOpen = false
                edit { it.copy(themeId = id) }
            },
            onDismiss = { themePickerOpen = false },
        )
    }

    if (layerThemePickerOpen) {
        ModeThemePickerDialog(
            settings = settings,
            selectedId = edited.watch { it.layer(layer)?.themeId },
            title = stringResource(R.string.layout_editor_layer_theme_picker_title),
            onPick = { id ->
                layerThemePickerOpen = false
                editLayer { it.copy(themeId = id) }
            },
            onDismiss = { layerThemePickerOpen = false },
        )
    }

    if (languagePickerOpen) {
        // The same picker the foreign-layout import uses: it searches the whole
        // registry, which is what re-languaging a duplicate needs.
        ForeignLanguageDialog(
            selected = edited.watch { it.langId },
            onDismiss = { languagePickerOpen = false },
            onPick = { id ->
                languagePickerOpen = false
                edit { it.copy(langId = id) }
            },
        )
    }

    if (fontPickerOpen) {
        // The theme editor's picker, offering the same three sources against the
        // same font ids. Narrowed to this layout's script where the font system
        // has faces for it — a Bengali grid should not be offered a Latin-only
        // display face — and left wide otherwise, because that branch of the
        // picker offers *nothing* for a script with no curated list.
        ThemeFontPickerDialog(
            current = appearance?.fontId,
            title = stringResource(R.string.layout_editor_font_title),
            defaultLabel = stringResource(R.string.layout_editor_font_inherit),
            script = edited.watch { it.script().id }.takeIf { KeyboardFonts.scriptFontChoices(it) != null },
            onDismiss = { fontPickerOpen = false },
            onPick = { id ->
                fontPickerOpen = false
                edit { it.withAppearance(fontId = id) }
            },
        )
    }

    val findings = edited.watch { validateLayout(it) }
    if (findings.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.layout_editor_problems_title)) {
            for (finding in findings) {
                item {
                    WmRow(
                        title = finding.text.format(context.resources),
                        subtitle = if (finding.severity == LayoutSeverity.BLOCKING) {
                                stringResource(R.string.layout_editor_problem_blocking_subtitle)
                            } else {
                                stringResource(R.string.layout_editor_problem_warning_subtitle)
                            },
                    )
                }
            }
        }
    }

    // A layout that is already on is live while you edit it, so the standing
    // "this does not affect typing yet" line was a lie in exactly the case where
    // it mattered — the keyboard follows every keystroke made here, and only the
    // repair pass at the point of use keeps a half-built grid typeable.
    val live = settings.watch { layoutId in it.enabledLayoutIds }
    CaptionText(
        stringResource(
            when {
                secondary -> R.string.layout_editor_secondary_caption
                live -> R.string.layout_editor_live_caption
                else -> R.string.layout_editor_not_live_caption
            },
        ),
    )

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}

/**
 * A selection waiting on the edit that moves it (issue #769): [onLanded] runs
 * once the layout on screen is no longer [before]. See `selectAfterEdit`.
 */
internal class PendingSelect(val before: LayoutSpec, val onLanded: () -> Unit)

/** How long a [PendingSelect] waits for an edit that may never change anything. */
private const val PendingSelectTimeoutMs = 1000L

/**
 * The column a drop gap carries when it is a new row rather than a place in
 * one (issue #769): its row is the row the new one goes in front of, or the
 * row count for one under them all. Not -1, which is an empty row's address.
 */
internal const val NewRowCol = -2

/** Keys side by side in one row: what several picked keys move as (issue #769). */
internal data class KeyRun(val row: Int, val start: Int, val len: Int) {
    val refs: List<KeyRef> get() = (start until start + len).map { KeyRef(row, it) }

    /** Where the run's keys are once it lands at [target]. */
    fun movedTo(target: KeyRef): List<KeyRef> = (0 until len).map { KeyRef(target.row, target.col + it) }
}

/** [picked] as one run, when it is one: all in one row, side by side. */
internal fun runOf(picked: Set<KeyRef>): KeyRun? {
    if (picked.isEmpty()) return null
    val row = picked.first().row
    if (picked.any { it.row != row || it.col < 0 }) return null
    val start = picked.minOf { it.col }
    val end = picked.maxOf { it.col }
    return KeyRun(row, start, end - start + 1).takeIf { it.len == picked.size }
}

/** [runOf], only when [ref] is one of its keys. */
internal fun runContaining(picked: Set<KeyRef>, ref: KeyRef): KeyRun? =
    runOf(picked)?.takeIf { ref in picked }

/**
 * Where [run] starts once it is taken out and dropped into the gap before key
 * [gap].col of row [gap].row, counted with the run still in place; null for a
 * gap inside or at either edge of the run itself, which moves nothing.
 */
internal fun runDropTarget(run: KeyRun, gap: KeyRef): KeyRef? = when {
    gap.row != run.row -> gap
    gap.col in run.start..run.start + run.len -> null
    gap.col > run.start -> KeyRef(gap.row, gap.col - run.len)
    else -> gap
}

/** [rows] with [run] taken out and put back starting at [target], counted after the removal. */
internal fun moveRunIn(rows: List<List<Key>>, run: KeyRun, target: KeyRef): List<List<Key>> {
    val source = rows.getOrNull(run.row) ?: return rows
    if (run.start < 0 || run.start + run.len > source.size || target.row !in rows.indices) return rows
    val keys = source.subList(run.start, run.start + run.len).toList()
    val out = rows.map { it.toMutableList() }
    repeat(run.len) { out[run.row].removeAt(run.start) }
    out[target.row].addAll(target.col.coerceIn(0, out[target.row].size), keys)
    return out.map { it.toList() }
}

/**
 * This layer with [keys] lifted out of their rows into a new row of their own,
 * put in front of row [at] (the row count puts it last). A row the move leaves
 * empty goes, so dragging a key down out of a row of one does not leave a hole.
 */
internal fun LayerSpec.withKeysInNewRow(keys: List<KeyRef>, at: Int): LayerSpec {
    val picked = keys.toSet()
    val moved = rows.flatMapIndexed { r, row -> row.filterIndexed { c, _ -> KeyRef(r, c) in picked } }
    if (moved.isEmpty()) return this
    class Line(val keys: List<Key>, val height: Float, val emptied: Boolean)
    val lines = rows.mapIndexed { r, row ->
        val left = row.filterIndexed { c, _ -> KeyRef(r, c) !in picked }
        Line(left, rowHeights?.getOrNull(r) ?: 1f, left.isEmpty() && row.isNotEmpty())
    }.toMutableList()
    lines.add(at.coerceIn(0, lines.size), Line(moved, 1f, emptied = false))
    val kept = lines.filterNot { it.emptied }
    return copy(
        rows = kept.map { it.keys },
        rowHeights = kept.map { it.height }.takeUnless { heights -> heights.all { it == 1f } },
    )
}

/** Where [withKeysInNewRow] puts [keys], against the rows as they were before it. */
internal fun newRowLanding(rows: List<List<Key>>, keys: List<KeyRef>, at: Int): List<KeyRef> {
    val picked = keys.toSet()
    val emptiedAbove = rows.indices.count { r ->
        r < at && rows[r].isNotEmpty() && rows[r].indices.all { KeyRef(r, it) in picked }
    }
    val row = at.coerceIn(0, rows.size) - emptiedAbove
    return keys.indices.map { KeyRef(row, it) }
}

/** Which of the rows drawn around a layer's grid, rather than in it, a key is on (issue #769). */
internal enum class ExtraBand { NUMBER, FILL, ARROW }

/** A key on one of the rows drawn around the grid; [col] is -1 for the row itself. */
internal data class ExtraRef(val band: ExtraBand, val col: Int)

/** The stored row for [band], or null where the layer follows the default. */
internal fun LayerSpec.extraRow(band: ExtraBand): List<Key>? = when (band) {
    ExtraBand.NUMBER -> numberRow
    ExtraBand.FILL -> fillRow
    ExtraBand.ARROW -> arrowRow
}

/** This layer with [row] as its row for [band]; null puts the default back. */
internal fun LayerSpec.withExtraRow(band: ExtraBand, row: List<Key>?): LayerSpec = when (band) {
    ExtraBand.NUMBER -> copy(numberRow = row)
    ExtraBand.FILL -> copy(fillRow = row)
    ExtraBand.ARROW -> copy(arrowRow = row)
}

@StringRes
private fun ExtraBand.titleRes(): Int = when (this) {
    ExtraBand.NUMBER -> R.string.layout_editor_number_row_title
    ExtraBand.FILL -> R.string.layout_editor_fill_row_title
    ExtraBand.ARROW -> R.string.layout_editor_arrow_row_title
}

@StringRes
private fun ExtraBand.keyTitleRes(): Int = when (this) {
    ExtraBand.NUMBER -> R.string.layout_editor_number_row_key_title
    ExtraBand.FILL -> R.string.layout_editor_fill_row_key_title
    ExtraBand.ARROW -> R.string.layout_editor_arrow_row_key_title
}

@StringRes
private fun ExtraBand.captionRes(): Int = when (this) {
    ExtraBand.NUMBER -> R.string.layout_editor_number_row_caption
    ExtraBand.FILL -> R.string.layout_editor_fill_row_caption
    ExtraBand.ARROW -> R.string.layout_editor_arrow_row_caption
}

/**
 * Which field the preview draws the keyboard for (issue #769): the three that
 * draw the letters differently. Chips rather than a picker row, since the
 * point is flicking between them and watching the keys change.
 */
@Composable
private fun PreviewFieldChips(selected: EditorPreviewField, onSelect: (EditorPreviewField) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.layout_editor_preview_field_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (field in EditorPreviewField.entries) {
            FilterChip(
                selected = field == selected,
                onClick = { onSelect(field) },
                label = {
                    Text(
                        stringResource(
                            when (field) {
                                EditorPreviewField.TEXT -> R.string.layout_editor_preview_field_text
                                EditorPreviewField.EMAIL -> R.string.layout_editor_preview_field_email
                                EditorPreviewField.WEB_ADDRESS -> R.string.layout_editor_preview_field_web
                            },
                        ),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/**
 * Puts a copied layer on the system clipboard, and says whether it went.
 *
 * Reported rather than swallowed: the row that calls this says "copied", and a
 * device whose clipboard service refuses the write would otherwise be told a
 * grid is waiting for it that is not there.
 */
private fun putOnClipboard(context: Context, label: String, text: String): Boolean = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}.isSuccess

/**
 * The text on the system clipboard, or null when there is none to read.
 *
 * Capped at as many characters as the foreign-layout import accepts bytes, and
 * for the same reason with more force: this text was written by any app at
 * all, and the parser should not be handed a clipping that a chat app filled
 * with a megabyte of markup. No real grid is within two orders of magnitude
 * of the cap.
 */
private fun clipboardText(context: Context): String? = runCatching {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return null
    (0 until clip.itemCount)
        .asSequence()
        .mapNotNull { clip.getItemAt(it).coerceToText(context)?.toString() }
        .firstOrNull { it.isNotBlank() }
        ?.takeIf { it.length <= MAX_FOREIGN_LAYOUT_BYTES }
}.getOrNull()

/** How a row reads in the reorder dialog: its number and how many keys it holds. */
internal fun rowReorderLabel(context: Context, number: Int, keyCount: Int): String {
    val name = context.getString(R.string.layout_editor_row_number, number)
    val keys = context.resources.getQuantityString(
        R.plurals.layout_editor_key_count,
        keyCount,
        keyCount,
    )
    return "$name · $keys"
}

/**
 * How a key reads in the reorder dialog, where there is no grid to look at.
 *
 * Takes a [context] because the label lambda it feeds is a plain lambda, and the
 * name of an action is a resource now.
 */
internal fun keyReorderLabel(context: Context, key: Key): String {
    val actionName = context.getString(
        (key.action as? KeyAction.Edit)?.let { textEditActionTitle(it.op) }
            ?: (key.action as? KeyAction.Field)?.let { fieldTitleRes(it.kind) }
            ?: KeyActionCatalog.firstOrNull { it.matches(key.action) }?.titleRes
            ?: R.string.layout_editor_key_fallback_label,
    )
    // An icon-drawn action reads as its name here too: the globe key's stored
    // label is 🌐, and a row of keys that says "🌐" identifies nothing.
    return if (key.label.isBlank() || actionIconName(key.action) != null || key.action is KeyAction.Field) {
        actionName
    } else {
        key.label
    }
}

/** Contextual actions for the row the selected key sits in. */
@Composable
internal fun RowActionBar(
    rowIndex: Int,
    rowCount: Int,
    rowWidth: Float,
    gridWeight: Float,
    onAddKey: () -> Unit,
    onDuplicateRow: () -> Unit,
    onDeleteRow: () -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.layout_editor_row_number, rowIndex + 1),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.width(8.dp))
        // Only worth saying when it disagrees with the grid — the width the
        // keyboard measures every other row against. Printed on every row it
        // would be five numbers that are correct and identical almost always.
        //
        // Weighted, and wrapping, because it is a whole sentence sharing a row
        // with three buttons: at its natural width it pushed Delete row clean
        // off the screen and clipped Duplicate row — and a row wide enough to
        // warn about is precisely the row you want to delete.
        if (kotlin.math.abs(rowWidth - gridWeight) > 0.01f) {
            Text(
                stringResource(R.string.layout_editor_row_width_mismatch, rowWidth, gridWeight),
                style = MaterialTheme.typography.bodySmall,
                // Narrower is a row drawn centred, which is a choice (QWERTY's
                // second row); only wider, which squeezes the row, is a fault.
                color = if (rowWidth > gridWeight) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        // Worded, not a bare plus (issue #769): adding a key to a row that
        // already has keys was asked for as if it could not be done.
        val addDesc = stringResource(R.string.layout_editor_add_key_desc, rowIndex + 1)
        TextButton(onClick = onAddKey, modifier = Modifier.semantics { contentDescription = addDesc }) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.layout_editor_row_add_key_action))
        }
        IconButton(onClick = onDuplicateRow) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription =
                    stringResource(R.string.layout_editor_duplicate_row_desc, rowIndex + 1),
            )
        }
        IconButton(enabled = rowCount > 1, onClick = onDeleteRow) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription =
                    stringResource(R.string.layout_editor_delete_row_desc, rowIndex + 1),
            )
        }
    }
}

/** Layer tabs. The pencil marks a layer this layout has actually authored. */
@Composable
private fun LayerChips(
    edited: OpenLayout,
    selected: LayoutLayer,
    onSelect: (LayoutLayer) -> Unit,
    /** The panel tab on screen, or null while a typing layer is; see issue #63. */
    selectedPanel: PanelKind? = null,
    onSelectPanel: (PanelKind) -> Unit = {},
) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(LayoutLayer.entries) { layer ->
            LayerChip(
                title = stringResource(layerTitleRes(layer)),
                selected = selectedPanel == null && layer == selected,
                authored = edited.watch { it.layer(layer) != null },
                onClick = { onSelect(layer) },
            )
        }
        // The panels, after the typing layers: every sub-layout of this
        // layout on one strip, which is what issue #63 asked for.
        items(PanelKind.entries.filter { it.shipped }) { kind ->
            LayerChip(
                title = stringResource(panelTitleRes(kind)),
                selected = kind == selectedPanel,
                authored = edited.watch { it.panelLayer(kind) != null },
                onClick = { onSelectPanel(kind) },
            )
        }
    }
}

@Composable
private fun LayerChip(title: String, selected: Boolean, authored: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(title, maxLines = 1) },
        leadingIcon = if (authored) {
            {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.layout_editor_customised_desc),
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
    )
}

/**
 * The name of a layer, as a resource id.
 *
 * The name is resolved where it is drawn rather than here, so a sentence that
 * carries it never has to change the case of a translated word.
 */
@StringRes
internal fun layerTitleRes(layer: LayoutLayer): Int = when (layer) {
    LayoutLayer.LETTERS -> R.string.layout_editor_layer_letters
    LayoutLayer.SYMBOLS -> R.string.layout_editor_layer_symbols
    LayoutLayer.SYMBOLS_SHIFTED -> R.string.layout_editor_layer_symbols_2
    LayoutLayer.NUMBER -> R.string.layout_editor_layer_number
    LayoutLayer.PHONE -> R.string.layout_editor_layer_phone
    LayoutLayer.DATE -> R.string.layout_editor_layer_date
    LayoutLayer.TIME -> R.string.layout_editor_layer_time
    LayoutLayer.DATETIME -> R.string.layout_editor_layer_date_time
    LayoutLayer.FN -> R.string.layout_editor_layer_fn
}

/**
 * The grid the user edits, drawn in the keyboard's own theme.
 *
 * [KeyboardThemeProvider] needs only [KeyboardSettings], so the editor gets the
 * real key colours, key shape, corner radius and font without touching the input
 * pipeline — `KeyboardScreen` itself wants a `StateFlow<KeyboardUiState>` and
 * some ninety callbacks, and a synthetic state that large is a maintenance
 * liability rather than a preview. Reusing the real `KeyCell`/`KeyButton` was the
 * other option and was rejected for the same reason, plus their whole job is the
 * press machinery — long-press popups, key repeat, the spacebar hold timer —
 * every one of which fights tap-to-select.
 *
 * The provider is scoped to this Box on purpose: it swaps MaterialTheme, and
 * hoisting it any higher would repaint every row and slider on the screen in the
 * keyboard's palette.
 */
@Composable
internal fun EditorGrid(
    layout: KeyboardLayout,
    settings: LiveSettings,
    selection: KeyRef?,
    showShift: Boolean,
    actualSize: Boolean,
    onSelect: (KeyRef) -> Unit,
    /**
     * Each row's height in dp, already decided, for a panel layout: its rows
     * share the key area rather than each being a key tall, and the panel
     * editor works that out with the same arithmetic the keyboard uses. Null
     * — every typing layout — sizes each row from the key height.
     */
    rowHeightsDp: List<Int>? = null,
    /**
     * Where a key dropped somewhere else in the grid goes: the seat it came
     * from, and the seat it lands in, already worked out against the grid this
     * drew. Null — a preview nobody edits — leaves the cells tap-only.
     *
     * One call per drop, so a drag is one edit and one undo step. Nothing is
     * written while the finger is down: the grid under it never moves, which is
     * both what makes the drop predictable and what keeps a half-finished move
     * out of a layout the keyboard is typing with.
     */
    onKeyDragged: ((from: KeyRef, to: KeyRef) -> Unit)? = null,
    /**
     * The icon slot the enter key draws, which follows the field the preview
     * is drawn for (issue #769): a web address field's enter is Go.
     */
    enterSlot: String = IconSlots.KEY_ENTER,
    /** What the spacebar says on the keyboard; null draws the word "space". */
    spaceText: String? = null,
    /**
     * Whether a key dropped between two rows, or above or below them all,
     * starts a row of its own there (issue #769). The drop then names the row
     * it goes in front of, with [NewRowCol] for its column.
     */
    allowNewRow: Boolean = false,
    /** Keys drawn as picked besides [selection]: the several-keys selection. */
    multiSelection: Set<KeyRef> = emptySet(),
) {
    // Where each cell was last drawn, in the coordinates of the composition
    // root. A plain map on purpose: it is written from onGloballyPositioned,
    // which fires on every scroll of the page this grid sits on, and snapshot
    // state there would recompose the whole grid as the user scrolls. Nothing
    // reads it during composition — the gesture reads it, and the two drag
    // decorations read it in their layout lambdas.
    //
    // Remembered without a key, like every other piece of drag state here. The
    // gesture below is started once per cell and keeps the map, the states and
    // the callbacks it was built with for as long as it runs, so keying any of
    // them on the grid would hand the running gesture a dead copy the moment an
    // edit landed. A seat left behind by a deleted key is harmless instead:
    // every read goes through the current rows, so an address that no longer
    // holds a key is never looked up, and a moved one is overwritten by the
    // layout pass that drew it in its new place.
    val cellBounds = remember { mutableMapOf<KeyRef, Rect>() }
    // Root position of the box the two decorations are placed in, so a root
    // coordinate a cell reported can be turned back into one of theirs.
    val gridOrigin = remember { mutableStateOf(Offset.Zero) }
    // The seat the finger picked up, null while nothing is being dragged. This
    // one is read in composition: it dims the seat and draws the floating key.
    var dragFrom by remember { mutableStateOf<KeyRef?>(null) }
    // The gap the drop would go into, counted against the grid as it stands.
    // Changes a handful of times per drag — once per boundary crossed — so
    // composing the caret from it costs nothing.
    var dropGap by remember { mutableStateOf<KeyRef?>(null) }
    // The finger and the point of the key it holds, both in root coordinates.
    // Read only from layout-phase lambdas, so the floating key follows the
    // finger without recomposing the grid underneath it every frame.
    val dragPointer = remember { mutableStateOf(Offset.Zero) }
    var dragGrab by remember { mutableStateOf(Offset.Zero) }
    val haptics = LocalHapticFeedback.current
    // The gesture below outlives every recomposition of the cell it is on, so
    // it reads both of these through the latest snapshot rather than closing
    // over the copies it was composed with.
    val currentRows by rememberUpdatedState(layout.rows)
    val currentDrop by rememberUpdatedState(onKeyDragged)
    val newRows by rememberUpdatedState(allowNewRow)
    val still by rememberUpdatedState(LocalReduceMotion.current)
    val scope = rememberCoroutineScope()
    // How far the key in the air has come up off the grid, 0 to 1: the scale
    // and the shadow it is drawn with. Raised as the long press takes it and
    // lowered again as it settles into its seat; held at the top under Reduce
    // motion, where a lifted key is still the clearest way to say which one
    // the finger has, it simply does not travel there.
    val lift = remember { Animatable(0f) }
    // The key on its way down after a drop, or back to where it came from
    // after a cancel. Null the rest of the time, and always under Reduce
    // motion, where the key is simply in its new seat on the next frame.
    var settle by remember { mutableStateOf<DropSettle?>(null) }

    /**
     * The end of the drag that started on [ref], dropped or cancelled.
     *
     * It bows out when another cell has since taken the grid: two fingers can
     * each hold a key of their own, and the second press moves the state this
     * reads — so without the check, lifting the first finger would drop the
     * second finger's key wherever the first one happened to be.
     */
    fun endDrag(ref: KeyRef, commit: Boolean) {
        if (dragFrom != ref) return
        val gap = dropGap
        // Taken before the drop goes out: the settle waits for the grid to
        // stop being this one, which is how it knows the edit has landed.
        val rowsAtDrop = currentRows
        val key = rowsAtDrop.getOrNull(ref.row)?.getOrNull(ref.col)
        val seat = cellBounds[ref]
        dragFrom = null
        dropGap = null
        val to = if (commit && gap != null) dropLanding(ref, gap) else ref
        if (to != ref) currentDrop?.invoke(ref, to)
        settle = if (still || key == null || seat == null) {
            null
        } else {
            DropSettle(key, from = ref, to = to, rowsAtDrop = rowsAtDrop, size = seat.size, start = dragPointer.value - dragGrab)
        }
        if (settle == null) scope.launch { lift.snapTo(0f) }
    }

    // Brings a let-go key down into its seat. A drop goes out as an edit, and
    // a panel's edit comes back through the repository some frames later, so
    // until the grid has the key in its new seat there is nowhere to land it:
    // it waits where the finger left it, still lifted, then springs into the
    // place its cell reports. A cancel has nothing to wait for and goes
    // straight home.
    LaunchedEffect(settle) {
        val landing = settle ?: return@LaunchedEffect
        if (landing.to != landing.from) {
            withTimeoutOrNull(DropLandingWaitMs) {
                snapshotFlow { currentRows }.first { it != landing.rowsAtDrop }
            }
            // One frame for the cells to report where the edit put them.
            withFrameNanos { }
        }
        landing.landed = true
        val target = cellBounds[landing.to]?.topLeft ?: landing.start
        launch { lift.animateTo(0f, DropLiftSpring) }
        landing.at.animateTo(target, DropTravelSpring)
        settle = null
    }

    // The neighbours a drop pushed along glide to their new seats while the
    // key comes down, and only then: a width slider moving the same cells
    // wants them under the thumb, not trailing behind it.
    val glideActive: () -> Boolean = { settle != null }

    /**
     * Everything a cell needs beyond its own drawing: where it landed, whether
     * it is the one in the air, and the drag itself.
     *
     * After a long press, so a tap still selects the key and a drag of the page
     * still scrolls it — the gesture only takes the pointer once the press has
     * been held, and consumes it from there so no scrolling ancestor can steal
     * the drag halfway through.
     */
    val cellModifier: (KeyRef) -> Modifier = { ref ->
        Modifier
            .onGloballyPositioned { cellBounds[ref] = it.boundsInRoot() }
            .then(
                when {
                    // The seat the key was taken from, until it has a new one.
                    dragFrom == ref -> Modifier.alpha(0.3f)
                    settle?.let { !it.landed && it.from == ref } == true -> Modifier.alpha(0.3f)
                    // The seat it is landing in, which the copy in the air
                    // is about to cover: drawn once, by whichever is on top.
                    settle?.let { it.landed && it.to == ref } == true -> Modifier.alpha(0f)
                    else -> Modifier
                },
            )
            .then(
                if (onKeyDragged == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(ref) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                dragGrab = offset
                                dragPointer.value = (cellBounds[ref]?.topLeft ?: Offset.Zero) + offset
                                dropGap = null
                                settle = null
                                dragFrom = ref
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                scope.launch {
                                    if (still) lift.snapTo(1f) else lift.animateTo(1f, DropLiftSpring)
                                }
                            },
                            onDragEnd = { endDrag(ref, commit = true) },
                            onDragCancel = { endDrag(ref, commit = false) },
                        ) { change, _ ->
                            // Consumed so the page this grid sits on does not
                            // read the same movement as a scroll.
                            change.consume()
                            // Only the key the grid is currently holding moves
                            // the caret. A second finger on a second key takes
                            // the grid over (see endDrag), and the first one
                            // must not go on steering the drop from underneath
                            // it until it is lifted.
                            if (dragFrom != ref) return@detectDragGesturesAfterLongPress
                            // The cell the gesture is on does not move while
                            // the finger is down — nothing is written until the
                            // drop — so its own origin is a fixed frame to
                            // measure in.
                            val at = (cellBounds[ref]?.topLeft ?: Offset.Zero) + change.position
                            dragPointer.value = at
                            dropGap = dropGapAt(currentRows, cellBounds, at, newRows)
                        }
                    }
                },
            )
    }

    // Edge to edge, the width the keyboard has (issue #769): an inset, framed
    // grid read as a picture of a keyboard, and made every key a size the real
    // one is not.
    Box(modifier = Modifier.fillMaxWidth()) {
        // The layout's own face, so the preview reads in the font the keyboard
        // will actually draw with rather than the global one.
        // …and in the theme the grid asks for (issue #61), so a layer given
        // its own colours is edited in them.
        KeyboardThemeProvider(
            settings.watch { it.applyLayoutTheme(layout.themeId) },
            layoutFontId = layout.appearance?.fontId,
        ) {
            val kb = LocalKbTheme.current
            // Forced LTR: a key's index in its row is its serialized order, so an
            // RTL locale mirroring the grid would make "move right" write
            // index - 1. Labels inside each cell still resolve their own bidi.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                // The layout's label-size multiplier, applied to the preview's
                // own (smaller) type the same way the keyboard applies it to
                // its own. A preview that ignored it would leave the one
                // control on this screen with no visible effect until the user
                // went and typed something. Read here rather than inside the
                // grid because the key in the air is drawn with it too.
                val fontScale = layout.appearance.drawnFontScale()
                // The grid, and over it the two things a drag draws: the key in
                // the air and the caret marking the gap it would fall into.
                // Both are placed from coordinates the cells reported, less
                // this box's own origin, so neither has to know how the rows
                // below were laid out — which is the only way one placement can
                // serve both the plain rows and a band of spanning keys.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { gridOrigin.value = it.boundsInRoot().topLeft },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(kb.board)
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // The most common row width sets the grid and every other
                        // row is centred against it, or squeezed if it is wider —
                        // the same rule the real keyboard lays rows out by, taken
                        // from the same helpers so the two can never disagree.
                        val gridWeight = gridWeightOf(layout.rows).takeIf { it > 0f } ?: 10f
                        if (layout.rows.isEmpty()) {
                            Text(
                                stringResource(R.string.layout_editor_layer_empty_message),
                                modifier = Modifier.padding(12.dp),
                                color = kb.keyText.copy(alpha = 0.7f),
                                fontSize = 13.sp,
                            )
                        }
                        // The preview's key height, before this row's own multiplier:
                        // the user's real setting, or a clamp of it. Clamped first and
                        // scaled second, so a row set to twice the height still draws
                        // twice as tall as its neighbours in the clamped preview.
                        val keyHeightDp = settings.watch { it.keyHeightDp }
                        // The corner hints, under the same switch the keyboard
                        // draws them under (issue #769): the preview drew none,
                        // so a grid full of them on the board looked bare here.
                        val hintsOn = settings.watch { it.longPressHints }
                        val baseHeightDp = if (actualSize) {
                            keyHeightDp
                        } else {
                            keyHeightDp.coerceIn(38, 56)
                        }
                        fun heightOf(r: Int) = rowHeightsDp?.getOrNull(r) ?: rowScaledKeyHeight(
                            baseHeightDp,
                            layout.rowHeights?.getOrNull(r),
                        )
                        // Rows joined by a spanning key are drawn as one block, the
                        // same way the keyboard does it — see EditorBand. Every other
                        // row is its own Row, which is every row of almost every
                        // layout.
                        val slots = if (hasRowSpans(layout.rows)) {
                            spanSlots(layout.rows, gridWeight)
                        } else {
                            emptyList()
                        }
                        for (band in spanBands(layout.rows)) {
                            if (band.first != band.last) {
                                EditorBand(
                                    slots = slots.filter { it.row in band },
                                    band = band,
                                    kb = kb,
                                    gridWeight = gridWeight,
                                    heights = band.map { heightOf(it) },
                                    selection = selection,
                                    multiSelection = multiSelection,
                                    showShift = showShift,
                                    fontScale = fontScale,
                                    onSelect = onSelect,
                                    cellModifier = cellModifier,
                                    glideActive = glideActive,
                                    face = KeyFace(enterSlot, spaceText, hintsOn),
                                )
                                continue
                            }
                            val r = band.first
                            val row = layout.rows[r]
                            // A row with no keys left in it drew as nothing at
                            // all, so it could be neither seen nor picked to be
                            // deleted (issue #769). It draws a cell that says so.
                            if (row.isEmpty()) {
                                EditorEmptyRow(
                                    kb = kb,
                                    heightDp = heightOf(r),
                                    selected = selection?.row == r,
                                ) { onSelect(KeyRef(r, -1)) }
                                continue
                            }
                            val sidePad = sidePadFor(row, gridWeight)
                            // No spacing between the cells: each takes half the
                            // 3dp gap on either side as an inset instead, as the
                            // keyboard and EditorBand do (#846). Spaced, a row's
                            // gaps came out of the width the weights share, so a
                            // key 12 wide drew narrower than 12 keys 1 wide with
                            // their 11 gaps, and rows of different counts did
                            // not line up.
                            Row {
                                if (sidePad > 0.01f) Spacer(Modifier.weight(sidePad))
                                row.forEachIndexed { c, cellKey ->
                                    // Keyed on the key, not its seat, so a key a
                                    // drop pushed along is the same cell in its new
                                    // seat and can glide there.
                                    key(cellKey) {
                                        EditorKeyCell(
                                            key = cellKey,
                                            kb = kb,
                                            heightDp = heightOf(r),
                                            selected = selection == KeyRef(r, c) || KeyRef(r, c) in multiSelection,
                                            showShift = showShift,
                                            fontScale = fontScale,
                                            face = KeyFace(enterSlot, spaceText, hintsOn),
                                            modifier = Modifier
                                                .weight(cellKey.width)
                                                .padding(horizontal = 1.5.dp)
                                                .then(cellModifier(KeyRef(r, c)))
                                                .seatGlide(glideActive),
                                        ) { onSelect(KeyRef(r, c)) }
                                    }
                                }
                                if (sidePad > 0.01f) Spacer(Modifier.weight(sidePad))
                            }
                        }
                    }
                    DragDecorations(
                        rows = layout.rows,
                        bounds = cellBounds,
                        from = dragFrom,
                        gap = dropGap,
                        pointer = dragPointer,
                        grab = dragGrab,
                        origin = gridOrigin,
                        settle = settle,
                        lift = lift.asState(),
                        kb = kb,
                        showShift = showShift,
                        fontScale = fontScale,
                    )
                }
            }
        }
    }
}

/**
 * The two things a drag draws over the grid: the caret at the gap the key would
 * fall into, and the key itself under the finger — and after the drop, the key
 * on its way down into its seat ([settle]). Nothing at all while both [from]
 * and [settle] are null, which is every frame outside a drag.
 *
 * [pointer] and [origin] arrive as state rather than as values, and are read
 * inside the placement lambdas below: the finger moves every frame, and reading
 * it in composition would recompose the whole grid at that rate. Read where it
 * is, the frame costs one placement of one box. [lift] is read the same way,
 * in the key's layer, so raising and lowering it costs a redraw of that layer.
 */
@Composable
private fun DragDecorations(
    rows: List<List<Key>>,
    bounds: Map<KeyRef, Rect>,
    from: KeyRef?,
    gap: KeyRef?,
    pointer: State<Offset>,
    grab: Offset,
    origin: State<Offset>,
    settle: DropSettle?,
    lift: State<Float>,
    kb: KbTheme,
    showShift: Boolean,
    fontScale: Float,
) {
    if (from == null && settle == null) return
    val density = LocalDensity.current
    // The caret first, so the key in the air rides over it. Upright between
    // two keys; lying flat between two rows, for a drop that makes a row of
    // its own (issue #769).
    val gapBar = gap?.let { caretRect(rows, bounds, it) }
    if (gapBar != null) {
        val thickness = 3.dp
        val flat = gapBar.height == 0f
        val half = with(density) { (thickness / 2).roundToPx() }
        Box(
            modifier = Modifier
                .offset {
                    val at = origin.value
                    IntOffset(
                        (gapBar.left - at.x).roundToInt() - if (flat) 0 else half,
                        (gapBar.top - at.y).roundToInt() - if (flat) half else 0,
                    )
                }
                .size(
                    width = if (flat) with(density) { gapBar.width.toDp() } else thickness,
                    height = if (flat) thickness else with(density) { gapBar.height.toDp() },
                )
                .clip(RoundedCornerShape(2.dp))
                .background(kb.accent),
        )
    }
    // The same cell, drawn once more under the finger, at the size it was
    // drawn in the grid — a key two columns wide picks up two columns wide.
    // After the drop it is the settle's copy, at the size it was picked up at.
    val key: Key
    val seat: Size
    if (from != null) {
        key = rows.getOrNull(from.row)?.getOrNull(from.col) ?: return
        seat = bounds[from]?.size ?: return
    } else {
        val landing = settle ?: return
        key = landing.key
        seat = landing.size
    }
    val shape = kb.keyShape()
    Box(
        modifier = Modifier
            .offset {
                val at = if (from != null) {
                    pointer.value - grab - origin.value
                } else {
                    (settle?.at?.value ?: Offset.Zero) - origin.value
                }
                IntOffset(at.x.roundToInt(), at.y.roundToInt())
            }
            // Lifted: a touch bigger than its seat, casting a shadow on the
            // grid, and a touch see-through so the caret under it still reads.
            // Scaled in its own layer, which nothing measures: the finger's
            // coordinates come from the seat the drag began on, not from here.
            .graphicsLayer {
                val raised = lift.value
                val scale = 1f + (DropLiftScale - 1f) * raised
                scaleX = scale
                scaleY = scale
                shadowElevation = DropLiftElevation.toPx() * raised
                this.shape = shape
                alpha = 1f - 0.1f * raised
            },
    ) {
        EditorKeyCell(
            key = key,
            kb = kb,
            heightDp = with(density) { seat.height.toDp().value.roundToInt() },
            selected = true,
            showShift = showShift,
            fontScale = fontScale,
            modifier = Modifier.width(with(density) { seat.width.toDp() }),
            onClick = {},
        )
    }
}

/** How much bigger than its seat a picked-up key is drawn. */
private const val DropLiftScale = 1.05f

/** The shadow a picked-up key casts on the grid. */
private val DropLiftElevation = 8.dp

/** How long a drop waits for its edit to come back before landing anyway. */
private const val DropLandingWaitMs = 600L

/** The key coming up off the grid, and going back down as it settles: quick, with nothing to overshoot. */
private val DropLiftSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/** The key travelling from where it was let go into its seat. */
private val DropTravelSpring = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = Offset.VisibilityThreshold,
)

/** A pushed-along neighbour sliding into its new seat; see [seatGlide]. */
private val SeatGlideSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * A key let go of, on its way into the seat it was dropped in, or back into
 * the one it came from.
 *
 * [rowsAtDrop] is the grid as it was when the finger lifted: the landing waits
 * for the grid to stop being that, which is the one sign that the edit has
 * reached it. [at] is where the copy in the air is drawn, in root coordinates,
 * and [landed] says the grid has the key in its seat, so the seat can be left
 * empty for the copy to fill rather than drawn twice.
 */
@Stable
private class DropSettle(
    val key: Key,
    val from: KeyRef,
    val to: KeyRef,
    val rowsAtDrop: List<List<Key>>,
    val size: Size,
    val start: Offset,
) {
    val at = Animatable(start, Offset.VectorConverter)
    var landed by mutableStateOf(false)
}

/**
 * Where a cell was last placed, and how far it is still drawn from there.
 *
 * Plain fields and two snapshot values written from `onPlaced`, which runs in
 * the layout pass before the frame is drawn: the cell's first frame in its new
 * seat is already drawn displaced back at the old one, never one frame in the
 * new seat and then a jump back.
 */
private class SeatGlide {
    var seat: Offset? = null
    private var shift by mutableStateOf(Offset.Zero)
    private var progress by mutableFloatStateOf(0f)
    private var job: Job? = null

    fun current(): Offset = if (progress == 0f) Offset.Zero else shift * progress

    fun stop() {
        job?.cancel()
        job = null
        progress = 0f
    }

    fun glideFrom(displacement: Offset, scope: CoroutineScope) {
        job?.cancel()
        shift = displacement
        progress = 1f
        job = scope.launch {
            animate(1f, 0f, animationSpec = SeatGlideSpring) { value, _ -> progress = value }
        }
    }
}

/**
 * Lets a cell a drop pushed along slide to its new seat instead of jumping
 * there, while [active] says a drop is landing.
 *
 * Moved at draw time only. The grid's own drag reads every cell's bounds — the
 * gap a drop would fall into is worked out from them — and a cell translated
 * by its layout or its layer would report somewhere it is only passing
 * through. The node itself is where the layout put it from the first frame.
 */
@Composable
private fun Modifier.seatGlide(active: () -> Boolean): Modifier {
    val scope = rememberCoroutineScope()
    val glide = remember { SeatGlide() }
    return this
        .onPlaced { coordinates ->
            val now = coordinates.positionInParent()
            val was = glide.seat
            glide.seat = now
            if (was == null || was == now) return@onPlaced
            if (active()) glide.glideFrom(was - now + glide.current(), scope) else glide.stop()
        }
        .drawWithContent {
            val shift = glide.current()
            if (shift == Offset.Zero) {
                drawContent()
            } else {
                translate(shift.x, shift.y) { this@drawWithContent.drawContent() }
            }
        }
}

/**
 * The gap a key dropped at [pointer] would fall into: the row nearest the
 * finger, and how many of that row's keys the finger is already past.
 *
 * Measured from where the cells actually landed rather than from the grid
 * arithmetic, so one rule covers a plain row, a row centred against a wider
 * grid, and a row flowing around a spanning key from above. Rows a spanning key
 * reaches into are judged by their own keys where they have any: a two-row
 * Enter covers the row below it, and counting it there would make every drop
 * near the bottom right land a row too high.
 *
 * Returns null only for a grid with nothing drawn in it.
 *
 * Internal rather than private so the unit tests can drive it with a grid of
 * rectangles: this is the whole of "where does the key land", and it is the one
 * part of the drag that no amount of looking at the screen fully pins down.
 */
internal fun dropGapAt(
    rows: List<List<Key>>,
    bounds: Map<KeyRef, Rect>,
    pointer: Offset,
    /**
     * Whether the seams between rows are drop targets of their own (issue
     * #769): a finger on the thin band where two rows meet, or above the first
     * row or below the last, asks for a new row there.
     */
    newRows: Boolean = false,
): KeyRef? {
    var bestRow = -1
    var bestDistance = Float.MAX_VALUE
    // Each drawn row's top and bottom, in order, for the seams between them.
    val spans = mutableListOf<Triple<Int, Float, Float>>()
    for (r in rows.indices) {
        val own = rows[r].indices.mapNotNull { c ->
            bounds[KeyRef(r, c)]?.takeIf { rows[r][c].rowSpan <= 1 }
        }
        val rects = own.ifEmpty { rows[r].indices.mapNotNull { bounds[KeyRef(r, it)] } }
        if (rects.isEmpty()) continue
        val top = rects.minOf { it.top }
        val bottom = rects.maxOf { it.bottom }
        spans += Triple(r, top, bottom)
        val distance = when {
            pointer.y < top -> top - pointer.y
            pointer.y > bottom -> pointer.y - bottom
            else -> 0f
        }
        if (distance < bestDistance) {
            bestDistance = distance
            bestRow = r
        }
    }
    if (bestRow < 0) return null
    if (newRows) newRowSeamAt(spans, rows.size, pointer.y)?.let { return KeyRef(it, NewRowCol) }
    // Keys are written left to right, so the gap is simply how many of them the
    // finger has passed the middle of.
    val gap = rows[bestRow].indices.count { c ->
        bounds[KeyRef(bestRow, c)]?.let { pointer.x > it.center.x } == true
    }
    return KeyRef(bestRow, gap)
}

/**
 * The row a new row would go in front of, for a finger at height [y] on one of
 * the seams of [spans] (row, top, bottom, in order), or null off them. A seam
 * is the gap between two rows and [NewRowSeamShare] of each row beside it; the
 * first row's top edge and the last row's bottom edge are seams too, reaching
 * out past the grid.
 */
private fun newRowSeamAt(spans: List<Triple<Int, Float, Float>>, rowCount: Int, y: Float): Int? {
    if (spans.isEmpty()) return null
    for ((index, span) in spans.withIndex()) {
        val (row, top, bottom) = span
        val edge = (bottom - top) * NewRowSeamShare
        val previous = spans.getOrNull(index - 1)
        val above = previous?.let { it.third - (it.third - it.second) * NewRowSeamShare } ?: Float.NEGATIVE_INFINITY
        if (y >= above && y < top + edge) return row
    }
    val (lastRow, lastTop, lastBottom) = spans.last()
    return if (y > lastBottom - (lastBottom - lastTop) * NewRowSeamShare) minOf(lastRow + 1, rowCount) else null
}

/** How much of a row's height, at its top and bottom edge, counts as the seam a new row is dropped on. */
private const val NewRowSeamShare = 0.12f

/**
 * Where the caret for [gap] is drawn: a hairline down the left edge of the key
 * that would be pushed along, or down the right edge of the row when the key is
 * going on the end. Root coordinates, and zero width — the caller gives it one.
 * A new row's caret is zero height instead, lying across the grid on the seam.
 */
internal fun caretRect(rows: List<List<Key>>, bounds: Map<KeyRef, Rect>, gap: KeyRef): Rect? {
    if (gap.col == NewRowCol) return seamRect(rows, bounds, gap.row)
    val row = rows.getOrNull(gap.row) ?: return null
    val rects = row.indices.mapNotNull { bounds[KeyRef(gap.row, it)] }
    if (rects.isEmpty()) return null
    // The gap after the last key has no key to sit in front of. Asked of the
    // map by index it would find whatever a longer grid left there, so the
    // index is checked against the row rather than against what was recorded.
    val ahead = if (gap.col in row.indices) bounds[KeyRef(gap.row, gap.col)] else null
    val x = ahead?.left ?: rects.maxOf { it.right }
    return Rect(left = x, top = rects.minOf { it.top }, right = x, bottom = rects.maxOf { it.bottom })
}

/** The flat caret for a new row in front of row [at]: across the grid, midway in the gap. */
private fun seamRect(rows: List<List<Key>>, bounds: Map<KeyRef, Rect>, at: Int): Rect? {
    val all = rows.indices.flatMap { r -> rows[r].indices.mapNotNull { bounds[KeyRef(r, it)] } }
    if (all.isEmpty()) return null
    fun edges(r: Int): Pair<Float, Float>? {
        val rects = rows.getOrNull(r)?.indices?.mapNotNull { bounds[KeyRef(r, it)] }.orEmpty()
        return if (rects.isEmpty()) null else rects.minOf { it.top } to rects.maxOf { it.bottom }
    }
    val below = (at until rows.size).firstNotNullOfOrNull { edges(it) }
    val above = (at - 1 downTo 0).firstNotNullOfOrNull { edges(it) }
    val y = when {
        above != null && below != null -> (above.second + below.first) / 2f
        below != null -> below.first - 2f
        above != null -> above.second + 2f
        else -> return null
    }
    return Rect(left = all.minOf { it.left }, top = y, right = all.maxOf { it.right }, bottom = y)
}

/**
 * The preview's answer to a run of rows joined by a spanning key.
 *
 * Same shape as the keyboard's own `KeyBand` and for the same reason: a key
 * covering two rows has to be a child of something that covers both of them.
 * Placed by hand, so the 3dp/4dp gaps the `Row`/`Column` above get from their
 * arrangements are taken here as a per-cell inset and a per-row pitch instead.
 */
@Composable
internal fun EditorBand(
    slots: List<KeySlot>,
    band: IntRange,
    kb: KbTheme,
    gridWeight: Float,
    heights: List<Int>,
    selection: KeyRef?,
    showShift: Boolean,
    fontScale: Float,
    onSelect: (KeyRef) -> Unit,
    multiSelection: Set<KeyRef> = emptySet(),
    /**
     * What the grid adds to every cell: where it landed, and the drag that can
     * pick it up. A band's cells are placed by hand, so they cannot inherit it
     * from the rows around them the way the ordinary rows do.
     */
    cellModifier: (KeyRef) -> Modifier = { Modifier },
    /** Whether a cell that has just changed seat glides there; see [seatGlide]. */
    glideActive: () -> Boolean = { false },
    face: KeyFace = KeyFace(),
) {
    val weight = maxOf(gridWeight, slots.maxOfOrNull { it.end } ?: 0f)
    val gap = with(LocalDensity.current) { 4.dp.roundToPx() }
    val pitch = with(LocalDensity.current) { heights.map { it.dp.roundToPx() + gap } }
    val tops = IntArray(pitch.size + 1).also {
        for (i in pitch.indices) it[i + 1] = it[i] + pitch[i]
    }
    Layout(
        content = {
            for (slot in slots) {
                val ref = KeyRef(slot.row, slot.col)
                // Keyed like the plain rows' cells, and for the same reason.
                key(slot.key) {
                    EditorKeyCell(
                        key = slot.key,
                        kb = kb,
                        heightDp = heights[slot.row - band.first],
                        selected = selection == ref || ref in multiSelection,
                        showShift = showShift,
                        fontScale = fontScale,
                        face = face,
                        // Half the 3dp the spaced rows put between neighbours, on
                        // each side, so a band's keys read at the same size as the
                        // rows above and below it.
                        modifier = Modifier
                            .padding(horizontal = 1.5.dp)
                            .then(cellModifier(ref))
                            .seatGlide(glideActive),
                    ) { onSelect(ref) }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val unit = if (weight > 0f) width / weight else 0f
        val lefts = IntArray(measurables.size)
        val placeables = measurables.mapIndexed { index, measurable ->
            val slot = slots[index]
            val row = slot.row - band.first
            val left = (unit * slot.x).roundToInt()
            val right = (unit * slot.end).roundToInt()
            lefts[index] = left
            measurable.measure(
                Constraints.fixed(
                    width = (right - left).coerceIn(0, width),
                    // Minus the trailing gap, so a one-row key is exactly the
                    // height the Row path would give it.
                    height = (tops[row + slot.span] - tops[row] - gap).coerceAtLeast(0),
                ),
            )
        }
        layout(width, (tops.last() - gap).coerceAtLeast(0)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(lefts[index], tops[slots[index].row - band.first])
            }
        }
    }
}

@Composable
internal fun EditorKeyCell(
    key: Key,
    kb: KbTheme,
    heightDp: Int,
    selected: Boolean,
    showShift: Boolean,
    /** The layout's own label-size multiplier; 1.0 for a layout that sets none. */
    fontScale: Float,
    modifier: Modifier = Modifier,
    face: KeyFace = KeyFace(),
    onClick: () -> Unit,
) {
    // A panel component's cell: no key face, a hatched stand-in for the live
    // component and its name, so the layout reads as "the grid goes here".
    (key.action as? KeyAction.Field)?.let { field ->
        EditorFieldCell(field.kind, kb, heightDp, selected, modifier, onClick)
        return
    }
    val background = when {
        key.action == KeyAction.Enter -> kb.enterKey
        key.action != KeyAction.Text -> kb.modifierKey
        else -> kb.key
    }
    val foreground = when {
        key.action == KeyAction.Enter -> kb.enterKeyText
        key.action != KeyAction.Text -> kb.modifierKeyText
        else -> kb.keyText
    }
    // Already the user's own key height, clamped or not and scaled by this row's
    // multiplier, decided by the caller — which is the one place that knows which
    // row this cell is in.
    val height = heightDp.dp
    // Read here rather than inside the ifBlank lambda below, which is not a
    // composable and so cannot reach a resource itself.
    val spaceLabel = stringResource(R.string.layout_editor_space_key_label)
    Box(
        modifier = modifier
            .height(height)
            .clip(kb.keyShape())
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(2.dp, kb.accent, kb.keyShape())
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Shown verbatim rather than uppercased the way the real keyboard does
        // under shift: the stored label is the thing being edited.
        val primary = if (showShift) key.shiftLabel ?: key.label.uppercase() else key.label
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The face the keyboard draws from an icon slot: the shift arrow,
            // the field's own enter, the globe, a tool's toolbar icon. Through
            // the slot, so an icon pack redresses the preview with the board.
            val slot = editorKeySlot(key, face.enterSlot)
            // A tool key draws the tool's icon on the keyboard, so it draws one
            // here too — the rule this whole pair of helpers exists to keep.
            val cellIcon = KeyIcons.byName(key.icon)
                ?: KeyIcons.byName(actionIconName(key.action))
                ?: (key.action as? KeyAction.Tool)
                    ?.takeIf { key.label.isBlank() }
                    ?.let { toolIconFor(it.tool) }
                // A text-editing key wears its operation's icon, as on the board.
                ?: (key.action as? KeyAction.Edit)
                    ?.takeIf { key.label.isBlank() }
                    ?.let { textEditIcon(it.op) }
                // Tab, the arrows, and a layout, layer, broadcast or key-send
                // key: the glyph the board draws where the label is blank.
                ?: key.action
                    .takeIf { key.label.isBlank() || key.label == it.fallbackLabel() }
                    ?.let(KeyIcons::forAction)
            if (slot != null) {
                SlotIcon(
                    slot,
                    contentDescription = primary.ifBlank { null },
                    tint = foreground,
                    modifier = Modifier.size(20.dp),
                )
            } else if (cellIcon != null) {
                Icon(
                    cellIcon,
                    contentDescription = primary.ifBlank { key.icon },
                    tint = foreground,
                    modifier = Modifier.size(20.dp),
                )
            } else if (key.action == KeyAction.Space && face.spaceText != null) {
                // The language name the board prints, small and faded as it
                // is there; a split spacebar's empty half prints nothing.
                Text(
                    text = if (key.label.isEmpty()) "" else face.spaceText,
                    color = foreground.copy(alpha = 0.5f),
                    fontSize = (EditorSpaceSp * fontScale).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = primary.ifBlank { actionGlyph(key.action, spaceLabel) },
                    color = foreground,
                    fontSize = labelSize(primary, key.drawnLabelScale(), fontScale),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
            // Probhat and Jatiya put half the alphabet on shiftLabel, and many
            // fonts render a bare matra (া, ি) as an orphaned mark. Showing the
            // pair identifies the key even when the top glyph is ambiguous. The
            // real keyboard swaps on shift instead, so this is editor-only.
            val shiftLabel = key.shiftLabel
            if (!showShift && shiftLabel != null) {
                Text(
                    text = shiftLabel,
                    color = foreground.copy(alpha = 0.55f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Issue #339: what each flick types, at the edge it is flicked
        // towards. The keyboard only shows the cross under a finger, which
        // the editor has none of, so a kana pad here was a grid of あ, か, さ
        // with no way to see the forty other kana it types.
        // An arm that runs an action (issue #549) wears that action's icon,
        // and the shift view shows each arm's own shift form (issue #550).
        for (direction in FlickDirection.entries) {
            val armModifier = Modifier
                .align(flickAlignment(direction))
                .padding(horizontal = 3.dp, vertical = 1.dp)
            when (val arm = key.flickArm(direction)) {
                null -> Unit
                is FlickArm.Text -> Text(
                    text = if (showShift) key.flickShift[direction] ?: arm.text.uppercase() else arm.text,
                    color = foreground.copy(alpha = 0.6f),
                    fontSize = (EditorFlickSp * fontScale).sp,
                    maxLines = 1,
                    modifier = armModifier,
                )
                is FlickArm.Action -> {
                    val action = arm.alternate.action
                    val armIcon = flickArmIcon(arm.alternate)
                    if (armIcon != null && arm.alternate.label.isBlank()) {
                        Icon(
                            armIcon,
                            contentDescription = null,
                            tint = foreground.copy(alpha = 0.6f),
                            modifier = armModifier.size((EditorFlickSp * 1.4f * fontScale).dp),
                        )
                    } else {
                        Text(
                            text = arm.alternate.label.ifBlank { actionGlyph(action, spaceLabel) },
                            color = foreground.copy(alpha = 0.6f),
                            fontSize = (EditorFlickSp * fontScale).sp,
                            maxLines = 1,
                            modifier = armModifier,
                        )
                    }
                }
            }
        }
        // The corner hint, by the keyboard's own rule (issue #769): an icon
        // hint, else the author's hint text, else the first alternate on a key
        // whose hold opens the popup. An up-right flick owns that corner.
        val showHint = (face.hintsOn || key.forceHint) && !key.hideHint &&
            key.flickArm(FlickDirection.UP_RIGHT) == null
        val hintIcon = KeyIcons.byName(key.iconHint)?.takeIf { showHint }
        val hintText = if (!showHint || hintIcon != null) {
            null
        } else {
            key.hint?.takeIf { it.isNotBlank() }
                ?: key.longPress.firstOrNull()?.takeIf { key.opensAlternatesPopup() }
                    ?.let { if (showShift) it.uppercase() else it }
        }
        if (hintIcon != null) {
            Icon(
                hintIcon,
                contentDescription = null,
                tint = foreground.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 2.dp)
                    .size((EditorHintSp * fontScale).dp),
            )
        } else if (hintText != null) {
            Text(
                text = hintText,
                color = foreground.copy(alpha = 0.55f),
                fontSize = (EditorHintSp * fontScale).sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 3.dp, top = 1.dp),
            )
        }
        // Issue #340: a key that becomes 小゛゜ after a kana says so.
        if (key.kanaVariantWhileComposing) {
            Text(
                text = KanaVariantKeyLabel,
                color = foreground.copy(alpha = 0.6f),
                fontSize = (EditorFlickSp * fontScale).sp,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 3.dp, vertical = 1.dp),
            )
        }
    }
}

/** Where a flick's glyph sits on a preview cell: the edge or corner it is flicked towards. */
private fun flickAlignment(direction: FlickDirection): Alignment = when (direction) {
    FlickDirection.LEFT -> Alignment.CenterStart
    FlickDirection.UP -> Alignment.TopCenter
    FlickDirection.RIGHT -> Alignment.CenterEnd
    FlickDirection.DOWN -> Alignment.BottomCenter
    FlickDirection.UP_LEFT -> Alignment.TopStart
    FlickDirection.UP_RIGHT -> Alignment.TopEnd
    FlickDirection.DOWN_LEFT -> Alignment.BottomStart
    FlickDirection.DOWN_RIGHT -> Alignment.BottomEnd
}

/**
 * The icon an action arm draws in the preview and on the flick pad: the one
 * the author named, the action's own, a tool's, or a text-editing operation's.
 * Null when the arm wears a label instead, or nothing draws for it.
 */
private fun flickArmIcon(alternate: KeyAlternate): ImageVector? {
    val action = alternate.action
    return KeyIcons.byName(alternate.icon)
        ?: KeyIcons.byName(actionIconName(action))
        ?: (action as? KeyAction.Tool)?.let { toolIconFor(it.tool) }
        ?: (action as? KeyAction.Edit)?.let { textEditIcon(it.op) }
        ?: KeyIcons.forAction(action)
}

/** The preview's size for a flick glyph: small enough to leave the key's own label alone. */
private const val EditorFlickSp = 9f

/** The preview's corner hint, a step under a flick glyph as the board's is under its arms. */
private const val EditorHintSp = 8f

/** The spacebar's language name in the preview. */
private const val EditorSpaceSp = 10f

/**
 * What a preview cell draws beyond the key itself (issue #769): the enter key's
 * slot in the field the preview is drawn for, the spacebar's words, and whether
 * the corner hints are on. One value, so the grid hands every cell the same.
 */
@Immutable
internal data class KeyFace(
    val enterSlot: String = IconSlots.KEY_ENTER,
    val spaceText: String? = null,
    val hintsOn: Boolean = false,
)

/** A row with no keys in it, drawn so it can be seen and picked (issue #769). */
@Composable
private fun EditorEmptyRow(kb: KbTheme, heightDp: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .clip(kb.keyShape())
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) kb.accent else kb.keyText.copy(alpha = 0.3f),
                kb.keyShape(),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.layout_editor_empty_row_label),
            color = kb.keyText.copy(alpha = 0.6f),
            fontSize = 12.sp,
        )
    }
}

/**
 * ".com" and "https://" are legal labels and would blow a cell open at full
 * size, so the label steps down as it grows.
 *
 * The preview's cell is a fraction of a real key, so these are its own numbers
 * rather than the keyboard's — but the two multipliers on top are the same ones,
 * applied the same way round: a key's own [Key.labelScale] replaces the
 * automatic size (which here is the length rule), and the layout's scales
 * whatever came out of it.
 */
private fun labelSize(label: String, keyScale: Float?, layoutScale: Float) = when {
    keyScale != null -> EditorLetterSp * keyScale
    label.length <= 1 -> EditorLetterSp
    label.length <= 3 -> 13f
    else -> 11f
}.times(layoutScale).sp

/** The preview cell's size for a one-glyph label, and the unit a scale multiplies. */
private const val EditorLetterSp = 16f

/**
 * The [KeyIcons] name an action always draws with, whatever its stored label
 * says, or null for an action that draws its label.
 *
 * The keyboard itself ignores the label on these two and draws an icon (see
 * `KeyContent`), so the grid has to as well. The globe key is why: its label is
 * the emoji `🌐`, and drawing that verbatim gave the editor a colour glyph in
 * whichever house style the device's emoji font uses, beside a row of flat
 * monochrome keys, and it was never what the keyboard put on screen anyway.
 */
internal fun actionIconName(action: KeyAction): String? = when (action) {
    KeyAction.LanguageSwitch -> "language"
    KeyAction.InputMethodPicker, is KeyAction.SwitchInputMethod -> "keyboard"
    KeyAction.Emoji -> "emoji"
    else -> null
}

/**
 * What to draw for an action key whose label is blank, like a keypad spacebar.
 *
 * Only the handful the keyboard draws from an icon slot are answered here; every
 * other action defers to [KeyAction.fallbackLabel], which is what the keyboard
 * itself falls back to. The two used to disagree — this one ended in a catch-all
 * "·" — and a Tab or Ctrl key therefore looked present in the editor and came
 * out invisible on the keyboard.
 *
 * [spaceLabel] is the one glyph here that is a word, so the caller reads it and
 * hands it over.
 */
internal fun actionGlyph(action: KeyAction, spaceLabel: String): String = when (action) {
    KeyAction.Space -> spaceLabel
    KeyAction.Enter -> "⏎"
    KeyAction.Delete -> "⌫"
    KeyAction.ForwardDelete -> "⌦"
    KeyAction.Shift -> "⇧"
    else -> action.fallbackLabel()
}


// ---------------------------------------------------------------------------
// Key edit sheet
// ---------------------------------------------------------------------------

/**
 * One pickable action, with whatever extra input it needs.
 *
 * The picker renders from this list rather than from `KeyAction`'s members, so
 * an action that ships later — a keycode sender, a tool launcher — is one entry
 * here plus its serialization, and no change to the editor at all. Building it
 * off an enum's entries would bake in "an action is a bare value" and break the
 * moment payloads land.
 */
internal data class KeyActionOption(
    @StringRes val titleRes: Int,
    @StringRes val groupRes: Int,
    @StringRes val detailRes: Int,
    val build: () -> KeyAction,
    val matches: (KeyAction) -> Boolean,
)

internal val KeyActionCatalog: List<KeyActionOption> = listOf(
    KeyActionOption(
        R.string.layout_editor_action_text_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_text_detail,
        { KeyAction.Text }, { it == KeyAction.Text },
    ),
    KeyActionOption(
        R.string.layout_editor_action_shift_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_shift_detail,
        { KeyAction.Shift }, { it == KeyAction.Shift },
    ),
    KeyActionOption(
        R.string.layout_editor_action_caps_lock_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_caps_lock_detail,
        { KeyAction.CapsLock }, { it == KeyAction.CapsLock },
    ),
    KeyActionOption(
        R.string.layout_editor_action_delete_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_delete_detail,
        { KeyAction.Delete }, { it == KeyAction.Delete },
    ),
    KeyActionOption(
        R.string.layout_editor_action_forward_delete_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_forward_delete_detail,
        { KeyAction.ForwardDelete }, { it == KeyAction.ForwardDelete },
    ),
    KeyActionOption(
        R.string.layout_editor_action_space_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_space_detail,
        { KeyAction.Space }, { it == KeyAction.Space },
    ),
    KeyActionOption(
        R.string.layout_editor_action_enter_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_enter_detail,
        { KeyAction.Enter }, { it == KeyAction.Enter },
    ),
    KeyActionOption(
        R.string.layout_editor_action_newline_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_newline_detail,
        { KeyAction.Newline }, { it == KeyAction.Newline },
    ),
    // The Japanese flick pad's 小゛゜ key. It shipped on that pad with no entry
    // here, so the editor called it "Unknown action" and no other grid could
    // be given one.
    KeyActionOption(
        R.string.layout_editor_action_kana_variant_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_kana_variant_detail,
        { KeyAction.KanaVariant }, { it == KeyAction.KanaVariant },
    ),
    // The 나랏글 pad's two letter-changing keys (issue #599).
    KeyActionOption(
        R.string.layout_editor_action_add_stroke_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_add_stroke_detail,
        { KeyAction.AddStroke }, { it == KeyAction.AddStroke },
    ),
    KeyActionOption(
        R.string.layout_editor_action_double_consonant_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_double_consonant_detail,
        { KeyAction.DoubleConsonant }, { it == KeyAction.DoubleConsonant },
    ),
    // The 九宫格 pinyin pad's left column (issue #635).
    KeyActionOption(
        R.string.layout_editor_action_reading_column_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_reading_column_detail,
        { KeyAction.ReadingColumn }, { it == KeyAction.ReadingColumn },
    ),
    // The Simplified/Traditional switch the Pinyin layout's '词 key holds (#962).
    KeyActionOption(
        R.string.layout_editor_action_han_script_title,
        R.string.layout_editor_action_group_typing,
        R.string.layout_editor_action_han_script_detail,
        { KeyAction.HanScript }, { it == KeyAction.HanScript },
    ),
    KeyActionOption(
        R.string.layout_editor_action_symbols_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_symbols_detail,
        { KeyAction.Symbols }, { it == KeyAction.Symbols },
    ),
    KeyActionOption(
        R.string.layout_editor_action_letters_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_letters_detail,
        { KeyAction.Letters }, { it == KeyAction.Letters },
    ),
    KeyActionOption(
        R.string.layout_editor_action_emoji_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_emoji_detail,
        { KeyAction.Emoji }, { it == KeyAction.Emoji },
    ),
    KeyActionOption(
        R.string.layout_editor_action_toolbox_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_toolbox_detail,
        { KeyAction.Toolbox }, { it == KeyAction.Toolbox },
    ),
    KeyActionOption(
        R.string.layout_editor_action_switch_layout_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_switch_layout_detail,
        { KeyAction.LanguageSwitch }, { it == KeyAction.LanguageSwitch },
    ),
    KeyActionOption(
        R.string.layout_editor_action_input_method_picker_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_input_method_picker_detail,
        { KeyAction.InputMethodPicker }, { it == KeyAction.InputMethodPicker },
    ),
    // The id is a placeholder: the sheet opens the keyboard picker the moment
    // this is chosen, the way the layout entry does.
    KeyActionOption(
        R.string.layout_editor_action_switch_ime_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_switch_ime_detail,
        { KeyAction.SwitchInputMethod() }, { it is KeyAction.SwitchInputMethod },
    ),
    KeyActionOption(
        R.string.layout_editor_action_fn_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_fn_detail,
        { KeyAction.Fn }, { it == KeyAction.Fn },
    ),
    // The id is a placeholder: the sheet opens the layout picker the moment
    // this is chosen, the way the tool entry does.
    KeyActionOption(
        R.string.layout_editor_action_layout_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_layout_detail,
        { KeyAction.Layout() }, { it is KeyAction.Layout },
    ),
    // Same placeholder arrangement: the mode picker follows straight on.
    KeyActionOption(
        R.string.layout_editor_action_mode_title,
        R.string.layout_editor_action_group_layers,
        R.string.layout_editor_action_mode_detail,
        { KeyAction.Mode() }, { it is KeyAction.Mode },
    ),
    KeyActionOption(
        R.string.layout_editor_action_ctrl_title,
        R.string.layout_editor_action_group_modifiers,
        R.string.layout_editor_action_modifier_detail,
        { KeyAction.Mod(ModifierKey.CTRL) },
        { it is KeyAction.Mod && it.key == ModifierKey.CTRL },
    ),
    KeyActionOption(
        R.string.layout_editor_action_alt_title,
        R.string.layout_editor_action_group_modifiers,
        R.string.layout_editor_action_modifier_detail,
        { KeyAction.Mod(ModifierKey.ALT) },
        { it is KeyAction.Mod && it.key == ModifierKey.ALT },
    ),
    KeyActionOption(
        R.string.layout_editor_action_meta_title,
        R.string.layout_editor_action_group_modifiers,
        R.string.layout_editor_action_meta_detail,
        { KeyAction.Mod(ModifierKey.META) },
        { it is KeyAction.Mod && it.key == ModifierKey.META },
    ),
    KeyActionOption(
        R.string.layout_editor_action_tab_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_tab_detail,
        { KeyAction.SendKey(KEYCODE_TAB) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_TAB },
    ),
    KeyActionOption(
        R.string.layout_editor_action_escape_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_escape_detail,
        { KeyAction.SendKey(KEYCODE_ESCAPE) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_ESCAPE },
    ),
    KeyActionOption(
        R.string.layout_editor_action_arrow_up_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_arrow_up_detail,
        { KeyAction.SendKey(KEYCODE_DPAD_UP) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_DPAD_UP },
    ),
    KeyActionOption(
        R.string.layout_editor_action_arrow_down_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_arrow_down_detail,
        { KeyAction.SendKey(KEYCODE_DPAD_DOWN) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_DPAD_DOWN },
    ),
    KeyActionOption(
        R.string.layout_editor_action_arrow_left_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_arrow_left_detail,
        { KeyAction.SendKey(KEYCODE_DPAD_LEFT) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_DPAD_LEFT },
    ),
    KeyActionOption(
        R.string.layout_editor_action_arrow_right_title,
        R.string.layout_editor_action_group_send_key,
        R.string.layout_editor_action_arrow_right_detail,
        { KeyAction.SendKey(KEYCODE_DPAD_RIGHT) },
        { it is KeyAction.SendKey && it.keyCode == KEYCODE_DPAD_RIGHT },
    ),
    // A text-editing operation. The entry's LEFT is a placeholder: the sheet
    // opens the operation picker the moment this is chosen.
    KeyActionOption(
        R.string.layout_editor_action_edit_title,
        R.string.layout_editor_action_group_text_edit,
        R.string.layout_editor_action_edit_detail,
        { KeyAction.Edit(TextEditAction.LEFT) }, { it is KeyAction.Edit },
    ),
    // Empty until the sheet that opens the moment this is chosen fills it.
    KeyActionOption(
        R.string.layout_editor_action_sequence_title,
        R.string.layout_editor_action_group_text_edit,
        R.string.layout_editor_action_sequence_detail,
        { KeyAction.EditSequence(emptyList()) }, { it is KeyAction.EditSequence },
    ),
    KeyActionOption(
        R.string.layout_editor_action_braille_dot_title,
        R.string.layout_editor_action_group_chorded,
        R.string.layout_editor_action_braille_dot_detail,
        { KeyAction.BrailleDot(1) }, { it is KeyAction.BrailleDot },
    ),
    KeyActionOption(
        R.string.layout_editor_action_morse_dot_title,
        R.string.layout_editor_action_group_chorded,
        R.string.layout_editor_action_morse_dot_detail,
        { KeyAction.MorseDot }, { it == KeyAction.MorseDot },
    ),
    KeyActionOption(
        R.string.layout_editor_action_morse_dash_title,
        R.string.layout_editor_action_group_chorded,
        R.string.layout_editor_action_morse_dash_detail,
        { KeyAction.MorseDash }, { it == KeyAction.MorseDash },
    ),
    // The tool the entry builds is a placeholder: the sheet opens the tool picker
    // the moment this is chosen, so nothing lands on a key still saying "emoji"
    // unless the user picked emoji.
    KeyActionOption(
        R.string.layout_editor_action_tool_title,
        R.string.layout_editor_action_group_other,
        R.string.layout_editor_action_tool_detail,
        { KeyAction.Tool() }, { it is KeyAction.Tool },
    ),
    KeyActionOption(
        R.string.layout_editor_action_broadcast_title,
        R.string.layout_editor_action_group_other,
        R.string.layout_editor_action_broadcast_detail,
        { KeyAction.Broadcast("") }, { it is KeyAction.Broadcast },
    ),
    KeyActionOption(
        R.string.layout_editor_action_none_title,
        R.string.layout_editor_action_group_other,
        R.string.layout_editor_action_none_detail,
        { KeyAction.None }, { it == KeyAction.None },
    ),
)

// Written as numbers rather than KeyEvent.KEYCODE_* so this file, which is
// otherwise pure settings UI, needs no android.view import.
private const val KEYCODE_TAB = 61
private const val KEYCODE_ESCAPE = 111
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22

/**
 * One key's settings, on the page under the grid (issue #769).
 *
 * These were a sheet pulled up over the page, which hid the very grid being
 * edited the moment it was scrolled, and put the corner hint at the bottom of
 * a long scroll where the issues asking for it showed nobody found it. They
 * are groups of the page now: what the key is and does, then its press and
 * hold with the popup as the keyboard draws it and the corner hint beside it,
 * then folds for flicks, size and the rarer switches, each one closed until
 * wanted and remembered once opened.
 *
 * Still named for the sheet it was, since three screens hand it a key.
 */
@Composable
internal fun KeyEditSheet(
    key: Key,
    ref: KeyRef,
    rowSize: Int,
    /** Rows in this layer, so the span stepper cannot reach past the last one. */
    rowCount: Int,
    gridWeight: Float,
    otherWidthsInRow: Float,
    /**
     * One field of this key, changed. Takes a transform rather than a finished
     * key so the change lands on whatever is *stored* — see the call site: [key]
     * here is a read of the settings flow and can be a frame or more behind the
     * edit the user made just before this one.
     */
    onChange: ((Key) -> Key) -> Unit,
    onMove: (Int) -> Unit,
    /**
     * The key, one row up (-1) or down (+1). Separate from [onMove] because it
     * is a different edit: within a row a key swaps places with its neighbour,
     * while across rows it leaves one row and joins another at whichever column
     * that row has room for — see [rowMoveTarget].
     */
    onMoveRow: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    /** Done with this key: the selection goes, and the section with it. */
    onDismiss: () -> Unit,
    /** The key, whole, onto the clipboard; null offers no Copy (issue #769). */
    onCopy: (() -> Unit)? = null,
    /** Keys from the clipboard, straight after this one; null offers no Paste. */
    onPaste: (() -> Unit)? = null,
    /** The actions offered; a panel layout narrows it and adds its components. */
    catalog: List<KeyActionOption> = KeyActionCatalog,
    /** The components a panel layout may place; null for a typing layout. */
    fieldKinds: List<PanelFieldKind>? = null,
    /** The secondary layouts an "Open a layout" key may name; see [KeyAction.Layout]. */
    secondaryLayouts: List<LayoutSpec> = emptyList(),
    /** The modes a "Toggle a keyboard mode" key may name; see [KeyAction.Mode]. */
    keyboardModes: List<KeyboardMode> = emptyList(),
    /**
     * The layout types Japanese, so its keys are offered
     * [Key.kanaVariantWhileComposing]. Everywhere else the switch would name a
     * key no reading ever reaches.
     */
    kanaPad: Boolean = false,
    /** The heading; null names the key by its row and place in it. */
    title: String? = null,
    /** A new key straight after this one, in the same row; null offers none. */
    onInsertAfter: (() -> Unit)? = null,
    /**
     * This key as the keyboard draws it: its popup with whatever the settings
     * add to it (the hold shortcuts, the accents, a field's endings). Null
     * falls back to the key as stored.
     */
    boardKey: Key? = null,
    /** The keyboard's own column count for popups, 0 for automatic. */
    popupColumns: Int = 0,
    /** The keyboard draws a popup's first entries on its bottom row. */
    popupNearestFirst: Boolean = false,
) {
    var pickingAction by remember { mutableStateOf(false) }
    // Which tool this key opens, when the picker put a tool action on it.
    var pickingTool by remember { mutableStateOf(false) }
    // Which secondary layout it shows, when the picker put a layout action on it.
    var pickingLayout by remember { mutableStateOf(false) }
    // Which keyboard mode it toggles, for a mode action.
    var pickingMode by remember { mutableStateOf(false) }
    // Which other keyboard app it switches to, for a switch-keyboard action.
    var pickingIme by remember { mutableStateOf(false) }
    // Which operation an edit key runs, and which component a field cell hosts.
    var pickingEdit by remember { mutableStateOf(false) }
    var pickingSequence by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf(false) }
    // Which icon the picker is choosing: null while it is shut, false for the
    // key's own icon, true for its corner hint.
    var pickingIcon by remember { mutableStateOf<Boolean?>(null) }
    // A component's cell has a size and a kind and nothing else to edit.
    val isField = key.action is KeyAction.Field
    // Held as text so a half-typed entry survives; parsed on every change.
    var alternates by remember(ref) { mutableStateOf(key.longPress.joinToString(" ")) }

    KeyEditHeader(
        title = title ?: stringResource(R.string.layout_editor_key_position_title, ref.row + 1, ref.col + 1),
        ref = ref,
        rowSize = rowSize,
        rowCount = rowCount,
        onMove = onMove,
        onMoveRow = onMoveRow,
        onDuplicate = onDuplicate,
        onInsertAfter = onInsertAfter,
        onDelete = onDelete,
        onDismiss = onDismiss,
        onCopy = onCopy,
        onPaste = onPaste,
    )

    // What the key is and what a tap does.
    SettingsGroup(stringResource(R.string.layout_editor_key_section_main)) {
        if (!isField) item {
            SheetField(
                label = stringResource(R.string.layout_editor_key_label_label),
                value = key.label,
                supporting = stringResource(R.string.layout_editor_key_label_hint),
                resetKey = ref,
            ) { text -> onChange { it.copy(label = text) } }
        }
        if (!isField) item {
            SheetField(
                label = stringResource(R.string.layout_editor_key_output_label),
                value = key.output.orEmpty(),
                // Says what a blank field types, and — once there is a label to
                // name — what *this* key types, because "the key types the label"
                // read as a rule about some other key to the person who filed
                // issue #16. A key with neither is called out as well: repair
                // deletes it the moment the layout is turned on.
                supporting = outputFieldSupport(key),
                resetKey = ref,
                // While the key stands for a set of letters, its output is the
                // first of them and is not separately editable. Shown rather
                // than hidden: the value is still the answer to "what does this
                // key type", and a field that vanishes teaches nobody the rule.
                enabled = !key.isAmbiguous(),
            ) { text -> onChange { it.copy(output = text.ifBlank { null }) } }
        }
        if (!isField && key.action == KeyAction.Text) item(level = SettingLevel.STANDARD) {
            LettersField(
                key = key,
                ref = ref,
                onChange = onChange,
                // The alternates field holds its text here, so the "put the
                // letters in the popup" button has to move that text too or
                // the field it edits would show the old list back.
                onAlternatesChanged = { alternates = it.joinToString(" ") },
            )
        }
        if (!isField) item(level = SettingLevel.STANDARD) {
            SheetField(
                label = stringResource(R.string.layout_editor_key_shift_label_label),
                value = key.shiftLabel.orEmpty(),
                supporting = stringResource(R.string.layout_editor_key_shift_label_hint),
                resetKey = ref,
            ) { text -> onChange { it.copy(shiftLabel = text.ifBlank { null }) } }
        }
        item {
            val option = catalog.firstOrNull { it.matches(key.action) }
            NavRow(
                title = R.string.layout_editor_action_row_title,
                subtitle = option?.let { stringResource(it.detailRes) },
                value = stringResource(option?.titleRes ?: R.string.layout_editor_action_unknown),
            ) { pickingAction = true }
        }
        // Broadcast keys carry a free-form action string the automation app
        // listens for; every other action is self-contained.
        (key.action as? KeyAction.Broadcast)?.let { broadcast ->
            item {
                SheetField(
                    label = stringResource(R.string.layout_editor_broadcast_field_label),
                    value = broadcast.action,
                    supporting = stringResource(R.string.layout_editor_broadcast_field_hint),
                    resetKey = ref,
                ) { text -> onChange { it.copy(action = KeyAction.Broadcast(text.trim())) } }
            }
        }
        // A tool key carries which tool it opens. A row rather than a field:
        // there are forty of them and they have names, not values.
        (key.action as? KeyAction.Tool)?.let { toolAction ->
            item {
                NavRow(
                    title = R.string.layout_editor_tool_row_title,
                    subtitle = stringResource(R.string.layout_editor_tool_row_subtitle),
                    value = stringResource(toolTitle(toolAction.tool)),
                ) { pickingTool = true }
            }
        }
        // A layout key carries which secondary layout it shows.
        (key.action as? KeyAction.Layout)?.let { layoutAction ->
            item {
                NavRow(
                    title = R.string.layout_editor_layout_row_title,
                    subtitle = stringResource(R.string.layout_editor_layout_row_subtitle),
                    value = secondaryLayouts.firstOrNull { it.id == layoutAction.id }?.name
                        ?: stringResource(R.string.layout_editor_layout_row_unset),
                ) { pickingLayout = true }
            }
        }
        // A mode key carries which keyboard mode it toggles.
        (key.action as? KeyAction.Mode)?.let { modeAction ->
            item {
                NavRow(
                    title = R.string.layout_editor_mode_row_title,
                    subtitle = stringResource(R.string.layout_editor_mode_row_subtitle),
                    value = keyboardModes.firstOrNull { it.id == modeAction.id }?.name
                        ?: stringResource(R.string.layout_editor_mode_row_unset),
                ) { pickingMode = true }
            }
        }
        // A switch-keyboard key carries which keyboard app it goes to.
        (key.action as? KeyAction.SwitchInputMethod)?.let { imeAction ->
            item {
                val context = LocalContext.current
                NavRow(
                    title = R.string.layout_editor_ime_row_title,
                    subtitle = stringResource(R.string.layout_editor_ime_row_subtitle),
                    value = enabledOtherInputMethods(context).firstOrNull { it.id == imeAction.id }
                        ?.loadLabel(context.packageManager)?.toString()
                        ?: stringResource(R.string.layout_editor_ime_row_unset),
                ) { pickingIme = true }
            }
        }
        // An edit key carries which operation it runs.
        (key.action as? KeyAction.Edit)?.let { edit ->
            item {
                NavRow(
                    title = R.string.layout_editor_edit_row_title,
                    subtitle = stringResource(R.string.layout_editor_edit_row_subtitle),
                    value = stringResource(textEditActionTitle(edit.op)),
                ) { pickingEdit = true }
            }
        }
        // A component's cell carries which component it hosts.
        (key.action as? KeyAction.Field)?.let { field ->
            item {
                NavRow(
                    title = R.string.layout_editor_field_row_title,
                    subtitle = stringResource(R.string.layout_editor_field_row_subtitle),
                    value = stringResource(fieldTitleRes(field.kind)),
                ) { pickingField = true }
            }
        }
        // Braille dot keys carry which of the six dots this key is.
        (key.action as? KeyAction.BrailleDot)?.let { brailleDot ->
            item {
                SheetField(
                    label = stringResource(R.string.layout_editor_dot_field_label),
                    value = brailleDot.dot.toString(),
                    supporting = stringResource(R.string.layout_editor_dot_field_hint),
                    resetKey = ref,
                ) { text ->
                    text.trim().toIntOrNull()?.takeIf { it in 1..6 }?.let { dot ->
                        onChange { it.copy(action = KeyAction.BrailleDot(dot)) }
                    }
                }
            }
        }
    }

    // Press and hold, with the popup it opens as the keyboard draws it and the
    // corner hint that previews it, side by side (issue #769): the hint sat at
    // the foot of the sheet, far from the alternates it is drawn from.
    val shown = boardKey ?: key
    SettingsGroup(stringResource(R.string.layout_editor_key_section_hold)) {
        // Issue #231: a key whose action is worth doing twice can be told to
        // repeat under a held finger, the way delete always has. Above the
        // alternates on purpose — it is what takes them away, so it has to be
        // readable from where they were.
        if (key.canRepeatOnHold()) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.layout_editor_repeat_hold_title,
                    stringResource(R.string.layout_editor_repeat_hold_subtitle),
                    key.repeatOnHold,
                    info = stringResource(R.string.layout_editor_repeat_hold_info),
                ) { repeat -> onChange { it.copy(repeatOnHold = repeat) } }
            }
            // Only once both are true: the fields below have just vanished and
            // the entries the author typed into them are still stored, so say
            // where they went rather than leaving a key that quietly stopped
            // opening its popup.
            if (key.repeatOnHold && (key.longPress.isNotEmpty() || key.actionAlternates.isNotEmpty())) {
                item(level = SettingLevel.STANDARD) { CaptionText(stringResource(R.string.layout_editor_repeat_hold_alternates_notice)) }
            }
        }
        // One action the hold runs outright, with no popup: the "direct"
        // version of the action alternates below. Above them for the reason the
        // repeat switch is: setting it is what takes them away.
        if (key.canHoldAction()) {
            item(level = SettingLevel.STANDARD) {
                ActionAlternatesRows(
                    alternates = listOfNotNull(key.holdAction),
                    secondaryLayouts = secondaryLayouts,
                    keyboardModes = keyboardModes,
                    title = R.string.layout_editor_hold_action_label,
                    subtitle = stringResource(R.string.layout_editor_hold_action_hint),
                    addLabel = R.string.layout_editor_hold_action_add,
                    limit = 1,
                ) { picked -> onChange { it.copy(holdAction = picked.firstOrNull()) } }
            }
            if (key.holdAction != null && (key.longPress.isNotEmpty() || key.actionAlternates.isNotEmpty())) {
                item(level = SettingLevel.STANDARD) { CaptionText(stringResource(R.string.layout_editor_hold_action_alternates_notice)) }
            }
        }
        // Not only text keys: every key whose press and hold is free can carry
        // alternates, which is what the enter key was missing (issue #22). The
        // ones left out are the ones that hold to repeat or chord, where the
        // popup would never open — see [Key.canHoldAlternates].
        if (key.canHoldAlternates()) {
            // Issue #41: a Select key's hold already means something —
            // selection mode for as long as the finger is down. Alternates win
            // over it, so say so before the first one is added.
            if ((key.action as? KeyAction.Edit)?.op == TextEditAction.SELECT) {
                item { CaptionText(stringResource(R.string.layout_editor_select_hold_notice)) }
            }
            item {
                SheetField(
                    label = stringResource(R.string.layout_editor_alternates_field_label),
                    value = alternates,
                    supporting = stringResource(R.string.layout_editor_alternates_field_hint),
                    resetKey = ref,
                ) { text ->
                    alternates = text
                    onChange { it.copy(longPress = parseAlternates(text)) }
                }
            }
            item(level = SettingLevel.STANDARD) {
                ActionAlternatesRows(
                    alternates = key.actionAlternates,
                    secondaryLayouts = secondaryLayouts,
                    keyboardModes = keyboardModes,
                    title = R.string.layout_editor_action_alternates_label,
                    subtitle = stringResource(R.string.layout_editor_action_alternates_hint),
                    addLabel = R.string.layout_editor_action_alternate_add_action,
                ) { alternatesList -> onChange { it.copy(actionAlternates = alternatesList) } }
            }
            // Issue #570: where the actions sit among the characters. Only
            // where there are both, since that is all it orders.
            if (key.longPress.isNotEmpty() && key.actionAlternates.isNotEmpty()) {
                item(level = SettingLevel.ADVANCED) { ActionPositionRow(key) { at -> onChange { it.copy(actionAlternatesAt = at) } } }
            }
            if (key.opensAlternatesPopup()) {
                item(level = SettingLevel.ADVANCED) {
                    AlternateColumnsRow(key.alternateColumns) { columns ->
                        onChange { it.copy(alternateColumns = columns) }
                    }
                }
            }
        }
        // The popup as the keyboard will draw it (issue #769), settings'
        // additions included: the editor listed six alternates where the
        // keyboard opened eleven and a Select all.
        if (shown.opensAlternatesPopup() && shown.alternateEntries().isNotEmpty()) {
            item {
                KeyPopupPreview(
                    shown = shown,
                    stored = key,
                    columns = key.alternateColumns.takeIf { it > 0 } ?: popupColumns,
                    nearestFirst = popupNearestFirst,
                    secondaryLayouts = secondaryLayouts,
                    keyboardModes = keyboardModes,
                )
            }
        }
        // The corner hint: its own text, its icon, and whether it is drawn.
        if (!isField) {
            if (key.action == KeyAction.Text || key.hint != null) item(level = SettingLevel.STANDARD) {
                SheetField(
                    label = stringResource(R.string.layout_editor_key_hint_text_label),
                    value = key.hint.orEmpty(),
                    supporting = shown.longPress.firstOrNull()
                        ?.let { stringResource(R.string.layout_editor_key_hint_text_hint_alternate, it) }
                        ?: stringResource(R.string.layout_editor_key_hint_text_hint),
                    resetKey = ref,
                ) { text -> onChange { it.copy(hint = text.ifBlank { null }) } }
            }
            if (key.action == KeyAction.Text || key.iconHint != null) item(level = SettingLevel.ADVANCED) {
                IconPickRow(R.string.layout_editor_icon_hint_field_label, key.iconHint) { pickingIcon = true }
            }
            val hintable = key.hideHint || key.forceHint || key.iconHint != null || key.hint != null ||
                (key.opensAlternatesPopup() && key.longPress.isNotEmpty())
            if (hintable) item(level = SettingLevel.STANDARD) { HintRow(key, onChange) }
        }
    }

    // Issue #339: what a short flick towards each edge or corner types. Open
    // on a key that has flicks, folded on one that has none. Decided once per
    // key (#850): a group that unfolds the moment its first arm is set draws
    // its rows from another call site, and the remount would throw away the
    // picker that arm was about to open.
    if (!isField && (key.action == KeyAction.Text || key.takesFlickActions())) {
        val hadFlicks = remember(ref) { key.hasFlicks() }
        SettingsGroup(
            stringResource(R.string.layout_editor_key_section_flicks),
            foldKey = if (hadFlicks) null else "key_flicks",
            // A key that already flicks shows them at any depth.
            level = if (hadFlicks) SettingLevel.BASIC else SettingLevel.STANDARD,
        ) {
            item { FlickFields(key, ref, secondaryLayouts, keyboardModes, onChange) }
        }
    }

    SettingsGroup(stringResource(R.string.layout_editor_key_section_look), foldKey = "key_look") {
        item {
            KeyWidthRow(
                width = key.width,
                gridWeight = gridWeight,
                otherWidthsInRow = otherWidthsInRow,
                resetKey = ref,
            ) { width -> onChange { it.copy(width = width) } }
        }
        if (rowCount - ref.row - 1 > 0 || key.rowSpan > 1) item(level = SettingLevel.STANDARD) {
            KeyRowSpanRow(
                span = key.rowSpan,
                rowsBelow = rowCount - ref.row - 1,
            ) { span -> onChange { it.copy(rowSpan = span) } }
        }
        item(level = SettingLevel.STANDARD) {
            KeyScreenRow(key.showOn) { screen -> onChange { it.copy(showOn = screen) } }
        }
        if (!isField && drawsScalableLabel(key)) item(level = SettingLevel.STANDARD) {
            KeyLabelScaleRow(key) { scale -> onChange { it.copy(labelScale = scale) } }
        }
        if (!isField && !key.hideHint) item(level = SettingLevel.ADVANCED) {
            KeyHintScaleRow(key) { scale -> onChange { it.copy(hintScale = scale) } }
        }
        // Every key but a component's cell can wear an icon (issue #187). The
        // space bar and the action keys used to have no field for one, and the
        // letters' field wanted a name typed from memory; a picker shows the
        // icons and says what each is called.
        if (!isField) {
            item { IconPickRow(R.string.layout_editor_icon_field_label, key.icon) { pickingIcon = false } }
            if (key.action == KeyAction.Space && KeyIcons.byName(key.icon) != null) item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.layout_editor_icon_beside_label_title,
                    stringResource(R.string.layout_editor_icon_beside_label_subtitle),
                    key.iconBesideLabel,
                ) { beside -> onChange { it.copy(iconBesideLabel = beside) } }
            }
        }
    }

    // The rare ones: the slot the key fills when a field adapts the letters,
    // and the kana pad's stand-in switch.
    val kanaRow = !isField && key.action != KeyAction.KanaVariant && (kanaPad || key.kanaVariantWhileComposing)
    if (key.action == KeyAction.Text || kanaRow) {
        SettingsGroup(stringResource(R.string.layout_editor_key_section_more), foldKey = "key_more", level = SettingLevel.ADVANCED) {
            if (key.action == KeyAction.Text) item {
                RoleRow(key.role) { role -> onChange { it.copy(role = role) } }
            }
            // Issue #340: on a Japanese pad, any key but the 小゛゜ key itself
            // can stand in for it while there is a kana to change. Kept for a
            // key already carrying the flag, so a layout that changed language
            // can still turn it off.
            if (kanaRow) item {
                ToggleSetting(
                    R.string.layout_editor_kana_variant_title,
                    stringResource(R.string.layout_editor_kana_variant_subtitle),
                    key.kanaVariantWhileComposing,
                    info = stringResource(R.string.layout_editor_kana_variant_info),
                ) { on -> onChange { it.copy(kanaVariantWhileComposing = on) } }
            }
        }
    }

    if (pickingAction) {
        KeyActionPickerDialog(
            current = key.action,
            options = catalog,
            onPick = { action ->
                // The role goes with the text: it names a punctuation slot, only
                // text keys are offered it, and a key retyped as something else
                // would otherwise keep a tag with nowhere left to show it (issue
                // #25). The renderer ignores such a tag either way; this is so
                // the stored layout does not carry one.
                // A new action takes the old one's label and icon with it
                // (issue #769): a Del key turned into Ctrl kept saying Del.
                // Left blank, the key draws what the keyboard draws for the
                // action it now has. Typing text keeps its label. The old
                // key's text arms go too (#838): since #789 an action key
                // types them, so a letter's arms left on the Shift it became
                // would start typing under it.
                val changed = !(catalog.firstOrNull { o -> o.matches(action) }?.matches(key.action) ?: false)
                onChange { it.withEditedAction(action, changed) }
                pickingAction = false
                // Straight on to which tool, rather than leaving the key on
                // whichever one the catalog entry had to name as its default.
                if (action is KeyAction.Tool) pickingTool = true
                if (action is KeyAction.Layout) pickingLayout = true
                if (action is KeyAction.Mode) pickingMode = true
                if (action is KeyAction.SwitchInputMethod) pickingIme = true
                if (action is KeyAction.Edit) pickingEdit = true
                if (action is KeyAction.EditSequence) pickingSequence = true
                if (action is KeyAction.Field) pickingField = true
            },
            onDismiss = { pickingAction = false },
        )
    }

    if (pickingLayout) {
        SecondaryLayoutPickerDialog(
            current = (key.action as? KeyAction.Layout)?.id,
            options = secondaryLayouts,
            onDismiss = { pickingLayout = false },
            onPick = { picked ->
                pickingLayout = false
                // The layout's name lands as the label unless the author
                // already wrote one: a key reading "▦" says nothing about
                // which grid it opens.
                onChange {
                    it.copy(
                        action = KeyAction.Layout(picked.id),
                        label = it.label.ifBlank { picked.name },
                    )
                }
            },
        )
    }

    if (pickingMode) {
        KeyboardModePickerDialog(
            current = (key.action as? KeyAction.Mode)?.id,
            options = keyboardModes,
            onDismiss = { pickingMode = false },
            onPick = { picked ->
                pickingMode = false
                // No name stored (#821): the board looks it up by id, so a
                // rename reaches the key. A name an earlier pick left goes too;
                // a label typed by hand stays.
                onChange {
                    it.copy(action = KeyAction.Mode(picked.id), label = it.label.unlessModeName(keyboardModes))
                }
            },
        )
    }

    if (pickingIme) {
        InputMethodPickerDialog(
            current = (key.action as? KeyAction.SwitchInputMethod)?.id,
            onDismiss = { pickingIme = false },
            onPick = { picked, name ->
                pickingIme = false
                onChange { it.copy(action = KeyAction.SwitchInputMethod(picked), label = it.label.ifBlank { name }) }
            },
        )
    }

    if (pickingEdit) {
        TextEditActionPickerDialog(
            current = (key.action as? KeyAction.Edit)?.op,
            onDismiss = { pickingEdit = false },
            onPick = { op ->
                pickingEdit = false
                onChange { it.copy(action = KeyAction.Edit(op)) }
            },
        )
    }

    if (pickingSequence) {
        TextEditSequenceDialog(
            current = (key.action as? KeyAction.EditSequence)?.ops.orEmpty(),
            onDismiss = { pickingSequence = false },
            onPick = { ops ->
                pickingSequence = false
                onChange { it.copy(action = KeyAction.EditSequence(ops)) }
            },
        )
    }

    if (pickingField && fieldKinds != null) {
        FieldKindPickerDialog(
            current = (key.action as? KeyAction.Field)?.kind,
            options = fieldKinds,
            onDismiss = { pickingField = false },
            onPick = { kind ->
                pickingField = false
                onChange { it.copy(action = KeyAction.Field(kind)) }
            },
        )
    }

    pickingIcon?.let { hint ->
        KeyIconPickerDialog(
            title = stringResource(
                if (hint) R.string.layout_editor_icon_hint_field_label else R.string.layout_editor_icon_field_label,
            ),
            selected = if (hint) key.iconHint else key.icon,
            onDismiss = { pickingIcon = null },
            onPick = { name ->
                pickingIcon = null
                onChange { if (hint) it.copy(iconHint = name) else it.copy(icon = name) }
            },
        )
    }

    if (pickingTool) {
        ToolPickerDialog(
            title = stringResource(R.string.layout_editor_tool_picker_title),
            current = (key.action as? KeyAction.Tool)?.tool,
            options = ToolbarTool.entries.filter(::isSupportedTool),
            onDismiss = { pickingTool = false },
            onPick = { picked ->
                pickingTool = false
                picked?.let { tool -> onChange { it.copy(action = KeyAction.Tool(tool)) } }
            },
        )
    }
}

/**
 * The selected key's heading and the things done to the key as a whole: move
 * it, copy it, add a key after it, delete it, and close the section. One strip,
 * at the top, where the sheet's own arrows and buttons were at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyEditHeader(
    title: String,
    ref: KeyRef,
    rowSize: Int,
    rowCount: Int,
    onMove: (Int) -> Unit,
    onMoveRow: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onInsertAfter: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    onCopy: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.padding(start = 32.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.layout_editor_key_close_desc))
        }
    }
    // Where the key sits. Not mirrored, the up and down pair: they mean the
    // row above and the row below in every script.
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(enabled = ref.col > 0, onClick = { onMove(-1) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.layout_editor_move_left_desc),
            )
        }
        IconButton(enabled = ref.row > 0, onClick = { onMoveRow(-1) }) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.layout_editor_move_up_desc))
        }
        IconButton(enabled = ref.row < rowCount - 1, onClick = { onMoveRow(+1) }) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.layout_editor_move_down_desc),
            )
        }
        IconButton(enabled = ref.col < rowSize - 1, onClick = { onMove(+1) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.layout_editor_move_right_desc),
            )
        }
        IconButton(onClick = onDuplicate) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.layout_editor_duplicate_key_desc))
        }
        if (onInsertAfter != null) {
            TextButton(onClick = onInsertAfter) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_editor_insert_key_action))
            }
        }
        if (onCopy != null) {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.layout_editor_copy_key_action)) }
        }
        if (onPaste != null) {
            TextButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_editor_paste_key_action))
            }
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.layout_editor_delete_key_action))
        }
    }
}

/**
 * Several picked keys at once (issue #769): moved together while they sit side
 * by side in one row, copied, deleted, and given the same width, label size or
 * corner hint in one go. What one of them shows is the first one's value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiKeySection(
    keys: List<Key>,
    canMoveLeft: Boolean,
    canMoveRight: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onMoveRow: (Int) -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onChange: ((Key) -> Key) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 32.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.layout_editor_multi_title, keys.size, keys.size),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDone) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.layout_editor_multi_done_desc))
        }
    }
    FlowRow(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
        IconButton(enabled = canMoveLeft, onClick = { onMove(-1) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.layout_editor_move_left_desc),
            )
        }
        IconButton(enabled = canMoveUp, onClick = { onMoveRow(-1) }) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = stringResource(R.string.layout_editor_move_up_desc))
        }
        IconButton(enabled = canMoveDown, onClick = { onMoveRow(+1) }) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = stringResource(R.string.layout_editor_move_down_desc))
        }
        IconButton(enabled = canMoveRight, onClick = { onMove(+1) }) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = stringResource(R.string.layout_editor_move_right_desc),
            )
        }
        TextButton(onClick = onCopy) { Text(stringResource(R.string.layout_editor_copy_key_action)) }
        TextButton(onClick = onPaste) {
            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.layout_editor_paste_key_action))
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.layout_editor_multi_delete_action))
        }
    }
    if (!canMoveLeft && !canMoveRight && !canMoveUp && !canMoveDown && keys.size > 1) {
        CaptionText(stringResource(R.string.layout_editor_multi_move_caption))
    }
    val first = keys.first()
    SettingsGroup(stringResource(R.string.layout_editor_multi_section_look)) {
        item {
            // No row to fill: the keys may sit in several.
            KeyWidthRow(width = first.width, gridWeight = 0f, otherWidthsInRow = 0f, resetKey = keys.size) { width ->
                onChange { it.copy(width = width) }
            }
        }
        keys.firstOrNull(::drawsScalableLabel)?.let { sample ->
            item(level = SettingLevel.STANDARD) { KeyLabelScaleRow(sample) { scale -> onChange { it.copy(labelScale = scale) } } }
        }
        item(level = SettingLevel.STANDARD) { HintRow(first, onChange, always = true) }
    }
}

/**
 * The popup a press and hold opens on this key, drawn the way the keyboard
 * lays it out (issue #769): in [columns] columns (or as the entries fit, at 0),
 * the first entries on the bottom row when [nearestFirst] says so, actions in
 * their place among the characters. The first entry is outlined, since with
 * hold to select it is what a plain press and hold types. An entry the
 * settings add rather than this layout is drawn faded, so the author can see
 * which of the eleven are theirs to change here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPopupPreview(
    shown: Key,
    stored: Key,
    columns: Int,
    nearestFirst: Boolean,
    secondaryLayouts: List<LayoutSpec>,
    keyboardModes: List<KeyboardMode>,
) {
    val entries = shown.alternateEntries()
    val colors = MaterialTheme.colorScheme
    val added = entries.map { entry ->
        when (entry) {
            is AlternateEntry.Character -> entry.text !in stored.longPress
            is AlternateEntry.Action -> entry.alternate !in stored.actionAlternates
        }
    }
    val cell: @Composable (Int) -> Unit = { index ->
        val entry = entries[index]
        val shape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier
                .padding(2.dp)
                .heightIn(min = 40.dp)
                .width(44.dp)
                .clip(shape)
                .background(colors.surfaceContainerHighest)
                .then(if (index == 0) Modifier.border(2.dp, colors.primary, shape) else Modifier)
                .alpha(if (added[index]) 0.5f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            when (entry) {
                is AlternateEntry.Character -> Text(entry.text, maxLines = 1, fontSize = 16.sp)
                is AlternateEntry.Action -> {
                    val icon = flickArmIcon(entry.alternate).takeIf { entry.alternate.label.isBlank() }
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = actionAlternateName(entry.alternate, secondaryLayouts, keyboardModes),
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Text(
                            entry.alternate.label.ifBlank { actionAlternateName(entry.alternate, secondaryLayouts, keyboardModes) },
                            maxLines = 1,
                            fontSize = 11.sp,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    ControlSetting(
        R.string.layout_editor_popup_preview_title,
        subtitle = stringResource(
            if (added.any { it }) {
                R.string.layout_editor_popup_preview_caption_added
            } else {
                R.string.layout_editor_popup_preview_caption
            },
        ),
    ) {
        if (columns <= 0) {
            FlowRow(modifier = Modifier.padding(top = 4.dp)) {
                for (index in entries.indices) cell(index)
            }
        } else {
            val lines = entries.indices.chunked(columns).let { if (nearestFirst) it.reversed() else it }
            Column(modifier = Modifier.padding(top = 4.dp)) {
                for (line in lines) {
                    Row { for (index in line) cell(index) }
                }
            }
        }
    }
}

/**
 * Space-separated, exactly as the symbol-set editor parses its characters, so a
 * user meets the convention once. A chip-per-entry editor was the alternative
 * and fails on ".com" and "https://" — multi-character alternates the built-ins
 * already ship.
 */
private fun parseAlternates(text: String): List<String> =
    text.split(Regex("\\s+")).filter { it.isNotEmpty() }

/**
 * What a blank Output field means for *this* key, spelled out.
 *
 * The field has always been optional — the keyboard types `output ?: label` —
 * but "Blank: the key types the label" read as a rule about some other key, so
 * the case it does not cover got read as "Output is required" (issue #16). Naming
 * the label the key would type answers that where it is asked. A key with no
 * label either types nothing at all, and repair deletes it the moment the layout
 * is turned on, so that one says so rather than sounding optional.
 */
@Composable
private fun outputFieldSupport(key: Key): String = when {
    key.action != KeyAction.Text -> stringResource(R.string.layout_editor_key_output_hint)
    // The field is disabled in this case; say which control moves it, or a
    // disabled field with no explanation reads as a bug.
    key.isAmbiguous() -> stringResource(R.string.layout_editor_key_output_hint_letters)
    key.label.isNotBlank() ->
        stringResource(R.string.layout_editor_key_output_hint_typed, key.label)
    else -> stringResource(R.string.layout_editor_key_output_hint_none)
}

/**
 * The flick directions of a key (issue #339, eight of them since #410): what a
 * short flick towards each edge or corner types instead of the tap.
 *
 * These were reachable only from the raw JSON, on the grounds that a flick map
 * is rare and whoever wants one already knows the word (see [LettersField]).
 * Rare, yes; but the person who wants one is somebody adjusting the Japanese
 * pad they type on every day, and the JSON was the one part of that job the
 * editor sent them away for. So the fields are here, behind a button on a key
 * that has none, and open straight away on a key that has some.
 *
 * Blank removes the direction rather than storing an empty string: an empty
 * arm is what the keyboard already treats as no flick, and keeping the map to
 * the directions that type something keeps the file what an author would write.
 *
 * Each arm that types something gets a Shift field under it (issue #550), the
 * way the key's own label has one. Any arm can run an action instead (issue
 * #549), through the button at the end of its field; on an action key, which
 * has no text to flick, the arms are action rows and nothing else. An arm is one
 * or the other, so choosing an action drops the arm's text, and the file says
 * what the sheet shows.
 */
@Composable
private fun FlickFields(
    key: Key,
    ref: KeyRef,
    secondaryLayouts: List<LayoutSpec>,
    keyboardModes: List<KeyboardMode>,
    onChange: ((Key) -> Key) -> Unit,
) {
    val typesText = key.action == KeyAction.Text
    val takesActions = key.takesFlickActions()
    // Since #789 an arm can type text on any key that takes action arms too:
    // Enter with `?` on its flick up, Shift with a quote. The tap is still
    // the key's own action.
    val takesText = typesText || takesActions
    var open by remember(ref) { mutableStateOf(key.flick.isNotEmpty() || key.flickActions.isNotEmpty()) }
    // The arm whose action is being picked, then the second question some
    // actions ask: which operation, which tool, which layout.
    var picking by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    var pickingEditAt by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    var pickingSequenceAt by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    var pickingToolAt by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    var pickingLayoutAt by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    var pickingModeAt by remember(ref) { mutableStateOf<FlickDirection?>(null) }
    if (!open) {
        WmRow(
            title = stringResource(R.string.layout_editor_flick_add_action),
            subtitle = stringResource(
                if (typesText) R.string.layout_editor_flick_add_subtitle else R.string.layout_editor_flick_add_subtitle_action,
            ),
            leading = { Icon(Icons.Outlined.Add, contentDescription = null) },
            onClick = { open = true },
        )
        return
    }
    CaptionText(
        stringResource(if (typesText) R.string.layout_editor_flick_caption else R.string.layout_editor_flick_caption_action),
    )
    // What an arm here takes over (#838): the fixed rules from the gestures
    // page, said where the arm is being given rather than discovered later.
    val takesOver = flickTakesOverNote(key)
    if (takesOver != 0) CaptionText(stringResource(takesOver))
    // Issue #410: a key that is all swipes, with nothing in the middle. The
    // switch empties the centre; turning it off gives the centre the first
    // text arm back, so the key types something on a tap again.
    if (typesText && key.hasFlicks()) {
        val swipeOnly = key.isSwipeOnly()
        fun setSwipeOnly(on: Boolean) = onChange {
            if (on) {
                it.copy(label = "", output = null, shiftLabel = null)
            } else {
                it.copy(label = it.flick.values.firstOrNull { text -> text.isNotEmpty() } ?: it.label)
            }
        }
        WmRow(
            title = stringResource(R.string.layout_editor_flick_swipe_only_title),
            subtitle = stringResource(R.string.layout_editor_flick_swipe_only_subtitle),
            trailing = { Switch(checked = swipeOnly, onCheckedChange = ::setSwipeOnly) },
            onClick = { setSwipeOnly(!swipeOnly) },
        )
    }
    fun setArm(direction: FlickDirection, alternate: KeyAlternate) = onChange {
        it.copy(
            flickActions = (it.flickActions + (direction to alternate)).inFlickOrder(),
            flick = it.flick - direction,
            flickShift = it.flickShift - direction,
        )
    }
    // The arm a second question finishes (#850): the one already there when
    // it is the same kind of action, so its label survives, else a new one.
    fun finishArm(direction: FlickDirection, action: KeyAction, label: (String) -> String = { it }) = onChange { k ->
        val old = k.flickActions[direction]?.takeIf { it.action::class == action::class } ?: KeyAlternate(action)
        k.copy(
            flickActions = (k.flickActions + (direction to old.copy(action = action, label = label(old.label)))).inFlickOrder(),
            flick = k.flick - direction,
            flickShift = k.flickShift - direction,
        )
    }
    // Issue #410: the eight arms as a 3×3 pad with the key itself in the
    // middle, the way the keyboard draws them. Tap a cell to edit that arm in
    // the fields below; eight pairs of fields in a column was a sheet nobody
    // could see the shape of.
    var selected by remember(ref, key.action) { mutableStateOf(FlickDirection.UP) }
    FlickPad(key = key, selected = selected, takesActions = takesActions) { selected = it }
    val direction = selected
    val arm = key.flickActions[direction]?.takeIf { takesActions }
    if (arm != null) {
        WmRow(
            title = stringResource(flickLabelRes(direction)),
            subtitle = stringResource(
                R.string.layout_editor_flick_action_set,
                actionAlternateName(arm, secondaryLayouts, keyboardModes),
            ),
            leading = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
            trailing = {
                IconButton(onClick = { onChange { it.copy(flickActions = it.flickActions - direction) } }) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.layout_editor_action_alternate_remove_desc),
                    )
                }
            },
            onClick = { picking = direction },
        )
    } else if (!takesText) {
        WmRow(
            title = stringResource(flickLabelRes(direction)),
            subtitle = stringResource(R.string.layout_editor_flick_action_none),
            leading = { Icon(Icons.Outlined.Bolt, contentDescription = null) },
            onClick = { picking = direction },
        )
    } else {
        val value = key.flick[direction].orEmpty()
        // The direction is part of the reset key: the field remembers its text
        // per key, and switching cells mid-edit must show the new arm's text,
        // not the one just typed into.
        SheetField(
            label = stringResource(flickLabelRes(direction)),
            value = value,
            supporting = if (value.isEmpty()) {
                stringResource(R.string.layout_editor_flick_field_hint)
            } else {
                stringResource(R.string.layout_editor_flick_set_hint, value)
            },
            resetKey = Triple(ref, direction, FlickTextField),
            trailing = if (takesActions) {
                {
                    IconButton(onClick = { picking = direction }) {
                        Icon(
                            Icons.Outlined.Bolt,
                            contentDescription = stringResource(R.string.layout_editor_flick_action_pick_desc),
                        )
                    }
                }
            } else {
                null
            },
        ) { text ->
            onChange {
                it.copy(
                    flick = it.flick.withArm(direction, text),
                    // The shift form of an arm that types nothing is a field
                    // nothing reads; it goes with the arm.
                    flickShift = if (text.isEmpty()) it.flickShift - direction else it.flickShift,
                )
            }
        }
        if (value.isNotEmpty()) {
            SheetField(
                label = stringResource(R.string.layout_editor_flick_shift_label, stringResource(flickLabelRes(direction))),
                value = key.flickShift[direction].orEmpty(),
                supporting = stringResource(R.string.layout_editor_flick_shift_hint),
                resetKey = Triple(ref, direction, FlickShiftField),
            ) { text ->
                onChange { it.copy(flickShift = it.flickShift.withArm(direction, text)) }
            }
        }
    }
    if (key.flick.isNotEmpty() || key.flickShift.isNotEmpty() || key.flickActions.isNotEmpty()) {
        TextButton(
            onClick = {
                onChange { it.copy(flick = emptyMap(), flickShift = emptyMap(), flickActions = emptyMap()) }
                open = false
            },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.layout_editor_flick_clear))
        }
    }

    picking?.let { direction ->
        KeyActionPickerDialog(
            current = key.flickActions[direction]?.action ?: KeyAction.None,
            options = AlternateActionCatalog,
            onPick = { action ->
                picking = null
                // Half an answer until it names its operation, tool, layout or
                // mode, so nothing is saved until it does (#850): the catalog's
                // placeholder (Left, for a text-editing action) is not a choice,
                // and backing out of the second question leaves the arm as it was.
                when (action) {
                    is KeyAction.Edit -> pickingEditAt = direction
                    is KeyAction.EditSequence -> pickingSequenceAt = direction
                    is KeyAction.Tool -> pickingToolAt = direction
                    is KeyAction.Layout -> pickingLayoutAt = direction
                    is KeyAction.Mode -> pickingModeAt = direction
                    else -> setArm(direction, KeyAlternate(action))
                }
            },
            onDismiss = { picking = null },
        )
    }

    pickingSequenceAt?.let { direction ->
        TextEditSequenceDialog(
            current = (key.flickActions[direction]?.action as? KeyAction.EditSequence)?.ops.orEmpty(),
            onDismiss = { pickingSequenceAt = null },
            onPick = { ops ->
                pickingSequenceAt = null
                finishArm(direction, KeyAction.EditSequence(ops))
            },
        )
    }

    pickingEditAt?.let { direction ->
        TextEditActionPickerDialog(
            current = (key.flickActions[direction]?.action as? KeyAction.Edit)?.op,
            onDismiss = { pickingEditAt = null },
            onPick = { op ->
                pickingEditAt = null
                finishArm(direction, KeyAction.Edit(op))
            },
        )
    }

    pickingToolAt?.let { direction ->
        ToolPickerDialog(
            title = stringResource(R.string.layout_editor_tool_picker_title),
            current = (key.flickActions[direction]?.action as? KeyAction.Tool)?.tool,
            options = ToolbarTool.entries.filter(::isSupportedTool),
            onDismiss = { pickingToolAt = null },
            onPick = { picked ->
                pickingToolAt = null
                picked?.let { tool -> finishArm(direction, KeyAction.Tool(tool)) }
            },
        )
    }

    pickingLayoutAt?.let { direction ->
        SecondaryLayoutPickerDialog(
            current = (key.flickActions[direction]?.action as? KeyAction.Layout)?.id,
            options = secondaryLayouts,
            onDismiss = { pickingLayoutAt = null },
            onPick = { picked ->
                pickingLayoutAt = null
                finishArm(direction, KeyAction.Layout(picked.id)) { it.ifBlank { picked.name } }
            },
        )
    }

    pickingModeAt?.let { direction ->
        KeyboardModePickerDialog(
            current = (key.flickActions[direction]?.action as? KeyAction.Mode)?.id,
            options = keyboardModes,
            onDismiss = { pickingModeAt = null },
            onPick = { picked ->
                pickingModeAt = null
                finishArm(direction, KeyAction.Mode(picked.id)) { it.unlessModeName(keyboardModes) }
            },
        )
    }
}

/**
 * The 3×3 pad of a key's flick arms (issue #410): each arm's text or action
 * icon in the cell it is flicked towards, a faint plus where there is none, the
 * key's own label in the middle. Tapping a cell selects it for the fields
 * under the pad; the selected cell wears the accent outline.
 */
@Composable
private fun FlickPad(
    key: Key,
    selected: FlickDirection,
    takesActions: Boolean,
    onSelect: (FlickDirection) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val padDesc = stringResource(R.string.layout_editor_flick_pad_desc)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = padDesc },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in FlickDirection.gridOrder.chunked(3)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (direction in row) {
                    val isSelected = direction != null && direction == selected
                    val label = direction?.let { stringResource(flickLabelRes(it)) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(shape)
                            .background(if (direction == null) colors.surfaceVariant else colors.surface)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) colors.primary else colors.outlineVariant,
                                shape = shape,
                            )
                            .then(
                                if (direction != null) {
                                    Modifier.clickable(onClickLabel = label) { onSelect(direction) }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (direction == null) {
                            Text(
                                text = key.label.ifBlank { "•" },
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                color = colors.onSurfaceVariant,
                            )
                        } else {
                            FlickPadCell(key, direction, takesActions, isSelected)
                        }
                    }
                }
            }
        }
    }
}

/** One arm's cell on the [FlickPad]: its text, its action's icon, or a faint plus. */
@Composable
private fun FlickPadCell(key: Key, direction: FlickDirection, takesActions: Boolean, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint = if (selected) colors.primary else colors.onSurface
    when (val arm = key.flickArm(direction)) {
        null -> Icon(
            Icons.Outlined.Add,
            contentDescription = null,
            tint = colors.outline.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
        is FlickArm.Text -> Text(
            text = arm.text,
            color = tint,
            fontSize = 16.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        is FlickArm.Action -> {
            val icon = flickArmIcon(arm.alternate).takeIf { takesActions && arm.alternate.label.isBlank() }
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            } else {
                Text(
                    text = arm.alternate.label.ifBlank { "⚡" },
                    color = tint,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** Tells a flick arm's text field apart from its Shift field, which share a direction. */
private const val FlickTextField = "text"

/** Tells a flick arm's Shift field apart from its text field, which share a direction. */
private const val FlickShiftField = "shift"

/** [this] in the file's order of directions, which is the order an author would write. */
private fun <T> Map<FlickDirection, T>.inFlickOrder(): Map<FlickDirection, T> =
    FlickDirection.entries.mapNotNull { dir -> this[dir]?.let { dir to it } }.toMap()

/** [this] with [direction] typing [text], or without it for a blank one, in the file's order. */
internal fun Map<FlickDirection, String>.withArm(direction: FlickDirection, text: String): Map<FlickDirection, String> {
    val next = this + (direction to text)
    return FlickDirection.entries
        .mapNotNull { dir -> next[dir]?.takeIf { it.isNotEmpty() }?.let { dir to it } }
        .toMap()
}

@StringRes
private fun flickLabelRes(direction: FlickDirection): Int = when (direction) {
    FlickDirection.LEFT -> R.string.layout_editor_flick_left_label
    FlickDirection.UP -> R.string.layout_editor_flick_up_label
    FlickDirection.RIGHT -> R.string.layout_editor_flick_right_label
    FlickDirection.DOWN -> R.string.layout_editor_flick_down_label
    FlickDirection.UP_LEFT -> R.string.layout_editor_flick_up_left_label
    FlickDirection.UP_RIGHT -> R.string.layout_editor_flick_up_right_label
    FlickDirection.DOWN_LEFT -> R.string.layout_editor_flick_down_left_label
    FlickDirection.DOWN_RIGHT -> R.string.layout_editor_flick_down_right_label
}

/**
 * The letters one key stands for, for a board like T9 or Compact QWERTY where a
 * key carries several of them and the app works out which was meant.
 *
 * This shipped as a field only the raw JSON could set (discussion #103), on the
 * reasoning that puts flick maps and clipboard actions there: rare enough that a
 * control would crowd the sheet. That reasoning was wrong here, and the
 * difference is worth naming, because the same argument will come up again. A
 * flick map is rare *and* discoverable: the layout that wants one is a kana pad,
 * whose author already knows the word. An ambiguous key is rare and has no
 * symptom. Give a key the label `QW` and the output `q`, which is exactly what
 * the first person to try it did, and you get a board that looks finished, draws
 * correctly, types `q` forever, and says nothing about why. There is no failure
 * to search for. So the rule is not "how rare is it" but "what does a user see
 * when they guess wrong", and a silent nothing puts the control on the sheet.
 *
 * Three things this does that the JSON could not:
 *
 * - It writes the anchor, through [Key.withLetters], so the invariant the decode
 *   rests on cannot be broken from here at all.
 * - It offers the letters to the press and hold popup. Without them an ambiguous
 *   board has no way at all to spell a word the dictionary does not know, and
 *   that is not a thing an author discovers by testing common words.
 * - It says what the key now does in a sentence, where the JSON said it in a doc
 *   comment on a field in a different repository.
 */
@Composable
private fun LettersField(
    key: Key,
    ref: KeyRef,
    onChange: ((Key) -> Key) -> Unit,
    onAlternatesChanged: (List<String>) -> Unit,
) {
    // Held as text, like the alternates field: the store echoes the *cleaned*
    // set back, so feeding the stored value straight in would fight a half-typed
    // entry ("qw2" would lose the 2 as it was typed rather than on the way out).
    var text by remember(ref) { mutableStateOf(key.letters.orEmpty()) }
    val set = key.letters.orEmpty()
    SheetField(
        label = stringResource(R.string.layout_editor_key_letters_label),
        value = text,
        supporting = if (set.length > 1) {
            stringResource(
                R.string.layout_editor_key_letters_hint_set,
                set.toList().joinToString(" "),
                key.output.orEmpty(),
            )
        } else {
            stringResource(R.string.layout_editor_key_letters_hint)
        },
        resetKey = ref,
    ) { typed ->
        text = typed
        onChange { it.withLetters(typed) }
    }

    // Only once the key is actually ambiguous, and only while a letter of the
    // set is missing from the popup. A button that is always there, on every
    // ordinary key, would be a control for a problem almost nobody has.
    val missing = set.takeIf { it.length > 1 }
        ?.filter { letter -> key.longPress.none { it == letter.toString() } }
        .orEmpty()
    if (missing.isNotEmpty()) {
        CaptionText(stringResource(R.string.layout_editor_key_letters_popup_notice))
        // The letters go in after the first entry, which is the corner hint and
        // on a keypad is the digit: that is the order the shipped T9 and Compact
        // QWERTY keys use, and the hint is the one entry whose position shows on
        // the key itself.
        val added = key.longPress.take(1) + missing.map { it.toString() } + key.longPress.drop(1)
        TextButton(
            onClick = {
                onAlternatesChanged(added)
                onChange { it.copy(longPress = added) }
            },
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.layout_editor_key_letters_popup_button))
        }
    }
}

/** Inline validity feedback for the icon / icon-hint name fields. */
/**
 * The key sheet's icon row: the icon the key wears and its name, opening
 * [KeyIconPickerDialog]. The support line still names a name the registry does
 * not know, which is how a typo made in the JSON editor shows up here.
 */
@Composable
private fun IconPickRow(@StringRes title: Int, name: String?, onClick: () -> Unit) {
    NavRow(
        title = title,
        subtitle = iconFieldSupport(name),
        value = name ?: stringResource(R.string.layout_editor_icon_none_label),
        icon = KeyIcons.byName(name) ?: SettingsRowIcons[title],
        onClick = onClick,
    )
}

/**
 * Picks a key icon from [KeyIcons.pickerEntries]: the key glyphs, then the app's
 * own icons. Searchable by name, and each glyph wears the name a layout file
 * stores, so the picker doubles as the list of names (issue #187) — searching
 * also answers to an alias and to the name of the tool that wears the glyph,
 * which is how anyone looks for one (issue #223).
 */
@Composable
private fun KeyIconPickerDialog(
    title: String,
    selected: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Compared as drawings: a file may name the icon by an alias or in another
    // case ("Delete", "SEARCH"), and the cell should still light up.
    val selectedVector = KeyIcons.byName(selected)
    val reduceMotion = LocalReduceMotion.current
    val iconGrid = rememberLazyGridState()
    val iconRail = rememberScrollRailState(iconGrid)
    val context = LocalContext.current
    // What a search matches, beyond the name the layout file stores: the
    // spaced-out form of a bundled name ("SelectAll" → "Select all"), the
    // aliases that draw the same glyph, and the names of the tools that wear
    // it. A key glyph is stored under a short name — the clipboard drawing is
    // `paste`, selection mode's is `SelectAll` — so searching for the tool was
    // answered with "no match" and the icon read as missing (issue #223).
    val searchTerms = remember(context) {
        val byTool = HashMap<ImageVector, MutableList<String>>()
        for (tool in ToolbarTool.entries) {
            byTool.getOrPut(IconDefaults.forTool(tool)) { mutableListOf() }
                .add(context.getString(toolTitle(tool)))
        }
        KeyIcons.pickerEntries.associate { (name, vector) ->
            name to buildList {
                add(name)
                add(name.replace('_', ' '))
                add(BuiltinIcons.label(name))
                addAll(KeyIcons.aliasesFor(name))
                byTool[vector]?.let { addAll(it) }
            }
        }
    }
    val shown = remember(query, searchTerms) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            KeyIcons.pickerEntries
        } else {
            KeyIcons.pickerEntries.filter { (name, _) ->
                searchTerms[name].orEmpty().any { it.contains(needle, ignoreCase = true) }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(CommonR.string.common_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.plugins_icons_picker_no_match, query.trim()))
                } else {
                    ScrollRailBox(
                        state = iconRail,
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) { cells ->
                        LazyVerticalGrid(
                            state = iconGrid,
                            columns = GridCells.Adaptive(IconGridCellMinWidth),
                            modifier = cells,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            gridItems(shown, key = { it.first }) { (name, vector) ->
                                IconGridCell(
                                    vector = vector,
                                    name = name,
                                    selected = vector == selectedVector,
                                    onClick = { onPick(name) },
                                    modifier = gridItemMotion(reduceMotion),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(null) }) {
                Text(stringResource(R.string.layout_editor_icon_picker_clear_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun iconFieldSupport(name: String?): String = when {
    name.isNullOrBlank() -> stringResource(R.string.layout_editor_icon_field_hint)
    KeyIcons.byName(name) != null -> stringResource(R.string.layout_editor_icon_found_hint, name)
    else -> stringResource(R.string.layout_editor_icon_missing_hint, name)
}

/**
 * The alternates that run an action instead of typing: one chip each, and a
 * button that adds another (issue #21).
 *
 * Chips rather than the space-separated field the characters use, for the reason
 * that field's own comment gives in reverse: an action is not text, so there is
 * nothing to type. Tapping a chip re-picks its action; the × removes it.
 *
 * The catalog is offered minus the two entries that mean nothing here. "Types
 * text" is what the characters field above already is, and a popup entry with no
 * action is a button that does nothing — `repair` drops both on sight, so
 * offering them would be offering an author a chip that disappears.
 *
 * Also the [Key.holdAction] row, which is the same picking with [limit] 1: a
 * hold runs one action, so the add button goes once there is one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionAlternatesRows(
    alternates: List<KeyAlternate>,
    secondaryLayouts: List<LayoutSpec>,
    @StringRes title: Int,
    subtitle: String,
    @StringRes addLabel: Int,
    limit: Int = Int.MAX_VALUE,
    keyboardModes: List<KeyboardMode> = emptyList(),
    onChange: (List<KeyAlternate>) -> Unit,
) {
    // The chip whose action is being picked; [AddingAlternate] for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    // Where a tool, just chosen as an action, has to land.
    var pickingToolAt by remember { mutableStateOf<Int?>(null) }
    // Same for a secondary layout.
    var pickingLayoutAt by remember { mutableStateOf<Int?>(null) }
    // Same for a keyboard mode.
    var pickingModeAt by remember { mutableStateOf<Int?>(null) }
    // Same for a text-editing operation (#630): without it every one landed as Left.
    var pickingEditAt by remember { mutableStateOf<Int?>(null) }
    var pickingSequenceAt by remember { mutableStateOf<Int?>(null) }
    // Same for the keyboard a switch-keyboard action names.
    var pickingImeAt by remember { mutableStateOf<Int?>(null) }

    ControlSetting(title, subtitle = subtitle) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            alternates.forEachIndexed { index, alternate ->
                InputChip(
                    selected = false,
                    onClick = { editing = index },
                    label = { Text(actionAlternateName(alternate, secondaryLayouts, keyboardModes)) },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.layout_editor_action_alternate_remove_desc,
                            ),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable {
                                    onChange(alternates.filterIndexed { i, _ -> i != index })
                                },
                        )
                    },
                )
            }
            if (alternates.size < limit) {
                TextButton(onClick = { editing = AddingAlternate }) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(addLabel))
                }
            }
        }
    }

    editing?.let { index ->
        KeyActionPickerDialog(
            current = alternates.getOrNull(index)?.action ?: KeyAction.None,
            options = AlternateActionCatalog,
            onPick = { action ->
                val landsAt = if (index in alternates.indices) index else alternates.size
                onChange(
                    if (index in alternates.indices) {
                        alternates.mapIndexed { i, a -> if (i == index) a.copy(action = action) else a }
                    } else {
                        alternates + KeyAlternate(action)
                    },
                )
                editing = null
                // A tool action is only half an answer until it names its tool.
                if (action is KeyAction.Tool) pickingToolAt = landsAt
                if (action is KeyAction.Layout) pickingLayoutAt = landsAt
                if (action is KeyAction.Mode) pickingModeAt = landsAt
                if (action is KeyAction.Edit) pickingEditAt = landsAt
                if (action is KeyAction.EditSequence) pickingSequenceAt = landsAt
                if (action is KeyAction.SwitchInputMethod) pickingImeAt = landsAt
            },
            onDismiss = { editing = null },
        )
    }

    pickingLayoutAt?.let { index ->
        SecondaryLayoutPickerDialog(
            current = (alternates.getOrNull(index)?.action as? KeyAction.Layout)?.id,
            options = secondaryLayouts,
            onDismiss = { pickingLayoutAt = null },
            onPick = { picked ->
                pickingLayoutAt = null
                onChange(
                    alternates.mapIndexed { i, a ->
                        if (i == index) {
                            a.copy(action = KeyAction.Layout(picked.id), label = a.label.ifBlank { picked.name })
                        } else {
                            a
                        }
                    },
                )
            },
        )
    }

    pickingModeAt?.let { index ->
        KeyboardModePickerDialog(
            current = (alternates.getOrNull(index)?.action as? KeyAction.Mode)?.id,
            options = keyboardModes,
            onDismiss = { pickingModeAt = null },
            onPick = { picked ->
                pickingModeAt = null
                onChange(
                    alternates.mapIndexed { i, a ->
                        if (i == index) {
                            a.copy(action = KeyAction.Mode(picked.id), label = a.label.unlessModeName(keyboardModes))
                        } else {
                            a
                        }
                    },
                )
            },
        )
    }

    pickingSequenceAt?.let { index ->
        TextEditSequenceDialog(
            current = (alternates.getOrNull(index)?.action as? KeyAction.EditSequence)?.ops.orEmpty(),
            onDismiss = { pickingSequenceAt = null },
            onPick = { ops ->
                pickingSequenceAt = null
                onChange(
                    alternates.mapIndexed { i, a -> if (i == index) a.copy(action = KeyAction.EditSequence(ops)) else a },
                )
            },
        )
    }

    pickingEditAt?.let { index ->
        TextEditActionPickerDialog(
            current = (alternates.getOrNull(index)?.action as? KeyAction.Edit)?.op,
            onDismiss = { pickingEditAt = null },
            onPick = { op ->
                pickingEditAt = null
                onChange(
                    alternates.mapIndexed { i, a -> if (i == index) a.copy(action = KeyAction.Edit(op)) else a },
                )
            },
        )
    }

    pickingImeAt?.let { index ->
        InputMethodPickerDialog(
            current = (alternates.getOrNull(index)?.action as? KeyAction.SwitchInputMethod)?.id,
            onDismiss = { pickingImeAt = null },
            onPick = { picked, name ->
                pickingImeAt = null
                onChange(
                    alternates.mapIndexed { i, a ->
                        if (i == index) {
                            a.copy(action = KeyAction.SwitchInputMethod(picked), label = a.label.ifBlank { name })
                        } else {
                            a
                        }
                    },
                )
            },
        )
    }

    pickingToolAt?.let { index ->
        ToolPickerDialog(
            title = stringResource(R.string.layout_editor_tool_picker_title),
            current = (alternates.getOrNull(index)?.action as? KeyAction.Tool)?.tool,
            options = ToolbarTool.entries.filter(::isSupportedTool),
            onDismiss = { pickingToolAt = null },
            onPick = { picked ->
                pickingToolAt = null
                picked?.let { tool ->
                    onChange(
                        alternates.mapIndexed { i, a ->
                            if (i == index) a.copy(action = KeyAction.Tool(tool)) else a
                        },
                    )
                }
            },
        )
    }
}

/** The editing index that means "this pick appends a chip". */
private const val AddingAlternate = -1

/** Every action a popup entry may carry; see [ActionAlternatesRows]. */
private val AlternateActionCatalog: List<KeyActionOption> =
    KeyActionCatalog.filterNot { it.matches(KeyAction.Text) || it.matches(KeyAction.None) }

/** [this] label, or none when it is only some mode's name, which the board looks up for itself (#821). */
private fun String.unlessModeName(modes: List<KeyboardMode>): String =
    takeUnless { label -> modes.any { it.name == label } }.orEmpty()

/**
 * What a chip calls an alternate: the tool's name, the action's name from the
 * catalog, or — for an action a hand-written layout chose that the picker does
 * not list — the glyph the popup will draw.
 */
@Composable
internal fun actionAlternateName(
    alternate: KeyAlternate,
    secondaryLayouts: List<LayoutSpec>,
    keyboardModes: List<KeyboardMode> = emptyList(),
): String {
    val action = alternate.action
    if (action is KeyAction.Tool) return stringResource(toolTitle(action.tool))
    if (action is KeyAction.Layout) {
        return secondaryLayouts.firstOrNull { it.id == action.id }?.name
            ?: alternate.drawnLabel()
    }
    if (action is KeyAction.Mode) {
        return keyboardModes.firstOrNull { it.id == action.id }?.name
            ?: alternate.label.ifBlank { stringResource(R.string.layout_editor_action_mode_title) }
    }
    if (action is KeyAction.Edit) return stringResource(textEditActionTitle(action.op))
    if (action is KeyAction.EditSequence) {
        return action.ops.map { stringResource(textEditActionTitle(it)) }.joinToString(" + ")
    }
    val option = KeyActionCatalog.firstOrNull { it.matches(action) }
    return option?.let { stringResource(it.titleRes) }
        ?: alternate.drawnLabel().ifBlank { stringResource(R.string.layout_editor_action_unknown) }
}

/**
 * A text field whose text survives the round trip through the settings store.
 *
 * [value] is read back out of the repository, so it lags the keystroke that
 * caused it by a frame or more. Fed straight back into a Compose text field it
 * rewinds the text *and the cursor* mid-word: typing "ABCDEF" into a key label
 * landed as "qE", and even one character a second put the last one at the front.
 *
 * So the text lives here, and an incoming value is taken only while nothing of
 * ours is in flight. [pending] is the last thing this field emitted; until the
 * store echoes exactly that back, every value arriving is an older read rather
 * than a change from outside, and ignoring it is the whole fix. Once the echo
 * lands the field is in sync again and an undo — or anything else that rewrites
 * the key — moves the text as it should.
 *
 * [resetKey] bounds all of that to one editing session: hand it the key being
 * edited, and moving to another one starts the field over rather than waiting
 * for an echo that will now never come.
 */
@Composable
private fun SheetField(
    label: String,
    value: String,
    supporting: String,
    resetKey: Any?,
    /**
     * False for a field whose value another field decides: it still says what
     * the key will do, and the supporting text says which control moves it. The
     * echo guard above is skipped while disabled, because nothing of ours is in
     * flight and the incoming value is the only truth there is.
     */
    enabled: Boolean = true,
    /** Drawn at the field's end, for a control that acts on this one field. */
    trailing: (@Composable () -> Unit)? = null,
    onChange: (String) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(value) }
    var pending by remember(resetKey) { mutableStateOf<String?>(null) }
    when {
        !enabled -> {
            pending = null
            if (value != text) text = value
        }
        pending == null -> if (value != text) text = value
        value == pending -> pending = null
    }
    OutlinedTextField(
        enabled = enabled,
        value = text,
        onValueChange = {
            text = it
            pending = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = { Text(supporting) },
        trailingIcon = trailing,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * What the corner hint on this one key does about the global hints switch:
 * follow it, always draw, or never draw.
 *
 * Three states rather than the one switch it started as, because both overrides
 * turn out to be wanted and they point opposite ways (issue #33). "Never" is the
 * author with a clean corner in a hinted grid; "Always" is the author who turned
 * the hints off everywhere and wants the two keys nobody would guess to keep
 * saying what they hold.
 *
 * Shown only for a key that has a hint to draw, because on every other key the
 * row would do nothing visible and it would cost the whole sheet a row. The
 * test mirrors the keyboard's own draw: an icon hint annotates any key, while the
 * character hint needs a text key whose press and hold opens the alternates
 * rather than running a clipboard shortcut. A key already carrying an override
 * keeps the row whatever else changed, or setting one would be a one-way door.
 */
@Composable
private fun HintRow(
    key: Key,
    onChange: ((Key) -> Key) -> Unit,
    /** Drawn whatever [key] holds: the several-keys section sets it for keys it has not looked at. */
    always: Boolean = false,
) {
    val hasIconHint = key.iconHint != null || key.hint != null
    val hasCharHint = key.opensAlternatesPopup() && key.longPress.isNotEmpty()
    if (!always && !key.hideHint && !key.forceHint && !hasIconHint && !hasCharHint) return
    ChoiceSetting(
        title = R.string.layout_editor_hint_title,
        subtitle = stringResource(R.string.layout_editor_hint_subtitle),
        info = stringResource(R.string.layout_editor_hint_info),
        options = listOf(
            KeyHintMode.Auto to stringResource(R.string.layout_editor_hint_auto),
            KeyHintMode.Always to stringResource(R.string.layout_editor_hint_always),
            KeyHintMode.Never to stringResource(R.string.layout_editor_hint_never),
        ),
        selected = keyHintMode(key),
        default = KeyHintMode.Auto,
        detail = { mode ->
            ChoiceDetail(
                icon = when (mode) {
                    KeyHintMode.Auto -> Icons.Outlined.AutoMode
                    KeyHintMode.Always -> Icons.Outlined.Visibility
                    KeyHintMode.Never -> Icons.Outlined.VisibilityOff
                },
                description = stringResource(
                    when (mode) {
                        KeyHintMode.Auto -> R.string.layout_editor_hint_auto_desc
                        KeyHintMode.Always -> R.string.layout_editor_hint_always_desc
                        KeyHintMode.Never -> R.string.layout_editor_hint_never_desc
                    },
                ),
            )
        },
    ) { mode ->
        onChange {
            it.copy(hideHint = mode == KeyHintMode.Never, forceHint = mode == KeyHintMode.Always)
        }
    }
}

/**
 * The three answers [HintRow] offers, over the two booleans the layout format
 * stores. Editor-only: the file keeps `hideHint` and `forceHint` so an older
 * build reads a newer layout unchanged.
 */
private enum class KeyHintMode { Auto, Always, Never }

/** [Key.hideHint] wins, matching the keyboard's own draw. */
private fun keyHintMode(key: Key): KeyHintMode = when {
    key.hideHint -> KeyHintMode.Never
    key.forceHint -> KeyHintMode.Always
    else -> KeyHintMode.Auto
}

/**
 * This key's own alternates column count (issue #64).
 *
 * A key rather than the keyboard, because that is what the count is for: a `1`
 * carrying a dozen fractions wants a grid, an `e` carrying four accents wants one
 * row, and one number cannot be right for both. The first step is not a count at
 * all but "follow the setting", which is where every key starts and where the
 * global **Alternate key columns** row takes over.
 */
@Composable
private fun AlternateColumnsRow(columns: Int, onChange: (Int) -> Unit) {
    // Read out here rather than inside `display`: that is a plain lambda, and
    // stringResource needs a composition.
    val followsSetting = stringResource(R.string.layout_editor_alternate_columns_default)
    StepperSetting(
        title = R.string.layout_editor_alternate_columns_title,
        subtitle = stringResource(R.string.layout_editor_alternate_columns_subtitle),
        value = columns,
        range = AlternatesColumnsSteps,
        display = { if (it == 0) followsSetting else it.toString() },
        info = stringResource(R.string.layout_editor_alternate_columns_info),
        default = 0,
        onChange = onChange,
    )
}

/**
 * Where this key's action alternates sit among its characters (issue #570):
 * first, after one of the characters, or last.
 *
 * Each step after the first names the character the actions follow, which says
 * more than a number would: "After ç" is the popup the author will see. The
 * last step is the absence of a position, stored as null, so a key that never
 * touched the row reads exactly as it did. A stored index past the characters
 * means the same thing and shows as last.
 */
@Composable
private fun ActionPositionRow(key: Key, onChange: (Int?) -> Unit) {
    val characters = key.longPress
    val value = key.actionAlternatesAt?.takeIf { it in characters.indices } ?: ACTION_POSITION_LAST
    // Read out here rather than inside `display`: that is a plain lambda, and
    // stringResource needs a composition.
    val first = stringResource(R.string.keypress_hold_action_position_first)
    val last = stringResource(R.string.keypress_hold_action_position_last)
    val afterTemplate = stringResource(R.string.layout_editor_action_position_after)
    StepperSetting(
        title = R.string.layout_editor_action_position_title,
        subtitle = stringResource(R.string.layout_editor_action_position_subtitle),
        value = value,
        range = characters.indices.toList() + ACTION_POSITION_LAST,
        display = { at ->
            when (at) {
                ACTION_POSITION_LAST -> last
                0 -> first
                else -> afterTemplate.format(characters[at - 1])
            }
        },
        info = stringResource(R.string.layout_editor_action_position_info),
        default = ACTION_POSITION_LAST,
    ) { at -> onChange(at.takeIf { it >= 0 }) }
}

/** Which slot this key fills for field adaptation, or none. */
@Composable
private fun RoleRow(role: KeyRole?, onChange: (KeyRole?) -> Unit) {
    ChoiceSetting(
        title = R.string.layout_editor_role_title,
        subtitle = stringResource(R.string.layout_editor_role_subtitle),
        options = listOf(
            null to stringResource(CommonR.string.common_none),
            KeyRole.Comma to stringResource(R.string.layout_editor_role_comma),
            KeyRole.Period to stringResource(R.string.layout_editor_role_period),
            KeyRole.Plain to stringResource(R.string.layout_editor_role_plain),
        ),
        selected = role,
        detail = { slot ->
            ChoiceDetail(
                icon = when (slot) {
                    null -> Icons.Outlined.Block
                    KeyRole.Comma -> Icons.Outlined.MoreHoriz
                    KeyRole.Period -> Icons.Outlined.FiberManualRecord
                    KeyRole.Plain -> Icons.Outlined.Lock
                },
                description = stringResource(
                    when (slot) {
                        null -> R.string.layout_editor_role_none_desc
                        KeyRole.Comma -> R.string.layout_editor_role_comma_desc
                        KeyRole.Period -> R.string.layout_editor_role_period_desc
                        KeyRole.Plain -> R.string.layout_editor_role_plain_desc
                    },
                ),
            )
        },
        onChange = onChange,
    )
}

/**
 * Per-row height control for the layout editor: a multiplier on the standard
 * key height for this one row. 1.00 is the default (and collapses the stored
 * list back to nothing). Mirrors [KeyWidthRow] control for control.
 *
 * The range is the renderer's own ([MinRowHeightScale] to [MaxRowHeightScale]),
 * not the narrower 0.5 to 2 it used to be: a slider that stops short of what the
 * keyboard honours makes the last part of the range reachable only through the
 * JSON, and then unreachable again the next time the slider is touched.
 */
@Composable
internal fun RowHeightRow(
    rowIndex: Int,
    height: Float,
    onChange: (Float) -> Unit,
) {
    val travel = sliderTravel(
        height,
        floor = MinRowHeightScale,
        ceiling = MaxRowHeightScale,
        hardMax = MaxRowHeightScale,
    )
    ControlSetting(
        stringResource(R.string.layout_editor_row_height_label, height),
        icon = SettingsRowIcons[R.string.layout_editor_row_height_label],
    ) {
        WmSlider(
            value = sliderPosition(height, travel),
            onValueChange = { onChange(roundGridUnit(it)) },
            valueRange = travel,
        )
        GridSizeStepper(
            value = height,
            range = MinRowHeightScale..MaxRowHeightScale,
            fieldLabel = stringResource(R.string.layout_editor_height_field_label),
            decreaseDesc = stringResource(R.string.layout_editor_height_decrease_desc),
            increaseDesc = stringResource(R.string.layout_editor_height_increase_desc),
            resetKey = rowIndex,
            onChange = onChange,
        )
    }
}

/**
 * One field of this layout's appearance, changed.
 *
 * Drops the whole object again once nothing is left in it, so a layout set back
 * to its defaults stores no appearance rather than an object full of nulls —
 * which is what keeps `appearance == null` meaning "this layout says nothing"
 * everywhere else, exported files included.
 */
private fun LayoutSpec.withAppearance(
    fontId: String? = appearance?.fontId,
    fontScale: Float? = appearance?.fontScale,
): LayoutSpec {
    val next = (appearance ?: LayoutAppearance()).copy(fontId = fontId, fontScale = fontScale)
    return copy(appearance = next.takeUnless { it.isEmpty })
}

/** This layout's own sizes, changed; dropped again once none is left, as [withAppearance] does. */
private fun LayoutSpec.withSizing(change: (LayoutAppearance) -> LayoutAppearance): LayoutSpec {
    val next = change(appearance ?: LayoutAppearance())
    return copy(appearance = next.takeUnless { it.isEmpty })
}

/**
 * One of a layout's own sizes: the value, or "Same as everywhere" while it
 * follows the global setting ([global]). A tap opens a slider; its reset drops
 * the layout's own value.
 */
@Composable
private fun LayoutSizeOverrideRow(
    title: String,
    value: Float?,
    global: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    WmRow(
        title = title,
        subtitle = value?.let(display)
            ?: stringResource(R.string.layout_editor_size_inherit, display(global)),
        onClick = { open = true },
    )
    if (open) {
        var draft by remember { mutableFloatStateOf(value ?: global) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    Text(display(draft))
                    Slider(value = draft, onValueChange = { draft = it }, valueRange = range)
                    Text(stringResource(R.string.layout_editor_size_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    open = false
                    onChange(draft.coerceIn(range))
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    open = false
                    onChange(null)
                }) { Text(stringResource(R.string.layout_editor_size_reset)) }
            },
        )
    }
}

/**
 * Label size for the whole layout, as a multiple of whatever size the keyboard
 * would otherwise draw at.
 *
 * A multiplier and not a size, so it composes with the accessibility font scale
 * instead of overruling it. Null is the default and is offered as its own chip
 * rather than as 1.00, because "this layout does not care" and "this layout
 * wants exactly the normal size" are different things to store: only the first
 * one keeps following the settings.
 *
 * [title] names the scope, because the layer row below reuses this control
 * whole. The two differ only in what null falls back to, and that is said in
 * their captions rather than in their behaviour here.
 */
@Composable
internal fun LayoutFontScaleRow(
    scale: Float?,
    title: String,
    autoTitle: String,
    hint: String,
    onChange: (Float?) -> Unit,
) {
    val shown = scale ?: 1f
    val travel = sliderTravel(
        shown,
        floor = LayoutFontScaleRange.start,
        ceiling = LayoutFontScaleRange.endInclusive,
        hardMax = LayoutFontScaleRange.endInclusive,
    )
    ControlSetting(
        if (scale == null) autoTitle else title,
        subtitle = hint,
        icon = SettingsRowIcons[R.string.layout_editor_font_scale_label],
    ) {
        WmSlider(
            value = sliderPosition(shown, travel),
            onValueChange = { onChange(roundGridUnit(it)) },
            valueRange = travel,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = scale == null,
                onClick = { onChange(null) },
                label = { Text(stringResource(R.string.layout_editor_font_scale_auto_chip)) },
            )
            for (preset in listOf(0.8f, 1f, 1.2f)) {
                FilterChip(
                    selected = scale != null && kotlin.math.abs(shown - preset) < GridUnitStep / 2f,
                    onClick = { onChange(preset) },
                    label = { Text("×%.2f".format(preset).trimEnd('0').trimEnd('.')) },
                )
            }
        }
    }
}

/**
 * Corner hint size for one key, as a multiple of every hint's size (#970): for
 * the key whose hint is a mark too small to read, an Arabic haraka on a corner,
 * without making every other hint bigger with it.
 */
@Composable
private fun KeyHintScaleRow(key: Key, onChange: (Float?) -> Unit) {
    val scale = key.hintScale
    ControlSetting(
        if (scale == null) {
            stringResource(R.string.layout_editor_key_hint_scale_auto_label)
        } else {
            stringResource(R.string.layout_editor_key_hint_scale_label, scale)
        },
        subtitle = stringResource(R.string.layout_editor_key_hint_scale_hint),
        icon = SettingsRowIcons[R.string.layout_editor_key_label_scale_label],
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = scale == null,
                onClick = { onChange(null) },
                label = { Text(stringResource(R.string.layout_editor_font_scale_auto_chip)) },
            )
            for (preset in listOf(0.75f, 1.5f, 2f, 3f)) {
                FilterChip(
                    selected = scale != null && kotlin.math.abs(scale - preset) < 0.01f,
                    onClick = { onChange(preset) },
                    label = { Text("×%.2f".format(preset).trimEnd('0').trimEnd('.')) },
                )
            }
        }
    }
}

/**
 * Label size for one key, as a multiple of an ordinary letter's size.
 *
 * The twin of [LayoutFontScaleRow] one level down, and the control the label
 * flags of an imported layout land in (issue #18). Its null is louder than the
 * layout's: unset means the keyboard picks the size, which for a key labelled
 * with a word is deliberately smaller than a letter — so setting this to ×1 is
 * a real instruction ("draw the word at letter size"), not a no-op.
 */
@Composable
private fun KeyLabelScaleRow(key: Key, onChange: (Float?) -> Unit) {
    // Only for a key that draws a label at letter size. The keyboard draws the
    // shift, delete and enter keys from icon slots, prints the spacebar's
    // language name at its own small size, and an icon key draws no text at
    // all — so on those this control would move a number nothing reads. Same
    // rule as the hide-hint and row-span rows: a control that cannot do
    // anything here does not take a row here.
    if (!drawsScalableLabel(key)) return
    val scale = key.labelScale
    val shown = scale ?: 1f
    val travel = sliderTravel(
        shown,
        floor = KeyLabelScaleRange.start,
        ceiling = KeyLabelScaleRange.endInclusive,
        hardMax = KeyLabelScaleRange.endInclusive,
    )
    ControlSetting(
        if (scale == null) {
            stringResource(R.string.layout_editor_key_label_scale_auto_label)
        } else {
            stringResource(R.string.layout_editor_key_label_scale_label, scale)
        },
        subtitle = stringResource(R.string.layout_editor_key_label_scale_hint),
        icon = SettingsRowIcons[R.string.layout_editor_key_label_scale_label],
    ) {
        if (scale != null) {
            WmSlider(
                value = sliderPosition(shown, travel),
                onValueChange = { onChange(roundGridUnit(it)) },
                valueRange = travel,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = scale == null,
                onClick = { onChange(null) },
                label = { Text(stringResource(R.string.layout_editor_font_scale_auto_chip)) },
            )
            for (preset in listOf(0.5f, 0.7f, 1f, 1.3f)) {
                FilterChip(
                    selected = scale != null && kotlin.math.abs(shown - preset) < GridUnitStep / 2f,
                    onClick = { onChange(preset) },
                    label = { Text("×%.2f".format(preset).trimEnd('0').trimEnd('.')) },
                )
            }
        }
    }
    // Deliberately no GridSizeStepper: the exact-value field is what a width
    // needs, because a row has to add up to the grid. A label size never has to
    // land on a particular number.
}

/**
 * Whether this key's label is the one `KeyContent` draws at letter size, which
 * is the only label a [Key.labelScale] reaches.
 *
 * Kept beside the control rather than inside it so the list reads against
 * `KeyContent`'s own `when`, which is what it has to stay in step with.
 */
private fun drawsScalableLabel(key: Key): Boolean = when (key.action) {
    // A worded shift key draws its label in place of the arrow (#559).
    KeyAction.Shift, KeyAction.CapsLock ->
        KeyIcons.byName(key.icon) == null && shiftLabelReplacesIcon(key.label)
    KeyAction.Delete, KeyAction.ForwardDelete,
    KeyAction.Enter, KeyAction.Newline, KeyAction.LanguageSwitch,
    KeyAction.InputMethodPicker, KeyAction.Emoji, KeyAction.Space,
    is KeyAction.SwitchInputMethod,
    -> false
    // A component draws no label at all; an edit key draws its icon.
    is KeyAction.Field -> false
    is KeyAction.Edit -> key.label.isNotBlank() || textEditIcon((key.action as KeyAction.Edit).op) == null
    // An icon in place of the glyph means there is no text to size.
    else -> KeyIcons.byName(key.icon) == null
}

/**
 * The row-level answer to a row that does not add up to the grid: scale every
 * key in it, keeping the proportions.
 *
 * The key sheet's "Fill the row" grows one key into the slack, which is right
 * when one key is meant to be the wide one and wrong for a row whose keys are
 * deliberately several different sizes. Shown only while the row disagrees with
 * the grid, which is the only time it does anything.
 */
@Composable
internal fun RowFitRow(rowWidth: Float, gridWeight: Float, onFit: () -> Unit) {
    if (kotlin.math.abs(rowWidth - gridWeight) <= GridUnitStep) return
    OutlinedButton(
        onClick = onFit,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.layout_editor_fit_row_action, gridWeight)) }
}

/**
 * The selected row's own width (issue #769), the row-level twin of a key's:
 * every key in the row keeps its share as the total moves. A row narrower than
 * the grid is centred, which is how QWERTY's second row is short; the only way
 * to get that on another row used to be resizing its keys one at a time.
 */
@Composable
internal fun RowWidthRow(rowIndex: Int, width: Float, gridWeight: Float, onChange: (Float) -> Unit) {
    if (width <= 0f) return
    val travel = sliderTravel(width, floor = 1f, ceiling = maxOf(gridWeight, 10f), hardMax = MaxRowWidthShown)
    ControlSetting(
        stringResource(R.string.layout_editor_row_width_label, width),
        subtitle = stringResource(R.string.layout_editor_row_width_hint, gridWeight),
        icon = SettingsRowIcons[R.string.layout_editor_key_width_label],
    ) {
        WmSlider(
            value = sliderPosition(width, travel),
            onValueChange = { onChange(roundGridUnit(it)) },
            valueRange = travel,
        )
        GridSizeStepper(
            value = width,
            range = GridUnitStep..MaxRowWidthShown,
            fieldLabel = stringResource(R.string.layout_editor_row_width_field_label),
            decreaseDesc = stringResource(R.string.layout_editor_row_width_decrease_desc),
            increaseDesc = stringResource(R.string.layout_editor_row_width_increase_desc),
            resetKey = rowIndex,
            onChange = onChange,
        )
    }
}

/** The widest the row width control goes; the stepper's two-digit field holds no more. */
private const val MaxRowWidthShown = 30f

/**
 * The keys "Add a special key" makes (issue #769): every action of the catalog
 * that needs nothing more said about it, and each text-editing operation by
 * name, so a Ctrl, a Tab or a Copy key is one pick rather than a new key, an
 * action, and then a second question. Left out: typing text, the ones that ask
 * which tool, layout, mode or keyboard, and the keys of one script's pad.
 */
internal val SpecialKeyPresets: List<KeyActionOption> by lazy {
    val skipped = setOf(
        KeyAction.Text, KeyAction.None, KeyAction.KanaVariant, KeyAction.AddStroke,
        KeyAction.DoubleConsonant, KeyAction.ReadingColumn, KeyAction.MorseDot, KeyAction.MorseDash,
    )
    KeyActionCatalog.filter { option ->
        when (val action = option.build()) {
            is KeyAction.Edit, is KeyAction.EditSequence, is KeyAction.Tool, is KeyAction.Layout, is KeyAction.Mode,
            is KeyAction.SwitchInputMethod, is KeyAction.Broadcast, is KeyAction.BrailleDot,
            -> false
            else -> action !in skipped
        }
    } + TextEditAction.entries.map { op ->
        KeyActionOption(
            textEditActionTitle(op),
            R.string.layout_editor_action_group_text_edit,
            R.string.layout_editor_special_edit_detail,
            { KeyAction.Edit(op) },
            { it == KeyAction.Edit(op) },
        )
    }
}

/** Which way up the screen a key exists: both, or one of them. The editor always draws it. */
@Composable
private fun KeyScreenRow(screen: KeyScreen, onChange: (KeyScreen) -> Unit) {
    ControlSetting(
        stringResource(R.string.layout_editor_key_screen_title),
        subtitle = stringResource(R.string.layout_editor_key_screen_hint),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (choice in KeyScreen.entries) {
                FilterChip(
                    selected = choice == screen,
                    onClick = { onChange(choice) },
                    label = {
                        Text(
                            stringResource(
                                when (choice) {
                                    KeyScreen.ALWAYS -> R.string.layout_editor_key_screen_always
                                    KeyScreen.PORTRAIT -> R.string.layout_editor_key_screen_portrait
                                    KeyScreen.LANDSCAPE -> R.string.layout_editor_key_screen_landscape
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

/**
 * How many rows this key covers — the vertical twin of [KeyWidthRow].
 *
 * A row of chips rather than a slider: the useful range is two or three, the
 * values are whole rows, and the ceiling moves with the key's position, since a
 * key on the last row has nothing to reach into. A key that already covers more
 * rows than the layer has left (a row was deleted under it) keeps its chip, so
 * touching this control never silently rewrites what the file says — the same
 * rule [sliderTravel] follows for an out-of-range width.
 */
@Composable
private fun KeyRowSpanRow(span: Int, rowsBelow: Int, onChange: (Int) -> Unit) {
    // Nothing to span into and nothing already spanning: the control would offer
    // one choice, which is not a choice.
    if (rowsBelow <= 0 && span <= 1) return
    val choices = (1..maxOf(rowsBelow + 1, span)).toList()
    ControlSetting(
        pluralStringResource(R.plurals.layout_editor_key_row_span_label, span, span),
        subtitle = stringResource(R.string.layout_editor_key_row_span_hint),
        icon = SettingsRowIcons[R.string.layout_editor_key_row_span_hint],
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            for (choice in choices) {
                FilterChip(
                    selected = choice == span,
                    onClick = { onChange(choice) },
                    label = { Text(choice.toString()) },
                )
            }
        }
    }
}

@Composable
private fun KeyWidthRow(
    width: Float,
    gridWeight: Float,
    otherWidthsInRow: Float,
    resetKey: Any?,
    onChange: (Float) -> Unit,
) {
    val remaining = gridWeight - otherWidthsInRow
    val travel = sliderTravel(width, floor = 0.5f, ceiling = 5f, hardMax = MaxKeyWidth)
    ControlSetting(
        stringResource(R.string.layout_editor_key_width_label, width),
        icon = SettingsRowIcons[R.string.layout_editor_key_width_label],
    ) {
        // Continuous, landing on hundredths. It used to move in quarters, on the
        // grounds that a free slider writes 1.0374 into a file people are invited
        // to hand-edit — true, but quarters cannot express the 1.43 that seven
        // keys need to fill a ten-wide grid, and rounding to the two decimals the
        // number is displayed at answers both.
        WmSlider(
            value = sliderPosition(width, travel),
            onValueChange = { onChange(roundGridUnit(it)) },
            valueRange = travel,
        )
        GridSizeStepper(
            value = width,
            range = GridUnitStep..MaxKeyWidth,
            fieldLabel = stringResource(R.string.layout_editor_width_field_label),
            decreaseDesc = stringResource(R.string.layout_editor_width_decrease_desc),
            increaseDesc = stringResource(R.string.layout_editor_width_increase_desc),
            resetKey = resetKey,
            onChange = onChange,
        )
        // The same width as a share of the row (#970): "a tenth of the row" is
        // how a layout is planned, and the grid's units are only how it is kept.
        if (gridWeight > 0f) KeyWidthPercentField(width, gridWeight, resetKey, onChange)
        // The one-tap fix for a row left short after an edit: hand this key
        // whatever row 1's width is not already spoken for. Exact, not rounded to
        // a quarter, or pressing it would leave the row it promises to fill still
        // short and the warning above it still on screen.
        if (remaining >= GridUnitStep && kotlin.math.abs(remaining - width) > GridUnitStep / 2f) {
            OutlinedButton(
                onClick = { onChange(roundGridUnit(remaining)) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text(stringResource(R.string.layout_editor_fill_row_action, remaining)) }
        }
    }
}

/**
 * The key's width as a percentage of the grid's (#970), typed in and written
 * back as grid units. Held while the user is typing in it, so a half-typed
 * "1" does not get replaced by what 1% rounds to.
 */
@Composable
private fun KeyWidthPercentField(width: Float, gridWeight: Float, resetKey: Any?, onChange: (Float) -> Unit) {
    val shown = "%.1f".format(Locale.US, width / gridWeight * 100f).removeSuffix(".0")
    var text by remember(resetKey) { mutableStateOf(shown) }
    var typing by remember(resetKey) { mutableStateOf(false) }
    LaunchedEffect(shown) { if (!typing) text = shown }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            if (!percentPattern.matches(typed)) return@OutlinedTextField
            text = typed
            typing = true
            typed.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f && it <= 100f }?.let { percent ->
                onChange(roundGridUnit(percent / 100f * gridWeight).coerceIn(GridUnitStep, MaxKeyWidth))
            }
        },
        singleLine = true,
        label = { Text(stringResource(R.string.layout_editor_width_percent_label)) },
        suffix = { Text("%") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier
            .padding(top = 4.dp)
            .onFocusChanged { if (!it.isFocused) typing = false },
    )
}

/** Up to 100, with one decimal, either separator. */
private val percentPattern = Regex("""^\d{0,3}([.,]\d?)?$""")

/**
 * How far a size slider travels: its usual range, widened to reach a value that
 * is already outside it.
 *
 * A fixed range silently rewrote the layout it was showing. A key stored at 8
 * wide, which the JSON editor and every import path can write, drew its handle
 * pinned at the old maximum of 5, and the next touch of the slider committed
 * that 5 over the 8 that was there.
 */
private fun sliderTravel(
    value: Float,
    floor: Float,
    ceiling: Float,
    hardMax: Float,
): ClosedFloatingPointRange<Float> {
    val safe = if (value.isFinite()) value else floor
    val low = minOf(floor, safe).coerceAtLeast(0f)
    val high = maxOf(ceiling, kotlin.math.ceil(safe)).coerceAtMost(hardMax)
    return low..maxOf(high, low + GridUnitStep)
}

/** Where the handle sits, for a stored value that may be anything at all. */
private fun sliderPosition(value: Float, travel: ClosedFloatingPointRange<Float>): Float =
    if (value.isFinite()) value.coerceIn(travel) else travel.start

/** Grid sizes are stored in hundredths; the nudge buttons move five at a time. */
private const val SizeNudgeStep = 0.05f

/**
 * The one format the editable size fields read and write. Both separators are
 * accepted because a decimal comma is what half of Europe's keyboards offer.
 */
private val gridSizePattern = Regex("""^\d{0,2}([.,]\d{0,2})?$""")

/**
 * Fixed notation, not the reader's own, because this is the one number on the
 * screen that has to be read back: a field that prints "1,43" and then cannot
 * parse it is worse than one that disagrees with the label above it.
 */
private fun formatGridUnit(value: Float): String = "%.2f".format(Locale.US, value)

private fun parseGridUnit(text: String): Float? = text.replace(',', '.').toFloatOrNull()

/**
 * Minus, an exact value, plus: the half of a size control a slider cannot do.
 *
 * The slider and the preset chips cover "about this wide". This covers "1.43",
 * which before this existed meant leaving the grid editor for the raw JSON.
 *
 * The text is held here and only taken from [value] while nothing of ours is in
 * flight, for the reason [SheetField] documents at length: the value is read
 * back out of the settings store a frame or more after the keystroke that caused
 * it, and fed straight back in it rewinds the field mid-entry. The extra rule
 * this one needs is the focus check — "1." and "" are not numbers, so without it
 * the field rewrites itself the moment you clear it to type a new value.
 */
@Composable
private fun GridSizeStepper(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fieldLabel: String,
    decreaseDesc: String,
    increaseDesc: String,
    resetKey: Any?,
    onChange: (Float) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(formatGridUnit(value)) }
    var pending by remember(resetKey) { mutableStateOf<Float?>(null) }
    var focused by remember(resetKey) { mutableStateOf(false) }

    val typed = parseGridUnit(text)
    val settled = pending
    when {
        focused && typed == null -> Unit
        settled == null ->
            if (typed == null || kotlin.math.abs(typed - value) > GridUnitStep / 2f) {
                text = formatGridUnit(value)
            }
        kotlin.math.abs(value - settled) < GridUnitStep / 2f -> pending = null
    }

    fun emit(next: Float) {
        if (!next.isFinite()) return
        val clamped = roundGridUnit(next).coerceIn(range)
        pending = clamped
        onChange(clamped)
    }

    // Counted from what this control last asked for, not from what the store has
    // said back so far. Two presses inside one round trip both read the same
    // stored value, so counted from that the second one does nothing.
    fun nudge(delta: Float) = emit((pending ?: value) + delta)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            enabled = value > range.start + GridUnitStep / 2f,
            onClick = { nudge(-SizeNudgeStep) },
        ) { Icon(Icons.Outlined.Remove, contentDescription = decreaseDesc) }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val trimmed = raw.trim()
                // A third decimal is refused rather than rounded away, so the
                // field never disagrees with the number it just accepted.
                if (gridSizePattern.matches(trimmed)) {
                    text = trimmed
                    parseGridUnit(trimmed)?.let(::emit)
                }
            },
            // Named even though the line above it already says "Width 1.43":
            // an unlabelled edit box in the middle of a sheet is a shrug to
            // anyone reading the screen with TalkBack.
            label = { Text(fieldLabel, maxLines = 1) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .width(124.dp)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    // Leaving tidies "1." or a value that was clamped on the way in.
                    if (!state.isFocused) text = formatGridUnit(value)
                },
        )
        IconButton(
            enabled = value < range.endInclusive - GridUnitStep / 2f,
            onClick = { nudge(+SizeNudgeStep) },
        ) { Icon(Icons.Outlined.Add, contentDescription = increaseDesc) }
    }
}

/**
 * Which secondary layout an "Open a layout" key shows. The list is the user's
 * secondary layouts by name; with none yet it says where to make one rather
 * than offering an empty radio group.
 */
@Composable
internal fun SecondaryLayoutPickerDialog(
    current: String?,
    options: List<LayoutSpec>,
    onDismiss: () -> Unit,
    onPick: (LayoutSpec) -> Unit,
) {
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_layout_picker_title)) },
        text = {
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 380.dp)) {
                if (options.isEmpty()) {
                    Text(stringResource(R.string.layout_editor_layout_picker_empty))
                }
                for (option in options) {
                    WmRow(
                        title = option.name,
                        leading = {
                            RadioButton(
                                selected = option.id == current,
                                onClick = { onPick(option) },
                            )
                        },
                        onClick = { onPick(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

/**
 * Which keyboard mode a "Toggle a keyboard mode" key turns on. The list is the
 * user's modes by name; with none it says where to make one, like
 * [SecondaryLayoutPickerDialog].
 */
@Composable
internal fun KeyboardModePickerDialog(
    current: String?,
    options: List<KeyboardMode>,
    onDismiss: () -> Unit,
    onPick: (KeyboardMode) -> Unit,
) {
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_mode_picker_title)) },
        text = {
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 380.dp)) {
                if (options.isEmpty()) {
                    Text(stringResource(R.string.layout_editor_mode_picker_empty))
                }
                for (option in options) {
                    WmRow(
                        title = option.name,
                        leading = {
                            RadioButton(
                                selected = option.id == current,
                                onClick = { onPick(option) },
                            )
                        },
                        onClick = { onPick(option) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

/** The other keyboard apps turned on for this device: what a switch-keyboard key can name. */
private fun enabledOtherInputMethods(context: android.content.Context): List<android.view.inputmethod.InputMethodInfo> =
    context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.enabledInputMethodList.orEmpty()
        .filter { it.packageName != context.packageName }

/**
 * Lists the other keyboards the device has turned on, for a switch-keyboard
 * key (issue #354). Read live from the platform each time the dialog opens, so
 * a keyboard enabled a moment ago is there; ours is left out, since a key that
 * switches to the keyboard already showing would do nothing.
 */
@Composable
internal fun InputMethodPickerDialog(
    current: String?,
    onDismiss: () -> Unit,
    onPick: (id: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val options = remember { enabledOtherInputMethods(context) }
    val rail = rememberScrollRailState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_ime_picker_title)) },
        text = {
            ScrollRail(state = rail, modifier = Modifier.heightIn(max = 380.dp)) {
                if (options.isEmpty()) {
                    Text(stringResource(R.string.layout_editor_ime_picker_empty))
                }
                for (option in options) {
                    val name = option.loadLabel(context.packageManager).toString()
                    WmRow(
                        title = name,
                        leading = {
                            RadioButton(selected = option.id == current, onClick = { onPick(option.id, name) })
                        },
                        onClick = { onPick(option.id, name) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

/** The blank grid a new secondary layout starts from: rows × columns of empty keys. */
private const val SecondarySkeletonRows = 3
private const val SecondarySkeletonColumns = 4

@Composable
internal fun KeyActionPickerDialog(
    current: KeyAction,
    onPick: (KeyAction) -> Unit,
    onDismiss: () -> Unit,
    /** Narrowed by the popup-alternates picker, which cannot use them all. */
    options: List<KeyActionOption> = KeyActionCatalog,
) {
    // A page of its own now (#665): the groups fold, a field searches every
    // action's name and description, and the page reopens the way it was left.
    val groups = options.groupBy { it.groupRes }.map { (groupRes, inGroup) ->
        PickerGroup(
            key = "group-$groupRes",
            title = stringResource(groupRes),
            rows = inGroup.map { option ->
                PickerRow(
                    key = "${option.titleRes}",
                    title = stringResource(option.titleRes),
                    subtitle = stringResource(option.detailRes),
                    selected = option.matches(current),
                    onPick = { onPick(option.build()) },
                )
            },
        )
    }
    ActionPickerPage(
        memoryKey = if (options === KeyActionCatalog) "key-actions" else "key-actions-${options.size}",
        title = stringResource(R.string.layout_editor_action_picker_title),
        groups = groups,
        onDismiss = onDismiss,
    )
}

// ---------------------------------------------------------------------------
// Raw JSON
// ---------------------------------------------------------------------------

/**
 * The escape hatch: the layout as text, for pasting one in or fixing something
 * the grid editor has no control for.
 *
 * Draft plus an explicit Apply, unlike the grid editor's auto-save — half-typed
 * JSON is not a layout, so saving as you go is not merely undesirable, it is
 * impossible. Applying runs the same repair the import path does, and says what
 * it changed rather than rewriting the text silently.
 */
@Composable
internal fun KeyLayoutJsonScreen(
    repository: SettingsRepository,
    settings: LiveSettings,
    layoutId: String,
    onDone: () -> Unit,
) {
    val title = stringResource(R.string.home_screen_layout_json_title)
    // The whole layout: whether it is there decides the screen, and the editor
    // opens on its text.
    val layout = settings.watch { findLayout(it.customLayouts, layoutId) }
    if (layout == null) {
        MissingJsonDocument(title, stringResource(R.string.layout_editor_missing_layout_message), onDone)
        return
    }
    LayoutJsonEditorScreen(
        title = title,
        documentKey = layoutId,
        root = LayoutJsonRoot.LAYOUT,
        settings = settings,
        initialText = { LayoutCodec.encodeForEditing(layout) },
        onApply = { text ->
            // The bare layout this screen prints, or the exported file that
            // wraps the same layout in its envelope: both are the user's
            // layout, and both are accepted.
            val parsed = LayoutCodec.decode(text) ?: LayoutFile.unwrap(text)
            if (parsed == null) {
                JsonApplyOutcome.Invalid
            } else {
                // The id in the text is ignored: this screen edits one layout,
                // and honouring a pasted id would silently overwrite a different
                // one, or create a second layout the user never asked for.
                val repaired = parsed.copy(id = layoutId).repair()
                repository.upsertCustomLayout(repaired.spec)
                JsonApplyOutcome.Applied(repaired.repairNotes)
            }
        },
        onBack = onDone,
    )
}

/**
 * [this] key given [action] in the editor; [changed] is whether that is a
 * different action from the one it had. A new action takes the old one's
 * label and icon with it (issue #769), and its text arms (#838): since #789 an
 * action key types them, so a letter's arms left on the Shift it became would
 * start typing under it. Typing text keeps all of them.
 */
internal fun Key.withEditedAction(action: KeyAction, changed: Boolean): Key {
    val relabel = changed && action != KeyAction.Text
    return copy(
        action = action,
        role = role.takeIf { action == KeyAction.Text },
        label = if (relabel) "" else label,
        shiftLabel = if (relabel) null else shiftLabel,
        icon = if (relabel) null else icon,
        flick = if (relabel) emptyMap() else flick,
        flickShift = if (relabel) emptyMap() else flickShift,
    )
}

/**
 * The note under a key's flick pad saying what an arm there takes over (#838),
 * or 0 for a key where an arm takes nothing: the fixed rules from the gestures
 * page, said where the arm is being given rather than discovered later.
 */
@StringRes
internal fun flickTakesOverNote(key: Key): Int = when {
    key.action == KeyAction.Shift || key.action is KeyAction.Mod -> R.string.layout_editor_flick_takes_modifier_drag
    key.action == KeyAction.Symbols || key.action == KeyAction.Letters -> R.string.layout_editor_flick_takes_layer_peek
    else -> 0
}
