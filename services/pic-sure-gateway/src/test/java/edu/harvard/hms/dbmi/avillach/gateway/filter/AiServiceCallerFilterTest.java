package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.VerifiedCaller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;

class AiServiceCallerFilterTest {

    private static final String CURRENT = "current-ai-secret";
    private static final String PREVIOUS = "previous-ai-secret";

    private final AuditContext audit = new AuditContext();
    private final AtomicBoolean chainCalled = new AtomicBoolean();
    private final FilterChain chain = (req, resp) -> chainCalled.set(true);

    private MockHttpServletResponse run(jakarta.servlet.Filter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String path, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (token != null) {
            request.addHeader(AiServiceCallerFilter.TOKEN_HEADER, token);
        }
        return request;
    }

    @Test
    void currentTokenOnTheMcpPathMarksTheRequestVerifiedCallerAiService() throws Exception {
        MockHttpServletRequest request = request("/mcp", CURRENT);

        MockHttpServletResponse response = run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(VerifiedCaller.get(request)).contains("ai-service");
        assertThat(audit.getMetadata()).isEmpty();
    }

    @Test
    void previousTokenIsAcceptedDuringRotation() throws Exception {
        MockHttpServletRequest request = request("/mcp", PREVIOUS);

        run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).contains("ai-service");
    }

    @Test
    void aVerifiedAiCallReplacesTheMarkMcpCallerFilterPutOnEveryMcpRequest() throws Exception {
        MockHttpServletRequest request = request("/mcp", CURRENT);
        run(new McpCallerFilter(audit, "mcp-secret", null), request);
        assertThat(VerifiedCaller.get(request)).contains("mcp");

        run(new AiServiceCallerFilter(audit, CURRENT, null), request);

        assertThat(VerifiedCaller.get(request)).contains("ai-service");
    }

    @Test
    void aValidTokenOnAnotherPathMarksNothing() throws Exception {
        MockHttpServletRequest request = request("/hpds/auth/query/sync", CURRENT);

        run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).isEmpty();
    }

    @Test
    void anInvalidTokenIsRejectedWith401AndAuditedWithoutEchoingTheValue() throws Exception {
        String presented = "not-the-secret-value";
        MockHttpServletRequest request = request("/mcp", presented);

        MockHttpServletResponse response = run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"errorType\":\"invalid_service_credential\"").doesNotContain(presented);
        assertThat(VerifiedCaller.get(request)).isEmpty();
        assertThat(audit.getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "invalid_service_credential");
        assertThat(audit.getMetadata().values()).doesNotContain(presented);
    }

    @Test
    void theMcpServiceTokenIsNotAnAiServiceToken() throws Exception {
        MockHttpServletRequest request = request("/mcp", "mcp-secret");

        MockHttpServletResponse response = run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void everyPresentedTokenIsInvalidWhenNoneIsConfigured() throws Exception {
        MockHttpServletResponse response = run(new AiServiceCallerFilter(audit, " ", null), request("/mcp", CURRENT));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chainCalled).isFalse();
    }

    @Test
    void absentTokenChangesNothing() throws Exception {
        MockHttpServletRequest request = request("/mcp", null);

        MockHttpServletResponse response = run(new AiServiceCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(VerifiedCaller.get(request)).isEmpty();
    }

    @Test
    void identityPropagationForwardsClientTypeAiServiceForAVerifiedCallAndMcpOtherwise() throws Exception {
        AtomicReference<String> verified = new AtomicReference<>();
        MockHttpServletRequest ai = request("/mcp", CURRENT);
        ai.addHeader("X-Client-Type", "web");
        run(new McpCallerFilter(audit, "mcp-secret", null), ai);
        run(new AiServiceCallerFilter(audit, CURRENT, null), ai);
        new IdentityPropagationFilter().doFilter(
            ai, new MockHttpServletResponse(), (req, resp) -> verified.set(((HttpServletRequest) req).getHeader("X-Client-Type"))
        );

        AtomicReference<String> unverified = new AtomicReference<>();
        MockHttpServletRequest plain = request("/mcp", null);
        run(new McpCallerFilter(audit, "mcp-secret", null), plain);
        new IdentityPropagationFilter().doFilter(
            plain, new MockHttpServletResponse(), (req, resp) -> unverified.set(((HttpServletRequest) req).getHeader("X-Client-Type"))
        );

        assertThat(verified.get()).isEqualTo("ai-service");
        assertThat(unverified.get()).isEqualTo("mcp");
    }
}
