package com.wasimaster.wmkeyboard.ime.files

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.addons.AddonDownloadManager
import com.wasimaster.wmkeyboard.core.addons.AddonRepoCodec
import com.wasimaster.wmkeyboard.core.addons.AddonStore
import com.wasimaster.wmkeyboard.core.addons.AddonType
import com.wasimaster.wmkeyboard.core.files.FilePins
import com.wasimaster.wmkeyboard.core.settings.DeviceNetworkState
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.stopsBackgroundWork
import kotlinx.coroutines.flow.first

/**
 * A plugin from one of the user's add-on repositories that would draw a file
 * nothing installed draws: "Office Viewer can show this file", with Get.
 *
 * [manifestUrl] and [addonId] name its page in the app's add-on store, which
 * is where it installs, after the user has seen its permissions there.
 */
data class PluginOffer(
    val manifestUrl: String,
    val addonId: String,
    val name: String,
    val description: String,
)

/**
 * Finds [PluginOffer]s in the repositories' cached manifests, from the
 * `fileTypes` and `fileNames` each plugin entry lists, and keeps the official
 * repository's manifest fresh enough to find them.
 *
 * Nothing here installs anything: the offer only opens the add-on's page.
 */
internal class PluginOffers(context: Context) {
    private val app = context.applicationContext

    private class Candidate(val offer: PluginOffer, val types: Set<String>, val names: Set<String>)

    @Volatile
    private var index: Pair<String, List<Candidate>>? = null

    /** The cached manifests as candidates, rebuilt when any repository's cache changes. */
    private fun candidates(): List<Candidate> {
        val store = AddonStore.get(app)
        val repos = store.repos()
        val stamp = repos.joinToString("|") { "${it.manifestUrl}@${it.fetchedAt}" }
        index?.takeIf { it.first == stamp }?.let { return it.second }
        val built = buildList {
            for (ref in repos) {
                val manifest = ref.cachedManifest.takeIf { it.isNotBlank() }?.let(AddonRepoCodec::decode) ?: continue
                for (entry in manifest.addons) {
                    if (entry.type != AddonType.Plugin || (entry.fileTypes.isEmpty() && entry.fileNames.isEmpty())) continue
                    // One this build cannot install would only send the user to "update the app".
                    if ((entry.minAppVersion ?: 0) > BuildConfig.VERSION_CODE) continue
                    add(
                        Candidate(
                            PluginOffer(ref.manifestUrl, entry.id, entry.name, entry.description),
                            entry.fileTypes.map { it.lowercase().removePrefix(".") }.toSet(),
                            entry.fileNames.toSet(),
                        ),
                    )
                }
            }
        }
        index = stamp to built
        return built
    }

    /**
     * The plugin to offer for a file called [name], or null: none is listed
     * for it, it is installed already, or the user said "Not now" to plugins
     * for this type.
     */
    fun offerFor(name: String): PluginOffer? {
        val lower = name.lowercase()
        if (FilePins.offerDismissed(app, dismissKey(name))) return null
        val store = AddonStore.get(app)
        // An exact name first, then the longest matching extension ("tar.gz" before "gz").
        val ranked = candidates()
            .mapNotNull { c ->
                val fit = when {
                    name in c.names -> 1000
                    else -> c.types.filter { lower.endsWith(".$it") }.maxOfOrNull { it.length } ?: return@mapNotNull null
                }
                c to fit
            }
            .sortedByDescending { it.second }
        return ranked.firstOrNull { (c, _) -> store.installedFor(c.offer.manifestUrl, c.offer.addonId) == null }?.first?.offer
    }

    /** "Not now" for every offer on files like [name]. */
    fun dismiss(name: String) = FilePins.dismissOffer(app, dismissKey(name))

    /** Opens [offer]'s page in the add-on store, where its permissions show before Install. */
    fun open(offer: PluginOffer): Boolean {
        val link = "wmkeyboard://addon?repo=" + Uri.encode(offer.manifestUrl) + "&id=" + Uri.encode(offer.addonId)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.isSuccess
    }

    /**
     * Fetches the official repository's manifest when its cache is missing or
     * a week old, under the same rules the Addons screen keeps for its own
     * refresh: the store's auto-refresh switch, its unmetered-only switch, and
     * data saving. Blocking; call it off the main thread.
     */
    suspend fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        val store = AddonStore.get(app)
        val official = AddonStore.SEED_URLS.firstOrNull() ?: return
        val manifestUrl = AddonRepoCodec.resolveManifestUrl(official)
        val ref = store.repos().firstOrNull { it.manifestUrl == manifestUrl || it.url == official } ?: return
        if (ref.cachedManifest.isNotBlank() && now - ref.fetchedAt < STALE_MS) return
        if (!store.autoRefresh()) return
        val metered = runCatching { app.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered }.getOrNull() ?: false
        if (store.refreshUnmeteredOnly() && metered) return
        val dataSaver = runCatching { SettingsRepository(app).settings.first().dataSaver }.getOrNull() ?: return
        if (dataSaver.appliesTo(DeviceNetworkState(metered = metered)) && dataSaver.addonRefresh.stopsBackgroundWork) return
        runCatching { AddonDownloadManager.fetchManifest(store, ref, app.cacheDir) }
    }

    private fun dismissKey(name: String): String =
        name.substringAfterLast('.', "").lowercase().ifEmpty { "name:$name" }

    private companion object {
        const val STALE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
