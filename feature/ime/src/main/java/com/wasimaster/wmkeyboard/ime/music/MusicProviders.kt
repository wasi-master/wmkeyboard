package com.wasimaster.wmkeyboard.ime.music

import com.wasimaster.wmkeyboard.core.music.AcrCloudClient
import com.wasimaster.wmkeyboard.core.music.AuddClient
import com.wasimaster.wmkeyboard.core.music.AudioTagClient
import com.wasimaster.wmkeyboard.core.music.MusicClient
import com.wasimaster.wmkeyboard.core.music.MusicOutcome
import com.wasimaster.wmkeyboard.core.music.MusicProviderException
import com.wasimaster.wmkeyboard.core.music.MusicServerClient
import com.wasimaster.wmkeyboard.core.music.RapidShazamClient
import com.wasimaster.wmkeyboard.core.music.SongMatch
import com.wasimaster.wmkeyboard.core.providers.CredentialSpec
import com.wasimaster.wmkeyboard.core.providers.ProviderRunner
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.ResolvedProvider
import com.wasimaster.wmkeyboard.core.settings.resolveProviders

/**
 * The Music ID tool's providers as clients, from its list: the rows that
 * are on, in order, and a clip asked of each in turn until one names the
 * song ([ProviderRunner.fallback]).
 */
object MusicProviders {

    /** What a run of the list came to. */
    sealed interface Result {
        data class Found(val match: SongMatch, val key: String) : Result

        /** Every provider asked heard the clip and knew no song. */
        data object NotFound : Result

        /** Nothing could answer: [message] is the last failure's, null for an empty list. */
        data class Failed(val message: String?) : Result
    }

    /** The rows that are on and set up, in list order. */
    fun online(settings: KeyboardSettings): List<MusicClient> =
        settings.resolveProviders(ProviderTool.MUSIC).mapNotNull(::clientOf)

    fun clientOf(row: ResolvedProvider): MusicClient? = when (row.typeId) {
        ProviderTypes.AUDD.id -> AuddClient(row.key, row.typeId, row.apiKey)
        ProviderTypes.ACRCLOUD.id -> AcrCloudClient(
            row.key, row.typeId, row.url, row.apiKey, row.credential?.extra?.get(CredentialSpec.SECRET).orEmpty(),
        )
        ProviderTypes.RAPID_SHAZAM.id -> RapidShazamClient(row.key, row.typeId, row.apiKey)
        ProviderTypes.AUDIOTAG.id -> AudioTagClient(row.key, row.typeId, row.apiKey)
        ProviderTypes.MUSIC_SERVER.id -> MusicServerClient(row.key, row.url, row.apiKey)
        ProviderTypes.PLUGIN.id -> com.wasimaster.wmkeyboard.core.music.PluginMusicClient(row.key, row.customName)
        else -> null
    }

    /**
     * Asks [clients] in order. A provider that says "no match" ends the walk
     * at [Result.NotFound] only when every one did; one that failed is
     * stepped past, and cooled down when it was unreachable.
     */
    fun recognize(clients: List<MusicClient>, samples: FloatArray, sampleRate: Int): Result {
        val outcome = ProviderRunner.fallback(
            providers = clients,
            key = { it.key },
            isEmpty = { it !is MusicOutcome.Match },
            cool = { ProviderRunner.unreachable(it) },
        ) { client ->
            when (val answer = client.recognize(samples, sampleRate)) {
                is MusicOutcome.Failed -> throw MusicProviderException(answer.message, answer.cause)
                else -> answer
            }
        }
        return when (outcome) {
            is ProviderRunner.Outcome.Found -> {
                val match = (outcome.value as MusicOutcome.Match).match
                Result.Found(match.copy(via = match.via.ifEmpty { clients.first { it.key == outcome.key }.typeId }), outcome.key)
            }
            ProviderRunner.Outcome.NotFound -> Result.NotFound
            is ProviderRunner.Outcome.Failed -> Result.Failed(outcome.error?.message)
        }
    }
}
