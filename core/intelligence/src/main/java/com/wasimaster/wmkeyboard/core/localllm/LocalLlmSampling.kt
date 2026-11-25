package com.wasimaster.wmkeyboard.core.localllm

import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.AiProviders
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.aiRows
import com.wasimaster.wmkeyboard.core.settings.through
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The temperature and top_p the on-device model samples with: the ones set
 * on the On-device AI row of the AI providers list (Telegram 13475), else
 * the runtimes' own defaults, which are what every answer used before.
 *
 * One process-wide pair rather than an argument on every call, because the
 * phone has one model loaded at a time and every caller (the panel, the chat,
 * the strip, dictation's tidy) asks the same row. Both runtimes read it when
 * an answer starts: llama.cpp rebuilds its sampler chain when the pair has
 * changed, LiteRT-LM puts it in each new conversation's sampler.
 */
object LocalLlmSampling {

    /** llama.cpp's and LiteRT-LM's temperature when the row sets none. */
    const val DEFAULT_TEMPERATURE = 0.7

    /** Their top_p when the row sets none. */
    const val DEFAULT_TOP_P = 0.95

    @Volatile
    private var temperatureSet: Double? = null

    @Volatile
    private var topPSet: Double? = null

    val temperature: Double get() = temperatureSet ?: DEFAULT_TEMPERATURE

    val topP: Double get() = topPSet ?: DEFAULT_TOP_P

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var started = false

    /** Follows the On-device AI row's values for the life of the process. */
    fun start(repository: SettingsRepository) {
        if (started) return
        started = true
        scope.launch {
            repository.settings
                .map { onDeviceRow(it) }
                .distinctUntilChanged()
                .collect { (temperature, topP) ->
                    temperatureSet = temperature
                    topPSet = topP
                }
        }
    }

    private fun onDeviceRow(settings: KeyboardSettings): Pair<Double?, Double?> {
        val row = runCatching {
            settings.aiRows().firstOrNull { AiProviders.of(it.typeId) == AiProvider.ON_DEVICE }
        }.getOrNull() ?: return null to null
        val through = settings.ai.through(row)
        return through.temperature to through.topP
    }
}
