package com.wasimaster.wmkeyboard.ime.calendar.invite

import com.wasimaster.wmkeyboard.core.net.InternetGate
import com.wasimaster.wmkeyboard.core.net.NetLogInterceptor
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Why a call to an invitation service did not work, sorted into what the
 * card can say about it. [SIGNED_OUT] and [REFUSED] need the settings page;
 * the rest are worth another try.
 */
class InviteFailure(val kind: Kind, message: String, val code: Int = 0) : IOException(message) {
    enum class Kind {
        /** No address, or one that is not a web address. */
        BAD_ADDRESS,

        /** The service no longer takes the account's tokens, and could not renew them. */
        SIGNED_OUT,

        /** The email and password, or the API token, were turned down. */
        BAD_LOGIN,

        /** The account may not do this: no right to create events, or no such organizer. */
        REFUSED,

        /** The service said no to what was sent, with [message] as its reason. */
        REJECTED,

        /** No answer, or one that is not what the service sends. */
        NETWORK,
    }
}

/** One shared client for both services; every request is in the network log as [NetSource.INVITES]. */
internal object InviteHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(InternetGate)
            .addNetworkInterceptor(NetLogInterceptor(NetSource.INVITES, route = { route(it.pathSegments) }))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * The path as the network log shows it. A Pretix event's slug is made
     * from the title the user typed, so it and the organizer become
     * placeholders: `/api/v1/organizers/{organizer}/events/{event}/items/`.
     */
    fun route(segments: List<String>): String {
        val named = segments.mapIndexed { i, seg ->
            when {
                segments.size > 3 && segments[0] == "api" && segments[2] == "organizers" && i == 3 -> "{organizer}"
                segments.size > 5 && segments[0] == "api" && segments[4] == "events" && i == 5 && seg.isNotEmpty() -> "{event}"
                else -> seg
            }
        }
        return "/" + named.joinToString("/")
    }

    /** `https://` in front of what was typed when it has no scheme, and no trailing slash. */
    fun root(server: String): HttpUrl? {
        var s = server.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "https://$s"
        return s.toHttpUrlOrNull()
    }

    const val DETAIL_MAX = 300
}

/**
 * The calls the Calendar tool makes to a Mobilizon instance's GraphQL API at
 * `/api` (#1274), as the instance's own documentation and schema have them:
 * `login(email, password)` and `refreshToken(refreshToken)` for the tokens,
 * `identities` and `loggedUser.memberships` for who may post, and
 * `createEvent` for the event itself. The access token goes in
 * `Authorization: Bearer …`, never in the address.
 *
 * Mobilizon answers most failures with HTTP 200 and an `errors` list; each
 * error may carry a `status_code`, which is what tells a lapsed token apart
 * from an event it turned down.
 */
class MobilizonClient(server: String) {

    val root: HttpUrl? = InviteHttp.root(server)

    data class Tokens(val access: String, val refresh: String)

    /** Who an event can be posted as: one of the user's profiles, or a group through one of them. */
    data class Organizer(val actorId: String, val groupId: String?, val name: String, val handle: String)

    data class Created(val id: String, val uuid: String, val url: String)

    /** The event as `createEvent` takes it; [descriptionHtml] is already HTML. */
    data class NewEvent(
        val organizer: Organizer?,
        val title: String,
        val descriptionHtml: String,
        val beginsOn: String,
        val endsOn: String?,
        val place: String?,
        val onlineAddress: String?,
        val listed: Boolean,
        val allDay: Boolean,
    )

    fun login(email: String, password: String): Tokens {
        val data = run(
            "mutation Login(\$email: String!, \$password: String!) { login(email: \$email, password: \$password) { accessToken refreshToken } }",
            JSONObject().put("email", email.trim()).put("password", password),
            token = null,
            loginCall = true,
        )
        return tokensOf(data.optJSONObject("login"))
    }

    fun refresh(refreshToken: String): Tokens {
        val data = run(
            "mutation RefreshToken(\$rt: String!) { refreshToken(refreshToken: \$rt) { accessToken refreshToken } }",
            JSONObject().put("rt", refreshToken),
            token = null,
            refreshCall = true,
        )
        return tokensOf(data.optJSONObject("refreshToken"))
    }

