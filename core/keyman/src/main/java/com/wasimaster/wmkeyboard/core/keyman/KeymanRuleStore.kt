package com.wasimaster.wmkeyboard.core.keyman

import android.content.Context
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import java.io.File

/**
 * Where a converted layout's rules live on disk, and the only place a
 * [KeyProcessor] is made.
 *
 * One `.kmx` per keyboard id under `filesDir/keyman/rules`. Credential-encrypted
 * storage, so nothing here resolves before first unlock — a converted layout on
 * the lock screen draws its grid and types its key caps, which is the same
 * degradation as a device that never downloaded the rules and is preferable to
 * having no keyboard at all.
 *
 * Parsing happens once per keyboard and the result is immutable, so the parsed
 * keyboard is cached and shared while each [KeyProcessor] handed out keeps its
 * own context. Callers must not share one processor across two input sessions.
 */
class KeymanRuleStore(private val context: Context) {

    /**
     * The keyboards parsed most recently, at most [PARSED_KEPT], least recently
     * used first. Every keyboard switched through used to stay for as long as
     * the store, which is the keyboard service's lifetime, and a parsed rule
     * set is objects for every group, rule and string of a `.kmx` that may be
     * up to [KeymanLimits.MAX_KMX_BYTES]. The one in use is also held by its
     * [KeyProcessor], so this only decides how many others wait for a switch
     * back; going further back costs a file read and a parse, as the first
     * time did.
     */
    private val parsed = object : LinkedHashMap<String, KeymanKeyboard>(PARSED_KEPT * 2, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KeymanKeyboard>?): Boolean =
            size > PARSED_KEPT
    }

    /** `filesDir/keyman/rules`, or null while the device is locked. */
    fun rulesDir(): File? {
        val files = runCatching { context.filesDir }.getOrNull() ?: return null
        return File(File(files, DIR_KEYMAN), DIR_RULES)
    }

    fun ruleFile(keyboardId: String): File? =
        rulesDir()?.let { File(it, "$keyboardId.$EXTENSION") }

    /** True when rules for this keyboard are on disk and readable. */
    fun hasRules(keyboardId: String): Boolean = ruleFile(keyboardId)?.isFile == true

    /**
     * A fresh processor for [binding], or null when the rules are missing or
     * unreadable.
     *
     * Null is an ordinary answer, not an error: it is what a device without the
     * rules returns, and the caller's response is to leave the layout typing its
     * own key caps.
     */
    fun processorFor(binding: KeymanBinding, tablet: Boolean = false): KeyProcessor? {
        val keyboard = keyboard(binding.keyboardId) ?: return null
        return KmxProcessor(
            keyboard,
            platform = if (tablet) KmxProcessor.PLATFORM_TOUCH_TABLET else KmxProcessor.PLATFORM_TOUCH_PHONE,
        )
    }

    /** The parsed keyboard, cached. Null when absent, too large or malformed. */
    fun keyboard(keyboardId: String): KeymanKeyboard? {
        synchronized(parsed) { parsed[keyboardId] }?.let { return it }
        val file = ruleFile(keyboardId)?.takeIf { it.isFile } ?: return null
        if (file.length() > KeymanLimits.MAX_KMX_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val keyboard = when (val result = KmxParser.parse(bytes)) {
            is KeymanResult.Success -> result.value
            is KeymanResult.Failure -> return null
        }
        synchronized(parsed) { parsed[keyboardId] = keyboard }
        return keyboard
    }

    /** Why [keyboard] returned null, for the diagnostics screen. Never the text. */
    fun faultFor(keyboardId: String): KeymanFault? {
        val file = ruleFile(keyboardId) ?: return null
        if (!file.isFile) return null
        if (file.length() > KeymanLimits.MAX_KMX_BYTES) return KeymanFault.TOO_LARGE
        val bytes = runCatching { file.readBytes() }.getOrNull()
            ?: return KeymanFault.TRUNCATED
        return (KmxParser.parse(bytes) as? KeymanResult.Failure)?.fault
    }

    /** Drops the parse cache, for when a download replaces a keyboard's rules. */
    fun invalidate(keyboardId: String) {
        synchronized(parsed) { parsed.remove(keyboardId) }
    }

    /**
     * The upstream version of the rules on disk, or null when none are.
     *
     * Kept in a sidecar file rather than in preferences so it cannot disagree
     * with what is actually there: the two are written and deleted together, and
     * a `.kmx` restored from a backup without its sidecar simply reports no
     * version and gets re-fetched.
     *
     * The `.kmx` header carries a keyboard version of its own, but that is the
     * *author's* number and does not identify the package the file came from,
     * which is what an update check needs to compare.
     */
    fun installedVersion(keyboardId: String): String? {
        val file = versionFile(keyboardId)?.takeIf { it.isFile } ?: return null
        if (file.length() > MAX_VERSION_BYTES) return null
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun writeInstalledVersion(keyboardId: String, version: String) {
        val file = versionFile(keyboardId) ?: return
        file.parentFile?.mkdirs()
        runCatching { file.writeText(version.take(MAX_VERSION_BYTES.toInt())) }
    }

    fun clearInstalledVersion(keyboardId: String) {
        versionFile(keyboardId)?.delete()
    }

    private fun versionFile(keyboardId: String): File? =
        rulesDir()?.let { File(it, "$keyboardId.version") }

    companion object {
        private const val DIR_KEYMAN = "keyman"
        private const val DIR_RULES = "rules"

        /**
         * Keyman's own extension, kept rather than renamed. The bytes are
         * upstream's unchanged, so calling them something else would only make
         * them harder to identify in a bug report.
         */
        const val EXTENSION = "kmx"

        /** A version string is a few characters; anything longer is not one. */
        private const val MAX_VERSION_BYTES = 64L

        /** The keyboard typing now and the one before it, for a switch back and forth. */
        private const val PARSED_KEPT = 2

        private const val LOAD_FACTOR = 0.75f
    }
}
