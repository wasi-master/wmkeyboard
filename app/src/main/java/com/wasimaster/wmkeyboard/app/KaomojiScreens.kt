package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.EmoticArchive
import com.wasimaster.wmkeyboard.core.emoji.Kaomoji
import com.wasimaster.wmkeyboard.core.emoji.KaomojiFiles
import com.wasimaster.wmkeyboard.core.emoji.KaomojiImport
import com.wasimaster.wmkeyboard.core.emoji.KaomojiKind
import com.wasimaster.wmkeyboard.core.emoji.KaomojiLibrary
import com.wasimaster.wmkeyboard.core.emoji.KaomojiStore
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Edit
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Star
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Visibility
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.settings.KaomojiRailButton
import com.wasimaster.wmkeyboard.core.settings.KaomojiRailSide
import com.wasimaster.wmkeyboard.core.settings.KaomojiStartView
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.stickers.StickerAddResult
import com.wasimaster.wmkeyboard.core.stickers.StickerImage
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ---- kaomoji ----

/** The kaomoji tab's settings and the door to its library. Route `kaomoji`. */
@Composable
internal fun KaomojiSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val model = rememberKaomojiModel(repository)
    // The switch that shows the tab stays on the Emoji panel page, beside the
    // row that opens this one; the tab's own rows are here whether it is on
    // or not, so it can be set up before it is shown.
    val rail = settings.watch { it.emoji.kaomoji.rail }
    SettingsGroup(stringResource(R.string.langemoji_kaomoji_tab_group)) {
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_kaomoji_split_title,
                stringResource(R.string.langemoji_kaomoji_split_subtitle),
                settings.watch { it.emoji.kaomoji.splitEmoticons },
                default = SettingsDefaults.emoji.kaomoji.splitEmoticons,
                info = stringResource(R.string.langemoji_kaomoji_split_info),
            ) { scope.launch { repository.setKaomojiSplitEmoticons(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_kaomoji_tag_tabs_title,
                stringResource(R.string.langemoji_kaomoji_tag_tabs_subtitle),
                settings.watch { it.emoji.kaomoji.tagTabs },
                default = SettingsDefaults.emoji.kaomoji.tagTabs,
                info = stringResource(R.string.langemoji_kaomoji_tag_tabs_info),
            ) { scope.launch { repository.setKaomojiTagTabs(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.langemoji_kaomoji_start_title,
                subtitle = stringResource(R.string.langemoji_kaomoji_start_subtitle),
                options = listOf(
                    KaomojiStartView.LAST_USED to stringResource(R.string.langemoji_kaomoji_start_last),
                    KaomojiStartView.RECENT to stringResource(R.string.langemoji_kaomoji_start_recent),
                    KaomojiStartView.FAVOURITES to stringResource(R.string.langemoji_kaomoji_start_favourites),
                    KaomojiStartView.ALL to stringResource(R.string.langemoji_kaomoji_start_all),
                ),
                selected = settings.watch { it.emoji.kaomoji.startView },
                default = SettingsDefaults.emoji.kaomoji.startView,
                info = stringResource(R.string.langemoji_kaomoji_start_info),
            ) { scope.launch { repository.setKaomojiStartView(it) } }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_kaomoji_rail_group)) {
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.langemoji_kaomoji_rail_title,
                subtitle = stringResource(R.string.langemoji_kaomoji_rail_subtitle),
                info = stringResource(R.string.langemoji_kaomoji_rail_info),
                options = listOf(
                    KaomojiRailSide.LEFT to stringResource(R.string.langemoji_kaomoji_rail_left),
                    KaomojiRailSide.RIGHT to stringResource(R.string.langemoji_kaomoji_rail_right),
                    KaomojiRailSide.OFF to stringResource(CommonR.string.common_off),
                ),
                selected = rail,
                default = SettingsDefaults.emoji.kaomoji.rail,
            ) { scope.launch { repository.setKaomojiRail(it) } }
        }
        // One switch per button, so a rail can carry only what its owner uses.
        for ((button, title) in KaomojiRailButtonTitles) {
            item(visible = rail != KaomojiRailSide.OFF, level = SettingLevel.ADVANCED) {
                val buttons = settings.watch { it.emoji.kaomoji.railButtons }
                ToggleSetting(
                    title,
                    null,
                    button in buttons,
                    default = button in SettingsDefaults.emoji.kaomoji.railButtons,
                ) { on -> scope.launch { repository.setKaomojiRailButtons(if (on) buttons + button else buttons - button) } }
            }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_kaomoji_typing_group)) {
        item {
            ToggleSetting(
                R.string.langemoji_kaomoji_suggest_title,
                stringResource(R.string.langemoji_kaomoji_suggest_subtitle),
                settings.watch { it.emoji.kaomoji.suggest },
                info = stringResource(R.string.langemoji_kaomoji_suggest_info),
                default = SettingsDefaults.emoji.kaomoji.suggest,
            ) { scope.launch { repository.setKaomojiSuggest(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.langemoji_kaomoji_in_search_title,
                stringResource(R.string.langemoji_kaomoji_in_search_subtitle),
                settings.watch { it.emoji.kaomoji.inSearch },
                default = SettingsDefaults.emoji.kaomoji.inSearch,
                info = stringResource(R.string.langemoji_kaomoji_in_search_info),
            ) { scope.launch { repository.setKaomojiInSearch(it) } }
        }
    }
    KaomojiLibraryGroup(model, context, onNavigate)
}

/** The library's rows: the faces, the tags, import, export and reset. */
@Composable
private fun KaomojiLibraryGroup(model: KaomojiModel, context: Context, onNavigate: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<KaomojiImportPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readKaomojiImport(context, uri) }
            if (read == null) message = context.getString(R.string.langemoji_kaomoji_import_unreadable) else preview = read
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(KaomojiFiles.MIME_JSON),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val library = model.library
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { it.write(KaomojiFiles.encode(library).toByteArray()) }
                }.isSuccess
            }
            message = if (ok) {
                context.resources.getQuantityString(R.plurals.langemoji_kaomoji_exported, library.items.size, library.items.size)
            } else {
                context.getString(R.string.langemoji_kaomoji_export_error)
            }
        }
    }
    val library = model.library
    SettingsGroup(stringResource(R.string.langemoji_kaomoji_library_group)) {
        item {
            NavRow(
                R.string.langemoji_kaomoji_faces_title,
                context.resources.getQuantityString(R.plurals.langemoji_kaomoji_faces_count, library.items.size, library.items.size),
                route = "kaomoji/faces",
            ) { onNavigate("kaomoji/faces") }
        }
        item {
            NavRow(
                R.string.langemoji_kaomoji_tags_title,
                context.resources.getQuantityString(R.plurals.langemoji_kaomoji_tags_count, library.tags.size, library.tags.size),
                route = "kaomoji/tags",
            ) { onNavigate("kaomoji/tags") }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                R.string.langemoji_kaomoji_import_title,
                stringResource(R.string.langemoji_kaomoji_import_subtitle),
                action = stringResource(R.string.langemoji_kaomoji_import_action),
            ) { importLauncher.launch(arrayOf("*/*")) }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                R.string.langemoji_kaomoji_export_title,
                stringResource(R.string.langemoji_kaomoji_export_subtitle),
                action = stringResource(R.string.langemoji_kaomoji_export_action),
            ) { exportLauncher.launch(KaomojiFiles.DEFAULT_FILE_NAME) }
        }
        item(level = SettingLevel.STANDARD) {
            ActionRow(
                R.string.langemoji_kaomoji_reset_title,
                stringResource(R.string.langemoji_kaomoji_reset_subtitle),
                action = stringResource(CommonR.string.common_reset),
                confirm = stringResource(R.string.langemoji_kaomoji_reset_confirm),
            ) { model.change { it.resetToDefaults() } }
        }
    }
    preview?.let { found ->
        KaomojiImportDialog(
            found,
            onDismiss = { preview = null },
        ) { replace ->
            preview = null
            scope.launch { message = applyKaomojiImport(context, model, found, replace) }
        }
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) } },
        )
    }
}

