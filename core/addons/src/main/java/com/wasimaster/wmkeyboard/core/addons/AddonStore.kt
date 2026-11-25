package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * A repository the user added, plus the last manifest we managed to fetch from
 * it.
 *
 * The manifest is cached verbatim rather than as parsed objects so the Addons
 * screen has something to show offline, and so a manifest written against a
 * newer format survives a round-trip through an older build unchanged.
 */
@Serializable
data class AddonRepoRef(
    /** Exactly what the user pasted; shown back to them, never fetched. */
    val url: String = "",
    /** What [AddonRepoCodec.resolveManifestUrl] made of it; the fetch target. */
    val manifestUrl: String = "",
    val addedAt: Long = 0L,
    val cachedManifest: String = "",
    val fetchedAt: Long = 0L,
    /**
     * True for the repository the app ships pre-added. Tracked so removing it
     * sticks: without this the seeding pass would helpfully add it back on the
     * next launch, which reads as the app ignoring the user.
     */
    val seeded: Boolean = false,
)

/** What an install left behind, so uninstall can undo exactly that. */
@Serializable
data class InstalledAddon(
    val version: String = "",
    val type: AddonType = AddonType.Unknown,
    /**
     * The local handle the install produced — a custom theme or layout id, a
     * pack id, a font or sound id, or a dictionary file path. Interpreted by
     * type; see `AddonInstaller`.
     */
    val localRef: String = "",
    val installedAt: Long = 0L,
    /** Kept for the Installed list so it can be rendered without the manifest. */
    val name: String = "",
    val repoName: String = "",
    /**
     * The manifest this came from, so the Installed list can open the addon's
     * own page. Blank on records written before this field existed; the UI
     * falls back to matching the repository by id.
     */
    val manifestUrl: String = "",
    /** Also kept for the offline page, which has no manifest to read it from. */
    val description: String = "",
    val author: String = "",
    /**
     * Which language of a localized addon this is (`es-419`), blank for an
     * addon with one payload. The same addon installed in two languages is
     * two records, and this is what tells them apart in the Installed list.
     */
    val locale: String = "",
)

/**
 * Added repositories and installed addons, persisted under `filesDir/addons/`:
 *
 * ```
 * addons/
 * ├── repos.json       the user's repository list, with cached manifests
 * └── installed.json   "<repoId>/<addonId>" -> what installing it created
 * ```
 *
 * Same shape as [com.wasimaster.wmkeyboard.core.icons.IconPackStore] and for
 * the same reasons: one store per process, every mutator writes immediately,
 * and [revision] is how the UI notices.
 *
 * Files rather than DataStore, unlike `custom_themes` / `custom_layouts`. A
 * cached manifest runs to several KB and `KeyboardSettings` is re-emitted to
 * the IME on every settings change, so keeping manifests in preferences would
 * push them through the keyboard's hot path on every keystroke-adjacent
 * setting read, for no benefit — nothing in the addon layer is needed while
 * typing.
 */
class AddonStore(private var baseDir: File?) {

    @Serializable
    private data class Snapshot(
        val version: Int = FORMAT_VERSION,
        val repos: List<AddonRepoRef> = emptyList(),
        // Both default to the behaviour a file written before they existed was
        // written under: fetch on every visit, over any connection.
        val autoRefresh: Boolean = true,
        val refreshUnmeteredOnly: Boolean = false,
        /**
         * An optional GitHub token, for link imports only. See [forgeToken].
         */
        val forgeToken: String = "",
    )

    /**
     * `installed.json` as it is written. Read field by field rather than as
     * this class (see [reload]): one record of a type this build does not
     * know must not cost the others.
     */
    @Serializable
    private data class InstalledSnapshot(
        val version: Int = FORMAT_VERSION,
        val installed: Map<String, JsonElement> = emptyMap(),
    )

    private val repoList = ArrayList<AddonRepoRef>()
    private var refreshOnOpen = true
    private var unmeteredRefresh = false
    private var token = ""
    private val installedMap = LinkedHashMap<String, InstalledAddon>()

    /**
     * Records this build cannot interpret, kept exactly as they were read and
     * written back on every [save].
     *
     * A record's type is an enum, and a build older than the type that wrote
     * it has no value to decode it into. Before this, the whole file failed
     * to decode, the installed list came up empty, and the next save wrote it
     * out empty: going back one version forgot every install. Coercing the
     * type to [AddonType.Unknown] would keep the record but lose its real
     * type on the next save, so it would stay unusable after upgrading again.
     * Kept verbatim instead, and left out of [installed]: nothing here can
     * uninstall, update or open one, and a newer build reads it as it was.
     */
    private val foreignRecords = LinkedHashMap<String, JsonElement>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    private val _revision = MutableStateFlow(0)

