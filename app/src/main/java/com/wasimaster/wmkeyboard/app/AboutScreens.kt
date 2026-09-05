package com.wasimaster.wmkeyboard.app

import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.updates.UpdateSettings
import com.wasimaster.wmkeyboard.core.settings.OnboardingSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.PersonaLanguages
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.support.Support
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Attribution and licence notices, in three sections.
 *
 * Every third-party component that ships inside the APK is listed here with
 * its licence, and the full licence texts (or, for aggregated sources, a
 * notice naming each one) are bundled as assets under `assets/licenses/` —
 * Apache-2.0 §4(a) and the MIT/BSD/OFL/Unicode notices all require the text
 * to travel with the binary, not merely a link to it. Data packs the app
 * downloads on demand are listed the same way: their licences (CC BY,
 * CC BY-SA, BSD, …) attach to the data wherever it ends up, bundled or not.
 * Online services the tools call are listed last: no code or data of theirs
 * is distributed, but their terms still ask to be credited.
 */

internal const val SOURCE_URL = Support.SOURCE_URL
internal const val DOCS_URL = "https://wmkeyboard.pages.dev"
/**
 * The privacy policy for *this* build, not for the project in general.
 *
 * The two editions are different apps underneath — lite has no ML Kit, no Play
 * libraries and no Google sign-in compiled into it — so they carry one policy
 * each, and this row has to open the one that is true of the APK the reader is
 * holding. Opening the wrong one would disclose collection that does not
 * happen, or worse, fail to disclose collection that does.
 *
 * Keyed on the flavour rather than on `BuildConfig.ENABLE_FDROID`: the F-Droid
 * build recipe sets `wmkb.enablePlayStore=false` and `wmkb.enableGms=false`
 * but not `wmkb.enableFdroid`, so that flag is false in an F-Droid install.
 * The flavour is also the more honest key — a lite APK downloaded from GitHub
 * wants exactly the same page as an F-Droid install, because it is the same
 * binary.
 */
private val PRIVACY_POLICY_URL = if (BuildConfig.FLAVOR == "lite") {
    "$DOCS_URL/privacy/policy-fdroid/"
} else {
    "$DOCS_URL/privacy/policy/"
}
private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID}"
private const val FDROID_URL = "https://f-droid.org/packages/${BuildConfig.APPLICATION_ID}/"

/**
 * Opens the system share sheet with the share blurb and [url].
 *
 * The blurb and the link go out on one line. A newline between them reads
 * better, but a receiving app is free to take the two lines as two parts of a
 * structured share: Messenger sends them on tagged with its own markers, and
 * the friend reads `mp:Try WM Keyboard…ms:https://…`. One line has no seam to
 * split on.
 */
