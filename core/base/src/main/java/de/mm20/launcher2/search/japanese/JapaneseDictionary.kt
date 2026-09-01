package de.mm20.launcher2.search.japanese

/**
 * The reading tables that ship with the launcher, see
 * `core/base/src/main/resources/de/mm20/launcher2/search/japanese`. They are what lets a label
 * written in kanji be found by a query typed in kana or in latin script, which is impossible to
 * do by rule: 時計 reads とけい, and nothing about the two characters says so.
 *
 * Both tables are plain text, sorted by their key, and are searched in the bytes they were read
 * as. Turning 24000 entries into a map would cost a few megabytes of heap, for a table that is
 * consulted a handful of times per label and then never again.
 *
 * The tables are only read when Japanese readings are actually enabled, so a user who never
 * types Japanese pays nothing for them.
 */
internal object JapaneseDictionary {

    /**
     * The longest written form in the word table. Looking up a longer prefix than this can never
     * match, which bounds the segmentation in [JapaneseSearchKeys].
     */
    const val MaxWordLength = 8

    private val Newline = '\n'.code.toByte()
    private val Tab = '\t'.code.toByte()
    private val Comment = '#'.code.toByte()

    private val kanjiTable by lazy { Table("kanji-readings.txt") }
    private val wordTable by lazy { Table("word-readings.txt") }

    /**
     * The readings of a single kanji, in katakana, most common first. Empty for a character the
     * table does not know, which includes every kanji that is not in common use.
     */
    fun readingsOfKanji(kanji: Char): List<String> = kanjiTable[kanji.toString()]

    /**
     * The readings of a whole word, in katakana. [word] has to be folded with [Kana.fold] first,
     * because that is the form the table is keyed by.
     */
    fun readingsOfWord(word: String): List<String> = wordTable[word]

    private class Table(resourceName: String) {

        private val data: ByteArray = (
                try {
                    JapaneseDictionary::class.java.getResourceAsStream(resourceName)
                        ?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                }) ?: ByteArray(0)

        /** Offset of the first byte of every entry, comment lines excluded. */
        private val entries: IntArray = run {
            val offsets = mutableListOf<Int>()
            var start = 0
            while (start < data.size) {
                var end = start
                while (end < data.size && data[end] != Newline) end++
                if (end > start && data[start] != Comment) offsets.add(start)
                start = end + 1
            }
            offsets.toIntArray()
        }

        operator fun get(key: String): List<String> {
            var low = 0
            var high = entries.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val start = entries[middle]
                val separator = endOfKey(start)
                val comparison = String(data, start, separator - start, Charsets.UTF_8)
                    .compareTo(key)
                when {
                    comparison < 0 -> low = middle + 1
                    comparison > 0 -> high = middle - 1
                    else -> return readingsAt(separator)
                }
            }
            return emptyList()
        }

        private fun endOfKey(start: Int): Int {
            var i = start
            while (i < data.size && data[i] != Tab && data[i] != Newline) i++
            return i
        }

        private fun readingsAt(separator: Int): List<String> {
            val readings = mutableListOf<String>()
            var start = separator
            while (start < data.size && data[start] == Tab) {
                start++
                var end = start
                while (end < data.size && data[end] != Tab && data[end] != Newline) end++
                if (end > start) readings.add(String(data, start, end - start, Charsets.UTF_8))
                start = end
            }
            return readings
        }
    }
}
