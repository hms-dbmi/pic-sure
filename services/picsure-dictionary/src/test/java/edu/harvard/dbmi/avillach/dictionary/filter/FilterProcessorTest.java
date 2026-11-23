package edu.harvard.dbmi.avillach.dictionary.filter;

import edu.harvard.dbmi.avillach.dictionary.facet.Facet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

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

    @Test
    void shouldAcceptAnyFacetADictionaryCanHold() {
        List<Facet> facets = List.of(
            new Facet("phs000007", "study_ids_dataset_ids"), new Facet("data_source_genomic", "data_source"),
            new Facet("J45.51 Severe persistent asthma with (acute) exacerbation", "Diagnosis (ICD-10)"),
            new Facet("heart\trate", "data-type"), new Facet("", "")
        );

        Filter actual = new FilterProcessor().processsFilter(new Filter(facets, "", List.of()));

        Assertions.assertTrue(actual.facets().containsAll(facets));
    }

    static Stream<Arguments> malformedFacets() {
        return Stream.of(
            Arguments.of("null facet", null), Arguments.of("null category", new Facet("phs000007", null)),
            Arguments.of("category with U+0000", new Facet("phs000007", "study\u0000ids")),
            Arguments.of("null name", new Facet(null, "study_ids_dataset_ids")),
            Arguments.of("name with U+0000", new Facet("phs\u0000007", "study_ids_dataset_ids"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedFacets")
    void shouldRejectMalformedFacet(String description, Facet facet) {
        Filter filter = new Filter(Arrays.asList(new Facet("PhenX", "nsrr_harmonized"), facet), "", List.of());

        ResponseStatusException e =
            Assertions.assertThrows(ResponseStatusException.class, () -> new FilterProcessor().processsFilter(filter));
        Assertions.assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
    }
}
