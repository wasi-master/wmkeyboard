package com.wasimaster.wmkeyboard.core.settings

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.settings.R

/**
 * What arms power saving by itself, on top of the manual switch.
 *
 * The default is [OFF]: power saving changes how the keyboard feels (no
 * vibration, no sounds, no animations), and that must never happen unless the
 * user opted in here. Even following the system battery saver surprised people
 * who turned that on for the phone, not for their keyboard.
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
    val trigger: PowerSavingTrigger = PowerSavingTrigger.OFF,
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
    /**
     * Stop floating predicted words over the keys (the octopus). On by default:
     * it predicts and redraws across the whole board on every keystroke, which
     * is exactly the kind of work a flat battery wants back, and the suggestion
     * strip is still saying the same thing.
     */
    val dropOctopus: Boolean = true,
    /** Stop drawing the character bubble over each pressed key. Off by default — it is cheap. */
    val dropKeyPopup: Boolean = false,
    /**
     * Stop a glass theme asking the system to blur the app behind the
     * keyboard and under its popups. On by default: that blur is GPU work in
     * the system compositor on every frame the keyboard is up. The theme's
     * glass keeps its rim, its grain and its wallpaper or image backdrop,
     * which cost nothing per frame.
     *
     * Read by the service, not by [underPowerSaving]: the blur belongs to the
     * theme, and the settings object has no field for it to clear.
     */
    val dropGlassBlur: Boolean = true,
    /**
     * Turn gesture typing off entirely. Decoding a swipe is the most expensive
     * thing the keyboard does, but it is also why many people installed it, so
     * this is off by default and [dropGlideTrail] carries the cheap half.
     */
    val dropGestureTyping: Boolean = false,
    /**
     * Stop scanning what you type for emoji to suggest. Off by default: the
     * scan is a dictionary look-up on a word already in hand, and a strip
     * that stops offering emoji reads as broken rather than as saving power.
     */
    val dropEmojiPrediction: Boolean = false,
    /**
     * Stop the smart chips — the sums, unit and currency conversions and tool
     * keywords matched against every word you type. Off by default for the
     * same reason as [dropEmojiPrediction]: a handful of pattern matches on
     * the current word is not where a battery goes, and the chips vanishing
     * is what users reported as a bug.
     */
    val dropSmartChips: Boolean = false,
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
    /**
     * Hide the floating button (#1160) while saving. Off by default: the
     * button is how some people open the keyboard at all, and a button that
     * vanishes at 20% reads as a bug. What it costs is an extra window the
     * system composites over every app, so the aggressive bundle takes it.
     * Its quick-menu animation already stills with [dropAnimations].
     */
    val dropFloatingButton: Boolean = false,
    /**
     * Still a theme's moving parts (#1160): animated backgrounds, the board
     * effects, key lighting, the touch bloom and press particles. On by
     * default: these redraw the board on every frame for as long as the
     * keyboard is up, which is the most a theme can cost, and the theme keeps
     * its colours, images and key materials. Separate from [dropAnimations]
     * so someone can keep the panel transitions and still save here.
     */
    val dropMovingThemes: Boolean = true,
    /**
     * Let go of loaded models soon after the keyboard hides (#1163) rather
     * than after the usual two minutes: the language model, speech, the
     * translator, OCR and the rest of what the keyboard's idle release
     * frees. Off by default and only in the aggressive bundle: a resident
     * model costs memory rather than battery, and loading it again for the
     * next field is the part that does cost battery.
     */
    val dropWarmModels: Boolean = false,
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
            dropKeyPopup || dropGlassBlur || dropGestureTyping || dropEmojiPrediction || dropSmartChips ||
            dropOctopus ||
            dropBackgroundNetwork || dropScreenshotWatch || dropOnDeviceModels ||
            dropTypingStats || dropMediaPin || dropFloatingButton || dropMovingThemes || dropWarmModels
}

/**
 * How long the keyboard waits, once hidden, before it lets go of its loaded
 * models: [normalMs] as usual, [SAVING_IDLE_RELEASE_MS] while power saving is
 * in force ([saving]) with [PowerSavingSettings.dropWarmModels] on. Long
 * enough that a hop from one field to the next does not reload anything.
 */
fun PowerSavingSettings.idleReleaseMs(saving: Boolean, normalMs: Long): Long =
    if (saving && dropWarmModels) minOf(normalMs, SAVING_IDLE_RELEASE_MS) else normalMs

/** The idle release's wait under [PowerSavingSettings.dropWarmModels]. */
const val SAVING_IDLE_RELEASE_MS = 15_000L

/**
 * The named bundles the settings page offers over the individual `drop*`
 * switches: twelve switches is the definition of settings bloat, so most
 * people pick one of these and only the curious open the switches
 * themselves. [CUSTOM] is what the switches read as once they match no
 * bundle; picking it writes nothing.
 */
