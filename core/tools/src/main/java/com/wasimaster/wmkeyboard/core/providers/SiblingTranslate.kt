package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.core.tools.Translation
import java.io.IOException

/**
 * WM Translate as a row of the translate list. The app is reached from
 * `:feature:ime`, which this module cannot see, so the keyboard installs a
 * [Bridge] at start-up as [PluginProviders] does for plugins. Until it does,
 * or while WM Translate is missing, the row does not answer and the list
 * moves on.
 */
object SiblingTranslate {
    interface Bridge {
        /**
         * Translates [text] into [target] from [source] ("" to detect) with
         * WM Translate's providers. Blocking; throws an [IOException] whose
         * message is for the user.
         */
        fun translate(text: String, target: String, source: String, isActive: () -> Boolean): Translation
    }

    @Volatile
    private var bridge: Bridge? = null

    fun install(bridge: Bridge) {
        this.bridge = bridge
    }

    fun translate(text: String, target: String, source: String, isActive: () -> Boolean = { true }): Translation =
        (bridge ?: throw IOException("WM Translate is not available")).translate(text, target, source, isActive)
}
