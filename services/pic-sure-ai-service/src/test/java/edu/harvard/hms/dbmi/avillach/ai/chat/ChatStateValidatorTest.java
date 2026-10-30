package edu.harvard.hms.dbmi.avillach.ai.chat;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertThrows(
            IllegalArgumentException.class,
            () -> ChatStateValidator.query(json("{\"phenotypicClause\":{\"operator\":\"XOR\",\"phenotypicClauses\":[]}}"))
        );
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
    void aFilterMustMatchOnValuesOrARangeExactlyWhenItsTypeIsFilter() {
        String path = "\"conceptPath\":\"\\\\demo\\\\age\\\\\"";
        for (String filter : List.of(
            "{\"phenotypicFilterType\":\"FILTER\"," + path + "}",
            "{\"phenotypicFilterType\":\"FILTER\"," + path + ",\"values\":[]}",
            "{\"phenotypicFilterType\":\"REQUIRED\"," + path + ",\"values\":[\"a\"]}",
            "{\"phenotypicFilterType\":\"ANY_RECORD_OF\"," + path + ",\"min\":1}"
        )) {
            assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json("{\"phenotypicClause\":" + filter + "}")), filter);
        }
        for (String filter : List.of(
            "{\"phenotypicFilterType\":\"FILTER\"," + path + ",\"values\":[\"a\"]}",
            "{\"phenotypicFilterType\":\"FILTER\"," + path + ",\"max\":40}",
            "{\"phenotypicFilterType\":\"REQUIRED\"," + path + "}",
            "{\"phenotypicFilterType\":\"ANY_RECORD_OF\"," + path + ",\"values\":[]}"
        )) {
            assertDoesNotThrow(() -> ChatStateValidator.query(json("{\"phenotypicClause\":" + filter + "}")), filter);
        }
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
    void formattingCharactersOrdinaryTextCarriesAreAccepted() {
        for (String text : List.of("می\u200Cخواهم", "family\u200Demoji", "soft\u00ADhyphen")) {
            assertDoesNotThrow(() -> ChatStateValidator.requireValidSearch(text), text);
            assertDoesNotThrow(() -> ChatStateValidator.requireValidFacets(List.of(new FacetSelection("study", text))), text);
        }
        assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.requireValidSearch("tag\uDB40\uDC41smuggled"));
    }

    @Test
    void anOverlongSearchIsRefused() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ChatStateValidator.requireValidSearch("x".repeat(ChatStateValidator.MAX_SEARCH_LENGTH + 1))
        );
    }

    @Test
    void searchMustBeAStringWhenPresent() throws Exception {
        assertDoesNotThrow(() -> ChatStateValidator.searchText(null));
        assertDoesNotThrow(() -> ChatStateValidator.searchText(json("null")));
        assertEquals("bmi", ChatStateValidator.searchText(json("\"bmi\"")));
        for (String bad : List.of("42", "true", "[]", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.searchText(json(bad)), bad);
        }
    }

    @Test
    void aNumberOrBooleanSearchOnTheWireIsRefusedAfterRealJsonBinding() throws Exception {
        for (String search : List.of("42", "4.5", "0", "true", "false")) {
            ChatRequest request = MAPPER.readValue("{\"message\":\"hi\",\"search\":" + search + "}", ChatRequest.class);

            PicsureException refused = assertThrows(PicsureException.class, () -> ChatStateValidator.requireValid(request), search);
            assertTrue(refused.getMessage().contains("search"), refused.getMessage());
        }
        ChatRequest text = MAPPER.readValue("{\"message\":\"hi\",\"search\":\"42\"}", ChatRequest.class);
        assertDoesNotThrow(() -> ChatStateValidator.requireValid(text));
    }

    @Test
    void aQueryFifteenLevelsDeepWithAFilterAtEachLevelIsValidButTooLargeForTheModel() throws Exception {
        String filter = "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\age\\\\\",\"values\":[\"x\"]}";
        String clause = filter;
        for (int level = 0; level < 15; level++) {
            String operator = level % 2 == 0 ? "AND" : "OR";
            clause = "{\"operator\":\"" + operator + "\",\"phenotypicClauses\":[" + filter + "," + clause + "]}";
        }

        ProposeQueryInput query = ChatStateValidator.query(json("{\"phenotypicClause\":" + clause + "}"));

        // 15 nested subqueries, each holding a filter, around one innermost filter.
        assertEquals(31, query.filterCount());
        assertTrue(query.exceedsSizeLimit());
    }

    @Test
    void aNumberOrBooleanWhereTheQueryWantsTextIsRefusedNotCoercedToText() {
        String filter = "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":%s,\"values\":%s}";
        for (String query : List.of(
            "{\"select\":[42]}", "{\"select\":[true]}", "{\"select\":[\"\\\\demo\\\\age\\\\\",1.5]}",
            "{\"phenotypicClause\":" + filter.formatted("12", "[\"x\"]") + "}",
            "{\"phenotypicClause\":" + filter.formatted("\"\\\\demo\\\\age\\\\\"", "[1]") + "}",
            "{\"phenotypicClause\":" + filter.formatted("\"\\\\demo\\\\age\\\\\"", "[false]") + "}"
        )) {
            assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json(query)), query);
        }
    }

    @Test
    void aNullInsideValuesIsRefused() {
        String query = "{\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demo\\\\sex\\\\\",\"values\":[\"f\",null]}}";

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> ChatStateValidator.query(json(query)));
        assertTrue(refused.getMessage().contains("values"), refused.getMessage());
    }

    @Test
    void aNumberOrBooleanFacetCategoryOrNameIsRefusedWhenTheRequestIsBound() {
        for (String facet : List.of("{\"category\":12,\"name\":\"demo\"}", "{\"category\":\"study\",\"name\":true}")) {
            assertThrows(
                com.fasterxml.jackson.databind.exc.MismatchedInputException.class,
                () -> MAPPER.readValue("{\"message\":\"hi\",\"facets\":[" + facet + "]}", ChatRequest.class), facet
            );
        }
        assertDoesNotThrow(
            () -> MAPPER.readValue("{\"message\":\"hi\",\"facets\":[{\"category\":\"study\",\"name\":\"12\"}]}", ChatRequest.class)
        );
    }
}
