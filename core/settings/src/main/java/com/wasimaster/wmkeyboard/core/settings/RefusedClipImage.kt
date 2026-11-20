package com.wasimaster.wmkeyboard.core.settings

/**
 * What a tap on a clipboard picture does when the field takes no pictures
 * through the keyboard (#1472).
 */
enum class RefusedClipImage {
    /** Onto the system clipboard and the field's own Paste (#1178). */
    PASTE,

    /** To the field's app through Android's share screen, the chooser if that fails. */
    SHARE,

    /** Onto the system clipboard only, to paste by hand. */
    CLIPBOARD,
}
