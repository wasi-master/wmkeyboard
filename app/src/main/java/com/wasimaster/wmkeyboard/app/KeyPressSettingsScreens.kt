package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Check
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.settings.AlternateGroup
import com.wasimaster.wmkeyboard.core.settings.SettingsDefaults
import com.wasimaster.wmkeyboard.core.settings.SwiftKeyHoldSymbols
import com.wasimaster.wmkeyboard.core.settings.BackspaceSwipeUnit
import com.wasimaster.wmkeyboard.core.settings.TopRowHolds
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.feedback.R as FeedbackR
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.feedback.HapticPlayer
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.settings.ShiftCapsLockMsRange
import com.wasimaster.wmkeyboard.core.settings.DefaultCurrencyKeys
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.settings.HapticStyle
import com.wasimaster.wmkeyboard.core.settings.KeySoundOutput
import com.wasimaster.wmkeyboard.core.settings.KeySoundStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.settings.DEFAULT_LONG_PRESS_LETTERS
import com.wasimaster.wmkeyboard.core.layout.resolveLayoutName
import com.wasimaster.wmkeyboard.core.settings.KeySoundSettings
import com.wasimaster.wmkeyboard.core.settings.LongPressLetterActions
import com.wasimaster.wmkeyboard.core.settings.ACTION_POSITION_LAST
import com.wasimaster.wmkeyboard.core.settings.ACTION_POSITION_MAX
import com.wasimaster.wmkeyboard.core.theme.popupOnKeyOrNull
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlin.math.roundToInt
import com.wasimaster.wmkeyboard.core.feedback.SoundFile
import com.wasimaster.wmkeyboard.core.feedback.SoundImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.InstalledSoundPack
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import kotlinx.coroutines.launch

// ---- key press ----

/**
 * Key-press sound controls, shared by the Key press settings screen and the
 * sound & haptics tool's detail page. Changes preview immediately through
 * [KeySoundPlayer].
 */
@Composable
internal fun KeySoundGroup(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Slider readouts are plain lambdas, so their format strings are resolved
    // here and captured. The format also puts the number through the locale,
    // which is what gives Bengali or Arabic digits.
    val percentFormat = stringResource(R.string.typing_value_percent)
    // Which rows the group holds depends on the style, so it is read here; the
    // rows read their own values below.
    val soundStyle = settings.watch { it.sound.style }
    val soundOn = settings.watch { it.sound.enabled }
    SettingsGroup(stringResource(R.string.hardware_sound_group_title)) {
        item {
            ToggleSetting(
                R.string.hardware_sound_key_title,
                stringResource(R.string.hardware_sound_key_subtitle),
                settings.watch { it.sound.enabled },
                default = SettingsDefaults.sound.enabled,
            ) {
                scope.launch { repository.setKeySound(it) }
                if (it) {
                    val sound = settings.value.sound
                    KeySoundPlayer.preview(context, sound.style, sound.volume, sound.previewId())
                }
            }
        }
        item(visible = soundOn) {
            ChoiceSetting(
                R.string.hardware_sound_output_title,
                subtitle = stringResource(R.string.hardware_sound_output_subtitle),
                options = listOf(
                    KeySoundOutput.ANYWHERE to stringResource(R.string.hardware_sound_output_anywhere),
                    KeySoundOutput.HEADPHONES to stringResource(R.string.hardware_sound_output_headphones),
                    KeySoundOutput.SPEAKER to stringResource(R.string.hardware_sound_output_speaker),
                ),
                selected = settings.watch { it.sound.output },
                default = SettingsDefaults.sound.output,
            ) { scope.launch { repository.setKeySoundOutput(it) } }
        }
        item {
            // A ControlSetting rather than a ChoiceSetting: each chip acts the
            // moment it is tapped (it previews the sound, and Custom and Pack
            // pick a sound as well as a style). The row keys its highlight on
            // its own string resource — this is where the Sound addon's Use
            // button lands — so the match holds in every language.
            ControlSetting(
                R.string.hardware_sound_style_title,
                info = stringResource(R.string.hardware_sound_style_info),
            ) {
                // Custom is a segment like any other, so the styles read as one
                // choice rather than five here and a sixth hidden in a list. It
                // names a file rather than a fixed waveform, so it needs a sound
                // installed before it can be picked — the list below is where that
                // sound is chosen and where the "install one" note lives.
                val soundStore = remember { SoundStore.get(context) }
                val soundRevision by soundStore.revision.collectAsStateWithLifecycle()
                val installedSounds = remember(soundRevision) { soundStore.sounds() }
                // Sound pack is the same shape of chip and needs the same
                // fallback: the pack list below only appears once the style is
                // Pack, so a chip that switched the style without choosing a
                // pack left every key on the system click until the user
                // guessed that a second tap was owed.
                val packStore = remember { SoundPackStore.get(context) }
                val packRevision by packStore.revision.collectAsStateWithLifecycle()
                val installedPacks = remember(packRevision) { packStore.packs() }
                val selectedStyle = settings.watch { it.sound.style }
                // Chips rather than a segmented row. Six equal segments across a
                // phone leave ~55dp of label each, which truncated "Chime" to
                // "Chim" and "Custom" to "Custo"; a segmented row set to scroll is
                // worse still, since SegmentedButton has a wide minimum and only
                // three and a half fit. Chips size to their own text, so every
                // style keeps its real name, and the row scrolls only as far as it
                // has to. Same control the addon type filter uses.
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in KeySoundStyle.entries) {
                        FilterChip(
                            selected = selectedStyle == style,
                            onClick = {
                                val sound = settings.value.sound
                                scope.launch {
                                    when (style) {
                                        KeySoundStyle.CUSTOM -> {
                                            // Falls back to the first installed
                                            // sound when none has been chosen yet,
                                            // so the chip always makes a sound.
                                            // With nothing installed it still
                                            // selects — the section it reveals is
                                            // where a sound is imported, so a
                                            // disabled chip would hide its own
                                            // remedy.
                                            val id = sound.customId
                                                .takeIf { id -> installedSounds.any { it.id == id } }
                                                ?: installedSounds.firstOrNull()?.id
                                            if (id == null) {
                                                repository.setKeySoundStyle(style)
                                            } else {
                                                repository.setKeySoundCustomId(id)
                                                KeySoundPlayer.preview(
                                                    context, style, sound.volume, id,
                                                )
                                            }
                                        }
                                        KeySoundStyle.PACK -> {
                                            // Same, for packs. The whole keystroke
                                            // previews, since a pack that recorded
                                            // the key coming back up is only half
                                            // itself on the way down.
                                            val id = sound.packId
                                                .takeIf { id -> installedPacks.any { it.id == id } }
                                                ?: installedPacks.firstOrNull()?.id
                                            if (id == null) {
                                                repository.setKeySoundStyle(style)
                                            } else {
                                                repository.setKeySoundPackId(id)
                                                KeySoundPlayer.previewStroke(
                                                    context, style, sound.volume, id,
                                                )
                                            }
                                        }
                                        else -> {
                                            repository.setKeySoundStyle(style)
                                            // Sound the freshly picked style so the
                                            // user hears the choice immediately.
                                            KeySoundPlayer.preview(
                                                context, style, sound.volume,
                                            )
                                        }
                                    }
                                }
                            },
                            label = {
                                Text(
                                    stringResource(
                                        when (style) {
                                            KeySoundStyle.CLICK ->
                                                R.string.hardware_sound_style_click_label
                                            KeySoundStyle.STANDARD ->
                                                R.string.hardware_sound_style_standard_label
                                            KeySoundStyle.POP ->
                                                R.string.hardware_sound_style_pop_label
                                            KeySoundStyle.THOCK ->
                                                R.string.hardware_sound_style_thock_label
                                            KeySoundStyle.CHIME ->
                                                R.string.hardware_sound_style_chime_label
                                            KeySoundStyle.CUSTOM -> CommonR.string.common_custom
                                            KeySoundStyle.PACK ->
                                                R.string.hardware_sound_pack_style_label
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
        // Only under Custom. The sound library and its import button are what
        // Custom *means*; showing them under Click is offering a choice that
        // has no effect until the style changes too.
        if (soundStyle == KeySoundStyle.CUSTOM) {
            item { InstalledSoundSection(repository, settings, onNavigate) }
        }
        if (soundStyle == KeySoundStyle.PACK) {
            item { InstalledSoundPackSection(repository, settings, onNavigate) }
            item { KeyReleaseSoundToggle(repository, settings) }
        }
        item {
            SliderSetting(
                R.string.hardware_sound_volume_title,
                subtitle = stringResource(R.string.hardware_sound_volume_subtitle),
                value = settings.watch { it.sound.volume },
                range = 0.05f..1f,
                display = { percentFormat.format((it * 100).roundToInt()) },
                default = SettingsDefaults.sound.volume,
                // Debounced inside the player, so dragging previews smoothly.
                // The store waits for the finger to lift; the sound does not.
                preview = {
                    val sound = settings.value.sound
                    KeySoundPlayer.preview(context, sound.style, it, sound.previewId())
                },
            ) {
                scope.launch { repository.setKeySoundVolume(it) }
            }
        }
    }
}

/**
 * The sound a preview of these settings should play. Custom and Pack read their
 * id from different fields, and a preview that passes neither plays the system
 * click instead of the sound being previewed — which reads as the chosen sound
 * not working at all.
 */
private fun KeySoundSettings.previewId(): String = when (style) {
    KeySoundStyle.PACK -> packId
    else -> customId
}

/**
 * The installed key sounds — whatever came from an addon repository, plus
 * anything imported here — and the import button.
 *
 * Picking one also switches the style to [KeySoundStyle.CUSTOM]; choosing a
 * sound and then finding the keyboard still clicking would be baffling.
 */
@Composable
private fun InstalledSoundSection(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val sounds = remember(revision) { store.sounds() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundFile.import(it, store, name = fontFileLabel(context, uri))
                    }
                }.getOrElse {
                    SoundImportResult.Failed(FeedbackR.string.core_feedback_sound_import_read_error)
                }
            }
            when (result) {
                is SoundImportResult.Imported -> {
                    repository.setKeySoundCustomId(result.sound.id)
                    KeySoundPlayer.preview(
                        context, KeySoundStyle.CUSTOM, settings.value.sound.volume, result.sound.id,
                    )
                }
                is SoundImportResult.NotASound -> message = context.getString(result.messageRes)
                SoundImportResult.TooManySounds ->
                    message = context.resources.getQuantityString(
                        R.plurals.hardware_sound_limit_error,
                        SoundStore.MAX_SOUNDS,
                        SoundStore.MAX_SOUNDS,
                    )
                // The refusal carries at most one argument, and "" means none.
                is SoundImportResult.Failed -> message = if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val selectedId = settings.watch { s -> s.sound.customId.takeIf { s.sound.style == KeySoundStyle.CUSTOM } }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (sounds.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_empty))
        }
        for (sound in sounds) key(sound.id) {
            val selected = selectedId == sound.id
            val deleteSound = {
                scope.launch {
                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                    withContext(Dispatchers.IO) { store.delete(sound.id) }
                    KeySoundPlayer.forgetCustom(sound.id)
                }
                Unit
            }
            HighlightableItem(sound.id) {
                WmRow(
                    title = sound.name,
                    supporting = sound.author.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                    swipeActions = listOf(
                        deleteSwipeAction(
                            stringResource(R.string.hardware_sound_delete_desc, sound.name),
                            deleteSound,
                        ),
                    ),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(sound.id) }
                                    // The pool keeps its decoded copy independently
                                    // of the file, so it has to be told too.
                                    KeySoundPlayer.forgetCustom(sound.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        sound.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundCustomId(sound.id) }
                        KeySoundPlayer.preview(
                            context, KeySoundStyle.CUSTOM, settings.value.sound.volume, sound.id,
                        )
                    },
                )
            }
        }
        // Beside the file importer: the store is the other way to get a sound,
        // and it is the one that works when the user has no file to import.
        AddonStoreRow(AddonType.Sound, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_import_action)) }
    }
}
/**
 * What importing a sound pack came to, in words.
 *
 * Shared with the open-a-file dialog, which reports every outcome including the
 * good one; the section below stays quiet on success and plays the pack instead.
 */
