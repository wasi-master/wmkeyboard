package com.wasimaster.wmkeyboard.app

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.scaleToBounds
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.ChevronRight
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.icons.IconSlots
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.ime.ui.SlotIcon

/*
 * The path strip: "Home › Appearance › Themes" under the heading of every
 * screen below the settings home, with each step behind the current one
 * tappable.
 *
 * Android has no breadcrumb of its own — Material has no such component and the
 * system Settings app does without one — because a back arrow says enough for
 * two levels. This app has sixty-odd destinations and paths four deep
 * ("Home › Tools › Clipboard › Phone number formats"), which is where a back
 * arrow stops answering "where am I" and "take me two steps up". It is drawn
 * from the first step down as well, so the strip is a fixture of every screen
 * rather than a thing that appears three levels in.
 *
 * The path comes from the navigation back stack rather than from a table of
 * parents, for two reasons. A table lies whenever a screen has two ways in —
 * Fonts hangs off both Appearance and Accessibility — and it has to be kept in
 * step with the graph by hand. The back stack cannot disagree with itself: a
 * step in the strip is an entry that is really there, and pressing it pops to
 * it, so the strip and the back arrow can never tell different stories.
 */

/**
 * How far from the home screen a path has to be before the strip is drawn:
 * two steps, so the strip starts at the third level. A second-level page
 * already says where it is — its heading is the row on Home that opened it,
 * and the back arrow is the whole way back — so a strip reading "Home" under
 * a heading that says Typing spent a strip's height saying nothing. From the
 * third level the path has a middle, which is what the strip is for. The
 * home list has no path at all.
 */
internal const val MinCrumbDepth = 2

/** The strip's own height. Chrome, so it is shorter than a settings row. */
private val CrumbBarHeight = 48.dp

/** How wide one step's name may grow before it ellipsises. A theme can be named anything. */
private val CrumbMaxWidth = 160.dp

/** A step's glyph, beside its name. Smaller than a row's: this is a label, not a tile. */
private val CrumbIconSize = 16.dp

/** Air between a step's glyph and its name. */
private val CrumbIconGap = 6.dp

/** Air between two pills, on each side of the chevron. */
private val CrumbGap = 2.dp

/**
 * One step of the path: a screen the user passed through, and the back stack
 * entry that is still holding it open.
 *
 * The entry's id rather than its route, because a route is not unique on the
 * stack — two tool pages are both `tool/{toolName}` — and the strip has to be
 * able to tell one from the other. The route rides along as well, for the
 * step's glyph: it is what the icon table is keyed by.
 */
@Immutable
internal data class SettingsCrumb(
    val entryId: String,
    val title: String,
    val route: String? = null,
    /**
     * Whether the step stands for a screen that is not on the back stack.
     *
     * A search result opens a screen from nowhere: the stack behind it holds
     * the results list and nothing else, so the path derived from it is a lie
     * by omission — "Home › Personal dictionary" for a screen that really
     * lives three levels down (#111). The screens it skipped are seeded onto
     * the trail so the strip tells the truth about where the user has landed,
     * and a seeded step is pressed by *going* to its route rather than by
     * popping back to an entry that was never opened.
     */
    val seeded: Boolean = false,
)

/**
 * The path walked to reach the screen on top of the stack.
 *
 * One instance for the whole settings graph, saved and restored with it: after
 * a rotation or a process death only the top screen is composed again, so a
 * trail rebuilt from composition alone would come back one step long.
 * Navigation restores its entries under the same ids, which is what lets a
 * restored trail be matched back up to a restored stack.
 *
 * Knows nothing about `NavController`: [bind] hands it the two things it needs
 * from one, which keeps the whole path-keeping side of this testable off a
 * device.
 */
@Stable
internal class SettingsCrumbTrail {

    private val steps = mutableStateListOf<SettingsCrumb>()

    private var topEntryId: () -> String? = { null }
    private var pop: () -> Boolean = { false }
    private var openRoute: (String) -> Unit = { }

