package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import com.wasimaster.wmkeyboard.core.vocab.VocabPackFile
import com.wasimaster.wmkeyboard.core.vocab.VocabPack
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.IntentCompat
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.emoji.KaomojiFiles
import com.wasimaster.wmkeyboard.core.emoji.KaomojiStore
import com.wasimaster.wmkeyboard.core.feedback.SoundFile
import com.wasimaster.wmkeyboard.core.feedback.SoundImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictDownloadManager
import com.wasimaster.wmkeyboard.core.input.composer.DoublePinyinProfile
import com.wasimaster.wmkeyboard.core.input.composer.FlexLanguagePack
import com.wasimaster.wmkeyboard.core.input.imported.Conversion
import com.wasimaster.wmkeyboard.core.input.imported.ConvertedMethod
import com.wasimaster.wmkeyboard.core.input.imported.InputMethodImport
import com.wasimaster.wmkeyboard.core.input.imported.SourceFile
import com.wasimaster.wmkeyboard.core.keyman.KeymanImport
import com.wasimaster.wmkeyboard.core.layout.ForeignLayouts
import com.wasimaster.wmkeyboard.core.prediction.AospCombined
import com.wasimaster.wmkeyboard.core.prediction.AospDictionary
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.theme.HeliResult
import com.wasimaster.wmkeyboard.core.theme.HeliTheme
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.icons.IconImportResult
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.layout.AssetLayouts
import com.wasimaster.wmkeyboard.core.keyman.ConvertedKeymanLayout
import com.wasimaster.wmkeyboard.core.keyman.KeymanResult
import com.wasimaster.wmkeyboard.core.keyman.KeymanRuleStore
import com.wasimaster.wmkeyboard.core.keyman.KeymanTouchLayoutReader
import com.wasimaster.wmkeyboard.core.keyman.TouchLayoutConverter
import com.wasimaster.wmkeyboard.core.layout.KeymanBinding
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.keyman.KeymanPackage
import com.wasimaster.wmkeyboard.core.layout.ConvertedLayout
import com.wasimaster.wmkeyboard.core.layout.DesktopLayouts
import com.wasimaster.wmkeyboard.core.layout.FutoLayouts
import com.wasimaster.wmkeyboard.core.layout.ImportedLayout
import com.wasimaster.wmkeyboard.core.layout.KeysCafeLayouts
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.layout.MultilingLayouts
import com.wasimaster.wmkeyboard.core.layout.UnexpectedLayouts
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginImportResult
import com.wasimaster.wmkeyboard.core.plugins.PluginManifestResult
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.resolve
import com.wasimaster.wmkeyboard.core.settings.BackupCrypto
import com.wasimaster.wmkeyboard.core.settings.ConfigBackup
import com.wasimaster.wmkeyboard.core.settings.DeviceForm
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsBackup
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignBackup
import com.wasimaster.wmkeyboard.core.settings.foreign.ForeignBackups
import com.wasimaster.wmkeyboard.core.prediction.UserLexicon
import com.wasimaster.wmkeyboard.core.snippets.GboardDictionary
import com.wasimaster.wmkeyboard.core.snippets.ImportedSnippets
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetPayload
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.whatsapp.WaStickersFile
import com.wasimaster.wmkeyboard.core.theme.ConvertedTheme
import com.wasimaster.wmkeyboard.core.theme.FlexResult
import com.wasimaster.wmkeyboard.core.theme.FlexTheme
import com.wasimaster.wmkeyboard.core.theme.FutoTheme
import com.wasimaster.wmkeyboard.core.theme.FutoThemeResult
import com.wasimaster.wmkeyboard.core.theme.FutoThemeUnsupported
import com.wasimaster.wmkeyboard.core.theme.dynamicSnyggPalette
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.theme.ConvertedFont
import com.wasimaster.wmkeyboard.core.theme.FlexUnsupported
import com.wasimaster.wmkeyboard.core.theme.GboardResult
import com.wasimaster.wmkeyboard.core.theme.GboardTheme
import com.wasimaster.wmkeyboard.core.theme.HeliUnsupported
import com.wasimaster.wmkeyboard.core.theme.MultilingTheme
import com.wasimaster.wmkeyboard.core.theme.MultilingThemeResult
import com.wasimaster.wmkeyboard.core.theme.MultilingUnsupported
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.ThemeSpec
import com.wasimaster.wmkeyboard.core.theme.groupAsFamily
import com.wasimaster.wmkeyboard.core.theme.themeFamilyName
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import com.wasimaster.wmkeyboard.core.util.MultilingExport
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import com.wasimaster.wmkeyboard.core.util.readCapped
import com.wasimaster.wmkeyboard.core.util.requireInputStream
import com.wasimaster.wmkeyboard.core.util.runCancellable
import com.wasimaster.wmkeyboard.content.R as ContentR
import com.wasimaster.wmkeyboard.feedback.R as FeedbackR
import com.wasimaster.wmkeyboard.icons.R as IconsR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opening one of the keyboard's own files from outside the app — a file
 * manager, a chat app's "open with", a downloads notification.
 *
 * Every format the keyboard exports is either JSON or a ZIP, so the manifest
 * filters on the *file name* first (`pathPattern` per extension), which is why
 * each export writes a compound extension: `.wmtheme.json` rather than
 * `.json`. A name is the precise signal, and it is what keeps a file of ours
 * out of the chooser's crowd of unknown binaries.
 *
 * A name is not always there, though. A provider may hand out a URI with no
 * file name in it at all — the downloads provider answers `msf:19` for
 * anything MediaStore has indexed — and every pattern misses those, which used
 * to mean a theme downloaded in a browser opened in some other keyboard and
 * never in this one. So the manifest also claims `application/json` and
 * `application/octet-stream` outright, and this app is now offered for files
 * that are nobody's business here. [Opened.Text] is the other half of that
 * bargain: anything readable that turns out not to be ours opens in the editor
 * rather than on an error.
 *
 * The extension only gets the file here. What is actually *done* with it is
 * decided by reading it: every format but the theme carries a format tag, so
 * a `.wmlayout.json` holding a backup still restores a backup. Everything the
 * app imports anywhere is recognised the same way, by its contents: fonts and
 * sounds by their headers, other keyboards' files by their structure (see
 * [FileSniff]), and the files the download screens install by their published
 * names. Only plain text that could be anything (a word list, a note) is left
 * to the editor.
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
        SoundPackFile.FILE_EXTENSION,
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
        // A WhatsApp sticker pack as the sticker-maker apps export it, claimed
        // for the same reason again: whoever has one made it for WhatsApp and
        // wants it here too. The apps that write the format keep their own
        // filter, and with one installed Android asks which app should open it.
        WaStickersFile.FILE_EXTENSION,
        // A Samsung Keyboard grid shared from Keys Cafe, claimed for the same
        // reason again: the file is somebody's own arrangement, and opening it
        // is how they bring it over. Keys Cafe keeps its own filter.
        KeysCafeLayouts.FILE_EXTENSION,
        // A Windows layout's MSKLC source and a macOS layout from Ukelele,
        // claimed for the same reason again: for a language nobody ships a
        // phone layout for, the desktop file is often the only one there is.
        // Neither extension means anything else, and the desktop tools that
        // own them have no Android app to compete with. XKB files have no
        // extension at all, so they are recognised by their contents alone.
        DesktopLayouts.KLC_EXTENSION,
        DesktopLayouts.KEYLAYOUT_EXTENSION,
    )

    /**
     * Keyman's package extension. A bare constant rather than a reference into
     * `:core:keyman`, because that module describes what is *inside* a package
     * and has no opinion about what a file is called.
     */
    const val KEYMAN_PACKAGE_EXTENSION = "kmp"

    /** First four bytes of every ZIP local file header. */
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private const val GZIP_MAGIC_1: Byte = 0x1f
    private const val GZIP_MAGIC_2: Byte = 0x8b.toByte()

    /** What a file at [uri] turned out to be. */
    sealed interface Opened {
        data class Theme(val theme: ThemeSpec) : Opened
        data class Layout(val layout: ImportedLayout) : Opened
        data class Config(val text: String, val parsed: ConfigBackup.Parsed) : Opened
        data class Snippets(val snippets: ImportedSnippets) : Opened
        data class Vocabulary(val pack: VocabPack) : Opened

        /**
         * A FUTO Keyboard layout, already converted.
         *
         * Its own case rather than a [Layout], for the same reason a
         * [FlorisTheme] is not a [Theme]: the file came from another keyboard,
         * only the letters came across, and the language is a guess that has to
         * be settled before the grid is stored.
         */
        data class FutoLayout(val converted: ConvertedLayout) : Opened

        /**
         * A Samsung Keyboard grid shared from Keys Cafe, already converted. Its
         * own case for the reason [FutoLayout] is: the language is a guess to
         * settle, and the body has to say that this one keeps its whole frame.
         */
        data class KeysCafeLayout(val converted: ConvertedLayout) : Opened

        /**
         * An Unexpected Keyboard or CleverKeys layout, already converted. Its
         * own case for the reason [FutoLayout] is: the language is a guess to
         * settle, and the body says the corner swipes came across.
         */
        data class UnexpectedLayout(val converted: ConvertedLayout) : Opened

        /**
         * A computer keyboard's layout, already converted: a Windows `.klc`,
         * a macOS `.keylayout`, or an XKB `symbols` file, which holds one
         * layout per variant. Its own case for the reason [FutoLayout] is, and
         * a list because of the XKB variants.
         */
        data class DesktopLayoutFile(val layouts: List<ConvertedLayout>) : Opened

        /**
         * Text from Multiling O Keyboard, already converted: one layout, or a
         * settings export holding every layout its user made and the colours
         * of their theme. [theme] is null when the text has no colours, which
         * is every layout shared on its own.
         */
        data class MultilingFile(
            val layouts: List<ConvertedLayout>,
            val theme: MultilingThemeResult.Converted?,
        ) : Opened

        /**
         * Somebody else's snippets, read into snippets of ours: an Espanso
         * match file, or a table of them from another text expander or a
         * spreadsheet. [SnippetPayload.Parsed.source] says which.
         *
         * Carries the whole [SnippetPayload.Parsed] rather than the snippets
         * alone, because the notes are the point of the dialog — an Espanso file
         * can say things this app has no equivalent for, and finding that out
         * after the import is worse than being told and deciding.
         */
        data class ForeignSnippets(val parsed: SnippetPayload.Parsed) : Opened

        /**
         * Gboard's personal dictionary export. Its own case rather than a
         * [ForeignSnippets], because only half of it is snippets: the words
         * with no shortcut go to the personal dictionary.
         */
        data class GboardDictionaryFile(val contents: GboardDictionary.Contents) : Opened

        /**
         * Another keyboard's whole backup: HeliBoard, LeanType, FUTO,
         * FlorisBoard, CleverKeys or AnySoftKeyboard, already read and
         * converted. Not a [Config]: nothing in it is ours, and the dialog for
         * it is a list of everything inside to tick or leave rather than one
         * question.
         */
        data class ForeignBackupFile(val backup: ForeignBackup) : Opened

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
         * A WhatsApp sticker pack, the `.wastickers` the sticker-maker apps
         * export. Carries what the dialog needs to say — the title, the
         * author and the count — and nothing else: the importer re-opens
         * [uri] and streams the archive itself, as the sticker importer does.
         */
        data class WhatsAppStickers(val header: WaStickersFile.Header) : Opened

        /**
         * A key-sound pack. A ZIP with the same `pack.json` sticker and icon
         * packs carry, told apart by its format tag like those two.
         */
        data object SoundPack : Opened

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
         * A FUTO Keyboard custom theme ZIP, already converted. Its own case for
         * the reason [FlorisTheme] is: the dialog has to say that it came from
         * another keyboard and what did not come across.
         */
        data class FutoThemeFile(val result: FutoThemeResult.Converted) : Opened

        /**
         * A Gboard theme ZIP or an Rboard pack of them, already converted.
         * Its own case for the reason [FlorisTheme] is: the dialog has to say
         * that it came from another keyboard and how much of it survived.
         */
        data class GboardThemeFile(val result: GboardResult) : Opened

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

        /**
         * Readable text that is none of the formats above: someone else's JSON,
         * a config file, a note that arrived with the wrong extension. It opens
         * in the editor.
         *
         * Carries no bytes, for the same reason [Stickers] and [Icons] do not:
         * the editor re-opens the file for itself, because a document may be
         * two million characters and an intent extra that size kills the
         * process.
         *
         * Anything that is *not* text — a foreign archive, an image, a PDF —
         * stays [Unrecognized]: a hex dump helps nobody.
         */
        data object Text : Opened

        /**
         * Files the app otherwise downloads or imports on a settings screen: a
         * language pack zip, one word list, word-pair half, emoji name list or
         * dictionary on its own, a model, Google's gesture library, a feature
         * library. They install through [OfflineImport], the same as Import on
         * a download screen, which re-opens the file itself. [kind] is what
         * the question before the import calls them.
         */
        data class OfflineFiles(val kind: OfflineImport.Kind) : Opened

        /** A font. [emoji] when it draws colour glyphs, which is what makes one an emoji font. */
        data class Font(val emoji: Boolean) : Opened

        /** One key-press sound: an MP3, Ogg or WAV. */
        data object KeySound : Opened

        /** A FlorisBoard language pack (`.flex`): Chinese code tables. [schemas] are the tables inside. */
        data class FlorisLanguagePack(val schemas: List<String>) : Opened

        /** A HeliBoard or LeanType colour theme, already converted. */
        data class HeliThemeFile(val result: HeliResult.Converted) : Opened

        /** A layout from FlorisBoard's JSON or a Keyman touch layout, already converted. */
        data class ForeignLayout(val converted: ConvertedLayout) : Opened

        /** This app's kaomoji export, or an Emotic archive, already read. */
        data class KaomojiFile(val preview: KaomojiImportPreview) : Opened

        /** An fcitx double-pinyin scheme: lines of `zh=v` under a `方案名称=` name. */
        data class DoublePinyinScheme(val text: String, val name: String?) : Opened

        /** A `.cin` table, a Rime schema or dictionary, or an m17n method, already converted. */
        data class InputMethod(val method: ConvertedMethod) : Opened

        /**
         * Word lists for suggestions: a compiled AOSP `.dict`, AOSP's
         * `.combined` text, or a zip of dictionaries (a LibreOffice `.oxt`, a
         * Firefox `.xpi`). Already read, so the dialog can say how many words.
         */
        data class Dictionaries(val found: List<CustomDictionaries.ImportCandidate>) : Opened

        /** An emoji keyword pack, in its TSV shape or the data repository's JSON. */
        data object EmojiKeywords : Opened

        /** An Anki deck or collection (`.apkg`, `.colpkg`), for the vocabulary tool. */
        data object AnkiDeck : Opened

        /** Not one of ours, and not something worth showing either. */
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
            context.contentResolver.requireInputStream(uri).use { readHead(it) }
        }.getOrNull() ?: return Opened.Unreadable
        // A file the download screens install: a word list, a model, a
        // handwriting pack. Told by the name it is published under, and for
        // the models by their size too, before anything below reads it: a
        // word list is gzipped text, and a `.task` model is a zip.
        OfflineImport.kindOf(context, name, sizeOf(context, uri))?.let { return Opened.OfflineFiles(it) }
        // A native library under a name that says nothing (a browser or a
        // chat app renamed it): told by its hash, as its import tells it.
        if (name.endsWith(".so", ignoreCase = true)) {
            val kind = runCatching {
                context.contentResolver.requireInputStream(uri).use { OfflineImport.kindOfLibrary(sha256Of(it)) }
            }.getOrNull()
            return if (kind != null) Opened.OfflineFiles(kind) else Opened.Unrecognized
        }
        if (head.size >= ZIP_MAGIC.size && head.copyOf(ZIP_MAGIC.size).contentEquals(ZIP_MAGIC)) {
            return identifyArchive(context, uri)
        }
        // Before the text read below, which on a large encrypted bundle would
        // decode megabytes of ciphertext as UTF-8 to produce a string that
        // could never match anything.
        if (BackupCrypto.looksEncrypted(head)) return Opened.EncryptedConfig

        // Binary formats with a header of their own, before the text read
        // would turn them into replacement characters.
        if (FileSniff.isFont(head)) return Opened.Font(emoji = FileSniff.isColourFont(head))
        if (FileSniff.isSound(head, name)) return Opened.KeySound
        if (AospDictionary.looksLikeDictionary(head)) return dictionariesIn(context, uri, name)
        if (com.wasimaster.wmkeyboard.core.prediction.SogouScel.looksLikeScel(head)) {
            return dictionariesIn(context, uri, name)
        }
        if (FileSniff.isSqlite(head) || FileSniff.isTar(head)) return kaomojiIn(context, uri)

        // A gzipped file is read through the inflater: the data repository
        // hosts vocabulary packs that way, and an add-on entry may point
        // straight at one. The proposal re-opens the raw stream when it
        // applies, and each importer inflates for itself.
        val gzipped = head.size >= 2 && head[0] == GZIP_MAGIC_1 && head[1] == GZIP_MAGIC_2
        // Emotic's current export is a gzipped tar, which the inflater below
        // would otherwise read as text.
        if (gzipped) {
            val inner = runCatching {
                context.contentResolver.requireInputStream(uri).use {
                    readHead(java.util.zip.GZIPInputStream(it, 32 * 1024))
                }
            }.getOrNull()
            if (inner != null && FileSniff.isTar(inner)) return kaomojiIn(context, uri)
        }
        val bytes = runCatching {
            context.contentResolver.requireInputStream(uri).use { raw ->
                val input = if (gzipped) java.util.zip.GZIPInputStream(raw, 32 * 1024) else raw
                // Capped on what comes *out*: a few megabytes of gzip inflate
                // to far more, and this is the one read where the file's own
                // size says nothing about what it costs to hold.
                input.readCapped()
            }
        }.getOrNull() ?: return Opened.Unreadable
        // UTF-16 is read as such: MSKLC saves every `.klc` that way.
        val text = DesktopLayouts.decodeText(bytes)

        // Exports from before the truncating write could carry the tail of an
        // older, longer file after the document. The proposal below keeps this
        // trimmed text, so what gets applied is what got recognised.
        val kind = textKindFor(text.firstJsonDocument(), name)
        if (kind != Opened.Unrecognized) return kind
        // An input method table. After every text format above, because the
        // conversion is the expensive part, and with the raw bytes, because a
        // `.cin` is as often Big5 as UTF-8.
        if (FileSniff.looksLikeInputMethod(text, name)) {
            val conversion = runCatching { InputMethodImport.read(listOf(SourceFile(name, bytes))) }.getOrNull()
            if (conversion is Conversion.Ready) return Opened.InputMethod(conversion.method)
        }
        // None of ours. Text still opens — in the editor rather than on a dead
        // end — now that the manifest claims application/json and
        // application/octet-stream and files that are nobody's business here
        // reach this activity on purpose.
        return if (isEditableText(text)) Opened.Text else Opened.Unrecognized
    }

    /** Up to [FileSniff.HEAD_BYTES] of [input]: enough for every header sniff. */
    private fun readHead(input: java.io.InputStream): ByteArray {
        val buffer = ByteArray(FileSniff.HEAD_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n <= 0) break
            read += n
        }
        return buffer.copyOf(read)
    }

    /** The provider's size for [uri], or -1 when it does not say. */
    private fun sha256Of(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeOf(context: android.content.Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /** Every dictionary in [uri], read the way the word-list screen reads a pick. */
    private fun dictionariesIn(context: android.content.Context, uri: Uri, name: String): Opened {
        val bytes = runCatching {
            context.contentResolver.requireInputStream(uri).use { CustomDictionaries.readCapped(it) }
        }.getOrNull() ?: return Opened.Unrecognized
        val inspection = CustomDictionaries.inspect(listOf(CustomDictionaries.ImportFile(name, bytes)))
        return (inspection as? CustomDictionaries.Inspection.Found)?.let { Opened.Dictionaries(it.dictionaries) }
            ?: Opened.Unrecognized
    }

    /** An Emotic archive or database, read as the kaomoji screen reads one. */
    private fun kaomojiIn(context: android.content.Context, uri: Uri): Opened =
        readKaomojiImport(context, uri)?.let { Opened.KaomojiFile(it) } ?: Opened.Unrecognized

    /**
     * Whether [text] is worth putting in an editor.
     *
     * `decodeToString` turns every byte it cannot read into U+FFFD rather than
     * failing, so a PNG arrives here as a long string of replacement characters
     * and would otherwise open as a screen of garbage. Control characters are
     * counted with them: both are common in binary and vanishingly rare in the
     * text files this is for. One stray byte in an otherwise readable file is
     * not enough to refuse it, so the test is a share of the whole rather than
     * a first sighting — except for NUL, which no text file has and every
     * binary does.
     */
    internal fun isEditableText(text: String): Boolean {
        if (text.isEmpty() || text.length > MAX_EDITABLE_CHARS) return false
        var odd = 0
        for (ch in text) {
            if (ch == '\u0000') return false
            if (ch == '\uFFFD' || (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r')) odd++
        }
        return odd * BINARY_SHARE < text.length
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
        // Not ours, but its first line is as good as a tag: no other format
        // can start with Gboard's own header comment.
        GboardDictionary.read(text)?.let { return Opened.GboardDictionaryFile(it) }
        // This app's kaomoji export carries its format tag; the plain lists the
        // kaomoji screen also reads are any text, and stay with the editor.
        if (text.contains("\"${KaomojiFiles.FORMAT}\"")) {
            KaomojiFiles.decode(text)?.let {
                return Opened.KaomojiFile(KaomojiImportPreview(it.items, it.tags))
            }
        }
        // A theme has no tag and every field has a default, so decoding any JSON
        // object at all succeeds and yields an all-defaults theme. The file name
        // is one piece of evidence that this one was meant to be a theme; the
        // keys every export writes are the other, for the many routes that lose
        // the name (a chat app's cache, a `msf:` URI). No untagged *JSON* format
        // may be added after this line: these checks are what stop the branch
        // claiming every JSON file, and a second untagged one would have
        // nothing left to be told apart by.
        if (name.endsWith(".${ThemeCodec.FILE_EXTENSION}", ignoreCase = true) || looksLikeThemeExport(text)) {
            ThemeCodec.decode(text)?.let { return Opened.Theme(it) }
        }

        // Untagged JSON from other keyboards, each told by structure the theme
        // check above cannot match: a Keyman touch layout by its platform
        // keys, a HeliBoard theme by its colour pairs or colour roles, and a
        // FlorisBoard layout by its key types. Keyman before FlorisBoard, as
        // the layout editor orders them, since the Floris reader would take a
        // Keyman file and return nothing.
        if (KeymanImport.looksLikeTouchLayout(text)) {
            KeymanImport.convert(text, name)?.let { return Opened.ForeignLayout(it) }
        }
        (HeliTheme.read(text) as? HeliResult.Converted)?.let { return Opened.HeliThemeFile(it) }
        if (FileSniff.looksLikeFlorisLayout(text)) {
            ForeignLayouts.fromFlorisJson(text, name)?.let { return Opened.ForeignLayout(it) }
        }
        // Emoji names for search, as TSV rows that each start with an emoji, or
        // as the data repository's JSON.
        if (FileSniff.looksLikeEmojiKeywords(text) || FileSniff.looksLikeEmojiKeywordJson(text)) {
            return Opened.EmojiKeywords
        }
        // AOSP's `.combined` word list opens with its own header line.
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        if (firstLine != null && AospCombined.looksLikeCombined(firstLine)) {
            val inspection = CustomDictionaries.inspect(listOf(CustomDictionaries.ImportFile(name, text.toByteArray())))
            (inspection as? CustomDictionaries.Inspection.Found)?.let { return Opened.Dictionaries(it.dictionaries) }
        }
        // An fcitx double-pinyin scheme names itself on a line of its own.
        if (text.lineSequence().any { it.trim().removePrefix("\uFEFF").startsWith(DoublePinyinProfile.NAME_PREFIX) }) {
            return Opened.DoublePinyinScheme(text, DoublePinyinProfile.parse(text).name)
        }

        // A Keys Cafe grid is base64 text, which no JSON or YAML document is,
        // so it can sit here safely: the sniff is on the characters, and the
        // conversion then has to decrypt to a grid or the file falls through.
        if (KeysCafeLayouts.looksLikeKcf(text)) {
            KeysCafeLayouts.convert(text, name)?.let { return Opened.KeysCafeLayout(it) }
        }

        // Multiling O shares text: a settings block or a DIY+ layout behind a
        // marker no other file has, or a DIY layout whose JSON is told by its
        // `onScreen` object, a key no other layout format writes. That key is
        // the evidence the theme branch above needs a file name for.
        if (MultilingLayouts.looksLikeMultiling(text) || text.contains(MultilingExport.SETTINGS_BEGIN)) {
            val layouts = MultilingLayouts.convertAll(text, name)
            val theme = MultilingTheme.read(text) as? MultilingThemeResult.Converted
            if (layouts.isNotEmpty() || theme != null) return Opened.MultilingFile(layouts, theme)
        }

        // A computer keyboard's layout. A `.klc` is told by its section
        // words, a `.keylayout` by its `<keyMapSet>`, an XKB file by its
        // `xkb_symbols "name" {` blocks; no other format here has any of them.
        // Ahead of the Unexpected Keyboard branch below, whose `<keyboard>`
        // and `<key>` test a `.keylayout` passes as well.
        if (DesktopLayouts.looksLikeDesktopLayout(text)) {
            val layouts = DesktopLayouts.convertAll(text, name)
            if (layouts.isNotEmpty()) return Opened.DesktopLayoutFile(layouts)
        }

        // An Unexpected Keyboard or CleverKeys layout: XML with a `<keyboard>`
        // and its `<key>`s, which no other format here is. AnySoftKeyboard's
        // XML, read just below, has neither element.
        if (UnexpectedLayouts.looksLikeUnexpected(text)) {
            UnexpectedLayouts.convert(text, name)?.let { return Opened.UnexpectedLayout(it) }
        }

        // Another keyboard's settings as one text file: a CleverKeys export,
        // told by its `metadata` and `preferences` objects together, or
        // AnySoftKeyboard's XML, which no other format here is.
        ForeignBackups.readText(text)?.let { return Opened.ForeignBackupFile(it) }

        // The two YAML formats, which are the two that can follow the branch
        // above safely. Both are somebody else's and neither carries a tag of
        // ours, but each is recognised by a key at the start of a line — `rows:`
        // for FUTO, `matches:` for Espanso — and a JSON document cannot have
        // one, because every key in it is inside quotes. That is the whole
        // reason they can sit here rather than needing a file name.
        //
        // FUTO first: it is the narrower test of the two, wanting both `name:`
        // and `rows:`.
        if (FutoLayouts.looksLikeFutoLayout(text)) {
            FutoLayouts.convert(text, name)?.let { return Opened.FutoLayout(it) }
        }
        // The foreign half of SnippetPayload: Espanso, then a table. The native
        // half and Gboard's dictionary already answered above, so only those
        // two can still come back — and asking the shared reader rather than
        // repeating its sniff here is what keeps this screen and the Text
        // Expander screen from disagreeing about what a snippet file is. A
        // table is last of everything: it needs a header naming a text column,
        // or a `.csv` name, and almost any text has a comma in it.
        SnippetPayload.readText(text, name)
            ?.takeIf { it.isForeign }
            ?.let { return Opened.ForeignSnippets(it) }
        return Opened.Unrecognized
    }

    /**
     * Whether [text] is a theme as this app exports it, whatever it is called.
     * Exports write every field, defaults included, so the two colours every
     * theme has are always there; no other format here uses either key, and
     * the tagged formats that embed themes (a config backup) answered above.
     */
    internal fun looksLikeThemeExport(text: String): Boolean =
        text.trimStart().startsWith("{") &&
            text.contains("\"boardBackground\"") &&
            text.contains("\"keyBackground\"")

    /**
     * Which archive format a ZIP holds, from the `format` tag in its manifest.
     *
     * Sticker packs and icon packs are both ZIPs with a `pack.json` at the
     * root, so the magic bytes alone can't tell them apart — reading the
     * manifest is the only way, and guessing would send every icon pack to the
     * sticker importer.
     */
    private fun identifyArchive(context: android.content.Context, uri: Uri): Opened {
        // The entry names seen on the way to the manifest, for the one archive
        // format that has no manifest: a `.wastickers` is told by the two text
        // files it carries, which the scan below walks past.
        val names = ArrayList<String>()
        val manifest = runCatching {
            context.contentResolver.requireInputStream(uri).use { input ->
                java.util.zip.ZipInputStream(input.buffered()).use { zip ->
                    var scanned = 0
                    while (scanned++ < MANIFEST_SCAN_LIMIT) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        names += entry.name
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
        }.getOrNull() ?: return identifyManifestless(context, uri, names)

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
        if (manifest.contains("\"${FlexLanguagePack.FORMAT}\"")) {
            val schemas = runCatching {
                context.contentResolver.requireInputStream(uri).use { FlexLanguagePack.schemas(it, context.cacheDir) }
            }.getOrDefault(emptyList())
            return Opened.FlorisLanguagePack(schemas)
        }
        if (isFlexManifest(manifest)) {
            val result = runCatching {
                context.contentResolver.requireInputStream(uri).use {
                    FlexTheme.read(it, dynamicSnyggPalette(context))
                }
            }.getOrElse { FlexResult.Unreadable }
            return Opened.FlorisTheme(result)
        }
        if (isGboardManifest(manifest)) {
            val result = runCatching {
                context.contentResolver.requireInputStream(uri).use {
                    GboardTheme.read(it, displayName(context, uri).substringBeforeLast('.'))
                }
            }.getOrElse { GboardResult.Unreadable }
            return Opened.GboardThemeFile(result)
        }
        return archiveKindFor(manifest)
    }

    /**
     * A ZIP with none of the manifests above in it.
     *
     * One format is recognised anyway: a WhatsApp `.wastickers` has no
     * manifest, only a `title.txt` and an `author.txt` beside the pictures,
     * and those two names are its whole signature (see
     * [WaStickersFile.looksLikeWaStickers]). Anything else stays unrecognised,
     * because a ZIP of pictures with no such file is any ZIP of pictures.
     */
    private fun identifyManifestless(context: android.content.Context, uri: Uri, names: List<String>): Opened {
        // A FUTO theme: no manifest, only its settings file beside the
        // pictures. `theme.txt` is a common name, so the read has to find
        // FUTO's colours before the file counts as one.
        if (FutoTheme.looksLikeFutoTheme(names)) {
            val result = runCatching {
                context.contentResolver.requireInputStream(uri).use { FutoTheme.read(it) }
            }.getOrNull()
            if (result is FutoThemeResult.Converted) return Opened.FutoThemeFile(result)
        }
        // Another keyboard's backup, told by the file each one always writes
        // first: its settings, or its export's version stamp.
        if (ForeignBackups.looksLikeBackup(names)) {
            val backup = runCatching {
                context.contentResolver.requireInputStream(uri).use { ForeignBackups.read(it) }
            }.getOrNull()
            return if (backup != null) Opened.ForeignBackupFile(backup) else Opened.Unrecognized
        }
        // Gboard's personal dictionary export: one `dictionary.txt`, which the
        // read then has to find Gboard's header in.
        if (GboardDictionary.looksLikeGboardZip(names)) {
            val contents = runCatching {
                context.contentResolver.requireInputStream(uri).use { GboardDictionary.readZip(it.buffered()) }
            }.getOrNull()
            if (contents != null) return Opened.GboardDictionaryFile(contents)
        }
        // A language pack (word lists, word pairs and emoji names, each under
        // the name it is published under), a GIF pack or a Yomichan
        // dictionary: whatever the download screens install. Any one file of
        // a language pack is enough, since the import reports on every file.
        OfflineImport.kindOfArchive(names)?.let { return Opened.OfflineFiles(it) }
        // An Anki deck, by the collection every export carries.
        if (names.any { it.substringAfterLast('/').startsWith("collection.anki2") }) return Opened.AnkiDeck
        // Dictionaries for suggestions: AOSP's compiled ones, or a Hunspell pair
        // as LibreOffice and Firefox package them.
        val lower = names.map { it.lowercase() }
        val hunspell = lower.any { it.endsWith(".aff") } && lower.any { it.endsWith(".dic") }
        if (hunspell || lower.any { it.endsWith(".dict") || it.endsWith(".combined") }) {
            val found = dictionariesIn(context, uri, displayName(context, uri))
            if (found is Opened.Dictionaries) return found
        }
        if (!WaStickersFile.looksLikeWaStickers(names)) return Opened.Unrecognized
        val header = runCatching {
            context.contentResolver.requireInputStream(uri).use { WaStickersFile.peek(it) }
        }.getOrNull() ?: return Opened.Unrecognized
        return Opened.WhatsAppStickers(header)
    }

    /**
     * Whether a manifest is a Gboard theme's `metadata.json` or an Rboard
     * pack's `pack.meta`. Neither carries a format tag: the first is told by
     * the stylesheet list every Gboard theme names, the second by its
     * `name=` / `author=` lines, which no JSON manifest can start a line with.
     */
    internal fun isGboardManifest(manifest: String): Boolean {
        if (manifest.contains("\"style_sheets\"")) return true
        if (manifest.trimStart().startsWith("{")) return false
        return manifest.lineSequence().any { it.startsWith("name=") || it.startsWith("author=") }
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
        SoundPackFile.recognises(manifest) -> Opened.SoundPack
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
     * The longest file the editor will open. Well past any hand-written config
     * and far short of what a field holding the whole document can lay out.
     */
    private const val MAX_EDITABLE_CHARS = 2_000_000

    /** One odd character in this many is still text. */
    private const val BINARY_SHARE = 100

    /**
     * The manifest names the archive formats use. Sticker, icon and sound packs
     * all share `pack.json` and are told apart by their format tag; a plugin
     * names its manifest differently so that a `.wmplugin` is recognisable
     * without reading any Lua.
     */
    private val ARCHIVE_MANIFESTS =
        setOf(
            StickerPackFile.MANIFEST,
            SoundPackFile.MANIFEST,
            PluginFile.MANIFEST,
            FlexTheme.MANIFEST,
            KeymanPackage.MANIFEST,
            GboardTheme.METADATA,
            GboardTheme.PACK_META,
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
        val uri = intent?.let(::sourceUri)
        if (uri == null) {
            finish()
            return
        }
        val repository = SettingsRepository(applicationContext)
        // A layout import resolves against the asset layouts, same as the
        // settings app does before its first frame.
        AssetLayouts.load(applicationContext.assets)
        setContent {
            val stored = repository.settings
                .collectAsStateWithLifecycle(null as KeyboardSettings?)
            val deviceForm = DeviceForm.of(LocalConfiguration.current.smallestScreenWidthDp)
            val settings = rememberLiveSettings(stored, deviceForm) ?: return@setContent
            AppTheme(settings) {
                ImportFileDialog(repository, uri) { finish() }
            }
        }
    }
}

/**
 * The file an intent points at.
 *
 * An "open with" carries it as the data URI. A share sheet carries it as
 * `EXTRA_STREAM` instead, with no data URI at all, which is why the manifest's
 * `ACTION_SEND` filter would otherwise reach an activity that finishes on
 * arrival.
 */
private fun sourceUri(intent: Intent): Uri? = intent.data
    ?: if (intent.action == Intent.ACTION_SEND) {
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        null
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
    /**
     * Set instead of [apply] by a proposal whose confirm opens a screen rather
     * than importing anything. The dialog runs it and closes; there is no
     * result message to show, because the screen is the result.
     */
    val open: (() -> Unit)? = null,
    /**
     * The language a converted foreign layout is guessed to be in, and the seed
     * for the row the dialog draws to change it. Set together with
     * [applyWithLanguage].
     *
     * A step rather than a guess applied silently, exactly as the in-app import
     * makes it: the language decides the dictionary, the autocorrect, the script
     * rules, dictation and how shift behaves, and no foreign layout file states
     * one.
     */
    val language: String? = null,
    /** Set instead of [apply] by a proposal that has a [language] to settle. */
    val applyWithLanguage: (suspend (String) -> String)? = null,
) {
    /** Whether there is anything to press the confirm button for. */
    val actionable: Boolean
        get() = apply != null || applyWithPassphrase != null ||
            applyWithLanguage != null || open != null
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
internal fun ImportFileDialog(
    repository: SettingsRepository,
    uri: Uri,
    /**
     * Called once a backup has been restored: one of ours, or another
     * keyboard's. The setup wizard listens for it, because a restored phone
     * has nothing left to be asked.
     */
    onImported: () -> Unit = {},
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
    if (state == null) {
        // Reading a backup with sticker packs in it is not instant.
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.import_progress_reading)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        return
    }

    // A whole backup from another keyboard is a list to choose from, not one
    // question, so it has a dialog of its own.
    if (state is WMFileTypes.Opened.ForeignBackupFile) {
        ForeignBackupDialog(
            backup = state.backup,
            repository = repository,
            onDone = { result ->
                message = result
                onImported()
            },
            onDismiss = onClose,
        )
        return
    }

    // A sticker pack, the app's own or a WhatsApp one, opens on a page of its
    // stickers to tick before any of them is converted (#965).
    if (state == WMFileTypes.Opened.Stickers || state is WMFileTypes.Opened.WhatsAppStickers) {
        StickerFileImport(uri) { report -> if (report == null) onClose() else message = report }
        return
    }

    // Word lists and the rest install through the download screens' own
    // import, which brings its own choice of list, progress and report.
    if (state is WMFileTypes.Opened.OfflineFiles) {
        OfflineFilesDialog(uri, state.kind, onClose)
        return
    }

    val proposal = rememberProposal(state, repository, context, uri, onImported)
    var passphrase by remember { mutableStateOf("") }
    var language by remember(proposal) { mutableStateOf(proposal.language.orEmpty()) }
    var pickingLanguage by remember { mutableStateOf(false) }
    val needsPassphrase = proposal.applyWithPassphrase != null
    // Writing it out is not instant either. The dialog stays, its title says
    // so, and the spinner sits in the button that was pressed rather than the
    // whole dialog being swapped for another one.
    val phase = if (working) RunPhase.RUNNING else RunPhase.IDLE
    AlertDialog(
        onDismissRequest = { if (!working) onClose() },
        title = { Text(if (working) stringResource(R.string.import_progress_importing) else proposalTitle(proposal)) },
        text = {
            // Scrolls: a preview, a long backup summary and a list of repairs
            // together are taller than a dialog on a short screen, and a
            // dialog that cannot reach its own text is worse than no preview.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ImportFilePreview(state, uri)
                Text(proposal.body)
                if (proposal.repairs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    // Future tense: this dialog is the *offer*, and its
                    // button says Import. The past-tense heading belongs to
                    // the messages shown once an import has happened.
                    Text(
                        stringResource(R.string.import_repairs_pending_title),
                        fontWeight = FontWeight.Medium,
                    )
                    for (line in proposal.repairs) Text("• $line")
                }
                if (proposal.language != null) {
                    Spacer(Modifier.height(8.dp))
                    WmRow(
                        title = stringResource(R.string.layout_editor_foreign_language_title),
                        subtitle = LanguageRegistry.byId(language).displayName,
                        onClick = { pickingLanguage = true },
                    )
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
            val applyWithLanguage = proposal.applyWithLanguage
            val open = proposal.open
            when {
                // Closes on the way out: the screen it opens is the result, and
                // a dialog left behind it would be waiting on a finished import.
                open != null -> TextButton(onClick = {
                    open()
                    onClose()
                }) { Text(stringResource(proposal.confirmLabelRes)) }

                applyWithLanguage != null -> TextButton(onClick = {
                    // Left enabled while it works, so the spinner in it is
                    // drawn in the button's colour rather than greyed out.
                    if (working) return@TextButton
                    working = true
                    scope.launch { message = applyWithLanguage(language); working = false }
                }) {
                    RunGlyph(phase)
                    Text(stringResource(proposal.confirmLabelRes))
                }

                applyWithPassphrase != null -> TextButton(
                    enabled = passphrase.isNotEmpty(),
                    onClick = {
                        if (working) return@TextButton
                        working = true
                        scope.launch { message = applyWithPassphrase(passphrase); working = false }
                    },
                ) {
                    RunGlyph(phase)
                    Text(stringResource(proposal.confirmLabelRes))
                }

                apply != null -> TextButton(onClick = {
                    if (working) return@TextButton
                    working = true
                    scope.launch { message = apply(); working = false }
                }) {
                    RunGlyph(phase)
                    Text(stringResource(proposal.confirmLabelRes))
                }

                else -> TextButton(onClick = onClose) {
                    Text(stringResource(CommonR.string.common_ok))
                }
            }
        },
        dismissButton = {
            if (proposal.actionable) {
                TextButton(onClick = onClose, enabled = !working) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )

    // Over the dialog above, the same picker the layout editor's own foreign
    // import opens.
    if (pickingLanguage) {
        ForeignLanguageDialog(
            selected = language,
            onPick = {
                language = it
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

/**
 * A language pack or another file the app downloads, opened from outside it.
 * One question, worded for what the file is ([kind]), then the same import,
 * progress and report a download screen's Import button runs; the activity
 * closes with the report.
 */
@Composable
private fun OfflineFilesDialog(uri: Uri, kind: OfflineImport.Kind, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runner = rememberOfflineImport(onReportDismissed = onClose)
    var started by remember { mutableStateOf(false) }
    if (started) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(kind.titleRes)) },
        text = {
            Text(stringResource(kind.bodyRes, remember(uri) { WMFileTypes.displayName(context, uri) }))
        },
        confirmButton = {
            TextButton(onClick = {
                started = true
                runner.install(listOf(uri))
            }) { Text(stringResource(R.string.offline_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text(stringResource(CommonR.string.common_cancel)) }
        },
    )
}

/**
 * The layout inside a Keyman package, or null for a package that carries no
 * on-screen keyboard.
 *
 * Shared with the preview above the dialog, so the grid the user is shown is
 * the grid Import would save rather than a second reading of the same file.
 */
internal fun keymanLayoutOf(contents: KeymanPackage.Contents): ConvertedKeymanLayout? {
    val doc = contents.touchLayoutJson
        ?.let { KeymanTouchLayoutReader.parse(it) as? KeymanResult.Success }
        ?.value
    return doc
        ?.let { TouchLayoutConverter.convert(it, contents.keyboardId, contents.name) }
        ?.let { it as? KeymanResult.Success }
        ?.value
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
    val converted = keymanLayoutOf(contents)

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
 * What text from Multiling O Keyboard offers.
 *
 * One layout on its own goes through the language step, like every other
 * foreign layout: nothing in a shared layout says what language it is for. A
 * settings export says it for each layout, so all of them are added at once,
 * each with the language it was filed under, or the one its letters suggest
 * where this app does not have that language. Its colours are saved as a theme
 * beside them. Nothing is turned on: a converted layout and a converted theme
 * are both things to look at first.
 */
private fun multilingProposal(
    state: WMFileTypes.Opened.MultilingFile,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    val single = state.layouts.singleOrNull()
    if (single != null && state.theme == null) {
        return ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = single.layout.name,
            body = context.getString(R.string.import_multiling_layout_body),
            repairs = single.notes.map { it.format(context.resources) },
            language = single.guessedLangId,
            applyWithLanguage = { langId ->
                repository.upsertCustomLayout(
                    single.withLanguage(langId).copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, single.layout.name)
            },
        )
    }
    val count = state.layouts.size
    return ImportProposal(
        titleRes = R.string.import_multiling_title,
        body = buildString {
            if (count > 0) {
                append(context.resources.getQuantityString(R.plurals.import_multiling_layouts_body, count, count))
            }
            if (state.theme != null) {
                if (isNotEmpty()) append("\n\n")
                append(context.getString(R.string.import_multiling_theme_line))
            }
        },
        apply = {
            val stamp = System.currentTimeMillis()
            state.layouts.forEachIndexed { index, converted ->
                // One stamp for the whole batch, with the position after it:
                // a loop is faster than the clock, and two layouts saved under
                // one id would leave only the second.
                repository.upsertCustomLayout(
                    converted.withLanguage(converted.guessedLangId).copy(id = "custom_${stamp}_$index"),
                )
            }
            state.theme?.let { result ->
                repository.upsertCustomTheme(result.theme.copy(id = "custom_${stamp}_theme"))
            }
            buildString {
                if (count > 0) {
                    append(context.resources.getQuantityString(R.plurals.import_multiling_layouts_done, count, count))
                }
                if (state.theme != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(context.getString(R.string.import_multiling_theme_done))
                }
            }
        },
    )
}

/**
 * What a computer keyboard's layout file offers.
 *
 * One layout goes through the language step, like every other foreign layout:
 * only a `.klc` states a language, and the picker starts from it. An XKB file
 * with several variants adds them all at once, each with the language its
 * letters suggest, the way a Multiling settings export does. Nothing is turned
 * on.
 */
private fun desktopProposal(
    layouts: List<ConvertedLayout>,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    val single = layouts.singleOrNull()
    if (single != null) {
        return ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = single.layout.name,
            body = context.getString(R.string.import_desktop_layout_body),
            repairs = single.notes.map { it.format(context.resources) },
            language = single.guessedLangId,
            applyWithLanguage = { langId ->
                repository.upsertCustomLayout(
                    single.withLanguage(langId).copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, single.layout.name)
            },
        )
    }
    val count = layouts.size
    return ImportProposal(
        titleRes = R.string.import_desktop_title,
        body = context.resources.getQuantityString(R.plurals.import_desktop_layouts_body, count, count) +
            layouts.joinToString(separator = "\n", prefix = "\n\n") { "• ${it.layout.name}" },
        apply = {
            // One stamp for the batch with the position after it, as for a
            // Multiling export: the loop is faster than the clock.
            val stamp = System.currentTimeMillis()
            layouts.forEachIndexed { index, converted ->
                repository.upsertCustomLayout(
                    converted.withLanguage(converted.guessedLangId).copy(id = "custom_${stamp}_$index"),
                )
            }
            context.resources.getQuantityString(R.plurals.import_multiling_layouts_done, count, count)
        },
    )
}

/**
 * A font opened from outside the app. An emoji font is switched on at once,
 * as the emoji screen's own import does; any other font is added to the list
 * the font pickers offer, since which script it should draw is the user's
 * to say.
 */
private fun fontProposal(
    emoji: Boolean,
    repository: SettingsRepository,
    context: android.content.Context,
    uri: Uri,
): ImportProposal {
    val name = WMFileTypes.displayName(context, uri).substringBeforeLast('.')
    return ImportProposal(
        titleRes = R.string.import_name_title,
        titleArg = name,
        body = context.getString(if (emoji) R.string.import_emoji_font_body else R.string.import_font_body),
        apply = {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        FontFile.import(it, FontStore.get(context), name = name, emoji = emoji)
                    }
                }.getOrElse { FontImportResult.Failed(ContentR.string.core_content_font_error_read) }
            }
            when (result) {
                is FontImportResult.Imported -> {
                    if (emoji) repository.setInstalledEmojiFont(result.font.id)
                    context.getString(R.string.import_done_name, result.font.name)
                }
                is FontImportResult.NotAFont -> context.getString(result.messageRes)
                FontImportResult.TooManyFonts -> context.resources.getQuantityString(
                    R.plurals.fonts_import_limit_message, FontStore.MAX_FONTS, FontStore.MAX_FONTS,
                )
                is FontImportResult.Failed -> context.getString(result.messageRes, *result.messageArgs.toTypedArray())
            }
        },
    )
}

/** A key sound opened from outside the app: added, and played on every key from then on. */
private fun keySoundProposal(
    repository: SettingsRepository,
    context: android.content.Context,
    uri: Uri,
): ImportProposal {
    val name = WMFileTypes.displayName(context, uri).substringBeforeLast('.')
    return ImportProposal(
        titleRes = R.string.import_name_title,
        titleArg = name,
        body = context.getString(R.string.import_key_sound_body),
        apply = {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.requireInputStream(uri).use {
                        SoundFile.import(it, SoundStore.get(context), name = name)
                    }
                }.getOrElse { SoundImportResult.Failed(FeedbackR.string.core_feedback_sound_import_read_error) }
            }
            when (result) {
                is SoundImportResult.Imported -> {
                    repository.setKeySoundCustomId(result.sound.id)
                    context.getString(R.string.import_done_name, result.sound.name)
                }
                is SoundImportResult.NotASound -> context.getString(result.messageRes)
                SoundImportResult.TooManySounds -> context.resources.getQuantityString(
                    R.plurals.hardware_sound_limit_error, SoundStore.MAX_SOUNDS, SoundStore.MAX_SOUNDS,
                )
                is SoundImportResult.Failed -> if (result.messageArg.isEmpty()) {
                    context.getString(result.messageRes)
                } else {
                    context.getString(result.messageRes, result.messageArg)
                }
            }
        },
    )
}

/**
 * Word lists for suggestions, with the language step: a dictionary names its
 * locale when it is an AOSP or Hunspell one, and that seeds the picker.
 * Everything a list carries is written (its pairs and shortcuts too), as the
 * word-list screen does when there is nothing to choose between.
 */
private fun dictionariesProposal(
    found: List<CustomDictionaries.ImportCandidate>,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    val words = found.sumOf { it.words.size }
    return ImportProposal(
        titleRes = R.string.import_name_title,
        titleArg = found.first().description ?: found.first().name,
        body = context.resources.getQuantityString(R.plurals.import_dictionary_body, words, words),
        language = guessFileLanguage(found.firstNotNullOfOrNull { it.locale }),
        applyWithLanguage = { langId ->
            val written = withContext(Dispatchers.IO) {
                found.sumOf {
                    CustomDictionaries.write(context.filesDir, langId, it, CustomDictionaries.ImportParts.ALL).words
                }
            }
            repository.bumpCustomDictVersion()
            context.resources.getQuantityString(
                R.plurals.customdict_import_added_words, written, written, LanguageRegistry.byId(langId).displayName,
            )
        },
    )
}

/**
 * The language to seed the picker with for a file that may name a [locale]
 * (`en_US`, `pt-BR`, `de`), or else the phone's own, or else English. Only a
 * language this build has counts: `byId` falls back for anything else.
 */
private fun guessFileLanguage(locale: String?): String {
    val candidates = listOfNotNull(locale, java.util.Locale.getDefault().toLanguageTag())
        .flatMap { tag ->
            val dashed = tag.replace('_', '-')
            listOf(dashed, dashed.lowercase(), dashed.substringBefore('-').lowercase())
        }
    return candidates.firstOrNull { LanguageRegistry.byId(it).id == it } ?: "en"
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
    onImported: () -> Unit,
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

        is WMFileTypes.Opened.GboardThemeFile -> gboardProposal(state.result, repository, context)

        is WMFileTypes.Opened.FutoThemeFile -> futoThemeProposal(state.result, repository, context)

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

        is WMFileTypes.Opened.FutoLayout -> ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = state.converted.layout.name,
            body = context.getString(R.string.import_futo_body),
            repairs = state.converted.notes.map { it.format(context.resources) },
            language = state.converted.guessedLangId,
            applyWithLanguage = { langId ->
                // withLanguage is the only supported way out of a conversion,
                // for the reason its own comment gives: a blank langId is
                // migrated to English on the next read, which would give a
                // Georgian grid an English dictionary with nothing to say why.
                repository.upsertCustomLayout(
                    state.converted.withLanguage(langId)
                        .copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, state.converted.layout.name)
            },
        )

        is WMFileTypes.Opened.KeysCafeLayout -> ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = state.converted.layout.name,
            body = context.getString(R.string.import_keyscafe_body),
            repairs = state.converted.notes.map { it.format(context.resources) },
            language = state.converted.guessedLangId,
            applyWithLanguage = { langId ->
                repository.upsertCustomLayout(
                    state.converted.withLanguage(langId)
                        .copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, state.converted.layout.name)
            },
        )

        is WMFileTypes.Opened.UnexpectedLayout -> ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = state.converted.layout.name,
            body = context.getString(R.string.import_unexpected_body),
            repairs = state.converted.notes.map { it.format(context.resources) },
            language = state.converted.guessedLangId,
            applyWithLanguage = { langId ->
                repository.upsertCustomLayout(
                    state.converted.withLanguage(langId)
                        .copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, state.converted.layout.name)
            },
        )

        is WMFileTypes.Opened.MultilingFile -> multilingProposal(state, repository, context)

        is WMFileTypes.Opened.DesktopLayoutFile -> desktopProposal(state.layouts, repository, context)

        // Drawn by ForeignBackupDialog before this is reached; here only so the
        // list stays exhaustive.
        is WMFileTypes.Opened.ForeignBackupFile -> ImportProposal(
            titleRes = R.string.foreign_backup_title_generic,
            body = "",
        )

        is WMFileTypes.Opened.Font -> fontProposal(state.emoji, repository, context, uri)

        WMFileTypes.Opened.KeySound -> keySoundProposal(repository, context, uri)

        is WMFileTypes.Opened.FlorisLanguagePack -> ImportProposal(
            titleRes = R.string.import_flex_language_title,
            body = context.getString(
                R.string.import_flex_language_body,
                state.schemas.joinToString(", ").ifEmpty { "?" },
            ),
            apply = {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri).use {
                            FlexLanguagePack.import(it, context.filesDir, context.cacheDir)
                        }
                    }.getOrDefault(FlexLanguagePack.Result.Unreadable)
                }
                // A new pack on disk changes the state token the keyboard checks.
                CjkDictDownloadManager.refresh(context.filesDir)
                when (result) {
                    is FlexLanguagePack.Result.Imported ->
                        context.getString(R.string.languages_cjk_flex_import_done, result.schema, result.rows)
                    is FlexLanguagePack.Result.NoUsableTable ->
                        context.getString(R.string.languages_cjk_flex_import_unusable, result.schemas.joinToString(", "))
                    FlexLanguagePack.Result.NotALanguagePack ->
                        context.getString(R.string.languages_cjk_flex_import_not_a_pack)
                    FlexLanguagePack.Result.Unreadable ->
                        context.getString(R.string.languages_cjk_flex_import_unreadable)
                }
            },
        )

        is WMFileTypes.Opened.HeliThemeFile -> {
            val result = state.result
            ImportProposal(
                titleRes = R.string.import_name_title,
                titleArg = result.theme.name,
                body = context.getString(R.string.import_heli_file_body),
                repairs = result.dropped.map { heliDroppedLine(context, it) },
                apply = {
                    // Saved, not switched to, as the theme gallery's own import
                    // does: a converted theme is the thing worth looking at first.
                    val stored = result.theme.copy(id = "custom_${System.currentTimeMillis()}")
                    repository.upsertCustomTheme(stored)
                    context.getString(R.string.import_heli_done, stored.name, result.coloursUsed, result.coloursRead)
                },
            )
        }

        is WMFileTypes.Opened.ForeignLayout -> ImportProposal(
            titleRes = R.string.import_name_title,
            titleArg = state.converted.layout.name,
            body = context.getString(R.string.import_foreign_layout_body),
            repairs = state.converted.notes.map { it.format(context.resources) },
            language = state.converted.guessedLangId,
            applyWithLanguage = { langId ->
                repository.upsertCustomLayout(
                    state.converted.withLanguage(langId)
                        .copy(id = "custom_${System.currentTimeMillis()}"),
                )
                context.getString(R.string.import_done_name, state.converted.layout.name)
            },
        )

        is WMFileTypes.Opened.KaomojiFile -> {
            val preview = state.preview
            ImportProposal(
                titleRes = if (preview.fromEmotic) {
                    R.string.langemoji_kaomoji_import_emotic_title
                } else {
                    R.string.langemoji_kaomoji_import_title
                },
                body = buildString {
                    append(
                        context.resources.getQuantityString(
                            R.plurals.langemoji_kaomoji_import_faces, preview.faces.size, preview.faces.size,
                        ),
                    )
                    if (preview.images.isNotEmpty()) {
                        append("\n\n")
                        append(
                            context.resources.getQuantityString(
                                R.plurals.langemoji_kaomoji_import_images, preview.images.size, preview.images.size,
                            ),
                        )
                    }
                },
                apply = {
                    // Added to the library rather than replacing it: an opened
                    // file is a gift, and the screen is where to start over.
                    coroutineScope {
                        val model = KaomojiModel(File(context.filesDir, KaomojiStore.FILE_PATH), this, repository)
                        applyKaomojiImport(context, model, preview, replace = false)
                    }
                },
            )
        }

        is WMFileTypes.Opened.DoublePinyinScheme -> ImportProposal(
            titleRes = R.string.import_double_pinyin_title,
            body = context.getString(
                R.string.import_double_pinyin_body,
                state.name ?: WMFileTypes.displayName(context, uri),
            ),
            apply = {
                repository.setPinyinDoublePinyinCustom(DoublePinyinProfile.withHeader(state.text), select = true)
                context.getString(R.string.import_double_pinyin_done)
            },
        )

        is WMFileTypes.Opened.InputMethod -> {
            val method = state.method
            ImportProposal(
                titleRes = R.string.import_name_title,
                titleArg = method.name,
                body = context.getString(R.string.import_input_method_body) + "\n\n" +
                    InputMethodImport.summary(method).format(context.resources),
                repairs = InputMethodImport.notes(method).map { it.format(context.resources) },
                language = InputMethodImport.guessLanguage(method),
                applyWithLanguage = { langId ->
                    val id = "custom_${System.currentTimeMillis()}"
                    val layout = withContext(Dispatchers.IO) {
                        InputMethodImport.install(context.filesDir, method, langId, id)
                    }
                    if (layout == null) {
                        context.getString(R.string.layout_editor_im_failed)
                    } else {
                        repository.upsertCustomLayout(layout)
                        context.getString(R.string.layout_editor_import_done_message, layout.name)
                    }
                },
            )
        }

        is WMFileTypes.Opened.Dictionaries -> dictionariesProposal(state.found, repository, context)

        WMFileTypes.Opened.EmojiKeywords -> ImportProposal(
            titleRes = R.string.import_emoji_keywords_title,
            body = context.getString(R.string.import_emoji_keywords_body),
            language = guessFileLanguage(null),
            applyWithLanguage = { langId ->
                val count = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri).use {
                            EmojiKeywordPacks.import(context.filesDir, langId, WMFileTypes.displayName(context, uri), it)
                        }
                    }.getOrDefault(0)
                }
                if (count > 0) repository.bumpEmojiKeywordPackVersion()
                when {
                    count == EmojiKeywordPacks.TOO_LARGE -> context.getString(R.string.customdict_import_too_large_error)
                    count <= 0 -> context.getString(R.string.customdict_emoji_import_empty_error)
                    else -> context.resources.getQuantityString(
                        R.plurals.customdict_emoji_import_added, count, count, LanguageRegistry.byId(langId).displayName,
                    )
                }
            },
        )

        WMFileTypes.Opened.AnkiDeck -> ImportProposal(
            titleRes = R.string.import_anki_title,
            body = context.getString(R.string.import_anki_body),
            apply = {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri).use {
                            VocabPacks.import(context.filesDir, "en", WMFileTypes.displayName(context, uri), it)
                        }
                    }.getOrNull()
                }
                when (result) {
                    is VocabPacks.ImportResult.Imported -> context.resources.getQuantityString(
                        R.plurals.import_vocab_done, result.wordCount, result.wordCount,
                    )
                    else -> context.getString(R.string.import_vocab_error)
                }
            },
        )

        // Drawn by OfflineFilesDialog instead; never reaches here.
        is WMFileTypes.Opened.OfflineFiles -> ImportProposal(
            titleRes = state.kind.titleRes,
            body = "",
        )

        is WMFileTypes.Opened.ForeignSnippets -> foreignSnippetsProposal(state.parsed, context)

        is WMFileTypes.Opened.GboardDictionaryFile -> gboardDictionaryProposal(state.contents, repository, context)

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
                            if (result.restored.isNotEmpty() && !result.settingsFailed) onImported()
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
                                    if (!result.settingsFailed) onImported()
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
                    is SettingsRepository.ImportResult.Applied -> {
                        onImported()
                        context.resources.getQuantityString(
                            R.plurals.import_settings_restored,
                            result.settings,
                            result.settings,
                        )
                    }
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

        // Drawn by [StickerFileImport] before a proposal is asked for (see
        // [ImportFileDialog]); a sticker pack is a page to pick from, not one
        // question.
        WMFileTypes.Opened.Stickers, is WMFileTypes.Opened.WhatsAppStickers -> ImportProposal(
            titleRes = R.string.import_stickers_title,
            body = context.getString(R.string.import_stickers_body),
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

        WMFileTypes.Opened.SoundPack -> ImportProposal(
            titleRes = R.string.import_sound_pack_title,
            body = context.getString(R.string.import_sound_pack_body),
            apply = {
                val store = SoundPackStore.get(context)
                // Names a pack whose own manifest gives no name, resolved here
                // because the import runs off the main thread.
                val fallbackName = WMFileTypes.displayName(context, uri)
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.requireInputStream(uri)
                            .use { SoundPackFile.import(it, store, fallbackName) }
                    }.getOrElse { SoundPackImportResult.Failed }
                }
                // Selected on arrival, the way an imported icon pack is: a pack
                // that stays unselected makes no sound and reads as a failure.
                if (result is SoundPackImportResult.Imported) repository.setKeySoundPackId(result.pack.id)
                describeSoundPackImport(context, result)
            },
        )

        WMFileTypes.Opened.Text -> ImportProposal(
            // Its own title rather than the unrecognised one: the file *can* be
            // opened, and a heading saying it is not a WM Keyboard file read as
            // a dead end sitting above a button that is not one.
            titleRes = R.string.import_text_title,
            body = context.getString(R.string.import_text_body, WMFileTypes.displayName(context, uri)),
            confirmLabelRes = R.string.import_open_editor_action,
            open = { FileEditorActivity.start(context, uri) },
        )

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
 * The confirmation for an Espanso match file or a table of snippets.
 *
 * Worded as a conversion, like the FlorisBoard one below and unlike the app's
 * own snippet file: Espanso can say things this app has no equivalent for, and
 * the notes listing what did not survive are the reason the dialog exists.
 *
 * The snippets land in one folder named after the file, which is what gives an
 * imported pack a single off switch — the same rule the Text Expander screen's
 * own Espanso import follows.
 */
