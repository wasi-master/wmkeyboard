package com.wasimaster.wmkeyboard.app

import android.content.Context
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.dictionaries.ContextModelCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.DictionaryCatalog
import com.wasimaster.wmkeyboard.core.dictionaries.NgramPackCatalog
import com.wasimaster.wmkeyboard.core.emoji.EmojiDictCatalog
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.script.LanguageRegistry

/**
 * Language packs: one zip per folder of the data repository
 * (`wmkb-lang-<folder>.zip`, on its [RELEASE_TAG] release), holding that
 * folder's word lists, word pairs and emoji names, and for Chinese, Japanese
 * and Cantonese their conversion dictionaries too. The data repository's
 * `language-packs` workflow builds them; [OfflineImport] installs one.
 *
 * A release is a GitHub thing, so a data repository pointed somewhere else
 * has no packs to link, and the links fall back to the loose files, which
 * every forge serves.
 */
internal object LanguagePacks {

    const val RELEASE_TAG = "language-packs"

    fun zipName(folder: String): String = "wmkb-lang-$folder.zip"

    /** Every data-repo folder holding a file for [langId], preferred list first. */
    fun foldersFor(langId: String): List<String> = buildList {
        DictionaryCatalog.preferred(langId)?.let { add(it.repoCode) }
        DictionaryCatalog.forLanguage(langId).forEach { add(it.repoCode) }
        NgramPackCatalog.forLanguage(langId)?.let { add(it.repoCode) }
        EmojiDictCatalog.forLanguage(langId)?.let { add(it.repoDir) }
        // The conversion dictionaries sit outside data/, and ride in their
        // language's own pack.
        if (langId != "braille" && CjkDictCatalog.forLang(langId).isNotEmpty() && isEmpty()) add(langId)
    }.distinct()

    /** The release's address for [folder]'s pack, or null when the data repository is not on GitHub. */
    fun packUrl(folder: String): String? {
        val repo = ServiceEndpoints.repo(ServiceRepo.DATA)
        if (repo.forge != GitForge.GITHUB) return null
        return "https://${repo.host}/${repo.owner}/${repo.repo}/releases/download/$RELEASE_TAG/${zipName(folder)}"
    }

    /**
     * What to fetch for [langId] on another device: its pack, or where there
     * is none, each file a download would fetch.
     */
    fun links(context: Context, langId: String): List<OfflineLink> {
        val name = LanguageRegistry.byId(langId).displayName
        val packs = foldersFor(langId).mapNotNull { folder ->
            packUrl(folder)?.let { OfflineLink(context.getString(R.string.offline_import_language_pack_label, name), it) }
        }.distinctBy { it.url }
        // The context model is its own file beside the pack (#1000), every size of it.
        val models = ContextModelCatalog.variantsFor(langId).map { OfflineLink(it.fileName, it.url()) }
        if (packs.isNotEmpty()) return packs + models
        return models + buildList {
            DictionaryCatalog.forLanguage(langId).forEach { add(OfflineLink(it.fileName, it.url)) }
            NgramPackCatalog.forLanguage(langId)?.let {
                add(OfflineLink(it.bigramFileName, it.bigramUrl()))
                add(OfflineLink(it.trigramFileName, it.trigramUrl()))
            }
            EmojiDictCatalog.forLanguage(langId)?.let { add(OfflineLink(it.fileName, it.url)) }
            CjkDictCatalog.forLang(langId).filter { it.available }.forEach {
                add(OfflineLink(context.getString(it.displayNameRes), it.url))
            }
        }
    }
}
