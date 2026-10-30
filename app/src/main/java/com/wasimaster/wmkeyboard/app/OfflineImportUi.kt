package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.app.share.AppShareSheet
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.CheckCircle
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ContentCopy
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ErrorOutline
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryEntry
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file to fetch on another device: what it is, and where it is published. */
internal data class OfflineLink(val label: String, val url: String)

/**
 * How deep an [OfflineImportRow] sits on a screen that also downloads: a detail
 * where the download works, and at every depth on the offline build, where the
 * row is the only way in.
 */
internal val offlineImportLevel: SettingLevel
    get() = if (offlineBuild) SettingLevel.BASIC else SettingLevel.ADVANCED

/** The build with no internet permission, where a Download button can only fail. */
internal val offlineBuild: Boolean get() = !InternetPermission.granted

/**
 * The row every download screen carries for a phone that cannot download:
 * **Get the files** says where they are published (opened in the browser, or
 * shared to another device), and **Import** takes them from the file picker
 * and installs them through [OfflineImport], exactly as the download would
 * have. Any number of files at once, zips included, so one pick can bring a
 * language pack, a Whisper graph with its vocabulary, or a folder's worth of
 * OCR data.
 *
 * [onImported] runs after an import that installed anything, for a screen
 * that has to re-read what is on disk.
 */
@Composable
internal fun OfflineImportRow(
    subtitle: String,
    links: List<OfflineLink>,
    wordlistSize: DictionaryCatalog.DictionarySize = DictionaryCatalog.DictionarySize.LARGE,
    title: String = stringResource(R.string.offline_import_row_title),
    onImported: () -> Unit = {},
) {
    var showLinks by remember { mutableStateOf(false) }
    val runner = rememberOfflineImport(wordlistSize, onImported)
    WmRow(
        title = title,
        supporting = { Text(subtitle) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (links.isNotEmpty()) {
                    TextButton(onClick = { showLinks = true }) {
                        Text(stringResource(R.string.offline_import_get_action))
                    }
                }
                TextButton(onClick = runner.pick, enabled = !runner.running) {
                    Text(stringResource(R.string.offline_import_action))
                }
            }
        },
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    if (showLinks) OfflineLinksDialog(links) { showLinks = false }
}

/**
 * The picker, the running import and its report, for any surface that starts
 * one. [install] skips the picker, for files that are already in hand: one
 * opened from a file manager or a chat app.
 */
internal class OfflineImportRunner(
    val pick: () -> Unit,
    val install: (List<Uri>) -> Unit,
    val running: Boolean,
)

/** A language the import holds several word lists for, waiting on the user to pick one. */
private class WordlistChoice(
    val langId: String,
    val options: List<DictionaryEntry>,
    val answer: CompletableDeferred<DictionaryEntry?>,
)

@Composable
internal fun rememberOfflineImport(
    wordlistSize: DictionaryCatalog.DictionarySize = DictionaryCatalog.DictionarySize.LARGE,
    onImported: () -> Unit = {},
    onReportDismissed: () -> Unit = {},
): OfflineImportRunner {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<OfflineImport.Outcome>?>(null) }
    var choice by remember { mutableStateOf<WordlistChoice?>(null) }
    val install: (List<Uri>) -> Unit = install@{ uris ->
        if (uris.isEmpty()) return@install
        working = ""
        scope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                OfflineImport.run(
                    context.applicationContext, uris, wordlistSize,
                    choose = { langId, options ->
                        val answer = CompletableDeferred<DictionaryEntry?>()
                        withContext(Dispatchers.Main) { choice = WordlistChoice(langId, options, answer) }
                        answer.await()
                    },
                ) { name ->
                    scope.launch { working = name }
                }
            }
            working = null
            report = outcomes
            if (outcomes.any { it.ok }) onImported()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), install)
    val pending = choice
    if (pending != null) {
        WordlistChoiceDialog(pending) { picked ->
            choice = null
            pending.answer.complete(picked)
        }
    } else {
        working?.let { OfflineImportProgress(it) }
    }
    report?.let {
        OfflineImportReport(it) {
            report = null
            onReportDismissed()
        }
    }
    return OfflineImportRunner(
        pick = { launcher.launch(arrayOf("*/*")) },
        install = install,
        running = working != null,
    )
}

/**
 * Which of a language's word lists to install, when one import carries
 * several: a language pack holds all of them (English's US and UK AOSP lists
 * and its counted one). Opens on the list a download would start on.
 */
@Composable
private fun WordlistChoiceDialog(choice: WordlistChoice, onDone: (DictionaryEntry?) -> Unit) {
    val preferred = DictionaryCatalog.preferred(choice.langId)
    var selected by remember(choice) {
        mutableStateOf(choice.options.firstOrNull { it == preferred } ?: choice.options.first())
    }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    R.string.offline_import_wordlist_choose_title,
                    LanguageRegistry.byId(choice.langId).displayName,
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.offline_import_wordlist_choose_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (option in choice.options) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { selected = option },
                            )
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            entryLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(selected) }) { Text(stringResource(R.string.offline_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun OfflineImportProgress(fileName: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.offline_import_working_title)) },
        text = {
            Column {
                if (fileName.isNotEmpty()) {
                    Text(fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun OfflineImportReport(outcomes: List<OfflineImport.Outcome>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (outcomes.isNotEmpty() && outcomes.all { it.ok }) {
                        R.string.offline_import_done_title
                    } else {
                        R.string.offline_import_report_title
                    },
                ),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (outcomes.isEmpty()) Text(stringResource(R.string.offline_import_nothing))
                for (outcome in outcomes) {
                    Row(modifier = Modifier.padding(vertical = 6.dp)) {
                        Icon(
                            if (outcome.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = if (outcome.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(outcome.fileName, style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(outcome.messageRes, *outcome.args.toTypedArray()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_ok)) } },
    )
}

/**
 * Where to fetch [links] on another device: each one opens in the browser or
 * copies, and **Share** sends the whole list to wherever the user will do the
 * downloading. The browser does the fetching, so this works in a build that
 * cannot reach the network itself. [onImport], when given, adds **Import**
 * for the files once they are on the phone.
 */
@Composable
internal fun OfflineLinksDialog(links: List<OfflineLink>, onImport: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_import_links_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.offline_import_links_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                for (link in links) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(link.label, style = MaterialTheme.typography.bodyLarge)
                            // The whole address, selectable, so it can be read
                            // off or copied in part as well as by the button (#665).
                            SelectionContainer {
                                Text(
                                    link.url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { copyLink(context, link.url) }) {
                            Icon(Icons.Outlined.ContentCopy, stringResource(R.string.offline_import_copy_link_desc))
                        }
                        IconButton(onClick = { openLink(context, link.url) }) {
                            Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.offline_import_open_link_desc))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { shareLinks(context, links) }) {
                Text(stringResource(R.string.offline_import_share_links_action))
            }
        },
        dismissButton = {
            Row {
                if (onImport != null) {
                    TextButton(onClick = onImport) { Text(stringResource(R.string.offline_import_action)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
            }
        },
    )
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyLink(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(url, url))
}

private fun shareLinks(context: Context, links: List<OfflineLink>) {
    val text = links.joinToString("\n") { "${it.label}: ${it.url}" }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    AppShareSheet.start(context, send)
}
