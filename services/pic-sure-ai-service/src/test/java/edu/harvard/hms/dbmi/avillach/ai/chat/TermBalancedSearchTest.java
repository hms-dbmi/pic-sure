package edu.harvard.hms.dbmi.avillach.ai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.ai.mcp.ToolResult;

class TermBalancedSearchTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A concept as the OR search returns it: its path and the terms that matched it. */
    private static String concept(String path, String... terms) {
        return "{\"conceptPath\":\"" + path + "\",\"type\":\"continuous\",\"matchedTerms\":[\"" + String.join("\",\"", terms) + "\"]}";
    }

    /** One page of an OR search; {@code termTotals} is the JSON object of per-term totals. */
    private static String page(int total, String termTotals, String... concepts) {
        return "{\"terms\":[],\"page\":0,\"pageSize\":50,\"total\":" + total + ",\"concepts\":[" + String.join(",", concepts)
            + "],\"termTotals\":" + termTotals + "}";
    }

    /** Answers each page number with the page given for it, and an empty page past those. */
    private static Function<String, ToolResult> pages(List<String> calls, String... byPage) {
        return arguments -> {
            calls.add(arguments);
            try {
                int number = JSON.readTree(arguments).path("page").asInt();
                return ToolResult.success(number < byPage.length ? byPage[number] : page(0, "{}"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static List<String> newCalls() {
        return Collections.synchronizedList(new ArrayList<>());
    }

    @Test
    void aTermWithManyMatchesCannotCrowdOutAnotherTermOnTheFirstPage() throws Exception {
        String[] concepts = new String[42];
        for (int i = 0; i < 40; i++) {
            concepts[i] = concept("\\\\s\\\\smoking " + i + "\\\\", "smoking");
        }
        concepts[40] = concept("\\\\a\\\\drink per day\\\\", "alcohol use");
        concepts[41] = concept("\\\\a\\\\days drink in year\\\\", "alcohol use");
        List<String> calls = newCalls();

        ToolResult merged = TermBalancedSearch.search(
            "{\"search\":\"smoking | alcohol use\",\"pageSize\":25}", pages(calls, page(42, "{\"smoking\":40,\"alcohol use\":2}", concepts))
        );

        JsonNode out = JSON.readTree(merged.content());
        assertEquals(25, out.path("concepts").size());
        assertTrue(merged.content().contains("drink per day") && merged.content().contains("days drink in year"));
        assertEquals(40, out.path("termTotals").path("smoking").asInt());
        assertEquals(2, out.path("termTotals").path("alcohol use").asInt());
        assertEquals(42, out.path("total").asInt(), "the dictionary's count of concepts matching any term");
        assertEquals(1, calls.size(), "42 matches fit one page");
    }

    @Test
    void aTermThatMatchedNothingIsReportedAtZeroAndAnotherTermFillsTheSpace() throws Exception {
        String result = page(
            3, "{\"sleep\":0,\"age\":3}", concept("\\\\d\\\\a\\\\", "age"), concept("\\\\d\\\\b\\\\", "age"),
            concept("\\\\d\\\\c\\\\", "age")
        );

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"search\":\"sleep | age\"}", pages(newCalls(), result)).content());

        assertEquals(0, out.path("termTotals").path("sleep").asInt());
        assertEquals(3, out.path("concepts").size());
    }

    @Test
    void aPathTwoTermsBothMatchedIsListedOnce() throws Exception {
        String result = page(1, "{\"a\":1,\"b\":1}", concept("\\\\x\\\\", "a", "b"));

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"search\":\"a | b\"}", pages(newCalls(), result)).content());

        assertEquals(1, out.path("concepts").size());
        assertEquals(1, out.path("total").asInt());
    }

    @Test
    void searchAndTermsAreBothSentAsOneTermsSearchAtTheLargestPageSize() throws Exception {
        String result = page(2, "{\"sex\":1,\"age\":1}", concept("\\\\d\\\\sex\\\\", "sex"), concept("\\\\d\\\\age\\\\", "age"));
        List<String> fromTerms = newCalls();
        List<String> fromSearch = newCalls();

        JsonNode out = JSON.readTree(TermBalancedSearch.search("{\"terms\":[\"sex\",\"age\"]}", pages(fromTerms, result)).content());
        TermBalancedSearch.search("{\"search\":\"sex | age\"}", pages(fromSearch, result));

        assertEquals(2, out.path("concepts").size());
        for (List<String> calls : List.of(fromTerms, fromSearch)) {
            JsonNode sent = JSON.readTree(calls.getFirst());
            assertEquals(List.of("sex", "age"), List.of(sent.path("terms").get(0).asText(), sent.path("terms").get(1).asText()));
            assertTrue(sent.path("search").isMissingNode(), "search and terms are never both sent: " + sent);
            assertEquals(50, sent.path("pageSize").asInt());
        }
    }

    @Test
    void aResultThatHoldsOnlyTheBestMatchesSaysSoAndOneThatHoldsAllDoesNot() throws Exception {
        String[] firstPage = new String[50];
        for (int i = 0; i < firstPage.length; i++) {
            firstPage[i] = concept("\\\\s\\\\smoking " + i + "\\\\", "smoking");
        }
        String arguments = "{\"search\":\"smoking | alcohol use\",\"pageSize\":10}";

        JsonNode truncated = JSON.readTree(
            TermBalancedSearch.search(arguments, pages(newCalls(), page(5000, "{\"smoking\":4990,\"alcohol use\":10}", firstPage)))
                .content()
        );
        JsonNode whole = JSON.readTree(
            TermBalancedSearch.search(arguments, pages(newCalls(), page(50, "{\"smoking\":40,\"alcohol use\":10}", firstPage))).content()
        );

        assertEquals(TermBalancedSearch.TRUNCATED_NOTE.formatted(300, 5000), truncated.path("note").asText());
        assertTrue(whole.path("note").isMissingNode());
    }

    @Test
    void theRestOfTheMatchesAreFetchedAPageAtATimeSoALaterPageCanSurfaceATerm() throws Exception {
        String[] firstPage = new String[50];
        for (int i = 0; i < firstPage.length; i++) {
            firstPage[i] = concept("\\\\s\\\\smoking " + i + "\\\\", "smoking");
        }
        List<String> calls = newCalls();

        JsonNode out = JSON.readTree(
            TermBalancedSearch.search(
                "{\"search\":\"smoking | alcohol use\",\"pageSize\":10}",
                pages(
                    calls, page(120, "{\"smoking\":118,\"alcohol use\":2}", firstPage), page(120, "{}"),
                    page(
                        120, "{}", concept("\\\\a\\\\drink per day\\\\", "alcohol use"),
                        concept("\\\\a\\\\days drink in year\\\\", "alcohol use")
                    )
                )
            ).content()
        );

        assertEquals(3, calls.size(), "120 matches at 50 a page");
        assertEquals(List.of(0, 1, 2), calls.stream().map(call -> pageNumber(call)).sorted().toList());
        assertTrue(out.path("concepts").toString().contains("drink per day"), out.toString());
        assertEquals(10, out.path("concepts").size());
        assertEquals(120, out.path("total").asInt());
    }

    @Test
    void aSearchWithMoreMatchesThanThePageCapCostsTheFirstPagePlusTheCappedExtraPages() throws Exception {
        List<String> calls = newCalls();
        String first = page(493 * 50, "{\"a\":1,\"b\":1}", concept("\\\\x\\\\", "a"), concept("\\\\y\\\\", "b"));

        assertNotNull(TermBalancedSearch.search("{\"search\":\"a | b\"}", pages(calls, first, first, first, first, first, first, first)));

        assertEquals(1 + TermBalancedSearch.MAX_EXTRA_PAGES, calls.size());
    }

    @Test
    void aTermsListOverTheToolsLimitIsPassedThroughForTheToolToRefuse() {
        Function<String, ToolResult> never = arguments -> {
            throw new AssertionError("should not be called");
        };
        assertNull(TermBalancedSearch.search("{\"terms\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\",\"k\"]}", never));
        assertNotNull(
            TermBalancedSearch
                .search("{\"terms\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\",\"j\"]}", pages(newCalls(), page(0, "{}")))
        );
    }

    @Test
    void aSingleTermALaterPageAFailedPageOrADictionaryWithoutTheBreakdownRunsTheCallAsItCame() {
        Function<String, ToolResult> never = arguments -> {
            throw new AssertionError("should not be called");
        };
        assertNull(TermBalancedSearch.search("{\"search\":\"age\"}", never));
        assertNull(TermBalancedSearch.search("{\"query\":\"a | b\"}", never));
        assertNull(TermBalancedSearch.search("{\"search\":\"a | b\",\"page\":1}", never));
        assertNull(TermBalancedSearch.search("not json", never));
        assertNull(TermBalancedSearch.search("{\"search\":\"a | b\"}", arguments -> ToolResult.failure("down")));
        // A later page that fails leaves the whole call to run as it came.
        String full = page(120, "{\"a\":60,\"b\":60}", concept("\\\\x\\\\", "a"));
        assertNull(
            TermBalancedSearch.search(
                "{\"search\":\"a | b\"}", arguments -> pageNumber(arguments) == 0 ? ToolResult.success(full) : ToolResult.failure("down")
            )
        );
        // No matchedTerms on any concept, as from a dictionary without the term breakdown.
        String bare = "{\"page\":0,\"total\":1,\"concepts\":[{\"conceptPath\":\"\\\\x\\\\\"}],\"termTotals\":{\"a\":1,\"b\":0}}";
        assertNull(TermBalancedSearch.search("{\"search\":\"a | b\"}", arguments -> ToolResult.success(bare)));
    }

    private static int pageNumber(String arguments) {
        try {
            return JSON.readTree(arguments).path("page").asInt();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
