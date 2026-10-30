import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
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

// F-Droid's build carries no Google artifact (fdroid/README.md): no ML Kit
// and no Google Home, so src/nogoogle's stand-ins replace src/google's
// scanner, OCR and document-scan classes. See the same flag in
// :core:intelligence.
val googleSdks: Boolean = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    !(providers.gradleProperty("wmkb.enableFdroid").orNull
        ?: localProperties.getProperty("wmkb.enableFdroid")
        ?: System.getenv("WMKB_ENABLE_FDROID")
        ?: "false").toBoolean()
}

// Same channel flag :app reads (see the flag() helper there): a Play-store
// build resolves it true, every other channel false. Here it swaps the two
// bundled ML Kit artifacts for their unbundled play-services twins — a Play
// install implies GMS, so the models can come from Play services instead of
// shipping ~18 MB per ABI in the AAB. Sideloaded builds keep the bundled
// artifacts so scanners never depend on GMS there, and F-Droid's has none.
// Both variants expose the identical com.google.mlkit.vision.* API, so no
// source changes ride on this flag.
// The Google Home APIs SDK, which Google hands out as a bundle rather than
// a public Maven artifact: unpacked to libs/googlehome/m2 it is a Maven
// repository (settings.gradle.kts registers it when the directory exists),
// and the newest play-services-home under it is the version to depend on.
// Loose AARs dropped straight into libs/googlehome work too.
val googleHomeVersion: String? = file("libs/googlehome/m2/com/google/android/gms/play-services-home").listFiles()
    ?.filter { it.isDirectory }?.map { it.name }?.maxOrNull()
val googleHomeAars: Boolean = file("libs/googlehome").listFiles()?.any { it.name.endsWith(".aar") } == true
val googleHomeSdk: Boolean = googleHomeVersion != null || googleHomeAars
// Having the SDK on disk is the decision: a build that has it links it and
// compiles the real GoogleHomeSupport, at about 22 MB of dex before R8 on the
// builds that carry it whole (Play gets it as an on-demand split). There
// used to be a wmkb.enableGoogleHome flag on top; :core:config reads the
// same two facts for ENABLE_GOOGLE_HOME, so the code and the dependency
// never disagree.
val googleHome: Boolean = googleHomeSdk && googleSdks

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

