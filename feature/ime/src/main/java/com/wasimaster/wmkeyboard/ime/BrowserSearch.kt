package com.wasimaster.wmkeyboard.ime

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The search list's "Search in the browser" row (#1383): the query goes to
 * the default browser's own search, with whatever engine the browser is set
 * to, and the keyboard does nothing more with it.
 *
 * `ACTION_WEB_SEARCH` aimed at the default browser's package, which Chrome,
 * Firefox, Samsung Internet and most others accept. A browser that refuses
 * it gets [fallbackUrl] opened instead (the next search provider's own
 * results page, or DuckDuckGo's), still in that browser. The launcher query
 * in the manifest makes every launchable app visible, browsers included,
 * so the default one can be found.
 */
internal object BrowserSearch {

    /** False when nothing could take it. */
    fun open(context: Context, query: String, fallbackUrl: String): Boolean {
        val browser = defaultBrowser(context)
        val search = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (browser != null && runCatching { context.startActivity(Intent(search).setPackage(browser)) }.isSuccess) return true
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (browser != null && runCatching { context.startActivity(Intent(view).setPackage(browser)) }.isSuccess) return true
        return runCatching { context.startActivity(view) }.isSuccess
    }

    /**
     * The package that opens web links by default, or null when the user
     * never picked one (the system's chooser, package `android`, answers then).
     */
    private fun defaultBrowser(context: Context): String? =
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" && it != context.packageName }
}
