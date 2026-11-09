package edu.harvard.hms.dbmi.avillach.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

class TermBalancedSearchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String result(int total, String... paths) {
        StringBuilder concepts = new StringBuilder();
        for (String path : paths) {
            concepts.append(concepts.isEmpty() ? "" : ",").append("{\"conceptPath\":\"").append(path).append("\",\"type\":\"continuous\"}");
        }
        return "{\"page\":0,\"total\":" + total + ",\"concepts\":[" + concepts + "]}";
    }

    private static Function<String, ToolResult> byTerm(Map<String, String> results, List<String> calls) {
        return arguments -> {
            calls.add(arguments);
            try {
                return ToolResult.success(results.getOrDefault(JSON.readTree(arguments).path("search").asText(), result(0)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    @Test
    void aTermWithManyMatchesCannotCrowdOutAnotherTermOnTheFirstPage() throws Exception {
        String[] smoking = new String[40];
        for (int i = 0; i < smoking.length; i++) {
            smoking[i] = "\\\\s\\\\smoking " + i + "\\\\";
        }
        Map<String, String> results =
            Map.of("smoking", result(40, smoking), "alcohol use", result(2, "\\\\a\\\\drink per day\\\\", "\\\\a\\\\days drink in year\\\\"));

        ToolResult merged =
            TermBalancedSearch.search("{\"search\":\"smoking | alcohol use\",\"pageSize\":25}", byTerm(results, Collections.synchronizedList(new ArrayList<>())));

        JsonNode out = JSON.readTree(merged.content());
        assertEquals(25, out.path("concepts").size());
        assertTrue(merged.content().contains("drink per day") && merged.content().contains("days drink in year"));
        assertEquals(40, out.path("termTotals").path("smoking").asInt());
        assertEquals(2, out.path("termTotals").path("alcohol use").asInt());
    }

    @Test
    void aTermThatMatchedNothingIsReportedAtZeroAndAnotherTermFillsTheSpace() throws Exception {
        Map<String, String> results = Map.of("sleep", result(0), "age", result(3, "\\\\d\\\\a\\\\", "\\\\d\\\\b\\\\", "\\\\d\\\\c\\\\"));

        JsonNode out = JSON.readTree(
            TermBalancedSearch.search("{\"search\":\"sleep | age\"}", byTerm(results, Collections.synchronizedList(new ArrayList<>()))).content()
        );

        assertEquals(0, out.path("termTotals").path("sleep").asInt());
        assertEquals(3, out.path("concepts").size());
    }

    @Test
    void aPathTwoTermsBothMatchedIsListedOnce() throws Exception {
        Map<String, String> results = Map.of("a", result(1, "\\\\x\\\\"), "b", result(1, "\\\\x\\\\"));

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"search\":\"a | b\"}", byTerm(results, Collections.synchronizedList(new ArrayList<>()))).content());

        assertEquals(1, out.path("concepts").size());
    }

    @Test
    void aTermsListIsSearchedOneTermAtATime() throws Exception {
        Map<String, String> results = Map.of("sex", result(1, "\\\\d\\\\sex\\\\"), "age", result(1, "\\\\d\\\\age\\\\"));
        List<String> calls = Collections.synchronizedList(new ArrayList<>());

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"terms\":[\"sex\",\"age\"]}", byTerm(results, calls)).content());

        assertEquals(2, out.path("concepts").size());
        assertEquals(1, out.path("termTotals").path("sex").asInt());
        assertTrue(calls.stream().noneMatch(call -> call.contains("terms")), "each term is sent as search, not terms: " + calls);
    }

    @Test
    void aTermsListTheToolWouldRefuseIsPassedThroughButTheSameTermsInSearchAreSplit() {
        // Split here, seven terms worked on page 0 and the model's page 1 came back "Argument 'terms' takes at most 5 terms."
        Function<String, ToolResult> never = arguments -> {
            throw new AssertionError("should not be called");
        };
        assertNull(TermBalancedSearch.search("{\"terms\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}", never));
        assertNotNull(TermBalancedSearch.search("{\"search\":\"a | b | c | d | e | f\"}", arguments -> ToolResult.success(result(0))));
    }

    @Test
    void aSingleTermALaterPageOrAFailedTermRunsTheCallAsItCame() {
        Function<String, ToolResult> never = arguments -> {
            throw new AssertionError("should not be called");
        };
        assertNull(TermBalancedSearch.search("{\"search\":\"age\"}", never));
        assertNull(TermBalancedSearch.search("{\"query\":\"a | b\"}", never));
        assertNull(TermBalancedSearch.search("{\"search\":\"a | b\",\"page\":1}", never));
        assertNull(TermBalancedSearch.search("not json", never));
        assertNull(TermBalancedSearch.search("{\"search\":\"a | b\"}", arguments -> ToolResult.failure("down")));
    }
}
