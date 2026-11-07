package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.grammar.DivvunGrammar
import com.wasimaster.wmkeyboard.core.grammar.DivvunGrammar.Status
import com.wasimaster.wmkeyboard.core.grammar.DivvunGrammarCatalog
import com.wasimaster.wmkeyboard.core.grammar.DivvunGrammarEntry
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The grammar tool's GiellaLT group: one row per language with a versioned
 * grammar checker, to download or delete its bundle. Nothing here is fetched
 * until the user asks; the library that runs the bundles comes with the
 * first one. Hidden on the build without internet, which could fetch none.
 */
@Composable
internal fun GiellaGrammarGroup(settings: LiveSettings) {
    if (!InternetPermission.granted) return
    val module by FeatureModules.divvunGrammar.state.collectAsState()
    SettingsGroup(
        stringResource(R.string.tooldetail_grammar_giella_group),
        info = stringResource(R.string.tooldetail_grammar_giella_info),
        level = SettingLevel.ADVANCED,
    ) {
        for (entry in DivvunGrammarCatalog.entries) {
            item { GiellaGrammarRow(entry, settings) }
        }
        if (module != ModuleState.Installed) {
            item { StateBanner(stringResource(R.string.tooldetail_grammar_giella_module_info)) }
        }
    }
}

@Composable
private fun GiellaGrammarRow(entry: DivvunGrammarEntry, settings: LiveSettings) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val langId = entry.languageId
    val states by DivvunGrammar.states.collectAsState()
    LaunchedEffect(langId) { withContext(Dispatchers.IO) { DivvunGrammar.refresh(filesDir, langId) } }
    val status = states[langId] ?: Status.NotInstalled

    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    fun download() {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> DivvunGrammar.start(filesDir, langId)
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    val supporting = when (status) {
        is Status.Installed -> stringResource(
            R.string.tooldetail_grammar_giella_installed,
            status.version ?: entry.version,
            formatBytes(status.sizeBytes),
        )
        is Status.Downloading -> stringResource(CommonR.string.common_downloading)
        Status.Failed -> stringResource(OfflineBuild.download(R.string.tooldetail_grammar_giella_failed))
        Status.NotInstalled -> stringResource(R.string.tooldetail_grammar_giella_size, formatBytes(entry.bytes), entry.licence)
    }
    Column {
        WmRow(
            title = Locale.forLanguageTag(langId).getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) },
            supporting = {
                Text(supporting, color = if (status == Status.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
            },
            trailing = {
                when (status) {
                    is Status.Installed -> IconButton(onClick = { DivvunGrammar.delete(filesDir, langId) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.tooldetail_grammar_giella_delete_desc))
                    }
                    is Status.Downloading -> TextButton(onClick = { DivvunGrammar.cancel(langId) }) {
                        Text(stringResource(CommonR.string.common_cancel))
                    }
                    Status.NotInstalled, Status.Failed -> TextButton(onClick = { download() }) {
                        Text(stringResource(if (status == Status.Failed) CommonR.string.common_retry else CommonR.string.common_download))
                    }
                }
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (status is Status.Downloading) {
            val progress = if (status.totalBytes > 0L) (status.bytes.toFloat() / status.totalBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                metered = false
                DivvunGrammar.start(filesDir, langId)
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}
