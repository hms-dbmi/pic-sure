package edu.harvard.hms.dbmi.avillach.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import edu.harvard.hms.dbmi.avillach.mcp.gateway.AuthorizedQueryClient;
import io.modelcontextprotocol.common.McpTransportContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Covers {@code authorized_cross_count} against a mock gateway: the chosen type sent with the caller's token, cells read and capped, no
 * request without a token, and failures that carry no downstream text.
 */
class AuthorizedCrossCountToolTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String AUTHORIZED = GATEWAY + "/hpds/auth/query/sync";
    private static final String BEARER = "Bearer caller-token";
    private static final String LEAK = "SECRET-DOWNSTREAM-BODY";
    private static final Map<String, Object> ARGS = Map.of("query", Map.of());

    private MockRestServiceServer server;

    private AuthorizedQueryClient client() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient gateway = builder.build();
        return new AuthorizedQueryClient(gateway, gateway);
    }

    private static McpTransportContext contextWith(String authorization) {
        return McpTransportContext.create(Map.of(CallerHeaders.KEY, new CallerHeaders(authorization, null, "req-1", null)));
    }

    private static final Map<String, Object> FILTER = Map.of("phenotypicFilterType", "REQUIRED", "conceptPath", "\\phs1\\sex\\");
    private static final Map<String, Object> FILTERED = Map.of("phenotypicClause", FILTER);
    private static final Map<String, Object> SELECTED = Map.of("select", List.of("\\phs1\\age\\"), "phenotypicClause", FILTER);
    private static final Map<String, Object> CROSS = Map.of("query", SELECTED, "resultType", "CROSS_COUNT");

    private AuthorizedCrossCountTool tool;

    @BeforeEach
    void setUp() {
        tool = new AuthorizedCrossCountTool(client());
    }

    @ParameterizedTest
    @EnumSource(AuthorizedCrossCountTool.CrossCountType.class)
    void everyTypeIsSentAsChosenWithTheCallersTokenAndReturnsExactCells(AuthorizedCrossCountTool.CrossCountType type) {
        server.expect(requestTo(AUTHORIZED)).andExpect(header("Authorization", BEARER))
            .andExpect(jsonPath("$.query.expectedResultType").value(type.name()))
            .andRespond(withSuccess("{\"\\\\phs1\\\\sex\\\\\":{\"Female\":38},\"\\\\phs1\\\\age\\\\\":900}", MediaType.APPLICATION_JSON));

        AuthorizedCrossCountResult result = tool.handle(contextWith(BEARER), Map.of("query", SELECTED, "resultType", type.name()));

        server.verify();
        assertThat(result.resultType()).isEqualTo(type.name());
        assertThat(result.totalCells()).isEqualTo(2);
        assertThat(result.cells()).extracting(AuthorizedCrossCountResult.Cell::count).containsExactlyInAnyOrder(38, 900);
        assertThat(result.cells()).extracting(AuthorizedCrossCountResult.Cell::failed).containsOnlyNulls();
    }

    @ParameterizedTest
    @EnumSource(value = AuthorizedCrossCountTool.CrossCountType.class, names = {"CROSS_COUNT", "OBSERVATION_CROSS_COUNT"})
    void aSelectDrivenTypeWithoutSelectSendsNothing(AuthorizedCrossCountTool.CrossCountType type) {
        server.expect(never(), requestTo(AUTHORIZED));

        for (Map<String, Object> query : List.<Map<String, Object>>of(Map.of(), Map.of("select", List.of()))) {
            assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("query", query, "resultType", type.name())))
                .isInstanceOf(ToolFailure.class).hasMessageContaining("Pass select");
        }
        server.verify();
    }

    @Test
    void aCategoricalCrossCountNeedsNoSelect() {
        server.expect(requestTo(AUTHORIZED))
            .andRespond(withSuccess("{\"\\\\phs1\\\\sex\\\\\":{\"Female\":38}}", MediaType.APPLICATION_JSON));

        AuthorizedCrossCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", FILTERED, "resultType", "CATEGORICAL_CROSS_COUNT"));

        assertThat(result.cells()).containsExactly(new AuthorizedCrossCountResult.Cell("\\phs1\\sex\\", "Female", 38, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = AuthorizedCrossCountTool.CrossCountType.class, names = {"CROSS_COUNT", "OBSERVATION_CROSS_COUNT"})
    void anEmptyBodyForASelectDrivenTypeIsNoDataCellsNotAFailure(AuthorizedCrossCountTool.CrossCountType type) {
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AuthorizedCrossCountResult result = tool.handle(contextWith(BEARER), Map.of("query", SELECTED, "resultType", type.name()));

        assertThat(result.cells()).isNotEmpty().extracting(AuthorizedCrossCountResult.Cell::noData).containsOnly(true);
    }

    @ParameterizedTest
    @EnumSource(value = AuthorizedCrossCountTool.CrossCountType.class, names = {"CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT"})
    void aBreakdownWithNoFilterSendsNothing(AuthorizedCrossCountTool.CrossCountType type) {
        server.expect(never(), requestTo(AUTHORIZED));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("query", Map.of(), "resultType", type.name())))
            .isInstanceOf(ToolFailure.class).hasMessageContaining("Add a phenotypicClause");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"CATEGORICAL_CROSS_COUNT,no categorical concept", "CONTINUOUS_CROSS_COUNT,no continuous concept"})
    void aBreakdownWithNoConceptOfItsKindSaysWhy(String type, String expected) {
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("query", FILTERED, "resultType", type)))
            .isInstanceOf(ToolFailure.class).hasMessageContaining(expected);
    }

    @Test
    void aSelectPathTheQueryServiceLeftOutIsANoDataCell() {
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess("{\"\\\\phs1\\\\age\\\\\":12}", MediaType.APPLICATION_JSON));
        Map<String, Object> query = Map.of("select", List.of("\\phs1\\age\\", "\\phs1\\empty\\"));

        AuthorizedCrossCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", query, "resultType", "OBSERVATION_CROSS_COUNT"));

        assertThat(result.totalCells()).isEqualTo(2);
        assertThat(result.cells()).containsExactly(
            new AuthorizedCrossCountResult.Cell("\\phs1\\age\\", null, 12, null, null),
            new AuthorizedCrossCountResult.Cell("\\phs1\\empty\\", null, null, true, null)
        );
    }

    @Test
    void moreSelectPathsThanTheCellCapSendsNothing() {
        server.expect(never(), requestTo(AUTHORIZED));
        List<String> paths = java.util.stream.IntStream.rangeClosed(0, CrossCountResult.MAX_CELLS).mapToObj(i -> "\\p" + i + "\\").toList();

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("query", Map.of("select", paths), "resultType", "CROSS_COUNT")))
            .isInstanceOf(ToolFailure.class).hasMessageContaining("at most 100");
        server.verify();
    }

    @Test
    void aFilteredConceptWithNoValuesIsANoDataCellInResponseOrder() {
        server.expect(requestTo(AUTHORIZED)).andRespond(
            withSuccess(
                "{\"\\\\phs1\\\\age\\\\\":{\"40.0\":3},\"\\\\phs1\\\\bmi\\\\\":{},\"\\\\phs1\\\\ht\\\\\":{\"170.0\":2}}",
                MediaType.APPLICATION_JSON
            )
        );

        AuthorizedCrossCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", FILTERED, "resultType", "CONTINUOUS_CROSS_COUNT"));

        assertThat(result.totalCells()).isEqualTo(3);
        assertThat(result.cells()).containsExactly(
            new AuthorizedCrossCountResult.Cell("\\phs1\\age\\", "40.0", 3, null, null),
            new AuthorizedCrossCountResult.Cell("\\phs1\\bmi\\", null, null, true, null),
            new AuthorizedCrossCountResult.Cell("\\phs1\\ht\\", "170.0", 2, null, null)
        );
    }

    @Test
    void theCapAppliesAfterNoDataCellsSoAtMost100CellsAreReturned() {
        StringBuilder body = new StringBuilder("{\"\\\\a\\\\\":{");
        for (int i = 0; i < 150; i++) {
            body.append(i == 0 ? "" : ",").append('"').append(i).append("\":1");
        }
        body.append("},\"\\\\b\\\\\":{}}");
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess(body.toString(), MediaType.APPLICATION_JSON));

        AuthorizedCrossCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", FILTERED, "resultType", "CATEGORICAL_CROSS_COUNT"));

        assertThat(result.totalCells()).isEqualTo(151);
        assertThat(result.cells()).hasSize(CrossCountResult.MAX_CELLS).allMatch(cell -> cell.conceptPath().equals("\\a\\"));
        assertThat(result.cellsOmitted()).isEqualTo(51);
    }

    @Test
    void duplicateSelectPathsCountOnceTowardTheCapAndAreSentOnce() {
        server.expect(requestTo(AUTHORIZED)).andExpect(jsonPath("$.query.select.length()").value(CrossCountResult.MAX_CELLS))
            .andRespond(withSuccess("{\"\\\\p0\\\\\":5}", MediaType.APPLICATION_JSON));
        List<String> paths = new java.util.ArrayList<>(
            java.util.stream.IntStream.range(0, CrossCountResult.MAX_CELLS).mapToObj(i -> "\\p" + i + "\\").toList()
        );
        paths.add(paths.get(0));

        AuthorizedCrossCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", Map.of("select", paths), "resultType", "CROSS_COUNT"));

        assertThat(result.totalCells()).isEqualTo(CrossCountResult.MAX_CELLS);
        assertThat(result.cells().get(0)).isEqualTo(new AuthorizedCrossCountResult.Cell("\\p0\\", null, 5, null, null));
        assertThat(result.cells()).filteredOn(cell -> Boolean.TRUE.equals(cell.noData())).hasSize(CrossCountResult.MAX_CELLS - 1);
        server.verify();
    }

    @Test
    void selectIsNotSentForACategoricalCrossCount() {
        server.expect(requestTo(AUTHORIZED)).andExpect(jsonPath("$.query.select").isEmpty())
            .andRespond(withSuccess("{\"\\\\phs1\\\\sex\\\\\":{\"Female\":38}}", MediaType.APPLICATION_JSON));

        tool.handle(
            contextWith(BEARER),
            Map.of("query", Map.of("select", List.of(""), "phenotypicClause", FILTER), "resultType", "CATEGORICAL_CROSS_COUNT")
        );

        server.verify();
    }

    @Test
    void aCellTheQueryServiceCouldNotCountIsMarkedFailedNotCounted() {
        server.expect(requestTo(AUTHORIZED))
            .andRespond(withSuccess("{\"\\\\phs1\\\\age\\\\\":-1,\"\\\\phs1\\\\bmi\\\\\":7}", MediaType.APPLICATION_JSON));

        AuthorizedCrossCountResult result = tool.handle(contextWith(BEARER), CROSS);

        assertThat(result.cells()).containsExactlyInAnyOrder(
            new AuthorizedCrossCountResult.Cell("\\phs1\\age\\", null, null, null, true),
            new AuthorizedCrossCountResult.Cell("\\phs1\\bmi\\", null, 7, null, null)
        );
    }

    @Test
    void aMissingQueryOrResultTypeSendsNothing() {
        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("resultType", "CROSS_COUNT"))).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'query' is required.");
        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), ARGS)).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'resultType' is required.");
        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of("query", Map.of(), "resultType", "COUNT")))
            .isInstanceOf(ToolFailure.class);
        server.verify();
    }

    @Test
    void aBodyThatIsNotAnObjectIsUnreadable() {
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess("[1,2]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), CROSS)).isInstanceOf(ToolFailure.class)
            .hasMessage("The query service returned a result the tool could not read.");
    }

    @Test
    void noTokenFailsWithoutSendingAnything() {
        server.expect(never(), requestTo(AUTHORIZED));

        assertThatThrownBy(() -> tool.handle(contextWith(null), CROSS)).isInstanceOf(ToolFailure.class).hasMessageContaining("no token");
        server.verify();
    }

    @Test
    void noTokenIsReportedBeforeAQueryProblem() {
        server.expect(never(), requestTo(AUTHORIZED));

        assertThatThrownBy(() -> tool.handle(contextWith(null), Map.of("query", Map.of(), "resultType", "CATEGORICAL_CROSS_COUNT")))
            .isInstanceOf(ToolFailure.class).hasMessageContaining("no token");
        server.verify();
    }

    @ParameterizedTest
    @CsvSource(
        {"UNAUTHORIZED,sign in", "FORBIDDEN,does not permit this authorized count", "BAD_REQUEST,rejected the query",
            "BAD_GATEWAY,no study access", "SERVICE_UNAVAILABLE,not available on this PIC-SURE deployment",
            "GATEWAY_TIMEOUT,Retry once at most", "NOT_FOUND,rejected the request"}
    )
    void aFailedResponseBecomesAMessageThatCarriesNoDownstreamText(HttpStatus status, String expected) {
        server.expect(requestTo(AUTHORIZED)).andRespond(withStatus(status).body(LEAK));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), CROSS)).isInstanceOf(ToolFailure.class).hasMessageContaining(expected)
            .message().doesNotContain(LEAK);
    }

    @Test
    void noResponseIsUnavailable() {
        server.expect(requestTo(AUTHORIZED)).andRespond(withException(new IOException("refused")));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), CROSS)).isInstanceOf(ToolFailure.class)
            .hasMessageContaining("took too long. Retry once at most");
    }
}