internal fun describeSoundPackImport(context: Context, result: SoundPackImportResult): String = when (result) {
    is SoundPackImportResult.Imported -> context.resources.getQuantityString(
        R.plurals.import_sound_pack_done,
        result.pack.sampleCount,
        result.pack.name,
        result.pack.sampleCount,
    )

    SoundPackImportResult.NotASoundPack ->
        context.getString(R.string.hardware_sound_pack_not_a_pack_error)

    SoundPackImportResult.TooManyPacks -> context.resources.getQuantityString(
        R.plurals.hardware_sound_pack_limit_error,
        SoundPackStore.MAX_PACKS,
        SoundPackStore.MAX_PACKS,
    )

    // The refusal carries at most one argument, and "" means none.
    is SoundPackImportResult.Rejected -> if (result.messageArg.isEmpty()) {
        context.getString(result.messageRes)
    } else {
        context.getString(result.messageRes, result.messageArg)
    }

    SoundPackImportResult.Failed -> context.getString(R.string.hardware_sound_pack_read_error)
}

/**
 * The installed sound packs, and the import button.
 *
 * A pack differs from a single sound in the one way worth showing on the row:
 * how many recordings it holds, and which key roles it recorded separately.
 * Tapping one plays a variant, so tapping twice actually demonstrates the
 * variation rather than repeating itself.
 *
 * There is no file-picker mime type worth narrowing to — a `.wmsoundpack` is a
 * ZIP, and providers report a custom extension as `application/octet-stream` as
 * often as not — so the importer's own list is used and the real check is the
 * manifest inside.
 */
@Composable
private fun InstalledSoundPackSection(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packs = remember(revision) { store.packs() }
    var message by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundPackFile.import(it, store, fallbackName = fontFileLabel(context, uri))
                    }
                }.getOrElse { SoundPackImportResult.Failed }
            }
            if (result is SoundPackImportResult.Imported) {
                repository.setKeySoundPackId(result.pack.id)
                // Nothing is said on success here: the pack that was just
                // selected plays itself, which says it better.
                KeySoundPlayer.previewStroke(
                    context, KeySoundStyle.PACK, settings.value.sound.volume, result.pack.id,
                )
            } else {
                message = describeSoundPackImport(context, result)
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    val selectedId = settings.watch { s -> s.sound.packId.takeIf { s.sound.style == KeySoundStyle.PACK } }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        if (packs.isEmpty()) {
            CaptionText(stringResource(R.string.hardware_sound_pack_empty))
        }
        for (pack in packs) key(pack.id) {
            val selected = selectedId == pack.id
            val deletePack = {
                scope.launch {
                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                    withContext(Dispatchers.IO) { store.delete(pack.id) }
                    KeySoundPlayer.forgetPack(pack.id)
                }
                Unit
            }
            HighlightableItem(pack.id) {
                WmRow(
                    title = pack.name,
                    supporting = {
                        val roles = pack.roles
                        val variants = pluralStringResource(
                            R.plurals.hardware_sound_pack_variants,
                            pack.variantCount,
                            pack.variantCount,
                        )
                        val withRoles = if (roles.isEmpty()) {
                            variants
                        } else {
                            stringResource(
                                R.string.hardware_sound_pack_variants_and_roles,
                                variants,
                                roles.joinToString(", "),
                            )
                        }
                        // How many keys the pack recorded individually, which
                        // is the one thing that tells a voice pack apart from a
                        // switch pack at a glance.
                        val counts = if (pack.keyCount > 0) {
                            pluralStringResource(
                                R.plurals.hardware_sound_pack_with_keys,
                                pack.keyCount,
                                withRoles,
                                pack.keyCount,
                            )
                        } else {
                            withRoles
                        }
                        // Appended rather than given its own line: it is one
                        // more fact about the pack, and the row already has a
                        // subtitle that reads as a list.
                        Text(
                            if (pack.hasRelease == true) {
                                stringResource(
                                    R.string.hardware_sound_pack_with_release,
                                    counts,
                                )
                            } else {
                                counts
                            },
                        )
                    },
                    swipeActions = listOf(
                        deleteSwipeAction(
                            stringResource(R.string.hardware_sound_delete_desc, pack.name),
                            deletePack,
                        ),
                    ),
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_selected_desc,
                                    ),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    if (selected) repository.setKeySoundStyle(KeySoundStyle.CLICK)
                                    withContext(Dispatchers.IO) { store.delete(pack.id) }
                                    // The pool keeps its decoded samples
                                    // independently of the files.
                                    KeySoundPlayer.forgetPack(pack.id)
                                }
                            }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(
                                        R.string.hardware_sound_delete_desc,
                                        pack.name,
                                    ),
                                )
                            }
                        }
                    },
                    onClick = {
                        scope.launch { repository.setKeySoundPackId(pack.id) }
                        // The whole keystroke, not just the way down: a pack
                        // that recorded the switch returning is being judged on
                        // both halves, and half of it is a different pack.
                        KeySoundPlayer.previewStroke(
                            context, KeySoundStyle.PACK, settings.value.sound.volume, pack.id,
                        )
                    },
                )
            }
        }
        PerLayoutSoundPackSection(repository, settings, packs)
        AddonStoreRow(AddonType.SoundPack, onNavigate)
        OutlinedButton(
            onClick = { importLauncher.launch(SoundPackFile.IMPORT_MIME_TYPES) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_pack_import_action)) }
    }
}