private fun foreignSnippetsProposal(
    parsed: SnippetPayload.Parsed,
    context: android.content.Context,
): ImportProposal {
    val table = parsed.source == SnippetPayload.Source.CSV
    val folderName = parsed.suggestedName.trim()
    if (parsed.snippets.isEmpty()) {
        return ImportProposal(
            titleRes = R.string.import_espanso_title,
            body = context.getString(if (table) R.string.import_csv_none else R.string.import_espanso_none),
            repairs = parsed.notes.map { it.resolve(context) },
            apply = null,
        )
    }
    return ImportProposal(
        titlePluralRes = R.plurals.import_snippets_title,
        titleQuantity = parsed.snippets.size,
        body = context.getString(
            if (table) R.string.import_csv_body else R.string.import_espanso_body,
            folderName.ifEmpty {
                context.getString(if (table) R.string.import_csv_folder_fallback else R.string.import_espanso_folder_fallback)
            },
        ),
        repairs = parsed.notes.map { it.resolve(context) },
        apply = {
            val store = withContext(Dispatchers.IO) {
                SnippetStore(File(context.filesDir, "snippets/snippets.json"))
            }
            withContext(Dispatchers.IO) {
                val target = folderName.takeIf { it.isNotEmpty() }?.let { store.addFolder(it).id } ?: 0L
                // Whole snippets, not a handful of named fields, and fresh ids
                // from the store: importing the same pack twice gives two
                // independent sets rather than silently overwriting the first.
                store.addAll(parsed.snippets, parsed.folders, fallbackFolderId = target)
                // The adds are in-memory only; save() is what writes the file.
                store.save()
            }
            context.resources.getQuantityString(
                R.plurals.import_snippets_done,
                parsed.snippets.size,
                parsed.snippets.size,
            )
        },
    )
}

