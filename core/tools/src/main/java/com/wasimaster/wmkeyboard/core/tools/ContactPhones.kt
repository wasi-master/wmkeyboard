package com.wasimaster.wmkeyboard.core.tools

import java.util.Locale

/**
 * The address book's names and phone numbers, for the contact chip (#656):
 * type a contact's name and the strip offers to call or text them.
 *
 * Kept in memory only, like the contact names the suggestions use, and built
 * only while the chip's switch is on. Nothing here is ever written to a file.
 *
 * Two ways a typed phrase finds somebody. The whole display name, any case
 * ("jane doe", "Mom"), always. The first name on its own ("Jane") only when it
 * is typed with its capital and no other contact shares it: "Will" or "Mark"
 * typed lowercase are words, and "Jane" with two Janes saved would have to
 * guess which one to ring.
 */
class ContactPhones private constructor(
    private val byName: Map<String, Contact>,
    private val byFirstName: Map<String, Contact>,
) {

    /** One person: the name as saved and the number the chip uses. */
    data class Contact(val name: String, val number: String)

    /**
     * One phone row read from the address book. [contactId] tells two people
     * saved under the same name apart from one person with two numbers.
     */
    data class Entry(
        val name: String,
        val number: String,
        val primary: Boolean = false,
        val contactId: Long = 0,
    )

    val isEmpty: Boolean get() = byName.isEmpty()

    /**
     * The contact [phrase] names, or null. [phrase] is the typed words joined
     * by single spaces, as [SmartSuggest] hands them over.
     */
    fun match(phrase: String): Contact? {
        val key = normalize(phrase)
        if (key.isEmpty()) return null
        byName[key]?.let { return it }
        if (' ' in key) return null
        if (!phrase.first().isUpperCase()) return null
        return byFirstName[key]
    }

    /**
     * The first saved contact, in name order, whose name or one of whose
     * words starts with [prefix]: what a contact search shortcut offers
     * (#1242). Null for an empty prefix.
     */
    fun startingWith(prefix: String): Contact? {
        val key = normalize(prefix)
        if (key.isEmpty()) return null
        val matches = byName.filterKeys { name -> name.startsWith(key) || name.split(' ').any { it.startsWith(key) } }
        return matches.entries.minByOrNull { (name, _) -> if (name.startsWith(key)) "0$name" else "1$name" }?.value
    }

    companion object {
        val EMPTY = ContactPhones(emptyMap(), emptyMap())

        /** More words than this is not a name anyone types before calling. */
        const val MAX_WORDS = 4

        /**
         * Builds the index. A contact with several numbers is offered on the
         * one marked primary, else the first one read; two contacts saved
         * under the same name collapse to the first, as the dialer's own
         * search would show both and this chip can only ring one.
         */
        fun from(entries: Iterable<Entry>): ContactPhones {
            val chosen = LinkedHashMap<String, Entry>()
            for (entry in entries) {
                val name = entry.name.trim()
                val number = entry.number.trim()
                if (name.isEmpty() || number.none { it.isDigit() }) continue
                val key = normalize(name)
                if (key.isEmpty() || key.count { it == ' ' } >= MAX_WORDS) continue
                val previous = chosen[key]
                if (previous == null || (entry.primary && !previous.primary && previous.contactId == entry.contactId)) {
                    chosen[key] = entry.copy(name = name, number = number)
                }
            }
            val byName = chosen.mapValues { (_, e) -> Contact(e.name, e.number) }
            // First names that belong to exactly one saved contact.
            val firstCounts = HashMap<String, Int>()
            for (key in byName.keys) {
                if (' ' !in key) continue
                val first = key.substringBefore(' ')
                firstCounts[first] = (firstCounts[first] ?: 0) + 1
            }
            val byFirstName = HashMap<String, Contact>()
            for ((key, contact) in byName) {
                if (' ' !in key) continue
                val first = key.substringBefore(' ')
                // A first name that is also somebody's whole name ("Jane" and
                // "Jane Doe") already resolves to the whole-name one.
                if (firstCounts[first] == 1 && first !in byName) byFirstName[first] = contact
            }
            return ContactPhones(byName, byFirstName)
        }

        private fun normalize(text: String): String =
            text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                .joinToString(" ").lowercase(Locale.ROOT)
    }
}
