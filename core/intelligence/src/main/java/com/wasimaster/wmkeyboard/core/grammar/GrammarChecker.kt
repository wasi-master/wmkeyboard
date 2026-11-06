package com.wasimaster.wmkeyboard.core.grammar

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.FeatureModules.awaitInstalled
import com.wasimaster.wmkeyboard.core.modules.ModuleState
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.wasimaster.wmkeyboard.common.R as CommonR

/** One way to resolve a [GrammarLint]; mirrors Harper's Suggestion enum. */
@Serializable
data class GrammarFix(
    /** "replace", "remove" or "insertAfter". */
    val kind: String,
    val text: String? = null,
) {
    /**
     * The text of the fix chip. It is null for a fix that carries no text of
     * its own, and [labelRes] gives the label then.
     */
    val labelText: String?
        get() = when (kind) {
            "remove" -> null
            "insertAfter" -> "+ ${text.orEmpty()}"
            else -> text.orEmpty()
        }

    /**
     * The label of the fix chip when [labelText] is null. The UI layer
     * resolves it, so the chip reads in the language of the user.
     */
    @get:StringRes
    val labelRes: Int?
        get() = if (kind == "remove") CommonR.string.common_delete else null
}

/**
 * One fix expressed as the smallest splice that applies it: replace
 * `[start, end)` of the checked text with [text].
 *
 * The point of the splice is what it does *not* touch. Applying a fix by
 * rewriting the whole field works, but it also throws away every span the
 * editor was carrying — bold, colour, a checklist item's own formatting in
 * Google Keep — and re-commits text the user never asked to change. The
 * caller splices the span alone, so the rest of the note is never rewritten
 * and keeps whatever it was wearing.
 *
 * Offsets are UTF-16 into the *original* checked text, so a list of edits
 * stays valid as long as it is applied back-to-front (see [editsAll]).
 */
data class GrammarEdit(val start: Int, val end: Int, val text: String)

/**
 * One grammar/style issue. [start]/[end] are UTF-16 indices into the checked text.
 *
 * [title] and [rule] come from LanguageTool only (its `shortMessage` and its
 * rule id); Harper's lints leave them blank.
 */
@Serializable
data class GrammarLint(
    val start: Int,
    val end: Int,
    val original: String = "",
    val kind: String = "",
    val message: String = "",
    val priority: Int = 0,
    val suggestions: List<GrammarFix> = emptyList(),
    val title: String = "",
    val rule: String = "",
)

/**
 * Offline grammar checking via the bundled Harper engine.
 *
 * All native calls run on one dedicated thread: Harper's linter cache is
 * thread-local on the Rust side (its `LintGroup` is not `Send`), so fanning
 * out over a pool would rebuild the ~100ms rule set per thread for nothing.
 */
object GrammarChecker {
    /** Loads the library on first read, so read it only on the way to a lint. */
    val available: Boolean get() = HarperNative.available

    /**
     * Whether this build ships the library, answered without loading it.
     *
     * [available] maps and relocates a 5 MB native library to say yes, and the
     * keyboard asks at every start just to decide how the grammar tool draws
     * itself; most sessions never lint anything. The class loader can say
     * whether the library is packaged without opening it. Off Android (unit
     * tests) there is no such loader and the answer is [available]'s. On
     * Play the library is the on-demand `:feature:grammar` split, so the
     * build has it whether or not Play has fetched it yet.
     */
    val bundled: Boolean by lazy {
        if (FeatureModules.grammar.onDemand) return@lazy true
        val loader = GrammarChecker::class.java.classLoader
        if (loader is dalvik.system.BaseDexClassLoader) {
            loader.findLibrary(HarperNative.LIBRARY) != null
        } else {
            available
        }
    }

    /** Where the library's Play module stands. Always installed outside Play. */
    val moduleState: StateFlow<ModuleState> get() = FeatureModules.grammar.state

    /** Whether the library is on the device, without loading it. */
    val moduleReady: Boolean get() = FeatureModules.grammar.installed

    /** Asks Play for the library's module. A no-op where it is in the base. */
    fun requestModule() = FeatureModules.grammar.requestInstall()

    /** Asks Play for the module and waits for it. False when the fetch failed. */
    suspend fun awaitModule(): Boolean = FeatureModules.grammar.awaitInstalled()

    private val linter = Executors.newSingleThreadExecutor { r -> Thread(r, "harper-lint") }
    private val dispatcher = linter.asCoroutineDispatcher()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Whether a rule set has actually been built, so [release] can tell
     * "nothing to free" from "not loaded yet" **without reading [available]**
     * — which would map and relocate the 5 MB library just to discover there
     * was nothing to give back.
     */
    @Volatile
    private var built = false

