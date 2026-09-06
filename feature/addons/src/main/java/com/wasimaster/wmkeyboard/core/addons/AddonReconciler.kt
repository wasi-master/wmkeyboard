package com.wasimaster.wmkeyboard.core.addons

import android.content.Context
import com.wasimaster.wmkeyboard.core.feedback.SoundPackStore
import com.wasimaster.wmkeyboard.core.feedback.SoundStore
import com.wasimaster.wmkeyboard.core.fonts.FontStore
import com.wasimaster.wmkeyboard.core.icons.IconPackStore
import com.wasimaster.wmkeyboard.core.plugins.PluginStore
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.snippets.SnippetStore
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Makes the installed-addon list agree with what is actually on the device.
 *
 * Nothing forces a user through the Addons screen to get rid of something they
 * installed from it. A theme is deleted from the Themes gallery, an icon pack
 * from the Icons screen, a font from the font picker — all of which do the right
 * thing locally and leave [AddonStore] still claiming the addon is installed.
 * The visible result is an addon page offering *Uninstall* for something that is
 * already gone, and offering *Update* for it later on.
 *
 * So the addon screens sweep on the way in: ask each record's own subsystem
 * whether its [InstalledAddon.localRef] still resolves, and drop the ones that
 * don't. The reverse case needs no handling — an addon installed and then
 * deleted and then re-created by hand is simply the user's own theme now.
 */
object AddonReconciler {

    /**
     * Drops records whose local target is gone. Reads DataStore and several pack
     * stores, so call it off the main thread.
     */
    suspend fun reconcile(context: Context, store: AddonStore) {
        val app = context.applicationContext
        // One settings read for the whole sweep: themes and layouts both live in
        // DataStore, and collecting it once per record would be a read per addon.
        val settings = SettingsRepository(app).settings.first()
        val themeIds = settings.customThemes.mapTo(HashSet()) { it.id }
        val layoutIds = settings.customLayouts.mapTo(HashSet()) { it.id }
        val snippets = SnippetStore(File(app.filesDir, "snippets/snippets.json"))
        adoptPackFolders(snippets, store)
        val snippetIds = snippets.items().mapTo(HashSet()) { it.id }

        store.reconcileInstalled { record ->
            when (record.type) {
                AddonType.Theme -> record.localRef in themeIds
                AddonType.Layout -> record.localRef in layoutIds
                AddonType.Dictionary, AddonType.EmojiKeywords, AddonType.Vocabulary ->
                    File(record.localRef).exists()
                // A snippet pack installs several snippets at once and records
                // their ids comma-joined. Deleting one of five by hand doesn't
                // mean the pack is gone, so the pack survives while any of its
                // snippets does.
                AddonType.Snippets, AddonType.Espanso -> record.localRef.split(',')
                    .mapNotNull { it.trim().toLongOrNull() }
                    .any { it in snippetIds }
                AddonType.Stickers -> StickerPackStore.get(app).pack(record.localRef) != null
                AddonType.IconPack -> IconPackStore.get(app).pack(record.localRef) != null
                AddonType.Font, AddonType.EmojiFont ->
                    FontStore.get(app).font(record.localRef) != null
                AddonType.Sound -> SoundStore.get(app).sound(record.localRef) != null
                AddonType.SoundPack -> SoundPackStore.get(app).pack(record.localRef) != null
                AddonType.Plugin -> PluginStore.get(app).plugin(record.localRef) != null
                // A record this build can't interpret is left alone rather than
                // deleted: it may well belong to a type a newer build installed.
                AddonType.Unknown -> true
            }
        }
    }

    /**
     * Files the snippets of already-installed packs into folders named after
     * them, for packs installed before snippets had folders.
     *
     * The record already holds everything this needs — the pack's name and the
     * ids it added — so the grouping can be recovered rather than lost to
     * whoever installed early. It runs on the sweep the addon screens already
     * do, and it is idempotent twice over: a pack whose snippets are all in
     * some folder is skipped, and one whose snippets are gone has nothing to
     * move.
     *
     * A pack whose snippets the user has since filed by hand is left exactly as
     * they filed it. Their arrangement is newer information than the pack's.
     */
    private fun adoptPackFolders(snippets: SnippetStore, store: AddonStore) {
        var moved = false
        for ((key, record) in store.installed()) {
            if (adoptPackFolder(snippets, key, record)) moved = true
        }
        if (moved) snippets.save()
    }

    /** One record's share of [adoptPackFolders]; true when it moved anything. */
    private fun adoptPackFolder(
        snippets: SnippetStore,
        key: String,
        record: InstalledAddon,
    ): Boolean {
        if (record.type != AddonType.Snippets && record.type != AddonType.Espanso) return false
        val ids = record.localRef.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
        if (ids.isEmpty()) return false
        val own = snippets.items().filter { it.id in ids }
        // Nothing of the pack left, or the user has already filed some of it.
        // Either way there is nothing here to put right.
        if (own.isEmpty() || own.any { it.folderId != 0L }) return false
        // The key is "<repoId>/<addonId>", so its tail names the pack when the
        // record predates the field that keeps its display name.
        val name = record.name.trim().ifBlank { key.substringAfterLast('/') }
        if (name.isBlank()) return false
        val folder = snippets.addFolder(name)
        for (snippet in own) snippets.moveToFolder(snippet.id, folder.id)
        return true
    }
}
