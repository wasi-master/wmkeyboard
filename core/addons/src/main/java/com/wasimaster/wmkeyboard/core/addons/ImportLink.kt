package com.wasimaster.wmkeyboard.core.addons

import com.wasimaster.wmkeyboard.core.endpoints.GitForge
import com.wasimaster.wmkeyboard.core.endpoints.RepoLocation
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a link shared into the app points at, worked out from the address alone.
 *
 * The other half of [AddonRepoCodec]: that one turns a pasted repository
 * address into the manifest to fetch, this one turns any address at all into
 * the thing the importer should be handed. A link arrives from a share sheet,
 * a `wmkeyboard://import` deep link or a text field, so it is untrusted input
 * in exactly the same way, and the rules are the same: https only, no scheme
 * guessing past that, and the address that will actually be fetched is shown
 * before anything is.
 *
 * Nothing here touches the network. A release page or a folder resolves to a
 * [Target.Listing] naming the API address that lists what is inside it, and
 * `LinkImport` in `:feature:addons` is what fetches and reads it. That split is
 * what makes every rule below testable on the JVM.
 */
object ImportLink {

    /** What the address turned out to name. */
    sealed interface Target {

        /**
         * One file to download. [name] is what it should be called on disk,
         * which is the only thing the importer reads a name for: a
         * `.wmtheme.json` is told from any other JSON by its name, and a file
         * fetched under the wrong one would import as the wrong format.
         */
        data class File(val url: String, val name: String) : Target

        /**
         * Something holding several files: a release, a gist, a repository
         * folder. [apiUrl] lists them and [shape] says how to read the answer.
         *
         * [manifestProbe] is set when the address could also be an addon
         * repository, which is every address that named a repository folder.
         * The fetcher tries it first: a repository is a better answer than a
         * list of the files that happen to sit next to its manifest.
         *
         * [location] and [dir] are carried for the one forge whose listing
         * names no download address of its own (GitLab), where each entry's
         * raw URL has to be built from the path it came back with.
         */
        data class Listing(
            val apiUrl: String,
            val shape: ListingShape,
            val manifestProbe: String? = null,
            val location: RepoLocation? = null,
            val dir: String = "",
        ) : Target

        /**
         * A GitHub Actions artifact. Its own case because it is the one target
         * that cannot be fetched by address alone: the artifact download
         * endpoint answers 403 to an anonymous request even for a public
         * repository, so this either goes through a token or through
         * [nightlyLinkUrl].
         */
        data class Artifact(val owner: String, val repo: String, val id: String) : Target

        /**
         * A Signal sticker pack. Not a file at an address: the link carries the
         * pack's id and the key its stickers are encrypted under
         * (`https://signal.art/addstickers/#pack_id=…&pack_key=…`, or the
         * `sgnl://addstickers?…` form Signal itself opens), and the pack is
         * fetched from Signal's sticker CDN with them. Both are lowercase hex,
         * already checked for length.
         */
        data class SignalStickers(val packId: String, val packKey: String) : Target

        /**
         * A Telegram sticker set, by its name: `https://t.me/addstickers/<name>`,
         * the `tg://addstickers?set=<name>` form Telegram itself opens, and
         * the same two for custom emoji (`addemoji`). Not a file at an address
         * either: the set is asked for through the Bot API with the user's
         * own bot token. The name is letters, digits and underscores.
         */
        data class TelegramStickers(val setName: String) : Target

        /** A plain http address. Its own case so the refusal can say why. */
        data object Insecure : Target

        /** No address in the shared text at all. */
        data object None : Target
    }

    /** How to read what an [Target.Listing]'s API address answers with. */
    enum class ListingShape {
        /** One release object: `assets[]` with `name` and `browser_download_url`. GitHub and Forgejo both. */
        RELEASE_ASSETS,

        /** An array of release objects, newest first. */
        RELEASE_LIST,

        /** One GitLab release: `assets.links[]` with `name` and `url`. */
        GITLAB_RELEASE,

        /** An array of GitLab releases. */
        GITLAB_RELEASE_LIST,

        /** A gist: `files` keyed by name, each with `filename` and `raw_url`. */
        GIST,

        /** A directory listing: an array with `name`, `type` and `download_url`. */
        CONTENTS,

        /** A GitLab tree: an array with `name`, `type` and `path`, and no address. */
        GITLAB_TREE,

        /**
         * Nothing to list. The forge has no listing that works without an
         * account, so all that is left is the repository-manifest probe.
         */
        NONE,
    }

