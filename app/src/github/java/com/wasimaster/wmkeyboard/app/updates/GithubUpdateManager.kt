package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.wasimaster.wmkeyboard.core.download.FileDownloader
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The GitHub update, as one thing that outlives the screen showing it.
 *
 * A process singleton rather than something remembered in composition, for the
 * same reason the download managers elsewhere in this app are: a download has
 * to survive a rotation, a walk to another settings screen, and the trip out
 * to the system settings switch and back. The composable driver
 * ([GithubAppUpdater]) is a thin reader of this.
 *
 * Everything that could be decided without Android is decided elsewhere, in
 * [GithubUpdateChecker], [ReleaseAssets] and [installOutcome]. What is left
 * here is sequencing and files.
 */
internal object GithubUpdateManager {

    private const val TAG = "AppUpdates"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** See [AppUpdater.switchingToAllLanguages]. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching.asStateFlow()

    private val _releaseNotes = MutableStateFlow<String?>(null)
    val releaseNotes: StateFlow<String?> = _releaseNotes.asStateFlow()

    /**
     * The screen Android wants shown before it installs, waiting for an
     * activity to show it. Cleared as soon as one does.
     */
    private val _pendingUserAction = MutableStateFlow<Intent?>(null)
    val pendingUserAction: StateFlow<Intent?> = _pendingUserAction.asStateFlow()

    private var candidate: UpdateCandidate? = null
    private var running: Job? = null
    private var notesFor: Int = 0
    private var started = false

    /**
     * Whether the offer has already been put in front of the user as a dialog
     * in this process.
     *
     * Kept here rather than in the composable driver because a check is
     * asynchronous: by the time the driver could see that a dialog had opened,
     * the resume that opened it is long over, and the next resume would open
     * it again. Which makes this the only place that knows.
     */
    private var prompted = false

    /**
     * First look at the world, once per process.
     *
     * Reconciles three things that can disagree after a process death: what
     * the preferences last recorded, what is actually on disk, and what
     * install sessions are still open.
     */
    fun init(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        // The shade follows the state for as long as this process lives. Here
        // rather than in the composable driver because a download outlives the
        // screen that started it, which is the whole reason to notify at all.
        scope.launch { _state.collect { UpdateNotifications.render(app, it) } }
        scope.launch {
            ApkStaging.sweep(app, BuildConfig.RELEASE_VERSION_CODE, BuildConfig.FLAVOR)
            ApkInstall.abandonStale(app, keepSessionId = prefs.sessionId)
            val known = GithubUpdateChecker.decodeCandidate(prefs.knownCandidate)
            // A move to every language is still on only while this is the
            // English-only build and the offer really is the other one. Once
            // the new build runs, the same record reads as finished.
            val switchingNow = prefs.languageSwitch &&
                BuildConfig.FLAVOR == ReleaseAssets.LANGUAGES_EN &&
                known != null && isAllLanguages(known)
            val stale = known == null ||
                known.versionCode < BuildConfig.RELEASE_VERSION_CODE ||
                (known.versionCode == BuildConfig.RELEASE_VERSION_CODE && !switchingNow)
            if (stale) {
                prefs.clearCandidate()
                return@launch
            }
            if (!switchingNow) prefs.languageSwitch = false
            _switching.value = switchingNow
            candidate = known
            // Answering from the cache costs no request, which matters when
            // sixty an hour are shared with every device behind this address.
            _state.value = offerOf(app, prefs, known, userAsked = false, allowPrompt = false)
        }
    }

