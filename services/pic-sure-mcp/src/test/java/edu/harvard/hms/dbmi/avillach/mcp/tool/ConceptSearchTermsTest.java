package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.DictionaryClient;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.FacetFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Covers {@code search_concepts} with several {@code terms} against a mock gateway: the terms go to the dictionary as one {@code |}-joined
 * search with the term breakdown on, the caller's headers are replayed, the breakdown comes back as {@code matchedTerms} on each concept and
 * {@code termTotals} on the result, and the argument rules that hold before any request is sent.
 */
class ConceptSearchTermsTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String BEARER = "Bearer caller-token";
    private static final String LEAK = "SECRET-DOWNSTREAM-BODY-91be";

    private MockRestServiceServer server;
    private ConceptSearchTool search;
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        search = new ConceptSearchTool(new DictionaryClient(builder.build()));
        context =
            McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(BEARER, "caller-api-key", "req-9", "203.0.113.7")));
    }

    @Test
    void theTermsGoInOneRequestWithTheBreakdownOnAndTheCallerHeadersReplayed() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=1&page_size=5&term_breakdown=true"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", BEARER)).andExpect(header("X-PICSURE-API-Key", "caller-api-key"))
            .andExpect(header("X-Request-Id", "req-9")).andExpect(header("X-Forwarded-For", "203.0.113.7"))
            .andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(content().json("{\"facets\":[],\"search\":\"blood pressure|bp|hypertension\",\"consents\":[]}", true))
            .andRespond(breakdown(0, Map.of(), List.of()));

        ConceptSearchResult result =
            search.searchConcepts(context, null, List.of("blood pressure", "bp", "hypertension"), 1, 5, null);

        server.verify();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(5);
        assertThat(result.search()).isNull();
    }

    @Test
    void eachConceptNamesTheTermsThatMatchItAndTheResultGivesEachTermsTotal() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true")).andRespond(
            breakdown(
                50, linked("blood pressure", 40, "bp", 7, "hypertension", 3),
                List.of(
                    match("A", "blood pressure", "bp"), match("D", "bp"), match("B", "blood pressure", "hypertension"),
                    match("E", "hypertension")
                )
            )
        );

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("blood pressure", "bp", "hypertension"), null, null, null);

        server.verify();
        assertThat(result.terms()).containsExactly("blood pressure", "bp", "hypertension");
        assertThat(result.termTotals()).containsExactly(
            Map.entry("blood pressure", 40L), Map.entry("bp", 7L), Map.entry("hypertension", 3L)
        );
        assertThat(result.total()).isEqualTo(50);
        assertThat(result.pageSize()).isEqualTo(ConceptSearchTool.DEFAULT_PAGE_SIZE);
        assertThat(result.concepts()).extracting(ConceptSummary::conceptPath).containsExactly("A", "D", "B", "E");
        assertThat(matched(result, "A")).containsExactly("blood pressure", "bp");
        assertThat(matched(result, "D")).containsExactly("bp");
        assertThat(matched(result, "B")).containsExactly("blood pressure", "hypertension");
        assertThat(matched(result, "E")).containsExactly("hypertension");
    }

    @Test
    void aTermAtZeroShowsInTheTotals() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true"))
            .andRespond(breakdown(2, linked("sleep", 2, "bmi", 0), List.of(match("A", "sleep"))));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("sleep", "bmi"), null, null, null);

        assertThat(result.termTotals()).containsEntry("bmi", 0L);
    }

    @Test
    void facetsNarrowTheTermsSearchToo() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true"))
            .andExpect(content().json("{\"facets\":[{\"category\":\"dataset_id\",\"name\":\"Nhanes\"}],\"search\":\"a|b\"}"))
            .andRespond(breakdown(0, Map.of(), List.of()));

        search.searchConcepts(context, null, List.of("a", "b"), null, null, List.of(new FacetFilter("dataset_id", "Nhanes")));

        server.verify();
    }

    @Test
    void aDictionaryWithoutTheBreakdownStillAnswersWithoutMatchedTerms() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true"))
            .andRespond(withSuccess("{\"content\":[" + concept("A") + "],\"totalElements\":1}", MediaType.APPLICATION_JSON));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("sex", "gender"), null, null, null);

        assertThat(result.terms()).containsExactly("sex", "gender");
        assertThat(result.termTotals()).isNull();
        assertThat(matched(result, "A")).isNull();
    }

    @Test
    void aRepeatedTermIsSentOnce() {
        server.expect(once(), content().json("{\"search\":\"sex\"}")).andRespond(breakdown(1, linked("sex", 1), List.of(match("S", "sex"))));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("sex", "sex"), null, null, null);

        server.verify();
        assertThat(result.terms()).containsExactly("sex");
    }

    @Test
    void aFailedDictionaryCallFailsTheToolWithoutLeakingTheBody() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true"))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body(LEAK));

        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("alpha", "beta"), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("The caller is not authorized for the dictionary.").hasNoCause();
        server.verify();
    }

    @Test
    void aResponseWithNoBodyFailsTheTool() {
        server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25&term_breakdown=true"))
            .andRespond(DictionaryToolsTest.noBody(302));

        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("alpha", "beta"), null, null, null))
            .isInstanceOf(ToolFailure.class);
        server.verify();
    }

    @Test
    void queryAndTermsTogetherOrNeitherIsRejectedWithoutCallingTheGateway() {
        assertThatThrownBy(() -> search.searchConcepts(context, "sex", List.of("bp"), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'search' or 'terms', not both.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, null, null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'search' or 'terms'.");
        assertThatThrownBy(() -> search.searchConcepts(context, " ", List.of(), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'search' or 'terms'.");
        server.verify();
    }

    @Test
    void badTermsAreRejectedWithoutCallingTheGateway() {
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("a", "b", "c", "d", "e", "f"), null, null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms' takes at most 5 terms.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("bp", " "), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[1]' is required.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, Arrays.asList("bp", null), null, null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms[1]' is required.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("x".repeat(201)), null, null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms[0]' must be at most 200 characters.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("a\nb"), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[0]' must not contain control characters.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("a", "b|c"), null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[1]' must not contain '|'; give each alternative as its own term.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("x".repeat(150), "y".repeat(150)), null, null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms' must total at most 200 characters.");
        server.verify();
    }

    @Test
    void aQuerySearchLeavesTheTermFieldsOut() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=25"))
            .andRespond(withSuccess("{\"content\":[" + concept("A") + "],\"totalElements\":1}", MediaType.APPLICATION_JSON));

        ConceptSearchResult result = search.searchConcepts(context, "sex", null, null, null, null);

        assertThat(result.search()).isEqualTo("sex");
        assertThat(result.terms()).isNull();
        assertThat(result.termTotals()).isNull();
        assertThat(result.concepts()).singleElement().satisfies(c -> assertThat(c.matchedTerms()).isNull());
    }

    private static Map<String, Long> linked(Object... pairs) {
        Map<String, Long> totals = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            totals.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return totals;
    }

    private static String concept(String path) {
        return "{\"type\":\"Categorical\",\"conceptPath\":\"" + path + "\",\"dataset\":\"phs1\",\"values\":[\"x\"]}";
    }

    /** One concept of a breakdown page and the terms that match it. */
    private record Hit(String path, List<String> terms) {
    }

    private static Hit match(String path, String... terms) {
        return new Hit(path, List.of(terms));
    }

    private static ResponseCreator breakdown(long total, Map<String, Long> termTotals, List<Hit> matches) {
        String content = matches.stream().map(m -> concept(m.path())).collect(Collectors.joining(","));
        String matchJson = matches.stream().map(
            m -> "{\"dataset\":\"phs1\",\"conceptPath\":\"" + m.path() + "\",\"terms\":["
                + m.terms().stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(",")) + "]}"
        ).collect(Collectors.joining(","));
        String totalsJson =
            termTotals.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(Collectors.joining(","));
        return withSuccess(
            "{\"content\":[" + content + "],\"totalElements\":" + total + ",\"termTotals\":{" + totalsJson + "},\"matches\":[" + matchJson
                + "]}",
            MediaType.APPLICATION_JSON
        );
    }

    private static List<String> matched(ConceptSearchResult result, String path) {
        return result.concepts().stream().filter(c -> path.equals(c.conceptPath())).findFirst().orElseThrow().matchedTerms();
    }
}
