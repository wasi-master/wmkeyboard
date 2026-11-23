package com.wasimaster.wmkeyboard.ime.suite

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Process
import java.security.MessageDigest

/**
 * Which of the family's apps to believe: the expected package name signed by a published
 * certificate (or this keyboard's own), never the name alone. The same list WM Pass's bridge
 * checks; WM Translate and WM Pass check the keyboard against it too.
 */
object SuiteTrust {
    val CERTS = setOf(
        "d6c1f30a6116d33e4e778b372f335faa91dad35f657a84e4e7077b29641e7cad",
        "a9b43a363119f1c6b747be2f7b942f71b4dab8d76bd1b8e488d94774f6aa0981",
        "dc4493107b16cedd0c0ab51275d7f5c3217caf9d8cd246a4a8a9f59eab1acf3e",
        "f9ee53b27ae388701b68e3268eb5b8eb26964d83c00755d8cb73a8eca222d69a",
        "3a97c7f20270addb101763ffd7e1c6e3eeab9f4cb24c7f7fcddca807f0f63f09",
        "1ac715ba5bfe36bcac1ee31a514c492211c750888ff5207118203626622aa904",
    )

    /** Lowercase hex SHA-256 of [pkg]'s current signer, or null when it is missing or multi-signed. */
    fun certOf(context: Context, pkg: String): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            if (info.hasMultipleSigners()) return null
            info.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val latest = signatures?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(latest.toByteArray()).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** Null when [pkg] is not installed; otherwise whether it is signed by the family. */
    fun trusted(context: Context, pkg: String): Boolean? {
        val installed = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        if (!installed) return null
        val theirs = certOf(context, pkg) ?: return false
        return theirs in CERTS || theirs == certOf(context, context.packageName)
    }

    /** The calling app of a binder call when it is [pkg] signed by the family; null for anyone else. */
    fun callerIs(context: Context, pkg: String): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return false
        val packages = context.packageManager.getPackagesForUid(uid) ?: return false
        return pkg in packages && trusted(context, pkg) == true
    }
}
