package com.wasimaster.wmkeyboard.core.addons

import android.content.Context
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.layout.KeymanLayoutPack
import com.wasimaster.wmkeyboard.core.net.DataSaverDownloadGate
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fetches [KeymanLayoutPack] from the data repository's release. A process
 * singleton with one download at a time, because the watcher and every card on a
 * page can all ask at once for the one file.
 *
 * The server is not trusted: the file has to hash to this build's pin before it
 * replaces anything. Data saver's Downloads row is obeyed here, so a blocked
 * connection reads as a failure and the watcher tries again on the next network.
 */
object KeymanLayoutPackDownloader {

    sealed interface Status {
        data object Idle : Status

        /** [percent] is 0 until the size is known. */
        data class Downloading(val percent: Int) : Status

        /** [message] is in the user's language when the network said why. */
        data class Failed(val message: String? = null) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.Idle)
    private var job: Job? = null

    val status: StateFlow<Status> = _status.asStateFlow()

    /** Starts the download unless the pack is here or already on its way. */
    fun start(context: Context) {
        if (!KeymanLayoutPack.needsDownload) return
        val app = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) return
            _status.value = Status.Downloading(0)
            job = scope.launch { _status.value = fetch(app) }
        }
    }

    private fun fetch(context: Context): Status {
        val pin = KeymanLayoutPack.pin ?: return Status.Idle
        val part = KeymanLayoutPack.partFile() ?: return Status.Failed()
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB || !DataSaverDownloadGate.allows(NetSource.KEYMAN)) return Status.Failed()
        val route = "/releases/download/${KeymanLayoutPack.RELEASE_TAG}/${pin.fileName}"
        return try {
            part.parentFile?.mkdirs()
            ToolHttp.download(
                url = "https://${repo.host}/${repo.owner}/${repo.repo}$route",
                target = part,
                maxBytes = MAX_PACK_BYTES,
                onProgress = { read, total ->
                    if (total > 0) _status.value = Status.Downloading(((read * 100) / total).toInt().coerceIn(0, 100))
                },
                source = NetSource.KEYMAN,
                route = route,
            )
            if (KeymanLayoutPack.install(part)) Status.Idle else Status.Failed()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Offline, refused or out of space: the card offers the retry, and
            // no keyboard should go down over a file it can fetch later.
            Status.Failed(ToolHttp.friendlyMessage(context, e))
        } finally {
            part.delete()
        }
    }

    /** Far above the 2 MB the grids deflate to, and far below what would hurt. */
    private const val MAX_PACK_BYTES = 32L * 1024 * 1024
}
