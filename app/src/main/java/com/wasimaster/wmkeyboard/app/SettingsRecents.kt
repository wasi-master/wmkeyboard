package com.wasimaster.wmkeyboard.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * Recently visited settings pages (#1317): the screens the user stayed on,
 * newest first, so a page left a minute ago is one press away from anywhere.
 * Listed in the favourites sheet, under the pins, and on the search screen
 * before anything is typed.
 *
 * A page counts once it has been open for [RecentPages.DWELL_MS]: a screen
 * only passed through on the way somewhere deeper is not one the user came
 * for. Each page is kept by the same link a favourite is ([SettingsFavourite]),
 * so a page that a later version moves still opens.
 */

internal object RecentPages {

    /** How long a page has to stay open to count as visited. */
    const val DWELL_MS = 2_000L

    /** How many pages are kept. */
    const val MAX = 12

    /** How many a list shows. */
    const val SHOWN = 6

    /** [visits] with [page] put first, any earlier visit of it dropped, and no more than [MAX]. */
    fun withVisit(visits: List<SettingsFavourite>, page: SettingsFavourite): List<SettingsFavourite> =
        (listOf(page) + visits.filterNot { it.link == page.link }).take(MAX)
}

/**
 * The visits on disk, in the favourites' own form, one line each. A plain
 * preferences file like the search picks: a convenience of this phone that a
 * backup has no need to carry.
 */
internal class RecentPagesStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val state = MutableStateFlow(
        SettingsFavourites.decode(prefs.getString(KEY_PAGES, null)?.lines().orEmpty().filter { it.isNotBlank() }),
    )

    /** The pages, newest first. */
    val pages: StateFlow<List<SettingsFavourite>> get() = state

    fun record(page: SettingsFavourite) {
        val next = RecentPages.withVisit(state.value, page)
        state.value = next
        prefs.edit { putString(KEY_PAGES, SettingsFavourites.encode(next).joinToString("\n")) }
    }

    fun clear() {
        state.value = emptyList()
        prefs.edit { remove(KEY_PAGES) }
    }

    companion object {
        private const val FILE_NAME = "settings_recent_pages"
        private const val KEY_PAGES = "pages"

        @Volatile
        private var instance: RecentPagesStore? = null

        fun get(context: Context): RecentPagesStore =
            instance ?: synchronized(this) {
                instance ?: RecentPagesStore(context).also { instance = it }
            }
    }
}

/** The recent pages, newest first, as state. */
@Composable
internal fun rememberRecentPages(): List<SettingsFavourite> {
    val context = LocalContext.current
    val store = remember(context) { RecentPagesStore.get(context) }
    val pages by store.pages.collectAsStateWithLifecycle()
    return pages
}

/**
 * Counts the screen being drawn as visited once it has stayed open for
 * [RecentPages.DWELL_MS]. [page] is read when the time is up rather than
 * now: the path strip is still settling on a screen's first frames. Null for
 * a screen a link cannot name, which then is not kept.
 */
@Composable
internal fun RecordRecentPage(link: String?, page: () -> SettingsFavourite) {
    val current by rememberUpdatedState(page)
    link ?: return
    val context = LocalContext.current
    LaunchedEffect(link) {
        delay(RecentPages.DWELL_MS)
        RecentPagesStore.get(context).record(current())
    }
}