/**
 * The confirmation for Gboard's personal dictionary export.
 *
 * Both halves go in on one press, because both are what the file is: the words
 * with no shortcut join the personal dictionary as words added by hand, and the
 * shortcuts become snippets in a folder of their own, which gives the set one
 * off switch. Each half is counted in the body, so a file that holds only one
 * of them says so.
 */
private fun gboardDictionaryProposal(
    contents: GboardDictionary.Contents,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    val words = contents.words
    val converted = GboardDictionary.toSnippets(contents)
    val snippets = converted.snippets
    if (words.isEmpty() && snippets.isEmpty()) {
        return ImportProposal(
            titleRes = R.string.import_gboard_dictionary_title,
            body = context.getString(R.string.import_gboard_dictionary_empty),
        )
    }
    val res = context.resources
    return ImportProposal(
        titleRes = R.string.import_gboard_dictionary_title,
        body = buildString {
            if (words.isNotEmpty()) {
                append(res.getQuantityString(R.plurals.import_gboard_dictionary_words, words.size, words.size))
            }
            if (snippets.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(
                    res.getQuantityString(
                        R.plurals.import_gboard_dictionary_shortcuts,
                        snippets.size,
                        snippets.size,
                        GboardDictionary.FOLDER_NAME,
                    ),
                )
            }
        },
        repairs = converted.notes.map { it.resolve(context) },
        apply = {
            if (words.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val lexicon = UserLexicon(File(context.filesDir, "learning/user_lexicon.json"))
                    // Added by hand there, so added by hand here, and a capital
                    // the user wrote is kept, as Android's own import does. A
                    // word Gboard filed under a locale we carry is filed under
                    // that language (#930); the rest belong to every language.
                    for ((word, locale) in contents.localizedWords) {
                        lexicon.addWord(word, caseEvidence = word != word.lowercase())
                        LanguageRegistry.byLocale(locale)?.let { lexicon.setLanguage(word, it.id) }
                    }
                    lexicon.save()
                }
                repository.bumpLexiconVersion()
            }
            if (snippets.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    val store = SnippetStore(File(context.filesDir, "snippets/snippets.json"))
                    val folder = store.addFolder(GboardDictionary.FOLDER_NAME).id
                    store.addAll(snippets, fallbackFolderId = folder)
                    store.save()
                }
            }
            buildString {
                if (words.isNotEmpty()) {
                    append(res.getQuantityString(R.plurals.import_gboard_dictionary_words_done, words.size, words.size))
                }
                if (snippets.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append(res.getQuantityString(R.plurals.import_snippets_done, snippets.size, snippets.size))
                }
            }
        },
    )
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
        repairs = result.dropped.map { florisDroppedLine(it, result, context) },
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
                    converted.stored(
                        if (index == 0) base else "${base}_v$index",
                        dir,
                        FontStore.get(context),
                    )
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

    FlexResult.NotAFlex, FlexResult.Unreadable -> ImportProposal(
        titleRes = R.string.import_unrecognized_title,
        body = context.getString(R.string.import_floris_unreadable_body),
        apply = null,
    )
}

