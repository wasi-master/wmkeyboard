package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.IconImportResult
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.keyman.KeymanResult
import com.wasimaster.wmkeyboard.core.keyman.KeymanRuleStore
import com.wasimaster.wmkeyboard.core.keyman.KeymanTouchLayoutReader
import com.wasimaster.wmkeyboard.core.keyman.TouchLayoutConverter
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.keyman.KeymanPackage
import com.wasimaster.wmkeyboard.core.layout.ImportedLayout
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginImportResult
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.snippets.ImportedSnippets
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.theme.ConvertedTheme
import com.wasimaster.wmkeyboard.core.theme.FlexResult
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.FlexUnsupported
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.groupAsFamily
import com.wasimaster.wmkeyboard.core.theme.themeFamilyName
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.icons.R as IconsR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opening one of the keyboard's own files from outside the app — a file
 * manager, a chat app's "open with", a downloads notification.
 *
 * Every format the keyboard exports is either JSON or a ZIP, so a MIME-typed
 * intent filter would claim every `.json` and every archive on the device.
 * The manifest filters on the *file name* instead (`pathPattern` per
 * extension), which is why each export writes a compound extension:
 * `.wmtheme.json` rather than `.json`. Files whose content URI carries no
 * name — a downloads-provider `msf:1234`, say — simply don't match, and the
 * user opens them through the in-app pickers as before. That miss is the
 * intended trade for not being offered as a JSON viewer.
 *
 * The extension only gets the file here. What is actually *done* with it is
 * decided by reading it: every format but the theme carries a format tag, so
 * a `.wmlayout.json` holding a backup still restores a backup.
 */
object WMFileTypes {

    /**
     * Extensions the manifest's intent filters match, and what to keep them in
     * step with. Each is a compound extension so that plain `.json` stays
     * unclaimed.
     */
    val EXTENSIONS: List<String> = listOf(
        ThemeCodec.FILE_EXTENSION,
        LayoutFile.FILE_EXTENSION,
        SettingsBackup.FILE_EXTENSION,
        ConfigBackup.FILE_EXTENSION,
        ConfigBackup.ENCRYPTED_FILE_EXTENSION,
        StickerPackFile.FILE_EXTENSION,
        IconPackFile.FILE_EXTENSION,
        SnippetFile.FILE_EXTENSION,
        PluginFile.FILE_EXTENSION,
        VocabPackFile.FILE_EXTENSION,
        // The one extension here that is not ours. A `.flex` is FlorisBoard's
        // theme file, and claiming it is the point: someone moving over opens
        // the file they already have. FlorisBoard's own filter is unaffected —
        // with both installed the user is asked which app to open it with.
        FlexTheme.FILE_EXTENSION,
        // Keyman's keyboard package, claimed for the same reason as `.flex`:
        // someone arriving with a keyboard they already use should be able to
        // open it. Keyman's own filter is unaffected, and with both installed
        // Android asks which app should handle it.
        KEYMAN_PACKAGE_EXTENSION,
    )

    /**
     * Keyman's package extension. A bare constant rather than a reference into
     * `:core:keyman`, because that module describes what is *inside* a package
     * and has no opinion about what a file is called.
     */
    const val KEYMAN_PACKAGE_EXTENSION = "kmp"

    /** First four bytes of every ZIP local file header. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** What a file at [uri] turned out to be. */
    sealed interface Opened {
        data class Theme(val theme: ThemeSpec) : Opened
        data class Layout(val layout: ImportedLayout) : Opened
        data class Config(val text: String, val parsed: ConfigBackup.Parsed) : Opened
        data class Snippets(val snippets: ImportedSnippets) : Opened
        data class Vocabulary(val pack: VocabPack) : Opened

        /** The older standalone `wmsettings.json`. */
        data class Settings(val text: String, val parsed: SettingsBackup.Parsed) : Opened

        /**
         * A ZIP whose manifest claims one of the archive formats. Only the
         * manifest is read here — each importer streams the archive itself and
         * repeats the format check, so both re-open [uri].
         */
        data object Stickers : Opened
        data object Icons : Opened

        /**
         * A FlorisBoard `.flex` theme extension, already converted.
         *
         * Its own case rather than a [Theme], and it carries the whole
         * [FlexResult] rather than only the themes: the dialog has to be able to
         * say that this came from another keyboard, how much of it survived, and
         * what did not. A converted theme must never be presented as one of ours.
         */
        data class FlorisTheme(val result: FlexResult) : Opened

        /**
         * A Keyman keyboard package: the grid, and the rules that decide what
         * its keys type.
         *
         * Carries the whole contents rather than a converted layout because the
         * rules are installed alongside it, and a package that turned out to
         * hold no grid still has something worth saying about it.
         */
        data class KeymanPackageFile(val contents: KeymanPackage.Contents) : Opened

