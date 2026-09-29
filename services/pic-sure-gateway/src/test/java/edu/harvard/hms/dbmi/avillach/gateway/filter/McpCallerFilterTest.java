package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import jakarta.servlet.FilterChain;

class McpCallerFilterTest {

    private static final String CURRENT = "current-mcp-secret";
    private static final String PREVIOUS = "previous-mcp-secret";

    private final AuditContext audit = new AuditContext();
    private final AtomicBoolean chainCalled = new AtomicBoolean();
    private final FilterChain chain = (req, resp) -> chainCalled.set(true);

    private MockHttpServletResponse run(McpCallerFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) {
            request.addHeader(McpCallerFilter.HEADER, token);
        }
        return request;
    }

    @Test
    void currentTokenMarksTheRequestVerifiedCallerMcp() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", CURRENT);

        MockHttpServletResponse response = run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(VerifiedCaller.get(request)).contains("mcp");
        assertThat(audit.getMetadata()).isEmpty();
    }

    @Test
    void previousTokenIsAcceptedDuringRotation() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", PREVIOUS);

        run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).contains("mcp");
    }

    @Test
    void invalidTokenIsRejectedWith401AndAuditedWithoutEchoingTheValue() throws Exception {
        String presented = "not-the-secret-value";
        MockHttpServletRequest request = request("/hpds/auth/query/sync", presented);

        MockHttpServletResponse response = run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorType\":\"invalid_service_credential\"").doesNotContain(presented);
        assertThat(VerifiedCaller.get(request)).isEmpty();
        assertThat(audit.getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "invalid_service_credential");
        assertThat(audit.getMetadata().values()).doesNotContain(presented);
    }

    @Test
    void emptyTokenHeaderIsInvalid() throws Exception {
        MockHttpServletResponse response = run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request("/hpds/auth/query/sync", ""));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void invalidTokenIsRejectedEvenOnTheMcpPath() throws Exception {
        MockHttpServletRequest request = request("/mcp", "wrong");

        MockHttpServletResponse response = run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(VerifiedCaller.get(request)).isEmpty();
    }

    @Test
    void absentTokenChangesNothing() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", null);

        MockHttpServletResponse response = run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(VerifiedCaller.get(request)).isEmpty();
        assertThat(audit.getMetadata()).isEmpty();
    }

    @Test
    void mcpPathIsMarkedVerifiedCallerMcpWithoutAToken() throws Exception {
        MockHttpServletRequest request = request("/mcp", null);

        run(new McpCallerFilter(audit, null, null), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).contains("mcp");
    }

    @Test
    void onlyTheExactMcpPathIsMarked() throws Exception {
        MockHttpServletRequest subPath = request("/mcp/other", null);
        MockHttpServletRequest lookalike = request("/mcpx", null);
        McpCallerFilter filter = new McpCallerFilter(audit, CURRENT, PREVIOUS);

        run(filter, subPath);
        run(filter, lookalike);

        assertThat(VerifiedCaller.get(subPath)).isEmpty();
        assertThat(VerifiedCaller.get(lookalike)).isEmpty();
    }

    @Test
    void withNoTokenConfiguredAPresentedHeaderIsInvalid() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", "anything");

        MockHttpServletResponse response = run(new McpCallerFilter(audit, null, null), request);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(VerifiedCaller.get(request)).isEmpty();
    }

    @Test
    void blankConfiguredTokensNeverMatchABlankHeader() throws Exception {
        MockHttpServletResponse response = run(new McpCallerFilter(audit, " ", ""), request("/hpds/auth/query/sync", " "));

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void withOnlyAPreviousTokenConfiguredThatTokenIsStillAccepted() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", PREVIOUS);

        run(new McpCallerFilter(audit, "", PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).contains("mcp");
    }
}
