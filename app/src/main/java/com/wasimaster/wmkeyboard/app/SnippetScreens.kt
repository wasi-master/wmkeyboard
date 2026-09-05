package com.wasimaster.wmkeyboard.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Star
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.AssistChip
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.InputChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.snippets.MultiExpand
import com.wasimaster.wmkeyboard.core.snippets.MultiExpandMode
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetFolder
import com.wasimaster.wmkeyboard.core.snippets.SnippetIndex
import com.wasimaster.wmkeyboard.core.snippets.SnippetMatcher
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.util.requireOutputStream
import com.wasimaster.wmkeyboard.core.snippets.SnippetPayload
import com.wasimaster.wmkeyboard.core.snippets.SnippetVariable
import com.wasimaster.wmkeyboard.core.snippets.UppercaseStyle
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoFile
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoHub
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoManifest
import com.wasimaster.wmkeyboard.core.snippets.espanso.EspansoWriter
import kotlinx.coroutines.launch

// ---- text expander ----

/**
 * The Text Expander screen: every snippet setting there is.
 *
 * It is a Features row rather than the Snippets tool page, which now holds one
 * row that opens this — the same split the Emoji tool has. A snippet expands
 * while you type whether or not the tool is on the toolbar, so its settings
 * are not the tool's.
 */
