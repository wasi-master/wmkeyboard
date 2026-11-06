package com.wasimaster.wmkeyboard.ime.wmpass

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.wasimaster.wmkeyboard.core.otp.NotificationOtp
import com.wasimaster.wmkeyboard.core.otp.NotificationOtpBus
import java.security.MessageDigest
import org.json.JSONArray

/**
 * Talks to the WM Pass app, which owns the vault. This side keeps nothing: a value is asked for at
 * the moment it is typed and is not held after that. It opens a locked vault only by starting WM
 * Pass's own fingerprint prompt, or by handing over a master password the person typed into the
 * tool, which WM Pass checks. Before anything is asked
 * or believed, WM Pass's signing certificate is checked, because an app that merely took the package
 * name could otherwise list made-up logins and collect what the person types next.
 *
 * Public because the settings app asks too: its key fields fill from, and save to, API key items.
 */
object WmPassBridge {
    const val PACKAGE = "com.wasimaster.wmpass"
    private val PROVIDER: Uri = Uri.parse("content://com.wasimaster.wmpass.keyboard")

    /** Besides this keyboard's own certificates: the published signers of both apps. */
    private val KNOWN_CERTS = setOf(
        "d6c1f30a6116d33e4e778b372f335faa91dad35f657a84e4e7077b29641e7cad",
        "a9b43a363119f1c6b747be2f7b942f71b4dab8d76bd1b8e488d94774f6aa0981",
        "dc4493107b16cedd0c0ab51275d7f5c3217caf9d8cd246a4a8a9f59eab1acf3e",
        "f9ee53b27ae388701b68e3268eb5b8eb26964d83c00755d8cb73a8eca222d69a",
        "3a97c7f20270addb101763ffd7e1c6e3eeab9f4cb24c7f7fcddca807f0f63f09",
        "1ac715ba5bfe36bcac1ee31a514c492211c750888ff5207118203626622aa904",
    )

    enum class Status { MISSING, UNTRUSTED, OFF, NO_VAULT, LOCKED, OPEN }

    /** The two values of an API key item a key field can fill from or save to. */
    const val API_KEY = "key"
    const val API_SECRET = "secret"

    data class ApiKey(val id: String, val title: String, val provider: String, val matches: Boolean)

    /** The app the focused field belongs to. */
    @Volatile
    var focusedPackage: String? = null
        private set

