package de.mm20.launcher2.search.japanese

/**
 * Builds additional, reading based search keys for a label or a query.
 *
 * A search key is a lossy romanization of how the string is *pronounced* in Japanese. Because
 * the very same alphabet is used for kana and for latin script, a query and a label that sound
 * alike produce the same key even when they are written in completely different scripts:
 *
 * ```
 * keysOf("LINE")   == ["rain", "rine"]
 * keysOf("らいん")  == ["rain"]
 * keysOf("ライン")  == ["rain"]
 * ```
 *
 * Two keys are produced for strings containing latin script, because a latin word can either be
 * an English word read as a loanword (`google` -> グーグル -> `guguru`) or romaji of a Japanese
 * word (`mercari` -> メルカリ -> `merukari`), and there is no reliable way to tell them apart.
 */
object JapaneseSearchKeys {

    private const val MaxInputLength = 256

    /**
     * @return the reading keys of [input], or an empty list if it has no readable content.
     * Never contains blank entries and never contains duplicates.
     */
    fun keysOf(input: String): List<String> {
        if (input.isEmpty() || input.length > MaxInputLength) return emptyList()

        val folded = Kana.fold(input)
        var hasKana = false
        var hasLatin = false
        for (c in folded) {
            if (Kana.isKana(c)) hasKana = true
            else if (c.isLatinLetter()) hasLatin = true
        }
        if (!hasKana && !hasLatin) return emptyList()

        val english = buildKey(folded, romaji = false)
        if (!hasLatin) {
            return if (english.isEmpty()) emptyList() else listOf(english)
        }
        val romaji = buildKey(folded, romaji = true)
        return listOf(english, romaji).filter { it.isNotEmpty() }.distinct()
    }

    /**
     * Walks the folded string and reads every run of kana as kana and every run of latin letters
     * as a word. Separators are dropped so that `Google Maps` and `ぐーぐるまっぷ` line up.
     */
    private fun buildKey(folded: String, romaji: Boolean): String {
        val sb = StringBuilder(folded.length * 2)
        var i = 0
        while (i < folded.length) {
            val c = folded[i]
            when {
                Kana.isKana(c) -> {
                    var j = i
                    while (j < folded.length && Kana.isKana(folded[j])) j++
                    sb.append(Kana.toKey(folded.substring(i, j)))
                    i = j
                }

                c.isLatinLetter() -> {
                    var j = i
                    while (j < folded.length && folded[j].isLatinLetter()) j++
                    val word = folded.substring(i, j).lowercase()
                    sb.append(if (romaji) LatinReading.romajiReading(word) else LatinReading.englishReading(word))
                    i = j
                }

                c.isDigit() -> {
                    sb.append(c)
                    i++
                }

                else -> i++
            }
        }
        return Kana.collapseRepeated(sb.toString())
    }

    private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
