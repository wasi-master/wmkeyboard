package com.wasimaster.wmkeyboard.core.barcode

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.modules.GoogleLibraries

/** Which reader the QR scanner reads codes with: ML Kit's or zxing-cpp. */
object BarcodeEngine {
    /**
     * Whether ML Kit's barcode reader can run here, so zxing-cpp is a choice
     * rather than the only reader. The F-Droid build has no ML Kit at all; a
     * Play build carries the Play services twin, whose model comes from Play
     * services, so it needs them; every other build bundles the model.
     */
    val mlKitUsable: Boolean
        get() = BuildConfig.ENABLE_ML_KIT_SCANNERS && GoogleLibraries.usable &&
            (!BuildConfig.ENABLE_PLAY_STORE || PlayServices.available)

    /** Whether to read with zxing-cpp: when picked ([pickedZxing]) or when ML Kit cannot run. */
    fun useZxing(pickedZxing: Boolean): Boolean = pickedZxing || !mlKitUsable
}
