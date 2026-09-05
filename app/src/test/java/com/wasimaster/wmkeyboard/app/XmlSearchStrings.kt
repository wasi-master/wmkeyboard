package com.wasimaster.wmkeyboard.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * [SearchStrings] read from the `values/strings*.xml` files on disk, so a plain
 * JVM test can build the real search index — every row, every keyword — without
 * Android resources.
 *
 * The ids come from the generated `R` classes by reflection (name to id), the
 * words from the XML (name to text). A name the index uses that no file defines
 * fails loudly rather than returning an empty string: an empty title would
 * silently drop a row from every ranking test.
 */
internal class XmlSearchStrings(
    rClasses: List<Class<*>>,
    valuesDirs: List<File>,
) : SearchStrings {

    private val names: Map<Int, String> = buildMap {
        for (r in rClasses) {
            for (inner in listOf("string", "array")) {
                val cls = r.declaredClasses.firstOrNull { it.simpleName == inner } ?: continue
                for (field in cls.fields) {
                    val id = field.getInt(null)
                    val clash = put(id, field.name)
                    require(clash == null) { "id $id is both $clash and ${field.name}" }
                }
            }
        }
    }

    private val strings = HashMap<String, String>()
    private val arrays = HashMap<String, List<String>>()

    init {
        val builder = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }.newDocumentBuilder()
        for (dir in valuesDirs) {
            val files = dir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            require(files.isNotEmpty()) { "no strings*.xml under $dir" }
            for (file in files) {
                val doc = builder.parse(file)
                val nodes = doc.documentElement.childNodes
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as? Element ?: continue
                    val name = node.getAttribute("name")
                    when (node.tagName) {
                        "string" -> strings[name] = unescape(node.textContent)
                        "string-array" -> {
                            val items = node.getElementsByTagName("item")
                            arrays[name] = (0 until items.length).map { unescape(items.item(it).textContent) }
                        }
                    }
                }
            }
        }
    }

    override fun getString(id: Int): String {
        val name = resourceName(id)
        return strings[name] ?: error("string '$name' is not defined in any values/strings*.xml")
    }

    override fun getStringArray(id: Int): Array<String> {
        val name = resourceName(id)
        return arrays[name]?.toTypedArray() ?: error("string-array '$name' is not defined")
    }

    override fun resourceName(id: Int): String = names[id] ?: error("no R field has id $id")

    private fun unescape(raw: String): String {
        var text = raw.trim()
        if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text = text.substring(1, text.length - 1)
        return text.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@")
    }

    companion object {
        /** The strings the settings app's index reads: its own, and the two library modules it names. */
        fun forApp(): XmlSearchStrings = XmlSearchStrings(
            rClasses = listOf(
                com.wasimaster.wmkeyboard.R::class.java,
                com.wasimaster.wmkeyboard.common.R::class.java,
                com.wasimaster.wmkeyboard.ime.R::class.java,
            ),
            valuesDirs = listOf(
                File("src/main/res/values"),
                File("src/full/res/values"),
                File("../core/common/src/main/res/values"),
                File("../feature/ime/src/main/res/values"),
            ),
        )
    }
}