        /**
         * An encrypted config bundle. Nothing but the header has been read: the
         * contents cannot be known until there is a passphrase to try, so the
         * importer re-opens [uri] once it has one. Deliberately carries no
         * bytes, for the same reason [Stickers] and [Icons] do not.
         */
        data object EncryptedConfig : Opened

        /**
         * A `.wmplugin`. Only the manifest is read here — importing re-opens the
         * archive, and the script is never touched just to work out what a file
         * is.
         */
        data object Plugin : Opened

        /** Readable, but not one of ours. */
        data object Unrecognized : Opened

        /** Gone, or no permission, or not readable at all. */
        data object Unreadable : Opened
    }

    /**
     * Reads enough of [uri] to say what it is. [name] is the display name, used
     * only for the theme check — see below. Blocking; call off the main thread.
     */
    fun identify(context: android.content.Context, uri: Uri, name: String): Opened {
        val head = runCatching {
            context.contentResolver.requireInputStream(uri).use { input ->
                val buffer = ByteArray(4)
                var read = 0
                while (read < buffer.size) {
                    val n = input.read(buffer, read, buffer.size - read)
                    if (n <= 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
        }.getOrNull() ?: return Opened.Unreadable
        if (head.contentEquals(ZIP_MAGIC)) return identifyArchive(context, uri)
        // Before the text read below, which on a large encrypted bundle would
        // decode megabytes of ciphertext as UTF-8 to produce a string that
        // could never match anything.
        if (BackupCrypto.looksEncrypted(head)) return Opened.EncryptedConfig

        val text = runCatching {
            context.contentResolver.requireInputStream(uri).use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return Opened.Unreadable

        // Exports from before the truncating write could carry the tail of an
        // older, longer file after the document. The proposal below keeps this
        // trimmed text, so what gets applied is what got recognised.
        return textKindFor(text.firstJsonDocument(), name)
    }

    /**
     * Which text format [text] holds, from its format tag — or, for the one
     * format that has none, from [name].
     *
     * Split out of [identify] so the decision is testable without a
     * `ContentResolver`. The ordering *is* the safety property here, and the
     * cases worth pinning are the negative ones: a foreign keyboard's layout
     * file carries no tag of ours, so it has to come back [Opened.Unrecognized]
     * rather than falling into the theme branch below.
     */
    internal fun textKindFor(text: String, name: String): Opened {
        // Tagged formats first, and in the order a tag can only mean one thing.
        ConfigBackup.decode(text)?.let { return Opened.Config(text, it) }
        SettingsBackup.decode(text)?.let { return Opened.Settings(text, it) }
        LayoutFile.decode(text)?.let { return Opened.Layout(it) }
        SnippetFile.decode(text)?.let { return Opened.Snippets(it) }
        VocabPackFile.decode(text)?.let { return Opened.Vocabulary(it) }
        // A theme has no tag and every field has a default, so decoding any JSON
        // object at all succeeds and yields an all-defaults theme. The file name
        // is the only evidence there is that this one was meant to be a theme.
        // Nothing untagged may be added after this line: the name check is what
        // stops the branch claiming every JSON file, and a second untagged
        // format would have nothing left to be told apart by.
        if (name.endsWith(".${ThemeCodec.FILE_EXTENSION}", ignoreCase = true)) {
            ThemeCodec.decode(text)?.let { return Opened.Theme(it) }
        }
        return Opened.Unrecognized
    }

    /**
     * Which archive format a ZIP holds, from the `format` tag in its manifest.
     *
     * Sticker packs and icon packs are both ZIPs with a `pack.json` at the
     * root, so the magic bytes alone can't tell them apart — reading the
     * manifest is the only way, and guessing would send every icon pack to the
     * sticker importer.
     */
    private fun identifyArchive(context: android.content.Context, uri: Uri): Opened {
        val manifest = runCatching {
            context.contentResolver.requireInputStream(uri).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    var scanned = 0
                    while (scanned++ < MANIFEST_SCAN_LIMIT) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        if (entry.name !in ARCHIVE_MANIFESTS) continue
                        // Both manifests are small; the tag is near the front.
                        // (InputStream.readNBytes is API 33; minSdk here is 24.)
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8 * 1024)
                        while (out.size() < MAX_MANIFEST_BYTES) {
                            val n = zip.read(buffer, 0, minOf(buffer.size, MAX_MANIFEST_BYTES - out.size()))
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                        }
                        return@use out.toByteArray().decodeToString()
                    }
                    null
                }
            }
        }.getOrNull() ?: return Opened.Unrecognized

        // Converting reads the whole archive, so it happens only once the
        // manifest has said the archive is worth reading.
        // Keyman's manifest has no format tag to check, so the file name it
        // arrived under is the evidence, the same way a .wmtheme.json is. The
        // read below is what actually confirms it: a manifest that names no
        // keyboard comes back null and the archive falls through as
        // unrecognised.
        if (isKeymanManifest(manifest)) {
            val contents = runCatching {
                context.contentResolver.requireInputStream(uri).use { KeymanPackage.read(it) }
            }.getOrNull()
            return if (contents != null) Opened.KeymanPackageFile(contents) else Opened.Unrecognized
        }
        if (isFlexManifest(manifest)) {
            val result = runCatching {
                context.contentResolver.requireInputStream(uri).use { FlexTheme.read(it) }
            }.getOrElse { FlexResult.Unreadable }
            return Opened.FlorisTheme(result)
        }
        return archiveKindFor(manifest)
    }

