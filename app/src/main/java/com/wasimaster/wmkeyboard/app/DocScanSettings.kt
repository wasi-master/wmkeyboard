package com.wasimaster.wmkeyboard.app

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.docscan.DocScanNative
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.util.PlayServices

/** Whether Google's document scanner can run here, so the built-in one is a choice rather than the only one. */
internal val googleDocScanner: Boolean get() = BuildConfig.ENABLE_ML_KIT_SCANNERS && PlayServices.available

/**
 * The built-in scanner's library, on an install that has not fetched it and
 * would use it: always where Google's scanner cannot run, and where the user
 * has picked the built-in one. Nothing where the APK carries the library.
 * The scanner itself offers the same download the first time it opens.
 */
@Composable
internal fun DocScanModuleBanner(settings: LiveSettings, builtIn: Boolean) {
    val context = LocalContext.current
    val module by DocScanNative.moduleState.collectAsState()
    if (module == ModuleState.Installed || (googleDocScanner && !builtIn)) return
    var metered by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    val download: () -> Unit = {
        when (downloadDecisionNow(context, settings.value)) {
            MeteredDecision.ALLOWED -> DocScanNative.requestModule()
            MeteredDecision.ASK -> metered = true
            MeteredDecision.BLOCKED -> blocked = true
        }
    }
    when (val state = module) {
        is ModuleState.Installing -> StateBanner(
            if (state.totalBytes > 0L) {
                stringResource(
                    R.string.tooldetail_doc_scan_module_installing_sized_info,
                    Formatter.formatShortFileSize(context, state.bytes),
                    Formatter.formatShortFileSize(context, state.totalBytes),
                )
            } else {
                stringResource(R.string.tooldetail_doc_scan_module_installing_info)
            },
        )
        // F-Droid, before the user has opted in to library downloads.
        is ModuleState.Missing if state.consent != null -> NativeConsentBanner(state.consent.orEmpty(), "docscan")
        is ModuleState.Missing -> StateBanner(
            stringResource(
                if (state.failed) {
                    OfflineBuild.library(R.string.models_whisper_module_failed_info)
                } else {
                    R.string.tooldetail_doc_scan_module_missing_info
                },
            ),
            action = stringResource(if (state.failed) CommonR.string.common_retry else CommonR.string.common_download),
            tone = if (state.failed) BannerTone.WARNING else BannerTone.INFO,
            onAction = download,
        )
        ModuleState.Installed -> Unit
    }
    if (metered) {
        MeteredDownloadDialog(
            detail = stringResource(R.string.tooldetail_doc_scan_module_metered_body),
            onConfirm = {
                metered = false
                DocScanNative.requestModule()
            },
            onDismiss = { metered = false },
        )
    }
    if (blocked) MeteredBlockedDialog(onDismiss = { blocked = false })
}
