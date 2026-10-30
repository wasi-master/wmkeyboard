package com.wasimaster.wmkeyboard.ime.send

import android.content.Context
import com.wasimaster.wmkeyboard.core.send.PairDropRtc

/**
 * F-Droid's build holds no prebuilt WebRTC library, so PairDrop there works only with
 * a server that relays files itself.
 */
internal object PairDropWebRtc {

    /** This build can have the library at all. */
    const val supported = false

    fun create(context: Context): PairDropRtc? = null
}