    /** Builds the native linter ahead of the first check; cheap if already built. */
    suspend fun warmUp(dialectOrdinal: Int) {
        // The spell checker's warm-up is the first sign it is in use: on Play
        // that is when the library is worth fetching.
        if (!moduleReady) {
            requestModule()
            return
        }
        if (!available) return
        withContext(dispatcher) { runCatching { HarperNative.nativeWarmUp(dialectOrdinal) } }
        built = true
    }

    /**
     * Frees the rule sets Harper has built, if it has built any.
     *
     * Harper caches a `LintGroup` per dialect for the life of the process, and
     * until this existed there was no way to hand that back — on a keyboard
     * that lints in the same process it draws in, which is what issue #476 is
     * about. Rebuilt on the next lint at the usual ~100 ms.
     *
     * Posted to the lint thread rather than run here, because the native cache
     * is thread-local: it has to be freed by the thread that filled it. That
     * also serialises it behind any lint already running instead of racing one.
     */
    /** Whether a rule set is built; for the Resources screen (#1163). Any thread. */
    val isLoaded: Boolean get() = built

    fun release() {
        if (!built) return
        built = false
        linter.execute { runCatching { HarperNative.nativeRelease() } }
    }

    /** Lints [text]; empty result when the native library is missing or errors. */
    suspend fun check(text: String, dialectOrdinal: Int): List<GrammarLint> {
        if (!available || text.isBlank()) return emptyList()
        built = true
        return withContext(dispatcher) {
            runCatching {
                val raw = HarperNative.nativeLint(text, dialectOrdinal) ?: return@runCatching emptyList()
                json.decodeFromString<List<GrammarLint>>(raw)
                    .filter { it.start in 0..it.end && it.end <= text.length }
                    // Harper can emit the same fix twice for one span (e.g.
                    // "they're" and "they're "), which renders as identical
                    // chips; whitespace-insensitive dedupe keeps one.
                    .map { lint ->
                        lint.copy(
                            suggestions = lint.suggestions
                                .distinctBy { it.kind to it.text?.trim() },
                        )
                    }
                    .distinct()
            }.getOrDefault(emptyList())
        }
    }

    /**
     * One fix as the span it rewrites, or null when it cannot be applied to
     * [text] at all. [apply] is this spliced in; callers that own an editor
     * rather than a string take the edit instead, and change only that span.
     */
    fun edit(text: String, lint: GrammarLint, fix: GrammarFix): GrammarEdit? {
        if (lint.end > text.length || lint.start > lint.end || lint.start < 0) return null
        return when (fix.kind) {
            "replace" -> GrammarEdit(
                lint.start, lint.end,
                trimOverlap(fix.text.orEmpty(), text, lint.start, lint.end),
            )
            "remove" -> GrammarEdit(lint.start, lint.end, "")
            "insertAfter" -> GrammarEdit(lint.end, lint.end, fix.text.orEmpty())
            else -> null
        }
    }

    /** [text] with one fix applied. */
    fun apply(text: String, lint: GrammarLint, fix: GrammarFix): String {
        val edit = edit(text, lint, fix) ?: return text
        return text.replaceRange(edit.start, edit.end, edit.text)
    }

    /**
     * Harper sometimes pads a replacement with whitespace the text already
     * has around the span ("they're " next to a following space), which
     * lands as a doubled space. Drop the padding that duplicates what is
     * already adjacent.
     */
    private fun trimOverlap(replacement: String, text: String, start: Int, end: Int): String {
        var result = replacement
        while (result.endsWith(' ') && text.getOrNull(end) == ' ') {
            result = result.dropLast(1)
        }
        while (result.startsWith(' ') && start > 0 && text[start - 1] == ' ') {
            result = result.drop(1)
        }
        return result
    }

    /**
     * Every lint's first suggestion as a splice into [text], ordered
     * back-to-front so that applying them in order leaves each later edit's
     * offsets untouched. Overlapping lints keep only the later one, so no
     * edit lands inside a span another edit already rewrote.
     */
    fun editsAll(text: String, lints: List<GrammarLint>): List<GrammarEdit> = buildList {
        var lastStart = Int.MAX_VALUE
        for (lint in lints.sortedByDescending { it.start }) {
            val fix = lint.suggestions.firstOrNull() ?: continue
            if (lint.end > lastStart || lint.end > text.length) continue
            add(edit(text, lint, fix) ?: continue)
            lastStart = lint.start
        }
    }

    /** [text] with every lint's first suggestion applied. */
    fun applyAll(text: String, lints: List<GrammarLint>): String {
        var result = text
        for (edit in editsAll(text, lints)) {
            result = result.replaceRange(edit.start, edit.end, edit.text)
        }
        return result
    }
}
