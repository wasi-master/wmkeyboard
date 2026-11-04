package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.dictionaries.VoikkoDictionaryDownloads
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.prediction.HunspellRuntime
import com.wasimaster.wmkeyboard.core.prediction.VoikkoRuntime
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where Hunspell stands for one language, as the row shows it. */
private enum class HunspellRowState {
    /** Checking words: a list with a pair is on and the library is loaded. */
    ON,

    /** Every list with a pair is switched off. */
    LIST_OFF,

    /** The library's module is not on the device yet. */
    MODULE_MISSING,

    /** The module is here and the library would not load: a build made without it. */
    NOT_IN_BUILD,

    /** The language has a Voikko dictionary, which checks it in Hunspell's place. */
    VOIKKO,
}

/**
 * Whether [langId] has an imported Hunspell dictionary whose `.aff` and
 * `.dic` were kept, which is when [HunspellRow] belongs on its dictionary
 * screen. Lists imported before the pair was kept have none.
 */
@Composable
internal fun rememberHasHunspell(langId: String): Boolean {
    val context = LocalContext.current
    val has by produceState(false, langId) {
        value = withContext(Dispatchers.IO) { CustomDictionaries.hasHunspell(context.filesDir, langId) }
    }
    return has
}

/**
 * The Hunspell row on a language's Dictionaries group: what checking against
 * the imported `.aff` and `.dic` does, and, while the library is not on the
 * device, the same module banner the other on-demand features use, with its
 * Download. The library is never fetched from the keyboard, only here.
 */
@Composable
internal fun HunspellRow(langId: String, settings: LiveSettings) {
    val context = LocalContext.current
    val module by HunspellRuntime.moduleState.collectAsState()
    // A Voikko download or delete changes which of the two checks the language.
    val voikko by VoikkoDictionaryDownloads.states.collectAsState()
    val state by produceState<HunspellRowState?>(null, langId, module, voikko[langId]) {
        value = withContext(Dispatchers.IO) {
            when {
                CustomDictionaries.hunspellFiles(context.filesDir, langId).isEmpty() -> HunspellRowState.LIST_OFF
                module != ModuleState.Installed -> HunspellRowState.MODULE_MISSING
                VoikkoRuntime.hasDictionary(context.filesDir, langId) && VoikkoRuntime.available ->
                    HunspellRowState.VOIKKO
                // Loads the library, about 1 MB, which is why this is off the main thread.
                HunspellRuntime.available -> HunspellRowState.ON
                else -> HunspellRowState.NOT_IN_BUILD
            }
        }
    }
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val download: () -> Unit = {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> HunspellRuntime.requestModule()
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
    Column {
        WmRow(
            title = stringResource(R.string.languages_hunspell_title),
            icon = SettingsRowIcons[R.string.languages_hunspell_title],
            supporting = {
                Text(
                    stringResource(
                        when (state) {
                            HunspellRowState.LIST_OFF -> R.string.languages_hunspell_list_off_subtitle
                            HunspellRowState.NOT_IN_BUILD -> R.string.languages_hunspell_not_in_build_subtitle
                            HunspellRowState.VOIKKO -> R.string.languages_hunspell_voikko_subtitle
                            else -> R.string.languages_hunspell_subtitle
                        },
                    ),
                )
            },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (state == HunspellRowState.MODULE_MISSING) HunspellModuleBanner(module, download)
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.languages_hunspell_module_metered_body),
            onConfirm = {
                metered = false
                HunspellRuntime.requestModule()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/** The library's own download, the same shape as the offline voice part's banner. */
@Composable
private fun HunspellModuleBanner(module: ModuleState, onDownload: () -> Unit) {
    val context = LocalContext.current
    when (module) {
        is ModuleState.Installing -> StateBanner(
            if (module.totalBytes > 0L) {
                stringResource(
                    R.string.languages_hunspell_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, module.bytes),
                    Formatter.formatShortFileSize(context, module.totalBytes),
                )
            } else {
                stringResource(R.string.languages_hunspell_module_installing_info)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if module.consent != null -> NativeConsentBanner(module.consent.orEmpty(), "spell")
        is ModuleState.Missing -> StateBanner(
            stringResource(
                if (module.failed) {
                    OfflineBuild.library(R.string.models_whisper_module_failed_info)
                } else {
                    R.string.languages_hunspell_module_missing_info
                },
            ),
            action = stringResource(
                if (module.failed) CommonR.string.common_retry else CommonR.string.common_download,
            ),
            tone = if (module.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = onDownload,
        )
        ModuleState.Installed -> Unit
    }
}
