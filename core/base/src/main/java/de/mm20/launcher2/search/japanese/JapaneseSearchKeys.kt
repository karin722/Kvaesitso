package de.mm20.launcher2.search.japanese

/**
 * Builds additional, reading based search keys for a label or a query.
 *
 * A search key is a lossy romanization of how the string is *pronounced* in Japanese. Because
 * the very same alphabet is used for kana, kanji and latin script, a query and a label that
 * sound alike produce the same key even when they are written in completely different scripts:
 *
 * ```
 * keysOf("LINE")   == ["rain", "rine"]
 * keysOf("らいん")  == ["rain"]
 * keysOf("ライン")  == ["rain"]
 * keysOf("時計")    == ["tokei"]
 * keysOf("とけい")  == ["tokei"]
 * ```
 *
 * Two keys are produced for strings containing latin script, because a latin word can either be
 * an English word read as a loanword (`google` -> グーグル -> `guguru`) or romaji of a Japanese
 * word (`mercari` -> メルカリ -> `merukari`), and there is no reliable way to tell them apart.
 *
 * Kanji can be read in more than one way as well, and unlike latin script there is no rule that
 * picks the right one, so several keys are produced for them too. Whole words are looked up
 * first, because a compound is usually not read the way its characters are read on their own
 * (時 とき plus 計 けい is not とけい), and only what is left over is read character by
 * character.
 */
object JapaneseSearchKeys {

    private const val MaxInputLength = 256

    /**
     * How many readings of a single kanji are considered. The table lists them most common
     * first, so this drops the readings that are unlikely to be the one that was meant.
     */
    private const val MaxReadingsPerKanji = 4

    /**
     * How many keys a single pass may produce. Every key costs a string comparison per result
     * per keystroke, and the alternatives multiply with every kanji in the label, so a long
     * label has to give some of them up (see [limitAlternatives]).
     */
    private const val MaxKeysPerPass = 32

    /** How many keys [keysOf] returns at most, over all passes. */
    private const val MaxKeys = 48

    /**
     * @return the reading keys of [input], or an empty list if it has no readable content.
     * Never contains blank entries and never contains duplicates.
     */
    fun keysOf(input: String): List<String> {
        if (input.isEmpty() || input.length > MaxInputLength) return emptyList()

        val folded = Kana.fold(input)
        var hasKana = false
        var hasLatin = false
        var hasKanji = false
        for (c in folded) {
            when {
                Kana.isKana(c) -> hasKana = true
                Kana.isKanji(c) -> hasKanji = true
                c.isLatinLetter() -> hasLatin = true
            }
        }
        if (!hasKana && !hasLatin && !hasKanji) return emptyList()

        val keys = mutableListOf<String>()
        keys += buildKeys(folded, romaji = false)
        if (hasLatin) keys += buildKeys(folded, romaji = true)
        return keys.filter { it.isNotEmpty() }.distinct().take(MaxKeys)
    }

    /**
     * Walks the folded string and reads every run of kana as kana, every run of latin letters as
     * a word, and every kanji as the word it starts or, failing that, on its own. Separators are
     * dropped so that `Google Maps` and `ぐーぐるまっぷ` line up.
     */
    private fun buildKeys(folded: String, romaji: Boolean): List<String> {
        val alternatives = mutableListOf<List<String>>()
        var previousKanji = Kana.IterationMark
        var i = 0
        while (i < folded.length) {
            val c = folded[i]
            when {
                Kana.isKanji(c) -> {
                    val word = readWord(folded, i)
                    if (word != null) {
                        alternatives.add(word.readings)
                        i += word.length
                    } else {
                        val literal = if (c == Kana.IterationMark) previousKanji else c
                        val readings = JapaneseDictionary.readingsOfKanji(literal)
                        if (readings.isNotEmpty()) {
                            alternatives.add(readings.take(MaxReadingsPerKanji).map { Kana.toKey(it) })
                        }
                        previousKanji = literal
                        i++
                    }
                }

                Kana.isKana(c) -> {
                    var j = i
                    while (j < folded.length && Kana.isKana(folded[j])) j++
                    alternatives.add(listOf(Kana.toKey(folded.substring(i, j))))
                    i = j
                }

                c.isLatinLetter() -> {
                    var j = i
                    while (j < folded.length && folded[j].isLatinLetter()) j++
                    val word = folded.substring(i, j).lowercase()
                    alternatives.add(
                        listOf(
                            if (romaji) LatinReading.romajiReading(word)
                            else LatinReading.englishReading(word)
                        )
                    )
                    i = j
                }

                c.isDigit() -> {
                    alternatives.add(listOf(c.toString()))
                    i++
                }

                else -> i++
            }
        }
        return expand(alternatives)
    }

    private class Word(val length: Int, val readings: List<String>)

    /**
     * Looks up the longest word that starts at [start]. A word may well contain kana (買い物),
     * which is why this is not limited to the run of kanji.
     */
    private fun readWord(folded: String, start: Int): Word? {
        val longest = minOf(JapaneseDictionary.MaxWordLength, folded.length - start)
        for (length in longest downTo 2) {
            val readings = JapaneseDictionary.readingsOfWord(folded.substring(start, start + length))
            if (readings.isNotEmpty()) {
                return Word(length, readings.map { Kana.toKey(it) })
            }
        }
        return null
    }

    /**
     * Every combination of the alternatives, in order, capped at [MaxKeysPerPass].
     */
    private fun expand(alternatives: List<List<String>>): List<String> {
        if (alternatives.isEmpty()) return emptyList()
        var keys = listOf("")
        for (alternative in limitAlternatives(alternatives)) {
            if (alternative.isEmpty()) continue
            if (alternative.size == 1) {
                val only = alternative[0]
                keys = keys.map { it + only }
                continue
            }
            val expanded = ArrayList<String>(keys.size * alternative.size)
            for (key in keys) {
                for (reading in alternative) expanded.add(key + reading)
            }
            keys = expanded
        }
        return keys.map { Kana.collapseRepeated(it) }.distinct()
    }

    /**
     * Drops readings until the combinations fit into [MaxKeysPerPass], always from whichever
     * part of the string still has the most of them. Readings are ordered most common first, so
     * what is dropped is always the least likely reading of the most ambiguous character.
     */
    private fun limitAlternatives(alternatives: List<List<String>>): List<List<String>> {
        if (combinationsOf(alternatives) <= MaxKeysPerPass) return alternatives

        val limited = alternatives.map { it.toMutableList() }
        while (combinationsOf(limited) > MaxKeysPerPass) {
            val widest = limited.maxByOrNull { it.size } ?: break
            if (widest.size <= 1) break
            widest.removeAt(widest.size - 1)
        }
        return limited
    }

    /**
     * How many keys the alternatives combine into, saturating at [MaxKeysPerPass]. A long
     * string of kanji would overflow any counter long before it is done multiplying.
     */
    private fun combinationsOf(alternatives: List<List<String>>): Int {
        var combinations = 1
        for (alternative in alternatives) {
            if (alternative.size <= 1) continue
            combinations *= alternative.size
            if (combinations > MaxKeysPerPass) return MaxKeysPerPass + 1
        }
        return combinations
    }

    private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
}
