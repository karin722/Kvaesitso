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
        check("楽天")
        check("")
        check("!?")
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
}
