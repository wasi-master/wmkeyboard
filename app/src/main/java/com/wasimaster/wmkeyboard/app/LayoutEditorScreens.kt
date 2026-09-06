package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import java.util.Locale
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import com.wasimaster.wmkeyboard.core.layout.KeyRole
import com.wasimaster.wmkeyboard.core.layout.LayerSpec
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import androidx.compose.material3.Button
import com.wasimaster.wmkeyboard.core.layout.LayoutCodec
import com.wasimaster.wmkeyboard.core.layout.repair
import com.wasimaster.wmkeyboard.core.layout.tabletGridWidth
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.addons.AddonType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapHoriz
import com.wasimaster.wmkeyboard.core.layout.ConvertedLayout
import com.wasimaster.wmkeyboard.core.keyman.KeymanImport
import com.wasimaster.wmkeyboard.core.layout.ForeignLayouts
import com.wasimaster.wmkeyboard.core.layout.ForeignSource
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
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
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.layout.BuiltInLayouts
import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyAction
import com.wasimaster.wmkeyboard.core.layout.KeyAlternate
import com.wasimaster.wmkeyboard.core.layout.PanelFieldKind
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutLayer
import com.wasimaster.wmkeyboard.core.layout.LayoutSpec
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.ModifierKey
import com.wasimaster.wmkeyboard.core.layout.LayoutSeverity
import com.wasimaster.wmkeyboard.core.layout.compile
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
import com.wasimaster.wmkeyboard.core.layout.drawnFontScale
import com.wasimaster.wmkeyboard.core.layout.drawnLabel
import com.wasimaster.wmkeyboard.core.layout.drawnLabelScale
import com.wasimaster.wmkeyboard.core.layout.opensAlternatesPopup
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
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.layout.resolveLayouts
import com.wasimaster.wmkeyboard.core.layout.sidePadFor
import com.wasimaster.wmkeyboard.core.layout.spanBands
import com.wasimaster.wmkeyboard.core.layout.spanRowWidths
import com.wasimaster.wmkeyboard.core.layout.spanSlots
import com.wasimaster.wmkeyboard.core.layout.validateLayout
import com.wasimaster.wmkeyboard.core.script.ComposerType
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.isSupportedTool
import com.wasimaster.wmkeyboard.ime.ui.KbTheme
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import com.wasimaster.wmkeyboard.ime.ui.KeyboardThemeProvider
import com.wasimaster.wmkeyboard.ime.ui.LocalKbTheme
import com.wasimaster.wmkeyboard.ime.ui.keyShape
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton

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
private val FOREIGN_LAYOUT_MIME_TYPES =
    arrayOf("application/json", "text/plain", "application/octet-stream")

/** Largest foreign layout worth reading. The whole file is decoded as one string. */
private const val MAX_FOREIGN_LAYOUT_BYTES = 4 * 1024 * 1024

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
        ComposerType.CANGJIE -> R.string.layout_editor_composer_cangjie
        ComposerType.CANGJIE_QUICK -> R.string.layout_editor_composer_cangjie_quick
        ComposerType.JYUTPING -> R.string.layout_editor_composer_jyutping
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
    ComposerType.CANGJIE -> R.string.layout_editor_composer_cangjie_desc
    ComposerType.CANGJIE_QUICK -> R.string.layout_editor_composer_cangjie_quick_desc
    ComposerType.JYUTPING -> R.string.layout_editor_composer_jyutping_desc
}

