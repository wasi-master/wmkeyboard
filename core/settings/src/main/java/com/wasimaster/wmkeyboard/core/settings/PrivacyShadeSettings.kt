package com.wasimaster.wmkeyboard.core.settings

/**
 * The privacy shade (#1242): a cover the keyboard draws over the app above
 * it, so someone beside you cannot read the conversation while you type.
 * Drag its edge up to see the last few lines, and back down to cover them.
 *
 * Nested like [NetworkLogSettings]: one slot in [KeyboardSettings], flat
 * DataStore keys.
 */
data class PrivacyShadeSettings(
    /**
     * Whether the shade is up. Kept as a setting so it stays up from one
     * field to the next, and after the keyboard restarts, until the tool is
     * pressed again: a cover that quietly fell away on its own would be worse
     * than none.
     */
    val on: Boolean = false,
    val style: PrivacyShadeStyle = PrivacyShadeStyle.DARK,
    /**
     * How much of the app shows through, as the cover's opacity from
     * [MIN_OPACITY] to 1. Just under solid by default: your own text stays
     * faintly readable from where you hold the phone, and not from a seat away.
     */
    val opacity: Float = DEFAULT_OPACITY,
    /**
     * Taps and scrolls on the shade reach the app under it. Off, the shade
     * takes them instead, and holding a finger on it lifts it until you let go.
     */
    val touchThrough: Boolean = true,
    /**
     * Apps the shade comes up in by itself, as soon as one of their fields
     * opens the keyboard, whether or not it is switched on; package names.
     * Its close button takes it down until the keyboard opens in another app.
     */
    val apps: Set<String> = emptySet(),
    /**
     * The screen does not time out while the shade is up (#1428), for a video
     * or a download left running under it. Off by default: it costs battery.
     */
    val keepScreenOn: Boolean = false,
    /** The screen goes to its lowest brightness while the shade is up (#1428). */
    val dimScreen: Boolean = false,
    /**
     * Minutes after which the shade takes itself down, counted from when it
     * came up; 0 never (#1428). One of [TIMER_CHOICES].
     */
    val timerMinutes: Int = 0,
) {
    companion object {
        const val DEFAULT_OPACITY = 0.94f
        const val MIN_OPACITY = 0.5f

        /** What the "Take the shade down after" row offers, in minutes; 0 is never. */
        val TIMER_CHOICES = listOf(0, 5, 15, 30, 45, 60, 120)
    }
}

/** The shade's colour. Stored by [id]. */
enum class PrivacyShadeStyle(val id: String) {
    DARK("dark"),
    /** Pure black: on an OLED screen the pixels under it are off (#1428). */
    BLACK("black"),
    GREY("grey"),
    LIGHT("light"),
    WARM("warm"),
    ;

    companion object {
        fun of(id: String?): PrivacyShadeStyle? = entries.firstOrNull { it.id == id }
    }
}
