package com.wasimaster.wmkeyboard.ime.wmtranslate

/**
 * What WM Translate and WM Keyboard say to each other, version 1. The same file sits in both
 * repos (`interop/.../TranslateWire.kt` in WM Translate) and one golden-vector test in each
 * keeps them equal, as VaultWire and FieldWire do for WM Pass.
 *
 * Every call goes through `ContentResolver.call`, and each side checks the other's signing
 * certificate first. A reply is a Bundle of strings and numbers with [STATE] and [V]; a failure
 * adds [ERROR] and [CODE] and never throws. An unknown method answers [UNKNOWN_CALL].
 */
object TranslateWire {
    const val VERSION = 1

    /** Served by WM Translate, asked by the keyboard. */
    const val APP_AUTHORITY = "com.wasimaster.wmtranslate.keyboard"
    /** Served by the keyboard, asked by WM Translate. */
    const val KEYBOARD_AUTHORITY = "com.wasimaster.wmkeyboard.translate"

    const val APP_PACKAGE = "com.wasimaster.wmtranslate"
    const val KEYBOARD_PACKAGE = "com.wasimaster.wmkeyboard"

    // Methods.
    const val STATUS = "status"
    const val TRANSLATE = "translate"
    const val CANCEL = "cancel"
    const val SAVE = "save"
    const val CONFIG = "config"
    /** The keyboard's downloaded model files, for WM Translate to copy instead of downloading again. */
    const val MODELS = "models"
    /** Delegated translation: the keyboard runs one of its own rows (a plugin, ML Kit) for WM Translate. */
    const val ROWS = "rows"
    const val RUN = "run"

    // Keys.
    const val STATE = "state"
    const val V = "v"
    const val ERROR = "error"
    const val CODE = "code"
    const val ID = "id"
    const val TEXT = "text"
    const val FROM = "from"
    const val TO = "to"
    const val RESULT = "result"
    const val DETECTED = "detected"
    const val PROVIDER = "provider"
    const val NET = "net"
    const val INCOGNITO = "incognito"
    const val ORIGIN = "origin"
    const val HOPS = "hops"
    const val VALUES = "values"
    const val SHARE = "share"
    const val READY = "ready"
    const val PATH = "path"
    const val ROW = "row"

    // States.
    const val OK = "ok"
    const val OFF = "off"
    const val BOOT = "boot"

    // Values of NET and SHARE.
    const val NET_ALLOW = "allow"
    const val NET_DENY = "deny"
    const val SHARE_NONE = "none"
    const val SHARE_ONCE = "once"
    const val SHARE_FOLLOW = "follow"

    // Codes.
    const val UNKNOWN_CALL = "unknown_call"
    const val FAILED = "failed"
    const val LOOP = "loop"
    const val NO_TEXT = "no_text"
    const val TOO_LONG = "too_long"
    const val NOT_SHARED = "not_shared"
    const val NO_ROW = "no_row"

    /** At most this much text per translate call. */
    const val MAX_TEXT = 10_000

    /** Every name above in order, for the golden-vector test both repos run. */
    val GOLDEN: String = listOf(
        VERSION.toString(), APP_AUTHORITY, KEYBOARD_AUTHORITY, APP_PACKAGE, KEYBOARD_PACKAGE,
        STATUS, TRANSLATE, CANCEL, SAVE, CONFIG, MODELS, ROWS, RUN,
        STATE, V, ERROR, CODE, ID, TEXT, FROM, TO, RESULT, DETECTED, PROVIDER, NET, INCOGNITO, ORIGIN, HOPS, VALUES, SHARE, READY, PATH, ROW,
        OK, OFF, BOOT, NET_ALLOW, NET_DENY, SHARE_NONE, SHARE_ONCE, SHARE_FOLLOW,
        UNKNOWN_CALL, FAILED, LOOP, NO_TEXT, TOO_LONG, NOT_SHARED, NO_ROW, MAX_TEXT.toString(),
    ).joinToString("|")
}
