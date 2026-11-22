package com.wasimaster.wmkeyboard.ime.kdeconnect

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeClipboardPause
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeConnectEngine
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceNames
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDeviceType
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeEngineConfig
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeEvent
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeMediaAction
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePairCode
import com.wasimaster.wmkeyboard.core.kdeconnect.KdePhoneMedia
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeState
import com.wasimaster.wmkeyboard.core.media.MediaControlManager
import com.wasimaster.wmkeyboard.core.media.MediaSnapshot
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpBus
import com.wasimaster.wmkeyboard.core.settings.KdeConnectSettings
import com.wasimaster.wmkeyboard.core.settings.BackupDestination
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.KdeLinkLifetime
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.sink.NearbySync
import com.wasimaster.wmkeyboard.core.settings.sync.SyncNaming
import com.wasimaster.wmkeyboard.core.settings.sync.SyncRunner
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * The one KDE Connect engine of this process, and the rules for when it runs.
 *
 * A singleton for the reason `AiChatController` is: the keyboard, the settings
 * screens and the share activity all live in one process and must see one set
 * of paired devices, one link, one pairing in progress. Whoever needs the
 * engine [hold]s it under a reason and [release]s it after; the hub works out
 * from the reasons held and [KdeConnectSettings.lifetime] whether the engine
 * should be up.
 *
 * ## When it runs
 *
 * Only when all of these hold: the tool is switched on; the device has been
 * unlocked since boot (the identity lives in credential-encrypted storage);
 * the lifetime setting is satisfied by a reason currently held; and there is
 * something to do — a paired device to reach, or the user looking for one.
 * A keyboard with nothing paired and nobody browsing opens no socket at all.
 *
 * All bookkeeping is on the main thread. Starting and stopping the engine binds
 * sockets and may mint a key pair, so that part runs on one background worker,
 * in order.
 */
object KdeConnectHub {

    /** Why the engine is wanted right now. */
    enum class Reason {
        /** The input-method service exists. Enough only under [KdeLinkLifetime.ALWAYS]. */
        SERVICE,

        /** The keyboard is on screen. Enough under [KdeLinkLifetime.KEYBOARD] and ALWAYS. */
        KEYBOARD,

        /**
         * The keyboard is hidden over the field it was shown for, which a
         * computer may still type into (#1511). Enough where [KEYBOARD] is, but
         * it is not the keyboard being up: requests still go to a notification.
         */
        FIELD,

        /** The tool's panel is open, a settings screen is showing devices, or a share is in flight. Always enough. */
        PANEL, SETTINGS, SHARE,

        /** The Send files tool's Nearby mode is up: its device list, or a direct link between two phones. Always enough. */
        NEARBY,
    }

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Sync passes and mailbox files: disk and network, never the main thread. */
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(KdeState())
    val state: StateFlow<KdeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<KdeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<KdeEvent> = _events.asSharedFlow()

    private var app: Context? = null
    private var settings = KdeConnectSettings()
    private val reasons = HashSet<Reason>()
    private val browsers = HashSet<Reason>()
    private var keyboardLeftAtMs = 0L
    private var lingerJob: Job? = null

    @Volatile var engine: KdeConnectEngine? = null
        private set

    private var collectors: List<Job> = emptyList()
    private var wired = false

    /** Open [batch] calls; while above zero, [reconcile] only notes that it is owed. */
    private var batchDepth = 0
    private var reconcileOwed = false

    /** What [syncShareAlias] last set the alias to, so an unchanged answer costs no binder call. */
    private var shareAliasState: Int? = null

    /** Whether the keyboard is on screen: decides between showing something in the panel and posting a notification. */
    private val keyboardUp: Boolean get() = Reason.KEYBOARD in reasons

    /**
     * Runs [block] and reconciles once at the end, however many holds,
     * releases and settings it changed. The keyboard's sync touches four of
     * them on every show and hide, and each one alone reconciles — with a
     * package-manager call and a config rebuild each time — on the main thread.
     */
    fun batch(block: () -> Unit) {
        batchDepth++
        try {
            block()
        } finally {
            batchDepth--
            if (batchDepth == 0 && reconcileOwed) {
                reconcileOwed = false
                reconcile()
            }
        }
    }

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    fun applySettings(next: KdeConnectSettings) {
        if (next == settings) return
        val resumed = settings.clipboardPauses.isNotEmpty() &&
            KdeClipboardPause.live(next.clipboardPauses, System.currentTimeMillis()).isEmpty()
        settings = next
        // Resumed from the panel or the settings: the shade's "paused" row is stale.
        if (resumed) app?.let { WmNotifications.cancel(it, NotificationIds.CONNECT_PAUSE) }
        reconcile()
    }

