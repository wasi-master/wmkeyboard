package com.wasimaster.wmkeyboard.core.shell

import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Lifts the hidden-API filter for this process, once, before reflection on a
 * framework class no SDK lists: MIUI's freeform options factory, mostly. Only
 * the launcher's window routes call it, and only on the skins that need it.
 */
object HiddenApi {
    private val exempted: Boolean by lazy {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
            runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }.getOrDefault(false)
    }

    /** True when reflection on hidden members may now succeed. */
    fun exempt(): Boolean = exempted
}