@Composable
private fun ForeignLanguageDialog(
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // Remembered on the query: this recomposes on every letter typed, and
    // re-running the filter over the whole registry per keystroke is what makes
    // a search field feel heavy.
    val results = remember(query) { searchLanguages(query.trim().lowercase()) }
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
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.id }) { language ->
                        WmRow(
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
internal fun KeyLayoutsScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
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
    var foreignLangId by remember { mutableStateOf("") }
    var pickingLanguage by remember { mutableStateOf(false) }

    // CreateDocument cannot carry a payload, so the layout waiting to be written
    // is parked here between launching the picker and its result.
    var pendingExport by remember { mutableStateOf<LayoutSpec?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LayoutFile.MIME_TYPE),
    ) { uri ->
        val layout = pendingExport
        pendingExport = null
        if (uri == null || layout == null) return@rememberLauncherForActivityResult
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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { it.readBytes().decodeToString() }
                }.getOrNull()
            }
            val parsed = text?.let { LayoutFile.decode(it) }
            if (parsed == null) {
                message = context.getString(R.string.layout_editor_import_wrong_file_error)
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
                        null
                    } else {
                        // Keyman first: its files are JSON too, and the
                        // FlorisBoard reader would take one and return null
                        // rather than deferring, so order is the dispatch.
                        val text = bytes.decodeToString()
                        if (KeymanImport.looksLikeTouchLayout(text)) {
                            KeymanImport.convert(text, name)
                        } else {
                            ForeignLayouts.convert(text, name)
                        }
                    }
                }.getOrNull()
            }
            if (converted == null) {
                message = context.getString(R.string.layout_editor_foreign_wrong_file_error)
                return@launch
            }
            foreignLangId = converted.guessedLangId
            confirmForeign = converted
        }
    }

    // The layout the editor was last opened on. A grid takes a while to build,
    // and coming out of one to hunt for it again in a list of every layout you
    // have made and every shipped one you have on is the sort of small tax that
    // makes an editor tiring to use.
    val returnTo = remember { ReturnAnchor.take(KEYMAPS_ANCHOR) }
    val layouts = resolveLayouts(settings.customLayouts)
    val customIds = settings.customLayouts.map { it.id }.toSet()
    // "Shipped" is both the compiled built-ins and the JSON asset layouts.
    // Testing only BuiltInLayouts put every asset layout in neither group — an
    // enabled Français BÉPO was invisible here — and made an *edit* of one look
    // like a layout of the user's own, offering Delete where it should offer
    // Reset. resolveLayouts already treats the two the same way.
    val shippedIds = remember(layouts) {
        (BuiltInLayouts.all + AssetLayouts.all).mapTo(HashSet()) { it.id }
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
    /** Opens the editor, and remembers the row to come back to. */
    fun openEditor(id: String) {
        ReturnAnchor.arm(KEYMAPS_ANCHOR, id)
        onNavigate("keymap_edit/$id")
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
            item {
                ScrollAnchor(layout.id == returnTo) {
                    LayoutRow(
                        layout = layout,
                        enabled = layout.id in settings.enabledLayoutIds,
                        onEdit = { openEditor(layout.id) },
                        onExport = {
                            pendingExport = layout
                            exportLauncher.launch(LayoutFile.fileName(layout))
                        },
                        onDuplicate = { duplicateAndEdit(layout) },
                        onDelete = { confirmDelete = layout },
                        deleteIsReset = false,
                    )
                }
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
            item {
                ScrollAnchor(layout.id == returnTo) {
                    LayoutRow(
                        layout = layout,
                        enabled = false,
                        onEdit = { openEditor(layout.id) },
                        onExport = {
                            pendingExport = layout
                            exportLauncher.launch(LayoutFile.fileName(layout))
                        },
                        onDuplicate = { duplicateAndEdit(layout) },
                        onDelete = { confirmDelete = layout },
                        deleteIsReset = false,
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
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_foreign_title),
                subtitle = stringResource(R.string.layout_editor_foreign_subtitle),
                leading = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                onClick = { foreignLauncher.launch(FOREIGN_LAYOUT_MIME_TYPES) },
            )
        }
    }

    val builtIns = layouts.filter {
        it.id in shippedIds && it.id in settings.enabledLayoutIds
    }
    if (builtIns.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.layout_editor_built_in_title)) {
            for (layout in builtIns) {
                item {
                    ScrollAnchor(layout.id == returnTo) {
                        LayoutRow(
                            layout = layout,
                            enabled = layout.id in settings.enabledLayoutIds,
                            onEdit = { openEditor(layout.id) },
                            onExport = {
                                pendingExport = layout
                                exportLauncher.launch(LayoutFile.fileName(layout))
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
                        )
                    }
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
                TextButton(onClick = {
                    val id = "custom_${System.currentTimeMillis()}"
                    val name = imported.layout.name
                    scope.launch {
                        repository.upsertCustomLayout(imported.layout.copy(id = id))
                        message =
                            context.getString(R.string.layout_editor_import_done_message, name)
                    }
                    confirmImport = null
                }) { Text(stringResource(CommonR.string.common_import)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmImport = null }) {
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
                                ForeignSource.KEYMAN_TOUCH_LAYOUT ->
                                    R.string.layout_editor_foreign_from_keyman
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
                    scope.launch { repository.deleteCustomLayout(layout.id) }
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
) {
    val resources = LocalContext.current.resources
    WmRow(
        title = layout.name,
        subtitle = layoutSummary(resources, layout, enabled),
        trailing = {
            Row {
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription =
                            stringResource(R.string.layout_editor_export_desc, layout.name),
                    )
                }
                IconButton(onClick = onDuplicate) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription =
                            stringResource(R.string.layout_editor_duplicate_desc, layout.name),
                    )
                }
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            if (deleteIsReset) Icons.Outlined.Refresh else Icons.Outlined.Delete,
                            contentDescription = if (deleteIsReset) {
                                stringResource(R.string.layout_editor_reset_desc, layout.name)
                            } else {
                                stringResource(R.string.layout_editor_delete_desc, layout.name)
                            },
                        )
                    }
                }
            }
        },
        onClick = onEdit,
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
    settings: KeyboardSettings,
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
        val spec = resolveLayout(settings.customLayouts, layoutId)
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
 * Undo holds whole layouts rather than diffs. A layout is a few kB of data
 * classes, thirty of them cost nothing, and a diff type would need an inverse
 * for every edit the sheet can make — which is exactly the list that keeps
 * growing as actions gain payloads.
 */
