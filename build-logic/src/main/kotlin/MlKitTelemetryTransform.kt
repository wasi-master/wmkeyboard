import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Stops ML Kit telling Google about its own use. See `MlKitTelemetry` in
 * :core:intelligence's src/google for what is sent and why; this is the half
 * that edits the libraries. Each call below is swapped for a static method
 * there with the same stack effect:
 *
 *  - `Transport.send(Event)`, Firebase datatransport, which carries ML Kit's
 *    "Firelog" events to Google's logging endpoint: a no-op;
 *  - `TelemetryLoggingClient.log(TelemetryData)`, which hands a latency
 *    record to Google Play services for upload. ML Kit's digital ink,
 *    language ID and translator call it, and so does Play services' own
 *    GoogleApiManager for every call the app's ML Kit clients make into it:
 *    a no-op;
 *  - in the translator only, `OkHttpClient.Builder.build()` and
 *    `URL.openConnection()`, the two ways it reaches Firebase for an
 *    installation ID and its Remote Config: the same calls, refusing those
 *    two hosts as if the phone were offline.
 *
 * The app's manifest also removes datatransport's only backend (src/google's
 * AndroidManifest.xml), which drops anything this misses before it is stored.
 *
 * Keyed on the called APIs and ML Kit's package names, never on the
 * obfuscated class names, so a library update either still matches or
 * matches nothing. MlKitTelemetryHookTest notices the second.
 */
abstract class MlKitTelemetryTransform : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return name.startsWith(MLKIT_SHADED) || name.startsWith(MLKIT_API) || name == API_MANAGER
    }

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor {
        // The network calls are rewritten in the translator's classes alone:
        // digital ink builds its own OkHttp client for model downloads, which
        // InkOfflineImportsTransform already rewrites.
        val translator = classContext.currentClassData.className.startsWith(TRANSLATOR)
        return object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor = object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                override fun visitMethodInsn(
                    opcode: Int,
                    owner: String?,
                    name: String?,
                    descriptor: String?,
                    isInterface: Boolean,
                ) {
                    when {
                        opcode == Opcodes.INVOKEINTERFACE && owner == TELEMETRY_CLIENT && name == "log" && descriptor == TELEMETRY_LOG ->
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "log", "(L$TELEMETRY_CLIENT;${TELEMETRY_LOG.drop(1)}", false)
                        opcode == Opcodes.INVOKEINTERFACE && owner == TRANSPORT && name == "send" && descriptor == TRANSPORT_SEND ->
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "send", "(L$TRANSPORT;${TRANSPORT_SEND.drop(1)}", false)
                        translator && opcode == Opcodes.INVOKEVIRTUAL && owner == OKHTTP_BUILDER && name == "build" && descriptor == OKHTTP_BUILD ->
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "build", "(L$OKHTTP_BUILDER;${OKHTTP_BUILD.drop(1)}", false)
                        translator && opcode == Opcodes.INVOKEVIRTUAL && owner == URL && name == "openConnection" && descriptor == URL_OPEN ->
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "openConnection", "(L$URL;${URL_OPEN.drop(1)}", false)
                        else -> super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                }
            }
        }
    }

    private companion object {
        /** ML Kit's shaded internals: mlkit_common, mlkit_vision_text_common, mlkit_translate and the rest. */
        const val MLKIT_SHADED = "com.google.android.gms.internal.mlkit_"
        const val MLKIT_API = "com.google.mlkit."
        const val API_MANAGER = "com.google.android.gms.common.api.internal.GoogleApiManager"
        const val HOOK = "com/wasimaster/wmkeyboard/core/mlkit/MlKitTelemetry"
        const val TELEMETRY_CLIENT = "com/google/android/gms/common/internal/TelemetryLoggingClient"
        const val TELEMETRY_LOG = "(Lcom/google/android/gms/common/internal/TelemetryData;)Lcom/google/android/gms/tasks/Task;"
        const val TRANSPORT = "com/google/android/datatransport/Transport"
        const val TRANSPORT_SEND = "(Lcom/google/android/datatransport/Event;)V"
        const val TRANSLATOR = "com.google.android.gms.internal.mlkit_translate."
        const val OKHTTP_BUILDER = "okhttp3/OkHttpClient\$Builder"
        const val OKHTTP_BUILD = "()Lokhttp3/OkHttpClient;"
        const val URL = "java/net/URL"
        const val URL_OPEN = "()Ljava/net/URLConnection;"
    }
}
