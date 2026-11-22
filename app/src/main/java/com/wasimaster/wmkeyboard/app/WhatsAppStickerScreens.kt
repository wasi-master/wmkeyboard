package com.wasimaster.wmkeyboard.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Add
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Folder
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.notify.BackgroundTasks
import com.wasimaster.wmkeyboard.core.notify.TaskState
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPack
import com.wasimaster.wmkeyboard.core.stickers.StickerPackAdoption
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.whatsapp.WaStickersFile
import com.wasimaster.wmkeyboard.core.util.readBytesCapped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The WhatsApp stickers screen. */
internal const val WHATSAPP_STICKERS_ROUTE = "whatsapp_stickers"

/**
 * Where WhatsApp keeps every sticker that was sent or received on this phone,
 * relative to the primary shared storage. It sits under `Android/media` rather
 * than `Android/data`, which is what makes it readable at all: since Android
 * 11 the document picker refuses `Android/data` and `Android/obb`, and
 * `Android/media` is the folder WhatsApp moved its media to for exactly this
 * reason. The folder carries a `.nomedia`, so the photo picker the pack page
 * uses never lists these files; the document picker does.
 */
internal const val WHATSAPP_STICKER_FOLDER = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * The document URI of [WHATSAPP_STICKER_FOLDER], for opening the picker there
 * rather than at the storage root. Null below API 26, where the picker takes
 * no starting point; the user finds the folder by hand.
 */
private fun whatsAppFolderUri(): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$WHATSAPP_STICKER_FOLDER")
    } else {
        null
    }

/**
 * The stock multi-document picker, opened inside WhatsApp's sticker folder.
 * The contract has no argument for a starting folder, so the extra is added
 * to the intent it builds.
 */
private class PickWhatsAppStickers : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            whatsAppFolderUri()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}

/** What WhatsApp itself writes: a WebP per sticker, still or animated. */
private val PICK_MIME_TYPES = arrayOf("image/webp", "image/*")

/** One sticker file found in the picked folder. */
private class FolderSticker(val uri: Uri, val name: String, val modified: Long)

/** Where the folder route has got to. */
private sealed interface FolderScan {
    data object Scanning : FolderScan
    data class Found(val folderName: String, val stickers: List<FolderSticker>) : FolderScan
}

/**
 * WhatsApp stickers, three ways in.
 *
 * WhatsApp has no pack file and lets no other app read its packs. What is
 * open on the phone is the `.wastickers` file the sticker-maker apps export,
 * and the folder WhatsApp saves every sent and received sticker to. The first
 * is a pack with a name; the second is loose pictures, which become one pack
 * of the newest [StickerPackStore.MAX_STICKERS_PER_PACK], or a handful picked
 * by hand into a pack that already exists. Nothing here touches the network.
 */
