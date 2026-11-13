package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import java.io.InputStream
import java.io.StringReader
import java.text.Normalizer
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Reading a CLDR keyboard3 layout: the Unicode keyboard XML of LDML Part 7
 * (UTS #35, "Keyboards", CLDR 45 and later), whose root element is
 * `<keyboard3>`.
 *
 * Two places write these. CLDR keeps its own keyboards under `keyboards/3.0/`,
 * and FlorisBoard's `main` dropped its JSON layouts for this format (its
 * `ime.extension.keyboard3` extensions carry the files under `keyboard/`), so
 * the FlorisBoard JSON reader in [ForeignLayouts] only ever sees old files now.
 * FlorisBoard adds a few names of its own, which are read too: `@k3:action/…`
 * outputs for backspace, delete and enter, `@fl:action/…` for its other keys,
 * `@fl:icon/…` displays (ignored: the keys draw this keyboard's icons), and
 * `flex://` imports, resolved inside the same extension.
 *
 * **Written from the published specification and the data files**, not from
 * either project's code. CLDR's data is under the Unicode licence; the only
 * part of it carried here is the table of implied key ids below (`a` types a,
 * `comma` types a comma), which is a fact about the format. FlorisBoard is
 * Apache-2.0 and nothing of it is copied.
 *
 * ### What comes across
 *
 * - **Keys**: `output` (with `\u{…}` escapes and `${…}` string variables),
 *   `width`, `gap`, `stretch` (the key takes what the row leaves),
 *   `longPressKeyIds` with `longPressDefaultKeyId` first, `multiTapKeyIds` as
 *   this keyboard's multi-tap, and `flickId`.
 * - **Flicks**: a segment with one direction becomes that flick arm. A segment
 *   with several directions is a path ("north-east, then south"); arms here go
 *   one way, so those are counted and named in the notes.
 * - **Layers**: the touch layers when the file has any, the set meant for the
 *   narrowest device when it has several. `base` is the letters; a layer that
 *   shifts it (named `shift`, or holding the capitals of the base in the same
 *   places) folds into each key's shift letter; the first layer the base
 *   reaches becomes the symbols page and the first one that reaches becomes
 *   the second symbols page; any other layer the keys reach is a page of the
 *   layout, opened by the same key. A file with only hardware layers (`iso`,
 *   `us`, `jis`, `abnt2`, `ks`) is a computer keyboard, and is folded the way
 *   [DesktopLayouts] folds a `.klc`: shift, AltGr and its shift become the
 *   four levels there. Caps, Ctrl and other modifier layers are counted.
 * - **Displays**: a `display` by key id or by output is the key's label.
 * - **Imports**: CLDR's implied keys and its punctuation and currency imports
 *   are built in. Another file is read when it came with this one (inside a
 *   FlorisBoard extension); otherwise it is named in the notes.
 *
 * ### Transforms
 *
 * keyboard3 rewrites the text before the caret after each key ("simple"
 * transforms) and on backspace ("backspace" transforms). This keyboard runs no
 * per-layout rewrite rules, so each simple rule is mapped onto what it does
 * have, and counted under what it became:
 *
 * - **A dead key**, when the rules a key starts all put one combining mark on
 *   the next letter (French `\m{grave}` then `e` makes è). The key types that
 *   mark, which is exactly this keyboard's dead key (see `DeadKeys` in
 *   `:core:input`), and Unicode composes the rest.
 * - **Press and hold**, for the rest of a key's rules: the characters they
 *   make are on press and hold of the key that starts them. A key whose output
 *   is only a marker (Bengali's "more" key) has nothing to type of its own, so
 *   it types the first of them. On a computer layout the characters go on
 *   press and hold of the letter they are built on instead, as a `.klc`'s dead
 *   keys do.
 * - **Left out**: rules with no key that types their start, rules using
 *   pattern features beyond literal text, sets, classes and `?`, every
 *   `reorder` rule (this keyboard writes marks in the order they are typed,
 *   and its Indic composer orders the common cases itself) and every backspace
 *   rule. Each kind is counted in the notes. A rule that only drops markers
 *   left unused is satisfied as it is: markers never reach the text here.
 *
 * ### XML
 *
 * The platform SAX parser, with no DTD. CLDR's files open with a
 * `<!DOCTYPE keyboard3 SYSTEM "…">` line, so a DOCTYPE with no internal subset
 * is cut out before parsing (it can declare nothing); one with an internal
 * subset, the only kind that can declare entities, is refused. Android's SAX
 * parser cannot be told to refuse a DOCTYPE itself (see `SvgParser`).
 */
object Keyboard3Layouts {

    /** Far above a real layout: CLDR's largest is under 20 KB. */
    const val MAX_LENGTH = 2 * 1024 * 1024

    /** The `$` tag of a FlorisBoard extension that holds keyboard3 layouts. */
    const val EXTENSION_FORMAT = "ime.extension.keyboard3"

    /** True when [text] has a `<keyboard3>` element. Cheap, and parses nothing. */
    fun looksLikeKeyboard3(text: String): Boolean =
        text.length <= MAX_LENGTH && RootOpen.containsMatchIn(text)

    /** Whether a FlorisBoard `extension.json` is a keyboard3 extension's. */
    fun isExtensionManifest(manifest: String): Boolean = manifest.contains("\"$EXTENSION_FORMAT\"")

    /**
     * Converts [text]. Null when it is not a keyboard3 file or holds no keys.
     * Never throws.
     *
     * [siblings] are other files that arrived with this one, by path, for its
     * `<import>`s to be read from: the rest of a FlorisBoard extension.
     */
    fun convert(text: String, name: String, siblings: Map<String, String> = emptyMap()): ConvertedLayout? =
        runCatching {
            val root = parseXml(text)?.takeIf { it.name == ROOT } ?: return null
            Builder(root, name, siblings).build()
        }.getOrNull()

    /**
     * Every keyboard3 layout in a FlorisBoard keyboard extension (a `.flex`
     * ZIP), each with the extension's other files to import from. Empty when
     * there are none. Never throws.
     */
    fun convertArchive(input: InputStream): List<ConvertedLayout> = runCatching {
        val files = LinkedHashMap<String, String>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            var scanned = 0
            while (scanned++ < MAX_ARCHIVE_ENTRIES) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !entry.name.endsWith(".xml", ignoreCase = true)) continue
                val bytes = zip.readCapped(MAX_LENGTH) ?: continue
                total += bytes.size
                if (total > MAX_ARCHIVE_BYTES) break
                files[entry.name] = bytes.decodeToString().removePrefix("\uFEFF")
            }
        }
        files.filter { (_, text) -> looksLikeKeyboard3(text) }
            .mapNotNull { (path, text) -> convert(text, path.substringAfterLast('/'), files) }
    }.getOrDefault(emptyList())

    private fun InputStream.readCapped(max: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) return null
        }
        return out.toByteArray()
    }

    // ---- the XML ----

    /** One element: its name, attributes and child elements. Text is never read. */
    internal class Node(val name: String, val attrs: Map<String, String>) {
        val children = ArrayList<Node>()
        operator fun get(attr: String): String? = attrs[attr]
        fun all(child: String): List<Node> = children.filter { it.name == child }
        fun first(child: String): Node? = children.firstOrNull { it.name == child }
    }

    private class TreeHandler : DefaultHandler() {
        var root: Node? = null
        private val stack = ArrayDeque<Node>()
        private var count = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = (qName?.ifEmpty { null } ?: localName ?: return).substringAfter(':')
            check(++count <= MAX_NODES && stack.size < MAX_DEPTH) { "too large" }
            val attrs = LinkedHashMap<String, String>()
            for (i in 0 until attributes.length) {
                attrs[attributes.getQName(i).substringAfter(':')] = attributes.getValue(i)
            }
            val node = Node(name, attrs)
            stack.lastOrNull()?.children?.add(node) ?: run { root = node }
            stack.addLast(node)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            stack.removeLastOrNull()
        }
    }

    internal fun parseXml(text: String): Node? {
        if (text.length > MAX_LENGTH) return null
        val clean = withoutDoctype(text.removePrefix("\uFEFF")) ?: return null
        val handler = TreeHandler()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            quietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            quietly("http://xml.org/sax/features/external-general-entities", false)
            quietly("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching { factory.newSAXParser().parse(InputSource(StringReader(clean)), handler) }
            .onFailure { return null }
        return handler.root
    }

    private fun SAXParserFactory.quietly(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }

    /**
     * [text] with its DOCTYPE cut out, or null when that cannot be done
     * safely: an internal subset (`[ … ]`) is where entities are declared, and
     * a second DOCTYPE is not a file anyone wrote by hand.
     */
    private fun withoutDoctype(text: String): String? {
        val start = Doctype.find(text)?.range?.first ?: return text
        val end = text.indexOf('>', start)
        if (end < 0) return null
        if (text.substring(start, end).contains('[')) return null
        val rest = text.removeRange(start, end + 1)
        return rest.takeIf { Doctype.find(it) == null }
    }

    // ---- reading the file ----

    private class K3Key(
        val id: String,
        val output: String?,
        val gap: Boolean,
        val width: Float,
        val stretch: Boolean,
        val layerId: String?,
        val longPressKeyIds: List<String>,
        val longPressDefault: String?,
        val multiTapKeyIds: List<String>,
        val flickId: String?,
    )

    private class K3Layer(val id: String?, val modifiers: String?, val rows: List<List<String>>)

    private class K3LayerSet(val formId: String, val minDeviceWidth: Int, val layers: List<K3Layer>)

    /** What a key's transforms became. */
    private sealed interface Plan {
        /** Everything the rules make of each following [compositions] key. */
        val compositions: Map<String, String>

        /** The key types [mark], this keyboard's dead key; [hold] is what the mark does not explain. */
        class Dead(val mark: Char, val hold: List<String>, override val compositions: Map<String, String>) : Plan

        /** The rules' results go on press and hold. */
        class Hold(val results: List<String>, override val compositions: Map<String, String>) : Plan
    }

    private class Builder(
        private val root: Node,
        private val fileName: String,
        private val siblings: Map<String, String>,
    ) {
        private val report = Report()
        private val strings = HashMap<String, String>()
        private val sets = HashMap<String, List<String>>()
        private val keys = LinkedHashMap<String, K3Key>().apply { putAll(ImpliedKeys) }
        private val flicks = HashMap<String, List<Pair<List<String>, String>>>()
        private val displayByKey = HashMap<String, String>()
        private val displayByOutput = HashMap<String, String>()
        private val markers = LinkedHashMap<String, String>()
        private var baseCharacter = DOTTED_CIRCLE

        fun build(): ConvertedLayout? {
            resolveImports(root, depth = 0)
            root.first("variables")?.let(::readVariables)
            root.all("keys").forEach(::readKeys)
            root.all("flicks").forEach(::readFlicks)
            root.all("displays").forEach(::readDisplays)
            val sets = root.all("layers").map(::readLayerSet).filter { it.layers.isNotEmpty() }
            val touch = pickTouch(sets.filter { it.formId == TOUCH })
            val hardware = sets.firstOrNull { it.formId != TOUCH }
            val chosen = touch ?: hardware ?: return null
            val onGrid = chosen.layers.flatMap { it.rows.flatten() }.mapNotNull { keys[it] }
            val outputs = onGrid.mapNotNull { it.output }.filter { it.isNotEmpty() && special(it) == null }.toSet()
            val plans = planTransforms(outputs, typed = if (touch == null) outputs.map(::visible).toSet() else null)
            val title = root.first("info")?.get("name")?.trim()?.ifEmpty { null }
            val tag = root["locale"]?.let(::languagePart)
            val converted = if (touch != null) {
                buildTouch(touch.layers, plans, tag)
            } else {
                buildHardware(chosen, plans, title, tag)
            } ?: return null
            return if (title == null) converted else converted.copy(layout = converted.layout.copy(name = title))
        }

        // ---- imports ----

        /**
         * Puts each `<import>`ed file's elements where the import stood, the
         * way the format reads them: imports come first, and anything the file
         * declares after them with the same id wins.
         */
        private fun resolveImports(node: Node, depth: Int) {
            if (depth > MAX_IMPORT_DEPTH) return
            var i = 0
            while (i < node.children.size) {
                val child = node.children[i]
                if (child.name != "import") {
                    resolveImports(child, depth)
                    i++
                    continue
                }
                node.children.removeAt(i)
                val imported = importedRoot(child, parent = node.name)
                if (imported != null) {
                    resolveImports(imported, depth + 1)
                    node.children.addAll(i, imported.children)
                    i += imported.children.size
                }
            }
        }

        /** The root of the file [ref] names, or null when it is built in or missing. */
        private fun importedRoot(ref: Node, parent: String): Node? {
            val path = ref["path"]?.trim().orEmpty()
            if (path.isEmpty()) return null
            if (ref["base"] == "cldr") {
                // The implied keys and these two imports are built in, as
                // [ImpliedKeys]; any other CLDR file is not.
                if (path.substringAfterLast('/') !in BuiltInCldrImports) report.missingIncludes += path
                return null
            }
            // FlorisBoard's foundation extension: its implied keys and
            // displays are this keyboard's own, and its layers are the number
            // and phone pads this keyboard draws for itself.
            if (path.startsWith(FLORIS_FOUNDATION)) return null
            val text = siblingText(path)
            val imported = text?.let(::parseXml)
            if (imported == null || imported.name != parent) {
                report.missingIncludes += path
                return null
            }
            return imported
        }

        private fun siblingText(path: String): String? {
            if (siblings.isEmpty()) return null
            val rest = path.removePrefix("flex://").let { if (path.startsWith("flex://")) it.substringAfter('/') else it }
            val leaf = rest.substringAfterLast('/')
            return siblings[rest]
                ?: siblings.entries.firstOrNull { it.key.endsWith("/$rest") }?.value
                ?: siblings.entries.firstOrNull { it.key.substringAfterLast('/') == leaf }?.value
        }

        // ---- variables, keys, flicks, displays ----

        private fun readVariables(node: Node) {
            for (child in node.children) {
                val id = child["id"] ?: continue
                val value = child["value"] ?: continue
                when (child.name) {
                    "string" -> strings[id] = decode(value)
                    "set" -> sets[id] = value.trim().split(Whitespace).filter { it.isNotEmpty() }.flatMap { item ->
                        SetRef.matchEntire(item)?.let { sets[it.groupValues[1]] } ?: listOf(decode(item))
                    }
                    "uset" -> sets[id] = charClass(value.trim().removeSurrounding("[", "]")).orEmpty()
                }
            }
        }

        private fun readKeys(node: Node) {
            for (k in node.all("key")) {
                val id = k["id"] ?: continue
                val output = (k["output"] ?: k["to"])?.let(::decode)?.let(::nfc)
                keys[id] = K3Key(
                    id = id,
                    output = output,
                    gap = k["gap"] == "true",
                    width = k["width"]?.toFloatOrNull()?.takeIf { it > 0f && it.isFinite() }?.coerceAtMost(MaxKeyWidth) ?: 1f,
                    stretch = k["stretch"] == "true",
                    layerId = k["layerId"]?.trim()?.ifEmpty { null },
                    longPressKeyIds = k["longPressKeyIds"].ids(),
                    longPressDefault = k["longPressDefaultKeyId"]?.trim()?.ifEmpty { null },
                    multiTapKeyIds = k["multiTapKeyIds"].ids(),
                    flickId = k["flickId"]?.trim()?.ifEmpty { null },
                )
            }
        }

        private fun readFlicks(node: Node) {
            for (flick in node.all("flick")) {
                val id = flick["id"] ?: continue
                flicks[id] = flick.all("flickSegment").mapNotNull { seg ->
                    val keyId = seg["keyId"] ?: return@mapNotNull null
                    seg["directions"].ids() to keyId
                }
            }
        }

        private fun readDisplays(node: Node) {
            for (d in node.children) {
                if (d.name == "displayOptions") {
                    d["baseCharacter"]?.let(::decode)?.takeIf { it.isNotEmpty() }?.let { baseCharacter = it }
                    continue
                }
                if (d.name != "display") continue
                // FlorisBoard's icon references: the keys draw this keyboard's icons.
                val shown = d["display"]?.takeIf { !FlorisReference.matches(it) }?.let(::decode)
                    ?.let(::visible)?.replace('\n', ' ')?.trim()?.ifEmpty { null } ?: continue
                d["keyId"]?.let { displayByKey[it] = shown }
                d["output"]?.let { displayByOutput[nfc(decode(it))] = shown }
            }
        }

        private fun readLayerSet(node: Node): K3LayerSet = K3LayerSet(
            formId = node["formId"]?.trim().orEmpty().ifEmpty { TOUCH },
            minDeviceWidth = node["minDeviceWidth"]?.trim()?.toIntOrNull() ?: 0,
            layers = node.all("layer").map { layer ->
                K3Layer(
                    id = layer["id"]?.trim()?.ifEmpty { null },
                    modifiers = layer["modifiers"]?.trim()?.ifEmpty { null },
                    rows = layer.all("row").map { it["keys"].ids() }.filter { it.isNotEmpty() },
                )
            }.filter { it.rows.isNotEmpty() },
        )

        /**
         * The touch layers for a phone: the set for the widest device a phone
         * still is, or the narrowest set when every one asks for more.
         */
        private fun pickTouch(sets: List<K3LayerSet>): K3LayerSet? =
            sets.filter { it.minDeviceWidth <= PHONE_WIDTH_MM }.maxByOrNull { it.minDeviceWidth }
                ?: sets.minByOrNull { it.minDeviceWidth }

        // ---- escapes ----

        /** `\u{…}`, `\m{…}` and `${…}` resolved; everything else as written. */
        fun decode(raw: String): String {
            if (!raw.contains('\\') && !raw.contains('$')) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                if (raw.startsWith("\\u{", i) || raw.startsWith("\\m{", i) || raw.startsWith("\${", i)) {
                    val end = raw.indexOf('}', i)
                    if (end > 0) {
                        val body = raw.substring(i + if (raw[i] == '$') 2 else 3, end)
                        when (raw[i + 1]) {
                            'u' -> codePoints(body)?.let { out.append(it) }
                            'm' -> out.append(marker(body))
                            else -> strings[body]?.let { out.append(it) }
                        }
                        i = end + 1
                        continue
                    }
                }
                out.append(raw[i])
                i++
            }
            return out.toString()
        }

        private fun codePoints(hex: String): String? {
            val out = StringBuilder()
            for (part in hex.trim().split(Whitespace)) {
                val cp = part.toIntOrNull(HEX)?.takeIf { Character.isValidCodePoint(it) && it !in SURROGATES } ?: return null
                out.appendCodePoint(cp)
            }
            return out.toString()
        }

        /** A marker as a private-use character of its own, so it can sit inside text. */
        private fun marker(name: String): String =
            markers.getOrPut(name) { String(Character.toChars(MARKER_BASE + markers.size.coerceAtMost(MAX_MARKERS))) }

        // ---- transforms ----

        /**
         * What the simple transforms become, by the output of the key that
         * starts each one. Counts every rule as dead key, press and hold, or
         * left out, and the reorder and backspace rules as left out.
         *
         * [typed] is null on a touch layout, where every rule's result can go
         * on press and hold of the key that starts it. On a computer layout it
         * is what the grid's keys type: a result lands on press and hold of the
         * letter it is built on (see [DesktopLayouts]), so a rule whose second
         * key is not a letter key reaches nothing and is left out. The rules that do
         * land are described by that reader's own dead-key note there.
         */
        private fun planTransforms(outputs: Set<String>, typed: Set<String>?): Map<String, Plan> {
            class Entry(val prefix: String, val rest: String, val result: String)
            class Rule(val entries: List<Entry>, val partial: Boolean)

            val byPrefix = LinkedHashMap<String, LinkedHashMap<String, String>>()
            val rules = mutableListOf<Rule>()
            for (transforms in root.all("transforms")) {
                val backspace = transforms["type"] == "backspace"
                for (group in transforms.all("transformGroup")) {
                    report.reorderDropped += group.all("reorder").size
                    for (t in group.all("transform")) {
                        if (backspace) {
                            report.backspaceDropped++
                            continue
                        }
                        val from = t["from"] ?: continue
                        val to = t["to"].orEmpty()
                        if (isMarkerCleanup(from, to)) continue
                        val expansions = Pattern(from).expand()
                        if (expansions == null) {
                            report.transformsDropped++
                            continue
                        }
                        var partial = false
                        val entries = mutableListOf<Entry>()
                        for (e in expansions) {
                            val result = substitute(to, e)?.let(::nfc)
                            val text = nfc(e.text)
                            val prefix = outputs.filter { text.length > it.length && text.startsWith(it) }.maxByOrNull { it.length }
                            if (result == null || prefix == null || hasMarker(result)) {
                                partial = true
                                continue
                            }
                            val rest = text.substring(prefix.length)
                            byPrefix.getOrPut(prefix) { LinkedHashMap() }.putIfAbsent(rest, result)
                            entries += Entry(prefix, rest, result)
                        }
                        rules += Rule(entries, partial)
                    }
                }
            }

            val plans = byPrefix.mapValues { (prefix, comps) -> planFor(prefix, comps) }
            fun dead(e: Entry) = (plans[e.prefix] as? Plan.Dead)?.explains(e.prefix, e.rest, e.result) == true
            for (rule in rules) {
                when {
                    rule.partial || rule.entries.isEmpty() -> report.transformsDropped++
                    typed != null -> if (!rule.entries.all { dead(it) || (it.rest in typed && it.rest.any(Char::isLetter)) }) {
                        report.transformsDropped++
                    }
                    rule.entries.all(::dead) -> report.transformsAsDeadKeys++
                    else -> report.transformsOnHold++
                }
            }
            return plans
        }

        private fun planFor(prefix: String, comps: Map<String, String>): Plan {
            val singles = comps.filterKeys { it.codePointCount(0, it.length) == 1 && !hasMarker(it) }
            val mark = markOf(singles)
            if (mark != null) {
                val dead = Plan.Dead(mark, emptyList(), comps)
                val hold = comps.filter { (rest, result) -> !dead.explains(prefix, rest, result) }
                    .values.map(::visible).filter { it.isNotEmpty() }.distinct()
                return Plan.Dead(mark, hold.take(MAX_HOLD), comps)
            }
            val results = comps.values.map(::visible).filter { it.isNotEmpty() }.distinct()
            return Plan.Hold(results.take(MAX_HOLD), comps)
        }

        /**
         * Whether the dead key's mark makes [result] of [rest] the way the
         * rule does. A letter Unicode has no single character for still counts
         * when the rule writes the plain letter and the mark: the dead key
         * here writes the spacing accent before it instead, which is the
         * nearest it comes. Accent then space giving the accent is the dead
         * key's own standalone form.
         */
        private fun Plan.Dead.explains(prefix: String, rest: String, result: String): Boolean {
            if (rest == " " && result == visible(prefix)) return true
            if (rest.codePointCount(0, rest.length) != 1 || hasMarker(rest)) return false
            val composed = nfc(rest + mark)
            return composed == result || nfc(result) == composed
        }

        /** `\m{.}(.)` → `$1`, or markers deleted outright: the format's own cleanup. */
        private fun isMarkerCleanup(from: String, to: String): Boolean {
            val f = from.trim()
            if (to.isEmpty() && MarkersOnly.matches(f)) return true
            return AnyMarkerThenChar.matches(f) && to.trim() in setOf("$1", "")
        }

        private fun substitute(to: String, e: Expansion): String? {
            val out = StringBuilder()
            var i = 0
            while (i < to.length) {
                val c = to[i]
                when {
                    to.startsWith("$$", i) || to.startsWith("\\$", i) -> {
                        out.append('$')
                        i += 2
                    }
                    to.startsWith("$[", i) -> {
                        val end = to.indexOf(']', i).takeIf { it > 0 } ?: return null
                        val ref = to.substring(i + 2, end)
                        val n = ref.substringBefore(':').toIntOrNull() ?: return null
                        val set = sets[ref.substringAfter(':', "")] ?: return null
                        val index = e.groups[n]?.setIndex ?: return null
                        out.append(set.getOrNull(index) ?: return null)
                        i = end + 1
                    }
                    to.startsWith("\${", i) -> {
                        val end = to.indexOf('}', i).takeIf { it > 0 } ?: return null
                        out.append(strings[to.substring(i + 2, end)] ?: return null)
                        i = end + 1
                    }
                    c == '$' && i + 1 < to.length && to[i + 1].isDigit() -> {
                        val n = to[i + 1] - '0'
                        out.append(if (n == 0) e.text else e.groups[n]?.text ?: return null)
                        i += 2
                    }
                    to.startsWith("\\u{", i) || to.startsWith("\\m{", i) -> {
                        val end = to.indexOf('}', i).takeIf { it > 0 } ?: return null
                        out.append(decode(to.substring(i, end + 1)))
                        i = end + 1
                    }
                    c == '\\' && i + 1 < to.length -> {
                        out.append(to[i + 1])
                        i += 2
                    }
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return out.toString()
        }

        /** One way a pattern can match: its text, and what each capture took. */
        class Expansion(val text: String, val groups: Map<Int, Capture>)

        /** A capture's text, and its place in the set it was, when it was exactly one set. */
        class Capture(val text: String, val setIndex: Int?)

        /**
         * A transform's `from`, written out as every literal it matches.
         *
         * The subset a phone needs: literal text and escapes, `$[set]`,
         * `${string}`, character classes, groups (capturing or not),
         * alternation and `?`. A pattern with `.`, `*`, `+`, `{…}` or an anchor
         * matches text no list can hold, and comes back null.
         */
        private inner class Pattern(private val src: String) {
            private var pos = 0
            private var groupCount = 0

            private inner class Part(val text: String, val groups: Map<Int, Capture>, val set: Int?)

            fun expand(): List<Expansion>? {
                val parts = alternatives(stopAtParen = false) ?: return null
                if (pos != src.length) return null
                return parts.map { Expansion(it.text, it.groups) }
            }

            private fun alternatives(stopAtParen: Boolean): List<Part>? {
                val result = mutableListOf<Part>()
                var current = listOf(Part("", emptyMap(), null))
                var atoms = 0
                while (pos < src.length) {
                    val c = src[pos]
                    if (c == ')') {
                        if (!stopAtParen) return null
                        break
                    }
                    if (c == '|') {
                        result += current
                        current = listOf(Part("", emptyMap(), null))
                        atoms = 0
                        pos++
                        continue
                    }
                    var atom = atom() ?: return null
                    if (pos < src.length && src[pos] == '?') {
                        pos++
                        atom = listOf(Part("", emptyMap(), null)) + atom
                    }
                    if (pos < src.length && src[pos] in "*+{") return null
                    atoms++
                    current = current.flatMap { a ->
                        atom.map { b ->
                            Part(a.text + b.text, a.groups + b.groups, if (atoms == 1) b.set else null)
                        }
                    }
                    if (current.size > MAX_EXPANSIONS) return null
                }
                result += current
                return result.takeIf { it.size <= MAX_EXPANSIONS }
            }

            private fun atom(): List<Part>? {
                val c = src[pos]
                return when {
                    c == '(' -> group()
                    c == '[' -> {
                        val end = classEnd(pos) ?: return null
                        val items = charClass(src.substring(pos + 1, end)) ?: return null
                        pos = end + 1
                        items.map { Part(it, emptyMap(), null) }
                    }
                    src.startsWith("$[", pos) -> {
                        val end = src.indexOf(']', pos).takeIf { it > 0 } ?: return null
                        val items = sets[src.substring(pos + 2, end)] ?: return null
                        pos = end + 1
                        items.mapIndexed { i, item -> Part(item, emptyMap(), i) }
                    }
                    src.startsWith("\${", pos) || src.startsWith("\\u{", pos) || src.startsWith("\\m{", pos) -> {
                        val end = src.indexOf('}', pos).takeIf { it > 0 } ?: return null
                        val body = src.substring(pos, end + 1)
                        if (body == "\\m{.}") return null
                        pos = end + 1
                        listOf(Part(decode(body), emptyMap(), null))
                    }
                    c == '\\' && pos + 1 < src.length -> {
                        val cp = src.codePointAt(pos + 1)
                        pos += 1 + Character.charCount(cp)
                        listOf(Part(String(Character.toChars(cp)), emptyMap(), null))
                    }
                    c == '.' || c == '^' || c == '$' || c in "*+{?" -> null
                    else -> {
                        val cp = src.codePointAt(pos)
                        pos += Character.charCount(cp)
                        listOf(Part(String(Character.toChars(cp)), emptyMap(), null))
                    }
                }
            }

            private fun group(): List<Part>? {
                val capturing = !src.startsWith("(?:", pos)
                if (!capturing) {
                    pos += 3
                } else if (src.startsWith("(?", pos)) {
                    return null
                } else {
                    pos++
                }
                val index = if (capturing) ++groupCount else 0
                val inner = alternatives(stopAtParen = true) ?: return null
                if (pos >= src.length || src[pos] != ')') return null
                pos++
                if (!capturing) return inner.map { Part(it.text, it.groups, null) }
                return inner.map { Part(it.text, it.groups + (index to Capture(it.text, it.set)), null) }
            }

            private fun classEnd(open: Int): Int? {
                var i = open + 1
                while (i < src.length) {
                    when (src[i]) {
                        '\\' -> i += 2
                        ']' -> return i
                        else -> i++
                    }
                }
                return null
            }
        }

        /**
         * The single characters a class body (`a-z \u{300}-\u{36F} $[uset]`)
         * lists, or null for a negated class or one too large to list.
         */
        fun charClass(body: String): List<String>? {
            if (body.startsWith("^")) return null
            val points = mutableListOf<Int>()
            var i = 0
            fun next(): Int? {
                if (body.startsWith("\\u{", i)) {
                    val end = body.indexOf('}', i).takeIf { it > 0 } ?: return null
                    val cps = codePoints(body.substring(i + 3, end)) ?: return null
                    i = end + 1
                    return cps.codePointAt(0).takeIf { cps.codePointCount(0, cps.length) == 1 }
                }
                if (body[i] == '\\' && i + 1 < body.length) i++
                val cp = body.codePointAt(i)
                i += Character.charCount(cp)
                return cp
            }
            while (i < body.length) {
                if (body[i].isWhitespace()) {
                    i++
                    continue
                }
                if (body.startsWith("$[", i)) {
                    val end = body.indexOf(']', i).takeIf { it > 0 } ?: return null
                    val items = sets[body.substring(i + 2, end)] ?: return null
                    items.forEach { item -> item.codePoints().forEach { points.add(it) } }
                    i = end + 1
                    continue
                }
                val from = next() ?: return null
                if (i < body.length && body[i] == '-' && i + 1 < body.length) {
                    i++
                    val to = next() ?: return null
                    if (to < from || to - from > MAX_CLASS) return null
                    (from..to).forEach(points::add)
                } else {
                    points += from
                }
                if (points.size > MAX_CLASS) return null
            }
            return points.distinct().map { String(Character.toChars(it)) }
        }

        // ---- keys to keys ----

        private sealed interface Slot {
            data object Letters : Slot
            data object Shift : Slot
            data object Symbols : Slot
            data class Page(val key: String) : Slot
        }

        /**
         * A key of this keyboard for [k], or null when it has none here.
         * [slots] says where each layer went.
         */
        @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
        private fun keyFor(k: K3Key, slots: Map<String, Slot>, plans: Map<String, Plan>): Key? {
            val width = k.width
            // FlorisBoard's slot for its own function key (emoji or language),
            // which this keyboard's bottom row has already.
            if (k.id == FLORIS_FN_KEY && k.output == null && k.layerId == null) return null
            if (k.gap) return Key(label = "", action = KeyAction.None, width = width)
            val shown = displayByKey[k.id] ?: k.output?.let { displayByOutput[it] }
            if (k.layerId != null) {
                val action = when (val slot = slots[k.layerId]) {
                    Slot.Letters -> KeyAction.Letters
                    Slot.Shift -> KeyAction.Shift
                    Slot.Symbols -> KeyAction.Symbols
                    is Slot.Page -> KeyAction.LayerSwitch(slot.key)
                    null -> {
                        report.droppedLabels += k.id
                        return null
                    }
                }
                val label = when (action) {
                    KeyAction.Shift -> action.fallbackLabel()
                    KeyAction.Letters -> shown ?: LETTERS_LABEL
                    KeyAction.Symbols -> shown ?: SYMBOLS_LABEL
                    else -> shown ?: k.id
                }
                return Key(label = label, action = action, width = width)
            }
            val raw = k.output ?: run {
                report.droppedLabels += k.id
                return null
            }
            special(raw)?.let { action ->
                if (action == DROPPED) {
                    report.droppedLabels += raw
                    return null
                }
                val label = if (action in IconActions) action.fallbackLabel() else shown ?: action.fallbackLabel()
                return Key(label = label, action = action, width = width)
            }

            val plan = plans[raw]
            val own = visible(raw)
            var output: String
            var extra: List<String> = emptyList()
            when (plan) {
                is Plan.Dead -> {
                    output = plan.mark.toString()
                    extra = plan.hold
                }
                is Plan.Hold -> if (own.isEmpty()) {
                    output = plan.results.firstOrNull() ?: return null
                    extra = plan.results.drop(1)
                } else {
                    output = own
                    extra = plan.results
                }
                null -> {
                    if (own.isEmpty()) {
                        report.droppedLabels += k.id
                        return null
                    }
                    output = own
                }
            }
            output = normalizeForScript(output)
            val label = normalizeForScript(
                shown ?: when {
                    plan is Plan.Dead -> own.ifEmpty { SpacingAccents[plan.mark] ?: "$baseCharacter${plan.mark}" }
                    isMark(output) -> baseCharacter + output
                    else -> output
                },
            )

            val pressAndHold = buildList {
                val ids = k.longPressDefault?.let { listOf(it) + (k.longPressKeyIds - it) } ?: k.longPressKeyIds
                ids.mapNotNull { textOf(it) }.forEach(::add)
                addAll(extra)
            }
            val base = Key(
                label = label,
                output = output.takeIf { it != label },
                width = width,
                multitap = k.multiTapKeyIds.mapNotNull { textOf(it) },
            )
            val withFlicks = flicksOn(base, k)
            val flickTexts = withFlicks.flick.values.toList()
            val hold = (pressAndHold.ifEmpty { flickTexts })
                .map(::normalizeForScript)
                .filter { it.isNotEmpty() && it != output }
                .distinct()
                .take(MAX_HOLD)
            return withFlicks.copy(longPress = hold, hideHint = flickTexts.isNotEmpty())
        }

        /** What the key [id] types, for a press-and-hold or multi-tap entry; null for anything else. */
        private fun textOf(id: String): String? {
            val k = keys[id] ?: return null
            if (k.gap || k.layerId != null) return null
            val raw = k.output ?: return null
            if (special(raw) != null) return null
            return visible(raw).ifEmpty { null }
        }

        /** [key] with the arms of the flick [k] names. */
        private fun flicksOn(key: Key, k: K3Key): Key {
            val segments = k.flickId?.let { flicks[it] } ?: return key
            val text = LinkedHashMap<FlickDirection, String>()
            val actions = LinkedHashMap<FlickDirection, KeyAlternate>()
            val tap = key.output ?: key.label
            for ((directions, target) in segments) {
                if (directions.size != 1) {
                    report.flickPathsDropped++
                    continue
                }
                val direction = Directions[directions.single()] ?: continue
                if (direction in text || direction in actions) continue
                val t = keys[target] ?: continue
                val typed = textOf(target)
                when {
                    typed != null && typed == tap -> Unit
                    typed != null -> text[direction] = normalizeForScript(typed)
                    t.layerId != null || t.output?.let(::special) != null -> {
                        val armKey = keyFor(t, currentSlots, emptyMap()) ?: continue
                        if (key.takesFlickActions()) {
                            actions[direction] = KeyAlternate(armKey.action, armKey.label)
                        } else {
                            report.gesturesDropped++
                        }
                    }
                }
            }
            return key.copy(flick = text, flickActions = actions)
        }

        /** Where each layer went, for the flick arms that switch layers. */
        private var currentSlots: Map<String, Slot> = emptyMap()

        private fun rowsOf(layer: K3Layer, slots: Map<String, Slot>, plans: Map<String, Plan>): List<List<Key>> {
            val built = layer.rows.map { ids ->
                ids.mapNotNull { id ->
                    val k = keys[id]
                    if (k == null) {
                        report.droppedLabels += id
                        return@mapNotNull null
                    }
                    keyFor(k, slots, plans)?.let { it to k.stretch }
                }
            }.filter { it.isNotEmpty() }
            // A stretched key takes what its row leaves of the widest row.
            val target = built.maxOfOrNull { row -> row.sumOf { it.first.width.toDouble() } } ?: 0.0
            return built.map { row ->
                val stretchers = row.count { it.second }
                if (stretchers == 0) return@map row.map { it.first }
                val fixed = row.filter { !it.second }.sumOf { it.first.width.toDouble() }
                val each = ((target - fixed) / stretchers).toFloat()
                row.map { (key, stretch) ->
                    if (stretch) key.copy(width = maxOf(key.width, each).coerceAtMost(MaxKeyWidth)) else key
                }
            }
        }

        // ---- touch ----

        private fun buildTouch(layers: List<K3Layer>, plans: Map<String, Plan>, tag: String?): ConvertedLayout? {
            val byId = layers.filter { it.id != null }.associateBy { it.id!! }
            val base = byId[BASE] ?: layers.firstOrNull { it.modifiers.isNone() } ?: layers.first()
            fun reached(layer: K3Layer): List<K3Layer> =
                layer.rows.flatten().mapNotNull { keys[it]?.layerId }.distinct()
                    .mapNotNull { byId[it] }.filter { it !== layer }

            val shift = pickShift(base, byId, reached(base))
            val slots = LinkedHashMap<String, Slot>()
            base.id?.let { slots[it] = Slot.Letters }
            shift?.id?.let { slots[it] = Slot.Shift }
            val pages = LinkedHashMap<String, K3Layer>()
            val symbols = reached(base).firstOrNull { it !== shift }
            val symbols2 = symbols?.let { s -> reached(s).firstOrNull { it !== base && it !== shift && it !== s } }
            symbols?.let { pages[LayoutLayer.SYMBOLS.key] = it }
            symbols2?.let { pages[LayoutLayer.SYMBOLS_SHIFTED.key] = it }
            // Every other layer the keys can reach, breadth first from the base.
            val queue = ArrayDeque(listOf(base))
            val seen = mutableSetOf<K3Layer>(base)
            while (queue.isNotEmpty()) {
                for (next in reached(queue.removeFirst())) {
                    if (!seen.add(next)) continue
                    queue.addLast(next)
                    if (next === shift || pages.values.any { it === next }) continue
                    pages[PAGE_PREFIX + next.id] = next
                }
            }
            for ((key, layer) in pages) {
                val id = layer.id ?: continue
                slots[id] = if (key.startsWith(PAGE_PREFIX)) Slot.Page(key) else Slot.Symbols
            }
            currentSlots = slots

            var letters = rowsOf(base, slots, plans)
            if (shift != null) letters = mergeShift(letters, rowsOf(shift, slots, plans))
            val extra = pages.mapValues { (_, layer) -> LayerSpec(rows = rowsOf(layer, slots, plans)) }
                .filterValues { it.rows.isNotEmpty() }
            if (extra.keys.any { it.startsWith(PAGE_PREFIX) }) report.pagesKept = true
            return ForeignLayouts.assemble(
                rows = letters,
                name = fileName,
                source = ForeignSource.KEYBOARD3,
                report = report,
                langId = DesktopLayouts.declaredLanguage(tag),
                extraLayers = extra,
            )
        }

        /**
         * The layer that shifts [base]: one named `shift`, else one the base
         * reaches whose text keys are mostly the base's capitals in the same
         * places.
         */
        private fun pickShift(base: K3Layer, byId: Map<String, K3Layer>, reached: List<K3Layer>): K3Layer? {
            byId[SHIFT]?.takeIf { it !== base }?.let { return it }
            val baseTexts = base.rows.map { row -> row.mapNotNull { textOf(it) } }
            return reached.firstOrNull { layer ->
                val texts = layer.rows.map { row -> row.mapNotNull { textOf(it) } }
                var pairs = 0
                var capitals = 0
                baseTexts.zip(texts).forEach { (b, s) ->
                    if (b.size != s.size) return@forEach
                    b.zip(s).forEach { (lower, upper) ->
                        pairs++
                        if (upper != lower && upper == lower.uppercase()) capitals++
                    }
                }
                pairs > 0 && capitals >= pairs * SHIFT_SHARE
            }
        }

        /**
         * The shift layer folded into the letters: each text key takes the
         * text key in the same place of the same row as its shift letter, when
         * that is more than its capital. A row whose text keys do not line up
         * keeps its capitals and is counted.
         */
        private fun mergeShift(letters: List<List<Key>>, shifted: List<List<Key>>): List<List<Key>> {
            var unmerged = letters.size != shifted.size
            val out = letters.mapIndexed { r, row ->
                val other = shifted.getOrNull(r) ?: return@mapIndexed row
                val mine = row.indices.filter { row[it].action == KeyAction.Text }
                val theirs = other.indices.filter { other[it].action == KeyAction.Text }
                if (mine.size != theirs.size) {
                    unmerged = true
                    return@mapIndexed row
                }
                val copy = row.toMutableList()
                for ((b, s) in mine.zip(theirs)) {
                    val key = row[b]
                    val up = other[s]
                    val typed = key.output ?: key.label
                    val upTyped = up.output ?: up.label
                    if (upTyped != typed && upTyped != typed.uppercase()) copy[b] = key.copy(shiftLabel = upTyped)
                    if (up.longPress.isNotEmpty() && up.longPress != key.longPress.map { it.uppercase() }) {
                        report.shiftedPopups++
                    }
                }
                copy
            }
            if (unmerged) report.shiftedPagesUnmerged++
            return out
        }

        // ---- hardware ----

        /**
         * A computer layout: the layers become the four levels of a
         * [DesktopKeymap], by the form's key positions, and [DesktopLayouts]
         * folds that the way it folds a `.klc`.
         */
        private fun buildHardware(set: K3LayerSet, plans: Map<String, Plan>, title: String?, tag: String?): ConvertedLayout? {
            val levels = HashMap<DesktopKeyPos, Array<DesktopOutput?>>()
            val used = HashSet<Int>()
            for (layer in set.layers) {
                val level = levelOf(layer.modifiers)
                if (level == null || !used.add(level)) {
                    report.layersDropped++
                    continue
                }
                layer.rows.forEachIndexed { r, ids ->
                    val positions = positionsFor(set.formId, r, ids.size)
                    ids.forEachIndexed { c, id ->
                        val pos = positions.getOrNull(c) ?: return@forEachIndexed
                        val k = keys[id] ?: return@forEachIndexed
                        val out = hardwareOutput(k, plans) ?: return@forEachIndexed
                        levels.getOrPut(pos) { arrayOfNulls(LEVEL_COUNT) }[level] = out
                    }
                }
            }
            if (levels.isEmpty()) return null
            val map = DesktopKeymap(
                format = DesktopFormat.KEYBOARD3,
                name = title,
                languageTag = tag,
                keys = levels.mapValues { it.value.toList() },
            )
            val converted = DesktopLayouts.convert(map, fileName) ?: return null
            return converted.copy(notes = report.notes(scaled = false) + converted.notes)
        }

        private fun hardwareOutput(k: K3Key, plans: Map<String, Plan>): DesktopOutput? {
            if (k.gap || k.layerId != null) return null
            val raw = k.output ?: return null
            if (special(raw) != null) return null
            val own = visible(raw)
            val plan = plans[raw]
            if (plan != null) {
                // An accented letter Unicode has no single character for is
                // left to the dead key's own spacing form, as XKB's are, rather
                // than put on every letter's press and hold.
                val compositions = plan.compositions
                    .filter { (rest, result) ->
                        rest.isNotEmpty() && !hasMarker(rest) &&
                            !(plan is Plan.Dead && plan.explains(raw, rest, result) && result.codePointCount(0, result.length) > 1)
                    }
                    .mapValues { visible(it.value) }
                    .filterValues { it.isNotEmpty() }
                if (compositions.isNotEmpty()) {
                    return DesktopOutput.Dead(
                        name = k.id,
                        compositions = compositions,
                        mark = (plan as? Plan.Dead)?.mark,
                        standalone = own.ifEmpty { null },
                    )
                }
            }
            // A lone combining mark is this keyboard's dead key, and one
            // there too: Unicode composes it with the letter that follows.
            if (own.length == 1 && own[0].code in COMBINING_MARKS) {
                return DesktopOutput.Dead(name = k.id, compositions = emptyMap(), mark = own.single(), standalone = null)
            }
            return own.ifEmpty { null }?.let { DesktopOutput.Text(normalizeForScript(it)) }
        }

        /**
         * The level a hardware layer's `modifiers` name, or null for one this
         * keyboard has no level for (Caps, Ctrl, `other`). Alternatives are
         * comma-separated; the first one that names a level wins.
         */
        private fun levelOf(modifiers: String?): Int? {
            for (alt in (modifiers ?: NONE).split(',')) {
                val parts = alt.trim().split(Whitespace).filter { it.isNotEmpty() }
                    .map { if (it.startsWith("alt")) "alt" else if (it.startsWith("ctrl")) "ctrl" else it }
                    .toSet()
                val level = when (parts) {
                    emptySet<String>(), setOf(NONE) -> LEVEL_BASE
                    setOf(SHIFT) -> LEVEL_SHIFT
                    setOf("alt") -> LEVEL_ALTGR
                    setOf("alt", SHIFT) -> LEVEL_SHIFT_ALTGR
                    else -> null
                }
                if (level != null) return level
            }
            return null
        }

        /** The key positions of row [row] of [form], left to right. */
        private fun positionsFor(form: String, row: Int, count: Int): List<DesktopKeyPos> = when (row) {
            0 -> (if (form == JIS) emptyList() else listOf(DesktopKeyPos.TLDE)) + (1..12).map { keyPos("AE", it) }
            1 -> (1..12).map { keyPos("AD", it) } + DesktopKeyPos.BKSL
            2 -> (1..11).map { keyPos("AC", it) } + DesktopKeyPos.BKSL
            3 -> {
                val iso = form == ISO || form == ABNT2 || (form !in AnsiForms && count == ISO_BOTTOM)
                (if (iso) listOf(DesktopKeyPos.LSGT) else emptyList()) + (1..10).map { keyPos("AB", it) }
            }
            else -> emptyList()
        }

        // ---- helpers ----

        private fun String?.ids(): List<String> = this?.trim()?.split(Whitespace)?.filter { it.isNotEmpty() }.orEmpty()

        private fun String?.isNone(): Boolean = this == null || this.split(',').any { it.trim() == NONE }

        fun hasMarker(text: String): Boolean = text.codePoints().anyMatch { isMarker(it) }

        fun visible(text: String): String {
            if (!hasMarker(text)) return text
            val out = StringBuilder(text.length)
            text.codePoints().filter { !isMarker(it) }.forEach { out.appendCodePoint(it) }
            return out.toString()
        }

        private fun isMarker(cp: Int): Boolean = cp in MARKER_BASE..(MARKER_BASE + MAX_MARKERS)
    }

    /**
     * The action an output names when it is not text: a space, a line break,
     * a tab, or one of FlorisBoard's `@…/…` references. [DROPPED] for a
     * reference this keyboard has nothing for; null for text.
     */
    private fun special(output: String): KeyAction? = when {
        output == " " -> KeyAction.Space
        output == "\n" || output == "\r" || output == "\r\n" -> KeyAction.Enter
        output == "\t" -> KeyAction.SendKey(KEYCODE_TAB)
        output.startsWith(K3_ACTION) -> when (output.removePrefix(K3_ACTION)) {
            "backspace" -> KeyAction.Delete
            "delete" -> KeyAction.ForwardDelete
            "enter" -> KeyAction.Enter
            else -> DROPPED
        }
        output.startsWith(FL_ACTION) -> FlorisActions[output.removePrefix(FL_ACTION)] ?: DROPPED
        FlorisReference.matches(output) -> DROPPED
        else -> null
    }

    private fun nfc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)

    private fun isMark(text: String): Boolean =
        text.codePointCount(0, text.length) == 1 && Character.getType(text.codePointAt(0)) == Character.NON_SPACING_MARK.toInt()

    /** The part of a BCP 47 tag before its `-t-`/`-u-` extensions: `fr` of `fr-t-k0-test`. */
    private fun languagePart(tag: String): String? =
        tag.trim().split('-').takeWhile { it.length != 1 }.joinToString("-").ifEmpty { null }

    /** A marker in the output of a key that types nothing, kept apart from FlorisBoard's own ids. */
    private val DROPPED: KeyAction = KeyAction.Unknown("keyboard3")

    private val RootOpen = Regex("""<keyboard3[\s>]""")
    private val Doctype = Regex("""<!DOCTYPE""", RegexOption.IGNORE_CASE)
    private val Whitespace = Regex("""\s+""")
    private val SetRef = Regex("""\$\[(\w+)]""")
    private val MarkersOnly = Regex("""(?:\\m\{[^}]+})+""")
    private val AnyMarkerThenChar = Regex("""\\m\{\.}(?:\(\.\)|\.)?""")
    private val FlorisReference = Regex("""@[a-z0-9]+:[a-z_]+/.+""")

    private const val ROOT = "keyboard3"
    private const val TOUCH = "touch"
    private const val ISO = "iso"
    private const val ABNT2 = "abnt2"
    private const val JIS = "jis"
    private val AnsiForms = setOf("us", "ks", JIS)
    private const val ISO_BOTTOM = 11
    private const val BASE = "base"
    private const val SHIFT = "shift"
    private const val NONE = "none"
    private const val PAGE_PREFIX = "k3:"
    private const val LETTERS_LABEL = "ABC"
    private const val SYMBOLS_LABEL = "?123"
    private const val DOTTED_CIRCLE = "◌"
    private const val K3_ACTION = "@k3:action/"
    private const val FL_ACTION = "@fl:action/"
    private const val FLORIS_FOUNDATION = "flex://org.florisboard.k3.foundation/"
    private const val FLORIS_FN_KEY = "fn-key"
    private const val LEVEL_COUNT = 4
    private const val SHIFT_SHARE = 0.6
    private const val HEX = 16
    private const val BUFFER = 8 * 1024
    private const val MAX_NODES = 50_000
    private const val MAX_DEPTH = 32
    private const val MAX_IMPORT_DEPTH = 4
    private const val MAX_ARCHIVE_ENTRIES = 400
    private const val MAX_ARCHIVE_BYTES = 16L * 1024 * 1024
    private const val MAX_EXPANSIONS = 2048
    private const val MAX_CLASS = 2048
    private const val MAX_HOLD = 64
    private const val MAX_MARKERS = 0xFFFD

    /** Markers live in plane 16's private use area, which no layout types. */
    private const val MARKER_BASE = 0x100000

    /** A phone is under 80 mm wide; `minDeviceWidth` is in millimetres. */
    private const val PHONE_WIDTH_MM = 80
    private val SURROGATES = 0xD800..0xDFFF
    private val COMBINING_MARKS = 0x0300..0x036F

    /** The CLDR imports whose keys [ImpliedKeys] already holds. */
    private val BuiltInCldrImports = setOf(
        "keys-Latn-implied.xml",
        "keys-Zyyy-punctuation.xml",
        "keys-Zyyy-currency.xml",
        "scanCodes-implied.xml",
    )

    private val Directions: Map<String, FlickDirection> = mapOf(
        "n" to FlickDirection.UP,
        "s" to FlickDirection.DOWN,
        "e" to FlickDirection.RIGHT,
        "w" to FlickDirection.LEFT,
        "ne" to FlickDirection.UP_RIGHT,
        "nw" to FlickDirection.UP_LEFT,
        "se" to FlickDirection.DOWN_RIGHT,
        "sw" to FlickDirection.DOWN_LEFT,
    )

    private val IconActions: Set<KeyAction> = setOf(
        KeyAction.Shift, KeyAction.CapsLock, KeyAction.Delete, KeyAction.ForwardDelete,
        KeyAction.Space, KeyAction.Enter,
    )

    /** FlorisBoard's `@fl:action/…` names that have a key here. */
    private val FlorisActions: Map<String, KeyAction> = mapOf(
        "arrow_left" to KeyAction.SendKey(KEYCODE_DPAD_LEFT),
        "arrow_right" to KeyAction.SendKey(KEYCODE_DPAD_RIGHT),
        "arrow_up" to KeyAction.SendKey(KEYCODE_DPAD_UP),
        "arrow_down" to KeyAction.SendKey(KEYCODE_DPAD_DOWN),
        "clipboard_copy" to KeyAction.Edit(TextEditAction.COPY),
        "clipboard_cut" to KeyAction.Edit(TextEditAction.CUT),
        "clipboard_paste" to KeyAction.Edit(TextEditAction.PASTE),
        "select_all" to KeyAction.Edit(TextEditAction.SELECT_ALL),
        "undo" to KeyAction.Tool(ToolbarTool.UNDO),
        "redo" to KeyAction.Tool(ToolbarTool.REDO),
        "settings" to KeyAction.Tool(ToolbarTool.SETTINGS),
        "switch_to_next_subtype" to KeyAction.LanguageSwitch,
        "hide_ime_window" to KeyAction.Tool(ToolbarTool.HIDE_KEYBOARD),
        "show_clipboard_panel" to KeyAction.Tool(ToolbarTool.CLIPBOARD),
        "show_media_panel" to KeyAction.Emoji,
        "show_text_panel" to KeyAction.Letters,
        "external_voice_input" to KeyAction.Tool(ToolbarTool.VOICE),
        "noop_spacer" to KeyAction.None,
    )

    /** The spacing forms of the common marks, to draw a dead key with. */
    private val SpacingAccents: Map<Char, String> = mapOf(
        '\u0300' to "`", '\u0301' to "´", '\u0302' to "^", '\u0303' to "~", '\u0304' to "¯",
        '\u0306' to "˘", '\u0307' to "˙", '\u0308' to "¨", '\u030A' to "˚", '\u030B' to "˝",
        '\u030C' to "ˇ", '\u0327' to "¸", '\u0328' to "˛",
    )

    /**
     * The keys every keyboard3 file may use without declaring: CLDR's implied
     * keys (`gap`, `space`, the digits and the Latin letters) and the keys of
     * its punctuation and currency imports, by id. Transcribed from the files
     * under `keyboards/import/` in the CLDR repository (Unicode licence).
     */
    private val ImpliedKeys: Map<String, K3Key> = buildMap {
        fun key(id: String, output: String?, gap: Boolean = false, stretch: Boolean = false) =
            put(id, K3Key(id, output, gap, 1f, stretch, null, emptyList(), null, emptyList(), null))
        key("gap", null, gap = true)
        key("space", " ", stretch = true)
        ('0'..'9').forEach { key(it.toString(), it.toString()) }
        ('a'..'z').forEach { key(it.toString(), it.toString()) }
        ('A'..'Z').forEach { key(it.toString(), it.toString()) }
        mapOf(
            "amp" to "&", "apos" to "'", "asterisk" to "*", "at" to "@", "backslash" to "\\", "bang" to "!",
            "caret" to "^", "close-angle" to ">", "close-curly" to "}", "close-paren" to ")",
            "close-square" to "]", "colon" to ":", "comma" to ",", "degree" to "°", "double-quote" to "\"",
            "equal" to "=", "grave" to "`", "hash" to "#", "hyphen" to "-", "micro" to "µ", "not" to "¬",
            "open-angle" to "<", "open-curly" to "{", "open-paren" to "(", "open-square" to "[",
            "percent" to "%", "period" to ".", "pipe" to "|", "plus" to "+", "question" to "?",
            "section" to "§", "semi-colon" to ";", "slash" to "/", "tilde" to "~", "underscore" to "_",
            "dollar" to "$", "euro" to "€", "pound" to "£", "yen" to "¥", "cruzeiro" to "₢", "cent" to "¢",
        ).forEach { (id, output) -> key(id, output) }
        // FlorisBoard's foundation extension declares this one as a gap.
        key(FLORIS_FN_KEY, null, gap = true)
    }
}

// Android key codes, as numbers: this module is layout data and takes no
// android.view import (see KeyActions.kt).
private const val KEYCODE_TAB = 61
private const val KEYCODE_DPAD_UP = 19
private const val KEYCODE_DPAD_DOWN = 20
private const val KEYCODE_DPAD_LEFT = 21
private const val KEYCODE_DPAD_RIGHT = 22
