package edu.harvard.dbmi.avillach.dictionary.filter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FilterProcessorTest {

    @Test
    void shouldReturnNullForNullInput() {
        Assertions.assertNull(FilterProcessor.sanitizeSearch(null));
    }

    @Test
    void shouldReturnEmptyForEmptyInput() {
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch(""));
    }

    @Test
    void shouldReturnEmptyForWhitespaceOnly() {
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("   "));
    }

    @Test
    void shouldStripTsqueryOperators() {
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("&"));
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("|"));
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("!"));
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch(":*"));
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("()"));
        Assertions.assertEquals("", FilterProcessor.sanitizeSearch("!@#$%"));
    }

    @Test
    void shouldPreserveAlphanumericWords() {
        Assertions.assertEquals("Asthma", FilterProcessor.sanitizeSearch("Asthma"));
        Assertions.assertEquals("ear infection", FilterProcessor.sanitizeSearch("ear infection"));
        Assertions.assertEquals("HbA1c", FilterProcessor.sanitizeSearch("HbA1c"));
    }

    @Test
    void shouldStripSpecialCharsAndPreserveWords() {
        Assertions.assertEquals("blood pressure", FilterProcessor.sanitizeSearch("blood & pressure"));
        Assertions.assertEquals("C reactive protein", FilterProcessor.sanitizeSearch("C-reactive protein"));
        Assertions.assertEquals("BMI 30", FilterProcessor.sanitizeSearch("BMI > 30"));
    }

    @Test
    void shouldTrimAndCollapseWhitespace() {
        Assertions.assertEquals("Asthma", FilterProcessor.sanitizeSearch("  Asthma  "));
        Assertions.assertEquals("ear infection", FilterProcessor.sanitizeSearch("  ear   infection  "));
    }

    @Test
    void shouldPreserveUnicodeLetters() {
        Assertions.assertEquals("café", FilterProcessor.sanitizeSearch("café"));
        Assertions.assertEquals("naïve", FilterProcessor.sanitizeSearch("naïve"));
        Assertions.assertEquals("señor", FilterProcessor.sanitizeSearch("señor"));
    }

    @Test
    void shouldCapLongSearchStrings() {
        String longSearch = "a".repeat(300);
        String result = FilterProcessor.sanitizeSearch(longSearch);
        Assertions.assertEquals(200, result.length());
    }
}