    private fun signers(context: Context, name: String): List<Signature> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) return emptyList()
            info.signingCertificateHistory?.toList().orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(name, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
    }

    private fun digest(signature: Signature): String =
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Null when it is not installed, otherwise whether it is really WM Pass. */
    private fun trusted(context: Context): Boolean? {
        val theirs = try {
            signers(context, PACKAGE).lastOrNull()
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } ?: return false
        val own = signers(context, context.packageName).lastOrNull()
        return theirs == own || digest(theirs) in KNOWN_CERTS
    }

    private fun ask(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? = try {
        context.contentResolver.call(PROVIDER, method, arg, extras)
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun statusOf(reply: Bundle?) = when (reply?.getString(VaultWire.STATE)) {
        "open" -> Status.OPEN
        "locked" -> Status.LOCKED
        "none" -> Status.NO_VAULT
        "off" -> Status.OFF
        else -> Status.MISSING
    }

    fun status(context: Context): Status = when (trusted(context)) {
        null -> Status.MISSING
        false -> Status.UNTRUSTED
        true -> statusOf(ask(context, VaultWire.STATUS))
    }

    /**
     * One value of one item, by its key on the item's [VaultWire.Page]: a
     * login's `username`, `password` or `code`, an API key's [API_KEY] or
     * [API_SECRET], and so on. Asked for only on the tap that types it. Call
     * away from the main thread.
     */
    fun value(context: Context, id: String, field: String): String? {
        if (trusted(context) != true) return null
        return ask(context, VaultWire.VALUE, id, Bundle().apply { putString(VaultWire.FIELD, field) })?.getString(VaultWire.VALUE)
    }

    /**
     * What the WM Pass tool lists: where the vault stands, and its items when it is open. Open, it
     * names the [vault]; locked, it says whether the [fingerprint] can open it and, after too many
     * wrong passwords, when the next may be tried ([retryAt], 0 for now).
     */
    data class Listing(
        val status: Status,
        val rows: List<VaultWire.Row>,
        val vault: String = "",
        val fingerprint: Boolean = false,
        val retryAt: Long = 0,
    )

    /**
     * The vault's items, every kind, those saved for the app in front first,
     * narrowed by [query] when it is not empty. Call away from the main thread.
     */
    fun items(context: Context, query: String): Listing {
        when (trusted(context)) {
            null -> return Listing(Status.MISSING, emptyList())
            false -> return Listing(Status.UNTRUSTED, emptyList())
            true -> Unit
        }
        val reply = ask(context, VaultWire.ITEMS, focusedPackage.orEmpty(), Bundle().apply { putString(VaultWire.QUERY, query) })
        val status = statusOf(reply)
        return Listing(
            status = status,
            rows = if (status == Status.OPEN) VaultWire.rows(reply?.getString(VaultWire.ROWS)) else emptyList(),
            vault = reply?.getString(VaultWire.VAULT).orEmpty(),
            fingerprint = reply?.getBoolean(VaultWire.FINGERPRINT) == true,
            retryAt = reply?.getLong(VaultWire.RETRY_AT) ?: 0L,
        )
    }

    /**
     * Starts WM Pass's fingerprint prompt over the app in front, or its door
     * when the fingerprint is not set up. WM Pass makes the pending intent and
     * checks the fingerprint; this side only starts it. False when WM Pass
     * gave none, as when the vault is open already. Call away from the main
     * thread.
     */
    fun startUnlockPrompt(context: Context): Boolean {
        if (trusted(context) != true) return false
        val reply = ask(context, VaultWire.UNLOCK_PROMPT) ?: return false
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reply.getParcelable(VaultWire.INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            reply.getParcelable(VaultWire.INTENT)
        } ?: return false
        // The keyboard is on screen, and from Android 14 the starter has to say
        // it lends that to the prompt: WM Pass itself is in the background.
        val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            @Suppress("DEPRECATION")
            ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle()
        } else {
            null
        }
        return runCatching { intent.send(context, 0, null, null, null, null, options) }.isSuccess
    }

    /** How an unlock with a typed master password went. */
    enum class Unlock { OPENED, WRONG, WAITING, FACTOR, FAILED }

    data class UnlockReply(val outcome: Unlock, val retryAt: Long = 0, val vault: String = "")

    /**
     * Sends the master password the person typed into the tool's own box to
     * WM Pass, which opens the vault with it as its door would: the same count
     * of wrong tries, the same waits. Nothing is kept here; the bytes are
     * wiped once sent. Call away from the main thread.
     */
    fun unlock(context: Context, password: String): UnlockReply {
        if (trusted(context) != true) return UnlockReply(Unlock.FAILED)
        val bytes = password.toByteArray(Charsets.UTF_8)
        val reply = try {
            ask(context, VaultWire.UNLOCK, extras = Bundle().apply { putByteArray(VaultWire.SECRET, bytes) })
        } finally {
            bytes.fill(0)
        }
        val retryAt = reply?.getLong(VaultWire.RETRY_AT) ?: 0L
        val outcome = when (reply?.getString(VaultWire.OUTCOME)) {
            "opened" -> Unlock.OPENED
            "wrong" -> Unlock.WRONG
            "waiting" -> Unlock.WAITING
            "factor" -> Unlock.FACTOR
            // An older WM Pass that knows no unlock call, or a vault already open.
            else -> if (statusOf(reply) == Status.OPEN) Unlock.OPENED else Unlock.FAILED
        }
        return UnlockReply(outcome, retryAt, reply?.getString(VaultWire.VAULT).orEmpty())
    }

    /** Locks the vault. Call away from the main thread. */
    fun lock(context: Context): Boolean {
        if (trusted(context) != true) return false
        return statusOf(ask(context, VaultWire.LOCK)) == Status.LOCKED
    }

    /** The name the phone shows for [packageName], or empty. */
    fun appLabel(context: Context, packageName: String?): String {
        if (packageName.isNullOrEmpty()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault("")
    }

    /** One item's fields, secrets named but not held. Null when it is gone or the vault locked. Call away from the main thread. */
    fun item(context: Context, id: String): VaultWire.Page? {
        if (trusted(context) != true) return null
        return VaultWire.page(ask(context, VaultWire.ITEM, id)?.getString(VaultWire.PAGE))
    }

    /**
     * Has WM Pass copy one value of an item itself, a secret marked sensitive
     * and wiped after the time set there, so it never passes through this
     * process. Call away from the main thread.
     */
    fun copy(context: Context, id: String, field: String): Boolean {
        if (trusted(context) != true) return false
        return ask(context, VaultWire.COPY, id, Bundle().apply { putString(VaultWire.FIELD, field) })?.getBoolean(VaultWire.COPIED) == true
    }

    /**
     * Saves a new login in WM Pass. The app in front goes with it, for WM Pass
     * to tie the login to by its certificate. The new item's id, or null when
     * nothing was saved. Call away from the main thread.
     */
    fun addLogin(context: Context, title: String, username: String, password: String, website: String): String? {
        if (trusted(context) != true) return null
        val extras = Bundle().apply {
            putString(VaultWire.TITLE, title)
            putString(VaultWire.USERNAME, username)
            putString(VaultWire.PASSWORD, password)
            putString(VaultWire.WEBSITE, website)
            putString(VaultWire.APP, focusedPackage.orEmpty())
        }
        return ask(context, VaultWire.ADD_LOGIN, extras = extras)?.getString(VaultWire.ID)
    }

    /** Puts [password] on the item [id]; WM Pass keeps the old one in its history. Call away from the main thread. */
    fun setPassword(context: Context, id: String, password: String): Boolean {
        if (trusted(context) != true) return false
        val extras = Bundle().apply { putString(VaultWire.PASSWORD, password) }
        return ask(context, VaultWire.SET_PASSWORD, id, extras)?.getBoolean(VaultWire.SAVED) == true
    }

    /** A new password from WM Pass's generator, made the way it is set up there. Call away from the main thread. */
    fun generate(context: Context): String? {
        if (trusted(context) != true) return null
        return ask(context, VaultWire.GENERATE)?.getString(VaultWire.VALUE)?.takeIf { it.isNotEmpty() }
    }

    /** Opens the item [id] in WM Pass, which shows it once the vault is open. */
    fun openItem(context: Context, id: String): Boolean {
        if (trusted(context) != true) return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setClassName(PACKAGE, "$PACKAGE.app.MainActivity")
            .putExtra(VaultWire.ROUTE_EXTRA, VaultWire.openRoute(id))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * The buttons a field of WM Pass's own offers the strip ([FieldWire]),
     * or null for any other app's field, an impostor's, or a field with
     * none. Call away from the main thread: it checks the certificate.
     */
    fun fieldTag(context: Context, packageName: String?, privateImeOptions: String?): FieldWire.Tag? {
        if (packageName != PACKAGE) return null
        val tag = FieldWire.parse(privateImeOptions) ?: return null
        return tag.takeIf { trusted(context) == true }
    }

    /** Asks WM Pass to press the button [action] on the screen called [token]. Call away from the main thread. */
    fun runAction(context: Context, action: String, token: String): Boolean {
        if (action !in FieldWire.ACTIONS || trusted(context) != true) return false
        val extras = Bundle().apply { putString(FieldWire.TOKEN, token) }
        return ask(context, FieldWire.ACTION_CALL, action, extras)?.getBoolean(FieldWire.DONE) == true
    }

    /** The vault's API key items, those named for [service] first. Call away from the main thread. */
    fun apiKeys(context: Context, service: String): List<ApiKey> {
        if (trusted(context) != true) return emptyList()
        val rows = ask(context, "api_keys", service)?.getString("rows") ?: return emptyList()
        val array = JSONArray(rows)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            ApiKey(
                id = row.getString("id"),
                title = row.optString("title"),
                provider = row.optString("provider"),
                matches = row.optBoolean("matches"),
            )
        }
    }

    enum class Saved { CREATED, UPDATED }

    /**
     * Keeps [value] as [field] ([API_KEY] or [API_SECRET]) of the API key item called [title]. WM Pass
     * makes the item, or changes the one already called that. Null when nothing was kept, as when
     * the vault locked meanwhile. Call away from the main thread.
     */
    fun saveApiKey(context: Context, title: String, field: String, value: String): Saved? {
        if (trusted(context) != true) return null
        val extras = Bundle().apply {
            putString("title", title)
            putString("provider", title)
            putString("field", field)
            putString("value", value)
        }
        return when (ask(context, "save_api_key", extras = extras)?.getString("saved")) {
            "created" -> Saved.CREATED
            "updated" -> Saved.UPDATED
            else -> null
        }
    }

    /** Whether the real WM Pass is installed: the package, signed by a known key. */
    fun isInstalled(context: Context): Boolean = trusted(context) == true

    /**
     * Whether [packageName], an app that started one of this app's screens for
     * a result, is the real WM Pass. The settings app's place picker answers no
     * one else.
     */
    fun isWmPass(context: Context, packageName: String?): Boolean =
        packageName == PACKAGE && trusted(context) == true

    /**
     * WM Pass's own place picker, which hands back one of its places for copies
     * as a [com.wasimaster.wmkeyboard.core.settings.PlaceWire] result. Check
     * [isInstalled] first: the component name alone proves nothing.
     */
    fun placePicker(): Intent = Intent().setClassName(PACKAGE, "$PACKAGE.system.PlaceShareActivity")

    /**
     * Where WM Pass is installed from: its Play listing, through the Play app
     * when there is one and the web page when there is not (Telegram 5333).
     */
    fun openStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(STORE_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.recoverCatching { context.startActivity(web) }
    }

    /** WM Pass's Play listing, for a page that can only link. */
    const val STORE_URL = "https://play.google.com/store/apps/details?id=$PACKAGE"

    fun openApp(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * A field took focus. In a code box of an app with a login saved, the current one-time code goes
     * on the bus that raises the code chip. It carries when it stops being valid, since a code from
     * an authenticator lasts seconds rather than minutes. Call away from the main thread.
     */
    fun fieldFocused(context: Context, packageName: String?, codeField: Boolean, offerCode: Boolean) {
        focusedPackage = packageName
        if (!codeField || !offerCode || packageName == null || packageName == context.packageName) return
        if (trusted(context) != true) return
        val reply = ask(context, "code", packageName) ?: return
        val code = reply.getString("code") ?: return
        val now = System.currentTimeMillis()
        NotificationOtpBus.publish(
            NotificationOtp(
                code = code,
                sourcePackage = PACKAGE,
                sourceApp = "WM Pass",
                message = "",
                notificationKey = null,
                postedAt = now,
                validUntil = now + reply.getInt("seconds_left", 30) * 1000L,
            ),
        )
    }
}
