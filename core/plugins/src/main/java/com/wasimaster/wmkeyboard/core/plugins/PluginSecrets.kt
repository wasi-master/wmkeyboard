package com.wasimaster.wmkeyboard.core.plugins

import kotlinx.serialization.json.Json
import java.io.File

/**
 * `wm.secrets`: the API keys and tokens one plugin keeps, apart from its
 * storage. Kept under `filesDir/plugin_secrets/<id>.json`, outside the plugin's
 * own folder, so the storage inspector never shows them, an export of the
 * plugin never carries them, and the backup rules leave the folder out.
 * Deleted with the plugin ([forget]).
 */
class PluginSecrets(private val dir: File?, private val pluginId: String) {

    private val json = Json { ignoreUnknownKeys = true }

    private fun file(): File? {
        val base = dir ?: return null
        if (!SAFE_ID.matches(pluginId)) return null
        return File(base, "$pluginId.json")
    }

    @Synchronized
    fun get(name: String): String? = read()[name]

    /** Why it was refused, or null. */
    @Synchronized
    fun set(name: String, value: String): String? {
        if (name.isEmpty() || name.length > MAX_NAME) return "secret names are 1 to $MAX_NAME characters"
        if (value.length > MAX_VALUE) return "a secret can't be longer than $MAX_VALUE characters"
        val all = read().toMutableMap()
        if (name !in all && all.size >= MAX_SECRETS) return "this plugin already keeps $MAX_SECRETS secrets"
        all[name] = value
        write(all)
        return null
    }

    @Synchronized
    fun remove(name: String) {
        val all = read().toMutableMap()
        if (all.remove(name) != null) write(all)
    }

    @Synchronized
    fun forget() {
        file()?.delete()
    }

    private fun read(): Map<String, String> {
        val f = file() ?: return emptyMap()
        if (!f.isFile) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(f.readText()) }.getOrDefault(emptyMap())
    }

    private fun write(all: Map<String, String>) {
        val f = file() ?: return
        runCatching {
            f.parentFile?.mkdirs()
            val part = File(f.parentFile, f.name + ".part")
            part.writeText(json.encodeToString(all))
            if (!part.renameTo(f)) {
                f.delete()
                part.renameTo(f)
            }
        }
    }

    companion object {
        const val DIR_NAME = "plugin_secrets"
        const val MAX_SECRETS = 32
        const val MAX_NAME = 64
        const val MAX_VALUE = 4096
        private val SAFE_ID = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

        fun of(context: android.content.Context, pluginId: String): PluginSecrets =
            PluginSecrets(File(context.applicationContext.filesDir, DIR_NAME), pluginId)
    }
}
