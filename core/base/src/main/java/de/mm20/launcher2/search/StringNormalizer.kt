package de.mm20.launcher2.search

interface StringNormalizer {
    /**
     * A unique identifier for the normalization algorithm. Two normalizers that share the same ID must
     * return the same normalized string for the same input.
     */
    val id: String

    fun normalize(input: String): String

    /**
     * All spellings [input] should be matched by. The first entry is always [normalize], further
     * entries are alternative spellings, for example the Japanese reading of a latin label.
     *
     * A query and a result match if *any* of their variants match, so both sides of a search have
     * to be normalized with this method for the alternative spellings to be of any use.
     */
    fun normalizeVariants(input: String): List<String> = listOf(normalize(input))
}
