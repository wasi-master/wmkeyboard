package com.wasimaster.wmkeyboard.core.mlkit

import com.google.android.datatransport.Event
import com.google.android.datatransport.Transport
import com.google.android.gms.common.internal.TelemetryData
import com.google.android.gms.common.internal.TelemetryLoggingClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.net.URL
import java.net.URLConnection
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Where ML Kit's reports about its own use go instead of Google: nowhere.
 * This is the first level of Privacy › Google libraries
 * ([com.wasimaster.wmkeyboard.core.modules.GoogleLibraries]), on in every
 * build that has ML Kit and at both levels; the second level keeps ML Kit
 * from running at all.
 *
 * ML Kit reports every init, model download and detection to Google, with
 * the device model, the app's package and version, a per-install identifier,
 * latencies and error codes (developers.google.com/ml-kit/android-data-disclosure).
 * Its terms ask the app to tell users; they offer no switch to turn it off.
 * MlKitTelemetryTransform in build-logic rewrites these call sites in ML
 * Kit's own classes to land here:
 *
 *  - [send]: Firebase datatransport's `Transport.send`, ML Kit's "Firelog"
 *    events. The manifest (app/src/google) also removes datatransport's only
 *    backend, so an event that reaches it anyway is dropped before it is
 *    stored, and events queued by an older install are deleted, not sent.
 *  - [log]: `TelemetryLoggingClient.log`, a latency record handed to Google
 *    Play services, from ML Kit's digital ink, language ID and translator
 *    and from Play services' GoogleApiManager on each call ML Kit makes
 *    into it.
 *  - [build] and [openConnection], in the translator only: its own small
 *    clients for Firebase Installations (which registers a per-install ID and
 *    gets a token for it) and Firebase Remote Config (which fetches the
 *    translator's per-language overrides with that token). Both requests are
 *    refused as if the phone were offline, which the translator already
 *    handles: it keeps its built-in settings, and its models still come from
 *    their fixed addresses on dl.google.com.
 *
 * What this cannot reach: code Play services loads into the app at run time
 * (the Play build's text and barcode readers, the Chinese, Japanese, Korean
 * and Devanagari readers, subject segmentation) and screens Play services
 * runs itself (the document scanner). Model downloads are not reports and
 * still happen. The second level of Google libraries is what avoids those.
 *
 * Every method takes the receiver the rewritten call would have used, so the
 * operand stack is the same before and after the rewrite. Never call them
 * from app code.
 */
object MlKitTelemetry {

    /** The translator's two Firebase services. */
    private val FIREBASE_HOSTS = setOf(
        "firebaseinstallations.googleapis.com",
        "firebaseremoteconfig.googleapis.com",
    )

    private fun refused(host: String?): Boolean = host != null && host.lowercase() in FIREBASE_HOSTS

    private fun refusal(host: String) = IOException("$host is blocked by Privacy › Google libraries")

    @JvmStatic
    @Suppress("UnusedParameter")
    fun log(client: TelemetryLoggingClient?, data: TelemetryData?): Task<Void?> = Tasks.forResult(null)

    @JvmStatic
    @Suppress("UnusedParameter")
    fun send(transport: Transport<*>?, event: Event<*>?) = Unit

    /** `builder.build()`, with an interceptor that refuses the translator's Firebase hosts. */
    @JvmStatic
    fun build(builder: OkHttpClient.Builder): OkHttpClient =
        builder.addInterceptor(
            Interceptor { chain ->
                val host = chain.request().url.host
                if (refused(host)) throw refusal(host)
                chain.proceed(chain.request())
            },
        ).build()

    /** `url.openConnection()`, refused for the translator's Firebase hosts. */
    @JvmStatic
    fun openConnection(url: URL): URLConnection {
        if (refused(url.host)) throw refusal(url.host)
        return url.openConnection()
    }
}
