package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceGroup
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Settings › Advanced › Servers: where every online service the keyboard uses
 * is called, on the F-Droid build.
 *
 * F-Droid marks an app TetheredNet when it depends on a service that cannot be
 * swapped out, and waives it when there is "a simple configuration option" to
 * point the app at a self-hostable alternative. This screen is that option for
 * all of them at once, which is also what makes it easy for a packager to check.
 * The same rows sit on each service's own page ([serverItems],
 * [ServerFieldsGroup]); both edit one setting.
 *
 * The other builds never read these values, so there the screen says so and
 * nothing links to it.
 */
@Composable
internal fun ServersSettingsScreen(repository: SettingsRepository, settings: LiveSettings) {
    if (!BuildConfig.ENABLE_FDROID) {
        StateBanner(stringResource(R.string.servers_other_edition))
    }
    SettingsGroup(
        stringResource(R.string.servers_repos_group),
        info = stringResource(R.string.servers_repos_info),
    ) {
        for (repo in ServiceRepo.entries) item { RepoLocationRows(repo, repository, settings) }
    }
    for (group in ServiceGroup.entries) {
        SettingsGroup(stringResource(group.titleRes)) {
            selfHostedItems(group, repository, settings)
            for (endpoint in ServiceEndpoint.entries.filter { it.group == group }) {
                item { EndpointField(endpoint, repository, settings) }
            }
        }
    }
}

/**
 * The server rows for [endpoints] and [repos], added to the caller's group at
 * [level] on the settings depth. Adds nothing outside the F-Droid build.
 */
internal fun SettingsGroupScope.serverItems(
    repository: SettingsRepository,
    settings: LiveSettings,
    endpoints: List<ServiceEndpoint> = emptyList(),
    repos: List<ServiceRepo> = emptyList(),
    level: SettingLevel = SettingLevel.BASIC,
) {
    if (!BuildConfig.ENABLE_FDROID) return
    for (repo in repos) item(level = level) { RepoLocationRows(repo, repository, settings) }
    for (endpoint in endpoints) item(level = level) { EndpointField(endpoint, repository, settings) }
}

/** The same rows as a group of their own, for a page with no natural group to put them in. */
@Composable
internal fun ServerFieldsGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    endpoints: List<ServiceEndpoint> = emptyList(),
    repos: List<ServiceRepo> = emptyList(),
    /** How far down the settings depth the group sits; see [SettingLevel]. */
    level: SettingLevel = SettingLevel.BASIC,
) {
    if (!BuildConfig.ENABLE_FDROID || (endpoints.isEmpty() && repos.isEmpty())) return
    SettingsGroup(
        stringResource(R.string.servers_group_title),
        info = stringResource(R.string.servers_group_info),
        level = level,
    ) {
        serverItems(repository, settings, endpoints, repos)
    }
}

/**
 * The self-hosted services each group carries besides its endpoints. None are
 * left: LibreTranslate, SearXNG, the MediaWiki, the AI servers (Ollama, LM
 * Studio, an OpenAI-compatible gateway) and transcription servers are rows of
 * their tools' provider lists, set up on the tools' own pages.
 */
@Suppress("UNUSED_PARAMETER", "UnusedReceiverParameter")
private fun SettingsGroupScope.selfHostedItems(
    group: ServiceGroup,
    repository: SettingsRepository,
    settings: LiveSettings,
) = Unit

/** One service's address. Empty means the service's own server, which the hint names. */
@Composable
private fun EndpointField(endpoint: ServiceEndpoint, repository: SettingsRepository, settings: LiveSettings) {
    val stored = settings.watch { it.selfHosted.endpoints[endpoint.id].orEmpty() }
    val usable = stored.isBlank() || ServiceEndpoints.isUsableBase(stored.trim().trimEnd('/'))
    val hint = when {
        !usable -> stringResource(R.string.servers_endpoint_invalid, endpoint.default)
        endpoint == ServiceEndpoint.WIKIPEDIA -> stringResource(R.string.servers_wikipedia_hint, endpoint.default)
        else -> stringResource(R.string.servers_endpoint_hint, endpoint.default)
    }
    TextFieldSetting(
        label = stringResource(endpoint.labelRes),
        value = stored,
        hint = hint,
        default = "",
    ) { repository.setServiceEndpoint(endpoint, it) }
}

/**
 * Where one repository lives: its forge, then the fields that forge needs, then
 * the address the files will actually come from.
 *
 * The text fields keep their own state while typing (every keystroke is saved),
 * so they are rebuilt only when something replaces the whole location: a new
 * forge, a pasted link, a reset. [revision] is that signal.
 */
