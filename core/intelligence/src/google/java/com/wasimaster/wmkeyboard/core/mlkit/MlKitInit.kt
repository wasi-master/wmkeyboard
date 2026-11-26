package com.wasimaster.wmkeyboard.core.mlkit

import android.content.Context
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/**
 * ML Kit sets itself up from a ContentProvider, and providers are skipped for
 * a process that starts before the user has unlocked the phone. The keyboard
 * service is direct-boot aware, so that is exactly what happens after a
 * reboot: the IME comes up on the PIN screen, ML Kit's provider never runs,
 * and every ML Kit entry point in that process throws "MlKitContext has not
 * been initialized" for the rest of the process's life — the settings app
 * included, since it shares the process with the keyboard.
 *
 * Calling this once the user is unlocked puts the context back. Cheap and
 * idempotent: a no-op when the provider already did the work.
 */
object MlKitInit {

    /**
     * Only safe once credential storage is available — ML Kit's own components
     * read shared preferences as they come up. Failures are swallowed: the
     * callers all handle a missing ML Kit already, and a keyboard must not die
     * over a model manager.
     */
    fun ensure(context: Context) {
        // Switched off, ML Kit is never woken: no feature reaches it then, and
        // its components would start for nothing.
        if (!GoogleLibraries.usable) return
        runCatching { MlKitContext.initializeIfNeeded(context.applicationContext) }
    }
}