    /**
     * Holds clipboard sharing with [deviceId], or with every computer when
     * null, for [minutes] (0: until it is turned back on), #918. Written to
     * settings, so the pause outlives the process; the engine sees it once
     * the new settings reach [applySettings].
     */
    fun pauseClipboard(context: Context, deviceId: String?, minutes: Int) {
        val now = System.currentTimeMillis()
        val until = KdeClipboardPause.endFor(minutes, now)
        val appContext = context.applicationContext
        val name = deviceId?.let { state.value.device(it)?.name }
        io.launch {
            SettingsRepository(appContext).updateKdeClipboardPauses { KdeClipboardPause.pause(it, deviceId, until, now) }
        }
        notifyClipboardPaused(appContext, deviceId, name, until)
    }

    /** Sharing back on with [deviceId], or with everything when null. [done] runs once it is written. */
    fun resumeClipboard(context: Context, deviceId: String?, done: () -> Unit = {}) {
        val appContext = context.applicationContext
        io.launch {
            try {
                SettingsRepository(appContext).updateKdeClipboardPauses {
                    KdeClipboardPause.resume(it, deviceId, System.currentTimeMillis())
                }
            } finally {
                done()
            }
        }
    }

    fun hold(reason: Reason) {
        if (reasons.add(reason)) {
            if (reason == Reason.KEYBOARD) {
                lingerJob?.cancel()
                // A link that has been silent since the keyboard last went
                // away may be dead without either end knowing yet.
                engine?.probe()
            }
            reconcile()
        }
    }

    fun release(reason: Reason) {
        if (!reasons.remove(reason)) return
        browsers.remove(reason)
        if (reason == Reason.KEYBOARD) {
            keyboardLeftAtMs = System.currentTimeMillis()
            lingerJob?.cancel()
            lingerJob = main.launch {
                delay(KEYBOARD_LINGER_MS + 250)
                reconcile()
            }
        }
        reconcile()
    }

    /** [who] is showing (or no longer showing) a list of nearby devices. */
    fun setBrowsing(who: Reason, on: Boolean) {
        val changed = if (on) browsers.add(who) else browsers.remove(who)
        if (changed) reconcile()
    }

    /** True once a computer has been paired; false before the first unlock, when that cannot be known. */
    fun hasPairedDevices(): Boolean = engineOrNull()?.hasPairedDevices() == true

    /**
     * Pairs with the computer whose pairing code was scanned
     * (`wmkeyboard://pair?...`): dials it directly and pairs without a prompt
     * on the computer. Hold [Reason.SETTINGS] while it runs; the engine comes
     * up for it even with nothing paired yet. False for a link that is not a
     * pairing code, or when the engine cannot run.
     */
    fun pairByCode(link: String): Boolean {
        val code = KdePairCode.parse(link) ?: return false
        reasons += Reason.SETTINGS
        browsers += Reason.SETTINGS
        reconcile()
        val current = engineOrNull() ?: return false
        main.launch(worker) {
            if (current.start()) current.pairByCode(code)
        }
        return true
    }

    /** Announce now. The keyboard just came up, the network changed, Refresh was pressed. */
    fun refresh() {
        engine?.announce()
    }

