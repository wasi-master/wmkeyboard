package com.wasimaster.wmkeyboard.app.homeassistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.BannerTone
import com.wasimaster.wmkeyboard.app.ChoiceDetail
import com.wasimaster.wmkeyboard.app.ChoiceSetting
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.ProvidersGroup
import com.wasimaster.wmkeyboard.app.ReorderableColumn
import com.wasimaster.wmkeyboard.app.SettingLevel
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.StateBanner
import com.wasimaster.wmkeyboard.app.SwipeAction
import com.wasimaster.wmkeyboard.app.SwipeActionsRow
import com.wasimaster.wmkeyboard.app.SwipeTone
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.core.homeassistant.HaDomains
import com.wasimaster.wmkeyboard.core.homeassistant.HaInsertFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaStateFormat
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeRef
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.VisibilityOff
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.settings.HaLinkLifetime
import com.wasimaster.wmkeyboard.core.settings.HaOpenOn
import com.wasimaster.wmkeyboard.core.settings.HaTileSize
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.assistHome
import com.wasimaster.wmkeyboard.core.settings.homes
import com.wasimaster.wmkeyboard.ime.homeassistant.HomeStatus
import com.wasimaster.wmkeyboard.ime.homeassistant.SmartHomeHub
import kotlinx.coroutines.launch

