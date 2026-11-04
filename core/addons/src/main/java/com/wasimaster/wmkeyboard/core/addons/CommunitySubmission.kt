package com.wasimaster.wmkeyboard.core.addons

import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sending an addon to the community repository: the issue the app opens for
 * it, and how the file gets into that issue. Themes, layouts, icon packs,
 * sticker packs and snippets go from the app; the repository also takes sound
 * packs, which the app has no way to make.
 *
 * The repository takes submissions as GitHub issues filled in from a form. Its
 * bot reads the payload out of the issue, checks it with the same importers the
 * app uses, renders a preview and, once a maintainer approves it, publishes it.
 * So everything here is shaped by what an issue form can carry:
 *
 * - [Tier.LINK]: the whole payload rides in the link that opens the form, so
 *   the user lands on a filled form and only presses Submit. Fits colour and
 *   gradient themes, every layout and most snippet packs.
 * - [Tier.PASTE]: too long for a link, short enough for the form's text box.
 *   The app copies it and the user pastes it.
 * - [Tier.ATTACH]: a theme carrying photos, and nearly every pack. The app
 *   writes a zip the user drags into the box, which GitHub turns into an
 *   attachment link.
 *
 * The encoding is a contract with the bot, which keeps the same test vectors
 * (`community-submission-vectors.json`): [PREFIX], then base64url without
 * padding of the zlib-wrapped DEFLATE of the file's bytes, which for a JSON
 * kind is its UTF-8 text and for a [Kind.binary] one the pack's own zip. Two
 * encoders may produce different bytes for the same file, so only decoding
 * is compared.
 */
object CommunitySubmission {

    /** Issues are filed here whatever mirror the manifest is read from: the bot runs on GitHub. */
    const val REPO_URL = AddonStore.COMMUNITY_URL

    const val PREFIX = "wmkb:1:"

    /**
     * Longest link the app opens with the payload in it. GitHub answers a
     * longer one with an error page rather than a form; 8 KB is where browsers
     * and its front end stop agreeing, so this stays short of it.
     */
    const val LINK_LIMIT = 7_500

    /** Longest payload worth pasting: an issue body holds 65,536 characters, and the form has other fields. */
    const val PASTE_LIMIT = 60_000

    enum class Kind(
        val type: AddonType,
        /** The issue form, `.github/ISSUE_TEMPLATE/<template>` in the community repository. */
        val template: String,
        /** The one file inside an attached zip, for a kind that is not [binary]. */
        val fileName: String,
        val titlePrefix: String,
        /**
         * The file is a zip of its own (`pack.json` and its pictures or
         * sounds), which goes to GitHub as it is, named `.zip`; the bot knows
         * it by its `pack.json`. The rest are one JSON file, wrapped in a zip.
         */
        val binary: Boolean = false,
        /**
         * The most the repository takes: an issue attachment stops at 24 MB,
         * under the app's own 64 MB for stickers.
         */
        val maxBytes: Long = type.maxBytes,
    ) {
        THEME(AddonType.Theme, "submit-theme.yml", "theme.wmtheme.json", "[Theme]"),
        LAYOUT(AddonType.Layout, "submit-layout.yml", "layout.wmlayout.json", "[Layout]"),
        ICON_PACK(AddonType.IconPack, "submit-icon-pack.yml", "pack.wmicons", "[Icon pack]", binary = true),
        STICKERS(
            AddonType.Stickers,
            "submit-stickers.yml",
            "pack.wmstickers",
            "[Sticker pack]",
            binary = true,
            maxBytes = 24L * 1024 * 1024,
        ),
        SNIPPETS(AddonType.Snippets, "submit-snippets.yml", "snippets.wmsnippets.json", "[Snippets]"),
    }

    enum class Tier { LINK, PASTE, ATTACH }

    /** The licences the form offers, by SPDX id; the form's dropdown lists these exact labels. */
    val LICENSES = listOf("CC0-1.0", "CC-BY-4.0", "CC-BY-SA-4.0")

    /** What the user filled in; each field is a form input id of the same name. */
    data class Fields(
        val name: String,
        val description: String = "",
        val tags: List<String> = emptyList(),
        val license: String = LICENSES.first(),
        /** Id of an addon this user published before, to send a new version of it. */
        val updates: String = "",
        /** `"<repoId>/<addonId>"` of the addon this one remixes. */
        val basedOn: String = "",
    )

