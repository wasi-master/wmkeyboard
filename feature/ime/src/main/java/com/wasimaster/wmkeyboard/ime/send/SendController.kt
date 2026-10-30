package com.wasimaster.wmkeyboard.ime.send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.Uri
import android.os.SystemClock
import com.wasimaster.wmkeyboard.app.SendPickActivity
import com.wasimaster.wmkeyboard.ime.files.FilePickRequest
import com.wasimaster.wmkeyboard.ime.files.FilePicks
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeEvent
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeSocketBinder
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.send.Ece
import com.wasimaster.wmkeyboard.core.send.LanShare
import com.wasimaster.wmkeyboard.core.send.LanTransfer
import com.wasimaster.wmkeyboard.core.send.SendClient
import com.wasimaster.wmkeyboard.core.send.SendDirectCode
import com.wasimaster.wmkeyboard.core.send.SendError
import com.wasimaster.wmkeyboard.core.send.SendException
import com.wasimaster.wmkeyboard.core.send.SendFile
import com.wasimaster.wmkeyboard.core.send.SendInfo
import com.wasimaster.wmkeyboard.core.send.SendInstances
import com.wasimaster.wmkeyboard.core.send.SendLink
import com.wasimaster.wmkeyboard.core.send.SendReceived
import com.wasimaster.wmkeyboard.core.send.SendStore
import com.wasimaster.wmkeyboard.core.send.SendUpload
import com.wasimaster.wmkeyboard.core.send.SendUploadOptions
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import com.wasimaster.wmkeyboard.core.settings.SendAfterUpload
import com.wasimaster.wmkeyboard.core.settings.SendMode
import com.wasimaster.wmkeyboard.core.settings.SendPasswordMode
import com.wasimaster.wmkeyboard.core.settings.SendSettings
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The keyboard half of the Send files tool (#729).
 *
 * Holds the panel's [SendUi]: the draft (files, limits, password), the Link
 * mode's stage (composing, uploading, the finished link, a link being
 * received), the Nearby mode (a device on the network, or a direct Wi-Fi link
 * between two phones), the sheets and the history. Turns the panel's
 * [SendAction]s into [SendClient] calls, KDE Connect transfers, and commits
 * through the [Host]. Owned by the service and built on first use.
 *
 * Uploads and downloads run on [scope] and outlive the panel: closing it, or
 * the keyboard, leaves the job running and the result goes to a notification.
 */
