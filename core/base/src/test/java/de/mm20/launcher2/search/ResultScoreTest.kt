package de.mm20.launcher2.search

import org.junit.Assert
import org.junit.Test

class ResultScoreTest {

    private fun score(query: String, term: String): Float =
        ResultScore.from(query, primaryFields = listOf(term)).score

    private fun matches(query: String, term: String): Boolean =
        score(query, term) >= ResultScore.MatchThreshold

    @Test
    fun exactMatchScoresOne() {
        Assert.assertEquals(1f, score("chrome", "chrome"), 0f)
    }

    @Test
    fun emptyQueryMatchesEverything() {
        Assert.assertEquals(1f, score("", "chrome"), 0f)
    }

    @Test
    fun matchesSubstrings() {
        Assert.assertTrue(matches("chr", "chrome"))
        Assert.assertTrue(matches("hrom", "chrome"))
        Assert.assertTrue(matches("music", "yt music"))
    }

    @Test
    fun matchesAbbreviations() {
        Assert.assertTrue(matches("ytm", "yt music"))
        Assert.assertTrue(matches("pstr", "play store"))
        Assert.assertTrue(matches("ktso", "kvaesitso"))
        Assert.assertTrue(matches("ggl", "google"))
        Assert.assertTrue(matches("cnt", "contacts"))
    }

    @Test
    fun toleratesTypos() {
        // Neither query is a subsequence of its label, so these can only match by similarity.
        Assert.assertTrue(matches("settimgs", "settings"))
        Assert.assertTrue(matches("chorme", "chrome"))
    }

    @Test
    fun toleratesDroppedCharacters() {
        Assert.assertTrue(matches("chrme", "chrome"))
        Assert.assertTrue(matches("settngs", "settings"))
    }

    @Test
    fun rejectsUnrelatedLabels() {
        Assert.assertFalse(matches("chrome", "calendar"))
        Assert.assertFalse(matches("maps", "settings"))
    }

    @Test
    fun noTypoCorrectionForShortQueries() {
        // Similar enough by Jaro-Winkler, but two characters are too little to guess a typo from.
        Assert.assertFalse(matches("ab", "a"))
    }

    @Test
    fun ranksPrefixOverScatteredOverTypo() {
        // Only the matched window is scored, so an exact match and a prefix match tie, as they
        // did when every substring match saturated to 1. Usage weights decide between them.
        val exact = score("chrome", "chrome")
        val prefix = score("chrome", "chrome beta")
        val scattered = score("chrome", "cash register over media")
        val typo = score("chorme", "chrome")
        Assert.assertEquals(exact, prefix, 0f)
        Assert.assertTrue("prefix $prefix > scattered $scattered", prefix > scattered)
        Assert.assertTrue("scattered $scattered > typo $typo", scattered > typo)
    }

    @Test
    fun literalMatchOutranksCloseTypo() {
        // A near-perfect typo still ranks below a loose literal match.
        Assert.assertTrue(score("settimgs", "settings") < score("stg", "settings"))
    }

    @Test
    fun primaryFieldOutranksIdenticalSecondaryField() {
        val primary = ResultScore.from("chrome", primaryFields = listOf("chrome"))
        val secondary = ResultScore.from("chrome", secondaryFields = listOf("chrome"))
        Assert.assertTrue(primary > secondary)
    }

    @Test
    fun noFieldsIsNotAMatch() {
        Assert.assertEquals(ResultScore.Zero, ResultScore.from("chrome"))
    }

    @Test
    fun toleratesTyposInsideLongerLabels() {
        // Jaro-Winkler compares the whole label, so these rely on the edit distance to one word.
        Assert.assertTrue(matches("gooble", "google chrome"))
        Assert.assertTrue(matches("chorme", "google chrome"))
        Assert.assertTrue(matches("googgle", "google"))
    }

    @Test
    fun fewerTyposRankHigher() {
        Assert.assertTrue(score("calendar", "calender") > score("calendar", "celender"))
    }

    @Test
    fun typoToleranceGrowsWithQueryLength() {
        Assert.assertEquals(0, ResultScore.maxTyposFor(1))
        Assert.assertEquals(0, ResultScore.maxTyposFor(3))
        Assert.assertEquals(1, ResultScore.maxTyposFor(4))
        Assert.assertEquals(2, ResultScore.maxTyposFor(7))
        Assert.assertEquals(ResultScore.MaxTypos, ResultScore.maxTyposFor(20))

        Assert.assertFalse(matches("lin", "lane"))
    }

    @Test
    fun picksTheBestOfSeveralQuerySpellings() {
        val best = ResultScore.from(
            queries = listOf("nonsense", "rain"),
            primaryFields = listOf("rain"),
        )
        Assert.assertEquals(1f, best.score, 0f)
        Assert.assertEquals(
            ResultScore.Zero,
            ResultScore.from(queries = emptyList(), primaryFields = listOf("rain")),
        )
    }

    @Test
    fun reusedRowBuffersDoNotLeakBetweenCalls() {
        // Scoring against a long term grows the internal edit distance buffers. A shorter term
        // scored afterwards must not see anything left over from the longer one.
        score("gooble", "google chrome canary developer build")
        val reused = score("gooble", "google")

        var fresh = 0f
        val thread = Thread { fresh = score("gooble", "google") }
        thread.start()
        thread.join()

        Assert.assertEquals(fresh, reused, 0f)
    }
}
