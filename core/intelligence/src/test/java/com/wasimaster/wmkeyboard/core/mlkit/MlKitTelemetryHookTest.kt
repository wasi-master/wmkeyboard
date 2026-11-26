package com.wasimaster.wmkeyboard.core.mlkit

import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import java.io.DataInputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ML Kit's reports about its own use are cut by rewriting calls inside its
 * classes at build time (MlKitTelemetryTransform in build-logic). The
 * transform matches on what the library calls, so a library update that
 * reports some other way does not fail the build: it silently starts
 * reporting again. This reads the libraries' own classes and fails instead.
 */
class MlKitTelemetryHookTest {

    /** One class: its name, every method it calls as `owner.name descriptor`, and its string constants. */
    private class ClassRefs(val name: String, val calls: Set<String>, val strings: List<String>)

    private fun classesOf(anchor: Class<*>): List<ClassRefs> {
        val location = File(anchor.protectionDomain!!.codeSource!!.location.toURI())
        val bytes = if (location.isDirectory) {
            location.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".class") && it.path.contains(SHADED_PATH) }
                .map { it.readBytes() }
                .toList()
        } else {
            ZipFile(location).use { jar ->
                jar.entries().toList()
                    .filter { it.name.startsWith(SHADED_PATH) && it.name.endsWith(".class") }
                    .map { entry -> jar.getInputStream(entry).use { it.readBytes() } }
            }
        }
        return bytes.map(::read)
    }

    /** Reads [bytes]' constant pool: enough to see every method it references. */
    private fun read(bytes: ByteArray): ClassRefs {
        val input = DataInputStream(bytes.inputStream())
        input.skipBytes(8)
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classOf = IntArray(count)
        val nameAndType = arrayOfNulls<IntArray>(count)
        val refs = ArrayList<IntArray>()
        var i = 1
        while (i < count) {
            when (input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                7 -> classOf[i] = input.readUnsignedShort()
                10, 11 -> refs += intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                12 -> nameAndType[i] = intArrayOf(input.readUnsignedShort(), input.readUnsignedShort())
                3, 4, 9, 17, 18 -> input.skipBytes(4)
                5, 6 -> {
                    input.skipBytes(8)
                    i++
                }
                8, 16, 19, 20 -> input.skipBytes(2)
                15 -> input.skipBytes(3)
                else -> error("unknown constant pool tag")
            }
            i++
        }
        input.skipBytes(2)
        val thisClass = input.readUnsignedShort()
        val calls = refs.mapTo(HashSet()) { (owner, nt) ->
            val (name, type) = nameAndType[nt]!!
            "${utf8[classOf[owner]]}.${utf8[name]} ${utf8[type]}"
        }
        return ClassRefs(utf8[classOf[thisClass]].orEmpty(), calls, utf8.filterNotNull())
    }

    private val common by lazy { classesOf(MlKitContext::class.java) }
    private val translate by lazy { classesOf(Translation::class.java) }
    private val ink by lazy { classesOf(DigitalInkRecognition::class.java) }

    /** Whether any class calls [call], or any method [call] starts (an owner and name without the descriptor). */
    private fun List<ClassRefs>.calls(call: String): Boolean = any { c -> c.calls.any { it.startsWith(call) } }

    private fun List<ClassRefs>.mentions(text: String): List<ClassRefs> = filter { c -> c.strings.any { text in it } }

    @Test
    fun theLibrariesAreWhereTheTransformLooks() {
        assertTrue("no ML Kit classes in common", common.isNotEmpty())
        assertTrue("no ML Kit classes in translate", translate.isNotEmpty())
        assertTrue("no ML Kit classes in digital ink", ink.isNotEmpty())
        assertTrue(
            "the translator's classes moved out of ${TRANSLATOR_PATH}",
            translate.mentions(INSTALLATIONS_HOST).all { it.name.startsWith(TRANSLATOR_PATH) },
        )
    }

    @Test
    fun commonSendsThroughDatatransport() {
        assertTrue(common.calls(TRANSPORT_SEND))
    }

    @Test
    fun translateAndInkLogThroughPlayServicesTelemetry() {
        assertTrue(translate.calls(TELEMETRY_LOG))
        assertTrue(ink.calls(TELEMETRY_LOG))
    }

    @Test
    fun noLibraryUsesAReportingRouteTheTransformMisses() {
        for ((name, classes) in listOf("common" to common, "translate" to translate, "digital ink" to ink)) {
            assertFalse("$name calls Play services' Clearcut logger", classes.calls(CLEARCUT))
            assertFalse("$name calls Transport.schedule", classes.any { c -> c.calls.any { SCHEDULE_CALLBACK in it } })
        }
    }

    /**
     * The translator reaches Firebase Installations through OkHttp and Remote
     * Config through `URL.openConnection`: the two calls the transform
     * rewrites in its package. A third way of opening a connection would go
     * around them.
     */
    @Test
    fun theTranslatorReachesFirebaseOnlyThroughTheRewrittenCalls() {
        assertTrue("no class names Firebase Installations", translate.mentions(INSTALLATIONS_HOST).isNotEmpty())
        assertTrue("no class names Remote Config", translate.mentions(REMOTE_CONFIG_HOST).isNotEmpty())
        assertTrue("Firebase Installations no longer goes through OkHttp's builder", translate.calls(OKHTTP_BUILD))
        assertTrue("Remote Config no longer goes through URL.openConnection", translate.calls(URL_OPEN))
        val others = listOf(OKHTTP_NEW_CLIENT, URL_OPEN_PROXY, SOCKET_NEW, HTTPS_FACTORY)
        for (call in others) assertFalse("the translator calls $call", translate.calls(call))
    }

    /** Only the translator talks to Firebase, which is why the network rewrite stays in its package. */
    @Test
    fun noOtherLibraryNamesTheFirebaseHosts() {
        for ((name, classes) in listOf("common" to common, "digital ink" to ink)) {
            assertTrue("$name names Firebase Installations", classes.mentions(INSTALLATIONS_HOST).isEmpty())
            assertTrue("$name names Remote Config", classes.mentions(REMOTE_CONFIG_HOST).isEmpty())
        }
    }

    private companion object {
        const val SHADED_PATH = "com/google/android/gms/internal/mlkit_"
        const val TRANSLATOR_PATH = "com/google/android/gms/internal/mlkit_translate/"
        const val TRANSPORT_SEND = "com/google/android/datatransport/Transport.send (Lcom/google/android/datatransport/Event;)V"
        const val TELEMETRY_LOG = "com/google/android/gms/common/internal/TelemetryLoggingClient.log " +
            "(Lcom/google/android/gms/common/internal/TelemetryData;)Lcom/google/android/gms/tasks/Task;"
        const val CLEARCUT = "com/google/android/gms/clearcut/"

        /** The other way to hand datatransport an event; only `schedule` takes it. */
        const val SCHEDULE_CALLBACK = "com/google/android/datatransport/TransportScheduleCallback"

        const val INSTALLATIONS_HOST = "firebaseinstallations.googleapis.com"
        const val REMOTE_CONFIG_HOST = "firebaseremoteconfig.googleapis.com"
        const val OKHTTP_BUILD = "okhttp3/OkHttpClient\$Builder.build ()Lokhttp3/OkHttpClient;"
        const val URL_OPEN = "java/net/URL.openConnection ()Ljava/net/URLConnection;"
        const val OKHTTP_NEW_CLIENT = "okhttp3/OkHttpClient.<init> ()V"
        const val URL_OPEN_PROXY = "java/net/URL.openConnection (Ljava/net/Proxy;)Ljava/net/URLConnection;"
        const val SOCKET_NEW = "java/net/Socket.<init>"
        const val HTTPS_FACTORY = "javax/net/ssl/HttpsURLConnection.setDefaultSSLSocketFactory"
    }
}
