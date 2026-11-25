package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one file a plugin preview may read. Never a path: the file manager
 * hands over its name, type and size, and reads bytes for the plugin.
 */
interface PreviewSource {
    /** The file's name as the user sees it: `Report.docx`. */
    val name: String
    val mime: String?
    val size: Long

    /** When it last changed, in milliseconds since 1970, or null. */
    val modified: Long?

    /** What tells this file apart from every other, for the thumbnail cache: its path or address. */
    val identity: String

    /** Up to [length] bytes from [offset]; fewer at the end of the file. Blocking; throws on a failed read. */
    fun read(offset: Long, length: Int): ByteArray
}

/** What one preview came to. */
sealed interface PluginPreviewResult {
    data class Shown(val ui: RenderedUi, val log: List<String>) : PluginPreviewResult

    /** The plugin failed or refused; the file manager offers the built-in view or another app. */
    data class Failed(val message: PluginText) : PluginPreviewResult

    /** Stopped for running away. [disabled] means it used its last strike. */
    data class Stopped(val message: PluginText, val disabled: Boolean) : PluginPreviewResult
}

/**
 * Runs plugin file previews and thumbnails (API 5): the file manager's
 * "View with" chips and its grid pictures for types the keyboard does not
 * draw itself.
 *
 * Each call is a fresh interpreter, as a selection action is
 * ([PluginActionRunner]): the plugin's script loads, its `@plugin.preview`
 * function draws the file, and the whole thing is freed. The plugin is given
 * the file's first [PluginPreviewSpec.maxBytes] bytes; `file.read()` reaches
 * further through the `preview.read` host call, which reads that one file and
 * nothing else, a megabyte at a time.
 *
 * A preview that runs away earns a strike as an action does, and the
 * watchdog abandons one still running a grace period past its deadline. A
 * thumbnail never earns a strike: one plugin failing three thumbnails in a
 * row is paused for the rest of the keyboard's life instead, so a broken
 * plugin costs the grid three icons rather than a stutter on every scroll.
 *
 * [preview] and [cancel] may be called from any thread; one preview runs at
 * a time and a new one cancels the last. [thumbnail] blocks, and is for the
 * thumbnail loader's own background threads.
 */
