package com.wasimaster.wmkeyboard.app.updates

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wasimaster.wmkeyboard.BuildConfig
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.app.LiveSettings
import com.wasimaster.wmkeyboard.app.LocalReduceMotion
import com.wasimaster.wmkeyboard.app.MeteredBlockedDialog
import com.wasimaster.wmkeyboard.app.MeteredDownloadDialog
import com.wasimaster.wmkeyboard.app.NavRow
import com.wasimaster.wmkeyboard.app.SettingsGroup
import com.wasimaster.wmkeyboard.app.SettingsRowIcons
import com.wasimaster.wmkeyboard.app.SpecialAccess
import com.wasimaster.wmkeyboard.app.ToggleSetting
import com.wasimaster.wmkeyboard.app.WmRow
import com.wasimaster.wmkeyboard.app.downloadDecisionNow
import com.wasimaster.wmkeyboard.app.isMeteredNow
import com.wasimaster.wmkeyboard.app.RunGlyph
import com.wasimaster.wmkeyboard.app.RunPhase
import com.wasimaster.wmkeyboard.app.rememberDisclosedSpecialAccess
import com.wasimaster.wmkeyboard.app.stateSwapTransform
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.automirrored.outlined.KeyboardArrowRight
import com.wasimaster.wmkeyboard.core.netlog.OfflineBuild
import com.wasimaster.wmkeyboard.core.settings.MeteredDecision
import kotlinx.coroutines.delay

/**
 * The update card on the settings home screen.
 *
 * Draws nothing unless there is something to act on, which is most of the
 * time: an up-to-date install sits on [UpdateState.UpToDate] and a build with
 * no update source behind it on [UpdateState.Unsupported]. A failed check
 * draws nothing either. The About row is where a retry belongs, not the front
 * page.
 */
@Composable
internal fun UpdateCard(settings: LiveSettings, modifier: Modifier = Modifier) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val switching by updater.switchingToAllLanguages.collectAsStateWithLifecycle()
    // The move to every language is drawn on the About screen's App language
    // row, where it was started. As a card it would read as a version update.
    if (switching) return
    val reduceMotion = LocalReduceMotion.current
    // One card handing over to the next as the update moves along — the offer
    // to the download, the download's bar to the Install button — and opening
    // out of nothing or closing into it at either end. Keyed by the card, so a
    // download's every progress report is the same card updating, not a swap.
    AnimatedContent(
        targetState = state,
        transitionSpec = { stateSwapTransform(reduceMotion) },
        contentKey = { it.card() },
        label = "updateCard",
    ) { shown ->
        when (shown.card()) {
            UpdateCardKind.AVAILABLE ->
                AvailableCard(shown as UpdateState.Available, updater, settings, modifier)
            UpdateCardKind.DOWNLOADING ->
                DownloadingCard(shown as UpdateState.Downloading, updater, modifier)
            UpdateCardKind.READY -> ReadyCard(updater, modifier)
            UpdateCardKind.INSTALLING -> InstallingCard(updater, modifier)
            UpdateCardKind.NONE -> Unit
        }
    }
}

/** Which of the home screen's update cards a state draws. */
private enum class UpdateCardKind { NONE, AVAILABLE, DOWNLOADING, READY, INSTALLING }

private fun UpdateState.card(): UpdateCardKind = when (this) {
    is UpdateState.Available -> if (dismissed) UpdateCardKind.NONE else UpdateCardKind.AVAILABLE
    is UpdateState.Downloading -> UpdateCardKind.DOWNLOADING
    UpdateState.Downloaded -> UpdateCardKind.READY
    UpdateState.Installing -> UpdateCardKind.INSTALLING
    UpdateState.Unsupported,
    UpdateState.Idle,
    UpdateState.Checking,
    is UpdateState.UpToDate,
    is UpdateState.Failed,
    -> UpdateCardKind.NONE
}

/**
 * Whether the updater is still where [kind]'s card says it is. The card that
 * is fading out keeps its buttons for the moment it takes, and a press on the
 * old offer must not start a second download under the new card's bar.
 */
private fun AppUpdater.showing(kind: UpdateCardKind): Boolean = state.value.card() == kind

