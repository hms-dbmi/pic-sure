package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.OpenQueryClient;
import edu.harvard.hms.dbmi.avillach.mcp.query.QueryBinder;
import io.modelcontextprotocol.common.McpTransportContext;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Covers the two count tools against a mock gateway: the result type each one sends whatever the input holds, how {@code select} is
 * handled, how each cross-count body is read and capped, and how downstream failures become {@link ToolFailure}s that carry no downstream
 * text.
 */
class QueryToolsTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String SYNC = GATEWAY + "/hpds/open/query/sync";
    private static final String BEARER = "Bearer caller-token";
    private static final String LEAK = "SECRET-DOWNSTREAM-BODY-7f3a";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String QUERY = """
        {"select":["\\\\phs1\\\\sex\\\\","\\\\phs1\\\\age\\\\"],
         "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"]}}""";

    private MockRestServiceServer server;
    private CountTool count;
    private CrossCountTool crossCount;
    private McpTransportContext context;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        OpenQueryClient client = new OpenQueryClient(builder.build());
        count = new CountTool(client);
        crossCount = new CrossCountTool(client);
        context = McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(BEARER, null, "req-1", null)));
    }

    private static <T> T bind(String json, Class<T> type) throws Exception {
        Map<String, Object> args = JSON.readValue(json, new TypeReference<>() {});
        return QueryBinder.bind(args, type);
    }

    @Test
    void countAlwaysSendsCountAndDropsSelect() throws Exception {
        server.expect(requestTo(SYNC)).andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", BEARER))
            .andExpect(jsonPath("$.query.expectedResultType").value("COUNT")).andExpect(jsonPath("$.query.select").isEmpty())
            .andExpect(jsonPath("$.query.authorizationFilters").isEmpty()).andExpect(jsonPath("$.resourceUUID").doesNotExist())
            .andRespond(withSuccess("1234 ±3", MediaType.APPLICATION_JSON));

        CountResult result = count.handle(context, bind("{\"query\":" + QUERY + "}", CountTool.Input.class));

        server.verify();
        assertThat(result).isEqualTo(new CountResult("1234 ±3", 1234, 3, null, false));
    }

    @Test
    void countReturnsASuppressedCount() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("< 10", MediaType.APPLICATION_JSON));

        assertThat(count.handle(context, bind("{\"query\":{}}", CountTool.Input.class)))
            .isEqualTo(new CountResult("< 10", null, null, 10, true));
    }

    @Test
    void countRequiresAQueryAndSendsNothingWithoutOne() {
        assertThatThrownBy(() -> count.handle(context, new CountTool.Input(null))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'query' is required.");
        server.verify();
    }

    @Test
    void countFailsOnAnEmptyBody() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> count.handle(context, bind("{\"query\":{}}", CountTool.Input.class)))
            .hasMessage("The query service returned no count.");
    }

    @ParameterizedTest
    @EnumSource(CrossCountTool.CrossCountType.class)
    void crossCountSendsTheChosenTypeAndPassesSelectThrough(CrossCountTool.CrossCountType type) throws Exception {
        server.expect(requestTo(SYNC)).andExpect(jsonPath("$.query.expectedResultType").value(type.name()))
            .andExpect(jsonPath("$.query.select", Matchers.contains("\\phs1\\sex\\", "\\phs1\\age\\")))
            .andExpect(jsonPath("$.query.authorizationFilters").isEmpty()).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CrossCountResult result = crossCount
            .handle(context, bind("{\"resultType\":\"" + type.name() + "\",\"query\":" + QUERY + "}", CrossCountTool.Input.class));

        server.verify();
        assertThat(result.resultType()).isEqualTo(type.name());
        assertThat(result.cells()).isEmpty();
    }

    @Test
    void crossCountKeepsTheStudyConsentsCells() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("""
            {"\\\\_studies_consents\\\\":"1234 ±3","\\\\_studies_consents\\\\phs1\\\\":"< 10"}""", MediaType.APPLICATION_JSON));

        CrossCountResult result = crossCount.handle(
            context,
            new CrossCountTool.Input(bind("{\"query\":{}}", CountTool.Input.class).query(), CrossCountTool.CrossCountType.CROSS_COUNT)
        );

        assertThat(result.totalCells()).isEqualTo(2);
        assertThat(result.cellsOmitted()).isNull();
        assertThat(result.withheld()).isNull();
        assertThat(result.cells()).containsExactly(
            new CrossCountResult.Cell("\\_studies_consents\\", null, new CountResult("1234 ±3", 1234, 3, null, false)),
            new CrossCountResult.Cell("\\_studies_consents\\phs1\\", null, new CountResult("< 10", null, null, 10, true))
        );
    }

    @Test
    void crossCountReadsCategoricalCellsFromTheDisplayNotTheCount() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("""
            {"\\\\phs1\\\\sex\\\\":{"Female":{"count":1234,"display":"1234 ±3","variance":3},
                                  "Male":{"count":0,"display":"< 10","variance":9}}}""", MediaType.APPLICATION_JSON));

        CrossCountResult result = crossCount
            .handle(context, bind("{\"resultType\":\"CATEGORICAL_CROSS_COUNT\",\"query\":" + QUERY + "}", CrossCountTool.Input.class));

        assertThat(result.cells()).containsExactly(
            new CrossCountResult.Cell("\\phs1\\sex\\", "Female", new CountResult("1234 ±3", 1234, 3, null, false)),
            new CrossCountResult.Cell("\\phs1\\sex\\", "Male", new CountResult("< 10", null, null, 10, true))
        );
        String json = JSON.writeValueAsString(result);
        assertThat(json).doesNotContain("null").doesNotContain("cellsOmitted").doesNotContain("withheld");
    }

    @Test
    void crossCountCapsItsCells() throws Exception {
        String bins = IntStream.range(0, 130).mapToObj(i -> "\"" + i + "\":{\"count\":20,\"display\":\"20 ±3\",\"variance\":3}")
            .collect(Collectors.joining(","));
        server.expect(requestTo(SYNC)).andRespond(withSuccess("{\"\\\\phs1\\\\age\\\\\":{" + bins + "}}", MediaType.APPLICATION_JSON));

        CrossCountResult result = crossCount
            .handle(context, bind("{\"resultType\":\"CONTINUOUS_CROSS_COUNT\",\"query\":" + QUERY + "}", CrossCountTool.Input.class));

        assertThat(result.totalCells()).isEqualTo(130);
        assertThat(result.cells()).hasSize(CrossCountResult.MAX_CELLS);
        assertThat(result.cellsOmitted()).isEqualTo(30);
        assertThat(result.cells().get(0).category()).isEqualTo("0");
    }

    @Test
    void anEmptyContinuousBodyIsReportedAsWithheld() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        CrossCountResult result = crossCount
            .handle(context, bind("{\"resultType\":\"CONTINUOUS_CROSS_COUNT\",\"query\":" + QUERY + "}", CrossCountTool.Input.class));

        assertThat(result).isEqualTo(new CrossCountResult("CONTINUOUS_CROSS_COUNT", 0, java.util.List.of(), null, true));
        assertThat(JSON.writeValueAsString(result))
            .isEqualTo("{\"resultType\":\"CONTINUOUS_CROSS_COUNT\",\"totalCells\":0,\"cells\":[],\"withheld\":true}");
    }

    @Test
    void anUnreadableCrossCountBodyFailsWithoutEchoingIt() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withSuccess("[\"" + LEAK + "\"]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(
            () -> crossCount.handle(context, bind("{\"resultType\":\"CROSS_COUNT\",\"query\":{}}", CrossCountTool.Input.class))
        ).hasMessage("The query service returned a result the tool could not read.").hasNoCause();
    }

    @Test
    void crossCountRequiresAResultType() throws Exception {
        assertThatThrownBy(() -> crossCount.handle(context, bind("{\"query\":{}}", CrossCountTool.Input.class)))
            .hasMessage("Argument 'resultType' is required.");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource(
        {"400,The query service rejected the query. Check the concept paths and values with search_concepts.",
            "401,The caller is not authorized for open-access counts.", "403,The caller is not authorized for open-access counts.",
            "404,The open query endpoint was not found.", "409,The query service rejected the request.",
            "500,The query service is unavailable. Try again shortly.", "502,The query service is unavailable. Try again shortly."}
    )
    void downstreamFailuresMapToShortMessagesWithoutTheBody(int status, String message) throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withStatus(HttpStatus.valueOf(status)).body(LEAK).contentType(MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> count.handle(context, bind("{\"query\":{}}", CountTool.Input.class))).isInstanceOf(ToolFailure.class)
            .hasMessage(message).hasNoCause();
    }

    @Test
    void anIoFailureMapsToUnavailable() throws Exception {
        server.expect(requestTo(SYNC)).andRespond(withException(new IOException(LEAK)));

        assertThatThrownBy(
            () -> crossCount.handle(context, bind("{\"resultType\":\"CROSS_COUNT\",\"query\":{}}", CrossCountTool.Input.class))
        ).hasMessage("The query service is unavailable. Try again shortly.").hasNoCause();
    }

    @Test
    void descriptionsCarryAWorkedQueryAndSayTheCountsAreObfuscatedAndIgnoreConsents() throws Exception {
        for (String description : new String[] {CountTool.DESCRIPTION, CrossCountTool.DESCRIPTION}) {
            assertThat(description).contains("obfuscated open-access").contains("ignores the caller's consents");
            assertThat(description).doesNotContain(String.valueOf((char) 0x2014)).doesNotContain("-".repeat(2));
            String example = description.substring(description.indexOf("Example arguments: ") + "Example arguments: ".length());
            Map<String, Object> args = JSON.readValue(example, new TypeReference<>() {});
            assertThat(args).containsKey("query");
        }
        bind(CountTool.DESCRIPTION.substring(CountTool.DESCRIPTION.indexOf("{\"query\"")), CountTool.Input.class).query()
            .toQuery(edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType.COUNT);
        bind(CrossCountTool.DESCRIPTION.substring(CrossCountTool.DESCRIPTION.indexOf("{\"resultType\"")), CrossCountTool.Input.class)
            .query().toQuery(edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType.CATEGORICAL_CROSS_COUNT);
        assertThat(CrossCountTool.DESCRIPTION).contains("\\_studies_consents\\ cell itself").contains("kept rather than dropped")
            .contains("At most " + CrossCountResult.MAX_CELLS + " cells");
    }
}
