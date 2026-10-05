package com.wasimaster.wmkeyboard.core.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs [VoiceClipRecorder]'s float samples into a 16-bit PCM WAV file, the one
 * audio format every transcription server accepts without a decoder of its own
 * (speaches, whisper.cpp's server, OpenAI, Groq). A full 30-second clip at
 * 16 kHz mono is under a megabyte, far below any server's upload limit, so
 * nothing is gained by compressing it on the phone.
 */
object WavEncoder {

    private const val HEADER_BYTES = 44

    fun encode(samples: FloatArray, sampleRate: Int = VoiceClipFormat.SAMPLE_RATE): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteBuffer.allocate(HEADER_BYTES + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray(Charsets.US_ASCII))
        out.putInt(36 + dataBytes)
        out.put("WAVE".toByteArray(Charsets.US_ASCII))
        out.put("fmt ".toByteArray(Charsets.US_ASCII))
        out.putInt(16) // PCM fmt chunk size
        out.putShort(1) // PCM
        out.putShort(1) // mono
        out.putInt(sampleRate)
        out.putInt(sampleRate * 2) // byte rate
        out.putShort(2) // block align
        out.putShort(16) // bits per sample
        out.put("data".toByteArray(Charsets.US_ASCII))
        out.putInt(dataBytes)
        for (s in samples) {
            out.putShort((s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
        }
        return out.array()
    }
}
