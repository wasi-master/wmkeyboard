package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.integrity.IntegrityChecks
import android.content.Context
import android.os.StatFs
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.addons.feature.R
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.share.RemixCredit
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetching repository manifests and installing addons from them.
 *
 * Process singleton with its own IO scope and a [StateFlow] of per-addon
 * status, the same shape as `WordlistDownloadManager` and
 * `LocalLlmDownloadManager` — the settings UI collects [states] so a download
 * survives rotation and navigating away, and only one install runs at a time.
 *
 * The transfer borrows Range/`.part` resume from the model downloader, because
 * a sticker pack can be tens of megabytes on a phone connection. Verification
 * is a separate pass over the finished `.part` rather than a digest fed during
 * transfer: resuming would otherwise have to re-hash the existing prefix
 * anyway, and one extra sequential read of a file this size costs nothing next
 * to the download that produced it.
 */
object AddonDownloadManager {

    sealed interface AddonStatus {
        data object NotInstalled : AddonStatus

        /** [totalBytes] is 0 when neither the manifest nor the server said. */
        data class Downloading(val bytes: Long, val totalBytes: Long) : AddonStatus

        /** Transfer done; hashing before anything touches the importer. */
        data object Verifying : AddonStatus

        /** Handing the payload to the importer that owns this type. */
        data object Installing : AddonStatus

        data class Installed(val version: String) : AddonStatus

        data class UpdateAvailable(val installed: String, val latest: String) : AddonStatus

        /** [text] is the line to show the user. Resolve it where it is drawn. */
        data class Failed(val reason: FailReason, val text: AddonText) : AddonStatus
    }

    enum class FailReason {
        /** Offline, DNS, timeout, or a non-2xx response. */
        NETWORK,

        /** Not enough free space for the payload. */
        NO_SPACE,

        /** The payload's SHA-256 did not match the one the manifest declared. */
        CHECKSUM,

        /** The importer refused it: wrong format, unsupported language, empty. */
        REJECTED,

        /** This build predates the addon's `minAppVersion`. */
        APP_TOO_OLD,

        OTHER,
    }

    private const val PROGRESS_INTERVAL_MS = 250L
    private const val BUFFER_BYTES = 256 * 1024

    /** Headroom over the payload size, so an install never fills the disk. */
    private const val SPACE_MARGIN_BYTES = 32L * 1024 * 1024

    /**
     * Ceiling on a *preview* download, under every type's install cap.
     *
     * A preview is a courtesy — pulling 60 MB of sticker pack to show a grid
     * the user may glance at and leave isn't one. Past this the detail page
     * says so and offers Install, which has the real cap.
     */
    private const val PREVIEW_MAX_BYTES = 12L * 1024 * 1024

    /**
     * A Clink theme catalogue carries every theme whole, about 3.5 KB each, so
     * the official one is already 600 KB; this leaves a fork room to grow.
     */
    private const val CLINK_MANIFEST_MAX_BYTES = 6L * 1024 * 1024

    /** A licence file longer than this is not a licence file. */
    private const val MAX_TEXT_BYTES = 256L * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private var activeKey: String? = null

    private val _states = MutableStateFlow<Map<String, AddonStatus>>(emptyMap())

    /** `"<repoId>/<addonId>"` -> status, for every addon the UI has looked at. */
    val states: StateFlow<Map<String, AddonStatus>> = _states.asStateFlow()

    /**
     * A freshly installed addon that has a slot it could fill, waiting on the
     * user's answer. See [AddonApply] for why installing no longer fills it.
     */
    data class PendingApply(
        val key: String,
        val record: InstalledAddon,
        /** The question to ask. The dialog resolves it. */
        @get:StringRes val questionRes: Int,
    )

    private val _pendingApply = MutableStateFlow<PendingApply?>(null)

