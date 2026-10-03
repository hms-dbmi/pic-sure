package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Covers {@code get_concepts} against a mock gateway: the batch request it sends, how found concepts map to the same summaries
 * {@code get_concept} returns, how unknown paths land in {@code notFound}, the argument rules that hold before any request is sent, and how
 * a downstream failure becomes a {@link ToolFailure} without the downstream body.
 */
class ConceptsDetailToolTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String BEARER = "Bearer caller-token";
    private static final String LEAK = "SECRET-DOWNSTREAM-BODY-2d7c";
    private static final String AGE = "\\phs1\\age\\";
    private static final String SEX = "\\phs1\\sex\\";
    private static final String UNKNOWN = "\\phs1\\unknown\\";

    private MockRestServiceServer server;
    private ConceptsDetailTool tool;
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        tool = new ConceptsDetailTool(new DictionaryClient(builder.build()));
        context = McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(BEARER, null, "req-3", null)));
    }

    @Test
    void postsThePathsAsAJsonArrayAndMapsTheFoundConceptsLikeGetConcept() {
        String values25 = IntStream.range(0, 25).mapToObj(i -> "\"v" + i + "\"").collect(Collectors.joining(","));
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-Request-Id", "req-3"))
            .andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token")).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("[\"\\\\phs1\\\\age\\\\\",\"\\\\phs1\\\\sex\\\\\",\"\\\\phs1\\\\unknown\\\\\"]", true))
            .andRespond(withSuccess("""
                [{"type":"Continuous","conceptPath":"\\\\phs1\\\\age\\\\","name":"age","display":"Age","dataset":"phs1",
                  "min":1.0,"max":2.0,"children":[{"conceptPath":"c"}],"meta":{"unit":"years","long":"%s"}},
                 {"type":"Categorical","conceptPath":"\\\\phs1\\\\sex\\\\","name":"sex","display":"Sex","dataset":"phs1",
                  "values":[%s]}]""".formatted("y".repeat(400), values25), MediaType.APPLICATION_JSON));

        ConceptsDetailResult result = tool.getConcepts(context, List.of(AGE, SEX, UNKNOWN));

        server.verify();
        assertThat(result.concepts()).extracting(ConceptSummary::conceptPath).containsExactly(AGE, SEX);
        ConceptSummary age = result.concepts().get(0);
        assertThat(age.name()).isEqualTo("age");
        assertThat(age.type()).isEqualTo("continuous");
        assertThat(age.meta()).containsEntry("unit", "years");
        assertThat(age.meta().get("long")).hasSize(ConceptSummary.MAX_META_VALUE_LENGTH);
        assertThat(age.matchedTerms()).isNull();
        ConceptSummary sex = result.concepts().get(1);
        assertThat(sex.values()).hasSize(ConceptSummary.MAX_VALUES);
        assertThat(sex.valuesOmitted()).isEqualTo(5);
        assertThat(result.notFound()).containsExactly(UNKNOWN);
    }

    @Test
    void anEmptyAnswerPutsEveryPathInNotFound() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ConceptsDetailResult result = tool.getConcepts(context, List.of(UNKNOWN, AGE));

        assertThat(result.concepts()).isEmpty();
        assertThat(result.notFound()).containsExactly(UNKNOWN, AGE);
    }

    @Test
    void aRepeatedPathIsSentOnce() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail")).andExpect(content().json("[\"\\\\phs1\\\\age\\\\\"]", true))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(tool.getConcepts(context, List.of(AGE, AGE)).notFound()).containsExactly(AGE);
        server.verify();
    }

    @Test
    void badPathListsAreRejectedWithoutCallingTheGateway() {
        List<String> tooMany = IntStream.range(0, 26).mapToObj(i -> "\\phs1\\c" + i + "\\").toList();
        String bounds = "Argument 'conceptPaths' must hold 1 to 25 concept paths.";

        assertThatThrownBy(() -> tool.getConcepts(context, null)).isInstanceOf(ToolFailure.class).hasMessage(bounds);
        assertThatThrownBy(() -> tool.getConcepts(context, List.of())).isInstanceOf(ToolFailure.class).hasMessage(bounds);
        assertThatThrownBy(() -> tool.getConcepts(context, tooMany)).isInstanceOf(ToolFailure.class).hasMessage(bounds);
        assertThatThrownBy(() -> tool.getConcepts(context, List.of(AGE, " "))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'conceptPaths[1]' is required.");
        assertThatThrownBy(() -> tool.getConcepts(context, Arrays.asList(null, AGE))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'conceptPaths[0]' is required.");
        assertThatThrownBy(() -> tool.getConcepts(context, List.of("x".repeat(2001)))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'conceptPaths[0]' must be at most 2000 characters.");
        assertThatThrownBy(() -> tool.getConcepts(context, List.of("\\a\u0000b\\"))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'conceptPaths[0]' must not contain control characters.");
        server.verify();
    }

    @Test
    void exactlyTheCapIsAccepted() {
        List<String> paths = IntStream.range(0, 25).mapToObj(i -> "\\phs1\\c" + i + "\\").toList();
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(tool.getConcepts(context, paths).notFound()).hasSize(25);
    }

    @Test
    void aDownstreamFailureIsAShortMessageWithoutTheBody() {
        server.expect(requestTo(GATEWAY + "/dictionary/concepts/detail"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(LEAK).contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> tool.getConcepts(context, List.of(AGE))).isInstanceOf(ToolFailure.class)
            .hasMessage("The dictionary is unavailable. Try again shortly.").hasNoCause();
    }
}
