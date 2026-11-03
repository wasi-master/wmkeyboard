package com.wasimaster.wmkeyboard.ime.wmpass

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
 * Talks to the WM Pass app, which owns the vault. This side unlocks nothing and keeps nothing: a
 * value is asked for at the moment it is typed and is not held after that. Before anything is asked
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

    data class Login(
        val id: String,
        val title: String,
        val username: String,
        val hasPassword: Boolean,
        val hasCode: Boolean,
        val matches: Boolean,
    )

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

    private fun statusOf(reply: Bundle?) = when (reply?.getString("state")) {
        "open" -> Status.OPEN
        "locked" -> Status.LOCKED
        "none" -> Status.NO_VAULT
        "off" -> Status.OFF
        else -> Status.MISSING
    }

    fun status(context: Context): Status = when (trusted(context)) {
        null -> Status.MISSING
        false -> Status.UNTRUSTED
        true -> statusOf(ask(context, "status"))
    }

    fun logins(context: Context): List<Login> {
        if (trusted(context) != true) return emptyList()
        val rows = ask(context, "logins", focusedPackage.orEmpty())?.getString("rows") ?: return emptyList()
        val array = JSONArray(rows)
        return List(array.length()) { i ->
            val row = array.getJSONObject(i)
            Login(
                id = row.getString("id"),
                title = row.optString("title"),
                username = row.optString("username"),
                hasPassword = row.optBoolean("has_password"),
                hasCode = row.optBoolean("has_code"),
                matches = row.optBoolean("matches"),
            )
        }
    }

    /** One value of one item: a login's `username`, `password` or `code`, or an API key's [API_KEY] or [API_SECRET]. */
    fun value(context: Context, id: String, field: String): String? {
        if (trusted(context) != true) return null
        return ask(context, "value", id, Bundle().apply { putString("field", field) })?.getString("value")
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