class PluginPreviewRunner(
    private val store: PluginStore,
    private val post: (() -> Unit) -> Unit = { it() },
    /** The rest of the API 5 host calls: a preview may use the network it declares, like any call. */
    private val services: PluginServices = PluginServices.NONE,
    /** Where thumbnails are kept between calls, or null for none. */
    cacheDir: File? = null,
    private val previewLimit: PluginLimit = PluginLimit.PREVIEW,
    private val thumbnailLimit: PluginLimit = PluginLimit.THUMBNAIL,
    private val watchdogPeriodMillis: Long = 250,
    private val watchdogGraceMillis: Long = 750,
) {
    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "wm-plugin-preview-watchdog").apply { isDaemon = true } }

    val thumbnails = PluginThumbnailCache(cacheDir)

    /** Thumbnail failures in a row, by plugin id; at [MAX_THUMBNAIL_FAILURES] the plugin draws none until restart. */
    private val thumbnailFailures = ConcurrentHashMap<String, Int>()

    private var current: Call? = null

    /** Draws [source] with [ref]. Cancels the preview before it. */
    @Synchronized
    fun preview(ref: PluginPreviewRef, source: PreviewSource, onDone: (PluginPreviewResult) -> Unit) {
        cancel()
        refusal(ref.plugin.id)?.let { reason ->
            post { onDone(PluginPreviewResult.Failed(reason)) }
            return
        }
        val plugin = store.plugin(ref.plugin.id) ?: return
        val script = store.script(plugin.id) ?: return
        val call = Call(plugin, onDone)
        current = call
        val thread = Thread(null, { execute(call, ref.preview, script, source) }, "wm-plugin-preview-${plugin.id}", PluginRuntime.PLUGIN_STACK_BYTES).apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
        call.thread = thread
        call.deadlineNanos = System.nanoTime() + previewLimit.wallMillis * NANOS_PER_MILLI
        call.watch = watchdog.scheduleWithFixedDelay({ patrol(call) }, watchdogPeriodMillis, watchdogPeriodMillis, TimeUnit.MILLISECONDS)
        thread.start()
    }

    /** Stops the running preview; its result is dropped. */
    @Synchronized
    fun cancel() {
        val call = current ?: return
        current = null
        call.cancelled = true
        call.python?.cancel()
        call.watch?.cancel(false)
    }

    @Synchronized
    fun shutdown() {
        cancel()
        watchdog.shutdownNow()
    }

    /**
     * The picture [ref]'s `@plugin.thumbnail` makes for [source]: from the
     * cache when it has one, else drawn now. Null for none (the plugin
     * returned None, failed, is paused, or the subsystem is off). Blocks for
     * at most the thumbnail allowance and a grace period.
     */
    fun thumbnail(ref: PluginPreviewRef, source: PreviewSource): ByteArray? {
        if (!ref.preview.thumbnails || refusal(ref.plugin.id) != null) return null
        val plugin = store.plugin(ref.plugin.id) ?: return null
        val key = PluginThumbnailCache.keyOf(source, plugin, ref.preview.id)
        thumbnails.get(key)?.let { return it.takeIf { bytes -> bytes.isNotEmpty() } }
        if ((thumbnailFailures[plugin.id] ?: 0) >= MAX_THUMBNAIL_FAILURES) return null
        val script = store.script(plugin.id) ?: return null
        var made: ByteArray? = null
        var failed = false
        var session: PythonSession? = null
        val worker = Thread(null, {
            val outcome = runCatching { draw(plugin, ref.preview, script, source, thumb = true) { session = it } }.getOrNull()
            val result = outcome?.first
            when {
                result == null || !result.ok -> failed = true
                else -> made = result.value?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() } ?: EMPTY
            }
        }, "wm-plugin-thumb-${plugin.id}", PluginRuntime.PLUGIN_STACK_BYTES).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        worker.start()
        worker.join(thumbnailLimit.wallMillis + watchdogGraceMillis)
        if (worker.isAlive) {
            session?.cancel()
            worker.join(watchdogGraceMillis)
            failed = true
        }
        if (failed) {
            thumbnailFailures.merge(plugin.id, 1, Int::plus)
            return null
        }
        thumbnailFailures.remove(plugin.id)
        val bytes = made ?: EMPTY
        // An empty entry remembers "no picture" so the grid does not ask again.
        thumbnails.put(key, bytes)
        return bytes.takeIf { it.isNotEmpty() }
    }

    /** Forgets every thumbnail [pluginId] made: on update and removal. */
    fun forget(pluginId: String) {
        thumbnails.forget(pluginId)
        thumbnailFailures.remove(pluginId)
    }

    private fun refusal(pluginId: String): PluginText? {
        val plugin = store.plugin(pluginId) ?: return PluginText.of(R.string.core_plugins_error_not_installed)
        return when {
            !store.subsystemEnabled() || !plugin.enabled -> PluginText.of(R.string.core_plugins_error_turned_off)
            !plugin.grants(PluginPermission.Previews) -> PluginText.of(R.string.core_plugins_error_no_preview)
            store.script(pluginId) == null -> PluginText.of(R.string.core_plugins_error_script_missing)
            !PythonNative.available -> PythonNative.notReady()
            else -> null
        }
    }

    // ---- on a call's own thread ---------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun execute(call: Call, spec: PluginPreviewSpec, script: String, source: PreviewSource) {
        val log = PluginLog(store.logFile(call.plugin.id))
        val result: PluginPreviewResult? = try {
            val (outcome, _) = draw(call.plugin, spec, script, source, thumb = false) { session ->
                call.python = session
                if (call.cancelled) session.cancel()
            }
            for (line in outcome.log) log.add(line)
            when {
                outcome.ok && outcome.ui != null -> PluginPreviewResult.Shown(outcome.ui, outcome.log)
                outcome.error != null -> failed(call, log, outcome.error)
                else -> PluginPreviewResult.Failed(PluginText.of(R.string.core_plugins_error_script_failed))
            }
        } catch (failure: java.io.IOException) {
            log.add("error: the file could not be read: ${failure.message?.take(MAX_ERROR)}")
            PluginPreviewResult.Failed(PluginText.of(R.string.core_plugins_error_preview_unreadable))
        } catch (failure: Throwable) {
            val name = failure.javaClass.simpleName
            log.add("error: " + (failure.message?.take(MAX_ERROR)?.let { "$name: $it" } ?: name))
            PluginPreviewResult.Failed(PluginText.of(R.string.core_plugins_stopped_unexpected))
        } finally {
            call.deadlineNanos = Long.MAX_VALUE
            call.watch?.cancel(false)
            log.flush()
        }
        if (result != null) deliver(call, result)
    }

    /**
     * One preview or thumbnail call on this thread: the session, the file's
     * head, the `preview.read` binding. Returns the result and how many bytes
     * the head held.
     */
    private fun draw(
        plugin: InstalledPlugin,
        spec: PluginPreviewSpec,
        script: String,
        source: PreviewSource,
        thumb: Boolean,
        onSession: (PythonSession) -> Unit,
    ): Pair<PythonSession.Result, Int> {
        val python = PythonSession.create(
            PythonSession.Config(
                plugin = plugin,
                mode = PythonSession.Config.ACTION,
                storagePath = store.storageFile(plugin.id)?.path,
                remindersPath = store.remindersFile(plugin.id)?.path,
                preview = previewLimit,
                thumbnail = thumbnailLimit,
                modules = store.modules(plugin.id),
                strings = store.strings(plugin.id),
                surface = SURFACE,
            ),
        ) ?: return PythonSession.Result(ok = false, error = PythonSession.Error(PythonSession.Error.INTERNAL, "", null, null)) to 0
        onSession(python)
        val binding = PluginHost.Binding(plugin, store, PreviewServices(services, source), SURFACE) {}
        PluginHost.bind(python.id, binding)
        try {
            val loaded = python.load(script)
            if (loaded.error != null) return loaded to 0
            val headSize = minOf(source.size, spec.maxBytes).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            val head = if (headSize == 0) ByteArray(0) else source.read(0, headSize)
            val request = requestJson(spec.id, source, truncated = source.size > head.size)
            val result = if (thumb) python.thumbnail(request, head) else python.preview(request, head)
            return result.copy(log = loaded.log + result.log) to head.size
        } finally {
            PluginHost.unbind(python.id)
            python.close()
        }
    }

    private fun failed(call: Call, log: PluginLog, error: PythonSession.Error): PluginPreviewResult? {
        if (error.kind == PythonSession.Error.NO_PREVIEW) {
            return PluginPreviewResult.Failed(PluginText.of(R.string.core_plugins_error_no_preview))
        }
        if (error.kind == PythonSession.Error.ABORT) {
            val reason = PluginAbortReason.ofWire(error.abort) ?: PluginAbortReason.INSTRUCTIONS
            log.add("stopped: ${error.abort ?: "steps"}")
            if (reason == PluginAbortReason.CANCELLED || call.cancelled || call.abandoned) return null
            if (reason == PluginAbortReason.RECURSION || reason == PluginAbortReason.MEMORY) {
                return PluginPreviewResult.Failed(PluginText.of(reason.messageRes))
            }
            val disabled = store.recordAbandon(call.plugin.id)?.enabled == false
            return PluginPreviewResult.Stopped(PluginText.of(reason.messageRes), disabled)
        }
        val text = error.message.take(MAX_ERROR)
        log.add("error: ${text.ifEmpty { "the script failed" }}")
        return PluginPreviewResult.Failed(
            text.takeIf { it.isNotEmpty() }?.let { PluginText.Script(PluginRuntime.locatedText(it, error.line)) }
                ?: PluginText.of(R.string.core_plugins_error_script_failed),
        )
    }

    // ---- the watchdog -------------------------------------------------------

    private fun patrol(call: Call) {
        if (call.cancelled || call.abandoned || call.delivered.get()) return
        val deadline = call.deadlineNanos
        if (deadline == Long.MAX_VALUE) return
        val over = System.nanoTime() - deadline
        val grace = watchdogGraceMillis * NANOS_PER_MILLI
        if (over > grace && over <= 2 * grace) {
            call.python?.cancel()
            return
        }
        if (over <= 2 * grace) return
        call.abandoned = true
        call.watch?.cancel(false)
        runCatching { call.thread?.priority = Thread.MIN_PRIORITY }
        val disabled = store.recordAbandon(call.plugin.id)?.enabled == false
        val message = PluginText.of(if (disabled) R.string.core_plugins_stopped_not_responding_disabled else R.string.core_plugins_stopped_not_responding)
        deliver(call, PluginPreviewResult.Stopped(message, disabled), evenIfAbandoned = true)
        synchronized(this) { if (current === call) current = null }
    }

    private fun deliver(call: Call, result: PluginPreviewResult, evenIfAbandoned: Boolean = false) {
        if (call.cancelled) return
        if (call.abandoned && !evenIfAbandoned) return
        if (!call.delivered.compareAndSet(false, true)) return
        synchronized(this) { if (current === call) current = null }
        post { if (!call.cancelled) call.onDone(result) }
    }

    private class Call(val plugin: InstalledPlugin, val onDone: (PluginPreviewResult) -> Unit) {
        val delivered = AtomicBoolean(false)

        @Volatile var python: PythonSession? = null

        @Volatile var cancelled = false

        @Volatile var abandoned = false

        @Volatile var deadlineNanos = Long.MAX_VALUE

        @Volatile var thread: Thread? = null

        @Volatile var watch: ScheduledFuture<*>? = null
    }

    /**
     * The host calls of a preview: `preview.read` from [source], a megabyte
     * at most, and everything else as [base] answers it.
     */
    private class PreviewServices(private val base: PluginServices, private val source: PreviewSource) : PluginServices {
        override fun call(call: PluginCall): JsonElement {
            if (call.op != "preview.read") return base.call(call)
            val offset = (call.args["offset"] as? JsonPrimitive)?.longOrNull ?: 0L
            val length = ((call.args["length"] as? JsonPrimitive)?.longOrNull ?: 0L).coerceIn(0, MAX_READ.toLong()).toInt()
            if (offset < 0) throw PluginCallException("ValueError", "preview.read: the offset can't be negative")
            if (offset >= source.size || length == 0) return JsonPrimitive("")
            val bytes = try {
                source.read(offset, minOf(length.toLong(), source.size - offset).toInt())
            } catch (failure: java.io.IOException) {
                throw PluginCallException("OSError", "the file could not be read: ${failure.message.orEmpty().take(MAX_ERROR)}")
            }
            return JsonPrimitive(Base64.getEncoder().encodeToString(bytes))
        }

        override fun dataSaver(): Boolean = base.dataSaver()
    }

    companion object {
        /** What `plugin.surface` says during a preview. */
        const val SURFACE = "preview"

        /** One `preview.read`. */
        const val MAX_READ = 1024 * 1024

        const val MAX_THUMBNAIL_FAILURES = 3
        private const val MAX_ERROR = 300
        private const val NANOS_PER_MILLI = 1_000_000L
        private val EMPTY = ByteArray(0)

        /** The request `wm._host.preview` reads. */
        fun requestJson(previewId: String, source: PreviewSource, truncated: Boolean): String = buildJsonObject {
            put("id", previewId)
            put("name", source.name)
            put("extension", source.name.substringAfterLast('.', "").lowercase().takeIf { '.' in source.name }.orEmpty())
            put("mime", source.mime?.let(::JsonPrimitive) ?: JsonNull)
            put("size", source.size)
            put("modified", source.modified?.let { JsonPrimitive(it / 1000) } ?: JsonNull)
            put("truncated", truncated)
        }.toString()
    }
}

