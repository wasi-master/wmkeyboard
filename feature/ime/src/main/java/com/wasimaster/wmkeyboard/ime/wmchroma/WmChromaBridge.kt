package com.wasimaster.wmkeyboard.ime.wmchroma

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.wasimaster.wmkeyboard.ime.suite.SuiteTrust
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID

/** A colour as WM Chroma hands it over: [text] is it in the person's copy format there. */
data class ChromaColour(val argb: Int, val name: String, val text: String)

data class ChromaFormat(val id: String, val label: String, val text: String)

/** A colour named and written in every format WM Chroma's detail sheet shows, its copy format first. */
data class ChromaDescribed(val colour: ChromaColour, val formats: List<ChromaFormat>)

data class ChromaPalette(val id: Long, val name: String, val pinned: Boolean, val swatches: List<ChromaColour>)

data class ChromaLibrary(val recent: List<ChromaColour>, val tray: List<ChromaColour>, val palettes: List<ChromaPalette>)

/** What WM Chroma said. [Off]: the person turned the link off there. [Missing]: not installed, or not the real one. */
sealed interface ChromaAnswer<out T> {
    data class Ok<T>(val value: T) : ChromaAnswer<T>
    data object Waiting : ChromaAnswer<Nothing>
    data object Off : ChromaAnswer<Nothing>
    data object Missing : ChromaAnswer<Nothing>
    data object Failed : ChromaAnswer<Nothing>
}

/**
 * The keyboard's side of the link with WM Chroma. Every call checks WM Chroma's signing
 * certificate first, and nothing is asked while it is missing. The colour engine, the names and
 * the library all stay in WM Chroma: the keyboard only asks. Calls block, so run them off the
 * main thread.
 */
object WmChromaBridge {
    private val APP: Uri = Uri.parse("content://${ChromaWire.APP_AUTHORITY}")

    private const val FILE = "wm_chroma_link"
    private const val PICK_TOKEN = "pick_token"
    private const val PICK_SINCE = "pick_since"

    fun installed(context: Context): Boolean = SuiteTrust.trusted(context, ChromaWire.APP_PACKAGE) == true

    /** Whether WM Chroma answers the keyboard now: installed, real and with the link on. */
    fun status(context: Context): ChromaAnswer<Unit> = ask(context, ChromaWire.STATUS, null) { }

    /** [text] read as a colour by WM Chroma; Failed when it is not one. */
    fun describe(context: Context, text: String): ChromaAnswer<ChromaDescribed> {
        if (text.isBlank() || text.length > ChromaWire.MAX_TEXT) return ChromaAnswer.Failed
        val extras = Bundle().apply { putString(ChromaWire.TEXT, text) }
        return ask(context, ChromaWire.DESCRIBE, extras) { ChromaJson.described(it) }
    }

    fun library(context: Context): ChromaAnswer<ChromaLibrary> = ask(context, ChromaWire.PALETTES, null) { ChromaJson.library(it) }

