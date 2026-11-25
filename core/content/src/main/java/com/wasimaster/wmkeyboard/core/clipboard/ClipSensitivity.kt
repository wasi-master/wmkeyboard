package com.wasimaster.wmkeyboard.core.clipboard

/**
 * Decides whether a copied string is the kind of thing that should not sit in
 * clipboard history for a day — a password out of a manager, a one-time code
 * out of an SMS.
 *
 * The authoritative signal is not here: Android 13 lets the copying app mark a
 * clip sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`), and a password manager
 * that sets it is telling the truth about its own data. This is the fallback for
 * everything else — the managers that predate the flag, the ones that never set
 * it, and the verification code the user copied by hand out of a message.
 *
 * Tuned to be quiet rather than clever. A false positive costs a clip that
 * expires in minutes instead of hours and is drawn masked until tapped; a false
 * negative costs a password left readable by every app on the device for a day.
 * That asymmetry argues for leaning in — but only on shapes that are genuinely
 * unlike prose, because a masked card the user has to reveal is friction, and
 * friction spread over ordinary clips would have people turning this off.
 */
object ClipSensitivity {

    /** Below this a "password" is more likely a word, a name, or a typo. */
    private const val MIN_PASSWORD_LENGTH = 8

    /** Above this it is a token, a key or a hash — still worth protecting. */
    private const val MAX_PASSWORD_LENGTH = 128

    /** Character classes a generated password draws from. */
    private const val REQUIRED_CLASSES = 3

    /** A bare code of this many characters, alone in the clip, reads as an OTP. */
    private val BARE_CODE = Regex("""[A-Z0-9]{4,8}""")

    /**
     * Whether [text] should be treated as sensitive.
     *
     * Two shapes qualify, both of which have to be the *whole* clip: a bare
     * one-time code, and a single token that looks generated rather than typed.
     * "Whole clip" is doing real work — a code quoted inside a sentence is a
     * message the user copied, and messages are what history is for. The code
     * *alone* is what someone copies to paste into a login box.
     */
    fun looksSensitive(text: String): Boolean {
        val token = text.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return false
        if (isBareCode(token)) return true
        return isGeneratedSecret(token)
    }

    /**
     * Whether a copy of [text] is kept out of, or hidden in, the clipboard
     * history by "Let the keyboard find them": [looksSensitive] without the
     * bare code.
     *
     * A bare code is the same shape as everything else people copy on its
     * own: a game's ID, an order number, an amount, a promo code. Treated as
     * a secret, the default "Never save it" dropped every one of them and
     * the history never showed the copy (Telegram, a number copied out of a
     * game). A one-time code is dead within minutes anyway, so it is kept
     * like any other clip; the strip and the typing still treat it as a code
     * through [isBareCode].
     */
    fun looksSecretToKeepOut(text: String): Boolean {
        val token = text.trim()
        if (token.isEmpty() || token.any { it.isWhitespace() }) return false
        return isGeneratedSecret(token)
    }

    /**
     * `483920`, `G4K7QP` — a short all-caps/digit run with nothing around it.
     *
     * Public because the shape is also what decides how a clip is *typed*: a
     * bare code goes into the field one character at a time, since the boxes
     * that ask for one usually take one character each.
     */
    fun isBareCode(token: String): Boolean {
        if (!BARE_CODE.matches(token)) return false
        // Needs at least one digit: PLEASE and HTTPS are not codes, and a year
        // on its own is a date far more often than it is a passcode.
        if (token.none { it.isDigit() }) return false
        if (token.length == 4 && token.all { it.isDigit() }) {
            val year = token.toIntOrNull()
            if (year != null && year in 1900..2099) return false
        }
        return true
    }

    /**
     * A single token long enough and mixed enough to be machine-generated:
     * three of the four character classes, or two classes with a symbol in
     * them. An all-lowercase word of any length never qualifies, so a copied
     * URL slug or a long compound word is left alone.
     */
    private fun isGeneratedSecret(token: String): Boolean {
        if (token.length !in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH) return false
        // A link is a link even when it is long and full of symbols.
        if (ClipLinks.asUrl(token) != null) return false
        // An email address is the other everyday token that would otherwise
        // score three classes on punctuation alone.
        if (token.count { it == '@' } == 1 && token.substringAfterLast('@').contains('.')) {
            return false
        }
        val lower = token.any { it.isLowerCase() }
        val upper = token.any { it.isUpperCase() }
        val digit = token.any { it.isDigit() }
        val symbol = token.any { !it.isLetterOrDigit() }
        val classes = listOf(lower, upper, digit, symbol).count { it }
        return classes >= REQUIRED_CLASSES
    }
}
