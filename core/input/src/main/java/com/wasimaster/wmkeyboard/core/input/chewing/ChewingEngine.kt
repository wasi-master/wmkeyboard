package com.wasimaster.wmkeyboard.core.input.chewing

import android.content.Context
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngine
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import com.wasimaster.wmkeyboard.core.script.ComposerType
import java.util.IdentityHashMap

/**
 * Chewing as a [ConversionEngine]: once the user picks it for Zhuyin, the
 * Chinese Zhuyin layout ([ComposerType.ZHUYIN]) types through
 * [ChewingComposer] instead of the built-in composer, as soon as libchewing
 * is open. The keys and the bopomofo they type stay the built-in
 * composer's; only the conversion changes hands. The 12-key Zhuyin pad is
 * left alone: its keys stand for several symbols each, which libchewing's
 * keyboard model has no way to say.
 *
 * It answers ahead of RIME ([priority]): RIME is picked for every Chinese
 * layout at once, Chewing for Zhuyin alone, so a user who picked both meant
 * Chewing there.
 *
 * The keyboard pushes the setting in with [configure] from its settings
 * collector, so [composerFor] answers from fields alone.
 */
object ChewingEngine : ConversionEngine {

    override val id: String = "chewing"

    override val priority: Int get() = 1

    /** Whether Zhuyin converts on Chewing. */
    @Volatile
    var enabled: Boolean = false
        private set

    private val composers = IdentityHashMap<Composer, ChewingComposer>()

    init {
        ConversionEngines.register(this)
    }

    /**
     * Whether Chewing is picked. Opens libchewing in the background when it
     * is, which on a locked phone only notes that it cannot yet.
     */
    fun configure(context: Context, enabled: Boolean) {
        val changed = enabled != this.enabled
        this.enabled = enabled
        if (enabled) ChewingRuntime.prepare(context) else if (changed) ChewingRuntime.shutDown()
        if (changed) ConversionEngines.changed()
    }

    override fun composerFor(base: Composer, type: ComposerType, langId: String?): Composer? {
        if (!enabled || langId != "zh" || type != ComposerType.ZHUYIN || !ChewingRuntime.ready) return null
        return synchronized(composers) { composers.getOrPut(base) { ChewingComposer(base, ChewingRuntime) } }
    }
}
