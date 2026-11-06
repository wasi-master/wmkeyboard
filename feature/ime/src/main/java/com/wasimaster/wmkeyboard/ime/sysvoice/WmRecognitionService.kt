package com.wasimaster.wmkeyboard.ime.sysvoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.wasimaster.wmkeyboard.app.MicPermissionActivity
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * WM Keyboard as the phone's speech recognizer (#1142): picked under
 * Android's voice input settings, it hears other apps' `SpeechRecognizer`
 * requests, and the assistant's (#1143), with the keyboard's own engines.
 * The work is [AppDictation]'s; this only speaks the service's protocol.
 *
 * Switched on and off as a component by the **Voice input for other apps**
 * setting, so it is only on the phone's list when the user asked for it.
 * With voice typing itself switched off, a request is passed to another
 * app's recognizer, as if WM Keyboard were not the default: an assistant
 * made default also makes its recognizer the default, and turning voice
 * typing off should not leave every other app's microphone dead.
 */
class WmRecognitionService : RecognitionService() {

    private val scope = MainScope()
    private val repository by lazy { SettingsRepository(applicationContext) }

    /** The request running now, and the callback it answers. */
    private var dictation: AppDictation? = null
    private var callback: Callback? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        dictation?.cancel()
        dictation = null
        callback = listener
        val requestedTag = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val partials = recognizerIntent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        val pause = recognizerIntent
            .getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, -1L)
            .takeIf { it > 0 }
        scope.launch {
            val settings = runCatching { repository.settings.first() }.getOrNull()
            if (callback !== listener) return@launch
            if (settings == null) {
                send(listener) { error(SpeechRecognizer.ERROR_CLIENT) }
                return@launch
            }
            var spoke = false
            val session = AppDictation(
                this@WmRecognitionService,
                settings,
                AppDictation.languagesFor(settings, requestedTag),
                requestedPauseMs = pause,
                forwardOnly = !settings.voiceBar.enabled,
                listener = object : AppDictation.Listener {
                    override fun onReady() = send(listener) { readyForSpeech(Bundle()) }

                    override fun onLevel(level: Float) = send(listener) { rmsChanged(RecognitionErrors.rmsDb(level)) }

                    override fun onSpeechStart() {
                        spoke = true
                        send(listener) { beginningOfSpeech() }
                    }

                    override fun onPartial(text: String) {
                        if (partials) send(listener) { partialResults(resultBundle(text)) }
                    }

                    override fun onEndOfSpeech() = send(listener) { endOfSpeech() }

                    override fun onResult(text: String) {
                        finished(listener)
                        send(listener) { results(resultBundle(text)) }
                    }

                    override fun onError(error: AppDictation.Error) {
                        finished(listener)
                        if (error == AppDictation.Error.PERMISSION) askForMicrophone()
                        send(listener) { error(RecognitionErrors.code(error, spoke, Build.VERSION.SDK_INT)) }
                    }
                },
            )
            dictation = session
            session.start()
        }
    }

    override fun onStopListening(listener: Callback) {
        if (callback === listener) dictation?.stop()
    }

    override fun onCancel(listener: Callback) {
        if (callback !== listener) return
        dictation?.cancel()
        dictation = null
        callback = null
    }

    override fun onDestroy() {
        dictation?.cancel()
        dictation = null
        callback = null
        scope.cancel()
        super.onDestroy()
    }

    /** The request answering [listener] is over. */
    private fun finished(listener: Callback) {
        if (callback === listener) {
            dictation = null
            callback = null
        }
    }

    /**
     * A service cannot ask for a permission, so the keyboard's own
     * microphone prompt opens over the app that asked. Android may refuse
     * to let a service start it; the error goes back either way.
     */
    private fun askForMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun resultBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /** The caller may have gone; a dead binder is not this service's problem. */
    private inline fun send(listener: Callback, call: Callback.() -> Unit) {
        try {
            listener.call()
        } catch (e: android.os.RemoteException) {
            DebugLog.w(TAG, "caller went away: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "WmRecognitionService"
    }
}
