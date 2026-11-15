package com.wasimaster.wmkeyboard.core.download

import com.wasimaster.wmkeyboard.core.endpoints.ServiceEndpoints
import com.wasimaster.wmkeyboard.core.endpoints.ServiceRepo

/**
 * A copy of the data repository's `models` and `natives` releases on
 * Cloudflare R2, which [FileDownloader] tries before GitHub. Same file names
 * (`<sha256>-<name>`) under `<tag>/`, so the pins built into the app check its
 * bytes exactly as they check GitHub's, and the mirror is trusted no more.
 *
 * GitHub holds one connection to 0.3 to 0.8 MB/s from Bangladesh; Cloudflare's
 * Dhaka edge gave 7.6 to 8.4 MB/s on one connection from the phone (measured
 * 2026-10-21). A file missing from the mirror, or a mirror that is down, falls
 * back to GitHub with whatever pieces already arrived.
 *
 * Filled by `scripts/mirror-releases-to-r2.sh`.
 */
object DataMirror {

    /** The mirror's address with no trailing slash, or null to fetch everything from GitHub. */
    @Volatile
    var base: String? = null

    /** The releases that are mirrored. Anything else in the repository is not. */
    val TAGS = setOf("models", "natives")

    /**
     * The mirror's address for [url], or null when [url] is not an asset of a
     * mirrored release, when there is no mirror, or when the data repository
     * has been pointed somewhere else (the F-Droid build's server settings):
     * a repository the user chose is the one they get.
     */
    fun of(url: String): String? {
        val data = ServiceRepo.DATA.default
        val r2 = base?.takeIf { ServiceEndpoints.repo(ServiceRepo.DATA) == data }
            ?.let { mirrored(url, "https://${data.host}/${data.owner}/${data.repo}/releases/download/", it) }
        return r2 ?: proxy?.let { proxied(url, it) }
    }

    /**
     * A GitHub proxy the user set (Settings › Privacy › Downloads), or null.
     * Either a prefix the whole GitHub address goes after
     * (`https://proxy.example/` → `https://proxy.example/https://github.com/…`),
     * the form most public GitHub proxies take, or a template with `{url}` in
     * it. Every GitHub file the keyboard downloads tries it first and falls back
     * to GitHub for whatever it did not deliver.
     *
     * For anyone GitHub is blocked or crawling for (#1045: mainland China,
     * where `github.com` timed out and `raw.githubusercontent.com` stalled).
     * A pinned file is still checked against its SHA-256 whatever served it, so
     * a proxy can make a pinned download fail but not swap it.
     */
    @Volatile
    var proxy: String? = null

    /** GitHub's download hosts: what [proxy] is used for. */
    private val GITHUB_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "codeload.github.com",
        "gist.githubusercontent.com",
    )

    /** Whether [value] can be used as a [proxy]: an http(s) address, `{url}` at most once. */
    fun isUsableProxy(value: String): Boolean {
        val v = value.trim()
        if (!v.startsWith("https://") && !v.startsWith("http://")) return false
        val host = v.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isEmpty() || ' ' in v) return false
        return v.split("{url}").size <= 2
    }

    /** [url] through [proxy] when [url] is on one of GitHub's hosts, else null. */
    internal fun proxied(url: String, proxy: String): String? {
        if (!url.startsWith("https://")) return null
        val host = url.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
        if (host !in GITHUB_HOSTS) return null
        val p = proxy.trim()
        if (!isUsableProxy(p)) return null
        return if ("{url}" in p) p.replace("{url}", url) else "${p.trimEnd('/')}/$url"
    }

    /** [url] under [origin] (`…/releases/download/`) moved to [base], for a [TAGS] release. */
    internal fun mirrored(url: String, origin: String, base: String): String? {
        if (!url.startsWith(origin)) return null
        val rest = url.removePrefix(origin)
        val tag = rest.substringBefore('/', "")
        val asset = rest.substringAfter('/', "")
        if (tag !in TAGS || asset.isEmpty() || '/' in asset || '?' in asset) return null
        return "${base.trimEnd('/')}/$tag/$asset"
    }
}
