package com.wasimaster.wmkeyboard.app

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession.AssistState
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import androidx.annotation.RequiresApi
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.ime.aichat.AskAiLaunch

/**
 * WM Keyboard as the phone's digital assistant (#1143). Android binds this
 * service once the user picks WM Keyboard as the assistant app, and opens an
 * [AssistSession] for the assistant gesture: a long press on the home button
 * or the power button, a swipe from a bottom corner, or a headset's button.
 *
 * Nothing runs here between sessions. The service is on Android's list only
 * while **Use as the phone's assistant** is on ([SystemVoiceComponents]).
 * Its XML names [com.wasimaster.wmkeyboard.ime.sysvoice.WmRecognitionService]
 * as its recognizer, which Android requires of an assistant and makes the
 * phone's speech recognizer while WM Keyboard is the assistant.
 */
class AssistService : VoiceInteractionService()

/** Hands Android a new [AssistSession] each time the assistant is opened. */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * One opening of the assistant. It draws nothing of its own: it waits a
 * moment for the screen's text, which Android sends when **Use text from
 * screen** is on, then opens [AssistMenuActivity] over the app with that text
 * and steps aside.
 */
internal class AssistSession(context: Context) : VoiceInteractionSession(context) {

    private val main = Handler(Looper.getMainLooper())
    private var shown = false
    private var launched = false

    /** Android sent the screen, or said it has none to send. */
    private var assistDone = false
    private var screenText = ""
    private var screenPackage = ""

    private val giveUp = Runnable {
        assistDone = true
        launch()
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // The menu is an activity; the session needs no window of its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        shown = true
        launched = false
        // Without SHOW_WITH_ASSIST no screen is coming; with it, it may
        // already be here, or come in a moment, or not at all.
        if (showFlags and SHOW_WITH_ASSIST == 0) assistDone = true
        if (assistDone) launch() else main.postDelayed(giveUp, ASSIST_WAIT_MS)
    }

    /**
     * Android 10 and later: the screen of each app showing, the top one at
     * index 0. Also called when there is nothing to send (screen text off),
     * which the older call below never hears of.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        if (state.index != 0) return
        take(state.assistStructure)
    }

    @Deprecated("Android 9 and earlier only; see the AssistState overload")
    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        take(structure)
    }

    /** The top app's [structure], or null when Android sent none. */
    private fun take(structure: AssistStructure?) {
        if (structure != null) {
            screenText = runCatching { AssistScreenText.of(structure) }
                .onFailure { DebugLog.w(TAG, "screen unreadable: ${it.message}") }
                .getOrDefault("")
            screenPackage = structure.activityComponent?.packageName.orEmpty()
        }
        assistDone = true
        launch()
    }

    override fun onHide() {
        shown = false
        main.removeCallbacks(giveUp)
        // The next opening waits for its own screen.
        assistDone = false
        screenText = ""
        screenPackage = ""
        super.onHide()
    }

    override fun onDestroy() {
        main.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun launch() {
        if (!shown || launched || !assistDone) return
        launched = true
        main.removeCallbacks(giveUp)
        val intent = Intent(context, AssistMenuActivity::class.java).apply {
            if (screenText.isNotBlank()) putExtra(AskAiLaunch.EXTRA_SCREEN_TEXT, screenText)
            if (screenPackage.isNotBlank() && screenPackage != context.packageName) {
                putExtra(AskAiLaunch.EXTRA_SCREEN_PACKAGE, screenPackage)
            }
        }
        val started = runCatching { startAssistantActivity(intent) }
            .onFailure { DebugLog.w(TAG, "assistant activity refused: ${it.message}") }
            .isSuccess
        if (!started) {
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        // The menu is up on its own now; the session has nothing to show.
        main.post { hide() }
    }

    private companion object {
        const val TAG = "AssistSession"

        /**
         * How long to wait for the screen's text before opening the menu
         * without it. Android sends it within a few hundred milliseconds when
         * it sends it at all.
         */
        const val ASSIST_WAIT_MS = 1_200L
    }
}
