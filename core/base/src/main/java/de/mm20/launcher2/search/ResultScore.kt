package de.mm20.launcher2.search

import com.aallam.similarity.JaroWinkler
import de.mm20.launcher2.search.fuzzy.FzfMatcher

/**
 * How well a search result matches a query, between 0 and 1. Higher is better. Any score of at
 * least [MatchThreshold] is a match.
 */
@JvmInline
value class ResultScore(val score: Float) : Comparable<ResultScore> {

    override fun compareTo(other: ResultScore): Int {
        return score.compareTo(other.score)
    }

    companion object {
        /**
         * Scores [query] against the best of [primaryFields] and [secondaryFields]. Queries and
         * fields are expected to be normalized (see [StringNormalizer]) by the caller.
         *
         * A field matches if it contains the query's characters in order (an fzf-style
         * subsequence match, e.g. "ytm" in "yt music"), or failing that, if it is close enough to
         * the query to be a typo of it (Jaro-Winkler similarity, e.g. "settimgs" for "settings").
         *
         * Every match lands in one of three non-overlapping bands, so that the kind of evidence
         * decides the order before the similarity within a band does:
         *
         * - `[0.95, 1]` Strong literal match: the characters are all there, tightly clustered or at
         *   word boundaries. An exact match scores 1.
         * - `[0.90, 0.95)` Weak literal match: the characters are all there, but scattered.
         * - `[0.80, 0.90)` Typo match: some typed character is not in the field at all, so this is
         *   a guess at a correction.
         *
         * All bands sit above [MatchThreshold], where the previous substring-or-similar scorer put
         * its matches too, so the balance against usage weights in ranking is unchanged.
         */
        fun from(
            query: String,
            primaryFields: Iterable<String> = emptyList(),
            secondaryFields: Iterable<String> = emptyList(),
        ): ResultScore {
            // An empty query is a prefix of everything.
            if (query.isEmpty()) return ResultScore(1f)

            val maxScore = FzfMatcher.maxScoreFor(query)
            val bestPrimary = primaryFields.maxOfOrNull { scoreField(query, it, maxScore) } ?: 0f
            val bestSecondary = secondaryFields.maxOfOrNull {
                scoreField(query, it, maxScore) * SecondaryFieldFactor
            } ?: 0f
            return ResultScore(maxOf(bestPrimary, bestSecondary))
        }

        /**
         * Scores a result against multiple spellings of the same query, for example the query as
         * typed and its Japanese reading, and returns the best match.
         */
        fun from(
            queries: Iterable<String>,
            primaryFields: Iterable<String> = emptyList(),
            secondaryFields: Iterable<String> = emptyList(),
        ): ResultScore {
            var best = Zero
            for (query in queries) {
                val score = from(query, primaryFields, secondaryFields)
                if (score > best) {
                    best = score
                    // Nothing can score higher than an exact match.
                    if (best.score >= 1f) return best
                }
            }
            return best
        }

        private fun scoreField(query: String, term: String, maxScore: Int): Float {
            val literal = FzfMatcher.normalizedScore(query, term, maxScore)
            if (literal > 0f) {
                return if (literal >= StrongLiteralThreshold) {
                    lerp(
                        StrongLiteralBand,
                        1f,
                        (literal - StrongLiteralThreshold) / (1f - StrongLiteralThreshold)
                    )
                } else {
                    lerp(WeakLiteralBand, StrongLiteralBand, literal / StrongLiteralThreshold)
                }
            }

            // With only one or two characters, there is nothing to base a correction on.
            if (query.length < MinTypoQueryLength) return 0f

            val similarity = jaroWinklerLocal.get()!!.similarity(query, term).toFloat()
            // Similarity is only 1 for identical strings, which the literal path has already
            // caught, so this stays below WeakLiteralBand.
            val bySimilarity = if (similarity < MatchThreshold) 0f else lerp(
                TypoBand,
                WeakLiteralBand,
                (similarity - MatchThreshold) / (1f - MatchThreshold)
            )

            // Jaro-Winkler compares whole strings, so a typo in one word of a longer label
            // ("gooble" in "google chrome") is caught by the edit distance to the closest part of
            // the label instead.
            val maxTypos = maxTyposFor(query.length)
            if (maxTypos == 0 || query.length - term.length > maxTypos) return bySimilarity
            val typos = editDistance(query, term, limit = maxTypos)
            if (typos > maxTypos) return bySimilarity
            // At least one typo, since the literal path would have caught an exact substring, so
            // this stays below WeakLiteralBand as well.
            val byEditDistance = lerp(
                TypoBand,
                WeakLiteralBand,
                1f - typos.toFloat() / (MaxTypos + 1)
            )
            return maxOf(bySimilarity, byEditDistance)
        }

        /**
         * How many typos are tolerated for a query of the given length. Short queries are matched
         * literally: with only two or three characters to go by, a single correction would match
         * almost anything.
         */
        internal fun maxTyposFor(queryLength: Int): Int = when {
            queryLength <= 3 -> 0
            queryLength <= 6 -> 1
            queryLength <= 10 -> 2
            else -> MaxTypos
        }

        /**
         * Edit distance of [query] to the best matching part of [term], which may start anywhere.
         *
         * Swapping two neighbouring characters counts as a single edit (Damerau-Levenshtein),
         * because that is what a mistyped word usually looks like. Aborts as soon as the distance
         * is known to exceed [limit].
         */
        private fun editDistance(query: String, term: String, limit: Int): Int {
            val n = query.length
            val m = term.length
            if (n == 0) return 0
            val rows = scratchLocal.get()!!.forSize(m + 1)
            var beforePrevious = rows.first
            var previous = rows.second
            var current = rows.third
            for (j in 0..m) previous[j] = 0
            for (i in 1..n) {
                current[0] = i
                var rowMin = current[0]
                val queryChar = query[i - 1]
                for (j in 1..m) {
                    val termChar = term[j - 1]
                    var cost = minOf(
                        previous[j - 1] + if (queryChar == termChar) 0 else 1,
                        previous[j] + 1,
                        current[j - 1] + 1,
                    )
                    if (i > 1 && j > 1 &&
                        queryChar == term[j - 2] && query[i - 2] == termChar
                    ) {
                        cost = minOf(cost, beforePrevious[j - 2] + 1)
                    }
                    current[j] = cost
                    if (cost < rowMin) rowMin = cost
                }
                if (rowMin > limit) return limit + 1
                val recycled = beforePrevious
                beforePrevious = previous
                previous = current
                current = recycled
            }
            var best = previous[0]
            for (j in 1..m) {
                if (previous[j] < best) best = previous[j]
            }
            return best
        }

        /**
         * The three rows [editDistance] alternates between, grown to the longest term seen so far.
         */
        private class Scratch {
            private var rows = Triple(IntArray(0), IntArray(0), IntArray(0))

            fun forSize(size: Int): Triple<IntArray, IntArray, IntArray> {
                if (rows.first.size < size) {
                    rows = Triple(IntArray(size), IntArray(size), IntArray(size))
                }
                return rows
            }
        }

        /**
         * Jaro-Winkler is stateless, but not documented to be thread safe, so every thread that
         * scores results gets its own instance instead of allocating one per call.
         */
        private val jaroWinklerLocal = ThreadLocal.withInitial { JaroWinkler() }

        /**
         * Row buffers for [editDistance]. Scoring a single search runs the algorithm once per
         * result, field and spelling, so the rows are reused instead of being allocated every time.
         */
        private val scratchLocal = ThreadLocal.withInitial { Scratch() }

        /**
         * The highest number of typos tolerated for any query, see [maxTyposFor].
         */
        internal const val MaxTypos = 3

        private fun lerp(start: Float, end: Float, fraction: Float): Float {
            return start + (end - start) * fraction.coerceIn(0f, 1f)
        }

        /**
         * The lowest score that counts as a match.
         */
        internal const val MatchThreshold = 0.8f

        /**
         * Normalized fzf score from which a literal match is considered strong.
         */
        private const val StrongLiteralThreshold = 0.5f

        private const val StrongLiteralBand = 0.95f
        private const val WeakLiteralBand = 0.9f
        private const val TypoBand = MatchThreshold

        private const val MinTypoQueryLength = 3

        /**
         * How much a match on a secondary field is worth relative to the same match on a primary
         * field.
         */
        private const val SecondaryFieldFactor = 0.8f

        val Zero = ResultScore(0f)

        /**
         * No score has been computed yet. Results that carry this are scored lazily when they are
         * ranked, since not every repository scores its own results.
         */
        val Unspecified = ResultScore(Float.NaN)
    }
}

inline val ResultScore.isUnspecified: Boolean
    get() = score.isNaN()
