package com.wasimaster.wmkeyboard.ime.wmchroma

/**
 * What WM Chroma and WM Keyboard say to each other, version 1. The same file sits in both repos
 * (`app/.../wmchroma/bridge/ChromaWire.kt` in WM Chroma) and one golden-vector test in
 * each keeps them equal, as TranslateWire does for WM Translate.
 *
 * The keyboard asks; WM Chroma answers, through `ContentResolver.call` on [APP_AUTHORITY], and
 * only to the real keyboard, checked by its signing certificate. A reply is a Bundle with [STATE]
 * and [V]; a failure adds [CODE] and never throws. An unknown method answers [UNKNOWN_CALL].
 *
 * - [DESCRIBE] with [TEXT]: the colour written there, named and in the person's formats.
 * - [PALETTES]: recent colours, the tray and the saved palettes, each colour in the copy format.
 * - [TAKE_PICK] with [TOKEN]: the colour picked from the screen for that token, once.
 *
 * A pick starts with the keyboard opening [PICK_ACTIVITY] with [EXTRA_TOKEN]; the colour waits in
 * WM Chroma until [TAKE_PICK] collects it, for at most [PICK_WAIT_MS].
 *
 * [VALUES] is JSON. A colour in it is `{"argb", "name", "text"}`, where text is the colour in the
 * person's copy format; [DESCRIBE] and [TAKE_PICK] add `"formats": [{"id", "label", "text"}]`.
 */
object ChromaWire {
    const val VERSION = 1

    /** Served by WM Chroma, asked by the keyboard. */
    const val APP_AUTHORITY = "com.wasimaster.wmchroma.keyboard"
    const val APP_PACKAGE = "com.wasimaster.wmchroma"
    const val KEYBOARD_PACKAGE = "com.wasimaster.wmkeyboard"
    const val PICK_ACTIVITY = "com.wasimaster.wmchroma.ScreenPickActivity"
    const val EXTRA_TOKEN = "com.wasimaster.suite.extra.PICK_TOKEN"

    // Methods.
    const val STATUS = "status"
    const val DESCRIBE = "describe"
    const val PALETTES = "palettes"
    const val TAKE_PICK = "take_pick"

    // Keys.
    const val STATE = "state"
    const val V = "v"
    const val CODE = "code"
    const val TEXT = "text"
    const val TOKEN = "token"
    const val VALUES = "values"

    // States.
    const val OK = "ok"
    const val OFF = "off"
    const val WAITING = "waiting"
    const val ERROR = "error"

    // Codes.
    const val UNKNOWN_CALL = "unknown_call"
    const val UNTRUSTED = "untrusted"
    const val NOT_A_COLOUR = "not_a_colour"
    const val NO_PICK = "no_pick"
    const val FAILED = "failed"

    /** At most this much text per describe call. */
    const val MAX_TEXT = 400

    /** How long a pick waits to be collected, from the moment the keyboard asked for it. */
    const val PICK_WAIT_MS = 10 * 60_000L

    /** Every name above in order, for the golden-vector test both repos run. */
    val GOLDEN: String = listOf(
        VERSION.toString(), APP_AUTHORITY, APP_PACKAGE, KEYBOARD_PACKAGE, PICK_ACTIVITY, EXTRA_TOKEN,
        STATUS, DESCRIBE, PALETTES, TAKE_PICK,
        STATE, V, CODE, TEXT, TOKEN, VALUES,
        OK, OFF, WAITING, ERROR,
        UNKNOWN_CALL, UNTRUSTED, NOT_A_COLOUR, NO_PICK, FAILED,
        MAX_TEXT.toString(), PICK_WAIT_MS.toString(),
    ).joinToString("|")
}