/**
 * Plugin thumbnails on disk: `cacheDir/plugin-thumbs/<hash>`, the picture's
 * bytes as the plugin made them (an empty file for "no picture"), dropped
 * oldest first past [MAX_BYTES]. The name hashes the file's identity, size
 * and time with the plugin's id, version and preview, so a changed file or
 * an updated plugin is simply a miss; [forget] clears a plugin's at once.
 */
class PluginThumbnailCache(cacheDir: File?) {

    private val dir: File? = cacheDir?.let { File(it, DIR) }

    fun get(key: String): ByteArray? {
        val file = file(key) ?: return null
        if (!file.isFile) return null
        file.setLastModified(System.currentTimeMillis())
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun put(key: String, bytes: ByteArray) {
        val file = file(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }
        trim()
    }

    /** Drops every thumbnail [pluginId] made. */
    fun forget(pluginId: String) {
        val prefix = pluginPrefix(pluginId)
        dir?.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    private fun file(key: String): File? = dir?.let { File(it, key) }

    @Synchronized
    private fun trim() {
        val files = dir?.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_BYTES * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        const val DIR = "plugin-thumbs"
        const val MAX_BYTES = 32L * 1024 * 1024

        /** `<plugin hash>-<file hash>`: the plugin part lets [forget] find a plugin's files by name. */
        fun keyOf(source: PreviewSource, plugin: InstalledPlugin, previewId: String): String {
            val file = sha1("${source.identity}|${source.size}|${source.modified}")
            return pluginPrefix(plugin.id) + sha1("${plugin.version}|$previewId|$file").take(24)
        }

        private fun pluginPrefix(pluginId: String) = sha1(pluginId).take(12) + "-"

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
