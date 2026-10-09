package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ChatStateValidator}: the structural bounds on query/facets/search, shared by request input and model output. */
class ChatStateValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void absentAndExplicitNullStateIsValid() throws Exception {
        assertDoesNotThrow(() -> ChatStateValidator.query(null));
        assertDoesNotThrow(() -> ChatStateValidator.query(json("null")));
        assertDoesNotThrow(() -> ChatStateValidator.requireValidFacets(null));
        assertDoesNotThrow(() -> ChatStateValidator.requireValidSearch(null));
    }

    @Test
    void aQueryWithWrongTypedFieldsIsRefused() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json("{\"select\":\"a-string\"}")));
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json("{\"phenotypicClause\":{\"operator\":\"XOR\",\"phenotypicClauses\":[]}}")));
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json("[]")));
    }

    @Test
    void aQueryNestedBeyondTheDepthBoundIsRefusedRatherThanRecursedInto() throws Exception {
        StringBuilder open = new StringBuilder();
        StringBuilder close = new StringBuilder();
        for (int i = 0; i < ProposeQueryInput.MAX_CLAUSE_DEPTH + 1; i++) {
            open.append("{\"operator\":\"AND\",\"phenotypicClauses\":[");
            close.append("]}");
        }
        String leaf = "{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\a\\\\\"}";
        JsonNode tooDeep = json("{\"phenotypicClause\":" + open + leaf + close + "}");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(tooDeep));

        assertTrue(thrown.getMessage().contains("deeply"), thrown.getMessage());
    }

    @Test
    void anOverlongConceptPathIsRefused() throws Exception {
        String path = "x".repeat(ProposeQueryInput.MAX_CONCEPT_PATH_LENGTH + 1);

        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json("{\"select\":[\"" + path + "\"]}")));
    }

    @Test
    void facetCountAndLengthAreBounded() {
        List<FacetSelection> tooMany = java.util.Collections.nCopies(ChatStateValidator.MAX_FACETS + 1, new FacetSelection("a", "b"));
        FacetSelection tooLong = new FacetSelection("a", "b".repeat(ChatStateValidator.MAX_FACET_TEXT_LENGTH + 1));

        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.requireValidFacets(tooMany));
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.requireValidFacets(List.of(tooLong)));
    }

    @Test
    void ordinarySearchTermsAreAccepted() {
        for (String term : List.of("bmi", "body mass index", "blood pressure (systolic)", "HbA1c > 6", "café", "血压", "")) {
            assertDoesNotThrow(() -> ChatStateValidator.requireValidSearch(term), term);
        }
    }

    @Test
    void controlAndInvisibleFormattingCharactersAreRefusedInSearchAndFacets() {
        for (String harmful : List.of("a\nb", "a\tb", "nul\u0000byte", "esc\u001b[31m", "zero​width", "bidi‮override")) {
            assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.requireValidSearch(harmful), harmful);
            assertThrows(
                IllegalArgumentException.class, () -> ChatStateValidator.requireValidFacets(List.of(new FacetSelection("study", harmful))),
                harmful
            );
        }
    }

    @Test
    void anOverlongSearchIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.requireValidSearch("x".repeat(ChatStateValidator.MAX_SEARCH_LENGTH + 1)));
    }
}