/** The rail's buttons, each with the switch that keeps it on the rail. */
private val KaomojiRailButtonTitles = listOf(
    KaomojiRailButton.SEARCH to R.string.langemoji_kaomoji_rail_search,
    KaomojiRailButton.ADD to R.string.langemoji_kaomoji_rail_add,
    KaomojiRailButton.EDIT to R.string.langemoji_kaomoji_rail_edit,
    KaomojiRailButton.TAGS to R.string.langemoji_kaomoji_rail_tags,
)

/**
 * Every face, filtered by a tag. Drag a face by its handle to move it (only
 * among the faces the filter shows; the rest keep their places), press one to
 * change it, and the bin removes it. Route `kaomoji/faces`.
 */
@Composable
internal fun KaomojiFacesScreen(repository: SettingsRepository) {
    val model = rememberKaomojiModel(repository)
    val library = model.library
    var filter by remember { mutableStateOf(KaomojiLibrary.VIEW_ALL) }
    var editing by remember { mutableStateOf<Kaomoji?>(null) }
    var adding by remember { mutableStateOf(false) }
    val faces = remember(library, filter) { library.view(filter, null) }
    SettingsGroup {
        item {
            ActionRow(
                R.string.langemoji_kaomoji_add_title,
                stringResource(R.string.langemoji_kaomoji_add_subtitle),
                action = stringResource(R.string.langemoji_kaomoji_add_action),
            ) { adding = true }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = filter == KaomojiLibrary.VIEW_ALL,
                    onClick = { filter = KaomojiLibrary.VIEW_ALL },
                    label = { Text(stringResource(R.string.langemoji_kaomoji_filter_all)) },
                )
                FilterChip(
                    selected = filter == KaomojiLibrary.VIEW_FAVOURITES,
                    onClick = { filter = KaomojiLibrary.VIEW_FAVOURITES },
                    label = { Text(stringResource(R.string.langemoji_kaomoji_filter_favourites)) },
                )
                for (tag in library.tags) {
                    FilterChip(selected = filter == tag, onClick = { filter = tag }, label = { Text(tag) })
                }
            }
        }
        item {
            if (faces.isEmpty()) {
                CaptionText(stringResource(R.string.langemoji_kaomoji_faces_empty))
            } else {
                ReorderableColumn(
                    items = faces,
                    label = { it.text },
                    onReorder = { order -> model.change { s -> s.reorder(order.map { it.id }) } },
                    onDelete = { face -> model.change { it.delete(face.id) } },
                    keepLast = false,
                ) { face ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editing = face }
                            .padding(vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                face.text,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (face.favourite) {
                                Icon(
                                    Icons.Outlined.Star,
                                    contentDescription = stringResource(R.string.langemoji_kaomoji_favourite),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        if (face.tags.isNotEmpty()) {
                            Text(
                                face.tags.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding) {
        val seedTag = filter.takeIf { !it.startsWith('\u0001') }
        KaomojiEditDialog(initial = null, seedTags = listOfNotNull(seedTag), onDismiss = { adding = false }) { text, tags, kind, favourite ->
            adding = false
            model.change { s -> s.add(text, tags, kind)?.let { if (favourite) s.setFavourite(it.id, true) } }
        }
    }
    editing?.let { face ->
        KaomojiEditDialog(initial = face, seedTags = face.tags, onDismiss = { editing = null }) { text, tags, kind, favourite ->
            editing = null
            model.change { s -> s.update(face.id, text, tags, kind)?.let { s.setFavourite(it.id, favourite) } }
        }
    }
}

/** A face's text, tags, family and star, in a dialog: the one form for adding and changing. */
@Composable
private fun KaomojiEditDialog(
    initial: Kaomoji?,
    seedTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (text: String, tags: List<String>, kind: KaomojiKind, favourite: Boolean) -> Unit,
) {
    var text by remember { mutableStateOf(initial?.text.orEmpty()) }
    var tags by remember { mutableStateOf(seedTags.joinToString(", ")) }
    var kind by remember { mutableStateOf(initial?.kind) }
    var favourite by remember { mutableStateOf(initial?.favourite == true) }
    val shownKind = kind ?: KaomojiKind.of(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.langemoji_kaomoji_add_title else R.string.langemoji_kaomoji_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace("\n", " ") },
                    label = { Text(stringResource(R.string.langemoji_kaomoji_face_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text(stringResource(R.string.langemoji_kaomoji_tags_label)) },
                    supportingText = { Text(stringResource(R.string.langemoji_kaomoji_tags_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = shownKind == KaomojiKind.KAOMOJI,
                        onClick = { kind = KaomojiKind.KAOMOJI },
                        label = { Text(stringResource(R.string.langemoji_kaomoji_kind_kaomoji)) },
                    )
                    FilterChip(
                        selected = shownKind == KaomojiKind.EMOTICON,
                        onClick = { kind = KaomojiKind.EMOTICON },
                        label = { Text(stringResource(R.string.langemoji_kaomoji_kind_emoticon)) },
                    )
                    FilterChip(
                        selected = favourite,
                        onClick = { favourite = !favourite },
                        label = { Text(stringResource(R.string.langemoji_kaomoji_favourite)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = KaomojiStore.cleanText(text) != null,
                onClick = { onSave(text, tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }, shownKind, favourite) },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/**
 * The tags in the order the keyboard's tag strip shows them. Drag to move,
 * the eye hides a tag from the strip (its faces stay in All), the pencil
 * renames, the bin removes the tag from every face. Route `kaomoji/tags`.
 */
@Composable
internal fun KaomojiTagsScreen(repository: SettingsRepository) {
    val model = rememberKaomojiModel(repository)
    val library = model.library
    var renaming by remember { mutableStateOf<String?>(null) }
    val counts = remember(library) { library.tags.associateWith { tag -> library.items.count { it.hasTag(tag) } } }
    SettingsGroup {
        item {
            ActionRow(
                R.string.langemoji_kaomoji_new_tag_title,
                null,
                action = stringResource(R.string.langemoji_kaomoji_add_action),
            ) { renaming = "" }
        }
        item {
            if (library.tags.isEmpty()) {
                CaptionText(stringResource(R.string.langemoji_kaomoji_tags_empty))
            } else {
                ReorderableColumn(
                    items = library.tags,
                    label = { it },
                    onReorder = { order -> model.change { it.reorderTags(order) } },
                    onDelete = { tag -> model.change { it.removeTag(tag) } },
                    keepLast = false,
                ) { tag ->
                    val hidden = library.isHidden(tag)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tag, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            LocalContext.current.resources.getQuantityString(
                                R.plurals.langemoji_kaomoji_faces_count, counts[tag] ?: 0, counts[tag] ?: 0,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { model.change { it.setTagHidden(tag, !hidden) } }) {
                        Icon(
                            if (hidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(
                                if (hidden) R.string.langemoji_kaomoji_tag_show else R.string.langemoji_kaomoji_tag_hide,
                            ),
                        )
                    }
                    IconButton(onClick = { renaming = tag }) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.langemoji_kaomoji_tag_rename))
                    }
                }
            }
        }
    }
    renaming?.let { from ->
        var name by remember(from) { mutableStateOf(from) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = {
                Text(stringResource(if (from.isEmpty()) R.string.langemoji_kaomoji_new_tag_title else R.string.langemoji_kaomoji_tag_rename))
            },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = KaomojiStore.cleanTag(name) != null,
                    onClick = {
                        renaming = null
                        model.change { s -> if (from.isEmpty()) s.addTag(name) else s.renameTag(from, name) }
                    },
                ) { Text(stringResource(CommonR.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(CommonR.string.common_cancel)) } },
        )
    }
}

// ---- the library on this side ----

/**
 * The settings app's handle on `kaomoji/kaomoji.json`. Every change is
 * written at once and announced through [SettingsRepository.bumpKaomojiVersion],
 * so the running keyboard reloads instead of saving its copy back over it.
 */
@Stable
internal class KaomojiModel(
    private val file: File,
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
) {
    var library by mutableStateOf(KaomojiLibrary.EMPTY)
        private set
    private var store: KaomojiStore? = null

    fun load() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { KaomojiStore(file) }
            store = loaded
            library = loaded.library()
        }
    }

    fun change(block: (KaomojiStore) -> Unit) {
        scope.launch {
            val s = store ?: withContext(Dispatchers.IO) { KaomojiStore(file) }.also { store = it }
            withContext(Dispatchers.IO) {
                // The keyboard may have written since this screen opened.
                s.reloadIfChanged()
                block(s)
                s.save()
            }
            library = s.library()
            repository.bumpKaomojiVersion()
        }
    }
}

@Composable
private fun rememberKaomojiModel(repository: SettingsRepository): KaomojiModel {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = remember {
        KaomojiModel(File(context.filesDir, KaomojiStore.FILE_PATH), scope, repository)
    }
    LaunchedEffect(model) { model.load() }
    return model
}

/** What an import file holds, for the dialog that asks how to bring it in. */
data class KaomojiImportPreview(
    val faces: List<KaomojiImport>,
    val tags: List<String>,
    /** Emotic's reaction images, already unpacked into the cache. */
    val images: List<EmoticArchive.Image> = emptyList(),
    val fromEmotic: Boolean = false,
)

/**
 * Reads [uri] as an Emotic export (its `.tar.gz` or the older bare database)
 * or as one of [KaomojiFiles]'s shapes. Null for a file that is neither.
 */
internal fun readKaomojiImport(context: Context, uri: Uri): KaomojiImportPreview? = runCatching {
    val head = ByteArray(HEADER_BYTES)
    val got = context.contentResolver.requireInputStream(uri).use { stream ->
        var n = 0
        while (n < head.size) {
            val r = stream.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        n
    }
    if (looksLikeEmotic(head, got)) {
        val staging = File(context.cacheDir, "emotic-import")
        val contents = context.contentResolver.requireInputStream(uri).use { EmoticArchive.read(it, staging) }
        KaomojiImportPreview(contents.faces, contents.tags, contents.images, fromEmotic = true)
            .takeIf { it.faces.isNotEmpty() || it.images.isNotEmpty() }
    } else {
        val text = context.contentResolver.requireInputStream(uri).use { stream ->
            String(stream.readNBytesCompat(MAX_TEXT_BYTES), Charsets.UTF_8)
        }
        KaomojiFiles.decode(text)?.let { KaomojiImportPreview(it.items, it.tags) }
    }
}.getOrNull()

private fun looksLikeEmotic(head: ByteArray, size: Int): Boolean {
    val gzip = size >= 2 && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
    val sqlite = size >= 16 && String(head, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
    val tar = size >= 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar"
    return gzip || sqlite || tar
}

private fun java.io.InputStream.readNBytesCompat(cap: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < cap) {
        val n = read(buffer, 0, minOf(buffer.size, cap - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

@Composable
private fun KaomojiImportDialog(found: KaomojiImportPreview, onDismiss: () -> Unit, onImport: (replace: Boolean) -> Unit) {
    val context = LocalContext.current
    val body = buildString {
        append(context.resources.getQuantityString(R.plurals.langemoji_kaomoji_import_faces, found.faces.size, found.faces.size))
        if (found.tags.isNotEmpty()) {
            append(' ')
            append(context.resources.getQuantityString(R.plurals.langemoji_kaomoji_import_tags, found.tags.size, found.tags.size))
        }
        if (found.images.isNotEmpty()) {
            append("\n\n")
            append(context.resources.getQuantityString(R.plurals.langemoji_kaomoji_import_images, found.images.size, found.images.size))
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (found.fromEmotic) R.string.langemoji_kaomoji_import_emotic_title else R.string.langemoji_kaomoji_import_title))
        },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onImport(false) }) { Text(stringResource(R.string.langemoji_kaomoji_import_merge)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onImport(true) }) { Text(stringResource(R.string.langemoji_kaomoji_import_replace)) }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/**
 * Brings [found] in: the faces into the library, Emotic's images into a new
 * sticker pack named after it, their tags kept as the stickers' search words.
 * Returns what to tell the user.
 */
internal suspend fun applyKaomojiImport(
    context: Context,
    model: KaomojiModel,
    found: KaomojiImportPreview,
    replace: Boolean,
): String {
    var added = 0
    if (found.faces.isNotEmpty() || replace) {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        model.change { s ->
            try {
                added = s.merge(found.faces, found.tags, replace)
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }
    var stickers = 0
    if (found.images.isNotEmpty()) {
        stickers = withContext(Dispatchers.IO) {
            val store = StickerPackStore.get(context)
            val pack = store.createPack(EMOTIC_PACK_NAME) ?: return@withContext -1
            var count = 0
            for (image in found.images) {
                val bytes = runCatching { image.file.readBytes() }.getOrNull() ?: continue
                val processed = (StickerImage.process(bytes) as? StickerImage.Result.Ok)?.sticker ?: continue
                val result = store.addSticker(pack.id, processed, keywords = image.tags)
                if (result is StickerAddResult.Added) count++ else if (result is StickerAddResult.PackFull) break
            }
            if (count == 0) store.deletePack(pack.id)
            count
        }
    }
    File(context.cacheDir, "emotic-import").deleteRecursively()
    return buildString {
        append(context.resources.getQuantityString(R.plurals.langemoji_kaomoji_import_done, added, added))
        when {
            stickers > 0 -> append("\n\n").append(
                context.resources.getQuantityString(R.plurals.langemoji_kaomoji_import_stickers_done, stickers, EMOTIC_PACK_NAME, stickers),
            )
            stickers < 0 -> append("\n\n").append(context.getString(R.string.langemoji_kaomoji_import_stickers_full))
        }
    }
}

/** The sticker pack Emotic's images land in: the app's own name, which is not translated. */
private const val EMOTIC_PACK_NAME = "Emotic"
private const val HEADER_BYTES = 512
private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
