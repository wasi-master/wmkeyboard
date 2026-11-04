package com.wasimaster.wmkeyboard.ime.wmpass

/**
 * How a field in WM Pass says which of its own buttons the keyboard may
 * offer on the strip while that field has the keys: **Scan the card** in a
 * card's number, **Fill in from a QR code** in a Wi-Fi network's fields. The
 * chips wear this keyboard's own words, so they translate; a tap asks WM Pass,
 * through its provider's `action` call, to press that button for the person.
 *
 * WM Pass writes the same format in its own `FieldWire`. A change here is a
 * change there too.
 *
 * The field says it in `EditorInfo.privateImeOptions`. Several apps' options
 * can share that string, separated by commas, so WM Pass's is one item of
 * its own:
 *
 * ```
 * com.wasimaster.suite.field=1;token=Kx3m9QaZ0bLw7RtE;actions=card_scan
 * ```
 *
 * - `1` is the format's version. The keys this side knows are read from any
 *   version, and the rest are left alone.
 * - `token` names the WM Pass screen the field is on, made fresh for each
 *   screen. It goes back with the tap, so a tap never presses a button on a
 *   screen other than the one the person is typing on, and one for a screen
 *   that has closed does nothing.
 * - `actions` are the buttons, joined by `+`. An id this side has no words for
 *   is skipped.
 *
 * Nothing in it is secret, and it is read only from a field whose package is
 * WM Pass and whose signing certificate is WM Pass's
 * ([WmPassBridge.isWmPass]).
 */
object FieldWire {
    const val VERSION = 1

    /** Where WM Pass's item starts inside `privateImeOptions`. */
    const val PREFIX = "com.wasimaster.suite.field="

    /** The provider's call that presses a button: the action id as its argument, [TOKEN] in its extras. */
    const val ACTION_CALL = "action"

    /** The extra holding the screen's token, and the key in the tag. */
    const val TOKEN = "token"

    /** The reply's key: whether a screen took the action. */
    const val DONE = "done"

    /** A card's **Scan the card**. */
    const val CARD_SCAN = "card_scan"

    /** A Wi-Fi network's **Fill in from a QR code**. */
    const val WIFI_QR = "wifi_qr"

    /** A Wi-Fi network's **Pick a network saved on this phone**. */
    const val WIFI_SAVED = "wifi_saved"

    /** The ids this side has words for, in the order their chips stand. */
    val ACTIONS = listOf(CARD_SCAN, WIFI_QR, WIFI_SAVED)

    private val TOKEN_SHAPE = Regex("[A-Za-z0-9_-]{8,64}")
    private val ACTION_SHAPE = Regex("[a-z0-9_]{1,32}")
    private const val MOST_ACTIONS = 8

    /** What a field's tag says: the screen's [token] and the [actions] this side knows. */
    data class Tag(val token: String, val actions: List<String>)

    /** The item for [actions] on the screen called [token], or null when there is nothing to offer. */
    fun tag(token: String, actions: List<String>): String? {
        val known = actions.filter { it in ACTIONS }.distinct()
        if (known.isEmpty() || !TOKEN_SHAPE.matches(token)) return null
        return "$PREFIX$VERSION;$TOKEN=$token;actions=${known.joinToString("+")}"
    }

    /** WM Pass's item in a field's whole `privateImeOptions`, or null when there is none or it is not well formed. */
    fun parse(privateImeOptions: String?): Tag? {
        val item = privateImeOptions?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith(PREFIX) } ?: return null
        val parts = item.removePrefix(PREFIX).split(';')
        if (parts.first().toIntOrNull()?.takeIf { it >= 1 } == null) return null
        val keys = parts.drop(1).mapNotNull { part ->
            val at = part.indexOf('=')
            if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
        }.toMap()
        val token = keys[TOKEN]?.takeIf { TOKEN_SHAPE.matches(it) } ?: return null
        val actions = keys["actions"].orEmpty().split('+')
            .filter { ACTION_SHAPE.matches(it) && it in ACTIONS }
            .distinct()
            .take(MOST_ACTIONS)
        if (actions.isEmpty()) return null
        return Tag(token, actions)
    }
}
