package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.vocab.VocabPacks
import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.addons.feature.R
import com.wasimaster.wmkeyboard.core.feedback.KeySoundPlayer
import com.wasimaster.wmkeyboard.core.feedback.SoundFile
import com.wasimaster.wmkeyboard.core.feedback.SoundImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackFile
import com.wasimaster.wmkeyboard.core.feedback.SoundPackImportResult
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontFile
import com.wasimaster.wmkeyboard.core.fonts.FontImportResult
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.IconImportResult
import com.wasimaster.wmkeyboard.core.icons.IconPackFile
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.layout.LayoutFile
import com.wasimaster.wmkeyboard.core.plugins.PluginFile
import com.wasimaster.wmkeyboard.core.plugins.PluginImportResult
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.plugins.PluginText
import com.wasimaster.wmkeyboard.core.emoji.EmojiKeywordPacks
import com.wasimaster.wmkeyboard.core.prediction.CustomDictionaries
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.content.ContentText
import com.wasimaster.wmkeyboard.core.snippets.Snippet
import com.wasimaster.wmkeyboard.core.snippets.SnippetFile
import com.wasimaster.wmkeyboard.core.snippets.SnippetPayload
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.stickers.StickerImportResult
import com.wasimaster.wmkeyboard.core.stickers.StickerPackFile
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.theme.ThemeCodec
import com.wasimaster.wmkeyboard.core.theme.withExtractedImages
import com.wasimaster.wmkeyboard.core.theme.withFreshIds
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * A line of text for the user that the thing producing it cannot put into
 * words itself.
 *
 * The installer, the downloader and the preview reader all run off the main
 * thread, and their results outlive the screen that asked for them: a download
 * carries on while the user opens another page, and a status flow survives
 * rotation. So a message travels as a string resource with its arguments, and
 * the screen that shows it calls [resolve]. That also keeps the words in the
 * language the user reads at the moment they read them.
 */
sealed interface AddonText {

    /**
     * A line this build wrote. [arg1] and [arg2] fill `%1$s` and `%2$s`, in
     * that order.
     *
     * Set [textRes] for a plain line, or [pluralsRes] together with [quantity]
     * when the wording depends on a count, the same shape `ContentText` uses in
     * :core:content. A count needs it: languages disagree about how many number
     * forms a sentence has, so only the plural resource keeps the agreement
     * right outside English. For a plural line the count is normally [arg1] as
     * well, once to pick the form and once to fill `%1$d`.
     */
    data class Resource(
        @get:StringRes val textRes: Int = 0,
        @get:PluralsRes val pluralsRes: Int = 0,
        val quantity: Int = 0,
        val arg1: Any? = null,
        val arg2: Any? = null,
    ) : AddonText

    /**
     * Text that arrived already in words: a plugin author's own error line, or
     * a message an importer in another module resolved before handing it over.
     */
    data class Literal(val text: String) : AddonText

    companion object {
        /** A message with no arguments. */
        fun of(@StringRes textRes: Int): AddonText = Resource(textRes)

        /** A message with one argument. */
        fun of(@StringRes textRes: Int, arg1: Any): AddonText = Resource(textRes, arg1 = arg1)

        /**
         * A message whose wording depends on [count]. The count both picks the
         * form and fills the line's first placeholder.
         */
        fun ofCount(@PluralsRes pluralsRes: Int, count: Int): AddonText =
            Resource(pluralsRes = pluralsRes, quantity = count, arg1 = count)
    }
}

/** Puts an [AddonText] into words. Call this where the text is drawn. */
fun AddonText.resolve(context: Context): String = when (this) {
    is AddonText.Literal -> text
    is AddonText.Resource -> if (pluralsRes != 0) {
        when {
            arg1 == null -> context.resources.getQuantityString(pluralsRes, quantity)
            arg2 == null -> context.resources.getQuantityString(pluralsRes, quantity, arg1)
            else -> context.resources.getQuantityString(pluralsRes, quantity, arg1, arg2)
        }
    } else {
        when {
            arg1 == null -> context.getString(textRes)
            arg2 == null -> context.getString(textRes, arg1)
            else -> context.getString(textRes, arg1, arg2)
        }
    }
}

