package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.tools.SmartSuggest

/**
 * Which of the tools' shipped CJK keywords these settings can type: the
 * scripts of the enabled languages. Japanese writes both kana and Han.
 */
val KeyboardSettings.toolKeywordScripts: SmartSuggest.KeywordScripts
    get() {
        val scripts = enabledLanguages.mapTo(HashSet()) { it.script }
        return SmartSuggest.KeywordScripts(
            han = ScriptId.HAN in scripts || ScriptId.JAPANESE in scripts,
            kana = ScriptId.JAPANESE in scripts,
            hangul = ScriptId.HANGUL in scripts,
        )
    }
