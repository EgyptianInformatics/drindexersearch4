package com.drindexer.search

import org.junit.Assert.*
import org.junit.Test

class SearchNormalizerTest {
    @Test fun separatorsAreEquivalent() {
        val q = SearchNormalizer.parse("word1 word2 word3 ext")
        assertTrue(SearchNormalizer.matches("word1.word2-word3_ext", q))
        assertTrue(SearchNormalizer.matches("word1 word2 word3.ext", q))
    }

    @Test fun arabicAlefTatweelAndTashkeelAreTolerant() {
        val q = SearchNormalizer.parse("اسلام")
        assertTrue(SearchNormalizer.matches("إِسـلام.pdf", q))
    }

    @Test fun arabicPersianGlyphsAndDigitsAreEquivalent() {
        val q = SearchNormalizer.parse("كتاب 123")
        assertTrue(SearchNormalizer.matches("کتاب_١٢٣.epub", q))
        assertTrue(SearchNormalizer.matches("كتاب-۱۲۳.epub", q))
    }

    @Test fun yehAndAlefMaqsuraAreEquivalent() {
        val q = SearchNormalizer.parse("علي")
        assertTrue(SearchNormalizer.matches("على.txt", q))
        assertTrue(SearchNormalizer.matches("علی.txt", q))
    }

    @Test fun quotedSearchStaysLiteral() {
        val q = SearchNormalizer.parse("\"word1 word2\"")
        assertTrue(q.exact)
        assertTrue(SearchNormalizer.matches("x word1 word2 y", q))
        assertFalse(SearchNormalizer.matches("word1.word2", q))
    }

    @Test fun ordinarySearchUsesAndSemantics() {
        val q = SearchNormalizer.parse("alpha gamma")
        assertTrue(SearchNormalizer.matches("gamma-beta-alpha.txt", q))
        assertFalse(SearchNormalizer.matches("alpha-only.txt", q))
    }
}
