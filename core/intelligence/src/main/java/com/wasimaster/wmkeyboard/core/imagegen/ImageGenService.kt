package com.wasimaster.wmkeyboard.core.imagegen

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.upscale.UpscaleEngine
import com.wasimaster.wmkeyboard.core.upscale.UpscaleKind
import com.wasimaster.wmkeyboard.core.upscale.UpscaleRequest
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [ImageGenEngine] in a process of its own (`:imagegen` in the
 * manifest): a loaded model takes one to two gigabytes, which must never be
 * the keyboard's or the settings app's. If the system needs the memory back
 * it ends this process and nothing else notices. [ImageGen] is the other end.
 *
 * A [Messenger] service with two threads: requests and cancels arrive on one,
 * generation runs on the other, so a cancel lands while a picture is being
 * drawn. Every event goes back to the requester as it happens. The model is
 * let go when nothing has asked for a while.
 *
 * The upscaler ([UpscaleEngine]) runs here too, on the same worker thread:
 * it is the same MNN library, and one process holding one big picture job at
 * a time is what keeps either from running a phone out of memory.
 */
class ImageGenService : Service() {

    private lateinit var control: HandlerThread
    private lateinit var worker: HandlerThread
    private lateinit var controlHandler: Handler
    private lateinit var workHandler: Handler
    private lateinit var messenger: Messenger
    private var engine: ImageGenEngine? = null
    private var upscaler: UpscaleEngine? = null

    /** The running request's id and its cancel flag. */
    @Volatile private var running: Pair<Int, AtomicBoolean>? = null

