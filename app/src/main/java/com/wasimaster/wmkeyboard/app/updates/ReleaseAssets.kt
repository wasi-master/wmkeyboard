package com.wasimaster.wmkeyboard.app.updates

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The five things an asset's file name says about it.
 *
 * The release workflow builds these names, in `.github/workflows/release.yml`:
 * `wmkeyboard-<versionName>-vc<versionCode>-<flavor>-<languages>-<abi>.apk`,
 * where [flavor] is the edition (full, or lite up to 0.6.2; see
 * [ReleaseAssets.STANDARD_FLAVOR]) and [languages] the `languages` flavour
 * (intl, en). Releases before 0.5.10 had no languages part and
 * were English only, so a name without one reads as `en`. Reading the version
 * out of the name rather than out of the tag is what lets this compare
 * versions exactly: the tag is a version *name*, and names are not ordered.
 */
internal data class AssetName(
    val versionName: String,
    val versionCode: Int,
    val flavor: String,
    val abi: String,
    val languages: String = ReleaseAssets.LANGUAGES_EN,
)

/** An update this device could actually install, with everything needed to do it. */
@Serializable
internal data class UpdateCandidate(
    val versionCode: Int,
    val versionName: String,
    val tag: String,
    val assetName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String?,
    val publishedAtMillis: Long?,
    val releaseUrl: String,
) {
    /** Where this version's long-form release notes live. */
    val releaseNotesUrl: String get() = GithubReleases.releaseNotesUrl(tag, versionName)

    /** The short store changelog, read only when [releaseNotesUrl] is not there. */
    val changelogUrl: String get() = GithubReleases.changelogUrl(tag, versionCode)
}

/** Reading release assets, and choosing the one this device wants. */
internal object ReleaseAssets {

    /**
     * The universal APK, which carries every ABI and is the fallback for a
     * device whose own ABI has no split. Twice the size, and always correct.
     */
    const val UNIVERSAL = "universal"

    /**
     * The edition token in the standard build's asset names, which every
     * install moves onto. The app has had no `capabilities` flavour since
     * 0.6.3, but every install out there only parses names that carry `full`
     * or `lite` here, so the release workflow keeps writing `full` and this
     * app asks for it.
     */
    const val STANDARD_FLAVOR = "full"

    /** The `languages` flavour that carries English only. GitHub is the only place it ships. */
    const val LANGUAGES_EN = "en"

    /** The `languages` flavour that carries every translation. */
    const val LANGUAGES_INTL = "intl"

    /**
     * Anchored, and the version part is lazy so that a pre-release name like
     * `wmkeyboard-0.6.0-beta.1-vc15-full-intl-arm64-v8a.apk` splits at the
     * right hyphen. The languages part is optional, for the names releases
     * carried before it existed. The ABI list is spelled out rather than
     * matched loosely so a file this app cannot use never looks like one it can,
     * which is also why the x86_64 APKs older releases carried no longer parse.
     */
    private val ASSET = Regex(
        """^wmkeyboard-(.+?)-vc(\d+)-(full|lite)(?:-(intl|en))?-(arm64-v8a|armeabi-v7a|universal)\.apk$""",
    )

    private val SHA256 = Regex("""^[0-9a-f]{64}$""")

    /**
     * The dex metadata the release workflow publishes beside the APKs:
     * `wmkeyboard-<versionName>-vc<versionCode>-<flavor>-<languages>-api<minApi>.dm`,
     * one per variant and API range (the ABI splits share one dex).
     */
    private val DEX_METADATA = Regex(
        """^wmkeyboard-(.+?)-vc(\d+)-(full|lite)-(intl|en)-api(\d+)\.dm$""",
    )

    /**
     * The first API level each published profile is for, newest first. AGP
     * writes one for 31 and up and one for 28 to 30; below 28 there is nothing
     * Android would compile with.
     */
    private val DEX_METADATA_MIN_APIS = listOf(31, 28)

    /** The "abi" a parsed dex metadata name reports, so it never matches an APK's. */
    const val DEX_METADATA_ABI = "dm"

    /** What [name] says about itself, or null when it is not one of our APKs. */
    fun parseAssetName(name: String): AssetName? {
        val match = ASSET.matchEntire(name) ?: return null
        val (versionName, versionCode, flavor, languages, abi) = match.destructured
        val code = versionCode.toIntOrNull() ?: return null
        return AssetName(versionName, code, flavor, abi, languages.ifEmpty { LANGUAGES_EN })
    }

    /** What a dex metadata name says about itself, or null when it is not one of ours. */
    fun parseDexMetadataName(name: String): AssetName? {
        val match = DEX_METADATA.matchEntire(name) ?: return null
        val (versionName, versionCode, flavor, languages) = match.destructured
        val code = versionCode.toIntOrNull() ?: return null
        return AssetName(versionName, code, flavor, DEX_METADATA_ABI, languages)
    }

    /**
     * The dex metadata that goes with [apkAssetName] on a device at [sdkInt],
     * or null when there is none to want: an APK name this app does not
     * write, or an Android older than any published profile.
     *
     * Derived from the APK's name rather than read off the release, so the
     * updater needs nothing more than it already has; a release published
     * before these existed simply answers the download with a 404.
     */
    fun dexMetadataName(apkAssetName: String, sdkInt: Int): String? {
        val apk = parseAssetName(apkAssetName) ?: return null
        val api = DEX_METADATA_MIN_APIS.firstOrNull { sdkInt >= it } ?: return null
        return "wmkeyboard-${apk.versionName}-vc${apk.versionCode}-${apk.flavor}-${apk.languages}-api$api.dm"
    }

