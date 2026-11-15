package com.wasimaster.wmkeyboard.app

import androidx.activity.compose.rememberLauncherForActivityResult
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Autorenew
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Collections
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.PhotoLibrary
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.wasimaster.wmkeyboard.core.settings.stopsBackgroundWork
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.PhotoBackgroundSettings
import com.wasimaster.wmkeyboard.core.settings.PoolEntry
import com.wasimaster.wmkeyboard.core.settings.RotationInterval
import com.wasimaster.wmkeyboard.core.settings.RotationScope
import com.wasimaster.wmkeyboard.core.settings.RotationSourceKind
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.theme.BuiltInThemes
import com.wasimaster.wmkeyboard.core.theme.findThemeFamily
import com.wasimaster.wmkeyboard.core.theme.flattenedThemes
import com.wasimaster.wmkeyboard.core.theme.replacingMember
import com.wasimaster.wmkeyboard.core.theme.reseeded
import com.wasimaster.wmkeyboard.core.theme.themeName
import com.wasimaster.wmkeyboard.core.tools.PhotoBackgroundManager
import com.wasimaster.wmkeyboard.core.tools.PhotoCache
import com.wasimaster.wmkeyboard.core.tools.PhotoRateLimit
import com.wasimaster.wmkeyboard.core.tools.PhotoTopic
import com.wasimaster.wmkeyboard.core.tools.ToolApiKeys
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/** Keys for the photo services, reached from a theme and from the picker. */
@Composable
fun PhotoServicesScreen(
    anim: AnimatedVisibilityScope? = null,
    repository: SettingsRepository,
    settings: LiveSettings,
    onBack: () -> Unit,
) {
    WmScreen(
        anim = anim,
        title = stringResource(R.string.photo_services_title),
        onBack = onBack,
        route = PHOTO_HUB_ROUTE,
        icon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
    ) {
        // Unsplash, Pexels and Commons (or another MediaWiki), all asked at
        // once for the mixed grid.
        ProvidersGroup(
            repository,
            settings,
            ProviderTool.PHOTOS,
            titleRes = R.string.photo_providers_section_title,
            info = listOf(stringResource(R.string.photo_providers_body), stringResource(R.string.photo_licence_body)).joinToString("\n\n"),
            // The old key's budget and its cached pages belong to it, not to the new one.
            onChanged = {
                PhotoRateLimit.reset()
                PhotoCache.clear()
            },
        )
        // Unsplash's and Pexels' F-Droid server addresses are on their own screens.
        HighContrastNote(settings)
    }
}

