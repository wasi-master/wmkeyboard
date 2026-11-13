import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.variant.DynamicFeatureAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

// A library-only Play split, one per `"play": "split"` gate of
// native/gates.json: `:feature:<id>`, the on-demand home of that gate's own
// native libraries on Play builds only. settings.gradle.kts includes the
// module and :app lists it in `dynamicFeatures` when wmkb.enablePlayStore is
// on; no other build references it at all. Every other channel packages the
// same files in the base APK from the core module that keeps them, or pins
// them for download (wmkeyboard.native-pins).
//
// The module has no code: the gate's Kotlin stays in its core module and
// loads the library through FeatureModules.loadLibrary once Play has installed
// the split. It packages the gate's jniLibs directories in place, which the
// core module leaves out of itself on Play (wmkeyboard.native-libs), so the
// files keep the paths the native build scripts and F-Droid's recipe know.
// Its manifest (src/main/AndroidManifest.xml: on-demand, fusing, hasCode
// false, the title string in :app) is the one file the module keeps besides
// the build file applying this.

// Applied rather than requested in a plugins block, so that kotlin-dsl does
// not generate accessors for AGP: the configuration below goes through the
// extension types instead.
apply(plugin = "com.android.dynamic-feature")

val gate = NativeGates.of(project).gate(project.name)
check(gate.play == "split") { "${project.path}: gate `${gate.id}` in native/gates.json is not a library-only split" }

extensions.configure<DynamicFeatureExtension> {
    namespace = "com.wasimaster.wmkeyboard.${gate.id}feature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set: the release base's two, which is also exactly
        // what the directories below carry. An arm64-only library (Whistle)
        // therefore rides in a split that has an armeabi-v7a stand-in.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

extensions.configure<KotlinAndroidProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

extensions.configure<DynamicFeatureAndroidComponentsExtension> {
    onVariants { variant ->
        for (dir in gate.dirs) {
            variant.sources.jniLibs?.addStaticSourceDirectory(projectDir.toPath().relativize(rootDir.resolve(dir).toPath()).toString())
        }
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    "implementation"(project(":app"))
}
