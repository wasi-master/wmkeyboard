package com.wasimaster.wmkeyboard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.vocab.VocabLanguages
import kotlinx.coroutines.launch

/**
 * Languages Wiktionary's English entries commonly translate into, offered
 * in the picker beside the packs' sidecars and the keyboard's languages.
 */
private val WIKTIONARY_COMMON_CODES = listOf(
    "ar", "bn", "cmn", "cs", "da", "de", "el", "es", "fa", "fi", "fr", "he", "hi", "hu", "id", "it",
    "ja", "ko", "ms", "nl", "no", "pl", "pt", "ro", "ru", "sv", "sw", "ta", "th", "tr", "uk", "ur", "vi",
)

/** The codes the Dictionary tool translates into: the user's picks, else the keyboard's languages. */
internal fun dictionaryTranslationCodes(settings: KeyboardSettings): List<String> =
    VocabLanguages.wantedCodes(settings.dictionaryTranslations.langList, settings.enabledLanguages.map { it.id })

/**
 * The Dictionary tool's translations (#764): whether the panel shows the
 * looked-up word in other languages, and which languages.
 */
@Composable
internal fun DictionaryTranslationsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val enabled = settings.watch { it.dictionaryTranslations.enabled }
    SettingsGroup(
        stringResource(R.string.tooldetail_dictionary_translations_group),
        info = stringResource(R.string.tooldetail_dictionary_translations_info),
        level = SettingLevel.STANDARD,
    ) {
        item {
            ToggleSetting(
                R.string.tooldetail_dictionary_translations_title,
                stringResource(R.string.tooldetail_dictionary_translations_subtitle),
                enabled,
                default = SettingsDefaults.dictionaryTranslations.enabled,
                info = stringResource(R.string.tooldetail_dictionary_translations_info),
            ) { scope.launch { repository.setDictionaryTranslations(it) } }
        }
        item(visible = enabled) {
            var picking by remember { mutableStateOf(false) }
            val chosen = settings.watch { it.dictionaryTranslations.langList }
            val automatic = settings.watch { dictionaryTranslationCodes(it) }
            NavRow(
                R.string.tooldetail_dictionary_translation_langs_title,
                subtitle = when {
                    chosen.isNotEmpty() -> chosen.joinToString(", ") { languageNameFor(it) }
                    automatic.isNotEmpty() -> stringResource(
                        R.string.tooldetail_vocab_translations_auto_named_subtitle,
                        automatic.joinToString(", ") { languageNameFor(it) },
                    )
                    else -> stringResource(R.string.tooldetail_dictionary_translation_langs_none_subtitle)
                },
            ) { picking = true }
            if (picking) {
                TranslationLanguagesDialog(
                    settings = settings,
                    onDismiss = { picking = false },
                    onChange = { codes -> scope.launch { repository.setDictionaryTranslationLangs(codes.joinToString(",")) } },
                    chosenNow = { it.dictionaryTranslations.langList },
                    title = stringResource(R.string.tooldetail_dictionary_translation_langs_title),
                    body = stringResource(R.string.tooldetail_dictionary_translation_langs_dialog_body),
                    extraCodes = WIKTIONARY_COMMON_CODES,
                )
            }
        }
    }
}
