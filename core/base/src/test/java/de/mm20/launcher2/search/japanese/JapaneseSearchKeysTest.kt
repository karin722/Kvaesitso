package de.mm20.launcher2.search.japanese

import de.mm20.launcher2.search.ResultScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseSearchKeysTest {

    private fun check(input: String, vararg expected: String) {
        assertEquals("keys of \"$input\"", expected.toList(), JapaneseSearchKeys.keysOf(input))
    }

    @Test
    fun readsKana() {
        check("らいん", "rain")
        check("ライン", "rain")
        check("ﾗｲﾝ", "rain")
        check("ぐーぐる", "guguru")
        check("グーグル", "guguru")
        check("ゆーちゅーぶ", "yutyubu")
        check("ついったー", "tuita")
        check("いんすたぐらむ", "insutaguramu")
        check("ねっとふりっくす", "netohurikusu")
        check("でぃすこーど", "disukodo")
        check("ふぁいる", "hairu")
        check("じーめーる", "zimeru")
    }

    @Test
    fun readsLatin() {
        check("LINE", "rain", "rine")
        check("Google", "guguru", "gogure")
        check("Chrome", "kuromu", "kuhurome")
        check("YouTube", "yutyubu", "youtube")
        check("Gmail", "zimeru", "gumairu")
        check("Amazon", "amazon")
        check("Twitter", "tuita", "towitoteru")
        check("Instagram", "insutaguramu")
        check("PayPay", "peipei", "paipai")
        check("Discord", "disukodo", "disukorudo")
        check("Slack", "suraku", "surakuku")
        check("Netflix", "netohurikusu")
        check("Camera", "kamera")
        check("Calendar", "karenda", "karendaru")
        check("Clock", "kuroku", "kurokuku")
        check("Maps", "mapusu")
        check("Files", "hairuzu", "hiresu")
        check("Drive", "doraibu", "doribe")
        check("Store", "sutoa", "sutore")
    }

    @Test
    fun readsRomajiOfJapaneseWords() {
        check("Mercari", "makari", "merukari")
        check("Yodobashi", "yodobasi", "yodobasuhi")
        check("Rakuten", "rakuten")
        check("docomo", "dokomo")
        check("Suica", "suika")
        check("Tokyo Metro", "tokyometoro")
    }

    @Test
    fun readsMixedScriptsAndFullwidth() {
        check("Yahoo!ニュース", "yahunyusu", "yahonyusu")
        check("Google Maps", "gugurumapusu", "goguremapusu")
        check("ＬＩＮＥ", "rain", "rine")
    }

    @Test
    fun ignoresWhatItCannotRead() {
        check("")
        check("!?")
        // 兀 is a kanji, but not one in common use, so it is not in the reading table
        check("兀")
    }

    /**
     * Both sides of a search are normalized the same way, so a kana query and a latin label meet
     * in the middle. This is what the whole thing is for.
     */
    private fun assertFinds(query: String, label: String) {
        val score = ResultScore.from(
            queries = JapaneseSearchKeys.keysOf(query) + query.lowercase(),
            primaryFields = JapaneseSearchKeys.keysOf(label) + label.lowercase(),
        )
        assertTrue(
            "\"$query\" should find \"$label\" (score ${score.score}, ${score.typos} typos)",
            score.score >= 0.8f,
        )
    }

    @Test
    fun kanaQueryFindsLatinLabel() {
        assertFinds("らいん", "LINE")
        assertFinds("ライン", "LINE")
        assertFinds("らい", "LINE")
        assertFinds("ぐーぐる", "Google")
        assertFinds("くろーむ", "Chrome")
        assertFinds("ゆーちゅーぶ", "YouTube")
        assertFinds("めるかり", "Mercari")
        assertFinds("あまぞん", "Amazon")
        assertFinds("かめら", "Camera")
        assertFinds("すとあ", "Store")
        assertFinds("ふぁいる", "Files")
        assertFinds("ついったー", "Twitter")
        assertFinds("すぽてぃふぁい", "Spotify")
        assertFinds("にゅーす", "Yahoo!ニュース")
    }

    @Test
    fun latinQueryFindsKanaLabel() {
        assertFinds("line", "ライン")
        assertFinds("google", "グーグル")
        assertFinds("mercari", "メルカリ")
        assertFinds("camera", "カメラ")
        assertFinds("news", "ニュース")
        assertFinds("rain", "ライン")
        assertFinds("merukari", "メルカリ")
    }

    @Test
    fun stillFindsWhatItAlwaysFound() {
        assertFinds("line", "LINE")
        assertFinds("goog", "Google")
        assertFinds("らいん", "らいん")
    }

    @Test
    fun readsKanji() {
        check("楽天", "rakuten")
        check("時計", "tokei")
        check("設定", "setei")
        check("電卓", "dentaku")
        check("地図", "tizu")
    }

    @Test
    fun kanaQueryFindsKanjiLabel() {
        assertFinds("とけい", "時計")
        assertFinds("せってい", "設定")
        assertFinds("でんたく", "電卓")
        assertFinds("しゃしん", "写真")
        assertFinds("ちず", "地図")
        assertFinds("おんがく", "音楽")
        assertFinds("てんき", "天気")
        assertFinds("でんわ", "電話")
        assertFinds("かめら", "カメラ")
        assertFinds("れんらくさき", "連絡先")
        assertFinds("かけいぼ", "家計簿")
        assertFinds("けいさんき", "計算機")
        assertFinds("ときどき", "時々")
    }

    @Test
    fun latinQueryFindsKanjiLabel() {
        assertFinds("tokei", "時計")
        assertFinds("settei", "設定")
        assertFinds("dentaku", "電卓")
        assertFinds("chizu", "地図")
        assertFinds("ongaku", "音楽")
        assertFinds("denwa", "電話")
    }

    @Test
    fun readsKanjiWordByWord() {
        // None of these are single dictionary words, they are read as the words they are made of
        assertFinds("のりかえあんない", "乗換案内")
        assertFinds("らくてんいちば", "楽天市場")
        assertFinds("めざまし", "目覚まし時計")
        assertFinds("ぎんこう", "三菱UFJ銀行")
        assertFinds("てんきよほう", "天気予報")
    }

    @Test
    fun findsKanjiLabelByItsPrefix() {
        assertFinds("とけ", "時計")
        assertFinds("せって", "設定")
        assertFinds("でんた", "電卓")
    }

}