    /** The whole path, outermost first. */
    internal val path: List<SettingsCrumb> get() = steps

    /**
     * Where the trail reaches the navigator. Left unbound by the tests.
     *
     * [open] is only ever asked for a seeded step's route, and it is expected
     * to land on that screen with nothing of the jump left above it — the
     * step names an ancestor, so walking to it must not leave the screen it
     * was pressed from on the stack.
     */
    fun bind(topEntryId: () -> String?, pop: () -> Boolean, open: (String) -> Unit = { }) {
        this.topEntryId = topEntryId
        this.pop = pop
        this.openRoute = open
    }

    /**
     * Records that [entryId] is a screen being drawn, under the name [title].
     *
     * An entry the trail has not seen is a screen being opened, and goes on the
     * end. An entry it already holds is either a screen being returned to, in
     * which case everything that was above it has been popped and goes too, or
     * a screen that changed its own heading while something else sat on top of
     * it. The two are told apart by the live stack rather than assumed, because
     * trimming the second one would throw away the screen the user is looking
     * at.
     */
    fun enter(entryId: String, title: String, route: String? = null) {
        val at = steps.indexOfFirst { it.entryId == entryId }
        if (at < 0) {
            steps.add(SettingsCrumb(entryId, title, route))
            return
        }
        if (steps[at].title != title) steps[at] = SettingsCrumb(entryId, title, route)
        if (topEntryId() != entryId) return
        while (steps.size > at + 1) steps.removeAt(steps.lastIndex)
    }

    /**
     * Drops a screen that left the stack without anything below it being
     * entered — what `popUpTo(inclusive = true)` does when it replaces a screen
     * with the one it opened. Called from the entry's own death, so the trail
     * never keeps a step that cannot be gone back to.
     */
    fun forget(entryId: String) {
        steps.removeAll { it.entryId == entryId }
    }

    /**
     * Puts [ancestors] on the end of the trail as steps that stand for screens
     * nobody opened.
     *
     * Called just before a jump — today only the one a search result makes —
     * so that the screen about to be entered lands on a path that names where
     * it actually lives instead of on the one screen the jump was made from.
     * Any earlier seeding is dropped first: a second result is reached from
     * the same results list, not from the last result's path.
     *
     * The steps are appended to whatever real trail is standing, which after a
     * jump from the results list is the home screen alone. Nothing is seeded
     * on top of a screen the user walked to, because a jump always starts from
     * the search screen, which keeps no step of its own.
     */
    fun seed(ancestors: List<SettingsCrumb>) {
        clearSeeded()
        steps.addAll(ancestors.map { it.copy(seeded = true) })
    }

    /** Drops every seeded step. A walked path never wants one. */
    fun clearSeeded() {
        steps.removeAll { it.seeded }
    }

    /**
     * Whether the trail already knows [entryId].
     *
     * Read once by a screen as it composes, before its own [enter] has run,
     * and that answer says how the screen was reached: a screen the trail has
     * never seen is being opened, and a screen it is already holding is being
     * returned to. Which of the two it is decides which step the accent is
     * moving between, and the answer has to be had on the first frame — a
     * frame later both cases look identical.
     */
    fun holds(entryId: String): Boolean = steps.any { it.entryId == entryId }

    /**
     * The steps above the home screen and below [entryId] — what the screen
     * holding that entry draws as its path.
     *
     * An entry that is not in the trail is a screen on its way in (its
     * [enter] runs after the frame that first draws it) or on its way out (the
     * screen below it has already trimmed the trail back). Both want the whole
     * trail as it stands, which is why that is what they get: the strip is
     * right on the first frame, and does not blink during either animation.
     */
    fun ancestorsOf(entryId: String): List<SettingsCrumb> {
        val at = steps.indexOfFirst { it.entryId == entryId }
        return if (at < 0) steps.toList() else steps.subList(0, at).toList()
    }

    /**
     * How many screens sit above [entryId] — the number of pops that reaching
     * it costs, and the bound [popTo] will not go past.
     */
    fun popsTo(entryId: String): Int {
        val at = steps.indexOfFirst { it.entryId == entryId }
        return if (at < 0) 0 else steps.lastIndex - at
    }