    /**
     * Which archive format a manifest claims. Split out of [identifyArchive] for
     * the same reason [textKindFor] is: the tag match is the whole decision, and
     * it is worth a test that presence of a manifest *name* is never enough.
     */
    internal fun archiveKindFor(manifest: String): Opened = when {
        manifest.contains("\"${IconPackFile.FORMAT}\"") -> Opened.Icons
        manifest.contains("\"${StickerPackFile.FORMAT}\"") -> Opened.Stickers
        manifest.contains("\"${PluginFile.FORMAT}\"") -> Opened.Plugin
        else -> Opened.Unrecognized
    }

    /**
     * Whether a manifest is a FlorisBoard theme extension's.
     *
     * The tag, never the file name: `extension.json` is a name plenty of things
     * use, and FlorisBoard has other extension kinds — language packs, keyboard
     * extensions — that carry the same manifest and nothing this app can read.
     */
    internal fun isFlexManifest(manifest: String): Boolean =
        manifest.contains("\"${FlexTheme.FORMAT}\"")

    /**
     * Entries to look at before giving up on finding the manifest. Matches the
     * cap the importers use, so a pack that zips its manifest last is still
     * recognised here rather than being refused by a check the import itself
     * would have passed.
     */
    private const val MANIFEST_SCAN_LIMIT = 400

    /** Enough of a manifest to carry its format tag, with room to spare. */
    private const val MAX_MANIFEST_BYTES = 64 * 1024

    /**
     * The manifest names the archive formats use. Sticker and icon packs share
     * `pack.json` and are told apart by their format tag; a plugin names its
     * manifest differently so that a `.wmplugin` is recognisable without
     * reading any Lua.
     */
    private val ARCHIVE_MANIFESTS =
        setOf(
            StickerPackFile.MANIFEST,
            PluginFile.MANIFEST,
            FlexTheme.MANIFEST,
            KeymanPackage.MANIFEST,
        )

    /**
     * Whether a manifest is Keyman's. Matched on the two fields every package
     * carries and nothing else does, because `kmp.json` has no format tag of its
     * own to check.
     */
    private fun isKeymanManifest(manifest: String): Boolean =
        manifest.contains("\"keyboards\"") && manifest.contains("\"system\"")

    /** The provider's display name for [uri], or its last path segment. */
    fun displayName(context: android.content.Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment.orEmpty()
    }
}

/**
 * The activity the manifest's file associations point at: a single confirm
 * dialog over whatever was on screen, then the import, then a result. It never
 * opens the settings UI — someone who tapped a theme in their downloads wants
 * the theme, not a tour of the app.
 */
class ImportFileActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri == null) {
            finish()
            return
        }
        val repository = SettingsRepository(applicationContext)
        // A layout import resolves against the asset layouts, same as the
        // settings app does before its first frame.
        AssetLayouts.load(applicationContext.assets)
        setContent {
            val settings by repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            settings?.let { loaded ->
                AppTheme(loaded) {
                    ImportFileDialog(repository, uri) { finish() }
                }
            }
        }
    }
}

/**
 * The pending import, once the file has been read and named.
 *
 * The title stays a resource id so the dialog resolves it, which keeps the
 * heading in the language the screen is drawn in. A title that counts something
 * sets [titlePluralRes] and [titleQuantity] instead of [titleRes]; a title that
 * names the theme, layout or plugin inside the file sets [titleArg].
 */
private data class ImportProposal(
    @StringRes val titleRes: Int = 0,
    @PluralsRes val titlePluralRes: Int = 0,
    val titleQuantity: Int = 0,
    val titleArg: String? = null,
    val body: String,
    /** Lines describing what had to be fixed to make the file usable. */
    val repairs: List<String> = emptyList(),
    @StringRes val confirmLabelRes: Int = CommonR.string.common_import,
    val apply: (suspend () -> String)? = null,
    /**
     * Set instead of [apply] by a file that cannot be read until the user says
     * something. The dialog draws a passphrase field and hands over what was
     * typed. Separate from [apply] rather than a parameter on it, so that the
     * eight formats which need no such thing say nothing about it.
     */
    val applyWithPassphrase: (suspend (String) -> String)? = null,
) {
    /** Whether there is anything to press Import for. */
    val actionable: Boolean get() = apply != null || applyWithPassphrase != null
}

