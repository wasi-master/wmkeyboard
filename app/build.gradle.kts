import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// API keys for the network tools (GIF/sticker via KLIPY/GIPHY, web/image search via
// Brave Search, optional official Cloud Translation). Read from
// local.properties (never committed) or, failing that, environment variables —
// all optional: without a key the affected tool shows a "needs API key" panel
// and users can paste their own key in the tool's settings.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun apiKey(propertyName: String, envName: String): String =
    localProperties.getProperty(propertyName)?.trim()
        ?: System.getenv(envName)?.trim()
        ?: ""

fun flag(propertyName: String, envName: String): Boolean =
    (providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(envName)
        ?: "false").toBoolean()

// Whether this build is going to the Play Store. It decides more than a label:
// Play In-App Updates only works for an install that came from Play, and the
// library is a Google binary that F-Droid will not accept, so it must be
// absent from every other channel rather than merely unused. The flag
// therefore selects a source directory as well as a dependency —
// `src/play/java` drives the real AppUpdateManager, `src/noplay/java` declares
// the same entry point and does nothing. Everything else lives in
// `src/main/java` and compiles either way.
val playStoreChannel = flag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE")
val updateChannelSourceDir = if (playStoreChannel) "src/play/java" else "src/noplay/java"

// Whether Google Play services may be compiled in. Separate from the store
// channel above, and deliberately: a sideloaded build on an ordinary phone
// should still be able to reach Drive, while an F-Droid build must not carry
// the library at all. Same shape as the channel seam — `src/gms/java` supplies
// the real Drive authorizer, `src/nogms/java` declares the same entry point and
// reports the destination unavailable. The only thing behind it is getting an
// OAuth token; the Drive REST calls themselves are ordinary HTTP in
// :core:settings and compile everywhere.
val gmsChannel = flag("wmkb.enableGms", "WMKB_ENABLE_GMS")
val gmsSourceDir = if (gmsChannel) "src/gms/java" else "src/nogms/java"

// Sideload packaging. With `-Pwmkb.splitApks=true`, assemble<Variant> emits one
// APK per ABI plus a universal fallback instead of a single fat APK — the
// arm64 artifact is roughly half the universal's size, which is what most
// GitHub-release downloaders want. Off by default so day-to-day builds and the
// AAB pipeline (Play does its own ABI splitting) are untouched. When it is on,
// the ndk.abiFilters lines below step aside: AGP treats abiFilters and ABI
// splits as conflicting ways of saying the same thing.
val splitApks = flag("wmkb.splitApks", "WMKB_SPLIT_APKS")

