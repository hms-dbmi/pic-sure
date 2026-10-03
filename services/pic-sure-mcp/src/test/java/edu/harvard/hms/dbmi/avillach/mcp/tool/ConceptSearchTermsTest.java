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
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Covers {@code search_concepts} with several {@code terms} against a mock gateway that accepts the parallel requests in any order: every
 * request replays the caller's headers, pages merge in the given term order with each term's own order kept, duplicates collapse into one
 * concept that names every matching term, the merged list is capped with a {@code truncated} flag, a failed term becomes a warning, and the
 * argument rules that hold before any request is sent.
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
    void everyParallelTermRequestReplaysTheCallerHeaders() {
        for (String term : List.of("blood pressure", "bp", "hypertension")) {
            server.expect(once(), requestTo(GATEWAY + "/dictionary/concepts?page_number=1&page_size=5")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", BEARER)).andExpect(header("X-PICSURE-API-Key", "caller-api-key"))
                .andExpect(header("X-Request-Id", "req-9")).andExpect(header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
                .andExpect(content().json("{\"facets\":[],\"search\":\"" + term + "\",\"consents\":[]}", true))
                .andRespond(page(0, List.of()));
        }

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("blood pressure", "bp", "hypertension"), 1, 5);

        server.verify();
        assertThat(result.terms()).containsExactly("blood pressure", "bp", "hypertension");
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(5);
    }

    @Test
    void termsInterleaveInTheGivenOrderAndDuplicatesNameEveryMatchingTerm() {
        expectTerm("blood pressure", page(40, concepts("A", "B", "C")));
        expectTerm("bp", page(7, concepts("D", "A")));
        expectTerm("hypertension", page(3, concepts("B", "E")));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("blood pressure", "bp", "hypertension"), null, null);

        server.verify();
        assertThat(result.concepts()).extracting(ConceptSummary::conceptPath).containsExactly("A", "D", "B", "E", "C");
        assertThat(matched(result, "A")).containsExactly("blood pressure", "bp");
        assertThat(matched(result, "B")).containsExactly("blood pressure", "hypertension");
        assertThat(matched(result, "C")).containsExactly("blood pressure");
        assertThat(matched(result, "D")).containsExactly("bp");
        assertThat(matched(result, "E")).containsExactly("hypertension");
        assertThat(result.total()).isEqualTo(50);
        assertThat(result.query()).isNull();
        assertThat(result.page()).isZero();
        assertThat(result.pageSize()).isEqualTo(ConceptSearchTool.DEFAULT_PAGE_SIZE);
        assertThat(result.truncated()).isFalse();
        assertThat(result.warnings()).isNull();
    }

    @Test
    void theMergedListIsCappedAtTheMaximumPageSizeWithTruncatedSet() {
        expectTerm("alpha", page(30, IntStream.range(0, 25).mapToObj(i -> "a" + i).toList()));
        expectTerm("beta", page(30, IntStream.range(0, 25).mapToObj(i -> "b" + i).toList()));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("alpha", "beta"), 0, 25);

        assertThat(result.concepts()).hasSize(ConceptSearchTool.MAX_PAGE_SIZE);
        assertThat(result.concepts()).extracting(ConceptSummary::conceptPath).startsWith("a0", "b0", "a1", "b1").endsWith("a12");
        assertThat(result.truncated()).isTrue();
        assertThat(result.total()).isEqualTo(60);
    }

    @Test
    void exactlyTheCapAfterDeduplicationIsNotTruncated() {
        List<String> shared = IntStream.range(0, 25).mapToObj(i -> "c" + i).toList();
        expectTerm("alpha", page(25, shared));
        expectTerm("beta", page(25, shared));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("alpha", "beta"), 0, 25);

        assertThat(result.concepts()).hasSize(25).allSatisfy(c -> assertThat(c.matchedTerms()).containsExactly("alpha", "beta"));
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aFailingTermBecomesAWarningAndTheOtherTermsStillReturn() {
        expectTerm("blood pressure", page(2, concepts("A", "B")));
        server.expect(once(), content().json("{\"search\":\"bp\"}"))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(LEAK).contentType(MediaType.TEXT_PLAIN));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("blood pressure", "bp"), null, null);

        server.verify();
        assertThat(result.concepts()).extracting(ConceptSummary::conceptPath).containsExactly("A", "B");
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.warnings()).containsExactly("Term 'bp' failed: The dictionary is unavailable. Try again shortly.");
        assertThat(result.toString()).doesNotContain(LEAK);
    }

    @Test
    void whenEveryTermFailsTheToolFails() {
        server.expect(once(), content().json("{\"search\":\"alpha\"}")).andRespond(withStatus(HttpStatus.FORBIDDEN).body(LEAK));
        server.expect(once(), content().json("{\"search\":\"beta\"}")).andRespond(withStatus(HttpStatus.FORBIDDEN).body(LEAK));

        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("alpha", "beta"), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("The caller is not authorized for the dictionary.").hasNoCause();
        server.verify();
    }

    @Test
    void aRepeatedTermIsSearchedOnce() {
        expectTerm("sex", page(1, concepts("S")));

        ConceptSearchResult result = search.searchConcepts(context, null, List.of("sex", "sex"), null, null);

        server.verify();
        assertThat(result.terms()).containsExactly("sex");
        assertThat(matched(result, "S")).containsExactly("sex");
    }

    @Test
    void queryAndTermsTogetherOrNeitherIsRejectedWithoutCallingTheGateway() {
        assertThatThrownBy(() -> search.searchConcepts(context, "sex", List.of("bp"), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'query' or 'terms', not both.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, null, null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'query' or 'terms'.");
        assertThatThrownBy(() -> search.searchConcepts(context, " ", List.of(), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Give either 'query' or 'terms'.");
        server.verify();
    }

    @Test
    void badTermsAreRejectedWithoutCallingTheGateway() {
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("a", "b", "c", "d", "e", "f"), null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms' takes at most 5 terms.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("bp", " "), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[1]' is required.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, Arrays.asList("bp", null), null, null))
            .isInstanceOf(ToolFailure.class).hasMessage("Argument 'terms[1]' is required.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("x".repeat(501)), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[0]' must be at most 500 characters.");
        assertThatThrownBy(() -> search.searchConcepts(context, null, List.of("a\nb"), null, null)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'terms[0]' must not contain control characters.");
        server.verify();
    }

    @Test
    void aQuerySearchLeavesTheTermFieldsOut() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts?page_number=0&page_size=10")).andRespond(page(1, concepts("A")));

        ConceptSearchResult result = search.searchConcepts(context, "sex", null, null, null);

        assertThat(result.query()).isEqualTo("sex");
        assertThat(result.terms()).isNull();
        assertThat(result.truncated()).isNull();
        assertThat(result.warnings()).isNull();
        assertThat(result.concepts()).singleElement().satisfies(c -> assertThat(c.matchedTerms()).isNull());
    }

    private void expectTerm(String term, ResponseCreator response) {
        server.expect(once(), content().json("{\"search\":\"" + term + "\"}")).andRespond(response);
    }

    private static ResponseCreator page(long total, List<String> paths) {
        String content =
            paths.stream().map(p -> "{\"type\":\"Categorical\",\"conceptPath\":\"" + p + "\",\"dataset\":\"phs1\",\"values\":[\"x\"]}")
                .collect(Collectors.joining(","));
        return withSuccess("{\"content\":[" + content + "],\"totalElements\":" + total + "}", MediaType.APPLICATION_JSON);
    }

    private static List<String> concepts(String... paths) {
        return new ArrayList<>(List.of(paths));
    }

    private static List<String> matched(ConceptSearchResult result, String path) {
        return result.concepts().stream().filter(c -> path.equals(c.conceptPath())).findFirst().orElseThrow().matchedTerms();
    }
}
