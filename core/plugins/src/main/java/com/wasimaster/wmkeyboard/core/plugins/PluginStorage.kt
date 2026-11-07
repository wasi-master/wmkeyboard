package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import java.io.File

/**
 * `wm.storage`: a small string-to-string map that belongs to one plugin.
 *
 * Deliberately boring, and safe precisely because of what surrounds it. With no
 * network API anywhere in the sandbox there is nowhere for stored data to go, so
 * this is scratch space rather than a data-collection surface — which is why the
 * `storage` permission is disclosed before install but never prompts at use.
 *
 * The quotas are per-plugin and enforced before every write, so a plugin cannot
 * fill the user's device by looping. A null [file] — direct boot, before first
 * unlock — makes the whole thing an in-memory map that is never persisted, the
 * same contract every other store in the app follows.
 *
 * The interpreter reads and writes the same file itself while a plugin runs
 * (`native/wm-python/src/host`, with these quotas and this format), so this
 * class keeps no copy: a file-backed instance reads the file again on every
 * call. Only a null [file] keeps its map in memory. Every public method is
 * synchronized, for the plugin editor's storage inspector.
 */
class PluginStorage(private val file: File?) {

    private val entries = LinkedHashMap<String, String>()

    companion object {
        const val MAX_KEY_LENGTH = 64
        const val MAX_VALUE_LENGTH = 8 * 1024
        const val MAX_KEYS = 128
        const val MAX_TOTAL_BYTES = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    /** Why a write was refused, or null when it went through. */
    @Synchronized
    fun set(key: String, value: String): String? {
        load()
        if (key.isEmpty()) return "storage keys can't be empty"
        if (key.length > MAX_KEY_LENGTH) return "storage keys can't be longer than $MAX_KEY_LENGTH characters"
        if (value.length > MAX_VALUE_LENGTH) {
            return "that value is larger than the ${MAX_VALUE_LENGTH / 1024} KB limit for one key"
        }
        if (!entries.containsKey(key) && entries.size >= MAX_KEYS) {
            return "this plugin already has the maximum of $MAX_KEYS stored keys"
        }
        val existing = entries[key]
        // A new key costs its name as well as its value; overwriting one only
        // costs the difference between the old value and the new.
        val delta = if (existing == null) key.length + value.length else value.length - existing.length
        if (usedBytes() + delta > MAX_TOTAL_BYTES) {
            return "this plugin has used all ${MAX_TOTAL_BYTES / 1024} KB of its storage"
        }
        entries[key] = value
        save()
        return null
    }

    @Synchronized
    fun get(key: String): String? {
        load()
        return entries[key]
    }

    @Synchronized
    fun remove(key: String) {
        load()
        if (entries.remove(key) != null) save()
    }

    @Synchronized
    fun keys(): List<String> {
        load()
        return entries.keys.toList()
    }

    @Synchronized
    fun clear() {
        load()
        if (entries.isEmpty()) return
        entries.clear()
        save()
    }

    @Synchronized
    fun usedBytes(): Int {
        load()
        return entries.entries.sumOf { it.key.length + it.value.length }
    }

    private fun load() {
        val source = file ?: return
        entries.clear()
        if (!source.isFile) return
        runCatching {
            val decoded = json.decodeFromString<Map<String, String>>(source.readText())
            for ((key, value) in decoded) {
                if (entries.size >= MAX_KEYS) break
                if (key.length <= MAX_KEY_LENGTH && value.length <= MAX_VALUE_LENGTH) {
                    entries[key] = value
                }
            }
        }
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            part.writeText(json.encodeToString(entries))
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
        }
    }
}
