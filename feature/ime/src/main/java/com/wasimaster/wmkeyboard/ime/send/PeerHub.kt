package com.wasimaster.wmkeyboard.ime.send

import android.content.Context
import android.net.wifi.WifiManager
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import com.wasimaster.wmkeyboard.core.send.LanTransfer
import com.wasimaster.wmkeyboard.core.send.LocalSendIdentity
import com.wasimaster.wmkeyboard.core.send.LocalSendNode
import com.wasimaster.wmkeyboard.core.send.LocalSendPeer
import com.wasimaster.wmkeyboard.core.send.PairDropClient
import com.wasimaster.wmkeyboard.core.send.PairDropPeer
import com.wasimaster.wmkeyboard.core.send.PeerOffer
import com.wasimaster.wmkeyboard.core.send.SendFile
import com.wasimaster.wmkeyboard.core.settings.SendSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/** Which protocol reaches a [LanPeer]. */
enum class PeerVia { LOCALSEND, PAIRDROP }

/** A device reached by another app's protocol, as a card in the TO row. */
data class LanPeer(val id: String, val name: String, val via: PeerVia, val deviceType: String? = null, val model: String? = null)

/** What the panel shows of those devices. */
data class PeersUi(
    val peers: List<LanPeer> = emptyList(),
    /** Somebody is waiting for a yes or a no. */
    val offer: PeerOffer? = null,
    /** Newest first, the last few. */
    val transfers: List<LanTransfer> = emptyList(),
    /** PairDrop is on and its WebRTC library is not on this phone yet. */
    val rtcMissing: Boolean = false,
    /** The library is on its way. */
    val rtcInstalling: Boolean = false,
    /** PairDrop is on and its server cannot be reached. */
    val pairDropOffline: Boolean = false,
)

/**
 * LocalSend and PairDrop for the Send files panel. Both run only while [sync] says
 * Nearby is showing, and outlive that only to finish a transfer under way: the first
 * is a server on this phone and the second tells a server this phone's address, so
 * neither has any business running behind a closed panel.
 */
