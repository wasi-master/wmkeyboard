package com.wasimaster.wmkeyboard.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for the phone's own voice window (a last resort for
 * dictation): when no recognition service will start for the keyboard, the
 * `RECOGNIZE_SPEECH` window an app's mic button opens often still works,
 * since it is the recognizer's own activity rather than a bound service. An
 * input method cannot wait for an activity's result, so this one asks for it
 * and keeps the words; the keyboard commits them once the field it was
 * typing into has the keyboard back ([consumePending]).
 *
 * The window to open travels in [target] rather than as an extra, as with
 * the reply tool's trampoline: the IME and this activity share a process.
 */
class VoiceWindowActivity : ComponentActivity() {

    companion object {
        @Volatile
        private var target: ComponentName? = null

        @Volatile
        private var language: String = ""

        @Volatile
        private var pending: String? = null

        /** Opens [window], another app's voice window, in [languageTag]. Call from the IME. */
        fun start(context: Context, window: ComponentName, languageTag: String) {
            target = window
            language = languageTag
            pending = null
            context.startActivity(
                Intent(context, VoiceWindowActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }

        /** Hands over (and clears) what the window heard, or null when it heard nothing. */
        fun consumePending(): String? = pending.also { pending = null }
    }

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val window = target.also { target = null }
        if (window == null) {
            finish()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .setComponent(window)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        runCatching { launcher.launch(intent) }.onFailure { finish() }
    }
}