/** The same line, carried out of the plugin subsystem unresolved. */
fun PluginText.toAddonText(): AddonText = when (this) {
    is PluginText.Script -> AddonText.Literal(text)
    is PluginText.Resource -> AddonText.Resource(textRes, arg1 = arg1, arg2 = arg2)
}

/** The same line, carried out of :core:content unresolved. */
fun ContentText.toAddonText(): AddonText = AddonText.Resource(
    textRes = stringRes,
    pluralsRes = pluralsRes,
    quantity = quantity,
    arg1 = args.getOrNull(0),
    arg2 = args.getOrNull(1),
)

/**
 * Turning a downloaded payload into an installed addon.
 *
 * This is the whole point of the repository format being an *index* rather
 * than a package format: every branch below hands the file to an importer the
 * app already had for local file import, so installing from a URL and opening
 * a `.wmtheme.json` from the Downloads folder end up in exactly the same code.
 * Nothing here parses a payload itself.
 *
 * Every branch records a `localRef` — whatever handle the importer produced —
 * so [uninstall] can reverse precisely that one install and nothing else.
 *
 * Nothing here *selects* what it installs. Putting a theme on the device and
 * wearing it are two decisions, and only the first one was asked for; the
 * second is [AddonApply]'s, offered as a question once the file has landed.
 */
object AddonInstaller {

    sealed interface Outcome {
        /**
         * [localRef] is the handle to undo this install: a custom theme or
         * layout id, a pack id, a font or sound id, or a dictionary file path.
         * [repairs] carries whatever the importer had to fix on the way in.
         */
        data class Installed(val localRef: String, val repairs: List<String> = emptyList()) : Outcome

        /** The importer refused it, with a line worth showing the user. */
        data class Rejected(val text: AddonText) : Outcome
    }

    suspend fun install(context: Context, entry: AddonEntry, payload: File): Outcome {
        val outcome = installPayload(context, entry, payload)
        if (outcome is Outcome.Installed) notifyContentChanged(context, entry.type)
        return outcome
    }

