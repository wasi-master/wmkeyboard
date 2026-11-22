package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoint
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.MyLocation
import com.wasimaster.wmkeyboard.core.location.GeoPoint
import com.wasimaster.wmkeyboard.core.location.LocationAccess
import com.wasimaster.wmkeyboard.core.location.LocationFixer
import com.wasimaster.wmkeyboard.core.location.LocationFormat
import com.wasimaster.wmkeyboard.core.location.LocationFormatter
import com.wasimaster.wmkeyboard.core.location.LocationGrant
import com.wasimaster.wmkeyboard.core.location.LocationPrecision
import com.wasimaster.wmkeyboard.core.location.LocationShare
import com.wasimaster.wmkeyboard.core.location.MapLink
import com.wasimaster.wmkeyboard.core.location.ReverseGeocoder
import com.wasimaster.wmkeyboard.core.location.SavedPlace
import com.wasimaster.wmkeyboard.core.location.SavedPlaces
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.tools.GeoPlace
import com.wasimaster.wmkeyboard.core.tools.WeatherClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.common.R as CommonR

/**
 * Tools → Location (#350): what Insert types, which map a link opens, how
 * much of the position a share gives away, the saved places, and the
 * permission.
 */
@Composable
internal fun LocationToolSettings(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val defaults = SettingsDefaults.location

    val formatOptions = LocationFormat.entries.map { it to stringResource(it.labelRes) }
    val geoLabel = stringResource(R.string.location_map_link_geo)
    val cardLabel = stringResource(R.string.location_map_link_card)
    val mapOptions = MapLink.entries.map {
        it to when (it) {
            MapLink.GEO -> geoLabel
            MapLink.CARD -> cardLabel
            else -> it.label
        }
    }
    val precisionOptions = LocationPrecision.entries.map { it to stringResource(it.labelRes) }

    SettingsGroup(stringResource(R.string.location_share_group)) {
        item {
            ChoiceSetting(
                title = R.string.location_format_title,
                subtitle = stringResource(R.string.location_format_subtitle),
                options = formatOptions,
                selected = settings.watch { it.location.format },
                default = defaults.format,
                info = stringResource(R.string.location_format_info),
            ) { scope.launch { repository.setLocationFormat(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ChoiceSetting(
                title = R.string.location_map_link_title,
                subtitle = stringResource(R.string.location_map_link_subtitle),
                options = mapOptions,
                selected = settings.watch { it.location.mapLink },
                default = defaults.mapLink,
                info = stringResource(R.string.location_map_link_info),
            ) { scope.launch { repository.setLocationMapLink(it) } }
        }
        item {
            ChoiceSetting(
                title = R.string.location_precision_title,
                subtitle = stringResource(R.string.location_precision_subtitle),
                info = stringResource(R.string.location_precision_info),
                options = precisionOptions,
                selected = settings.watch { it.location.precision },
                default = defaults.precision,
            ) { scope.launch { repository.setLocationPrecision(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.location_lookup_address_title,
                stringResource(R.string.location_lookup_address_subtitle),
                settings.watch { it.location.lookupAddress },
                info = stringResource(R.string.location_lookup_address_info),
                default = defaults.lookupAddress,
            ) { scope.launch { repository.setLocationLookupAddress(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.location_locate_on_open_title,
                stringResource(R.string.location_locate_on_open_subtitle),
                settings.watch { it.location.locateOnOpen },
                default = defaults.locateOnOpen,
                info = stringResource(R.string.location_locate_on_open_info),
            ) { scope.launch { repository.setLocationLocateOnOpen(it) } }
        }
        serverItems(repository, settings, endpoints = listOf(ServiceEndpoint.NOMINATIM), level = SettingLevel.ADVANCED)
    }

    // Map tiles come from a server, so the offline build has no use for one.
    if (!OfflineBuild.isThisBuild) SettingsGroup(
        stringResource(R.string.location_tiles_group),
        info = stringResource(R.string.location_tiles_info),
        level = SettingLevel.ADVANCED,
    ) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.location_tiles_label),
                value = settings.watch { it.location.tileUrl },
                hint = "https://tile.example.org/{z}/{x}/{y}.png",
                default = "",
            ) { repository.setLocationTileUrl(it) }
        }
    }

    val template = settings.watch { it.location.template }
    val mapLink = settings.watch { it.location.mapLink }
    SettingsGroup(
        stringResource(R.string.location_template_group),
        info = stringResource(R.string.location_template_info, LocationFormatter.TOKENS.joinToString(" ")),
        level = SettingLevel.ADVANCED,
    ) {
        item {
            TextFieldSetting(
                label = stringResource(R.string.location_template_label),
                value = template,
                hint = LocationFormatter.DEFAULT_TEMPLATE,
                default = LocationFormatter.DEFAULT_TEMPLATE,
            ) { repository.setLocationTemplate(it) }
        }
        item {
            // A fixed, well-known place, so the preview reads the same for
            // everyone and never shows where the reader actually is.
            val sample = LocationShare(
                point = GeoPoint(48.858370, 2.294481),
                address = stringResource(R.string.location_template_sample_address),
                placeName = stringResource(R.string.location_template_sample_name),
                accuracyM = 8f,
                timeText = "12:30",
            )
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.location_template_preview_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    LocationFormatter.expand(template.ifBlank { LocationFormatter.DEFAULT_TEMPLATE }, sample, mapLink),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    val places = settings.watch { it.location.places }
    val lookupNames = settings.watch { it.location.lookupAddress }
    var editing by remember { mutableStateOf<SavedPlace?>(null) }
    var adding by remember { mutableStateOf(false) }
    val emptyAddress = stringResource(R.string.location_place_no_address)
    SettingsGroup(
        stringResource(R.string.location_places_group),
        info = stringResource(R.string.location_places_info),
    ) {
        for (place in places) {
            item(key = place.id) {
                NavRow(
                    title = place.name.ifBlank { emptyAddress },
                    subtitle = place.address.ifBlank {
                        LocationFormatter.coordinates(place.point, LocationPrecision.STREET)
                    },
                    highlightKey = R.string.location_places_group,
                ) { editing = place }
            }
        }
        item {
            NavRow(
                R.string.location_add_place_title,
                stringResource(R.string.location_add_place_subtitle),
            ) { adding = true }
        }
    }

    LocationPermissionGroup()

    if (adding) {
        PlaceEditorDialog(
            title = stringResource(R.string.location_add_place_title),
            initialName = "",
            initialPoint = null,
            lookupNames = lookupNames,
            onSave = { name, point ->
                adding = false
                scope.launch {
                    repository.updateLocationPlaces { list ->
                        val label = name.ifBlank { context.getString(R.string.location_place_default_name, list.size + 1) }
                        (list + SavedPlace(SavedPlaces.newId(list), label, point)).takeLast(SavedPlaces.MAX)
                    }
                }
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { place ->
        PlaceEditorDialog(
            title = stringResource(R.string.location_edit_place_title),
            initialName = place.name,
            initialPoint = place.point,
            lookupNames = lookupNames,
            clearLabel = stringResource(R.string.location_delete_place_action),
            onClear = {
                editing = null
                scope.launch { repository.updateLocationPlaces { list -> list.filterNot { it.id == place.id } } }
            },
            onSave = { name, point ->
                editing = null
                scope.launch {
                    repository.updateLocationPlaces { list ->
                        list.map {
                            if (it.id != place.id) {
                                it
                            } else {
                                // A moved place keeps its address only when it did not move.
                                it.copy(name = name.ifBlank { it.name }, point = point, address = if (point == it.point) it.address else "")
                            }
                        }
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }
}

/**
 * The location permission as this page sees it: precise, approximate, not
 * granted, or not in this build at all. Granting goes through the shared
 * disclosure; taking it back is on Android's own page.
 */
@Composable
private fun LocationPermissionGroup() {
    val context = LocalContext.current
    val declared = remember { LocationAccess.declared(context) }
    val grant = rememberGrantValue { LocationAccess.grant(it) }
    val request = rememberDisclosedPermissionRequest(PermissionDisclosures.LOCATION) {}
    SettingsGroup(stringResource(R.string.location_permission_group)) {
        item {
            NavRow(
                R.string.location_permission_title,
                stringResource(
                    if (declared) R.string.location_permission_subtitle else R.string.location_permission_not_declared,
                ),
                value = stringResource(
                    when {
                        !declared -> R.string.location_permission_status_absent
                        grant == LocationGrant.PRECISE -> R.string.location_permission_status_precise
                        grant == LocationGrant.APPROXIMATE -> R.string.location_permission_status_approximate
                        else -> R.string.privacy_permissions_status_not_granted
                    },
                ),
            ) {
                when {
                    !declared -> Unit
                    grant == LocationGrant.PRECISE -> runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData("package:${context.packageName}".toUri()),
                        )
                    }
                    // Approximate can still be raised to precise from Android's dialog.
                    else -> request()
                }
            }
        }
    }
}

/** [rememberGrantState] for a value with more than two answers: re-read on every resume. */
@Composable
private fun <T> rememberGrantValue(read: (android.content.Context) -> T): T {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var value by remember { mutableStateOf(read(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) value = read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return value
}

/** What "Use my location" is doing right now, for the button that starts it. */
internal class UseMyLocation(val start: () -> Unit, val busy: Boolean)

/**
 * "Use my location" in the settings app: the disclosure and Android's dialog
 * if needed, then one reading, then [onResult] with the point and, when
 * [lookupNames] is on, the city's name.
 */
@Composable
internal fun rememberUseMyLocation(
    lookupNames: Boolean,
    onResult: (GeoPoint, String?) -> Unit,
): UseMyLocation {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.location_use_mine_failed_toast)

    fun read() {
        if (busy) return
        busy = true
        scope.launch {
            val fixer = LocationFixer(context)
            val fix = runCatching { fixer.lastKnown(USE_MINE_MAX_AGE_MS) }.getOrNull() ?: try {
                fixer.fixes(goodEnoughM = USE_MINE_GOOD_ENOUGH_M).lastOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (fix == null) {
                busy = false
                Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val name = if (lookupNames) {
                val city = LocationPrecision.CITY
                withContext(Dispatchers.IO) {
                    runCatching {
                        ReverseGeocoder.lookup(city.apply(fix.point), city, context.resources.configuration.locales[0].language)
                    }.getOrNull()
                }?.substringBefore(',')?.trim()
            } else {
                null
            }
            busy = false
            onResult(fix.point, name)
        }
    }

    val request = rememberDisclosedPermissionRequest(PermissionDisclosures.LOCATION) { read() }
    return UseMyLocation(
        start = {
            when {
                !LocationAccess.declared(context) -> Unit
                LocationAccess.grant(context) == LocationGrant.NONE -> request()
                !LocationAccess.servicesOn(context) -> runCatching {
                    context.startActivity(LocationAccess.servicesSettingsIntent())
                }
                else -> read()
            }
        },
        busy = busy,
    )
}

private const val USE_MINE_MAX_AGE_MS = 30 * 60_000L
private const val USE_MINE_GOOD_ENOUGH_M = 500f

/**
 * A place, by search, by "Use my location", or by typed coordinates. One
 * dialog for the weather location and the Location tool's saved places, so
 * they are found the same way.
 */
@Composable
internal fun PlaceEditorDialog(
    title: String,
    initialName: String,
    initialPoint: GeoPoint?,
    lookupNames: Boolean,
    onSave: (name: String, point: GeoPoint) -> Unit,
    onDismiss: () -> Unit,
    clearLabel: String? = null,
    onClear: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var place by remember { mutableStateOf(initialName) }
    var lat by remember { mutableStateOf(initialPoint?.latitude?.toString().orEmpty()) }
    var lon by remember { mutableStateOf(initialPoint?.longitude?.toString().orEmpty()) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchFailed by remember { mutableStateOf(false) }
    val parsedLat = lat.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val parsedLon = lon.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val declared = remember { LocationAccess.declared(context) }
    val useMine = rememberUseMyLocation(lookupNames) { point, name ->
        lat = point.latitude.toString()
        lon = point.longitude.toString()
        if (place.isBlank() && name != null) place = name
    }

    fun search() {
        if (query.isBlank() || searching) return
        searching = true
        searchFailed = false
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { WeatherClient.geocode(query) }.getOrNull()
            }
            searching = false
            if (found == null) {
                searchFailed = true
            } else {
                results = found
                searchFailed = found.isEmpty()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Typing moves the dialog around the keyboard, so a tap can land on
        // the scrim where it just was and throw the half-typed place away.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            val unknownRegion = stringResource(R.string.privacy_weather_region_unknown)
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (declared) {
                    OutlinedButton(
                        onClick = useMine.start,
                        enabled = !useMine.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (useMine.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.location_use_mine_action))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.privacy_weather_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { search() }, enabled = query.isNotBlank() && !searching) {
                        Text(if (searching) "…" else stringResource(CommonR.string.common_search))
                    }
                }
                if (searchFailed) {
                    Text(
                        if (results.isEmpty() && !searching) {
                            stringResource(R.string.privacy_weather_no_matches_error)
                        } else {
                            stringResource(R.string.privacy_weather_search_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                for (result in results) {
                    ListItem(
                        headlineContent = { Text(result.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.privacy_weather_result_summary,
                                    result.region.ifBlank { unknownRegion },
                                    result.latitude,
                                    result.longitude,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                place = result.name
                                lat = result.latitude.toString()
                                lon = result.longitude.toString()
                                results = emptyList()
                            },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.privacy_weather_manual_info),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it },
                    label = { Text(stringResource(R.string.privacy_weather_name_hint)) },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text(stringResource(R.string.privacy_weather_latitude_hint)) },
                    singleLine = true,
                    isError = lat.isNotBlank() && parsedLat == null,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text(stringResource(R.string.privacy_weather_longitude_hint)) },
                    singleLine = true,
                    isError = lon.isNotBlank() && parsedLon == null,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsedLat != null && parsedLon != null,
                onClick = {
                    if (parsedLat != null && parsedLon != null) onSave(place.trim(), GeoPoint(parsedLat, parsedLon))
                },
            ) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (onClear != null) {
                    TextButton(onClick = onClear) {
                        Text(clearLabel ?: stringResource(CommonR.string.common_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        },
    )
}
