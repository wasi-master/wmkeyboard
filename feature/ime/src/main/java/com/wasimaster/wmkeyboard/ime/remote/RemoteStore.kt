package com.wasimaster.wmkeyboard.ime.remote

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * The remembered remotes, in `files/remote/devices.json`. One process holds both
 * the keyboard and the settings app, so a single in-memory copy behind a
 * [StateFlow] is what both draw; every change is written through at once.
 */
object RemoteStore {

    @Serializable
    private data class Saved(
        val devices: List<RemoteDevice> = emptyList(),
        val selected: String? = null,
    )

    data class State(val devices: List<RemoteDevice> = emptyList(), val selected: String? = null) {
        /** The remote the panel opens on: the last one used, else the first. */
        val current: RemoteDevice? get() = devices.firstOrNull { it.id == selected } ?: devices.firstOrNull()
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val state = MutableStateFlow(State())
    private var file: AtomicFile? = null

    val flow: StateFlow<State> get() = state.asStateFlow()

    /** Reads the file the first time; later calls are free. */
    @Synchronized
    fun load(context: Context): State {
        if (file != null) return state.value
        val dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
        val atomic = AtomicFile(File(dir, FILE))
        file = atomic
        val saved = runCatching { json.decodeFromString(Saved.serializer(), String(atomic.readFully(), Charsets.UTF_8)) }.getOrNull()
        if (saved != null) state.value = State(saved.devices, saved.selected)
        return state.value
    }

    fun add(context: Context, device: RemoteDevice): RemoteDevice {
        change(context) { it.copy(devices = it.devices + device, selected = device.id) }
        return device
    }

    fun update(context: Context, id: String, edit: (RemoteDevice) -> RemoteDevice) {
        change(context) { s -> s.copy(devices = s.devices.map { if (it.id == id) edit(it) else it }) }
    }

    fun remove(context: Context, id: String) {
        change(context) { s -> s.copy(devices = s.devices.filterNot { it.id == id }, selected = s.selected.takeIf { it != id }) }
    }

    fun select(context: Context, id: String) {
        if (state.value.selected == id) return
        change(context) { it.copy(selected = id) }
    }

    fun get(id: String): RemoteDevice? = state.value.devices.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    private fun change(context: Context, edit: (State) -> State) {
        load(context)
        val next = edit(state.value)
        state.value = next
        val atomic = file ?: return
        runCatching {
            val out = atomic.startWrite()
            try {
                out.write(json.encodeToString(Saved.serializer(), Saved(next.devices, next.selected)).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (t: Throwable) {
                atomic.failWrite(out)
                throw t
            }
        }
    }

    private const val DIR = "remote"
    private const val FILE = "devices.json"
}
