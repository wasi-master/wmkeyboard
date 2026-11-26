package com.wasimaster.wmkeyboard.core.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * How many of this app's activities are started (visible, or about to be) in
 * this process.
 *
 * The keyboard and the settings app share one process, so the keyboard has to
 * ask before doing anything that ends it: recycling the process to get back
 * memory a native library will not return (the GPU model runtime) is fine
 * while the user is typing elsewhere, and not while they are looking at the
 * AI chat screen. Installed from `Application.onCreate`, so it sees every
 * activity the process ever starts.
 *
 * It is also where what only a screen needs lets go once the screens are gone
 * ([onAllStopped]). The process outlives the settings app by days, because it
 * is the keyboard's, and a cache a settings screen built (the settings search
 * index and what it builds once searched, about 4 MB in a heap dump) used to
 * stay with it for all of them.
 */
object StartedActivities : Application.ActivityLifecycleCallbacks {

    @Volatile
    var count = 0
        private set

    /**
     * How many of this app's activities exist, started or not: created and not
     * yet destroyed. A settings screen left with Home is stopped but still
     * here, and comes back with every screen's state as it was, so what such a
     * screen loaded once and reads again without asking cannot go while it is.
     */
    @Volatile
    var alive = 0
        private set

    private var installed = false

    /** What [releaseIfNoneStarted] runs, by a name each owner registers under once. */
    private val releases = ConcurrentHashMap<String, () -> Unit>()

    /** Lazy, so a JVM test that never starts an activity never touches a Looper. */
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val runReleases = Runnable { releaseIfNoneStarted() }

    /**
     * How long no activity has to be started before [onAllStopped] runs.
     *
     * Long enough that a trip to another app and back, a rotation, or one
     * screen of this app handing over to another (the next starts before the
     * last stops, but not always) keeps everything; short enough that the
     * memory is back long before the keyboard's own idle release, two minutes
     * after it was last on screen.
     */
    private const val RELEASE_DELAY_MS = 30_000L

    @Synchronized
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    /**
     * Runs [release] once [RELEASE_DELAY_MS] has passed with no activity of
     * this app started, and every time after that this happens again, until
     * the process ends. For memory that only screens use and that the next
     * screen builds again when it needs it: [release] should drop a reference,
     * or hand the work to a thread of its own; it runs on the main thread.
     *
     * One registration per [key]: registering again under the same key
     * replaces the earlier one, so an owner can register each time it fills
     * its cache.
     */
    fun onAllStopped(key: String, release: () -> Unit) {
        releases[key] = release
    }

    /**
     * Runs every [onAllStopped] release now, when no activity is started; a
     * no-op while one is. The keyboard calls it when it lets go of its own
     * engines, so a cache a screen built and the keyboard then used too goes
     * with them.
     */
    fun releaseIfNoneStarted() {
        if (count > 0) return
        for (release in releases.values) runCatching { release() }
    }

    override fun onActivityStarted(activity: Activity) {
        count++
        if (releases.isNotEmpty()) handler.removeCallbacks(runReleases)
    }

    override fun onActivityStopped(activity: Activity) {
        count = (count - 1).coerceAtLeast(0)
        scheduleReleases()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        alive++
    }

    override fun onActivityDestroyed(activity: Activity) {
        alive = (alive - 1).coerceAtLeast(0)
        // A screen left with Home and closed from Recents later: what waited
        // for it to be gone can go now, the stop's run having been too early.
        scheduleReleases()
    }

    private fun scheduleReleases() {
        if (count > 0 || releases.isEmpty()) return
        handler.removeCallbacks(runReleases)
        handler.postDelayed(runReleases, RELEASE_DELAY_MS)
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
