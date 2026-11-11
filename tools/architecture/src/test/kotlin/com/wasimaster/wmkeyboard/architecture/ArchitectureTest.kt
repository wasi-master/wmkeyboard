package com.wasimaster.wmkeyboard.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * House rules the compiler cannot see, checked by reading the sources with
 * Konsist. Each one is here because breaking it compiles, passes every other
 * test, and costs something real later: an F-Droid build that no longer builds, a
 * key grid that recomposes on every keystroke, a dictionary compiler that
 * quietly drops a file.
 *
 *     ./gradlew :tools:architecture:test
 *
 * The scopes are directories, never the whole project: the checkout holds the
 * session worktrees under `.claude/`, each a full copy of the tree, and parsing
 * those would multiply the run and report every finding several times over.
 */
class ArchitectureTest {

    private val root = File(projectRoot())

    /** Every module's production sources: app, core, feature, tools. */
    private val production: KoScope by lazy {
        sources("app/src") + sources("core") + sources("feature") + sources("tools")
    }

    // ---- F-Droid stand-ins ------------------------------------------------

    /**
     * A `src/nogoogle` stub stands in for `src/google` code (the classes that
     * call ML Kit directly) in F-Droid's build, and `src/main` compiles against
     * whichever of the two the build picked. So a name `src/main` uses has to
     * exist in both, or one build stops compiling; locally nothing notices
     * until an F-Droid build runs, which is almost never.
     *
     * Checked per module, over each side's whole source set rather than file
     * against file: the google side may split what a stub keeps in one file,
     * and carries helpers of its own that only google code calls, which is
     * fine. What is not fine:
     *  - a stub file with no google file at the same path;
     *  - a stub declaring something the google side does not (the stub has
     *    drifted from the API it stands in for);
     *  - `src/main` naming something only the google side declares. That is
     *    read textually: a top-level name as a word, an object's member as
     *    `Object.member`. A class's instance members are not checked, since a
     *    call through a variable cannot be traced to its class by reading.
     */
    @Test
    fun `F-Droid stubs offer what main code uses of the google sources`() {
        val problems = mutableListOf<String>()
        val stubs = trackedFiles { "/src/nogoogle/java/" in it && it.endsWith(".kt") }
        val main = trackedFiles { path -> path.endsWith(".kt") && MAIN_SOURCE.containsMatchIn(path) }
            .joinToString("\n") { code(File(root, it).readText()) }
        for ((module, nogoogle) in stubs.groupBy { it.substringBefore("/src/nogoogle/java/") }) {
            val google = trackedFiles { it.startsWith("$module/src/google/java/") && it.endsWith(".kt") }
            nogoogle.map { it.replace("/src/nogoogle/java/", "/src/google/java/") }
                .filterNot { File(root, it).isFile }
                .forEach { problems += "$it: missing, though the F-Droid stub at the same path stands in for it" }
            val stubApi = nogoogle.flatMap(::api).toSet()
            val googleApi = google.flatMap(::api).toSet()
            (stubApi - googleApi).forEach { problems += "$module: src/nogoogle declares ${it.label}, src/google does not" }
            (googleApi - stubApi).filter { it.usedIn(main) }
                .forEach { problems += "$module: main code uses ${it.label}, which only src/google declares" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** One name a file offers: its kind, the object it belongs to if any, and the name. */
    private data class Api(val kind: String, val owner: String?, val name: String) {
        val label get() = if (owner == null) "$kind $name" else "$kind $owner.$name"

        /** Whether [code] names it: a top-level name as a word, an object member qualified. */
        fun usedIn(code: String): Boolean = when {
            kind == "member" -> false
            owner != null -> Regex("""\b${Regex.escape(owner)}\.${Regex.escape(name)}\b""").containsMatchIn(code)
            else -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(code)
        }
    }

    /** Non-private names [path] offers: top-level declarations and their members. */
    private fun api(path: String): List<Api> {
        val file = Konsist.scopeFromFile(path).files.single()
        val out = mutableListOf<Api>()
        file.classes(false).filter { !it.hasPrivateModifier }.forEach { c ->
            out += Api("class", null, c.name)
            c.functions(false, false).filter { !it.hasPrivateModifier }.forEach { out += Api("member", c.name, it.name) }
            c.properties(false).filter { !it.hasPrivateModifier }.forEach { out += Api("member", c.name, it.name) }
        }
        file.objects(false).filter { !it.hasPrivateModifier }.forEach { o ->
            out += Api("object", null, o.name)
            o.functions(false, false).filter { !it.hasPrivateModifier }.forEach { out += Api("fun", o.name, it.name) }
            o.properties(false).filter { !it.hasPrivateModifier }.forEach { out += Api("val", o.name, it.name) }
        }
        file.interfaces(false).filter { !it.hasPrivateModifier }.forEach { out += Api("interface", null, it.name) }
        file.functions(false, false).filter { it.isTopLevel && !it.hasPrivateModifier }
            .forEach { out += Api("fun", null, it.name) }
        file.properties(false).filter { it.isTopLevel && !it.hasPrivateModifier }
            .forEach { out += Api("val", null, it.name) }
        return out
    }

    // ---- the key grid -----------------------------------------------------

    /**
     * The service publishes a fresh `KeyboardUiState` for every keystroke, and
     * an unstable parameter is compared by instance, so a key composable that
     * takes the state can never skip: all forty keys recompose on every press.
     * Keys take a resolved `KeyVisual` instead; `KeyRows` is the boundary that
     * reads the state, on purpose. Breaking this makes typing slower and
     * nothing fails, which is why it is written down here.
     */
    @Test
    fun `key composables never take the whole keyboard state`() {
        val keyComposables = setOf("KeyRow", "KeyCell", "KeyButton", "KeyContent")
        val functions = sources("feature/ime/src/main").functions()
            .filter { fn -> fn.name in keyComposables && fn.annotations.any { it.name == "Composable" } }
        assertEquals(
            "every key composable is still where this test looks",
            keyComposables,
            functions.map { it.name }.toSet(),
        )
        val offenders = functions.filter { fn -> fn.parameters.any { it.type.name == "KeyboardUiState" } }
        assertTrue(
            "take a KeyVisual, not KeyboardUiState: ${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    // ---- coroutines -------------------------------------------------------

    /**
     * Work launched on `GlobalScope` outlives the keyboard window, the service
     * and every test, and nobody cancels it. The service has `serviceScope`,
     * the stores their own; there is always a scope with an owner.
     */
    @Test
    fun `nothing launches on GlobalScope`() {
        val offenders = production.files
            .filter { file -> file.imports.any { it.name == "kotlinx.coroutines.GlobalScope" } || "GlobalScope." in code(file.text) }
            .map { it.path.removePrefix(root.path + "/") }
        assertTrue("GlobalScope used in $offenders", offenders.isEmpty())
    }

    // ---- tests ------------------------------------------------------------

    /**
     * compileSdk is the minor level 36.1, for which no android-all jar exists,
     * and an AGP 9 library has no targetSdk for Robolectric to fall back on. A
     * Robolectric test therefore needs its SDK pinned, by the source set's
     * robolectric.properties or the class's own `@Config(sdk = …)`.
     */
    @Test
    fun `every Robolectric test runs on a pinned SDK`() {
        val unpinned = trackedFiles { it.endsWith(".kt") && TEST_SOURCE.containsMatchIn(it) }
            .filter { path ->
                val text = File(root, path).readText()
                "RobolectricTestRunner" in text && !Regex("""@Config\([^)]*sdk\s*=""").containsMatchIn(text)
            }
            .filterNot { path -> pinnedBySourceSet(path) }
        assertTrue("Robolectric tests without a pinned sdk:\n${unpinned.joinToString("\n")}", unpinned.isEmpty())
    }

    private fun pinnedBySourceSet(path: String): Boolean {
        val sourceSet = path.substringBefore("/java/")
        val properties = File(root, "$sourceSet/resources/robolectric.properties")
        return properties.isFile && properties.readLines().any { it.trim().startsWith("sdk=") }
    }

    // ---- clipboard --------------------------------------------------------

    /**
     * Every clipboard write the keyboard makes goes through
     * `ClipboardWriteGate` (#1215), which sets Android's sensitive flag where
     * asked and the token the copy listener lets the keyboard's own writes go
     * by with. A new direct call compiles and passes every other test, then
     * quietly skips both. The count below is the paste-next path still
     * waiting to move; it may only go down.
     */
    @Test
    fun `keyboard clipboard writes go through the write gate`() {
        val allowed = mapOf("feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/WMKeyboardService.kt" to 3)
        val offenders = trackedFiles { it.endsWith(".kt") && "feature/ime/src/main/" in it }
            .filterNot { it.endsWith("/ClipboardWriteGate.kt") }
            .mapNotNull { path ->
                val text = code(File(root, path).readText())
                val writes = Regex("""\b(setPrimaryClip|clearPrimaryClip)\(""").findAll(text).count()
                "$path ($writes)".takeIf { writes > (allowed[path] ?: 0) }
            }
        assertTrue("write the clipboard through ClipboardWriteGate:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    // ---- sources ----------------------------------------------------------

    /**
     * A NUL typed as an escape in a tool's input lands as the raw byte. It
     * compiles and behaves, but grep then skips the file, git diffs it as
     * binary and `file` calls it data. Write the escape, never the byte.
     */
    @Test
    fun `no Kotlin source holds a raw NUL`() {
        val nul = Char(0)
        val offenders = trackedFiles { it.endsWith(".kt") || it.endsWith(".kts") }
            .filter { nul in File(root, it).readText() }
        assertTrue("raw NUL bytes in $offenders", offenders.isEmpty())
    }

    /**
     * `:tools:dictc` compiles a named handful of `core/prediction` files on
     * a plain JVM. Renaming one of them, or giving it an import from outside
     * that handful, breaks the dictionary compiler, and the rename does it
     * silently: a missing file is simply not included.
     */
    @Test
    fun `the dictionary compiler's shared sources exist and stand alone`() {
        val build = File(root, "tools/dictc/build.gradle.kts").readText()
        val dir = Regex("""srcDir\(rootProject\.file\("([^"]+)"\)\)""").find(build)!!.groupValues[1]
        val included = Regex("""include\(([^)]*)\)""", RegexOption.DOT_MATCHES_ALL).find(build)!!
            .groupValues[1].let { list -> Regex("\"([^\"]+)\"").findAll(list).map { it.groupValues[1] }.toList() }
        assertTrue("no files named in tools/dictc/build.gradle.kts", included.isNotEmpty())
        // Each named file is either shared from core/prediction or the tool's own.
        val own = File(root, "tools/dictc/src/main/kotlin")
        val paths = included.map { name ->
            when {
                File(root, "$dir/$name").isFile -> "$dir/$name"
                File(own, name).isFile -> File(own, name).relativeTo(root).invariantSeparatorsPath
                else -> null
            }
        }
        val missing = included.filterIndexed { i, _ -> paths[i] == null }
        assertTrue("dictc names files that do not exist: $missing", missing.isEmpty())

        val scope = Konsist.scopeFromFiles(paths.filterNotNull())
        val shared = scope.files.flatMap { f -> f.classes().map { it.fullyQualifiedName } + f.objects().map { it.fullyQualifiedName } }
            .filterNotNull().toSet()
        val problems = scope.files.flatMap { file ->
            file.imports.map { it.name }.filter { name ->
                name.startsWith("android.") || name.startsWith("androidx.") ||
                    (name.startsWith("com.wasimaster.") && name !in shared && name.substringBeforeLast('.') !in shared)
            }.map { "${file.name}.kt imports $it" }
        }
        assertTrue("dictc's sources must build on a plain JVM:\n${problems.joinToString("\n")}", problems.isEmpty())
    }

    // ---- naming -----------------------------------------------------------

    /** `composerFor` and the registry find composers by reading; the suffix is how. */
    @Test
    fun `composers are named as composers`() {
        val scope = sources("core/input/src/main")
        val composers = scope.classes().filter { c -> c.parents().any { it.name == "Composer" } }.map { it.name } +
            scope.objects().filter { o -> o.parents().any { it.name == "Composer" } }.map { it.name }
        assertTrue("no Composer implementations found; has the interface moved?", composers.isNotEmpty())
        val misnamed = composers.filterNot { it.endsWith("Composer") }
        assertTrue("Composer implementations not named *Composer: $misnamed", misnamed.isEmpty())
    }

    /** Every trace section shares the prefix scripts/perfetto.sh and the docs query on. */
    @Test
    fun `trace sections are prefixed and unique`() {
        val file = Konsist.scopeFromFile("feature/ime/src/main/java/com/wasimaster/wmkeyboard/ime/ImeTrace.kt").files.single()
        val values = file.objects().single { it.name == "ImeTrace" }.properties()
            .map { it.text.substringAfter('"').substringBefore('"') }
        assertTrue("ImeTrace declares no sections", values.isNotEmpty())
        assertEquals("duplicate section names", values.size, values.toSet().size)
        assertTrue("sections not prefixed WM: $values", values.all { it.startsWith("WM:") })
    }

    // ---- helpers ----------------------------------------------------------

    private fun sources(dir: String): KoScope =
        Konsist.scopeFromDirectory(dir).slice { "/build/" !in it.path && "/src/test" !in it.path && "/src/androidTest" !in it.path }

    /** The repo's tracked paths, relative to its root, that [keep] accepts. */
    private fun trackedFiles(keep: (String) -> Boolean): List<String> =
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIPPED_DIRS }
            .filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter(keep)
            .toList()

    /** [text] with comments removed, so a rule about code does not trip on prose about it. */
    private fun code(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    private companion object {
        val TEST_SOURCE = Regex("""/src/test[A-Za-z]*/java/""")

        /** Sources every flavour compiles: main, and :app's store-channel directories. */
        val MAIN_SOURCE = Regex("""/src/(main|play|noplay|github|fdroid|gms|nogms)/java/""")

        /** Build output, VCS data, node packages and the session worktrees under .claude. */
        val SKIPPED_DIRS = setOf("build", ".git", ".gradle", ".claude", "node_modules", ".idea", "target")

        /** The directory holding settings.gradle.kts, walking up from where the test runs. */
        fun projectRoot(): String {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
            return requireNotNull(dir) { "no settings.gradle.kts above ${File("").absolutePath}" }.path
        }
    }
}