/** The background that changes on its own. Part of the theme settings. */
@Composable
fun PhotoRotationScreen(
    anim: AnimatedVisibilityScope? = null,
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // What decides which rows and groups the screen holds; each row reads its
    // own value.
    val rotateEnabled = settings.watch { it.photoBackground.rotateEnabled }
    val hasSource = settings.watch { it.photoBackground.hasSource }
    val pickingThemes = settings.watch { it.photoBackground.scope == RotationScope.SELECTED_THEMES }
    val usesNetwork = settings.watch { it.photoBackground.usesNetwork }
    val saverStops = settings.watch { it.dataSaver.photoBackgrounds.stopsBackgroundWork }
    var poolRevision by remember { mutableIntStateOf(0) }
    val pool by produceState(initialValue = emptyList<PoolEntry>(), poolRevision) {
        value = PhotoBackgroundManager.readPool(context).entries
    }
    var intervalOpen by remember { mutableStateOf(false) }
    var topicsOpen by remember { mutableStateOf(false) }
    var scopeOpen by remember { mutableStateOf(false) }
    var scopeThemesOpen by remember { mutableStateOf(false) }

    WmScreen(
        anim = anim,
        title = stringResource(R.string.photo_rotation_title),
        onBack = onBack,
        route = PHOTO_ROTATION_ROUTE,
        icon = { Icon(Icons.Outlined.Autorenew, contentDescription = null) },
    ) {
        if (!rotateEnabled) {
            // First run is one explanation and one button, rather than fifteen
            // rows for something that is not switched on.
            PhotoNotice(
                icon = Icons.Outlined.Autorenew,
                title = stringResource(R.string.photo_rotation_intro_title),
                body = stringResource(R.string.photo_rotation_intro_body),
                actionLabel = stringResource(R.string.photo_rotation_start_action),
                onAction = { scope.launch { repository.setPhotoRotateEnabled(true) } },
            )
            return@WmScreen
        }

        // The master switch, first and on its own. It used to sit under
        // "Schedule" and read "Change the background", four rows down a screen
        // whose first section is about the photo already up — which reads as an
        // action rather than as the switch that turns the feature off, so the
        // only obvious control on the screen was the one that turned it on.
        SettingsGroup {
            item {
                ToggleSetting(
                    title = R.string.photo_rotation_on_title,
                    subtitle = stringResource(R.string.photo_rotation_on_subtitle),
                    checked = rotateEnabled,
                    default = SettingsDefaults.photoBackground.rotateEnabled,
                    info = stringResource(R.string.photo_rotation_on_info),
                ) { scope.launch { repository.setPhotoRotateEnabled(it) } }
            }
        }

        SettingsGroup(stringResource(R.string.photo_rotation_now_section_title)) {
            item {
                val poolTarget = settings.watch { it.photoBackground.poolTarget }
                when {
                    pool.isEmpty() -> CaptionText(stringResource(R.string.photo_rotation_now_empty_body))
                    pool.size < poolTarget -> CaptionText(
                        stringResource(
                            R.string.photo_rotation_filling_progress,
                            pool.size,
                            poolTarget,
                        ),
                    )
                    else -> CaptionText(
                        pluralStringResource(R.plurals.photo_pool_count, pool.size, pool.size),
                    )
                }
            }
            if (!hasSource) {
                item { CaptionText(stringResource(R.string.photo_rotation_no_source_body)) }
            }
            item {
                WmRow(
                    title = stringResource(R.string.photo_rotation_shuffle_title),
                    icon = SettingsRowIcons[R.string.photo_rotation_shuffle_title],
                    subtitle = stringResource(R.string.photo_rotation_shuffle_subtitle),
                    enabled = pool.size >= 2,
                    trailing = {
                        TextButton(
                            enabled = pool.size >= 2,
                            onClick = {
                                val now = settings.value
                                scope.launch {
                                    PhotoBackgroundManager.rotate(
                                        context = context,
                                        repository = repository,
                                        themeId = now.keyboardThemeId,
                                        current = null,
                                        wantWide = now.photoBackground.landscapeOnly,
                                        sources = now.photoBackground.sources,
                                    )
                                    poolRevision++
                                }
                            },
                        ) { Text(stringResource(R.string.photo_rotation_shuffle_action)) }
                    },
                )
            }
        }

        SettingsGroup(stringResource(R.string.photo_rotation_schedule_section_title)) {
            item {
                // A dialog, not a row of chips: six choices with names this
                // long do not fit across a phone.
                NavRow(
                    title = R.string.photo_rotation_interval_title,
                    value = stringResource(settings.watch { it.photoBackground.interval }.labelRes),
                    onClick = { intervalOpen = true },
                )
            }
            item(level = SettingLevel.STANDARD) {
                NavRow(
                    title = R.string.photo_rotation_scope_title,
                    value = stringResource(settings.watch { it.photoBackground.scope }.labelRes),
                    onClick = { scopeOpen = true },
                )
            }
            // Without this the "Themes I select" choice would select nothing.
            item(visible = pickingThemes, level = SettingLevel.STANDARD) {
                val picked = settings.watch { it.photoBackground.scopeThemeIds.size }
                NavRow(
                    title = R.string.photo_rotation_scope_pick_title,
                    value = pluralStringResource(
                        R.plurals.photo_rotation_topics_value,
                        picked,
                        picked,
                    ),
                    onClick = { scopeThemesOpen = true },
                )
            }
        }

        SettingsGroup(stringResource(R.string.photo_rotation_sources_section_title)) {
            item {
                // One source for everything the user chose: a photo kept from a
                // service and a photo picked off the device are the same thing
                // to a rotation, and both live in the collection.
                SourceToggle(
                    title = R.string.photo_rotation_source_saved_title,
                    subtitle = stringResource(R.string.photo_rotation_source_saved_subtitle),
                    kind = RotationSourceKind.SAVED,
                    sources = settings.watch { it.photoBackground.sources },
                ) { scope.launch { repository.setPhotoRotateSources(it) } }
            }
            item {
                SourceToggle(
                    title = R.string.photo_rotation_source_online_title,
                    subtitle = stringResource(R.string.photo_rotation_source_online_subtitle),
                    kind = RotationSourceKind.ONLINE,
                    sources = settings.watch { it.photoBackground.sources },
                ) { scope.launch { repository.setPhotoRotateSources(it) } }
            }
        }

        if (usesNetwork) {
            SettingsGroup(
                stringResource(R.string.photo_rotation_online_section_title),
                info = stringResource(R.string.photo_rotation_subject_body),
            ) {
                item {
                    val topics = settings.watch { it.photoBackground.topics.size }
                    NavRow(
                        title = R.string.photo_rotation_topics_title,
                        subtitle = stringResource(R.string.photo_rotation_topics_subtitle),
                        value = pluralStringResource(
                            R.plurals.photo_rotation_topics_value,
                            topics,
                            topics,
                        ),
                        onClick = { topicsOpen = true },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    TextFieldSetting(
                        label = stringResource(R.string.photo_rotation_terms_label),
                        value = settings.watch { it.photoBackground.queries }.joinToString(", "),
                        hint = stringResource(R.string.photo_rotation_terms_hint),
                        default = SettingsDefaults.photoBackground.queries.joinToString(", "),
                    ) { text ->
                        repository.setPhotoRotateQueries(
                            text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }
                }
                item(level = SettingLevel.ADVANCED) {
                    ToggleSetting(
                        title = R.string.photo_rotation_wide_title,
                        subtitle = stringResource(R.string.photo_rotation_wide_subtitle),
                        checked = settings.watch { it.photoBackground.landscapeOnly },
                        default = SettingsDefaults.photoBackground.landscapeOnly,
                        info = stringResource(R.string.photo_rotation_wide_info),
                    ) { scope.launch { repository.setPhotoLandscapeOnly(it) } }
                }
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        title = R.string.photo_rotation_safe_title,
                        subtitle = stringResource(R.string.photo_rotation_safe_subtitle),
                        checked = settings.watch { it.photoBackground.safeSearch },
                        default = SettingsDefaults.photoBackground.safeSearch,
                        info = stringResource(R.string.photo_rotation_safe_info),
                    ) { scope.launch { repository.setPhotoSafeSearch(it) } }
                }
                // Data saver copies `false` over this switch on a metered
                // network without touching the stored choice, so the row says
                // so and points at the policy instead of showing a switch that
                // does nothing there.
                item(level = SettingLevel.STANDARD) {
                    ToggleSetting(
                        title = R.string.photo_rotation_metered_title,
                        subtitle = stringResource(
                            if (saverStops) R.string.photo_rotation_metered_saver_subtitle
                            else R.string.photo_rotation_metered_subtitle,
                        ),
                        checked = settings.watch { it.photoBackground.fetchOnMetered },
                        default = SettingsDefaults.photoBackground.fetchOnMetered,
                        info = stringResource(R.string.photo_rotation_metered_info),
                    ) { scope.launch { repository.setPhotoFetchOnMetered(it) } }
                }
                item(visible = saverStops, level = SettingLevel.STANDARD) {
                    NavRow(
                        title = R.string.home_datasaver_title,
                        subtitle = stringResource(R.string.photo_rotation_metered_saver_nav_subtitle),
                        route = "datasaver",
                        onClick = { onNavigate("datasaver") },
                    )
                }
                item(level = SettingLevel.STANDARD) {
                    NavRow(
                        title = R.string.photo_services_title,
                        subtitle = stringResource(R.string.photo_services_subtitle),
                        route = PHOTO_HUB_ROUTE,
                        onClick = { onNavigate(PHOTO_HUB_ROUTE) },
                    )
                }
            }
        }

        // Both of these were read by withRotation and had repository setters,
        // and no screen ever drew them: the palette rebuild was unreachable and
        // the scrim dimmed the board on every rotation with nothing to say so.
        SettingsGroup(stringResource(R.string.photo_rotation_look_section_title)) {
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.photo_rotation_seed_palette_title,
                    stringResource(R.string.photo_rotation_seed_palette_subtitle),
                    settings.watch { it.photoBackground.seedPalette },
                    info = stringResource(R.string.photo_rotation_seed_palette_info),
                    default = SettingsDefaults.photoBackground.seedPalette,
                ) { scope.launch { repository.setPhotoSeedPalette(it) } }
            }
            item(level = SettingLevel.STANDARD) {
                ToggleSetting(
                    R.string.photo_rotation_readability_title,
                    stringResource(R.string.photo_rotation_readability_subtitle),
                    settings.watch { it.photoBackground.readabilityGuard },
                    info = stringResource(R.string.photo_rotation_readability_info),
                    default = SettingsDefaults.photoBackground.readabilityGuard,
                ) { scope.launch { repository.setPhotoReadabilityGuard(it) } }
            }
            item(level = SettingLevel.ADVANCED) {
                val percentFormat = stringResource(R.string.typing_value_percent)
                SliderSetting(
                    title = R.string.photo_rotation_key_opacity_title,
                    subtitle = stringResource(R.string.photo_rotation_key_opacity_subtitle),
                    value = settings.watch { it.photoBackground.keyOpacity },
                    range = 0.2f..1f,
                    display = { percentFormat.format((it * 100).roundToInt()) },
                    info = stringResource(R.string.photo_rotation_key_opacity_info),
                    default = SettingsDefaults.photoBackground.keyOpacity,
                ) { scope.launch { repository.setPhotoKeyOpacity(it) } }
            }
        }

        SettingsGroup(stringResource(R.string.photo_rotation_storage_section_title)) {
            item(level = SettingLevel.ADVANCED) {
                SliderSetting(
                    title = R.string.photo_rotation_pool_title,
                    value = settings.watch { it.photoBackground.poolTarget }.toFloat(),
                    range = PhotoBackgroundSettings.MIN_POOL_TARGET.toFloat()..
                        PhotoBackgroundSettings.MAX_POOL_TARGET.toFloat(),
                    display = { it.toInt().toString() },
                    info = stringResource(R.string.photo_rotation_pool_info),
                    default = SettingsDefaults.photoBackground.poolTarget.toFloat(),
                ) { scope.launch { repository.setPhotoPoolTarget(it.toInt()) } }
            }
            item(level = SettingLevel.ADVANCED) {
                val mbFormat = stringResource(R.string.photo_value_megabytes)
                SliderSetting(
                    title = R.string.photo_rotation_budget_title,
                    subtitle = stringResource(R.string.photo_rotation_budget_subtitle),
                    value = settings.watch { it.photoBackground.poolBudgetMb }.toFloat(),
                    range = PhotoBackgroundSettings.POOL_BUDGET_MB_RANGE.first.toFloat()..
                        PhotoBackgroundSettings.POOL_BUDGET_MB_RANGE.last.toFloat(),
                    display = { mbFormat.format(it.roundToInt()) },
                    info = stringResource(R.string.photo_rotation_budget_info),
                    default = SettingsDefaults.photoBackground.poolBudgetMb.toFloat(),
                ) { scope.launch { repository.setPhotoPoolBudgetMb(it.roundToInt()) } }
            }
            item {
                NavRow(
                    title = R.string.photo_library_title,
                    subtitle = stringResource(R.string.photo_library_subtitle),
                    route = PHOTO_LIBRARY_ROUTE,
                    onClick = { onNavigate(photoLibraryRoute(null)) },
                )
            }
            item(level = SettingLevel.STANDARD) {
                WmRow(
                    title = stringResource(R.string.photo_rotation_delete_downloads_title),
                    icon = SettingsRowIcons[R.string.photo_rotation_delete_downloads_title],
                    subtitle = stringResource(R.string.photo_rotation_delete_downloads_subtitle),
                    trailing = {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    PhotoBackgroundManager.poolDir(context)
                                        .listFiles()
                                        ?.forEach { it.delete() }
                                }
                                poolRevision++
                            }
                        }) { Text(stringResource(CommonR.string.common_delete)) }
                    },
                )
            }
        }
        HighContrastNote(settings)
    }

    if (intervalOpen) {
        RadioPickerDialog(
            title = stringResource(R.string.photo_rotation_interval_title),
            options = RotationInterval.entries.map { it to stringResource(it.labelRes) },
            selected = settings.watch { it.photoBackground.interval },
            onDismiss = { intervalOpen = false },
        ) { picked ->
            scope.launch { repository.setPhotoRotateInterval(picked) }
            intervalOpen = false
        }
    }
    if (scopeOpen) {
        RadioPickerDialog(
            title = stringResource(R.string.photo_rotation_scope_title),
            options = RotationScope.entries.map { it to stringResource(it.labelRes) },
            selected = settings.watch { it.photoBackground.scope },
            onDismiss = { scopeOpen = false },
        ) { picked ->
            scope.launch { repository.setPhotoRotateScope(picked) }
            scopeOpen = false
        }
    }
    if (topicsOpen) {
        TopicPickerDialog(settings.watch { it.photoBackground.topics.toSet() }, onDismiss = { topicsOpen = false }) { chosen ->
            scope.launch { repository.setPhotoRotateTopics(chosen.toList()) }
            topicsOpen = false
        }
    }
    if (scopeThemesOpen) {
        CheckboxPickerDialog(
            title = stringResource(R.string.photo_rotation_scope_pick_title),
            // Built-in themes are offered as well as the user's own: the
            // rotating photo is laid over a theme as it is drawn, so a
            // built-in can carry one even though it cannot store an image.
            // Families flatten — each variant is its own scope target.
            options = (settings.watch { s -> s.customThemes.flattenedThemes().map { it.id to it.name } } +
                BuiltInThemes.flattenedThemes().map { it.id to themeName(it) })
                .sortedBy { it.second.lowercase() },
            selected = settings.watch { it.photoBackground.scopeThemeIds },
            onDismiss = { scopeThemesOpen = false },
        ) { chosen ->
            scope.launch { repository.setPhotoRotateScopeThemes(chosen) }
            scopeThemesOpen = false
        }
    }
}