    fun check(context: Context, userAsked: Boolean, allowPrompt: Boolean) {
        if (running?.isActive == true) return
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        if (_switching.value) {
            // The move to every language owns the updater until it lands or is
            // dropped. A failed one gives way to the ordinary check, so a user
            // who walked away from it is not left without updates.
            if (userAsked || _state.value !is UpdateState.Failed) return
            candidate?.takeIf(::isAllLanguages)?.let { target ->
                FileDownloader.discard(ApkStaging.partFile(app, target))
                ApkStaging.apkFile(app, target).delete()
            }
            candidate = null
            prefs.clearCandidate()
            _switching.value = false
        }
        // The user's switch first, then the interval: with automatic checks
        // off, a resume must not reach the network at all (#507).
        if (!userAsked && !prefs.autoCheck) return
        if (!userAsked &&
            !UpdateCheckGate.shouldAutoCheck(
                now = System.currentTimeMillis(),
                lastCheckAt = prefs.lastCheckAt,
                rateLimitedUntil = prefs.rateLimitedUntil,
                state = _state.value,
            )
        ) {
            return
        }
        if (userAsked) _state.value = UpdateState.Checking
        running = scope.launch { runCheck(app, prefs, userAsked, allowPrompt) }
    }

    private fun runCheck(
        context: Context,
        prefs: UpdatePrefs,
        userAsked: Boolean,
        allowPrompt: Boolean,
    ) {
        val outcome = newChecker(context).check(
            installedVersionCode = BuildConfig.RELEASE_VERSION_CODE,
            // The edition token every published name still carries, and the
            // languages flavour on its own (see ReleaseAssets.STANDARD_FLAVOR).
            flavor = ReleaseAssets.STANDARD_FLAVOR,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            includePrereleases = prefs.includePrereleases,
            storedEtag = prefs.etag,
            onEtag = { prefs.etag = it },
            languages = BuildConfig.FLAVOR,
            // Each track updates only to its own APK. BOOX never turns an
            // update into a silent loss of raw ink, and no release lacking a
            // BOOX APK makes the updater offer a standard build instead.
            onyxPen = BuildConfig.ENABLE_ONYX_PEN,
        )
        prefs.lastCheckAt = System.currentTimeMillis()
        when (outcome) {
            is CheckOutcome.Available -> {
                candidate = outcome.candidate
                prefs.knownCandidate = GithubUpdateChecker.encode(outcome.candidate)
                _state.value = offerOf(context, prefs, outcome.candidate, userAsked, allowPrompt)
            }
            CheckOutcome.UpToDate -> {
                candidate = null
                prefs.clearCandidate()
                _state.value = UpdateState.UpToDate(userAsked)
            }
            is CheckOutcome.RateLimited -> {
                prefs.rateLimitedUntil = outcome.untilMillis
                DebugLog.d(TAG, "GitHub is rate limiting, waiting")
                if (userAsked) {
                    _state.value = UpdateState.Failed(false, UpdateFailure.RATE_LIMITED)
                }
            }
            CheckOutcome.Failed ->
                if (userAsked) _state.value = UpdateState.Failed(false, UpdateFailure.NETWORK)
        }
    }

    private fun newChecker(context: Context) = GithubUpdateChecker(
        fetcher = GithubReleaseSource(),
        cache = ReleaseFileCache(ApkStaging.listCache(context)),
    )

    /** Whether [target] is a build that carries every interface language. */
    private fun isAllLanguages(target: UpdateCandidate): Boolean =
        ReleaseAssets.parseAssetName(target.assetName)?.languages == ReleaseAssets.LANGUAGES_INTL

    /**
     * Starts the move from this English-only build to the one with every
     * language (#322): finds that build of the newest release this install may
     * have, and offers it. Anything the updater was doing gives way, since the
     * every-language build of the newest release is also the newest update.
     * An install already running is the one thing that does not.
     */
    fun switchToAllLanguages(context: Context) {
        if (_state.value == UpdateState.Installing) return
        if (_switching.value && running?.isActive == true) return
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        val previous = running
        _switching.value = true
        _state.value = UpdateState.Checking
        running = scope.launch {
            // A download cancelled here puts its own offer back on the way
            // out, so wait for it before saying what this is doing instead.
            previous?.cancelAndJoin()
            _state.value = UpdateState.Checking
            runSwitchCheck(app, prefs)
        }
    }