    private suspend fun installPayload(
        context: Context,
        entry: AddonEntry,
        payload: File,
    ): Outcome =
        when (entry.type) {
            AddonType.Theme -> installTheme(context, payload)
            AddonType.Layout -> installLayout(context, payload)
            AddonType.Dictionary -> installDictionary(context, entry, payload)
            AddonType.EmojiKeywords -> installEmojiKeywords(context, entry, payload)
            AddonType.Snippets -> installSnippets(context, entry, payload)
            AddonType.Espanso -> installEspanso(context, entry, payload)
            AddonType.Stickers -> installStickers(context, entry, payload)
            AddonType.IconPack -> installIconPack(context, entry, payload)
            AddonType.Font -> installFont(context, entry, payload, emoji = false)
            AddonType.EmojiFont -> installFont(context, entry, payload, emoji = true)
            AddonType.Sound -> installSound(context, entry, payload)
            AddonType.SoundPack -> installSoundPack(context, entry, payload)
            AddonType.Plugin -> installPlugin(context, payload)
            AddonType.Vocabulary -> installVocabulary(context, entry, payload)
            AddonType.Unknown ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_unknown_type))
        }

    /**
     * Undoes what [install] recorded. Best-effort by design: the user may well
     * have deleted the theme by hand already, and the desired end state — it's
     * gone, and the store no longer claims it's installed — is the same either
     * way.
     */
    suspend fun uninstall(context: Context, record: InstalledAddon) {
        when (record.type) {
            // deleteCustomTheme already falls the active theme back to a
            // built-in when the deleted one was in use.
            AddonType.Theme -> SettingsRepository(context).deleteCustomTheme(record.localRef)
            AddonType.Layout -> SettingsRepository(context).deleteCustomLayout(record.localRef)
            AddonType.Dictionary, AddonType.EmojiKeywords -> File(record.localRef).delete()
            // The pack and its translation sidecars; the learning progress stays.
            AddonType.Vocabulary -> VocabPacks.remove(File(record.localRef))
            AddonType.Snippets, AddonType.Espanso -> removeSnippets(context, record.localRef)
            AddonType.Stickers -> StickerPackStore.get(context).deletePack(record.localRef)
            AddonType.IconPack -> uninstallIconPack(context, record.localRef)
            AddonType.Font -> uninstallFont(context, record.localRef)
            AddonType.EmojiFont -> uninstallEmojiFont(context, record.localRef)
            AddonType.Sound -> uninstallSound(context, record.localRef)
            AddonType.SoundPack -> uninstallSoundPack(context, record.localRef)
            // Deletes the plugin's whole directory: script, stored data and log.
            AddonType.Plugin -> PluginStore.get(context).delete(record.localRef)
            AddonType.Unknown -> Unit
        }
        notifyContentChanged(context, record.type)
    }

    /**
     * Nudges the running keyboard for the types whose content it holds in
     * memory rather than re-reading per use. Without it an imported dictionary
     * or emoji keyword pack sits on disk doing nothing until the IME next
     * restarts — installed, by every visible sign, and inert.
     */
    private suspend fun notifyContentChanged(context: Context, type: AddonType) {
        when (type) {
            AddonType.Dictionary -> SettingsRepository(context).bumpCustomDictVersion()
            AddonType.EmojiKeywords ->
                SettingsRepository(context).bumpEmojiKeywordPackVersion()
            // The rest are read from disk when they are used, or are already
            // live the moment the importer returns. Listed rather than
            // defaulted so a new type has to decide.
            AddonType.Theme, AddonType.Layout, AddonType.Snippets, AddonType.Espanso,
            AddonType.Stickers, AddonType.IconPack, AddonType.Font,
            AddonType.EmojiFont, AddonType.Sound, AddonType.SoundPack, AddonType.Plugin,
            // The keyboard re-reads the pack folder by file token on its next focus.
            AddonType.Vocabulary,
            AddonType.Unknown,
            -> Unit
        }
    }

    // ---- plugins --------------------------------------------------------

    /**
     * Installs a `.wmplugin`.
     *
     * Nothing runs: the script lands on disk and, unlike a plugin opened from a
     * local file, lands switched *off*. "Apply" for code would mean "execute",
     * which is not a thing an install should do on its own — so the plugin does
     * not even appear in the panel until the user says yes to [AddonApply].
     */
    private fun installPlugin(context: Context, payload: File): Outcome {
        val store = PluginStore.get(context)
        // The master switch gates installing as well as running. Someone who
        // has not turned plugins on should not end up with one on disk because
        // they tapped Install on a catalogue page.
        if (!store.subsystemEnabled()) {
            // Name the switch and where it lives. "Turn on plugins" on its own
            // reads as already done to anyone who has enabled the Plugins tool
            // on the toolbar, which is a different setting.
            return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_plugins_turned_off),
            )
        }
        return when (val result = payload.inputStream().use { PluginFile.import(it, store) }) {
            is PluginImportResult.Imported -> {
                // PluginFile.import adopts a plugin enabled, which is right for
                // a file the user opened by hand and wrong for one that arrived
                // from a repository — that one is offered, not switched on.
                store.setEnabled(result.plugin.id, false)
                Outcome.Installed(result.plugin.id)
            }
            is PluginImportResult.Rejected -> Outcome.Rejected(result.reasonText.toAddonText())
            PluginImportResult.NotAPlugin ->
                Outcome.Rejected(AddonText.of(R.string.faddons_error_not_a_plugin))
            PluginImportResult.TooManyPlugins -> Outcome.Rejected(
                AddonText.ofCount(
                    R.plurals.faddons_install_error_too_many_plugins,
                    PluginStore.MAX_PLUGINS,
                ),
            )

            PluginImportResult.Failed ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_plugin_failed))
        }
    }

    // ---- themes & layouts ----------------------------------------------

    private suspend fun installTheme(context: Context, payload: File): Outcome {
        val theme = ThemeCodec.decode(payload.readText())
            ?: return Outcome.Rejected(AddonText.of(R.string.faddons_install_error_not_a_theme))
        val id = "custom_${System.currentTimeMillis()}"
        // withExtractedImages writes any base64-embedded background out to
        // app-private storage and rewrites the path, which is also what drops
        // any absolute path the file arrived with — a theme must never point
        // at a file outside our own storage. withFreshIds remints the
        // variants' ids too, for the same reason the parent's changes.
        val stored = theme.withFreshIds(id).withExtractedImages(themeImagesDir(context))
        SettingsRepository(context).upsertCustomTheme(stored)
        // Added to the gallery, not worn: browsing a repository must not repaint
        // the keyboard behind the user's back. AddonApply offers the switch.
        return Outcome.Installed(id)
    }

    private suspend fun installLayout(context: Context, payload: File): Outcome {
        val imported = LayoutFile.decode(payload.readText())
            ?: return Outcome.Rejected(AddonText.of(R.string.faddons_install_error_not_a_layout))
        val id = "custom_${System.currentTimeMillis()}"
        SettingsRepository(context).upsertCustomLayout(imported.layout.copy(id = id))
        // The layout repairer hands back resource ids; the other importers hand
        // back finished lines. They meet here, so the layout notes are put into
        // words now rather than travelling on as a second kind of repair.
        return Outcome.Installed(id, imported.repairNotes.map { it.format(context.resources) })
    }

    // ---- dictionaries ---------------------------------------------------

    private fun installDictionary(context: Context, entry: AddonEntry, payload: File): Outcome {
        val langId = entry.langId?.takeIf { it.isNotBlank() }
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_dictionary_no_language),
            )
        // byId falls back to a generic definition rather than failing, so the
        // registry has to be asked directly. A repository can ship words for a
        // language the app knows; it cannot add a new language.
        if (LanguageRegistry.all.none { it.id == langId }) {
            return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_unknown_language, langId),
            )
        }

        val name = entry.name.ifBlank { entry.id }
        val words = openPayload(entry, payload).use { stream ->
            CustomDictionaries.import(context.filesDir, langId, name, stream)
        }
        if (words == 0) {
            return Outcome.Rejected(AddonText.of(R.string.faddons_error_no_words))
        }
        // import() names the file itself to avoid collisions, so the newest
        // file in the language's folder is the one it just wrote.
        val created = CustomDictionaries.lists(context.filesDir, langId)
            .maxByOrNull { it.lastModified() }
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_dictionary_not_saved),
            )
        return Outcome.Installed(created.absolutePath)
    }

    // ---- emoji keyword packs --------------------------------------------

    /**
     * Installs an emoji keyword pack: the keywords one language uses for the
     * emoji the app already carries, so search and prediction work in it.
     *
     * Deliberately the same shape as [installDictionary] — same per-language
     * folder layout, same "did anything parse?" gate, same gzip tolerance —
     * because it is the same kind of payload arriving by the same route.
     */
    private fun installEmojiKeywords(context: Context, entry: AddonEntry, payload: File): Outcome {
        val langId = entry.langId?.takeIf { it.isNotBlank() }
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_keywords_no_language),
            )
        if (LanguageRegistry.all.none { it.id == langId }) {
            return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_unknown_language, langId),
            )
        }

        val name = entry.name.ifBlank { entry.id }
        val emoji = openPayload(entry, payload).use { stream ->
            EmojiKeywordPacks.import(context.filesDir, langId, name, stream)
        }
        if (emoji == EmojiKeywordPacks.TOO_LARGE) {
            return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_keywords_too_large),
            )
        }
        if (emoji == 0) {
            return Outcome.Rejected(AddonText.of(R.string.faddons_install_error_no_keywords))
        }
        // import() names the file itself to avoid collisions, so the newest
        // file in the language's folder is the one it just wrote.
        val created = EmojiKeywordPacks.packs(context.filesDir, langId)
            .maxByOrNull { it.lastModified() }
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_keywords_not_saved),
            )
        return Outcome.Installed(created.absolutePath)
    }

    /**
     * Installs a vocabulary pack into the language's folder. The reader
     * inflates a gzipped payload itself, so the repo may host either form.
     */
    private fun installVocabulary(context: Context, entry: AddonEntry, payload: File): Outcome {
        val langId = entry.langId?.takeIf { it.isNotBlank() }
            ?: return Outcome.Rejected(AddonText.of(R.string.faddons_install_error_vocab_no_language))
        if (LanguageRegistry.all.none { it.id == langId }) {
            return Outcome.Rejected(AddonText.of(R.string.faddons_install_error_unknown_language, langId))
        }
        val name = entry.name.ifBlank { entry.id }
        val result = payload.inputStream().buffered().use { stream ->
            VocabPacks.import(context.filesDir, langId, name, stream)
        }
        return when (result) {
            is VocabPacks.ImportResult.Imported -> Outcome.Installed(result.file.absolutePath)
            VocabPacks.ImportResult.TooLarge ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_vocab_too_large))
            VocabPacks.ImportResult.NotAPack ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_vocab_not_a_pack))
            VocabPacks.ImportResult.Empty ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_vocab_no_words))
        }
    }

    /** Unwraps a `.gz` payload; the format allows dictionaries to be gzipped. */
    private fun openPayload(entry: AddonEntry, payload: File): InputStream {
        val stream = payload.inputStream().buffered()
        return if (entry.path.endsWith(".gz", ignoreCase = true)) GZIPInputStream(stream) else stream
    }

    // ---- snippets --------------------------------------------------------

    /**
     * Installs a snippet pack into a folder of its own, named after the pack.
     *
     * A pack is a set, not thirty loose snippets: a folder is what lets it be
     * switched off in one gesture, found again in six months, and uninstalled
     * without hunting. Any folders the pack file declares itself are flattened
     * into that one — folders are a single level, and a pack that arrived as
     * four groups nested under its own name would be a structure this app has
     * no way to draw.
     */
    private fun installSnippets(context: Context, entry: AddonEntry, payload: File): Outcome {
        val imported = SnippetFile.decode(payload.readText())
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_not_a_snippet_pack),
            )
        return storeSnippets(context, entry, imported.snippets, imported.repairs.map { it.resolve(context) })
    }

    /**
     * Installs an Espanso package or match file.
     *
     * A separate branch rather than a widening of [installSnippets], because a
     * repository entry declares which format its payload is in: a `.yml` filed
     * as `snippets` is a mistake in the manifest, and saying so beats guessing.
     * What the two share is everything after the conversion, which is why they
     * meet again in [storeSnippets] — an Espanso pack has to uninstall by
     * exactly the same rules, or its folder would outlive it.
     */
    private fun installEspanso(context: Context, entry: AddonEntry, payload: File): Outcome {
        val imported = SnippetPayload.read(payload, entry.name.ifBlank { entry.id })
            ?.takeIf { it.isEspanso }
            ?: return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_not_an_espanso_pack),
            )
        return storeSnippets(context, entry, imported.snippets, imported.notes.map { it.resolve(context) })
    }

    /** The half of a snippet install that does not care where the file came from. */
    private fun storeSnippets(
        context: Context,
        entry: AddonEntry,
        snippets: List<Snippet>,
        repairs: List<String>,
    ): Outcome {
        if (snippets.isEmpty()) {
            return Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_snippet_pack_empty),
            )
        }
        val store = SnippetStore(snippetsFile(context))
        val folder = store.addFolder(
            entry.name.trim().ifBlank {
                context.getString(R.string.faddons_snippet_pack_folder_fallback)
            },
        )
        // Ids in the file are ignored — the store assigns fresh ones — so the
        // ids it hands back are what uninstall has to remember. Whole snippets
        // go in: rebuilding them out of named fields would silently strip
        // whatever the format gained last, and a pack would install with its
        // patterns missing and nothing to say so.
        //
        // The localRef stays the comma-joined snippet ids it has always been.
        // The folder is found back from them at uninstall, which keeps every
        // record written by an older build readable by this one.
        val added = store.addAll(snippets, fallbackFolderId = folder.id).map { it.id }
        // The adds only mutate the in-memory list; save() is what writes the file.
        store.save()
        return Outcome.Installed(added.joinToString(","), repairs)
    }

    private fun removeSnippets(context: Context, localRef: String) {
        val store = SnippetStore(snippetsFile(context))
        val ids = localRef.split(',').mapNotNull { it.trim().toLongOrNull() }
        // Read before the removal: afterwards there is nothing left pointing at
        // the pack's folder to find it by.
        val folders = store.items()
            .filter { it.id in ids }
            .mapTo(HashSet()) { it.folderId }
        ids.forEach { store.remove(it) }
        // The folder goes with the pack, unless the user has moved something
        // else into it — then it is theirs now and outlives the pack.
        folders.filter { it != 0L }.forEach { store.removeFolderIfEmpty(it) }
        store.save()
    }

    // ---- packs -----------------------------------------------------------

    private fun installStickers(context: Context, entry: AddonEntry, payload: File): Outcome {
        val store = StickerPackStore.get(context)
        val result = payload.inputStream().use {
            // Names a pack whose own file gives no name, as the icon path does.
            StickerPackFile.import(it, store, defaultName = entry.name.ifBlank { entry.id })
        }
        return when (result) {
            is StickerImportResult.Imported ->
                Outcome.Installed(result.pack.id, result.repairs.map { it.resolve(context) })
            StickerImportResult.NotAStickerPack ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_not_a_sticker_pack))
            is StickerImportResult.NoStickers -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_no_stickers, result.repairs.first().resolve(context)),
            )
            StickerImportResult.TooManyPacks -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_too_many_sticker_packs),
            )
            StickerImportResult.Failed -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_sticker_pack_unreadable),
            )
        }
    }

    private fun installIconPack(context: Context, entry: AddonEntry, payload: File): Outcome {
        val store = IconPackStore.get(context)
        val result = payload.inputStream().use {
            // Names a pack whose own manifest gives no name.
            IconPackFile.import(it, store, defaultName = entry.name.ifBlank { entry.id })
        }
        return when (result) {
            is IconImportResult.Imported ->
                Outcome.Installed(result.pack.id, result.repairs.map { it.resolve(context) })
            IconImportResult.NotAnIconPack ->
                Outcome.Rejected(AddonText.of(R.string.faddons_install_error_not_an_icon_pack))
            IconImportResult.TooManyPacks -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_too_many_icon_packs),
            )
            IconImportResult.Failed -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_icon_pack_unreadable),
            )
        }
    }

    private suspend fun uninstallIconPack(context: Context, packId: String) {
        IconPackStore.get(context).deletePack(packId)
        // Drops the active-pack setting and any per-slot override pinned to it,
        // so the keyboard falls back to its built-in icons instead of resolving
        // half its glyphs to a pack that no longer exists.
        SettingsRepository(context).forgetIconPack(packId)
    }

    // ---- fonts & sounds --------------------------------------------------

    private fun installFont(
        context: Context,
        entry: AddonEntry,
        payload: File,
        emoji: Boolean,
    ): Outcome {
        val store = FontStore.get(context)
        val result = payload.inputStream().use {
            FontFile.import(
                input = it,
                store = store,
                name = entry.name.ifBlank { entry.id },
                author = entry.author,
                version = entry.version,
                langIds = entry.languages,
                emoji = emoji,
            )
        }
        return when (result) {
            // An emoji font has one obvious slot and a text face has several
            // (English, Bengali, per-script), but neither is filled here: the
            // font joins the library, and AddonApply asks about the emoji slot.
            is FontImportResult.Imported -> Outcome.Installed(result.font.id)
            is FontImportResult.NotAFont -> Outcome.Rejected(AddonText.of(result.messageRes))
            FontImportResult.TooManyFonts -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_too_many_fonts),
            )
            is FontImportResult.Failed -> Outcome.Rejected(
                AddonText.Resource(
                    result.messageRes,
                    arg1 = result.messageArgs.getOrNull(0),
                    arg2 = result.messageArgs.getOrNull(1),
                ),
            )
        }
    }

    private suspend fun uninstallSound(context: Context, soundId: String) {
        SoundStore.get(context).delete(soundId)
        SettingsRepository(context).forgetKeySound(soundId)
        // The pool holds a decoded copy that outlives the file.
        KeySoundPlayer.forgetCustom(soundId)
    }

    private suspend fun uninstallSoundPack(context: Context, packId: String) {
        SoundPackStore.get(context).delete(packId)
        SettingsRepository(context).forgetKeySoundPack(packId)
        // Same reason as a single sound: the pool's samples outlive the files.
        KeySoundPlayer.forgetPack(packId)
    }

    private suspend fun uninstallFont(context: Context, fontId: String) {
        FontStore.get(context).delete(fontId)
        // A text face can be selected in three places — English, Bengali, and
        // any per-script override — and deleting the file from under a live
        // selection leaves the keyboard drawing with the fallback while the
        // picker still shows the font chosen.
        SettingsRepository(context).forgetInstalledFont(FontStore.fontIdFor(fontId))
    }

    private suspend fun uninstallEmojiFont(context: Context, fontId: String) {
        FontStore.get(context).delete(fontId)
        // Leaving the choice pointing at a deleted file would render every
        // emoji with the fallback face while the setting still claims otherwise.
        SettingsRepository(context).forgetInstalledEmojiFont(fontId)
    }

    private fun installSound(context: Context, entry: AddonEntry, payload: File): Outcome {
        val store = SoundStore.get(context)
        val result = payload.inputStream().use {
            SoundFile.import(
                input = it,
                store = store,
                name = entry.name.ifBlank { entry.id },
                author = entry.author,
                version = entry.version,
            )
        }
        return when (result) {
            // Added to the sound library and silent until chosen. Installing a
            // sound is not consent for the keyboard to start making it.
            is SoundImportResult.Imported -> Outcome.Installed(result.sound.id)
            is SoundImportResult.NotASound -> Outcome.Rejected(AddonText.of(result.messageRes))
            SoundImportResult.TooManySounds -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_too_many_sounds),
            )
            // An empty messageArg means the line takes no argument.
            is SoundImportResult.Failed -> Outcome.Rejected(
                AddonText.Resource(result.messageRes, arg1 = result.messageArg.ifEmpty { null }),
            )
        }
    }

    /**
     * Installs a `.wmsoundpack`: many recordings of one keyboard, played one
     * per keystroke.
     *
     * Silent until chosen, exactly like a single sound — [AddonApply] asks.
     */
    private fun installSoundPack(context: Context, entry: AddonEntry, payload: File): Outcome {
        val store = SoundPackStore.get(context)
        val result = payload.inputStream().use {
            SoundPackFile.import(
                input = it,
                store = store,
                fallbackName = entry.name.ifBlank { entry.id },
                version = entry.version,
            )
        }
        return when (result) {
            is SoundPackImportResult.Imported -> Outcome.Installed(result.pack.id)
            SoundPackImportResult.NotASoundPack -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_not_a_sound_pack),
            )
            SoundPackImportResult.TooManyPacks -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_too_many_sound_packs),
            )
            // An empty messageArg means the line takes no argument.
            is SoundPackImportResult.Rejected -> Outcome.Rejected(
                AddonText.Resource(result.messageRes, arg1 = result.messageArg.ifEmpty { null }),
            )
            SoundPackImportResult.Failed -> Outcome.Rejected(
                AddonText.of(R.string.faddons_install_error_sound_pack_read),
            )
        }
    }

    // ---- shared paths ----------------------------------------------------

    private fun themeImagesDir(context: Context): File =
        File(context.filesDir, "theme_images").apply { mkdirs() }

    private fun snippetsFile(context: Context): File =
        File(context.filesDir, "snippets/snippets.json")
}
