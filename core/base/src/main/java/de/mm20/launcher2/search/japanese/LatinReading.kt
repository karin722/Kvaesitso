package de.mm20.launcher2.search.japanese

/**
 * Rule based grapheme to phoneme conversion for latin words, producing the same key alphabet
 * that [Kana] produces for kana. This is what lets a query in kana find an app that is labelled
 * in latin script: `line` reads as ライン, which is the key `rain`, and so does `らいん`.
 *
 * The rules are approximate on purpose. They are combined with the typo tolerance in
 * `ResultScore`, so a reading that is one or two characters off the real katakana spelling
 * (ゲーム vs ゲイム) still matches.
 */
internal object LatinReading {

    private const val Vowels = "aiueo"
    private const val Doubled = "bcdfgklmnprstz"

    /** Readings of a consonant before each vowel, standalone, and as a y-glide stem. */
    private class Consonant(
        val a: String,
        val i: String,
        val u: String,
        val e: String,
        val o: String,
        val alone: String,
        val glide: String?,
    ) {
        fun before(vowel: Char): String = when (vowel) {
            'a' -> a
            'i' -> i
            'u' -> u
            'e' -> e
            'o' -> o
            else -> alone
        }
    }

    private val consonants: Map<String, Consonant> = buildMap {
        fun add(c: String, a: String, i: String, u: String, e: String, o: String, alone: String, glide: String?) {
            put(c, Consonant(a, i, u, e, o, alone, glide))
        }
        add("b", "ba", "bi", "bu", "be", "bo", "bu", "by")
        // c never survives preprocess(), but romajiReading() does not preprocess
        add("c", "ka", "si", "ku", "se", "ko", "ku", null)
        add("d", "da", "di", "du", "de", "do", "do", null)
        add("f", "ha", "hi", "hu", "he", "ho", "hu", "hy")
        add("g", "ga", "gi", "gu", "ge", "go", "gu", "gy")
        add("h", "ha", "hi", "hu", "he", "ho", "hu", "hy")
        add("j", "zya", "zi", "zyu", "zye", "zyo", "zi", "zy")
        add("k", "ka", "ki", "ku", "ke", "ko", "ku", "ky")
        add("l", "ra", "ri", "ru", "re", "ro", "ru", "ry")
        add("m", "ma", "mi", "mu", "me", "mo", "mu", "my")
        add("n", "na", "ni", "nu", "ne", "no", "n", "ny")
        add("p", "pa", "pi", "pu", "pe", "po", "pu", "py")
        add("q", "ka", "ki", "ku", "ke", "ko", "ku", "ky")
        add("r", "ra", "ri", "ru", "re", "ro", "ru", "ry")
        add("s", "sa", "si", "su", "se", "so", "su", null)
        add("t", "ta", "ti", "tu", "te", "to", "to", "ty")
        add("v", "ba", "bi", "bu", "be", "bo", "bu", "by")
        add("w", "wa", "wi", "u", "we", "wo", "u", null)
        add("x", "kusa", "kusi", "kusu", "kuse", "kuso", "kusu", null)
        add("y", "ya", "i", "yu", "ye", "yo", "i", null)
        add("z", "za", "zi", "zu", "ze", "zo", "zu", "zy")
        add("ch", "tya", "ti", "tyu", "tye", "tyo", "ti", "ty")
        add("sh", "sya", "si", "syu", "sye", "syo", "si", "sy")
        add("th", "sa", "si", "su", "se", "so", "su", null)
        add("ph", "ha", "hi", "hu", "he", "ho", "hu", "hy")
        add("wh", "wa", "hi", "hu", "we", "ho", "hu", null)
        add("ts", "tua", "tui", "tu", "tue", "tuo", "tu", null)
        add("tw", "tua", "tui", "tuu", "tue", "tuo", "tu", null)
    }

    private class VowelRule(
        val pattern: String,
        val key: String,
        val beforeConsonant: Boolean = false,
        val atEnd: Boolean = false,
    )

    private val vowelRules = listOf(
        VowelRule("eau", "oo"),
        VowelRule("air", "ea"),
        VowelRule("ear", "iaa"),
        VowelRule("eer", "iaa"),
        VowelRule("oor", "uaa"),
        VowelRule("our", "aa"),
        VowelRule("ar", "aa", beforeConsonant = true, atEnd = true),
        VowelRule("er", "aa", beforeConsonant = true, atEnd = true),
        VowelRule("ir", "aa", beforeConsonant = true, atEnd = true),
        VowelRule("ur", "aa", beforeConsonant = true, atEnd = true),
        VowelRule("or", "oo", beforeConsonant = true, atEnd = true),
        VowelRule("ee", "ii"),
        VowelRule("ea", "ii"),
        VowelRule("oo", "uu"),
        VowelRule("ou", "au"),
        VowelRule("ow", "au"),
        VowelRule("oa", "oo"),
        VowelRule("oi", "oi"),
        VowelRule("oy", "oi"),
        VowelRule("ai", "ei"),
        VowelRule("ay", "ei"),
        VowelRule("ei", "ei"),
        VowelRule("ey", "ii"),
        VowelRule("au", "oo"),
        VowelRule("aw", "oo"),
        VowelRule("ew", "yuu"),
        VowelRule("ie", "ii"),
        VowelRule("y", "ii", atEnd = true),
        VowelRule("a", "a"),
        VowelRule("i", "i"),
        VowelRule("u", "u"),
        VowelRule("e", "e"),
        VowelRule("o", "o"),
    )

