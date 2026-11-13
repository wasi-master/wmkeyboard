package com.wasimaster.wmkeyboard.core.imagegen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.wasimaster.wmkeyboard.core.upscale.UpscaleRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way the app asks for pictures: [ImageGenService] in its own
 * process. Binds on the first request and stays bound a few minutes after the
 * last one, so the model stays loaded while someone tries prompt after
 * prompt, then lets go so the process can be reclaimed. Cancelling a
 * [generate] collection cancels its picture run.
 */
object ImageGen {

    private const val CONNECT_TIMEOUT_MS = 30_000L

    /** Loading three networks of an unknown size on a slow phone. */
    private const val VERIFY_TIMEOUT_MS = 3 * 60_000L

    /** Unbound this long after the last run ends: a little past the service's own idle unload. */
    private const val UNBIND_AFTER_MS = 4 * 60_000L

    private val thread = HandlerThread("imagegen-client").apply { start() }
    private val handler = Handler(thread.looper) { message -> route(message); true }
    private val replies = Messenger(handler)
    private val nextId = AtomicInteger(1)
    private val listeners = ConcurrentHashMap<Int, (ImageGenEvent) -> Unit>()
    private val verifications = ConcurrentHashMap<Int, CompletableDeferred<String?>>()
    private val binding = Mutex()

    @Volatile private var service: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var appContext: Context? = null

    private val unbind = Runnable { disconnect() }

    /**
     * Draws [request]: [ImageGenEvent.Loading] when the model is loaded,
     * [ImageGenEvent.Progress] per step, one [ImageGenEvent.Image] or
     * [ImageGenEvent.Blocked] per variant, then [ImageGenEvent.Done] or
     * [ImageGenEvent.Failed], after which the flow completes.
     */
    fun generate(context: Context, request: ImageGenRequest): Flow<ImageGenEvent> =
        ask(context, ImageGenService.GENERATE, ImageGenService.toBundle(request))

    /**
     * Enlarges a picture (see [com.wasimaster.wmkeyboard.core.upscale.UpscaleEngine]):
     * [ImageGenEvent.Progress] per tile (step of steps), one
     * [ImageGenEvent.Image] with the output file unless it was cancelled, then
     * [ImageGenEvent.Done] or [ImageGenEvent.Failed]. Cancelling the
     * collection stops it within a tile.
     */
    fun upscale(context: Context, request: UpscaleRequest): Flow<ImageGenEvent> =
        ask(context, ImageGenService.UPSCALE, ImageGenService.upscaleToBundle(request))

    private fun ask(context: Context, what: Int, request: Bundle): Flow<ImageGenEvent> = callbackFlow {
        val id = nextId.getAndIncrement()
        var finished = false
        handler.removeCallbacks(unbind)
        listeners[id] = { event ->
            trySend(event)
            if (event is ImageGenEvent.Done || event is ImageGenEvent.Failed) {
                finished = true
                close()
            }
        }
        val messenger = connect(context)
        val sent = messenger != null && runCatching {
            messenger.send(Message.obtain(null, what, id, 0).apply {
                data = request
                replyTo = replies
            })
        }.isSuccess
        if (!sent) {
            finished = true
            trySend(ImageGenEvent.Failed("the generator could not start"))
            close()
        }
        awaitClose {
            listeners.remove(id)
            if (!finished) runCatching { service?.send(Message.obtain(null, ImageGenService.CANCEL, id, 0)) }
            if (listeners.isEmpty()) handler.postDelayed(unbind, UNBIND_AFTER_MS)
        }
    }

    /**
     * Checks an imported bundle in [dir] in the generator's process, where
     * loading a gigabyte-sized network costs the settings app nothing. Null
     * when it fits what its own wmimage.json promises, else what is wrong.
     */
    suspend fun verify(context: Context, dir: File): String? {
        val messenger = connect(context) ?: return "the generator could not start"
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<String?>()
        verifications[id] = answer
        handler.removeCallbacks(unbind)
        try {
            messenger.send(Message.obtain(null, ImageGenService.VERIFY, id, 0).apply {
                data = Bundle().apply { putString("dir", dir.absolutePath) }
                replyTo = replies
            })
            return withTimeoutOrNull(VERIFY_TIMEOUT_MS) { answer.await() } ?: "the check took too long"
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return e.message ?: "the generator did not answer"
        } finally {
            verifications.remove(id)
            if (listeners.isEmpty()) handler.postDelayed(unbind, UNBIND_AFTER_MS)
        }
    }

    private fun route(message: Message) {
        if (message.what == ImageGenService.VERIFIED) {
            verifications[message.arg1]?.complete(message.data?.getString("error"))
            return
        }
        val event = ImageGenService.decode(message.what, message.data ?: Bundle()) ?: return
        listeners[message.arg1]?.invoke(event)
    }

    private suspend fun connect(context: Context): Messenger? {
        service?.let { return it }
        return binding.withLock {
            service?.let { return@withLock it }
            val app = context.applicationContext
            appContext = app
            val ready = CompletableDeferred<Messenger?>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val messenger = binder?.let(::Messenger)
                    service = messenger
                    ready.complete(messenger)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The process was ended (memory, most likely). The next
                    // request binds again and the system restarts it.
                    service = null
                    failAll("the generator stopped")
                }

                override fun onBindingDied(name: ComponentName?) = disconnect()
            }
            val ok = runCatching {
                app.bindService(Intent(app, ImageGenService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            }.getOrDefault(false)
            if (!ok) {
                runCatching { app.unbindService(conn) }
                return@withLock null
            }
            connection = conn
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ready.await() }
        }
    }

    private fun failAll(message: String) {
        for (listener in listeners.values.toList()) listener(ImageGenEvent.Failed(message))
    }

    private fun disconnect() {
        val conn = connection ?: return
        connection = null
        service = null
        runCatching { appContext?.unbindService(conn) }
        failAll("the generator stopped")
    }
}
