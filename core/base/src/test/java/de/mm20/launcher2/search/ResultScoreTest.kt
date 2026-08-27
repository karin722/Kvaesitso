package de.mm20.launcher2.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultScoreTest {

    @Test
    fun literalMatchesAreUnchanged() {
        val exact = ResultScore.from("google", primaryFields = listOf("google"))
        assertEquals(0, exact.typos)
        assertTrue(exact.isPrefix)
        assertTrue(exact.isSubstring)
        assertEquals(1f, exact.score, 0.0001f)

        val prefix = ResultScore.from("goog", primaryFields = listOf("google chrome"))
        assertEquals(0, prefix.typos)
        assertTrue(prefix.isPrefix)

        val substring = ResultScore.from("chrome", primaryFields = listOf("google chrome"))
        assertEquals(0, substring.typos)
        assertFalse(substring.isPrefix)
        assertTrue(substring.isSubstring)
    }

    @Test
    fun findsResultsWithTypos() {
        val missingLetter = ResultScore.from("gogle", primaryFields = listOf("google"))
        assertEquals(1, missingLetter.typos)
        assertTrue(missingLetter.isSubstring)
        assertTrue(missingLetter.score >= 0.8f)

        val inTheMiddleOfALabel = ResultScore.from("gogle", primaryFields = listOf("google chrome"))
        assertEquals(1, inTheMiddleOfALabel.typos)
        assertTrue(inTheMiddleOfALabel.score >= 0.8f)

        val extraLetter = ResultScore.from("googgle", primaryFields = listOf("google"))
        assertEquals(1, extraLetter.typos)

        val wrongLetter = ResultScore.from("gooble", primaryFields = listOf("google"))
        assertEquals(1, wrongLetter.typos)
    }

    @Test
    fun swappedLettersAreASingleTypo() {
        val swapped = ResultScore.from("chorme", primaryFields = listOf("chrome"))
        assertEquals(1, swapped.typos)
        assertTrue(swapped.score >= 0.8f)
    }

    @Test
    fun literalMatchesWinOverCorrectedOnes() {
        val literal = ResultScore.from("line", primaryFields = listOf("line"))
        val corrected = ResultScore.from("line", primaryFields = listOf("link"))
        assertTrue(literal.score > corrected.score)

        val oneTypo = ResultScore.from("calendar", primaryFields = listOf("calender"))
        val twoTypos = ResultScore.from("calendar", primaryFields = listOf("celender"))
        assertTrue(oneTypo.score > twoTypos.score)
    }

    @Test
    fun shortQueriesAreMatchedLiterally() {
        assertEquals(0, ResultScore.maxTyposFor(1))
        assertEquals(0, ResultScore.maxTyposFor(3))
        assertEquals(1, ResultScore.maxTyposFor(4))
        assertEquals(2, ResultScore.maxTyposFor(7))
        assertEquals(ResultScore.MaxTypos, ResultScore.maxTyposFor(20))

        val tooShortToCorrect = ResultScore.from("lin", primaryFields = listOf("lane"))
        assertFalse(tooShortToCorrect.isSubstring)
        assertEquals(0, tooShortToCorrect.typos)
    }

    @Test
    fun doesNotMatchUnrelatedResults() {
        val unrelated = ResultScore.from("calculator", primaryFields = listOf("messages"))
        assertFalse(unrelated.isSubstring)
        assertTrue(unrelated.score < 0.8f)
    }

    @Test
    fun theWorstCorrectedMatchStillPassesTheUsualThreshold() {
        val worst = ResultScore(
            isPrefix = true,
            isSubstring = true,
            isPrimary = true,
            similarity = 1f,
            typos = ResultScore.MaxTypos,
        )
        assertTrue(worst.score >= 0.8f)
    }

    @Test
    fun picksTheBestOfSeveralQuerySpellings() {
        val best = ResultScore.from(
            queries = listOf("nonsense", "rain"),
            primaryFields = listOf("rain"),
        )
        assertEquals(0, best.typos)
        assertEquals(1f, best.score, 0.0001f)
    }
}
