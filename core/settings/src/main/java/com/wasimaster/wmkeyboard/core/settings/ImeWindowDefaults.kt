package com.wasimaster.wmkeyboard.core.settings

/**
 * Whether the keyboard's window starts out full height
 * ([LayoutBehaviorSettings.fullHeightWindow]). Not on Huawei: HarmonyOS 4.2
 * (nova 9, Android 12) animates the whole window when the keyboard hides
 * instead of the keyboard's part of it, so the keys jumped up, stopped halfway
 * and vanished (#1297). Honor's own builds are not known to, so they keep it.
 */
internal fun fullHeightImeWindowByDefault(): Boolean =
    !android.os.Build.MANUFACTURER.orEmpty().equals("HUAWEI", ignoreCase = true)