android {
    namespace = "com.wasimaster.wmkeyboard.ime"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // Whether the keyboard tells Android it takes stylus handwriting
        // sessions (method.xml's supportsStylusHandwriting, issue #386). Off
        // in F-Droid's build: it has no recogniser, and declaring support
        // anyway would let Android hand it stylus drags over a text field
        // that it can only refuse, after the app has already given them up.
        resValue("bool", "ime_supports_stylus_handwriting", "$googleSdks")
    }

    // The Google Home APIs are a hand-downloaded SDK (Google hosts no Maven
    // artifact for them): the AARs go under libs/googlehome, and only a build
    // that has them compiles the real GoogleHomeSupport. Without them it uses
    // the stub, so a checkout without the SDK still builds, and so does
    // F-Droid's. ENABLE_GOOGLE_HOME in :core:config follows suit.
    //
    // On Play the SDK is not linked here at all: it is the on-demand
    // :feature:googlehome split, which compiles src/googlehome, and this
    // module gets the facade that reaches it by reflection instead.
    sourceSets {
        getByName("main") {
            val dirs = when {
                googleHome && playStoreChannel -> listOf("src/googlehomeplay/java")
                googleHome -> listOf("src/googlehome/java", "src/googlehomedirect/java")
                else -> listOf("src/googlehomestub/java")
            }
            // The classes that call ML Kit directly, or their F-Droid stand-ins.
            val google = if (googleSdks) "src/google/java" else "src/nogoogle/java"
            for (dir in dirs + google) {
                java.srcDir(dir)
                kotlin.srcDir(dir)
            }
            // The Play services module request the Home APIs need; only with the SDK.
            if (googleHome) manifest.srcFile("src/googlehome/AndroidManifest.xml")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // For ime_supports_stylus_handwriting above.
        resValues = true
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
    // Robolectric gives each test class its own sandbox classloader, which the
    // default 512m worker does not survive alongside this module's 486 plain
    // tests. 2g matches :app, where the same ceiling was found the hard way —
    // see the EOFException note there.
    testOptions.unitTests.all { it.maxHeapSize = "2g" }
    // Without the merged resources Robolectric knows no string at all, and
    // composables under test now resolve real ones: SearchQueryText reaches
    // PublishFieldSelection, which reads :core:common's cut/copy/paste labels.
    // Costs an AAPT2 link per test run of this module; :app keeps it off.
    testOptions.unitTests.isIncludeAndroidResources = true
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
    api(project(":core:config"))
    api(project(":core:common"))
    api(project(":core:language"))
    api(project(":core:input"))
    api(project(":core:keyman"))
    api(project(":core:prediction"))
    api(project(":core:emoji"))
    api(project(":core:theme"))
    api(project(":core:icons"))
    api(project(":core:content"))
    api(project(":core:tools"))
    api(project(":core:kdeconnect"))
    api(project(":core:homeassistant"))
    api(project(":core:smarthome"))
    api(project(":core:send"))
    api(project(":core:settings"))
    api(project(":core:addons"))
    api(project(":core:voice"))
    api(project(":core:feedback"))
    api(project(":core:plugins"))
    api(project(":core:intelligence"))
    api(project(":feature:tools"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    // Named sections in system traces (ImeTrace). A native flag check when
    // nobody is tracing, so it stays in release builds.
    implementation(libs.androidx.tracing)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.network.okhttp)
    if (!googleSdks) {
        // F-Droid: no ML Kit at all; see src/nogoogle.
    } else if (playStoreChannel) {
        implementation(libs.mlkit.text.recognition.unbundled)
        implementation(libs.mlkit.barcode.scanning.unbundled)
    } else {
        implementation(libs.mlkit.text.recognition)
        implementation(libs.mlkit.barcode.scanning)
    }
    // The other four OCR scripts (#757), unbundled on every channel: Play
    // services fetches each model on first use. About 3 KB of code each, as
    // the shared pipeline is already here through the Latin artifact (the
    // bundled one depends on the unbundled one). Without Play services those
    // scripts read with Paddle or Tesseract instead (OcrLanguages).
    if (googleSdks) {
        // PairDrop's data channels. Only the Java API stays in the APK: the
        // library is the `webrtc` gate's (native/gates.json).
        implementation(libs.webrtc.android)
        implementation(libs.mlkit.text.recognition.chinese.unbundled)
        implementation(libs.mlkit.text.recognition.japanese.unbundled)
        implementation(libs.mlkit.text.recognition.korean.unbundled)
        implementation(libs.mlkit.text.recognition.devanagari.unbundled)
        implementation(libs.mlkit.document.scanner)
    }
    if (googleHome && playStoreChannel) {
        // In :feature:googlehome; see the source sets above.
    } else if (googleHome && googleHomeVersion != null) {
        implementation("com.google.android.gms:play-services-home:$googleHomeVersion")
        implementation("com.google.android.gms:play-services-home-types:$googleHomeVersion")
    } else if (googleHome && googleHomeAars) {
        implementation(fileTree("libs/googlehome") { include("*.aar", "*.jar") })
    }

    testImplementation(libs.junit)
    // A virtual clock for the coroutine plumbing around the input connection.
    testImplementation(libs.kotlinx.coroutines.test)
    // Flow assertions on the published keyboard state: every emission accounted for.
    testImplementation(libs.turbine)
    // An Android runtime on the JVM, so a test can drive WMKeyboardService
    // itself rather than only the pure helpers around it.
    testImplementation(libs.robolectric)
    // Compose's test rule and clock, for layout behaviour no plain test can
    // see: when RowRevealHeadroom lets the frame's height change while a bar
    // row animates.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // The rule asks for older androidx.test artifacts than anything else here
    // resolves; pin the versions :app's instrumented tests already use.
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.espresso.core)
}

// Tests that reach the Rust text engines load libwm_engine_jni for this
// machine, which :core:language builds; see the comment on its task.
tasks.withType<Test>().configureEach { dependsOn(":core:language:buildHostEngine") }