/**
 * "A pack per layout": one row per enabled layout, naming the pack it plays
 * (issue #520).
 *
 * Drawn only when there is a choice to make — two layouts and at least one
 * installed pack. For everyone else it is a list of rows that can only say
 * "same as above", which is a worse way of saying nothing.
 *
 * A layout with no override is not stored, so this section is a list of
 * exceptions rather than a second copy of the selection above: the pack picked
 * on the list above keeps meaning "everywhere I did not say otherwise".
 */
@Composable
private fun PerLayoutSoundPackSection(
    repository: SettingsRepository,
    settings: LiveSettings,
    packs: List<InstalledSoundPack>,
) {
    val layoutIds = settings.watch { it.enabledLayoutIds }
    if (packs.isEmpty() || layoutIds.size < 2) return

    val scope = rememberCoroutineScope()
    val customLayouts = settings.watch { it.customLayouts }
    val overrides = settings.watch { it.sound.packByLayout }
    // The layout whose pack is being picked; null when no dialog is up.
    var picking by remember { mutableStateOf<String?>(null) }

    CaptionText(stringResource(R.string.hardware_sound_pack_per_layout_title))
    CaptionText(stringResource(R.string.hardware_sound_pack_per_layout_caption))
    for (layoutId in layoutIds) {
        val chosen = overrides[layoutId]?.let { id -> packs.firstOrNull { it.id == id } }
        WmRow(
            title = resolveLayoutName(customLayouts, layoutId),
            subtitle = chosen?.name
                ?: stringResource(R.string.hardware_sound_pack_per_layout_default),
            onClick = { picking = layoutId },
        )
    }
    if (overrides.isNotEmpty()) {
        TextButton(
            onClick = { scope.launch { repository.clearKeySoundPacksByLayout() } },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(R.string.hardware_sound_pack_per_layout_clear)) }
    }

    picking?.let { layoutId ->
        val current = overrides[layoutId].orEmpty()
        AlertDialog(
            onDismissRequest = { picking = null },
            title = {
                Text(
                    stringResource(
                        R.string.hardware_sound_pack_per_layout_pick,
                        resolveLayoutName(customLayouts, layoutId),
                    ),
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // "Follow the global pick" first, because it is the state
                    // every layout starts in and the one a user who opened this
                    // by mistake is looking for.
                    SoundPackChoiceRow(
                        label = stringResource(R.string.hardware_sound_pack_per_layout_default),
                        selected = current.isEmpty(),
                    ) {
                        scope.launch { repository.setKeySoundPackForLayout(layoutId, "") }
                        picking = null
                    }
                    for (pack in packs) {
                        SoundPackChoiceRow(label = pack.name, selected = pack.id == current) {
                            scope.launch { repository.setKeySoundPackForLayout(layoutId, pack.id) }
                            picking = null
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = null }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
}

/** One line of the per-layout pack dialog. */
@Composable
private fun SoundPackChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    WmRow(
        title = label,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.hardware_sound_selected_desc),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        onClick = onClick,
    )
}

/**
 * "Play the key coming back up", for packs that recorded it.
 *
 * Draws nothing at all when the selected pack has no key-up recordings, which
 * is most of them: a switch the user can flip and hear no difference from is
 * worse than an absent one — it reads as the feature being broken rather than
 * as the pack not having it. The toggle appearing *is* how a pack announces it
 * has both halves.
 */
@Composable
private fun KeyReleaseSoundToggle(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val store = remember { SoundPackStore.get(context) }
    val revision by store.revision.collectAsStateWithLifecycle()
    val packId = settings.watch { it.sound.packId }
    val hasRelease = remember(revision, packId) {
        store.resolve(packId)?.let { store.pack(it)?.hasRelease } == true
    }
    if (!hasRelease) return
    ToggleSetting(
        R.string.hardware_sound_pack_release_title,
        stringResource(R.string.hardware_sound_pack_release_subtitle),
        settings.watch { it.sound.playRelease },
        default = SettingsDefaults.sound.playRelease,
    ) { on ->
        scope.launch { repository.setKeySoundPlayRelease(on) }
        // Turning it on previews the whole keystroke, which is the only way to
        // hear what the switch just bought; turning it off previews the press
        // alone, so the difference is the thing demonstrated either way.
        val volume = settings.value.sound.volume
        if (on) {
            KeySoundPlayer.previewStroke(context, KeySoundStyle.PACK, volume, packId)
        } else {
            KeySoundPlayer.preview(context, KeySoundStyle.PACK, volume, packId)
        }
    }
}
@Composable
internal fun KeyPressSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // What decides which rows the groups hold; each row reads its own value.
    val swipeDelete = settings.watch { it.backspaceSwipeDelete }
    val swipeUnit = settings.watch { it.textEditing.backspaceSwipeUnit }
    val holdDeletesWords = settings.watch { it.textEditing.deleteHoldDeletesWords }
    SettingsGroup {
        item {
            NavRow(
                R.string.keypress_haptics_page_title,
                stringResource(R.string.keypress_haptics_group_subtitle),
                route = "keypress/haptics",
            ) {
                onNavigate("keypress/haptics")
            }
        }
        item {
            NavRow(
                R.string.keypress_popup_group_title,
                stringResource(R.string.keypress_popup_group_subtitle),
                route = "keypress/popup",
            ) {
                onNavigate("keypress/popup")
            }
        }
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.keypress_shortcuts_group_title,
                stringResource(R.string.keypress_shortcuts_group_subtitle),
                route = "keypress/shortcuts",
            ) {
                onNavigate("keypress/shortcuts")
            }
        }
    }



    // Moved here from Typing (#136): a swipe on the backspace key is a thing a
    // key does, and this is the screen for what keys do. The string names
    // keep their typing_ prefix so nothing that links to them by name breaks.
    SettingsGroup(stringResource(R.string.typing_group_backspace_title)) {
        item {
            ToggleSetting(
                R.string.typing_backspace_swipe_title,
                stringResource(R.string.typing_backspace_swipe_subtitle),
                swipeDelete,
                info = stringResource(R.string.typing_backspace_swipe_info),
                default = SettingsDefaults.backspaceSwipeDelete,
            ) { scope.launch { repository.setBackspaceSwipeDelete(it) } }
        }
        if (swipeDelete) {
            item(level = SettingLevel.ADVANCED) {
                ChoiceSetting(
                    R.string.typing_backspace_unit_title,
                    subtitle = stringResource(R.string.typing_backspace_unit_subtitle),
                    info = stringResource(R.string.typing_backspace_unit_info),
                    options = listOf(
                        BackspaceSwipeUnit.WORD to
                            stringResource(R.string.typing_backspace_unit_word),
                        BackspaceSwipeUnit.CHARACTER to
                            stringResource(R.string.typing_backspace_unit_character),
                    ),
                    selected = swipeUnit,
                    default = SettingsDefaults.textEditing.backspaceSwipeUnit,
                    detail = { unit ->
                        ChoiceDetail(
                            stringResource(
                                if (unit == BackspaceSwipeUnit.WORD) {
                                    R.string.typing_backspace_unit_word_desc
                                } else {
                                    R.string.typing_backspace_unit_character_desc
                                },
                            ),
                        )
                    },
                ) { scope.launch { repository.setBackspaceSwipeUnit(it) } }
            }
            item(level = SettingLevel.ADVANCED) {
                ToggleSetting(
                    R.string.typing_backspace_preview_title,
                    stringResource(R.string.typing_backspace_preview_subtitle),
                    settings.watch { it.textEditing.backspaceSwipePreview },
                    info = stringResource(R.string.typing_backspace_preview_info),
                    default = SettingsDefaults.textEditing.backspaceSwipePreview,
                ) { scope.launch { repository.setBackspaceSwipePreview(it) } }
            }
            if (swipeUnit == BackspaceSwipeUnit.WORD) {
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.typing_backspace_step_title,
                        subtitle = stringResource(R.string.typing_backspace_step_subtitle),
                        value = settings.watch { it.textEditing.backspaceWordStepDp }.toFloat(),
                        range = 32f..120f,
                        display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                        info = stringResource(R.string.typing_backspace_step_info),
                        default = SettingsDefaults.textEditing.backspaceWordStepDp.toFloat(),
                    ) { scope.launch { repository.setBackspaceWordStepDp(it.toInt()) } }
                }
            } else {
                item(level = SettingLevel.ADVANCED) {
                    SliderSetting(
                        R.string.typing_backspace_char_step_title,
                        subtitle = stringResource(R.string.typing_backspace_char_step_subtitle),
                        value = settings.watch { it.textEditing.backspaceCharStepDp }.toFloat(),
                        range = 8f..48f,
                        display = { context.getString(R.string.typing_value_dp, it.toInt()) },
                        info = stringResource(R.string.typing_backspace_char_step_info),
                        default = SettingsDefaults.textEditing.backspaceCharStepDp.toFloat(),
                    ) { scope.launch { repository.setBackspaceCharStepDp(it.toInt()) } }
                }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.typing_backspace_up_clears_title,
                stringResource(R.string.typing_backspace_up_clears_subtitle),
                settings.watch { it.textEditing.backspaceSwipeUpClears },
                info = stringResource(R.string.typing_backspace_up_clears_info),
                default = SettingsDefaults.textEditing.backspaceSwipeUpClears,
            ) { scope.launch { repository.setBackspaceSwipeUpClears(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.typing_forward_delete_swipe_title,
                stringResource(R.string.typing_forward_delete_swipe_subtitle),
                settings.watch { it.textEditing.forwardDeleteSwipe },
                info = stringResource(R.string.typing_forward_delete_swipe_info),
                default = SettingsDefaults.textEditing.forwardDeleteSwipe,
            ) { scope.launch { repository.setForwardDeleteSwipe(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.typing_web_backspace_key_title,
                stringResource(R.string.typing_web_backspace_key_subtitle),
                settings.watch { it.textEditing.webBackspaceKeyEvents },
                info = stringResource(R.string.typing_web_backspace_key_info),
                default = SettingsDefaults.textEditing.webBackspaceKeyEvents,
            ) { scope.launch { repository.setWebBackspaceKeyEvents(it) } }
        }
    }

    // The alternates ("more keys") get their own group rather than joining the
    // bubble's: they are a different popup, sized against a grid instead of a
    // fixed box, and sharing the bubble's font slider was what kept them small
    // (issue #64).
    SettingsGroup(stringResource(R.string.keypress_alternates_group_title), level = SettingLevel.ADVANCED) {
        item {
            SliderSetting(
                R.string.keypress_alternates_size_title,
                subtitle = stringResource(R.string.keypress_alternates_size_subtitle),
                value = settings.watch { it.popup.alternatesFontScale },
                range = 0.7f..3.2f,
                display = { context.getString(R.string.keypress_value_multiplier, it) },
                info = stringResource(R.string.keypress_alternates_size_info),
                default = SettingsDefaults.popup.alternatesFontScale,
            ) { scope.launch { repository.setAlternatesFontScale(it) } }
        }
        item {
            SliderSetting(
                R.string.keypress_alternates_padding_title,
                subtitle = stringResource(R.string.keypress_alternates_padding_subtitle),
                value = settings.watch { it.popup.alternatesPaddingDp }.toFloat(),
                range = 0f..32f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_alternates_padding_info),
                default = SettingsDefaults.popup.alternatesPaddingDp.toFloat(),
            ) { scope.launch { repository.setAlternatesPaddingDp(it.toInt()) } }
        }
        item {
            StepperSetting(
                R.string.keypress_alternates_columns_title,
                subtitle = stringResource(R.string.keypress_alternates_columns_subtitle),
                value = settings.watch { it.popup.alternatesColumns },
                range = AlternatesColumnsSteps,
                // 0 is not a column count but the automatic wrap, so it steps in
                // from 3 rather than 1 and reads as a word instead of a number.
                display = {
                    if (it == 0) {
                        context.getString(R.string.keypress_alternates_columns_auto)
                    } else {
                        it.toString()
                    }
                },
                info = stringResource(R.string.keypress_alternates_columns_info),
                default = SettingsDefaults.popup.alternatesColumns,
            ) { scope.launch { repository.setAlternatesColumns(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_alternates_nearest_title,
                stringResource(R.string.keypress_alternates_nearest_subtitle),
                settings.watch { it.popup.alternatesNearestFirst },
                info = stringResource(R.string.keypress_alternates_nearest_info),
                default = SettingsDefaults.popup.alternatesNearestFirst,
            ) { scope.launch { repository.setAlternatesNearestFirst(it) } }
        }
        item {
            ToggleSetting(
                R.string.keypress_alternates_centred_title,
                stringResource(R.string.keypress_alternates_centred_subtitle),
                settings.watch { it.popup.alternatesCentred },
                info = stringResource(R.string.keypress_alternates_centred_info),
                default = SettingsDefaults.popup.alternatesCentred,
            ) { scope.launch { repository.setAlternatesCentred(it) } }
        }
        item {
            // Issue #385: which group a letter's popup leads with.
            ControlSetting(
                R.string.keypress_alternates_order_title,
                subtitle = stringResource(R.string.keypress_alternates_order_subtitle),
                info = stringResource(R.string.keypress_alternates_order_info),
            ) {
                val names = mapOf(
                    AlternateGroup.LAYOUT to stringResource(R.string.keypress_alternates_group_layout),
                    AlternateGroup.ACCENTS to stringResource(R.string.keypress_alternates_group_accents),
                    AlternateGroup.SHIFTED to stringResource(R.string.keypress_alternates_group_shifted),
                )
                ReorderableColumn(
                    items = settings.watch { it.popup.alternatesOrder },
                    label = { names[it].orEmpty() },
                    onReorder = { scope.launch { repository.setAlternatesOrder(it) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            ToggleSetting(
                R.string.keypress_alternates_hold_title,
                stringResource(R.string.keypress_alternates_hold_subtitle),
                settings.watch { it.popup.alternatesHoldToSelect },
                info = stringResource(R.string.keypress_alternates_hold_info),
                default = SettingsDefaults.popup.alternatesHoldToSelect,
            ) { scope.launch { repository.setAlternatesHoldToSelect(it) } }
        }
    }

    SettingsGroup(stringResource(R.string.keypress_timing_group_title)) {
        item {
            SliderSetting(
                R.string.keypress_long_press_delay_title,
                subtitle = stringResource(R.string.keypress_long_press_delay_subtitle),
                value = settings.watch { it.longPressDelayMs }.toFloat(),
                // To 3 s: a screen reader's double tap and hold reaches the
                // key as a held press, and 800 ms ran out before a TalkBack
                // user had heard which key they were on.
                range = 100f..3000f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_long_press_delay_info),
                default = SettingsDefaults.longPressDelayMs.toFloat(),
            ) { scope.launch { repository.setLongPressDelayMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_repeat_start_title,
                subtitle = stringResource(R.string.keypress_repeat_start_subtitle),
                value = settings.watch { it.keyRepeat.startDelayMs }.toFloat(),
                range = 150f..3000f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_repeat_start_info),
                default = SettingsDefaults.keyRepeat.startDelayMs.toFloat(),
            ) { scope.launch { repository.setKeyRepeatStartDelayMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_delete_repeat_title,
                subtitle = stringResource(R.string.keypress_delete_repeat_subtitle),
                value = settings.watch { it.keyRepeat.deleteMs }.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_delete_repeat_info),
                default = SettingsDefaults.keyRepeat.deleteMs.toFloat(),
            ) { scope.launch { repository.setDeleteRepeatIntervalMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_hold_words_title,
                stringResource(R.string.keypress_hold_words_subtitle),
                holdDeletesWords,
                info = stringResource(R.string.keypress_hold_words_info),
                default = SettingsDefaults.textEditing.deleteHoldDeletesWords,
            ) { scope.launch { repository.setDeleteHoldDeletesWords(it) } }
        }
        // What one word is, for every delete that takes words: this hold, and a
        // backspace swipe set to words (#662).
        item(visible = holdDeletesWords || (swipeDelete && swipeUnit == BackspaceSwipeUnit.WORD), level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_word_stops_punctuation_title,
                stringResource(R.string.keypress_word_stops_punctuation_subtitle),
                settings.watch { it.textEditing.deleteWordStopsAtPunctuation },
                info = stringResource(R.string.keypress_word_stops_punctuation_info),
                default = SettingsDefaults.textEditing.deleteWordStopsAtPunctuation,
            ) { scope.launch { repository.setDeleteWordStopsAtPunctuation(it) } }
        }
        item(visible = holdDeletesWords || (swipeDelete && swipeUnit == BackspaceSwipeUnit.WORD), level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_word_stops_capitals_title,
                stringResource(R.string.keypress_word_stops_capitals_subtitle),
                settings.watch { it.textEditing.deleteWordStopsAtCapitals },
                default = SettingsDefaults.textEditing.deleteWordStopsAtCapitals,
            ) { scope.launch { repository.setDeleteWordStopsAtCapitals(it) } }
        }
        item(visible = holdDeletesWords || (swipeDelete && swipeUnit == BackspaceSwipeUnit.WORD), level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_word_stops_digits_title,
                stringResource(R.string.keypress_word_stops_digits_subtitle),
                settings.watch { it.textEditing.deleteWordStopsAtDigits },
                default = SettingsDefaults.textEditing.deleteWordStopsAtDigits,
            ) { scope.launch { repository.setDeleteWordStopsAtDigits(it) } }
        }
        item(visible = holdDeletesWords, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_word_delete_repeat_title,
                subtitle = stringResource(R.string.keypress_word_delete_repeat_subtitle),
                value = settings.watch { it.keyRepeat.wordDeleteMs }.toFloat(),
                range = 60f..500f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_word_delete_repeat_info),
                default = SettingsDefaults.keyRepeat.wordDeleteMs.toFloat(),
            ) { scope.launch { repository.setWordDeleteRepeatIntervalMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_space_repeat_title,
                subtitle = stringResource(R.string.keypress_space_repeat_subtitle),
                value = settings.watch { it.keyRepeat.spaceMs }.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_space_repeat_info),
                default = SettingsDefaults.keyRepeat.spaceMs.toFloat(),
            ) { scope.launch { repository.setSpaceRepeatIntervalMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_custom_repeat_title,
                subtitle = stringResource(R.string.keypress_custom_repeat_subtitle),
                value = settings.watch { it.keyRepeat.customKeyMs }.toFloat(),
                range = 20f..200f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_custom_repeat_info),
                default = SettingsDefaults.keyRepeat.customKeyMs.toFloat(),
            ) { scope.launch { repository.setCustomKeyRepeatIntervalMs(it.toInt()) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_flicks_repeat_title,
                stringResource(R.string.keypress_flicks_repeat_subtitle),
                settings.watch { it.keyRepeat.flicksRepeat },
                default = SettingsDefaults.keyRepeat.flicksRepeat,
            ) { scope.launch { repository.setFlicksRepeat(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.keypress_caps_lock_title,
                subtitle = stringResource(R.string.keypress_caps_lock_subtitle),
                value = settings.watch { it.layoutBehavior.shiftCapsLockMs }.toFloat(),
                range = ShiftCapsLockMsRange.first.toFloat()..ShiftCapsLockMsRange.last.toFloat(),
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_caps_lock_info),
                default = SettingsDefaults.layoutBehavior.shiftCapsLockMs.toFloat(),
            ) { scope.launch { repository.setShiftCapsLockMs(it.toInt()) } }
        }
    }

}

@Composable
internal fun KeyPressHapticsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    // What decides which rows the group holds; each row reads its own value.
    val hapticsOn = settings.watch { it.haptics.enabled }
    val hapticStyle = settings.watch { it.haptics.style }
    val soundOn = settings.watch { it.sound.enabled }
    val respectSystemTouch = settings.watch { it.feedback.respectSystemTouchFeedback }
    val asHardware = settings.watch { it.feedback.hapticsAsHardwareFeedback }
    // Re-read on resume: the switch lives in Android's own settings, and
    // someone who reads the notice below goes there and comes back.
    var systemTouchOn by remember { mutableStateOf(HapticPlayer.systemTouchFeedbackOn(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemTouchOn = HapticPlayer.systemTouchFeedbackOn(context)
    }
    SettingsGroup(stringResource(R.string.keypress_haptics_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_haptics_title,
                stringResource(R.string.keypress_haptics_subtitle),
                hapticsOn,
                info = stringResource(R.string.keypress_haptics_info),
                default = SettingsDefaults.haptics.enabled,
            ) {
                scope.launch { repository.setHapticFeedback(it) }
                if (it) {
                    val haptics = settings.value.haptics
                    HapticPlayer.preview(context, haptics.style, haptics.amplitude, haptics.strengthMs)
                }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ControlSetting(
                R.string.keypress_haptic_style_title,
                info = stringResource(R.string.keypress_haptic_style_info),
            ) {
                // Six styles overflow a segmented row; wrapping chips give each a
                // full, readable label. Ordered best-to-worst via HapticStyle.entries.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    HapticStyle.entries.forEach { style ->
                        FilterChip(
                            selected = hapticStyle == style,
                            onClick = {
                                scope.launch { repository.setHapticStyle(style) }
                                // Fire the motor with the freshly picked style so the
                                // user feels the choice immediately.
                                val haptics = settings.value.haptics
                                HapticPlayer.preview(
                                    context, style, haptics.amplitude, haptics.strengthMs, view,
                                )
                            },
                            label = { Text(stringResource(style.labelRes), maxLines = 1) },
                        )
                    }
                }
            }
        }
        item(visible = hapticStyle == HapticStyle.CUSTOM, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.keypress_haptic_strength_title,
                subtitle = stringResource(R.string.keypress_haptic_strength_subtitle),
                value = settings.watch { it.haptics.strengthMs }.toFloat(),
                range = 5f..60f,
                display = { context.getString(R.string.keypress_value_ms, it.roundToInt()) },
                info = stringResource(R.string.keypress_haptic_strength_info),
                default = SettingsDefaults.haptics.strengthMs.toFloat(),
                // Debounced inside the player, so dragging previews smoothly.
                preview = {
                    val haptics = settings.value.haptics
                    HapticPlayer.preview(context, haptics.style, haptics.amplitude, it.toInt(), view)
                },
            ) {
                scope.launch { repository.setHapticStrengthMs(it.toInt()) }
            }
        }
        item(visible = hapticStyle == HapticStyle.CUSTOM || hapticStyle == HapticStyle.SHARP, level = SettingLevel.STANDARD) {
            SliderSetting(
                R.string.keypress_haptic_intensity_title,
                subtitle = stringResource(R.string.keypress_haptic_intensity_subtitle),
                value = settings.watch { it.haptics.amplitude }.toFloat(),
                range = 1f..255f,
                display = {
                    context.getString(R.string.keypress_value_percent, it.roundToInt() * 100 / 255)
                },
                info = stringResource(R.string.keypress_haptic_intensity_info),
                default = SettingsDefaults.haptics.amplitude.toFloat(),
                // Debounced inside the player, so dragging previews smoothly.
                preview = {
                    val haptics = settings.value.haptics
                    HapticPlayer.preview(context, haptics.style, it.toInt(), haptics.strengthMs, view)
                },
            ) {
                scope.launch { repository.setHapticAmplitude(it.toInt()) }
            }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_long_press_haptics_title,
                stringResource(R.string.keypress_long_press_haptics_subtitle),
                settings.watch { it.haptics.onLongPress },
                info = stringResource(R.string.keypress_long_press_haptics_info),
                default = SettingsDefaults.haptics.onLongPress,
            ) { scope.launch { repository.setHapticOnLongPress(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_long_press_release_title,
                stringResource(R.string.keypress_long_press_release_subtitle),
                settings.watch { it.haptics.onLongPressRelease },
                info = stringResource(R.string.keypress_long_press_release_info),
                default = SettingsDefaults.haptics.onLongPressRelease,
            ) { scope.launch { repository.setHapticOnLongPressRelease(it) } }
        }
        item(visible = hapticsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_cursor_haptics_title,
                stringResource(R.string.keypress_cursor_haptics_subtitle),
                settings.watch { it.haptics.onCursorMove },
                info = stringResource(R.string.keypress_cursor_haptics_info),
                default = SettingsDefaults.haptics.onCursorMove,
            ) { scope.launch { repository.setHapticOnCursorMove(it) } }
        }
        // Per-event gates: only meaningful while the master switch above is on,
        // so they fold away when it is off.
        item(visible = hapticsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_vibrate_space_title,
                stringResource(R.string.keypress_vibrate_space_subtitle),
                settings.watch { it.feedback.vibrateOnSpace },
                info = stringResource(R.string.keypress_vibrate_space_info),
                default = SettingsDefaults.feedback.vibrateOnSpace,
            ) { scope.launch { repository.setVibrateOnSpace(it) } }
        }
        item(visible = hapticsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_vibrate_delete_swipe_title,
                stringResource(R.string.keypress_vibrate_delete_swipe_subtitle),
                settings.watch { it.feedback.vibrateOnDeleteSwipe },
                info = stringResource(R.string.keypress_vibrate_delete_swipe_info),
                default = SettingsDefaults.feedback.vibrateOnDeleteSwipe,
            ) { scope.launch { repository.setVibrateOnDeleteSwipe(it) } }
        }
        item(visible = hapticsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_vibrate_repeat_title,
                stringResource(R.string.keypress_vibrate_repeat_subtitle),
                settings.watch { it.feedback.vibrateOnRepeat },
                info = stringResource(R.string.keypress_vibrate_repeat_info),
                default = SettingsDefaults.feedback.vibrateOnRepeat,
            ) { scope.launch { repository.setVibrateOnRepeat(it) } }
        }
        item(visible = hapticsOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_system_touch_title,
                stringResource(R.string.keypress_system_touch_subtitle),
                settings.watch { it.feedback.respectSystemTouchFeedback },
                info = stringResource(R.string.keypress_system_touch_info),
                default = SettingsDefaults.feedback.respectSystemTouchFeedback,
            ) { scope.launch { repository.setRespectSystemTouchFeedback(it) } }
        }
        // Issue #786: the styles that drive the vibrator directly used to go
        // silent with Android's "Touch feedback" switch off. Say so whenever
        // that switch is off, both when the keyboard is getting past it and
        // when it cannot.
        item(
            visible = hapticsOn && !respectSystemTouch && !systemTouchOn &&
                HapticPlayer.isDirectStyle(hapticStyle),
        ) {
            val bypassing = asHardware && HapticPlayer.canBypassSystemTouch()
            StateBanner(
                text = stringResource(
                    when {
                        bypassing -> R.string.keypress_system_touch_off_bypassed
                        HapticPlayer.canBypassSystemTouch() -> R.string.keypress_system_touch_off_silent
                        else -> R.string.keypress_system_touch_off_silent_old
                    },
                ),
                tone = if (bypassing) BannerTone.INFO else BannerTone.WARNING,
            )
        }
        item(visible = hapticsOn && !respectSystemTouch && HapticPlayer.canBypassSystemTouch(), level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_haptics_hardware_title,
                stringResource(R.string.keypress_haptics_hardware_subtitle),
                asHardware,
                info = stringResource(R.string.keypress_haptics_hardware_info),
                default = SettingsDefaults.feedback.hapticsAsHardwareFeedback,
            ) { scope.launch { repository.setHapticsAsHardwareFeedback(it) } }
        }
        item(visible = hapticsOn, level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_dnd_mute_title,
                stringResource(R.string.keypress_dnd_mute_subtitle),
                settings.watch { it.feedback.hapticsRespectDnd },
                info = stringResource(R.string.keypress_dnd_mute_info),
                default = SettingsDefaults.feedback.hapticsRespectDnd,
            ) { scope.launch { repository.setHapticsRespectDnd(it) } }
        }
        // A click on each repeat is a sound, not a buzz: it belongs to the key
        // sound switch, which is where it was drawn from until this was fixed.
        // It sat under the haptics master, so it was both shown while it could
        // not be heard and hidden while it could.
        if (soundOn) item {
            ToggleSetting(
                R.string.keypress_sound_repeat_title,
                stringResource(R.string.keypress_sound_repeat_subtitle),
                settings.watch { it.feedback.soundOnRepeat },
                info = stringResource(R.string.keypress_sound_repeat_info),
                default = SettingsDefaults.feedback.soundOnRepeat,
            ) { scope.launch { repository.setSoundOnRepeat(it) } }
        }
    }
    KeySoundGroup(repository, settings, onNavigate)
}

