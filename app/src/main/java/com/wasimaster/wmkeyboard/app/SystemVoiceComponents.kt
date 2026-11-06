package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The components that put WM Keyboard on Android's lists of voice input apps
 * (#1142): the recognition service and the `RECOGNIZE_SPEECH` window. Both
 * are off in the manifest and switched here from the settings, so the phone
 * only offers WM Keyboard once the user asked for it, and stops when they
 * turn it off, from whatever screen, backup or sync that happens.
 *
 * Follows the settings from the process start, as [AskAiSelectionMenu] does.
 */
internal object SystemVoiceComponents {

    private const val RECOGNIZER = "com.wasimaster.wmkeyboard.ime.sysvoice.WmRecognitionService"
    private const val VOICE_INPUT = "com.wasimaster.wmkeyboard.app.VoiceInputActivity"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> DebugLog.e("SystemVoiceComponents", "watch failed", failure) },
    )

    @Volatile
    private var started = false

    fun start(context: Context, repository: SettingsRepository) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            repository.settings
                // Before the first unlock the store reads as the defaults,
                // which would take WM Keyboard off a list the user put it on.
                .filter { DirectBoot.isUserUnlocked(app) }
                .map(::recognizerOn)
                .distinctUntilChanged()
                .collect { on ->
                    apply(app, RECOGNIZER, on)
                    apply(app, VOICE_INPUT, on)
                }
        }
    }

    /** Whether the recognition service and the voice input window are offered. */
    internal fun recognizerOn(settings: KeyboardSettings): Boolean = settings.voiceBar.systemInput

    /** Writes only when the state is wrong, so a process start pays one read each. */
    private fun apply(context: Context, className: String, enabled: Boolean) {
        val component = ComponentName(context.packageName, className)
        val pm = context.packageManager
        // The manifest has them off, so on must be said outright.
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        runCatching {
            if (pm.getComponentEnabledSetting(component) != state) {
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
