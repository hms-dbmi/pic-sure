package edu.harvard.hms.dbmi.avillach.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
                return ToolResult.success(results.getOrDefault(JSON.readTree(arguments).path("query").asText(), result(0)));
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
            TermBalancedSearch.search("{\"query\":\"smoking | alcohol use\",\"pageSize\":25}", byTerm(results, new ArrayList<>()));

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
            TermBalancedSearch.search("{\"query\":\"sleep | age\"}", byTerm(results, new ArrayList<>())).content()
        );

        assertEquals(0, out.path("termTotals").path("sleep").asInt());
        assertEquals(3, out.path("concepts").size());
    }

    @Test
    void aPathTwoTermsBothMatchedIsListedOnce() throws Exception {
        Map<String, String> results = Map.of("a", result(1, "\\\\x\\\\"), "b", result(1, "\\\\x\\\\"));

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"query\":\"a | b\"}", byTerm(results, new ArrayList<>())).content());

        assertEquals(1, out.path("concepts").size());
    }

    @Test
    void aSingleTermALaterPageOrAFailedTermRunsTheCallAsItCame() {
        Function<String, ToolResult> never = arguments -> {
            throw new AssertionError("should not be called");
        };
        assertNull(TermBalancedSearch.search("{\"query\":\"age\"}", never));
        assertNull(TermBalancedSearch.search("{\"query\":\"a | b\",\"page\":1}", never));
        assertNull(TermBalancedSearch.search("not json", never));
        assertNull(TermBalancedSearch.search("{\"query\":\"a | b\"}", arguments -> ToolResult.failure("down")));
    }
}