    private fun runSwitchCheck(context: Context, prefs: UpdatePrefs) {
        val outcome = newChecker(context).check(
            installedVersionCode = BuildConfig.RELEASE_VERSION_CODE,
            flavor = ReleaseAssets.STANDARD_FLAVOR,
            supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
            includePrereleases = prefs.includePrereleases,
            storedEtag = prefs.etag,
            onEtag = { prefs.etag = it },
            languages = ReleaseAssets.LANGUAGES_INTL,
            // The same release in its other build is the usual answer.
            allowSameVersion = true,
        )
        prefs.lastCheckAt = System.currentTimeMillis()
        _state.value = when (outcome) {
            is CheckOutcome.Available -> {
                candidate = outcome.candidate
                // Both, and in this order: a record with the flag and no
                // matching candidate would read as a switch to the wrong file.
                prefs.knownCandidate = GithubUpdateChecker.encode(outcome.candidate)
                prefs.languageSwitch = true
                offerOf(context, prefs, outcome.candidate, userAsked = true, allowPrompt = false)
            }
            CheckOutcome.UpToDate -> UpdateState.Failed(false, UpdateFailure.NO_ASSET)
            is CheckOutcome.RateLimited -> {
                prefs.rateLimitedUntil = outcome.untilMillis
                UpdateState.Failed(false, UpdateFailure.RATE_LIMITED)
            }
            CheckOutcome.Failed -> UpdateState.Failed(false, UpdateFailure.NETWORK)
        }
    }

    /** Drops the move to every language, and anything it downloaded. */
    fun abandonLanguageSwitch(context: Context) {
        if (!_switching.value || _state.value == UpdateState.Installing) return
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        val previous = running
        running = scope.launch {
            previous?.cancelAndJoin()
            candidate?.takeIf(::isAllLanguages)?.let { target ->
                FileDownloader.discard(ApkStaging.partFile(app, target))
                ApkStaging.apkFile(app, target).delete()
            }
            candidate = null
            prefs.clearCandidate()
            _switching.value = false
            _state.value = UpdateState.Idle
        }
    }