    /**
     * The hex digest in a `sha256:…` value, or null when there is not one.
     *
     * Strict about the prefix: a value in some other form is an unknown
     * algorithm, and treating its bytes as a SHA-256 would fail every download
     * rather than skip the check.
     */
    fun sha256Of(digest: String?): String? {
        val value = digest ?: return null
        if (!value.startsWith(SHA256_PREFIX)) return null
        return value.removePrefix(SHA256_PREFIX).lowercase().takeIf { SHA256.matches(it) }
    }

    /**
     * The asset in [release] this device should download, or null when the
     * release carries nothing for it.
     *
     * [supportedAbis] is [android.os.Build.SUPPORTED_ABIS], which is ordered
     * by the device's own preference, so the first one with a matching asset
     * is the right answer. The universal APK is the last resort, for a device
     * whose first ABI has no split of its own.
     *
     * [flavor] is the edition token, [STANDARD_FLAVOR] for this app, and
     * [languages] the `languages` flavour (`BuildConfig.FLAVOR`, the only dimension).
     */
    fun pickAsset(
        release: GithubRelease,
        flavor: String,
        supportedAbis: List<String>,
        languages: String = LANGUAGES_EN,
    ): GithubAsset? {
        fun matching(wantFlavor: String, wantLanguages: String) = release.assets.mapNotNull { asset ->
            parseAssetName(asset.name)
                ?.takeIf { it.flavor == wantFlavor && it.languages == wantLanguages }
                ?.let { it to asset }
        }
        // From 0.6.3 there is no lite build on GitHub: a lite install moves
        // onto the full one with its own languages. 0.6.3 itself carried no
        // all-languages build either, only copies of the standard one under
        // its name, so where a release has neither, the standard build
        // (English, which fetches its other languages) is the answer.
        val ours = matching(flavor, languages)
            .ifEmpty { matching(STANDARD_FLAVOR, languages) }
            .ifEmpty { matching(STANDARD_FLAVOR, LANGUAGES_EN) }
        if (ours.isEmpty()) return null
        for (abi in supportedAbis) {
            ours.firstOrNull { it.first.abi == abi }?.let { return it.second }
        }
        return ours.firstOrNull { it.first.abi == UNIVERSAL }?.second
    }

    /**
     * The newest release worth offering, or null when this install is current.
     *
     * Drafts never reach an unauthenticated caller, so filtering them is
     * belt and braces; pre-releases do, and whether to offer them is the
     * user's setting. A release that carries no file for this device is
     * skipped rather than offered and then failed, which is why this walks the
     * list instead of only looking at the first entry.
     *
     * [allowSameVersion] is for moving to the other [languages] build of the
     * release already installed: same version code, same key, a different APK.
     */
    @Suppress("LongParameterList")
    fun chooseCandidate(
        releases: List<GithubRelease>,
        installedVersionCode: Int,
        flavor: String,
        supportedAbis: List<String>,
        includePrereleases: Boolean,
        languages: String = LANGUAGES_EN,
        allowSameVersion: Boolean = false,
    ): UpdateCandidate? = releases
        .asSequence()
        .filterNot { it.draft }
        .filter { includePrereleases || !it.prerelease }
        .mapNotNull { release ->
            val asset = pickAsset(release, flavor, supportedAbis, languages) ?: return@mapNotNull null
            val parsed = parseAssetName(asset.name) ?: return@mapNotNull null
            UpdateCandidate(
                versionCode = parsed.versionCode,
                versionName = parsed.versionName,
                tag = release.tagName,
                assetName = asset.name,
                url = asset.browserDownloadUrl,
                sizeBytes = asset.size,
                sha256 = sha256Of(asset.digest),
                publishedAtMillis = publishedAtMillis(release.publishedAt),
                releaseUrl = release.htmlUrl ?: GithubReleases.releasePage(release.tagName),
            )
        }
        .filter {
            val newEnough = if (allowSameVersion) {
                it.versionCode >= installedVersionCode
            } else {
                it.versionCode > installedVersionCode
            }
            newEnough && it.url.startsWith("https://")
        }
        .maxByOrNull { it.versionCode }

    /**
     * GitHub's `published_at` as epoch milliseconds, or null.
     *
     * [SimpleDateFormat] rather than `java.time`: minSdk is 24, this project
     * has no core library desugaring, and `NewApi` is an error.
     */
    fun publishedAtMillis(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        return runCatching { format.parse(iso)?.time }.getOrNull()
    }

    /**
     * How many days old [publishedAtMillis] is, or -1 when it is unknown.
     *
     * -1 rather than 0 because [UpdatePolicy] reads a negative staleness as
     * "no idea" and refuses to prompt on it, which is the right answer for a
     * release whose date did not parse.
     */
    fun stalenessDays(publishedAtMillis: Long?, now: Long): Int {
        if (publishedAtMillis == null || now < publishedAtMillis) return -1
        return ((now - publishedAtMillis) / DAY_MILLIS).toInt()
    }

    /**
     * The digest [assetName] is listed with in a `SHA256SUMS.txt` body, or
     * null. The file is `sha256sum` output: one `<hex>  <name>` line per file.
     */
    fun sha256From(checksums: String, assetName: String): String? = checksums
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("""\s+"""), limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val hex = parts[0].lowercase()
            val name = parts[1].removePrefix("*").trim()
            if (name == assetName && SHA256.matches(hex)) hex else null
        }
        .firstOrNull()

    private const val SHA256_PREFIX = "sha256:"

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000
}
