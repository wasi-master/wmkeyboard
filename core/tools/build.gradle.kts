plugins {
    alias(libs.plugins.android.library)
    // This module's jniLibs of on-demand gates, per channel (native/gates.json):
    // Numbat's, in src/numbat/jniLibs.
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
    namespace = "com.wasimaster.wmkeyboard.tools"
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
    implementation(project(":core:send"))
    // Speech out: the speaker buttons read with a downloaded MMS voice first.
    implementation(project(":core:voice"))
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    // Offline GIF packs are SQLite databases, and opening one is an Android
    // API call, so OfflineGifPacksTest runs under Robolectric.
    testImplementation(libs.robolectric)
}
