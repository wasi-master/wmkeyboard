package com.wasimaster.wmkeyboard.ime

import android.os.Build
import android.view.inputmethod.InputMethodManager

/**
 * Holds back an app's request to hide the keyboard for a moment, in the apps
 * known to hide it only to show it again straight away, so the keyboard does
 * not drop and come back up.
 *
 * Messenger does this when a message is edited (long press, More, Edit).
 * Closing the menu hands focus back to a window whose composer is being
 * swapped for the edit box, so the framework's InputMethodManager in the app
 * finds no field to serve and closes the input session, which is a hide
 * (`HIDE_SOFT_INPUT_CLOSE_CURRENT_SESSION`). The edit box then asks for the
 * keyboard about 40 ms later. Measured on a CPH2481, Android 15: the hide
 * reached the keyboard at +2 ms, the new field at +46 ms and its show at
 * +60 ms, and in between the app had already started sliding the keyboard
 * away, so it dipped and came back over 400 ms.
 *
 * Up to Android 15 the hide passes through the keyboard before anything
 * moves: the app only animates once `InputMethodImpl.hideSoftInput` has run
 * `hideWindow`. Held here instead, the show that follows finds the keyboard
 * still up and nothing moves at all. When no show comes, the hide goes ahead
 * [HOLD_MS] late (or [AFTER_START_MS] after a new field of the same app
 * started, whose show is then on its way).
 *
 * From Android 16 the app animates first and tells the keyboard afterwards,
 * so there is nothing left to hold and [applies] says no.
 *
 * [replies] answer the hide's own result receiver when there is one, with the
 * [InputMethodManager] result the hide finally had.
 */
internal class HideGrace(
    private val schedule: (delayMs: Long, block: Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val hide: () -> Unit,
) {
    private val replies = ArrayList<(Int) -> Unit>()
    private var heldPackage: String? = null
    private val expire = Runnable { release() }

    val holding: Boolean get() = heldPackage != null

    /** Holds [packageName]'s hide; [hide] runs after [HOLD_MS] unless a show comes first. */
    fun hold(packageName: String, reply: ((Int) -> Unit)?) {
        if (reply != null) replies += reply
        if (heldPackage != null) return
        heldPackage = packageName
        schedule(HOLD_MS, expire)
    }

    /** A field of the app whose hide is held started: give its show a little longer. */
    fun fieldStarted(packageName: String?) {
        if (heldPackage == null || packageName != heldPackage) return
        unschedule(expire)
        schedule(AFTER_START_MS, expire)
    }

    /** A show arrived while a hide was held: the keyboard never went down. */
    fun shown() {
        if (holding) settle(InputMethodManager.RESULT_UNCHANGED_SHOWN)
    }

    /** Another hide went through on its own, which is the held one's answer too. */
    fun hiddenAnyway() {
        if (holding) settle(InputMethodManager.RESULT_HIDDEN)
    }

    /** Drops a held hide without running it: the service is going away. */
    fun cancel() {
        unschedule(expire)
        heldPackage = null
        replies.clear()
    }

    private fun release() {
        if (!holding) return
        settle(InputMethodManager.RESULT_HIDDEN)
        hide()
    }

    private fun settle(result: Int) {
        unschedule(expire)
        heldPackage = null
        val pending = replies.toList()
        replies.clear()
        pending.forEach { it(result) }
    }

    companion object {
        /** How long a hide waits for the show that cancels it. */
        const val HOLD_MS = 120L

        /** How long it waits once the app's next field has started. */
        const val AFTER_START_MS = 250L

        /** Apps seen hiding the keyboard only to show it again at once. */
        private val PACKAGES = setOf(
            // Editing a sent message.
            "com.facebook.orca",
        )

        /**
         * Whether an app hide in [packageName] is held. Only through Android 15
         * ([sdk] 35), where the hide still passes through the keyboard first.
         */
        fun applies(packageName: String?, sdk: Int = Build.VERSION.SDK_INT): Boolean =
            sdk <= Build.VERSION_CODES.VANILLA_ICE_CREAM && packageName in PACKAGES
    }
}