@Composable
internal fun KeyPressPopupSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var popupShapePickerOpen by rememberSaveable { mutableStateOf(false) }
    // What decides which rows the group holds; each row reads its own value.
    val popupOn = settings.watch { it.popup.enabled }
    val onKey = settings.watch { it.popup.onKey }
    if (popupShapePickerOpen) {
        KeyShapePickerDialog(
            selected = settings.watch { it.popup.shape },
            radiusDp = settings.watch { it.popup.cornerRadiusDp },
            onPick = { kind ->
                scope.launch { repository.setKeyPopupShape(kind) }
                popupShapePickerOpen = false
            },
            onDismiss = { popupShapePickerOpen = false },
            title = R.string.keypress_popup_shape_title,
        )
    }
    SettingsGroup(stringResource(R.string.keypress_popup_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_popup_title,
                stringResource(R.string.keypress_popup_subtitle),
                popupOn,
                info = stringResource(R.string.keypress_popup_info),
                default = SettingsDefaults.popup.enabled,
            ) { scope.launch { repository.setKeyPopup(it) } }
        }
        item(visible = popupOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_popup_numeric_title,
                stringResource(R.string.keypress_popup_numeric_subtitle),
                settings.watch { it.popup.inNumericFields },
                info = stringResource(R.string.keypress_popup_numeric_info),
                default = SettingsDefaults.popup.inNumericFields,
            ) { scope.launch { repository.setKeyPopupInNumericFields(it) } }
        }
        item(visible = popupOn, level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_popup_window_band_title,
                stringResource(R.string.keypress_popup_window_band_subtitle),
                settings.watch { it.popup.windowBand },
                info = stringResource(R.string.keypress_popup_window_band_info),
                default = SettingsDefaults.popup.windowBand,
            ) { scope.launch { repository.setKeyPopupWindowBand(it) } }
        }
        item(visible = popupOn, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_popup_min_duration_title,
                subtitle = stringResource(R.string.keypress_popup_min_duration_subtitle),
                value = settings.watch { it.popup.minDurationMs }.toFloat(),
                range = 0f..300f,
                display = { context.getString(R.string.keypress_value_ms, it.toInt()) },
                info = stringResource(R.string.keypress_popup_min_duration_info),
                default = SettingsDefaults.popup.minDurationMs.toFloat(),
            ) { scope.launch { repository.setKeyPopupMinDurationMs(it.toInt()) } }
        }
        // The maximum duration has no row (SETTINGS_REDESIGN.md §4). It is the
        // stuck-bubble backstop, not a feel, and its own info text said never
        // to lower it; the stored value is read as the constant floor it
        // ships at, and a value set before this is still honoured.
        // These stop at the preview bubble, and no bubble is published while
        // the switch at the top of the group is off. The font size below is
        // not among them: the flick popup and the spacebar language tooltip
        // read it too.
        if (popupOn) item {
            // A theme may name the placement itself; the row then says so
            // rather than flipping a switch the board no longer reads (#88).
            val pin = themePin(settings) { popupOnKeyOrNull(it.popupPlacement) }
            ToggleSetting(
                R.string.keypress_popup_on_key_title,
                pin?.text ?: stringResource(R.string.keypress_popup_on_key_subtitle),
                onKey,
                info = stringResource(R.string.keypress_popup_on_key_info),
                enabled = pin == null,
                disabledReason = pin,
                default = SettingsDefaults.popup.onKey,
            ) { scope.launch { repository.setKeyPopupOnKey(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_popup_font_size_title,
                subtitle = stringResource(R.string.keypress_popup_font_size_subtitle),
                value = settings.watch { it.popup.fontScale },
                range = 0.7f..1.6f,
                display = { context.getString(R.string.keypress_value_multiplier, it) },
                info = stringResource(R.string.keypress_popup_font_size_info),
                default = SettingsDefaults.popup.fontScale,
            ) { scope.launch { repository.setPopupFontScale(it) } }
        }
        if (popupOn) item {
            // Pinned when the theme sizes the style the board is drawing —
            // its own placement where it names one, else this switch's.
            val pin = themePin(settings) { spec ->
                val drawnOnKey = popupOnKeyOrNull(spec.popupPlacement) ?: onKey
                if (drawnOnKey) spec.popupHeightDp else spec.popupFloatingHeightDp
            }
            SliderSetting(
                R.string.keypress_popup_height_title,
                subtitle = pin?.text ?: stringResource(R.string.keypress_popup_height_subtitle),
                value = settings.watch { it.popup.heightDp }.toFloat(),
                range = 32f..160f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_height_info),
                enabled = pin == null,
                disabledReason = pin,
                // The default of the style the slider is showing: the on-key
                // one was handed to the floating bubble too, so a reset while
                // floating set it to 110 dp.
                default = SettingsDefaults.popup.heightFor(onKey).toFloat(),
            ) { scope.launch { repository.setKeyPopupHeightDp(it.toInt()) } }
        }
        // Only the floating bubble is placed by these. The on-key one grows out
        // of the key it covers, so there is no distance to set: its height is
        // what carries it past the finger, and that slider is above.
        item(visible = popupOn && !onKey, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_popup_offset_y_title,
                subtitle = stringResource(R.string.keypress_popup_offset_y_subtitle),
                value = settings.watch { it.popup.floatingOffsetYDp }.toFloat(),
                range = 0f..96f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_offset_y_info),
                default = SettingsDefaults.popup.floatingOffsetYDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupFloatingOffsetYDp(it.toInt()) } }
        }
        item(visible = popupOn && !onKey, level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_popup_offset_x_title,
                subtitle = stringResource(R.string.keypress_popup_offset_x_subtitle),
                value = settings.watch { it.popup.floatingOffsetXDp }.toFloat(),
                range = -64f..64f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_offset_x_info),
                default = SettingsDefaults.popup.floatingOffsetXDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupFloatingOffsetXDp(it.toInt()) } }
        }
        // Shape and radius govern every popup surface, not only the preview
        // bubble: the long-press alternates, the language picker and the panel
        // menus all draw with them.
        item(level = SettingLevel.STANDARD) {
            NavRow(
                R.string.keypress_popup_shape_title,
                subtitle = stringResource(R.string.keypress_popup_shape_subtitle),
                value = keyShapeName(settings.watch { it.popup.shape }),
                onClick = { popupShapePickerOpen = true },
            )
        }
        item(level = SettingLevel.ADVANCED) {
            SliderSetting(
                R.string.keypress_popup_radius_title,
                subtitle = stringResource(R.string.keypress_popup_radius_subtitle),
                value = settings.watch { it.popup.cornerRadiusDp }.toFloat(),
                range = 0f..40f,
                display = { context.getString(R.string.keypress_value_dp, it.toInt()) },
                info = stringResource(R.string.keypress_popup_radius_info),
                default = SettingsDefaults.popup.cornerRadiusDp.toFloat(),
            ) { scope.launch { repository.setKeyPopupCornerRadiusDp(it.toInt()) } }
        }
        // Unlike shape and radius, these two stop at the preview bubble: the
        // alternates, the language picker and the panel menus keep the theme's
        // popup colour. Seeded from the settings app's own surface, which is
        // only where the wheel opens — the keyboard's palette cannot be read
        // here without repainting this screen in it.
        if (popupOn) item {
            ColorSetting(
                R.string.keypress_popup_background_title,
                subtitle = stringResource(R.string.keypress_popup_background_subtitle),
                color = settings.watch { it.popup.backgroundColor },
                fallback = MaterialTheme.colorScheme.surfaceVariant.argbLong(),
                info = stringResource(R.string.keypress_popup_background_info),
            ) { scope.launch { repository.setKeyPopupBackgroundColor(it) } }
        }
        if (popupOn) item {
            ColorSetting(
                R.string.keypress_popup_text_color_title,
                subtitle = stringResource(R.string.keypress_popup_text_color_subtitle),
                color = settings.watch { it.popup.textColor },
                fallback = MaterialTheme.colorScheme.onSurfaceVariant.argbLong(),
                info = stringResource(R.string.keypress_popup_text_color_info),
            ) { scope.launch { repository.setKeyPopupTextColor(it) } }
        }
    }
}

