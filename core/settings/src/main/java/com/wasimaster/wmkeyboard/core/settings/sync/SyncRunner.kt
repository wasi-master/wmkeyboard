package com.wasimaster.wmkeyboard.core.settings.sync

import com.wasimaster.wmkeyboard.core.settings.isLink
import com.wasimaster.wmkeyboard.core.settings.needsNetwork
import android.content.Context
import android.util.Base64
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.settings.AutoBackupRunner
import com.wasimaster.wmkeyboard.core.settings.AutoBackupSettings
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.BackupGate
import com.wasimaster.wmkeyboard.core.settings.BackupInstall
import com.wasimaster.wmkeyboard.core.settings.BackupLocation
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.keepLocalGroups
import com.wasimaster.wmkeyboard.core.settings.paused
import com.wasimaster.wmkeyboard.core.settings.sectionSet
import com.wasimaster.wmkeyboard.core.settings.sink.BackupLog
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSink
import com.wasimaster.wmkeyboard.core.settings.sink.BackupSinkException
import com.wasimaster.wmkeyboard.core.settings.sink.SinkEntry
import com.wasimaster.wmkeyboard.core.settings.sink.SinkError
import com.wasimaster.wmkeyboard.core.settings.sink.sinkErrorOf
import com.wasimaster.wmkeyboard.core.settings.targets
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One sync pass: read the other devices' files, merge, apply, write ours.
 *
 * Every location sync uses holds one file per device (see [SyncNaming]), and
 * each file carries everything its device knows, not just what it changed. So
 * two phones that never sync at the same moment still converge, and a third
 * joining later learns everything from whichever file it reads first.
 *
 * The same safety rule as a backup: a locked phone does nothing, because the
 * stores it would read are not there yet and would look like deletions.
 */
object SyncRunner {

    private const val STATE_FILE = "sync_state.json"
    private const val FILTER_FILE = "sync_filter.json"

    /** A sync-only reason, beside the `SinkError` names, for a file sealed under another passphrase. */
    const val ERROR_PASSPHRASE = "SYNC_PASSPHRASE"