    /** Begins, or resumes, the download the user just agreed to. */
    fun start(context: Context) {
        if (running?.isActive == true) return
        val app = context.applicationContext
        val target = candidate ?: return
        running = scope.launch { runDownload(app, target) }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runDownload(context: Context, target: UpdateCandidate) {
        val part = ApkStaging.partFile(context, target)
        val apk = ApkStaging.apkFile(context, target)
        try {
            ApkDownload.requireSpace(
                dir = ApkStaging.dir(context),
                sizeBytes = target.sizeBytes,
                alreadyHave = FileDownloader.bytesDone(part),
            )
            _state.value = UpdateState.Downloading(
                bytesDownloaded = FileDownloader.bytesDone(part),
                totalBytes = target.sizeBytes,
            )
            ApkDownload.transfer(target, part) { written, total ->
                _state.value = UpdateState.Downloading(written, total)
            }
            val expected = target.sha256 ?: fetchChecksum(target)
            if (expected == null) {
                // Refusing rather than installing on trust. An update is the
                // one download in this app that replaces the app itself, so
                // "we could not check it" has to mean "we do not install it".
                DebugLog.w(TAG, "release published no checksum for ${target.assetName}")
                FileDownloader.discard(part)
                _state.value = UpdateState.Failed(false, UpdateFailure.CORRUPT)
                return
            }
            if (!ApkDownload.verify(part, target, expected)) {
                FileDownloader.discard(part)
                _state.value = UpdateState.Failed(false, UpdateFailure.CORRUPT)
                return
            }
            apk.delete()
            if (!part.renameTo(apk)) {
                _state.value = UpdateState.Failed(false, UpdateFailure.NO_SPACE)
                return
            }
            stageDexMetadata(context, target)
            _state.value = UpdateState.Downloaded
        } catch (cancelled: CancellationException) {
            // The part file stays: cancelling is "not now", and the next press
            // should pick up where this left off rather than start again.
            backToAvailable(context, target)
            throw cancelled
        } catch (failure: DownloadFailure) {
            if (!failure.keepPartial) FileDownloader.discard(part)
            _state.value = UpdateState.Failed(false, failure.reason)
        } catch (error: Exception) {
            DebugLog.w(TAG, "update download failed: ${error.message.orEmpty()}")
            _state.value = UpdateState.Failed(false, UpdateFailure.NETWORK)
        }
    }

    /**
     * Fetches the dex metadata that goes with [target], best effort.
     *
     * A few tens of kilobytes, and nothing depends on it: without it the
     * update installs exactly as before and Android compiles the keyboard
     * later, on its own schedule. So any failure (an older release that never
     * published one, a network blip, a checksum that does not match) leaves
     * nothing staged and says nothing. The checksum is still required: the
     * file goes into the same install session as the APK.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun stageDexMetadata(context: Context, target: UpdateCandidate) {
        val file = ApkStaging.dexMetadataFile(context, target) ?: return
        val part = File(file.path + ".part")
        val asset = target.copy(
            assetName = file.name,
            url = target.url.substringBeforeLast('/') + "/" + file.name,
            sizeBytes = 0L,
            sha256 = null,
        )
        try {
            FileDownloader.discard(part)
            ApkDownload.transfer(asset, part) { _, _ -> }
            val expected = fetchChecksum(asset)
            if (expected == null || !ApkDownload.verify(part, asset, expected)) {
                FileDownloader.discard(part)
                return
            }
            file.delete()
            if (!part.renameTo(file)) FileDownloader.discard(part)
        } catch (cancelled: CancellationException) {
            FileDownloader.discard(part)
            throw cancelled
        } catch (error: Exception) {
            FileDownloader.discard(part)
            DebugLog.d(TAG, "no dex metadata for ${target.assetName}: ${error.message.orEmpty()}")
        }
    }

    fun cancel() {
        running?.cancel()
    }

    /**
     * Records the press, and commits when Android already lets this app
     * install. When it does not, the press is all that happens here and the UI
     * sends the user to the switch; [continuePending] finishes the job.
     */
    fun install(context: Context, grantNeeded: Boolean) {
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        prefs.installPressedAt = SystemClock.elapsedRealtime()
        if (grantNeeded) return
        commit(app, prefs)
    }

    /** Picks up an install whose only obstacle was the permission, if it is still recent. */
    fun continuePending(context: Context, grantNeeded: Boolean) {
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        val pending = UpdateCheckGate.installStillPending(
            elapsedNow = SystemClock.elapsedRealtime(),
            pressedAt = prefs.installPressedAt,
        )
        if (!pending) {
            prefs.installPressedAt = 0L
            return
        }
        if (grantNeeded) return
        prefs.installPressedAt = 0L
        if (_state.value == UpdateState.Downloaded) commit(app, prefs)
    }

    private fun commit(context: Context, prefs: UpdatePrefs) {
        val target = candidate ?: return
        val apk = ApkStaging.apkFile(context, target)
        if (!apk.isFile) {
            backToAvailable(context, target)
            return
        }
        scope.launch {
            when (val verdict = ApkInstall.precheck(context, apk)) {
                is InstallPrecheck.Rejected -> {
                    if (verdict.reason == UpdateFailure.SIGNATURE_MISMATCH) apk.delete()
                    _state.value = UpdateState.Failed(false, verdict.reason)
                }
                InstallPrecheck.Ok -> {
                    _state.value = UpdateState.Installing
                    // Written before the commit: on the quiet path the process
                    // is replaced without warning, so this is the only chance
                    // to record what the next launch should say happened.
                    prefs.rememberInstall(
                        target.versionCode,
                        System.currentTimeMillis(),
                        languageSwitch = _switching.value,
                    )
                    val session = ApkInstall.commit(
                        context,
                        apk,
                        ApkStaging.dexMetadataFile(context, target),
                    )
                    if (session == null) {
                        _state.value = UpdateState.Failed(false, UpdateFailure.INSTALL_FAILED)
                    } else {
                        prefs.sessionId = session
                    }
                }
            }
        }
    }

    /** Android wants a screen shown. The activity shows it; a receiver may not. */
    fun onUserActionRequired(context: Context, confirm: Intent?) {
        if (confirm == null) {
            DebugLog.w(TAG, "install asked for a screen and named none")
            return
        }
        _pendingUserAction.value = confirm
        // If no activity is alive to show it, there is nobody to be
        // interrupted either, so starting it here is the lesser evil.
        if (!hasResumedActivity) {
            val standalone = Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.applicationContext.startActivity(standalone) }
                .onSuccess { _pendingUserAction.value = null }
        }
    }