    /** Bumped on every change, so Compose re-reads the store. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    init {
        reload()
    }

    companion object {
        @Volatile
        private var shared: AddonStore? = null

        /**
         * The one store for this process. Created with no directory during
         * direct boot and re-pointed by [attach] on unlock, exactly like the
         * pack stores — before first unlock `filesDir` is not readable, and an
         * addon list is not something the lock-screen keyboard needs.
         */
        fun get(context: android.content.Context): AddonStore =
            shared ?: synchronized(this) {
                shared ?: AddonStore(addonsDir(context)).also { shared = it }
            }

        /** Re-points the shared store after a direct-boot unlock. */
        fun attach(context: android.content.Context) {
            get(context).attach(addonsDir(context))
        }

        private fun addonsDir(context: android.content.Context): File? =
            if (DirectBoot.isUserUnlocked(context)) {
                File(context.applicationContext.filesDir, DIR_NAME)
            } else {
                null
            }

        private const val FORMAT_VERSION = 1

        const val DIR_NAME = "addons"

        /** Generous, but a list this long is a mistake rather than a use case. */
        const val MAX_REPOS = 30

        /** The first repository the app shipped pre-added. Kept for the legacy marker. */
        const val SEED_URL = "https://github.com/wasi-master/wmkeyboard-addon-repository"

        /** Where people's own themes and layouts are published; see `CommunitySubmission`. */
        const val COMMUNITY_URL = "https://github.com/wasi-master/wmkeyboard-community-addons"

        /** The community repository as [SEED_URLS] offers it, for screens that link to it. */
        val COMMUNITY_SEED: String
            get() = seedUrl(ServiceRepo.COMMUNITY, COMMUNITY_URL)

        /**
         * The repositories the app ships pre-added, each removable and sticky
         * once removed.
         *
         * Order is the order they appear in on a fresh install. Appending to
         * this list is how a new default repository ships: [seedIfNeeded] tracks
         * the ones it has already offered *per URL*, so an existing install
         * picks up the addition on its next launch without the ones the user
         * already removed coming back with it.
         */
        val SEED_URLS: List<String>
            get() = listOf(
                seedUrl(ServiceRepo.ADDONS, SEED_URL),
                seedUrl(ServiceRepo.SOUNDS, "https://github.com/wasi-master/wmkeyboard-monkeytype-sounds"),
                COMMUNITY_SEED,
            )

        /**
         * A seed at its default location keeps the address it has always been
         * offered under, so an existing install does not take it for a new one.
         * Moved to a mirror (the F-Droid build's Servers screen), it is offered
         * at the mirror's manifest.
         */
        private fun seedUrl(repo: ServiceRepo, legacy: String): String {
            val location = ServiceEndpoints.repo(repo)
            return if (location == repo.default) legacy else location.rawUrl(AddonRepoCodec.MANIFEST_NAME)
        }

