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
    /**
     * The user's own photo laid into the cover (#1417): a file name in
     * [PrivacyShadePhoto.dir], or null for none, the default.
     */
    val photo: String? = null,
    /**
     * How much of the photo shows against the shade's colour, from
     * [MIN_PHOTO_OPACITY] to 1. The cover as a whole stays as solid as
     * [opacity] says, so the photo never hides more of the app than the
     * colour alone would.
     */
    val photoOpacity: Float = DEFAULT_PHOTO_OPACITY,
) {
    companion object {
        const val DEFAULT_OPACITY = 0.94f
        const val MIN_OPACITY = 0.5f
        const val DEFAULT_PHOTO_OPACITY = 0.5f
        const val MIN_PHOTO_OPACITY = 0.1f

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

/**
 * Where the shade's photo lives (#1417): one downscaled JPEG in the app's
 * private files, named by when it was picked so a new pick never reads as the
 * old one to a keyboard that still holds it. The copy carries no EXIF, so no
 * place or camera from the original travels with it. `.img`, as every image
 * saved for a look is, so no gallery takes it for one of the user's photos.
 */
object PrivacyShadePhoto {
    const val DIR = "privacy_shade"

    /**
     * The settings backup's key for the photo itself, next to the setting
     * that names it, as `{ type: "file", name, value: base64 }`. A build that
     * does not know the type skips the entry and restores the rest.
     */
    const val BACKUP_KEY = "privacy_shade_photo_data"

    /** Larger than any photo the picker writes; a backup's copy past it is turned away. */
    const val MAX_BYTES = 8L * 1024 * 1024

    private val NAME = Regex("""photo_\d{1,20}\.img""")

    fun dir(filesDir: java.io.File): java.io.File = java.io.File(filesDir, DIR)

    /** A new name for a photo picked now. */
    fun newName(): String = "photo_${System.currentTimeMillis()}.img"

    /**
     * The file [name] stands for, or null when it is not a name this app
     * gives a photo, so a name read from a backup cannot point elsewhere.
     */
    fun file(filesDir: java.io.File, name: String?): java.io.File? =
        name?.takeIf { NAME.matches(it) }?.let { java.io.File(dir(filesDir), it) }
}
