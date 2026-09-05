package com.wasimaster.wmkeyboard.core.settings

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.settings.R

/**
 * What arms power saving by itself, on top of the manual switch.
 *
 * The default is [SYSTEM_SAVER] rather than a battery percentage: it only ever
 * fires because the user already asked the *system* to save power, so the
 * keyboard changing its feel is an answer to something they did, not a surprise
 * at some threshold they never set.
 */
enum class PowerSavingTrigger(@StringRes val labelRes: Int) {
    /** Nothing but the manual switch. */
    OFF(R.string.core_settings_power_saving_trigger_off_label),

    /** The device's own battery saver is on. */
    SYSTEM_SAVER(R.string.core_settings_power_saving_trigger_system_saver_label),

    /** The battery is at or under [PowerSavingSettings.batteryPercent]. */
    LOW_BATTERY(R.string.core_settings_power_saving_trigger_low_battery_label),

    /** Either of the two above. */
    EITHER(R.string.core_settings_power_saving_trigger_either_label),
}

/**
 * What the device's power situation is right now, as the keyboard sees it.
 *
 * A plain value so the decision ([PowerSavingSettings.appliesTo]) is a pure
 * function of settings and state, testable without a `Context`, a broadcast or
 * a `PowerManager`. `core.power.PowerSaver` is what fills it in on device.
 *
 * [batteryPercent] is -1 when the level has not been read yet — treated as "not
 * low", so an unread battery never switches features off.
 */
data class DevicePowerState(
    val batteryPercent: Int = -1,
    val charging: Boolean = false,
    val systemSaver: Boolean = false,
)

/**
 * Power saving: drop the parts of the keyboard that cost battery — the
 * vibrator, the audio, per-keystroke prediction work, background watchers and
 * network fetches — either on demand or when the battery gets low.
 *
 * Grouped into its own class rather than sitting flat on [KeyboardSettings]
 * because that class's primary constructor is at the JVM's 255-argument ceiling
 * (see [ToolbarBehavior]). Each field still persists under its own DataStore
 * key via the matching setter.
 *
 * Every `drop*` flag is a separate switch on purpose: what counts as an
 * acceptable loss is personal. Someone who types by feel will keep haptics and
 * give up gesture typing; someone else will do exactly the reverse. The
 * defaults give up the things that cost the most and are noticed the least.
 */
data class PowerSavingSettings(
    /**
     * Turned on by hand, from the toolbar tool or from settings. Persisted, so
     * it survives the keyboard being torn down and rebuilt between fields —
     * like incognito, it stays on until the user turns it off.
     */
    val manual: Boolean = false,
    /** What switches it on by itself. */
    val trigger: PowerSavingTrigger = PowerSavingTrigger.SYSTEM_SAVER,
    /** The level [PowerSavingTrigger.LOW_BATTERY] fires at or below, in percent. */
    val batteryPercent: Int = 20,
    /**
     * While the charger is in, an automatic trigger is ignored — there is
     * nothing to save. The manual switch is unaffected: someone who turned it
     * on by hand meant it.
     */
    val offWhileCharging: Boolean = true,

    /** Silence the vibrator. The single biggest per-keystroke power draw. */
    val dropHaptics: Boolean = true,
    /** Silence key click sounds. */
    val dropKeySound: Boolean = true,
    /**
     * Cut every animation and transition (the same switch accessibility's
     * reduced motion throws), so nothing is being drawn between frames.
     */
    val dropAnimations: Boolean = true,
    /** Stop drawing the comet trail behind a glide. */
    val dropGlideTrail: Boolean = true,
    /** Stop drawing the character bubble over each pressed key. Off by default — it is cheap. */
    val dropKeyPopup: Boolean = false,
    /**
     * Turn gesture typing off entirely. Decoding a swipe is the most expensive
     * thing the keyboard does, but it is also why many people installed it, so
     * this is off by default and [dropGlideTrail] carries the cheap half.
     */
    val dropGestureTyping: Boolean = false,
    /** Stop scanning what you type for emoji to suggest. */
    val dropEmojiPrediction: Boolean = true,
    /**
     * Stop the smart chips — the sums, unit and currency conversions and tool
     * keywords matched against every word you type.
     */
    val dropSmartChips: Boolean = true,
    /**
     * Stop the optional network fetches that happen without being asked: link
     * previews for copied URLs and for scanned QR codes, and the dictionary's
     * look-up-on-selection. Tools you open by hand still work.
     */
    val dropBackgroundNetwork: Boolean = true,
    /** Stop watching the screenshot folder for new images to offer in the clipboard. */
    val dropScreenshotWatch: Boolean = true,
    /**
     * Fall back off the on-device models: dictation goes to the system
     * recognizer instead of running Whisper locally, and the handwriting swipe
     * goes back to gesture-typing words. Both are heavy inference on the CPU.
     */
    val dropOnDeviceModels: Boolean = true,
    /**
     * Stop counting typing statistics. Off by default like [dropKeyPopup]:
     * the counters are a few integer bumps per keystroke, so the saving is
     * unmeasured and speculative, but someone squeezing the last percent out
     * of a battery can give the statistics up here. Days spent saving simply
     * record nothing — they never drag the speed averages down.
     */
    val dropTypingStats: Boolean = false,
    /**
     * Stop pinning the media tool to the toolbar while music plays. The pin
     * costs nothing to draw, but keeping it accurate means holding a live
     * media-session listener for as long as the keyboard is up, and that is
     * the part worth giving back on a low battery.
     */
    val dropMediaPin: Boolean = true,
) {
    /** Whether power saving should be in force given the device's [state]. */
    fun appliesTo(state: DevicePowerState): Boolean {
        if (manual) return true
        if (offWhileCharging && state.charging) return false
        val low = state.batteryPercent in 0..batteryPercent
        return when (trigger) {
            PowerSavingTrigger.OFF -> false
            PowerSavingTrigger.SYSTEM_SAVER -> state.systemSaver
            PowerSavingTrigger.LOW_BATTERY -> low
            PowerSavingTrigger.EITHER -> state.systemSaver || low
        }
    }

    /** Whether any feature is actually given up — a saving mode that drops nothing is inert. */
    val dropsAnything: Boolean
        get() = dropHaptics || dropKeySound || dropAnimations || dropGlideTrail ||
            dropKeyPopup || dropGestureTyping || dropEmojiPrediction || dropSmartChips ||
            dropBackgroundNetwork || dropScreenshotWatch || dropOnDeviceModels ||
            dropTypingStats || dropMediaPin
}