    /**
     * The connected computers, waiting up to [timeoutMs] for the first one: what
     * a share from another app needs, since the link is usually down when it
     * arrives and takes a second or two to come up. Hold [Reason.SHARE] first.
     */
    suspend fun awaitConnected(timeoutMs: Long): List<com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice> =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            state.first { it.connected.isNotEmpty() }
        }?.connected.orEmpty()

    /** [deviceId] once it is connected, or null if it is not within [timeoutMs]. */
    suspend fun awaitConnected(deviceId: String, timeoutMs: Long): com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice? =
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            state.first { s -> s.connected.any { it.id == deviceId } }
        }?.device(deviceId)

    /**
     * Sends [uris] (and [text], if any) to [deviceId]. Returns how many files
     * were queued; the caller keeps whatever granted it the URIs alive until
     * [activeOutgoing] drops to zero.
     */
    fun send(context: Context, deviceId: String, uris: List<Uri>, text: String?): Int {
        val current = engine ?: return 0
        if (!text.isNullOrBlank()) {
            val trimmed = text.trim()
            val isUrl = trimmed.startsWith("http://") || trimmed.startsWith("https://")
            if (isUrl && !trimmed.any { it.isWhitespace() }) current.share.sendUrl(deviceId, trimmed) else current.share.sendText(deviceId, text)
        }
        val files = uris.mapNotNull { outgoingFile(context, it) }
        if (files.isNotEmpty()) current.share.sendFiles(deviceId, files)
        return files.size
    }

    fun activeOutgoing(deviceId: String): Int = state.value.device(deviceId)?.transfers.orEmpty().count {
        it.outgoing && (it.state == com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState.WAITING ||
            it.state == com.wasimaster.wmkeyboard.core.kdeconnect.KdeTransferState.RUNNING)
    }

    private fun wanted(): Boolean {
        val context = app ?: return false
        if (!settings.enabled || !DirectBoot.isUserUnlocked(context)) return false
        // Every KDE Connect socket needs the internet permission, LAN or not (#292).
        if (!InternetPermission.granted) return false
        if (reasons.any { it == Reason.PANEL || it == Reason.SETTINGS || it == Reason.SHARE || it == Reason.NEARBY }) return true
        val lingering = System.currentTimeMillis() - keyboardLeftAtMs < KEYBOARD_LINGER_MS
        return when (settings.lifetime) {
            KdeLinkLifetime.PANEL -> false
            KdeLinkLifetime.KEYBOARD -> Reason.KEYBOARD in reasons || Reason.FIELD in reasons || lingering
            KdeLinkLifetime.ALWAYS -> Reason.SERVICE in reasons || Reason.KEYBOARD in reasons ||
                Reason.FIELD in reasons || lingering
        }
    }

    private fun engineOrNull(): KdeConnectEngine? {
        engine?.let { return it }
        val context = app ?: return null
        if (!DirectBoot.isUserUnlocked(context)) return null
        val made = KdeConnectEngine(
            dir = File(context.filesDir, DIR),
            mdns = AndroidMdns(context),
            fileSink = KdeReceivedFiles(context),
            fetchArt = ::fetchAlbumArt,
            traffic = KdeNetMeter,
        )
        engine = made
        // A Nearby device location's writes go out through this engine.
        NearbySync.transport = NearbySync.Transport { id, name, bytes -> made.sync.send(id, name, bytes) }
        collectors = listOf(
            main.launch { made.state.collect { _state.value = it } },
            main.launch { made.events.collect(::onEvent) },
            // A code from a notification goes to the computer as it arrives.
            main.launch {
                NotificationOtpBus.latest.collect { otp ->
                    if (otp != null) made.codes.send(otp.code, otp.sourceApp, otp.postedAt)
                }
            },
        )
        return made
    }

    private fun reconcile() {
        if (batchDepth > 0) {
            reconcileOwed = true
            return
        }
        val context = app ?: return
        val browsing = browsers.isNotEmpty()
        if (!wanted()) {
            val current = engine ?: return
            unwire(context)
            main.launch(worker) { current.stop() }
            return
        }
        val current = engineOrNull() ?: return
        current.configure(settings.toConfig(context))
        val run = browsing || current.hasPairedDevices()
        if (run) wire(context) else unwire(context)
        main.launch(worker) {
            if (run) {
                if (current.start()) current.setDiscovering(browsing)
            } else {
                current.stop()
            }
        }
        syncShareAlias(context, current.hasPairedDevices())
        syncPhoneMedia(context)
    }

    // ---- the Android things an engine on the network needs around it ----

    private var multicast: WifiManager.MulticastLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var media: MediaControlManager? = null

    private fun wire(context: Context) {
        if (wired) return
        wired = true
        // Without the lock most phones drop multicast and broadcast datagrams
        // addressed to them while the screen is on and Wi-Fi is idle.
        runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("wmkb-kdeconnect")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    engine?.announce()
                }
            }
            cm?.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    intent?.let(::reportBattery)
                }
            }
            // Sticky: registering hands back the current reading at once.
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )?.let(::reportBattery)
            batteryReceiver = receiver
        }
    }

    private fun unwire(context: Context) {
        if (!wired) return
        wired = false
        runCatching { multicast?.release() }
        multicast = null
        networkCallback?.let { cb ->
            runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
        }
        networkCallback = null
        batteryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        batteryReceiver = null
        media?.stop()
        media = null
    }

    private fun reportBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val charge = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        engine?.battery?.setLocal(charge, plugged, low = charge <= LOW_BATTERY && !plugged)
    }

    /**
     * The phone's media, offered to the computer. A second [MediaControlManager]
     * beside the media tool's own: that class hands its frames to one callback,
     * and the two consumers start and stop for unrelated reasons.
     */
    private fun syncPhoneMedia(context: Context) {
        val want = wired && settings.exposeMedia
        if (!want) {
            media?.stop()
            media = null
            engine?.phoneMedia?.setMedia(null)
            return
        }
        if (media != null) return
        val manager = MediaControlManager(context)
        // Without notification access the platform hands over no sessions.
        // Nothing is asked for here: the settings page has the row for it.
        if (!manager.hasAccess()) return
        media = manager
        manager.start { snapshot -> engine?.phoneMedia?.setMedia(snapshot?.toPhoneMedia(context)) }
    }

    private fun MediaSnapshot.toPhoneMedia(context: Context): KdePhoneMedia {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1
        val now = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        return KdePhoneMedia(
            player = appLabel.ifBlank { packageName }.ifBlank { "Phone" },
            title = title,
            artist = artist,
            album = album,
            playing = playing,
            positionMs = positionMs,
            lengthMs = if (durationMs > 0) durationMs else -1,
            volume = now * 100 / max,
            canNext = canNext,
            canPrevious = canPrev,
            canSeek = canSeek,
        )
    }

    private fun onPhoneMediaCommand(context: Context, command: KdeEvent.PhoneMediaCommand) {
        val manager = media ?: return
        when (command.action) {
            KdeMediaAction.PLAY, KdeMediaAction.PAUSE, KdeMediaAction.PLAY_PAUSE, KdeMediaAction.STOP -> manager.playPause()
            KdeMediaAction.NEXT -> manager.next()
            KdeMediaAction.PREVIOUS -> manager.previous()
            null -> Unit
        }
        command.seekToMs?.let { manager.seekTo(it) }
        command.volume?.let { percent ->
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@let
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0) }
        }
    }

    // ---- events ----

    private suspend fun onEvent(event: KdeEvent) {
        val context = app ?: return
        when (event) {
            is KdeEvent.PhoneMediaCommand -> onPhoneMediaCommand(context, event)
            is KdeEvent.Paired, is KdeEvent.Unpaired -> reconcile()
            is KdeEvent.PairRequested -> if (Reason.PANEL !in reasons && Reason.SETTINGS !in reasons) notifyPairRequest(context, event)
            is KdeEvent.PairFailed -> WmNotifications.cancel(context, NotificationIds.CONNECT_PAIR)
            is KdeEvent.FileReceived -> if (!keyboardUp) notifyFile(context, event)
            is KdeEvent.SyncReachable -> io.launch { syncWith(context, event.deviceId, event.deviceName, ask = true) }
            is KdeEvent.SyncRequested -> io.launch { syncWith(context, event.deviceId, null, ask = false) }
            is KdeEvent.SyncFile -> io.launch { received(context, event) }
            is KdeEvent.Ping -> if (!keyboardUp) notifyText(
                context,
                context.getString(R.string.ime_kde_notify_ping_title, event.deviceName),
                event.message.ifBlank { context.getString(R.string.ime_kde_ping_default) },
            )
            else -> Unit
        }
        _events.emit(event)
    }

    // ---- sync with a nearby computer ----

    /**
     * A computer with WM Keyboard is in reach (or asked): with Sync with
     * paired devices on it becomes a Nearby device location, our file in its
     * mailbox goes to it, we ask for its file when [ask], and a pass runs.
     */
    private suspend fun syncWith(context: Context, deviceId: String, deviceName: String?, ask: Boolean) {
        val repository = SettingsRepository(context)
        if (deviceName != null) repository.adoptNearbyDevice(deviceId, deviceName)
        if (!isSyncLocation(repository, deviceId)) return
        sendOwn(context, deviceId)
        if (ask) engine?.sync?.request(deviceId)
        SyncRunner.run(context, repository)
        // A pass that found nothing new writes nothing, so nothing went out:
        // the file already in the mailbox is the one to send.
        sendOwn(context, deviceId)
    }

    private suspend fun isSyncLocation(repository: SettingsRepository, deviceId: String): Boolean {
        val auto = repository.settings.first().autoBackup
        return auto.sync.enabled && auto.locations.any {
            it.type == BackupDestination.NEARBY && it.nearbyDeviceId == deviceId && it.id in auto.sync.locationIds
        }
    }

    private fun sendOwn(context: Context, deviceId: String) {
        val dir = NearbySync.dir(context, deviceId) ?: return
        val me = BackupInstall.id(context)
        val sync = engine?.sync ?: return
        dir.listFiles().orEmpty()
            .filter { it.isFile && SyncNaming.installId(it.name) == me }
            .forEach { file -> runCatching { sync.send(deviceId, file.name, file.readBytes()) } }
    }

    /** The computer's sync file: into its mailbox, its older copies out, then a pass. */
    private suspend fun received(context: Context, event: KdeEvent.SyncFile) {
        val repository = SettingsRepository(context)
        if (!isSyncLocation(repository, event.deviceId)) return
        val dir = NearbySync.dir(context, event.deviceId) ?: return
        val owner = SyncNaming.installId(event.fileName) ?: return
        dir.listFiles().orEmpty()
            .filter { it.name != event.fileName && SyncNaming.installId(it.name) == owner }
            .forEach { it.delete() }
        runCatching { File(dir, event.fileName).writeBytes(event.bytes) }.onFailure { return }
        SyncRunner.run(context, repository)
    }

    private fun notifyPairRequest(context: Context, event: KdeEvent.PairRequested) {
        fun action(name: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            context, code,
            Intent(name).setPackage(context.packageName).putExtra(EXTRA_DEVICE, event.deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        WmNotifications.post(context, NotificationKind.CONNECT, NotificationIds.CONNECT_PAIR) {
            setContentTitle(context.getString(R.string.ime_kde_notify_pair_title, event.deviceName))
            setContentText(context.getString(R.string.ime_kde_notify_pair_body, event.verificationKey))
            setAutoCancel(true)
            // The request itself lapses; a notification that outlives it offers a button that does nothing.
            setTimeoutAfter(PAIR_NOTIFICATION_MS)
            addAction(0, context.getString(R.string.ime_kde_pair_accept), action(ACTION_ACCEPT, 1))
            addAction(0, context.getString(R.string.ime_kde_pair_reject), action(ACTION_REJECT, 2))
        }
    }

    private fun notifyFile(context: Context, event: KdeEvent.FileReceived) {
        val uri = Uri.parse(event.location)
        val open = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, KdeReceivedFiles.mimeOf(event.fileName) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        WmNotifications.post(context, NotificationKind.CONNECT, NotificationIds.CONNECT_EVENT) {
            setContentTitle(context.getString(R.string.ime_kde_notify_file_title, event.deviceName))
            setContentText(event.fileName)
            setAutoCancel(true)
            setContentIntent(
                PendingIntent.getActivity(context, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
    }

    /**
     * A row in the shade while sharing is held, with Resume on it: the pause
     * is easy to forget, and this is where it can be ended without opening the
     * keyboard. A timed one leaves the shade when it ends.
     */
    private fun notifyClipboardPaused(context: Context, deviceId: String?, deviceName: String?, untilMs: Long) {
        val forever = untilMs == KdeClipboardPause.FOREVER
        val time = if (forever) "" else android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(untilMs))
        val body = when {
            deviceName != null && forever -> context.getString(R.string.ime_kde_notify_paused_device_forever, deviceName)
            deviceName != null -> context.getString(R.string.ime_kde_notify_paused_device_until, deviceName, time)
            forever -> context.getString(R.string.ime_kde_notify_paused_all_forever)
            else -> context.getString(R.string.ime_kde_notify_paused_all_until, time)
        }
        val resume = PendingIntent.getBroadcast(
            context, 5,
            Intent(ACTION_RESUME_CLIPBOARD).setPackage(context.packageName).putExtra(EXTRA_DEVICE, deviceId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        WmNotifications.post(context, NotificationKind.CONNECT, NotificationIds.CONNECT_PAUSE) {
            setContentTitle(context.getString(R.string.ime_kde_notify_paused_title))
            setContentText(body)
            setOnlyAlertOnce(true)
            setSilent(true)
            if (!forever) setTimeoutAfter((untilMs - System.currentTimeMillis()).coerceAtLeast(1))
            addAction(0, context.getString(R.string.ime_kde_clipboard_resume), resume)
        }
    }

    private fun notifyText(context: Context, title: String, body: String) {
        WmNotifications.post(context, NotificationKind.CONNECT, NotificationIds.CONNECT_EVENT) {
            setContentTitle(title)
            setContentText(body)
            setAutoCancel(true)
            WmNotifications.launchIntent(context)?.let {
                setContentIntent(PendingIntent.getActivity(context, 4, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
    }

    /** From [KdeActionReceiver]: the user answered a pair request in the shade. */
    internal fun onNotificationAction(context: Context, action: String?, deviceId: String?) {
        attach(context)
        if (action == ACTION_RESUME_CLIPBOARD) {
            resumeClipboard(context, deviceId)
            WmNotifications.cancel(context, NotificationIds.CONNECT_PAUSE)
            return
        }
        deviceId ?: return
        when (action) {
            ACTION_ACCEPT -> engine?.acceptPair(deviceId)
            ACTION_REJECT -> engine?.cancelPair(deviceId)
        }
        WmNotifications.cancel(context, NotificationIds.CONNECT_PAIR)
    }

    /**
     * "Send to computer" in other apps' share sheets is an `activity-alias`,
     * disabled in the manifest and switched on here only once something is
     * paired — so someone who never uses this tool never sees the entry.
     */
    private fun syncShareAlias(context: Context, paired: Boolean) {
        val want = settings.enabled && settings.shareSheet && paired
        val component = ComponentName(context.packageName, SHARE_ALIAS)
        val pm = context.packageManager
        val desired = if (want) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        if (shareAliasState == desired) return
        runCatching {
            if (pm.getComponentEnabledSetting(component) != desired) {
                pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            }
            // Only this process moves the alias, so once set it stays set.
            shareAliasState = desired
        }
    }

    private fun KdeConnectSettings.toConfig(context: Context): KdeEngineConfig {
        // Tagged, because the KDE Connect app on the same phone also calls itself by the model.
        val fallback = KdeDeviceNames.branded(Build.MODEL.orEmpty().ifBlank { "Android" })
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        return KdeEngineConfig(
            deviceName = KdeDeviceNames.sanitize(deviceName).ifEmpty { fallback },
            deviceType = if (tablet) KdeDeviceType.TABLET else KdeDeviceType.PHONE,
            hosts = hosts,
            autoConnect = autoConnect,
            clipboardReceive = clipboardReceive,
            clipboardSend = clipboardSend,
            clipboardPauses = clipboardPauses,
            remoteTyping = remoteTyping,
            batteryReport = batteryReport,
            sendCodes = sendCodes,
            exposeMedia = exposeMedia,
            receiveFiles = receiveFiles,
        )
    }

    const val DIR = "kdeconnect"
    const val KEYBOARD_LINGER_MS = 60_000L
    private const val LOW_BATTERY = 15
    private const val PAIR_NOTIFICATION_MS = 25_000L
    const val ACTION_ACCEPT = "com.wasimaster.wmkeyboard.kdeconnect.ACCEPT"
    const val ACTION_REJECT = "com.wasimaster.wmkeyboard.kdeconnect.REJECT"
    const val ACTION_RESUME_CLIPBOARD = "com.wasimaster.wmkeyboard.kdeconnect.RESUME_CLIPBOARD"
    const val EXTRA_DEVICE = "device"

    /** The manifest's `activity-alias`; never rename it, launchers and share targets remember component names. */
    const val SHARE_ALIAS = "com.wasimaster.wmkeyboard.app.KdeShareAlias"

    /** The transparent file picker in `:app`, named rather than referenced: this module sits below it. */
    const val PICKER_ACTIVITY = "com.wasimaster.wmkeyboard.app.kdeconnect.KdeFilePickerActivity"

    /** The settings app's paired-devices screen. */
    const val DEVICES_ROUTE = "kdeconnect/devices"

    /** How much of the line typed on the computer is kept for diffing; only its tail ever changes. */
    const val LINE_MAX = 2_000
}

/** Answers the Accept / Reject buttons on a pair-request notification, and Resume on a clipboard pause. Not exported. */
class KdeActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        KdeConnectHub.onNotificationAction(context, intent?.action, intent?.getStringExtra(KdeConnectHub.EXTRA_DEVICE))
    }
}