internal const val UndoDepth = 30

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyLayoutEditorScreen(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    layoutId: String,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val layout = resolveLayouts(settings.customLayouts).firstOrNull { it.id == layoutId }
    if (layout == null) {
        Text(
            stringResource(R.string.layout_editor_missing_layout_message),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    // A secondary layout (issue #62) is one grid: no language, no layer chips,
    // no tablet widening, and it is never "on" — a key or the toolbar shows it.
    val secondary = layout.secondary
    var layer by rememberSaveable(layoutId) { mutableStateOf(LayoutLayer.LETTERS) }
    var selection by remember(layoutId, layer) { mutableStateOf<KeyRef?>(null) }
    var showShift by rememberSaveable(layoutId) { mutableStateOf(false) }
    // Draw the preview at the user's real key height instead of the clamped
    // one. Off by default because a tall setting pushes the grid off screen.
    var actualSize by rememberSaveable(layoutId) { mutableStateOf(false) }
    var sheetOpen by remember(layoutId, layer) { mutableStateOf(false) }

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
        undo = (undo + layout).takeLast(UndoDepth)
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
    val inheritable = layout.layer(layer) != null || BuiltInLayouts.default.layer(layer) != null
    val compiled = if (inheritable) {
        layout.compile(layer)
    } else {
        KeyboardLayout(name = "$layoutId/${layer.key}", rows = emptyList())
    }
    val rows = compiled.rows
    val rowHeights = compiled.rowHeights
    val selectedKey = selection?.let { rows.getOrNull(it.row)?.getOrNull(it.col) }
    // Whether the tablet expansion would actually do anything here, so its
    // toggle can say so. Asked of the letters layer on the widest form — the
    // gate is a property of the layout, not of whichever layer is on screen —
    // and only to word a subtitle, never to gate the toggle: a layout that the
    // transform declines today should still keep the author's answer on file.
    val tabletExpandApplies = remember(layout) {
        tabletGridWidth(layout.compile(LayoutLayer.LETTERS), DeviceForm.LARGE_TABLET) != null
    }

    SectionHeaderPublic(layout.name)

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
    SettingsGroup {
        item {
            WmRow(
                title = stringResource(R.string.layout_editor_name_title),
                subtitle = layout.name,
                onClick = { renaming = true },
            )
        }
        item {
            // Issue #61: a theme of the layout's own, the same override a
            // keyboard mode carries and the same picker, customs included.
            WmRow(
                title = stringResource(R.string.layout_editor_theme_title),
                subtitle = layout.themeId?.let { themeDisplayName(settings, it) }
                    ?: stringResource(R.string.layout_editor_theme_inherit_subtitle),
                trailing = {
                    if (layout.themeId != null) {
                        TextButton(onClick = { edit { it.copy(themeId = null) } }) {
                            Text(clearLabel)
                        }
                    }
                },
                onClick = { themePickerOpen = true },
            )
        }
        // A secondary layout types with the language of the layout under it,
        // so neither of these means anything on one.
        if (!secondary) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_editor_language_title),
                    subtitle = layout.langId.takeIf { it.isNotBlank() }
                        ?.let { LanguageRegistry.byId(it).displayName }
                        ?: stringResource(R.string.layout_editor_language_unset),
                    onClick = { languagePickerOpen = true },
                )
            }
            item {
                // Null is "whatever this script normally uses", which is the right
                // answer for almost every layout; the override exists because a
                // phonetic and a direct grid for the same language differ only here.
                val inheritLabel = stringResource(R.string.layout_editor_composer_inherit)
                ChoiceSetting(
                    title = R.string.layout_editor_composer_title,
                    subtitle = stringResource(R.string.layout_editor_composer_subtitle),
                    options = listOf<Pair<ComposerType?, String>>(null to inheritLabel) +
                        ComposerType.entries.map { it to composerLabel(it) },
                    selected = layout.composer,
                    info = stringResource(R.string.layout_editor_composer_info),
                    detail = { type -> ChoiceDetail(stringResource(composerDescRes(type))) },
                ) { chosen -> edit { it.copy(composer = chosen) } }
            }
        }
    }

    if (renaming) {
        LayoutNameDialog(
            initial = layout.name,
            onDismiss = { renaming = false },
            onConfirm = { typed ->
                renaming = false
                edit { it.copy(name = typed) }
            },
        )
    }

    if (layout.themeId != null || layout.layer(layer)?.themeId != null) {
        CaptionText(stringResource(R.string.layout_editor_theme_override_body))
    }

    if (themePickerOpen) {
        ModeThemePickerDialog(
            settings = settings,
            selectedId = layout.themeId,
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
            selectedId = layout.layer(layer)?.themeId,
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
            selected = layout.langId,
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
            current = layout.appearance?.fontId,
            title = stringResource(R.string.layout_editor_font_title),
            defaultLabel = stringResource(R.string.layout_editor_font_inherit),
            script = layout.script().id.takeIf { KeyboardFonts.scriptFontChoices(it) != null },
            onDismiss = { fontPickerOpen = false },
            onPick = { id ->
                fontPickerOpen = false
                edit { it.withAppearance(fontId = id) }
            },
        )
    }

    if (!secondary) LayerChips(layout, layer) { layer = it; selection = null }

    if (layout.layer(layer) == null) {
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

    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            enabled = undo.isNotEmpty(),
            onClick = {
                val previous = undo.last()
                undo = undo.dropLast(1)
                redo = redo + layout
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
                undo = undo + layout
                stepPushed = false
                save(next)
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Redo,
                contentDescription = stringResource(R.string.layout_editor_redo_desc),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.layout_editor_autosave_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The grid stays under the bar while the row-height and key-width
    // controls further down scroll (#43). At actual size it can be taller
    // than the viewport, so it scrolls with the body instead.
    val grid: @Composable () -> Unit = {
        EditorGrid(
            layout = compiled,
            settings = settings,
            selection = selection,
            showShift = showShift,
            actualSize = actualSize,
            onSelect = { ref ->
                selection = ref
                stepPushed = false
                sheetOpen = true
            },
        )
    }
    if (actualSize) grid() else RegisterPinned(grid)

    selection?.let { ref ->
        if (ref.row in rows.indices) {
            // A row is as wide as its own keys plus the columns a spanning key
            // above holds over it — that is the number the keyboard centres it
            // on, so it is the number the mismatch warning has to judge.
            val spanWidth = spanRowWidths(rows)[ref.row]
            RowActionBar(
                rowIndex = ref.row,
                rowCount = rows.size,
                rowWidth = spanWidth,
                gridWeight = gridWeightOf(rows),
                onAddKey = {
                    editRows { r ->
                        r.mapIndexed { i, row ->
                            if (i == ref.row) row + Key("new") else row
                        }
                    }
                },
                onDuplicateRow = {
                    editLayer { ls ->
                        val src = (0..ref.row) + ref.row + (ref.row + 1 until ls.rows.size)
                        ls.copy(
                            rows = src.map { ls.rows[it] },
                            rowHeights = pickHeights(ls.rowHeights, src),
                        )
                    }
                },
                onDeleteRow = {
                    editLayer { ls ->
                        val src = ls.rows.indices.filter { it != ref.row }
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
                    val held = spanRowWidths(r)[ref.row] -
                        r[ref.row].sumOf { it.width.toDouble() }.toFloat()
                    r.mapIndexed { i, row ->
                        if (i == ref.row) fitRowToGrid(row, gridWeightOf(r) - held) else row
                    }
                }
            }
            RowHeightRow(
                rowIndex = ref.row,
                height = rowHeights?.getOrNull(ref.row) ?: 1f,
            ) { setRowHeight(ref.row, it) }
        }
    }

    SettingsGroup {
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
        selection?.let { ref ->
            if (ref.row in rows.indices && rows[ref.row].size > 1) {
                item {
                    ReorderSetting(
                        title = stringResource(
                            R.string.layout_editor_reorder_keys_title,
                            ref.row + 1,
                        ),
                        dialogTitle = stringResource(R.string.layout_editor_key_order_dialog_title),
                        // Positions, not the keys themselves — the same shape the
                        // row reorder above uses, and for the stronger of its two
                        // reasons: a list of keys carries this composition's copy
                        // of them, so writing it back would put a key edited a
                        // frame ago back the way it was. It also disambiguates a
                        // row holding two identical keys.
                        items = rows[ref.row].indices.toList(),
                        label = { keyReorderLabel(context, rows[ref.row][it]) },
                    ) { order ->
                        editRows { r ->
                            r.mapIndexed { i, row ->
                                // Guarded because the stored row may have gained
                                // or lost a key since the dialog opened; a
                                // permutation that no longer fits it is dropped
                                // rather than allowed to delete keys.
                                if (i == ref.row && order.size == row.size) {
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
        }
        item {
            ToggleSetting(
                R.string.layout_editor_show_shift_title,
                stringResource(R.string.layout_editor_show_shift_subtitle),
                showShift,
            ) { showShift = it }
        }
        item {
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
        if (layer != LayoutLayer.LETTERS || secondary) {
            item {
                ToggleSetting(
                    R.string.layout_editor_persist_title,
                    stringResource(R.string.layout_editor_persist_subtitle),
                    layout.layer(layer)?.persistent ?: false,
                    info = stringResource(R.string.layout_editor_persist_info),
                ) { on -> editLayer { it.copy(persistent = on) } }
            }
        }
        // Issue #61 again, one layer down: a symbols page in its own colours.
        // Not on a secondary layout, whose one grid is the layout.
        if (!secondary) {
            item {
                val layerThemeId = layout.layer(layer)?.themeId
                WmRow(
                    title = stringResource(
                        R.string.layout_editor_layer_theme_title,
                        stringResource(layerTitleRes(layer)),
                    ),
                    subtitle = layerThemeId?.let { themeDisplayName(settings, it) }
                        ?: stringResource(R.string.layout_editor_layer_theme_inherit_subtitle),
                    trailing = {
                        if (layerThemeId != null) {
                            TextButton(onClick = { editLayer { it.copy(themeId = null) } }) {
                                Text(clearLabel)
                            }
                        }
                    },
                    onClick = { layerThemePickerOpen = true },
                )
            }
        }
        item {
            // Layer-scoped, and it sits above the layout-wide size deliberately:
            // the layout-wide one reaches every layer, so a symbol page whose
            // keys are punctuation had no way to stay put while the letters grew.
            // Null here follows the layout's, which is what almost every layer
            // wants and what the caption says.
            LayoutFontScaleRow(
                scale = layout.layer(layer)?.fontScale,
                title = stringResource(
                    R.string.layout_editor_layer_font_scale_label,
                    layout.layer(layer)?.fontScale ?: 1f,
                ),
                autoTitle = stringResource(R.string.layout_editor_layer_font_scale_auto_label),
                hint = stringResource(
                    R.string.layout_editor_layer_font_scale_hint,
                    stringResource(layerTitleRes(layer)),
                ),
                onChange = { value -> editLayerCoalesced { it.copy(fontScale = value) } },
            )
        }
        item {
            // Layout-wide, like the tablet row and the JSON row below it. The
            // font and the size are what issue #18 asked for: a grid whose keys
            // are labelled with words, or drawn in a script the global font does
            // not suit, needs type of its own, and neither the theme nor the
            // global settings can hold an answer for one layout.
            WmRow(
                title = stringResource(R.string.layout_editor_font_title),
                subtitle = layout.appearance?.fontId?.let {
                    KeyboardFonts.displayName(context, it, settings.customFontName)
                } ?: stringResource(R.string.layout_editor_font_inherit),
                onClick = { fontPickerOpen = true },
            )
        }
        item {
            LayoutFontScaleRow(
                scale = layout.appearance?.fontScale,
                title = stringResource(
                    R.string.layout_editor_font_scale_label,
                    layout.appearance?.fontScale ?: 1f,
                ),
                autoTitle = stringResource(R.string.layout_editor_font_scale_auto_label),
                hint = stringResource(R.string.layout_editor_font_scale_hint),
                // Coalesced: one drag of the slider is one undo step, the same
                // rule the row-height slider follows.
                onChange = { value -> editCoalesced { it.withAppearance(fontScale = value) } },
            )
        }
        if (!secondary) item {
            // Layout-wide, like the JSON row below it, rather than layer-scoped
            // like everything above — and `edit`, not `editCoalesced`, because
            // one deliberate flip deserves one undo step.
            ToggleSetting(
                R.string.layout_editor_tablet_expand_title,
                stringResource(
                    if (tabletExpandApplies) {
                        R.string.layout_editor_tablet_expand_subtitle
                    } else {
                        R.string.layout_editor_tablet_expand_subtitle_na
                    },
                ),
                layout.tabletExpand,
            ) { on -> edit { it.copy(tabletExpand = on) } }
        }
        item {
            NavRow(
                R.string.layout_editor_json_title,
                subtitle = stringResource(R.string.layout_editor_json_subtitle),
            ) { onNavigate("keymap_json/$layoutId") }
        }
        if (layout.layer(layer) != null) {
            item {
                WmRow(
                    title = stringResource(R.string.layout_editor_reset_layer_title),
                    subtitle = stringResource(
                        R.string.layout_editor_reset_layer_subtitle,
                        stringResource(layerTitleRes(layer)),
                    ),
                    leading = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                    onClick = {
                        edit { it.copy(layers = it.layers - layer.key) }
                        selection = null
                    },
                )
            }
        }
    }

    val findings = validateLayout(layout)
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
    CaptionText(
        stringResource(
            when {
                secondary -> R.string.layout_editor_secondary_caption
                layoutId in settings.enabledLayoutIds -> R.string.layout_editor_live_caption
                else -> R.string.layout_editor_not_live_caption
            },
        ),
    )

    val ref = selection
    if (sheetOpen && ref != null && selectedKey != null) {
        KeyEditSheet(
            key = selectedKey,
            ref = ref,
            rowSize = rows[ref.row].size,
            rowCount = rows.size,
            gridWeight = gridWeightOf(rows),
            // Counting the columns a spanning key above holds over this row, so
            // "Fill the row" offers the width that is genuinely left.
            otherWidthsInRow = spanRowWidths(rows)[ref.row] - selectedKey.width,
            // A change to *one field* of the key, never a whole key built here.
            // The sheet's copy of the key comes from the settings flow, which lags
            // the write it just made, so a control that handed back `key.copy(…)`
            // was also handing back every other field as it stood a frame or more
            // ago: type a label, then nudge the width, and the width edit wrote
            // the pre-label key straight back over it. Applying the change to the
            // key inside the same store edit is the same rule
            // `updateCustomLayout` already follows for the layout as a whole.
            onChange = { change ->
                editCoalesced { spec ->
                    withLayerRows(
                        spec,
                        baseLayerOf(spec).rows.mapIndexed { r, row ->
                            if (r != ref.row) {
                                row
                            } else {
                                row.mapIndexed { c, k -> if (c == ref.col) change(k) else k }
                            }
                        },
                    )
                }
            },
            onMove = { delta ->
                val target = ref.col + delta
                if (target in rows[ref.row].indices) {
                    editRows { r ->
                        r.mapIndexed { i, row ->
                            if (i != ref.row) {
                                row
                            } else {
                                row.toMutableList().apply { add(target, removeAt(ref.col)) }
                            }
                        }
                    }
                    selection = ref.copy(col = target)
                }
            },
            onDuplicate = {
                editRows { r ->
                    r.mapIndexed { i, row ->
                        if (i != ref.row) {
                            row
                        } else {
                            row.subList(0, ref.col + 1) + row[ref.col] + row.drop(ref.col + 1)
                        }
                    }
                }
            },
            onDelete = {
                editRows { r ->
                    r.mapIndexed { i, row ->
                        if (i != ref.row) row else row.filterIndexed { c, _ -> c != ref.col }
                    }
                }
                selection = null
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
            secondaryLayouts = secondaryLayouts(settings.customLayouts),
        )
    }
}

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
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onAddKey) {
            Icon(
                Icons.Outlined.Add,
                contentDescription =
                    stringResource(R.string.layout_editor_add_key_desc, rowIndex + 1),
            )
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
    layout: LayoutSpec,
    selected: LayoutLayer,
    onSelect: (LayoutLayer) -> Unit,
) {
    LazyRow(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(LayoutLayer.entries) { layer ->
            val authored = layout.layer(layer) != null
            FilterChip(
                selected = layer == selected,
                onClick = { onSelect(layer) },
                label = { Text(stringResource(layerTitleRes(layer)), maxLines = 1) },
                leadingIcon = if (authored) {
                    {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription =
                                stringResource(R.string.layout_editor_customised_desc),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
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
    settings: KeyboardSettings,
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
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        // The layout's own face, so the preview reads in the font the keyboard
        // will actually draw with rather than the global one.
        // …and in the theme the grid asks for (issue #61), so a layer given
        // its own colours is edited in them.
        KeyboardThemeProvider(
            settings.applyLayoutTheme(layout.themeId),
            layoutFontId = layout.appearance?.fontId,
        ) {
            val kb = LocalKbTheme.current
            // Forced LTR: a key's index in its row is its serialized order, so an
            // RTL locale mirroring the grid would make "move right" write
            // index - 1. Labels inside each cell still resolve their own bidi.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
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
                    val baseHeightDp = if (actualSize) {
                        settings.keyHeightDp
                    } else {
                        settings.keyHeightDp.coerceIn(38, 56)
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
                    // The layout's label-size multiplier, applied to the
                    // preview's own (smaller) type the same way the keyboard
                    // applies it to its own. A preview that ignored it would
                    // leave the one control on this screen with no visible
                    // effect until the user went and typed something.
                    val fontScale = layout.appearance.drawnFontScale()
                    for (band in spanBands(layout.rows)) {
                        if (band.first != band.last) {
                            EditorBand(
                                slots = slots.filter { it.row in band },
                                band = band,
                                kb = kb,
                                gridWeight = gridWeight,
                                heights = band.map { heightOf(it) },
                                selection = selection,
                                showShift = showShift,
                                fontScale = fontScale,
                                onSelect = onSelect,
                            )
                            continue
                        }
                        val r = band.first
                        val row = layout.rows[r]
                        val sidePad = sidePadFor(row, gridWeight)
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (sidePad > 0.01f) Spacer(Modifier.weight(sidePad))
                            row.forEachIndexed { c, key ->
                                EditorKeyCell(
                                    key = key,
                                    kb = kb,
                                    heightDp = heightOf(r),
                                    selected = selection == KeyRef(r, c),
                                    showShift = showShift,
                                    fontScale = fontScale,
                                    modifier = Modifier.weight(key.width),
                                ) { onSelect(KeyRef(r, c)) }
                            }
                            if (sidePad > 0.01f) Spacer(Modifier.weight(sidePad))
                        }
                    }
                }
            }
        }
    }
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
                EditorKeyCell(
                    key = slot.key,
                    kb = kb,
                    heightDp = heights[slot.row - band.first],
                    selected = selection == ref,
                    showShift = showShift,
                    fontScale = fontScale,
                    // Half the 3dp the spaced rows put between neighbours, on
                    // each side, so a band's keys read at the same size as the
                    // rows above and below it.
                    modifier = Modifier.padding(horizontal = 1.5.dp),
                ) { onSelect(ref) }
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
            if (cellIcon != null) {
                Icon(
                    cellIcon,
                    contentDescription = primary.ifBlank { key.icon },
                    tint = foreground,
                    modifier = Modifier.size(20.dp),
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
    KeyAction.InputMethodPicker -> "keyboard"
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** The actions offered; a panel layout narrows it and adds its components. */
    catalog: List<KeyActionOption> = KeyActionCatalog,
    /** The components a panel layout may place; null for a typing layout. */
    fieldKinds: List<PanelFieldKind>? = null,
    /** The secondary layouts an "Open a layout" key may name; see [KeyAction.Layout]. */
    secondaryLayouts: List<LayoutSpec> = emptyList(),
) {
    var pickingAction by remember { mutableStateOf(false) }
    // Which tool this key opens, when the picker put a tool action on it.
    var pickingTool by remember { mutableStateOf(false) }
    // Which secondary layout it shows, when the picker put a layout action on it.
    var pickingLayout by remember { mutableStateOf(false) }
    // Which operation an edit key runs, and which component a field cell hosts.
    var pickingEdit by remember { mutableStateOf(false) }
    var pickingField by remember { mutableStateOf(false) }
    // A component's cell has a size and a kind and nothing else to edit.
    val isField = key.action is KeyAction.Field
    // Held as text so a half-typed entry survives; parsed on every change.
    var alternates by remember(ref) { mutableStateOf(key.longPress.joinToString(" ")) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SectionHeaderPublic(
                stringResource(
                    R.string.layout_editor_key_position_title,
                    ref.row + 1,
                    ref.col + 1,
                ),
            )

            if (!isField) SheetField(
                label = stringResource(R.string.layout_editor_key_label_label),
                value = key.label,
                supporting = stringResource(R.string.layout_editor_key_label_hint),
                resetKey = ref,
            ) { text -> onChange { it.copy(label = text) } }

            if (!isField) SheetField(
                label = stringResource(R.string.layout_editor_key_output_label),
                value = key.output.orEmpty(),
                // Says what a blank field types, and — once there is a label to
                // name — what *this* key types, because "the key types the label"
                // read as a rule about some other key to the person who filed
                // issue #16. A key with neither is called out as well: repair
                // deletes it the moment the layout is turned on.
                supporting = outputFieldSupport(key),
                resetKey = ref,
            ) { text -> onChange { it.copy(output = text.ifBlank { null }) } }

            if (!isField) SheetField(
                label = stringResource(R.string.layout_editor_key_shift_label_label),
                value = key.shiftLabel.orEmpty(),
                supporting = stringResource(R.string.layout_editor_key_shift_label_hint),
                resetKey = ref,
            ) { text -> onChange { it.copy(shiftLabel = text.ifBlank { null }) } }

            val option = catalog.firstOrNull { it.matches(key.action) }
            val actionDetail = option?.let { stringResource(it.detailRes) }
            NavRow(
                title = R.string.layout_editor_action_row_title,
                subtitle = actionDetail,
                value = stringResource(
                    option?.titleRes ?: R.string.layout_editor_action_unknown,
                ),
            ) { pickingAction = true }

            // Broadcast keys carry a free-form action string the automation app
            // listens for; every other action is self-contained.
            (key.action as? KeyAction.Broadcast)?.let { broadcast ->
                SheetField(
                    label = stringResource(R.string.layout_editor_broadcast_field_label),
                    value = broadcast.action,
                    supporting = stringResource(R.string.layout_editor_broadcast_field_hint),
                    resetKey = ref,
                ) { text -> onChange { it.copy(action = KeyAction.Broadcast(text.trim())) } }
            }

            // A tool key carries which tool it opens. A row rather than a field:
            // there are forty of them and they have names, not values.
            (key.action as? KeyAction.Tool)?.let { toolAction ->
                NavRow(
                    title = R.string.layout_editor_tool_row_title,
                    subtitle = stringResource(R.string.layout_editor_tool_row_subtitle),
                    value = stringResource(toolTitle(toolAction.tool)),
                ) { pickingTool = true }
            }

            // A layout key carries which secondary layout it shows.
            (key.action as? KeyAction.Layout)?.let { layoutAction ->
                NavRow(
                    title = R.string.layout_editor_layout_row_title,
                    subtitle = stringResource(R.string.layout_editor_layout_row_subtitle),
                    value = secondaryLayouts.firstOrNull { it.id == layoutAction.id }?.name
                        ?: stringResource(R.string.layout_editor_layout_row_unset),
                ) { pickingLayout = true }
            }

            // An edit key carries which operation it runs.
            (key.action as? KeyAction.Edit)?.let { edit ->
                NavRow(
                    title = R.string.layout_editor_edit_row_title,
                    subtitle = stringResource(R.string.layout_editor_edit_row_subtitle),
                    value = stringResource(textEditActionTitle(edit.op)),
                ) { pickingEdit = true }
            }

            // A component's cell carries which component it hosts.
            (key.action as? KeyAction.Field)?.let { field ->
                NavRow(
                    title = R.string.layout_editor_field_row_title,
                    subtitle = stringResource(R.string.layout_editor_field_row_subtitle),
                    value = stringResource(fieldTitleRes(field.kind)),
                ) { pickingField = true }
            }

            // Braille dot keys carry which of the six dots this key is.
            (key.action as? KeyAction.BrailleDot)?.let { brailleDot ->
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

            KeyWidthRow(
                width = key.width,
                gridWeight = gridWeight,
                otherWidthsInRow = otherWidthsInRow,
                resetKey = ref,
            ) { width -> onChange { it.copy(width = width) } }

            KeyRowSpanRow(
                span = key.rowSpan,
                rowsBelow = rowCount - ref.row - 1,
            ) { span -> onChange { it.copy(rowSpan = span) } }

            if (!isField) KeyLabelScaleRow(key) { scale -> onChange { it.copy(labelScale = scale) } }

            if (key.action == KeyAction.Text) {
                SheetField(
                    label = stringResource(R.string.layout_editor_icon_field_label),
                    value = key.icon.orEmpty(),
                    supporting = iconFieldSupport(key.icon),
                    resetKey = ref,
                ) { text -> onChange { it.copy(icon = text.ifBlank { null }) } }

                SheetField(
                    label = stringResource(R.string.layout_editor_icon_hint_field_label),
                    value = key.iconHint.orEmpty(),
                    supporting = iconFieldSupport(key.iconHint),
                    resetKey = ref,
                ) { text -> onChange { it.copy(iconHint = text.ifBlank { null }) } }
            }

            // Not only text keys: every key whose press and hold is free can
            // carry alternates, which is what the enter key was missing (issue
            // #22). The ones left out are the ones that hold to repeat or chord,
            // where the popup would never open — see [Key.canHoldAlternates].
            if (key.canHoldAlternates()) {
                // Issue #41: a Select key's hold already means something —
                // selection mode for as long as the finger is down, the same as
                // a hold on the Selection mode tool. Alternates win over it, so
                // say so before the first one is added rather than leaving the
                // gesture to quietly stop working.
                if ((key.action as? KeyAction.Edit)?.op == TextEditAction.SELECT) {
                    CaptionText(stringResource(R.string.layout_editor_select_hold_notice))
                }
                SheetField(
                    label = stringResource(R.string.layout_editor_alternates_field_label),
                    value = alternates,
                    supporting = stringResource(R.string.layout_editor_alternates_field_hint),
                    resetKey = ref,
                ) { text ->
                    alternates = text
                    onChange { it.copy(longPress = parseAlternates(text)) }
                }
                AlternatePreview(parseAlternates(alternates))

                ActionAlternatesRows(key.actionAlternates, secondaryLayouts) { alternatesList ->
                    onChange { it.copy(actionAlternates = alternatesList) }
                }
            }

            if (key.action == KeyAction.Text) {
                RoleRow(key.role) { role -> onChange { it.copy(role = role) } }
            }

            if (!isField) HintRow(key, onChange)

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.layout_editor_delete_key_action))
                }
                Spacer(Modifier.weight(1f))
                IconButton(enabled = ref.col > 0, onClick = { onMove(-1) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.layout_editor_move_left_desc),
                    )
                }
                IconButton(enabled = ref.col < rowSize - 1, onClick = { onMove(+1) }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.layout_editor_move_right_desc),
                    )
                }
                IconButton(onClick = onDuplicate) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription =
                            stringResource(R.string.layout_editor_duplicate_key_desc),
                    )
                }
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
                onChange {
                    it.copy(
                        action = action,
                        role = it.role.takeIf { _ -> action == KeyAction.Text },
                    )
                }
                pickingAction = false
                // Straight on to which tool, rather than leaving the key on
                // whichever one the catalog entry had to name as its default.
                if (action is KeyAction.Tool) pickingTool = true
                if (action is KeyAction.Layout) pickingLayout = true
                if (action is KeyAction.Edit) pickingEdit = true
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
    key.label.isNotBlank() ->
        stringResource(R.string.layout_editor_key_output_hint_typed, key.label)
    else -> stringResource(R.string.layout_editor_key_output_hint_none)
}

/** Inline validity feedback for the icon / icon-hint name fields. */
@Composable
private fun iconFieldSupport(name: String?): String = when {
    name.isNullOrBlank() -> stringResource(R.string.layout_editor_icon_field_hint)
    KeyIcons.byName(name) != null -> stringResource(R.string.layout_editor_icon_found_hint, name)
    else -> stringResource(R.string.layout_editor_icon_missing_hint, name)
}

/** The first alternate is also the corner hint, and a flat string cannot say so. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlternatePreview(alternates: List<String>) {
    if (alternates.isEmpty()) return
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        alternates.forEachIndexed { index, alternate ->
            AssistChip(
                onClick = {},
                label = { Text(alternate) },
                leadingIcon = if (index == 0) {
                    {
                        Text(
                            stringResource(R.string.layout_editor_alternate_hint_badge),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
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
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionAlternatesRows(
    alternates: List<KeyAlternate>,
    secondaryLayouts: List<LayoutSpec>,
    onChange: (List<KeyAlternate>) -> Unit,
) {
    // The chip whose action is being picked; [AddingAlternate] for a new one.
    var editing by remember { mutableStateOf<Int?>(null) }
    // Where a tool, just chosen as an action, has to land.
    var pickingToolAt by remember { mutableStateOf<Int?>(null) }
    // Same for a secondary layout.
    var pickingLayoutAt by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.layout_editor_action_alternates_label),
            style = MaterialTheme.typography.bodyLarge,
        )
        CaptionText(stringResource(R.string.layout_editor_action_alternates_hint))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            alternates.forEachIndexed { index, alternate ->
                InputChip(
                    selected = false,
                    onClick = { editing = index },
                    label = { Text(actionAlternateName(alternate, secondaryLayouts)) },
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
            TextButton(onClick = { editing = AddingAlternate }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.layout_editor_action_alternate_add_action))
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

/**
 * What a chip calls an alternate: the tool's name, the action's name from the
 * catalog, or — for an action a hand-written layout chose that the picker does
 * not list — the glyph the popup will draw.
 */
@Composable
private fun actionAlternateName(alternate: KeyAlternate, secondaryLayouts: List<LayoutSpec>): String {
    val action = alternate.action
    if (action is KeyAction.Tool) return stringResource(toolTitle(action.tool))
    if (action is KeyAction.Layout) {
        return secondaryLayouts.firstOrNull { it.id == action.id }?.name
            ?: alternate.drawnLabel()
    }
    if (action is KeyAction.Edit) return stringResource(textEditActionTitle(action.op))
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
    onChange: (String) -> Unit,
) {
    var text by remember(resetKey) { mutableStateOf(value) }
    var pending by remember(resetKey) { mutableStateOf<String?>(null) }
    when {
        pending == null -> if (value != text) text = value
        value == pending -> pending = null
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            pending = it
            onChange(it)
        },
        label = { Text(label) },
        supportingText = { Text(supporting) },
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
private fun HintRow(key: Key, onChange: ((Key) -> Key) -> Unit) {
    val hasIconHint = key.iconHint != null
    val hasCharHint = key.opensAlternatesPopup() && key.longPress.isNotEmpty()
    if (!key.hideHint && !key.forceHint && !hasIconHint && !hasCharHint) return
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
                stringResource(
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
        ),
        selected = role,
        detail = { slot ->
            ChoiceDetail(
                stringResource(
                    when (slot) {
                        null -> R.string.layout_editor_role_none_desc
                        KeyRole.Comma -> R.string.layout_editor_role_comma_desc
                        KeyRole.Period -> R.string.layout_editor_role_period_desc
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.layout_editor_row_height_label, height),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
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
    val next = LayoutAppearance(fontId = fontId, fontScale = fontScale)
    return copy(appearance = next.takeUnless { it.isEmpty })
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (scale == null) autoTitle else title,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            if (scale == null) {
                stringResource(R.string.layout_editor_key_label_scale_auto_label)
            } else {
                stringResource(R.string.layout_editor_key_label_scale_label, scale)
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.layout_editor_key_label_scale_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (scale != null) {
            Slider(
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
    KeyAction.Shift, KeyAction.CapsLock, KeyAction.Delete, KeyAction.ForwardDelete,
    KeyAction.Enter, KeyAction.Newline, KeyAction.LanguageSwitch,
    KeyAction.InputMethodPicker, KeyAction.Emoji, KeyAction.Space,
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            pluralStringResource(R.plurals.layout_editor_key_row_span_label, span, span),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.layout_editor_key_row_span_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.layout_editor_key_width_label, width),
            style = MaterialTheme.typography.bodyLarge,
        )
        // Continuous, landing on hundredths. It used to move in quarters, on the
        // grounds that a free slider writes 1.0374 into a file people are invited
        // to hand-edit — true, but quarters cannot express the 1.43 that seven
        // keys need to fill a ten-wide grid, and rounding to the two decimals the
        // number is displayed at answers both.
        Slider(
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
private fun SecondaryLayoutPickerDialog(
    current: String?,
    options: List<LayoutSpec>,
    onDismiss: () -> Unit,
    onPick: (LayoutSpec) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_layout_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout_editor_action_picker_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                var lastGroup: Int? = null
                for (option in options) {
                    if (option.groupRes != lastGroup) {
                        SectionHeaderPublic(stringResource(option.groupRes))
                        lastGroup = option.groupRes
                    }
                    WmRow(
                        title = stringResource(option.titleRes),
                        subtitle = stringResource(option.detailRes),
                        leading = {
                            RadioButton(
                                selected = option.matches(current),
                                onClick = { onPick(option.build()) },
                            )
                        },
                        onClick = { onPick(option.build()) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
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
    settings: KeyboardSettings,
    layoutId: String,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val layout = resolveLayouts(settings.customLayouts).firstOrNull { it.id == layoutId }
    if (layout == null) {
        Text(
            stringResource(R.string.layout_editor_missing_layout_message),
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    var text by rememberSaveable(layoutId) { mutableStateOf(LayoutCodec.encodeForEditing(layout)) }
    var error by remember { mutableStateOf<String?>(null) }
    var repairs by remember { mutableStateOf<List<LayoutMessage>>(emptyList()) }
    // The Apply button is a plain lambda, so the message it may set is read here.
    val invalidJsonMessage = stringResource(R.string.layout_editor_json_invalid_error)


    OutlinedTextField(
        value = text,
        onValueChange = { text = it; error = null },
        label = { Text(stringResource(R.string.layout_editor_json_field_label)) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = rememberJsonSyntaxHighlighter(),
        // Capped, and scrolling inside itself. Uncapped the field grew to the
        // height of the whole document, which put Apply — and the repair notes
        // it prints — dozens of screens below the fold on any real layout.
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp, max = 420.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )

    if (repairs.isNotEmpty()) {
        SettingsGroup(
            stringResource(R.string.layout_editor_json_applied_title),
            info = stringResource(R.string.layout_editor_json_caption),
        ) {
            for (note in repairs) {
                item {
                    WmRow(
                        title = note.format(context.resources),
                    )
                }
            }
        }
    }

    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Button(
            enabled = text.isNotBlank(),
            onClick = {
                // The bare layout this screen prints, or the exported file
                // that wraps the same layout in its envelope: both are the
                // user's layout, and both are accepted.
                val parsed = LayoutCodec.decode(text) ?: LayoutFile.unwrap(text)
                if (parsed == null) {
                    error = invalidJsonMessage
                    return@Button
                }
                // The id in the text is ignored: this screen edits one layout,
                // and honouring a pasted id would silently overwrite a different
                // one — or create a second layout the user never asked for.
                val repaired = parsed.copy(id = layoutId).repair()
                repairs = repaired.repairNotes
                scope.launch {
                    repository.upsertCustomLayout(repaired.spec)
                    if (repaired.repairNotes.isEmpty()) onDone()
                }
            },
        ) { Text(stringResource(R.string.layout_editor_apply_action)) }
    }
}
