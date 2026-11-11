package com.wasimaster.wmkeyboard.ime.paperless

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.app.DocScanPages
import com.wasimaster.wmkeyboard.core.settings.PaperlessAfterScan
import com.wasimaster.wmkeyboard.core.settings.PaperlessSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Where a finished scan goes once a Paperless server is set up (#1198), for
 * both scanners: Google's (DocScanActivity) and the built-in one. The text
 * box as before, Paperless, or both, as **After a scan** says, with the scan
 * card in between when it says to ask.
 */
object PaperlessHandoff {

    enum class Route {
        /** No Paperless, or it is told to stay out: the pages go into the text box. */
        FIELD,

        /** The scan card asks. */
        ASK,

        /** Straight to the queue with the defaults, and into the text box too when [PaperlessAfterScan.BOTH]. */
        AUTO,
    }

    suspend fun settings(context: Context): PaperlessSettings =
        runCatching { SettingsRepository(context.applicationContext).settings.first().scanner.paperless }
            .getOrDefault(PaperlessSettings())

    fun route(s: PaperlessSettings): Route = when {
        !s.configured || s.afterScan == PaperlessAfterScan.FIELD -> Route.FIELD
        s.afterScan == PaperlessAfterScan.ASK -> Route.ASK
        else -> Route.AUTO
    }

    /**
     * Hands [pages] on: to the Paperless queue when [choice] is given, and to
     * the keyboard for the text box when [toField]. Says which in a toast.
     */
    suspend fun deliver(context: Context, pages: List<File>, choice: PaperlessQueue.Choice?, toField: Boolean) {
        if (choice != null) {
            val queued = withContext(Dispatchers.IO) { runCatching { PaperlessQueue.enqueue(context, pages, choice) }.getOrDefault(0) }
            val text = if (queued > 0) {
                context.resources.getQuantityString(R.plurals.ime_paperless_queued, queued, queued)
            } else {
                context.getString(R.string.ime_paperless_queue_failed)
            }
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
        if (toField) DocScanPages.offer(pages)
    }

    /** The automatic routes' choice: the defaults, and whether the text box gets the pages too. */
    fun auto(s: PaperlessSettings): Pair<PaperlessQueue.Choice, Boolean> =
        PaperlessQueue.Choice.defaults(s) to (s.afterScan == PaperlessAfterScan.BOTH)
}

/**
 * The scan card: a title, the tags, the correspondent and the document type
 * for this one scan, picked from the server's own lists (the defaults
 * ticked), and whether the pages go into the text box as well. [onResult]
 * gets the choice (null: not to Paperless) and whether the text box gets the
 * pages. Dismissing it is "text box only", which is what a scan did before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperlessScanCard(pages: Int, settings: PaperlessSettings, onResult: (PaperlessQueue.Choice?, Boolean) -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme) {
        var title by remember { mutableStateOf("") }
        var tags by remember { mutableStateOf(settings.tags) }
        var correspondent by remember { mutableStateOf(settings.correspondent) }
        var documentType by remember { mutableStateOf(settings.documentType) }
        var onePdf by remember { mutableStateOf(settings.onePdf) }
        var toField by remember { mutableStateOf(false) }
        val lists by produceState<Result<PaperlessClient.Lists>?>(null) {
            value = withContext(Dispatchers.IO) {
                runCatching { PaperlessClient(settings.url, settings.token).lists() }.onSuccess { PaperlessLists.remember(it) }
            }
        }
        // The last lists seen stand in while the server is out of reach.
        val shown = lists?.getOrNull() ?: PaperlessLists.last
        AlertDialog(
            onDismissRequest = { onResult(null, true) },
            title = { Text(stringResource(R.string.ime_paperless_card_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(pluralStringResource(R.plurals.ime_paperless_card_pages, pages, pages), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.ime_paperless_card_name)) },
                        supportingText = { Text(stringResource(R.string.ime_paperless_card_name_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        shown == null && lists == null -> Row(
                            Modifier.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(10.dp))
                            Text(stringResource(R.string.ime_paperless_card_loading), style = MaterialTheme.typography.bodySmall)
                        }
                        shown == null -> Text(
                            stringResource(R.string.ime_paperless_card_offline),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        else -> {
                            if (lists?.isFailure == true) {
                                Text(
                                    stringResource(R.string.ime_paperless_card_offline_cached),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            if (shown.tags.isNotEmpty()) {
                                Label(R.string.ime_paperless_card_tags)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    shown.tags.forEach { t ->
                                        FilterChip(
                                            selected = t.id in tags,
                                            onClick = { tags = if (t.id in tags) tags - t.id else tags + t.id },
                                            label = { Text(t.name) },
                                        )
                                    }
                                }
                            }
                            if (shown.correspondents.isNotEmpty()) {
                                Label(R.string.ime_paperless_card_correspondent)
                                ChoiceRow(shown.correspondents, correspondent) { correspondent = it }
                            }
                            if (shown.documentTypes.isNotEmpty()) {
                                Label(R.string.ime_paperless_card_type)
                                ChoiceRow(shown.documentTypes, documentType) { documentType = it }
                            }
                        }
                    }
                    if (pages > 1) {
                        CheckRow(R.string.ime_paperless_card_one_pdf, onePdf) { onePdf = it }
                    }
                    CheckRow(R.string.ime_paperless_card_also_field, toField) { toField = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onResult(PaperlessQueue.Choice(title, tags, correspondent, documentType, onePdf, settings.wifiOnly), toField)
                }) { Text(stringResource(R.string.ime_paperless_card_send)) }
            },
            dismissButton = {
                TextButton(onClick = { onResult(null, true) }) { Text(stringResource(R.string.ime_paperless_card_field_only)) }
            },
        )
    }
}

@Composable
private fun Label(res: Int) {
    Text(
        stringResource(res),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** None, then each entry, one of them picked. */
@Composable
private fun ChoiceRow(entries: List<PaperlessClient.Named>, picked: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = picked == 0, onClick = { onPick(0) }, label = { Text(stringResource(R.string.ime_paperless_card_none)) })
        entries.forEach { e ->
            FilterChip(selected = picked == e.id, onClick = { onPick(e.id) }, label = { Text(e.name) })
        }
    }
}

@Composable
private fun CheckRow(res: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(res), style = MaterialTheme.typography.bodyMedium)
    }
}

/** The server's lists as last fetched in this process, for the card while the server is out of reach. */
object PaperlessLists {
    @Volatile
    var last: PaperlessClient.Lists? = null
        private set

    fun remember(lists: PaperlessClient.Lists) {
        last = lists
    }
}
