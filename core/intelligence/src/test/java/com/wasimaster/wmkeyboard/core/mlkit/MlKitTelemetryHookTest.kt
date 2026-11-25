package com.wasimaster.wmkeyboard.core.mlkit

import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's usage reports are cut by rewriting two calls inside its classes at
 * build time (MlKitTelemetryTransform in build-logic). The transform matches
 * on what the library calls, so a library update that reports some other way
 * does not fail the build: it silently starts reporting again. This reads the
 * libraries' own classes and fails instead.
 */
class MlKitTelemetryHookTest {

    private fun classesOf(anchor: Class<*>): List<ByteArray> {
        val location = File(anchor.protectionDomain!!.codeSource!!.location.toURI())
        if (location.isDirectory) {
            return location.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.path.contains(SHADED_PATH) }
                .map { it.readBytes() }
                .toList()
        }
        return ZipFile(location).use { jar ->
            jar.entries().toList()
                .filter { it.name.startsWith(SHADED_PATH) && it.name.endsWith(".class") }
                .map { entry -> jar.getInputStream(entry).use { it.readBytes() } }
        }
    }

    private val common by lazy { classesOf(MlKitContext::class.java) }
    private val translate by lazy { classesOf(Translation::class.java) }
    private val ink by lazy { classesOf(DigitalInkRecognition::class.java) }

    private fun List<ByteArray>.anyHas(vararg needles: String): Boolean = any { bytes ->
        val text = String(bytes, Charsets.ISO_8859_1)
        needles.all { it in text }
    }

    @Test
    fun theLibrariesAreWhereTheTransformLooks() {
        assertTrue("no ML Kit classes in common", common.isNotEmpty())
        assertTrue("no ML Kit classes in translate", translate.isNotEmpty())
        assertTrue("no ML Kit classes in digital ink", ink.isNotEmpty())
    }

    @Test
    fun commonSendsThroughDatatransport() {
        assertTrue(common.anyHas(TRANSPORT, "send", TRANSPORT_SEND))
    }

    @Test
    fun translateAndInkLogThroughPlayServicesTelemetry() {
        assertTrue(translate.anyHas(TELEMETRY_CLIENT, "log", TELEMETRY_LOG))
        assertTrue(ink.anyHas(TELEMETRY_CLIENT, "log", TELEMETRY_LOG))
    }

    @Test
    fun noLibraryUsesAReportingRouteTheTransformMisses() {
        for ((name, classes) in listOf("common" to common, "translate" to translate, "digital ink" to ink)) {
            assertFalse("$name calls Play services' Clearcut logger", classes.anyHas(CLEARCUT))
            assertFalse("$name calls Transport.schedule", classes.anyHas(SCHEDULE_CALLBACK))
        }
    }

    private companion object {
        const val SHADED_PATH = "com/google/android/gms/internal/mlkit_"
        const val TRANSPORT = "com/google/android/datatransport/Transport"
        const val TRANSPORT_SEND = "(Lcom/google/android/datatransport/Event;)V"
        const val TELEMETRY_CLIENT = "com/google/android/gms/common/internal/TelemetryLoggingClient"
        const val TELEMETRY_LOG = "(Lcom/google/android/gms/common/internal/TelemetryData;)Lcom/google/android/gms/tasks/Task;"
        const val CLEARCUT = "com/google/android/gms/clearcut/"

        /** The other way to hand datatransport an event; only `schedule` takes it. */
        const val SCHEDULE_CALLBACK = "com/google/android/datatransport/TransportScheduleCallback"
    }
}