    /**
     * Goes back to the screen [entryId] names.
     *
     * Popped one at a time, up to the number of steps the trail says are in the
     * way, and no further. A destination that never registered a step — a
     * screen that does not use the house frame — would leave the count short,
     * and a short count lands on a screen the user did pass through. Counting
     * long could throw away the whole stack, so the count is never trusted to
     * be long.
     */
    fun popTo(entryId: String) {
        var left = popsTo(entryId)
        while (left-- > 0 && topEntryId() != entryId) {
            if (!pop()) return
        }
    }

    /**
     * Goes to the screen [crumb] names, whichever kind of step it is.
     *
     * A real step is on the stack and is popped back to. A seeded one never
     * was, so it is navigated to instead, and the seeding is thrown away
     * first: the user is leaving the jump behind and walking the tree from
     * here, which is a path the stack can keep for itself. A seeded step with
     * no route of its own is a screen the index cannot address, and it is
     * drawn as a plain label rather than pressed.
     */
    fun goTo(crumb: SettingsCrumb) {
        if (!crumb.seeded) {
            popTo(crumb.entryId)
            return
        }
        val route = crumb.route ?: return
        clearSeeded()
        openRoute(route)
    }

    /** Restores a saved path. Only [Saver] calls this. */
    private fun restore(saved: List<SettingsCrumb>) {
        steps.clear()
        steps.addAll(saved)
    }

    companion object {
        /**
         * Saved as a flat list of id, title, route, seeded, and again: a
         * `listSaver` writes its entries into a Bundle one by one, and a String
         * is something every Bundle can hold. A step without a route saves an
         * empty one and a step nobody opened saves `"1"`, so every step is
         * exactly four entries long.
         */
        val Saver: Saver<SettingsCrumbTrail, Any> = listSaver(
            save = { trail ->
                trail.steps.flatMap {
                    listOf(it.entryId, it.title, it.route.orEmpty(), if (it.seeded) "1" else "")
                }
            },
            restore = { flat ->
                SettingsCrumbTrail().apply {
                    restore(
                        flat.chunked(SavedStepWidth)
                            .filter { it.size == SavedStepWidth }
                            .map {
                                SettingsCrumb(
                                    it[0], it[1], it[2].ifEmpty { null }, seeded = it[3].isNotEmpty(),
                                )
                            },
                    )
                }
            },
        )

        /** How many saved entries one step takes: id, title, route, seeded. */
        private const val SavedStepWidth = 4
    }
}

/**
 * The paths each screen has been reached by in this run of the app (#979).
 *
 * A screen with two ways in shows a different path down each, which is the
 * strip telling the truth about the back stack, and it is also why a screen
 * reached the second way looks like a new one: the Emoji panel under
 * "Layout & size › Key layouts" reads as nowhere the user has been, though
 * "Home › Emoji" opened it a minute ago. So the strip remembers each path it
 * has drawn, and a screen reached by another one names the earlier ones
 * under it.
 *
 * Remembered rather than read off a table of parents, for the reason the
 * strip itself is: no table has to be kept in step with the graph, and every
 * path named was really walked. Kept for the process, which is the length of
 * one look through the settings; a later run starts with nothing to name.
 */
internal object SettingsDoors {
    private val walked = mutableStateMapOf<String, List<List<String>>>()

    /** Records [path], titles outermost first, as a way into the screen [key] names. */
    fun record(key: String, path: List<String>) {
        val known = walked[key].orEmpty()
        if (path !in known) walked[key] = (known + listOf(path)).takeLast(MaxDoors)
    }

    /** The paths into [key] walked before, other than [path]. */
    fun othersThan(key: String, path: List<String>): List<List<String>> =
        walked[key].orEmpty().filter { it != path }

    /** How many paths one screen keeps; past it, the oldest goes. */
    private const val MaxDoors = 4
}

