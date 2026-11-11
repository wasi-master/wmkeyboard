package com.wasimaster.wmkeyboard.ime.paperless

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.wasimaster.wmkeyboard.app.DocScanPages
import com.wasimaster.wmkeyboard.app.PaperlessUploadJob
import com.wasimaster.wmkeyboard.core.settings.PaperlessSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Scans on their way to the user's Paperless server (#1198), kept on the
 * phone until they are there.
 *
 * Each document is a file and a small JSON beside it, under the scanner's
 * own folder, which backups already leave out. An upload is a [JobScheduler]
 * job that waits for a network (Wi-Fi only, if the user asked), runs even
 * after the keyboard has gone, and backs off and tries again when the server
 * cannot be reached. A reply that will not change on a second try (a wrong
 * token, a file the server refuses) parks the document with the reason, for
 * the settings page to show and to retry or delete.
 *
 * Nothing is persisted across a reboot by the job itself (that would take
 * the boot permission): [resume] at process start schedules it again.
 */
object PaperlessQueue {

    /** One document waiting. [error] is set once it failed for a reason a retry will not fix. */
    data class Entry(
        val id: String,
        val file: File,
        val mime: String,
        val fileName: String,
        val title: String,
        val tags: List<Int>,
        val correspondent: Int,
        val documentType: Int,
        val addedMs: Long,
        val wifiOnly: Boolean,
        val attempts: Int = 0,
        val error: String? = null,
    )

    /** What the scan card chose for one scan. */
    data class Choice(
        val title: String,
        val tags: Set<Int>,
        val correspondent: Int,
        val documentType: Int,
        val onePdf: Boolean,
        val wifiOnly: Boolean,
    ) {
        companion object {
            fun defaults(s: PaperlessSettings) = Choice("", s.tags, s.correspondent, s.documentType, s.onePdf, s.wifiOnly)
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())

    /** The documents waiting, oldest first, as of the last change in this process. */
    val entries: StateFlow<List<Entry>> = _entries

    fun dir(context: Context): File = File(DocScanPages.dir(context), "paperless").apply { mkdirs() }

    /**
     * Copies [pages] into the queue as one PDF or one document per page, as
     * [choice] says, and schedules the upload. The scan's own JPEGs are left
     * where they are (the keyboard may still put them into the text box).
     * Returns how many documents were queued.
     */
    fun enqueue(context: Context, pages: List<File>, choice: Choice): Int {
        if (pages.isEmpty()) return 0
        val dir = dir(context)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        val now = System.currentTimeMillis()
        val made = ArrayList<Entry>()
        if (choice.onePdf) {
            val id = newId()
            val pdf = File(dir, "$id.pdf")
            if (JpegPdf.write(pages, pdf)) {
                made += Entry(id, pdf, "application/pdf", "Scan $stamp.pdf", choice.title.trim(), choice.tags.toList(),
                    choice.correspondent, choice.documentType, now, choice.wifiOnly)
            } else {
                pdf.delete()
            }
        }
        if (made.isEmpty()) {
            // One document per page: asked for, or the PDF could not be made.
            pages.forEachIndexed { i, page ->
                val id = newId()
                val copy = File(dir, "$id.jpg")
                if (runCatching { page.copyTo(copy, overwrite = true) }.isFailure) return@forEachIndexed
                val title = choice.title.trim().let { t -> if (t.isNotEmpty() && pages.size > 1) "$t (${i + 1})" else t }
                made += Entry(id, copy, "image/jpeg", "Scan $stamp ${i + 1}.jpg", title, choice.tags.toList(),
                    choice.correspondent, choice.documentType, now, choice.wifiOnly)
            }
        }
        made.forEach { save(context, it) }
        refresh(context)
        schedule(context)
        return made.size
    }

    /** Reads the queue from disk into [entries]. */
    fun refresh(context: Context): List<Entry> {
        val list = dir(context).listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { read(context, it) }
            .sortedBy { it.addedMs }
        _entries.value = list
        return list
    }

    fun remove(context: Context, id: String) {
        val dir = dir(context)
        File(dir, "$id.json").delete()
        dir.listFiles { f -> f.nameWithoutExtension == id }?.forEach { it.delete() }
        refresh(context)
    }

    /** Clears the parked failures so the next run tries them again, and runs it. */
    fun retryAll(context: Context) {
        refresh(context).filter { it.error != null }.forEach { save(context, it.copy(error = null)) }
        refresh(context)
        schedule(context)
    }

    /** At process start: a queue left from before a reboot, or a killed job, is scheduled again. */
    fun resume(context: Context) {
        val dir = File(DocScanPages.dir(context), "paperless")
        if (dir.listFiles { f -> f.extension == "json" }.isNullOrEmpty()) return
        schedule(context)
    }

    /**
     * Uploads what is waiting, oldest first. Returns true when something is
     * left that a later try may get through (the server unreachable), so the
     * job asks to be run again.
     */
    fun drain(context: Context, settings: PaperlessSettings): Boolean {
        if (!settings.configured) return false
        val client = PaperlessClient(settings.url, settings.token)
        if (client.base == null) return false
        var again = false
        for (entry in refresh(context)) {
            if (entry.error != null) continue
            if (!entry.file.isFile) {
                remove(context, entry.id)
                continue
            }
            try {
                client.upload(entry.file, entry.mime, entry.fileName, entry.title.ifBlank { null }, entry.tags,
                    entry.correspondent, entry.documentType)
                remove(context, entry.id)
            } catch (e: PaperlessClient.Failure) {
                if (e.permanent) {
                    save(context, entry.copy(attempts = entry.attempts + 1, error = e.message ?: "HTTP ${e.code}"))
                } else {
                    save(context, entry.copy(attempts = entry.attempts + 1))
                    again = true
                }
            } catch (e: Exception) {
                // No network after all, a timeout, the server down: later.
                save(context, entry.copy(attempts = entry.attempts + 1))
                again = true
                break
            }
        }
        refresh(context)
        return again
    }

    /** Asks for the upload job, on any network or on an unmetered one when every waiting document wants Wi-Fi. */
    fun schedule(context: Context) {
        val waiting = refresh(context).filter { it.error == null }
        if (waiting.isEmpty()) return
        val unmetered = waiting.all { it.wifiOnly }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, PaperlessUploadJob::class.java))
            .setRequiredNetworkType(if (unmetered) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        runCatching { context.getSystemService(JobScheduler::class.java)?.schedule(job) }
    }

