package com.wasimaster.wmkeyboard.ime.wmpass

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the WM Pass tool asks WM Pass's provider, and what comes back. WM
 * Pass writes the same format in its own `VaultWire`, where the calls are
 * set out one by one. A change here is a change there too.
 *
 * In short: every reply carries [STATE] (`open`, `locked`, `none`, `off`),
 * and only an open one carries more. [ITEMS] lists the vault, every kind, the
 * app in front's first, as rows with no secret in them. [ITEM] is one item's
 * page: each field's `key`, `label`, `secret`, and `value` only when it is
 * not secret. [VALUE] hands over one value to type, the only call that
 * brings a secret here, and only on the tap that types it. [COPY] has WM Pass
 * copy a value itself, so a copied secret never passes through this process.
 * [ADD_LOGIN], [SET_PASSWORD] and [GENERATE] add, change and make passwords.
 * An item opens in WM Pass by starting it with [ROUTE_EXTRA] set to
 * [openRoute].
 */
object VaultWire {
    const val VERSION = 1

    const val STATE = "state"
    const val STATUS = "status"
    const val ITEMS = "items"
    const val ITEM = "item"
    const val VALUE = "value"
    const val COPY = "copy"
    const val ADD_LOGIN = "add_login"
    const val SET_PASSWORD = "set_password"
    const val GENERATE = "generate"

    const val QUERY = "query"
    const val FIELD = "field"
    const val TITLE = "title"
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val WEBSITE = "website"
    const val APP = "app"

    const val ROWS = "rows"
    const val PAGE = "item"
    const val COPIED = "copied"
    const val ID = "id"
    const val SAVED = "saved"

    /** The keys of what an item holds beside its kind's own fields. */
    const val CODE = "code"
    const val NOTES = "notes"
    const val SITE = "site:"
    const val CUSTOM = "custom:"

    const val ROUTE_EXTRA = "route"

    fun openRoute(id: String): String = "item:$id"

    /** One item as the list shows it. Nothing secret. */
    data class Row(
        val id: String,
        val kind: String,
        val title: String,
        val subtitle: String,
        val matches: Boolean,
        val hasPassword: Boolean,
        val hasCode: Boolean,
        val favourite: Boolean,
    )

    /** One field of an item. [value] is there only when it is not [secret]. */
    data class Field(val key: String, val label: String, val secret: Boolean, val value: String?)

    /** One item opened: its fields, and [changes], the field [SET_PASSWORD] changes, when it has one. */
    data class Page(
        val id: String,
        val kind: String,
        val title: String,
        val subtitle: String,
        val fields: List<Field>,
        val changes: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** The rows of an [ITEMS] reply; empty for anything that does not parse. */
    fun rows(raw: String?): List<Row> {
        val array = raw?.let { runCatching { json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return emptyList()
        return array.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.str("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Row(
                id = id,
                kind = o.str("kind"),
                title = o.str("title"),
                subtitle = o.str("subtitle"),
                matches = o.flag("matches"),
                hasPassword = o.flag("has_password"),
                hasCode = o.flag("has_code"),
                favourite = o.flag("favourite"),
            )
        }
    }

    /**
     * The page of an [ITEM] reply, or null. A secret field keeps no value
     * here even if one were sent: it is asked for on the tap that uses it.
     */
    fun page(raw: String?): Page? {
        val o = raw?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val id = o.str("id").takeIf { it.isNotEmpty() } ?: return null
        val fields = (o["fields"] as? JsonArray).orEmpty().mapNotNull { element ->
            val f = element as? JsonObject ?: return@mapNotNull null
            val key = f.str("key").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val secret = f.flag("secret")
            Field(key, f.str("label").ifEmpty { key }, secret, if (secret) null else f.str("value"))
        }
        return Page(id, o.str("kind"), o.str("title"), o.str("subtitle"), fields, o.str("changes").ifEmpty { null })
    }

    private fun JsonObject.str(key: String): String =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()

    private fun JsonObject.flag(key: String): Boolean =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
}