    /**
     * Opens WM Chroma's pick from the screen. The colour waits there until [takePick] collects
     * it with the token kept here. False when WM Chroma could not be opened.
     */
    fun startPick(context: Context): Boolean {
        if (!installed(context)) return false
        val token = UUID.randomUUID().toString()
        val started = runCatching {
            context.startActivity(
                Intent().setClassName(ChromaWire.APP_PACKAGE, ChromaWire.PICK_ACTIVITY)
                    .putExtra(ChromaWire.EXTRA_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (started) prefs(context).edit().putString(PICK_TOKEN, token).putLong(PICK_SINCE, System.currentTimeMillis()).apply()
        return started
    }

    /** Whether a pick was opened and not collected yet. */
    fun pickPending(context: Context): Boolean = pendingToken(context) != null

    private fun pendingToken(context: Context): String? {
        val p = prefs(context)
        val token = p.getString(PICK_TOKEN, null) ?: return null
        if (System.currentTimeMillis() - p.getLong(PICK_SINCE, 0) > ChromaWire.PICK_WAIT_MS) {
            clearPick(context)
            return null
        }
        return token
    }

    /** The colour picked for this keyboard, once; Waiting while the person is still picking. */
    fun takePick(context: Context): ChromaAnswer<ChromaDescribed> {
        val token = pendingToken(context) ?: return ChromaAnswer.Failed
        val extras = Bundle().apply { putString(ChromaWire.TOKEN, token) }
        val answer = ask(context, ChromaWire.TAKE_PICK, extras) { ChromaJson.described(it) }
        // Waiting keeps the token; anything else ends the pick, collected or not.
        if (answer !is ChromaAnswer.Waiting) clearPick(context)
        return answer
    }

    fun clearPick(context: Context) {
        prefs(context).edit().remove(PICK_TOKEN).remove(PICK_SINCE).apply()
    }

    /** Opens WM Chroma itself. */
    fun open(context: Context): Boolean = runCatching {
        val launch = context.packageManager.getLaunchIntentForPackage(ChromaWire.APP_PACKAGE) ?: return false
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** Whether WM Chroma is out on a store, so [openStore] has a listing to open. */
    const val RELEASED = false
    private const val STORE_URL = "https://play.google.com/store/apps/details?id=${ChromaWire.APP_PACKAGE}"

    /** Where WM Chroma is installed from: the Play app when there is one, else the web page. */
    fun openStore(context: Context) {
        if (!RELEASED) {
            android.widget.Toast.makeText(context, com.wasimaster.wmkeyboard.ime.R.string.ime_wmchroma_not_released, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ChromaWire.APP_PACKAGE}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.recoverCatching { context.startActivity(web) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun <T> ask(context: Context, method: String, extras: Bundle?, read: (String) -> T?): ChromaAnswer<T> {
        if (!installed(context)) return ChromaAnswer.Missing
        val reply = try {
            context.contentResolver.call(APP, method, null, extras)
        } catch (_: IllegalArgumentException) {
            // No provider under that authority: a WM Chroma from before the link.
            return ChromaAnswer.Missing
        } catch (_: SecurityException) {
            return ChromaAnswer.Missing
        } ?: return ChromaAnswer.Failed
        return when (reply.getString(ChromaWire.STATE)) {
            ChromaWire.OK -> read(reply.getString(ChromaWire.VALUES).orEmpty())?.let { ChromaAnswer.Ok(it) } ?: ChromaAnswer.Failed
            ChromaWire.WAITING -> ChromaAnswer.Waiting
            ChromaWire.OFF -> ChromaAnswer.Off
            else -> if (reply.getString(ChromaWire.CODE) == ChromaWire.UNTRUSTED) ChromaAnswer.Missing else ChromaAnswer.Failed
        }
    }
}

/** WM Chroma's JSON values read into the types above. Anything malformed reads as null or is skipped. */
internal object ChromaJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun described(text: String): ChromaDescribed? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        val colour = colour(o) ?: return null
        val formats = (o["formats"] as? JsonArray).orEmpty().mapNotNull { e ->
            val f = e as? JsonObject ?: return@mapNotNull null
            val t = f.str("text").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChromaFormat(f.str("id"), f.str("label"), t)
        }
        ChromaDescribed(colour, formats)
    }.getOrNull()

    fun library(text: String): ChromaLibrary? = runCatching {
        val o = json.parseToJsonElement(text).jsonObject
        fun list(key: String) = (o[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::colour) }
        val palettes = (o["palettes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            ChromaPalette(
                id = p["id"]?.jsonPrimitive?.longOrNull ?: 0L,
                name = p.str("name"),
                pinned = p["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                swatches = p["swatches"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::colour) },
            )
        }
        ChromaLibrary(list("recent"), list("tray"), palettes)
    }.getOrNull()

    private fun colour(o: JsonObject): ChromaColour? {
        val argb = o["argb"]?.jsonPrimitive?.intOrNull ?: return null
        val text = o.str("text").takeIf { it.isNotBlank() } ?: return null
        return ChromaColour(argb, o.str("name"), text)
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
}
