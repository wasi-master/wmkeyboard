package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Tune
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import kotlinx.coroutines.flow.drop

/*
 * Settings depth: how much of the settings app is shown.
 *
 * Every row carries a [SettingLevel]; the depth in force ([SettingsDepth],
 * stored in `AppUiSettings.settingsDepth` and resolved by the nav host)
 * decides which levels are drawn. A row above the depth is not drawn at all.
 * It keeps its value, a link or a search still reaches it ([DepthReveal]), and
 * About › App has the two controls: the depth itself, and a switch that puts a
 * "N more settings" row ([MoreSettingsRow]) at the end of each group that hid
 * something, for whoever wants to know where the rest went.
 *
 * The point is parity with the keyboards most people come from: at Simple
 * the app shows about what they offer. Nothing here changes what the
 * keyboard does.
 */

/** How far down the settings depth a row sits. Untagged rows are [BASIC]. */
internal enum class SettingLevel {
    /**
     * Shown at every depth: what other keyboards offer, a feature's master
     * switch, or the list a page exists for.
     */
    BASIC,

    /** Shown at Standard and Everything: a choice a non-technical person could still want. */
    STANDARD,

    /** Shown at Everything only: timings, thresholds, multipliers, per-variant overrides. */
    ADVANCED,
}

/** Whether a row of [level] is drawn at this depth. */
internal fun SettingsDepth.shows(level: SettingLevel): Boolean = when (this) {
    SettingsDepth.SIMPLE -> level == SettingLevel.BASIC
    SettingsDepth.STANDARD -> level != SettingLevel.ADVANCED
    // AUTO never reaches a screen: the nav host publishes the resolved depth.
    SettingsDepth.EVERYTHING, SettingsDepth.AUTO -> true
}

/**
 * The depth in force, whether hidden rows are to be pointed at, and the way
 * to change the depth, published for every screen.
 */
@Stable
internal class DepthControl(
    val depth: SettingsDepth,
    val hiddenRows: Boolean,
    val change: (SettingsDepth) -> Unit,
)

internal val LocalSettingsDepth = compositionLocalOf { DepthControl(SettingsDepth.EVERYTHING, hiddenRows = false) {} }

/** The depth that first shows a row of this level. */
internal fun SettingLevel.depthShowing(): SettingsDepth = when (this) {
    SettingLevel.BASIC -> SettingsDepth.SIMPLE
    SettingLevel.STANDARD -> SettingsDepth.STANDARD
    SettingLevel.ADVANCED -> SettingsDepth.EVERYTHING
}

/**
 * One screen's visit from a link, a search result or an addon's Use button.
 *
 * Whoever was sent to a setting has to find it there, whatever the depth, and
 * still find it after the flash, a turn of the phone or a trip to a page under
 * it. So the screen holds this for as long as it is on the back stack, and its
 * groups draw every row in place while [arrived]; [DepthRevealNotice] says why.
 * Leaving the screen is what ends it.
 */
@Stable
internal class DepthReveal(
    arrived: Boolean,
    opensFolds: Boolean,
    landedOn: SettingLevel? = null,
    highest: SettingLevel? = null,
    dismissed: Boolean = false,
) {
    var arrived by mutableStateOf(arrived)
        private set

    /** Named a row or entry, which may be inside a fold; a whole screen did not. */
    private var opensFolds by mutableStateOf(opensFolds)

    /** The level of the row the user was sent to, when the depth would have hidden it. */
    var landedOn by mutableStateOf(landedOn)

    /** The highest level among the rows drawn only because of this visit. */
    var highest by mutableStateOf(highest)
        private set

    var dismissed by mutableStateOf(dismissed)

    private val letGo = mutableStateListOf<String>()

    /** What keeping the rows takes: the row the user came for, or failing that all of them. */
    val needed: SettingLevel? get() = landedOn ?: highest

    fun arrive(opensFolds: Boolean) {
        arrived = true
        if (opensFolds) this.opensFolds = true
    }

    fun note(level: SettingLevel) {
        val top = highest
        if (top == null || level > top) highest = level
    }

    /** Whether the fold [id] is held open for this visit, until the user closes it themselves. */
    fun holdsOpen(id: String): Boolean = arrived && opensFolds && id !in letGo

    fun letGo(id: String) {
        if (id !in letGo) letGo += id
    }

    companion object {
        val Saver = listSaver<DepthReveal, Int>(
            save = {
                listOf(
                    if (it.arrived) 1 else 0,
                    if (it.opensFolds) 1 else 0,
                    it.landedOn?.ordinal ?: -1,
                    it.highest?.ordinal ?: -1,
                    if (it.dismissed) 1 else 0,
                )
            },
            restore = {
                DepthReveal(
                    arrived = it[0] == 1,
                    opensFolds = it[1] == 1,
                    landedOn = SettingLevel.entries.getOrNull(it[2]),
                    highest = SettingLevel.entries.getOrNull(it[3]),
                    dismissed = it[4] == 1,
                )
            },
        )
    }
}

