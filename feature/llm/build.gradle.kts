plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the LiteRT-LM runtime, Play builds only: :app lists
// this module in `dynamicFeatures` when wmkb.enablePlayStore is on, and no
// other build references it at all — F-Droid and direct-download APKs embed
// the same code in :core:intelligence instead (see the playStoreChannel block
// there). The module has no sources of its own: it compiles the shared
// src/llmbridge directory, whose one class the base app reaches by
// reflection, plus the litertlm dependency whose ~20 MB per ABI of native
// code is the entire point of the split.
//
// src/main/jniLibs holds two ~2 KB libwmllm_abi_parity.so placeholders
// (empty NDK-built ELFs, nothing ever loads them). They exist because
// bundletool requires every module with native libraries to support the SAME
// ABI set, and litertlm ships no armeabi-v7a — without the placeholders the
// Play bundle fails with "module 'base' supports [ARM64_V8A, ARMEABI_V7A]
// and module 'llm' supports [ARM64_V8A]". litertlm's x86_64 libraries are
// filtered out below for the same reason. A v7a device that
// installs the module gets litertlm's dex but no real .so, and the engine's
// existing load-failure path reports it — identical to what v7a users of the
// universal sideload APK have always seen. Rebuild recipe in the commit that
// added them (NDK clang, -nostdlib, 16 KB max-page-size on arm64).
android {
    namespace = "com.wasimaster.wmkeyboard.llmfeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // :app has a `languages` dimension and this module does not, so the
        // dependency on :app below has to be told which one to resolve. `en`
        // first because this module only exists in Play builds and Play takes
        // the `en` bundle: with `intl` first, bundleEnRelease compiles
        // :app a second time as intlRelease just to give this module a
        // classpath, and links the split against a base it will not ship with.
        missingDimensionStrategy("languages", "en", "intl")
        // The release base's two ABIs; see the `release` build type in
        // app/build.gradle.kts.
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
            ?.addStaticSourceDirectory("../../core/intelligence/src/llmbridge/java")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
    // Named directly, not inherited: a feature compiles against the base's
    // *api* surface only, and :app keeps its project modules internal. AGP's
    // feature packaging strips everything the base already carries, so this
    // puts no :core class in the split — it only makes LlmRuntime, ChatReplay,
    // LocalLlmBackend and the intelligence R visible to the bridge compile.
    implementation(project(":core:intelligence"))
    implementation(libs.litertlm.android)
}
