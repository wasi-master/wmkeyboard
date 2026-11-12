package com.wasimaster.wmkeyboard.core.icons

/**
 * How the per-slot icon overrides are stored: a CSV of `slot=source` pairs,
 * the same shape the per-tool colour overrides use.
 *
 * Safe as a flat CSV because neither half can contain a separator. Slot ids are
 * lowercase `a-z0-9._` (see [IconSlots.isWellFormed]) and a source is `b:<name>`
 * for a bundled icon, `p:<packId>` for one out of an installed pack,
 * `v:<variant>` for one of the slot's alternative looks, `u:<id>` for a
 * picture in the user's [IconLibrary], or `s:<package>/<drawable>` for one out
 * of a launcher icon pack installed on the phone — no `,` and no `=` in any of
 * them.
 *
 * Lives here rather than inside `SettingsRepository` so it is a plain pair of
 * functions a unit test can call, with no DataStore or Android context in the
 * way.
 */
object IconOverrides {

    /** `b:<BuiltinIcons name>` — one of the bundled Material glyphs. */
    const val BUILTIN_PREFIX = "b:"

    /** `p:<packId>` — this slot's icon out of a specific installed pack. */
    const val PACK_PREFIX = "p:"

    /**
     * `v:<variant>` — one of this slot's alternative looks, out of whatever
     * supplies the slot: the active pack when it has the variant (a file named
     * `<slot>@<variant>`), otherwise the app's own variant of that name.
     *
     * Unlike `p:` this is relative, not pinned. Picking the GIF tool's plain
     * lettering means "the lettering look", so switching to a pack that draws
     * its own lettering shows the pack's; a pack that has no such variant keeps
     * its own icon for the slot rather than reverting to a built-in one beside
     * its other glyphs.
     */
    const val VARIANT_PREFIX = "v:"

    /**
     * `u:<id>` — a picture out of the user's own [IconLibrary]. Pinned like
     * `p:`, and to the picture rather than to a slot, so one picture can sit on
     * any number of slots and stays listed when a slot moves off it.
     */
    const val LIBRARY_PREFIX = "u:"

    /**
     * `s:<package>/<drawable>` — one drawable out of a launcher icon pack
     * installed on the phone (#1275), such as Arcticons; see [SystemIconPacks].
     * Pinned like `p:`. A package name and a resource name hold neither `,`
     * nor `=`, and [SystemIconPacks.parseSource] refuses anything else.
     */
    const val SYSTEM_PACK_PREFIX = "s:"

    fun builtinSource(name: String): String = BUILTIN_PREFIX + name

    fun librarySource(id: String): String = LIBRARY_PREFIX + id

    /** The library picture a source names, or null when it is not a `u:` source. */
    fun libraryIdOf(source: String?): String? =
        source?.takeIf { it.startsWith(LIBRARY_PREFIX) }?.removePrefix(LIBRARY_PREFIX)

    fun packSource(packId: String): String = PACK_PREFIX + packId

    fun variantSource(variant: String): String = VARIANT_PREFIX + variant

    /** The variant a source picks, or null when it is not a `v:` source. */
    fun variantOf(source: String?): String? =
        source?.takeIf { it.startsWith(VARIANT_PREFIX) }?.removePrefix(VARIANT_PREFIX)

    /**
     * Parses [csv], dropping anything unusable.
     *
     * A slot this version doesn't have is dropped, so an override written by a
     * newer build simply doesn't apply here instead of corrupting the map. A
     * source naming a missing pack or icon is *kept*: the resolver falls back
     * for it, and dropping it would quietly lose the user's choice while a pack
     * was only temporarily unavailable.
     */
    fun decode(csv: String?): Map<String, String> =
        csv?.split(',')?.mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val slot = entry.substring(0, separator)
            val source = entry.substring(separator + 1)
            if (source.isEmpty() || IconSlots.byId(slot) == null) return@mapNotNull null
            slot to source
        }?.toMap().orEmpty()

    fun encode(map: Map<String, String>): String =
        map.entries.joinToString(",") { (slot, source) -> "$slot=$source" }
}
