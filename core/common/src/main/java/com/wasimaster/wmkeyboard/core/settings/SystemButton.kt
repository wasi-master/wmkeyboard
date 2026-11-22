package com.wasimaster.wmkeyboard.core.settings

import android.os.Build

/**
 * One of Android's own system buttons the floating button can press (#1170),
 * through the system buttons accessibility service: Back, recent apps, the
 * app drawer, and pulling down the notification shade or quick settings;
 * and since #1171, Home, locking the screen and the power menu. Stored by
 * [name], so a button a later build drops reads back as nothing.
 *
 * [minSdk] is the first Android version that has the button for another app
 * to press: the power menu arrived in Android 5, locking the screen in
 * Android 9, the app drawer in Android 12.
 */
enum class SystemButton(val minSdk: Int) {
    BACK(Build.VERSION_CODES.JELLY_BEAN),
    RECENTS(Build.VERSION_CODES.JELLY_BEAN),
    APP_DRAWER(Build.VERSION_CODES.S),
    NOTIFICATIONS(Build.VERSION_CODES.JELLY_BEAN),
    QUICK_SETTINGS(Build.VERSION_CODES.JELLY_BEAN_MR1),
    HOME(Build.VERSION_CODES.JELLY_BEAN),

    /** Turns the screen off and locks the phone, as a press of the power key does. */
    LOCK_SCREEN(Build.VERSION_CODES.P),

    /** The menu a long press of the power key opens. */
    POWER_DIALOG(Build.VERSION_CODES.LOLLIPOP),
    ;

    /** This phone's Android has the button. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= minSdk
}
