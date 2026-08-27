package de.mm20.launcher2.search.japanese

/**
 * Kana handling for the Japanese search keys.
 *
 * All readings are mapped to a deliberately lossy, kunrei-like romanization (the "key alphabet")
 * so that spellings which sound the same to a Japanese speaker end up as the same string:
 * ラ行 is always `r`, ヴ is `b`, ファ行 is `h`, long vowels and 促音 are dropped by
 * [collapseRepeated]. The exact same alphabet is produced by [LatinReading] for latin words,
 * which is what makes `LINE` and `らいん` comparable.
 */
internal object Kana {

    private const val HiraganaStart = 0x3041
    private const val HiraganaEnd = 0x3096
    private const val KatakanaStart = 0x30A1
    private const val KatakanaEnd = 0x30FA
    private const val HalfwidthStart = 0xFF61
    private const val HalfwidthEnd = 0xFF9F
    private const val FullwidthAsciiStart = 0xFF01
    private const val FullwidthAsciiEnd = 0xFF5E
    private const val KanaShift = 0x60

    /**
     * Katakana (and everything that folds to katakana) to key alphabet. Longest match wins,
     * the longest entry is two characters.
     */
    private val readings: Map<String, String> = buildMap {
        // 拗音
        put("キャ", "kya"); put("キュ", "kyu"); put("キョ", "kyo"); put("キェ", "kye")
        put("ギャ", "gya"); put("ギュ", "gyu"); put("ギョ", "gyo")
        put("シャ", "sya"); put("シュ", "syu"); put("ショ", "syo"); put("シェ", "sye")
        put("ジャ", "zya"); put("ジュ", "zyu"); put("ジョ", "zyo"); put("ジェ", "zye")
        put("チャ", "tya"); put("チュ", "tyu"); put("チョ", "tyo"); put("チェ", "tye")
        put("ヂャ", "zya"); put("ヂュ", "zyu"); put("ヂョ", "zyo")
        put("ニャ", "nya"); put("ニュ", "nyu"); put("ニョ", "nyo")
        put("ヒャ", "hya"); put("ヒュ", "hyu"); put("ヒョ", "hyo")
        put("ビャ", "bya"); put("ビュ", "byu"); put("ビョ", "byo")
        put("ピャ", "pya"); put("ピュ", "pyu"); put("ピョ", "pyo")
        put("ミャ", "mya"); put("ミュ", "myu"); put("ミョ", "myo")
        put("リャ", "rya"); put("リュ", "ryu"); put("リョ", "ryo")
        // 外来音
        put("ファ", "ha"); put("フィ", "hi"); put("フェ", "he"); put("フォ", "ho"); put("フュ", "hyu")
        put("ヴァ", "ba"); put("ヴィ", "bi"); put("ヴェ", "be"); put("ヴォ", "bo"); put("ヴュ", "byu")
        put("ウィ", "wi"); put("ウェ", "we"); put("ウォ", "wo")
        put("ティ", "ti"); put("トゥ", "tu"); put("ディ", "di"); put("ドゥ", "du")
        put("ツァ", "tua"); put("ツィ", "tui"); put("ツェ", "tue"); put("ツォ", "tuo")
        put("クァ", "kua"); put("クィ", "kui"); put("クェ", "kue"); put("クォ", "kuo")
        put("グァ", "gua"); put("シィ", "si"); put("ジィ", "zi")
        // 五十音
        put("ア", "a"); put("イ", "i"); put("ウ", "u"); put("エ", "e"); put("オ", "o")
        put("カ", "ka"); put("キ", "ki"); put("ク", "ku"); put("ケ", "ke"); put("コ", "ko")
        put("ガ", "ga"); put("ギ", "gi"); put("グ", "gu"); put("ゲ", "ge"); put("ゴ", "go")
        put("サ", "sa"); put("シ", "si"); put("ス", "su"); put("セ", "se"); put("ソ", "so")
        put("ザ", "za"); put("ジ", "zi"); put("ズ", "zu"); put("ゼ", "ze"); put("ゾ", "zo")
        put("タ", "ta"); put("チ", "ti"); put("ツ", "tu"); put("テ", "te"); put("ト", "to")
        put("ダ", "da"); put("ヂ", "zi"); put("ヅ", "zu"); put("デ", "de"); put("ド", "do")
        put("ナ", "na"); put("ニ", "ni"); put("ヌ", "nu"); put("ネ", "ne"); put("ノ", "no")
        put("ハ", "ha"); put("ヒ", "hi"); put("フ", "hu"); put("ヘ", "he"); put("ホ", "ho")
        put("バ", "ba"); put("ビ", "bi"); put("ブ", "bu"); put("ベ", "be"); put("ボ", "bo")
        put("パ", "pa"); put("ピ", "pi"); put("プ", "pu"); put("ペ", "pe"); put("ポ", "po")
        put("マ", "ma"); put("ミ", "mi"); put("ム", "mu"); put("メ", "me"); put("モ", "mo")
        put("ヤ", "ya"); put("ユ", "yu"); put("ヨ", "yo")
        put("ラ", "ra"); put("リ", "ri"); put("ル", "ru"); put("レ", "re"); put("ロ", "ro")
        put("ワ", "wa"); put("ヰ", "i"); put("ヱ", "e"); put("ヲ", "o"); put("ン", "n")
        put("ヴ", "bu")
        // 小書き（単体で現れた場合）
        put("ァ", "a"); put("ィ", "i"); put("ゥ", "u"); put("ェ", "e"); put("ォ", "o")
        put("ャ", "ya"); put("ュ", "yu"); put("ョ", "yo"); put("ヮ", "wa")
        // 促音・長音は落とす（collapseRepeated と組み合わせて長短の揺れを吸収する）
        put("ッ", ""); put("ー", ""); put("ｰ", "")
    }

