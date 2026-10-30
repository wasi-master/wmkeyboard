import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// The `webrtc` gate's Play split: libjingle_peerconnection_so.so out of the
// webrtc-sdk AAR, as this module's library. The AAR's Java API is an ordinary
// dependency of :feature:ime; :app leaves the library itself out of the base.
// Everywhere but Play the gate's native delivery pins the same file instead.

val webRtc = configurations.create("webRtc") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
catalog.findLibrary("webrtc-android").get().get().let { module ->
    dependencies.add(webRtc.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
}

extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        val task = tasks.register(
            "webRtcLibs${variant.name.replaceFirstChar { it.uppercase() }}",
            OnnxRuntimeLibs::class.java,
        ) {
            archives.from(webRtc)
            abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            library.set("libjingle_peerconnection_so.so")
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, OnnxRuntimeLibs::outputDir)
    }
}
