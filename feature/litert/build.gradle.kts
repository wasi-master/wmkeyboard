plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the LiteRT interpreter, Play builds only: :app lists
// this module in `dynamicFeatures` when wmkb.enablePlayStore is on, and no
// other build references it at all — direct-download APKs embed the same
// code in :core:content instead (see the playStoreChannel block there), and
// F-Droid builds are lite and have no LiteRT. Built exactly like
// :feature:translate beside it. The module compiles the shared sticker
// cutout bridge, which the base app reaches by reflection, plus LiteRT.
// libtensorflowlite_jni.so is about 4 MB per ABI and the cutout tool only
// runs where Play services cannot supply Google's model, so installs that do
// not use that fallback do not carry it.
android {
    namespace = "com.wasimaster.wmkeyboard.litertfeature"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set, and LiteRT ships one more than the base does
        // (x86). These are the release base's three; see the `release` build
        // type in app/build.gradle.kts.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Must mirror the base app's dimension: each base variant needs a
    // matching variant here (fullRelease base -> fullRelease split).
    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

androidComponents {
    onVariants { variant ->
        // Only the full flavour has the LiteRT classes the bridge imports; the
        // lite split stays empty and is never requested.
        if (variant.flavorName == "full") {
            variant.sources.kotlin
                ?.addStaticSourceDirectory("../../core/content/src/cutoutbridge/java")
        }
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
    // Named directly, not inherited: a feature compiles against the base's
    // *api* surface only, and :app keeps its project modules internal. AGP's
    // feature packaging strips everything the base already carries; the
    // content API is available to the cutout bridge compile.
    implementation(project(":core:content"))
    "fullImplementation"(libs.litert)
}
