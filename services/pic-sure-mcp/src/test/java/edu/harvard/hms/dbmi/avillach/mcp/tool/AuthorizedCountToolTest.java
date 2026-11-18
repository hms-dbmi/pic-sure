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
import java.util.Map;

/**
 * Covers {@code authorized_count_participants} against a mock gateway: COUNT sent with the caller's token, no request without a token, only
 * an exact count accepted, and failures that carry no downstream text.
 */
class AuthorizedCountToolTest {

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

    private AuthorizedCountTool tool;

    @BeforeEach
    void setUp() {
        tool = new AuthorizedCountTool(client());
    }

    @Test
    void sendsCountWithTheCallersTokenAndDropsSelectAndReturnsTheExactCount() {
        server.expect(requestTo(AUTHORIZED)).andExpect(header("Authorization", BEARER))
            .andExpect(jsonPath("$.query.expectedResultType").value("COUNT")).andExpect(jsonPath("$.query.select").isEmpty())
            .andExpect(jsonPath("$.query.authorizationFilters").isEmpty()).andRespond(withSuccess("1180", MediaType.APPLICATION_JSON));

        AuthorizedCountResult result =
            tool.handle(contextWith(BEARER), Map.of("query", Map.of("select", java.util.List.of("\\\\phs1\\\\sex\\\\"))));

        server.verify();
        assertThat(result).isEqualTo(new AuthorizedCountResult(1180));
    }

    @Test
    void aMissingQuerySendsNothing() {
        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), Map.of())).isInstanceOf(ToolFailure.class)
            .hasMessage("Argument 'query' is required.");
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"1234 ±3", "< 10", ""})
    void anObfuscatedSuppressedOrEmptyBodyIsNotAnExactCount(String body) {
        server.expect(requestTo(AUTHORIZED)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), ARGS)).isInstanceOf(ToolFailure.class);
    }

    @Test
    void noTokenFailsWithoutSendingAnything() {
        server.expect(never(), requestTo(AUTHORIZED));

        assertThatThrownBy(() -> tool.handle(contextWith(null), ARGS)).isInstanceOf(ToolFailure.class).hasMessageContaining("no token");
        assertThatThrownBy(() -> tool.handle(contextWith(null), Map.of())).isInstanceOf(ToolFailure.class).hasMessageContaining("no token");
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

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), ARGS)).isInstanceOf(ToolFailure.class).hasMessageContaining(expected)
            .message().doesNotContain(LEAK);
    }

    @Test
    void noResponseIsUnavailable() {
        server.expect(requestTo(AUTHORIZED)).andRespond(withException(new IOException("refused")));

        assertThatThrownBy(() -> tool.handle(contextWith(BEARER), ARGS)).isInstanceOf(ToolFailure.class)
            .hasMessageContaining("took too long. Retry once at most");
    }
}
