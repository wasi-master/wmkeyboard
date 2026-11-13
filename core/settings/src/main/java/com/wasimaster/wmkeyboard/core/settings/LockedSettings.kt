package com.wasimaster.wmkeyboard.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The keyboard's settings as they can be read *before the user unlocks the
 * device* — a mirror of the real DataStore kept in device-protected storage,
 * which is the only storage that exists during direct boot.
 *
 * Why a mirror rather than moving the settings there outright: device-protected
 * storage is not covered by the user's credential. Everything in it is
 * readable by anyone who can read the data partition of a locked phone, so it
 * gets the keyboard's *configuration* (layout, theme, sizing, sounds) and
 * nothing that belongs to the user. [SettingsBackup.SECRET_KEYS] — the API
 * keys and tokens — is dropped on the way in, and the personal stores
 * (learned words, clipboard, snippets, emoji history) are never mirrored at
 * all: they stay behind, and the keyboard runs without them while locked.
 *
 * Direction of travel is one-way. Unlocked, [write] republishes the real
 * settings here on every change. Locked, [edit] lets the keyboard's own
 * toggles (autocorrect, one-handed, theme) work against the mirror so the UI
 * still responds — those edits are deliberately transient, since the first
 * [write] after unlock overwrites them with the credential-encrypted truth.
 *
 * Stored as a single typed-JSON blob under one SharedPreferences key rather
 * than key-per-preference: DataStore preference keys carry a type that
 * SharedPreferences would lose (an Int read back as a Long is an unreadable
 * setting), and [SettingsBackup] already encodes exactly that. SharedPreferences
 * rather than a second DataStore because the locked keyboard has to draw its
 * first frame off this, and a synchronous read beats a coroutine round trip.
 */
class LockedSettings(context: Context) {

    private val prefs: SharedPreferences =
        DirectBoot.deviceContext(context).getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    /** The snapshot [write] last mirrored, so the same one is not encoded again. */
    @Volatile
    private var lastWritten: Preferences? = null

    /**
     * The mirrored preferences, or an empty set when nothing has been mirrored
     * yet (a first run that has never been unlocked, or an upgrade whose first
     * boot after install lands here). Empty means every setting falls back to
     * its default, which is a plain working keyboard.
     */
    fun read(): Preferences {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return mutablePreferencesOf()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return mutablePreferencesOf()
        return decode(obj)
    }

    /**
     * Republishes [source] (the real, credential-encrypted settings) into the
     * mirror, minus the credentials. Cheap to call on every settings emission:
     * an unchanged snapshot is not written back, so this costs an encode and a
     * string compare rather than disk.
     */
    fun write(source: Preferences) {
        // Every collector of the settings flow calls this with the same
        // snapshot, and DataStore re-emits unchanged ones after a no-op
        // write: the whole store encoded to JSON each time, while typing on a
        // G99 a fifteenth of the keyboard's background work. Preferences
        // compare as maps, far cheaper than the encode.
        if (source == lastWritten) return
        lastWritten = source
        val encoded = json.encodeToString(
            JsonObject.serializer(),
            SettingsBackup.encodeSettings(source, includeSecrets = false),
        )
        if (prefs.getString(KEY_SNAPSHOT, null) == encoded) return
        prefs.edit().putString(KEY_SNAPSHOT, encoded).apply()
    }

    /**
     * Applies [transform] to the mirror in place — the locked-mode stand-in for
     * `DataStore.edit`. Not atomic against a concurrent [write]; it does not
     * need to be, since a locked device has exactly one writer (the keyboard)
     * and the unlock that introduces the other one discards this copy anyway.
     */
    suspend fun edit(transform: suspend (MutablePreferences) -> Unit) {
        lastWritten = null
        val current = read().toMutablePreferences()
        transform(current)
        prefs.edit().putString(
            KEY_SNAPSHOT,
            json.encodeToString(
                JsonObject.serializer(),
                // Locked edits go through the same filter as [write]: a
                // credential typed into a tool panel on the lock screen must
                // not be the thing that lands it in unprotected storage.
                SettingsBackup.encodeSettings(current, includeSecrets = false),
            ),
        ).apply()
    }

    /**
     * Drops the mirror, so a locked boot falls back to defaults. Only for a
     * deliberate wipe of every setting: the ordinary path back to an empty
     * mirror is a [write] of empty settings after unlock.
     */
    fun clear() {
        lastWritten = null
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    /**
     * The mirror, re-emitted whenever it changes. Stands in for the DataStore
     * flow while locked so the keyboard's own toggles still move the UI.
     */
    fun snapshots(): Flow<Preferences> = callbackFlow {
        // Signal only: the change callback lands on the main thread, and the
        // decode belongs on the same dispatcher DataStore would have used.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.map { read() }.flowOn(Dispatchers.IO)

    private fun decode(obj: JsonObject): Preferences {
        val (entries, _) = SettingsBackup.decodeSettings(obj)
        val out = mutablePreferencesOf()
        entries.forEach { out.put(it) }
        return out
    }

    companion object {
        /** File name under the device-protected `shared_prefs` directory. */
        const val FILE_NAME = "locked_settings"

        private const val KEY_SNAPSHOT = "snapshot"
    }
}