/** Photos the user kept, reusable on any theme. Works with no network. */
@Composable
fun PhotoLibraryScreen(
    anim: AnimatedVisibilityScope? = null,
    repository: SettingsRepository,
    settings: LiveSettings,
    themeId: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    val entries by produceState(initialValue = emptyList<PoolEntry>(), revision) {
        value = PhotoBackgroundManager.readPool(context).entries
    }
    val bytes = remember(entries) { entries.sumOf { it.bytes } }
    val reduceMotion = LocalReduceMotion.current

    val devicePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                uris.forEach { PhotoBackgroundManager.addDevicePhoto(context, it) }
                revision++
            }
        }
    }

    WmLazyScreen(
        anim = anim,
        title = stringResource(R.string.photo_library_title),
        onBack = onBack,
        route = PHOTO_LIBRARY_ROUTE,
        subtitle = settings.watch { s ->
            themeId.takeIf { it.isNotBlank() }?.let { id ->
                s.customThemes.flattenedThemes().find { it.id == id }?.name
            }
        },
        subtitleInBar = true,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = padding.calculateTopPadding(),
                bottom = 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Two ways in, side by side. A settings row for "add a photo"
                // sat oddly above a grid of photos; buttons read as actions.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            onNavigate(photoBrowseRoute(themeId.takeIf { it.isNotBlank() }))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.photo_library_empty_action))
                    }
                    OutlinedButton(
                        onClick = {
                            devicePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.photo_add_device_title))
                    }
                }
            }
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PhotoNotice(
                        icon = Icons.Outlined.Collections,
                        title = stringResource(R.string.photo_library_empty_title),
                        body = stringResource(R.string.photo_library_empty_body),
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CaptionText(
                        stringResource(R.string.photo_storage_title) + ": " + formatPhotoSize(bytes),
                    )
                }
                items(entries, key = { it.fileName }) { entry ->
                    // A deleted photo fades out and the ones after it slide
                    // back a place, instead of the grid redrawing one short.
                    SavedPhotoTile(
                        file = File(PhotoBackgroundManager.poolDir(context), entry.fileName),
                        entry = entry,
                        modifier = gridItemMotion(reduceMotion),
                        onApply = {
                            val target = themeId.takeIf { it.isNotBlank() } ?: settings.value.keyboardThemeId
                            scope.launch {
                                repository.applyThemePhoto(
                                    themeId = target,
                                    path = File(
                                        PhotoBackgroundManager.poolDir(context),
                                        entry.fileName,
                                    ).absolutePath,
                                    credit = entry.credit,
                                    landscape = false,
                                )
                                onBack()
                            }
                        },
                        onSeed = {
                            val now = settings.value
                            val target = themeId.takeIf { it.isNotBlank() } ?: now.keyboardThemeId
                            scope.launch {
                                // The target can be a variant; the write goes
                                // back through the family that carries it.
                                now.customThemes.findThemeFamily(target)?.let { family ->
                                    repository.upsertCustomTheme(
                                        family.replacingMember(target) {
                                            it.reseeded(entry.seedColor, it.dark)
                                        },
                                    )
                                }
                            }
                        },
                        onDelete = {
                            scope.launch {
                                PhotoBackgroundManager.removeFromPool(context, entry.fileName)
                                revision++
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HighContrastNote(settings: LiveSettings) {
    // High-contrast keys drops background images entirely, so a photo picked
    // here would quietly do nothing. Saying so beats letting it look broken.
    if (settings.watch { it.accessibility.highContrast }) {
        CaptionText(stringResource(R.string.photo_high_contrast_body))
    }
}

@Composable
private fun SourceToggle(
    @StringRes title: Int,
    subtitle: String,
    kind: RotationSourceKind,
    sources: Set<RotationSourceKind>,
    onChange: (Set<RotationSourceKind>) -> Unit,
) {
    ToggleSetting(
        title = title,
        subtitle = subtitle,
        checked = kind in sources,
        default = kind in SettingsDefaults.photoBackground.sources,
    ) { on ->
        onChange(if (on) sources + kind else sources - kind)
    }
}

/** A radio list, for choices whose names are too long to sit in a row of chips. */
@Composable
private fun <T> RadioPickerDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (value, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = {
                            RadioButton(selected = value == selected, onClick = { onPick(value) })
                        },
                        colors = transparentListColors(),
                        modifier = Modifier.clickable { onPick(value) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_close)) }
        },
    )
}

