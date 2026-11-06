import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    // This module's jniLibs of on-demand gates, per channel (native/gates.json).
    id("wmkeyboard.native-libs")
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
    alias(libs.plugins.kotlin.serialization)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("debug") }
    }
}

android {
    namespace = "com.wasimaster.wmkeyboard.prediction"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

// Same channel flag :app reads.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}
// Google's gesture typing library is the user's own import, loaded from app
// storage (core/gesture/latinime). Play forbids loading executable code that
// did not come from Play, so its build compiles the loader out: src/nouserlib
// stands in and refuses every import and load. A static source dir, not a
// flavour folder, for the AGP 9 reason the channel seams in :app give.
androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory(if (playStoreChannel) "src/nouserlib/java" else "src/userlib/java")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

// Prediction is moving into the Rust engine (native/RUST_NEON_PLAN.md), so a
// JVM test here reaches libwm_engine_jni through NativeEngine like :app's
// do: the host build comes first. See the comment on the task in :core:language.
tasks.withType<Test>().configureEach { dependsOn(":core:language:buildHostEngine") }

dependencies {
    api(project(":core:language"))
    api(project(":core:common"))
    api(project(":core:config"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    testImplementation(libs.junit)
}