android {
    // The unit-test worker dies with an EOFException on the default 512m: the
    // settings tests parse every strings*.xml in the app to check the search
    // index against the real resources, and the whole app suite runs in one
    // worker.
    testOptions.unitTests.all { it.maxHeapSize = "2g" }

    namespace = "com.wasimaster.wmkeyboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.wasimaster.wmkeyboard"
        minSdk = 24
        targetSdk = 36
        // Both from gradle.properties so :core:config stamps the same numbers on
        // crash reports as the manifest carries. Never hardcode them here again.
        versionCode = providers.gradleProperty("wmkb.versionCode").get().toInt()
        versionName = providers.gradleProperty("wmkb.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI target filtering is handled by ndk.abiFilters per buildType below.

        buildConfigField("String", "KLIPY_API_KEY", "\"${apiKey("wmkb.klipyApiKey", "WMKB_KLIPY_API_KEY")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${apiKey("wmkb.giphyApiKey", "WMKB_GIPHY_API_KEY")}\"")
        buildConfigField("String", "BRAVE_API_KEY", "\"${apiKey("wmkb.braveApiKey", "WMKB_BRAVE_API_KEY")}\"")
        buildConfigField("String", "TRANSLATE_API_KEY", "\"${apiKey("wmkb.translateApiKey", "WMKB_TRANSLATE_API_KEY")}\"")
        buildConfigField("String", "UNSPLASH_API_KEY", "\"${apiKey("wmkb.unsplashApiKey", "WMKB_UNSPLASH_API_KEY")}\"")
        buildConfigField("String", "PEXELS_API_KEY", "\"${apiKey("wmkb.pexelsApiKey", "WMKB_PEXELS_API_KEY")}\"")
        buildConfigField("Boolean", "ENABLE_PLAY_STORE", "${flag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE")}")
        buildConfigField("Boolean", "ENABLE_FDROID", "${flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")}")
        buildConfigField("Boolean", "ENABLE_GMS", "$gmsChannel")
        // OAuth client ids for the Dropbox and OneDrive backup destinations.
        // Not secrets: the sign-in uses PKCE so no client secret ships. A
        // build without them simply leaves those two destinations out.
        buildConfigField("String", "DROPBOX_APP_KEY", "\"${apiKey("wmkb.dropboxAppKey", "WMKB_DROPBOX_APP_KEY")}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${apiKey("wmkb.oneDriveClientId", "WMKB_ONEDRIVE_CLIENT_ID")}\"")
        // Diagnostic builds only — see the same field in :core:config, which is
        // the copy DebugLog reads. Mirrored here for the app-package screens.
        buildConfigField("Boolean", "ENABLE_CRASH_SCREEN", "${flag("wmkb.enableCrashScreen", "WMKB_ENABLE_CRASH_SCREEN")}")
    }

    // Build flavors for storage-constrained devices.
    // - full: all features (handwriting, OCR, QR scan, doc scan, grammar checker).
    // - lite: removes ~100 MB of ML Kit + Harper native libraries for low-storage devices.
    flavorDimensions += "capabilities"
    productFlavors {
        create("full") {
            dimension = "capabilities"
            buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "true")
            buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "true")
            buildConfigField("Boolean", "ENABLE_GRAMMAR", "true")
            buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "true")
            buildConfigField("Boolean", "ENABLE_WHISPER", "true")
        }
        create("lite") {
            dimension = "capabilities"
            buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "false")
            buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "false")
            buildConfigField("Boolean", "ENABLE_GRAMMAR", "false")
            buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "false")
            buildConfigField("Boolean", "ENABLE_WHISPER", "false")
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = apiKey("RELEASE_STORE_FILE", "RELEASE_STORE_FILE")
            if (storeFilePath.isNotEmpty()) {
                val file = rootProject.file(storeFilePath)
                if (file.exists()) {
                    storeFile = file
                    storePassword = apiKey("RELEASE_STORE_PASSWORD", "RELEASE_STORE_PASSWORD")
                    keyAlias = apiKey("RELEASE_KEY_ALIAS", "RELEASE_KEY_ALIAS")
                    keyPassword = apiKey("RELEASE_KEY_PASSWORD", "RELEASE_KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                if (!splitApks) abiFilters += setOf("arm64-v8a")
            }
        }
        release {
            ndk {
                if (!splitApks) abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
                debugSymbolLevel = "FULL"
            }
            // No debug-key fallback. A debug-signed "release" build looks
            // shippable and is not: Play rejects the debug key outright, and
            // anything installed from such a build can never be updated by the
            // real key. Without the keystore the build produces an *unsigned*
            // release APK instead — an obvious failure rather than a silent one.
            signingConfig = signingConfigs.getByName("release")
                .takeIf { it.storeFile?.exists() == true }
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        // A build for putting the app on a phone and using it, which is not
        // what either of the other two is for. `debug` is unusable as a
        // keyboard: a debuggable process gets no aggressive ART compilation,
        // ignores the baseline profile entirely, and carries Compose's
        // source-info and recomposition tracing, so the thing you are typing
        // on is not the thing you are shipping. `release` types like the real
        // app but costs a fourteen-minute R8 run to find out.
        //
        // `fast` is release minus R8: not debuggable, release-signed,
        // baseline profile applied, and no shrinking at all. It runs close
        // enough to release to judge typing latency by hand, and it builds in
        // roughly the time the Kotlin compile alone takes.
        //
        //     ./gradlew :app:installFullFast
        //
        // What it deliberately does not test: anything R8 does. Keep rules
        // (the luaj ones especially) are never exercised here, so a build that
        // works as `fast` can still crash as `release`. Ship-checking is
        // `release`'s job; this one is for iterating.
        create("fast") {
            initWith(getByName("release"))
            // The whole point. Both switches, not one: under AGP 9
            // `optimization.enable` is a second, independent way to turn R8
            // on ("when enabled the Android plugin uses R8 for optimization"),
            // and `initWith` above copies release's `true` along with
            // everything else. Setting only `isMinifyEnabled = false` leaves
            // `minify<Variant>WithR8` doing the full shrink-and-optimize pass
            // this build type exists to skip.
            isMinifyEnabled = false
            isShrinkResources = false
            optimization {
                enable = false
            }
            // `initWith` copies it from release, which is already false --
            // stated here because it is the reason this build type exists.
            isDebuggable = false
            // Library modules declare only `debug` and `release`, so their
            // `release` variant is what a `fast` app links against.
            matchingFallbacks += "release"
            ndk {
                // One ABI: the phone in your hand, not the three the store
                // needs. Skips packaging and stripping two ABIs' worth of ML
                // Kit and LiteRT native code. `debugSymbolLevel` off for the
                // same reason -- nothing here is going to a crash symbolicator.
                abiFilters.clear()
                if (!splitApks) abiFilters += setOf("arm64-v8a")
                debugSymbolLevel = "NONE"
            }
        }
    }
    // Play builds carry the LiteRT-LM runtime as an on-demand module instead
    // of ~20 MB per ABI in every install; LocalLlmEngine requests it on first
    // AI use. Channel-gated because Play Feature Delivery only exists on
    // Play: sideload and F-Droid builds compile the same runtime code into
    // :core:intelligence instead (see playStoreChannel there), so for them
    // the module is not even part of the build graph.
    if (playStoreChannel) {
        dynamicFeatures += ":feature:llm"
    }

    // See the splitApks flag at the top of this file. Splits shape APK
    // assembly only — bundle<Variant> ignores this block entirely.
    splits {
        abi {
            isEnable = splitApks
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    packaging {
        jniLibs {
            // Uncompressed and page-aligned in the APK, which is both what the
            // 16 KB page-size requirement wants and what lets the loader mmap
            // the library straight out of the APK.
            useLegacyPackaging = false
            // No keepDebugSymbols override: AGP strips as usual. Every .so we
            // package — ours and the prebuilt ML Kit/LiteRT ones — already
            // ships stripped (measured: 48 bytes of symbol table across all
            // three ABIs), so keeping them was buying nothing.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Android Lint. Severities live in config/lint/lint.xml — this block only
    // says *what* to analyse and *how loudly* to report it.
    lint {
        lintConfig = rootProject.file("config/lint/lint.xml")

        // Turn on every check Lint ships, including the large set that is
        // disabled by default (interoperability, correctness edge cases,
        // API-surface checks). lint.xml then silences the ones that do not
        // apply to an IME, each with a written reason.
        checkAllWarnings = true
        checkDependencies = true
        // Test sources ship bugs too, and the unit tests here encode the
        // dictionary/transliteration contracts.
        checkTestSources = true
        ignoreTestFixturesSources = false
        checkGeneratedSources = false

        // Real problems stop the build; style-grade warnings do not, so the
        // signal stays readable. Escalation to `error` is per-issue in lint.xml.
        abortOnError = true
        warningsAsErrors = false
        // `lintVital` runs automatically before every release build when this
        // is on, and with `checkDependencies` above that means a full
        // 19-module analysis — measured at 25 minutes, roughly half the wall
        // clock of a release install, to produce a report nobody reads at that
        // moment. Off by default so building a release APK for the phone is a
        // build and not an audit; CI turns it back on:
        //
        //     ./gradlew lintFullRelease -Pwmkb.lintRelease=true
        //
        // Every other lint setting in this block is untouched, so the explicit
        // `lint*` tasks still analyse exactly as much as they always did.
        checkReleaseBuilds =
            providers.gradleProperty("wmkb.lintRelease").map { it.toBoolean() }.getOrElse(false)

        // Print the full explanation and the offending lines, not just the
        // one-line summary — a finding nobody understands is a finding nobody
        // fixes.
        explainIssues = true
        noLines = false
        absolutePaths = false

        // AGP 9 always writes HTML/XML/SARIF/text reports to
        // build/reports/lint-results-<variant>.*; the report toggles and output
        // paths were removed from the DSL.
    }

    // The Harper native libraries live in :core:intelligence under src/full/jniLibs,
    // so lite builds exclude them by construction — no source-set surgery needed here.
}

// Compiles dictionaries-src/*.txt into assets/dictionaries/*.wmdict via the
// :tools:dictc host tool, which shares the app's own trie/codec sources so the
// binary format cannot drift between writer and reader. Wired into every
// variant's assets through the Variant API below.
val dictc: Configuration by configurations.creating

abstract class CompileDictionariesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    /** Assets root chosen by AGP; wordlists land in `dictionaries/` inside it. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: org.gradle.process.ExecOperations

    @TaskAction
    fun run() {
        execOperations.javaexec {
            classpath = toolClasspath
            mainClass.set("MainKt")
            args(
                sourceDir.get().asFile.absolutePath,
                outputDir.get().asFile.resolve("dictionaries").absolutePath,
            )
        }
    }
}

val compileBundledDictionaries =
    tasks.register<CompileDictionariesTask>("compileBundledDictionaries") {
        sourceDir.set(layout.projectDirectory.dir("dictionaries-src"))
        toolClasspath.from(dictc)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            compileBundledDictionaries,
            CompileDictionariesTask::outputDir,
        )
        // The update-channel driver picked at the top of this file, added to
        // every production variant. This is the Variant API rather than the
        // `android.sourceSets` or `kotlin.sourceSets` DSL because neither of
        // those reaches the Kotlin compilation under AGP 9: there is no
        // `main` Kotlin source set any more, and an extra *Java* directory is
        // not mirrored into the Kotlin task, so a directory added that way
        // compiles nothing and every reference into it fails to resolve.
        //
        // The store channel is not a flavour of its own on purpose: it is
        // orthogonal to full/lite, and a second dimension would double every
        // variant and every Gradle task name in the project for one file.
        variant.sources.kotlin?.addStaticSourceDirectory(updateChannelSourceDir)
        // Same reasoning, same mechanism, for the Drive authorizer.
        variant.sources.kotlin?.addStaticSourceDirectory(gmsSourceDir)
    }
}

kotlin {
    // The update-channel driver picked at the top of this file. It has to be
    // declared here and not only in `android.sourceSets`: under AGP 9 the
    // Kotlin plugin no longer mirrors extra Java source directories into its
    // own compilation, so a directory added there alone compiles nothing and
    // every reference into it fails to resolve.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)

        // The compiler is the cheapest static analyser available: it already
        // knows the types. `extraWarnings` turns on the diagnostics that are
        // off by default — unused expression results, redundant casts and
        // labels, useless elvis branches, unnecessary type arguments.
        extraWarnings.set(true)

        freeCompilerArgs.addAll(
            // Without this the compiler stops reporting warnings for a file as
            // soon as that file has an error — which hides warnings exactly when
            // the code is being changed most.
            "-Xreport-all-warnings",
        )

        // Gated rather than unconditional: CI can enforce a zero-warning build
        // (`./gradlew assemble -PwarningsAsErrors=true`) without making every
        // local edit fail on a warning mid-refactor.
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

// ---------------------------------------------------------------------------
// detekt: rule-based static analysis over the Kotlin sources.
//
// The `detekt<Variant>` tasks (detektFullDebug, ...) run *with type
// resolution* — they get the variant's compile classpath, which is what the
// high-value rules need: nullability, unreachable code, ignored return values
// and exhaustive-`when` checks are all impossible without resolved types.
// The plain `detekt` task is source-only and much weaker; it stays available
// for a fast pass but is not what `staticAnalysis` runs.
// ---------------------------------------------------------------------------
// Library-module *test* sources, analysed by this module's unit-test detekt
// task below. Main sources are NOT swept from here — each module's own
// wmkeyboard.detekt plugin analyses them against that module's classpath.
// (Analysing module sources with the app's classpath makes detekt see those
// classes as both source and binary, which fabricates UnreachableCode
// findings.) Test sources are safe to sweep: they are never packaged into a
// jar on this classpath.
val moduleTestSrc = listOf(
    "../core/language", "../core/tools", "../core/emoji",
    "../core/intelligence", "../feature/tools", "../feature/ime",
).map { "$it/src/test/java" } + listOf("../core/intelligence/src/testFull/java")

detekt {
    toolVersion = libs.versions.detekt.get()
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    // `allRules` would also enable rules detekt itself marks as unstable or
    // opinionated; the ruleset in detekt.yml is curated explicitly instead.
    allRules = false
    parallel = true
    ignoreFailures = false
    // Report paths relative to the repo root so findings are clickable from CI
    // logs regardless of where the checkout lives.
    basePath = rootProject.projectDir.absolutePath
    source.setFrom(
        files(
            "src/main/java",
            "src/full/java",
            "src/lite/java",
            // Only the channel this build compiles: the other directory names
            // Play Core types that are not on any classpath here.
            updateChannelSourceDir,
            gmsSourceDir,
            "src/test/java",
            "src/testFull/java",
            "src/androidTest/java",
        ),
    )
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "11"
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(true)
        md.required.set(false)
        txt.required.set(false)
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "11"
}

// detekt 1.23's Android integration is written against the AGP 7/8 variant API
// and creates no `detekt<Variant>` tasks under AGP 9, which would leave the
// project with source-only analysis — losing every rule that needs resolved
// types. So the type-resolution tasks are registered here by hand: same Detekt
// task type, classpath taken from the variant's own Kotlin compilation.
//
//   ./gradlew detektFullDebug            # main + flavour sources
//   ./gradlew detektFullDebugUnitTest    # unit tests, with main on the classpath
fun registerTypeResolvedDetekt(
    taskName: String,
    description: String,
    sourceDirs: List<String>,
    compileTaskName: String,
    extraClasspath: List<String> = emptyList(),
) = tasks.register<Detekt>(taskName) {
    this.description = description
    group = "verification"

    val compileTask =
        tasks.named(compileTaskName, org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class)

    setSource(files(sourceDirs.map { layout.projectDirectory.dir(it) }))
    include("**/*.kt")
    exclude("**/build/**", "**/resources/**")

    // `libraries` is the variant's resolved compile classpath; android.jar has
    // to be added separately because it is on the bootclasspath, not there.
    classpath.setFrom(
        compileTask.map { it.libraries },
        androidComponents.sdkComponents.bootClasspath,
        files(extraClasspath.map { layout.buildDirectory.dir(it) }),
    )
    if (extraClasspath.isNotEmpty()) {
        dependsOn(compileTask)
    }

    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false
    basePath = rootProject.projectDir.absolutePath
    jvmTarget = "11"

    reports {
        html.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.html"))
        xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.xml"))
        sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/$taskName.sarif"))
    }
}

registerTypeResolvedDetekt(
    taskName = "detektFullDebug",
    description = "Runs detekt with type resolution over the fullDebug variant's sources.",
    sourceDirs = listOf("src/main/java", "src/full/java", updateChannelSourceDir, gmsSourceDir),
    compileTaskName = "compileFullDebugKotlin",
)

registerTypeResolvedDetekt(
    taskName = "detektLiteDebug",
    description = "Runs detekt with type resolution over the liteDebug variant's sources.",
    sourceDirs = listOf("src/main/java", "src/lite/java", updateChannelSourceDir, gmsSourceDir),
    compileTaskName = "compileLiteDebugKotlin",
)

registerTypeResolvedDetekt(
    taskName = "detektFullDebugUnitTest",
    description = "Runs detekt with type resolution over the unit tests, including the library modules'.",
    sourceDirs = listOf("src/test/java", "src/testFull/java") + moduleTestSrc,
    compileTaskName = "compileFullDebugUnitTestKotlin",
    extraClasspath = listOf("tmp/kotlin-classes/fullDebug"),
)

registerTypeResolvedDetekt(
    taskName = "detektFullDebugAndroidTest",
    description = "Runs detekt with type resolution over the instrumentation tests.",
    sourceDirs = listOf("src/androidTest/java"),
    compileTaskName = "compileFullDebugAndroidTestKotlin",
    extraClasspath = listOf("tmp/kotlin-classes/fullDebug"),
)

// `./gradlew check` should mean "everything the analysers know how to check".
tasks.named("check") {
    dependsOn("detektFullDebug", "detektLiteDebug", "detektFullDebugUnitTest")
}

dependencies {
    dictc(project(":tools:dictc"))

    // Installs app/src/main/baseline-prof.txt into the app's ART profile so the
    // listed methods are compiled ahead of time. Declared explicitly, not left
    // to arrive transitively behind Compose and Coil: it already does arrive
    // that way, but a dependency bump that dropped it would leave the profile
    // packaged in the APK and never applied — the keyboard would quietly go
    // back to warming up from cold on every process start, with nothing
    // failing to say so. Play installs are covered by the store either way;
    // this is what covers F-Droid and sideloads.
    implementation(libs.androidx.profileinstaller)

    // Static-analysis rule packs. These are analyser plugins, not app code —
    // nothing here reaches the APK.
    detektPlugins(libs.detekt.formatting)
    // Compose correctness rules (unremembered state, unstable parameters,
    // modifier misuse, composables that read state too often). Compose bugs are
    // recomposition bugs, and none of them are visible to the type checker.
    lintChecks(libs.compose.lint.checks)

    // Feature modules. Their build files declare project dependencies with
    // `api`, so :feature:ime alone would reach every :core:* transitively —
    // the explicit list is for the unit tests in src/test, which compile
    // against classes from every layer.
    implementation(project(":core:config"))
    implementation(project(":core:common"))
    implementation(project(":core:language"))
    implementation(project(":core:input"))
    implementation(project(":core:prediction"))
    implementation(project(":core:emoji"))
    implementation(project(":core:theme"))
    implementation(project(":core:icons"))
    implementation(project(":core:tools"))
    implementation(project(":core:content"))
    implementation(project(":core:addons"))
    implementation(project(":core:voice"))
    implementation(project(":core:settings"))
    implementation(project(":core:feedback"))
    implementation(project(":core:plugins"))
    implementation(project(":core:intelligence"))
    implementation(project(":feature:tools"))
    implementation(project(":feature:addons"))
    implementation(project(":feature:ime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    // The fingerprint / device-credential prompt in front of the settings the
    // user chose to lock. Also why MainActivity is a FragmentActivity.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // The language-settings screens edit DataStore Preferences directly.
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    // Play In-App Updates, for Play-channel builds only. Compiled against by
    // src/play/java; src/noplay/java is what every other channel gets, so no
    // Google binary is linked into an F-Droid or direct-download APK.
    if (playStoreChannel) {
        implementation(libs.play.app.update)
        // SplitInstall + SplitCompat for the on-demand :feature:llm module.
        implementation(libs.play.feature.delivery)
    }
    // Only for the Drive backup destination's OAuth token. The Drive calls
    // themselves are plain HTTP and need nothing from Google.
    if (gmsChannel) {
        implementation(libs.play.services.auth)
    }

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The plugin tests drive the Lua sandbox through luaj types directly.
    testImplementation(libs.luaj.jse)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // The empty activity ComposeTestRule launches into. Debug-only by design:
    // it adds a manifest entry, so it must never reach a release build.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// `-Pwmkb.skipBenchmarks` drops the wall-clock benchmarks from the run. They
// assert against millisecond budgets measured on a quiet machine, so on a
// shared CI runner they measure the runner's neighbours rather than this
// project. Nothing else is filtered: the accuracy evals still run.
if (providers.gradleProperty("wmkb.skipBenchmarks").map(String::toBoolean).getOrElse(false)) {
    tasks.withType<Test>().configureEach {
        filter {
            excludeTestsMatching("*LatencyBench")
            excludeTestsMatching("*NoiseSweepTest")
        }
    }
}
