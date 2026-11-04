package com.wasimaster.wmkeyboard.app.modules

import android.app.Activity
import java.util.concurrent.ConcurrentHashMap

/**
 * The Play Store's on-demand modules as the Storage screen sees them: which
 * are installed, and a way to give one back. Filled in by the Play build's
 * [installOnDemandDelivery]; every other build has no modules and keeps the
 * empty defaults, so the Storage row stays empty there.
 */
internal object PlayModules {

    /** The names of the modules Play has installed, as `SplitInstallManager.installedModules` gives them. */
    @Volatile
    var installed: () -> Set<String> = { emptySet() }

    /**
     * Asks Play to remove [names]. Play does it later, within a day or so and
     * usually while the app is in the background, so a removed module stays
     * installed, and listed, until then.
     */
    @Volatile
    var uninstall: (List<String>) -> Unit = {}

    /** Modules asked to go this process, which Play has not removed yet. */
    val removing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Shows Play's question for any module download held for the user's yes.
     * The settings app calls it whenever it comes to the front, since Play
     * asks only over an activity and the keyboard that wanted the module has
     * none.
     */
    @Volatile
    var confirmPending: (Activity) -> Unit = {}
}
