package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import java.net.URI
import kotlinx.serialization.json.Json

/**
 * Decoding a repository manifest, and turning what the user pasted into the
 * URLs to actually fetch.
 *
 * Both halves are the app's trust boundary for addons: the strings arriving
 * here come from a text field or a deep link, and every URL they produce is
 * fetched over the network. So the rules are narrow on purpose — https only,
 * no path escaping out of the repository, no host switching on a relative
 * reference.
 */
object AddonRepoCodec {

    /** Magic tag. A manifest without exactly this is not one of ours. */
    const val FORMAT = "wmkeyboard-repo"

    /** The file a repository URL is expected to hold at its root. */
    const val MANIFEST_NAME = "wmkeyboard-repo.json"

    /**
     * A manifest indexing hundreds of addons is still small; this is a sanity
     * bound on the fetch, not a real limit anyone should hit.
     */
    const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /**
     * Parses [text] as a manifest, or returns null.
     *
     * Same contract as `LayoutFile.decode`: the `format` tag is the one strict
     * check and failure is a null rather than an exception, because the caller
     * is always "the user pasted a URL" and the answer they need is "that
     * isn't an addon repository", not a stack trace.
     */
    fun decode(text: String): AddonRepoManifest? {
        val manifest = runCatching { json.decodeFromString<AddonRepoManifest>(text) }.getOrNull()
            ?: return null
        if (manifest.format != FORMAT) return null
        if (manifest.repo.id.isBlank()) return null
        // Entries missing the fields that identify them can't be installed or
        // tracked, and an unknown type has no importer. Dropping them beats
        // rejecting the repository over one malformed row.
        val usable = manifest.addons.filter {
            it.id.isNotBlank() && it.path.isNotBlank() && it.type != AddonType.Unknown &&
                !it.id.contains(AddonLocalization.SEPARATOR)
        }
        return manifest.copy(addons = usable.map { it.copy(localizations = usableLocalizations(it)) })
    }

    /** `owner/repository`, GitHub's own shorthand: no scheme, no host, two parts. */
    private val GITHUB_SHORTHAND = Regex("""[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+""")

    /** `es`, `es-419`, `pt-BR`, `bn_rom-BD`: a language id, then an optional region. */
    private val LOCALE = Regex("""[a-z]{2,3}(_[a-z0-9]+)*(-[A-Za-z0-9]{2,8})?""")

    /**
     * The localizations worth offering: well-formed, with a payload, one per
     * locale, and none repeating the base list's own language. Same reasoning
     * as the entry filter above — one bad row drops, the addon stays.
     */
    private fun usableLocalizations(entry: AddonEntry): List<AddonLocalization> {
        if (!entry.type.perLanguage) return emptyList()
        val seen = HashSet<String>()
        entry.langId?.takeIf { it.isNotBlank() }?.let { seen += it.lowercase() }
        return entry.localizations.filter {
            it.path.isNotBlank() && LOCALE.matches(it.locale) && seen.add(it.locale.lowercase())
        }
    }

