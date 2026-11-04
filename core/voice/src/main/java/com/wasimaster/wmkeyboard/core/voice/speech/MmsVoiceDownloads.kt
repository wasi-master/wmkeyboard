package com.wasimaster.wmkeyboard.core.voice.speech

import android.os.StatFs
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.voice.executorch.EtDownloadManager.Status
import com.wasimaster.wmkeyboard.core.voice.executorch.PinnedDownload
import com.wasimaster.wmkeyboard.voice.R
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Downloads MMS voices into `filesDir/mms-tts/<code>/`, one at a time, with
 * the dictation models' resume and hash check ([PinnedDownload]). Nothing
 * starts until the user has agreed to the weights' licence
 * ([NativeConsent.MMS]); the settings screen asks first.
 */
object MmsVoiceDownloads {

    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var activeCode: String? = null
    private val _states = MutableStateFlow<Map<String, Status>>(emptyMap())

    /** Voice code → status, for the voices [refresh] was asked about and any started since. */
    val states: StateFlow<Map<String, Status>> = _states.asStateFlow()

    val isBusy: Boolean get() = job?.isActive == true

    /** Seeds [states] for [voices] from disk; call when the screen appears. */
    fun refresh(filesDir: File, voices: Collection<MmsVoice>) {
        _states.update { current ->
            current + voices.associate { voice ->
                val active = current[voice.code]
                val onDisk = MmsVoices.bytesOnDisk(filesDir, voice)
                voice.code to when {
                    voice.code == activeCode && isBusy && active != null -> active
                    MmsVoices.isDownloaded(filesDir, voice) -> Status.Downloaded
                    onDisk > 0 -> Status.Paused(onDisk, voice.bytes)
                    else -> Status.NotDownloaded
                }
            }
        }
    }

    fun start(filesDir: File, voice: MmsVoice) {
        if (isBusy || MmsVoices.isDownloaded(filesDir, voice) || !NativeConsent.has(NativeConsent.MMS)) return
        activeCode = voice.code
        set(voice, Status.Downloading(MmsVoices.bytesOnDisk(filesDir, voice), voice.bytes))
        job = scope.launch {
            try {
                download(filesDir, voice)
                set(voice, Status.Downloaded)
            } catch (e: CancellationException) {
                val onDisk = MmsVoices.bytesOnDisk(filesDir, voice)
                set(voice, if (onDisk > 0) Status.Paused(onDisk, voice.bytes) else Status.NotDownloaded)
                throw e
            } catch (e: PinnedDownload.FailedException) {
                set(voice, Status.Failed(e.messageRes, e.messageArg))
            } catch (_: Exception) {
                set(voice, Status.Failed(OfflineBuild.download(CommonR.string.common_error_network)))
            } finally {
                activeCode = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun delete(filesDir: File, voice: MmsVoice) {
        if (voice.code == activeCode) cancel()
        MmsSpeech.forget(voice)
        MmsVoices.dir(filesDir, voice).deleteRecursively()
        set(voice, Status.NotDownloaded)
    }

    private fun set(voice: MmsVoice, status: Status) {
        _states.update { it + (voice.code to status) }
    }

    private suspend fun download(filesDir: File, voice: MmsVoice) {
        val dir = MmsVoices.dir(filesDir, voice).apply { mkdirs() }
        val tokens = MmsVoices.tokensFile(filesDir, voice)
        val model = MmsVoices.modelFile(filesDir, voice)
        val stillNeeded = voice.bytes - MmsVoices.bytesOnDisk(filesDir, voice)
        if (StatFs(dir.path).availableBytes < stillNeeded + SPACE_MARGIN_BYTES) {
            throw PinnedDownload.FailedException(
                R.string.core_voice_download_space_error,
                ((stillNeeded + SPACE_MARGIN_BYTES) / 1e6).roundToInt().toString(),
            )
        }
        // The small file first, so a bad connection fails before the big one.
        if (tokens.length() != voice.tokensBytes) {
            PinnedDownload.fetch(
                voice.tokensUrl(), tokens, voice.tokensBytes, voice.tokensSha256,
                "${voice.code}/tokens.txt", NetSource.DOWNLOAD_VOICE_OUT,
            ) { }
        }
        if (model.length() != voice.modelBytes) {
            PinnedDownload.fetch(
                voice.modelUrl(), model, voice.modelBytes, voice.modelSha256,
                "${voice.code}/model.onnx", NetSource.DOWNLOAD_VOICE_OUT,
            ) { written -> set(voice, Status.Downloading(voice.tokensBytes + written, voice.bytes)) }
        }
    }
}