@Composable
internal fun KeyPressShortcutsSettings(
    repository: SettingsRepository,
    settings: LiveSettings,
) {
    val scope = rememberCoroutineScope()
    // Decides which rows the group holds; the rows read everything else.
    val holdActions = settings.watch { it.longPressLetterActions }
    SettingsGroup(stringResource(R.string.keypress_shortcuts_group_title)) {
        item {
            ToggleSetting(
                R.string.keypress_long_press_hints_title,
                stringResource(R.string.keypress_long_press_hints_subtitle),
                settings.watch { it.longPressHints },
                info = stringResource(R.string.keypress_long_press_hints_info),
                default = SettingsDefaults.longPressHints,
            ) { scope.launch { repository.setLongPressHints(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_all_accents_title,
                stringResource(R.string.keypress_all_accents_subtitle),
                settings.watch { it.layoutBehavior.showAllPopupKeys },
                info = stringResource(R.string.keypress_all_accents_info),
                default = SettingsDefaults.layoutBehavior.showAllPopupKeys,
            ) { scope.launch { repository.setShowAllPopupKeys(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Issue #677: the accents out of every letter's popup at once.
            ToggleSetting(
                R.string.keypress_no_accents_title,
                stringResource(R.string.keypress_no_accents_subtitle),
                settings.watch { it.layoutBehavior.noLetterAccents },
                info = stringResource(R.string.keypress_no_accents_info),
                default = SettingsDefaults.layoutBehavior.noLetterAccents,
            ) { scope.launch { repository.setNoLetterAccents(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            // Issue #672: the digits on Q-P, decided apart from the number row.
            ChoiceSetting(
                R.string.keypress_top_row_holds_title,
                subtitle = stringResource(R.string.keypress_top_row_holds_subtitle),
                info = stringResource(R.string.keypress_top_row_holds_info),
                options = listOf(
                    TopRowHolds.AUTO to stringResource(R.string.keypress_top_row_holds_auto),
                    TopRowHolds.DIGITS to stringResource(R.string.keypress_top_row_holds_digits),
                    TopRowHolds.SYMBOLS to stringResource(R.string.keypress_top_row_holds_symbols),
                    TopRowHolds.NONE to stringResource(R.string.keypress_top_row_holds_none),
                ),
                selected = settings.watch { it.layoutBehavior.topRowHolds },
                default = SettingsDefaults.layoutBehavior.topRowHolds,
                detail = { holds ->
                    ChoiceDetail(
                        stringResource(
                            when (holds) {
                                TopRowHolds.AUTO -> R.string.keypress_top_row_holds_auto_desc
                                TopRowHolds.DIGITS -> R.string.keypress_top_row_holds_digits_desc
                                TopRowHolds.SYMBOLS -> R.string.keypress_top_row_holds_symbols_desc
                                TopRowHolds.NONE -> R.string.keypress_top_row_holds_none_desc
                            },
                        ),
                    )
                },
            ) { scope.launch { repository.setTopRowHolds(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            // Telegram group: the symbols SwiftKey or Samsung put on the
            // letters, without editing every layout.
            var editing by remember { mutableStateOf(false) }
            val symbols = settings.watch { it.layoutBehavior.holdSymbols }
            WmRow(
                title = stringResource(R.string.keypress_hold_symbols_title),
                icon = SettingsRowIcons[R.string.keypress_hold_symbols_title],
                subtitle = if (symbols.isEmpty()) {
                    stringResource(R.string.keypress_hold_symbols_subtitle)
                } else {
                    symbols.entries.sortedBy { it.key }.joinToString("  ") { "${it.key} ${it.value}" }
                },
                onClick = { editing = true },
            )
            if (editing) {
                HoldSymbolsDialog(symbols, onDismiss = { editing = false }) { chosen ->
                    editing = false
                    scope.launch { repository.setHoldSymbols(chosen) }
                }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            // Discussion #382: ü ahead of 7 on German's u.
            ToggleSetting(
                R.string.keypress_native_letters_title,
                stringResource(R.string.keypress_native_letters_subtitle),
                settings.watch { it.layoutBehavior.nativeLettersFirst },
                info = stringResource(R.string.keypress_native_letters_info),
                default = SettingsDefaults.layoutBehavior.nativeLettersFirst,
            ) { scope.launch { repository.setNativeLettersFirst(it) } }
        }
        item(level = SettingLevel.ADVANCED) {
            // Issue #108: the capital of every letter joins its popup, which is
            // where a layer peek has to reach one from.
            ToggleSetting(
                R.string.keypress_shifted_popup_title,
                stringResource(R.string.keypress_shifted_popup_subtitle),
                settings.watch { it.layoutBehavior.shiftedPopupKeys },
                info = stringResource(R.string.keypress_shifted_popup_info),
                default = SettingsDefaults.layoutBehavior.shiftedPopupKeys,
            ) { scope.launch { repository.setShiftedPopupKeys(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_symbols_numpad_title,
                stringResource(R.string.keypress_symbols_numpad_subtitle),
                settings.watch { it.layoutBehavior.symbolsLongPressNumpad },
                info = stringResource(R.string.keypress_symbols_numpad_info),
                default = SettingsDefaults.layoutBehavior.symbolsLongPressNumpad,
            ) { scope.launch { repository.setSymbolsLongPressNumpad(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            ToggleSetting(
                R.string.keypress_enter_emoji_title,
                stringResource(R.string.keypress_enter_emoji_subtitle),
                settings.watch { it.layoutBehavior.enterLongPressEmoji },
                info = stringResource(R.string.keypress_enter_emoji_info),
                default = SettingsDefaults.layoutBehavior.enterLongPressEmoji,
            ) { scope.launch { repository.setEnterLongPressEmoji(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            // A44: the $ key's long-press currency glyphs, space-separated. Blank
            // restores the built-in set. Mirrors the layout editor's alternates field.
            val currencyKeys = settings.watch { it.layoutBehavior.currencyKeys }
            var currencyText by remember(currencyKeys) {
                mutableStateOf(
                    currencyKeys
                        .ifEmpty { DefaultCurrencyKeys }
                        .joinToString(" "),
                )
            }
            ControlSetting(
                R.string.keypress_currency_keys_title,
                info = stringResource(R.string.keypress_currency_keys_info),
            ) {
                OutlinedTextField(
                    value = currencyText,
                    onValueChange = {
                        currencyText = it
                        scope.launch {
                            repository.setCurrencyKeys(it.trim().split(" ").filter { s -> s.isNotBlank() })
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(level = SettingLevel.ADVANCED) {
            ToggleSetting(
                R.string.keypress_ctrl_raw_title,
                stringResource(R.string.keypress_ctrl_raw_subtitle),
                settings.watch { it.rawClipboardShortcuts },
                info = stringResource(R.string.keypress_ctrl_raw_info),
                default = SettingsDefaults.rawClipboardShortcuts,
            ) { scope.launch { repository.setRawClipboardShortcuts(it) } }
        }
        item(level = SettingLevel.STANDARD) {
            val actions = holdActions
            MultiChoiceSetting(
                R.string.keypress_hold_actions_title,
                subtitle = stringResource(R.string.keypress_hold_actions_subtitle),
                info = stringResource(R.string.keypress_hold_actions_info),
                options = HoldAction.entries.map { it to stringResource(it.labelRes) },
                selected = HoldAction.entries.filterTo(mutableSetOf()) { it.isOn(actions) },
                default = DefaultHoldActions,
            ) { chosen ->
                scope.launch {
                    for (action in HoldAction.entries) {
                        val on = action in chosen
                        if (on != action.isOn(actions)) action.set(repository, on)
                    }
                }
            }
        }
        val anyHoldAction = holdActions.selectAll || holdActions.copy || holdActions.paste ||
            holdActions.cut || holdActions.undo || holdActions.redo
        if (anyHoldAction) {
            item {
                HoldActionPositionSetting(holdActions.actionPosition) {
                    scope.launch { repository.setLongPressActionPosition(it) }
                }
            }
        }
        item(level = SettingLevel.ADVANCED) {
            // The same six shortcuts, reached by sliding off 🌐 onto the key.
            ToggleSetting(
                R.string.keypress_globe_drag_title,
                stringResource(R.string.keypress_globe_drag_subtitle),
                holdActions.globeDrag,
                info = stringResource(R.string.keypress_globe_drag_info),
                default = SettingsDefaults.longPressLetterActions.globeDrag,
            ) { scope.launch { repository.setGlobeDragShortcuts(it) } }
        }
        // The keys matter to both gestures, so either one keeps the row.
        if (anyHoldAction || holdActions.globeDrag) {
            item(level = SettingLevel.ADVANCED) { HoldShortcutLettersSetting(repository, holdActions) }
        }
    }
}
/**
 * Where the hold shortcut goes in its key's popup (issue #570): first, after a
 * number of characters, or last.
 *
 * A stepper rather than the on/off switch this started as, because the two ends
 * were not what was asked for. At the front a plain hold runs the shortcut but
 * the corner hint names an accent the hold no longer types; at the back the
 * shortcut is past every accent. One or two along keeps the hold and the hint
 * agreeing and puts the shortcut a short slide from the key.
 */
@Composable
private fun HoldActionPositionSetting(position: Int, onChange: (Int) -> Unit) {
    val first = stringResource(R.string.keypress_hold_action_position_first)
    val last = stringResource(R.string.keypress_hold_action_position_last)
    // Resolved up front: `display` is a plain lambda and plurals need a
    // composition.
    val after = (1..ACTION_POSITION_MAX).map {
        pluralStringResource(R.plurals.keypress_hold_action_position_after, it, it)
    }
    StepperSetting(
        title = R.string.keypress_hold_action_position_title,
        subtitle = stringResource(R.string.keypress_hold_action_position_subtitle),
        value = position,
        range = HoldActionPositionSteps,
        display = { at ->
            when (at) {
                ACTION_POSITION_LAST -> last
                0 -> first
                else -> after[at - 1]
            }
        },
        info = stringResource(R.string.keypress_hold_action_position_info),
        default = SettingsDefaults.longPressLetterActions.actionPosition,
        onChange = onChange,
    )
}

/** First, then one step per character, then last: the order the popup reads in. */
private val HoldActionPositionSteps: List<Int> = (0..ACTION_POSITION_MAX).toList() + ACTION_POSITION_LAST

/**
 * Which key each hold shortcut sits on.
 *
 * One row for all six rather than six rows, because the six letters are one
 * decision: the shipped `acvxzy` is the QWERTY answer, and someone typing
 * Bengali or Russian is rebinding the whole set at once or not at all. Shown
 * only once at least one of the six actions is on, since it has nothing to say
 * otherwise.
 */
@Composable
private fun HoldShortcutLettersSetting(
    repository: SettingsRepository,
    actions: LongPressLetterActions,
) {
    val scope = rememberCoroutineScope()
    val labels = listOf(
        R.string.keypress_hold_action_select_all_label,
        R.string.keypress_hold_action_copy_label,
        R.string.keypress_hold_action_paste_label,
        R.string.keypress_hold_action_cut_label,
        R.string.keypress_hold_action_undo_label,
        R.string.keypress_hold_action_redo_label,
    )
    val enabled = listOf(
        actions.selectAll, actions.copy, actions.paste,
        actions.cut, actions.undo, actions.redo,
    )
    var editing by remember { mutableStateOf(false) }
    val title = stringResource(R.string.keypress_hold_letters_title)
    // Only the keys that are actually bound, so the summary reads as what the
    // keyboard will do rather than as the whole six-character string.
    val summary = enabled.mapIndexedNotNull { slot, on ->
        actions.letterFor(slot)?.takeIf { on }?.uppercase()
    }.joinToString(" ")
    HighlightableRow(title) {
        WmRow(
            title = title,
            subtitle = summary.ifEmpty { stringResource(R.string.keypress_hold_letters_subtitle) },
            icon = SettingsRowIcons[R.string.keypress_hold_letters_title],
            trailing = {
                ResetSetting(title, actions.letters != DEFAULT_LONG_PRESS_LETTERS) {
                    scope.launch { repository.setLongPressLetters(DEFAULT_LONG_PRESS_LETTERS) }
                }
            },
            onClick = { editing = true },
        )
    }
    if (!editing) return
    // Edited as one string and written once on Save: a per-character write
    // would push five malformed values through the setter on the way to a
    // valid one, and the setter refuses anything that is not six characters.
    var draft by remember(actions.letters) { mutableStateOf(actions.letters) }
    AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(title) },
        text = {
            Column {
                DialogNote(stringResource(R.string.keypress_hold_letters_dialog_note))
                Spacer(Modifier.height(8.dp))
                labels.forEachIndexed { slot, labelRes ->
                    if (!enabled[slot]) return@forEachIndexed
                    OutlinedTextField(
                        value = draft.getOrNull(slot)?.toString().orEmpty(),
                        onValueChange = { typed ->
                            val ch = typed.lastOrNull() ?: return@OutlinedTextField
                            draft = draft.mapIndexed { i, old ->
                                if (i == slot) ch.lowercaseChar() else old
                            }.joinToString("")
                        },
                        label = { Text(stringResource(labelRes)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                editing = false
                scope.launch { repository.setLongPressLetters(draft) }
            }) { Text(stringResource(CommonR.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = { editing = false }) {
                Text(stringResource(CommonR.string.common_cancel))
            }
        },
    )
}

/**
 * The six hold shortcuts as one set, since they are one decision: which
 * letter keys give up their accent popup for an edit action.
 */
private enum class HoldAction(
    @StringRes val labelRes: Int,
    val isOn: (LongPressLetterActions) -> Boolean,
    val set: suspend (SettingsRepository, Boolean) -> Unit,
) {
    SELECT_ALL(R.string.keypress_hold_action_select_all_label, { it.selectAll }, { r, on -> r.setLongPressASelectAll(on) }),
    COPY(R.string.keypress_hold_action_copy_label, { it.copy }, { r, on -> r.setLongPressCCopy(on) }),
    CUT(R.string.keypress_hold_action_cut_label, { it.cut }, { r, on -> r.setLongPressXCut(on) }),
    PASTE(R.string.keypress_hold_action_paste_label, { it.paste }, { r, on -> r.setLongPressVPaste(on) }),
    UNDO(R.string.keypress_hold_action_undo_label, { it.undo }, { r, on -> r.setLongPressZUndo(on) }),
    REDO(R.string.keypress_hold_action_redo_label, { it.redo }, { r, on -> r.setLongPressYRedo(on) }),
}

private val DefaultHoldActions: Set<HoldAction> =
    HoldAction.entries.filterTo(mutableSetOf()) { it.isOn(SettingsDefaults.longPressLetterActions) }

/** The letter rows of the hold-symbol editor, in QWERTY order. */
private val HoldSymbolRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

/**
 * One box per letter for [LayoutBehaviorSettings.holdSymbols], with SwiftKey's
 * set as a starting point and a way back to every layout's own. A box left
 * empty keeps the layout's symbol for that letter.
 */
@Composable
private fun HoldSymbolsDialog(
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var symbols by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keypress_hold_symbols_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.keypress_hold_symbols_info))
                for (row in HoldSymbolRows) {
                    FlowRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        for (letter in row) {
                            val key = letter.toString()
                            OutlinedTextField(
                                value = symbols[key].orEmpty(),
                                onValueChange = { text ->
                                    val symbol = text.trim().take(4)
                                    symbols = if (symbol.isEmpty()) symbols - key else symbols + (key to symbol)
                                },
                                label = { Text(key) },
                                singleLine = true,
                                modifier = Modifier.width(64.dp).padding(end = 4.dp),
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { symbols = SwiftKeyHoldSymbols }) {
                        Text(stringResource(R.string.keypress_hold_symbols_swiftkey))
                    }
                    TextButton(onClick = { symbols = emptyMap() }) {
                        Text(stringResource(R.string.keypress_hold_symbols_clear))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(symbols) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}
