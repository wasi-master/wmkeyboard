package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the panel's remote stands. */
enum class RemoteStatus {
    /** Infrared, or a network remote that answered. */
    READY,
    CONNECTING,

    /** Nothing answered; a press of Power sends a wake-up where the TV's MAC is known. */
    UNREACHABLE,

    /** The TV forgot this phone, or never knew it: pair again on the tool's page. */
    NOT_PAIRED,

    /** A Roku with Control by mobile apps disabled. */
    BLOCKED,

    /** An infrared remote on a phone with no emitter. */
    NO_EMITTER,

    /** A network remote in the build without the internet permission, which local sockets need too. */
    OFFLINE_BUILD,
}

/** Everything the Remote panel draws, owned by [RemoteController]. */
data class RemoteUi(
    val devices: List<RemoteDevice> = emptyList(),
    val current: RemoteDevice? = null,
    val status: RemoteStatus = RemoteStatus.READY,
    val keys: Set<RemoteKey> = emptySet(),
    val extras: List<IrButton> = emptyList(),
)

sealed interface RemoteAction {
    data class Select(val id: String) : RemoteAction
    data class Press(val key: RemoteKey) : RemoteAction
    data class Extra(val button: IrButton) : RemoteAction
    data object Retry : RemoteAction
    data object OpenSettings : RemoteAction
}

/** What the Remote panel hands back; one bundle, see `ToolHoldCallbacks.remote`. */
data class RemoteCallbacks(
    val state: StateFlow<RemoteUi> = MutableStateFlow(RemoteUi()),
    val onAction: (RemoteAction) -> Unit = {},
)

/**
 * The Remote tool in the keyboard: the remembered remotes as chips, one of
 * them open. A network remote connects when the panel opens on it and lets go
 * when the panel closes or another is picked, so nothing stays connected to a
 * TV while the keyboard is doing something else.
 */
internal class RemoteController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
) {
    private val state = MutableStateFlow(RemoteUi())
    val callbacks = RemoteCallbacks(state = state, onAction = ::dispatch)

    private var session: RemoteSession? = null
    private var sessionFor: String? = null
    private var connectJob: Job? = null
    private val sending = Mutex()
    private var open = false
    private var watchJob: Job? = null

    fun onPanelOpen() {
        open = true
        val loaded = RemoteStore.load(context)
        show(loaded.current, loaded.devices)
        // A remote renamed, added or removed on the tool's page shows up without reopening the panel.
        watchJob?.cancel()
        watchJob = scope.launch {
            RemoteStore.flow.collect { s ->
                val current = state.value.current
                val fresh = s.devices.firstOrNull { it.id == current?.id }
                // An infrared remote's codes changed: its keys are rebuilt. A network
                // remote's learnt token or pin is not a reason to reconnect.
                val irChanged = fresh != null && fresh.remoteKind == RemoteKind.IR && fresh != current
                when {
                    irChanged -> {
                        drop()
                        show(fresh, s.devices)
                    }
                    (current == null && s.current != null) || (current != null && fresh == null) -> show(s.current, s.devices)
                    else -> state.update { it.copy(devices = s.devices, current = fresh ?: it.current) }
                }
            }
        }
    }

    /** The panel closed or the keyboard went: every connection goes with it. */
    fun onPanelClose() {
        if (!open) return
        open = false
        watchJob?.cancel()
        watchJob = null
        drop()
    }

    private fun dispatch(action: RemoteAction) {
        when (action) {
            is RemoteAction.Select -> {
                haptic()
                RemoteStore.select(context, action.id)
                val s = RemoteStore.flow.value
                show(s.current, s.devices)
            }
            is RemoteAction.Press -> press(action.key)
            is RemoteAction.Extra -> {
                haptic()
                scope.launch { sendIrCode(context, action.button.code) }
            }
            RemoteAction.Retry -> {
                val s = RemoteStore.flow.value
                drop()
                show(s.current, s.devices)
            }
            RemoteAction.OpenSettings -> openToolSettings()
        }
    }

    private fun show(device: RemoteDevice?, devices: List<RemoteDevice>) {
        if (device?.id != sessionFor) drop()
        val kind = device?.remoteKind
        val status = when {
            device == null || kind == null -> RemoteStatus.READY
            kind == RemoteKind.IR && !IrBlaster.available(context) -> RemoteStatus.NO_EMITTER
            kind.network && OfflineBuild.isThisBuild -> RemoteStatus.OFFLINE_BUILD
            kind.network -> RemoteStatus.CONNECTING
            else -> RemoteStatus.READY
        }
        val created = if (device != null && status != RemoteStatus.NO_EMITTER && status != RemoteStatus.OFFLINE_BUILD) {
            session ?: RemoteSessions.create(context, device, learner(device.id))
        } else {
            null
        }
        session = created
        sessionFor = device?.id
        state.value = RemoteUi(
            devices = devices,
            current = device,
            status = status,
            keys = created?.keys ?: emptySet(),
            extras = if (kind == RemoteKind.IR) device?.irExtra.orEmpty() else emptyList(),
        )
        if (status == RemoteStatus.CONNECTING && created != null) connect(created)
    }

    private fun learner(id: String): RemoteLearn = { edit -> RemoteStore.update(context, id, edit) }

    private fun connect(target: RemoteSession) {
        connectJob?.cancel()
        connectJob = scope.launch {
            val status = try {
                target.open()
                RemoteStatus.READY
            } catch (e: RemoteException) {
                statusOf(e)
            }
            if (session === target) state.update { it.copy(status = status) }
        }
    }

    private fun press(key: RemoteKey) {
        val target = session ?: return
        val device = state.value.current ?: return
        if (key !in state.value.keys) return
        haptic()
        scope.launch {
            sending.withLock {
                try {
                    target.press(key)
                    if (session === target && state.value.status != RemoteStatus.READY) state.update { it.copy(status = RemoteStatus.READY) }
                } catch (e: RemoteException) {
                    // Power on a TV that is off: its network is asleep, but Wake-on-LAN may reach it.
                    val mac = RemoteStore.get(device.id)?.mac.orEmpty()
                    val woke = key == RemoteKey.POWER && e.reason == RemoteException.Reason.UNREACHABLE && mac.isNotEmpty() &&
                        withContext(Dispatchers.IO) { runCatching { RemoteLan.wake(mac) }.getOrDefault(false) }
                    if (session === target) state.update { it.copy(status = if (woke) RemoteStatus.CONNECTING else statusOf(e)) }
                    if (woke) {
                        scope.launch {
                            // A TV takes a few seconds to bring its network up after the packet.
                            delay(WAKE_RETRY_MS)
                            if (session === target) connect(target)
                        }
                    }
                }
            }
        }
    }

    private fun statusOf(e: RemoteException): RemoteStatus = when (e.reason) {
        RemoteException.Reason.UNREACHABLE, RemoteException.Reason.TIMED_OUT -> RemoteStatus.UNREACHABLE
        RemoteException.Reason.NOT_PAIRED, RemoteException.Reason.REFUSED -> RemoteStatus.NOT_PAIRED
        RemoteException.Reason.BLOCKED -> RemoteStatus.BLOCKED
    }

    private fun drop() {
        connectJob?.cancel()
        connectJob = null
        session?.let { s -> scope.launch(Dispatchers.IO) { s.close() } }
        session = null
        sessionFor = null
    }

    private companion object {
        const val WAKE_RETRY_MS = 6_000L
    }
}