    override fun onCreate() {
        super.onCreate()
        control = HandlerThread("imagegen-control").apply { start() }
        worker = HandlerThread("imagegen-engine", Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
        workHandler = Handler(worker.looper)
        controlHandler = Handler(control.looper) { message -> serve(message); true }
        messenger = Messenger(controlHandler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        running?.second?.set(true)
        workHandler.post {
            engine?.close()
            engine = null
            upscaler?.close()
            upscaler = null
        }
        worker.quitSafely()
        control.quitSafely()
        super.onDestroy()
    }

    private fun serve(message: Message) {
        when (message.what) {
            CANCEL -> running?.takeIf { it.first == message.arg1 }?.second?.set(true)
            VERIFY -> {
                val dir = File(message.data?.getString("dir").orEmpty())
                val replyTo = message.replyTo
                val id = message.arg1
                workHandler.post {
                    val error = try {
                        val json = MnnBackend.find(dir, "wmimage.json")?.readText()
                        val spec = json?.let { ModelSpec.parse(dir.name, it) }
                        if (spec == null) "wmimage.json is not a format this version reads" else ImageGenImport.check(dir, spec)
                    } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                        t.message ?: t.javaClass.simpleName
                    }
                    runCatching {
                        replyTo?.send(Message.obtain(null, VERIFIED, id, 0).apply { data = Bundle().apply { putString("error", error) } })
                    }
                }
            }
            GENERATE -> {
                // One picture run at a time: a new request ends the one before.
                running?.second?.set(true)
                val flag = AtomicBoolean(false)
                val id = message.arg1
                val replyTo = message.replyTo
                val request = fromBundle(message.data)
                running = id to flag
                workHandler.removeCallbacks(release)
                workHandler.post { run(id, request, flag, replyTo) }
            }
            UPSCALE -> {
                running?.second?.set(true)
                val flag = AtomicBoolean(false)
                val id = message.arg1
                val replyTo = message.replyTo
                val request = upscaleFromBundle(message.data)
                running = id to flag
                workHandler.removeCallbacks(release)
                workHandler.post { upscale(id, request, flag, replyTo) }
            }
        }
    }

    private fun upscale(id: Int, request: UpscaleRequest, cancelled: AtomicBoolean, replyTo: Messenger?) {
        fun send(event: ImageGenEvent) {
            val (what, data) = encode(event)
            runCatching { replyTo?.send(Message.obtain(null, what, id, 0).apply { this.data = data }) }
        }
        try {
            val started = System.currentTimeMillis()
            val upscaler = upscaler ?: UpscaleEngine(applicationContext).also { upscaler = it }
            val done = !cancelled.get() && upscaler.run(request, cancelled) { tile, tiles ->
                send(ImageGenEvent.Progress(0, tile, tiles, null))
            }
            if (done) send(ImageGenEvent.Image(0, File(request.output), 0L, System.currentTimeMillis() - started))
            send(ImageGenEvent.Done)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            DebugLog.w(TAG, "upscale failed: ${t.javaClass.simpleName}: ${t.message}")
            upscaler?.close()
            upscaler = null
            send(ImageGenEvent.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            if (running?.first == id) running = null
            workHandler.removeCallbacks(release)
            workHandler.postDelayed(release, IDLE_MS)
        }
    }

    private fun run(id: Int, request: ImageGenRequest, cancelled: AtomicBoolean, replyTo: Messenger?) {
        fun send(event: ImageGenEvent) {
            val (what, data) = encode(event)
            runCatching { replyTo?.send(Message.obtain(null, what, id, 0).apply { this.data = data }) }
        }
        try {
            if (!cancelled.get()) {
                val engine = engine ?: ImageGenEngine(applicationContext).also { engine = it }
                engine.generate(request, cancelled) { send(it) }
            }
            send(ImageGenEvent.Done)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            DebugLog.w(TAG, "generation failed: ${t.javaClass.simpleName}: ${t.message}")
            // A model that would not load, or ran out of memory, is let go whole.
            engine?.close()
            engine = null
            send(ImageGenEvent.Failed(t.message ?: t.javaClass.simpleName))
        } finally {
            if (running?.first == id) running = null
            workHandler.removeCallbacks(release)
            workHandler.postDelayed(release, IDLE_MS)
        }
    }

    private val release = Runnable {
        engine?.close()
        engine = null
        upscaler?.close()
        upscaler = null
    }

    companion object {
        private const val TAG = "ImageGenService"

        internal const val GENERATE = 1
        internal const val CANCEL = 2
        internal const val VERIFY = 3
        internal const val UPSCALE = 4
        internal const val VERIFIED = 16

        internal const val LOADING = 10
        internal const val PROGRESS = 11
        internal const val IMAGE = 12
        internal const val BLOCKED = 13
        internal const val FAILED = 14
        internal const val DONE = 15
        internal const val WAITING = 17

        /** The model stays loaded this long after the last picture: people try a few prompts in a row. */
        private const val IDLE_MS = 3 * 60_000L

        const val PROCESS_SUFFIX = ":imagegen"

        fun isServiceProcess(): Boolean = DebugLog.processName().endsWith(PROCESS_SUFFIX)

        internal fun toBundle(r: ImageGenRequest) = Bundle().apply {
            putString("model", r.modelId)
            putString("prompt", r.prompt)
            putString("negative", r.negative)
            putInt("size", r.size)
            putInt("steps", r.steps)
            putFloat("guidance", r.guidance)
            putLong("seed", r.seed)
            putInt("variants", r.variants)
            putBoolean("safety", r.safetyFilter)
            putBoolean("gpu", r.gpu)
        }

        internal fun fromBundle(b: Bundle) = ImageGenRequest(
            modelId = b.getString("model").orEmpty(),
            prompt = b.getString("prompt").orEmpty(),
            negative = b.getString("negative").orEmpty(),
            size = b.getInt("size"),
            steps = b.getInt("steps"),
            guidance = b.getFloat("guidance", Float.NaN),
            seed = b.getLong("seed"),
            variants = b.getInt("variants", 1),
            // A request that did not say is filtered.
            safetyFilter = b.getBoolean("safety", true),
            gpu = b.getBoolean("gpu", false),
        )

        internal fun upscaleToBundle(r: UpscaleRequest) = Bundle().apply {
            putString("input", r.input)
            putString("output", r.output)
            putString("kind", r.kind.name)
            putInt("factor", r.factor)
        }

        internal fun upscaleFromBundle(b: Bundle) = UpscaleRequest(
            input = b.getString("input").orEmpty(),
            output = b.getString("output").orEmpty(),
            kind = UpscaleKind.entries.firstOrNull { it.name == b.getString("kind") } ?: UpscaleKind.ART,
            factor = b.getInt("factor", 2),
        )

        internal fun encode(e: ImageGenEvent): Pair<Int, Bundle> = when (e) {
            ImageGenEvent.Loading -> LOADING to Bundle()
            ImageGenEvent.Done -> DONE to Bundle()
            is ImageGenEvent.Progress -> PROGRESS to Bundle().apply {
                putInt("variant", e.variant)
                putInt("step", e.step)
                putInt("steps", e.steps)
                e.preview?.let { putString("file", it.absolutePath) }
            }
            is ImageGenEvent.Image -> IMAGE to Bundle().apply {
                putInt("variant", e.variant)
                putString("file", e.file.absolutePath)
                putLong("seed", e.seed)
                putLong("millis", e.millis)
            }
            is ImageGenEvent.Blocked -> BLOCKED to Bundle().apply {
                putInt("variant", e.variant)
                putLong("seed", e.seed)
            }
            is ImageGenEvent.Failed -> FAILED to Bundle().apply { putString("message", e.message) }
            // Only the online engines wait in a queue, and they run outside this service; kept for completeness.
            is ImageGenEvent.Waiting -> WAITING to Bundle().apply {
                putInt("position", e.position)
                putInt("seconds", e.seconds)
            }
        }

        internal fun decode(what: Int, b: Bundle): ImageGenEvent? = when (what) {
            LOADING -> ImageGenEvent.Loading
            DONE -> ImageGenEvent.Done
            PROGRESS -> ImageGenEvent.Progress(b.getInt("variant"), b.getInt("step"), b.getInt("steps"), b.getString("file")?.let(::File))
            IMAGE -> ImageGenEvent.Image(b.getInt("variant"), File(b.getString("file").orEmpty()), b.getLong("seed"), b.getLong("millis"))
            BLOCKED -> ImageGenEvent.Blocked(b.getInt("variant"), b.getLong("seed"))
            FAILED -> ImageGenEvent.Failed(b.getString("message").orEmpty())
            WAITING -> ImageGenEvent.Waiting(b.getInt("position"), b.getInt("seconds"))
            else -> null
        }
    }
}