    // ---- Files ------------------------------------------------------------------

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(ID_LENGTH)

    private fun save(context: Context, e: Entry) {
        val o = JSONObject()
            .put("file", e.file.name)
            .put("mime", e.mime)
            .put("fileName", e.fileName)
            .put("title", e.title)
            .put("tags", JSONArray(e.tags))
            .put("correspondent", e.correspondent)
            .put("documentType", e.documentType)
            .put("added", e.addedMs)
            .put("wifiOnly", e.wifiOnly)
            .put("attempts", e.attempts)
        if (e.error != null) o.put("error", e.error)
        val target = File(dir(context), "${e.id}.json")
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(o.toString())
            tmp.delete()
        }
    }

    private fun read(context: Context, json: File): Entry? = runCatching {
        val o = JSONObject(json.readText())
        val tags = o.optJSONArray("tags")
        Entry(
            id = json.nameWithoutExtension,
            file = File(dir(context), o.getString("file")),
            mime = o.optString("mime", "application/pdf"),
            fileName = o.optString("fileName", "Scan.pdf"),
            title = o.optString("title"),
            tags = (0 until (tags?.length() ?: 0)).map { tags!!.optInt(it) }.filter { it > 0 },
            correspondent = o.optInt("correspondent"),
            documentType = o.optInt("documentType"),
            addedMs = o.optLong("added"),
            wifiOnly = o.optBoolean("wifiOnly"),
            attempts = o.optInt("attempts"),
            error = o.optString("error").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Its own number beside the backup's and sync's jobs. */
    private const val JOB_ID = 20261198
    private const val BACKOFF_MS = 60_000L
    private const val ID_LENGTH = 16
}