/** The proposal's heading, resolved against the screen's resources. */
@Composable
private fun proposalTitle(proposal: ImportProposal): String {
    if (proposal.titlePluralRes != 0) {
        return pluralStringResource(
            proposal.titlePluralRes,
            proposal.titleQuantity,
            proposal.titleQuantity,
        )
    }
    val arg = proposal.titleArg
    return if (arg == null) {
        stringResource(proposal.titleRes)
    } else {
        stringResource(proposal.titleRes, arg)
    }
}

@Composable
private fun ImportFileDialog(
    repository: SettingsRepository,
    uri: Uri,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var opened by remember { mutableStateOf<WMFileTypes.Opened?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        opened = withContext(Dispatchers.IO) {
            WMFileTypes.identify(context, uri, WMFileTypes.displayName(context, uri))
        }
    }

    val messageText = message
    if (messageText != null) {
        AlertDialog(
            onDismissRequest = onClose,
            text = { Text(messageText) },
            confirmButton = {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_ok)) }
            },
        )
        return
    }

    val state = opened
    if (state == null || working) {
        // Reading a backup with sticker packs in it is not instant, and neither
        // is writing one back out.
        val progress = if (working) {
            R.string.import_progress_importing
        } else {
            R.string.import_progress_reading
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(progress)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        return
    }

    val proposal = rememberProposal(state, repository, context, uri)
    var passphrase by remember { mutableStateOf("") }
    val needsPassphrase = proposal.applyWithPassphrase != null
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(proposalTitle(proposal)) },
        text = {
            Column {
                Text(proposal.body)
                if (proposal.repairs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.import_repairs_title), fontWeight = FontWeight.Medium)
                    for (line in proposal.repairs) Text("• $line")
                }
                if (needsPassphrase) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.import_encrypted_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        // A password field, so this keyboard does not learn the
                        // passphrase into the dictionary that the backup carries.
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
        },
        confirmButton = {
            val apply = proposal.apply
            val applyWithPassphrase = proposal.applyWithPassphrase
            when {
                applyWithPassphrase != null -> TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        working = true
                        scope.launch { message = applyWithPassphrase(passphrase); working = false }
                    },
                ) { Text(stringResource(proposal.confirmLabelRes)) }

                apply != null -> TextButton(onClick = {
                    working = true
                    scope.launch { message = apply(); working = false }
                }) { Text(stringResource(proposal.confirmLabelRes)) }

                else -> TextButton(onClick = onClose) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            }
        },
        dismissButton = {
            if (proposal.actionable) {
                TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

/**
 * What a Keyman package offers, and what installing it does.
 *
 * A package is two things at once: a grid, and the rules that decide what its
 * keys type. Both are installed together, which is the whole reason to accept a
 * package rather than the touch layout on its own.
 *
 * The rules land in the same directory the downloader writes to, so a keyboard
 * installed from a file behaves exactly like one whose rules were fetched.
 */
private fun keymanProposal(
    contents: KeymanPackage.Contents,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    val doc = contents.touchLayoutJson
        ?.let { KeymanTouchLayoutReader.parse(it) as? KeymanResult.Success }
        ?.value
    val converted = doc
        ?.let { TouchLayoutConverter.convert(it, contents.keyboardId, contents.name) }
        ?.let { it as? KeymanResult.Success }
        ?.value

    if (converted == null) {
        return ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = contents.name,
            body = context.getString(R.string.import_keyman_no_grid_body),
            apply = { context.getString(R.string.import_keyman_no_grid_body) },
        )
    }

    // The language the package names, when it is one this build knows. A tag we
    // do not carry falls back to the script guess rather than inventing an
    // entry, which is the same rule the bundled set follows.
    val declared = contents.languages.firstOrNull { LanguageRegistry.byId(it).id == it }

    return ImportProposal(
        titleRes = R.string.import_name_title,
        titleArg = contents.name,
        body = context.getString(
            if (contents.rules != null) R.string.import_keyman_body_with_rules
            else R.string.import_keyman_body_no_rules,
        ),
        repairs = converted.repairNotes.map { it.format(context.resources) },
        apply = {
            val id = "custom_${System.currentTimeMillis()}"
            val binding = contents.rules?.let {
                KeymanBinding(keyboardId = contents.keyboardId)
            }
            repository.upsertCustomLayout(
                converted.layout.copy(
                    id = id,
                    langId = declared ?: "en",
                    // Only claim a binding when the rules actually landed, or
                    // the engine would look for a file that is not there.
                    keyman = binding,
                ),
            )
            contents.rules?.let { bytes ->
                val store = KeymanRuleStore(context)
                store.ruleFile(contents.keyboardId)?.let { file ->
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    store.invalidate(contents.keyboardId)
                }
            }
            context.getString(R.string.import_done_name, contents.name)
        },
    )
}

/**
 * Turns a read file into what the dialog should say and do. Every branch mirrors
 * the equivalent in-app import so an opened file and a picked file behave the
 * same — including reading first and asking before anything is written.
 */
@Composable
private fun rememberProposal(
    state: WMFileTypes.Opened,
    repository: SettingsRepository,
    context: android.content.Context,
    uri: Uri,
): ImportProposal = remember(state) {
    when (state) {
        is WMFileTypes.Opened.Theme -> {
            val themeName = state.theme.name
                .ifBlank { context.getString(R.string.import_theme_fallback_name) }
            ImportProposal(
                titleRes = R.string.import_name_title,
                titleArg = themeName,
                body = context.getString(R.string.import_theme_body),
                apply = {
                    val id = "custom_${System.currentTimeMillis()}"
                    // Fresh ids first — the theme's and every variant's — so
                    // extracted image filenames key off them and stay unique
                    // against the themes already saved.
                    repository.upsertCustomTheme(
                        state.theme.withFreshIds(id)
                            .withExtractedImages(
                                File(context.filesDir, "theme_images").apply { mkdirs() },
                            ),
                    )
                    repository.setKeyboardThemeId(id)
                    context.getString(R.string.import_done_name, themeName)
                },
            )
        }

        is WMFileTypes.Opened.FlorisTheme -> florisProposal(state.result, repository, context)

        is WMFileTypes.Opened.KeymanPackageFile ->
            keymanProposal(state.contents, repository, context)

        is WMFileTypes.Opened.Layout -> ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = state.layout.layout.name,
            body = context.getString(R.string.import_layout_body),
            // The repair lines are resource-backed; the file was read off the
            // main thread, so they are worded here rather than there.
            repairs = state.layout.repairNotes.map { it.format(context.resources) },
            apply = {
                repository.upsertCustomLayout(
                    state.layout.layout.copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, state.layout.layout.name)
            },
        )

        is WMFileTypes.Opened.Config -> {
            val counts = repository.describeConfig(state.parsed)
            val hasSecrets = repository.configContainsSecrets(state.parsed)
            ImportProposal(
                titleRes = R.string.import_backup_title,
                body = buildString {
                    append(context.getString(R.string.import_backup_contains))
                    append("\n")
                    for ((section, count) in counts) {
                        append("\n")
                        append(
                            context.getString(
                                R.string.import_backup_section_line,
                                sectionLabel(context, section),
                                sectionSummary(context, section, count),
                            ),
                        )
                    }
                    append("\n\n").append(context.getString(R.string.import_backup_merge_note))
                    if (hasSecrets) {
                        append("\n\n").append(context.getString(R.string.import_api_keys_note))
                    }
                },
                apply = {
                    when (val result = repository.importConfig(state.text)) {
                        is SettingsRepository.ConfigImportResult.Applied -> buildString {
                            if (result.restored.isEmpty()) {
                                append(context.getString(R.string.import_backup_nothing))
                            } else {
                                append(
                                    context.getString(
                                        R.string.import_backup_restored,
                                        // Each name carries its own mid-sentence
                                        // form. A translated name cannot be put
                                        // into lower case in code.
                                        result.restored.joinToString {
                                            sectionLabelLowercase(context, it)
                                        },
                                    ),
                                )
                            }
                            if (result.settingsFailed) {
                                append("\n\n")
                                append(context.getString(R.string.import_backup_settings_failed))
                            }
                        }
                        SettingsRepository.ConfigImportResult.NotABackup ->
                            context.getString(R.string.import_not_a_backup)
                    }
                },
            )
        }

        WMFileTypes.Opened.EncryptedConfig -> ImportProposal(
            titleRes = R.string.import_encrypted_title,
            body = context.getString(R.string.import_encrypted_body),
            // Nothing can be said about what is inside until it opens, so the
            // usual "this file contains…" summary is not available here. The
            // ordinary confirmation appears afterwards, on the decrypted text.
            applyWithPassphrase = { entered ->
                val decrypted = withContext(Dispatchers.IO) {
                    runCancellable {
                        context.contentResolver.requireInputStream(uri).use {
                            BackupCrypto.decrypt(it, entered.toCharArray())
                        }
                    }.getOrNull()
                }
                when (decrypted) {
                    is BackupCrypto.DecryptResult.Ok ->
                        when (val result = repository.importConfig(decrypted.text)) {
                            is SettingsRepository.ConfigImportResult.Applied ->
                                if (result.restored.isEmpty()) {
                                    context.getString(R.string.import_backup_nothing)
                                } else {
                                    context.getString(
                                        R.string.import_backup_restored,
                                        result.restored.joinToString {
                                            sectionLabelLowercase(context, it)
                                        },
                                    )
                                }
                            SettingsRepository.ConfigImportResult.NotABackup ->
                                context.getString(R.string.import_not_a_backup)
                        }
                    // The tag cannot tell a wrong passphrase from a damaged
                    // file, and pretending otherwise would be a lie in one of
                    // the two cases. Say both.
                    else -> context.getString(R.string.import_encrypted_failed)
                }
            },
        )

        is WMFileTypes.Opened.Settings -> ImportProposal(
            titleRes = R.string.import_settings_title,
            body = buildString {
                append(
                    context.resources.getQuantityString(
                        R.plurals.import_settings_overwrite,
                        state.parsed.entries.size,
                        state.parsed.entries.size,
                    ),
                )
                if (state.parsed.containsSecrets) {
                    append("\n\n").append(context.getString(R.string.import_api_keys_note))
                }
                if (state.parsed.skipped > 0) {
                    append("\n\n")
                    append(
                        context.resources.getQuantityString(
                            R.plurals.import_settings_skipped,
                            state.parsed.skipped,
                            state.parsed.skipped,
                        ),
                    )
                }
            },
            apply = {
                when (val result = repository.importSettings(state.text)) {
                    is SettingsRepository.ImportResult.Applied ->
                        context.resources.getQuantityString(
                            R.plurals.import_settings_restored,
                            result.settings,
                            result.settings,
                        )
                    SettingsRepository.ImportResult.RolledBack ->
                        context.getString(R.string.import_settings_rolled_back)
                    SettingsRepository.ImportResult.NotABackup ->
                        context.getString(R.string.import_not_a_settings_backup)
                }
            },
        )

        is WMFileTypes.Opened.Snippets -> ImportProposal(
            titlePluralRes = R.plurals.import_snippets_title,
            titleQuantity = state.snippets.snippets.size,
            body = context.getString(R.string.import_snippets_body),
            repairs = state.snippets.repairs.map { it.resolve(context) },
            apply = {
                val store = withContext(Dispatchers.IO) {
                    SnippetStore(File(context.filesDir, "snippets/snippets.json"))
                }
                // Ids in the file are ignored; the store assigns fresh ones, so
                // importing the same pack twice gives two independent sets
                // rather than silently overwriting the first. Whole snippets go
                // in, not a handful of named fields: rebuilding them here is
                // how a file's patterns, its ask-first flag and its folders get
                // dropped on the floor with nothing to say so.
                withContext(Dispatchers.IO) {
                    store.addAll(state.snippets.snippets, state.snippets.folders)
                    // The adds are in-memory only; save() is what writes the file.
                    store.save()
                }
                context.resources.getQuantityString(
                    R.plurals.import_snippets_done,
                    state.snippets.snippets.size,
                    state.snippets.snippets.size,
                )
            },
        )

        is WMFileTypes.Opened.Vocabulary -> ImportProposal(
            titlePluralRes = R.plurals.import_vocab_title,
            titleQuantity = state.pack.words.size,
            body = context.getString(R.string.import_vocab_body, state.pack.meta.name.ifBlank { WMFileTypes.displayName(context, uri) }),
            apply = {
                // The stream is re-opened rather than the parsed pack re-encoded,
                // so the file lands byte for byte as it was shared.
                val result = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        VocabPacks.import(
                            context.filesDir,
                            state.pack.meta.langId.ifBlank { "en" },
                            WMFileTypes.displayName(context, uri),
                            stream,
                        )
                    }
                }
                when (result) {
                    is VocabPacks.ImportResult.Imported -> context.resources.getQuantityString(
                        R.plurals.import_vocab_done, result.wordCount, result.wordCount,
                    )
                    else -> context.getString(R.string.import_vocab_error)
                }
            },
        )

        WMFileTypes.Opened.Stickers -> ImportProposal(
            titleRes = R.string.import_stickers_title,
            body = context.getString(R.string.import_stickers_body),
            apply = {
                val store = StickerPackStore.get(context)
                // Names a pack whose own file gives no name.
                val fallbackName = context.getString(ContentR.string.core_content_sticker_pack_imported_label)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri)
                            .use { StickerPackFile.import(it, store, fallbackName) }
                    }.getOrDefault(StickerImportResult.Failed)
                }
                when (result) {
                    is StickerImportResult.Imported -> buildString {
                        append(
                            context.resources.getQuantityString(
                                R.plurals.import_stickers_done,
                                result.pack.stickers.size,
                                result.pack.name,
                                result.pack.stickers.size,
                            ),
                        )
                        if (result.repairs.isNotEmpty()) {
                            append("\n\n").append(context.getString(R.string.import_repairs_title))
                            // The reader hands back a resource and its
                            // arguments, so the note is worded here.
                            for (line in result.repairs) {
                                append("\n• ${line.resolve(context)}")
                            }
                        }
                    }
                    StickerImportResult.NotAStickerPack ->
                        context.getString(R.string.import_not_a_sticker_pack)
                    is StickerImportResult.NoStickers -> buildString {
                        append(context.getString(R.string.import_stickers_none_read))
                        for (line in result.repairs.take(5)) {
                            append("\n• ${line.resolve(context)}")
                        }
                    }
                    StickerImportResult.TooManyPacks ->
                        context.resources.getQuantityString(
                            R.plurals.import_stickers_too_many,
                            StickerPackStore.MAX_PACKS,
                            StickerPackStore.MAX_PACKS,
                        )
                    StickerImportResult.Failed ->
                        context.getString(R.string.import_file_unreadable)
                }
            },
        )

        WMFileTypes.Opened.Icons -> ImportProposal(
            titleRes = R.string.import_icons_title,
            body = context.getString(R.string.import_icons_body),
            apply = {
                val store = IconPackStore.get(context)
                // The importer names a pack whose own file gives no name, and it
                // runs off the main thread, so the wording is resolved here.
                val fallbackName = context.getString(IconsR.string.core_icons_pack_imported_label)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri)
                            .use { IconPackFile.import(it, store, fallbackName) }
                    }.getOrDefault(IconImportResult.Failed)
                }
                if (result is IconImportResult.Imported) repository.setIconPack(result.pack.id)
                describeImport(context, result)
            },
        )

        WMFileTypes.Opened.Plugin -> pluginProposal(context, uri)

        WMFileTypes.Opened.Unrecognized -> ImportProposal(
            titleRes = R.string.import_unrecognized_title,
            body = context.getString(R.string.import_unrecognized_body),
            apply = null,
        )

        WMFileTypes.Opened.Unreadable -> ImportProposal(
            titleRes = R.string.import_unreadable_title,
            body = context.getString(R.string.import_unreadable_body),
            apply = null,
        )
    }
}