    /**
     * Held here rather than in the screen that started the install, because the
     * install outlives the screen: tapping the download arrow on a catalogue
     * card and opening the addon's page while it transfers must not lose the
     * question. Whichever addon screen is on top when it lands asks it.
     */
    val pendingApply: StateFlow<PendingApply?> = _pendingApply.asStateFlow()

    /** Answers [pendingApply] with "no", or clears one already acted on. */
    fun clearPendingApply() {
        _pendingApply.value = null
    }

    /**
     * Answers [pendingApply] with "yes". Runs on this object's scope rather
     * than the caller's, because the dialog asking the question is dismissed by
     * the same tap and would take a UI-scoped write down with it.
     */
    fun applyPending(context: Context) {
        val pending = _pendingApply.value ?: return
        _pendingApply.value = null
        val appContext = context.applicationContext
        scope.launch { AddonApply.apply(appContext, pending.record) }
    }

    val isBusy: Boolean get() = activeJob?.isActive == true

    // ---- manifests -----------------------------------------------------

    /**
     * Fetches a repository's manifest and caches it, returning the parsed form.
     *
     * Blocking; call on IO. A failure returns null and leaves the previous
     * cached manifest in place — a repository that is briefly unreachable
     * should keep showing what it had, not empty itself out.
     */
    fun fetchManifest(store: AddonStore, ref: AddonRepoRef, cacheDir: File): AddonRepoManifest? =
        fetchManifest(ref.manifestUrl, cacheDir, store)

    /**
     * Fetches a manifest by URL, optionally caching it against an already-added
     * repository.
     *
     * The store-less form is what a deep link uses: following a link has to be
     * able to *show* an addon without the link having quietly added its
     * repository to the user's list. Adding it is the user's decision, taken
     * when they install.
     */
    fun fetchManifest(
        manifestUrl: String,
        cacheDir: File,
        store: AddonStore? = null,
    ): AddonRepoManifest? {
        val text = downloadSmallText(manifestUrl, cacheDir, AddonRepoCodec.MAX_MANIFEST_BYTES)
        val manifest = text?.let(AddonRepoCodec::decode)
        if (text != null && manifest != null) {
            store?.cacheManifest(manifestUrl, text)
            return manifest
        }
        // A GitHub repository with no manifest of ours may be a Clink one. It
        // is cached already converted, so the cache reads like any other.
        val clink = fetchClinkManifest(manifestUrl, cacheDir) ?: return null
        store?.cacheManifest(manifestUrl, clink.text)
        return clink.manifest
    }

    private class Fetched(val manifest: AddonRepoManifest, val text: String)

    /**
     * The Clink catalogue of the GitHub repository [manifestUrl] points into,
     * converted, or null when there is none. The theme gallery's preview index
     * is read too when the repository holds themes, so the cards have pictures.
     */
    private fun fetchClinkManifest(manifestUrl: String, cacheDir: File): Fetched? {
        val location = ClinkRepo.locate(manifestUrl) ?: return null
        val text = downloadSmallText(location.releaseManifestUrl, cacheDir, CLINK_MANIFEST_MAX_BYTES) ?: return null
        if (!ClinkRepo.looksLikeManifest(text)) return null
        val previews = if (text.contains("\"themes\"")) {
            ClinkRepo.previewIds(downloadSmallText(location.previewIndexUrl, cacheDir, AddonRepoCodec.MAX_MANIFEST_BYTES))
        } else {
            emptySet()
        }
        val converted = ClinkRepo.convert(text, location, previews) ?: return null
        val encoded = ClinkRepo.encode(converted)
        // Read back through the decoder, so a Clink entry passes the same
        // filters every other repository's entries do.
        val manifest = AddonRepoCodec.decode(encoded) ?: return null
        return Fetched(manifest, encoded)
    }

