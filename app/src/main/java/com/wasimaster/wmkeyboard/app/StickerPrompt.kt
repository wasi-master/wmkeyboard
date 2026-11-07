package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.AiProvider
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.AiClient
import com.wasimaster.wmkeyboard.core.tools.OnlineTranslate
import com.wasimaster.wmkeyboard.core.translate.Cld3
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslateResult
import com.wasimaster.wmkeyboard.core.translate.OfflineTranslation
import com.wasimaster.wmkeyboard.core.translate.OnDeviceTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The prompt side of the sticker generator: the style suffixes, getting a
 * prompt in any language into the English the model reads, and the optional
 * rewrite through the user's AI provider.
 */
internal object StickerPrompt {

    /**
     * A look, as words appended to the prompt. [STICKER]'s suffix is the one
     * measured to give a single subject on white, which the background
     * remover then lifts cleanly.
     */
    enum class Style(val id: String, @StringRes val label: Int, val suffix: String) {
        STICKER(
            "sticker",
            R.string.stickergen_style_sticker,
            ", sticker, die-cut, cartoon, flat colors, bold outline, simple white background",
        ),
        CARTOON("cartoon", R.string.stickergen_style_cartoon, ", cartoon illustration, vibrant colors, clean lines, white background"),
        KAWAII("kawaii", R.string.stickergen_style_kawaii, ", kawaii, chibi, cute, pastel colors, sticker, white background"),
        PIXEL("pixel", R.string.stickergen_style_pixel, ", pixel art, 8-bit, sprite, white background"),
        THREE_D("3d", R.string.stickergen_style_3d, ", 3d render, clay style, soft lighting, cute, white background"),
        LINE("line", R.string.stickergen_style_line, ", black and white line art, ink outline, coloring book, white background"),
        EMOJI("emoji", R.string.stickergen_style_emoji, ", emoji, glossy icon, simple shapes, white background"),
        NONE("none", R.string.stickergen_style_none, ""),
        ;

        companion object {
            fun byId(id: String?): Style = entries.firstOrNull { it.id == id } ?: STICKER
        }
    }

    /** What [toEnglish] did. [source] is the language code it read, "" when it left the prompt as it was. */
    data class English(val text: String, val source: String, val failed: Boolean = false)

    /**
     * [prompt] in English. ASCII text is taken as English unless ML Kit's
     * identifier, where the phone has it, is sure it is not; anything else is
     * translated by the on-device engines first (Bergamot, NLLB, ML Kit), then
     * by the online translators on the user's translate list. [English.failed]
     * when nothing could translate it, in which
     * case the prompt goes as typed and the screen says why the picture may
     * miss.
     */
    suspend fun toEnglish(context: Context, settings: KeyboardSettings, prompt: String): English {
        val text = prompt.trim()
        if (text.isEmpty() || looksEnglish(context, text)) return English(text, "")
        if (OfflineTranslation.available) {
            val result = runCatching { OfflineTranslation.translateAny(context, text, ENGLISH) }.getOrNull()
            if (result is OfflineTranslateResult.Success && result.text.isNotBlank()) return English(result.text.trim(), result.source)
        }
        val online = withContext(Dispatchers.IO) {
            runCatching { OnlineTranslate.translate(settings, text, ENGLISH) }.getOrNull()
        }
        if (online != null && online.text.isNotBlank()) return English(online.text.trim(), online.detectedSource)
        return English(text, "", failed = true)
    }

    private suspend fun looksEnglish(context: Context, text: String): Boolean {
        if (text.any { it.code > ASCII_END && it.isLetter() }) return false
        val candidates = if (OnDeviceTranslator.AVAILABLE) {
            runCatching { OnDeviceTranslator.identify(context, text) }.getOrNull()
        } else {
            // F-Droid and the offline build: CLD3, when its library is here.
            withContext(Dispatchers.Default) { Cld3.identify(text) }
        }
        val best = candidates?.maxByOrNull { it.confidence } ?: return true
        // Short prompts are easy to misread ("pizza" is Italian); only a sure,
        // non-English verdict counts.
        return best.tag.substringBefore('-') == ENGLISH || best.confidence < SURE
    }

    /** Whether the user has an online AI provider to rewrite with. The on-device model is too slow for a tap. */
    fun canRewrite(settings: KeyboardSettings): Boolean =
        AiClient.isConfigured(settings.ai) && AiClient.config(settings.ai).provider != AiProvider.ON_DEVICE

    /**
     * [prompt] rewritten into a short English sticker prompt by the user's AI
     * provider: one subject, visual details, under the CLIP limit. Null when
     * it failed. Blocking; call off the main thread.
     */
    fun rewrite(settings: KeyboardSettings, prompt: String): String? = runCatching {
        AiClient.completeStreaming(
            AiClient.config(settings.ai),
            system = REWRITE_SYSTEM,
            user = prompt,
            maxTokens = REWRITE_TOKENS,
            onPhase = {},
            onPartial = {},
        ).text.trim().trim('"').lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private const val ENGLISH = "en"
    private const val ASCII_END = 0x7f
    private const val SURE = 0.8f
    private const val REWRITE_TOKENS = 120

    private const val REWRITE_SYSTEM =
        "You write prompts for a small image model that makes stickers. Rewrite the user's idea as one English " +
            "prompt of at most 40 words: a single clear subject, what it looks like and does, its colors and mood, " +
            "comma-separated. No background description, no style words like 'sticker' or 'cartoon' (they are added " +
            "later), no quotation marks, no explanation. Reply with the prompt only. Refuse anything sexual by replying " +
            "with the word: no"
}
