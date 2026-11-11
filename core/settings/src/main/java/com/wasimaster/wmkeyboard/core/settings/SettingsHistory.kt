package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import com.wasimaster.wmkeyboard.core.util.SnapshotFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * Undo for settings, and restore points (#1242).
 *
 * Two halves over one idea, that a setting is a key and a value:
 *
 * - **The change history.** Every write to the settings store is diffed
 *   against what it replaced, and the keys it changed are kept as one
 *   [Batch], with the value before and after. Undoing a batch writes the
 *   "before" values back. That covers a change made anywhere: a settings
 *   screen, the keyboard's own toggles, an import, a reset of everything.
 * - **Restore points.** A named copy of every [tracked] setting, kept as a
 *   file. Restoring one writes back each value that differs and removes each
 *   key the point did not have, which is how a setting that was on its
 *   default then goes back to it.
 *
 * Both walk the raw preference map, for the reason [SettingsBackup] does: a
 * setting added next week is covered the day it lands, with nobody having to
 * remember to list it.
 *
 * What is not a setting stays out of both, through [tracked]: the store also
 * holds counters the keyboard bumps to signal itself, recents, one-time
 * progress, credentials, and the backup and sync machinery, none of which an
 * undo should touch. Kept on this phone only, under `filesDir`; nothing here is
 * backed up or synced, because an undo is about this phone, today.
 */
class SettingsHistory internal constructor(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** What made a batch. */
    enum class Kind(val id: String) {
        /** An ordinary change. */
        EDIT("edit"),

        /** Writing back what earlier batches replaced; see [Batch.undid]. */
        UNDO("undo"),

        /** Going back to a restore point; [Batch.label] names it. */
        RESTORE("restore"),
        ;

        companion object {
            fun of(id: String?): Kind = entries.firstOrNull { it.id == id } ?: EDIT
        }
    }

    /** One setting's value before and after a write. Null is "not stored", the default. */
    data class Change(
        val key: String,
        val before: SettingsBackup.Entry?,
        val after: SettingsBackup.Entry?,
    )

    /**
     * The settings one write changed.
     *
     * [undone] is set once an undo has put the "before" values back, so the
     * next "Undo the last change" goes one further back instead of undoing
     * the same thing twice. An [Kind.UNDO] batch lists in [undid] the batches
     * it reverted: undoing the undo hands them back.
     */
    data class Batch(
        val id: Long,
        val at: Long,
        val kind: Kind,
        val changes: List<Change>,
        val label: String? = null,
        val undone: Boolean = false,
        val undid: List<Long> = emptyList(),
    ) {
        val keys: Set<String> get() = changes.mapTo(LinkedHashSet()) { it.key }
    }

    enum class PointKind(val id: String) {
        /** Saved by hand. */
        MANUAL("manual"),

        /** Saved by the app just before a restore replaced it; [RestorePoint.label] names the one restored. */
        BEFORE_RESTORE("before_restore"),
        ;

        companion object {
            fun of(id: String?): PointKind = entries.firstOrNull { it.id == id } ?: MANUAL
        }
    }

    /** A restore point's description; the settings themselves are in its own file. */
    data class RestorePoint(
        val id: String,
        val name: String?,
        val at: Long,
        val kind: PointKind,
        /** How many settings it holds. */
        val count: Int,
        val appVersionName: String,
        /** For [PointKind.BEFORE_RESTORE], the name of the point that was restored. */
        val label: String? = null,
    )

    /** What restoring a point would do, worked out before it is done. */
    data class Plan(val set: List<SettingsBackup.Entry>, val remove: Set<String>) {
        val size: Int get() = set.size + remove.size
        val isEmpty: Boolean get() = set.isEmpty() && remove.isEmpty()
    }

    private val mutex = Mutex()
    private val historyFile = File(dir, HISTORY_FILE)
    private val pointsFile = File(dir, POINTS_FILE)
    private val pointsDir = File(dir, POINTS_DIR)

    private val _batches = MutableStateFlow<List<Batch>>(emptyList())
    private val _points = MutableStateFlow<List<RestorePoint>>(emptyList())
    private var loaded = false
    private var nextId = 1L

    /** The history, newest first. Empty until [load] has run. */
    val batches: StateFlow<List<Batch>> = _batches.asStateFlow()

    /** The restore points, newest first. Empty until [load] has run. */
    val points: StateFlow<List<RestorePoint>> = _points.asStateFlow()

    /** Reads both lists from disk, once. Every other call does this first. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            _batches.value = runCatching { decodeBatches(historyFile.readText()) }.getOrDefault(emptyList())
            _points.value = runCatching { decodePoints(pointsFile.readText()) }.getOrDefault(emptyList())
        }
        nextId = (_batches.value.maxOfOrNull { it.id } ?: 0L) + 1
        loaded = true
    }

    // ---- the change history ----

    /**
     * Adds what one write changed. An ordinary change to the same settings as
     * the batch before it, within [COALESCE_MS], joins that batch: a slider
     * dragged across its range, or a name typed out, is one step back rather
     * than forty. Returns the batch the changes landed in, or null when there
     * was nothing left to keep.
     */
    suspend fun record(
        kind: Kind,
        changes: List<Change>,
        label: String? = null,
        undid: List<Long> = emptyList(),
    ): Batch? = mutex.withLock {
        ensureLoaded()
        val kept = changes.filter { tracked(it.key) && it.small() }
        if (kept.isEmpty()) return@withLock null
        val now = clock()
        val list = _batches.value
        val last = list.firstOrNull()
        val joins = kind == Kind.EDIT && last != null && last.kind == Kind.EDIT && !last.undone &&
            now - last.at in 0..COALESCE_MS && last.keys == kept.mapTo(HashSet()) { it.key }
        // A join that cancels out (a slider dragged back to where it started)
        // takes the batch away altogether.
        val (batch, rest) = if (joins && last != null) {
            coalesce(last, kept, now) to list.drop(1)
        } else {
            Batch(nextId++, now, kind, kept, label, undid = undid) to list
        }
        // Undoing a batch marks it; undoing an undo hands its batches back.
        val marked = if (kind == Kind.UNDO && undid.isNotEmpty()) {
            val ids = undid.toHashSet()
            rest.map { if (it.id in ids) it.copy(undone = true) else it }
        } else {
            rest
        }
        _batches.value = prune(listOfNotNull(batch) + marked, now)
        save()
        batch
    }

    /** Marks [ids] as no longer undone, after the undo that reverted them was itself undone. */
    suspend fun restoreUndone(ids: Collection<Long>) = mutex.withLock {
        ensureLoaded()
        val set = ids.toHashSet()
        _batches.value = _batches.value.map { if (it.id in set) it.copy(undone = false) else it }
        save()
    }

    /** The newest batch an "Undo the last change" would take back, or null. */
    fun lastUndoable(): Batch? = _batches.value.firstOrNull { it.undoable }

    /** The batches an undo of the last [windowMs] would take back, newest first. */
    fun undoableWithin(windowMs: Long): List<Batch> {
        val since = clock() - windowMs
        return _batches.value.filter { it.undoable && it.at >= since }
    }

    /** Forgets the whole history; restore points stay. */
    suspend fun clearHistory() = mutex.withLock {
        ensureLoaded()
        _batches.value = emptyList()
        withContext(Dispatchers.IO) { historyFile.delete() }
    }

    // ---- restore points ----

    /** Saves [prefs] as a new restore point and returns it. */
    suspend fun createPoint(
        prefs: Preferences,
        name: String?,
        appVersionName: String,
        kind: PointKind = PointKind.MANUAL,
        label: String? = null,
    ): RestorePoint = mutex.withLock {
        ensureLoaded()
        val settings = snapshotOf(prefs)
        val point = RestorePoint(
            id = UUID.randomUUID().toString(),
            name = name?.trim()?.takeIf { it.isNotEmpty() },
            at = clock(),
            kind = kind,
            count = settings.size,
            appVersionName = appVersionName,
            label = label,
        )
        withContext(Dispatchers.IO) {
            SnapshotFile.writeAtomically(pointFile(point.id), settings.toString())
        }
        val all = listOf(point) + _points.value
        // Points saved by hand are the user's to delete; the automatic ones
        // are a safety net, and only the last few are worth keeping.
        val automatic = all.filter { it.kind != PointKind.MANUAL }
        val dropped = automatic.drop(MAX_AUTOMATIC_POINTS).mapTo(HashSet()) { it.id }
        _points.value = all.filter { it.id !in dropped }
        withContext(Dispatchers.IO) { dropped.forEach { pointFile(it).delete() } }
        savePoints()
        point
    }

    suspend fun renamePoint(id: String, name: String?) = mutex.withLock {
        ensureLoaded()
        _points.value = _points.value.map {
            if (it.id == id) it.copy(name = name?.trim()?.takeIf { n -> n.isNotEmpty() }) else it
        }
        savePoints()
    }

    suspend fun deletePoint(id: String) = mutex.withLock {
        ensureLoaded()
        _points.value = _points.value.filter { it.id != id }
        withContext(Dispatchers.IO) { pointFile(id).delete() }
        savePoints()
    }

    /** The settings a point holds, or null when its file is gone or unreadable. */
    suspend fun pointEntries(id: String): List<SettingsBackup.Entry>? = withContext(Dispatchers.IO) {
        val text = runCatching { pointFile(id).readText() }.getOrNull() ?: return@withContext null
        val obj = runCatching { parser.parseToJsonElement(text).jsonObject }.getOrNull() ?: return@withContext null
        SettingsBackup.decodeSettings(obj).first
    }

    private fun pointFile(id: String) = File(pointsDir, "$id.json")

    // ---- storage ----

    private suspend fun save() {
        val text = encodeBatches(_batches.value)
        withContext(Dispatchers.IO) { runCatching { SnapshotFile.writeAtomically(historyFile, text) } }
    }

    private suspend fun savePoints() {
        val text = encodePoints(_points.value)
        withContext(Dispatchers.IO) { runCatching { SnapshotFile.writeAtomically(pointsFile, text) } }
    }

    private fun prune(list: List<Batch>, now: Long): List<Batch> =
        list.asSequence().filter { now - it.at <= MAX_AGE_MS }.take(MAX_BATCHES).toList()

    companion object {
        private const val HISTORY_FILE = "history.json"
        private const val POINTS_FILE = "points.json"
        private const val POINTS_DIR = "points"

        /** Changes to the same settings this close together are one step. */
        const val COALESCE_MS = 2_000L

        /** The history keeps a month, and at most this many steps of it. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_BATCHES = 400

        /** "Before restoring" points kept; the oldest goes first. */
        const val MAX_AUTOMATIC_POINTS = 5

        /**
         * A value longer than this is left out of the history. The settings
         * store holds whole layouts and themes as JSON strings, and the
         * layout editor rewrites one on every key it changes: kept before and
         * after, a month of that would run to megabytes. Those editors have
         * their own undo; a restore point still holds them.
         */
        const val MAX_VALUE_CHARS = 20_000

        /** Whether the change history is kept. On by default; stored like any setting. */
        const val ENABLED_KEY = "settings_history_enabled"

        /**
         * Keys that describe the store's own state rather than a choice:
         * signals the keyboard bumps to reload something, recents, the tab a
         * panel was left on, one-time progress, and where a tool last was.
         * Undoing one would do nothing useful, and restoring one could send
         * the keyboard back through its first run.
         */
        private val STATE_KEYS = setOf(
            ENABLED_KEY,
            "active_layout_id", "input_mode", "incognito", "data_saver_manual", "power_saving_manual",
            "recent_layout_ids", "globe_recent_order", "symbol_recents", "unit_convert_last",
            "photo_rotation_state", "auto_theme_shuffled_at", "auto_theme_shuffled_at_elapsed",
            "auto_theme_shuffle_light_id", "auto_theme_shuffle_dark_id", "advanced_open",
            "weather_lat", "weather_lon", "weather_place",
            "lexicon_version", "corrections_version", "custom_dict_version", "emoji_usage_version",
            "emoji_keyword_pack_version", "gesture_swipe_style_version", "mode_seed_version",
            "selection_macros_list_version", "stats_version", "emoji_mix_index_version",
            "gesture_checkup_version", "kaomoji_version", "notes_version", "todo_version",
            "onboarding_done", "onboarding_persona_depth", "onboarding_persona_languages",
            "onboarding_persona_privacy", "auto_pair_romanized_done", "toolbox_hint_dismissed",
            "launcher_recents", "share_sheet_recents", "gif_recent", "emoji_tone_override_last_used",
            "gallery_last_album", "ha_cache_states", "ha_last_tab", "kde_last_tab", "send_last_mode",
            "spd_last_result", "tt_history",
        )

        /**
         * Name prefixes left out: backup and sync run on their own record of
         * what ran when, and the app lock is not something a restore point
         * saved before it was set should be able to switch off.
         */
        private val STATE_PREFIXES = listOf("sync_", "auto_backup_", "frequent_backup_", "app_lock_")

        /** Whether the setting named [key] is kept in the history and in restore points. */
        fun tracked(key: String): Boolean =
            key !in SettingsBackup.SECRET_KEYS &&
                key !in SettingsBackup.TRANSIENT_KEYS &&
                // The theme library: a restore point must never delete a
                // theme made after it. Which theme is on is a setting, and stays.
                key !in SettingsBackup.THEME_KEYS &&
                key !in STATE_KEYS &&
                STATE_PREFIXES.none { key.startsWith(it) }

        @Volatile private var instance: SettingsHistory? = null

        /** The one history of this install; the settings app and the keyboard share a process. */
        fun get(context: Context): SettingsHistory = instance ?: synchronized(this) {
            instance ?: SettingsHistory(File(context.applicationContext.filesDir, "settings_history"))
                .also { instance = it }
        }

        private val parser = Json { ignoreUnknownKeys = true }

        /**
         * What changed between two preference maps, keyed by name. Only
         * [tracked] keys; the order is the keys' own, so a batch reads the
         * same way every time it is drawn.
         */
        fun diff(before: Map<Preferences.Key<*>, Any>, after: Map<Preferences.Key<*>, Any>): List<Change> {
            val old = before.entries.associate { it.key.name to it.value }
            val new = after.entries.associate { it.key.name to it.value }
            val names = (old.keys + new.keys).filter(::tracked).sorted()
            val out = ArrayList<Change>()
            for (name in names) {
                val a = old[name]
                val b = new[name]
                if (a == b) continue
                out += Change(name, a?.let { entryOf(name, it) }, b?.let { entryOf(name, it) })
            }
            return out
        }

        /** What restoring [point] over [current] would write and remove. */
        fun plan(point: List<SettingsBackup.Entry>, current: Map<Preferences.Key<*>, Any>): Plan {
            val now = current.entries.associate { it.key.name to it.value }
            val wanted = point.filter { tracked(it.name) }
            val set = wanted.filter { now[it.name] != it.value }
            val names = wanted.mapTo(HashSet()) { it.name }
            val remove = now.keys.filterTo(LinkedHashSet()) { tracked(it) && it !in names }
            return Plan(set, remove)
        }

        /**
         * What undoing [batches] writes: for each key, the value it had
         * before the oldest of them changed it. Null removes the key.
         */
        fun revertOf(batches: List<Batch>): Map<String, SettingsBackup.Entry?> {
            val out = LinkedHashMap<String, SettingsBackup.Entry?>()
            for (batch in batches.sortedBy { it.at }) {
                for (change in batch.changes) if (change.key !in out) out[change.key] = change.before
            }
            return out
        }

        private fun entryOf(name: String, value: Any): SettingsBackup.Entry? {
            val type = when (value) {
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Double -> "double"
                is String -> "string"
                is Set<*> -> if (value.all { it is String }) "stringSet" else return null
                else -> return null
            }
            return SettingsBackup.Entry(name, type, value)
        }

        /** Joins [kept] onto [last]; null when every setting ended up back where it started. */
        internal fun coalesce(last: Batch, kept: List<Change>, now: Long): Batch? {
            val after = kept.associateBy { it.key }
            val joined = last.changes.mapNotNull { old ->
                val newer = after[old.key] ?: return@mapNotNull old
                if (old.before == newer.after) null else Change(old.key, old.before, newer.after)
            }
            return if (joined.isEmpty()) null else last.copy(at = now, changes = joined)
        }

        private fun Change.small(): Boolean = before.small() && after.small()

        private fun SettingsBackup.Entry?.small(): Boolean {
            val value = this?.value ?: return true
            return when (value) {
                is String -> value.length <= MAX_VALUE_CHARS
                is Set<*> -> value.sumOf { (it as? String)?.length ?: 0 } <= MAX_VALUE_CHARS
                else -> true
            }
        }

        private fun snapshotOf(prefs: Preferences): JsonObject = buildJsonObject {
            for ((key, value) in prefs.asMap().entries.sortedBy { it.key.name }) {
                if (!tracked(key.name)) continue
                put(key.name, SettingsBackup.encodeValue(value) ?: continue)
            }
        }

        // ---- JSON ----

        private fun encodeEntry(entry: SettingsBackup.Entry?): JsonObject? =
            entry?.let { SettingsBackup.encodeValue(it.value) }

        internal fun encodeBatches(list: List<Batch>): String = buildJsonObject {
            put("version", 1)
            put(
                "batches",
                buildJsonArray {
                    for (batch in list) {
                        add(
                            buildJsonObject {
                                put("id", batch.id)
                                put("at", batch.at)
                                put("kind", batch.kind.id)
                                batch.label?.let { put("label", it) }
                                if (batch.undone) put("undone", true)
                                if (batch.undid.isNotEmpty()) {
                                    put("undid", buildJsonArray { batch.undid.forEach { add(JsonPrimitive(it)) } })
                                }
                                put(
                                    "changes",
                                    buildJsonArray {
                                        for (change in batch.changes) {
                                            add(
                                                buildJsonObject {
                                                    put("key", change.key)
                                                    encodeEntry(change.before)?.let { put("before", it) }
                                                    encodeEntry(change.after)?.let { put("after", it) }
                                                },
                                            )
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()

        internal fun decodeBatches(text: String): List<Batch> {
            val root = parser.parseToJsonElement(text).jsonObject
            val list = root["batches"] as? JsonArray ?: return emptyList()
            return list.mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    val changes = (obj["changes"] as? JsonArray).orEmpty().mapNotNull { c ->
                        val co = c.jsonObject
                        val key = (co["key"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                        Change(
                            key,
                            (co["before"] as? JsonObject)?.let { SettingsBackup.decodeEntry(key, it) },
                            (co["after"] as? JsonObject)?.let { SettingsBackup.decodeEntry(key, it) },
                        )
                    }
                    Batch(
                        id = (obj["id"] as JsonPrimitive).longOrNull ?: return@runCatching null,
                        at = (obj["at"] as JsonPrimitive).longOrNull ?: return@runCatching null,
                        kind = Kind.of((obj["kind"] as? JsonPrimitive)?.contentOrNull),
                        changes = changes,
                        label = (obj["label"] as? JsonPrimitive)?.contentOrNull,
                        undone = (obj["undone"] as? JsonPrimitive)?.booleanOrNull == true,
                        undid = (obj["undid"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull },
                    ).takeIf { it.changes.isNotEmpty() }
                }.getOrNull()
            }
        }

        internal fun encodePoints(list: List<RestorePoint>): String = buildJsonObject {
            put("version", 1)
            put(
                "points",
                buildJsonArray {
                    for (p in list) {
                        add(
                            buildJsonObject {
                                put("id", p.id)
                                p.name?.let { put("name", it) }
                                put("at", p.at)
                                put("kind", p.kind.id)
                                put("count", p.count)
                                put("appVersionName", p.appVersionName)
                                p.label?.let { put("label", it) }
                            },
                        )
                    }
                },
            )
        }.toString()

        internal fun decodePoints(text: String): List<RestorePoint> {
            val root = parser.parseToJsonElement(text).jsonObject
            return (root["points"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching {
                    val o = element.jsonObject
                    RestorePoint(
                        id = (o["id"] as JsonPrimitive).content,
                        name = (o["name"] as? JsonPrimitive)?.contentOrNull,
                        at = (o["at"] as JsonPrimitive).longOrNull ?: 0L,
                        kind = PointKind.of((o["kind"] as? JsonPrimitive)?.contentOrNull),
                        count = (o["count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                        appVersionName = (o["appVersionName"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        label = (o["label"] as? JsonPrimitive)?.contentOrNull,
                    )
                }.getOrNull()
            }
        }
    }
}

/** A batch an undo can still take back: not undone already, and not itself an undo. */
val SettingsHistory.Batch.undoable: Boolean get() = !undone && kind != SettingsHistory.Kind.UNDO