/**
 * The trail for the settings graph, published by it. Null wherever a house
 * screen is drawn outside the graph, and then no screen draws a path strip.
 */
internal val LocalSettingsCrumbTrail = compositionLocalOf<SettingsCrumbTrail?> { null }

/**
 * The back stack entry the destination being composed was opened for, or null
 * outside the settings graph. Navigation publishes it as the owner of
 * everything the destination draws, so a screen can find its own entry without
 * being handed one.
 */
@Composable
internal fun currentCrumbEntry(): NavBackStackEntry? =
    LocalViewModelStoreOwner.current as? NavBackStackEntry

/**
 * Puts the screen being composed into the path under the name [title].
 *
 * Every destination built on [WmScreen] does this for itself. A screen that
 * builds its own scaffold has to call it, or the path will not know it was ever
 * open: the strip on the screen above would name a step it cannot reach, and
 * pressing that step would stop one screen short of it.
 *
 * Recorded after composition rather than during it: the trail is snapshot
 * state, and a screen that wrote to it while composing would be writing to
 * state it also reads. Nothing is lost by the delay, because a screen's own
 * first frame reads the trail as it stands, which is the trail without itself
 * on the end.
 *
 * The observer outlives this composition on purpose, and is not removed. A
 * screen leaves composition as soon as the next one opens, and stays on the
 * back stack as an ancestor — being off-screen is what makes it one. What it
 * must not survive is its entry being *destroyed* without the screen below it
 * being entered, which is what `popUpTo(inclusive = true)` does; the entry's
 * own registry drops the observer at that point, so there is nothing to leak.
 */
@Composable
internal fun RegisterSettingsCrumb(title: String, route: String? = null) {
    val trail = LocalSettingsCrumbTrail.current ?: return
    val entry = currentCrumbEntry() ?: return
    LaunchedEffect(trail, entry, title, route) { trail.enter(entry.id, title, route) }
    DisposableEffect(trail, entry) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) trail.forget(entry.id)
        }
        entry.lifecycle.addObserver(observer)
        onDispose { }
    }
}

/**
 * Whether the screen being composed was opened, as opposed to returned to —
 * [SettingsBreadcrumbBar]'s `opened`.
 *
 * Asked in the screen's own composition, where it is answered before
 * [RegisterSettingsCrumb]'s effect can answer it by adding the step: a
 * `remember` runs as the composition does, and an effect only once it has been
 * applied. A screen not on the trail yet is being opened.
 */
@Composable
internal fun rememberCrumbOpened(): Boolean {
    val trail = LocalSettingsCrumbTrail.current ?: return true
    val entry = currentCrumbEntry() ?: return true
    return remember(trail, entry) { !trail.holds(entry.id) }
}

