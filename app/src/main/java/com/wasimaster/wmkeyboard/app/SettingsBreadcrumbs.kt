package com.wasimaster.wmkeyboard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * one step, so every screen but the home list wears one. The home list has
 * no path at all, and a strip with only its own pill on it would say nothing.
 */
private const val MinCrumbDepth = 1

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

    /** The whole path, outermost first. */
    internal val path: List<SettingsCrumb> get() = steps

    /** Where the trail reaches the navigator. Left unbound by the tests. */
    fun bind(topEntryId: () -> String?, pop: () -> Boolean) {
        this.topEntryId = topEntryId
        this.pop = pop
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

    /** Restores a saved path. Only [Saver] calls this. */
    private fun restore(saved: List<SettingsCrumb>) {
        steps.clear()
        steps.addAll(saved)
    }

    companion object {
        /**
         * Saved as a flat list of id, title, route, id, title, route: a
         * `listSaver` writes its entries into a Bundle one by one, and a String
         * is something every Bundle can hold. A step without a route saves an
         * empty one, so every step is exactly three entries long.
         */
        val Saver: Saver<SettingsCrumbTrail, Any> = listSaver(
            save = { trail ->
                trail.steps.flatMap { listOf(it.entryId, it.title, it.route.orEmpty()) }
            },
            restore = { flat ->
                SettingsCrumbTrail().apply {
                    restore(
                        flat.chunked(SavedStepWidth)
                            .filter { it.size == SavedStepWidth }
                            .map { SettingsCrumb(it[0], it[1], it[2].ifEmpty { null }) },
                    )
                }
            },
        )

        /** How many saved entries one step takes: id, title, route. */
        private const val SavedStepWidth = 3
    }
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
 */
@Composable
internal fun SettingsBreadcrumbBar(
    trail: SettingsCrumbTrail,
    entryId: String,
    currentTitle: String,
    currentRoute: String?,
    onCurrent: () -> Unit,
    accent: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val crumbs = trail.ancestorsOf(entryId)
    if (crumbs.size < MinCrumbDepth) return
    val scroll = rememberScrollState()
    // The near end of the path is the useful one, and the end a long path
    // pushes off the screen. Scrolled to whenever the path grows — and keyed
    // on the range as well, because on a screen's first frame the row has not
    // been measured yet and the range is still zero; it settles a frame later.
    LaunchedEffect(crumbs.size, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CrumbBarHeight)
            .background(tint),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEach { crumb ->
                Crumb(crumb) { trail.popTo(crumb.entryId) }
                CrumbSeparator()
            }
            CurrentCrumb(currentTitle, currentRoute, accent, onCurrent)
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
private const val HomeRoute = "home"

/** What every tool page's route starts with; the rest is the tool's name. */
private const val ToolRoutePrefix = "tool/"

/** The chevron between two steps. Drawn, not typed: a glyph sits level with the pills. */
@Composable
private fun CrumbSeparator() {
    Icon(
        Icons.Outlined.ChevronRight,
        contentDescription = null,
        modifier = Modifier
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
private fun Crumb(crumb: SettingsCrumb, onOpen: () -> Unit) {
    CrumbPill(
        title = crumb.title,
        route = crumb.route,
        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        content = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(
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
private fun CurrentCrumb(title: String, route: String?, accent: Color, onTop: () -> Unit) {
    CrumbPill(
        title = title,
        route = route,
        container = accent.copy(alpha = 0.14f),
        outline = accent.copy(alpha = 0.55f),
        content = accent,
        modifier = Modifier.clickable(
            onClickLabel = stringResource(R.string.shell_breadcrumb_top_desc),
            onClick = onTop,
        ),
    )
}

/** The pill both kinds of step are drawn as. [modifier] goes inside the clip, so a ripple stays round. */
@Composable
private fun CrumbPill(
    title: String,
    route: String?,
    container: Color,
    outline: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = Modifier
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
