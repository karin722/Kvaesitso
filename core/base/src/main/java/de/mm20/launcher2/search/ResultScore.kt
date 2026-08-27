package de.mm20.launcher2.search

import com.aallam.similarity.JaroWinkler

@JvmInline
value class ResultScore private constructor(private val packed: Long) : Comparable<ResultScore> {
    constructor(
        isPrefix: Boolean,
        isSubstring: Boolean,
        isPrimary: Boolean,
        similarity: Float,
        typos: Int,
    ) : this(
        (similarity.toRawBits().toLong() and 0xffffffffL) or
                (if (isPrefix) (1L shl 32) else 0) or
                (if (isSubstring) (1L shl 33) else 0) or
                (if (isPrimary) (1L shl 34) else 0) or
                (typos.coerceIn(0, MaxTypos).toLong() shl 35)
    )

    /**
     * Whether the query is a prefix of the result. May be off by up to [typos] characters.
     */
    val isPrefix: Boolean
        get() = (packed and (1L shl 32)) != 0L

    /**
     * Whether the query is a substring of the result. May be off by up to [typos] characters.
     */
    val isSubstring: Boolean
        get() = (packed and (1L shl 33)) != 0L

    /**
     * Whether the query was matched against a primary field.
     */
    val isPrimary: Boolean
        get() = (packed and (1L shl 34)) != 0L

    /**
     * How many characters had to be inserted, removed or replaced to match the query against the
     * result. Zero for a literal match.
     */
    val typos: Int
        get() = ((packed ushr 35) and 0xfL).toInt()

    /**
     * The Jaro-Winkler similarity between the query and the result.
     */
    val similarity: Float
        get() = Float.fromBits((packed and 0xffffffffL).toInt())

    /**
     * A total score for the result, combining the similarity with additional factors.
     * The score is normalized to be between 0 and 1.
     */
    val score: Float
        get() = (similarity + (if (isPrefix) 0.2f else 0f) + (if (isSubstring) 0.8f else 0f))
            .coerceIn(0f, 1f) *
                (if (isPrimary) 1f else 0.8f) *
                (1f - TypoPenalty * typos)

    override fun compareTo(other: ResultScore): Int {
        return score.compareTo(other.score)
    }

    companion object {
        /**
         * The highest number of typos that can be packed into a score. A result matched with the
         * highest number of typos still scores above the 0.8 threshold that most providers use.
         */
        const val MaxTypos = 3

        /**
         * How much a single typo costs, relative to a literal match. Small enough that a literal
         * match always wins over a corrected one, large enough to break ties.
         */
        private const val TypoPenalty = 0.06f

        /**
         * How many typos are tolerated for a query of the given length. Short queries are matched
         * literally: with only two or three characters to go by, a single correction would match
         * almost anything.
         */
        fun maxTyposFor(queryLength: Int): Int = when {
            queryLength <= 3 -> 0
            queryLength <= 6 -> 1
            queryLength <= 10 -> 2
            else -> MaxTypos
        }

        fun from(
            query: String,
            primaryFields: Iterable<String> = emptyList(),
            secondaryFields: Iterable<String> = emptyList(),
            maxTypos: Int = maxTyposFor(query.length),
        ): ResultScore {
            return from(listOf(query), primaryFields, secondaryFields, maxTypos)
        }

        /**
         * Scores a result against multiple spellings of the same query, for example the query as
         * typed and its Japanese reading, and returns the best match.
         */
        fun from(
            queries: Iterable<String>,
            primaryFields: Iterable<String> = emptyList(),
            secondaryFields: Iterable<String> = emptyList(),
            maxTypos: Int? = null,
        ): ResultScore {
            val jaroWinkler = JaroWinkler()
            var best = Zero
            for (query in queries) {
                val typos = (maxTypos ?: maxTyposFor(query.length)).coerceIn(0, MaxTypos)
                for (term in primaryFields) {
                    val score = score(jaroWinkler, query, term, true, typos)
                    if (score > best) best = score
                }
                for (term in secondaryFields) {
                    val score = score(jaroWinkler, query, term, false, typos)
                    if (score > best) best = score
                }
            }
            return best
        }

        private fun score(
            jaroWinkler: JaroWinkler,
            query: String,
            term: String,
            isPrimary: Boolean,
            maxTypos: Int,
        ): ResultScore {
            val similarity = jaroWinkler.similarity(query, term).toFloat()
            if (query in term) {
                return ResultScore(
                    isPrefix = term.startsWith(query),
                    isSubstring = true,
                    isPrimary = isPrimary,
                    similarity = similarity,
                    typos = 0,
                )
            }
            if (maxTypos == 0 || query.length - term.length > maxTypos) {
                return ResultScore(
                    isPrefix = false,
                    isSubstring = false,
                    isPrimary = isPrimary,
                    similarity = similarity,
                    typos = 0,
                )
            }
            val substringDistance = editDistance(query, term, anchored = false, limit = maxTypos)
            if (substringDistance > maxTypos) {
                return ResultScore(
                    isPrefix = false,
                    isSubstring = false,
                    isPrimary = isPrimary,
                    similarity = similarity,
                    typos = 0,
                )
            }
            val prefixDistance = editDistance(query, term, anchored = true, limit = substringDistance)
            return ResultScore(
                isPrefix = prefixDistance <= substringDistance,
                isSubstring = true,
                isPrimary = isPrimary,
                similarity = similarity,
                typos = substringDistance,
            )
        }

        /**
         * Edit distance of [query] to the best matching part of [term]. If [anchored] is true,
         * that part has to start at the beginning of [term], otherwise it may start anywhere.
         *
         * Swapping two neighbouring characters counts as a single edit (Damerau-Levenshtein),
         * because that is what a mistyped word usually looks like. Aborts as soon as the distance
         * is known to exceed [limit].
         */
        private fun editDistance(query: String, term: String, anchored: Boolean, limit: Int): Int {
            val n = query.length
            val m = term.length
            if (n == 0) return 0
            var beforePrevious = IntArray(m + 1)
            var previous = IntArray(m + 1) { if (anchored) it else 0 }
            var current = IntArray(m + 1)
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

        val Zero = ResultScore(
            isPrefix = false,
            isSubstring = false,
            isPrimary = false,
            similarity = 0f,
            typos = 0,
        )

        val Unspecified = ResultScore(
            isPrefix = false,
            isSubstring = false,
            isPrimary = false,
            similarity = Float.NaN,
            typos = 0,
        )
    }
}

inline val ResultScore.isUnspecified : Boolean
    get() = this == ResultScore.Unspecified
