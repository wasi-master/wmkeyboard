pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// Convention plugins (per-module type-resolved detekt).
includeBuild("build-logic")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // The Google Home APIs SDK, unpacked under feature/ime/libs/googlehome/m2
        // (see the README there): only its two artifacts come from here.
        val googleHomeM2 = rootDir.resolve("feature/ime/libs/googlehome/m2")
        if (googleHomeM2.isDirectory) {
            maven {
                url = uri(googleHomeM2)
                content {
                    includeModule("com.google.android.gms", "play-services-home")
                    includeModule("com.google.android.gms", "play-services-home-types")
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "WM Keyboard"
include(":app")

// Library modules, layered bottom-up. :core:config carries the build flags and
// API keys every library reads; :core:common the shared utilities; the rest are
// one feature area each. :feature:* sit above :core:settings (they read the
// settings repository), :feature:ime is the keyboard runtime itself.
include(":core:config")
include(":core:common")
include(":core:language")
include(":core:input")
include(":core:keyman")
include(":core:prediction")
include(":core:emoji")
include(":core:theme")
include(":core:icons")
include(":core:tools")
include(":core:kdeconnect")
include(":core:send")
include(":core:homeassistant")
include(":core:smarthome")
include(":core:content")
include(":core:addons")
include(":core:voice")
include(":core:settings")
include(":core:feedback")
include(":core:plugins")
include(":core:intelligence")
include(":core:shell")
include(":feature:tools")
include(":feature:addons")
include(":feature:ime")
// The on-demand gates' Play modules (`:feature:<id>` for every gate of
// native/gates.json with a `play` entry), Play channel only: :app adds the
// same modules to `dynamicFeatures` (the wmkeyboard.native-gates convention).
// A dynamic-feature module that no app registers cannot resolve its own
// `featureName`, so including them unconditionally breaks every non-Play build
// the moment an unqualified task name (`assembleDebug`, and so the whole of
// CI) fans out across every project. They are therefore in the build only
// when the channel that consumes them is. Developers who want them in the IDE
// have wmkb.enablePlayStore=true in local.properties already, since that is
// what building for Play needs.
val localProperties = java.util.Properties()
file("local.properties").takeIf { it.exists() }?.inputStream()?.use(localProperties::load)

// Same precedence as `flag()` in app/build.gradle.kts: -P, then
// local.properties, then the environment, then the default.
fun wmkbFlag(name: String, env: String, default: Boolean): Boolean =
    (providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(env)
        ?: default.toString()).toBoolean()

if (wmkbFlag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE", default = false)) {
    @Suppress("UNCHECKED_CAST")
    val gates = (groovy.json.JsonSlurper().parse(file("native/gates.json")) as Map<String, Any?>)["gates"] as List<Map<String, Any?>>
    for (gate in gates) {
        if (gate["play"] == null) continue
        // A gate the build switches off has no module either: the NPU
        // runtime unless wmkb.enableNpu (the flag :core:config reads), the
        // Google Home APIs unless their SDK is unpacked under
        // feature/ime/libs/googlehome (no flag: the SDK being there is the
        // decision).
        val by = gate["enabledBy"] as Map<*, *>?
        if (by != null) {
            val dir = by["requiresDir"] as String?
            if (dir != null && !file(dir).isDirectory) continue
            val flag = by["flag"] as String?
            if (flag != null && !wmkbFlag(flag, by["env"] as String, by["default"] as Boolean)) continue
        }
        include(":feature:${gate["id"]}")
    }
}
// Macrobenchmarks and the baseline profile generator (-Pwmkb.benchmark=true).
// Out of the ordinary build for two reasons. Applying the baselineprofile
// plugin to :app adds two build types to every flavour pair, which every
// configuration, analyser and `./gradlew tasks` then pays for. And this
// module's tests install a release-like build over the app on whichever
// device is connected, which nobody should get by running a task on a whim.
if (wmkbFlag("wmkb.benchmark", "WMKB_BENCHMARK", default = false)) include(":benchmark")
// Host-side dictionary compiler: turns dictionaries-src/*.txt into the .wmdict
// binary assets at build time, sharing the app's own trie/codec sources so the
// written format can never drift from the reader.
include(":tools:dictc")
// Konsist architecture rules over the whole source tree (tests only).
include(":tools:architecture")
