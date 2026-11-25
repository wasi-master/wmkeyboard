package com.wasimaster.wmkeyboard.core.mlkit

import com.google.android.datatransport.Event
import com.google.android.datatransport.Transport
import com.google.android.gms.common.internal.TelemetryData
import com.google.android.gms.common.internal.TelemetryLoggingClient
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

/**
 * Where ML Kit's usage reports go instead of Google: nowhere.
 *
 * ML Kit reports every init, model download and detection to Google, with
 * the device model, the app's package and version, a per-install identifier,
 * latencies and error codes (developers.google.com/ml-kit/android-data-disclosure).
 * Its terms ask the app to tell users; they offer no switch to turn it off.
 * The reports travel two ways, and MlKitTelemetryTransform in build-logic
 * rewrites both call sites in ML Kit's own classes to land here:
 *
 *  - [send]: Firebase datatransport's `Transport.send`, ML Kit's "Firelog"
 *    events. The manifest (app/src/google) also removes datatransport's only
 *    backend, so an event that reaches it anyway is dropped before it is
 *    stored, and events queued by an older install are deleted, not sent.
 *  - [log]: `TelemetryLoggingClient.log`, a latency record handed to Google
 *    Play services, from ML Kit's digital ink, language ID and translator
 *    and from Play services' GoogleApiManager on each call ML Kit makes
 *    into it.
 *
 * What this cannot reach: code Play services loads into the app at run time
 * (the Play build's text and barcode readers, the Chinese, Japanese, Korean
 * and Devanagari readers, subject segmentation) and screens Play services
 * runs itself (the document scanner). Model downloads and the translator's
 * Firebase configuration requests are not reports and still happen.
 *
 * Both methods take the receiver the rewritten call would have used, so the
 * operand stack is the same before and after the rewrite. Never call them
 * from app code.
 */
object MlKitTelemetry {

    @JvmStatic
    @Suppress("UnusedParameter")
    fun log(client: TelemetryLoggingClient?, data: TelemetryData?): Task<Void?> = Tasks.forResult(null)

    @JvmStatic
    @Suppress("UnusedParameter")
    fun send(transport: Transport<*>?, event: Event<*>?) = Unit
}
