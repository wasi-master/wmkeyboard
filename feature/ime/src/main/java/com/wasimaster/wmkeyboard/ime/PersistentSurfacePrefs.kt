package com.wasimaster.wmkeyboard.ime

import android.content.Context
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot

/**
 * The panel or key layer that was on screen when the keyboard last left it, for
 * the grids whose author switched "Keep this layer open" on (issue #60).
 *
 * Issue #60 kept such a layer across a change of *field*, in memory, and that is
 * not where the switch is judged: the system stops the IME process soon after
 * the keyboard goes away, so the keyboard that comes back is a fresh one holding
 * default state and the layer is gone. Both halves of issue #227 are that —
 * "closed the keyboard, the layer was closed too" and "changed apps, the layer
 * was closed" are the same process death seen twice.
 *
 * Its own `SharedPreferences` file rather than fields on `KeyboardSettings` (the
 * same reasoning as `VocabPrefs`): this records where the user was, not anything
 * they set, it has no business in a backup, and the settings class is near its
 * argument ceiling. Device-protected storage, because the keyboard types on the
 * lock screen, where the credential-protected one cannot be opened.
 */
class PersistentSurfacePrefs(context: Context) {

    private val prefs = DirectBoot.deviceContext(context)
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Name of the `PanelMode` that was open, or null when none was. */
    val panel: String? get() = prefs.getString(KEY_PANEL, null)

    /** Name of the `LayoutMode` that was showing, or null for the letters. */
    val layer: String? get() = prefs.getString(KEY_LAYER, null)

    /** The secondary layout [layer] names, when it is `SECONDARY`. */
    val secondaryLayoutId: String? get() = prefs.getString(KEY_SECONDARY, null)

    /**
     * Records what is on screen as the keyboard leaves it. Writes nothing when
     * the answer has not changed, so the common case — no persistent grid
     * anywhere — costs one file-backed read per hide and no write at all.
     */
    fun save(panel: String?, layer: String?, secondaryLayoutId: String?) {
        if (panel == this.panel &&
            layer == this.layer &&
            secondaryLayoutId == this.secondaryLayoutId
        ) {
            return
        }
        prefs.edit {
            putString(KEY_PANEL, panel)
            putString(KEY_LAYER, layer)
            putString(KEY_SECONDARY, secondaryLayoutId)
        }
    }

    /**
     * The voice view the keyboard last left the screen on, when the user asked
     * for it to open again on the next show (#699), or null.
     */
    val voice: String? get() = prefs.getString(KEY_VOICE, null)

    /** Records [voice]; writes nothing when it has not changed. */
    fun saveVoice(voice: String?) {
        if (voice == this.voice) return
        prefs.edit { putString(KEY_VOICE, voice) }
    }

    /**
     * The tool left open in an app the user switched away from, for a keyboard
     * that comes back as a new process: the app's package, the `PanelMode`
     * name, the plugin that was running in it, and when (wall clock). Only the
     * latest one; the ones before it live in memory alone.
     */
    data class ParkedTool(val packageName: String, val panel: String, val pluginId: String?, val at: Long)

    val parkedTool: ParkedTool?
        get() {
            val pkg = prefs.getString(KEY_PARKED_PACKAGE, null) ?: return null
            val panel = prefs.getString(KEY_PARKED_PANEL, null) ?: return null
            return ParkedTool(pkg, panel, prefs.getString(KEY_PARKED_PLUGIN, null), prefs.getLong(KEY_PARKED_AT, 0L))
        }

    /** Records [parkedTool], or clears it with null. */
    fun saveParkedTool(tool: ParkedTool?) {
        if (tool == null && !prefs.contains(KEY_PARKED_PACKAGE)) return
        prefs.edit {
            if (tool == null) {
                remove(KEY_PARKED_PACKAGE)
                remove(KEY_PARKED_PANEL)
                remove(KEY_PARKED_PLUGIN)
                remove(KEY_PARKED_AT)
            } else {
                putString(KEY_PARKED_PACKAGE, tool.packageName)
                putString(KEY_PARKED_PANEL, tool.panel)
                putString(KEY_PARKED_PLUGIN, tool.pluginId)
                putLong(KEY_PARKED_AT, tool.at)
            }
        }
    }

    private companion object {
        const val FILE_NAME = "persistent_surface"
        const val KEY_PANEL = "panel"
        const val KEY_LAYER = "layer"
        const val KEY_SECONDARY = "secondary"
        const val KEY_VOICE = "voice"
        const val KEY_PARKED_PACKAGE = "parked_package"
        const val KEY_PARKED_PANEL = "parked_panel"
        const val KEY_PARKED_PLUGIN = "parked_plugin"
        const val KEY_PARKED_AT = "parked_at"
    }
}
