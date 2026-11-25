package com.wasimaster.wmkeyboard.app.modules

import android.os.Handler
import android.os.Looper
import com.google.android.play.core.splitinstall.SplitInstallException
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.modules.ModuleFailure
import java.util.concurrent.ConcurrentHashMap

/**
 * How every Play module of this app is asked for: the natives-table gates
 * ([installOnDemandDelivery]), the on-device AI's ([installLlmDelivery]) and
 * translation's ([installTranslateDelivery]). One place, so no download is
 * left out of the rules:
 *
 * - A refusal that may pass by itself ([ModuleFailure.playRetrySoon]: the
 *   connection, Play's service restarting, too many downloads at once) is
 *   asked again a few times, waiting longer each time, before it is
 *   reported. Until then the part reads as on its way.
 * - Any other refusal is reported at once, with Play's code, for
 *   [ModuleFailure] to say why and what to do.
 *
 * Whether a later request may show "downloading" again is the gate's to
 * decide: one that Play will only refuse again ([ModuleFailure.playWillRefuseAgain])
 * keeps saying why (Telegram 12443).
 */
internal object PlayRequests {

    /** Waits before the second, third and fourth try of a refusal that may pass. */
    private val BACKOFF_MS = longArrayOf(2_000L, 8_000L, 30_000L)

    private val main = Handler(Looper.getMainLooper())

    /** Retries spent on each module since it was last asked for by a caller. */
    private val retries = ConcurrentHashMap<String, Int>()

    /**
     * Asks Play for [module] on a caller's behalf. [onRefused] gets Play's
     * code once the refusal stands; a request that becomes a session is
     * followed by the gate's own listener, which hands a failed session to
     * [failed].
     */
    fun request(manager: SplitInstallManager, module: String, onRefused: (Int) -> Unit) {
        retries[module] = 0
        start(manager, module, onRefused)
    }

    private fun start(manager: SplitInstallManager, module: String, onRefused: (Int) -> Unit) {
        // Re-requesting the module of an active session just returns that
        // session. A failure that never becomes one (no Play Store, an
        // install Play does not own) only reaches this listener.
        manager.startInstall(SplitInstallRequest.newBuilder().addModule(module).build())
            .addOnFailureListener { e ->
                val code = (e as? SplitInstallException)?.errorCode ?: ModuleFailure.PLAY_INTERNAL_ERROR
                DebugLog.w(TAG, "$module: Play refused the install, error $code")
                failed(manager, module, code, onRefused)
            }
    }

    /**
     * Play's [code] for [module], from a refused request or a failed
     * session. True while a retry is on its way, which the gate shows as
     * still downloading; false once [onRefused] has the code.
     */
    fun failed(manager: SplitInstallManager, module: String, code: Int, onRefused: (Int) -> Unit): Boolean {
        val spent = retries[module] ?: 0
        if (ModuleFailure.playRetrySoon(code) && spent < BACKOFF_MS.size) {
            retries[module] = spent + 1
            DebugLog.i(TAG, "$module: asking Play again in ${BACKOFF_MS[spent] / 1000} s (error $code)")
            main.postDelayed({ start(manager, module, onRefused) }, BACKOFF_MS[spent])
            return true
        }
        onRefused(code)
        return false
    }

    /** [module] arrived: its next failure starts its retries afresh. */
    fun installed(module: String) {
        retries.remove(module)
    }

    private const val TAG = "modules"
}