        private const val REPOS_FILE = "repos.json"
        private const val INSTALLED_FILE = "installed.json"
        private const val SEEDED_MARKER = ".seeded"
    }

    // ---- repositories --------------------------------------------------

    @Synchronized
    fun repos(): List<AddonRepoRef> = repoList.toList()

    /**
     * Whether opening the Addons screen re-fetches every repository manifest.
     *
     * On, because a repository that published something new should show it
     * without the user having to know to pull down. Worth being able to turn
     * off: the screen fetches every manifest on every visit, which on a metered
     * connection is somebody's data spent on a screen they opened to look at
     * what they already have.
     *
     * Stored here rather than in `KeyboardSettings` for the reason
     * `PluginStore.subsystemEnabled` gives: it belongs to this subsystem, only
     * this screen reads it, and nothing on the keyboard's hot path wants it.
     */
    @Synchronized
    fun autoRefresh(): Boolean = refreshOnOpen

    @Synchronized
    fun setAutoRefresh(enabled: Boolean) {
        if (refreshOnOpen == enabled) return
        refreshOnOpen = enabled
        save()
    }

    /** Whether [autoRefresh] waits for a connection Android does not meter. */
    @Synchronized
    fun refreshUnmeteredOnly(): Boolean = unmeteredRefresh

    @Synchronized
    fun setRefreshUnmeteredOnly(enabled: Boolean) {
        if (unmeteredRefresh == enabled) return
        unmeteredRefresh = enabled
        save()
    }

    /**
     * The user's own GitHub token, or blank, which is the normal case.
     *
     * Only ever sent to api.github.com, and only for what an address alone
     * cannot fetch: an Actions artifact, whose download endpoint refuses an
     * anonymous request even on a public repository, and a file in a private
     * repository. Without one, an artifact link goes through nightly.link
     * instead and everything else works unauthenticated.
     *
     * Kept here rather than in `KeyboardSettings` for a reason the other two
     * fields on this snapshot do not have: a settings backup is a file people
     * share, and a credential must not travel in one. Nothing under
     * `filesDir/addons/` is exported.
     */
    @Synchronized
    fun forgeToken(): String = token

    @Synchronized
    fun setForgeToken(value: String) {
        val trimmed = value.trim()
        if (token == trimmed) return
        token = trimmed
        save()
    }

    @Synchronized
    fun repo(manifestUrl: String): AddonRepoRef? =
        repoList.firstOrNull { it.manifestUrl == manifestUrl }

    /**
     * Adds a repository, or returns null when the URL doesn't resolve or the
     * list is full. Adding one that is already present is not an error — it
     * returns the existing entry, so pasting the same link twice, or following
     * a deep link to a repository already added, both just work.
     */
    @Synchronized
    fun addRepo(
        pastedUrl: String,
        now: Long = System.currentTimeMillis(),
        seeded: Boolean = false,
    ): AddonRepoRef? {
        // Before the first unlock there is nowhere to persist to. Refusing is
        // honest; accepting into memory would report success for something that
        // vanishes on the next process death.
        if (baseDir == null) return null
        val manifestUrl = AddonRepoCodec.resolveManifestUrl(pastedUrl) ?: return null
        repoList.firstOrNull { it.manifestUrl == manifestUrl }?.let { return it }
        if (repoList.size >= MAX_REPOS) return null
        val ref = AddonRepoRef(
            url = pastedUrl.trim(),
            manifestUrl = manifestUrl,
            addedAt = now,
            seeded = seeded,
        )
        repoList.add(ref)
        save()
        return ref
    }

    /** Stores a freshly fetched manifest against its repository. */
    @Synchronized
    fun cacheManifest(manifestUrl: String, text: String, now: Long = System.currentTimeMillis()) {
        val index = repoList.indexOfFirst { it.manifestUrl == manifestUrl }
        if (index < 0) return
        repoList[index] = repoList[index].copy(cachedManifest = text, fetchedAt = now)
        save()
    }

    @Synchronized
    fun removeRepo(manifestUrl: String) {
        val removed = repoList.firstOrNull { it.manifestUrl == manifestUrl } ?: return
        repoList.remove(removed)
        // Installs survive their repository being removed: the theme is still
        // the user's theme. Only the update check goes away.
        save()
    }

    /**
     * Adds each repository in [SEED_URLS] the first time it is offered, so the
     * Addons screen has something in it before the user has found a repository
     * to paste.
     *
     * The marker file records **which URLs** have been offered, not merely that
     * seeding has happened once. Both halves of that matter:
     *
     * - Per URL, so appending to [SEED_URLS] reaches installs that already
     *   seeded. A single "done" flag would mean only new installs ever saw a
     *   newly shipped default repository.
     * - By marker rather than by checking whether the repository is present, so
     *   removing one is permanent. Helpfully re-adding it on the next launch
     *   reads as the app ignoring the user.
     *
     * The marker written by builds before this held a timestamp. That is read
     * as "the original [SEED_URL] has been offered" and nothing else, which is
     * exactly what was true.
     */
    @Synchronized
    fun seedIfNeeded(now: Long = System.currentTimeMillis()) {
        val dir = baseDir ?: return
        val marker = File(dir, SEEDED_MARKER)
        val offered = if (marker.exists()) {
            val lines = runCatching { marker.readLines() }.getOrDefault(emptyList())
            val urls = lines.map { it.trim() }.filter { it.startsWith("https://") }
            urls.ifEmpty { listOf(SEED_URL) }
        } else {
            emptyList()
        }

        val fresh = SEED_URLS.filter { it !in offered }
        if (fresh.isEmpty()) return

        // Written before the adds, not after: a crash between the two would
        // otherwise re-offer a repository on every launch forever. Losing one
        // seed to a crash costs the user a paste; the other way round costs
        // them a repository they cannot get rid of.
        runCatching {
            dir.mkdirs()
            marker.writeText((offered + fresh).joinToString("\n"))
        }
        for (url in fresh) addRepo(url, now = now, seeded = true)
    }

    // ---- installed addons ----------------------------------------------

    @Synchronized
    fun installed(): Map<String, InstalledAddon> = LinkedHashMap(installedMap)

    @Synchronized
    fun installed(key: String): InstalledAddon? = installedMap[key]

    /**
     * The record for an addon identified the way a *route* identifies it — by
     * repository URL and addon id — rather than by the `"<repoId>/<addonId>"`
     * key, which the detail page can't build without the manifest.
     *
     * Falls back to matching on the addon id alone, because records written
     * before [InstalledAddon.manifestUrl] existed have no URL to match, and
     * because a repository can be removed while its installs stay.
     */
    @Synchronized
    fun installedFor(manifestUrl: String, addonId: String): Pair<String, InstalledAddon>? {
        val candidates = installedMap.entries
            .filter { it.key.substringAfterLast('/') == addonId }
        val exact = candidates.firstOrNull { it.value.manifestUrl == manifestUrl }
            ?: candidates.firstOrNull { it.value.manifestUrl.isBlank() }
            ?: return null
        return exact.key to exact.value
    }

    @Synchronized
    fun markInstalled(key: String, record: InstalledAddon) {
        // Same reason as addRepo: no directory means no record can outlive the
        // process, and a half-tracked install is worse than an untracked one.
        if (baseDir == null) return
        installedMap[key] = record
        foreignRecords.remove(key)
        save()
    }

    @Synchronized
    fun markUninstalled(key: String) {
        if (installedMap.remove(key) != null) save()
    }

    /**
     * Drops records whose local target no longer exists — the user deleted the
     * theme from the Themes screen, or the font from the font picker, without
     * going through Addons. Without this the addon would keep reporting itself
     * as installed and offer an Update for something that isn't there.
     *
     * [stillPresent] answers "does this local ref still exist?" per type; it is
     * passed in because the store has no business knowing about themes.
     */
    @Synchronized
    fun reconcileInstalled(stillPresent: (InstalledAddon) -> Boolean) {
        val gone = installedMap.filterValues { !stillPresent(it) }.keys
        if (gone.isEmpty()) return
        gone.forEach { installedMap.remove(it) }
        save()
    }

    // ---- persistence ---------------------------------------------------

    @Synchronized
    fun save() {
        val dir = baseDir
        if (dir != null) {
            writeAtomically(
                File(dir, REPOS_FILE),
                json.encodeToString(
                    Snapshot(
                        repos = repoList.toList(),
                        autoRefresh = refreshOnOpen,
                        refreshUnmeteredOnly = unmeteredRefresh,
                        forgeToken = token,
                    ),
                ),
            )
            val records = LinkedHashMap<String, JsonElement>(foreignRecords)
            installedMap.forEach { (key, record) -> records[key] = json.encodeToJsonElement(record) }
            writeAtomically(
                File(dir, INSTALLED_FILE),
                json.encodeToString(InstalledSnapshot(installed = records)),
            )
        }
        _revision.value++
    }

    private fun writeAtomically(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val part = File(file.parentFile, file.name + ".part")
            part.writeText(text)
            if (!part.renameTo(file)) {
                file.delete()
                part.renameTo(file)
            }
        }
    }

    /**
     * Fills [installedMap] from `installed.json`, one record at a time. A
     * record whose type this build does not know goes to [foreignRecords]
     * untouched, and one that does not decode at all is skipped, so neither
     * takes the rest of the list with it.
     */
    private fun readInstalled(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val records = root["installed"] as? JsonObject ?: return
        for ((key, raw) in records) {
            val record = runCatching { json.decodeFromJsonElement<InstalledAddon>(raw) }.getOrNull() ?: continue
            if (record.type == AddonType.Unknown) {
                foreignRecords[key] = raw
            } else {
                installedMap[key] = record
            }
        }
    }

    /** Points the store at [dir] and re-reads it. The direct-boot unlock path. */
    @Synchronized
    fun attach(dir: File?) {
        if (dir?.absolutePath == baseDir?.absolutePath) return
        baseDir = dir
        reload()
    }

    @Synchronized
    fun reload() {
        repoList.clear()
        refreshOnOpen = true
        unmeteredRefresh = false
        token = ""
        installedMap.clear()
        foreignRecords.clear()
        val dir = baseDir ?: run { _revision.value++; return }

        val reposFile = File(dir, REPOS_FILE)
        if (reposFile.exists()) {
            runCatching {
                val snapshot = json.decodeFromString<Snapshot>(reposFile.readText())
                repoList.addAll(snapshot.repos.take(MAX_REPOS))
                refreshOnOpen = snapshot.autoRefresh
                unmeteredRefresh = snapshot.refreshUnmeteredOnly
                token = snapshot.forgeToken
            }
        }

        val installedFile = File(dir, INSTALLED_FILE)
        if (installedFile.exists()) {
            runCatching { readInstalled(installedFile.readText()) }
        }

        _revision.value++
    }
}