@Composable
private fun TopicPickerDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    CheckboxPickerDialog(
        title = stringResource(R.string.photo_rotation_topics_title),
        options = PhotoTopic.entries.map { it.slug to stringResource(it.labelRes) },
        selected = selected,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/** A multiple-choice list. [options] is a list of value to label. */
@Composable
private fun CheckboxPickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var chosen by remember(options) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                items(options) { (value, label) ->
                    fun toggle() {
                        chosen = if (value in chosen) chosen - value else chosen + value
                    }
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = {
                            Checkbox(checked = value in chosen, onCheckedChange = { toggle() })
                        },
                        colors = transparentListColors(),
                        modifier = Modifier.clickable { toggle() },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }) {
                Text(stringResource(CommonR.string.common_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

@Composable
private fun SavedPhotoTile(
    file: File,
    entry: PoolEntry,
    onApply: () -> Unit,
    onSeed: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier) {
        AsyncImage(
            model = file,
            contentDescription = entry.credit?.photographer,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(SAVED_TILE_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .clickable { menuOpen = true },
            contentScale = ContentScale.Crop,
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.photo_apply_action)) },
                onClick = {
                    menuOpen = false
                    onApply()
                },
            )
            // The colour was measured when the photo was added, so this costs
            // nothing to offer here as well as on a photo's own page.
            if (entry.seedColor != 0L) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.photo_seed_from_collection_action)) },
                    onClick = {
                        menuOpen = false
                        onSeed()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(CommonR.string.common_delete)) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** A rough size for a storage readout, which never needs to be exact. */
internal fun formatPhotoSize(bytes: Long): String = when {
    bytes >= BYTES_PER_MB -> "${bytes / BYTES_PER_MB} MB"
    bytes >= BYTES_PER_KB -> "${bytes / BYTES_PER_KB} kB"
    else -> "$bytes B"
}

private const val SAVED_TILE_ASPECT = 3f / 2f
private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1024L * 1024
