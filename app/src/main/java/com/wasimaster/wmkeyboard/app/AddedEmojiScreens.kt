package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.EmojiCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiEntry
import com.wasimaster.wmkeyboard.core.emoji.EmojiOrder
import com.wasimaster.wmkeyboard.core.emoji.EmojiRenderCheck
import com.wasimaster.wmkeyboard.core.emoji.EmojiTestFile
import com.wasimaster.wmkeyboard.core.settings.AddedEmoji
import com.wasimaster.wmkeyboard.core.settings.MAX_ADDED_EMOJI
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Settings › Emoji › Added emoji: the way to a face a new Unicode release
 * brought before a build of the keyboard ships it. Route `emojiadded`.
 *
 * One emoji at a time through a dialog (paste the face, name it, pick its
 * tab), or a whole release at once from Unicode's `emoji-test.txt`. Both
 * write [com.wasimaster.wmkeyboard.core.settings.EmojiSettings.added]; the
 * keyboard folds that into its catalog, so an added face draws, searches,
 * suggests and lands in Recent like a bundled one.
 */
@Composable
internal fun AddedEmojiSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bundled = rememberBundledEmojiCatalog()
    val categories = remember(bundled) { EmojiOrder.catalogCategories(bundled) }
    val added = settings.watch { it.emoji.added }
    val fontChoice = settings.watch { it.emojiFont }
    val fontInstalled = settings.watch { it.emojiFontInstalled.installedId }
    // Which added faces nothing on this phone can draw: said on the row, since
    // the keyboard shows them anyway (a hand-added emoji is never hidden).
    val undrawable by produceState(emptySet<String>(), added, fontChoice, fontInstalled) {
        value = withContext(Dispatchers.Default) {
            val file = KeyboardFonts.emojiFontFile(context, fontChoice, fontInstalled)
            EmojiRenderCheck.unrenderable(added.map { it.emoji }, file)
        }
    }
    // null = no dialog; an AddedEmoji with an empty face = adding a new one.
    var editing by remember { mutableStateOf<AddedEmoji?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val current = added
            val found = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.requireInputStream(uri).use {
                        readCapped(it, EmojiTestFile.MAX_BYTES)
                    } ?: return@runCatching null
                    // The file's own marker: a picked text file of any other
                    // kind would otherwise read as "nothing new to add".
                    if (!text.contains("fully-qualified")) return@runCatching null
                    EmojiTestFile.missing(text.byteInputStream(), bundled, current.map { it.emoji })
                }.getOrNull()
            }
            importMessage = when {
                found == null -> context.getString(R.string.langemoji_emoji_added_import_failed)
                found.isEmpty() -> context.getString(R.string.langemoji_emoji_added_import_none)
                else -> {
                    val room = (MAX_ADDED_EMOJI - current.size).coerceAtLeast(0)
                    val taken = found.take(room)
                    repository.editAddedEmoji { list -> list + taken.map(::toAddedEmoji) }
                    if (taken.isEmpty()) {
                        context.resources.getQuantityString(
                            R.plurals.langemoji_emoji_added_full, MAX_ADDED_EMOJI, MAX_ADDED_EMOJI,
                        )
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.langemoji_emoji_added_import_done, taken.size, taken.size,
                        )
                    }
                }
            }
        }
    }

    SettingsGroup(
        stringResource(R.string.langemoji_emoji_added_group_title),
        info = stringResource(R.string.langemoji_emoji_added_info),
    ) {
        item {
            ActionRow(
                title = R.string.langemoji_emoji_added_add_title,
                subtitle = stringResource(R.string.langemoji_emoji_added_add_subtitle),
                action = stringResource(CommonR.string.common_add),
                enabled = added.size < MAX_ADDED_EMOJI && categories.isNotEmpty(),
            ) { editing = AddedEmoji(emoji = "", category = categories.firstOrNull().orEmpty()) }
        }
        item {
            ActionRow(
                title = R.string.langemoji_emoji_added_import_title,
                subtitle = stringResource(R.string.langemoji_emoji_added_import_subtitle),
                action = stringResource(CommonR.string.common_import),
                enabled = added.size < MAX_ADDED_EMOJI && bundled.isNotEmpty(),
            ) { importFile.launch(arrayOf("text/plain", "application/octet-stream")) }
        }
    }
    SettingsGroup(stringResource(R.string.langemoji_emoji_added_list_title)) {
        item(visible = added.isEmpty(), waitsOnSetting = false) { CaptionText(stringResource(R.string.langemoji_emoji_added_empty)) }
        for (entry in added) item(key = entry.emoji) {
            val builtIn = remember(bundled, entry.emoji) { EmojiCatalog.find(bundled, entry.emoji) != null }
            val label = entry.name.ifEmpty { entry.emoji }
            val details = buildList {
                add(emojiCategoryLabel(entry.category))
                if (entry.keywords.isNotEmpty()) add(entry.keywords.joinToString(", "))
            }.joinToString(" · ")
            val note = when {
                builtIn -> stringResource(R.string.langemoji_emoji_added_built_in)
                entry.emoji in undrawable -> stringResource(R.string.langemoji_emoji_added_undrawable_short)
                else -> null
            }
            WmRow(
                title = label,
                subtitle = listOfNotNull(details, note).joinToString("\n"),
                leading = { Text(entry.emoji, fontSize = 26.sp, modifier = Modifier.width(44.dp)) },
                swipeActions = listOf(
                    deleteSwipeAction(stringResource(R.string.langemoji_emoji_added_delete_desc, label)) {
                        scope.launch { repository.editAddedEmoji { list -> list.filterNot { it.emoji == entry.emoji } } }
                    },
                ),
            ) { editing = entry }
        }
        item(visible = added.isNotEmpty(), waitsOnSetting = false) {
            ActionRow(
                title = R.string.langemoji_emoji_added_clear_title,
                subtitle = pluralStringResource(R.plurals.langemoji_emoji_added_count, added.size, added.size),
                action = stringResource(CommonR.string.common_delete),
                confirm = stringResource(R.string.langemoji_emoji_added_clear_confirm),
            ) { scope.launch { repository.editAddedEmoji { emptyList() } } }
        }
    }

    editing?.let { start ->
        AddedEmojiDialog(
            initial = start,
            bundled = bundled,
            categories = categories,
            others = added.filter { it.emoji != start.emoji }.map { it.emoji },
            fontFile = { KeyboardFonts.emojiFontFile(context, fontChoice, fontInstalled) },
            onDismiss = { editing = null },
            onDelete = if (start.emoji.isEmpty()) {
                null
            } else {
                {
                    editing = null
                    scope.launch { repository.editAddedEmoji { list -> list.filterNot { it.emoji == start.emoji } } }
                }
            },
        ) { saved ->
            editing = null
            scope.launch {
                repository.editAddedEmoji { list ->
                    if (start.emoji.isEmpty()) {
                        list + saved
                    } else {
                        list.map { if (it.emoji == start.emoji) saved else it }
                    }
                }
            }
        }
    }
    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { importMessage = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/** One face's emoji, name, keywords and tab: the one form for adding and changing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddedEmojiDialog(
    initial: AddedEmoji,
    bundled: List<EmojiEntry>,
    categories: List<String>,
    others: List<String>,
    fontFile: () -> java.io.File?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (AddedEmoji) -> Unit,
) {
    var emoji by remember { mutableStateOf(initial.emoji) }
    var name by remember { mutableStateOf(initial.name) }
    var keywords by remember { mutableStateOf(initial.keywords.joinToString(", ")) }
    var category by remember { mutableStateOf(initial.category) }
    val face = emoji.trim()
    val clash = remember(face, bundled) { face.takeIf { it.isNotEmpty() }?.let { EmojiCatalog.find(bundled, it) } }
    val duplicate = face.isNotEmpty() && others.any { EmojiCatalog.matchKey(it) == EmojiCatalog.matchKey(face) }
    // A face with a space in it is two faces, or a word: the panel draws one
    // emoji per cell and a tap types exactly what is stored.
    val spaced = face.any { it.isWhitespace() }
    val undrawable by produceState(false, face) {
        value = face.isNotEmpty() && withContext(Dispatchers.Default) {
            EmojiRenderCheck.unrenderable(listOf(face), fontFile()).isNotEmpty()
        }
    }
    val problem = when {
        clash != null && clash.emoji != initial.emoji ->
            stringResource(R.string.langemoji_emoji_added_clash, emojiCategoryLabel(clash.category))
        duplicate -> stringResource(R.string.langemoji_emoji_added_duplicate)
        spaced -> stringResource(R.string.langemoji_emoji_added_spaced)
        face.length > MAX_FACE_LENGTH -> stringResource(R.string.langemoji_emoji_added_too_long)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.emoji.isEmpty()) R.string.langemoji_emoji_added_add_title else R.string.langemoji_emoji_added_edit_title,
                ),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (face.isNotEmpty()) {
                    Text(face, fontSize = 40.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.replace("\n", "") },
                    label = { Text(stringResource(R.string.langemoji_emoji_added_face_label)) },
                    supportingText = {
                        Text(
                            problem ?: stringResource(
                                if (undrawable) {
                                    R.string.langemoji_emoji_added_undrawable
                                } else {
                                    R.string.langemoji_emoji_added_face_hint
                                },
                            ),
                        )
                    },
                    isError = problem != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ") },
                    label = { Text(stringResource(R.string.langemoji_emoji_added_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it.replace("\n", " ") },
                    label = { Text(stringResource(R.string.langemoji_emoji_added_keywords_label)) },
                    supportingText = { Text(stringResource(R.string.langemoji_emoji_added_keywords_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.langemoji_emoji_added_category_label),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (id in categories) {
                        FilterChip(
                            selected = category == id,
                            onClick = { category = id },
                            label = { Text(emojiCategoryLabel(id)) },
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(CommonR.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = face.isNotEmpty() && problem == null && category.isNotEmpty(),
                onClick = {
                    onSave(
                        AddedEmoji(
                            emoji = face,
                            category = category,
                            name = name.trim(),
                            keywords = keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                        ),
                    )
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/**
 * The longest face the dialog takes, in UTF-16 units. The longest RGI emoji
 * (a family of four with tones, or a tagged subdivision flag) is under 20;
 * this leaves room for what Unicode adds without letting a paragraph in.
 */
private const val MAX_FACE_LENGTH = 32

private fun toAddedEmoji(entry: EmojiEntry) = AddedEmoji(
    emoji = entry.emoji,
    category = entry.category,
    name = entry.name.orEmpty(),
)

/** [stream] as UTF-8 text, or null once it runs past [max] bytes. */
private fun readCapped(stream: InputStream, max: Long): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        total += read
        if (total > max) return null
        out.write(buffer, 0, read)
    }
    return out.toString(Charsets.UTF_8.name())
}
