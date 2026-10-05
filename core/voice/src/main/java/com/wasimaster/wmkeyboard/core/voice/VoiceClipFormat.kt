package com.wasimaster.wmkeyboard.core.voice

/** Shared capture/audio-server contract: mono, 16 kHz, no longer than 30 seconds. */
object VoiceClipFormat {
    const val SAMPLE_RATE = 16_000
    const val MAX_DURATION_SECONDS = 30
    const val MAX_SAMPLES = SAMPLE_RATE * MAX_DURATION_SECONDS
}
