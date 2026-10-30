package com.wasimaster.wmkeyboard.ime.send

import android.net.Uri
import com.wasimaster.wmkeyboard.core.send.SendDirectCode
import com.wasimaster.wmkeyboard.core.send.SendError
import com.wasimaster.wmkeyboard.core.send.SendInfo
import com.wasimaster.wmkeyboard.core.send.SendInstance
import com.wasimaster.wmkeyboard.core.send.LanShare
import com.wasimaster.wmkeyboard.core.send.LanTransfer
import com.wasimaster.wmkeyboard.core.send.SendLink
import com.wasimaster.wmkeyboard.core.send.SendReceived
import com.wasimaster.wmkeyboard.core.send.SendRemoteFile
import com.wasimaster.wmkeyboard.core.send.SendServerConfig
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.core.settings.SendMode
import com.wasimaster.wmkeyboard.core.settings.SendSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the Send files panel draws (#729), owned by [SendController]
 * rather than `KeyboardUiState` for the reason the gallery's is: the panel is
 * the only reader, and an upload ticking should not be a reason for the whole
 * keyboard state to change. The one piece in `KeyboardUiState` is the text
 * being typed into the panel ([SendTextEntry]), because the keyboard's shared
 * capture path types into that state.
 */
data class SendUi(
    val mode: SendMode = SendMode.LINK,
    /** The instance uploads go to, as a base URL. */
    val instance: String = SendSettings.DEFAULT_INSTANCE,
    /** What that instance allows, once asked; null while the probe is out or failed. */
    val config: SendServerConfig? = null,
    val configFailed: Boolean = false,
    val draft: SendDraft = SendDraft(),
    val link: LinkStage = LinkStage.Compose,
    val nearby: NearbyUi = NearbyUi(),
    /** LocalSend and PairDrop devices, and whatever they are asking or moving. */
    val peers: PeersUi = PeersUi(),
    val sheet: SendSheet? = null,
    val uploads: List<SendUpload> = emptyList(),
    val received: List<SendReceived> = emptyList(),
    /** `/info` answers by upload id, fetched when the history sheet or a Done card asks. */
    val uploadInfo: Map<String, SendInfo> = emptyMap(),
    /** Probe results by instance URL, for the status dots in the instance sheet. */
    val instanceStatus: Map<String, InstanceStatus> = emptyMap(),
    /** The user's own instances, first in the sheet. */
    val ownInstances: List<String> = emptyList(),
    /** What the panel's text box is for while the keys type into it. */
    val typing: SendTextField? = null,
    /** The password box shows its letters. */
    val passwordVisible: Boolean = false,
    /** Data saving wants a yes before the transfer goes on mobile data. */
    val dataAsk: Boolean = false,
    /** A field is open to insert a link or a picture into. */
    val hasField: Boolean = true,
    /** The field takes pictures through the keyboard. */
    val fieldTakesImages: Boolean = false,
    /** The build has a QR scanner to read another phone's code with. */
    val canScan: Boolean = false,
    /** Nearby is on in settings and the build may open sockets. */
    val nearbyAvailable: Boolean = true,
    val notice: SendNotice? = null,
) {
    val busy: Boolean get() = link is LinkStage.Uploading || (link as? LinkStage.Receiving)?.ui?.progress != null

    /** The instance's cap, or the stock one until the probe answers. */
    val maxBytes: Long get() = (config ?: SendServerConfig.DEFAULT).maxFileSize

    val overCap: Boolean get() = draft.totalSize > maxBytes
}

/** A file waiting to go, from whichever source. Exactly one of [uri] and [file] is set. */
data class SendDraftFile(
    val id: Long,
    val name: String,
    val size: Long,
    val type: String,
    /** A grant this process holds: a pick, a share, a clip. */
    val uri: Uri? = null,
    /** A copy this keyboard made: the gallery's, or the field's text. */
    val file: File? = null,
    val source: SendSource = SendSource.FILES,
) {
    val isImage: Boolean get() = type.startsWith("image/")
    val isVideo: Boolean get() = type.startsWith("video/")
}

/** Where a draft file came from; the compose card's four tiles. */
enum class SendSource { FILES, GALLERY, CLIPBOARD, FIELD_TEXT }

/** The files picked and the limits chosen for them, before the upload. */
data class SendDraft(
    val files: List<SendDraftFile> = emptyList(),
    val expireSeconds: Long = 86_400L,
    val downloads: Int = 1,
    /** The Password chip is on: a password is typed or generated for this upload. */
    val passwordOn: Boolean = false,
    val password: String = "",
) {
    val totalSize: Long get() = files.sumOf { it.size.coerceAtLeast(0) }
    val isEmpty: Boolean get() = files.isEmpty()
    val effectivePassword: String? get() = password.takeIf { passwordOn && it.isNotEmpty() }
}

/** Where the Link mode is. */
sealed interface LinkStage {
    /** Picking files and limits. */
    data object Compose : LinkStage

    data class Uploading(
        val sent: Long,
        val total: Long,
        val startedAtMs: Long,
        val fileCount: Int,
        val name: String,
    ) : LinkStage {
        val fraction: Float get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** The link is ready. [password] is what was set, to show beside it once. */
    data class Done(
        val upload: SendUpload,
        val password: String?,
        /** Where the link went when it finished: typed, copied, or nowhere yet. */
        val delivered: SendDelivered?,
        val manageOpen: Boolean = false,
    ) : LinkStage

    data class Receiving(val ui: ReceiveUi) : LinkStage

    data class Failed(
        val error: SendError,
        val status: Int = 0,
        /** For `TOO_BIG`: how big the upload was, to suggest instances with room. */
        val bytes: Long = 0,
        val suggestions: List<SendInstance> = emptyList(),
    ) : LinkStage
}

/** How a finished link left the panel. */
enum class SendDelivered { INSERTED, COPIED }

/** A link being received: checked, then downloaded, then saved. */
data class ReceiveUi(
    val link: SendLink,
    /** The envelope, once the server was asked with a working key. */
    val remote: SendRemoteFile? = null,
    /** The server wants a password before it says anything. */
    val needsPassword: Boolean = false,
    /** A password was tried and refused. */
    val wrongPassword: Boolean = false,
    /** The first calls are out. */
    val checking: Boolean = true,
    /** Content bytes saved so far against the total, while downloading. */
    val progress: Pair<Long, Long>? = null,
    /** What landed, once it did. */
    val saved: List<SendReceived> = emptyList(),
    val error: SendError? = null,
) {
    val done: Boolean get() = saved.isNotEmpty()
}

/** A probe of one instance's `/config`. */
data class InstanceStatus(val config: SendServerConfig?, val checkedAtMs: Long) {
    val reachable: Boolean get() = config != null
}

/** The cards drawn over the panel, one at a time. */
enum class SendSheet { INSTANCES, HISTORY, UPLOAD_MENU, QR, DIRECT_CODE, BROWSER_CODE }

/** What the panel's text box is for. */
enum class SendTextField {
    /** The upload's password, or the one a received file wants. */
    PASSWORD,

    /** A pasted or typed share link to receive. */
    LINK,

    /** The address of an instance to add. */
    INSTANCE,
}

/** The panel's text box while the keys type into it. */
data class SendTextEntry(val field: SendTextField, val text: String = "")

/** The Nearby mode: a device on the same network, or a direct link between two phones. */
data class NearbyUi(
    /** The device picked to send to; null follows the first one connected. */
    val targetId: String? = null,
    val direct: DirectUi = DirectUi.Off,
    /** The device the last Nearby send went to, for the "sent" line. */
    val sentTo: String? = null,
    val browser: BrowserUi = BrowserUi.Off,
)

/** The draft served to any browser on this Wi-Fi from a QR code (#927); no app needed on the other side. */
sealed interface BrowserUi {
    data object Off : BrowserUi

    /** Opening a Wi-Fi for it first. */
    data object Starting : BrowserUi

    data class Serving(
        /** The page, token included: what the code carries. */
        val url: String,
        /** The Wi-Fi this phone opened for it, to join before the page; null on a Wi-Fi both are on. */
        val hotspot: BrowserHotspot? = null,
        /** Browsers may send files to this phone. */
        val uploads: Boolean = true,
        /** Newest first, the last few. */
        val transfers: List<LanTransfer> = emptyList(),
    ) : BrowserUi

    /** [hotspot] says why the Wi-Fi did not open, when that was the step that failed. */
    data class Failed(val reason: BrowserFailure, val hotspot: DirectFailure? = null) : BrowserUi
}

data class BrowserHotspot(val ssid: String, val passphrase: String) {
    /** The code a phone camera joins the Wi-Fi from. */
    val joinCode: String get() = LanShare.wifiCode(ssid, passphrase)
}

enum class BrowserFailure {
    /** No Wi-Fi, hotspot or tether to serve on. */
    NO_NETWORK,

    /** The socket would not open. */
    SERVER,

    /** The hotspot asked for did not open. */
    HOTSPOT,
}

/** A Wi-Fi link that needs no network between the two phones: one hosts, the other joins. */
sealed interface DirectUi {
    data object Off : DirectUi

    /** Asking Android for the group or the hotspot. */
    data object Starting : DirectUi

    /** This phone opened a Wi-Fi and shows the code; [joined] names the phone that came, once one did. */
    data class Hosting(val code: SendDirectCode, val joined: String? = null) : DirectUi

    /** This phone scanned a code and is on its way to the other one. */
    data class Joining(val code: SendDirectCode, val step: JoinStep) : DirectUi

    data class Failed(val reason: DirectFailure) : DirectUi
}

enum class JoinStep { WIFI, FINDING, PAIRING, LINKED }

enum class DirectFailure {
    /** Nearby devices (Android 13+) or location (below) was not granted. */
    NO_PERMISSION,
    WIFI_OFF,
    /** The phone cannot open a Wi-Fi Direct group or a hotspot. */
    UNSUPPORTED,
    GROUP_FAILED,
    JOIN_FAILED,
    /** Joined the Wi-Fi, found nobody at the address. */
    NOT_FOUND,
    TIMEOUT,
}

/** A short line that fades after a moment. */
data class SendNotice(val kind: Kind, val text: String = "", val atMs: Long = System.currentTimeMillis()) {
    enum class Kind {
        COPIED, INSERTED, SHARED, SAVED, DELETED, SENT, FAILED, NO_FILES, EMPTY_FIELD, EMPTY_CLIPBOARD,
        TOO_MANY, PASSWORD_GENERATED, INSTANCE_SET, INSTANCE_BAD, INSTANCE_ADDED, INSTANCE_REMOVED,
        NOT_ACCEPTED, OPENED, LINK_BAD, DATA_SAVER,
    }
}

/** Everything the panel can ask for, as one type, so the callback bundle stays one field. */
sealed interface SendAction {
    // Mode and chrome
    data class SetMode(val mode: SendMode) : SendAction
    data class OpenSheet(val sheet: SendSheet?) : SendAction
    data object OpenSettings : SendAction
    data object OpenHistoryScreen : SendAction
    data object Dismiss : SendAction
    data object NoticeDismiss : SendAction

    // Draft
    data class AddFrom(val source: SendSource) : SendAction
    data class RemoveFile(val id: Long) : SendAction
    data class MoveFile(val from: Int, val to: Int) : SendAction
    data object ClearFiles : SendAction
    data class SetExpiry(val seconds: Long) : SendAction
    data class SetDownloads(val count: Int) : SendAction
    data class PasswordOn(val on: Boolean) : SendAction
    data object PasswordFocus : SendAction
    data object PasswordGenerate : SendAction
    data object PasswordToggleVisible : SendAction
    data object TextDone : SendAction

    // Instances
    data class PickInstance(val url: String) : SendAction
    data object InstanceFocus : SendAction
    data object InstanceAdd : SendAction
    data class InstanceRemove(val url: String) : SendAction
    data object ProbeInstances : SendAction

    // Upload
    data class Upload(val then: SendDelivered? = null, val showQr: Boolean = false) : SendAction
    data object Cancel : SendAction
    data object DataAllow : SendAction
    data object DataRefuse : SendAction
    data object Reset : SendAction

    // The finished link
    data object InsertLink : SendAction
    data object CopyLink : SendAction
    data object ShareLink : SendAction
    data object CopyPassword : SendAction
    data class ManageOpen(val open: Boolean) : SendAction
    data class DeleteUpload(val upload: SendUpload) : SendAction
    data class SetUploadDownloads(val upload: SendUpload, val limit: Int) : SendAction
    data class RefreshInfo(val upload: SendUpload) : SendAction
    data class ShowUpload(val upload: SendUpload) : SendAction
    data class CopyUploadLink(val upload: SendUpload) : SendAction
    data class InsertUploadLink(val upload: SendUpload) : SendAction

    // Receiving
    data object LinkFocus : SendAction
    data class Receive(val url: String) : SendAction
    data object Download : SendAction
    data class OpenReceived(val item: SendReceived) : SendAction
    data class InsertReceived(val item: SendReceived) : SendAction
    data class ShareReceived(val item: SendReceived) : SendAction
    data class ForgetReceived(val item: SendReceived) : SendAction

    // Nearby
    data class PickDevice(val deviceId: String?) : SendAction
    data class SendNearby(val deviceId: String) : SendAction
    data object RefreshDevices : SendAction
    data class PairDevice(val deviceId: String) : SendAction
    data class AcceptPair(val deviceId: String) : SendAction
    data class RejectPair(val deviceId: String) : SendAction
    data object HostDirect : SendAction
    data object ScanDirect : SendAction
    data class JoinDirect(val code: String) : SendAction
    data object StopDirect : SendAction
    data class OpenTransfer(val location: String, val fileName: String) : SendAction
    data class CancelTransfer(val transferId: Long) : SendAction
    data class ClearTransfers(val deviceId: String) : SendAction
    data object AskNearbyPermission : SendAction

    // Nearby, in a browser (#927)
    data object StartBrowser : SendAction
    data object OpenBrowserHotspot : SendAction
    data object StopBrowser : SendAction
    data class BrowserUploads(val on: Boolean) : SendAction
    data object CopyBrowserLink : SendAction

    // Nearby, to LocalSend and PairDrop
    data class SendToPeer(val peerId: String) : SendAction
    data class AnswerOffer(val yes: Boolean) : SendAction
    data object InstallWebRtc : SendAction

    // The strip's link chip (a copied share link)
    data object OpenOffer : SendAction
    data object DismissOffer : SendAction
}

/** What the Send files panel hands back; one bundle, see `ToolHoldCallbacks.send`. */
data class SendCallbacks(
    val state: StateFlow<SendUi> = MutableStateFlow(SendUi()),
    val onAction: (SendAction) -> Unit = {},
)

/** A copied Send link offered on the suggestion strip until it is used, dismissed, or stale. */
data class SendLinkOffer(val url: String, val atMs: Long)