    /** The user's profiles, then each group a profile is a member of, as Mobilizon's guide lists them. */
    fun organizers(token: String): List<Organizer> {
        val out = ArrayList<Organizer>()
        val ids = run("query Identities { identities { id preferredUsername name } }", JSONObject(), token)
            .optJSONArray("identities") ?: JSONArray()
        for (i in 0 until ids.length()) {
            val a = ids.optJSONObject(i) ?: continue
            val id = a.optString("id")
            if (id.isBlank()) continue
            out += Organizer(id, null, a.optString("name").ifBlank { a.optString("preferredUsername") }, a.optString("preferredUsername"))
        }
        val members = run(
            "query Memberships(\$limit: Int) { loggedUser { memberships(limit: \$limit) { elements { role actor { id } parent { id preferredUsername name } } } } }",
            JSONObject().put("limit", MEMBERSHIP_LIMIT),
            token,
        ).optJSONObject("loggedUser")?.optJSONObject("memberships")?.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until members.length()) {
            val m = members.optJSONObject(i) ?: continue
            // An invited or pending member, or one who left, may not post for the group.
            if (m.optString("role") !in POSTING_ROLES) continue
            val actor = m.optJSONObject("actor")?.optString("id").orEmpty()
            val group = m.optJSONObject("parent") ?: continue
            val groupId = group.optString("id")
            if (actor.isBlank() || groupId.isBlank()) continue
            out += Organizer(actor, groupId, group.optString("name").ifBlank { group.optString("preferredUsername") }, group.optString("preferredUsername"))
        }
        return out
    }

    fun create(token: String, event: NewEvent): Created {
        val organizer = event.organizer ?: organizers(token).firstOrNull { it.groupId == null }
            ?: throw InviteFailure(InviteFailure.Kind.REFUSED, "This account has no profile to post as")
        val vars = JSONObject()
            .put("organizerActorId", organizer.actorId)
            .put("title", event.title)
            .put("description", event.descriptionHtml)
            .put("beginsOn", event.beginsOn)
            .put("visibility", if (event.listed) "PUBLIC" else "UNLISTED")
            .put("joinOptions", "FREE")
            .put("status", "CONFIRMED")
            .put("draft", false)
        organizer.groupId?.let { vars.put("attributedToId", it) }
        event.endsOn?.let { vars.put("endsOn", it) }
        event.onlineAddress?.let { vars.put("onlineAddress", it) }
        event.place?.let { vars.put("physicalAddress", JSONObject().put("description", it)) }
        if (event.allDay) vars.put("options", JSONObject().put("showStartTime", false).put("showEndTime", false))
        val data = run(
            "mutation CreateEvent(\$organizerActorId: ID!, \$attributedToId: ID, \$title: String!, \$description: String!, " +
                "\$beginsOn: DateTime!, \$endsOn: DateTime, \$status: EventStatus, \$visibility: EventVisibility, " +
                "\$joinOptions: EventJoinOptions, \$draft: Boolean, \$onlineAddress: String, \$physicalAddress: AddressInput, " +
                "\$options: EventOptionsInput) { createEvent(organizerActorId: \$organizerActorId, attributedToId: \$attributedToId, " +
                "title: \$title, description: \$description, beginsOn: \$beginsOn, endsOn: \$endsOn, status: \$status, " +
                "visibility: \$visibility, joinOptions: \$joinOptions, draft: \$draft, onlineAddress: \$onlineAddress, " +
                "physicalAddress: \$physicalAddress, options: \$options) { id uuid url } }",
            vars,
            token,
        )
        val e = data.optJSONObject("createEvent") ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon sent no event back")
        val uuid = e.optString("uuid")
        val url = e.optString("url").takeIf { it.startsWith("http") }
            ?: root?.newBuilder()?.addPathSegment("events")?.addPathSegment(uuid)?.build()?.toString()
            ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon sent no link back")
        return Created(e.optString("id"), uuid, url)
    }

    private fun tokensOf(o: JSONObject?): Tokens {
        val access = o?.optString("accessToken").orEmpty()
        if (access.isBlank()) throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon sent no token back")
        return Tokens(access, o?.optString("refreshToken").orEmpty())
    }

    /**
     * One GraphQL call; the `data` object back. A 401, or an error whose
     * `status_code` is 401, is a lapsed token (a turned-down password on the
     * login call itself); 403 is no right to it; anything else in `errors`
     * is the service turning the request down, with its own words.
     */
    private fun run(query: String, variables: JSONObject, token: String?, loginCall: Boolean = false, refreshCall: Boolean = false): JSONObject {
        val url = root?.newBuilder()?.addPathSegment("api")?.build()
            ?: throw InviteFailure(InviteFailure.Kind.BAD_ADDRESS, "Not a web address")
        val body = JSONObject().put("query", query).put("variables", variables).toString().toRequestBody(InviteHttp.JSON)
        val request = Request.Builder().url(url).post(body).header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        val response = try {
            InviteHttp.client.newCall(request).execute()
        } catch (e: IOException) {
            throw e as? InviteFailure ?: InviteFailure(InviteFailure.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        val text = response.use { it.body?.string().orEmpty() }
        val code = response.code
        val unauthorized = if (loginCall) InviteFailure.Kind.BAD_LOGIN else InviteFailure.Kind.SIGNED_OUT
        if (code == 401) throw InviteFailure(unauthorized, "Mobilizon answered 401", code)
        if (code == 403) throw InviteFailure(InviteFailure.Kind.REFUSED, "Mobilizon answered 403", code)
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon answered $code ${text.take(InviteHttp.DETAIL_MAX)}".trim(), code)
        val errors = json.optJSONArray("errors")
        if (errors != null && errors.length() > 0) {
            val first = errors.optJSONObject(0)
            val status = first?.optInt("status_code", 0) ?: 0
            val message = first?.optString("message").orEmpty().ifBlank { "Mobilizon turned it down" }
            throw when {
                status == 401 || (refreshCall && status in 400..499) -> InviteFailure(unauthorized, message, status)
                loginCall -> InviteFailure(InviteFailure.Kind.BAD_LOGIN, message, status)
                status == 403 -> InviteFailure(InviteFailure.Kind.REFUSED, message, status)
                else -> InviteFailure(InviteFailure.Kind.REJECTED, message, status)
            }
        }
        if (code !in 200..299) throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon answered $code", code)
        return json.optJSONObject("data") ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Mobilizon sent no data back", code)
    }

    private companion object {
        const val MEMBERSHIP_LIMIT = 50
        val POSTING_ROLES = setOf("MEMBER", "MODERATOR", "ADMINISTRATOR", "CREATOR")
    }
}

/**
 * The calls the Calendar tool makes to a Pretix server's REST API (#1274),
 * as Pretix documents them: `POST /api/v1/organizers/{organizer}/events/`
 * (always created offline), one free admission item and a quota over it so
 * people can sign up, the description as the shop's front page text, then
 * `PATCH … {"live": true}`. The token goes in `Authorization: Token …`.
 */
class PretixClient(server: String, private val token: String, private val organizer: String) {

    val root: HttpUrl? = InviteHttp.root(server)

    data class NewEvent(
        val title: String,
        val description: String,
        val dateFrom: String,
        val dateTo: String?,
        val place: String?,
        val timezone: String,
        val currency: String,
        val listed: Boolean,
        /** How many can sign up; null for no limit. */
        val places: Int?,
        val ticketName: String,
        /** The language codes the names are given in: Pretix shows the one nearest the visitor's. */
        val languages: List<String>,
    )

    /**
     * What publishing came to. [live] false means the event exists but
     * Pretix would not open its shop: [problem] is why, and [adminUrl] is the
     * page that says what is missing.
     */
    data class Created(val slug: String, val publicUrl: String, val adminUrl: String, val live: Boolean, val problem: String? = null)

    /** The organizer's display name, to show that the token and organizer go together. */
    fun check(): String {
        val o = JSONObject(call("GET", base().addPathSegment("").build(), null))
        return o.optString("name").ifBlank { organizer }
    }

    fun create(event: NewEvent, slugFor: (attempt: Int) -> String): Created {
        val events = base().addPathSegment("events").addPathSegment("").build()
        var slug = ""
        var created: JSONObject? = null
        // A clash on the slug is the only failure worth one more go, with a fresh suffix.
        for (attempt in 0 until SLUG_TRIES) {
            slug = slugFor(attempt)
            try {
                created = JSONObject(call("POST", events, eventBody(event, slug)))
                break
            } catch (e: InviteFailure) {
                val slugTaken = e.kind == InviteFailure.Kind.REJECTED && e.message.orEmpty().contains("slug", ignoreCase = true)
                if (!slugTaken || attempt == SLUG_TRIES - 1) throw e
            }
        }
        val made = created ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Pretix sent no event back")
        slug = made.optString("slug").ifBlank { slug }
        val eventUrl = base().addPathSegment("events").addPathSegment(slug)
        val item = JSONObject(
            call(
                "POST",
                eventUrl.newBuilder().addPathSegment("items").addPathSegment("").build(),
                JSONObject()
                    .put("name", names(event.ticketName, event.languages))
                    .put("default_price", "0.00")
                    .put("active", true)
                    .put("admission", true)
                    .put("free_price", false),
            ),
        )
        val itemId = item.optInt("id", 0).takeIf { it > 0 } ?: throw InviteFailure(InviteFailure.Kind.NETWORK, "Pretix sent no ticket back")
        call(
            "POST",
            eventUrl.newBuilder().addPathSegment("quotas").addPathSegment("").build(),
            JSONObject()
                .put("name", event.ticketName)
                .put("size", event.places ?: JSONObject.NULL)
                .put("items", JSONArray().put(itemId))
                .put("variations", JSONArray())
                .put("subevent", JSONObject.NULL),
        )
        if (event.description.isNotBlank()) {
            // The shop's front page text; an older server or a token without
            // the settings right only loses the description, not the event.
            runCatching {
                call(
                    "PATCH",
                    eventUrl.newBuilder().addPathSegment("settings").addPathSegment("").build(),
                    JSONObject().put("frontpage_text", names(event.description, event.languages)),
                )
            }
        }
        val publicUrl = made.optString("public_url").takeIf { it.startsWith("http") }
            ?: root!!.newBuilder().addPathSegment(organizer).addPathSegment(slug).addPathSegment("").build().toString()
        val adminUrl = root!!.newBuilder().addPathSegments("control/event").addPathSegment(organizer).addPathSegment(slug)
            .addPathSegment("live").addPathSegment("").build().toString()
        return try {
            call("PATCH", eventUrl.newBuilder().addPathSegment("").build(), JSONObject().put("live", true))
            Created(slug, publicUrl, adminUrl, live = true)
        } catch (e: InviteFailure) {
            if (e.kind == InviteFailure.Kind.NETWORK) throw e
            Created(slug, publicUrl, adminUrl, live = false, problem = e.message)
        }
    }

    private fun eventBody(e: NewEvent, slug: String): JSONObject = JSONObject()
        .put("name", names(e.title, e.languages))
        .put("slug", slug)
        .put("live", false)
        .put("testmode", false)
        .put("currency", e.currency)
        .put("date_from", e.dateFrom)
        .put("date_to", e.dateTo ?: JSONObject.NULL)
        .put("is_public", e.listed)
        .put("location", e.place?.let { names(it, e.languages) } ?: JSONObject.NULL)
        .put("has_subevents", false)
        .put("timezone", e.timezone)
        .put("all_sales_channels", true)

    /** A multi-lingual string: the same text under each language, which Pretix falls back between. */
    private fun names(text: String, languages: List<String>): JSONObject =
        JSONObject().apply { languages.ifEmpty { listOf("en") }.distinct().forEach { put(it, text) } }

    private fun base(): HttpUrl.Builder = (root ?: throw InviteFailure(InviteFailure.Kind.BAD_ADDRESS, "Not a web address"))
        .newBuilder().addPathSegments("api/v1/organizers").addPathSegment(organizer.trim())

    private fun call(method: String, url: HttpUrl, body: JSONObject?): String {
        val request = Request.Builder().url(url)
            .header("Authorization", "Token $token")
            .header("Accept", "application/json")
            .method(method, body?.toString()?.toRequestBody(InviteHttp.JSON))
            .build()
        val response = try {
            InviteHttp.client.newCall(request).execute()
        } catch (e: IOException) {
            throw e as? InviteFailure ?: InviteFailure(InviteFailure.Kind.NETWORK, e.message ?: e.javaClass.simpleName)
        }
        val text = response.use { it.body?.string().orEmpty() }
        val code = response.code
        if (code in 200..299) return text
        val detail = reason(text)
        throw when (code) {
            401 -> InviteFailure(InviteFailure.Kind.BAD_LOGIN, detail ?: "Pretix answered 401", code)
            403, 404 -> InviteFailure(InviteFailure.Kind.REFUSED, detail ?: "Pretix answered $code", code)
            in 400..499 -> InviteFailure(InviteFailure.Kind.REJECTED, detail ?: "Pretix answered $code", code)
            else -> InviteFailure(InviteFailure.Kind.NETWORK, detail ?: "Pretix answered $code", code)
        }
    }

    /** Pretix's own words: `{"detail": …}`, or `{"field": ["…"]}` for a form error, or a list of reasons. */
    private fun reason(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        runCatching {
            val o = JSONObject(t)
            o.optString("detail").takeIf { it.isNotBlank() }?.let { return it }
            val parts = o.keys().asSequence().map { k ->
                val v = o.opt(k)
                val said = if (v is JSONArray) (0 until v.length()).joinToString(" ") { v.optString(it) } else v.toString()
                "$k: $said"
            }.toList()
            if (parts.isNotEmpty()) return parts.joinToString("; ").take(InviteHttp.DETAIL_MAX)
        }
        runCatching {
            val a = JSONArray(t)
            return (0 until a.length()).joinToString(" ") { a.optString(it) }.take(InviteHttp.DETAIL_MAX)
        }
        return t.take(InviteHttp.DETAIL_MAX)
    }

    private companion object {
        const val SLUG_TRIES = 2
    }
}
