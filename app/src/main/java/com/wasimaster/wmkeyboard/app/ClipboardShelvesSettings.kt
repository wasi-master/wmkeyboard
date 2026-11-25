package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.NAMED_CLIPBOARDS_MAX
import com.wasimaster.wmkeyboard.core.settings.NAMED_CLIPBOARD_NAME_MAX
import com.wasimaster.wmkeyboard.core.settings.NamedClipboard
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The user's named clipboards (#1542), on the Clipboard page: one row each,
 * which renames or deletes it, a row that adds one, and whether the panel's
 * search looks through all of them. With none added the group is the Add row
 * alone, and the keyboard's clipboard is what it always was.
 */
@Composable
internal fun ClipboardShelvesGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val shelves = settings.watch { it.clipboard.clipboards }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NamedClipboard?>(null) }
    val context = LocalContext.current
    // Import into a new clipboard: a config file this app exported, or text
    // with one clip a line. The clipboard is named after the file.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val app = context.applicationContext
        scope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        // A clipboard file, not a book: a few megabytes at most.
                        String(input.readNBytesCompat(CLIP_IMPORT_MAX_BYTES), Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            val name = clipImportName(app, uri, app.getString(R.string.clipboard_named_import_default_name))
            val count = read?.let { repository.importClipsAsClipboard(name, it) }
            Toast.makeText(
                app,
                if (count == null) {
                    app.getString(R.string.clipboard_named_import_failed)
                } else {
                    app.resources.getQuantityString(R.plurals.clipboard_named_import_done, count, count, name)
                },
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    SettingsGroup(
        stringResource(R.string.clipboard_named_group),
        info = stringResource(R.string.clipboard_named_group_info),
    ) {
        for (shelf in shelves) {
            item(key = "clipboard-shelf-${shelf.id}") {
                NavRow(
                    title = shelf.name,
                    subtitle = stringResource(R.string.clipboard_named_row_subtitle),
                ) { editing = shelf }
            }
        }
        item {
            NavRow(
                R.string.clipboard_named_title,
                stringResource(
                    if (shelves.size >= NAMED_CLIPBOARDS_MAX) R.string.clipboard_named_full else R.string.clipboard_named_subtitle,
                ),
            ) { if (shelves.size < NAMED_CLIPBOARDS_MAX) adding = true }
        }
        item {
            NavRow(
                R.string.clipboard_named_import_title,
                stringResource(R.string.clipboard_named_import_subtitle),
            ) {
                if (shelves.size < NAMED_CLIPBOARDS_MAX) {
                    importLauncher.launch(arrayOf("text/plain", "application/json", "application/octet-stream"))
                }
            }
        }
        item(visible = shelves.isNotEmpty()) {
            ToggleSetting(
                R.string.clipboard_search_all_title,
                stringResource(R.string.clipboard_search_all_subtitle),
                settings.watch { it.clipboard.searchAllClipboards },
                info = stringResource(R.string.clipboard_search_all_info),
                default = SettingsDefaults.clipboard.searchAllClipboards,
            ) { scope.launch { repository.setClipboardSearchAll(it) } }
        }
    }
    if (adding) {
        ClipboardNameDialog(
            title = stringResource(R.string.clipboard_named_title),
            initial = "",
            onDismiss = { adding = false },
            onDelete = null,
        ) { name ->
            adding = false
            scope.launch { repository.addNamedClipboard(name) }
        }
    }
    editing?.let { shelf ->
        ClipboardNameDialog(
            title = stringResource(R.string.clipboard_named_rename_title),
            initial = shelf.name,
            onDismiss = { editing = null },
            onDelete = {
                editing = null
                scope.launch { repository.removeNamedClipboard(shelf.id) }
            },
        ) { name ->
            editing = null
            scope.launch { repository.renameNamedClipboard(shelf.id, name) }
        }
    }
}

/**
 * Names a clipboard, new or kept. [onDelete], for one that exists, asks once
 * more before it goes, since its clips go with it.
 */
@Composable
private fun ClipboardNameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onDone: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.clipboard_named_delete_title, initial)) },
            text = { Text(stringResource(R.string.clipboard_named_delete_text)) },
            confirmButton = { TextButton(onClick = onDelete) { Text(stringResource(CommonR.string.common_delete)) } },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(CommonR.string.common_cancel)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace('\n', ' ').take(NAMED_CLIPBOARD_NAME_MAX) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.clipboard_named_name_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.clipboard_named_delete_action))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** Largest file Import into a new clipboard reads (#1542). */
private const val CLIP_IMPORT_MAX_BYTES = 8 * 1024 * 1024

/** Up to [max] bytes of this stream, whatever the Android version's InputStream offers. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < max) {
        val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

/** The new clipboard's name: the file's, without its extension, or [fallback]. */
private fun clipImportName(context: Context, uri: Uri, fallback: String): String {
    val shown = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty()
    val base = shown.substringBeforeLast('.').removeSuffix(".wmconfig").trim()
    return base.ifEmpty { fallback }
}