/** The visit of the screen being drawn; null outside a screen. */
internal val LocalDepthReveal = compositionLocalOf<DepthReveal?> { null }

/** The level of the row being drawn when it is on screen only because of a [DepthReveal]. */
internal val LocalRevealedLevel = compositionLocalOf<SettingLevel?> { null }

/**
 * The [DepthReveal] of the screen being composed. A screen opening takes the
 * request that sent the user to it. One already open takes a request only while
 * it is the screen in front: a row on it can point at another row on it, and
 * the screen a request is navigating away from is no longer resumed by then.
 */
@Composable
internal fun rememberDepthReveal(): DepthReveal {
    val reveal = rememberSaveable(saver = DepthReveal.Saver) {
        val arrived = SettingsHighlight.takeArrival()
        DepthReveal(arrived, opensFolds = arrived && SettingsHighlight.pending)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reveal, lifecycle) {
        snapshotFlow { SettingsHighlight.serial }.drop(1).collect {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && SettingsHighlight.takeArrival()) {
                reveal.arrive(opensFolds = SettingsHighlight.pending)
            }
        }
    }
    return reveal
}

/**
 * The card under the bar while a screen shows rows the depth would hide: why
 * they are there, and the way to keep them. Draws nothing unless something on
 * the screen really is above the depth.
 */
// Only for reading the shared-transition local as the reduced-motion switch.
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun DepthRevealNotice(reveal: DepthReveal) {
    val control = LocalSettingsDepth.current
    val needed = reveal.needed
    val showing = reveal.arrived && !reveal.dismissed && !control.hiddenRows &&
        needed != null && !control.depth.shows(needed)
    // What the card said last, so it does not change its words on the way out
    // when its own button has just moved the depth.
    val words = remember { arrayOf(control.depth, SettingsDepth.STANDARD) }
    if (showing && needed != null) {
        words[0] = control.depth
        words[1] = needed.depthShowing()
    }
    val onRow = reveal.landedOn != null
    val card: @Composable () -> Unit = {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.Tune, contentDescription = null)
                    Text(
                        stringResource(
                            if (onRow) R.string.settings_depth_reveal_row_title else R.string.settings_depth_reveal_page_title,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    if (onRow) {
                        stringResource(R.string.settings_depth_reveal_row_body, depthName(words[1]), depthName(words[0]))
                    } else {
                        stringResource(R.string.settings_depth_reveal_page_body, depthName(words[0]))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = { reveal.dismissed = true }) {
                        Text(stringResource(R.string.settings_depth_reveal_dismiss))
                    }
                    FilledTonalButton(onClick = { control.change(words[1]) }) {
                        Text(stringResource(R.string.settings_depth_reveal_keep, depthName(words[1])))
                    }
                }
            }
        }
    }
    // Reduced motion has no still version of a reveal: the card is or is not there.
    if (LocalSharedTransition.current == null) {
        if (showing) card()
    } else {
        AnimatedVisibility(
            visible = showing,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            card()
        }
    }
}

/** The depth's name, as the row and the sheet say it. */
@Composable
internal fun depthName(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything
    },
)

/** One line on what the depth shows. */
@Composable
internal fun depthDescription(depth: SettingsDepth): String = stringResource(
    when (depth) {
        SettingsDepth.SIMPLE -> R.string.settings_depth_simple_desc
        SettingsDepth.STANDARD, SettingsDepth.AUTO -> R.string.settings_depth_standard_desc
        SettingsDepth.EVERYTHING -> R.string.settings_depth_everything_desc
    },
)

/** The three depths a user can pick, named. */
@Composable
internal fun depthOptions(): List<Pair<SettingsDepth, String>> = listOf(
    SettingsDepth.SIMPLE to depthName(SettingsDepth.SIMPLE),
    SettingsDepth.STANDARD to depthName(SettingsDepth.STANDARD),
    SettingsDepth.EVERYTHING to depthName(SettingsDepth.EVERYTHING),
)

/**
 * The row at the end of a group that holds the rows above the depth, drawn
 * only while About › App › "Show where settings are hidden" is on. One row
 * rather than a heading: it sits in the group's own card stack, and the rows
 * it opens grow in under it, so the group stays one object.
 */
@Composable
internal fun MoreSettingsRow(count: Int, open: Boolean, onToggle: () -> Unit) {
    WmRow(
        title = pluralStringResource(R.plurals.settings_depth_more_rows, count, count),
        icon = Icons.Outlined.Tune,
        trailing = { ExpandChevron(open) },
        onClick = onToggle,
    )
}