private fun shareLink(context: android.content.Context, url: String) {
    val blurb = context.getString(R.string.about_share_blurb)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "$blurb $url")
    }
    val chooserTitle = context.getString(R.string.about_share_chooser_title)
    context.startActivity(Intent.createChooser(intent, chooserTitle).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

/**
 * One row in the licences list. [licenseAsset] is a file in `assets/licenses/`.
 *
 * Only [usedRes] is translated. [name], [copyright] and [license] are the legal
 * identity of the component: a product name, a copyright notice and a licence
 * identifier all have to travel verbatim, so they stay in English.
 */
private data class Attribution(
    val name: String,
    @StringRes val usedRes: Int,
    val copyright: String,
    val license: String,
    val licenseAsset: String?,
    val url: String,
)

/**
 * Components whose code or data is bundled into the APK. Flavour-gated
 * entries are absent from lite builds, where their libraries are not linked.
 */
private val bundledAttributions: List<Attribution> = buildList {
    add(
        Attribution(
            "AndroidX & Jetpack Compose",
            R.string.about_bundled_androidx_used,
            "Copyright The Android Open Source Project",
            "Apache-2.0", "apache-2.0.txt",
            "https://developer.android.com/jetpack/androidx",
        ),
    )
    add(
        Attribution(
            "Kotlin, coroutines & serialization",
            R.string.about_bundled_kotlin_used,
            "Copyright JetBrains s.r.o. and Kotlin contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/JetBrains/kotlin",
        ),
    )
    add(
        Attribution(
            "Coil",
            R.string.about_bundled_coil_used,
            "Copyright Coil Contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/coil-kt/coil",
        ),
    )
    add(
        Attribution(
            "OkHttp & Okio",
            R.string.about_bundled_okhttp_used,
            "Copyright Square, Inc.",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/square/okhttp",
        ),
    )
    add(
        Attribution(
            "ZXing",
            R.string.about_bundled_zxing_used,
            "Copyright ZXing authors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/zxing/zxing",
        ),
    )
    add(
        Attribution(
            "LuaJ",
            R.string.about_bundled_luaj_used,
            "Copyright (c) 2007 LuaJ. All rights reserved.",
            "MIT", "mit-luaj.txt",
            "https://github.com/luaj/luaj",
        ),
    )
    if (BuildConfig.ENABLE_GRAMMAR) {
        add(
            Attribution(
                "Harper",
                R.string.about_bundled_harper_used,
                "Copyright Automattic, Inc. and Harper contributors",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/automattic/harper",
            ),
        )
        add(
            Attribution(
                "Rust crates used by Harper",
                R.string.about_bundled_rust_crates_used,
                "Copyright the respective crate authors",
                "MIT / Apache-2.0 / others", "harper-third-party.txt",
                "https://crates.io",
            ),
        )
    }
    if (BuildConfig.ENABLE_ML_KIT_HANDWRITING || BuildConfig.ENABLE_ML_KIT_SCANNERS) {
        add(
            Attribution(
                "Google ML Kit",
                R.string.about_bundled_mlkit_used,
                "Copyright Google LLC",
                "Google APIs Terms of Service", null,
                "https://developers.google.com/ml-kit/terms",
            ),
        )
    }
    if (BuildConfig.ENABLE_PLAY_STORE) {
        // Linked only into the Play build, which is the only one where in-app
        // updates can work. Its licence is Google's own SDK terms rather than
        // an open-source licence, so the row opens those terms instead of a
        // bundled text file.
        add(
            Attribution(
                "Play In-App Updates",
                R.string.about_bundled_play_update_used,
                "Copyright Google LLC",
                "Play Core Software Development Kit Terms of Service", null,
                "https://developer.android.com/guide/playcore/license",
            ),
        )
    }
    if (BuildConfig.ENABLE_LOCAL_LLM) {
        add(
            Attribution(
                "LiteRT-LM",
                R.string.about_bundled_litert_lm_used,
                "Copyright Google LLC",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/google-ai-edge/LiteRT-LM",
            ),
        )
    }
    if (BuildConfig.ENABLE_WHISPER) {
        add(
            Attribution(
                "LiteRT",
                R.string.about_bundled_litert_used,
                "Copyright Google LLC",
                "Apache-2.0", "apache-2.0.txt",
                "https://github.com/google-ai-edge/LiteRT",
            ),
        )
        add(
            Attribution(
                "OpenAI Whisper",
                R.string.about_bundled_whisper_used,
                "Copyright (c) 2022 OpenAI",
                "MIT", "mit-whisper.txt",
                "https://github.com/openai/whisper",
            ),
        )
        add(
            Attribution(
                "whisper_android",
                R.string.about_bundled_whisper_android_used,
                "Copyright (c) 2023 Vilas Ninawe",
                "MIT", "mit-whisper-android.txt",
                "https://github.com/vilassn/whisper_android",
            ),
        )
    }
    add(
        Attribution(
            "Unicode CLDR & emoji data",
            R.string.about_bundled_unicode_used,
            "Copyright Unicode, Inc.",
            "Unicode License v3", "unicode-3.0.txt",
            "https://www.unicode.org/license.txt",
        ),
    )
    add(
        Attribution(
            "gemoji",
            R.string.about_bundled_gemoji_used,
            "Copyright (c) 2019 GitHub, Inc.",
            "MIT", "mit-gemoji.txt",
            "https://github.com/github/gemoji",
        ),
    )
    // One row for 862 keyboards. Each carries its own MIT licence differing
    // only in the holder line, so the asset states the shared text once and
    // then names every holder; shipping 862 near-identical LICENSE.md files
    // would be the same obligation met less readably.
    add(
        Attribution(
            "Keyman keyboard layouts",
            R.string.about_bundled_keyman_used,
            "Copyright the individual keyboard authors, listed in the licence",
            "MIT", "mit-keyman.txt",
            "https://github.com/keymanapp/keyboards",
        ),
    )
    add(
        Attribution(
            "OpenCC",
            R.string.about_bundled_opencc_used,
            "Copyright Carbo Kuo and OpenCC contributors",
            "Apache-2.0", "apache-2.0.txt",
            "https://github.com/BYVoid/OpenCC",
        ),
    )
    add(
        Attribution(
            "LSHK Jyutping table",
            R.string.about_bundled_lshk_used,
            "Copyright the Linguistic Society of Hong Kong",
            "CC BY 4.0", "cc-by-4.0-lshk.txt",
            "https://github.com/lshk-org/jyutping-table",
        ),
    )
    add(
        Attribution(
            "Editor colour palettes",
            R.string.about_bundled_palettes_used,
            "Copyright the respective theme authors",
            "MIT", "mit-color-themes.txt",
            "https://github.com/dracula/dracula-theme",
        ),
    )
    add(
        Attribution(
            "Google Fonts",
            R.string.about_bundled_fonts_used,
            "Copyright the respective font authors",
            "SIL Open Font License 1.1", "ofl-1.1.txt",
            "https://fonts.google.com/attribution",
        ),
    )
    // The two faces the settings app itself is set in. Unlike the keyboard's
    // typefaces above, these files are in the APK, so their own notices travel
    // with them rather than the generic licence text.
    add(
        Attribution(
            "Inter & Manrope",
            R.string.about_bundled_ui_fonts_used,
            "Copyright 2020 The Inter Project Authors; Copyright 2018 The Manrope Project Authors",
            "SIL Open Font License 1.1", "ofl-1.1-fonts.txt",
            "https://github.com/rsms/inter",
        ),
    )
}

/**
 * Data the app downloads on demand rather than bundling: the CJK conversion
 * packs and the per-language wordlists, offensive lists and emoji keyword
 * dictionaries served from the wmkeyboard-data repository. Their licences
 * attach to the data itself, so they are listed with full notices exactly
 * like the bundled components.
 */
private val dataPackAttributions: List<Attribution> = listOf(
    Attribution(
        "Frequency wordlists",
        R.string.about_pack_wordlists_used,
        "Copyright the respective corpus authors",
        "CC BY-SA 4.0 / CC BY 4.0 / MIT / others", "wordlist-sources.txt",
        "https://github.com/wasi-master/wmkeyboard-data",
    ),
    Attribution(
        "Offensive word lists",
        R.string.about_pack_offensive_used,
        "Aggregated from LDNOOBW V2, profanity-list and other open lists",
        "CC0 / Unlicense / MIT", "wordlist-sources.txt",
        "https://github.com/wasi-master/wmkeyboard-data",
    ),
    Attribution(
        "Emoji keyword dictionaries",
        R.string.about_pack_emoji_keywords_used,
        "Copyright Unicode, Inc. (CLDR annotations and emoji data)",
        "Unicode License v3", "unicode-3.0.txt",
        "https://github.com/KDE/kemoji",
    ),
    Attribution(
        "Noto Animated Emoji",
        R.string.about_pack_animated_emoji_used,
        "Copyright Google LLC",
        "CC BY 4.0", "cc-by-4.0-noto-animated.txt",
        "https://googlefonts.github.io/noto-emoji-animation/",
    ),
    Attribution(
        "CC-CEDICT",
        R.string.about_pack_cedict_used,
        "Copyright MDBG and CC-CEDICT contributors",
        "CC BY-SA 4.0", "cc-by-sa-4.0.txt",
        "https://cc-cedict.org/",
    ),
    Attribution(
        "mozc",
        R.string.about_pack_mozc_used,
        "Copyright 2010-2021 Google Inc.",
        "BSD-3-Clause", "bsd-3-clause.txt",
        "https://github.com/google/mozc",
    ),
    Attribution(
        "rime-cantonese & CC-Canto",
        R.string.about_pack_cantonese_used,
        "Copyright CanCLID and Pleco Inc.",
        "CC BY 4.0 / CC BY-SA 3.0", "jyutping-sources.txt",
        "https://github.com/rime/rime-cantonese",
    ),
    Attribution(
        "Chinese stroke code table",
        R.string.about_pack_stroke_used,
        "Copyright (c) 2021, FeiJiang Ye",
        "BSD-2-Clause", "bsd-2-clause-stroke.txt",
        "https://github.com/yefeijiang/Chinese-characters-code-table",
    ),
    Attribution(
        "Unicode Unihan database",
        R.string.about_pack_unihan_used,
        "Copyright Unicode, Inc.",
        "Unicode License v3", "unicode-3.0.txt",
        "https://www.unicode.org/",
    ),
)

/**
 * Services the tools call over the network. Nothing of theirs is bundled, so
 * these are attribution and terms links rather than licence texts.
 */
private val serviceAttributions: List<Attribution> = listOf(
    Attribution(
        "Brave Search", R.string.about_service_brave_used, "",
        "Brave Search API terms", null,
        "https://brave.com/search/api/",
    ),
    Attribution(
        "KLIPY & GIPHY", R.string.about_service_gif_used, "",
        "Provider API terms", null,
        "https://developers.giphy.com/",
    ),
    Attribution(
        "Wikipedia", R.string.about_service_wikipedia_used, "",
        "CC BY-SA (article text keeps its own licence)", null,
        "https://en.wikipedia.org/wiki/Wikipedia:Copyrights",
    ),
    Attribution(
        "Google Translate", R.string.about_service_translate_used, "",
        "Google Cloud terms", null,
        "https://cloud.google.com/terms",
    ),
    Attribution(
        "Open-Meteo", R.string.about_service_weather_used, "",
        "CC BY 4.0", null,
        "https://open-meteo.com/en/license",
    ),
    Attribution(
        "Frankfurter & ExchangeRate-API", R.string.about_service_currency_used, "",
        "Provider terms", null,
        "https://www.frankfurter.app/",
    ),
    Attribution(
        "Coinbase, currency-api & CoinGecko", R.string.about_service_crypto_used, "",
        "Provider terms", null,
        "https://docs.cdp.coinbase.com/",
    ),
    Attribution(
        "Free Dictionary API", R.string.about_service_dictionary_used, "",
        "Provider terms", null,
        "https://dictionaryapi.dev/",
    ),
    Attribution(
        "Hugging Face", R.string.about_service_models_used, "",
        "Per-model licence, accepted on the model's page", null,
        "https://huggingface.co/terms-of-service",
    ),
    Attribution(
        "Anthropic", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://www.anthropic.com/legal/consumer-terms",
    ),
    Attribution(
        "OpenAI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://openai.com/policies/terms-of-use/",
    ),
    Attribution(
        "Google AI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://ai.google.dev/terms",
    ),
    Attribution(
        "xAI", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://x.ai/legal/terms-of-service",
    ),
    Attribution(
        "DeepSeek", R.string.about_service_byok_used, "",
        "Provider terms, under your own account", null,
        "https://platform.deepseek.com/downloads/DeepSeek%20Open%20Platform%20Terms%20of%20Service.html",
    ),
    // AiProvider.OPENAI_COMPATIBLE has no fixed endpoint: the address is typed
    // by the user, so the only honest attribution is that whatever they point
    // it at governs itself. The row still needs a destination (an empty URL
    // would throw out of openUri), so it goes to the docs page that explains
    // the escape hatch. Listed last so the named providers stay grouped.
    Attribution(
        "Any other OpenAI-compatible service", R.string.about_service_byok_used, "",
        "Terms of whichever service you point it at", null,
        "$DOCS_URL/tools/ai/#any-other-openai-compatible-service",
    ),
)

/**
 * "A good middle · Several languages", or "Not answered".
 *
 * Depth first, because it is the answer that changes the most: it decides how
 * many tools are pinned, how many wizard pages exist and whether the theme
 * gallery groups families. The privacy answer is left out — it wrote its
 * settings once and they are visible on the Privacy screen, so repeating it
 * here would imply this row still governs them.
 */
@Composable
private fun personaSummary(persona: OnboardingSettings): String {
    val depth = when (persona.personaDepth) {
        PersonaDepth.MINIMAL -> R.string.about_persona_depth_minimal
        PersonaDepth.BALANCED -> R.string.about_persona_depth_balanced
        PersonaDepth.POWER -> R.string.about_persona_depth_power
        PersonaDepth.UNSET -> return stringResource(R.string.about_persona_unset)
    }
    val languages = when (persona.personaLanguages) {
        PersonaLanguages.ONE -> R.string.about_persona_languages_one
        PersonaLanguages.MANY -> R.string.about_persona_languages_many
        PersonaLanguages.UNSET -> return stringResource(depth)
    }
    return stringResource(R.string.about_persona_value, stringResource(depth), stringResource(languages))
}

@Composable
internal fun AboutSettings(
    persona: OnboardingSettings,
    onOpenLicenses: () -> Unit,
    onOpenLicenseText: (String) -> Unit,
    onOpenDebugLog: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenEggGame: () -> Unit = {},
    onReplayOnboarding: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val flavor = BuildConfig.FLAVOR.replaceFirstChar { it.uppercase() }
    val channel = when {
        BuildConfig.ENABLE_PLAY_STORE -> " · Play Store"
        BuildConfig.ENABLE_FDROID -> " · F-Droid"
        else -> ""
    }

    // Read outside the click handlers: they are plain lambdas, not composables.
    val bugReportSubject = stringResource(R.string.about_bug_report_email_subject)

    SettingsGroup(stringResource(CommonR.string.common_share)) {
        if (!BuildConfig.ENABLE_FDROID) {
            item {
                NavRow(
                    R.string.about_share_play_link,
                    PLAY_STORE_URL.removePrefix("https://"),
                ) {
                    shareLink(context, PLAY_STORE_URL)
                }
            }
        }
        if (!BuildConfig.ENABLE_PLAY_STORE) {
            item {
                NavRow(
                    R.string.about_share_fdroid_link,
                    FDROID_URL.removePrefix("https://"),
                ) {
                    shareLink(context, FDROID_URL)
                }
            }
            item {
                NavRow(
                    R.string.about_share_github_link,
                    SOURCE_URL.removePrefix("https://"),
                ) {
                    shareLink(context, SOURCE_URL)
                }
            }
        }
    }

    // The version row's Android-style secret: seven taps open the keycap
    // catcher. The counter never leaves this screen, and the row keeps
    // looking like the inert fact it is the other 6 taps.
    var versionTaps by remember { mutableIntStateOf(0) }
    var versionTapToast by remember { mutableStateOf<Toast?>(null) }

    SettingsGroup(
        stringResource(R.string.about_app_title),
        info = stringResource(R.string.about_free_software_body),
    ) {
        item {
            NavRow(
                R.string.about_version_title,
                stringResource(
                    R.string.about_version_subtitle,
                    flavor,
                    BuildConfig.BUILD_TYPE,
                    channel,
                    BuildConfig.VERSION_CODE,
                ),
                value = BuildConfig.VERSION_NAME,
            ) {
                versionTaps++
                when {
                    versionTaps >= 7 -> {
                        versionTaps = 0
                        versionTapToast?.cancel()
                        onOpenEggGame()
                    }
                    versionTaps >= 4 -> {
                        val left = 7 - versionTaps
                        versionTapToast?.cancel()
                        versionTapToast = Toast.makeText(
                            context,
                            context.resources.getQuantityString(
                                R.plurals.egg_version_taps_body, left, left,
                            ),
                            Toast.LENGTH_SHORT,
                        ).also { it.show() }
                    }
                }
            }
        }
        item {
            // The licence identifier and the copyright line travel verbatim.
            NavRow(R.string.about_licence_title, "MIT, © 2026 Wasi Master") {
                onOpenLicenseText("mit-wmkeyboard.txt")
            }
        }
        item {
            NavRow(
                R.string.about_source_title,
                SOURCE_URL.removePrefix("https://"),
            ) {
                uriHandler.openUri(SOURCE_URL)
            }
        }
        item {
            NavRow(
                R.string.about_storage_title,
                stringResource(R.string.about_storage_subtitle),
                route = "storage",
                onClick = onOpenStorage,
            )
        }
        item {
            NavRow(
                R.string.statistics_title,
                stringResource(R.string.statistics_subtitle),
                route = "statistics",
                onClick = onOpenStatistics,
            )
        }
        item {
            NavRow(
                R.string.about_diagnostics_title,
                stringResource(R.string.about_diagnostics_subtitle),
                route = "debug_log",
                onClick = onOpenDebugLog,
            )
        }
        item {
            // The quiz answers still decide how many tools are pinned, how the
            // theme gallery is laid out and which pages the wizard even has,
            // and until this row existed there was nowhere in settings that
            // said so or showed what had been answered. Tapping goes to the
            // wizard, whose first question is this one.
            NavRow(
                R.string.about_persona_title,
                stringResource(R.string.about_persona_subtitle),
                value = personaSummary(persona),
                onClick = onReplayOnboarding,
            )
        }
        item {
            // The wizard's only way back in once it has been finished. A
            // replay never rewrites settings, so the row can promise that.
            NavRow(
                R.string.about_replay_onboarding_title,
                stringResource(R.string.about_replay_onboarding_subtitle),
                onClick = onReplayOnboarding,
            )
        }
    }

    // Directly under the version it is about. Present only in a Play build:
    // everywhere else the updater reports "unsupported" and this draws nothing.
    UpdateSettings()

    SettingsGroup(
        stringResource(R.string.about_feedback_title),
        info = stringResource(R.string.about_feedback_body),
    ) {
        item {
            NavRow(
                R.string.about_report_bug_title,
                stringResource(R.string.about_report_bug_subtitle),
            ) {
                uriHandler.openUri(Support.ISSUES_URL)
            }
        }
        item {
            NavRow(R.string.about_email_developer_title, Support.EMAIL) {
                if (!Support.email(context, bugReportSubject, Support.bugReport())) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.about_no_email_app_error, Support.EMAIL),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    SettingsGroup(stringResource(R.string.about_documentation_title)) {
        item {
            NavRow(
                R.string.about_user_guide_title,
                DOCS_URL.removePrefix("https://"),
            ) {
                uriHandler.openUri(DOCS_URL)
            }
        }
        item {
            NavRow(
                R.string.about_privacy_policy_title,
                stringResource(R.string.about_privacy_policy_subtitle),
            ) {
                uriHandler.openUri(PRIVACY_POLICY_URL)
            }
        }
    }

    SettingsGroup(stringResource(R.string.about_third_party_title)) {
        item {
            NavRow(
                R.string.about_licences_title,
                stringResource(R.string.about_licences_subtitle),
                route = "licenses",
            ) { onOpenLicenses() }
        }
    }

    SettingsGroup(stringResource(R.string.about_word_lists_title)) {
        item {
            NavRow(
                R.string.about_dictionaries_title,
                stringResource(R.string.about_dictionaries_subtitle),
            ) {}
        }
    }
}

@Composable
internal fun LicensesScreen(onOpenLicenseText: (String) -> Unit) {
    val uriHandler = LocalUriHandler.current

    SettingsGroup(
        stringResource(R.string.about_section_bundled_title),
        info = stringResource(R.string.about_licences_intro_body),
    ) {
        bundledAttributions.forEach { entry ->
            item {
                NavRow(entry.name, licenceRowSubtitle(entry)) {
                    if (entry.licenseAsset != null) onOpenLicenseText(entry.licenseAsset)
                    else uriHandler.openUri(entry.url)
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.about_section_data_packs_title),
        info = stringResource(R.string.about_data_packs_body),
    ) {
        dataPackAttributions.forEach { entry ->
            item {
                NavRow(entry.name, licenceRowSubtitle(entry)) {
                    if (entry.licenseAsset != null) onOpenLicenseText(entry.licenseAsset)
                    else uriHandler.openUri(entry.url)
                }
            }
        }
    }
    SettingsGroup(
        stringResource(R.string.about_section_services_title),
        info = stringResource(R.string.about_services_body),
    ) {
        serviceAttributions.forEach { entry ->
            item {
                val subtitle = stringResource(
                    R.string.about_service_row_subtitle,
                    stringResource(entry.usedRes),
                    entry.license,
                )
                NavRow(entry.name, subtitle) {
                    uriHandler.openUri(entry.url)
                }
            }
        }
    }
}

/** "What it is used for", then the copyright line and the licence name. */
@Composable
private fun licenceRowSubtitle(entry: Attribution): String = stringResource(
    R.string.about_licence_row_subtitle,
    stringResource(entry.usedRes),
    entry.copyright,
    entry.license,
)

/** Renders one bundled licence file verbatim. */
@Composable
internal fun LicenseTextScreen(assetName: String) {
    val context = LocalContext.current
    var text by remember(assetName) { mutableStateOf<String?>(null) }
    LaunchedEffect(assetName) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/$assetName").use { it.readBytes().decodeToString() }
            }.getOrElse { context.getString(R.string.about_licence_text_error) }
        }
    }
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(8.dp))
        // Licence texts are hard-wrapped at 80 columns; scroll sideways rather
        // than reflow, so the original layout stays intact.
        Text(
            text.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        )
    }
}
