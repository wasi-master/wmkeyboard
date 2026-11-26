package com.wasimaster.wmkeyboard.ime.remote

import kotlinx.serialization.Serializable

/**
 * The buttons the Remote tool draws. Every kind of remote maps them onto its own
 * codes; a remote that has no code for one draws it greyed out.
 */
enum class RemoteKey {
    POWER, VOL_UP, VOL_DOWN, MUTE, CH_UP, CH_DOWN, INPUT,
    UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, MENU,
    ;

    /** Held down, these repeat: a volume ramp, a long scroll through a list. */
    val repeats: Boolean get() = this in Repeating

    companion object {
        private val Repeating = setOf(VOL_UP, VOL_DOWN, CH_UP, CH_DOWN, UP, DOWN, LEFT, RIGHT)

        fun of(name: String): RemoteKey? = entries.firstOrNull { it.name == name }
    }
}

/**
 * How a remote reaches its device. [id] is what the devices file stores, so it
 * survives a rename; [network] kinds need the internet permission, which Android
 * also asks of a socket to the local network.
 */
enum class RemoteKind(val id: String, val network: Boolean) {
    /** The phone's own infrared emitter, with a brand's code set or imported codes. */
    IR("ir", false),

    /** Android TV and Google TV: the TV Remote service, paired with a code the TV shows. */
    ANDROID_TV("android_tv", true),

    /** Samsung Tizen TVs (2016 on): the remote websocket, allowed once on the TV. */
    SAMSUNG("samsung", true),

    /** LG webOS TVs: the second-screen websocket, accepted once on the TV. */
    LG("lg", true),

    /** Roku players and Roku TVs: External Control Protocol, no pairing. */
    ROKU("roku", true),
    ;

    companion object {
        fun of(id: String): RemoteKind? = entries.firstOrNull { it.id == id }
    }
}

/** A button an imported infrared file carried that is none of [RemoteKey]: drawn as a chip under the pad. */
@Serializable
data class IrButton(val name: String, val code: String)

/**
 * One remembered remote. Kept in the Remote tool's own file (see [RemoteStore]),
 * not in the settings store: pairing keys and certificate pins live here, and a
 * settings backup shared with someone should not carry the key to their TV.
 *
 * [kind] is a [RemoteKind.id] stored as text, so a kind a newer build added
 * survives a downgrade untouched instead of failing the whole file.
 */
@Serializable
data class RemoteDevice(
    val id: String,
    val kind: String,
    val name: String,
    /** A network remote's address on the local network. */
    val host: String = "",
    /** Its Wi-Fi or Ethernet MAC, for the Wake-on-LAN packet that turns a sleeping TV on. */
    val mac: String = "",
    /** What pairing handed back: Samsung's token, LG's client key. Android TV keeps its key in the keystore. */
    val token: String = "",
    /** The SHA-256 of the TV's own certificate, pinned the first time it was seen. */
    val certSha256: String = "",
    /** The model the device named, when it named one. */
    val model: String = "",
    /** An infrared remote's brand in the shipped code set, and which of that brand's sets. */
    val irBrand: String = "",
    val irSet: Int = 0,
    /** Codes the user imported or pasted, by [RemoteKey] name; these win over the brand's set. */
    val irCodes: Map<String, String> = emptyMap(),
    /** Imported buttons that are none of the pad's. */
    val irExtra: List<IrButton> = emptyList(),
    /** Samsung and LG: whether the encrypted port answered last time, tried first next time. */
    val secure: Boolean = true,
) {
    val remoteKind: RemoteKind? get() = RemoteKind.of(kind)
}

/** A device seen on the local network by [RemoteDiscovery], before it is added. */
data class FoundDevice(
    val kind: RemoteKind,
    val host: String,
    val name: String,
    val model: String = "",
    val mac: String = "",
)
