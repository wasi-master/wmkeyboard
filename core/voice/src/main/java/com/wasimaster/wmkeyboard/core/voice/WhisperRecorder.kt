package com.wasimaster.wmkeyboard.core.voice

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.wasimaster.wmkeyboard.core.voice.whisper.WhisperMel
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Captures microphone audio for offline Whisper: 16 kHz mono PCM into a fixed
 * float buffer, 30 seconds (Whisper's window) unless told otherwise. Reports a smoothed input level for
 * the panel's pulse ring and fires [onMaxReached] when the buffer fills so the
 * service can transcribe and, in continuous mode, start the next utterance.
 * [onLost] fires instead when the microphone stops delivering mid-clip — the
 * audio server died, or the capture was torn down under it — and what was
 * recorded up to then is still there for [stop] to return.
 *
 * With [carryOver] the microphone outlives the clip (#957): a clip that ends
 * by itself is set aside and the next one starts recording in the same
 * capture, without a sample between them going missing. The recorder is then
 * [hold]ing: it keeps listening but says nothing, while the clip that ended
 * is transcribed, until [resume] hands it to the next clip's owner or [stop]
 * shuts it.
 *
 * The caller must hold RECORD_AUDIO before [start]. [stop] is idempotent and
 * returns exactly the samples captured so far; it blocks briefly while the
 * reader thread winds down, so call it off the main thread.
 */
class WhisperRecorder(
    onLevel: (Float) -> Unit,
    onMaxReached: () -> Unit,
    onLost: () -> Unit = {},
    /**
     * How long the microphone may hear nothing, after it has heard speech,
     * before the clip ends by itself (#500). 0, the default, records until
     * [stop] or the window filling, as before. Measured in captured samples
     * rather than wall time, so a stalled reader cannot end a clip early.
     */
    private val silenceStopMs: Int = 0,
    /** The clip ended on a pause; the samples so far are there for [stop]. */
    onSilence: () -> Unit = {},
    /**
     * The microphone to record from (#581), or null for the one Android picks.
     * A preference, not a demand: if it goes away mid-clip Android carries on
     * with its own choice rather than ending the capture.
     */
    private val preferredDevice: AudioDeviceInfo? = null,
    /**
     * How long one clip may run. Whisper's window is 30 seconds, and a LiteRT
     * graph reads that and no more; whisper.cpp walks a longer clip window by
     * window, so its models get more room before [onMaxReached].
     */
    maxSeconds: Int = WhisperMel.CHUNK_SECONDS,
    /**
     * Noise reduction in front of whatever hears the clip ([VoiceDenoise]), or
     * null to keep the microphone's own audio. Owned from here on: the reader
     * thread uses it, adds what it still holds to the clip when the capture
     * ends, and closes it. The pulse ring still follows the raw microphone,
     * so it shows that the microphone hears; the silence stop and the clip
     * get the cleaned audio.
     */
    private val denoise: VoiceDenoise.Stream? = null,
    /**
     * The microphone stays open when a clip ends by itself (#957); see the
     * class comment. Safe for an engine that reads [samples] as they arrive:
     * the next clip is written from the front of the buffer while that
     * engine is still reading the old one's tail, and the whole old clip goes
     * to [hold] as a copy.
     */
    val carryOver: Boolean = false,
    /**
     * The last stretch of the clip, in seconds, in which a short pause ends
     * it before it fills (#957), so a clip that carries over is cut between
     * two words rather than through one. 0 runs every clip to the full window.
     */
    pauseCutSeconds: Int = 0,
) {
    private class Listener(
        val onLevel: (Float) -> Unit,
        val onMaxReached: () -> Unit,
        val onLost: () -> Unit,
        val onSilence: () -> Unit,
    )

    private enum class End { NONE, FULL, SILENCE }

    private val maxSamples = WhisperMel.SAMPLE_RATE * maxSeconds
    private val buffer = FloatArray(maxSamples)
    private val pauseCutFrom = maxSamples - WhisperMel.SAMPLE_RATE * pauseCutSeconds.coerceIn(0, maxSeconds)
    private val pauseCut = pauseCutSeconds > 0

    /** Guards [buffer], [count], [ended] and [quiet] between the reader thread and [hold]. */
    private val lock = Any()

    @Volatile private var listener = Listener(onLevel, onMaxReached, onLost, onSilence)
    @Volatile private var count = 0
    @Volatile private var running = false
    /** Listening for nobody yet: no callbacks, and no clip ends but a full one. */
    @Volatile private var held = false
    /** The clip that ended by itself, set aside for [hold] or [stop]. */
    private var ended: FloatArray? = null
    /** Quiet samples since the clip last heard speech. */
    private var quiet = 0L
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    val isRecording: Boolean get() = running

    /** The capture's audio session, for [MicBlockWatcher]; 0 when not recording. */
    val audioSessionId: Int get() = record?.audioSessionId ?: 0

    /** The microphone the capture is actually coming from, once it has started; null before or if Android won't say. */
    val routedDevice: AudioDeviceInfo? get() = runCatching { record?.routedDevice }.getOrNull()

    /**
     * The clip has picked up something loud enough to be speech (#921), by
     * the same measure that arms the silence stop. Set from the reader thread.
     */
    @Volatile var heardSpeech: Boolean = false
        private set

    /** How much has been captured so far, in samples. */
    val sampleCount: Int get() = count

    /**
     * A copy of the samples captured from [from] up to [to] (clamped to what is
     * there), for an engine that decodes while the clip is still recording.
     * Safe from any thread: samples below [sampleCount] are not written again
     * until a clip that carries over ends, and then the next clip starts from
     * the front of the buffer, far below where a reader of the old one is,
     * with [sampleCount] back at 0 so a read past it returns nothing.
     */
    fun samples(from: Int, to: Int): FloatArray = buffer.copyOfRange(from, minOf(to, count).coerceAtLeast(from))

    /** Whole seconds of room left in the clip, rounded up: 1 until the very end. */
    val secondsLeft: Int
        get() = (maxSamples - count + WhisperMel.SAMPLE_RATE - 1) / WhisperMel.SAMPLE_RATE

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            WhisperMel.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return failed()
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WhisperMel.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (_: Throwable) {
            return failed()
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { r.release() }
            return failed()
        }
        if (preferredDevice != null) runCatching { r.setPreferredDevice(preferredDevice) }
        record = r
        count = 0
        running = true
        runCatching { r.startRecording() }.onFailure {
            running = false
            runCatching { r.release() }
            record = null
            return failed()
        }
        thread = Thread { readLoop(r) }.apply { start() }
        return true
    }

    /**
     * The clip so far, or the one that just ended by itself, and the
     * microphone goes on recording the next clip from that very sample (#957),
     * holding it for [resume]. Safe from any thread.
     */
    fun hold(): FloatArray = synchronized(lock) {
        held = true
        ended?.also { ended = null } ?: buffer.copyOf(count).also { newClip() }
    }

    /**
     * A held capture taken over by the next clip's owner, whose callbacks it
     * calls from now on. If the microphone stopped while it was held (the
     * clip filled, or the microphone went), [isRecording] is false and what
     * it had is there for [stop].
     */
    fun resume(onLevel: (Float) -> Unit, onMaxReached: () -> Unit, onLost: () -> Unit, onSilence: () -> Unit) {
        listener = Listener(onLevel, onMaxReached, onLost, onSilence)
        held = false
    }

    /** A capture that never started: the denoiser it was handed is done with too. */
    private fun failed(): Boolean {
        denoise?.close()
        return false
    }

    private fun readLoop(r: AudioRecord) {
        try {
            capture(r)
        } finally {
            denoise?.close()
        }
    }

    /** Starts the next clip at the front of the buffer. Under [lock]. */
    private fun newClip() {
        count = 0
        quiet = 0
        heardSpeech = false
    }

    /**
     * The denoiser's last samples (DPDFNet trails the microphone by 40 ms) onto
     * the end of the clip, as far as there is room. Runs on the reader thread
     * once capture has stopped and before anything hears that it ended, so
     * [stop] and the end-of-clip callbacks see the whole clip.
     */
    private fun flushDenoiser() {
        val tail = runCatching { denoise?.finish() }.getOrNull() ?: return
        synchronized(lock) {
            val room = min(tail.size, maxSamples - count)
            if (room <= 0) return
            tail.copyInto(buffer, count, 0, room)
            count += room
        }
    }

    private fun capture(r: AudioRecord) {
        // A reader that loses the CPU to background work drops samples once
        // the capture buffer overruns, and a gap in the audio is a wrong word.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
        val chunk = ShortArray(1600) // ~100 ms
        val raw = FloatArray(chunk.size)
        // Silence stop (#500): armed by the first chunk loud enough to be
        // speech, so the quiet before the user starts talking never ends the
        // clip; then a run of quiet chunks of the asked length does.
        val quietLimit = silenceStopMs.toLong() * WhisperMel.SAMPLE_RATE / 1000
        while (running) {
            val n = r.read(chunk, 0, chunk.size)
            if (n < 0) {
                // An error code, not a short read, and it does not clear by
                // itself: asking again returns at once with the same answer,
                // so carrying on would spin here until somebody called stop.
                running = false
                flushDenoiser()
                if (!held) listener.onLost()
                return
            }
            if (n == 0) continue
            var rawSq = 0.0
            for (i in 0 until n) {
                val s = chunk[i] / 32768f
                raw[i] = s
                rawSq += (s * s).toDouble()
            }
            if (!held) listener.onLevel((sqrt(rawSq / n).toFloat() * 6f).coerceIn(0f, 1f))
            // Denoised audio comes back in whole 10 ms blocks, a moment
            // behind; a call can hand back nothing yet.
            val clean = denoise?.process(raw, n) ?: raw
            val got = if (denoise != null) clean.size else n
            when (synchronized(lock) { take(clean, got, quietLimit) }) {
                End.NONE -> Unit
                End.FULL -> if (!carryOver) {
                    running = false
                    listener.onMaxReached()
                    return
                } else if (running) {
                    listener.onMaxReached()
                } else {
                    return
                }
                End.SILENCE -> if (!carryOver) {
                    running = false
                    flushDenoiser()
                    listener.onSilence()
                    return
                } else {
                    listener.onSilence()
                }
            }
        }
        // Stopped from outside: [stop] waits for this before it copies the clip.
        flushDenoiser()
    }

    /**
     * Adds [got] samples of [clean] to the clip and says whether that ended
     * it. A clip that carries over is set aside here, in the same step, so the
     * next chunk already starts the next clip; one that is [held] ends only by
     * filling, which stops the capture, since nobody is there to take it yet.
     * Under [lock].
     */
    private fun take(clean: FloatArray, got: Int, quietLimit: Long): End {
        var sumSq = 0.0
        val room = min(got, maxSamples - count)
        for (i in 0 until room) {
            val s = clean[i]
            buffer[count + i] = s
            sumSq += (s * s).toDouble()
        }
        count += room
        var end = End.NONE
        if (room > 0) {
            val rms = sqrt(sumSq / room).toFloat()
            if (rms >= SPEECH_RMS) {
                heardSpeech = true
                quiet = 0
            } else if (heardSpeech) {
                quiet += room
                if (quietLimit > 0 && quiet >= quietLimit) {
                    end = End.SILENCE
                } else if (pauseCut && count >= pauseCutFrom && quiet >= PAUSE_CUT_SAMPLES) {
                    // As good as full: the window would end within seconds,
                    // and here it ends between two words.
                    end = End.FULL
                }
            }
        }
        val full = count >= maxSamples
        if (held) {
            if (full) running = false
            return if (full) End.FULL else End.NONE
        }
        if (full) end = End.FULL
        if (end == End.NONE) return end
        if (carryOver) {
            ended = buffer.copyOf(count)
            newClip()
            held = true
        }
        return end
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

        /**
         * The quiet that counts as a gap between words for the pause cut:
         * two chunks, 0.2 s. The silences inside a word are shorter, and so
         * are most between the words of a sentence said quickly.
         */
        const val PAUSE_CUT_SAMPLES = WhisperMel.SAMPLE_RATE / 5
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
        return synchronized(lock) { ended ?: buffer.copyOf(count) }
    }
}