/**
 * "Updated to 0.5.3", once, after this app has replaced itself.
 *
 * The reason it exists is the quiet install: from Android 12 an update this
 * app installs can finish with no screen of its own, so without this the whole
 * thing would happen with no sign that anything had. Reads and clears in one
 * go, so it appears in exactly one composition of one launch.
 */
@Composable
internal fun UpdatedCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Whether it was the move to every language rather than a new version,
    // read before takeJustUpdated clears the record it lives in.
    val (justUpdated, switchedLanguages) = remember {
        val prefs = UpdatePrefs(context)
        val switched = prefs.justSwitchedLanguages
        prefs.takeJustUpdated(BuildConfig.VERSION_CODE, System.currentTimeMillis()) to switched
    }
    if (!justUpdated) return
    var showing by remember { mutableStateOf(true) }
    val reduceMotion = LocalReduceMotion.current
    // Folds away when dismissed rather than vanishing from under the cards
    // below it.
    if (reduceMotion && !showing) return
    AnimatedVisibility(
        visible = showing,
        enter = fadeIn(),
        exit = fadeOut() + shrinkVertically(),
        label = "updatedCard",
    ) {
        UpdatedCardBody(switchedLanguages, modifier) { showing = false }
    }
}

@Composable
private fun UpdatedCardBody(
    switchedLanguages: Boolean,
    modifier: Modifier,
    onDismiss: () -> Unit,
) {
    // In the app rather than on the release page: the notes ship in the APK
    // (see [InstalledReleaseNotes]), and a browser is a long way to go for them.
    var showNotes by remember { mutableStateOf(false) }
    if (showNotes) InstalledReleaseNotesDialog { showNotes = false }
    UpdateCardFrame(
        title = if (switchedLanguages) {
            stringResource(R.string.update_card_switched_title)
        } else {
            stringResource(R.string.update_card_updated_title, BuildConfig.VERSION_NAME)
        },
        body = stringResource(
            if (switchedLanguages) R.string.update_card_switched_body else R.string.update_card_updated_body,
        ),
        modifier = modifier,
    ) {
        Button(onClick = onDismiss) {
            Text(stringResource(R.string.update_action_dismiss))
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { showNotes = true }) {
            Text(stringResource(R.string.update_action_notes))
        }
    }
}

/**
 * The offer, put in front of the user once rather than waited for on a card
 * they may never scroll to.
 *
 * Hosted at the root of the settings activity rather than inside [UpdateCard],
 * which only composes on the home screen: a dialog that could only open on one
 * screen would open on whichever screen the user happened not to be on.
 */
@Composable
internal fun UpdatePromptDialog(settings: LiveSettings) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val notes by updater.releaseNotes.collectAsStateWithLifecycle()
    val switching by updater.switchingToAllLanguages.collectAsStateWithLifecycle()
    val available = state as? UpdateState.Available ?: return
    if (!available.promptOpen || switching) return
    val download = rememberUpdateDownloadRequest(updater, available.sizeBytes, settings)
    var showNotes by remember(available.versionCode) { mutableStateOf(false) }
    if (showNotes) {
        ReleaseNotesDialog(
            version = available.displayVersion(),
            notes = notes,
            onDismiss = { showNotes = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = updater::dismiss,
        title = { Text(stringResource(R.string.update_card_available_title)) },
        text = { Text(availableBody(available, updater)) },
        confirmButton = {
            TextButton(onClick = download) { Text(startActionLabel(updater)) }
        },
        dismissButton = {
            Row {
                // The notes are their own dialog rather than more text in this
                // one: an offer the user has to read past to reach the button
                // is a worse offer.
                if (updater.supportsNotes) {
                    TextButton(
                        onClick = {
                            showNotes = true
                            updater.loadNotes()
                        },
                    ) {
                        Text(stringResource(R.string.update_action_notes))
                    }
                }
                TextButton(onClick = updater::dismiss) {
                    Text(stringResource(R.string.update_action_later))
                }
            }
        },
    )
}

@Composable
private fun AvailableCard(
    state: UpdateState.Available,
    updater: AppUpdater,
    settings: LiveSettings,
    modifier: Modifier = Modifier,
) {
    val notes by updater.releaseNotes.collectAsStateWithLifecycle()
    var showNotes by remember(state.versionCode) { mutableStateOf(false) }
    val download = rememberUpdateDownloadRequest(updater, state.sizeBytes, settings)
    if (showNotes) {
        ReleaseNotesDialog(
            version = state.displayVersion(),
            notes = notes,
            onDismiss = { showNotes = false },
        )
    }
    UpdateCardFrame(
        title = stringResource(R.string.update_card_available_title),
        body = availableBody(state, updater),
        modifier = modifier,
    ) {
        Button(onClick = { if (updater.showing(UpdateCardKind.AVAILABLE)) download() }) {
            Text(startActionLabel(updater))
        }
        // An urgent release gets no "Not now": the button would write a snooze
        // that [UpdatePolicy] ignores for that release anyway, so it would be
        // a control that does nothing.
        if (!state.immediate) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { if (updater.showing(UpdateCardKind.AVAILABLE)) updater.dismiss() }) {
                Text(stringResource(R.string.update_action_later))
            }
        }
        // Gated on the updater rather than on there being a page to link to:
        // F-Droid has the page and no notes behind it. See [AppUpdater.supportsNotes].
        if (updater.supportsNotes) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    showNotes = true
                    updater.loadNotes()
                },
            ) {
                Text(stringResource(R.string.update_action_notes))
            }
        }
    }
}

