package com.wasimaster.wmkeyboard.core.input.imported

import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The jquery.ime methods that ship with the app, read from its assets
 * (`input_methods/jquery/<id>.js`, or `.xml` for the two that only Indic
 * Keyboard's copy still has). A built-in layout names one with an
 * [InputMethodBinding][com.wasimaster.wmkeyboard.core.layout.InputMethodBinding]
 * whose id is `jq:` and the method's own id.
 *
 * The files are read the first time a layout asks, or ahead of that when the
 * keyboard knows a layout is enabled ([preload]); a few hundred rules compile
 * in milliseconds and stay compiled for the life of the process.
 */
object BundledInputMethods {

    const val PREFIX = "jq:"

    /** Opens an asset by path; set once the app's context exists. Until then nothing loads. */
    @Volatile
    var assets: ((String) -> InputStream?)? = null

    private val loaded = ConcurrentHashMap<String, RegexRules>()

    /** Ids that were asked for and are not among the assets, so a miss is not retried on every key. */
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun isBundled(id: String): Boolean = id.startsWith(PREFIX)

    /** The rules for [id], reading them now if they were not read yet. Null for an id the app does not ship. */
    fun rules(id: String): RegexRules? {
        loaded[id]?.let { return it }
        if (id in missing) return null
        val open = assets ?: return null
        val name = id.removePrefix(PREFIX)
        if (name.isEmpty() || name.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) return null
        val text = EXTENSIONS.firstNotNullOfOrNull { ext ->
            runCatching { open("$DIR/$name.$ext")?.use { it.readBytes().decodeToString() } }.getOrNull()
        }
        val rules = text?.let(JqueryImeFiles::parse)
        if (rules == null) {
            missing += id
            return null
        }
        return loaded.putIfAbsent(id, rules) ?: rules
    }

    /** Reads [ids] now. Blocking: call it off the main thread. */
    fun preload(ids: Collection<String>) {
        for (id in ids) if (isBundled(id)) rules(id)
    }

    private const val DIR = "input_methods/jquery"
    private val EXTENSIONS = listOf("js", "xml")
}