/**
 * The settings as they apply while power saving is in force.
 *
 * Written the same way as [restrictedToDirectBoot]: a view applied on the way
 * out of the repository, never persisted, so the moment the charger goes in
 * every setting the user actually chose is back without anything having been
 * rewritten. Turning it off cannot lose their configuration, because it never
 * touched it.
 *
 * Switching features off *in the settings object* rather than at each use site
 * means the renderer, the suggestion engine, the clipboard store and the tool
 * handlers all agree on what exists without any of them knowing that power
 * saving is a thing.
 */
fun KeyboardSettings.underPowerSaving(): KeyboardSettings {
    val ps = powerSaving
    return copy(
        hapticFeedback = if (ps.dropHaptics) false else hapticFeedback,
        keySound = if (ps.dropKeySound) false else keySound,
        // Also the accessibility switch, which is the one every animation
        // already reads — there is no second motion flag to set.
        reduceMotion = reduceMotion || ps.dropAnimations,
        popup = if (ps.dropKeyPopup) popup.copy(enabled = false) else popup,
        // A zero-opacity trail is never drawn, so the gesture keeps working
        // while the per-frame overlay stops.
        gesture = if (ps.dropGlideTrail) gesture.copy(trailOpacity = 0f) else gesture,
        gestureTyping = if (ps.dropGestureTyping) false else gestureTyping,
        emojiPrediction = if (ps.dropEmojiPrediction) false else emojiPrediction,
        smartSuggestions = if (ps.dropSmartChips) false else smartSuggestions,
        clipboard = clipboard.copy(
            linkPreviews = if (ps.dropBackgroundNetwork) false else clipboard.linkPreviews,
            userScreenshots = if (ps.dropScreenshotWatch) false else clipboard.userScreenshots,
        ),
        qrScanLinkPreviews = if (ps.dropBackgroundNetwork) false else qrScanLinkPreviews,
        dictionaryAutoLookup = if (ps.dropBackgroundNetwork) false else dictionaryAutoLookup,
        typingStatsEnabled = if (ps.dropTypingStats) false else typingStatsEnabled,
        mediaControl = if (ps.dropMediaPin) {
            mediaControl.copy(pinWhilePlaying = false)
        } else {
            mediaControl
        },
        whisper = if (ps.dropOnDeviceModels) whisper.copy(engine = "system") else whisper,
        letterSwipeAction = if (ps.dropOnDeviceModels &&
            letterSwipeAction == LetterSwipeAction.HANDWRITE
        ) {
            LetterSwipeAction.TYPE_WORDS
        } else {
            letterSwipeAction
        },
    )
}
