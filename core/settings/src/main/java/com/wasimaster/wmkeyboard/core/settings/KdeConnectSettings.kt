package com.wasimaster.wmkeyboard.core.settings

/**
 * When the keyboard holds its link to a paired computer.
 *
 * There is no foreground service behind any of these (the app has none, by
 * decision), so the link lives inside the input-method process and only ever as
 * long as that does. [ALWAYS] is therefore "for as long as Android keeps the
 * keyboard's process, which while it is the selected keyboard is most of the
 * time" — best effort, and Doze will still cut it overnight.
 */
enum class KdeLinkLifetime {
    /** Only while the KDE Connect panel is open. */
    PANEL,

    /** While the keyboard is on screen, and a minute after, so a quick app switch does not reconnect. */
    KEYBOARD,

    /** Whenever this is the active keyboard, shown or not. */
    ALWAYS,
}

/**
 * The KDE Connect tool (issue #285), grouped for the reason every family here
 * is: one [KeyboardSettings] slot, flat `kde_*` DataStore keys.
 *
 * What is *not* here is the pairing itself. The device's key, its certificate
 * and the certificates of paired computers are files under `filesDir/kdeconnect`
 * and are deliberately left out of every backup: restoring them onto a second
 * phone would put two devices on the network with one identity.
 */
data class KdeConnectSettings(
    /**
     * The master switch. On by default, so a computer with WM Keyboard can be
     * paired by scanning its code and then sync with this phone. On costs
     * nothing by itself: the engine opens no socket until something is
     * paired or the user is looking for a device (see KdeConnectHub).
     */
    val enabled: Boolean = true,
    /** The name computers show for this phone; blank uses the phone's model. */
    val deviceName: String = "",
    val lifetime: KdeLinkLifetime = KdeLinkLifetime.KEYBOARD,
    /** Reconnect to paired computers whenever they are heard. */
    val autoConnect: Boolean = true,
    /** Text copied on the computer lands in the phone's clipboard. */
    val clipboardReceive: Boolean = true,
    /** Text copied on the phone is sent to the computer. Sensitive clips never are, by themselves. */
    val clipboardSend: Boolean = true,
    /**
     * Clipboard sharing on hold (#918): wall-clock end time by computer id,
     * `*` for all of them, `Long.MAX_VALUE` until turned back on. Ended
     * pauses may linger here until the next pause or resume rewrites it; the
     * engine compares against the time now. A `kde_` key, so it never syncs
     * to another phone.
     */
    val clipboardPauses: Map<String, Long> = emptyMap(),
    /**
     * Minutes a password or code received from the computer stays on the
     * phone's clipboard before it is cleared (#704); 0 leaves it there. The
     * computer's copy is wanted for one paste, and every app can read the
     * clipboard for as long as it sits there.
     */
    val clearSecretsMinutes: Int = 1,
    /** The computer's keyboard may type into the field this keyboard was opened in, shown or hidden (#1511). */
    val remoteTyping: Boolean = true,
    /**
     * Run the computer's keystrokes through the typing pipeline — the layout's
     * transliteration, suggestions, autocorrect — the way a hardware keyboard's
     * are, instead of committing them exactly as sent.
     */
    val remoteTypingPipeline: Boolean = false,
    /** Pointer speed multiplier, [PAD_SPEED_RANGE]. */
    val padSensitivity: Float = 1f,
    val padAcceleration: Boolean = true,
    /** Scroll speed multiplier, [PAD_SPEED_RANGE]. */
    val scrollSpeed: Float = 1f,
    /** Content follows the fingers, as it does on the phone. */
    val naturalScroll: Boolean = true,
    val tapToClick: Boolean = true,
    val padHaptics: Boolean = true,
    /** Tell the computer the phone's charge. */
    val batteryReport: Boolean = true,
    /**
     * Send the one-time codes found in notifications to the computer. Off by
     * default: a code is a secret for the minutes it is valid. Needs the
     * phone's own Codes from notifications on, which is what reads them.
     */
    val sendCodes: Boolean = false,
    /** Offer what the phone is playing to the computer as a media player. Needs notification access. */
    val exposeMedia: Boolean = true,
    /** Show "Send to computer" in other apps' share sheets once something is paired. */
    val shareSheet: Boolean = true,
    /** Accept files the computer sends. */
    val receiveFiles: Boolean = true,
    /** Compose a line and send it on Enter, rather than typing live. Remembered from the panel. */
    val composeMode: Boolean = false,
    /** The panel tab last used. Remembered, never shown as a setting. */
    val lastTab: String = "",
    /** Addresses announced to directly, for networks that swallow broadcasts. */
    val hosts: Set<String> = emptySet(),
) {
    companion object {
        val PAD_SPEED_RANGE = 0.3f..3f
        const val MAX_HOSTS = 12
        const val MAX_CLEAR_SECRETS_MINUTES = 30
    }
}
