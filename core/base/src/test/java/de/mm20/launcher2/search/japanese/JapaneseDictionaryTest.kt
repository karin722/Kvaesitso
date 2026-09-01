package de.mm20.launcher2.search.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseDictionaryTest {

    @Test
    fun readsWords() {
        assertEquals(listOf("トケイ"), JapaneseDictionary.readingsOfWord("時計"))
        assertEquals(listOf("セッテイ"), JapaneseDictionary.readingsOfWord("設定"))
        assertEquals(listOf("イチバ", "シジョウ"), JapaneseDictionary.readingsOfWord("市場"))
        // Keyed by the folded spelling, which is what a label is looked up as
        assertEquals(listOf("カイモノ", "カイモン"), JapaneseDictionary.readingsOfWord("買イ物"))
    }

    @Test
    fun readsKanji() {
        assertEquals(listOf("デン"), JapaneseDictionary.readingsOfKanji('電'))
        assertEquals(listOf("タク"), JapaneseDictionary.readingsOfKanji('卓'))
        assertTrue(JapaneseDictionary.readingsOfKanji('楽').contains("ラク"))
    }

    @Test
    fun hasNoReadingForWhatItDoesNotKnow() {
        assertEquals(emptyList<String>(), JapaneseDictionary.readingsOfWord("時計の"))
        assertEquals(emptyList<String>(), JapaneseDictionary.readingsOfKanji('a'))
    }
}