internal class PeerHub(
    private val app: Context,
    private val scope: CoroutineScope,
    private val http: () -> OkHttpClient,
    private val sink: () -> com.wasimaster.wmkeyboard.core.send.SendSink,
    private val deviceName: () -> String,
    private val onSaved: (LanTransfer) -> Unit,
    private val onSendDone: (name: String, ok: Boolean) -> Unit,
) {

    private val _state = MutableStateFlow(PeersUi())
    val state: StateFlow<PeersUi> = _state

    private var localSend: LocalSendNode? = null
    private var pairDrop: PairDropClient? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wanted = false
    private var wantedLocalSend = false
    private var wantedPairDrop = false
    private var pairDropServer = ""
    private var rtcWatch: Job? = null
    private var localPeers = emptyList<LocalSendPeer>()
    private var dropPeers = emptyList<PairDropPeer>()

    @Synchronized
    fun sync(want: Boolean, settings: SendSettings) {
        wanted = want
        wantedLocalSend = want && settings.localSend
        wantedPairDrop = want && settings.pairDrop
        pairDropServer = settings.pairDropServer
        if (wantedLocalSend && localSend == null) startLocalSend()
        if (!wantedLocalSend && !busy()) stopLocalSend()
        if (wantedPairDrop && pairDrop == null) startPairDrop()
        if (!wantedPairDrop && !busy()) stopPairDrop()
    }

    fun refresh() {
        localSend?.refresh()
    }

    @Synchronized
    fun stop() {
        wanted = false
        wantedLocalSend = false
        wantedPairDrop = false
        stopLocalSend()
        stopPairDrop()
    }

    private fun busy(): Boolean = _state.value.transfers.any { it.state == LanTransfer.State.RUNNING }

    /** Fetches the WebRTC library: a Play split, or the file pinned for this build. */
    fun installRtc() {
        FeatureModules.webrtc.requestInstall()
    }

    // ---- LocalSend --------------------------------------------------------

    private fun startLocalSend() {
        val listener = object : LocalSendNode.Listener {
            override fun onPeers(peers: List<LocalSendPeer>) {
                localPeers = peers
                publishPeers()
            }

            override fun onTransfer(transfer: LanTransfer) = this@PeerHub.onTransfer(transfer)

            override fun onOffer(offer: PeerOffer) = this@PeerHub.onOffer(offer)
        }
        scope.launch(Dispatchers.IO) {
            val identity = runCatching { LocalSendIdentity.load(File(app.filesDir, IDENTITY_FILE)) }.getOrNull() ?: return@launch
            val node = LocalSendNode(identity, deviceName(), android.os.Build.MODEL.orEmpty(), sink(), http(), listener)
            synchronized(this@PeerHub) {
                if (!wantedLocalSend) return@launch
                holdMulticast()
                if (runCatching { node.start() }.isFailure) {
                    releaseMulticast()
                    return@launch
                }
                localSend = node
            }
        }
    }

    private fun stopLocalSend() {
        val node = localSend ?: return
        localSend = null
        localPeers = emptyList()
        releaseMulticast()
        scope.launch(Dispatchers.IO) { node.stop() }
        publishPeers()
    }

    /** Android drops multicast to save power unless somebody holds this; discovery is all multicast. */
    private fun holdMulticast() {
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = runCatching { wifi.createMulticastLock(LOCK_TAG).apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    private fun releaseMulticast() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    // ---- PairDrop ---------------------------------------------------------

    private fun startPairDrop() {
        val module = FeatureModules.webrtc
        val rtc = if (module.installed) PairDropWebRtc.create(app) else null
        val client = PairDropClient(
            pairDropServer.ifBlank { PairDropClient.DEFAULT_SERVER },
            http(),
            sink(),
            rtc,
            deviceName(),
            object : PairDropClient.Listener {
                override fun onPeers(peers: List<PairDropPeer>) {
                    dropPeers = peers
                    publishPeers()
                }

                override fun onTransfer(transfer: LanTransfer) = this@PeerHub.onTransfer(transfer)

                override fun onOffer(offer: PeerOffer) = this@PeerHub.onOffer(offer)

                override fun onConnection(connected: Boolean, relay: Boolean) {
                    _state.update { it.copy(pairDropOffline = !connected) }
                }
            },
        )
        pairDrop = client
        runCatching { client.start() }.onFailure { _state.update { s -> s.copy(pairDropOffline = true) } }
        watchRtc(rtc == null && PairDropWebRtc.supported)
    }

    /** Says the library is missing while it is, and reconnects with it the moment it lands. */
    private fun watchRtc(missing: Boolean) {
        rtcWatch?.cancel()
        rtcWatch = null
        _state.update { it.copy(rtcMissing = missing, rtcInstalling = false) }
        if (!missing) return
        rtcWatch = scope.launch {
            FeatureModules.webrtc.state.first { state ->
                val installing = state is ModuleState.Installing
                _state.update { it.copy(rtcInstalling = installing) }
                state == ModuleState.Installed
            }
            synchronized(this@PeerHub) {
                stopPairDrop()
                if (wantedPairDrop) startPairDrop()
            }
        }
    }

    private fun stopPairDrop() {
        rtcWatch?.cancel()
        rtcWatch = null
        val client = pairDrop ?: return
        pairDrop = null
        dropPeers = emptyList()
        _state.update { it.copy(rtcMissing = false, rtcInstalling = false, pairDropOffline = false) }
        scope.launch(Dispatchers.IO) { runCatching { client.stop() } }
        publishPeers()
    }

    // ---- shared -----------------------------------------------------------

    private fun publishPeers() {
        val peers = localPeers.map { LanPeer(LOCAL_PREFIX + it.fingerprint, it.alias, PeerVia.LOCALSEND, it.deviceType, it.deviceModel) } +
            dropPeers.map { LanPeer(DROP_PREFIX + it.id, it.name, PeerVia.PAIRDROP, null, it.model) }
        _state.update { it.copy(peers = peers) }
    }

    private fun onOffer(offer: PeerOffer) {
        // One at a time; a second one hears no rather than replacing the question on screen.
        var taken = false
        _state.update { s ->
            if (s.offer != null) {
                s
            } else {
                taken = true
                s.copy(offer = offer)
            }
        }
        if (!taken) offer.answer(false)
    }

    fun answerOffer(yes: Boolean) {
        val offer = _state.value.offer ?: return
        _state.update { it.copy(offer = null) }
        offer.answer(yes)
    }

    private fun onTransfer(t: LanTransfer) {
        _state.update { s ->
            val list = if (s.transfers.any { it.id == t.id && it.name == t.name }) {
                s.transfers.map { if (it.id == t.id && it.name == t.name) t else it }
            } else {
                listOf(t) + s.transfers
            }
            s.copy(transfers = list.take(MAX_TRANSFERS))
        }
        if (t.incoming && t.state == LanTransfer.State.DONE) onSaved(t)
        if (t.state != LanTransfer.State.RUNNING && !busy()) {
            synchronized(this) {
                if (!wantedLocalSend) stopLocalSend()
                if (!wantedPairDrop) stopPairDrop()
            }
        }
    }

    /** Sends [files] to the peer [id]; the outcome comes back through [onSendDone]. */
    fun send(id: String, name: String, files: List<SendFile>) {
        scope.launch(Dispatchers.IO) {
            val ok = runCatching {
                when {
                    id.startsWith(LOCAL_PREFIX) -> {
                        val node = localSend
                        val peer = localPeers.firstOrNull { LOCAL_PREFIX + it.fingerprint == id }
                        node != null && peer != null && node.send(peer, files) == LocalSendNode.SendResult.SENT
                    }
                    else -> pairDrop?.send(id.removePrefix(DROP_PREFIX), files) == PairDropClient.SendResult.SENT
                }
            }.getOrDefault(false)
            onSendDone(name, ok)
        }
    }

    private companion object {
        const val IDENTITY_FILE = "send/localsend.p12"
        const val LOCK_TAG = "wmkeyboard:localsend"
        const val LOCAL_PREFIX = "ls:"
        const val DROP_PREFIX = "pd:"
        const val MAX_TRANSFERS = 12
    }
}
