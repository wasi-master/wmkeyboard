package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.addons.KeymanLayoutPackDownloader
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.layout.KeymanLayoutPack
import com.wasimaster.wmkeyboard.core.net.NetworkWatcher
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * Fetches the Keyman grids once a layout that needs them is on. Here rather than
 * on the switch, because a layout gets switched on from the language screens,
 * onboarding, a restored backup and a sync, and an app update can take away the
 * grids of one that already was. Until they arrive that layout draws QWERTY, so
 * this also tries again each time the phone comes back online.
 *
 * Costs a settings read for anyone without a Keyman layout on, and nothing at all
 * on a build that carries the grids.
 */
internal object KeymanLayoutPackWatcher {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, failure -> DebugLog.e("KeymanLayoutPack", "watch failed", failure) },
    )

    @Volatile
    private var started = false

    fun start(context: Context, repository: SettingsRepository) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            repository.settings
                .map { settings -> settings.enabledLayoutIds.any { it.startsWith(KeymanLayoutPack.LAYOUT_ID_PREFIX) } }
                .distinctUntilChanged()
                .collectLatest { needed ->
                    if (needed && KeymanLayoutPack.needsDownload) fetchWhenOnline(app)
                }
        }
    }

    private suspend fun fetchWhenOnline(context: Context) {
        val network = NetworkWatcher(context)
        network.start()
        try {
            // The pack's revision is in here so that its arrival ends the watch
            // at once, rather than at the next change of network.
            network.state
                .map { it.online }
                .distinctUntilChanged()
                .combine(KeymanLayoutPack.revision) { online, _ -> online }
                .takeWhile { KeymanLayoutPack.needsDownload }
                .collect { online -> if (online) KeymanLayoutPackDownloader.start(context) }
        } finally {
            network.stop()
        }
    }
}