    /** A small text file, or null on any failure. Blocking. */
    private fun downloadSmallText(url: String, cacheDir: File, maxBytes: Long): String? {
        val temp = File(cacheDir, "manifest_${System.nanoTime()}.json")
        return try {
            ToolHttp.download(
                url = url,
                target = temp,
                maxBytes = maxBytes,
                source = NetSource.ADDONS,
                route = NetLog.pathOf(url),
            )
            temp.readText()
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    /** The cached manifest for [ref], or null if it has never been fetched. */
    fun cachedManifest(ref: AddonRepoRef): AddonRepoManifest? =
        ref.cachedManifest.takeIf { it.isNotBlank() }?.let { AddonRepoCodec.decode(it) }

    // ---- fetching without installing -------------------------------------

    /**
     * Downloads an addon's payload into [cacheDir] without installing it, for
     * the preview panel. Blocking; call on IO.
     *
     * Deliberately separate from [install]: it touches neither [states] nor the
     * single-install lock, so looking at what is in a snippet pack never
     * interferes with a download in flight, and never leaves an addon looking
     * half-installed if the user backs out.
     */
    fun fetchPayload(manifestUrl: String, entry: AddonEntry, cacheDir: File): File? {
        val url = AddonRepoCodec.resolveAsset(manifestUrl, entry.path) ?: return null
        val target = File(previewDir(cacheDir), sanitise("${entry.type.name}_${entry.id}"))
        // A preview the user already opened this session is on disk; refetching
        // tens of megabytes of sticker pack to show the same grid twice is
        // exactly the sort of thing a metered connection notices.
        if (target.isFile && target.length() > 0) return target
        return try {
            ToolHttp.download(
                url, target, maxBytes = minOf(entry.type.maxBytes, PREVIEW_MAX_BYTES),
                source = NetSource.ADDONS, route = NetLog.pathOf(url),
            )
            target.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            target.delete()
            null
        }
    }

    /** Fetches a plain-text asset (a licence file) for display. */
    fun fetchText(manifestUrl: String, path: String, cacheDir: File): String? {
        val url = AddonRepoCodec.resolveAsset(manifestUrl, path) ?: return null
        val temp = File(cacheDir, "addon_text_${System.nanoTime()}.txt")
        return try {
            ToolHttp.download(url, temp, maxBytes = MAX_TEXT_BYTES, source = NetSource.ADDONS, route = NetLog.pathOf(url))
            temp.readText().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    // ---- status --------------------------------------------------------

    /**
     * Recomputes the status of every addon in [manifest] against what the store
     * says is installed. Call when the Addons screen appears or after a
     * refresh; an install in flight keeps its live status.
     */
    fun refresh(store: AddonStore, repoId: String, manifest: AddonRepoManifest) {
        val installed = store.installed()
        _states.update { current ->
            // Every language of a localized addon is tracked on its own key.
            current + manifest.addons.flatMap { it.variants }.associate { entry ->
                val key = entry.key(repoId)
                key to when {
                    key == activeKey && isBusy -> current[key] ?: AddonStatus.NotInstalled
                    else -> statusFor(installed[key], entry)
                }
            }
        }
    }

    private fun statusFor(record: InstalledAddon?, entry: AddonEntry): AddonStatus = when {
        record == null -> AddonStatus.NotInstalled
        Semver.isNewer(entry.version, record.version) ->
            AddonStatus.UpdateAvailable(record.version, entry.version)
        else -> AddonStatus.Installed(record.version)
    }

    // ---- install -------------------------------------------------------

    /**
     * Downloads [entry], verifies it if the manifest said what to expect, and
     * hands it to its importer. Re-running this on an installed addon is how an
     * update is applied.
     */
    fun install(
        context: Context,
        store: AddonStore,
        manifestUrl: String,
        repo: AddonRepoInfo,
        entry: AddonEntry,
        appVersionCode: Int,
    ) {
        if (isBusy) return
        activeJob = scope.launch {
            performInstall(context, store, manifestUrl, repo, entry, appVersionCode)
        }
    }

    /**
     * Installs [entry] together with the [dependencies] its [AddonEntry.requires]
     * named, one after another under the single-install lock. A failed one
     * (offline mid-batch, a rejected payload) moves on to the next: the
     * dependencies are soft, and [entry] still installs and falls back.
     * Cancelling stops the whole batch.
     *
     * Only [entry] gets to ask its "use it?" question. The user tapped Install on
     * one thing; the sound and the font came along because that thing said it was
     * designed with them, and asking "switch your key sound to Blip?" on the way
     * to installing a theme turns one decision into three — the last of which
     * arrives after they have already answered the same question about the theme
     * that wanted it. Answering yes to the theme is what puts the theme's sound
     * and fonts on the board, through the theme's own spec.
     */
    fun installAll(
        context: Context,
        store: AddonStore,
        manifestUrl: String,
        repo: AddonRepoInfo,
        dependencies: List<AddonEntry>,
        entry: AddonEntry,
        appVersionCode: Int,
    ) {
        if (isBusy) return
        activeJob = scope.launch {
            dependencies.forEach { dependency ->
                performInstall(
                    context, store, manifestUrl, repo, dependency, appVersionCode,
                    askToApply = false,
                )
            }
            performInstall(context, store, manifestUrl, repo, entry, appVersionCode)
        }
    }

    private suspend fun performInstall(
        context: Context,
        store: AddonStore,
        manifestUrl: String,
        repo: AddonRepoInfo,
        entry: AddonEntry,
        appVersionCode: Int,
        /**
         * Whether a fresh install of a type with a slot may raise its
         * [pendingApply] question. False for the dependencies of another addon;
         * see [installAll].
         */
        askToApply: Boolean = true,
    ) {
        val key = entry.key(repo.id)

        val minAppVersion = entry.minAppVersion
        if (minAppVersion != null && minAppVersion > appVersionCode) {
            set(
                key,
                AddonStatus.Failed(
                    FailReason.APP_TOO_OLD,
                    AddonText.of(R.string.faddons_download_error_app_too_old),
                ),
            )
            return
        }
        // A missing checksum is tolerated everywhere else — the file arrived over
        // https, and refusing a theme for want of a hash would block half the
        // repositories anyone publishes. Code is different: "unverified" is not
        // a state a plugin gets to be in, so for executable payloads the digest
        // is mandatory rather than a badge.
        if (entry.type.isExecutable && entry.sha256.isNullOrBlank()) {
            set(
                key,
                AddonStatus.Failed(
                    FailReason.REJECTED,
                    AddonText.of(R.string.faddons_download_error_plugin_no_checksum),
                ),
            )
            return
        }
        val url = AddonRepoCodec.resolveAsset(manifestUrl, entry.path)
        if (url == null) {
            set(
                key,
                AddonStatus.Failed(
                    FailReason.REJECTED,
                    AddonText.of(R.string.faddons_download_error_bad_url),
                ),
            )
            return
        }

        activeKey = key
        set(key, AddonStatus.Downloading(0, entry.sizeBytes ?: 0L))

        val appContext = context.applicationContext
        run {
            val part = File(stagingDir(appContext), "${sanitise(key)}.part")
            try {
                requireSpace(appContext, entry)
                transfer(url, part, entry, key)

                currentCoroutineContext().ensureActive()
                set(key, AddonStatus.Verifying)
                val current = verifyOrRefresh(appContext, store, manifestUrl, part, entry)

                currentCoroutineContext().ensureActive()
                set(key, AddonStatus.Installing)

                // Updating means replacing, not accumulating. Every importer
                // mints a fresh local id, so without this an update would leave
                // the previous version behind as a second theme, a second pack,
                // a second copy of the dictionary. Removed only once the new
                // payload is downloaded and verified, so a failed update leaves
                // the working version in place.
                val previous = store.installed(key)
                // Asked before the old copy is removed: every importer mints a
                // fresh local id, so after the swap there is nothing left to
                // compare the user's active theme (or sound, or layout) against.
                val wasActive = previous != null && AddonApply.isActive(appContext, previous)
                if (previous != null) {
                    AddonInstaller.uninstall(appContext, previous)
                }

                val outcome = AddonInstaller.install(
                    appContext,
                    current,
                    part,
                    RemixCredit(key, current.name, current.author),
                )

                when (outcome) {
                    is AddonInstaller.Outcome.Installed -> {
                        val record = InstalledAddon(
                            version = current.version,
                            type = current.type,
                            localRef = outcome.localRef,
                            installedAt = System.currentTimeMillis(),
                            name = current.name,
                            repoName = repo.name,
                            manifestUrl = manifestUrl,
                            description = current.description,
                            author = current.author,
                            locale = current.id.substringAfter(AddonLocalization.SEPARATOR, ""),
                        )
                        store.markInstalled(key, record)
                        val questionRes = AddonApply.questionRes(current.type)
                        when {
                            // An update of the thing the user was already using
                            // re-selects itself under its new id. Asking here
                            // would be asking them to re-confirm a choice they
                            // never revoked — and saying no would look like the
                            // update had uninstalled their theme.
                            wasActive -> AddonApply.apply(appContext, record)
                            askToApply && questionRes != null && previous == null ->
                                _pendingApply.value = PendingApply(key, record, questionRes)
                        }
                        set(key, AddonStatus.Installed(current.version))
                    }
                    is AddonInstaller.Outcome.Rejected -> {
                        // The old version was already removed above, so the
                        // record has to go too — claiming it is still installed
                        // would offer an Update for something that is gone.
                        if (previous != null) store.markUninstalled(key)
                        set(key, AddonStatus.Failed(FailReason.REJECTED, outcome.text))
                    }
                }
            } catch (e: CancellationException) {
                part.delete()
                set(key, statusFor(store.installed(key), entry))
                throw e
            } catch (e: FailedException) {
                part.delete()
                set(key, AddonStatus.Failed(e.reason, e.text))
            } catch (e: Exception) {
                part.delete()
                // friendlyMessage needs a Context and hands back finished words,
                // so this one line is already in the user's language here.
                set(
                    key,
                    AddonStatus.Failed(
                        FailReason.NETWORK,
                        AddonText.Literal(ToolHttp.friendlyMessage(appContext, e)),
                    ),
                )
            } finally {
                part.delete()
                activeKey = null
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
    }

    /**
     * Reverses an install and forgets it. The addon goes back to being offered
     * in the list, which is the point: uninstall should leave no trace beyond
     * what the user themselves added afterwards.
     */
    suspend fun uninstall(context: Context, store: AddonStore, key: String, entry: AddonEntry?) {
        val record = store.installed(key) ?: return
        withContext(Dispatchers.IO) {
            AddonInstaller.uninstall(context.applicationContext, record)
        }
        store.markUninstalled(key)
        // "Switch to it?" about something that is no longer installed.
        if (_pendingApply.value?.key == key) clearPendingApply()
        set(key, entry?.let { statusFor(null, it) } ?: AddonStatus.NotInstalled)
    }

    // ---- transfer ------------------------------------------------------

    private suspend fun transfer(url: String, part: File, entry: AddonEntry, key: String) {
        part.parentFile?.mkdirs()
        val resumeFrom = if (part.exists()) part.length() else 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        val netCall = NetLog.call(NetSource.ADDONS, "GET", url, route = NetLog.pathOf(url))
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 20_000
            connection.instanceFollowRedirects = true
            if (resumeFrom > 0) connection.setRequestProperty("Range", "bytes=$resumeFrom-")

            netCall.status = connection.responseCode
            val status = connection.responseCode
            // A server that ignored the Range header sends 200 with the whole
            // file, so the partial we have is worthless and we start over.
            val appending = status == HttpURLConnection.HTTP_PARTIAL
            if (status != HttpURLConnection.HTTP_OK && !appending) {
                throw FailedException(
                    FailReason.NETWORK,
                    AddonText.of(R.string.faddons_download_error_http_status, status),
                )
            }
            if (!appending && resumeFrom > 0) part.delete()

            val already = if (appending) resumeFrom else 0L
            val reported = connection.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = when {
                reported > 0 -> already + reported
                else -> entry.sizeBytes ?: 0L
            }
            val cap = entry.type.maxBytes

            netCall.countIn(connection.inputStream).use { input ->
                RandomAccessFile(part, "rw").use { output ->
                    output.setLength(already)
                    output.seek(already)
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = already
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (cap > 0 && written > cap) {
                            throw FailedException(
                                FailReason.REJECTED,
                                AddonText.of(
                                    R.string.faddons_download_error_too_large,
                                    cap / (1024 * 1024),
                                ),
                            )
                        }
                        output.write(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            set(key, AddonStatus.Downloading(written, total))
                        }
                    }
                    set(key, AddonStatus.Downloading(written, maxOf(total, written)))
                }
            }
        } catch (t: Throwable) {
            netCall.fail(t)
            throw t
        } finally {
            connection.disconnect()
            netCall.end()
        }
    }

    /**
     * [verify], retried once against a freshly fetched manifest.
     *
     * A repository republishes an addon at the same path, so a manifest cached
     * before the update still lists the old `sha256` while the file is already
     * the new one. Until that cache is refreshed (the screen opened from it, or
     * the refresh failed offline) every install reads as a checksum failure.
     * When the live manifest names a different digest for the same addon, the
     * payload is checked against that instead, and the install proceeds as the
     * version the digest belongs to. A file matching neither is still refused.
     */
    private fun verifyOrRefresh(
        context: Context,
        store: AddonStore,
        manifestUrl: String,
        part: File,
        entry: AddonEntry,
    ): AddonEntry {
        try {
            verify(part, entry)
            return entry
        } catch (e: FailedException) {
            if (e.reason != FailReason.CHECKSUM) throw e
            val fresh = fetchManifest(manifestUrl, context.cacheDir, store)
                ?.addons
                ?.flatMap { it.variants }
                ?.firstOrNull { it.id == entry.id && it.type == entry.type }
                ?.takeIf { !it.sha256.isNullOrBlank() && !it.sha256.equals(entry.sha256, ignoreCase = true) }
                ?: throw e
            verify(part, fresh)
            return fresh
        }
    }

    /**
     * Checks the payload against the manifest's `sha256`, when there is one.
     *
     * There usually will be, but the field is optional by design: a publisher
     * writing their first manifest by hand shouldn't have to run a hashing tool
     * before anyone can install their theme. An unverified addon installs
     * normally; the UI is what says so.
     */
    private fun verify(part: File, entry: AddonEntry) {
        val expected = entry.sha256?.lowercase() ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!IntegrityChecks.accept(actual == expected, entry.id)) {
            throw FailedException(
                FailReason.CHECKSUM,
                AddonText.of(R.string.faddons_download_error_checksum),
            )
        }
    }

    private fun requireSpace(context: Context, entry: AddonEntry) {
        val needed = (entry.sizeBytes ?: 0L) + SPACE_MARGIN_BYTES
        val available = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (available < needed) {
            throw FailedException(
                FailReason.NO_SPACE,
                AddonText.of(R.string.faddons_download_error_no_space),
            )
        }
    }

    private fun stagingDir(context: Context): File =
        File(context.cacheDir, "addon_downloads").apply { mkdirs() }

    private fun previewDir(cacheDir: File): File =
        File(cacheDir, "addon_previews").apply { mkdirs() }

    /** Install keys contain a `/`; file names can't. */
    private fun sanitise(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun set(key: String, status: AddonStatus) {
        _states.update { it + (key to status) }
    }

    /**
     * Carries the reason out of the transfer without putting it into words.
     * [IOException] keeps no message of its own on purpose: [text] is the line
     * the user reads, and it is resolved by the screen that draws it.
     */
    private class FailedException(val reason: FailReason, val text: AddonText) : IOException()
}