    /** Hosts that serve file bytes rather than a web page. */
    private val RAW_HOSTS = setOf(
        "raw.githubusercontent.com",
        "gist.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "media.githubusercontent.com",
        "nightly.link",
    )

    /** Trailing characters a link picks up from the sentence it was written in. */
    private const val TRAILING = ".,;:!?)]}>'\"„“”’"

    // Built on first use: each compiled Regex holds native ICU sets for the
    // life of the process, about 18 KB across this file's patterns.
    private val URL_IN_TEXT by lazy { Regex("""https?://[^\s<>"'\\]+""", RegexOption.IGNORE_CASE) }

    /**
     * A Signal pack link, in the form it is shared in and the form Signal
     * opens. Group 1 is the `pack_id=…&pack_key=…` tail, which the first keeps
     * in the fragment (so that it never reaches signal.art's server) and the
     * second in the query.
     */
    private val SIGNAL_PACK by lazy {
        Regex(
            """(?:https://signal\.art/addstickers/?#|sgnl://addstickers/?\?)([A-Za-z0-9_=&]+)""",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * A Telegram set link on the web. Group 1 is the set name. https only,
     * like every other address here: a plain http one falls through and is
     * refused as [Target.Insecure].
     */
    private val TELEGRAM_WEB by lazy {
        Regex(
            """https://(?:www\.)?(?:t\.me|telegram\.me|telegram\.dog)/(?:addstickers|addemoji)/([A-Za-z0-9_]{1,64})(?![A-Za-z0-9_])""",
            RegexOption.IGNORE_CASE,
        )
    }

    /** The form Telegram opens: `tg://addstickers?set=<name>`. Group 1 is the set name. */
    private val TELEGRAM_APP by lazy {
        Regex(
            """tg:(?://)?(?:addstickers|addemoji)/?\?(?:[^\s#]*&)?set=([A-Za-z0-9_]{1,64})(?![A-Za-z0-9_])""",
            RegexOption.IGNORE_CASE,
        )
    }
    private const val SIGNAL_ID_LENGTH = 32
    private const val SIGNAL_KEY_LENGTH = 64

    /**
     * The first web address in [text].
     *
     * A share sheet rarely sends the address on its own: a browser sends the
     * page title and then the link, a chat app sends whatever the message said
     * around it. Taking the first address out of the text is what makes
     * "Look at this theme https://github.com/..." work, and it is also why the
     * trailing punctuation of the sentence has to come off again.
     */
    fun urlIn(text: String): String? {
        val found = URL_IN_TEXT.findAll(text.trim())
            .map { it.value.trimEnd(*TRAILING.toCharArray()) }
            .filter { it.isNotBlank() }
            .toList()
        // An https address anywhere in the text wins over an http one earlier
        // in it. A shared message that quotes both is a message about a link,
        // and refusing it over the first address in the sentence would be
        // refusing the wrong one. With no https address at all the http one is
        // still returned, so the refusal can say what was wrong with it.
        return found.firstOrNull { it.startsWith("https://", ignoreCase = true) } ?: found.firstOrNull()
    }

    /**
     * What the link in [shared] points at.
     *
     * Reads the address only. Every branch that cannot be decided from the
     * address comes back as a [Target.Listing] for the fetcher to resolve, and
     * anything this has no opinion about at all comes back as a
     * [Target.File] to be downloaded and identified by its bytes, which is what
     * the file importer does with every file anyway.
     *
     * A Signal pack is looked for before anything else: its `sgnl:` form is not
     * a web address, so [urlIn] would never find it, and its `signal.art` form
     * would otherwise be downloaded as a web page. A Telegram set is looked
     * for next, for the same two reasons.
     */
    fun resolve(shared: String): Target =
        signalStickers(shared) ?: telegramStickers(shared) ?: resolveAddress(shared)

    /** [resolve] for everything that is an address on the web. */
    private fun resolveAddress(shared: String): Target {
        val url = urlIn(shared) ?: return Target.None
        if (url.startsWith("http://", ignoreCase = true)) return Target.Insecure

        val uri = runCatching { URI(url) }.getOrNull() ?: return Target.None
        val host = uri.host?.lowercase() ?: return Target.None
        val path = uri.path.orEmpty().trim('/')
        val parts = path.split('/').filter { it.isNotEmpty() }

        if (host in RAW_HOSTS) return file(url, parts)
        // Every forge spells a raw file as /OWNER/REPO/raw/... , and GitLab as
        // /OWNER/REPO/-/raw/... , so the word sits at one of two places and
        // nowhere else. Matched by position rather than by presence: a
        // repository with a folder called "raw" in it is a page, not a file.
        if (parts.size >= 4 && (parts[2] == "raw" || parts.getOrNull(3) == "raw")) return file(url, parts)

        gitHub(url, host, parts)?.let { return it }
        gist(host, parts)?.let { return it }
        gitLabRelease(host, parts)?.let { return it }
        forgeRelease(url, host, parts)?.let { return it }
        repoPage(url)?.let { return it }

        // Someone else's host, or a shape nothing here knows. Download it and
        // let the bytes say what it is.
        return file(url, parts)
    }

    /**
     * The address an artifact can be fetched from without an account.
     *
     * nightly.link exists for exactly this: GitHub's own artifact endpoint
     * needs an `Authorization:` header with `actions:read`, public repository
     * or not, so a shared artifact link is otherwise a dead end for everyone
     * who is not signed in. It is a third party, which is why using it is
     * named in the dialog that offers it and why a token, when there is one,
     * is preferred.
     */
    fun nightlyLinkUrl(target: Target.Artifact): String =
        "https://nightly.link/${target.owner}/${target.repo}/actions/artifacts/${target.id}.zip"

    /** GitHub's own artifact download, which needs a token. */
    fun artifactApiUrl(target: Target.Artifact): String =
        "https://api.github.com/repos/${target.owner}/${target.repo}/actions/artifacts/${target.id}/zip"

    /**
     * The Signal pack [shared] links to, or null when it links to none.
     *
     * Both halves have an exact length (16 and 32 bytes of hex) and a link with
     * either one wrong is not a pack link, so it falls through to be read as
     * whatever else it may be.
     */
    fun signalStickers(shared: String): Target.SignalStickers? {
        val match = SIGNAL_PACK.find(shared) ?: return null
        val params = match.groupValues[1].split('&').associate {
            it.substringBefore('=').lowercase() to it.substringAfter('=', "")
        }
        val id = params["pack_id"]?.lowercase()?.takeIf { it.length == SIGNAL_ID_LENGTH && it.isHex() }
        val key = params["pack_key"]?.lowercase()?.takeIf { it.length == SIGNAL_KEY_LENGTH && it.isHex() }
        return if (id != null && key != null) Target.SignalStickers(id, key) else null
    }

    /** The Telegram sticker set [shared] links to, or null when it links to none. */
    fun telegramStickers(shared: String): Target.TelegramStickers? {
        val match = TELEGRAM_WEB.find(shared) ?: TELEGRAM_APP.find(shared) ?: return null
        return Target.TelegramStickers(match.groupValues[1])
    }

    private fun String.isHex(): Boolean = all { it in '0'..'9' || it in 'a'..'f' }

    // ---- per-forge shapes ------------------------------------------------

    private fun gitHub(url: String, host: String, parts: List<String>): Target? {
        if (host != "github.com" && host != "www.github.com") return null
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val api = "https://api.github.com/repos/$owner/$repo"

        // /actions/runs/<run>/artifacts/<id>, and the /suites/<id>/artifacts/<id>
        // form a check run links to. Both end the same way.
        val artifactAt = parts.size - 2
        if (artifactAt >= 2 && parts[artifactAt] == "artifacts" && parts.last().all { it.isDigit() }) {
            return Target.Artifact(owner, repo, parts.last())
        }

        if (parts.getOrNull(2) == "releases") {
            return when {
                // /releases/download/<tag>/<file> is the asset itself.
                parts.getOrNull(3) == "download" && parts.size >= 6 -> file(url, parts)
                parts.getOrNull(3) == "tag" && parts.size >= 5 ->
                    Target.Listing("$api/releases/tags/${enc(parts[4])}", ListingShape.RELEASE_ASSETS)
                parts.getOrNull(3) == "latest" ->
                    Target.Listing("$api/releases/latest", ListingShape.RELEASE_ASSETS)
                else -> Target.Listing("$api/releases?per_page=$RELEASE_PAGE", ListingShape.RELEASE_LIST)
            }
        }
        return null
    }

    private fun gist(host: String, parts: List<String>): Target? {
        if (host != "gist.github.com") return null
        // gist.github.com/<user>/<id> and gist.github.com/<id> are both used.
        val id = parts.lastOrNull { it.length >= GIST_ID_MIN && it.all { c -> c.isLetterOrDigit() } }
            ?: return null
        return Target.Listing("https://api.github.com/gists/$id", ListingShape.GIST)
    }

    private fun gitLabRelease(host: String, parts: List<String>): Target? {
        // /<group>/<project>/-/releases and /-/releases/<tag>. The project path
        // is everything before the "-", groups included.
        val dash = parts.indexOf("-")
        if (dash < 2 || parts.getOrNull(dash + 1) != "releases") return null
        val project = parts.subList(0, dash).joinToString("/")
        val api = "https://$host/api/v4/projects/${enc(project)}/releases"
        val tag = parts.getOrNull(dash + 2)
        return if (tag.isNullOrBlank()) {
            Target.Listing("$api?per_page=$RELEASE_PAGE", ListingShape.GITLAB_RELEASE_LIST)
        } else {
            Target.Listing("$api/${enc(tag)}", ListingShape.GITLAB_RELEASE)
        }
    }

    /**
     * A release page on a Forgejo or Gitea instance, which every one of them
     * spells the way GitHub does and answers for under `/api/v1`. Reached only
     * for hosts the GitHub branch above did not claim.
     */
    private fun forgeRelease(url: String, host: String, parts: List<String>): Target? {
        if (parts.size < 3 || parts[2] != "releases") return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val api = "https://$host/api/v1/repos/$owner/$repo"
        return when {
            parts.getOrNull(3) == "download" && parts.size >= 6 -> file(url, parts)
            parts.getOrNull(3) == "tag" && parts.size >= 5 ->
                Target.Listing("$api/releases/tags/${enc(parts[4])}", ListingShape.RELEASE_ASSETS)
            parts.getOrNull(3) == "latest" ->
                Target.Listing("$api/releases/latest", ListingShape.RELEASE_ASSETS)
            else -> Target.Listing("$api/releases?limit=$RELEASE_PAGE", ListingShape.RELEASE_LIST)
        }
    }

    /**
     * A repository page on any forge [RepoLocation] can place: a file inside it
     * resolves to that file's raw address, a folder to a listing of what is in
     * it, and the repository root to both a listing and a probe for the addon
     * repository manifest that may sit there.
     */
    private fun repoPage(url: String): Target? {
        val (location, inner) = RepoLocation.fromPageUrl(url) ?: return null
        if (!location.isComplete) return null
        if (inner.isNotEmpty() && looksLikeFile(inner)) {
            return Target.File(location.rawUrl(inner), inner.substringAfterLast('/'))
        }
        val listing = location.directoryListingUrl(inner)
        val shape = when {
            listing == null -> ListingShape.NONE
            location.forge == GitForge.GITLAB -> ListingShape.GITLAB_TREE
            else -> ListingShape.CONTENTS
        }
        return Target.Listing(
            apiUrl = listing.orEmpty(),
            shape = shape,
            // Only the repository root is probed for a manifest. A link into a
            // folder is a link to that folder, and [AddonRepoCodec] resolves
            // every repository address to the manifest at the root, so probing
            // for one here would answer a link to `/tree/main/themes` with
            // whatever sits at the top of the repository.
            manifestProbe = if (inner.isEmpty()) AddonRepoCodec.resolveManifestUrl(url) else null,
            location = location,
            dir = inner,
        )
    }

    // ---- helpers ---------------------------------------------------------

    /**
     * A last path segment with a dot in it is a file name; anything else is a
     * folder. Crude, and right for what this decides: a wrong guess either way
     * costs one request that comes back with nothing to import.
     */
    private fun looksLikeFile(path: String): Boolean = path.substringAfterLast('/').contains('.')

    /**
     * The download target for an address that is already the file.
     *
     * The name is the last path segment, percent-decoded, because that is what
     * the importer reads a `.wmtheme.json` out of. An address with no usable
     * segment gets a neutral one: the bytes still decide every format that
     * carries a tag, and the one that does not (a theme) was never going to be
     * recognised from an address with no file name in it anyway.
     */
    private fun file(url: String, parts: List<String>): Target.File {
        val raw = parts.lastOrNull().orEmpty().substringBefore('?')
        val name = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return Target.File(url, name.ifBlank { FALLBACK_NAME })
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** Releases to look through when a link names no particular one. */
    private const val RELEASE_PAGE = 10

    /** A gist id is 32 hex characters; older ones are shorter. */
    private const val GIST_ID_MIN = 16

    const val FALLBACK_NAME = "shared-file"
}
