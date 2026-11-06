package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
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
 * (#1142) and of assistant apps (#1143): the recognition service, the
 * `RECOGNIZE_SPEECH` window and the voice interaction service with its
 * session service. All are off in the manifest and switched here from the
 * settings, so the phone only offers WM Keyboard once the user asked for it,
 * and stops when they turn it off, from whatever screen, backup or sync that
 * happens.
 *
 * Follows the settings from the process start, as [AskAiSelectionMenu] does.
 */
internal object SystemVoiceComponents {

    private const val RECOGNIZER = "com.wasimaster.wmkeyboard.ime.sysvoice.WmRecognitionService"
    private const val VOICE_INPUT = "com.wasimaster.wmkeyboard.app.VoiceInputActivity"
    private const val ASSIST = "com.wasimaster.wmkeyboard.app.AssistService"
    private const val ASSIST_SESSION = "com.wasimaster.wmkeyboard.app.AssistSessionService"

    /** Which of the components are on. */
    internal data class Wanted(val recognizer: Boolean, val voiceInput: Boolean, val assistant: Boolean)

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
                .map(::wanted)
                .distinctUntilChanged()
                .collect { on ->
                    apply(app, RECOGNIZER, on.recognizer)
                    apply(app, VOICE_INPUT, on.voiceInput)
                    apply(app, ASSIST_SESSION, on.assistant)
                    apply(app, ASSIST, on.assistant)
                }
        }
    }

    /**
     * What [settings] ask for. The assistant needs the AI tool, whose chat is
     * what it opens. It also needs the recognition service: Android makes an
     * assistant's recognizer the phone's speech recognizer, so with the
     * assistant on the service stays on even with **Voice input for other
     * apps** off. The voice input window does not come with it, since that
     * would put WM Keyboard in front of every app's microphone button.
     */
    internal fun wanted(settings: KeyboardSettings): Wanted {
        val assistant = settings.ai.assistant && ToolbarTool.AI in settings.enabledTools
        val voiceInput = settings.voiceBar.systemInput
        return Wanted(recognizer = voiceInput || assistant, voiceInput = voiceInput, assistant = assistant)
    }

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
