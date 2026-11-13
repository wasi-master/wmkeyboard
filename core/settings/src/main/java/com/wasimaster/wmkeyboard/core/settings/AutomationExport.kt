package com.wasimaster.wmkeyboard.core.settings

import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.wasimaster.wmkeyboard.core.notify.NotificationIds
import com.wasimaster.wmkeyboard.core.notify.NotificationKind
import com.wasimaster.wmkeyboard.core.notify.WmNotifications
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.SafFolderSink
import com.wasimaster.wmkeyboard.settings.R
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first

/** One line of the export log. [sections] are section ids, [sender] a package or empty. */
data class AutomationExportRecord(
    val atMs: Long,
    val sections: String,
    val sender: String,
    val failed: Boolean,
) {
    companion object {
        /** One record a line, newest first, cut to [AutomationExport.MAX_LOG]. */
        fun encodeLog(records: List<AutomationExportRecord>): String =
            records.take(AutomationExport.MAX_LOG).joinToString("\n") {
                listOf(it.atMs, it.sections, it.sender, if (it.failed) "1" else "0").joinToString("\t")
            }

        fun decodeLog(raw: String): List<AutomationExportRecord> = raw.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            val at = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size == 4 && at != null) AutomationExportRecord(at, parts[1], parts[2], parts[3] == "1") else null
        }.toList()
    }
}

/**
 * The `EXPORT_DATA` automation command (#1076): the user's data as a file,
 * rather than handed over field by field through an intent.
 *
 * The folder is the user's pick and the sender cannot name another. The
 * automation receiver is exported with no permission, so a sender-named path
 * would let any installed app have the dictionary sent to itself once the row
 * is on. Who can read the file is then the folder's business, which the user
 * decided.
 *
 * Reading is silent where typing is not, and a word list is a fingerprint of a
 * person, so every export posts a notification and leaves a line in the log on
 * the Allowed actions screen.
 */
object AutomationExport {

    /** Always this name: an automation can find it, and a daily run leaves one copy, not a year of them. */
    const val FILE_NAME = "wm-keyboard-export.json"

    const val MAX_LOG = 20

    /**
     * What a sender may ask for. The clipboard stays out because it holds what
     * people copy out of password managers, and notes because a locked note's
     * neighbours are as private; the rest (stickers, icons, word lists) are
     * big and not the user's writing.
     */
    val EXPORTABLE: Set<ConfigBackup.Section> = setOf(
        ConfigBackup.Section.SETTINGS,
        ConfigBackup.Section.DICTIONARY,
        ConfigBackup.Section.SNIPPETS,
        ConfigBackup.Section.EMOJI,
        ConfigBackup.Section.STATISTICS,
        ConfigBackup.Section.VOCAB,
    )

    private val DEFAULT_SECTIONS = setOf(ConfigBackup.Section.SETTINGS, ConfigBackup.Section.DICTIONARY)

    /** The sections [value] names, the default when it is blank, null when it names one [EXPORTABLE] leaves out. */
    fun parseSections(value: String?): Set<ConfigBackup.Section>? {
        val ids = value.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return DEFAULT_SECTIONS
        return ids.mapTo(LinkedHashSet()) { id -> EXPORTABLE.firstOrNull { it.id == id } ?: return null }
    }

    /**
     * Writes the file, and tells the user. Checks the permission again: the job
     * can start after the row was switched off.
     */
    suspend fun run(
        context: Context,
        repository: SettingsRepository,
        sections: Set<ConfigBackup.Section>,
        sender: String,
    ) {
        val automation = repository.automation.first()
        if (!automation.allows(AutomationPermission.EXPORT_DATA) || sections.isEmpty()) return
        val written = try {
            write(context, repository, automation.exportFolder, sections)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupLog.w("automation export failed", e)
            false
        }
        repository.recordAutomationExport(
            AutomationExportRecord(
                System.currentTimeMillis(),
                sections.joinToString(", ") { it.id },
                sender,
                failed = !written,
            ),
        )
        if (written) notify(context)
    }

    private suspend fun write(
        context: Context,
        repository: SettingsRepository,
        folder: String,
        sections: Set<ConfigBackup.Section>,
    ) {
        require(folder.isNotEmpty())
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = info.versionCode
        // One section at a time, each written out before the next is read
        // in, as the backups do (#1080): a large dictionary and statistics
        // together were held whole before the first byte went out. Staged in
        // the cache, since the folder's writer takes a plain block.
        val staged = withContext(Dispatchers.IO) { File.createTempFile("automation-export", ".json", context.cacheDir) }
        try {
            withContext(Dispatchers.IO) {
                staged.outputStream().buffered().use { out ->
                    repository.exportConfigTo(
                        out,
                        sections,
                        includeSecrets = false,
                        appVersion = versionCode,
                        appVersionName = info.versionName.orEmpty(),
                        excludeKeys = setOf(SettingsBackup.AUTO_BACKUP_LOCATIONS),
                    )
                }
            }
            SafFolderSink(context, Uri.parse(folder)).write(FILE_NAME, ConfigBackup.MIME_TYPE) { out ->
                staged.inputStream().use { it.copyTo(out) }
            }.getOrThrow()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { staged.delete() }
        }
    }

    private fun notify(context: Context) {
        WmNotifications.post(context, NotificationKind.BACKUP, NotificationIds.AUTOMATION_EXPORT) {
            setContentTitle(context.getString(R.string.core_settings_notify_export_title))
            val body = context.getString(R.string.core_settings_notify_export_body, FILE_NAME)
            setContentText(body)
            setStyle(NotificationCompat.BigTextStyle().bigText(body))
            setAutoCancel(true)
            val launch = WmNotifications.launchIntent(context) ?: return@post
            setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }
}
