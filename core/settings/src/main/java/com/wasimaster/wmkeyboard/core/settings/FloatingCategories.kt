package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A category of the floating button (#923): a quick menu slot that, picked,
 * opens a ring of its own [items] round the button instead of running
 * something. Up to [MAX_FLOATING_MENU_ITEMS] of them, none a category itself.
 *
 * It is a slot of the quick menu, as [FloatingAction.Category] with this
 * [id], and lives as long as that slot does: taking the slot out of the menu
 * takes the category with it ([withoutOrphans]).
 */
data class FloatingCategory(
    val id: String,
    val name: String,
    val items: List<FloatingAction> = emptyList(),
    /**
     * The icon its slot wears (#1138), an id from the keyboard's icon
     * catalogue (the one modes and snippet folders pick from), or null for
     * the folder every category had before.
     */
    val icon: String? = null,
)

/** The longest icon id kept; the catalogue's are short words. */
private const val FLOATING_CATEGORY_ICON_MAX = 40

/**
 * The longest icon kept when it names a drawable in an installed icon pack
 * (`s:<package>/<drawable>`, #1275), which a package name makes longer.
 */
private const val FLOATING_CATEGORY_PACK_ICON_MAX = 200

/** The longest category name kept; it hangs under a slot. */
const val FLOATING_CATEGORY_NAME_MAX = 32

/** The category with this [id], or null. */
fun List<FloatingCategory>.categoryById(id: String): FloatingCategory? = firstOrNull { it.id == id }

/** An id no category in this list uses yet: `c1`, `c2`, … */
fun List<FloatingCategory>.nextCategoryId(): String {
    val taken = mapTo(HashSet()) { it.id }
    var n = 1
    while ("c$n" in taken) n++
    return "c$n"
}

/** Only the categories some slot of [menu] opens; the rest have lost their slot. */
fun List<FloatingCategory>.withoutOrphans(menu: List<FloatingAction>): List<FloatingCategory> {
    val used = menu.mapNotNullTo(HashSet()) { (it as? FloatingAction.Category)?.id }
    return filter { it.id in used }
}

/** A category's slots as they are kept: each once, none a category, at most [MAX_FLOATING_MENU_ITEMS]. */
fun categoryItems(items: List<FloatingAction>): List<FloatingAction> =
    items.filter { it.fitsCategory() }.distinct().take(MAX_FLOATING_MENU_ITEMS)

/**
 * Drops what cannot be a category: a second one under an id already taken,
 * and slots a category cannot hold ([categoryItems]). Run on everything read
 * back, so a hand-edited backup cannot nest one category in another.
 */
fun List<FloatingCategory>.normalizedCategories(): List<FloatingCategory> {
    val ids = HashSet<String>()
    return mapNotNull { category ->
        if (!ids.add(category.id)) return@mapNotNull null
        category.copy(
            name = category.name.trim().take(FLOATING_CATEGORY_NAME_MAX),
            items = categoryItems(category.items),
            icon = category.icon?.trim()?.takeIf {
                it.isNotEmpty() &&
                    it.length <= if (it.startsWith("s:")) FLOATING_CATEGORY_PACK_ICON_MAX else FLOATING_CATEGORY_ICON_MAX
            },
        )
    }
}

/**
 * The stored form: a JSON array of `{"id", "name", "items": [tokens]}`.
 * Empty encodes as null, so the preference is removed rather than kept empty.
 */
fun encodeFloatingCategories(categories: List<FloatingCategory>): String? {
    if (categories.isEmpty()) return null
    return buildJsonArray {
        for (category in categories) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(category.id))
                    put("name", JsonPrimitive(category.name))
                    put("items", JsonArray(category.items.map { JsonPrimitive(it.token) }))
                    category.icon?.let { put("icon", JsonPrimitive(it)) }
                },
            )
        }
    }.toString()
}

/** The inverse of [encodeFloatingCategories]; unknown slots and malformed entries are dropped. */
fun decodeFloatingCategories(raw: String?): List<FloatingCategory> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { Json.parseToJsonElement(raw).jsonArray }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            FloatingCategory(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                items = obj["items"]?.jsonArray.orEmpty().mapNotNull { item ->
                    item.jsonPrimitive.contentOrNull?.let(FloatingAction::parse)
                },
                icon = obj["icon"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()
    }.normalizedCategories()
}