@Composable
private fun DownloadingCard(
    state: UpdateState.Downloading,
    updater: AppUpdater,
    modifier: Modifier = Modifier,
) {
    val fraction = state.fraction
    UpdateCardFrame(
        title = stringResource(R.string.update_card_downloading_title),
        // Nothing to say yet while the download is only queued: there are no
        // byte counts until it starts, and "0 B of 0 B" is worse than silence.
        body = state.progressText(),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth()) {
            UpdateProgressBar(fraction, Modifier.fillMaxWidth())
            // Only where this app is the one downloading. A store's download
            // is the store's to stop.
            if (updater.ownsDownload) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { if (updater.showing(UpdateCardKind.DOWNLOADING)) updater.cancel() }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(updater: AppUpdater, modifier: Modifier = Modifier) {
    val grant = updater.installGrant
    val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
    UpdateCardFrame(
        title = stringResource(R.string.update_card_ready_title),
        // The warning is the point of this card. Installing kills the process
        // the keyboard runs in, and on a build that installs for itself Android
        // may not show a screen of its own at all, so this sentence is the only
        // warning the user gets. See [AppUpdater].
        body = stringResource(
            if (updater.ownsDownload) {
                R.string.update_card_ready_body_self
            } else {
                R.string.update_card_ready_body
            },
        ),
        modifier = modifier,
    ) {
        Button(
            onClick = {
                if (!updater.showing(UpdateCardKind.READY)) return@Button
                // Records the press either way. When Android still has to be
                // told this app may install, the disclosure goes first and the
                // install picks up on the way back.
                updater.install()
                if (grant != null) requestGrant()
            },
        ) {
            Text(stringResource(R.string.update_action_install))
        }
    }
}

@Composable
private fun InstallingCard(updater: AppUpdater, modifier: Modifier = Modifier) {
    UpdateCardFrame(
        title = stringResource(R.string.update_card_installing_title),
        body = stringResource(
            if (updater.ownsDownload) {
                R.string.update_card_installing_body_self
            } else {
                R.string.update_card_installing_body
            },
        ),
        modifier = modifier,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The shape every update card shares: a title, a line of prose, then controls.
 *
 * [busy] draws an endless bar under the words, for the wait that has no size to
 * measure — fetching the release notes is the only one. A download's own bar is
 * a control, and lives with the controls.
 */
@Composable
private fun UpdateCardFrame(
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    controls: @Composable () -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    Card(modifier = modifier.fillMaxWidth()) {
        // Grows with its words — a download's byte count arriving under the
        // title once the size is known — rather than jumping.
        Column(
            modifier = Modifier
                .then(if (reduceMotion) Modifier else Modifier.animateContentSize())
                .padding(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            if (busy) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Row { controls() }
        }
    }
}

/**
 * Starting a download, with the user's data saving setting in the way.
 *
 * Only where this app does the downloading: a store runs its own data policy,
 * and asking twice about one download would be this app second-guessing it.
 * The check itself is never gated, being a few kilobytes of JSON.
 */
@Composable
internal fun rememberUpdateDownloadRequest(
    updater: AppUpdater,
    sizeBytes: Long,
    settings: LiveSettings,
): () -> Unit {
    val context = LocalContext.current
    var blocked by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    if (blocked) MeteredBlockedDialog { blocked = false }
    if (confirming) {
        val size = Formatter.formatShortFileSize(context, sizeBytes)
        MeteredDownloadDialog(
            detail = stringResource(R.string.update_metered_body, size),
            onConfirm = {
                confirming = false
                updater.start()
            },
            onDismiss = { confirming = false },
        )
    }
    return {
        if (!updater.ownsDownload) {
            updater.start()
        } else {
            when (downloadDecisionNow(context, settings.value)) {
                MeteredDecision.BLOCKED -> blocked = true
                MeteredDecision.ASK -> confirming = true
                MeteredDecision.ALLOWED ->
                    if (isMeteredNow(context)) confirming = true else updater.start()
            }
        }
    }
}

/** What the button that starts an update says, which depends on where it goes. */
@Composable
internal fun startActionLabel(updater: AppUpdater): String = when {
    updater.startsExternally ->
        stringResource(R.string.update_action_open_source, stringResource(updater.sourceNameRes))
    updater.ownsDownload -> stringResource(R.string.update_action_download)
    else -> stringResource(R.string.update_action_update)
}

/**
 * The sentence describing what is on offer, with a version when there is one,
 * and the reason for the offer when that version has the name already installed.
 */
@Composable
private fun availableBody(state: UpdateState.Available, updater: AppUpdater): String {
    val source = stringResource(updater.sourceNameRes)
    val version = state.displayVersion()
    val offer = when {
        state.immediate -> stringResource(R.string.update_card_urgent_body, source)
        version == null -> stringResource(R.string.update_card_available_body, source)
        else -> stringResource(R.string.update_card_available_body_versioned, version, source)
    }
    // The build number alone answers "which one is newer" but not "why is it
    // offering me the version I have". Only the card and the prompt have room
    // for that answer; the rows keep the short form.
    if (!state.isSameName()) return offer
    val notice = stringResource(
        R.string.update_same_name_notice,
        state.versionName.orEmpty(),
        BuildConfig.VERSION_CODE,
        state.versionCode,
    )
    return "$offer\n\n$notice"
}

/** Whether the release reuses the installed version name with a higher version code. */
private fun UpdateState.Available.isSameName(): Boolean =
    !versionName.isNullOrBlank() && versionName == BuildConfig.VERSION_NAME

/**
 * The version on offer, as the user should read it, or null when the release
 * names none.
 *
 * Normally just the name. A fix can be re-released under the name already
 * installed with only the version code raised (0.5.14 went out as vc30 and
 * then vc31), and "0.5.14 is available" on a phone running 0.5.14 reads as a
 * bug. When the names match, the build number is what tells them apart, so it
 * is shown.
 */
@Composable
internal fun UpdateState.Available.displayVersion(): String? {
    val name = versionName?.takeIf { it.isNotBlank() } ?: return null
    return if (isSameName()) {
        stringResource(R.string.update_version_with_build, name, versionCode)
    } else {
        name
    }
}

/** "12 MB of 30 MB", or null while the size is still unknown. */
@Composable
private fun UpdateState.Downloading.progressText(): String? {
    if (totalBytes <= 0L) return null
    val context = LocalContext.current
    return stringResource(
        R.string.update_progress_body,
        Formatter.formatShortFileSize(context, bytesDownloaded),
        Formatter.formatShortFileSize(context, totalBytes),
    )
}

/**
 * The Updates group on the About screen: one row that always says where things
 * stand, and the settings that decide what arrives uninvited.
 *
 * The row's action follows its state rather than always meaning "check". A row
 * that reads "Install the update" and then only re-checks for one would be
 * lying about what pressing it does.
 */
@Composable
internal fun UpdateSettings(settings: LiveSettings) {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val switching by updater.switchingToAllLanguages.collectAsStateWithLifecycle()
    if (state is UpdateState.Unsupported) return

    // These settings live in SharedPreferences, not in the settings DataStore,
    // so they are not snapshot state and a write would not recompose anything
    // on its own. Each switch keeps its own copy and writes through.
    val autoCheck = remember { mutableStateOf(updater.autoCheck) }
    val autoPrompt = remember { mutableStateOf(updater.autoPrompt) }
    val prereleases = remember { mutableStateOf(updater.includePrereleases) }
    val releaseUrl = (state as? UpdateState.Available)?.releaseUrl?.takeUnless { switching }
    // The address the row last linked to, for it to keep while it folds away.
    val shownUrl = remember { arrayOfNulls<String>(1) }
    if (releaseUrl != null) shownUrl[0] = releaseUrl

    SettingsGroup(stringResource(R.string.update_section_title)) {
        // While the move to every language runs, the state is that move's, and
        // the App language row above draws it. Drawn here too it would say
        // "Download the update" about something that is not one.
        item(visible = !switching) { UpdateRow(state, updater, settings) }
        // Opens under the row above when a release turns up, and folds away
        // when it has been installed or the move to every language takes over.
        item(visible = releaseUrl != null) { shownUrl[0]?.let { ReleasePageRow(it) } }
        item {
            ToggleSetting(
                R.string.update_row_auto_check_title,
                stringResource(R.string.update_row_auto_check_subtitle),
                checked = autoCheck.value,
                default = UpdatePrefs.DEFAULT_AUTO_CHECK,
                onChange = {
                    autoCheck.value = it
                    updater.autoCheck = it
                },
            )
        }
        item {
            ToggleSetting(
                R.string.update_row_prompts_title,
                stringResource(R.string.update_row_prompts_subtitle),
                checked = autoPrompt.value,
                default = UpdatePrefs.DEFAULT_AUTO_PROMPT,
                onChange = {
                    autoPrompt.value = it
                    updater.autoPrompt = it
                },
            )
        }
        item(visible = updater.supportsPrereleases) {
            ToggleSetting(
                R.string.update_row_prereleases_title,
                stringResource(R.string.update_row_prereleases_subtitle),
                checked = prereleases.value,
                default = UpdatePrefs.DEFAULT_PRERELEASES,
                onChange = {
                    prereleases.value = it
                    updater.includePrereleases = it
                },
            )
        }
    }
}

@Composable
private fun UpdateRow(state: UpdateState, updater: AppUpdater, settings: LiveSettings) {
    val reduceMotion = LocalReduceMotion.current
    // The row hands over to its next self — the check to the offer, the offer
    // to the bar, the bar to Install — rather than being swapped in a frame.
    // Keyed by what the row is, so the bar's progress and the check's words
    // update in place.
    AnimatedContent(
        targetState = state,
        transitionSpec = { stateSwapTransform(reduceMotion) },
        contentKey = { it.row() },
        label = "updateRow",
    ) { shown -> UpdateRowContent(shown, updater, settings) }
}

/** Which of its faces the Updates row is showing. */
private enum class UpdateRowKind { CHECK, AVAILABLE, DOWNLOADING, INSTALL, INSTALLING }

private fun UpdateState.row(): UpdateRowKind = when (this) {
    is UpdateState.Available -> UpdateRowKind.AVAILABLE
    is UpdateState.Downloading -> UpdateRowKind.DOWNLOADING
    UpdateState.Downloaded -> UpdateRowKind.INSTALL
    UpdateState.Installing -> UpdateRowKind.INSTALLING
    UpdateState.Unsupported,
    UpdateState.Idle,
    UpdateState.Checking,
    is UpdateState.UpToDate,
    is UpdateState.Failed,
    -> UpdateRowKind.CHECK
}

@Composable
private fun UpdateRowContent(state: UpdateState, updater: AppUpdater, settings: LiveSettings) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> AvailableRow(state, updater, settings, context)
        // Nothing to press while the download runs, so this is a plain row
        // rather than a [NavRow]: a chevron would promise an action that is
        // not there.
        is UpdateState.Downloading -> ProgressRow(
            R.string.update_row_downloading_title,
            state.progressText(),
            state.fraction,
        )
        UpdateState.Downloaded -> InstallRow(updater)
        // An install reports no progress of its own, so the bar runs
        // indeterminate: the row's job here is to say the app is working, not
        // to claim it knows how far along Android is.
        UpdateState.Installing -> ProgressRow(R.string.update_row_installing_title, null, null)
        // Every quiet state, where the row's job is to offer a check and to
        // report the last one.
        UpdateState.Unsupported,
        UpdateState.Idle,
        UpdateState.Checking,
        is UpdateState.UpToDate,
        is UpdateState.Failed,
        -> CheckRow(state, updater)
    }
}

/**
 * "Check for updates", with the check's own glyph beside the chevron: a
 * spinner while it asks, then a tick that draws itself on when the answer is
 * that this is the newest, for a moment, before the row is a row again.
 */
@Composable
private fun CheckRow(state: UpdateState, updater: AppUpdater) {
    var answered by remember { mutableStateOf(false) }
    // Whether the last state this row saw was a check under way, so only an
    // answer to one earns the tick and not an up-to-date state it opened on.
    val wasChecking = remember { booleanArrayOf(false) }
    LaunchedEffect(state) {
        val finished = wasChecking[0] && state is UpdateState.UpToDate
        wasChecking[0] = state == UpdateState.Checking
        answered = finished
        if (finished) {
            delay(CHECK_DONE_HOLD_MS)
            answered = false
        }
    }
    val phase = when {
        state == UpdateState.Checking -> RunPhase.RUNNING
        answered -> RunPhase.DONE
        else -> RunPhase.IDLE
    }
    val title = R.string.update_row_check_title
    WmRow(
        title = stringResource(title),
        subtitle = stringResource(state.checkSubtitle()),
        icon = SettingsRowIcons[title],
        highlightKey = title,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RunGlyph(phase, MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        // Only from the row that is still the check: the one fading out under
        // a fresh offer must not ask again.
        onClick = { if (updater.state.value.row() == UpdateRowKind.CHECK) updater.check(userAsked = true) },
    )
}

@Composable
private fun AvailableRow(
    state: UpdateState.Available,
    updater: AppUpdater,
    settings: LiveSettings,
    context: Context,
) {
    val download = rememberUpdateDownloadRequest(updater, state.sizeBytes, settings)
    if (updater.startsExternally) {
        NavRow(
            R.string.update_row_available_title_external,
            subtitle = stringResource(
                R.string.update_row_available_subtitle_external,
                state.displayVersion().orEmpty(),
                stringResource(updater.sourceNameRes),
            ),
            onClick = { if (updater.state.value.row() == UpdateRowKind.AVAILABLE) download() },
        )
        return
    }
    val size = Formatter.formatShortFileSize(context, state.sizeBytes)
    NavRow(
        R.string.update_row_available_title,
        subtitle = if (state.sizeBytes > 0L) {
            stringResource(R.string.update_row_available_subtitle, state.versionCode, size)
        } else {
            stringResource(R.string.update_row_available_subtitle_plain, state.versionCode)
        },
        onClick = { if (updater.state.value.row() == UpdateRowKind.AVAILABLE) download() },
    )
}

@Composable
private fun InstallRow(updater: AppUpdater) {
    val grant = updater.installGrant
    val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
    NavRow(
        R.string.update_row_install_title,
        subtitle = stringResource(R.string.update_row_install_subtitle),
        onClick = {
            if (updater.state.value.row() != UpdateRowKind.INSTALL) return@NavRow
            updater.install()
            if (grant != null) requestGrant()
        },
    )
}

@Composable
private fun ReleasePageRow(url: String) {
    val uriHandler = LocalUriHandler.current
    NavRow(
        R.string.update_row_release_page_title,
        subtitle = stringResource(R.string.update_row_release_page_subtitle),
        onClick = { uriHandler.openUri(url) },
    )
}

/**
 * A row that reports work in progress and cannot be pressed, with the bar its
 * words cannot draw.
 *
 * "12 MB of 30 MB" is only as informative as the arithmetic the reader does on
 * it, and while the total is unknown it says nothing at all. The bar under the
 * row is the same shape every other download in this app draws, so an update
 * in flight looks like a dictionary or a model in flight.
 *
 * [fraction] is null for work whose end is not known — an install, or a
 * download whose source never reported a size — and the bar then runs
 * indeterminate rather than sitting convincingly at zero.
 */
@Composable
private fun ProgressRow(@StringRes title: Int, subtitle: String?, fraction: Float?) {
    Column {
        WmRow(
            title = stringResource(title),
            subtitle = subtitle,
            icon = SettingsRowIcons[title],
            highlightKey = title,
        )
        UpdateProgressBar(fraction, ProgressBarModifier)
    }
}

/**
 * An update's bar: endless while there is no size to measure against, then
 * filling. The endless one hands over to the filling one rather than being
 * swapped in a frame, and the fill glides between the download's reports
 * instead of stepping from one to the next. Under Reduce motion it steps.
 */
@Composable
private fun UpdateProgressBar(fraction: Float?, modifier: Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val shown by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = if (reduceMotion) snap<Float>() else tween<Float>(durationMillis = PROGRESS_GLIDE_MS),
        label = "updateProgress",
    )
    AnimatedContent(
        targetState = fraction != null,
        transitionSpec = { stateSwapTransform(reduceMotion) },
        modifier = modifier,
        label = "updateProgressKind",
    ) { known ->
        if (known) {
            LinearProgressIndicator(progress = { shown }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

/** How long the bar takes to glide to the download's latest report. */
private const val PROGRESS_GLIDE_MS = 250

/** How long the tick stays on "Check for updates" after a check finds nothing. */
private const val CHECK_DONE_HOLD_MS = 1_500L

/**
 * Where the progress bar sits under its row: inset to the row's own text
 * margin, with enough air under it to keep it off the next row's title.
 */
private val ProgressBarModifier = Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp, vertical = 4.dp)

/** What the "Check for updates" row says under its name, for the quiet states. */
@StringRes
private fun UpdateState.checkSubtitle(): Int = when (this) {
    UpdateState.Checking -> R.string.update_row_checking_subtitle
    is UpdateState.UpToDate -> R.string.update_row_current_subtitle
    // A cancelled flow is the user's own answer, so the row goes back to its
    // resting words rather than reporting their decision back at them.
    is UpdateState.Failed -> if (cancelled) R.string.update_row_check_subtitle else reason.subtitle()
    UpdateState.Unsupported,
    UpdateState.Idle,
    UpdateState.Downloaded,
    UpdateState.Installing,
    is UpdateState.Available,
    is UpdateState.Downloading,
    -> R.string.update_row_check_subtitle
}

/**
 * One sentence per way an update can fail, because they ask for different
 * things: wait, make room, try again, or go and fetch it by hand.
 *
 * Internal rather than private because the notification the download posts
 * says the same sentences, and a shade that explained a failure differently
 * from the row behind it would be two answers to one question.
 */
@StringRes
internal fun UpdateFailure?.subtitle(): Int = when (this) {
    UpdateFailure.RATE_LIMITED -> R.string.update_row_failed_rate_limited_subtitle
    UpdateFailure.NO_ASSET -> R.string.update_row_failed_no_asset_subtitle
    UpdateFailure.NO_SPACE -> R.string.update_row_failed_no_space_subtitle
    UpdateFailure.CORRUPT -> R.string.update_row_failed_corrupt_subtitle
    UpdateFailure.SIGNATURE_MISMATCH -> R.string.update_row_failed_signature_subtitle
    UpdateFailure.INSTALL_BLOCKED -> R.string.update_row_failed_blocked_subtitle
    UpdateFailure.INSTALL_FAILED -> R.string.update_row_failed_install_subtitle
    UpdateFailure.NETWORK, null -> OfflineBuild.network(R.string.update_row_failed_subtitle)
}

/**
 * The App language row on an English-only (`en`) build, which has nothing to
 * choose between (#322). It says so, and offers the one fix there is: the
 * build of the same release with every language, fetched and installed by
 * the GitHub updater. Only GitHub hands out the `en` APK, so where there is no
 * GitHub updater to do it the row links to the releases page instead.
 *
 * From the press on, the row follows the updater through the ordinary steps
 * (find, download, install), with words that say what they are for. The
 * download starts as soon as the file is found, through the same data-saving
 * questions as any update; the install waits for its own press, because it
 * restarts the keyboard. [extraLanguages] is how many the other build adds.
 */
@Composable
internal fun AllLanguagesRow(settings: LiveSettings, extraLanguages: Int, englishName: String) {
    val updater = LocalAppUpdater.current
    val uriHandler = LocalUriHandler.current
    if (!updater.canSwitchToAllLanguages) {
        NavRow(
            R.string.about_app_language_title,
            subtitle = pluralStringResource(
                R.plurals.about_app_language_english_only_link, extraLanguages, extraLanguages,
            ),
            value = englishName,
            onClick = { uriHandler.openUri(GithubReleases.LIST_PAGE) },
        )
        return
    }
    val state by updater.state.collectAsStateWithLifecycle()
    val switching by updater.switchingToAllLanguages.collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    // Set by the press that starts the move, and spent on the first offer that
    // arrives, so a later offer (after a cancel) waits for its own press.
    var continueWhenFound by rememberSaveable { mutableStateOf(false) }
    val available = (state as? UpdateState.Available)?.takeIf { switching }
    val download = rememberUpdateDownloadRequest(updater, available?.sizeBytes ?: 0L, settings)
    val begin = {
        continueWhenFound = true
        updater.switchToAllLanguages()
    }
    LaunchedEffect(switching, available, continueWhenFound) {
        when {
            !continueWhenFound -> Unit
            available != null -> {
                continueWhenFound = false
                download()
            }
            // The press did not take (an update was installing) or the move
            // was dropped before anything was found. Read live rather than
            // from `switching`: the press sets it synchronously, and the
            // collected copy is a frame behind.
            !updater.switchingToAllLanguages.value -> continueWhenFound = false
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.about_all_languages_dialog_title)) },
            text = { Text(pluralStringResource(R.plurals.about_all_languages_dialog_body, extraLanguages, extraLanguages)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        begin()
                    },
                ) {
                    Text(stringResource(R.string.about_all_languages_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(CommonR.string.common_cancel))
                }
            },
        )
    }
    if (!switching) {
        NavRow(
            R.string.about_app_language_title,
            subtitle = pluralStringResource(
                R.plurals.about_app_language_english_only, extraLanguages, extraLanguages,
            ),
            value = englishName,
            onClick = { confirming = true },
        )
        return
    }
    val context = LocalContext.current
    when (val current = state) {
        is UpdateState.Available -> NavRow(
            R.string.about_all_languages_download_title,
            subtitle = if (current.sizeBytes > 0L) {
                stringResource(
                    R.string.about_all_languages_download_subtitle,
                    current.displayVersion().orEmpty(),
                    Formatter.formatShortFileSize(context, current.sizeBytes),
                )
            } else {
                null
            },
            onClick = download,
        )
        is UpdateState.Downloading -> ProgressRow(
            R.string.about_all_languages_downloading_title,
            current.progressText(),
            current.fraction,
        )
        UpdateState.Downloaded -> {
            val grant = updater.installGrant
            val requestGrant = rememberDisclosedSpecialAccess(SpecialAccess.INSTALL_UPDATES)
            NavRow(
                R.string.about_all_languages_install_title,
                subtitle = stringResource(R.string.about_all_languages_install_subtitle),
                onClick = {
                    updater.install()
                    if (grant != null) requestGrant()
                },
            )
        }
        UpdateState.Installing -> ProgressRow(R.string.about_all_languages_installing_title, null, null)
        is UpdateState.Failed -> NavRow(
            R.string.about_all_languages_failed_title,
            subtitle = stringResource(
                if (current.cancelled) R.string.about_all_languages_retry_subtitle else current.reason.subtitle(),
            ),
            onClick = begin,
        )
        // Looking for the file. Also the one frame between the press and the
        // updater saying so.
        UpdateState.Unsupported,
        UpdateState.Idle,
        UpdateState.Checking,
        is UpdateState.UpToDate,
        -> ProgressRow(R.string.about_all_languages_checking_title, null, null)
    }
}

/**
 * The way out of the move to every language, under [AllLanguagesRow]: stops
 * the download, deletes it, and puts the Updates group back. Shown from the
 * moment there is something to stop until the install begins.
 */
@Composable
internal fun KeepEnglishOnlyRow() {
    val updater = LocalAppUpdater.current
    NavRow(
        R.string.about_all_languages_keep_title,
        subtitle = stringResource(R.string.about_all_languages_keep_subtitle),
        onClick = updater::abandonLanguageSwitch,
    )
}

/** Whether [KeepEnglishOnlyRow] belongs on screen right now. */
@Composable
internal fun languageSwitchCanBeDropped(): Boolean {
    val updater = LocalAppUpdater.current
    val state by updater.state.collectAsStateWithLifecycle()
    val switching by updater.switchingToAllLanguages.collectAsStateWithLifecycle()
    return switching && state != UpdateState.Installing
}
