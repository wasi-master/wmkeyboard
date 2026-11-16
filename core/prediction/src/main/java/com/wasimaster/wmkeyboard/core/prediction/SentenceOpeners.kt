package com.wasimaster.wmkeyboard.core.prediction

import java.io.InputStream

/**
 * The bundled sentence openers of every language but English: the words that
 * most often begin a sentence, best first, keyed by language id.
 *
 * The corpus word-pair packs were cut from sentences without recording which
 * word opened one, so on an empty field, or after a full stop, only English
 * (whose openers are the `<s>` rows of [SeedBigrams]) had anything to offer
 * and every other language's strip stood empty for the first word of every
 * sentence. Built by `scripts/build_sentence_openers.py` from Tatoeba.
 *
 * Format: `language<TAB>word<TAB>count` per line, best first within a
 * language, `#` comments. Words keep the capital they have at the start of a
 * sentence, as the English rows do.
 */
object SentenceOpeners {

    fun load(stream: InputStream): Map<String, List<String>> {
        val out = LinkedHashMap<String, ArrayList<String>>()
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank() || line.startsWith("#")) continue
                val parts = line.split('\t')
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue
                out.getOrPut(parts[0]) { ArrayList() }.add(parts[1])
            }
        }
        return out
    }
}