    /** vowel + single consonant + silent e (game -> ゲイム, note -> ノート) */
    private val magicE = mapOf('a' to "ei", 'i' to "ai", 'u' to "yuu", 'e' to "ii", 'o' to "oo")

    /** vowel + r + silent e (store -> ストア, care -> ケア, fire -> ファイア) */
    private val magicRe = mapOf('a' to "ea", 'i' to "aia", 'u' to "yua", 'e' to "ia", 'o' to "oa")

    private class WordRule(val pattern: String, val key: String, val atEnd: Boolean = false)

    private val wordRules = listOf(
        WordRule("tion", "syon"),
        WordRule("sion", "zyon"),
        WordRule("ough", "oo"),
        WordRule("ight", "aito"),
        WordRule("igh", "ai"),
        WordRule("augh", "aa"),
        WordRule("ng", "ngu", atEnd = true),
        WordRule("nk", "nku", atEnd = true),
        WordRule("dge", "zi", atEnd = true),
        WordRule("que", "ku", atEnd = true),
        WordRule("qu", "ku"),
        WordRule("mb", "mu", atEnd = true),
        WordRule("mn", "mu", atEnd = true),
        WordRule("gh", ""),
    )

    /** Words the rules get badly wrong, and abbreviations that are read as a Japanese word. */
    private val overrides = mapOf(
        "x" to "ekkusu",
        "youtube" to "yutyubu",
        "gmail" to "zimeru",
        "yahoo" to "yahu",
        "facebook" to "heisubuku",
        "weather" to "weza",
        "music" to "myuziku",
        "wifi" to "waihai",
        "tv" to "terebi",
        "pc" to "pasokon",
        "app" to "apuri",
        "apps" to "apuri",
        "one" to "wan",
        "the" to "za",
        "of" to "obu",
        "you" to "yu",
        "are" to "aa",
        "eye" to "ai",
        "buy" to "bai",
        "key" to "ki",
        "they" to "zei",
    )

    private fun isVowel(c: Char): Boolean = Vowels.indexOf(c) >= 0

    /**
     * Reads a latin word the way an English loanword is read in Japanese.
     */
    fun englishReading(word: String): String {
        overrides[word]?.let { return Kana.collapseRepeated(it) }

        val pluralized = plural(word)
        val w = preprocess(pluralized.stem)
        val n = w.length
        val silentE = n >= 3 && w[n - 1] == 'e' && !isVowel(w[n - 2])
        val sb = StringBuilder(n * 2)
        var i = 0

        outer@ while (i < n) {
            for (rule in wordRules) {
                if (!w.startsWith(rule.pattern, i)) continue
                if (rule.atEnd && i + rule.pattern.length != n) continue
                sb.append(rule.key)
                i += rule.pattern.length
                continue@outer
            }

            if (isVowel(w[i]) || (w[i] == 'y' && i == n - 1)) {
                val unit = readVowel(w, i, silentE)
                if (unit == null) {
                    sb.append(w[i])
                    i++
                } else {
                    sb.append(unit.key)
                    i += unit.length
                }
                continue
            }

            val digraph = if (i + 2 <= n) w.substring(i, i + 2) else ""
            val cons = if (consonants.containsKey(digraph)) digraph else w.substring(i, i + 1)
            val row = consonants[cons]
            if (row == null) {
                sb.append(w[i])
                i++
                continue
            }
            val j = i + cons.length
            // consonant + y + vowel is a glide (tokyo, ryokan, kyocera)
            val glide = row.glide
            if (glide != null && j + 1 < n && w[j] == 'y' && isVowel(w[j + 1])) {
                sb.append(glide).append(w[j + 1])
                i = j + 2
                continue
            }
            // ch before a consonant is /k/ (chrome, school, christmas)
            if (cons == "ch" && j < n && !isVowel(w[j])) {
                sb.append("ku")
                i = j
                continue
            }
            if (j == n - 1 && silentE) {
                sb.append(row.alone)
                i = n
                continue
            }
            val unit = if (j < n) readVowel(w, j, silentE) else null
            if (unit == null) {
                sb.append(row.alone)
                i = j
            } else {
                sb.append(combine(row, unit.key))
                i = j + unit.length
            }
        }
        sb.append(pluralized.suffix)
        return Kana.collapseRepeated(sb.toString())
    }