/**
 * The confirmation for a FUTO Keyboard theme.
 *
 * Worded as a conversion, like the FlorisBoard one: what did not come across
 * is listed, and the theme is saved but not switched on.
 */
private fun futoThemeProposal(
    result: FutoThemeResult.Converted,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal = ImportProposal(
    titleRes = R.string.import_futo_theme_title,
    body = buildString {
        append(context.getString(R.string.import_futo_theme_body))
        if (result.author.isNotBlank()) {
            append("\n\n").append(context.getString(R.string.import_futo_theme_credit, result.author))
        }
    },
    repairs = result.dropped.map { futoThemeDroppedLine(context, it) },
    apply = { saveFutoTheme(context, repository, result) },
)

/**
 * Stores a converted FUTO theme, its picture and its font, and says so. Shared
 * by the file dialog and the button on the themes screen.
 */
internal suspend fun saveFutoTheme(
    context: android.content.Context,
    repository: SettingsRepository,
    result: FutoThemeResult.Converted,
): String {
    val stored = withContext(Dispatchers.IO) {
        result.converted.stored(
            "custom_${System.currentTimeMillis()}",
            File(context.filesDir, "theme_images").apply { mkdirs() },
            FontStore.get(context),
        )
    }
    repository.upsertCustomTheme(stored)
    return buildString {
        append(context.getString(R.string.import_futo_theme_done, stored.name))
        if (result.dropped.isNotEmpty()) {
            append("\n\n").append(context.getString(R.string.import_repairs_title))
            for (line in result.dropped) append("\n• ").append(futoThemeDroppedLine(context, line))
        }
    }
}

/** One line of the "what the app changed" list of a FUTO theme import. */
internal fun futoThemeDroppedLine(context: android.content.Context, dropped: FutoThemeUnsupported): String =
    context.getString(
        when (dropped) {
            FutoThemeUnsupported.KEY_PICTURES -> R.string.import_futo_theme_dropped_pictures
            FutoThemeUnsupported.WALLPAPER_COLOURS -> R.string.import_futo_theme_dropped_wallpaper
            FutoThemeUnsupported.SHADER -> R.string.import_futo_theme_dropped_shader
        },
    )

/**
 * The confirmation for a Gboard theme ZIP or an Rboard pack.
 *
 * Worded as a conversion, like the FlorisBoard one: the rule count leads, the
 * named losses follow, and nothing is switched on. A pack has no list to pick
 * from here — this dialog is one question — so it imports every theme in it,
 * each as its own entry, and says so before the button is pressed.
 */
private fun gboardProposal(
    result: GboardResult,
    repository: SettingsRepository,
    context: android.content.Context,
): ImportProposal {
    if (result !is GboardResult.Converted) {
        return ImportProposal(
            titleRes = R.string.import_unrecognized_title,
            body = gboardFailureMessage(context, result),
            apply = null,
        )
    }
    val single = result.themes.singleOrNull()?.takeIf { !result.isPack }
    if (single != null) {
        return ImportProposal(
            titleRes = R.string.import_gboard_title,
            body = gboardThemeBody(context, single, author = ""),
            repairs = single.dropped.map { gboardDroppedLine(context, it) },
            apply = {
                saveGboardTheme(context, repository, single)
                gboardSavedMessage(context, single)
            },
        )
    }
    val count = result.themes.size
    return ImportProposal(
        titleRes = R.string.import_name_title,
        titleArg = result.packName.ifBlank { context.getString(R.string.import_gboard_pack_fallback_title) },
        body = buildString {
            append(context.resources.getQuantityString(R.plurals.import_gboard_pack_file_body, count, count))
            if (result.skipped > 0) {
                append("\n\n")
                append(
                    context.resources.getQuantityString(
                        R.plurals.import_gboard_pack_skipped,
                        result.skipped,
                        result.skipped,
                    ),
                )
            }
            if (result.packAuthor.isNotBlank()) {
                append("\n\n").append(context.getString(R.string.import_gboard_credit, result.packAuthor))
            }
        },
        repairs = result.themes.flatMap { it.dropped }.distinct().map { gboardDroppedLine(context, it) },
        apply = { saveGboardPack(context, repository, result.themes) },
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
internal fun ConvertedTheme.stored(
    id: String,
    dir: File,
    fontStore: FontStore? = null,
): ThemeSpec {
    fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val spec = theme.copy(
        id = id,
        backgroundImageBase64 = images[FlexTheme.IMAGE_BACKGROUND]?.let(::encode),
        // Only a Gboard theme carries a separate landscape photo.
        backgroundImageLandscapeBase64 = images[GboardTheme.IMAGE_BACKGROUND_LANDSCAPE]?.let(::encode),
        assets = images.filterKeys { it != FlexTheme.IMAGE_BACKGROUND && it != GboardTheme.IMAGE_BACKGROUND_LANDSCAPE }
            .mapValues { (_, bytes) -> encode(bytes) },
    ).withExtractedImages(dir)
    val installed = fontStore?.let { installConvertedFont(font, it) } ?: return spec
    return spec.copy(fontId = installed)
}

/**
 * Installs a typeface a `.flex` carried, and gives back the id a theme names it
 * by, or null when there was none or it would not load.
 *
 * A font cannot ride inside a [ThemeSpec] the way an image can: here a font is
 * an add-on in its own right, listed on the fonts screen and shared between
 * themes, and [ThemeSpec.fontId] is only a reference to one. So it is installed
 * through the same call the fonts screen uses, which is also what checks that
 * Android can actually load the file.
 *
 * A failure is deliberately quiet. The theme still converted, and every other
 * thing about it is worth having; a dialog about a typeface would be the only
 * thing standing between the user and a theme they asked for.
 */
private fun installConvertedFont(font: ConvertedFont?, store: FontStore): String? {
    if (font == null) return null
    val result = runCatching {
        font.bytes.inputStream().use { input ->
            FontFile.import(input = input, store = store, name = font.name)
        }
    }.getOrNull()
    return (result as? FontImportResult.Imported)?.font?.id?.let(FontStore::fontIdFor)
}

@StringRes
/**
 * One line of the "what will change" list.
 *
 * All but one are a fixed sentence. The unknown-element line names the parts
 * instead: "some parts of the file" was true of every theme and told the user
 * nothing, and the file's own words for them are what they can match against
 * the stylesheet they are looking at.
 */
private fun florisDroppedLine(
    dropped: FlexUnsupported,
    result: FlexResult.Converted,
    context: android.content.Context,
): String {
    // This phone may have no wallpaper palette at all, in which case those
    // colours came from stock Material and saying "your wallpaper" is false.
    if (dropped == FlexUnsupported.DYNAMIC_COLOR && !result.wallpaperColours) {
        return context.getString(R.string.import_floris_dropped_dynamic_baseline)
    }
    if (dropped != FlexUnsupported.UNKNOWN_ELEMENT || result.unknownElements.isEmpty()) {
        return context.getString(florisDroppedRes(dropped))
    }
    val named = result.unknownElements.take(MAX_NAMED_ELEMENTS).joinToString(", ")
    val rest = result.unknownElements.size - MAX_NAMED_ELEMENTS
    return if (rest > 0) {
        context.resources.getQuantityString(
            R.plurals.import_floris_dropped_unknown_named_more,
            rest,
            named,
            rest,
        )
    } else {
        context.getString(R.string.import_floris_dropped_unknown_named, named)
    }
}

/** Enough to recognise the file, short enough to stay one line of prose. */
private const val MAX_NAMED_ELEMENTS = 4

/**
 * One line of the "what will change" list, for a HeliBoard theme.
 *
 * The contrast line is the FlorisBoard one word for word, because it is the
 * same thing happening — the same guard, in the same place, for the same
 * reason.
 */
internal fun heliDroppedLine(context: android.content.Context, dropped: HeliUnsupported): String =
    context.getString(
        when (dropped) {
            HeliUnsupported.DERIVED_COLOURS -> R.string.import_heli_dropped_derived
            HeliUnsupported.UNUSED_COLOURS -> R.string.import_heli_dropped_unused
            HeliUnsupported.LOW_CONTRAST_FALLBACK -> R.string.import_floris_dropped_contrast
        },
    )

/** One line of the "what the app changed" list of a Multiling O theme import. */
internal fun multilingDroppedLine(context: android.content.Context, dropped: MultilingUnsupported): String =
    context.getString(
        when (dropped) {
            MultilingUnsupported.TEXT_SHADOWS -> R.string.import_multiling_dropped_text_shadows
            MultilingUnsupported.WALLPAPER -> R.string.import_multiling_dropped_wallpaper
            MultilingUnsupported.UNUSED_COLOURS -> R.string.import_multiling_dropped_unused
        },
    )

private fun florisDroppedRes(dropped: FlexUnsupported): Int = when (dropped) {
    FlexUnsupported.SHADOW_COLOR -> R.string.import_floris_dropped_shadow_color
    FlexUnsupported.PER_CORNER_RADIUS -> R.string.import_floris_dropped_corners
    FlexUnsupported.PER_ELEMENT_SPACING -> R.string.import_floris_dropped_spacing
    FlexUnsupported.FONT -> R.string.import_floris_dropped_font
    FlexUnsupported.DYNAMIC_COLOR -> R.string.import_floris_dropped_dynamic_follows
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
