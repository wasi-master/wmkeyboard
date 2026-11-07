package com.wasimaster.wmkeyboard.core.settings

import android.os.Build

/**
 * One of Android's own system buttons the floating button can press (#1170),
 * through the system buttons accessibility service: Back, recent apps, the
 * app drawer, and pulling down the notification shade or quick settings.
 * Stored by [name], so a button a later build drops reads back as nothing.
 *
 * [minSdk] is the first Android version that has the button for another app
 * to press: the app drawer arrived in Android 12.
 */
enum class SystemButton(val minSdk: Int) {
    BACK(Build.VERSION_CODES.JELLY_BEAN),
    RECENTS(Build.VERSION_CODES.JELLY_BEAN),
    APP_DRAWER(Build.VERSION_CODES.S),
    NOTIFICATIONS(Build.VERSION_CODES.JELLY_BEAN),
    QUICK_SETTINGS(Build.VERSION_CODES.JELLY_BEAN_MR1),
    ;

    /** This phone's Android has the button. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= minSdk
}