    private class Plural(val stem: String, val suffix: String)

    /**
     * Splits off a plural -s, which would otherwise hide the silent e it follows
     * (`files` reads as ファイルズ, not as フィレス).
     */
    private fun plural(word: String): Plural {
        val n = word.length
        if (n < 4 || word[n - 1] != 's' || "sxzu".indexOf(word[n - 2]) >= 0) {
            return Plural(word, "")
        }
        if (word[n - 2] == 'e' && (n < 5 || "sxzcho".indexOf(word[n - 3]) >= 0)) {
            return Plural(word, "")
        }
        val stem = word.substring(0, n - 1)
        // the plural -s is ス after a voiceless sound and ズ otherwise
        val last = if (stem.endsWith("e") && stem.length >= 2) stem[stem.length - 2] else stem[stem.length - 1]
        return Plural(stem, if ("ptkfsch".indexOf(last) >= 0) "su" else "zu")
    }

    /**
     * Reads a latin word as if it was plain romaji. Covers names that are Japanese to begin
     * with, where the English rules would be wrong: mercari -> メルカリ, yodobashi -> ヨドバシ.
     */
    fun romajiReading(word: String): String {
        val n = word.length
        val sb = StringBuilder(n * 2)
        var i = 0
        while (i < n) {
            val c = word[i]
            if (isVowel(c)) {
                sb.append(c)
                i++
                continue
            }
            val row = consonants[c.toString()]
            if (row == null) {
                sb.append(c)
                i++
                continue
            }
            val glide = row.glide
            if (glide != null && i + 2 < n && word[i + 1] == 'y' && isVowel(word[i + 2])) {
                sb.append(glide).append(word[i + 2])
                i += 3
            } else if (i + 1 < n && isVowel(word[i + 1])) {
                sb.append(row.before(word[i + 1]))
                i += 2
            } else {
                sb.append(row.alone)
                i++
            }
        }
        return Kana.collapseRepeated(sb.toString())
    }

    private fun preprocess(word: String): String {
        var w = word.replace("tch", "ch")

        // resolve soft and hard c up front (care -> kare, city -> sity)
        val resolved = StringBuilder(w.length)
        var i = 0
        while (i < w.length) {
            if (w[i] == 'c') {
                if (w.startsWith("ch", i)) {
                    resolved.append("ch")
                    i += 2
                    continue
                }
                val next = if (i + 1 < w.length) w[i + 1] else ' '
                resolved.append(if (next == 'e' || next == 'i' || next == 'y') 's' else 'k')
                i++
                continue
            }
            resolved.append(w[i])
            i++
        }
        w = resolved.toString()

        // double consonants are 促音, which the key alphabet does not spell out
        val deduped = StringBuilder(w.length)
        for (c in w) {
            if (deduped.isNotEmpty() && deduped[deduped.length - 1] == c && Doubled.indexOf(c) >= 0) continue
            deduped.append(c)
        }
        w = deduped.toString()

        // consonant + "le" at the end is a syllable of its own (google -> グーグル)
        if (w.length >= 4 && w.endsWith("le") && !isVowel(w[w.length - 3])) {
            w = w.substring(0, w.length - 2) + "uru"
        }
        return w
    }

    private class VowelUnit(val key: String, val length: Int)

    private fun readVowel(w: String, i: Int, silentE: Boolean): VowelUnit? {
        val n = w.length
        if (silentE && i == n - 3 && isVowel(w[i]) && !isVowel(w[i + 1])) {
            if (w[i + 1] == 'r') {
                return magicRe[w[i]]?.let { VowelUnit(it, 3) }
            }
            return magicE[w[i]]?.let { VowelUnit(it, 1) }
        }
        for (rule in vowelRules) {
            if (!w.startsWith(rule.pattern, i)) continue
            val j = i + rule.pattern.length
            if (rule.beforeConsonant || rule.atEnd) {
                if (j == n) {
                    if (!rule.atEnd) continue
                } else if (isVowel(w[j])) {
                    continue
                } else if (!rule.beforeConsonant) {
                    continue
                }
            }
            return VowelUnit(rule.key, rule.pattern.length)
        }
        return null
    }

    private fun combine(row: Consonant, unit: String): String {
        if (unit[0] == 'y') {
            val stem = row.glide
            if (stem != null) return stem + unit.substring(1)
            return row.u.dropLast(1) + unit
        }
        return row.before(unit[0]) + unit.substring(1)
    }
}
