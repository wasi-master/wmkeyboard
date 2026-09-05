package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import com.wasimaster.wmkeyboard.core.addons.AddonType
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.content.R as ContentR
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.OpenableColumns
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.ime.ui.KeyboardFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.script.ScriptId
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.fonts.InstalledFont
import kotlinx.coroutines.launch

// ---- fonts ----

/** Mime types SAF offers when picking a font; octet-stream covers file managers that don't tag fonts. */
internal val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/*", "application/x-font-ttf", "application/octet-stream",
)
/**
 * A refused font import, kept as resource ids rather than finished words: the
 * import runs off the main thread with no way to draw, so the dialog is what
 * resolves the wording against the language the app is running in.
 *
 * A message that counts fonts sets [pluralsRes] and [quantity] instead of
 * [stringRes]; [args] fills the placeholders of [stringRes].
 */
private data class FontMessage(
    @StringRes val stringRes: Int = 0,
    @PluralsRes val pluralsRes: Int = 0,
    val quantity: Int = 0,
    val args: List<Any> = emptyList(),
)
/**
 * Font picker: separate English and Bengali choices, each offering the
 * system default, the installed-font library, curated Google Fonts (every row
 * rendered in its own face as a live preview — faces download on first view and
 * are cached by the system provider), plus the legacy single imported file per
 * script.
 *
 * Importing a file here fills the library rather than overwriting one fixed
 * slot, so importing a second font no longer evicts the first. The two old
 * single-slot files still render and stay selectable for anyone who set one
 * before the library existed; nothing migrates and nothing is lost.
 */