@Composable
private fun RepoLocationRows(repo: ServiceRepo, repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val stored = settings.watch { it.selfHosted.repos[repo.id] }
    val shown = stored ?: repo.default
    var revision by remember { mutableIntStateOf(0) }

    fun replace(next: RepoLocation?) {
        scope.launch {
            repository.setServiceRepo(repo, next)
            revision++
        }
    }

    Column {
        ChoiceSetting(
            title = repo.labelRes,
            subtitle = stringResource(R.string.servers_repo_forge_title),
            options = GitForge.entries.map { it to stringResource(it.labelRes) },
            selected = shown.forge,
            default = repo.default.forge,
        ) { forge ->
            if (forge != shown.forge) replace(RepoLocation(forge, forge.defaultHost, shown.owner, shown.repo))
        }
        key(repo, revision) {
            var lastLength by remember { mutableIntStateOf(0) }
            TextFieldSetting(
                label = stringResource(R.string.servers_repo_paste_label),
                value = "",
                hint = stringResource(R.string.servers_repo_paste_hint),
            ) { pasted ->
                // Only a paste, never a link typed a letter at a time: the
                // first few letters of a repository name are a valid link too.
                val grew = pasted.length - lastLength
                lastLength = pasted.length
                if (grew >= MIN_PASTE_LENGTH) {
                    RepoLocation.fromPageUrl(pasted)?.let { (location, _) -> replace(location) }
                }
            }
            if (shown.forge == GitForge.FOLDER) {
                TextFieldSetting(
                    label = stringResource(R.string.servers_repo_folder_label),
                    value = shown.host,
                    hint = stringResource(R.string.servers_repo_folder_hint),
                ) { repository.setServiceRepo(repo, shown.copy(host = it)) }
            } else {
                TextFieldSetting(
                    label = stringResource(R.string.servers_repo_host_label),
                    value = shown.host,
                    hint = stringResource(R.string.servers_repo_host_hint),
                ) { repository.setServiceRepo(repo, shown.copy(host = it)) }
                TextFieldSetting(
                    label = stringResource(R.string.servers_repo_owner_label),
                    value = shown.owner,
                    hint = stringResource(R.string.servers_repo_owner_hint),
                ) { repository.setServiceRepo(repo, shown.copy(owner = it)) }
                TextFieldSetting(
                    label = stringResource(R.string.servers_repo_name_label),
                    value = shown.repo,
                    hint = stringResource(R.string.servers_repo_name_hint),
                ) { repository.setServiceRepo(repo, shown.copy(repo = it)) }
                TextFieldSetting(
                    label = stringResource(R.string.servers_repo_ref_label),
                    value = shown.ref,
                    hint = stringResource(R.string.servers_repo_ref_hint, shown.forge.defaultRef),
                ) { repository.setServiceRepo(repo, shown.copy(ref = it)) }
            }
        }
        val effective = ServiceEndpoints.resolveRepo(repo, stored, BuildConfig.ENABLE_FDROID)
        val note = if (stored != null && !stored.isComplete) {
            stringResource(R.string.servers_repo_incomplete)
        } else {
            stringResource(R.string.servers_repo_current, effective.rawUrl(""))
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (stored != null) {
            TextButton(onClick = { replace(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.servers_repo_reset))
            }
        }
    }
}

/** A change this big at once is a paste rather than typing. */
private const val MIN_PASTE_LENGTH = 8

@get:StringRes
private val ServiceGroup.titleRes: Int
    get() = when (this) {
        ServiceGroup.DOWNLOADS -> R.string.servers_section_downloads
        ServiceGroup.TRANSLATE -> R.string.servers_section_translate
        ServiceGroup.SEARCH -> R.string.servers_section_search
        ServiceGroup.MEDIA -> R.string.servers_section_media
        ServiceGroup.PHOTOS -> R.string.servers_section_photos
        ServiceGroup.WIKIPEDIA -> R.string.servers_section_wikipedia
        ServiceGroup.DICTIONARY -> R.string.servers_section_dictionary
        ServiceGroup.VOCABULARY -> R.string.servers_section_vocabulary
        ServiceGroup.WEATHER -> R.string.servers_section_weather
        ServiceGroup.MAPS -> R.string.servers_section_maps
        ServiceGroup.CURRENCY -> R.string.servers_section_currency
        ServiceGroup.SPEED_TEST -> R.string.servers_section_speed_test
        ServiceGroup.AI -> R.string.servers_section_ai
        ServiceGroup.MUSIC -> R.string.servers_section_music
        ServiceGroup.UPDATES -> R.string.servers_section_updates
    }

@get:StringRes
internal val ServiceEndpoint.labelRes: Int
    get() = when (this) {
        ServiceEndpoint.TRANSLATE_GOOGLE -> R.string.servers_translate_google
        ServiceEndpoint.TRANSLATE_CLOUD -> R.string.servers_translate_cloud
        ServiceEndpoint.BRAVE_SEARCH -> R.string.servers_brave_search
        ServiceEndpoint.TAVILY -> R.string.servers_tavily
        ServiceEndpoint.KLIPY -> R.string.servers_klipy
        ServiceEndpoint.GIPHY -> R.string.servers_giphy
        ServiceEndpoint.GIFCITIES -> R.string.servers_gifcities
        ServiceEndpoint.WAYBACK_MACHINE -> R.string.servers_wayback_machine
        ServiceEndpoint.SIGNAL_STICKER_CDN -> R.string.servers_signal_sticker_cdn
        ServiceEndpoint.TELEGRAM_BOT_API -> R.string.servers_telegram_bot_api
        ServiceEndpoint.UNSPLASH -> R.string.servers_unsplash
        ServiceEndpoint.PEXELS -> R.string.servers_pexels
        ServiceEndpoint.WIKIPEDIA -> R.string.servers_wikipedia
        ServiceEndpoint.DICTIONARY_API -> R.string.servers_dictionary_api
        ServiceEndpoint.DATAMUSE -> R.string.servers_datamuse
        ServiceEndpoint.KAIKKI -> R.string.servers_kaikki
        ServiceEndpoint.WIKTIONARY -> R.string.servers_wiktionary
        ServiceEndpoint.OPEN_METEO -> R.string.servers_open_meteo
        ServiceEndpoint.OPEN_METEO_GEOCODING -> R.string.servers_open_meteo_geocoding
        ServiceEndpoint.NOMINATIM -> R.string.servers_nominatim
        ServiceEndpoint.AUDD -> R.string.servers_audd
        ServiceEndpoint.AUDIOTAG -> R.string.servers_audiotag
        ServiceEndpoint.RAPIDAPI_SHAZAM -> R.string.servers_rapidapi_shazam
        ServiceEndpoint.ITUNES_SEARCH -> R.string.servers_itunes_search
        ServiceEndpoint.DEEZER_API -> R.string.servers_deezer_api
        ServiceEndpoint.CURRENCY_API -> R.string.servers_currency_api
        ServiceEndpoint.CURRENCY_API_MIRROR -> R.string.servers_currency_api_mirror
        ServiceEndpoint.FRANKFURTER -> R.string.servers_frankfurter
        ServiceEndpoint.ALLRATESTODAY -> R.string.servers_allratestoday
        ServiceEndpoint.ER_API -> R.string.servers_er_api
        ServiceEndpoint.COINBASE -> R.string.servers_coinbase
        ServiceEndpoint.COINGECKO -> R.string.servers_coingecko
        ServiceEndpoint.CLOUDFLARE_SPEED -> R.string.servers_cloudflare_speed
        ServiceEndpoint.LIBRESPEED_LIST -> R.string.servers_librespeed_list
        ServiceEndpoint.ANTHROPIC -> R.string.servers_anthropic
        ServiceEndpoint.OPENAI -> R.string.servers_openai
        ServiceEndpoint.GEMINI -> R.string.servers_gemini
        ServiceEndpoint.XAI -> R.string.servers_xai
        ServiceEndpoint.DEEPSEEK -> R.string.servers_deepseek
        ServiceEndpoint.KEYMAN_API -> R.string.servers_keyman_api
        ServiceEndpoint.KEYMAN_DOWNLOADS -> R.string.servers_keyman_downloads
        ServiceEndpoint.ANIMATED_EMOJI -> R.string.servers_animated_emoji
        ServiceEndpoint.FDROID_REPOSITORY -> R.string.servers_fdroid_repository
    }

@get:StringRes
private val ServiceRepo.labelRes: Int
    get() = when (this) {
        ServiceRepo.DATA -> R.string.servers_repo_data
        ServiceRepo.ADDONS -> R.string.servers_repo_addons
        ServiceRepo.SOUNDS -> R.string.servers_repo_sounds
        ServiceRepo.COMMUNITY -> R.string.servers_repo_community
        ServiceRepo.ESPANSO_HUB -> R.string.servers_repo_espanso_hub
    }

@get:StringRes
private val GitForge.labelRes: Int
    get() = when (this) {
        GitForge.GITHUB -> R.string.servers_forge_github
        GitForge.FORGEJO -> R.string.servers_forge_forgejo
        GitForge.GITLAB -> R.string.servers_forge_gitlab
        GitForge.SOURCEHUT -> R.string.servers_forge_sourcehut
        GitForge.BITBUCKET -> R.string.servers_forge_bitbucket
        GitForge.FOLDER -> R.string.servers_forge_folder
    }
