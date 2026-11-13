plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of ML Kit's translator, Play builds only: :app lists this
// module in `dynamicFeatures` when wmkb.enablePlayStore is on, and no other
// build references it at all — direct-download APKs embed the same code in
// :core:intelligence instead (see the playStoreChannel block there), and
// F-Droid's build has no ML Kit. Built exactly like :feature:llm
// beside it. The module has no sources of its own: it compiles the shared
// src/translatebridge directory, whose one class the base app reaches by
// reflection, plus the translate and language-id dependencies.
// libtranslate_jni.so is about 16 MB per ABI, which is the entire point of the
// split: on-device translation is off by default, and an install that never
// turns it on should not carry it.
android {
    namespace = "com.wasimaster.wmkeyboard.translatefeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set, and ML Kit's translator ships x86 and x86_64
        // besides. These are the release base's two; see the `release` build
        // type in app/build.gradle.kts.
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin
            ?.addStaticSourceDirectory("../../core/intelligence/src/translatebridge/java")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
    // Named directly, not inherited: a feature compiles against the base's
    // *api* surface only, and :app keeps its project modules internal. AGP's
    // feature packaging strips everything the base already carries, so this
    // puts no :core class in the split — it only makes TranslateRuntime and
    // OfflineTranslatePlan visible to the bridge compile.
    implementation(project(":core:intelligence"))
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.android)
}
