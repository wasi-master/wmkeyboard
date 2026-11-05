package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.settings.DataSaverStatus
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.MeteredFeature

/**
 * What data saving allows a download to do right now, in the settings app.
 *
 * The keyboard service keeps a `NetworkWatcher` running because it makes this
 * decision on every panel open; a settings screen asks once, when a button is
 * pressed, so it reads the connection there and then instead. The answer is
 * the same either way — [DataSaverSettings.appliesTo] against the network as
 * it stands.
 *
 * Session grants are deliberately not shared with the keyboard's: the two
 * processes ask separately, and a yes given to a 300 MB model is not a yes
 * given to a GIF grid.
 */
internal fun downloadDecisionNow(
    context: Context,
    settings: KeyboardSettings,
): MeteredDecision {
    val network = DeviceNetworkState(metered = isMeteredNow(context))
    val status = DataSaverStatus(
        active = settings.dataSaver.appliesTo(network),
        settings = settings.dataSaver,
    )
    return status.decide(MeteredFeature.DOWNLOADS)
}

/** [downloadDecisionNow] as a callback, for screens that ask on a button press. */
@Composable
internal fun rememberDownloadDecision(settings: LiveSettings): () -> MeteredDecision {
    val context = LocalContext.current
    return remember(settings) { { downloadDecisionNow(context, settings.value) } }
}

/**
 * The kind of connection Android called metered, and the words that go with it.
 *
 * Metered is not the same as mobile data. Android also says it for a Wi-Fi
 * network that asks to be treated as metered (a phone hotspot, or a 4G or 5G
 * home router that sends the hint), and for most VPNs, which count as metered
 * unless their app says otherwise. A dialog that says "mobile data" there reads
 * as the app ignoring the Wi-Fi it is connected to (#142), so every metered
 * dialog names the connection the download will really use.
 */
internal enum class MeteredLink(
    @StringRes val titleRes: Int,
    @StringRes val reasonRes: Int,
    @StringRes val confirmRes: Int,
    @StringRes val blockedTitleRes: Int,
    @StringRes val blockedBodyRes: Int,
) {
    MOBILE(
        R.string.datasaver_metered_mobile_title,
        R.string.datasaver_metered_mobile_body,
        R.string.datasaver_metered_mobile_action,
        R.string.datasaver_blocked_mobile_title,
        R.string.datasaver_blocked_mobile_body,
    ),
    WIFI(
        R.string.datasaver_metered_wifi_title,
        R.string.datasaver_metered_wifi_body,
        R.string.datasaver_metered_wifi_action,
        R.string.datasaver_blocked_wifi_title,
        R.string.datasaver_blocked_wifi_body,
    ),
    VPN(
        R.string.datasaver_metered_other_title,
        R.string.datasaver_metered_vpn_body,
        R.string.datasaver_metered_other_action,
        R.string.datasaver_blocked_other_title,
        R.string.datasaver_blocked_vpn_body,
    ),

    /** Ethernet, Bluetooth tethering, or no active network to read. */
    OTHER(
        R.string.datasaver_metered_other_title,
        R.string.datasaver_metered_other_body,
        R.string.datasaver_metered_other_action,
        R.string.datasaver_blocked_other_title,
        R.string.datasaver_blocked_other_body,
    ),
}

/**
 * Which [MeteredLink] a set of transports is.
 *
 * Mobile data wins over a VPN: a tunnel over mobile data still spends the
 * mobile allowance, and that is the thing the user needs to hear. A VPN wins
 * over Wi-Fi, because on Android 10 and later the VPN's own metered flag is
 * usually why a Wi-Fi connection reads as metered at all.
 */
internal fun meteredLinkOf(cellular: Boolean, vpn: Boolean, wifi: Boolean): MeteredLink = when {
    cellular -> MeteredLink.MOBILE
    vpn -> MeteredLink.VPN
    wifi -> MeteredLink.WIFI
    else -> MeteredLink.OTHER
}

/** The [MeteredLink] of the connection in use right now. */
internal fun meteredLinkNow(context: Context): MeteredLink {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = runCatching {
        cm?.activeNetwork?.let { network -> cm.getNetworkCapabilities(network) }
    }.getOrNull() ?: return MeteredLink.OTHER
    return meteredLinkOf(
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
    )
}

/**
 * Asks before a download starts on a metered connection.
 *
 * [detail] is the caller's own sentence about the download (its name and
 * size); the title, the reason and the confirm button come from the connection,
 * read once when the dialog opens so its words do not change under the thumb.
 * [onConfirm] and [onDismiss] must both close the dialog.
 */
@Composable
internal fun MeteredDownloadDialog(
    detail: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val link = remember { meteredLinkNow(context) }
    val reason = stringResource(link.reasonRes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(link.titleRes)) },
        text = { Text(if (detail == null) reason else "$detail $reason") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(link.confirmRes)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The download is off while the connection is metered, and the user said so.
 *
 * A dialog rather than a silent no-op: the button they pressed has to answer
 * for itself, and the answer names the setting so it can be found. There is no
 * "download anyway" here on purpose — that is what "ask each time" is for, and
 * an override on a refusal would make the two settings the same one.
 *
 * A refusal on a connection Android does not call metered can only come from
 * the manual switch, which holds on every network: saying "metered Wi-Fi"
 * there sent people to mark their Wi-Fi unmetered, which changes nothing
 * (Telegram 5353). That case names the switch instead. Either way the dialog
 * opens Data saver itself rather than only naming it.
 */
@Composable
internal fun MeteredBlockedDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val navigate = LocalSettingsNavigate.current
    val manual = remember { !isMeteredNow(context) }
    val link = remember { meteredLinkNow(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (manual) R.string.datasaver_blocked_manual_title else link.blockedTitleRes))
        },
        text = {
            Text(stringResource(if (manual) R.string.datasaver_blocked_manual_body else link.blockedBodyRes))
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                navigate("datasaver")
            }) { Text(stringResource(R.string.datasaver_blocked_open_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_ok)) }
        },
    )
}