@Composable
internal fun FontSettings(
    repository: SettingsRepository,
    settings: KeyboardSettings,
    onNavigate: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fontStore = remember { FontStore.get(context) }
    val fontRevision by fontStore.revision.collectAsStateWithLifecycle()
    // Text faces only: an emoji font is chosen on the Emoji screen, and picking
    // one for the key labels would draw the alphabet as coloured pictograms.
    val installedFonts = remember(fontRevision) { fontStore.textFonts() }
    // The failure to show, still unresolved; see [FontMessage].
    var fontMessage by remember { mutableStateOf<FontMessage?>(null) }

    fun importIntoLibrary(uri: android.net.Uri, apply: suspend (String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        FontFile.import(it, fontStore, name = fontFileLabel(context, uri))
                    }
                }.getOrElse {
                    FontImportResult.Failed(ContentR.string.core_content_font_error_read)
                }
            }
            when (result) {
                is FontImportResult.Imported -> apply(FontStore.fontIdFor(result.font.id))
                is FontImportResult.NotAFont -> fontMessage = FontMessage(result.messageRes)
                FontImportResult.TooManyFonts -> fontMessage = FontMessage(
                    pluralsRes = R.plurals.fonts_import_limit_message,
                    quantity = FontStore.MAX_FONTS,
                )
                is FontImportResult.Failed ->
                    fontMessage = FontMessage(result.messageRes, args = result.messageArgs)
            }
        }
    }

    fun deleteInstalled(font: InstalledFont) {
        scope.launch {
            // Drop the selection first, so the keyboard never renders against a
            // file that is about to disappear. Covers the per-script overrides
            // too, which neither of the pickers on this screen can see.
            repository.forgetInstalledFont(FontStore.fontIdFor(font.id))
            withContext(Dispatchers.IO) { fontStore.delete(font.id) }
        }
    }

    fontMessage?.let { message ->
        // Spelled out rather than spread: no font message takes more than one
        // argument, and a spread copies the array on every recomposition.
        val text = when {
            message.pluralsRes != 0 -> pluralStringResource(
                message.pluralsRes,
                message.quantity,
                message.quantity,
            )
            message.args.isEmpty() -> stringResource(message.stringRes)
            else -> stringResource(message.stringRes, message.args.first())
        }
        AlertDialog(
            onDismissRequest = { fontMessage = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { fontMessage = null }) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            },
        )
    }

    Text(
        stringResource(R.string.fonts_info),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    // Said once, at the top, so the short pickers below read as a missing
    // platform piece rather than a keyboard that forgot its fonts.
    if (!PlayServices.hasFontProvider(context)) {
        Text(
            stringResource(R.string.fonts_google_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
        )
    }
    AddonStoreGroup(AddonType.Font, onNavigate)
    FontPickerSection(
        header = stringResource(R.string.fonts_english_header),
        sample = "The quick brown fox jumps over the lazy dog",
        selectedId = settings.keyFontId,
        googleNames = KeyboardFonts.googleFonts,
        customId = KeyboardFonts.CUSTOM_ID,
        customFile = KeyboardFonts.customFontFile(context),
        customName = settings.customFontName,
        onSelect = { id -> scope.launch { repository.setKeyFontId(id) } },
        onImport = { uri -> importIntoLibrary(uri) { repository.setKeyFontId(it) } },
        installedFonts = installedFonts,
        installedTitle = stringResource(R.string.fonts_installed_header),
        // The English picker also drives Cyrillic and Greek, which have no
        // picker of their own — a font claiming any of the three belongs here.
        scripts = setOf(ScriptId.LATIN, ScriptId.CYRILLIC, ScriptId.GREEK),
        onDeleteInstalled = ::deleteInstalled,
    )
    // Curated font pickers for the non-Latin scripts, each shown only while a
    // language using that script is enabled. Every one offers the script's
    // automatic Noto face, a few alternatives, the import button and whatever in
    // the font library covers that script.
    // Latin/Cyrillic/Greek follow the English font above.
    val enabledScripts = settings.enabledLanguages.mapTo(mutableSetOf()) { it.script }
    for (choices in KeyboardFonts.scriptFontChoices) {
        if (choices.script !in enabledScripts) continue
        val script = choices.script.name
        // Non-null only for the scripts whose picker takes an imported file;
        // everything import-shaped below hangs off it.
        val customId = KeyboardFonts.customScriptFontId(choices.script)
        // Importing goes into the shared library, which every script can select
        // from, so it does not need the per-script file slot [customId] names
        // and is offered everywhere. Before this, a Devanagari or Thai font had
        // nowhere to be imported and nowhere to be picked even once installed
        // from an addon: only the two scripts with a legacy file slot showed
        // the library at all.
        val onImportFont: (Uri) -> Unit = { uri: Uri ->
            importIntoLibrary(uri) { id -> repository.setScriptFontId(script, id) }
        }
        // The name of the script, drawn into both headers of this picker.
        val scriptName = stringResource(choices.labelRes)
        FontPickerSection(
            header = stringResource(R.string.fonts_script_header, scriptName),
            sample = choices.sample,
            selectedId = settings.scriptFontIds[script] ?: KeyboardFonts.DEFAULT_ID,
            googleNames = choices.fonts,
            defaultLabel = stringResource(R.string.fonts_default_noto_label),
            customId = customId,
            customFile = KeyboardFonts.customScriptFontFile(context, choices.script),
            customName = settings.customScriptFontNames[script].orEmpty(),
            onSelect = { id -> scope.launch { repository.setScriptFontId(script, id) } },
            onImport = onImportFont,
            installedFonts = installedFonts,
            installedTitle = stringResource(R.string.fonts_installed_script_header, scriptName),
            scripts = setOf(choices.script),
            onDeleteInstalled = ::deleteInstalled,
        )
    }
    Spacer(Modifier.height(16.dp))
}
/**
 * One script's font list: the default row, curated Google faces, and — for the
 * scripts that support it (English/Bengali) — the single imported file and an
 * import button. Scripts that only offer curated faces pass [customFile] null;
 * their default row is relabelled via [defaultLabel] since it is the script's
 * automatic Noto face rather than the raw system font.
 *
 * [installedFonts] is the library filled by the Addons screen and by importing a
 * file here. It gets its own section above the curated list: it is a short,
 * personal list next to twenty stock faces, and burying it inside them makes a
 * font the user deliberately installed harder to find than one they didn't.
 *
 * [scripts] is what the picker is for. A font that declares which languages it
 * covers is only offered where it covers something — plenty of display faces are
 * Latin-only, and offering one for Bengali offers a keyboard of empty boxes. A
 * font that declares nothing makes no claim and is offered everywhere.
 */
@Composable
private fun FontPickerSection(
    header: String,
    sample: String,
    selectedId: String,
    googleNames: List<String>,
    onSelect: (String) -> Unit,
    defaultLabel: String = stringResource(R.string.fonts_default_system_label),
    /** The imported-file id this picker writes, or null if it takes no import. */
    customId: String? = KeyboardFonts.CUSTOM_ID,
    customFile: java.io.File? = null,
    customName: String = "",
    onImport: ((android.net.Uri) -> Unit)? = null,
    installedFonts: List<InstalledFont> = emptyList(),
    installedTitle: String = stringResource(R.string.fonts_installed_header),
    scripts: Set<ScriptId> = emptySet(),
    onDeleteInstalled: ((InstalledFont) -> Unit)? = null,
) {
    val context = LocalContext.current
    val importFont = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onImport?.invoke(uri) }
    val relevant = remember(installedFonts, scripts) {
        installedFonts.filter { font ->
            font.langIds.isEmpty() || scripts.isEmpty() ||
                font.langIds.any { LanguageRegistry.byId(it).script in scripts }
        }
    }
    // Every Google Fonts row is a file fetched from the Play services font
    // provider. On a device without it each one would resolve to the system
    // face, so picking one would change nothing on screen — the rows come out
    // rather than sit there doing nothing. The system default, the imported
    // file and the font library are all this app's own and stay.
    val googleFontNames = if (PlayServices.hasFontProvider(context)) googleNames else emptyList()
    if (relevant.isNotEmpty()) {
        SettingsGroup(installedTitle) {
            for (font in relevant) {
                item {
                    val id = FontStore.fontIdFor(font.id)
                    // Matched on the library id, not the prefixed settings one:
                    // an install records the font exactly as the store knows it.
                    HighlightableItem(font.id) {
                        FontChoiceRow(
                            label = font.name,
                            family = remember(id) { KeyboardFonts.family(context, id) },
                            sample = sample,
                            selected = selectedId == id,
                            onDelete = onDeleteInstalled?.let { delete -> { delete(font) } },
                        ) { onSelect(id) }
                    }
                }
            }
        }
    }
    SettingsGroup(header) {
        item {
            FontChoiceRow(
                label = defaultLabel,
                family = null,
                sample = sample,
                selected = selectedId == KeyboardFonts.DEFAULT_ID,
            ) { onSelect(KeyboardFonts.DEFAULT_ID) }
        }
        for (name in googleFontNames) {
            item {
                val id = KeyboardFonts.googleId(name)
                FontChoiceRow(
                    label = name,
                    family = remember(id) { KeyboardFonts.family(context, id) },
                    sample = sample,
                    selected = selectedId == id,
                ) { onSelect(id) }
            }
        }
        // The single imported file this picker used to keep before fonts moved
        // into the shared library above. Shown only while that file is still
        // there, so an old import stays selectable and a fresh install never
        // sees the row.
        if (customId != null && customFile?.exists() == true) {
            item {
                val importedLabel = stringResource(R.string.fonts_imported_label)
                FontChoiceRow(
                    label = customName.ifBlank { importedLabel },
                    family = remember(customName) { KeyboardFonts.family(context, customId) },
                    sample = sample,
                    selected = selectedId == customId,
                ) { onSelect(customId) }
            }
        }
        if (onImport != null) {
            item {
                OutlinedButton(
                    onClick = { importFont.launch(FONT_MIME_TYPES) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text(stringResource(R.string.fonts_import_action)) }
            }
        }
    }
}
/** One selectable font row, its label and sample line drawn in the font itself. */
@Composable
private fun FontChoiceRow(
    label: String,
    family: FontFamily?,
    sample: String,
    selected: Boolean,
    onDelete: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    WmRow(
        title = label,
        // The row is the preview: name and sample both drawn in the font
        // itself, so picking one shows what it will look like.
        titleContent = { Text(label, fontFamily = family, fontSize = 18.sp) },
        supporting = {
            Text(
                sample,
                fontFamily = family,
                maxLines = 1,
            )
        },
        trailing = if (selected || onDelete != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = stringResource(R.string.fonts_selected_desc),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(
                                    R.string.fonts_delete_desc,
                                    label,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        onClick = onClick,
    )
}
/**
 * Copies a picked font into private storage and returns its display name,
 * or null when the stream can't be read or the platform can't parse the
 * file (the bad copy is deleted so it never sticks as the "custom font").
 */
/**
 * A human-readable name for a picked font file: the provider's display name
 * with the extension stripped, since "Inter-Regular" reads better in the picker
 * than "Inter-Regular.ttf".
 */
internal fun fontFileLabel(context: Context, uri: android.net.Uri): String {
    val name = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
    return name?.substringBeforeLast('.')?.trim().orEmpty()
        .ifBlank { context.getString(R.string.fonts_imported_label) }
}
internal fun importFontFile(context: Context, uri: android.net.Uri, dest: java.io.File): String? {
    return runCatching {
        dest.parentFile?.mkdirs()
        val copied = context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
        if (copied == null) return null
        val parsed = runCatching { android.graphics.Typeface.createFromFile(dest) }.getOrNull()
        if (parsed == null || parsed == android.graphics.Typeface.DEFAULT) {
            dest.delete()
            return null
        }
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: dest.name
    }.getOrNull()
}