    /** Whether the active network costs data; true with none, which is no Wi-Fi either. */
    private fun onMeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.isActiveNetworkMetered || cm.activeNetwork == null
    }

    sealed interface Outcome {
        /** [applied] entries changed here; [devices] other devices were read. */
        data class Done(val applied: Int, val devices: Int, val failed: Map<String, String>) : Outcome
        data object Skipped : Outcome
        data object Locked : Outcome
        data class Failed(val reason: String) : Outcome
    }

    suspend fun run(
        context: Context,
        repository: SettingsRepository,
        force: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome = BackupGate.mutex.withLock {
        BackupTraffic.unattended = !force
        try {
            runCancellable { runLocked(context.applicationContext, repository, force, nowMs) }
                .getOrElse { failure ->
                    val reason = sinkErrorOf(failure).name
                    BackupLog.w("sync failed: $reason", failure)
                    repository.setSyncOutcome(nowMs, reason)
                    Outcome.Failed(reason)
                }
        } finally {
            BackupTraffic.unattended = false
        }
    }

    private suspend fun runLocked(
        context: Context,
        repository: SettingsRepository,
        force: Boolean,
        nowMs: Long,
    ): Outcome {
        val auto = repository.settings.first().autoBackup
        val sync = auto.sync
        if (!force && !sync.enabled) return Outcome.Skipped
        // A pause keeps the jobs in place (see BackupPause); each firing ends here.
        if (!force && sync.paused(nowMs)) return Outcome.Skipped
        val targets = sync.targets(auto.locations)
        val sections = sync.sectionSet
        if (targets.isEmpty() || sections.isEmpty()) return Outcome.Skipped
        if (!DirectBoot.isUserUnlocked(context)) return Outcome.Locked
        // Wi-Fi only (#928). The job already waits for an unmetered network,
        // but a "soon" pass runs at its deadline whatever the network, and
        // a pass can be asked for from elsewhere: on mobile data it waits.
        if (!force && sync.wifiOnly && targets.any { it.type.needsNetwork } && onMeteredNetwork(context)) {
            return Outcome.Skipped
        }

        val encrypt = auto.encrypt && auto.passphrase.isNotEmpty()
        val filter = SyncFilter(sync.includeSecrets, sync.keepLocalGroups)
        val me = BackupInstall.id(context)
        val stateFile = File(context.noBackupFilesDir, STATE_FILE)
        val remembered = SyncStateCodec.decode(runCatching { stateFile.readText() }.getOrNull())
        val firstSync = remembered == null
        val filterFile = File(context.noBackupFilesDir, FILTER_FILE)
        // Before this file existed there was no choice to have changed.
        val lastFilter = SyncFilter.decode(runCatching { filterFile.readText() }.getOrNull()) ?: filter

        // Every other device's newest file, from every target. The same device
        // can turn up at two locations; its later file wins.
        val remotes = LinkedHashMap<String, SyncFile>()
        val failed = LinkedHashMap<String, String>()
        val readable = ArrayList<Pair<BackupLocation, BackupSink>>()
        // Locations that already hold this device's file under exactly the
        // name it would write now. A changed passphrase, device name or group
        // changes the name, and then the file has to be written again.
        val device = BackupInstall.deviceLabel(context)
        val names = HashMap<String, String>()
        val hasMine = HashSet<String>()
        for (location in targets) {
            val sink = AutoBackupRunner.sinkFor(context, location)
            if (sink == null) {
                failed[location.id] = SinkError.NOT_CONFIGURED.name
                continue
            }
            val group = groupAt(location, sync.group)
            val name = SyncNaming.name(me, device, encrypt, group).also { names[location.id] = it }
            val read = runCancellable { readRemotes(sink, me, auto, group) }
            read.onSuccess { (files, passphraseMiss, mine) ->
                readable += location to sink
                if (name in mine) hasMine += location.id
                for (file in files) {
                    val known = remotes[file.installId]
                    if (known == null || file.writtenAtMs > known.writtenAtMs) remotes[file.installId] = file
                }
                if (passphraseMiss) failed[location.id] = ERROR_PASSPHRASE
            }.onFailure { failure ->
                failed[location.id] = ((failure as? BackupSinkException)?.reason ?: SinkError.IO).name
            }
        }
        if (readable.isEmpty()) {
            val reason = failed.values.firstOrNull() ?: SinkError.IO.name
            recordLocations(repository, targets, failed, nowMs)
            repository.setSyncOutcome(nowMs, reason)
            return Outcome.Failed(reason)
        }

        // A phone that synced the statistics as one shared total, before
        // they synced per device (#447), holds a blend of every device's
        // counts as its own. Counted beside the others' own, that blend would
        // count everything twice, so it moves aside once, under a shared name.
        if (ConfigBackup.Section.STATISTICS in sections &&
            remembered?.get(ConfigBackup.Section.STATISTICS.id)?.isNotEmpty() == true &&
            remembered?.containsKey(SyncStatistics.SECTION_ID) != true
        ) {
            repository.retireSharedStatistics()
        }

        // Read here, after the downloads rather than before them: whatever
        // changed on this phone while those ran is in it, and the gap before
        // [apply] writes is as short as it can be.
        val local = localEntries(repository, sections, filter, me)

        // Only what this phone would sync itself, from either side. A setting
        // that is per-device here, one the user keeps on this device, or a key
        // while keys are off, is neither taken from another phone nor, once it
        // drops out of the local view, sent back to them as a deletion.
        fun settingsOnly(table: Map<String, Map<String, Stamped>>) = table.mapValues { (section, entries) ->
            if (section != ConfigBackup.Section.SETTINGS.id) entries
            else entries.filterKeys(filter::syncable)
        }
        val rememberedHere = remembered.orEmpty().mapValues { (section, entries) ->
            if (section != ConfigBackup.Section.SETTINGS.id) entries
            else entries.filterKeys(filter::syncable)
        }
        val result = SyncMerge.merge(
            local = local,
            remembered = rememberedHere,
            remotes = remotes.values.map { file -> settingsOnly(file.table.filterKeys { it in local }) },
            me = me,
            nowMs = nowMs,
            firstSync = firstSync,
            rejoining = { section, key ->
                section == ConfigBackup.Section.SETTINGS.id && !lastFilter.syncable(key) && filter.syncable(key)
            },
            owned = { section, key -> SyncStatistics.owned(section, key, me) },
        )
        val applied = apply(repository, result, local, me)

        // What is remembered is what this phone holds after applying, so the
        // next pass does not mistake a store's own formatting of a value it was
        // just given for a change made here.
        val after = if (applied > 0) localEntries(repository, sections, filter, me) else local
        val nextState = result.remembered.mapValues { (section, entries) ->
            val now = after[section].orEmpty()
            entries.mapValues { (key, r) ->
                if (r.hash != null && key in now) r.copy(hash = SyncMerge.hash(now[key])) else r
            }
        }

        // Nothing new anywhere: the file already there says all of it. This is
        // the common pass, the one a synced change sets off on the phone that
        // just received it, and it should cost a listing, not an upload.
        val unchanged = remembered != null && nextState == rememberedHere
        val file = SyncFile(me, device, nowMs, result.merged)
        val bytes = encodeFile(file, auto, encrypt)
        for ((location, sink) in readable) {
            if (unchanged && location.id in hasMine) continue
            writeOwn(sink, names.getValue(location.id), bytes, me)
                .onFailure { failure ->
                    failed[location.id] = ((failure as? BackupSinkException)?.reason ?: SinkError.IO).name
                }
        }
        runCatching { stateFile.writeText(SyncStateCodec.encode(nextState)) }
        runCatching { filterFile.writeText(SyncFilter.encode(filter)) }

        recordLocations(repository, targets, failed, nowMs)
        val wrote = readable.any { (location, _) -> location.id !in failed }
        repository.setSyncOutcome(nowMs, if (wrote) "" else failed.values.firstOrNull().orEmpty())
        BackupLog.d("sync: devices=${remotes.size} applied=$applied failed=$failed first=$firstSync")
        return Outcome.Done(applied, remotes.size, failed)
    }

    /**
     * The name a section syncs under. The statistics sync per device, under
     * a name of their own; see [SyncStatistics.SECTION_ID].
     */
    private fun syncId(section: ConfigBackup.Section): String =
        if (section == ConfigBackup.Section.STATISTICS) SyncStatistics.SECTION_ID else section.id

    /** Each synced section on this phone, as entries. A section with nothing in it is empty, not absent. */
    private suspend fun localEntries(
        repository: SettingsRepository,
        sections: Set<ConfigBackup.Section>,
        filter: SyncFilter,
        me: String,
    ): Map<String, Map<String, JsonElement>> {
        val exported = sections - ConfigBackup.Section.STATISTICS
        val parsed = repository.exportSections(exported, filter.includeSecrets)
        return sections.associate { section ->
            val element = parsed[section]
            val entries = when {
                // One entry per device, compared whole: only its own device
                // ever changes one, so no two phones write the same entry.
                section == ConfigBackup.Section.STATISTICS ->
                    SyncEntries.explode(repository.statisticsByDevice(me), deep = false)
                element == null -> emptyMap()
                section == ConfigBackup.Section.SETTINGS ->
                    (element as? JsonObject).orEmpty().filterKeys(filter::syncable)
                else -> SyncEntries.explode(element)
            }
            syncId(section) to entries
        }
    }

    /**
     * Writes what the merge decided. Settings go straight into the store,
     * deletions included; every other section is rebuilt whole from its
     * merged entries and imported the way a restore would, which already knows
     * how to put each store back.
     */
    private suspend fun apply(
        repository: SettingsRepository,
        result: SyncMerge.Result,
        local: Map<String, Map<String, JsonElement>>,
        me: String,
    ): Int {
        var count = 0
        val bundleSections = LinkedHashMap<ConfigBackup.Section, JsonElement>()
        for ((sectionId, changes) in result.changes) {
            // A section sync never carries is never applied, whatever another
            // device's file holds.
            val section = ConfigBackup.Section.entries
                .firstOrNull { syncId(it) == sectionId }?.takeIf { it.syncable } ?: continue
            count += changes.size
            if (section == ConfigBackup.Section.SETTINGS) {
                val put = JsonObject(changes.filterValues { it != null }.mapValues { it.value!! })
                val remove = changes.filterValues { it == null }.keys
                repository.applySyncedSettings(put, remove)
            } else {
                val entries = result.merged[sectionId].orEmpty()
                    .filterValues { !it.deleted }
                    .mapValues { it.value.value!! }
                val rootIsArray = section == ConfigBackup.Section.THEMES
                // Everything in it deleted is still an answer: an empty store.
                // Skipping it left this phone's copy in place, which the next
                // pass read as fresh edits here and sent straight back.
                val element = SyncEntries.implode(entries, rootIsArray)
                    ?: if (rootIsArray) JsonArray(emptyList()) else JsonObject(emptyMap())
                if (section == ConfigBackup.Section.STATISTICS) {
                    // Only the other devices' counts are written; this one's
                    // belong to the keyboard, which is still counting.
                    repository.applySyncedStatistics(
                        element as? JsonObject ?: JsonObject(emptyMap()),
                        me = me,
                        hadOwn = local[sectionId]?.containsKey(me) == true,
                    )
                } else if (section == ConfigBackup.Section.CLIPBOARD) {
                    // Merged into the clipboard rather than written over it:
                    // the synced view is text clips only, and images, files and
                    // sensitive clips never leave this phone.
                    repository.applySyncedClipboard(element as? JsonObject ?: JsonObject(emptyMap()))
                } else {
                    bundleSections[section] = element
                }
            }
        }
        if (bundleSections.isNotEmpty()) {
            repository.importConfig(ConfigBackup.encode(0, "", bundleSections))
        }
        return count
    }

    /**
     * The group this device syncs in at [location]. Never one at a link
     * ([isLink]): a paired computer, or the copy in another profile, belongs to
     * the same person, and the KDE Connect share that carries the file to a
     * computer takes ungrouped names only.
     */
    fun groupAt(location: BackupLocation, group: String): String =
        if (location.type.isLink) "" else group

    /**
     * The sync groups other devices use at [location], sorted, with "" for
     * devices in none; empty when nothing else syncs there. For the screen
     * that picks a group, so joining a shared folder starts from the names
     * already in it rather than a guess at their spelling.
     */
    suspend fun groupsAt(context: Context, location: BackupLocation): Result<List<String>> {
        val sink = AutoBackupRunner.sinkFor(context, location)
            ?: return Result.failure(BackupSinkException(SinkError.NOT_CONFIGURED))
        return runCancellable {
            sink.readiness().getOrThrow()
            SyncNaming.groups(sink.list().getOrThrow().map { it.name }, except = BackupInstall.id(context))
        }
    }

    private data class Remote(val files: List<SyncFile>, val passphraseMiss: Boolean, val mine: Set<String>)

    /**
     * The other devices' newest files in [group] at one location, whether any
     * would not open, and every name this device's own files have there, in
     * any group. Another group's files are not even downloaded: they are other
     * people's, and merged here they would become this person's settings.
     */
    private suspend fun readRemotes(
        sink: BackupSink,
        me: String,
        auto: AutoBackupSettings,
        group: String,
    ): Remote {
        sink.readiness().getOrThrow()
        val all = sink.list().getOrThrow().filter { SyncNaming.isOurs(it.name) }
        val mine = all.filter { SyncNaming.installId(it.name) == me }.mapTo(HashSet()) { it.name }
        val newest = SyncNaming.newestOfOthers(all, me, group)
        var passphraseMiss = false
        val files = newest.mapNotNull { entry ->
            val bytes = sink.read(entry).getOrNull()?.use { it.readBytes() } ?: return@mapNotNull null
            val plain = if (BackupCrypto.looksEncrypted(bytes)) {
                val opened = auto.passphrase.takeIf { it.isNotEmpty() }?.let {
                    BackupCrypto.decryptBytes(bytes.inputStream(), it.toCharArray())
                }
                if (opened == null) passphraseMiss = true
                opened
            } else {
                bytes
            }
            plain?.let { SyncFile.decode(it.inputStream()) }
        }
        return Remote(files, passphraseMiss, mine)
    }

    /**
     * The file's bytes, encoded straight into them. A whole string first, its
     * bytes and the cipher's buffer on top were what ran out of heap (#1080).
     */
    private fun encodeFile(file: SyncFile, auto: AutoBackupSettings, encrypt: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (encrypt) {
            BackupCrypto.encrypting(
                out = out,
                passphrase = auto.passphrase.toCharArray(),
                salt = Base64.decode(auto.kdfSalt, Base64.NO_WRAP),
            ).buffered().use { SyncFile.encodeTo(it, file) }
        } else {
            SyncFile.encodeTo(out, file)
        }
        return out.toByteArray()
    }

    /**
     * Writes this device's file, then removes its older copies, in that order:
     * a failed write leaves the old file in place. Older copies in another
     * group go too: a device that changed group has left the old one.
     *
     * Most sinks replace a file of the same name, and then the copies left are
     * ones under another name: a folder's `… (1)` rename of a clash, or the
     * name this device had before it was renamed. Drive keeps both files under
     * one name, so there the same name counts too, told apart by id. Nowhere
     * else is an id compared: a sink's write and its listing do not always
     * spell one file's id the same way, and a mismatch would delete the file
     * just written.
     */
    private suspend fun writeOwn(sink: BackupSink, name: String, bytes: ByteArray, me: String): Result<SinkEntry> {
        val mime = if (SyncNaming.isEncrypted(name)) ConfigBackup.ENCRYPTED_MIME_TYPE else ConfigBackup.MIME_TYPE
        val written = sink.write(name, mime) { it.write(bytes) }
        written.onSuccess { entry ->
            sink.list().getOrNull().orEmpty()
                .filter { SyncNaming.installId(it.name) == me }
                .filter { other ->
                    other.name != entry.name || (sink.allowsDuplicateNames && other.id != entry.id)
                }
                .forEach { sink.delete(it) }
        }
        return written
    }

    private suspend fun recordLocations(
        repository: SettingsRepository,
        targets: List<BackupLocation>,
        failed: Map<String, String>,
        nowMs: Long,
    ) {
        for (location in targets) {
            val error = failed[location.id].orEmpty()
            repository.setLocationStatus(location.id) {
                if (error.isEmpty()) it.copy(syncAtMs = nowMs, syncError = "") else it.copy(syncError = error)
            }
        }
    }

    /**
     * Moves this device to the sync group [raw] names (see [SyncNaming]); true
     * when that changed the group. The device then joins it as a new device
     * would: what it agreed on in the old group is forgotten, so the new
     * group's settings win the first pass instead of losing to stamps from
     * people this device no longer syncs with. Under the gate, so a pass that
     * is running finishes in the old group first.
     */
    suspend fun joinGroup(context: Context, repository: SettingsRepository, raw: String): Boolean =
        BackupGate.mutex.withLock {
            repository.setSyncGroup(raw).also { changed -> if (changed) reset(context) }
        }

    /** Forgets what this phone agreed on, so the next pass is a first one again. */
    suspend fun reset(context: Context) = withContext(Dispatchers.IO) {
        File(context.noBackupFilesDir, STATE_FILE).delete()
        File(context.noBackupFilesDir, FILTER_FILE).delete()
    }
}