    /** Called by the activity once it has shown the screen Android asked for. */
    fun userActionShown() {
        _pendingUserAction.value = null
    }

    /** Whether a settings activity is in front and able to show a system screen. */
    @Volatile
    var hasResumedActivity: Boolean = false

    fun onInstallStatus(context: Context, status: Int, message: String?) {
        val app = context.applicationContext
        val prefs = UpdatePrefs(app)
        prefs.sessionId = 0
        val outcome = installOutcome(status)
        if (outcome.state is UpdateState.Failed) {
            DebugLog.w(TAG, "install failed with status $status: ${message.orEmpty()}")
        }
        val target = candidate ?: GithubUpdateChecker.decodeCandidate(prefs.knownCandidate)
        if (outcome.disposition == FileDisposition.DELETE) {
            target?.let { ApkStaging.apkFile(app, it).delete() }
        }
        if (status != android.content.pm.PackageInstaller.STATUS_SUCCESS &&
            status != android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION
        ) {
            // A failed install goes again without the dex metadata: it is the
            // one optional thing in the session, so it is never the reason an
            // update cannot be retried.
            target?.let { ApkStaging.dexMetadataFile(app, it)?.delete() }
        }
        if (status == android.content.pm.PackageInstaller.STATUS_SUCCESS) {
            // The new version is running. Nothing staged belongs to it, and
            // the offer it came from is finished with.
            candidate = null
            prefs.clearCandidate()
            ApkStaging.sweep(app, BuildConfig.RELEASE_VERSION_CODE, BuildConfig.FLAVOR)
        }
        _state.value = outcome.state
    }

    /** Fetches the release notes once per release, on request. */
    fun loadNotes() {
        val target = candidate ?: return
        if (notesFor == target.versionCode) return
        notesFor = target.versionCode
        scope.launch {
            _releaseNotes.value = fetchReleaseNotes(target).orEmpty()
        }
    }

    fun dismiss(context: Context) {
        val available = _state.value as? UpdateState.Available ?: return
        UpdatePrefs(context.applicationContext)
            .snooze(available.versionCode, System.currentTimeMillis())
        _state.value = available.copy(dismissed = true, promptOpen = false)
    }

    /** Re-reads the disk, for a file the user may have deleted since. */
    fun refresh(context: Context) {
        val target = candidate ?: return
        if (_state.value != UpdateState.Downloaded) return
        if (!ApkStaging.apkFile(context.applicationContext, target).isFile) {
            backToAvailable(context.applicationContext, target)
        }
    }

    private fun backToAvailable(context: Context, target: UpdateCandidate) {
        val prefs = UpdatePrefs(context)
        _state.value = offerOf(context, prefs, target, userAsked = false, allowPrompt = false)
    }

    private fun offerOf(
        context: Context,
        prefs: UpdatePrefs,
        target: UpdateCandidate,
        userAsked: Boolean,
        allowPrompt: Boolean,
    ): UpdateState {
        val now = System.currentTimeMillis()
        val apk: File = ApkStaging.apkFile(context, target)
        // The move to every language was asked for by a press, so it is never
        // snoozed and never pops up as an update dialog.
        val switching = _switching.value
        val state = GithubUpdateChecker.offer(
            candidate = target,
            snoozed = !switching && prefs.isSnoozed(target.versionCode, now),
            autoPrompt = prefs.autoPrompt,
            allowPrompt = allowPrompt && !prompted && !switching,
            userAsked = userAsked,
            now = now,
            // A file whose length matches is taken at its word here; the
            // checksum was already checked when it was renamed into place, and
            // the signature is checked again before it installs.
            fileVerified = apk.isFile && apk.length() == target.sizeBytes,
        )
        if (state is UpdateState.Available && state.promptOpen) {
            prompted = true
            // The dialog names the version and shows the first of the notes,
            // so they have to be on their way before it opens.
            loadNotes()
        }
        return state
    }
}