@Composable
internal fun SnippetSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, "snippets/snippets.json") }
    // SnippetStore's constructor reads and JSON-parses the file, so it (and
    // every save) runs on Dispatchers.IO, not during composition or on a click.
    var store by remember { mutableStateOf<SnippetStore?>(null) }
    var snippets by remember { mutableStateOf<List<Snippet>>(emptyList()) }
    var folders by remember { mutableStateOf<List<SnippetFolder>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    // The folder being renamed, or a blank one standing for "add a folder".
    var namingFolder by remember { mutableStateOf<SnippetFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<SnippetFolder?>(null) }

    LaunchedEffect(Unit) {
        val s = withContext(Dispatchers.IO) { SnippetStore(file) }
        snippets = s.items()
        folders = s.folders()
        store = s
    }

    fun mutate(block: (SnippetStore) -> Unit) {
        val s = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                block(s)
                s.save()
            }
            snippets = s.items()
            folders = s.folders()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SnippetFile.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireOutputStream(uri).use { out ->
                        out.write(
                            SnippetFile.encode(
                                current,
                                appVersion = BuildConfig.VERSION_CODE,
                                appVersionName = BuildConfig.VERSION_NAME,
                                folders = currentFolders,
                            ).toByteArray(),
                        )
                    } ?: error("no stream")
                }.isSuccess
            }
            message = if (ok) {
                context.resources.getQuantityString(
                    R.plurals.expander_saved_count, current.size, current.size,
                )
            } else {
                context.getString(R.string.expander_export_error)
            }
        }
    }

    // An Espanso file is somebody else's, in a format that cannot say
    // everything this app's own can, so it is described and confirmed before
    // anything is written. The app's own format applies straight away, exactly
    // as it always has.
    var pendingImport by remember { mutableStateOf<SnippetPayload.Parsed?>(null) }
    var pendingExport by remember { mutableStateOf<List<ContentText>>(emptyList()) }
    var importSource by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf(false) }
    var urlPrompt by remember { mutableStateOf(false) }
    var packagePrompt by remember { mutableStateOf(false) }
    var manifest by remember { mutableStateOf(EspansoManifest("", "", "")) }
    var busy by remember { mutableStateOf(false) }

    /** Writes [parsed] into the store, under a folder when it names one. */
    fun applyImport(parsed: SnippetPayload.Parsed, folderName: String?) {
        val s = store ?: return
        scope.launch {
            withContext(Dispatchers.IO) {
                // Whole snippets, not a handful of named fields: rebuilding them
                // would quietly drop whatever the format gained last. An Espanso
                // file has no folders of its own, so it lands in one named after
                // the package, which is what gives it a single off switch.
                val target = folderName?.trim()?.takeIf { it.isNotEmpty() }?.let { s.addFolder(it).id } ?: 0L
                s.addAll(parsed.snippets, parsed.folders, fallbackFolderId = target)
                // The adds are in-memory only; save() writes the file.
                s.save()
            }
            snippets = s.items()
            folders = s.folders()
            message = buildString {
                append(
                    context.resources.getQuantityString(
                        R.plurals.expander_imported_count,
                        parsed.snippets.size,
                        parsed.snippets.size,
                    ),
                )
                if (parsed.notes.isNotEmpty()) {
                    append("\n\n")
                    append(context.getString(R.string.expander_import_repairs_title))
                    // The reader hands back a resource and its arguments, so the
                    // note is worded here.
                    for (line in parsed.notes) append("\n• ${line.resolve(context)}")
                }
            }
        }
    }

    /** Reads whatever was picked, then either applies it or asks first. */
    fun offerImport(uri: Uri) {
        if (store == null) return
        scope.launch {
            val name = WMFileTypes.displayName(context, uri)
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SnippetPayload.read(it.readBytes(), name)
                    }
                }.getOrNull()
            }
            when {
                parsed == null -> message = context.getString(R.string.expander_import_error)
                parsed.isEspanso -> pendingImport = parsed
                else -> applyImport(parsed, folderName = null)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::offerImport) }

    val espansoExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EspansoWriter.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                // Encoding is inside the runCatching, not in front of it: a
                // throw out here would escape the coroutine and take the
                // process with it rather than reaching the error dialog.
                runCancellable {
                    val export = EspansoWriter.encodeMatchFile(current, currentFolders)
                    context.contentResolver.requireOutputStream(uri).use {
                        it.write(export.text.toByteArray())
                    }
                    export.notes
                }.getOrNull()
            }
            if (written == null) {
                message = context.getString(R.string.expander_export_error)
            } else {
                pendingExport = written
                if (written.isEmpty()) {
                    message = context.resources.getQuantityString(
                        R.plurals.expander_saved_count, current.size, current.size,
                    )
                }
            }
        }
    }

    val packageExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(EspansoWriter.PACKAGE_MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = snippets
        val currentFolders = folders
        val currentManifest = manifest
        scope.launch {
            val written = withContext(Dispatchers.IO) {
                // Encoding inside the runCatching, for the reason spelled out
                // on the match-file launcher above.
                runCancellable {
                    val (bytes, notes) = EspansoWriter.encodePackage(current, currentFolders, currentManifest)
                    context.contentResolver.requireOutputStream(uri).use { it.write(bytes) }
                    notes
                }.getOrNull()
            }
            if (written == null) {
                message = context.getString(R.string.expander_export_error)
            } else {
                pendingExport = written
                if (written.isEmpty()) {
                    message = context.resources.getQuantityString(
                        R.plurals.expander_saved_count, current.size, current.size,
                    )
                }
            }
        }
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

    Text(
        stringResource(R.string.expander_intro_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    AddonStoreGroup(AddonType.Snippets, onNavigate)
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.expander_variables_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            // Live examples: expand the actual templates so the preview always
            // matches what an insertion would produce right now. The variables
            // the IME alone can fill in get a stand-in example instead.
            for (variable in SnippetVariable.entries) {
                VariableRow(
                    variable.token,
                    stringResource(variable.descriptionRes),
                    sampleFor(variable),
                )
            }
            VariableRow(
                "{date:…}", stringResource(R.string.expander_var_date_pattern_info),
                SnippetStore.expand("{date:EEE d MMM}"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.expander_variables_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.expander_pattern_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.expander_pattern_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(12.dp))
    SettingsGroup(stringResource(R.string.expander_behaviour_title)) {
        item {
            ChoiceSetting(
                title = R.string.expander_multi_expand_title,
                subtitle = stringResource(R.string.expander_multi_expand_subtitle),
                info = stringResource(R.string.expander_multi_expand_info),
                options = listOf(
                    MultiExpandMode.CHIPS_ONLY to
                        stringResource(R.string.expander_multi_expand_chips_label),
                    MultiExpandMode.INSERT_FIRST to
                        stringResource(R.string.expander_multi_expand_insert_label),
                ),
                selected = settings.suggestionStrip.snippetMultiExpand,
                default = SettingsDefaults.suggestionStrip.snippetMultiExpand,
            ) { scope.launch { repository.setSnippetMultiExpand(it) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = { onNavigate("expander/edit/0") }) {
            Text(stringResource(R.string.expander_add_action))
        }
        OutlinedButton(
            onClick = { importSource = true },
        ) { Text(stringResource(CommonR.string.common_import)) }
        OutlinedButton(
            onClick = { exportTarget = true },
            enabled = snippets.isNotEmpty(),
        ) { Text(stringResource(CommonR.string.common_export)) }
    }
    Spacer(Modifier.height(12.dp))
    // Both this list and the snippets panel draw in stored order, and the panel
    // has no search, so a snippet used daily sank under a year of one-off ones.
    // The row disables itself below two snippets, where order means nothing.
    if (snippets.isNotEmpty()) {
        SettingsGroup {
            item {
                ReorderSetting(
                    title = stringResource(R.string.expander_reorder_title),
                    dialogTitle = stringResource(R.string.expander_reorder_title),
                    items = snippets,
                    label = { it.label },
                    onReordered = { ordered -> mutate { s -> s.reorder(ordered.map { it.id }) } },
                )
            }
        }
    }
    SettingsGroup(stringResource(R.string.expander_folders_title)) {
        item { CaptionText(stringResource(R.string.expander_folders_info)) }
        if (folders.size > 1) {
            item {
                ReorderSetting(
                    title = stringResource(R.string.expander_folder_order_title),
                    dialogTitle = stringResource(R.string.expander_folder_order_title),
                    items = folders,
                    label = { it.name },
                    onReordered = { ordered ->
                        mutate { s -> s.reorderFolders(ordered.map { it.id }) }
                    },
                )
            }
        }
        for (folder in folders) {
            item {
                val count = snippets.count { it.folderId == folder.id }
                WmRow(
                    title = folder.name,
                    icon = Icons.Outlined.Folder,
                    subtitle = buildString {
                        append(
                            pluralStringResource(R.plurals.expander_folder_count, count, count),
                        )
                        if (!folder.enabled) {
                            append(" • ")
                            append(stringResource(R.string.expander_folder_off_label))
                        }
                    },
                    // The row itself renames; the switch is the one action worth
                    // its own target, and delete asks before it does anything.
                    onClick = { namingFolder = folder },
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val switchDesc = stringResource(
                                R.string.expander_folder_switch_desc, folder.name,
                            )
                            Switch(
                                checked = folder.enabled,
                                onCheckedChange = { on ->
                                    mutate { it.setFolderEnabled(folder.id, on) }
                                },
                                modifier = Modifier.semantics { contentDescription = switchDesc },
                            )
                            IconButton(onClick = { deletingFolder = folder }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(CommonR.string.common_delete),
                                )
                            }
                        }
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(R.string.expander_folder_new_action),
                icon = Icons.Outlined.Add,
                onClick = { namingFolder = SnippetFolder(id = 0, name = "") },
            )
        }
    }
    // One section per folder, then whatever is in none of them. A folder with
    // nothing in it is not drawn here — it is already listed above, and an empty
    // headed section reads as a section that failed to load.
    for (folder in folders) {
        val inFolder = snippets.filter { it.folderId == folder.id }
        if (inFolder.isEmpty()) continue
        SettingsGroup(folder.name) {
            for (snippet in inFolder) {
                item {
                    SnippetRow(
                        snippet,
                        onEdit = { onNavigate("expander/edit/${snippet.id}") },
                        onDelete = { mutate { it.remove(snippet.id) } },
                    )
                }
            }
        }
    }
    val loose = snippets.filter { it.folderId == 0L }
    SettingsGroup(
        // Only worth a heading once there is something to tell it apart from.
        title = if (folders.isEmpty()) null else stringResource(R.string.expander_no_folder_title),
    ) {
        for (snippet in loose) {
            item {
                SnippetRow(
                    snippet,
                    onEdit = { onNavigate("expander/edit/${snippet.id}") },
                    onDelete = { mutate { it.remove(snippet.id) } },
                )
            }
        }
    }

    namingFolder?.let { folder ->
        SnippetFolderNameDialog(
            initial = folder,
            onDismiss = { namingFolder = null },
            onSave = { name ->
                mutate { s ->
                    if (folder.id == 0L) s.addFolder(name) else s.renameFolder(folder.id, name)
                }
                namingFolder = null
            },
        )
    }

    deletingFolder?.let { folder ->
        SnippetFolderDeleteDialog(
            folder = folder,
            count = snippets.count { it.folderId == folder.id },
            onDismiss = { deletingFolder = null },
            onDelete = { withSnippets ->
                mutate { it.removeFolder(folder.id, withSnippets) }
                deletingFolder = null
            },
        )
    }

    if (importSource) {
        SnippetSourceDialog(
            title = R.string.expander_import_source_title,
            options = listOf(
                R.string.expander_source_native to R.string.expander_source_native_body,
                R.string.expander_source_espanso to R.string.expander_source_espanso_body,
                R.string.expander_source_url to R.string.expander_source_url_body,
            ),
            onDismiss = { importSource = false },
            onPick = { index ->
                importSource = false
                when (index) {
                    0 -> importLauncher.launch(SnippetFile.IMPORT_MIME_TYPES)
                    1 -> importLauncher.launch(SnippetPayload.IMPORT_MIME_TYPES)
                    else -> urlPrompt = true
                }
            },
        )
    }

    if (exportTarget) {
        SnippetSourceDialog(
            title = R.string.expander_export_target_title,
            options = listOf(
                R.string.expander_target_native to R.string.expander_target_native_body,
                R.string.expander_target_espanso to R.string.expander_target_espanso_body,
                R.string.expander_target_package to R.string.expander_target_package_body,
            ),
            onDismiss = { exportTarget = false },
            onPick = { index ->
                exportTarget = false
                when (index) {
                    0 -> exportLauncher.launch(SnippetFile.fileName())
                    1 -> espansoExportLauncher.launch(EspansoWriter.FILE_NAME)
                    else -> packagePrompt = true
                }
            },
        )
    }

    if (urlPrompt) {
        SnippetUrlDialog(
            busy = busy,
            onDismiss = { if (!busy) urlPrompt = false },
            onFetch = { pasted ->
                busy = true
                scope.launch {
                    val parsed = withContext(Dispatchers.IO) { fetchEspanso(pasted) }
                    busy = false
                    urlPrompt = false
                    when {
                        parsed == null -> message = context.getString(R.string.expander_url_error)
                        parsed.isEspanso -> pendingImport = parsed
                        else -> applyImport(parsed, folderName = null)
                    }
                }
            },
        )
    }

    pendingImport?.let { parsed ->
        SnippetEspansoImportDialog(
            parsed = parsed,
            onDismiss = { pendingImport = null },
            onImport = { folderName ->
                pendingImport = null
                applyImport(parsed, folderName)
            },
        )
    }

    if (packagePrompt) {
        SnippetPackageDialog(
            onDismiss = { packagePrompt = false },
            onExport = { built ->
                packagePrompt = false
                manifest = built
                packageExportLauncher.launch("${EspansoManifest.sanitizeName(built.name)}.zip")
            },
        )
    }

    if (pendingExport.isNotEmpty()) {
        val lines = pendingExport
        AlertDialog(
            onDismissRequest = { pendingExport = emptyList() },
            title = { Text(stringResource(R.string.expander_export_notes_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = DialogScrollMaxHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (line in lines) Text("• ${line.resolve(context)}")
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingExport = emptyList() }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }
}
/**
 * Fetches an Espanso file from a pasted address and reads it.
 *
 * A Hub package page names a package without saying where its files are, so
 * that shape takes two requests: list the package's versions, take the newest,
 * then fetch that version's `package.yml`. Everything else is one.
 *
 * Blocking, so call it on IO. Every failure is null; the dialog says the same
 * thing whichever step it was, because none of the differences is something the
 * person pasting a link can act on.
 */
private fun fetchEspanso(pasted: String): SnippetPayload.Parsed? {
    val target = EspansoHub.resolve(pasted) ?: return null
    val url = when (target) {
        is EspansoHub.Target.Direct -> target.url
        is EspansoHub.Target.HubPackage -> {
            val listing = runCancellable { ToolHttp.get(target.contentsUrl) }.getOrNull() ?: return null
            val version = EspansoHub.newestVersion(listing) ?: return null
            target.packageUrl(version)
        }
    }
    val temp = java.io.File.createTempFile("espanso", null)
    return try {
        runCancellable {
            ToolHttp.download(url, temp, maxBytes = EspansoFile.MAX_BYTES.toLong())
        }.getOrNull() ?: return null
        SnippetPayload.read(temp, url.substringAfterLast('/'))
    } finally {
        temp.delete()
    }
}
/** A short list of ways to do the thing, one row each. */
@Composable
private fun SnippetSourceDialog(
    @StringRes title: Int,
    options: List<Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column {
                options.forEachIndexed { index, (label, body) ->
                    WmRow(
                        title = stringResource(label),
                        supporting = { Text(stringResource(body)) },
                        onClick = { onPick(index) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** Asks for an address to fetch a snippet file from. */
@Composable
private fun SnippetUrlDialog(busy: Boolean, onDismiss: () -> Unit, onFetch: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_url_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.expander_url_label)) },
                    singleLine = true,
                    enabled = !busy,
                )
                DialogNote(stringResource(R.string.expander_url_body))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && url.isNotBlank(),
                onClick = { onFetch(url) },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}
/**
 * What an Espanso file holds and what it loses, before any of it is written.
 *
 * The notes are the point of the dialog. An Espanso file can say things this app
 * has no equivalent for, and finding that out after the import is worse than
 * being told and deciding.
 */
@Composable
private fun SnippetEspansoImportDialog(
    parsed: SnippetPayload.Parsed,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    val context = LocalContext.current
    var folderName by remember { mutableStateOf(parsed.suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                pluralStringResource(
                    R.plurals.expander_espanso_import_title,
                    parsed.snippets.size,
                    parsed.snippets.size,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DialogScrollMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.expander_espanso_folder_label)) },
                    singleLine = true,
                )
                DialogNote(stringResource(R.string.expander_espanso_folder_body))
                if (parsed.notes.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.expander_espanso_changes_title),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    for (line in parsed.notes) DialogNote("• ${line.resolve(context)}")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed.snippets.isNotEmpty(),
                onClick = { onImport(folderName) },
            ) { Text(stringResource(CommonR.string.common_import)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** The handful of things an Espanso package's manifest has to declare. */
@Composable
private fun SnippetPackageDialog(onDismiss: () -> Unit, onExport: (EspansoManifest) -> Unit) {
    var name by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val cleaned = remember(name) { EspansoManifest.sanitizeName(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_package_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DialogScrollMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.expander_package_name_label)) },
                    singleLine = true,
                )
                // The specification allows lowercase letters, digits and hyphens
                // only, so show what the name will actually become.
                DialogNote(stringResource(R.string.expander_package_name_body, cleaned))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text(stringResource(R.string.expander_package_author_label)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.expander_package_description_label)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onExport(
                        EspansoManifest(
                            name = cleaned,
                            title = name.trim(),
                            description = description.trim(),
                            author = author.trim(),
                        ),
                    )
                },
            ) { Text(stringResource(CommonR.string.common_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/** One snippet in the Text Expander list: what it inserts, and what fires it. */
@Composable
private fun SnippetRow(snippet: Snippet, onEdit: () -> Unit, onDelete: () -> Unit) {
    // Snippet ids are numbers; an install records the batch it added as one
    // comma-joined list of them.
    HighlightableItem(snippet.id.toString()) {
        WmRow(
            title = snippet.label,
            supporting = {
                Column {
                    Text(snippet.text, maxLines = 2)
                    val preview = SnippetStore.expandWithCursor(
                        snippet.text,
                        context = SNIPPET_PREVIEW_CONTEXT,
                    ).text
                    if (snippet.text != preview) {
                        Text(
                            stringResource(R.string.expander_inserts_as_label, preview),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // What the snippet has beyond one text: how many things it
                    // can insert, and how many other snippets it reaches. Both
                    // are invisible in the preview above, which shows only the
                    // default.
                    val expansions = snippet.expansions().size
                    val linked = snippet.children.size
                    if (expansions > 1 || linked > 0) {
                        val parts = buildList {
                            if (expansions > 1) {
                                add(
                                    pluralStringResource(
                                        R.plurals.expander_expansion_count,
                                        expansions,
                                        expansions,
                                    ),
                                )
                            }
                            if (linked > 0) {
                                add(
                                    pluralStringResource(
                                        R.plurals.expander_linked_count,
                                        linked,
                                        linked,
                                    ),
                                )
                            }
                        }
                        Text(
                            parts.joinToString(" • "),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Aliases sit on the same line as the trigger: they are the
                    // same rule spelled more than once, not a second thing the
                    // row has to explain.
                    val trigger = snippet.spellings().takeIf { it.isNotEmpty() }?.joinToString(", ")
                    val pattern = snippet.triggerPattern
                    // Which of the two lines a trigger gets is only about
                    // wording: one asks first, the other rewrites what you
                    // typed, and the row has to say which.
                    if (trigger != null) {
                        Text(
                            stringResource(
                                if (snippet.confirm) {
                                    R.string.expander_trigger_asks_label
                                } else {
                                    R.string.expander_trigger_label
                                },
                                trigger,
                            ),
                            maxLines = 1,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (pattern != null) {
                        Text(
                            stringResource(
                                if (snippet.confirm) {
                                    R.string.expander_pattern_asks_label
                                } else {
                                    R.string.expander_pattern_label
                                },
                                pattern,
                            ),
                            maxLines = 1,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            trailing = {
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(CommonR.string.common_edit),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(CommonR.string.common_delete),
                        )
                    }
                }
            },
        )
    }
}
/** Names a new folder, or renames one. A blank [initial] name means new. */
@Composable
private fun SnippetFolderNameDialog(
    initial: SnippetFolder,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id == 0L) {
                        R.string.expander_folder_new_title
                    } else {
                        R.string.expander_folder_rename_title
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.expander_folder_name_label)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/**
 * Asks what should happen to a folder's snippets before the folder goes.
 *
 * The switch, not a second button: Cancel has to stay reachable, and three
 * buttons in an alert is how the destructive one gets tapped by accident. It
 * starts off, so the plain answer — the folder goes, the writing stays — is the
 * one a hurried tap gives. An empty folder is not asked about at all.
 */
@Composable
private fun SnippetFolderDeleteDialog(
    folder: SnippetFolder,
    count: Int,
    onDismiss: () -> Unit,
    onDelete: (withSnippets: Boolean) -> Unit,
) {
    var withSnippets by remember(folder.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expander_folder_delete_title, folder.name)) },
        text = if (count == 0) {
            null
        } else {
            {
                Column {
                    Text(pluralStringResource(R.plurals.expander_folder_delete_body, count, count))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.expander_folder_delete_all_action),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = withSnippets, onCheckedChange = { withSnippets = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(withSnippets) }) {
                Text(stringResource(CommonR.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/**
 * Stand-in values so the settings preview shows a realistic expansion.
 *
 * The getter is composable because one of the stand-ins is text the user
 * reads. Every read of this property has to sit in a composable body.
 */
private val SNIPPET_PREVIEW_CONTEXT: SnippetStore.Companion.Context
    @Composable get() = SnippetStore.Companion.Context(
        clipboard = "…",
        appName = stringResource(R.string.rows_snippet_preview_app_name),
        packageName = "com.example.app",
        selection = "…",
    )
/**
 * Example value for the reference card. Most variables can be expanded for
 * real; the ones that depend on the keyboard's live context (clipboard, app,
 * selection) get a description of what they'd produce instead.
 */
@Composable
private fun sampleFor(variable: SnippetVariable): String = when (variable) {
    SnippetVariable.CLIP -> stringResource(R.string.rows_snippet_sample_clip)
    SnippetVariable.SELECTION -> stringResource(R.string.rows_snippet_sample_selection)
    SnippetVariable.APP -> "Messages"
    SnippetVariable.PACKAGE -> "com.google.android.apps.messaging"
    SnippetVariable.CURSOR -> stringResource(R.string.rows_snippet_sample_cursor)
    else -> SnippetStore.expand(variable.token)
}
/** One row in the template-variable reference card. */
@Composable
private fun VariableRow(variable: String, meaning: String, example: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            variable,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(96.dp),
        )
        Column {
            Text(meaning, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.rows_snippet_variable_example, example),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
/** How a snippet expands as the user types: on one word, or on a pattern. */
private enum class SnippetTriggerMode { WORD, PATTERN }
/**
 * Adds or edits one snippet.
 *
 * A screen and not a dialog. The dialog said seven controls did not fit a
 * phone one, and a snippet now has a list of expansions, a list of triggers, a
 * list of links, tags and a behaviour choice on top of those. A screen also
 * has somewhere to put the soft keyboard, which a dialog holding a three-line
 * text field does not.
 *
 * [snippetId] is 0 for a snippet that does not exist yet.
 */
@Composable
internal fun SnippetEditor(
    settings: KeyboardSettings,
    snippetId: Long,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val file = remember { java.io.File(context.filesDir, "snippets/snippets.json") }
    // Its own store, loaded off the main thread exactly as the list screen's
    // is: the two screens are separate destinations and the file is the one
    // thing they share.
    var store by remember { mutableStateOf<SnippetStore?>(null) }
    var all by remember { mutableStateOf<List<Snippet>>(emptyList()) }
    var folders by remember { mutableStateOf<List<SnippetFolder>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(snippetId) {
        val s = withContext(Dispatchers.IO) { SnippetStore(file) }
        all = s.items()
        folders = s.folders()
        store = s
        loaded = true
    }
    if (!loaded) return
    val initial = all.firstOrNull { it.id == snippetId }
    SnippetEditorForm(
        settings = settings,
        initial = initial,
        all = all,
        folders = folders,
        onSave = { draft ->
            val s = store ?: return@SnippetEditorForm
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (initial == null) s.add(draft) else s.update(draft)
                    s.save()
                }
                onDone()
            }
        },
        onCancel = onDone,
    )
}
/**
 * The editor's fields, once the store behind them has loaded.
 *
 * Split from [SnippetEditor] so every `remember` here is keyed on a snippet
 * that already exists: a draft seeded from a value that arrives one frame late
 * starts empty and stays empty.
 */
@Composable
@Suppress("LongMethod")
private fun SnippetEditorForm(
    settings: KeyboardSettings,
    initial: Snippet?,
    all: List<Snippet>,
    folders: List<SnippetFolder>,
    onSave: (Snippet) -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var folderId by remember { mutableLongStateOf(initial?.folderId ?: 0L) }
    var expansions by remember {
        mutableStateOf(initial?.expansions() ?: listOf(""))
    }
    var openExpansion by remember { mutableStateOf<Int?>(if (initial == null) 0 else null) }
    var triggers by remember { mutableStateOf(initial?.spellings().orEmpty()) }
    var triggerDraft by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf(initial?.tags.orEmpty()) }
    var tagDraft by remember { mutableStateOf("") }
    var children by remember { mutableStateOf(initial?.children.orEmpty()) }
    var pickingLinks by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(TextFieldValue(initial?.triggerPattern.orEmpty())) }
    var words by remember {
        mutableIntStateOf(
            initial?.triggerWords?.takeIf { it in 1..SnippetMatcher.MAX_WORDS }
                ?: SnippetMatcher.DEFAULT_WORDS,
        )
    }
    var mode by remember {
        mutableStateOf(
            if (initial?.triggerPattern.isNullOrBlank()) {
                SnippetTriggerMode.WORD
            } else {
                SnippetTriggerMode.PATTERN
            },
        )
    }
    var confirm by remember { mutableStateOf(initial?.confirm == true) }
    var propagateCase by remember { mutableStateOf(initial?.propagateCase == true) }
    var uppercaseStyle by remember {
        mutableStateOf(initial?.uppercaseStyle ?: UppercaseStyle.CAPITALIZE)
    }
    var multiExpand by remember { mutableStateOf(initial?.multiExpand ?: MultiExpand.DEFAULT) }

    val fault = remember(pattern.text) { SnippetMatcher.validate(pattern.text) }
    val word = mode == SnippetTriggerMode.WORD
    val patternOk = word || fault == null
    // The half-typed chip counts: somebody who types a trigger and presses Save
    // meant that trigger, and losing it to a missing Enter is a bug report.
    val allTriggers = remember(triggers, triggerDraft) {
        triggers + listOfNotNull(triggerDraft.trim().takeIf(String::isNotEmpty))
    }
    val allTags = remember(tags, tagDraft) {
        tags + listOfNotNull(tagDraft.trim().takeIf(String::isNotEmpty))
    }
    val kept = expansions.filter { it.isNotBlank() }
    val valid = label.isNotBlank() && kept.isNotEmpty() && patternOk

    Column(modifier = Modifier.imePadding()) {
        SettingsGroup {
            item {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.rows_snippet_label_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }
            // Only once there is a folder to pick. A picker whose one choice is
            // "None" teaches nothing and costs a row.
            if (folders.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.rows_snippet_folder_label),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        ChoiceControl(
                            options = listOf(
                                0L to stringResource(R.string.rows_snippet_folder_none_label),
                            ) + folders.map { it.id to it.name },
                            selected = folderId,
                            onChange = { folderId = it },
                        )
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.rows_snippet_expansions_label)) {
            item {
                ExpansionListEditor(
                    expansions = expansions,
                    openIndex = openExpansion,
                    onOpenChange = { openExpansion = it },
                    onChange = { expansions = it },
                )
            }
        }

        SettingsGroup(stringResource(R.string.rows_snippet_pattern_label)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ChoiceControl(
                        options = listOf(
                            SnippetTriggerMode.WORD to
                                stringResource(R.string.rows_snippet_mode_word_label),
                            SnippetTriggerMode.PATTERN to
                                stringResource(R.string.rows_snippet_mode_pattern_label),
                        ),
                        selected = mode,
                        onChange = { mode = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (word) {
                        ChipInputField(
                            chips = triggers,
                            draft = triggerDraft,
                            onChipsChange = { triggers = it },
                            onDraftChange = { triggerDraft = it },
                            label = stringResource(R.string.rows_snippet_triggers_label),
                            // A trigger may hold spaces — "gr db" is one
                            // trigger, not two — so only a comma, a tab or a
                            // newline means "that was the whole of one".
                            separators = setOf(',', '\t', '\n'),
                            monospace = true,
                        )
                        DialogNote(stringResource(R.string.rows_snippet_triggers_body))
                        if (allTriggers.size > SnippetStore.MAX_ALIASES + 1) {
                            // The store truncates silently; a screen that let
                            // that happen without saying so would be lying.
                            DialogNote(
                                pluralStringResource(
                                    R.plurals.rows_snippet_triggers_capped_error,
                                    SnippetStore.MAX_ALIASES + 1,
                                    SnippetStore.MAX_ALIASES + 1,
                                ),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.rows_snippet_propagate_case_label),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = propagateCase, onCheckedChange = { propagateCase = it })
                        }
                        DialogNote(stringResource(R.string.rows_snippet_propagate_case_body))
                        // The style only means anything for a trigger typed with
                        // one leading capital. An all-caps trigger always shouts.
                        if (propagateCase) {
                            Spacer(Modifier.height(8.dp))
                            ChoiceControl(
                                options = listOf(
                                    UppercaseStyle.CAPITALIZE to
                                        stringResource(R.string.rows_snippet_case_first_label),
                                    UppercaseStyle.CAPITALIZE_WORDS to
                                        stringResource(R.string.rows_snippet_case_words_label),
                                    UppercaseStyle.UPPERCASE to
                                        stringResource(R.string.rows_snippet_case_all_label),
                                ),
                                selected = uppercaseStyle,
                                onChange = { uppercaseStyle = it },
                            )
                        }
                    } else {
                        SnippetPatternFields(
                            pattern = pattern,
                            onPatternChange = { pattern = it },
                            words = words,
                            onWordsChange = { words = it },
                            text = kept.firstOrNull().orEmpty(),
                            fault = fault,
                        )
                    }
                }
            }
        }

        SettingsGroup(stringResource(R.string.expander_behaviour_title)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.rows_snippet_confirm_label),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = confirm, onCheckedChange = { confirm = it })
                    }
                    DialogNote(stringResource(R.string.rows_snippet_confirm_body))
                }
            }
            // Nothing to choose between until there is more than one thing to
            // insert, so the row appears when the snippet earns it.
            if (kept.size > 1 || children.isNotEmpty()) {
                item {
                    ChoiceSetting(
                        title = R.string.rows_snippet_multi_expand_label,
                        options = listOf(
                            MultiExpand.DEFAULT to stringResource(
                                R.string.rows_snippet_multi_expand_default_label,
                                stringResource(
                                    multiExpandLabel(settings.suggestionStrip.snippetMultiExpand),
                                ),
                            ),
                            MultiExpand.CHIPS_ONLY to
                                stringResource(R.string.expander_multi_expand_chips_label),
                            MultiExpand.INSERT_FIRST to
                                stringResource(R.string.expander_multi_expand_insert_label),
                        ),
                        selected = multiExpand,
                        // Resetting a snippet's own answer means going back to
                        // following the app's.
                        default = MultiExpand.DEFAULT,
                    ) { multiExpand = it }
                }
            }
        }

        SettingsGroup(stringResource(R.string.rows_snippet_linked_label)) {
            item {
                LinkedSnippetsSection(
                    children = children,
                    all = all,
                    selfId = initial?.id ?: 0L,
                    onChange = { children = it },
                    onAdd = { pickingLinks = true },
                )
            }
        }

        SettingsGroup(stringResource(R.string.rows_snippet_tags_label)) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    ChipInputField(
                        chips = tags,
                        draft = tagDraft,
                        onChipsChange = { tags = it },
                        onDraftChange = { tagDraft = it },
                        label = stringResource(R.string.rows_snippet_tags_label),
                        // A tag may hold a space, so only a comma ends one.
                        separators = setOf(',', '\n'),
                    )
                    DialogNote(stringResource(R.string.rows_snippet_tags_body))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        Snippet(
                            id = initial?.id ?: 0,
                            label = label.trim(),
                            text = kept.first(),
                            alternates = kept.drop(1),
                            createdAt = initial?.createdAt ?: 0,
                            trigger = if (word) allTriggers.firstOrNull() else null,
                            aliases = if (word) allTriggers.drop(1) else emptyList(),
                            propagateCase = word && propagateCase,
                            uppercaseStyle = uppercaseStyle,
                            triggerPattern = if (word) null else pattern.text.trim().ifBlank { null },
                            triggerWords = if (word) 0 else words,
                            confirm = confirm,
                            folderId = folderId,
                            // A link to something deleted while this screen was
                            // open is not a link.
                            children = children.filter { id -> all.any { it.id == id } },
                            tags = allTags,
                            multiExpand = multiExpand,
                        ),
                    )
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
            OutlinedButton(onClick = onCancel) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (pickingLinks) {
        SnippetPickerDialog(
            candidates = all.filter { it.id != (initial?.id ?: 0L) },
            selected = children.toSet(),
            onConfirm = {
                children = it
                pickingLinks = false
            },
            onDismiss = { pickingLinks = false },
        )
    }
}
/** The label for one of the two things the app can do with several expansions. */
@StringRes
private fun multiExpandLabel(mode: MultiExpandMode): Int = when (mode) {
    MultiExpandMode.CHIPS_ONLY -> R.string.expander_multi_expand_chips_label
    MultiExpandMode.INSERT_FIRST -> R.string.expander_multi_expand_insert_label
}
/** Any run of whitespace inside a chip, which is shown and saved as one space. */
private val CHIP_WHITESPACE = Regex("\\s+")
/**
 * A field that turns what is typed into a row of removable chips.
 *
 * A separate text field per entry is what the issue asked for, and it is what
 * fifty triggers makes unusable: fifty fields to scroll past, fifty places for
 * focus to land, and no way to see at a glance what is already there. A chip
 * says the same thing in a line.
 *
 * Both the committed [chips] and the half-typed [draft] belong to the caller,
 * so a Save that lands before Enter does can still see what was typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipInputField(
    chips: List<String>,
    draft: String,
    onChipsChange: (List<String>) -> Unit,
    onDraftChange: (String) -> Unit,
    label: String,
    separators: Set<Char> = setOf(',', '\n'),
    monospace: Boolean = false,
) {
    fun commit(value: String) {
        // Same rules the store applies, so what the screen shows and what is
        // saved are the same list: whitespace runs collapse to one space, and a
        // repeat of an entry already there is dropped.
        val clean = value.trim().replace(CHIP_WHITESPACE, " ")
        if (clean.isEmpty()) return
        if (chips.any { it.equals(clean, ignoreCase = true) }) return
        onChipsChange(chips + clean)
    }
    if (chips.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (chip in chips) {
                InputChip(
                    selected = false,
                    // Tapping a chip puts it back in the field: the way to fix
                    // a typo in one, without a second gesture to learn.
                    onClick = {
                        onChipsChange(chips - chip)
                        onDraftChange(chip)
                    },
                    label = {
                        Text(chip, fontFamily = if (monospace) FontFamily.Monospace else null)
                    },
                    trailingIcon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = stringResource(
                                R.string.rows_snippet_chip_remove_desc,
                                chip,
                            ),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { onChipsChange(chips - chip) },
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { typed ->
            val cut = typed.indexOfLast { it in separators }
            if (cut < 0) {
                onDraftChange(typed)
                return@OutlinedTextField
            }
            // Everything up to the last separator is finished; whatever comes
            // after it is still being typed. Handles a paste of several at once.
            for (part in typed.take(cut + 1).split(*separators.toCharArray())) commit(part)
            onDraftChange(typed.substring(cut + 1))
        },
        label = { Text(label) },
        singleLine = true,
        textStyle = if (monospace) {
            LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
        } else {
            LocalTextStyle.current
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        // Enter reaches a single-line field as an action on most keyboards and
        // as a newline on some, so both are handled.
        keyboardActions = KeyboardActions(
            onDone = {
                commit(draft)
                onDraftChange("")
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
/**
 * The list of texts one snippet can insert, first one first.
 *
 * Position is the whole of what "default" means, so the star follows the top
 * row and moving a row up is how the default changes. One row opens at a time:
 * a screen of open multi-line fields is a screen with no shape to it.
 */
@Composable
private fun ExpansionListEditor(
    expansions: List<String>,
    openIndex: Int?,
    onOpenChange: (Int?) -> Unit,
    onChange: (List<String>) -> Unit,
) {
    Column {
        expansions.forEachIndexed { index, value ->
            val open = openIndex == index
            WmRow(
                title = value.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: stringResource(R.string.rows_snippet_expansion_empty_label),
                supporting = if (index == 0) {
                    { Text(stringResource(R.string.rows_snippet_expansion_default_label)) }
                } else {
                    null
                },
                leading = {
                    Icon(
                        if (index == 0) Icons.Outlined.Star else Icons.Outlined.Notes,
                        contentDescription = null,
                    )
                },
                trailing = {
                    Row {
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                onChange(expansions.swapped(index, index - 1))
                                onOpenChange(if (open) index - 1 else openIndex)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowUp,
                                contentDescription = stringResource(R.string.rows_move_up_desc),
                            )
                        }
                        IconButton(
                            enabled = index < expansions.lastIndex,
                            onClick = {
                                onChange(expansions.swapped(index, index + 1))
                                onOpenChange(if (open) index + 1 else openIndex)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.rows_move_down_desc),
                            )
                        }
                        IconButton(
                            // A snippet with nothing to insert is not a snippet.
                            enabled = expansions.size > 1,
                            onClick = {
                                onChange(expansions.filterIndexed { i, _ -> i != index })
                                onOpenChange(null)
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(CommonR.string.common_delete),
                            )
                        }
                    }
                },
                onClick = { onOpenChange(if (open) null else index) },
            )
            if (open) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { text ->
                            onChange(expansions.toMutableList().also { it[index] = text })
                        },
                        label = { Text(stringResource(R.string.rows_snippet_text_label)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val preview = SnippetStore.expandWithCursor(
                        value,
                        context = SNIPPET_PREVIEW_CONTEXT,
                    ).text
                    if (value.isNotBlank() && value != preview) {
                        Text(
                            stringResource(R.string.expander_inserts_as_label, preview),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        TextButton(
            onClick = {
                onChange(expansions + "")
                onOpenChange(expansions.size)
            },
            modifier = Modifier.padding(start = 8.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.rows_snippet_expansion_add_action))
        }
    }
}
/** [this] with the entries at [a] and [b] exchanged. */
private fun <T> List<T>.swapped(a: Int, b: Int): List<T> =
    toMutableList().also {
        val held = it[a]
        it[a] = it[b]
        it[b] = held
    }
/**
 * The snippets this one points at, and the ones that point back.
 *
 * The second half is read-only and easy to miss the point of: a snippet may
 * have several parents on purpose — Tehran belongs under Iran, under Capitals
 * and under Cities — so the only way to see where one is reachable from is to
 * be told.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkedSnippetsSection(
    children: List<Long>,
    all: List<Snippet>,
    selfId: Long,
    onChange: (List<Long>) -> Unit,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        val linked = children.mapNotNull { id -> all.firstOrNull { it.id == id } }
        if (linked.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (child in linked) {
                    InputChip(
                        selected = false,
                        onClick = { onChange(children - child.id) },
                        label = { Text(child.label) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(
                                    R.string.rows_snippet_chip_remove_desc,
                                    child.label,
                                ),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.rows_snippet_linked_add_action))
        }
        DialogNote(stringResource(R.string.rows_snippet_linked_body))
        val parents = all.filter { selfId != 0L && selfId in it.children }
        if (parents.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            DialogNote(
                stringResource(
                    R.string.rows_snippet_linked_from_label,
                    parents.joinToString(", ") { it.label },
                ),
            )
        }
    }
}
/** Picks the snippets one snippet points at, searching by name, trigger or tag. */
@Composable
private fun SnippetPickerDialog(
    candidates: List<Snippet>,
    selected: Set<Long>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<Long>().also { it.addAll(selected) } }
    val shown = candidates.filter { snippet ->
        query.isBlank() ||
            snippet.label.contains(query, ignoreCase = true) ||
            snippet.tags.any { it.contains(query, ignoreCase = true) } ||
            snippet.spellings().any { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rows_snippet_linked_picker_title)) },
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
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.rows_snippet_linked_picker_empty))
                }
                LazyColumn(modifier = Modifier.heightIn(max = DialogScrollMaxHeight)) {
                    items(shown, key = { it.id }) { snippet ->
                        val on = snippet.id in picked
                        ListItem(
                            headlineContent = { Text(snippet.label) },
                            supportingContent = {
                                Text(
                                    snippet.spellings().joinToString(", ").ifEmpty { snippet.text },
                                    maxLines = 1,
                                )
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = {
                                        if (on) picked.remove(snippet.id) else picked.add(snippet.id)
                                    },
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (on) picked.remove(snippet.id) else picked.add(snippet.id)
                                },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked.toList()) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}
/**
 * Tallest a scrolling dialog body may grow before it starts scrolling.
 *
 * A number rather than "whatever the dialog allows". `verticalScroll` throws
 * outright when it is measured with an infinite maximum height, and a dialog is
 * one of the places that can happen: the window measures its content to wrap,
 * and a slot that wraps hands its child no ceiling. Clamping the height with
 * `heightIn(max = …)` *before* the scroll in the chain makes that impossible
 * rather than unlikely, and it is also better behaviour — an unbounded body
 * with a long list grows until it pushes the dialog's own buttons off screen.
 */
private val DialogScrollMaxHeight = 400.dp
/**
 * A small explanatory line under a field in a dialog.
 *
 * Not [CaptionText]: that one insets itself by 32dp to line up with the
 * content of a settings group, which inside a dialog reads as a mistake.
 */
@Composable
internal fun DialogNote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
/**
 * The pattern half of the snippet dialog: the rule, how far back it may reach,
 * and a place to try it out.
 *
 * The tester is the important part. A pattern that does not fire gives no clue
 * why from inside the keyboard, and the settings app is the one place where a
 * runaway one is safe to meet — it is a different process, and a stopped
 * pattern here costs a moment rather than the keyboard.
 */
@Composable
private fun SnippetPatternFields(
    pattern: TextFieldValue,
    onPatternChange: (TextFieldValue) -> Unit,
    words: Int,
    onWordsChange: (Int) -> Unit,
    text: String,
    fault: SnippetMatcher.PatternError?,
) {
    var sample by remember { mutableStateOf("") }
    OutlinedTextField(
        value = pattern,
        onValueChange = onPatternChange,
        label = { Text(stringResource(R.string.rows_snippet_pattern_label)) },
        singleLine = true,
        isError = fault != null && pattern.text.isNotBlank(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    )
    // Inserted at the caret, not appended: a chip that only ever adds to the
    // end is useless once there is anything in the field.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (piece in PATTERN_PIECES) {
            AssistChip(
                onClick = { onPatternChange(pattern.insert(piece)) },
                label = { Text(piece, fontFamily = FontFamily.Monospace) },
            )
        }
    }
    if (pattern.text.isNotBlank() && fault != null) {
        DialogNote(
            stringResource(R.string.rows_snippet_pattern_error),
            color = MaterialTheme.colorScheme.error,
        )
        // The words java.util.regex uses for what is wrong are more use than
        // anything this screen could say, and they are not worth translating.
        fault.description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.error,
            )
        }
    } else if (pattern.text.isNotBlank() && SnippetMatcher.headOf(pattern.text) == null) {
        DialogNote(stringResource(R.string.rows_snippet_pattern_slow_info))
    } else {
        DialogNote(stringResource(R.string.rows_snippet_pattern_body))
    }
    Spacer(Modifier.height(12.dp))
    Text(
        stringResource(R.string.rows_snippet_words_label),
        style = MaterialTheme.typography.bodyMedium,
    )
    ChoiceControl(
        options = (1..SnippetMatcher.MAX_WORDS).map { it to it.toString() },
        selected = words,
        onChange = onWordsChange,
    )
    DialogNote(stringResource(R.string.rows_snippet_words_body))
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = sample,
        onValueChange = { sample = it },
        label = { Text(stringResource(R.string.rows_snippet_test_label)) },
        singleLine = true,
    )
    val previewContext = SNIPPET_PREVIEW_CONTEXT
    // The expansion, and whether the pattern had to be stopped for taking too
    // long. The keyboard would go quiet about the second; this is the one place
    // it can be found out safely, since a stopped pattern here costs a moment
    // in the settings app rather than a frame of typing.
    val attempt = remember(pattern.text, text, words, sample, previewContext) {
        if (sample.isBlank() || fault != null) {
            null
        } else {
            val index = SnippetIndex.of(
                listOf(
                    Snippet(
                        id = 1,
                        label = "",
                        text = text,
                        triggerPattern = pattern.text.trim(),
                        triggerWords = words,
                    ),
                ),
            )
            val hit = index.matchPattern(sample, atFieldStart = true, context = previewContext)
            hit to index.stopped().isNotEmpty()
        }
    }
    val hit = attempt?.first
    when {
        attempt == null -> DialogNote(stringResource(R.string.rows_snippet_test_body))
        attempt.second -> DialogNote(
            stringResource(R.string.rows_snippet_pattern_stopped_error),
            color = MaterialTheme.colorScheme.error,
        )
        hit != null -> Text(
            stringResource(R.string.rows_snippet_test_result_label, hit.text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> DialogNote(stringResource(R.string.rows_snippet_test_no_match_label))
    }
}
/** Chips that write the pieces of a pattern nobody wants to type by hand. */
private val PATTERN_PIECES = listOf("(.+)", "$1", "^", "$")
/** [piece] written in at the caret, with the caret left after it. */
private fun TextFieldValue.insert(piece: String): TextFieldValue {
    val at = selection.end.coerceIn(0, text.length)
    return TextFieldValue(
        text = text.substring(0, at) + piece + text.substring(at),
        selection = TextRange(at + piece.length),
    )
}
