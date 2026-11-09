package com.wasimaster.wmkeyboard.core.translate

import com.wasimaster.wmkeyboard.core.download.DownloadException
import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Where a [BergamotCatalog.File] can be fetched from (#1085).
 *
 * Mozilla's attachment CDN is the origin. It answers any user agent with
 * `Chrome/` in it with HTTP 406 (Chrome and every browser built on it), while
 * the app's own downloader ([com.wasimaster.wmkeyboard.core.download.FileDownloader.USER_AGENT]),
 * Firefox, curl and wget get the file. So the app fetches from Mozilla first,
 * and from the data repository's `translations` release when Mozilla fails.
 * That release holds the same zstd files, named `<sha256>-<name>.zst` like the
 * `natives` and `models` releases, and the same pins check them, so the copy is
 * trusted no more than the origin. The offline build's links go to the copy,
 * since there a phone's browser does the fetching.
 */
object BergamotSources {

    /** The data repository's release that mirrors Mozilla's files. */
    const val RELEASE_TAG = "translations"

    /** [file]'s asset in the mirror release: its download's SHA-256, then its name. */
    fun assetName(file: BergamotCatalog.File): String = "${file.sha256}-${file.name}.zst"

    /**
     * [file] in the data repository's mirror, or null when [repo] is not on
     * GitHub (a server the user set on the F-Droid build may have no releases).
     */
    fun mirrorUrl(file: BergamotCatalog.File, repo: RepoLocation = ServiceEndpoints.repo(ServiceRepo.DATA)): String? {
        if (repo.forge != GitForge.GITHUB || !repo.isComplete) return null
        val host = repo.host.trim().removePrefix("https://").removePrefix("http://").trim('/')
        val owner = repo.owner.trim().trim('/')
        val name = repo.repo.trim().trim('/').removeSuffix(".git")
        return "https://$host/$owner/$name/releases/download/$RELEASE_TAG/${assetName(file)}"
    }

    /** Where the app downloads [file] from, in the order it tries them: Mozilla, then the mirror. */
    fun downloadUrls(file: BergamotCatalog.File, repo: RepoLocation = ServiceEndpoints.repo(ServiceRepo.DATA)): List<String> =
        listOfNotNull(file.url, mirrorUrl(file, repo))

    /**
     * The address the offline build's sheet gives for [file], to open in a
     * browser on this phone or another: the mirror, which every browser gets,
     * or Mozilla's own where there is no mirror.
     */
    fun browserUrl(file: BergamotCatalog.File, repo: RepoLocation = ServiceEndpoints.repo(ServiceRepo.DATA)): String =
        mirrorUrl(file, repo) ?: file.url

    /**
     * Runs [attempt] on each of [urls] in turn until one returns, and returns
     * that. A failure moves on to the next address; the last one's failure is
     * thrown, with the earlier ones suppressed onto it. Cancelling, and data
     * saving holding the download back ([DownloadException.Blocked]), stop at
     * once: neither would go any better on another host.
     */
    suspend fun <T> firstOf(urls: List<String>, attempt: suspend (String) -> T): T {
        require(urls.isNotEmpty()) { "no address to fetch from" }
        val failures = ArrayList<IOException>()
        for (url in urls) {
            try {
                return attempt(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException.Blocked) {
                throw e
            } catch (e: IOException) {
                failures += e
            }
        }
        val last = failures.last()
        failures.dropLast(1).forEach(last::addSuppressed)
        throw last
    }
}