/**
 * The path strip for the screen holding [entryId], or nothing at all when that
 * screen is the home list, which has no path to draw.
 *
 * Each step is a pill with the screen's glyph and name, and the screen being
 * drawn closes the path as a pill in [accent] — the section's own colour, the
 * one the heading's tile and the collapsed bar wear — so the strip reads as
 * "you are here" and not only as "you came from there". The steps behind it
 * are the tappable ones; the last is where the user already is.
 *
 * [tint] is the colour the bar above wears once it has collapsed. At the top of
 * a page that leaves the strip a shade stronger than the heading over it, so it
 * reads as a band of chrome under the page's own top; scrolled, the bar arrives
 * at the same colour and the two become one strip.
 *
 * The trail is only read here, not in the frame around it, so a navigation
 * anywhere in the app recomposes this row and nothing else.
 *
 * [opened] is which way this screen was reached: opened, and the accent is
 * moving off the step behind it, or returned to, and the accent is moving back
 * onto this one. It is [rememberCrumbOpened]'s answer, taken before the
 * screen's own step reaches the trail. The strip used to latch it itself, on
 * its own first composition — but it sits in the Scaffold's top bar, which is
 * composed in the measure pass, and whether the step-adding effect had already
 * run by then came down to frame timing. When it had, a push read as a return,
 * and the accent snapped off the step behind instead of travelling off it.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun SettingsBreadcrumbBar(
    trail: SettingsCrumbTrail,
    entryId: String,
    currentTitle: String,
    currentRoute: String?,
    opened: Boolean,
    onCurrent: () -> Unit,
    accent: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val crumbs = trail.ancestorsOf(entryId)
    if (crumbs.size < MinCrumbDepth) return
    // Keyed by title as well as route: a route that takes an argument, a tool's
    // page or a mode's editor, is one route for many screens.
    val doorKey = "${currentRoute.orEmpty()}#$currentTitle"
    val path = crumbs.map { it.title }
    // Only paths really walked count as doors (#1111). The New settings walk
    // and a link seed the path they land on, and each one recorded made the
    // line name every screen the walk had passed through.
    val walked = crumbs.none { it.seeded }
    val otherDoors = if (LocalSettingsDepth.current.alsoUnder && walked) {
        SettingsDoors.othersThan(doorKey, path)
    } else {
        emptyList()
    }
    LaunchedEffect(doorKey, path, walked) { if (walked) SettingsDoors.record(doorKey, path) }
    // The step that wore the accent a moment ago. Only a screen that was just
    // opened has one on its own strip — the step it was opened from, which is
    // the last of its ancestors. A screen being returned to left its own
    // accent behind on a screen that is on its way out.
    val wasHere = if (opened) crumbs.lastOrNull()?.entryId else null
    // The near end of the path is the useful one, and the end a long path
    // pushes off the screen — so the strip scrolls from its end: reversed, a
    // scroll of zero is the end of the row. That holds from the first measure.
    // It used to be a scroll to the end from an effect, which on a screen's
    // first frame found the row unmeasured and the range zero, scrolled
    // nowhere, and jumped a frame later: every pill's landing place moved
    // after the flights had taken off, and they all retargeted mid-air.
    // A short path still sits at the start, because the row is only as wide
    // as its pills and the box lays it out from the start.
    val scroll = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CrumbBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            // The band is its own node rather than a background on the box,
            // because it is the one part of the strip that is the same object on
            // every screen: given a key of its own it stays put while the screens
            // slide past under it, and crosses from one section's tint to the
            // next's instead of being wiped across by the incoming page.
            Box(Modifier.fillMaxSize().crumbBand().background(tint))
            Row(
                modifier = Modifier
                    .horizontalScroll(scroll, reverseScrolling = true)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEach { crumb ->
                    Crumb(
                        crumb = crumb,
                        here = crumbAccent(from = if (crumb.entryId == wasHere) 1f else 0f, to = 0f),
                        accent = accent,
                        onOpen = if (crumb.seeded && crumb.route == null) null
                        else ({ trail.goTo(crumb) }),
                    )
                    CrumbSeparator(crumb.entryId)
                }
                CurrentCrumb(
                    entryId = entryId,
                    title = currentTitle,
                    route = currentRoute,
                    // Always arrives: a step that was just opened had no accent on
                    // the screen before, and a step being returned to left its own
                    // behind. Either way the accent comes onto it here.
                    here = crumbAccent(from = 0f, to = 1f),
                    accent = accent,
                    onTop = onCurrent,
                )
            }
        }
        if (otherDoors.isNotEmpty()) AlsoUnder(otherDoors, tint)
    }
}

/**
 * The other paths into this screen walked before ([SettingsDoors]), one line
 * under the strip on the strip's own tint, so it reads as part of it.
 */
