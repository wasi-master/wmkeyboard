package com.wasimaster.wmkeyboard.ime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The keyboard's own controls, in the notification shade.
 *
 * The problem it solves is old and specific: a keyboard only exists while
 * something is asking for text. Pin it to the screen (issue #58) and there is
 * still no way to get it back once it has gone down over a window with no
 * field in it, and no way to unpin it except by finding a field to type in
 * first. Hacker's Keyboard answered that with a notification, and this is the
 * same answer — a row in the shade with "Show keyboard" and "Unpin" on it.
 *
 * Off unless asked for ([com.wasimaster.wmkeyboard.core.settings.NotificationSettings.keyboardControls]),
 * because an ongoing notification is rent this app has to earn.
 */
object KeyboardControls {

    /** What the notification's buttons need from the running keyboard. */
    interface Host {
        /** Bring the keyboard back up. */
        fun showKeyboard()

        /** Pin or unpin, exactly as the toolbar's own toggle does. */
        fun setPinned(pinned: Boolean)

        /** Draw the controls again, after the user swiped them away. */
        fun repostControls()

        /**
         * Put the floating button up or take it down to match the settings
         * and the overlay grant, which the settings app may just have
         * changed. See [com.wasimaster.wmkeyboard.ime.floating.FloatingButton].
         */
        fun syncSummonButton()
    }

    /**
     * The running service, or null when there is none.
     *
     * A broadcast receiver may be started into a process where the keyboard
     * has never run — Android will happily deliver to a cold process — so
     * every use is null-checked rather than assumed. There is nothing useful a
     * "show the keyboard" button can do when no keyboard is bound anyway.
     */
    @Volatile
    var host: Host? = null

    private val _upOverOwnApp = MutableStateFlow(false)

    /**
     * True while the keyboard is on screen over this app's own settings with
     * no text field under it: summoned by the floating button or the shade,
     * or kept up by pinning. The settings app answers by opening its keyboard
     * test field, as if its button had been pressed, so the keys have a field
     * to type into and the frame pads the screen clear of them; without that,
     * the bottom rows of a screen sat behind the keyboard out of reach. Same
     * process, so a plain flow carries it.
     */
    val upOverOwnApp: StateFlow<Boolean> = _upOverOwnApp.asStateFlow()

    /** Set by the keyboard as it shows, changes field and hides; see [upOverOwnApp]. */
    fun setUpOverOwnApp(value: Boolean) {
        _upOverOwnApp.value = value
    }

    /**
     * True while the keyboard is on screen with a shake action set and is
     * listening for it (#1429). The settings app's shake-to-send-feedback
     * stands aside while this is true, so one shake over the settings app with
     * the keyboard up runs the keyboard's action and does not also open the
     * feedback form. Same process, so a plain field carries it.
     */
    @Volatile
    var shakeClaimed: Boolean = false
        private set

    /** Set by the keyboard's [ShakeDetector] as it starts and stops listening. */
    fun setShakeClaimed(value: Boolean) {
        shakeClaimed = value
    }

    /** Intent action: put the keyboard back on screen. */
    const val ACTION_SHOW = "com.wasimaster.wmkeyboard.action.SHOW_KEYBOARD"

    /** Intent action: stop keeping it on screen. */
    const val ACTION_UNPIN = "com.wasimaster.wmkeyboard.action.UNPIN_KEYBOARD"

    /** Intent action: open Android's own input-method picker. */
    const val ACTION_PICKER = "com.wasimaster.wmkeyboard.action.INPUT_METHOD_PICKER"

    /** Intent action: the user swiped the controls away; put them back. */
    const val ACTION_DISMISSED = "com.wasimaster.wmkeyboard.action.CONTROLS_DISMISSED"

    /**
     * Draws (or redraws) the controls.
     *
     * [pinned] only changes the words and which buttons are worth showing: the
     * notification is about the keyboard, not about the pin.
     */
    fun post(context: Context, pinned: Boolean) {
        WmNotifications.post(context, NotificationKind.KEYBOARD, NotificationIds.KEYBOARD) {
            setContentTitle(context.getString(R.string.ime_controls_title))
            setContentText(
                context.getString(
                    if (pinned) R.string.ime_controls_pinned_body else R.string.ime_controls_body,
                ),
            )
            setOngoing(true)
            setSilent(true)
            setShowWhen(false)
            addAction(
                0,
                context.getString(R.string.ime_controls_show),
                broadcast(context, ACTION_SHOW),
            )
            if (pinned) {
                addAction(
                    0,
                    context.getString(R.string.ime_controls_unpin),
                    broadcast(context, ACTION_UNPIN),
                )
            }
            addAction(
                0,
                context.getString(R.string.ime_controls_switch),
                broadcast(context, ACTION_PICKER),
            )
            setContentIntent(broadcast(context, ACTION_SHOW))
            // Android 14 lets the user swipe away even an ongoing notification.
            // These controls are the way back to a keyboard that has gone down
            // over a window with no field, so losing them to a stray swipe is
            // losing that way back (issue #374): they come straight back. The
            // switch in Settings > Notifications is how they are turned off.
            setDeleteIntent(broadcast(context, ACTION_DISMISSED))
        }
    }

    /** Takes the controls back down. */
    fun clear(context: Context) {
        WmNotifications.cancel(context, NotificationIds.KEYBOARD)
    }

    private fun broadcast(context: Context, action: String): PendingIntent {
        val intent = Intent(context, KeyboardControlReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Where the shade's buttons land.
 *
 * Deliberately thin: it hands the request to the running keyboard and does
 * nothing itself. The one exception is the input-method picker, which is a
 * platform dialog any component of the current IME's app may raise, and which
 * is useful in exactly the case where the keyboard is *not* running.
 */
class KeyboardControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            KeyboardControls.ACTION_SHOW -> KeyboardControls.host?.showKeyboard()
            KeyboardControls.ACTION_UNPIN -> KeyboardControls.host?.setPinned(false)
            KeyboardControls.ACTION_DISMISSED -> KeyboardControls.host?.repostControls()
            KeyboardControls.ACTION_PICKER -> {
                val manager = context.getSystemService(InputMethodManager::class.java)
                runCatching { manager?.showInputMethodPicker() }
            }
        }
    }
}