enum class PowerSavingPreset { LIGHT, BALANCED, AGGRESSIVE, CUSTOM }

/**
 * The switches set as [preset] stands for them, with everything that is not
 * a drop switch left as it is. [PowerSavingPreset.CUSTOM] returns the
 * receiver unchanged. The key popup and the typing statistics are never
 * dropped by a bundle: the popup is cheap to draw and the counters are a few
 * integer bumps per keystroke, so neither saves anything measurable.
 */
fun PowerSavingSettings.withPreset(preset: PowerSavingPreset): PowerSavingSettings {
    if (preset == PowerSavingPreset.CUSTOM) return this
    val light = copy(
        dropHaptics = true,
        dropKeySound = true,
        dropAnimations = true,
        dropGlideTrail = true,
        dropOctopus = true,
        dropGlassBlur = true,
        dropScreenshotWatch = true,
        dropMediaPin = true,
        dropMovingThemes = true,
        dropFloatingButton = false,
        dropWarmModels = false,
        dropBackgroundNetwork = false,
        dropOnDeviceModels = false,
        dropGestureTyping = false,
        dropEmojiPrediction = false,
        dropSmartChips = false,
        dropKeyPopup = false,
        dropTypingStats = false,
    )
    if (preset == PowerSavingPreset.LIGHT) return light
    val balanced = light.copy(dropBackgroundNetwork = true, dropOnDeviceModels = true)
    if (preset == PowerSavingPreset.BALANCED) return balanced
    return balanced.copy(
        dropGestureTyping = true,
        dropEmojiPrediction = true,
        dropSmartChips = true,
        dropFloatingButton = true,
        dropWarmModels = true,
    )
}

/** The bundle the drop switches currently match, or [PowerSavingPreset.CUSTOM]. */
val PowerSavingSettings.preset: PowerSavingPreset
    get() = PowerSavingPreset.entries.firstOrNull { it != PowerSavingPreset.CUSTOM && withPreset(it) == this }
        ?: PowerSavingPreset.CUSTOM

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
        haptics = if (ps.dropHaptics) haptics.copy(enabled = false) else haptics,
        sound = if (ps.dropKeySound) sound.copy(enabled = false) else sound,
        // Also the accessibility switch, which is the one every animation
        // already reads — there is no second motion flag to set.
        reduceMotion = reduceMotion || ps.dropAnimations,
        // The theme's own motion has a flag of its own, so it can stop while
        // the panel transitions carry on. ANDed, like reduceMotion is ORed.
        accessibility = if (ps.dropMovingThemes) {
            accessibility.copy(themeMotion = false)
        } else {
            accessibility
        },
        // The floating button is drawn by this same service from this same
        // object, so a disabled copy hides it and the stored switch brings it
        // back. Its menu has an animation of its own that reduce motion does
        // not reach (it is a separate window), so that stills here too.
        summonButton = when {
            ps.dropFloatingButton -> summonButton.copy(enabled = false)
            ps.dropAnimations -> summonButton.copy(menuAnimation = FloatingMenuAnimation.NONE)
            else -> summonButton
        },
        popup = if (ps.dropKeyPopup) popup.copy(enabled = false) else popup,
        // A zero-opacity trail is never drawn, so the gesture keeps working
        // while the per-frame overlay stops.
        gesture = if (ps.dropGlideTrail) gesture.copy(trailOpacity = 0f) else gesture,
        gestureTyping = if (ps.dropGestureTyping) false else gestureTyping,
        octopus = if (ps.dropOctopus) octopus.copy(enabled = false) else octopus,
        emojiPrediction = if (ps.dropEmojiPrediction) false else emojiPrediction,
        smartSuggestions = if (ps.dropSmartChips) false else smartSuggestions,
        clipboard = clipboard.copy(
            linkPreviews = if (ps.dropBackgroundNetwork) false else clipboard.linkPreviews,
            userScreenshots = if (ps.dropScreenshotWatch) false else clipboard.userScreenshots,
        ),
        scanner = if (ps.dropBackgroundNetwork) {
            scanner.copy(qrScanLinkPreviews = false)
        } else {
            scanner
        },
        dictionaryAutoLookup = if (ps.dropBackgroundNetwork) false else dictionaryAutoLookup,
        typingStatsEnabled = if (ps.dropTypingStats) false else typingStatsEnabled,
        mediaControl = if (ps.dropMediaPin) {
            mediaControl.copy(pinWhilePlaying = false)
        } else {
            mediaControl
        },
        // A link held while the keyboard is hidden is background network work
        // in the plainest sense. The link itself survives: it just goes back
        // to living only while the keyboard is up.
        kdeConnect = if (ps.dropBackgroundNetwork && kdeConnect.lifetime == KdeLinkLifetime.ALWAYS) {
            kdeConnect.copy(lifetime = KdeLinkLifetime.KEYBOARD)
        } else {
            kdeConnect
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