@Composable
private fun AlsoUnder(doors: List<List<String>>, tint: Color) {
    Text(
        text = stringResource(
            R.string.shell_breadcrumb_also_under,
            doors.joinToString(", ") { it.joinToString(CrumbPathSeparator) },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Between two steps of a path written out as text, the way a search result writes one. */
private const val CrumbPathSeparator = " › "

// ---- flight ----

/*
 * A step is the same step on both sides of a navigation, so it is drawn as one
 * object that moves rather than as two that slide past each other. Every part
 * of the strip — the band, each pill, each chevron — carries a key, and the
 * two screens' copies of a key are the two ends of one flight: the pill walks
 * to its new place in the path while the tint that marked it as "you are here"
 * moves off it and onto the step that arrived.
 *
 * A pill is flown, not cross-faded, and that is deliberate. The two ends of a
 * step differ only in colour, so fading one into the other spends the whole
 * navigation drawing two pills on top of each other at partial alpha. Their
 * fills are translucent, so the pair reads as one washed-out ghost rather than
 * as a colour changing, and the band underneath goes visibly dark for the same
 * reason. Instead only the arriving end is drawn, at full strength, and the
 * colour is changed where it belongs: on that one pill, by [crumbAccent], over
 * a curve this file chooses.
 *
 * What has no counterpart is what is new: the pill for the screen being opened
 * exists only on the arriving side, so no flight is found for it and it rides
 * in with its screen, fading up as it comes. That case has to be drawn in the
 * overlay all the same — the band above it is flying, and anything left in the
 * page would be painted over by it — which is what the second modifier is for.
 * It costs nothing while the keys match, because the lambda that arms it is
 * read at draw time.
 */

/** The band's key: one strip, so one name, shared by every screen that wears it. */
private const val CrumbBandKey = "settings-crumb-band"

/** A pill's key: the entry it stands for, which is unique on the stack. */
private fun crumbKey(entryId: String) = "settings-crumb/$entryId"

/** The chevron's key: the step in front of it, under a name of its own. */
private fun crumbSeparatorKey(entryId: String) = "settings-crumb-sep/$entryId"

/** Under the pills: the band never crosses over one of its own steps. */
private const val CrumbBandZ = 0f

/** A step in flight, over the band and over the page it left. */
private const val CrumbPillZ = 1f

/** A step with nowhere to fly from, over everything: it is the newest thing on the strip. */
private const val CrumbArrivalZ = 2f

/**
 * The strip's clock: the length the screens move over, on the curve the app's
 * other flights use.
 *
 * Not the screens' own curve. `NavTransitionEasing` is a push — it throws the
 * page most of the way across in the first tenth of its time and eases the
 * rest — and a colour put on that curve has finished changing before the eye
 * has found it, which is what made the accent look like it was snapping rather
 * than moving. A flight between two places is the standard curve, and a step
 * walking up the path is a flight.
 */
private val CrumbFlightSpec = tween<Float>(NavTransitionMs, easing = FastOutSlowInEasing)

@OptIn(ExperimentalSharedTransitionApi::class)
private val CrumbBounds = BoundsTransform { _, _ ->
    tween(durationMillis = NavTransitionMs, easing = FastOutSlowInEasing)
}

/**
 * How far this step is towards wearing the accent, from [from] as the screen
 * was reached to [to] once it is settled: 1 is "you are here", 0 is a step
 * behind you.
 *
 * Driven by the screen's own entrance rather than by a `sharedBounds`
 * cross-fade, so the accent moves over a curve the eye can follow and neither
 * end of it is ever drawn at partial alpha. Off the graph, and under reduced
 * motion, it is simply [to] and nothing moves.
 */
@Composable
private fun crumbAccent(from: Float, to: Float): Float {
    if (from == to) return to
    val anim = LocalNavAnimatedScope.current ?: return to
    val here by anim.transition.animateFloat(
        transitionSpec = { CrumbFlightSpec },
        label = "crumbAccent",
    ) { state -> if (state == EnterExitState.Visible) to else from }
    return here
}

/**
 * Holds the band still across a navigation, and crosses its tint from the
 * section being left to the one being opened.
 *
 * Both screens draw a band of the same size in the same place, so there is no
 * distance for the flight to cover and it reads as a band that never moved.
 * Remeasured rather than scaled for the one case where there is a distance:
 * leaving a screen whose bar had been scrolled shut for one whose bar is open
 * moves the strip down the window, and a stretched band would show it.
 *
 * Only the arriving band fades; the one being left stays at full strength
 * underneath until it is dropped. Two opaque bands both at part alpha do not
 * add back up to one — the page shows through the pair of them — and that was
 * the whole band dimming by a seventh for the length of every navigation.
 * With the floor left opaque the pair is a plain crossing of one colour into
 * the next, which is all this was ever meant to be.
 *
 * A no-op on the home list, which draws no strip at all, and under reduced
 * motion, where the band simply travels with its screen.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.crumbBand(): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        this@crumbBand.sharedBounds(
            rememberSharedContentState(CrumbBandKey),
            anim,
            enter = fadeIn(CrumbFlightSpec),
            exit = ExitTransition.None,
            boundsTransform = CrumbBounds,
            resizeMode = RemeasureToBounds,
            zIndexInOverlay = CrumbBandZ,
        )
    }
}

/**
 * Flies this piece of the strip to wherever [key] names it on the screen being
 * opened. Both screens put a copy on that flight and only the arriving one is
 * drawn, so the pill is solid the whole way across and its colours are
 * whatever [crumbAccent] has made them.
 *
 * The hiding is done here rather than by asking for `sharedElement`, which
 * draws the arriving end only and would be exactly this. It also refuses to
 * draw a *departing* end that has no match at all — and on the way back that
 * is the pill for the screen being left, which then blinks out of the strip
 * on the first frame of the pop instead of sliding away with its page.
 *
 * A piece with no match is a step that has only just appeared, or, going back,
 * one that is only just leaving. Nothing flies it, so it moves with its own
 * screen; it is lifted into the overlay for the crossing, because the band is
 * up there and would otherwise paint over it, and faded so that it arrives and
 * leaves rather than snapping.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.crumbFlight(key: String): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    return with(shared) {
        val state = rememberSharedContentState(key)
        // Read inside the layer rather than by the composition, so a step in
        // the air costs one draw a frame and not one recomposition.
        val arrival = anim.transition.animateFloat(
            transitionSpec = { CrumbFlightSpec },
            label = "crumb",
        ) { if (it == EnterExitState.Visible) 1f else 0f }
        this@crumbFlight
            .sharedBounds(
                state,
                anim,
                // Neither end is faded by the flight. A pill's fill is
                // translucent, so two of them at part alpha over the band read
                // as one washed-out ghost rather than as a colour changing —
                // which is what the accent crossing over used to look like.
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                boundsTransform = CrumbBounds,
                resizeMode = scaleToBounds(ContentScale.FillWidth, Alignment.CenterStart),
                zIndexInOverlay = CrumbPillZ,
            )
            .renderInSharedTransitionScopeOverlay(
                renderInOverlay = { isTransitionActive && !state.isMatchFound },
                zIndexInOverlay = CrumbArrivalZ,
            )
            .graphicsLayer {
                alpha = when {
                    // Riding out or in with its own screen.
                    !state.isMatchFound -> arrival.value
                    // Both ends are placed at the same flying bounds, so the
                    // one being left is the redundant copy. Dropping it is
                    // what keeps the pill from drawing over itself.
                    anim.transition.targetState == EnterExitState.PostExit -> 0f
                    else -> 1f
                }
            }
    }
}

/**
 * The glyph a step wears, or nothing for a screen that has none.
 *
 * A tool's page draws the tool's own glyph — the icon pack's, if the user
 * installed one — which is why this is a composable rather than a vector
 * lookup: the pack is resolved where the icon is drawn. Every other screen
 * takes its glyph from the same table the heading does, and the home list,
 * which that table has no entry for, gets the house.
 */
@Composable
private fun CrumbGlyph(route: String?, tint: Color) {
    val size = Modifier.size(CrumbIconSize)
    val tool = route?.removePrefix(ToolRoutePrefix)?.takeIf { it != route }
        ?.let { name -> runCatching { ToolbarTool.valueOf(name) }.getOrNull() }
    if (tool != null) {
        SlotIcon(IconSlots.forTool(tool), contentDescription = null, modifier = size, tint = tint)
        Spacer(Modifier.width(CrumbIconGap))
        return
    }
    val vector = when (route) {
        null -> null
        HomeRoute -> Icons.Outlined.Home
        else -> SettingsRouteIcons[route]
    } ?: return
    Icon(vector, contentDescription = null, modifier = size, tint = tint)
    Spacer(Modifier.width(CrumbIconGap))
}

/** The settings home's route, which the icon table has no entry for. */
internal const val HomeRoute = "home"

/** What every tool page's route starts with; the rest is the tool's name. */
private const val ToolRoutePrefix = "tool/"

/**
 * The chevron between two steps. Drawn, not typed: a glyph sits level with the
 * pills. Keyed by the step in front of it so it flies with that step rather
 * than being left behind by it — the chevron a navigation adds is the one
 * behind the step that was current, and it arrives with the new pill.
 */
@Composable
private fun CrumbSeparator(entryId: String) {
    Icon(
        Icons.Outlined.ChevronRight,
        contentDescription = null,
        modifier = Modifier
            .crumbFlight(crumbSeparatorKey(entryId))
            .padding(horizontal = CrumbGap)
            .size(CrumbIconSize),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    )
}

/**
 * One tappable step: a quiet pill, a shade off the strip it sits on so it
 * reads as a thing to press without shouting over the heading above.
 */
@Composable
private fun Crumb(crumb: SettingsCrumb, here: Float, accent: Color, onOpen: (() -> Unit)?) {
    CrumbPill(
        title = crumb.title,
        route = crumb.route,
        here = here,
        accent = accent,
        flight = crumbKey(crumb.entryId),
        // A seeded step the index cannot address goes nowhere, so it is a
        // label and not a button: it still says where the screen sits.
        modifier = if (onOpen == null) Modifier else Modifier.clickable(
            onClickLabel = stringResource(R.string.shell_breadcrumb_open_desc, crumb.title),
            onClick = onOpen,
        ),
    )
}

/**
 * The screen being drawn, closing the path in its section's colour. Pressing
 * it goes nowhere — the user is already here — so it does the one useful thing
 * left: takes the screen back to its top.
 */
@Composable
private fun CurrentCrumb(
    entryId: String,
    title: String,
    route: String?,
    here: Float,
    accent: Color,
    onTop: () -> Unit,
) {
    CrumbPill(
        title = title,
        route = route,
        here = here,
        accent = accent,
        // The same key the next screen will draw this step under, once it is a
        // step behind: that is what carries the accent off it as it goes.
        flight = crumbKey(entryId),
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.shell_breadcrumb_top_desc),
            onClick = onTop,
        ),
    )
}

/**
 * The pill both kinds of step are drawn as. [modifier] goes inside the clip, so
 * a ripple stays round; [flight] goes outside everything, because what crosses
 * between two screens is the whole pill — its fill and its outline as much as
 * its name.
 *
 * [here] is how far this step is towards being the one you are on: 0 is the
 * quiet pill, a shade off the strip it sits on so it reads as a thing to press
 * without shouting over the heading above; 1 closes the path in the section's
 * own colour, the one the heading's tile and the collapsed bar wear. The three
 * colours are read from the one number so the accent arrives and leaves as a
 * single movement rather than as three that could disagree.
 */
@Composable
private fun CrumbPill(
    title: String,
    route: String?,
    here: Float,
    accent: Color,
    flight: String,
    modifier: Modifier = Modifier,
) {
    val container = lerp(
        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        accent.copy(alpha = 0.14f),
        here,
    )
    val outline = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        accent.copy(alpha = 0.55f),
        here,
    )
    val content = lerp(MaterialTheme.colorScheme.onSurfaceVariant, accent, here)
    Row(
        modifier = Modifier
            .crumbFlight(flight)
            .clip(CircleShape)
            .background(container)
            .border(1.dp, outline, CircleShape)
            .then(modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrumbGlyph(route, content)
        Text(
            title,
            modifier = Modifier.widthIn(max = CrumbMaxWidth),
            style = MaterialTheme.typography.labelLarge,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
