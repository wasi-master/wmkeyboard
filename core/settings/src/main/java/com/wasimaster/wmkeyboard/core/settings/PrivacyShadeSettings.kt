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
) {
    companion object {
        const val DEFAULT_OPACITY = 0.94f
        const val MIN_OPACITY = 0.5f
    }
}

/** The shade's colour. Stored by [id]. */
enum class PrivacyShadeStyle(val id: String) {
    DARK("dark"),
    GREY("grey"),
    LIGHT("light"),
    WARM("warm"),
    ;

    companion object {
        fun of(id: String?): PrivacyShadeStyle? = entries.firstOrNull { it.id == id }
    }
}