/**
 * The confirmation for a FlorisBoard theme extension.
 *
 * Worded as a conversion throughout, and never as an ordinary theme import. The
 * user's file describes a keyboard this app is not, so the dialog leads with the
 * count of style rules that had somewhere to go and lists what did not, rather
 * than showing a theme name and implying the file came across whole.
 *
 * The themes are saved but not switched to. A native theme import activates
 * immediately because there is nothing to check; a converted one is exactly the
 * thing worth looking at before it becomes the keyboard, and the theme editor is
 * where the parts that could not be carried get finished.
 */
private fun florisProposal(
    result: FlexResult,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal = when (result) {
    is FlexResult.Converted -> ImportProposal(
        titleRes = R.string.import_floris_title,
        body = buildString {
            append(
                context.getString(
                    R.string.import_floris_body,
                    result.mappedRuleCount,
                    result.ruleCount,
                ),
            )
            val credit = result.authors.joinToString().takeIf { it.isNotBlank() }
            if (credit != null || result.license.isNotBlank()) {
                append("\n\n")
                append(
                    context.getString(
                        R.string.import_floris_credit,
                        credit ?: context.getString(R.string.import_floris_credit_unknown),
                        result.license.ifBlank { context.getString(R.string.import_floris_credit_unknown) },
                    ),
                )
            }
        },
        repairs = result.dropped.map { context.getString(florisDroppedRes(it)) },
        apply = {
            val dir = withContext(Dispatchers.IO) {
                File(context.filesDir, "theme_images").apply { mkdirs() }
            }
            val base = "custom_${System.currentTimeMillis()}"
            val stored = withContext(Dispatchers.IO) {
                result.themes.mapIndexed { index, converted ->
                    // One id per theme, and distinct: a day and night pair would
                    // otherwise write their images over each other, since the
                    // extracted file names are keyed on the id.
                    converted.stored(if (index == 0) base else "${base}_v$index", dir)
                }
            }
            // One entry, not N: an extension's themes are the looks of one
            // theme, named after the extension itself.
            val entry = groupAsFamily(stored, result.title)
            repository.upsertCustomTheme(entry)
            if (stored.size >= 2) {
                context.getString(
                    R.string.import_floris_done_family,
                    themeFamilyName(context, entry),
                    stored.size,
                )
            } else {
                context.resources.getQuantityString(
                    R.plurals.import_floris_done,
                    stored.size,
                    stored.size,
                )
            }
        },
    )

    FlexResult.SnyggV1 -> ImportProposal(
        titleRes = R.string.import_floris_old_title,
        body = context.getString(R.string.import_floris_old_body),
        apply = null,
    )

    FlexResult.NotAFlex, FlexResult.Unreadable -> ImportProposal(
        titleRes = R.string.import_unrecognized_title,
        body = context.getString(R.string.import_floris_unreadable_body),
        apply = null,
    )
}

/**
 * A converted theme as one this app can store: image bytes base64'd into the
 * fields the theme format carries them in, then written out to app-private
 * storage by the same call a native theme import uses.
 *
 * The base64 hop looks redundant next to writing the bytes straight to disk, and
 * is not: `withExtractedImages` owns the file naming and the rule that a theme
 * may only ever point inside our own storage. Going around it would mean a
 * second place that decides where a theme's images live.
 */
internal fun ConvertedTheme.stored(id: String, dir: File): ThemeSpec {
    fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    return theme.copy(
        id = id,
        backgroundImageBase64 = images[FlexTheme.IMAGE_BACKGROUND]?.let(::encode),
        assets = images.filterKeys { it != FlexTheme.IMAGE_BACKGROUND }
            .mapValues { (_, bytes) -> encode(bytes) },
    ).withExtractedImages(dir)
}

@StringRes
private fun florisDroppedRes(dropped: FlexUnsupported): Int = when (dropped) {
    FlexUnsupported.SNYGG_V1 -> R.string.import_floris_dropped_old
    FlexUnsupported.ELEVATION -> R.string.import_floris_dropped_elevation
    FlexUnsupported.PER_CORNER_RADIUS -> R.string.import_floris_dropped_corners
    FlexUnsupported.PER_ELEMENT_SPACING -> R.string.import_floris_dropped_spacing
    FlexUnsupported.FONT -> R.string.import_floris_dropped_font
    FlexUnsupported.DYNAMIC_COLOR -> R.string.import_floris_dropped_dynamic
    FlexUnsupported.UNKNOWN_ELEMENT -> R.string.import_floris_dropped_unknown
    FlexUnsupported.LOW_CONTRAST_FALLBACK -> R.string.import_floris_dropped_contrast
}

/**
 * The confirmation for installing a plugin from a file.
 *
 * Says what the plugin claims to be and what it would be allowed to do *before*
 * offering to install it, because this is the one import where the payload is
 * somebody else's code rather than somebody else's data. The manifest is read
 * here; the script is not read at all until the user opens the plugin.
 */
private fun pluginProposal(context: android.content.Context, uri: Uri): ImportProposal {
    val read = runCatching {
        context.contentResolver.requireInputStream(uri).use { PluginFile.readManifest(it) }
    }.getOrNull()

    if (!PluginStore.get(context).subsystemEnabled()) {
        return ImportProposal(
            titleRes = R.string.import_plugin_off_title,
            body = context.getString(R.string.import_plugin_off_body),
            apply = null,
        )
    }

    val manifest = (read as? PluginManifestResult.Ok)
        ?: return ImportProposal(
            titleRes = R.string.import_plugin_rejected_title,
            body = (read as? PluginManifestResult.Rejected)?.reasonText?.resolve(context)
                ?: context.getString(R.string.import_not_a_plugin),
            apply = null,
        )

    val capabilities = if (manifest.permissions.isEmpty()) {
        context.getString(R.string.import_plugin_no_permissions)
    } else {
        manifest.permissions.joinToString(
            prefix = context.getString(R.string.import_plugin_permissions_intro) + "\n",
            separator = "\n",
        ) { "• " + context.getString(it.labelRes) }
    }

    return ImportProposal(
        titleRes = R.string.import_plugin_install_title,
        titleArg = manifest.manifest.name,
        body = buildString {
            append(
                manifest.manifest.description.ifBlank {
                    context.getString(R.string.import_plugin_default_description)
                },
            )
            append("\n\n")
            append(
                if (manifest.manifest.author.isNotBlank()) {
                    context.getString(
                        R.string.import_plugin_version_by_author,
                        manifest.manifest.pluginVersion,
                        manifest.manifest.author,
                    )
                } else {
                    context.getString(
                        R.string.import_plugin_version,
                        manifest.manifest.pluginVersion,
                    )
                },
            )
            append("\n\n").append(capabilities)
            append("\n\n").append(context.getString(R.string.import_plugin_sandbox_note))
        },
        apply = {
            val store = PluginStore.get(context)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri)
                        .use { PluginFile.import(it, store) }
                }.getOrDefault(PluginImportResult.Failed)
            }
            when (result) {
                is PluginImportResult.Imported ->
                    if (result.replaced) {
                        context.getString(R.string.import_plugin_updated, result.plugin.name)
                    } else {
                        context.getString(R.string.import_plugin_installed, result.plugin.name)
                    }

                is PluginImportResult.Rejected -> result.reasonText.resolve(context)
                PluginImportResult.NotAPlugin -> context.getString(R.string.import_not_a_plugin)
                PluginImportResult.TooManyPlugins ->
                    context.resources.getQuantityString(
                        R.plurals.import_plugin_too_many,
                        PluginStore.MAX_PLUGINS,
                        PluginStore.MAX_PLUGINS,
                    )

                PluginImportResult.Failed -> context.getString(R.string.import_plugin_failed)
            }
        },
    )
}