@Composable
internal fun WhatsAppStickersScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { StickerPackStore.get(context) }

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The folder's import runs as a background task (#1522): leaving this
    // screen leaves it running, and coming back shows where it is.
    val startTask = rememberTaskStarter()
    val task = rememberTaskState(WHATSAPP_FOLDER_TASK)
    val progress = (task as? TaskState.Running)?.let { it.done.toInt() to it.total.toInt() }
    var scan by remember { mutableStateOf<FolderScan?>(null) }
    var picked by remember { mutableStateOf<List<Uri>?>(null) }
    var namingPack by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<Uri?>(null) }
    // Which of the folder's stickers are ticked on its pick page (#965).
    var folderChosen by remember { mutableStateOf<Set<Int>>(emptySet()) }

    val folderPackName = stringResource(R.string.import_whatsapp_folder_pack_name)
    val folderNone = stringResource(R.string.import_whatsapp_folder_none, WHATSAPP_STICKER_FOLDER)

    // A file is read, its stickers picked (#965) and the ticked ones added by
    // the same flow the Sticker packs screen's Import a pack row runs.
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importing = uri
    }
    importing?.let { uri ->
        StickerFileImport(uri) { report ->
            importing = null
            message = report
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        scan = FolderScan.Scanning
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { scanStickerFolder(context.contentResolver, tree) }.getOrNull()
            }
            if (found == null || found.stickers.isEmpty()) {
                scan = null
                message = folderNone
            } else {
                // The newest a pack can hold, ticked: what the folder route
                // added before there was a choice.
                folderChosen = (0 until minOf(found.stickers.size, StickerPackStore.MAX_STICKERS_PER_PACK)).toSet()
                scan = found
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(PickWhatsAppStickers()) { uris ->
        if (uris.isNotEmpty()) picked = uris
    }

    fun addFolder(found: FolderScan.Found, chosen: Set<Int>) {
        val app = context.applicationContext
        val wanted = found.stickers.filterIndexed { index, _ -> index in chosen }
        val total = minOf(wanted.size, StickerPackStore.MAX_STICKERS_PER_PACK)
        startTask(
            WHATSAPP_FOLDER_TASK,
            context.getString(R.string.import_stickers_task_title, folderPackName),
            StickerImportResult.Failed.describe(context),
        ) { report ->
            report.update(0, total.toLong(), app.getString(R.string.import_whatsapp_adding, 0, total))
            val result = runCatching {
                StickerPackAdoption.adopt(
                    store = store,
                    name = folderPackName,
                    incoming = wanted.map { sticker ->
                        StickerPackAdoption.Incoming(
                            label = sticker.name,
                            addedAt = sticker.modified,
                            read = {
                                runCatching {
                                    app.contentResolver.readBytesCapped(sticker.uri)
                                }.getOrNull()
                            },
                        )
                    },
                    source = StickerPack.whatsappSource(),
                    onProgress = { done, all ->
                        report.update(done.toLong(), all.toLong(), app.getString(R.string.import_whatsapp_adding, done, all))
                    },
                )
            }.getOrDefault(StickerImportResult.Failed)
            BackgroundTasks.Outcome(result.describe(app), ok = result is StickerImportResult.Imported)
        }
    }
    if (task is TaskState.Ended) {
        LaunchedEffect(task) {
            scan = null
            message = task.message
            BackgroundTasks.dismiss(WHATSAPP_FOLDER_TASK)
        }
    }

    fun addPicked(uris: List<Uri>, packId: String) {
        picked = null
        busy = true
        scope.launch {
            val outcome = addPickedStickers(context, store, packId, uris)
            busy = false
            message = outcome.describe(context)
        }
    }

    SettingsGroup(
        stringResource(R.string.import_whatsapp_section_title),
        info = stringResource(R.string.import_whatsapp_caption),
    ) {
        item {
            WmRow(
                title = stringResource(R.string.import_whatsapp_file_title),
                subtitle = stringResource(R.string.import_whatsapp_file_subtitle),
                icon = Icons.Outlined.FileOpen,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_whatsapp_file_title,
                enabled = !busy && progress == null,
                onClick = { fileLauncher.launch(WaStickersFile.IMPORT_MIME_TYPES) },
            )
        }
        item {
            val current = progress
            if (current != null) {
                WmRow(
                    title = stringResource(R.string.import_whatsapp_adding, current.first, current.second),
                    icon = Icons.Outlined.Add,
                    accent = routeAccent("sticker_packs"),
                    enabled = false,
                    supporting = {
                        LinearProgressIndicator(
                            progress = { if (current.second > 0) current.first.toFloat() / current.second else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    },
                )
            } else {
                WmRow(
                    title = stringResource(R.string.import_whatsapp_folder_title),
                    subtitle = stringResource(R.string.import_whatsapp_folder_subtitle),
                    icon = Icons.Outlined.Folder,
                    accent = routeAccent("sticker_packs"),
                    highlightKey = R.string.import_whatsapp_folder_title,
                    enabled = !busy && scan == null,
                    onClick = { folderLauncher.launch(whatsAppFolderUri()) },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.import_whatsapp_pick_title),
                subtitle = stringResource(R.string.import_whatsapp_pick_subtitle),
                icon = Icons.Outlined.PhotoLibrary,
                accent = routeAccent("sticker_packs"),
                highlightKey = R.string.import_whatsapp_pick_title,
                enabled = !busy && progress == null,
                onClick = { pickLauncher.launch(PICK_MIME_TYPES) },
            )
        }
    }

    when (val current = scan) {
        null -> Unit
        FolderScan.Scanning -> AlertDialog(
            onDismissRequest = {},
            text = { Text(stringResource(R.string.import_whatsapp_folder_scanning)) },
            confirmButton = {},
        )

        is FolderScan.Found -> {
            val count = current.stickers.size
            val cap = StickerPackStore.MAX_STICKERS_PER_PACK
            val shown = remember(current) { current.stickers.map { PickSticker(it.uri, it.name) } }
            StickerPickPage(
                title = stringResource(R.string.import_whatsapp_folder_title),
                subtitle = if (count > cap) {
                    stringResource(R.string.import_whatsapp_folder_found_capped, count, current.folderName, cap)
                } else {
                    pluralStringResource(R.plurals.import_whatsapp_folder_found, count, count, current.folderName)
                },
                stickers = shown,
                chosen = folderChosen,
                onChosenChange = { folderChosen = it },
                progress = progress,
                onAdd = { addFolder(current, folderChosen) },
                onDismiss = { scan = null },
            )
        }
    }

    picked?.let { uris ->
        if (namingPack == null) {
            PackChooserDialog(
                packs = store.packs(),
                onPick = { pack -> addPicked(uris, pack.id) },
                onNew = { namingPack = "" },
                onDismiss = { picked = null },
            )
        }
    }

    namingPack?.let { draft ->
        NameDialog(
            title = stringResource(R.string.import_sticker_pack_new_title),
            value = draft,
            onValueChange = { namingPack = it },
            onDismiss = { namingPack = null },
            onConfirm = {
                val created = store.createPack(draft)
                namingPack = null
                val uris = picked
                if (created == null) {
                    picked = null
                    message = context.resources.getQuantityString(
                        R.plurals.import_sticker_packs_full,
                        StickerPackStore.MAX_PACKS,
                        StickerPackStore.MAX_PACKS,
                    )
                } else if (uris != null) {
                    addPicked(uris, created.id)
                }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
    }
}

/** Which pack a handful of picked stickers should land in. */
@Composable
private fun PackChooserDialog(
    packs: List<StickerPack>,
    onPick: (StickerPack) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_whatsapp_pick_pack_title)) },
        text = {
            Column {
                for (pack in packs) {
                    TextButton(onClick = { onPick(pack) }, modifier = Modifier.fillMaxWidth()) {
                        Text(pack.name)
                    }
                }
                TextButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.import_whatsapp_pick_new_pack))
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
 * The sticker files in the picked folder, newest first, so that the cap a pack
 * has keeps the ones that arrived most recently. Only the folder itself is
 * read: WhatsApp keeps its stickers flat, and a subfolder would be somebody
 * else's. Blocking.
 */
private fun scanStickerFolder(resolver: ContentResolver, tree: Uri): FolderScan.Found {
    val treeId = DocumentsContract.getTreeDocumentId(tree)
    val folderName = resolver.query(
        DocumentsContract.buildDocumentUriUsingTree(tree, treeId),
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { rows -> if (rows.moveToFirst() && !rows.isNull(0)) rows.getString(0) else null }
        ?: tree.lastPathSegment.orEmpty().substringAfterLast('/')

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val stickers = ArrayList<FolderSticker>()
    resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId), projection, null, null, null)
        ?.use { rows ->
            val id = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modified = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            if (id < 0 || name < 0) return@use
            while (rows.moveToNext()) {
                val type = if (mime >= 0 && !rows.isNull(mime)) rows.getString(mime) else ""
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val displayName = if (rows.isNull(name)) "" else rows.getString(name)
                val webp = type == "image/webp" || displayName.endsWith(".webp", ignoreCase = true)
                if (!webp) continue
                stickers += FolderSticker(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, rows.getString(id)),
                    name = displayName.substringBeforeLast('.'),
                    modified = if (modified >= 0 && !rows.isNull(modified)) rows.getLong(modified) else 0L,
                )
            }
        }
    stickers.sortByDescending { it.modified }
    return FolderScan.Found(folderName, stickers)
}

/** The [BackgroundTasks] key of the folder import (#1522). */
private const val WHATSAPP_FOLDER_TASK = "whatsapp-sticker-folder"