    /**
     * Turns what the user pasted into the URL the manifest lives at.
     *
     * | Pasted | Fetched |
     * |---|---|
     * | `github.com/USER/REPO` | `raw.githubusercontent.com/USER/REPO/HEAD/wmkeyboard-repo.json` |
     * | `github.com/USER/REPO/tree/BRANCH` | the same on `BRANCH` |
     * | a direct URL to a `.json` | used as-is |
     * | a repository page on another forge (Codeberg, a GitLab, SourceHut, Bitbucket, a self-hosted Forgejo) | that forge's raw `wmkeyboard-repo.json` |
     * | any other https URL | treated as a directory; `wmkeyboard-repo.json` appended |
     * | `user/repo` alone | the GitHub repository, as the first row |
     *
     * A URL with no scheme is assumed to be https — people paste addresses
     * without typing the scheme. A GitHub repository address may also hold a
     * Clink repository instead; [ClinkRepo.locate] finds its catalogue from
     * the URL this returns. Returns null for
     * anything that isn't resolvable to an https URL, which includes plain
     * http: it is rejected rather than silently upgraded, so a link that
     * expected to be intercepted fails visibly.
     */
    fun resolveManifestUrl(pasted: String): String? {
        val trimmed = pasted.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null

        // Reject anything with an explicit non-https scheme before guessing.
        // Compare the whole scheme, not a prefix of it: "http" is a prefix of
        // "https", so a prefix test would let plain http through.
        val schemeEnd = trimmed.indexOf("://")
        val hasScheme = schemeEnd > 0
        if (hasScheme && !trimmed.take(schemeEnd).equals("https", ignoreCase = true)) return null
        // An opaque scheme has no "://" at all — javascript:, mailto:, content:
        // with no authority. A bare host never contains a colon before its
        // first slash, so this only catches the schemes.
        if (!hasScheme && trimmed.substringBefore('/').contains(':')) return null

        // `owner/repository` alone is a GitHub repository: it is how Clink
        // asks for one, so it is what someone bringing a Clink repository
        // pastes. A host always has a dot in it, and this first part has none.
        if (!hasScheme && GITHUB_SHORTHAND.matches(trimmed)) {
            val (user, repo) = trimmed.split('/')
            return "https://raw.githubusercontent.com/$user/${repo.removeSuffix(".git")}/HEAD/$MANIFEST_NAME"
        }

        val withScheme = if (hasScheme) trimmed else "https://$trimmed"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().trim('/')

        if (host == "github.com" || host == "www.github.com") {
            val parts = path.split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            val (user, repo) = parts[0] to parts[1].removeSuffix(".git")
            // .../tree/BRANCH and .../blob/BRANCH both name a ref; anything
            // else (an /issues link, a bare repo URL) resolves against HEAD.
            val ref = if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "blob")) parts[3] else "HEAD"
            return "https://raw.githubusercontent.com/$user/$repo/$ref/$MANIFEST_NAME"
        }

        if (path.endsWith(".json", ignoreCase = true)) return withScheme
        // A repository page on another forge is HTML: appending the file name
        // to it would fetch a web page. The forge's raw address is the file.
        RepoLocation.fromPageUrl(withScheme)?.let { (location, dir) ->
            if (location.isComplete) {
                return location.rawUrl(if (dir.isEmpty()) MANIFEST_NAME else "$dir/$MANIFEST_NAME")
            }
        }
        return "$withScheme/$MANIFEST_NAME"
    }

    /**
     * Resolves an entry's `path`, a `previews[]` item or `repo.icon` against
     * the manifest it came from.
     *
     * Absolute https URLs pass through — the format lets a publisher point at
     * a release asset or a CDN. A relative reference resolves against the
     * manifest's directory and must stay under it: a `../` walking up to
     * another user's repository on the same host would let a manifest serve
     * payloads it doesn't own, and on a raw host that is a real path.
     */
    fun resolveAsset(manifestUrl: String, reference: String): String? {
        val value = reference.trim()
        if (value.isEmpty()) return null

        if (value.contains("://")) {
            return value.takeIf { it.startsWith("https://", ignoreCase = true) }
        }
        // A protocol-relative or root-relative reference skips the repository
        // directory entirely, which the relative form is not allowed to do.
        if (value.startsWith("//") || value.startsWith("/")) return null

        val base = runCatching { URI(manifestUrl) }.getOrNull() ?: return null
        val resolved = runCatching { base.resolve(value).normalize() }.getOrNull() ?: return null

        if (!resolved.scheme.equals("https", ignoreCase = true)) return null
        if (!resolved.host.equals(base.host, ignoreCase = true)) return null

        val baseDir = base.resolve(".").normalize().path.orEmpty()
        if (!resolved.path.orEmpty().startsWith(baseDir)) return null

        return resolved.toString()
    }
}