/**
 * The smart home tool's own page: the switch, the homes as a provider list,
 * then how the panel lays itself out, Assist (with a Home Assistant on the
 * list), and how long the connections are held. The hub is fed the settings
 * here too, so the banners read the real connections even when the keyboard
 * has not run since the last change.
 */
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun SmartHomeToolSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val defaults = HomeAssistantSettings()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hub by SmartHomeHub.state.collectAsState()
    val haSignIn by HaSignIn.status.collectAsState()
    val stSignIn by SmartThingsSignIn.status.collectAsState()
    val ha = settings.watch { it.homeAssistant }
    val homes = settings.watch { it.homes() }
    LaunchedEffect(ha, homes) {
        SmartHomeHub.attach(context)
        SmartHomeHub.apply(ha, homes)
    }
    // The page is one of the reasons the connections may be up, so the banners are live.
    DisposableEffect(Unit) {
        SmartHomeHub.attach(context)
        SmartHomeHub.hold(SmartHomeHub.Reason.SETTINGS)
        onDispose { SmartHomeHub.release(SmartHomeHub.Reason.SETTINGS) }
    }
    val on = ha.enabled

    when (val s = haSignIn) {
        HaSignIn.Status.Working -> StateBanner(stringResource(R.string.ha_banner_working))
        is HaSignIn.Status.Failed -> StateBanner(stringResource(R.string.ha_banner_failed, s.message), action = stringResource(R.string.ha_banner_action_dismiss), tone = BannerTone.WARNING) { HaSignIn.clearStatus() }
        is HaSignIn.Status.SignedIn -> StateBanner(stringResource(R.string.ha_banner_signed_in, s.instanceName), action = stringResource(R.string.ha_banner_action_dismiss)) { HaSignIn.clearStatus() }
        null -> Unit
    }
    when (val s = stSignIn) {
        SmartThingsSignIn.Status.Working -> StateBanner(stringResource(R.string.ha_banner_working))
        is SmartThingsSignIn.Status.Failed -> StateBanner(stringResource(R.string.ha_banner_failed, s.message), action = stringResource(R.string.ha_banner_action_dismiss), tone = BannerTone.WARNING) { SmartThingsSignIn.clearStatus() }
        is SmartThingsSignIn.Status.SignedIn -> StateBanner(stringResource(R.string.ha_banner_signed_in, s.locationName), action = stringResource(R.string.ha_banner_action_dismiss)) { SmartThingsSignIn.clearStatus() }
        null -> Unit
    }
    when {
        !on -> StateBanner(stringResource(R.string.ha_banner_off))
        homes.isEmpty() -> StateBanner(stringResource(R.string.ha_banner_no_homes), tone = BannerTone.WARNING)
        else -> hub.homes.forEach { home -> HomeBanner(home) }
    }

    SettingsGroup(stringResource(R.string.ha_group_connection), info = stringResource(R.string.ha_group_connection_info)) {
        item {
            ToggleSetting(R.string.ha_enabled_title, stringResource(R.string.ha_enabled_subtitle), on, default = defaults.enabled, info = stringResource(R.string.ha_enabled_info)) {
                scope.launch { repository.setHaEnabled(it) }
            }
        }
    }
    ProvidersGroup(
        repository,
        settings,
        ProviderTool.SMART_HOME,
        titleRes = R.string.smarthome_homes_title,
        info = stringResource(R.string.smarthome_homes_info),
        onChanged = { if (!on) scope.launch { repository.setHaEnabled(true) } },
    )
    if (!on) return

    SettingsGroup(stringResource(R.string.ha_group_panel)) {
        item {
            val count = settings.watch { it.homeAssistant.favourites.size }
            NavRow(
                title = R.string.ha_favourites_title,
                subtitle = if (count == 0) stringResource(R.string.ha_favourites_subtitle_none) else stringResource(R.string.ha_favourites_subtitle, count),
            ) { onNavigate(ENTITIES_ROUTE) }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.ha_open_on_title,
                options = listOf(
                    HaOpenOn.FAVOURITES to stringResource(R.string.ha_open_on_favourites),
                    HaOpenOn.LAST_TAB to stringResource(R.string.ha_open_on_last),
                ),
                selected = settings.watch { it.homeAssistant.openOn },
                default = defaults.openOn,
                info = stringResource(R.string.ha_open_on_info),
            ) { scope.launch { repository.setHaOpenOn(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.ha_areas_tabs_title, stringResource(R.string.ha_areas_tabs_subtitle),
                settings.watch { it.homeAssistant.areasAsTabs }, default = defaults.areasAsTabs,
                info = stringResource(R.string.ha_areas_tabs_info),
            ) { scope.launch { repository.setHaAreasAsTabs(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.ha_tile_size_title,
                options = listOf(
                    HaTileSize.COMFORTABLE to stringResource(R.string.ha_tile_comfortable),
                    HaTileSize.COMPACT to stringResource(R.string.ha_tile_compact),
                ),
                selected = settings.watch { it.homeAssistant.tileSize },
                default = defaults.tileSize,
                info = stringResource(R.string.ha_tile_size_info),
            ) { scope.launch { repository.setHaTileSize(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.ha_confirm_risky_title, stringResource(R.string.ha_confirm_risky_subtitle),
                settings.watch { it.homeAssistant.confirmRisky }, default = defaults.confirmRisky,
                info = stringResource(R.string.ha_confirm_risky_info),
            ) { scope.launch { repository.setHaConfirmRisky(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                R.string.ha_insert_format_title,
                options = listOf(
                    HaInsertFormat.VALUE to stringResource(R.string.ha_insert_value),
                    HaInsertFormat.VALUE_UNIT to stringResource(R.string.ha_insert_value_unit),
                    HaInsertFormat.NAME_VALUE to stringResource(R.string.ha_insert_name_value),
                ),
                selected = settings.watch { HaInsertFormat.of(it.homeAssistant.insertFormat) },
                default = HaInsertFormat.of(defaults.insertFormat),
                info = stringResource(R.string.ha_insert_format_info),
            ) { scope.launch { repository.setHaInsertFormat(it.id) } }
        }
    }

    val hasAssist = settings.watch { it.assistHome() != null }
    if (hasAssist) {
        SettingsGroup(
            stringResource(R.string.ha_group_assist),
            info = stringResource(R.string.ha_group_assist_info),
            level = SettingLevel.STANDARD,
        ) {
            item {
                ToggleSetting(
                    R.string.ha_assist_enabled_title, stringResource(R.string.ha_assist_enabled_subtitle),
                    settings.watch { it.homeAssistant.assistEnabled }, default = defaults.assistEnabled,
                    info = stringResource(R.string.ha_assist_enabled_info),
                ) { scope.launch { repository.setHaAssistEnabled(it) } }
            }
            item(visible = ha.assistEnabled, level = SettingLevel.ADVANCED) {
                val agents = hub.agents
                val selected = settings.watch { it.homeAssistant.assistAgentId }
                ChoiceSetting(
                    R.string.ha_assist_agent_title,
                    subtitle = stringResource(R.string.ha_assist_agent_subtitle),
                    options = listOf("" to stringResource(R.string.ha_assist_agent_default)) + agents.map { it.id to it.name },
                    selected = if (agents.any { it.id == selected }) selected else "",
                    default = "",
                    info = stringResource(R.string.ha_assist_agent_info),
                ) { scope.launch { repository.setHaAssistAgent(it) } }
            }
            item(visible = ha.assistEnabled, level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.ha_assist_language_title, stringResource(R.string.ha_assist_language_subtitle),
                    settings.watch { it.homeAssistant.assistFollowsKeyboardLanguage }, default = defaults.assistFollowsKeyboardLanguage,
                    info = stringResource(R.string.ha_assist_language_info),
                ) { scope.launch { repository.setHaAssistFollowsLanguage(it) } }
            }
        }
    }

    SettingsGroup(stringResource(R.string.ha_group_link), level = SettingLevel.ADVANCED) {
        item {
            val details = mapOf(
                HaLinkLifetime.PANEL to stringResource(R.string.ha_lifetime_panel_detail),
                HaLinkLifetime.KEYBOARD to stringResource(R.string.ha_lifetime_keyboard_detail),
            )
            ChoiceSetting(
                R.string.ha_lifetime_title,
                info = stringResource(R.string.ha_lifetime_info),
                options = listOf(
                    HaLinkLifetime.PANEL to stringResource(R.string.ha_lifetime_panel),
                    HaLinkLifetime.KEYBOARD to stringResource(R.string.ha_lifetime_keyboard),
                ),
                selected = settings.watch { it.homeAssistant.lifetime },
                default = defaults.lifetime,
                detail = { choice -> details[choice]?.let { ChoiceDetail(it) } },
            ) { scope.launch { repository.setHaLifetime(it) } }
        }
        item {
            ToggleSetting(
                R.string.ha_cache_title, stringResource(R.string.ha_cache_subtitle),
                settings.watch { it.homeAssistant.cacheStates }, default = defaults.cacheStates,
                info = stringResource(R.string.ha_cache_info),
            ) { scope.launch { repository.setHaCacheStates(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.ha_group_also), level = SettingLevel.STANDARD) {
        item {
            NavRow(title = R.string.ha_ai_tools_title, subtitle = stringResource(R.string.ha_ai_tools_subtitle)) {
                onNavigate("tool/${ToolbarTool.AI.name}")
            }
        }
        item(visible = hasAssist) {
            ToggleSetting(
                R.string.ha_smart_chip_title, stringResource(R.string.ha_smart_chip_subtitle),
                settings.watch { it.homeAssistant.smartChip }, default = defaults.smartChip,
                info = stringResource(R.string.ha_smart_chip_info),
            ) { scope.launch { repository.setHaSmartChip(it) } }
        }
    }
}

/** One home's connection as a banner: its name, how it stands, and Retry when it does not answer. */
@Composable
private fun HomeBanner(home: HomeStatus) {
    val name = home.name.ifBlank { stringResource(R.string.ha_home_default_name) }
    when (home.phase) {
        HaPhase.TOKEN_REJECTED -> StateBanner(stringResource(R.string.ha_banner_home_rejected, name), tone = BannerTone.WARNING)
        HaPhase.UNREACHABLE -> StateBanner(
            stringResource(R.string.ha_banner_unreachable, name),
            action = stringResource(R.string.ha_banner_action_retry),
            tone = BannerTone.WARNING,
        ) { SmartHomeHub.refresh() }
        HaPhase.LIVE, HaPhase.REST_FALLBACK -> StateBanner(
            home.instance?.version?.let { stringResource(R.string.ha_banner_connected_version, name, it) } ?: stringResource(R.string.ha_banner_connected, name),
        )
        HaPhase.CONNECTING, HaPhase.AUTHENTICATING -> StateBanner(stringResource(R.string.ha_banner_connecting, name))
        HaPhase.OFFLINE -> StateBanner(stringResource(R.string.ha_banner_signed_in, name))
    }
}

/**
 * Favourites and rooms: the Favourites tab's tiles in their order, every
 * entity by home and room with a star to add it, the hidden ones to bring
 * back, and whole kinds to leave out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
internal fun HaEntitiesScreen(repository: SettingsRepository, settings: LiveSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hub by SmartHomeHub.state.collectAsState()
    val ha = settings.watch { it.homeAssistant }
    val homes = settings.watch { it.homes() }
    LaunchedEffect(ha, homes) {
        SmartHomeHub.attach(context)
        SmartHomeHub.apply(ha, homes)
    }
    DisposableEffect(Unit) {
        SmartHomeHub.attach(context)
        SmartHomeHub.hold(SmartHomeHub.Reason.SETTINGS)
        onDispose { SmartHomeHub.release(SmartHomeHub.Reason.SETTINGS) }
    }
    val format = remember { HaStateFormat() }
    var query by remember { mutableStateOf("") }
    val entities = hub.entities
    val several = hub.homes.size > 1
    val all = remember(entities, query, ha.hiddenDomains) {
        entities.values
            .filter { HaDomains.isListed(it.domain) && !hub.registryOf(it).isHidden(it.entityId) && it.domain !in ha.hiddenDomains }
            .filter { query.isBlank() || hub.registryOf(it).nameOf(it).contains(query, ignoreCase = true) || it.entityId.contains(query, ignoreCase = true) }
            .sortedBy { hub.registryOf(it).nameOf(it) }
    }
    if (entities.isEmpty()) {
        StateBanner(stringResource(R.string.ha_entities_empty), tone = BannerTone.WARNING)
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.ha_entities_search_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )

    SettingsGroup(stringResource(R.string.ha_entities_favourites_group), info = stringResource(R.string.ha_entities_favourites_info)) {
        item {
            val favourites = ha.favourites.filter { entities.containsKey(it) }
            if (favourites.isEmpty()) {
                Text(
                    stringResource(R.string.ha_entities_favourites_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                ReorderableColumn(
                    items = favourites,
                    label = { ref -> entities[ref]?.let { hub.registryOf(it).nameOf(it) } ?: HomeRef.native(ref) },
                    onReorder = { next -> scope.launch { repository.updateHaFavourites { next } } },
                    onDelete = { ref -> scope.launch { repository.updateHaFavourites { it - ref } } },
                    keepLast = false,
                )
            }
        }
    }

    // By home, then by room, in the list's order; a lone home needs no heading of its own.
    for (home in hub.homes) {
        val registry = hub.registryOf(home.key)
        val mine = all.filter { it.home == home.key }
        if (mine.isEmpty()) continue
        val byArea = mine.groupBy { registry.areaOf(it.entityId)?.name ?: "" }
        val areaOrder = registry.orderedAreas().map { it.name }.filter { it in byArea } + listOf("").filter { it in byArea }
        for (area in areaOrder) {
            val list = byArea[area] ?: continue
            val areaName = area.ifEmpty { stringResource(R.string.ha_entities_area_other) }
            SettingsGroup(if (several && home.name.isNotBlank()) "${home.name} · $areaName" else areaName) {
                for (e in list) {
                    val ref = HomeRef.of(e)
                    item(key = ref) {
                        val favourite = ref in ha.favourites
                        val hidden = ref in ha.hidden
                        val name = registry.nameOf(e)
                        SwipeActionsRow(
                            listOf(
                                SwipeAction(
                                    Icons.Outlined.VisibilityOff,
                                    if (hidden) stringResource(R.string.ha_entities_unhide, name) else stringResource(R.string.ha_entities_hide),
                                    if (hidden) SwipeTone.ACCENT else SwipeTone.NEUTRAL,
                                ) { scope.launch { repository.updateHaHidden { if (hidden) it - ref else it + ref } } },
                            ),
                        ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                ToggleSetting(
                                    title = registry.nameOf(e),
                                    subtitle = format.state(e),
                                    checked = favourite,
                                    enabled = !hidden,
                                ) { on -> scope.launch { repository.updateHaFavourites { if (on) it + ref else it - ref } } }
                            }
                            androidx.compose.material3.IconButton(onClick = { scope.launch { repository.updateHaHidden { if (hidden) it - ref else it + ref } } }) {
                                Icon(
                                    Icons.Outlined.VisibilityOff,
                                    contentDescription = if (hidden) stringResource(R.string.ha_entities_unhide, registry.nameOf(e)) else stringResource(R.string.ha_entities_hide),
                                    tint = if (hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        }
                    }
                }
            }
        }
    }

    val domains = entities.values.map { it.domain }.filter { HaDomains.isListed(it) }.distinct().sorted()
    if (domains.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.ha_entities_domains_group), info = stringResource(R.string.ha_entities_domains_info)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    domains.forEach { domain ->
                        val off = domain in ha.hiddenDomains
                        FilterChip(
                            selected = off,
                            onClick = { scope.launch { repository.setHaHiddenDomains(if (off) ha.hiddenDomains - domain else ha.hiddenDomains + domain) } },
                            label = { Text(domain.replace('_', ' ')) },
                            leadingIcon = if (off) ({ Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                        )
                    }
                }
            }
        }
    }
}

internal const val ENTITIES_ROUTE = "homeassistant/entities"
