package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Where a Bergamot file is fetched from (#1085): Mozilla, then the data
 * repository's `translations` release, and the copy for the offline build's
 * browser links.
 */
class BergamotSourcesTest {

    private val data = RepoLocation(GitForge.GITHUB, "github.com", "wasi-master", "wmkeyboard-data")
    private val file = BergamotCatalog.directions.first { it.key == "fr-en" }.files.first()

    @Test
    fun `the mirror asset is the download's sha256, then its name, packed`() {
        assertEquals("${file.sha256}-${file.name}.zst", BergamotSources.assetName(file))
        assertEquals(
            "https://github.com/wasi-master/wmkeyboard-data/releases/download/translations/${file.sha256}-${file.name}.zst",
            BergamotSources.mirrorUrl(file, data),
        )
    }

    @Test
    fun `a data repository moved to another GitHub owner is followed, scheme and slashes tolerated`() {
        val fork = RepoLocation(GitForge.GITHUB, "https://github.com/", "someone/", "data.git")
        assertEquals(
            "https://github.com/someone/data/releases/download/translations/${BergamotSources.assetName(file)}",
            BergamotSources.mirrorUrl(file, fork),
        )
    }

    @Test
    fun `no mirror off GitHub or for an unfinished location`() {
        assertNull(BergamotSources.mirrorUrl(file, RepoLocation(GitForge.GITLAB, "gitlab.com", "a", "b")))
        assertNull(BergamotSources.mirrorUrl(file, RepoLocation(GitForge.GITHUB, "github.com", "", "b")))
    }

    @Test
    fun `downloads try Mozilla first, then the mirror`() {
        assertEquals(listOf(file.url, BergamotSources.mirrorUrl(file, data)), BergamotSources.downloadUrls(file, data))
        assertTrue(file.url.startsWith(BergamotCatalog.BASE_URL))
        assertEquals(listOf(file.url), BergamotSources.downloadUrls(file, RepoLocation(GitForge.GITLAB, "gitlab.com", "a", "b")))
    }

    @Test
    fun `browser links go to the mirror, and to Mozilla only where there is none`() {
        assertEquals(BergamotSources.mirrorUrl(file, data), BergamotSources.browserUrl(file, data))
        assertEquals(file.url, BergamotSources.browserUrl(file, RepoLocation(GitForge.GITLAB, "gitlab.com", "a", "b")))
    }

    @Test
    fun `the first address that works wins and later ones are never asked`() = runBlocking {
        val asked = ArrayList<String>()
        val got = BergamotSources.firstOf(listOf("a", "b", "c")) { url ->
            asked += url
            if (url == "a") throw DownloadException.Http(406)
            "from $url"
        }
        assertEquals("from b", got)
        assertEquals(listOf("a", "b"), asked)
    }

    @Test
    fun `a checksum mismatch from the origin moves on to the mirror`() = runBlocking {
        val got = BergamotSources.firstOf(listOf("mozilla", "mirror")) { url ->
            if (url == "mozilla") throw IOException("did not match its pinned checksum")
            url
        }
        assertEquals("mirror", got)
    }

    @Test
    fun `when every address fails the last failure is thrown with the others on it`() = runBlocking {
        val first = DownloadException.Http(406)
        val last = IOException("mirror down")
        try {
            BergamotSources.firstOf(listOf("a", "b")) { url -> throw if (url == "a") first else last }
            fail("expected a failure")
        } catch (e: IOException) {
            assertSame(last, e)
            assertSame(first, e.suppressed.single())
        }
    }

    @Test
    fun `data saving and cancelling stop at once, without trying the mirror`() = runBlocking {
        for (stop in listOf<Exception>(DownloadException.Blocked(), CancellationException("cancelled"))) {
            val asked = ArrayList<String>()
            try {
                BergamotSources.firstOf(listOf("a", "b")) { url ->
                    asked += url
                    throw stop
                }
                fail("expected $stop")
            } catch (e: Exception) {
                assertSame(stop, e)
            }
            assertEquals(listOf("a"), asked)
        }
    }
}