    private val halfwidth: Map<Char, Char> = buildMap {
        val plain = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝｧｨｩｪｫｬｭｮｯ"
        val full = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲンァィゥェォャュョッ"
        for (i in plain.indices) put(plain[i], full[i])
    }

    private val halfwidthDakuten: Map<Char, Char> = buildMap {
        val plain = "ｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾊﾋﾌﾍﾎｳ"
        val full = "ガギグゲゴザジズゼゾダヂヅデドバビブベボヴ"
        for (i in plain.indices) put(plain[i], full[i])
    }

    private val halfwidthHandakuten: Map<Char, Char> = buildMap {
        val plain = "ﾊﾋﾌﾍﾎ"
        val full = "パピプペポ"
        for (i in plain.indices) put(plain[i], full[i])
    }

    fun isKana(c: Char): Boolean {
        val code = c.code
        return (code in HiraganaStart..HiraganaEnd) ||
                (code in KatakanaStart..KatakanaEnd) ||
                code == 0x30FC /* ー */ ||
                (code in 0xFF66..0xFF9D)
    }

    /**
     * Folds hiragana, half width katakana and full width ASCII into katakana / ASCII, so that
     * everything downstream only has to deal with a single representation.
     */
    fun fold(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val code = c.code
            if (code in HalfwidthStart..HalfwidthEnd) {
                val next = if (i + 1 < input.length) input[i + 1] else ' '
                val voiced = next == 'ﾞ' || next == '゙'
                val semiVoiced = next == 'ﾟ' || next == '゚'
                val dakuten = if (voiced) halfwidthDakuten[c] else null
                val handakuten = if (semiVoiced) halfwidthHandakuten[c] else null
                when {
                    dakuten != null -> { sb.append(dakuten); i += 2 }
                    handakuten != null -> { sb.append(handakuten); i += 2 }
                    else -> { sb.append(halfwidth[c] ?: c); i++ }
                }
                continue
            }
            when (code) {
                in HiraganaStart..HiraganaEnd -> sb.append((code + KanaShift).toChar())
                in FullwidthAsciiStart..FullwidthAsciiEnd -> sb.append((code - 0xFEE0).toChar())
                0x3000 -> sb.append(' ')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Reads a run of (already folded) katakana as a key. Characters that are not kana are
     * appended in lower case.
     */
    fun toKey(folded: String): String {
        val sb = StringBuilder(folded.length * 2)
        var i = 0
        while (i < folded.length) {
            if (i + 1 < folded.length) {
                val two = readings[folded.substring(i, i + 2)]
                if (two != null) {
                    sb.append(two)
                    i += 2
                    continue
                }
            }
            val one = readings[folded.substring(i, i + 1)]
            if (one != null) {
                sb.append(one)
            } else {
                sb.append(folded[i].lowercaseChar())
            }
            i++
        }
        return sb.toString()
    }

    /**
     * Drops repeated characters. This is what makes long vowels, 促音 and the various ways to
     * spell them in latin script (ブック / buku / bukku, メール / meru / meeru) equal.
     */
    fun collapseRepeated(input: String): String {
        if (input.length < 2) return input
        val sb = StringBuilder(input.length)
        for (c in input) {
            if (sb.isNotEmpty() && sb[sb.length - 1] == c) continue
            sb.append(c)
        }
        return sb.toString()
    }
}
