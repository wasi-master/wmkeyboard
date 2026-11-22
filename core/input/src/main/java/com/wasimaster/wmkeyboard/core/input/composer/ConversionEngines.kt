package com.wasimaster.wmkeyboard.core.input.composer

import com.wasimaster.wmkeyboard.core.script.ComposerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A native conversion engine that can stand in for one of the built-in
 * conversion composers: RIME for Chinese, Mozc for Japanese, Chewing for
 * Zhuyin.
 *
 * The built-in composer stays what the layout asks for ([composerFor]); an
 * engine only offers a replacement for it, per language, when the user has
 * picked that engine and everything it needs is on the device. Asked on every
 * composer resolution, which happens on layout binds and settings emissions,
 * so an answer has to come from fields, never from disk or the library.
 */
interface ConversionEngine {

    /** Stable id, as the settings store it (`rime`, `mozc`, `chewing`). */
    val id: String

    /**
     * Which engine answers first when two would replace the same composer:
     * the higher. An engine picked for one method (Chewing, for Zhuyin) goes
     * ahead of one picked for a whole language (RIME), since picking it there
     * was the more particular choice.
     */
    val priority: Int get() = 0

    /**
     * The composer to type through instead of [base], the built-in composer
     * [type] resolves to for [langId], or null to keep [base]. Null whenever
     * the engine is not picked for [langId], is not ready (library missing,
     * nothing deployed, storage still locked), or does not handle [type].
     */
    fun composerFor(base: Composer, type: ComposerType, langId: String?): Composer?
}

/**
 * The engines `LayoutSpec.resolvedComposer` consults, in order, and the
 * signal that their answers changed.
 *
 * A composer is captured in the keyboard's state when a layout binds, so an
 * engine becoming ready (a deploy finishing in Settings, the user unlocking
 * the phone) has to say so: it calls [changed], and the keyboard resolves its
 * composer again the next time nothing is composing.
 */
object ConversionEngines {

    @Volatile
    private var engines: List<ConversionEngine> = emptyList()

    private val generation = MutableStateFlow(0)

    /** Bumped whenever an engine's answer for some layout may have changed. */
    val changes: StateFlow<Int> = generation.asStateFlow()

    /**
     * Adds [engine] once; a second registration of the same id replaces the
     * first. Kept by [ConversionEngine.priority], highest first, and among
     * equals in the order they registered.
     */
    @Synchronized
    fun register(engine: ConversionEngine) {
        engines = (engines.filterNot { it.id == engine.id } + engine).sortedByDescending { it.priority }
        changed()
    }

    fun changed() {
        generation.update { it + 1 }
    }

    /** [base], or the first registered engine's replacement for it. */
    fun resolve(base: Composer, type: ComposerType, langId: String?): Composer {
        if (!base.isConversion) return base
        for (engine in engines) {
            engine.composerFor(base, type, langId)?.let { return it }
        }
        return base
    }
}