    /** One submission, ready to open. */
    data class Plan(
        val tier: Tier,
        /** The link to open. Carries the payload only on [Tier.LINK]. */
        val url: String,
        /** The encoded payload: what [Tier.PASTE] copies, and what a [Tier.LINK] URL carries. */
        val encoded: String,
    )

    /** Picks the smallest way [json] fits and builds the link for it. */
    fun plan(kind: Kind, fields: Fields, json: String): Plan = plan(kind, fields, json.toByteArray(Charsets.UTF_8))

    /** Picks the smallest way [file] fits and builds the link for it. */
    fun plan(kind: Kind, fields: Fields, file: ByteArray): Plan {
        val encoded = encodePayload(file)
        val withPayload = issueUrl(kind, fields, encoded)
        return when {
            withPayload.length <= LINK_LIMIT -> Plan(Tier.LINK, withPayload, encoded)
            encoded.length <= PASTE_LIMIT -> Plan(Tier.PASTE, issueUrl(kind, fields, null), encoded)
            else -> Plan(Tier.ATTACH, issueUrl(kind, fields, null), encoded)
        }
    }

    /**
     * The link that opens [kind]'s form with [fields] filled in, and the
     * payload too when [payload] is given. Every value is percent-encoded with
     * `%20` for a space: GitHub's form prefill does not read `+` as one.
     */
    fun issueUrl(kind: Kind, fields: Fields, payload: String?): String {
        val params = buildList {
            add("template" to kind.template)
            add("title" to "${kind.titlePrefix} ${fields.name.trim()}")
            add("name" to fields.name.trim())
            if (fields.description.isNotBlank()) add("description" to fields.description.trim())
            val tags = fields.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (tags.isNotEmpty()) add("tags" to tags.joinToString(", "))
            add("license" to fields.license)
            if (fields.updates.isNotBlank()) add("updates" to fields.updates.trim())
            if (fields.basedOn.isNotBlank()) add("based_on" to fields.basedOn.trim())
            if (payload != null) add("payload" to payload)
        }
        return "$REPO_URL/issues/new?" + params.joinToString("&") { (k, v) -> "$k=${v.urlEncoded()}" }
    }

    fun encodePayload(json: String): String = encodePayload(json.toByteArray(Charsets.UTF_8))

    fun encodePayload(file: ByteArray): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { it.write(file) }
        return PREFIX + Base64Url.encode(out.toByteArray())
    }

    /** The JSON in [text], or null when it is not an encoded payload. Surrounding whitespace is allowed. */
    fun decodePayload(text: String): String? = decodePayloadBytes(text)?.toString(Charsets.UTF_8)

    /** The file in [text], or null when it is not an encoded payload. */
    fun decodePayloadBytes(text: String): ByteArray? {
        val body = text.trim().takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
        val bytes = Base64Url.decode(body) ?: return null
        return runCatching {
            InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
        }.getOrNull()
    }

    /** The zip a [Tier.ATTACH] submission hands the user: one entry, [Kind.fileName]. */
    fun attachment(kind: Kind, json: String): ByteArray = attachment(kind, json.toByteArray(Charsets.UTF_8))

    /**
     * The zip a [Tier.ATTACH] submission hands the user. A [Kind.binary] pack
     * is a zip already and goes as it is; anything else is wrapped as one
     * entry, [Kind.fileName].
     */
    fun attachment(kind: Kind, file: ByteArray): ByteArray {
        if (kind.binary) return file
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(kind.fileName))
            zip.write(file)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** What the attachment is saved as: GitHub accepts a `.zip` in an issue, not a `.json`. */
    fun attachmentName(kind: Kind, name: String): String {
        val stem = name.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { kind.type.name.lowercase() }
        return "$stem.zip"
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

/**
 * Base64 with the URL alphabet and no padding. Written out rather than taken
 * from `java.util.Base64`, which needs API 26 on a module whose floor is 24,
 * and `android.util.Base64`, which does nothing in a JVM unit test.
 */
internal object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val n = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[n shr 18 and 63]).append(ALPHABET[n shr 12 and 63])
            if (i + 1 < bytes.size) out.append(ALPHABET[n shr 6 and 63])
            if (i + 2 < bytes.size) out.append(ALPHABET[n and 63])
            i += 3
        }
        return out.toString()
    }

    /** Null on a character outside the alphabet or an impossible length. Accepts `+`, `/` and padding too. */
    fun decode(text: String): ByteArray? {
        val clean = text.trimEnd('=').replace('+', '-').replace('/', '_').filterNot { it.isWhitespace() }
        if (clean.length % 4 == 1) return null
        val out = ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer shr bits and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
