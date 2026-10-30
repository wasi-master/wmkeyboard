package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.modules.DownloadedModuleGate
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.modules.NativePin
import com.wasimaster.wmkeyboard.core.modules.NativeSource
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * The rows for native delivery on the Privacy screen's Downloads group, on a
 * build that has it ([NativeDelivery.active]): F-Droid's opt-in, each vendor
 * licence this build's libraries need, and on the offline build, which cannot
 * download them, where to get every library and the import. Nothing on any
 * other build.
 */
@Composable
internal fun FeatureLibrariesRows() {
    if (!NativeDelivery.active) return
    if (!InternetPermission.granted) {
        OfflineLibrariesRow()
        return
    }
    val agreements by NativeConsent.agreements.collectAsState()
    if (NativeConsent.downloadsNeedOptIn) {
        var confirmOn by remember { mutableStateOf(false) }
        val on = NativeConsent.DOWNLOADS in agreements
        ToggleSetting(
            R.string.privacy_native_downloads_title,
            stringResource(
                if (on) R.string.privacy_native_downloads_on_subtitle else R.string.privacy_native_downloads_off_subtitle,
            ),
            on,
            info = stringResource(R.string.privacy_native_downloads_info),
        ) { want ->
            if (want) confirmOn = true else NativeConsent.set(NativeConsent.DOWNLOADS, false)
        }
        if (confirmOn) {
            AlertDialog(
                onDismissRequest = { confirmOn = false },
                title = { Text(stringResource(R.string.privacy_native_downloads_confirm_title)) },
                text = { Text(stringResource(R.string.privacy_native_downloads_info)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmOn = false
                        NativeConsent.set(NativeConsent.DOWNLOADS, true)
                    }) { Text(stringResource(R.string.privacy_native_downloads_confirm_action)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmOn = false }) { Text(stringResource(CommonR.string.common_cancel)) }
                },
            )
        }
    }
    if (NativeDelivery.pins().any { it.licence == NativeConsent.QUALCOMM }) {
        var asking by remember { mutableStateOf(false) }
        val accepted = NativeConsent.QUALCOMM in agreements
        ToggleSetting(
            R.string.privacy_qualcomm_licence_title,
            stringResource(
                if (accepted) R.string.privacy_qualcomm_licence_accepted else R.string.privacy_qualcomm_licence_not_accepted,
            ),
            accepted,
        ) { want ->
            if (want) asking = true else NativeConsent.set(NativeConsent.QUALCOMM, false)
        }
        if (asking) QualcommLicenceDialog(onDone = { asking = false })
    }
}

/**
 * Qualcomm's licence, before the phone fetches the QNN runtime for the NPU.
 * The files come straight from Qualcomm's own artifact on Maven Central, so
 * the user receives them under Qualcomm's terms rather than from this
 * project, and says so here. [onDone] is called with whether they accepted;
 * accepting also starts the download of the shared libraries.
 */
@Composable
internal fun QualcommLicenceDialog(onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    // The shared libraries plus one chip's pair: what this phone fetches.
    val bytes = remember {
        val qualcomm = NativeDelivery.pins().filter { it.licence == NativeConsent.QUALCOMM }
        val shared = qualcomm.filter { it.gate == "npu" }.sumOf { it.size }
        val perChip = qualcomm.filter { it.gate != "npu" }.groupBy { it.gate }.values.maxOfOrNull { g -> g.sumOf { it.size } }
        shared + (perChip ?: 0L)
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.privacy_qualcomm_licence_dialog_title)) },
        text = {
            Text(stringResource(R.string.privacy_qualcomm_licence_dialog_body, Formatter.formatShortFileSize(context, bytes)))
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(NativeConsent.QUALCOMM, true)
                (NativeDelivery.gate("npu") as? DownloadedModuleGate)?.requestInstall()
                onDone(true)
            }) { Text(stringResource(R.string.privacy_qualcomm_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[NativeConsent.QUALCOMM]?.let { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text(stringResource(R.string.privacy_qualcomm_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/** ML Kit's and NLLB-200's libraries: the offline build cannot get their models, so they would do nothing. */
private val OFFLINE_USELESS_GATES = setOf("translate", "ct2")

/**
 * The offline build's one row for its feature libraries: how many are here,
 * every missing one's published file under **Get the files**, and **Import**.
 * Re-reads the count after an import.
 */
@Composable
private fun OfflineLibrariesRow() {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val all = NativeDelivery.pins().filterNot { it.gate in OFFLINE_USELESS_GATES }
    val missing = remember(revision) { NativeDelivery.missing(all.mapTo(HashSet()) { it.gate }) }
    OfflineImportRow(
        title = stringResource(R.string.privacy_native_import_title),
        subtitle = stringResource(R.string.privacy_native_import_subtitle, all.size - missing.size, all.size),
        links = remember(missing) { nativeLibraryLinks(context, missing) },
        onImported = { revision++ },
    )
}

/**
 * What a feature says on the offline build when its library is missing, or on
 * F-Droid before the download opt-in: [consent] is the gate's
 * [ModuleState.Missing.consent][com.wasimaster.wmkeyboard.core.modules.ModuleState.Missing.consent].
 * The offline build's banner opens the files of [gates] still missing, each
 * with where it is published, and the import.
 */
@Composable
internal fun NativeConsentBanner(consent: String, vararg gates: String) {
    if (consent != NativeConsent.IMPORT) {
        StateBanner(stringResource(R.string.models_native_consent_needed_info), tone = BannerTone.WARNING)
        return
    }
    val context = LocalContext.current
    var showLinks by remember { mutableStateOf(false) }
    val runner = rememberOfflineImport()
    StateBanner(
        stringResource(R.string.models_native_import_needed_info),
        action = stringResource(R.string.offline_import_get_action),
        tone = BannerTone.WARNING,
        onAction = { showLinks = true },
    )
    if (showLinks) {
        val links = remember { nativeLibraryLinks(context, NativeDelivery.missing(gates.toSet())) }
        OfflineLinksDialog(
            links,
            onImport = {
                showLinks = false
                runner.pick()
            },
        ) { showLinks = false }
    }
}

/**
 * The line of text a screen with no room for [NativeConsentBanner] shows for
 * [consent]: where to import on the offline build, where to opt in on F-Droid.
 */
@Composable
internal fun nativeConsentInfo(consent: String): String = stringResource(
    if (consent == NativeConsent.IMPORT) R.string.models_native_import_where_info else R.string.models_native_consent_needed_info,
)

/**
 * Where each of [pins] is published: the data repository's `<sha256>-<name>`,
 * or the vendor's `.aar`, which imports whole. Pins in one archive share a link.
 */
internal fun nativeLibraryLinks(context: Context, pins: List<NativePin>): List<OfflineLink> =
    pins.groupBy { NativeDelivery.publishedUrl(it) }.mapNotNull { (url, group) ->
        url ?: return@mapNotNull null
        val size = Formatter.formatShortFileSize(context, group.sumOf { it.size })
        val names = group.joinToString(", ") { it.name }
        val label = if (group.first().source is NativeSource.Maven) {
            context.getString(R.string.offline_import_native_archive_label, names, NativeDelivery.publishedName(group.first()), size)
        } else {
            context.getString(R.string.offline_import_native_label, names, size)
        }
        OfflineLink(label, url)
    }
