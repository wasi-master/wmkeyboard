package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.gesture.latinime.LatinImeLibrary
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.settings.GlideEngine
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.voice.futoswipe.FutoSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Which decoder reads a glide: the built-in one, FUTO Swipe's neural models
 * for English on QWERTY, or Google's gesture library the user imported. Out
 * of line because the gestures screen's body is past its length budget, and
 * because picking FUTO Swipe is a small flow of its own: the weights licence,
 * the 3.9 MB of models, and the ExecuTorch runtime where the build fetches it.
 * The library has rows of its own ([GestureLibraryRows]), in every build but
 * Google Play's, which may not load it.
 *
 * The licence asks for a visible notice that the product is powered by
 * "FUTO Swipe". This row says so whenever that engine is picked, and the
 * About screen credits it.
 */
internal fun SettingsGroupScope.glideEngineRows(
    settings: LiveSettings,
    repository: SettingsRepository,
    scope: CoroutineScope,
) {
    item { GlideEngineRow(settings, repository, scope) }
    if (LatinImeLibrary.supported) item { GestureLibraryRows(settings) }
}

@Composable
private fun GlideEngineRow(settings: LiveSettings, repository: SettingsRepository, scope: CoroutineScope) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    val engine = settings.watch { it.gesture.engine }
    val status by FutoSwipe.status.collectAsState()
    LaunchedEffect(Unit) { FutoSwipe.refresh(filesDir) }
    var asking by remember { mutableStateOf(false) }
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }

    fun fetch() {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> {
                FutoSwipe.start(filesDir)
                FeatureModules.executorch.requestInstall()
            }
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }

    fun choose(pick: GlideEngine) {
        scope.launch { repository.setGestureEngine(pick) }
        if (pick == GlideEngine.FUTO_SWIPE && status != FutoSwipe.Status.Downloaded) fetch()
    }

    val subtitle = when {
        engine == GlideEngine.BUILT_IN -> stringResource(R.string.typing_glide_engine_subtitle)
        engine == GlideEngine.GOOGLE_LIBRARY -> stringResource(R.string.typing_glide_library_engine_subtitle)
        status is FutoSwipe.Status.Downloading -> (status as FutoSwipe.Status.Downloading).let {
            stringResource(
                R.string.typing_glide_engine_downloading,
                Formatter.formatShortFileSize(context, it.bytes),
                Formatter.formatShortFileSize(context, it.total),
            )
        }
        status == FutoSwipe.Status.Downloaded -> stringResource(R.string.typing_glide_engine_ready)
        else -> stringResource(R.string.typing_glide_engine_missing)
    }
    ChoiceSetting(
        R.string.typing_glide_engine_title,
        subtitle = subtitle,
        info = stringResource(R.string.typing_glide_engine_info),
        options = GlideEngine.entries
            .filter { it != GlideEngine.GOOGLE_LIBRARY || LatinImeLibrary.supported }
            .map { it to stringResource(it.labelRes) },
        selected = engine,
        default = SettingsDefaults.gesture.engine,
    ) { pick ->
        if (pick == GlideEngine.FUTO_SWIPE && !NativeConsent.has(NativeConsent.FUTO)) {
            asking = true
        } else {
            choose(pick)
        }
    }
    if (engine == GlideEngine.FUTO_SWIPE) {
        // The notice the weights licence asks for, wherever the engine is on.
        CaptionText(stringResource(R.string.typing_glide_engine_powered_by))
        when (val s = status) {
            is FutoSwipe.Status.Failed -> StateBanner(
                if (s.messageArg.isEmpty()) stringResource(s.messageRes) else stringResource(s.messageRes, s.messageArg),
                action = if (offlineBuild) null else stringResource(CommonR.string.common_retry),
                tone = BannerTone.WARNING,
                onAction = ::fetch,
            )
            FutoSwipe.Status.NotDownloaded -> StateBanner(
                stringResource(R.string.typing_glide_engine_missing),
                action = if (offlineBuild) null else stringResource(CommonR.string.common_download),
                onAction = ::fetch,
            )
            else -> Unit
        }
        // Where the build fetches the runtime rather than carrying it.
        SpeechModuleBanner(settings, FeatureModules.executorch, "executorch")
        // For a phone that cannot download (#1002): the two files, fetched
        // elsewhere and imported here.
        if (status != FutoSwipe.Status.Downloaded) {
            OfflineImportRow(
                subtitle = stringResource(R.string.offline_import_futo_subtitle),
                links = remember { FutoSwipe.files.map { OfflineLink(it.path, it.url) } },
                onImported = { FutoSwipe.refresh(filesDir) },
            )
        }
    }
    if (asking) {
        FutoSwipeLicenceDialog { accepted ->
            asking = false
            if (accepted) choose(GlideEngine.FUTO_SWIPE)
        }
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = null,
            onConfirm = {
                metered = false
                FutoSwipe.start(filesDir)
                FeatureModules.executorch.requestInstall()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}

/**
 * The FUTO Model Weights License, before the phone fetches the models. They
 * come straight from FUTO's own Hugging Face repository, so the user receives
 * them under FUTO's terms rather than from this project, and agrees here.
 * [onDone] is called with whether they accepted.
 */
@Composable
private fun FutoSwipeLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.typing_glide_engine_licence_title)) },
        text = {
            Text(
                stringResource(
                    R.string.typing_glide_engine_licence_body,
                    Formatter.formatShortFileSize(context, FutoSwipe.bytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.FUTO, true)
                onDone(true)
            }) { Text(stringResource(R.string.typing_glide_engine_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.FUTO]?.let { url ->
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                }) { Text(stringResource(R.string.typing_glide_engine_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/**
 * Google's gesture library: import it, see whether it is loaded, remove it.
 * Shown while that engine is picked or a library is on the phone.
 *
 * The file comes from the user, through the system file picker; nothing here
 * downloads it. [LatinImeLibrary.import] keeps it only when it is one of the
 * pinned builds for this phone's ABI, and every other outcome says what was
 * wrong with the file in words, not a hash.
 */
@Composable
private fun GestureLibraryRows(settings: LiveSettings) {
    val context = LocalContext.current
    val noBackup = context.noBackupFilesDir
    val engine = settings.watch { it.gesture.engine }
    val scope = rememberCoroutineScope()
    var generation by remember { mutableIntStateOf(0) }
    val status = remember(generation) { LatinImeLibrary.status(noBackup) }
    var result by remember { mutableStateOf<LatinImeLibrary.ImportResult?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            result = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { LatinImeLibrary.import(noBackup, it) }
                        ?: LatinImeLibrary.ImportResult.Error("")
                } catch (e: java.io.IOException) {
                    LatinImeLibrary.ImportResult.Error(e.message.orEmpty())
                } catch (e: SecurityException) {
                    LatinImeLibrary.ImportResult.Error(e.message.orEmpty())
                }
            }
            generation++
        }
    }
    // A library removed while loaded is still in memory (Android cannot
    // unload one) but out of use, and there is no file left to remove: for
    // these rows it is as good as missing, and the subtitle says the rest.
    val gone = status == LatinImeLibrary.Status.Missing || status == LatinImeLibrary.Status.Removed
    if (engine != GlideEngine.GOOGLE_LIBRARY && gone && result == null) return
    val abi = LatinImeLibrary.processAbi()
    val subtitle = when (status) {
        LatinImeLibrary.Status.Missing -> stringResource(R.string.typing_glide_library_missing, abi)
        LatinImeLibrary.Status.Imported -> stringResource(R.string.typing_glide_library_imported)
        LatinImeLibrary.Status.Loaded -> stringResource(R.string.typing_glide_library_loaded)
        LatinImeLibrary.Status.Removed -> stringResource(R.string.typing_glide_library_removed)
        is LatinImeLibrary.Status.Failed -> when (status.reason) {
            LatinImeLibrary.Failure.CHANGED -> stringResource(R.string.typing_glide_library_failed_changed)
            LatinImeLibrary.Failure.CRASHED -> stringResource(R.string.typing_glide_library_failed_crashed)
            LatinImeLibrary.Failure.LOAD_FAILED -> stringResource(R.string.typing_glide_library_failed_load, status.detail)
        }
        LatinImeLibrary.Status.Unsupported -> ""
    }
    ActionRow(
        R.string.typing_glide_library_title,
        subtitle = subtitle,
        action = stringResource(
            if (gone) R.string.typing_glide_library_import else R.string.typing_glide_library_replace,
        ),
    ) { picker.launch(arrayOf("*/*")) }
    result?.let { outcome ->
        val (text, ok) = when (outcome) {
            LatinImeLibrary.ImportResult.Installed -> stringResource(R.string.typing_glide_library_result_installed) to true
            is LatinImeLibrary.ImportResult.OtherAbi ->
                stringResource(R.string.typing_glide_library_result_other_abi, outcome.abi, abi) to false
            LatinImeLibrary.ImportResult.Unknown -> stringResource(R.string.typing_glide_library_result_unknown) to false
            LatinImeLibrary.ImportResult.Unsupported -> stringResource(R.string.typing_glide_library_result_unknown) to false
            is LatinImeLibrary.ImportResult.Error -> stringResource(R.string.typing_glide_library_result_error, outcome.message) to false
        }
        StateBanner(text, tone = if (ok) BannerTone.INFO else BannerTone.WARNING)
    }
    if (status is LatinImeLibrary.Status.Failed && status.reason == LatinImeLibrary.Failure.CRASHED) {
        StateBanner(
            stringResource(R.string.typing_glide_library_failed_crashed),
            action = stringResource(R.string.typing_glide_library_retry),
            tone = BannerTone.WARNING,
        ) {
            LatinImeLibrary.retry(noBackup)
            generation++
        }
    }
    if (!gone) {
        ActionRow(
            R.string.typing_glide_library_remove_title,
            subtitle = stringResource(R.string.typing_glide_library_remove_subtitle),
            action = stringResource(R.string.typing_glide_library_remove),
            confirm = stringResource(R.string.typing_glide_library_remove_confirm),
        ) {
            LatinImeLibrary.remove(noBackup)
            result = null
            generation++
        }
    }
}
