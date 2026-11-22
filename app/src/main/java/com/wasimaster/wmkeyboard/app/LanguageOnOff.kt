package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.core.layout.language
import com.wasimaster.wmkeyboard.core.layout.resolveLayout
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * Switches [langId] off without deleting anything it downloaded (#1484): its
 * layouts leave the enabled set, so it is out of the switcher and lends no
 * words to another language, as if it had been removed, and they are kept in
 * [com.wasimaster.wmkeyboard.core.settings.KeyboardSettings.languagesOff] to
 * come back with it. False, with nothing changed, when it is the last
 * language on.
 *
 * Its "Also suggest from" picks stay stored. The settings read drops a pick
 * of a language that is not on, so they stop working now and work again the
 * moment it is back.
 */
internal suspend fun switchLanguageOff(repository: SettingsRepository, settings: LiveSettings, langId: String): Boolean {
    val current = settings.value
    val own = current.enabledLayoutIds.filter { resolveLayout(current.customLayouts, it).language().id == langId }
    if (own.isEmpty()) return false
    val next = current.enabledLayoutIds.filterNot { it in own }
    if (next.isEmpty()) return false
    // The entry first: while the language is still on, the settings read
    // ignores it, so no frame shows the language both on and off.
    repository.setLanguageOff(langId, own)
    repository.setEnabledLayoutIds(next.distinct())
    return true
}

/**
 * Puts a language switched off with [switchLanguageOff] back: its layouts
 * rejoin the end of the enabled set, and the entry that kept them goes.
 */
internal suspend fun switchLanguageOn(repository: SettingsRepository, settings: LiveSettings, langId: String) {
    val current = settings.value
    val layouts = current.languagesOff[langId].orEmpty()
    if (layouts.isNotEmpty()) repository.setEnabledLayoutIds((current.enabledLayoutIds + layouts).distinct())
    repository.setLanguageOff(langId, null)
}

/**
 * Removes a language that is switched off, the way Remove takes one that is
 * on: other languages stop borrowing its words, and what it downloaded is
 * deleted ([deleteLanguageData] forgets the off entry too).
 */
internal suspend fun removeOffLanguage(
    context: Context,
    repository: SettingsRepository,
    settings: LiveSettings,
    langId: String,
) {
    val before = settings.value
    forgetAsSecondary(repository, before, langId)
    deleteLanguageData(context, repository, before, langId)
}
