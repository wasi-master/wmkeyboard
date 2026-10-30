package com.wasimaster.wmkeyboard.core.feedback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reads the sound pack `pack.json` that LeanType publishes, so a pack from that
 * keyboard's ecosystem can be imported as it is.
 *
 * It is the same shape of file as a `.wmsoundpack`, a ZIP with `pack.json` at
 * the root and recordings beside it, with a manifest that names events instead
 * of roles. This turns that manifest into a [SoundPackManifest] and leaves the
 * rest of the import, the path handling above all, to [SoundPackFile.import]:
 * nothing here touches an archive entry.
 *
 * | LeanType | Here |
 * |---|---|
 * | `keypress.default` | top-level `press` |
 * | `keypress.space`, `.delete`, `.return` | the matching role |
 * | `keypress.shift`, `.symbol` | both into [KeySoundRole.MODIFIER] |
 * | `defaultMasterVolume` × event `volume` | `gain` |
 * | `mode: "single"` | the first file only |
 * | `mode: "random"`, `"cycle"` | every file |
 *
 * LeanType multiplies the two volumes where a role's `gain` here replaces the
 * pack's, so every gain is written out already multiplied.
 *
 * `cycle` has no exact equivalent. The player's own rule, never the same variant
 * twice running, is closer to what a pack asking for a round robin wants than
 * reading only one file would be.
 */
internal object LeanTypeSoundPack {

    private const val DEFAULT_EVENT = "keypress.default"
    private const val MODE_SINGLE = "single"

    private val ROLE_EVENTS = mapOf(
        KeySoundRole.SPACE to listOf("keypress.space"),
        KeySoundRole.ENTER to listOf("keypress.return"),
        KeySoundRole.DELETE to listOf("keypress.delete"),
        KeySoundRole.MODIFIER to listOf("keypress.shift", "keypress.symbol"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Event(
        val files: List<String> = emptyList(),
        val mode: String = MODE_SINGLE,
        val volume: Float = 1f,
    ) {
        val samples: List<String> get() = if (mode == MODE_SINGLE) files.take(1) else files
    }

    @Serializable
    private data class Manifest(
        val schemaVersion: Int = 1,
        val id: String = "",
        val name: String = "",
        val summary: String = "",
        val versionName: String = "1.0.0",
        val author: String = "",
        val defaultMasterVolume: Float = 1f,
        val sounds: Map<String, Event> = emptyMap(),
    )

    /**
     * Whether a manifest claims this format. The two names together, because
     * neither is unusual alone and this format has no tag of its own to check.
     */
    fun looksLike(manifest: String): Boolean =
        manifest.contains("\"schemaVersion\"") && manifest.contains("\"$DEFAULT_EVENT\"")

    /**
     * [manifest] as this app's own format, or null when it is not one of these.
     *
     * `version` carries the pack's `schemaVersion`, so a later revision of the
     * format is refused as a newer one rather than half understood.
     */
    fun toManifest(manifest: String): SoundPackManifest? {
        if (!looksLike(manifest)) return null
        val source = runCatching { json.decodeFromString<Manifest>(manifest) }.getOrNull() ?: return null
        val master = source.defaultMasterVolume.coerceIn(0f, 1f)

        fun gainOf(event: Event) = master * event.volume.coerceIn(0f, 1f)

        val roles = ROLE_EVENTS.mapNotNull { (role, names) ->
            val events = names.mapNotNull { source.sounds[it] }.filter { it.samples.isNotEmpty() }
            val first = events.firstOrNull() ?: return@mapNotNull null
            role.serialName to SoundPackRoleSpec(
                press = events.flatMap { it.samples }.distinct(),
                // Shift and symbol share one role here, and so one volume.
                gain = gainOf(first),
            )
        }.toMap()

        val default = source.sounds[DEFAULT_EVENT]
        return SoundPackManifest(
            format = SoundPackFile.FORMAT,
            version = source.schemaVersion,
            id = source.id,
            name = source.name,
            author = source.author,
            packVersion = source.versionName,
            description = source.summary,
            gain = default?.let(::gainOf) ?: master,
            press = default?.samples.orEmpty(),
            roles = roles,
        )
    }
}
