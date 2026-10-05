package com.wasimaster.wmkeyboard.core.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures microphone audio for clip-based speech recognition: 16 kHz mono PCM
 * into a fixed 30-second float buffer. Reports a smoothed input level for
 * the panel's pulse ring and fires [onMaxReached] when the buffer fills so the
 * service can transcribe and, in continuous mode, start the next utterance.
 * [onLost] fires instead when the microphone stops delivering mid-clip — the
 * audio server died, or the capture was torn down under it — and what was
 * recorded up to then is still there for [stop] to return.
 *
 * The caller must hold RECORD_AUDIO before [start]. [stop] is idempotent and
 * returns exactly the samples captured so far; it blocks briefly while the
 * reader thread winds down, so call it off the main thread.
 */
class VoiceClipRecorder(
    private val onLevel: (Float) -> Unit,
    private val onMaxReached: () -> Unit,
    private val onLost: () -> Unit = {},
    /**
     * How long the microphone may hear nothing, after it has heard speech,
     * before the clip ends by itself (#500). 0, the default, records until
     * [stop] or the window filling, as before. Measured in captured samples
     * rather than wall time, so a stalled reader cannot end a clip early.
     */
    private val silenceStopMs: Int = 0,
    /** The clip ended on a pause; the samples so far are there for [stop]. */
    private val onSilence: () -> Unit = {},
) {
    private val maxSamples = VoiceClipFormat.MAX_SAMPLES
    private val buffer = FloatArray(maxSamples)

    @Volatile private var count = 0
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRecording: Boolean get() = running

    /** The capture's audio session, for [MicBlockWatcher]; 0 when not recording. */
    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    /** How much has been captured so far, in samples. */
    val sampleCount: Int get() = count

    /** Whole seconds of room left in the clip, rounded up: 1 until the very end. */
    val secondsLeft: Int
        get() = (maxSamples - count + VoiceClipFormat.SAMPLE_RATE - 1) / VoiceClipFormat.SAMPLE_RATE

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            VoiceClipFormat.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                VoiceClipFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (_: Throwable) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return false
        }
        record = r
        count = 0
        running = true
        runCatching { r.startRecording() }.onFailure {
            running = false
            runCatching { r.release() }
            record = null
            return false
        }
        thread = Thread { readLoop(r) }.apply { start() }
        return true
    }

    private fun readLoop(r: AudioRecord) {
        // A reader that loses the CPU to background work drops samples once
        // the capture buffer overruns, and a gap in the audio is a wrong word.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val chunk = ShortArray(1600) // ~100 ms
        // Silence stop (#500): armed by the first chunk loud enough to be
        // speech, so the quiet before the user starts talking never ends the
        // clip; then a run of quiet chunks of the asked length does.
        val quietLimit = silenceStopMs.toLong() * VoiceClipFormat.SAMPLE_RATE / 1000
        var heard = false
        var quiet = 0L
        while (running) {
            val n = r.read(chunk, 0, chunk.size)
            if (n < 0) {
                // An error code, not a short read, and it does not clear by
                // itself: asking again returns at once with the same answer,
                // so carrying on would spin here until somebody called stop.
                running = false
                onLost()
                break
            }
            if (n == 0) continue
            var sumSq = 0.0
            val room = min(n, maxSamples - count)
            for (i in 0 until room) {
                val s = chunk[i] / 32768f
                buffer[count + i] = s
                sumSq += (s * s).toDouble()
            }
            count += room
            if (room > 0) {
                val rms = sqrt(sumSq / room).toFloat()
                onLevel((rms * 6f).coerceIn(0f, 1f))
                if (quietLimit > 0) {
                    if (rms >= SPEECH_RMS) {
                        heard = true
                        quiet = 0
                    } else if (heard) {
                        quiet += room
                        if (quiet >= quietLimit) {
                            running = false
                            onSilence()
                            break
                        }
                    }
                }
            }
            if (count >= maxSamples) {
                running = false
                onMaxReached()
                break
            }
        }
    }

    private companion object {
        /**
         * The RMS a 100 ms chunk must reach to count as speech for the
         * silence stop. Speech into a phone held normally reads 0.03 and up
         * on this scale; a quiet room's floor is under 0.005, and the pulse
         * ring draws this as a tenth of its height. Fixed rather than adapted
         * to the room: a floor that learns a fan's hum would also learn a
         * speaker who trails off.
         */
        const val SPEECH_RMS = 0.015f
    }

    /** Stops capture and returns the samples recorded so far. Idempotent. */
    fun stop(): FloatArray {
        running = false
        thread?.let { runCatching { it.join(600) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        return buffer.copyOf(count)
    }
}