internal class SendController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the controller needs from the service, which owns the field and the keyboard's state. */
    interface Host {
        val settings: SendSettings
        fun updateSettings(change: (SendSettings) -> SendSettings)
        fun haptic()

        /** Whether the field is incognito: nothing is remembered from it. */
        fun incognito(): Boolean
        fun keyboardVisible(): Boolean

        /** A field is open to type into. */
        fun hasField(): Boolean
        fun fieldPackage(): String?

        /** The selection if there is one, otherwise the whole field. */
        fun fieldText(): String
        fun fieldAcceptsImages(): Boolean

        /** Puts [uri] into the field as content. False when the field would not take it. */
        fun commitUri(uri: Uri, mime: String, name: String): Boolean
        fun insertText(text: String)
        fun copyText(text: String)
        fun openPanel()
        fun closePanel()
        fun openSettings()
        fun openRoute(route: String)
        fun openScanner()
        fun openGallery()
        fun startActivity(intent: Intent)

        /** [send] on the keyboard's share sheet, or Android's when that is off (#970). True when one opened. */
        fun share(send: Intent): Boolean
        fun beginText(entry: SendTextEntry)
        fun endText()
        fun currentText(): SendTextEntry?

        /** Data saving's answer for a whole-file transfer right now. */
        fun metered(): MeteredDecision
        fun grantMetered()
        fun httpClient(): OkHttpClient

        /** What the clipboard holds that can travel as a file: content URIs, or text to save as one. */
        fun clipboardFiles(): List<SendDraftFile>
        fun fileUri(file: File): Uri?
        fun directLink(): DirectLink
        fun hasNearbyPermission(): Boolean
        fun askNearbyPermission()
        fun canScan(): Boolean

        /** The strip's chip for a copied link: open the panel on it, or put the chip away. */
        fun openLinkOffer()
        fun clearLinkOffer()
    }

    private val app = context.applicationContext
    private val _state = MutableStateFlow(SendUi())
    val state: StateFlow<SendUi> = _state

    val callbacks = SendCallbacks(state = state, onAction = ::dispatch)

    private val store by lazy { SendStore(File(app.filesDir, SendStore.DIR)) }
    private val client by lazy { SendClient(host.httpClient()) }
    private val random = SecureRandom()

    private var uploadJob: Job? = null
    private var receiveJob: Job? = null
    private var probeJob: Job? = null
    private var infoJob: Job? = null
    private var noticeJob: Job? = null
    private var hubJob: Job? = null
    private var directJob: Job? = null

    @Volatile private var cancelRequested = false
    private var open = false
    private var nextFileId = 1L

    /** The receiving conversation, kept between the metadata call and the download. */
    private var session: SendClient.Session? = null

    /** Picks whose read grant [SendPickActivity] persisted for the draft: released once they leave it. */
    private val heldGrants = mutableSetOf<Uri>()

    /** What a data-saving "ask" is holding. */
    private var pendingTransfer: (() -> Unit)? = null

    /** The password typed for a file being received. */
    private var receivePassword = ""
    private var linkDraft = ""
    private var instanceDraft = ""

    /** A direct link is up and the hub's reason must stay held for it. */
    private var directHeld = false
    private var pendingDirectHost = false

    /** The page a browser on this Wi-Fi opens (#927), while it is up. */
    private var lanShare: LanShare? = null

    /** Feeds the draft to [lanShare] and stops it once nobody has come for a while. */
    private var lanJob: Job? = null
    @Volatile private var lanSeenAt = 0L
    @Volatile private var lanTickAt = 0L

    /** The Wi-Fi the browser card opened itself, as opposed to one borrowed from a direct link. */
    private var browserOwnsHotspot = false
    private var pendingBrowserHotspot = false

    // ---- Lifecycle --------------------------------------------------------

    fun onPanelOpen() {
        open = true
        val s = host.settings
        _state.update {
            it.copy(
                mode = if (s.nearby) s.lastMode else SendMode.LINK,
                instance = s.instanceUrl,
                ownInstances = s.ownInstances,
                draft = if (it.draft.isEmpty && it.draft.password.isEmpty()) {
                    it.draft.copy(expireSeconds = s.expireSeconds, downloads = s.downloads, passwordOn = s.password != SendPasswordMode.OFF)
                } else {
                    it.draft
                },
                hasField = host.hasField(),
                fieldTakesImages = host.fieldAcceptsImages(),
                canScan = host.canScan(),
                nearbyAvailable = s.nearby,
                sheet = null,
                dataAsk = false,
            )
        }
        refreshHistory()
        probe(s.instanceUrl)
        consumePicks()
        syncHub()
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        pendingTransfer = null
        _state.update {
            it.copy(
                sheet = null,
                notice = null,
                dataAsk = false,
                link = when (val stage = it.link) {
                    // A finished link stays for the next open; a failure or a finished receive does not.
                    is LinkStage.Failed -> LinkStage.Compose
                    is LinkStage.Receiving -> if (stage.ui.done || stage.ui.error != null) LinkStage.Compose else stage
                    else -> stage
                },
            )
        }
        host.updateSettings { s -> if (s.lastMode == _state.value.mode) s else s.copy(lastMode = _state.value.mode) }
        syncHub()
    }

    /** Back from the file picker, a permission screen, or the scanner. */
    fun onKeyboardShown() {
        consumePicks()
        if (pendingDirectHost && host.hasNearbyPermission()) {
            pendingDirectHost = false
            hostDirect()
        }
        if (pendingBrowserHotspot && host.hasNearbyPermission()) {
            pendingBrowserHotspot = false
            browserHotspot()
        }
        if (open) _state.update { it.copy(hasField = host.hasField(), fieldTakesImages = host.fieldAcceptsImages()) }
    }

    /** The keyboard window went away; the jobs carry on and report through the shade. */
    fun onFinish() = Unit

    /** Whether [url] is a link this keyboard made: its own copies raise no chip. */
    fun ownsLink(url: String): Boolean {
        val s = _state.value
        return s.uploads.any { it.url == url } || (s.link as? LinkStage.Done)?.upload?.url == url
    }

    private fun consumePicks() {
        val picks = SendPickActivity.consume()
        if (picks.isEmpty()) return
        picks.filter { it.held }.forEach { heldGrants += it.uri }
        addFiles(picks.map { p -> SendDraftFile(nextFileId++, p.name, p.size, p.mime, uri = p.uri, source = SendSource.FILES) })
        if (!open) host.openPanel()
    }

    // ---- Dispatch ---------------------------------------------------------

    private fun dispatch(action: SendAction) {
        when (action) {
            is SendAction.SetMode -> setMode(action.mode)
            is SendAction.OpenSheet -> openSheet(action.sheet)
            SendAction.OpenSettings -> host.openSettings()
            SendAction.OpenHistoryScreen -> host.openRoute(SendShareAlias.HISTORY_ROUTE)
            SendAction.Dismiss -> dismiss()
            SendAction.NoticeDismiss -> _state.update { it.copy(notice = null) }

            is SendAction.AddFrom -> addFrom(action.source)
            is SendAction.RemoveFile -> removeFile(action.id)
            is SendAction.MoveFile -> moveFile(action.from, action.to)
            SendAction.ClearFiles -> clearFiles()
            is SendAction.SetExpiry -> _state.update { it.copy(draft = it.draft.copy(expireSeconds = action.seconds)) }
            is SendAction.SetDownloads -> _state.update { it.copy(draft = it.draft.copy(downloads = action.count)) }
            is SendAction.PasswordOn -> passwordOn(action.on)
            SendAction.PasswordFocus -> beginText(SendTextField.PASSWORD)
            SendAction.PasswordGenerate -> generatePassword()
            SendAction.PasswordToggleVisible -> _state.update { it.copy(passwordVisible = !it.passwordVisible) }
            SendAction.TextDone -> textDone()

            is SendAction.PickInstance -> pickInstance(action.url)
            SendAction.InstanceFocus -> beginText(SendTextField.INSTANCE)
            SendAction.InstanceAdd -> instanceAdd()
            is SendAction.InstanceRemove -> instanceRemove(action.url)
            SendAction.ProbeInstances -> probeAll()

            is SendAction.Upload -> upload(action.then, action.showQr)
            SendAction.Cancel -> cancel()
            SendAction.DataAllow -> {
                host.grantMetered()
                _state.update { it.copy(dataAsk = false) }
                pendingTransfer?.invoke()
                pendingTransfer = null
            }
            SendAction.DataRefuse -> {
                pendingTransfer = null
                _state.update { it.copy(dataAsk = false) }
            }
            SendAction.Reset -> reset()

            SendAction.InsertLink -> doneUpload()?.let { insertLink(it) }
            SendAction.CopyLink -> doneUpload()?.let { copyLink(it) }
            SendAction.ShareLink -> doneUpload()?.let { shareLink(it) }
            SendAction.CopyPassword -> (_state.value.link as? LinkStage.Done)?.password?.let {
                host.copyText(it)
                notice(SendNotice.Kind.COPIED)
            }
            is SendAction.ManageOpen -> _state.update { s ->
                s.copy(link = (s.link as? LinkStage.Done)?.copy(manageOpen = action.open) ?: s.link)
            }
            is SendAction.DeleteUpload -> deleteUpload(action.upload)
            is SendAction.SetUploadDownloads -> setUploadDownloads(action.upload, action.limit)
            is SendAction.RefreshInfo -> refreshInfo(listOf(action.upload))
            is SendAction.ShowUpload -> {
                _state.update { it.copy(mode = SendMode.LINK, link = LinkStage.Done(action.upload, null, null), sheet = null) }
                refreshInfo(listOf(action.upload))
            }
            is SendAction.CopyUploadLink -> copyLink(action.upload)
            is SendAction.InsertUploadLink -> insertLink(action.upload)

            SendAction.LinkFocus -> beginText(SendTextField.LINK)
            is SendAction.Receive -> receive(action.url)
            SendAction.Download -> download()
            is SendAction.OpenReceived -> openReceived(action.item)
            is SendAction.InsertReceived -> insertReceived(action.item)
            is SendAction.ShareReceived -> shareReceived(action.item)
            is SendAction.ForgetReceived -> {
                scope.launch(Dispatchers.IO) { store.removeReceived(action.item.location) }
                _state.update { it.copy(received = it.received.filterNot { r -> r.location == action.item.location }) }
            }

            is SendAction.PickDevice -> _state.update { it.copy(nearby = it.nearby.copy(targetId = action.deviceId)) }
            is SendAction.SendNearby -> sendNearby(action.deviceId)
            SendAction.RefreshDevices -> KdeConnectHub.refresh()
            is SendAction.PairDevice -> KdeConnectHub.engine?.requestPair(action.deviceId)
            is SendAction.AcceptPair -> KdeConnectHub.engine?.acceptPair(action.deviceId)
            is SendAction.RejectPair -> KdeConnectHub.engine?.cancelPair(action.deviceId)
            SendAction.HostDirect -> hostDirect()
            SendAction.ScanDirect -> host.openScanner()
            is SendAction.JoinDirect -> joinDirect(action.code)
            SendAction.StopDirect -> stopDirect()
            is SendAction.OpenTransfer -> openLocation(action.location, mimeOf(action.fileName))
            is SendAction.CancelTransfer -> KdeConnectHub.engine?.share?.cancel(action.transferId)
            is SendAction.ClearTransfers -> KdeConnectHub.engine?.share?.clearFinished(action.deviceId)
            SendAction.AskNearbyPermission -> host.askNearbyPermission()
            SendAction.StartBrowser -> startBrowser()
            SendAction.OpenBrowserHotspot -> browserHotspot()
            SendAction.StopBrowser -> {
                host.haptic()
                stopBrowser()
            }
            is SendAction.BrowserUploads -> {
                lanShare?.uploadsAllowed = action.on
                updateServing { it.copy(uploads = action.on) }
            }
            SendAction.CopyBrowserLink -> (_state.value.nearby.browser as? BrowserUi.Serving)?.let {
                host.copyText(it.url)
                notice(SendNotice.Kind.COPIED)
            }
            SendAction.OpenOffer -> host.openLinkOffer()
            SendAction.DismissOffer -> host.clearLinkOffer()
        }
    }

    private fun setMode(mode: SendMode) {
        if (mode == SendMode.NEARBY && !_state.value.nearbyAvailable) return
        _state.update { it.copy(mode = mode, sheet = null) }
        host.updateSettings { it.copy(lastMode = mode) }
        syncHub()
    }

    private fun openSheet(sheet: SendSheet?) {
        endTextIfAny()
        _state.update { it.copy(sheet = sheet) }
        when (sheet) {
            SendSheet.HISTORY -> {
                refreshHistory()
                refreshInfo(_state.value.uploads.take(INFO_BATCH))
            }
            SendSheet.INSTANCES -> probeAll()
            else -> Unit
        }
    }

    /** Back, or a tap on the scrim: the innermost thing goes first. */
    private fun dismiss() {
        val s = _state.value
        when {
            s.typing != null -> endTextIfAny()
            s.sheet != null -> _state.update { it.copy(sheet = null) }
            s.dataAsk -> dispatch(SendAction.DataRefuse)
            s.link is LinkStage.Done && s.link.manageOpen -> dispatch(SendAction.ManageOpen(false))
            s.link is LinkStage.Receiving || s.link is LinkStage.Failed -> reset()
            else -> host.closePanel()
        }
    }

    private fun reset() {
        receiveJob?.cancel()
        session = null
        receivePassword = ""
        _state.update { it.copy(link = LinkStage.Compose, sheet = null, dataAsk = false) }
        pendingTransfer = null
    }

    // ---- Draft ------------------------------------------------------------

    private fun addFrom(source: SendSource) {
        host.haptic()
        when (source) {
            SendSource.FILES -> runCatching { FilePicks.open(app, FilePickRequest.SendFiles) }
            SendSource.GALLERY -> host.openGallery()
            SendSource.CLIPBOARD -> {
                val files = host.clipboardFiles().map { it.copy(id = nextFileId++, source = SendSource.CLIPBOARD) }
                if (files.isEmpty()) notice(SendNotice.Kind.EMPTY_CLIPBOARD) else addFiles(files)
            }
            SendSource.FIELD_TEXT -> {
                val text = host.fieldText()
                if (text.isBlank()) {
                    notice(SendNotice.Kind.EMPTY_FIELD)
                    return
                }
                val file = textFile(text, host.fieldPackage())
                if (file == null) notice(SendNotice.Kind.FAILED) else addFiles(listOf(file))
            }
        }
    }

    /** The gallery's "Send as link": its prepared copies, already in the media cache. */
    fun addPrepared(files: List<Pair<File, String>>) {
        addFiles(
            files.map { (file, mime) ->
                SendDraftFile(nextFileId++, file.name, file.length(), mime, file = file, source = SendSource.GALLERY)
            },
        )
    }

    private fun addFiles(files: List<SendDraftFile>) {
        if (files.isEmpty()) return
        measureLater(files.filter { it.size <= 0 && it.uri != null })
        val max = (_state.value.config?.maxFilesPerArchive ?: 64).coerceAtLeast(1)
        _state.update { s ->
            val room = max - s.draft.files.size
            val taken = files.take(room.coerceAtLeast(0))
            s.copy(
                mode = s.mode,
                draft = s.draft.copy(files = s.draft.files + taken),
                // New files start a new upload: the old link stays in history.
                link = if (s.link is LinkStage.Compose || s.link is LinkStage.Done || s.link is LinkStage.Failed) LinkStage.Compose else s.link,
                sheet = null,
            )
        }
        if (files.size > max - (_state.value.draft.files.size - files.size.coerceAtMost(max))) {
            if (_state.value.draft.files.size >= max) notice(SendNotice.Kind.TOO_MANY, max.toString())
        }
    }

    /**
     * A pick whose size the picker did not know: ask the provider, and failing
     * that copy it into the cache, since the envelope and the progress need the
     * number before the first byte goes.
     */
    private fun measureLater(files: List<SendDraftFile>) {
        if (files.isEmpty()) return
        scope.launch {
            val measured = withContext(Dispatchers.IO) { files.map { measure(it) } }
            _state.update { s ->
                s.copy(draft = s.draft.copy(files = s.draft.files.map { f -> measured.firstOrNull { it.id == f.id } ?: f }))
            }
        }
    }

    private fun measure(f: SendDraftFile): SendDraftFile {
        val uri = f.uri ?: return f
        val length = runCatching { app.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        if (length >= 0) return f.copy(size = length)
        val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
        val copy = File(dir, "${f.id}-${f.name}")
        val copied = runCatching {
            app.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } } != null
        }.getOrDefault(false)
        return if (copied) f.copy(size = copy.length(), file = copy, uri = null) else f
    }

    private fun removeFile(id: Long) {
        val gone = _state.value.draft.files.firstOrNull { it.id == id }
        _state.update { it.copy(draft = it.draft.copy(files = it.draft.files.filterNot { f -> f.id == id })) }
        gone?.file?.takeIf { it.parentFile?.name == CACHE_DIR }?.delete()
        gone?.let(::releaseGrant)
    }

    private fun moveFile(from: Int, to: Int) {
        _state.update { s ->
            val files = s.draft.files.toMutableList()
            if (from !in files.indices || to !in files.indices) return@update s
            files.add(to, files.removeAt(from))
            s.copy(draft = s.draft.copy(files = files))
        }
    }

    private fun clearFiles() {
        val files = _state.value.draft.files
        _state.update { it.copy(draft = it.draft.copy(files = emptyList())) }
        files.forEach { it.file?.takeIf { f -> f.parentFile?.name == CACHE_DIR }?.delete() }
        files.forEach(::releaseGrant)
    }

    /** Lets go of a pick's persisted read grant, unless another draft entry still points at it. */
    private fun releaseGrant(f: SendDraftFile) {
        val uri = f.uri ?: return
        if (uri !in heldGrants || _state.value.draft.files.any { it.uri == uri }) return
        heldGrants -= uri
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun passwordOn(on: Boolean) {
        _state.update { it.copy(draft = it.draft.copy(passwordOn = on)) }
        if (on && _state.value.draft.password.isEmpty()) {
            if (host.settings.password == SendPasswordMode.GENERATE) generatePassword() else beginText(SendTextField.PASSWORD)
        } else if (!on) {
            endTextIfAny()
        }
    }

    private fun generatePassword() {
        val password = generatedPassword()
        _state.update { it.copy(draft = it.draft.copy(passwordOn = true, password = password), passwordVisible = true) }
        endTextIfAny()
        notice(SendNotice.Kind.PASSWORD_GENERATED)
    }

    /** Twelve characters from an alphabet with no look-alikes: readable over a shoulder, typeable on a phone. */
    private fun generatedPassword(): String =
        (1..GENERATED_PASSWORD_LENGTH).map { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }.joinToString("")

    /** The field's text as a `.txt` in the media cache, named after the app it came from. */
    private fun textFile(text: String, packageName: String?): SendDraftFile? {
        val dir = File(app.cacheDir, CACHE_DIR).apply { mkdirs() }
        val label = packageName?.let { pkg ->
            runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: "Text"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        val safe = label.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }.trim().ifEmpty { "Text" }
        val file = File(dir, "$safe $stamp.txt")
        return runCatching { file.writeText(text) }.map {
            SendDraftFile(nextFileId++, file.name, file.length(), "text/plain", file = file, source = SendSource.FIELD_TEXT)
        }.getOrNull()
    }

    // ---- Text boxes -------------------------------------------------------

    private fun beginText(field: SendTextField) {
        val initial = when (field) {
            SendTextField.PASSWORD -> if (_state.value.link is LinkStage.Receiving) receivePassword else _state.value.draft.password
            SendTextField.LINK -> linkDraft
            SendTextField.INSTANCE -> instanceDraft
        }
        _state.update { it.copy(typing = field) }
        host.beginText(SendTextEntry(field, initial))
    }

    private fun endTextIfAny() {
        if (_state.value.typing == null && host.currentText() == null) return
        _state.update { it.copy(typing = null) }
        host.endText()
    }

    /** Every keystroke into the panel's box. */
    fun onText(entry: SendTextEntry) {
        val text = entry.text.replace("\n", "")
        when (entry.field) {
            SendTextField.PASSWORD -> {
                if (_state.value.link is LinkStage.Receiving) {
                    receivePassword = text.take(PASSWORD_MAX)
                } else {
                    _state.update { it.copy(draft = it.draft.copy(password = text.take(PASSWORD_MAX), passwordOn = true)) }
                }
            }
            SendTextField.LINK -> linkDraft = text
            SendTextField.INSTANCE -> instanceDraft = text
        }
    }

    /** Enter, or the box's Done. */
    fun onTextEnter(entry: SendTextEntry) {
        onText(entry)
        textDone()
    }

    private fun textDone() {
        val field = _state.value.typing ?: return
        endTextIfAny()
        when (field) {
            SendTextField.PASSWORD -> {
                val stage = _state.value.link
                if (stage is LinkStage.Receiving && stage.ui.needsPassword && receivePassword.isNotEmpty()) {
                    check(stage.ui.link, receivePassword)
                }
            }
            SendTextField.LINK -> if (linkDraft.isNotBlank()) receive(linkDraft)
            SendTextField.INSTANCE -> instanceAdd()
        }
    }

    // ---- Instances --------------------------------------------------------

    private fun pickInstance(url: String) {
        val base = SendInstances.normaliseBase(url) ?: return
        _state.update { it.copy(instance = base, config = it.instanceStatus[base]?.config, configFailed = false) }
        host.updateSettings { it.copy(instanceUrl = base) }
        probe(base, force = _state.value.instanceStatus[base]?.config == null)
        notice(SendNotice.Kind.INSTANCE_SET, SendInstances.displayHost(base))
    }

    private fun instanceAdd() {
        val base = SendInstances.normaliseBase(instanceDraft)
        if (base == null) {
            if (instanceDraft.isNotBlank()) notice(SendNotice.Kind.INSTANCE_BAD)
            return
        }
        instanceDraft = ""
        val own = (_state.value.ownInstances.filterNot { it == base } + base).takeLast(SendSettings.MAX_OWN_INSTANCES)
        _state.update { it.copy(ownInstances = own) }
        host.updateSettings { it.copy(ownInstances = own) }
        probe(base, force = true)
        pickInstance(base)
        notice(SendNotice.Kind.INSTANCE_ADDED, SendInstances.displayHost(base))
    }

    private fun instanceRemove(url: String) {
        val own = _state.value.ownInstances.filterNot { it == url }
        _state.update { it.copy(ownInstances = own) }
        host.updateSettings { it.copy(ownInstances = own) }
        if (_state.value.instance == url) pickInstance(SendInstances.DEFAULT_URL)
        notice(SendNotice.Kind.INSTANCE_REMOVED)
    }

    /** Asks [base] what it allows, unless a recent answer is in hand. */
    private fun probe(base: String, force: Boolean = false) {
        val known = _state.value.instanceStatus[base]
        if (!force && known != null && SystemClock.elapsedRealtime() - known.checkedAtMs < PROBE_TTL_MS) {
            if (base == _state.value.instance) _state.update { it.copy(config = known.config, configFailed = known.config == null) }
            return
        }
        scope.launch {
            val config = withContext(Dispatchers.IO) { runCatching { client.config(base) }.getOrNull() }
            val status = InstanceStatus(config, SystemClock.elapsedRealtime())
            _state.update { s ->
                s.copy(
                    instanceStatus = s.instanceStatus + (base to status),
                    config = if (s.instance == base) config else s.config,
                    configFailed = if (s.instance == base) config == null else s.configFailed,
                )
            }
        }
    }

    /** Every instance the sheet lists, a few at a time; stale answers only. */
    private fun probeAll() {
        if (probeJob?.isActive == true) return
        val urls = (_state.value.ownInstances + SendInstances.catalogue.map { it.url }).distinct()
        val now = SystemClock.elapsedRealtime()
        val due = urls.filter { url ->
            val known = _state.value.instanceStatus[url]
            known == null || now - known.checkedAtMs >= PROBE_TTL_MS
        }
        if (due.isEmpty()) return
        probeJob = scope.launch {
            val gate = Semaphore(PROBE_PARALLEL)
            due.map { url ->
                launch {
                    gate.withPermit {
                        val config = withContext(Dispatchers.IO) { runCatching { client.config(url) }.getOrNull() }
                        val status = InstanceStatus(config, SystemClock.elapsedRealtime())
                        _state.update { s ->
                            s.copy(
                                instanceStatus = s.instanceStatus + (url to status),
                                config = if (s.instance == url && config != null) config else s.config,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- Upload -----------------------------------------------------------

    private fun upload(then: SendDelivered?, showQr: Boolean) {
        val s = _state.value
        if (s.draft.isEmpty) {
            notice(SendNotice.Kind.NO_FILES)
            return
        }
        if (uploadJob?.isActive == true) return
        if (s.overCap) {
            _state.update {
                it.copy(link = LinkStage.Failed(SendError.TOO_BIG, bytes = s.draft.totalSize, suggestions = SendInstances.roomFor(s.draft.totalSize)))
            }
            return
        }
        when (host.metered()) {
            MeteredDecision.ALLOWED -> startUpload(then, showQr)
            MeteredDecision.ASK -> {
                pendingTransfer = { startUpload(then, showQr) }
                _state.update { it.copy(dataAsk = true) }
            }
            MeteredDecision.BLOCKED -> notice(SendNotice.Kind.DATA_SAVER)
        }
    }

    private fun startUpload(then: SendDelivered?, showQr: Boolean) {
        endTextIfAny()
        host.haptic()
        val s = _state.value
        val draft = s.draft
        val settings = host.settings
        val password = draft.effectivePassword
            ?: if (draft.passwordOn && settings.password == SendPasswordMode.GENERATE) generatedPassword() else null
        val config = s.config ?: com.wasimaster.wmkeyboard.core.send.SendServerConfig.DEFAULT
        val options = SendUploadOptions(
            timeLimitSeconds = draft.expireSeconds.coerceIn(1L, config.maxExpireSeconds),
            downloadLimit = draft.downloads.coerceIn(1, config.maxDownloads),
            password = password,
        )
        val files = draft.files.map { f -> SendFile(f.name, f.size, f.type) { openDraft(f) } }
        val total = Ece.encryptedSize(draft.totalSize)
        val name = if (files.size == 1) files[0].name else app.resources.getQuantityString(R.plurals.ime_send_archive_name, files.size, files.size)
        _state.update {
            it.copy(link = LinkStage.Uploading(0, total, SystemClock.elapsedRealtime(), files.size, name), sheet = null, dataAsk = false)
        }
        cancelRequested = false
        val instance = s.instance
        uploadJob = scope.launch {
            var lastTick = 0L
            var lastShade = 0L
            try {
                val upload = withContext(Dispatchers.IO) {
                    client.upload(
                        instance, files, options,
                        progress = { sent, tot ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTick >= PROGRESS_TICK_MS || sent == tot) {
                                lastTick = now
                                _state.update { st ->
                                    (st.link as? LinkStage.Uploading)?.let { u -> st.copy(link = u.copy(sent = sent, total = tot)) } ?: st
                                }
                                if (!host.keyboardVisible() && now - lastShade >= SHADE_TICK_MS) {
                                    lastShade = now
                                    notifyProgress(name, sent, tot)
                                }
                            }
                        },
                        cancelled = { cancelRequested },
                    )
                }
                if (settings.keepHistory && !host.incognito()) withContext(Dispatchers.IO) { store.add(upload) }
                val delivered = deliver(upload, then ?: settings.afterUpload.asDelivered())
                _state.update {
                    it.copy(
                        link = LinkStage.Done(upload, password, delivered),
                        draft = SendDraft(expireSeconds = it.draft.expireSeconds, downloads = it.draft.downloads, passwordOn = it.draft.passwordOn),
                        sheet = if (showQr) SendSheet.QR else null,
                        passwordVisible = false,
                    )
                }
                refreshHistory()
                draft.files.forEach { f -> f.file?.takeIf { it.parentFile?.name == CACHE_DIR }?.delete() }
                draft.files.forEach(::releaseGrant)
                if (!host.keyboardVisible()) notifyDone(upload, delivered) else WmNotifications.cancel(app, NotificationIds.SEND_UPLOAD)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything else (a provider's SecurityException, a runtime
                // failure under the client) is a failed upload, never a dead keyboard (#808).
                val error = (e as? SendException)?.error ?: SendError.SERVER
                WmNotifications.cancel(app, NotificationIds.SEND_UPLOAD)
                if (error == SendError.CANCELLED) {
                    _state.update { it.copy(link = LinkStage.Compose) }
                } else {
                    _state.update {
                        it.copy(
                            link = LinkStage.Failed(
                                error, (e as? SendException)?.status ?: 0, draft.totalSize,
                                suggestions = if (error == SendError.TOO_BIG) SendInstances.roomFor(draft.totalSize) else emptyList(),
                            ),
                        )
                    }
                    if (!host.keyboardVisible()) notifyFailed(name)
                }
            }
        }
    }

    private fun SendAfterUpload.asDelivered(): SendDelivered? = when (this) {
        SendAfterUpload.INSERT -> SendDelivered.INSERTED
        SendAfterUpload.COPY -> SendDelivered.COPIED
        SendAfterUpload.SHOW, SendAfterUpload.ASK -> null
    }

    /** Puts the finished link where the setting (or the hold menu) said; what it did, or null. */
    private fun deliver(upload: SendUpload, how: SendDelivered?): SendDelivered? = when (how) {
        SendDelivered.INSERTED -> if (host.hasField() && host.keyboardVisible()) {
            host.insertText(upload.url)
            SendDelivered.INSERTED
        } else {
            host.copyText(upload.url)
            SendDelivered.COPIED
        }
        SendDelivered.COPIED -> {
            host.copyText(upload.url)
            SendDelivered.COPIED
        }
        null -> null
    }

    private fun openDraft(f: SendDraftFile): InputStream = when {
        f.file != null -> FileInputStream(f.file)
        f.uri != null -> app.contentResolver.openInputStream(f.uri) ?: throw FileNotFoundException(f.uri.toString())
        else -> throw FileNotFoundException(f.name)
    }

    private fun cancel() {
        cancelRequested = true
        receiveJob?.cancel()
        if (uploadJob?.isActive != true) {
            _state.update { s ->
                s.copy(
                    link = when (val stage = s.link) {
                        is LinkStage.Receiving -> LinkStage.Receiving(stage.ui.copy(progress = null, checking = false))
                        else -> stage
                    },
                )
            }
        }
    }

    private fun doneUpload(): SendUpload? = (_state.value.link as? LinkStage.Done)?.upload

    private fun insertLink(upload: SendUpload) {
        if (!host.hasField()) {
            copyLink(upload)
            return
        }
        host.haptic()
        host.insertText(upload.url)
        _state.update { s -> s.copy(link = (s.link as? LinkStage.Done)?.takeIf { it.upload.id == upload.id }?.copy(delivered = SendDelivered.INSERTED) ?: s.link) }
        notice(SendNotice.Kind.INSERTED)
    }

    private fun copyLink(upload: SendUpload) {
        host.haptic()
        host.copyText(upload.url)
        _state.update { s -> s.copy(link = (s.link as? LinkStage.Done)?.takeIf { it.upload.id == upload.id }?.copy(delivered = SendDelivered.COPIED) ?: s.link) }
        notice(SendNotice.Kind.COPIED)
    }

    private fun shareLink(upload: SendUpload) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, upload.url)
        if (host.share(send)) notice(SendNotice.Kind.SHARED)
    }

    // ---- History ----------------------------------------------------------

    private fun refreshHistory() {
        scope.launch {
            val (uploads, received) = withContext(Dispatchers.IO) { store.uploads() to store.received() }
            _state.update { it.copy(uploads = uploads, received = received) }
        }
    }

    /** `/info` for [uploads]: how many downloads are used. A gone file leaves the history. */
    private fun refreshInfo(uploads: List<SendUpload>) {
        if (uploads.isEmpty()) return
        infoJob?.cancel()
        infoJob = scope.launch {
            val gate = Semaphore(PROBE_PARALLEL)
            uploads.forEach { upload ->
                launch {
                    gate.withPermit {
                        val result = withContext(Dispatchers.IO) { runCatching { client.info(upload.instance, upload.id, upload.ownerToken) } }
                        result.onSuccess { info ->
                            _state.update { it.copy(uploadInfo = it.uploadInfo + (upload.id to info)) }
                        }.onFailure { e ->
                            if ((e as? SendException)?.error == SendError.NOT_FOUND) forget(upload)
                        }
                    }
                }
            }
        }
    }

    private fun forget(upload: SendUpload) {
        scope.launch(Dispatchers.IO) { store.remove(upload) }
        _state.update { s ->
            s.copy(
                uploads = s.uploads.filterNot { it.id == upload.id && it.instance == upload.instance },
                uploadInfo = s.uploadInfo - upload.id,
                link = (s.link as? LinkStage.Done)?.takeIf { it.upload.id == upload.id }?.let { LinkStage.Compose } ?: s.link,
            )
        }
    }

    private fun deleteUpload(upload: SendUpload) {
        host.haptic()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { client.delete(upload.instance, upload.id, upload.ownerToken) }
                    .map { true }
                    .getOrElse { (it as? SendException)?.error == SendError.NOT_FOUND }
            }
            if (ok) {
                forget(upload)
                notice(SendNotice.Kind.DELETED)
            } else {
                notice(SendNotice.Kind.FAILED)
            }
        }
    }

    private fun setUploadDownloads(upload: SendUpload, limit: Int) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { client.setDownloadLimit(upload.instance, upload.id, upload.ownerToken, limit) }.isSuccess }
            if (!ok) {
                notice(SendNotice.Kind.FAILED)
                return@launch
            }
            val changed = upload.copy(downloadLimit = limit)
            withContext(Dispatchers.IO) { store.update(changed) }
            _state.update { s ->
                s.copy(
                    uploads = s.uploads.map { if (it.id == upload.id) changed else it },
                    link = (s.link as? LinkStage.Done)?.takeIf { it.upload.id == upload.id }?.copy(upload = changed) ?: s.link,
                )
            }
            refreshInfo(listOf(changed))
        }
    }

    // ---- Receiving --------------------------------------------------------

    /** A link from the chip, the paste box, or the clipboard: look it up. */
    fun receive(url: String) {
        val link = SendLink.parse(url) ?: SendLink.find(url)
        if (link == null) {
            notice(SendNotice.Kind.LINK_BAD)
            return
        }
        linkDraft = ""
        receivePassword = ""
        endTextIfAny()
        _state.update { it.copy(mode = SendMode.LINK, link = LinkStage.Receiving(ReceiveUi(link)), sheet = null) }
        check(link, password = null)
    }

    private fun check(link: SendLink, password: String?) {
        receiveJob?.cancel()
        updateReceive { it.copy(checking = true, error = null, wrongPassword = false) }
        receiveJob = scope.launch {
            try {
                val exists = withContext(Dispatchers.IO) { client.exists(link) }
                if (exists.requiresPassword && password == null) {
                    updateReceive { it.copy(checking = false, needsPassword = true) }
                    if (open) beginText(SendTextField.PASSWORD)
                    return@launch
                }
                val opened = withContext(Dispatchers.IO) { client.open(link, password, exists.nonce) }
                val remote = withContext(Dispatchers.IO) { opened.metadata() }
                session = opened
                updateReceive { it.copy(checking = false, remote = remote, needsPassword = exists.requiresPassword) }
            } catch (e: SendException) {
                when (e.error) {
                    SendError.WRONG_PASSWORD -> {
                        updateReceive { it.copy(checking = false, needsPassword = true, wrongPassword = true) }
                        if (open) beginText(SendTextField.PASSWORD)
                    }
                    else -> updateReceive { it.copy(checking = false, error = e.error) }
                }
            }
        }
    }

    private fun updateReceive(change: (ReceiveUi) -> ReceiveUi) {
        _state.update { s -> (s.link as? LinkStage.Receiving)?.let { s.copy(link = LinkStage.Receiving(change(it.ui))) } ?: s }
    }

    private fun download() {
        val ui = (_state.value.link as? LinkStage.Receiving)?.ui ?: return
        val remote = ui.remote ?: return
        val opened = session ?: return
        if (ui.progress != null) return
        when (host.metered()) {
            MeteredDecision.ALLOWED -> startDownload(opened, remote)
            MeteredDecision.ASK -> {
                pendingTransfer = { startDownload(opened, remote) }
                _state.update { it.copy(dataAsk = true) }
            }
            MeteredDecision.BLOCKED -> notice(SendNotice.Kind.DATA_SAVER)
        }
    }

    private fun startDownload(opened: SendClient.Session, remote: com.wasimaster.wmkeyboard.core.send.SendRemoteFile) {
        host.haptic()
        cancelRequested = false
        updateReceive { it.copy(progress = 0L to remote.metadata.size, error = null) }
        val name = remote.metadata.name
        receiveJob = scope.launch {
            var lastTick = 0L
            var lastShade = 0L
            try {
                val sink = SendFileSink(app)
                val locations = withContext(Dispatchers.IO) {
                    opened.download(
                        remote.metadata, sink,
                        progress = { done, total ->
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastTick >= PROGRESS_TICK_MS || done == total) {
                                lastTick = now
                                updateReceive { it.copy(progress = done to total) }
                                if (!host.keyboardVisible() && now - lastShade >= SHADE_TICK_MS) {
                                    lastShade = now
                                    notifyProgress(name, done, total, receiving = true)
                                }
                            }
                        },
                        cancelled = { cancelRequested },
                    )
                }
                val now = System.currentTimeMillis()
                val saved = remote.metadata.saved.zip(locations) { entry, location ->
                    SendReceived(entry.name, entry.size, entry.type, location, SendInstances.displayHost(remote.link.base), now)
                }
                if (host.settings.keepHistory && !host.incognito()) {
                    withContext(Dispatchers.IO) { saved.forEach(store::addReceived) }
                }
                session = null
                updateReceive { it.copy(progress = null, saved = saved) }
                refreshHistory()
                notice(SendNotice.Kind.SAVED, saved.size.toString())
                if (!host.keyboardVisible()) notifyReceived(saved) else WmNotifications.cancel(app, NotificationIds.SEND_RECEIVE)
                if (host.settings.openAfterDownload && saved.size == 1 && host.keyboardVisible()) openReceived(saved.single())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = (e as? SendException)?.error ?: SendError.SERVER
                WmNotifications.cancel(app, NotificationIds.SEND_RECEIVE)
                if (error == SendError.CANCELLED) {
                    updateReceive { it.copy(progress = null) }
                } else {
                    updateReceive { it.copy(progress = null, error = error) }
                    // A download that failed after the server counted it may be gone for good.
                    if (error == SendError.NOT_FOUND) session = null
                }
            }
        }
    }

    private fun openReceived(item: SendReceived) = openLocation(item.location, item.type)

    private fun openLocation(location: String, type: String) {
        val ok = runCatching {
            host.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.parse(location), type.ifBlank { "*/*" })
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!ok) notice(SendNotice.Kind.FAILED) else notice(SendNotice.Kind.OPENED)
    }

    private fun insertReceived(item: SendReceived) {
        val uri = runCatching { Uri.parse(item.location) }.getOrNull() ?: return
        if (host.commitUri(uri, item.type, item.name)) {
            notice(SendNotice.Kind.INSERTED)
        } else {
            notice(SendNotice.Kind.NOT_ACCEPTED)
        }
    }

    private fun shareReceived(item: SendReceived) {
        val uri = runCatching { Uri.parse(item.location) }.getOrNull() ?: return
        val send = Intent(Intent.ACTION_SEND)
            .setType(item.type.ifBlank { "*/*" })
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (host.share(send)) notice(SendNotice.Kind.SHARED)
    }

    // ---- Nearby -----------------------------------------------------------

    /** Whether the KDE Connect engine is wanted for this tool right now, and whether it should look for strangers. */
    private fun syncHub() {
        KdeConnectHub.attach(app)
        val s = _state.value
        val wanted = (open && s.mode == SendMode.NEARBY && s.nearbyAvailable) || directHeld || outgoingActive()
        KdeConnectHub.batch {
            if (wanted) KdeConnectHub.hold(KdeConnectHub.Reason.NEARBY) else KdeConnectHub.release(KdeConnectHub.Reason.NEARBY)
            KdeConnectHub.setBrowsing(KdeConnectHub.Reason.NEARBY, wanted && open)
        }
        if (wanted && hubJob?.isActive != true) {
            hubJob = scope.launch { KdeConnectHub.events.collect(::onHubEvent) }
        } else if (!wanted) {
            hubJob?.cancel()
            hubJob = null
        }
    }

    private fun outgoingActive(): Boolean = KdeConnectHub.state.value.devices.any { d ->
        d.transfers.any { it.outgoing && (it.state == KdeTransferState.WAITING || it.state == KdeTransferState.RUNNING) }
    }

    private fun onHubEvent(event: KdeEvent) {
        val direct = _state.value.nearby.direct
        when (event) {
            is KdeEvent.Paired -> when (direct) {
                is DirectUi.Hosting -> _state.update { it.copy(nearby = it.nearby.copy(direct = direct.copy(joined = event.deviceName), targetId = event.deviceId)) }
                is DirectUi.Joining -> {
                    _state.update { it.copy(nearby = it.nearby.copy(direct = direct.copy(step = JoinStep.LINKED), targetId = event.deviceId)) }
                    // The draft was for this phone: off it goes.
                    if (!_state.value.draft.isEmpty) sendNearby(event.deviceId)
                }
                else -> Unit
            }
            is KdeEvent.PairFailed -> if (direct is DirectUi.Joining) {
                _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Failed(DirectFailure.JOIN_FAILED))) }
            }
            is KdeEvent.FileReceived -> if (host.settings.keepHistory && !host.incognito()) {
                val item = SendReceived(event.fileName, -1, mimeOf(event.fileName), event.location, event.deviceName, System.currentTimeMillis())
                scope.launch(Dispatchers.IO) { store.addReceived(item) }
                _state.update { it.copy(received = listOf(item) + it.received.filterNot { r -> r.location == item.location }) }
            }
            else -> Unit
        }
    }

    private fun sendNearby(deviceId: String) {
        val draft = _state.value.draft
        if (draft.isEmpty) {
            notice(SendNotice.Kind.NO_FILES)
            return
        }
        val uris = draft.files.mapNotNull { f -> f.uri ?: f.file?.let(host::fileUri) }
        if (uris.isEmpty()) {
            notice(SendNotice.Kind.FAILED)
            return
        }
        host.haptic()
        val queued = KdeConnectHub.send(app, deviceId, uris, null)
        if (queued == 0) {
            notice(SendNotice.Kind.FAILED)
            return
        }
        val name = KdeConnectHub.state.value.device(deviceId)?.name.orEmpty()
        _state.update { it.copy(nearby = it.nearby.copy(targetId = deviceId, sentTo = name), draft = it.draft.copy(files = emptyList())) }
        notice(SendNotice.Kind.SENT, name)
        syncHub()
    }

    // ---- Direct link --------------------------------------------------------

    private fun hostDirect() {
        // One Wi-Fi at a time: the direct link takes over the one the browser card opened.
        if (browserOwnsHotspot) stopBrowser()
        if (!host.hasNearbyPermission()) {
            pendingDirectHost = true
            host.askNearbyPermission()
            return
        }
        val link = host.directLink()
        if (!link.canHost()) {
            _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Failed(DirectFailure.UNSUPPORTED))) }
            return
        }
        host.haptic()
        directHeld = true
        syncHub()
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Starting), sheet = null) }
        link.host(object : DirectLink.Listener {
            override fun onHosting(ssid: String, passphrase: String, hostAddress: String, band: String) {
                scope.launch { onHostingUp(ssid, passphrase, hostAddress, band) }
            }

            override fun onJoined(network: Network?) = Unit

            override fun onFailed(reason: DirectFailure) {
                scope.launch { directFailed(reason) }
            }

            override fun onStopped() {
                scope.launch { directStopped() }
            }
        })
    }

    private suspend fun onHostingUp(ssid: String, passphrase: String, hostAddress: String, band: String) {
        // The engine must be up to be announced to; the hub brings it up for the NEARBY hold.
        val engine = awaitEngine() ?: run {
            directFailed(DirectFailure.GROUP_FAILED)
            return
        }
        val hub = KdeConnectHub.state.value
        val code = SendDirectCode(
            ssid = ssid,
            passphrase = passphrase,
            host = hostAddress,
            port = engine.tcpPort.takeIf { it > 0 } ?: DEFAULT_TCP_PORT,
            deviceId = hub.selfId,
            fingerprint = hub.selfFingerprint,
            token = engine.issuePairToken(),
            name = hub.selfName,
            band = band,
        )
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Hosting(code)), sheet = SendSheet.DIRECT_CODE) }
    }

    /** The engine, once the hub has started it for us; null when it will not run (tool off, locked, no permission). */
    private suspend fun awaitEngine(): com.wasimaster.wmkeyboard.core.kdeconnect.KdeConnectEngine? {
        repeat(ENGINE_WAIT_TICKS) {
            KdeConnectHub.engine?.takeIf { it.isRunning }?.let { return it }
            delay(ENGINE_WAIT_TICK_MS)
        }
        return KdeConnectHub.engine
    }

    /** A code scanned from the other phone: join its Wi-Fi, find it, pair with its token. */
    fun joinDirect(codeText: String) {
        val code = SendDirectCode.parse(codeText)
        if (code == null) {
            notice(SendNotice.Kind.LINK_BAD)
            return
        }
        if (!host.hasNearbyPermission()) {
            host.askNearbyPermission()
            return
        }
        if (browserOwnsHotspot) stopBrowser()
        host.haptic()
        directHeld = true
        _state.update { it.copy(mode = SendMode.NEARBY, nearby = it.nearby.copy(direct = DirectUi.Joining(code, JoinStep.WIFI)), sheet = null) }
        syncHub()
        host.directLink().join(code.ssid, code.passphrase, object : DirectLink.Listener {
            override fun onHosting(ssid: String, passphrase: String, hostAddress: String, band: String) = Unit

            override fun onJoined(network: Network?) {
                scope.launch { onJoinedWifi(code, network) }
            }

            override fun onFailed(reason: DirectFailure) {
                scope.launch { directFailed(reason) }
            }

            override fun onStopped() {
                scope.launch { directStopped() }
            }
        })
    }

    private suspend fun onJoinedWifi(code: SendDirectCode, network: Network?) {
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Joining(code, JoinStep.FINDING))) }
        val engine = awaitEngine() ?: run {
            directFailed(DirectFailure.NOT_FOUND)
            return
        }
        engine.socketBinder = network?.let(::NetworkBinder)
        engine.announceTo(code.host)
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Joining(code, JoinStep.PAIRING))) }
        val paired = engine.pairByCode(KdePairCode(code.host, code.port, code.deviceId, code.fingerprint, code.token, code.name))
        if (!paired) {
            directFailed(DirectFailure.NOT_FOUND)
            return
        }
        directJob?.cancel()
        directJob = scope.launch {
            delay(DIRECT_PAIR_TIMEOUT_MS)
            val direct = _state.value.nearby.direct
            if (direct is DirectUi.Joining && direct.step != JoinStep.LINKED) directFailed(DirectFailure.TIMEOUT)
        }
    }

    /** Sockets bound to the direct network, so a phone with mobile data does not route past it. */
    private class NetworkBinder(private val network: Network) : KdeSocketBinder {
        override fun bindOutgoing(socket: java.net.Socket) {
            runCatching { network.bindSocket(socket) }
        }

        override fun bindDatagram(socket: java.net.DatagramSocket) {
            runCatching { network.bindSocket(socket) }
        }
    }

    private fun directFailed(reason: DirectFailure) {
        directJob?.cancel()
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Failed(reason))) }
        host.directLink().stop()
        KdeConnectHub.engine?.socketBinder = null
        directHeld = false
        syncHub()
        browserLostBorrowedWifi()
    }

    private fun directStopped() {
        directJob?.cancel()
        KdeConnectHub.engine?.socketBinder = null
        directHeld = false
        _state.update { it.copy(nearby = it.nearby.copy(direct = DirectUi.Off), sheet = if (it.sheet == SendSheet.DIRECT_CODE) null else it.sheet) }
        syncHub()
        browserLostBorrowedWifi()
    }

    private fun stopDirect() {
        host.haptic()
        host.directLink().stop()
        directStopped()
    }

    // ---- In a browser (#927) -------------------------------------------------

    /**
     * Serves the draft to any browser that can reach [address]: this phone's
     * Wi-Fi by default, or the [hotspot] it opened. Already serving, the page
     * stays where it is and only the address and the code change.
     */
    private fun startBrowser(hotspot: BrowserHotspot? = null, address: String? = null) {
        host.haptic()
        scope.launch {
            val at = address ?: withContext(Dispatchers.IO) { LanShare.lanAddress() }
            if (at == null) {
                browserFailed(BrowserFailure.NO_NETWORK)
                return@launch
            }
            val share = lanShare ?: LanShare(SendFileSink(app), lanListener, deviceName()).also { fresh ->
                val started = withContext(Dispatchers.IO) { runCatching { fresh.start() }.isSuccess }
                if (!started) {
                    browserFailed(BrowserFailure.SERVER)
                    return@launch
                }
                lanShare = fresh
                lanSeenAt = SystemClock.elapsedRealtime()
                followDraft(fresh)
            }
            val uploads = (_state.value.nearby.browser as? BrowserUi.Serving)?.uploads ?: true
            share.uploadsAllowed = uploads
            _state.update { s ->
                val transfers = (s.nearby.browser as? BrowserUi.Serving)?.transfers.orEmpty()
                s.copy(
                    nearby = s.nearby.copy(browser = BrowserUi.Serving(share.url(at), hotspot, uploads, transfers)),
                    sheet = SendSheet.BROWSER_CODE,
                )
            }
        }
    }

    /** Keeps the page's list the draft's, and puts the page away after [BROWSER_IDLE_MS] with nobody on it. */
    private fun followDraft(share: LanShare) {
        lanJob?.cancel()
        lanJob = scope.launch {
            launch {
                state.map { it.draft.files }.distinctUntilChanged().collect { files ->
                    share.files = files.map { f -> SendFile(f.name, f.size, f.type) { openDraft(f) } }
                }
            }
            while (true) {
                delay(BROWSER_IDLE_CHECK_MS)
                val busy = (_state.value.nearby.browser as? BrowserUi.Serving)?.transfers.orEmpty().any { it.state == LanTransfer.State.RUNNING }
                if (!busy && SystemClock.elapsedRealtime() - lanSeenAt > BROWSER_IDLE_MS) {
                    stopBrowser()
                    break
                }
            }
        }
    }

    private val lanListener = object : LanShare.Listener {
        override fun onVisit(peer: String) {
            lanSeenAt = SystemClock.elapsedRealtime()
        }

        override fun onTransfer(transfer: LanTransfer) {
            lanSeenAt = SystemClock.elapsedRealtime()
            if (transfer.state == LanTransfer.State.RUNNING && transfer.done > 0) {
                if (lanSeenAt - lanTickAt < PROGRESS_TICK_MS) return
                lanTickAt = lanSeenAt
            }
            scope.launch { onBrowserTransfer(transfer) }
        }
    }

    private fun onBrowserTransfer(t: LanTransfer) {
        updateServing { b ->
            val list = if (b.transfers.any { it.id == t.id }) b.transfers.map { if (it.id == t.id) t else it } else listOf(t) + b.transfers
            b.copy(transfers = list.take(BROWSER_TRANSFERS))
        }
        val location = t.location
        if (t.incoming && t.state == LanTransfer.State.DONE && location != null) {
            val item = SendReceived(t.name, t.size, t.type, location, t.peer, System.currentTimeMillis())
            if (host.settings.keepHistory && !host.incognito()) {
                scope.launch(Dispatchers.IO) { store.addReceived(item) }
                _state.update { it.copy(received = listOf(item) + it.received.filterNot { r -> r.location == item.location }) }
            }
            notice(SendNotice.Kind.SAVED, "1")
        }
    }

    private fun updateServing(change: (BrowserUi.Serving) -> BrowserUi.Serving) {
        _state.update { s -> (s.nearby.browser as? BrowserUi.Serving)?.let { s.copy(nearby = s.nearby.copy(browser = change(it))) } ?: s }
    }

    /**
     * A Wi-Fi for a browser that is on no network with this phone: the direct
     * link's when one is up, otherwise one opened the same way (a Wi-Fi Direct
     * group, or a local-only hotspot), which an ordinary phone or laptop joins
     * like any other Wi-Fi.
     */
    private fun browserHotspot() {
        when (val direct = _state.value.nearby.direct) {
            is DirectUi.Hosting -> {
                startBrowser(BrowserHotspot(direct.code.ssid, direct.code.passphrase), direct.code.host)
                return
            }
            DirectUi.Off, is DirectUi.Failed -> Unit
            // Joining another phone's Wi-Fi: this phone has an address on it already.
            else -> {
                startBrowser()
                return
            }
        }
        if (!host.hasNearbyPermission()) {
            pendingBrowserHotspot = true
            host.askNearbyPermission()
            return
        }
        val link = host.directLink()
        if (!link.canHost()) {
            browserFailed(BrowserFailure.HOTSPOT, DirectFailure.UNSUPPORTED)
            return
        }
        host.haptic()
        browserOwnsHotspot = true
        _state.update { it.copy(nearby = it.nearby.copy(browser = BrowserUi.Starting, direct = DirectUi.Off), sheet = null) }
        link.host(object : DirectLink.Listener {
            override fun onHosting(ssid: String, passphrase: String, hostAddress: String, band: String) {
                scope.launch { if (browserOwnsHotspot) startBrowser(BrowserHotspot(ssid, passphrase), hostAddress) }
            }

            override fun onJoined(network: Network?) = Unit

            override fun onFailed(reason: DirectFailure) {
                scope.launch {
                    browserOwnsHotspot = false
                    browserFailed(BrowserFailure.HOTSPOT, reason)
                }
            }

            override fun onStopped() {
                scope.launch {
                    if (browserOwnsHotspot) {
                        browserOwnsHotspot = false
                        browserLostBorrowedWifi(force = true)
                    }
                }
            }
        })
    }

    /**
     * The Wi-Fi under the page went away: carry on over this phone's own Wi-Fi
     * if it has one, otherwise stop. [force] for the card's own hotspot; without
     * it, only a page that borrowed the direct link's Wi-Fi is touched.
     */
    private fun browserLostBorrowedWifi(force: Boolean = false) {
        val serving = _state.value.nearby.browser as? BrowserUi.Serving ?: return
        if (serving.hotspot == null || (!force && browserOwnsHotspot)) return
        val share = lanShare ?: return
        scope.launch {
            val at = withContext(Dispatchers.IO) { LanShare.lanAddress() }
            if (at == null) {
                stopBrowser()
            } else {
                updateServing { it.copy(url = share.url(at), hotspot = null) }
            }
        }
    }

    private fun browserFailed(reason: BrowserFailure, hotspot: DirectFailure? = null) {
        stopBrowser()
        _state.update { it.copy(nearby = it.nearby.copy(browser = BrowserUi.Failed(reason, hotspot))) }
    }

    private fun stopBrowser() {
        lanJob?.cancel()
        lanJob = null
        val share = lanShare
        lanShare = null
        if (share != null) scope.launch(Dispatchers.IO) { share.stop() }
        if (browserOwnsHotspot) {
            browserOwnsHotspot = false
            host.directLink().stop()
        }
        pendingBrowserHotspot = false
        _state.update {
            it.copy(
                nearby = it.nearby.copy(browser = BrowserUi.Off),
                sheet = if (it.sheet == SendSheet.BROWSER_CODE) null else it.sheet,
            )
        }
    }

    /** The name the page puts at its top: the one KDE Connect announces, or the phone's model. */
    private fun deviceName(): String =
        KdeConnectHub.state.value.selfName.ifBlank { android.os.Build.MODEL.orEmpty() }

    /** Called when the Nearby transfer list empties, so a direct link kept only for it can go. */
    fun onTransfersIdle() {
        if (!host.settings.keepDirectLink && _state.value.nearby.direct is DirectUi.Joining && !open) stopDirect()
    }

    // ---- Notices and the shade ---------------------------------------------

    private fun notice(kind: SendNotice.Kind, text: String = "") {
        _state.update { it.copy(notice = SendNotice(kind, text)) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { s -> if (s.notice?.kind == kind) s.copy(notice = null) else s }
        }
    }

    private fun notifyProgress(name: String, done: Long, total: Long, receiving: Boolean = false) {
        val id = if (receiving) NotificationIds.SEND_RECEIVE else NotificationIds.SEND_UPLOAD
        WmNotifications.post(app, NotificationKind.SEND, id) {
            setContentTitle(app.getString(if (receiving) R.string.ime_send_notify_receiving else R.string.ime_send_notify_uploading, name))
            setOnlyAlertOnce(true)
            setOngoing(true)
            if (total > 0) setProgress(PROGRESS_MAX, (done * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX), false)
            else setProgress(0, 0, true)
        }
    }

    private fun notifyDone(upload: SendUpload, delivered: SendDelivered?) {
        val copy = PendingIntent.getBroadcast(
            app, 1,
            Intent(SendActionReceiver.ACTION_COPY_LINK).setPackage(app.packageName).putExtra(SendActionReceiver.EXTRA_URL, upload.url),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        WmNotifications.post(app, NotificationKind.SEND, NotificationIds.SEND_UPLOAD) {
            setContentTitle(app.getString(R.string.ime_send_notify_done_title, upload.name))
            setContentText(
                app.getString(
                    if (delivered == SendDelivered.COPIED) R.string.ime_send_notify_done_copied else R.string.ime_send_notify_done_body,
                ),
            )
            setAutoCancel(true)
            setOngoing(false)
            addAction(0, app.getString(R.string.ime_send_notify_copy_link), copy)
            WmNotifications.launchIntent(app)?.let {
                setContentIntent(PendingIntent.getActivity(app, 2, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    private fun notifyFailed(name: String) {
        WmNotifications.post(app, NotificationKind.SEND, NotificationIds.SEND_UPLOAD) {
            setContentTitle(app.getString(R.string.ime_send_notify_failed_title, name))
            setContentText(app.getString(R.string.ime_send_notify_failed_body))
            setAutoCancel(true)
            setOngoing(false)
        }
    }

    private fun notifyReceived(saved: List<SendReceived>) {
        val first = saved.firstOrNull() ?: return
        val open = PendingIntent.getBroadcast(
            app, 3,
            Intent(SendActionReceiver.ACTION_OPEN_FILE).setPackage(app.packageName)
                .putExtra(SendActionReceiver.EXTRA_LOCATION, first.location)
                .putExtra(SendActionReceiver.EXTRA_TYPE, first.type),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        WmNotifications.post(app, NotificationKind.SEND, NotificationIds.SEND_RECEIVE) {
            setContentTitle(
                if (saved.size == 1) app.getString(R.string.ime_send_notify_received_title, first.name)
                else app.resources.getQuantityString(R.plurals.ime_send_notify_received_many, saved.size, saved.size),
            )
            setContentText(app.getString(R.string.ime_send_notify_received_body))
            setAutoCancel(true)
            setOngoing(false)
            setContentIntent(open)
        }
    }

    private fun mimeOf(fileName: String): String =
        android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(fileName.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    companion object {
        const val PASSWORD_MAX = 128
        const val LINK_MAX = 512
        const val INSTANCE_MAX = 256
        private const val CACHE_DIR = "send"
        private const val GENERATED_PASSWORD_LENGTH = 12
        private const val PASSWORD_ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val PROBE_TTL_MS = 12 * 3_600_000L
        private const val PROBE_PARALLEL = 6
        private const val INFO_BATCH = 10
        private const val PROGRESS_TICK_MS = 80L
        private const val SHADE_TICK_MS = 1_000L
        private const val PROGRESS_MAX = 1000
        private const val NOTICE_MS = 3_500L
        private const val DEFAULT_TCP_PORT = 1716
        private const val ENGINE_WAIT_TICKS = 40
        private const val ENGINE_WAIT_TICK_MS = 100L
        private const val DIRECT_PAIR_TIMEOUT_MS = 45_000L
        private const val BROWSER_IDLE_MS = 10 * 60_000L
        private const val BROWSER_IDLE_CHECK_MS = 30_000L
        private const val BROWSER_TRANSFERS = 20
    }
}
