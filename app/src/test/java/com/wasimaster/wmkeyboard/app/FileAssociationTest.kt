package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The file associations live in the manifest as `pathPattern` strings and the
 * importer's understanding of them lives in [WMFileTypes.EXTENSIONS]. Nothing
 * at compile time ties the two together, and the failure mode is silent: a new
 * export format gets a Kotlin constant, nobody adds the patterns, and the file
 * simply never opens from a file manager.
 *
 * These read the real manifest and check the two still agree.
 */
class FileAssociationTest {

    /** Unit tests run with the module directory as the working directory. */
    private val manifest = File("src/main/AndroidManifest.xml").readText()
        // Comments in this manifest quote the patterns they explain.
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    /**
     * The two import activities' own blocks. The file associations and the
     * link filter are checked separately, because they claim deliberately
     * different things: the file one claims the types our own exports arrive
     * as, the link one claims text/plain and nothing else.
     */
    private fun activityBlock(name: String): String =
        manifest.split("<activity").first { it.contains(name) }

    private val fileActivity = activityBlock("ImportFileActivity")
    private val linkActivity = activityBlock("ImportLinkActivity")

    private val patterns: List<String> =
        Regex("""android:pathPattern="([^"]+)"""")
            .findAll(fileActivity).map { it.groupValues[1] }.toList()

    /** Every MIME type the file associations claim, across their filters. */
    private val claimedTypes: List<String> =
        Regex("""android:mimeType="([^"]+)"""")
            .findAll(fileActivity).map { it.groupValues[1] }.toList()

    /** The filters that match on a type rather than on a file name. */
    private val typedFilters: List<String> = fileActivity.split("<intent-filter")
        .filter { it.contains("android:mimeType") && !it.contains("pathPattern") }

    /**
     * `.*\\..*\\.wmtheme\\.json` -> `wmtheme.json`: drop the leading wildcards,
     * then undo the backslash escaping aapt2 resolves at build time.
     */
    private fun extensionOf(pattern: String): String =
        pattern.substringAfterLast(""".*\\.""").replace("""\\.""", ".")

    @Test
    fun `every declared extension is matched by the manifest`() {
        assertEquals(WMFileTypes.EXTENSIONS.toSet(), patterns.map(::extensionOf).toSet())
    }

    @Test
    fun `each extension has the dotted-name variants pathPattern needs`() {
        // PatternMatcher's simple glob does not backtrack, so ".*\.wmtheme\.json"
        // matches "theme.wmtheme.json" but not "my.theme.wmtheme.json". Each
        // extra leading ".*\." buys one more dot in the name; four variants per
        // filter, across the two filters, is eight patterns per extension.
        val byExtension = patterns.groupBy(::extensionOf)
        for (extension in WMFileTypes.EXTENSIONS) {
            assertEquals(
                "wrong number of pathPattern variants for .$extension",
                8,
                byExtension[extension]?.size,
            )
        }
    }

    @Test
    fun `no pattern claims a general file extension`() {
        // The whole point of the compound extensions: opening someone else's
        // .json must not offer this app *by name*. The typed filter below is
        // offered for it, and says so about itself.
        //
        // One extension here is deliberately not ours. A `.flex` is a
        // FlorisBoard theme, which this app converts, and claiming it is how
        // someone moving over opens the file they already have. It is safe to
        // claim for the same reason the others are: it names one format
        // precisely, rather than a container half the device writes.
        // FlorisBoard's own filter still matches, so with both installed the
        // user is asked which app should open it.
        for (pattern in patterns) {
            val extension = extensionOf(pattern)
            assertTrue(
                "pattern $pattern claims files that are not WMKeyboard's",
                extension.startsWith("wm") || extension in FOREIGN_EXTENSIONS,
            )
        }
    }

    @Test
    fun `a MIME-typed filter catches the URIs that carry no file name`() {
        // A provider may hand out a URI with no name in it — the downloads
        // provider's `msf:19` for anything MediaStore has indexed — and every
        // pathPattern above misses those. Without a filter matching on the type
        // alone, a theme downloaded in a browser opens in whichever other
        // keyboard claims application/octet-stream and this one is never
        // offered. One filter for VIEW, one for SEND.
        assertEquals(2, typedFilters.size)
        for (filter in typedFilters) {
            for (type in NAMELESS_TYPES) {
                assertTrue(
                    "a type-matched filter does not claim $type",
                    filter.contains("""android:mimeType="$type""""),
                )
            }
        }
    }

    @Test
    fun `the share sheet reaches the import activity`() {
        // Sharing a theme out of a chat app reached FlorisBoard and not this
        // app, because only one of the two declared ACTION_SEND.
        val send = fileActivity.split("<intent-filter")
            .filter { it.contains("android.intent.action.SEND\"") }
        assertEquals(1, send.size)
        // No scheme: a share sheet matches on the type alone, and a declared
        // content:// would drop every app that shares its stream some other way.
        assertTrue("the share filter declares a scheme", !send.single().contains("android:scheme"))
    }

    @Test
    fun `a shared link reaches the link importer, and nothing else does`() {
        // A browser shares a page as text/plain and nothing else, so this is
        // the only filter a shared address can match. It is a wide claim and
        // it belongs to one activity: text with no address in it opens that
        // activity, says so, and closes. The file importer must never claim
        // text/plain, or every shared message would offer to be imported as a
        // theme.
        val send = linkActivity.split("<intent-filter")
            .filter { it.contains("android.intent.action.SEND\"") }
        assertEquals(1, send.size)
        assertTrue(
            "the link share filter does not claim text/plain",
            send.single().contains("""android:mimeType="text/plain""""),
        )
        assertTrue("the link share filter declares a scheme", !send.single().contains("android:scheme"))
        assertTrue(
            "the file importer claims text/plain",
            !fileActivity.contains("""android:mimeType="text/plain""""),
        )
        // The written form, for a README or a support reply. Browsable for the
        // same reason the addon links are: following one only ever opens a
        // dialog, and the fetch waits for the user.
        assertTrue(
            "the wmkeyboard://import link is not declared",
            linkActivity.contains("""android:scheme="wmkeyboard" android:host="import""""),
        )
    }

    @Test
    fun `no filter claims a type the app cannot make sense of`() {
        // Being offered for files that are none of ours is the price of the
        // filter above, and it is only worth paying for the types our own
        // formats actually arrive as. Anything wider — text/*, or */* outside
        // the name-matched filters — would put this app in the chooser for
        // documents it has nothing to say about.
        val loose = claimedTypes.toSet() - NAMELESS_TYPES - YAML_TYPES - "*/*"
        assertTrue("the manifest claims $loose", loose.isEmpty())
        for (filter in typedFilters) {
            assertTrue("a type-matched filter claims */*", !filter.contains("""android:mimeType="*/*""""))
        }
    }

    @Test
    fun `both a typed and an untyped filter are declared`() {
        // An intent carrying no MIME type only ever matches a filter that
        // declares none, so the associations are duplicated with and without
        // the wildcard type. Losing one silently halves the coverage.
        val filters = manifest.split("<intent-filter").filter { it.contains("pathPattern") }
        assertEquals(2, filters.size)
        assertEquals(1, filters.count { it.contains("""android:mimeType="*/*"""") })
    }

    @Test
    fun `path matching is only enforced when an authority is declared`() {
        // IntentFilter checks paths inside its authority branch: drop host="*"
        // and every pattern above stops being applied, which turns these filters
        // into "any content URI of any type".
        for (filter in manifest.split("<intent-filter").filter { it.contains("pathPattern") }) {
            assertTrue(
                "a path-matching filter is missing android:host",
                filter.contains("""android:host="*""""),
            )
        }
    }

    private companion object {
        /**
         * Extensions belonging to other projects that we deliberately claim.
         * `clinktheme` and `clinklayout` are Clink's (an iOS keyboard), each
         * one format, which the app converts.
         */
        val FOREIGN_EXTENSIONS = setOf("flex", "kmp", "wastickers", "kcf", "klc", "keylayout", "clinktheme", "clinklayout")

        /**
         * The types a file of ours arrives as when its URI carries no name.
         * JSON for the text formats; the other three are what a provider says
         * about a ZIP whose extension it has never heard of.
         */
        val NAMELESS_TYPES = setOf(
            "application/json",
            "application/octet-stream",
            "application/zip",
            "application/x-zip-compressed",
        )

        /**
         * YAML, in the four spellings providers use for it. Two formats this
         * app reads arrive as YAML — a FUTO Keyboard layout and an Espanso
         * snippet file — and claiming the type is how they are offered to this
         * app at all. Deliberately not claimed by `pathPattern`: a `.yaml` name
         * pattern would offer the app for every YAML file on the device.
         */
        val YAML_TYPES = setOf(
            "application/yaml",
            "application/x-yaml",
            "text/yaml",
            "text/x-yaml",
        )
    }
}
