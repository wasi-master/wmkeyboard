import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// Packages Alpha Cephei's libvosk.so (the C library only) as a native library
// of the module that applies it: :core:voice outside Play and F-Droid, beside
// libwmvosk from src/vosk/jniLibs, and the on-demand :feature:vosk split on
// Play. A build with native delivery on (wmkeyboard.native-pins) leaves the
// file out of the APK again and pins it instead, read on the phone out of
// this same AAR on Maven Central; only a build that bundles its libraries
// (the offline build) ships it.
//
// The AAR is resolved as a file from its own configuration, never as a
// dependency: as one, AGP would also package its Java API and pull in JNA,
// which nothing here uses (libwmvosk calls the C API).

val vosk = configurations.create("vosk") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
catalog.findLibrary("vosk-android").get().get().let { module ->
    dependencies.add(vosk.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
}

extensions.getByType(AndroidComponentsExtension::class.java).apply {
    onVariants(selector().all()) { variant ->
        val task = tasks.register(
            "voskLibs${variant.name.replaceFirstChar { it.uppercase() }}",
            OnnxRuntimeLibs::class.java,
        ) {
            archives.from(vosk)
            // The ABIs the app ships; the AAR's x86 goes nowhere.
            abis.set(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
            library.set("libvosk.so")
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, OnnxRuntimeLibs::outputDir)
    }
}
