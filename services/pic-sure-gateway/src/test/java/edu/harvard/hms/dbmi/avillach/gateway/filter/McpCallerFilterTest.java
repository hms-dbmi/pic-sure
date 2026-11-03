package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

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
            request.addHeader(McpCallerFilter.TOKEN_HEADER, token);
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
    void anEncodedSpellingOfTheMcpPathIsMarked() throws Exception {
        MockHttpServletRequest request = request("/%6Dcp", null);

        run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(chainCalled).isTrue();
        assertThat(VerifiedCaller.get(request)).contains("mcp");
    }

    @Test
    void aDoublyEncodedSpellingIsNotMarked() throws Exception {
        MockHttpServletRequest request = request("/%256Dcp", null);

        run(new McpCallerFilter(audit, CURRENT, PREVIOUS), request);

        assertThat(VerifiedCaller.get(request)).isEmpty();
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

    @Test
    void configuredSecretsAreTrimmedBeforeComparison() throws Exception {
        MockHttpServletRequest current = request("/hpds/auth/query/sync", CURRENT);
        MockHttpServletRequest previous = request("/hpds/auth/query/sync", PREVIOUS);
        McpCallerFilter filter = new McpCallerFilter(audit, "  " + CURRENT + "\n", "\t" + PREVIOUS + " ");

        run(filter, current);
        run(filter, previous);

        assertThat(VerifiedCaller.get(current)).contains("mcp");
        assertThat(VerifiedCaller.get(previous)).contains("mcp");
    }

    @Test
    void oneWarningIsLoggedAtConstructionWhenNoSecretIsConfigured() {
        assertThat(warningsLoggedWhileConstructing(" ", null)).hasSize(1).first().asString().contains(McpCallerFilter.TOKEN_HEADER);
    }

    @Test
    void noWarningIsLoggedWhenEitherSecretIsConfigured() {
        assertThat(warningsLoggedWhileConstructing(CURRENT, null)).isEmpty();
        assertThat(warningsLoggedWhileConstructing(null, PREVIOUS)).isEmpty();
    }

    private List<String> warningsLoggedWhileConstructing(String serviceToken, String previousServiceToken) {
        Logger logger = (Logger) LoggerFactory.getLogger(McpCallerFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new McpCallerFilter(audit, serviceToken, previousServiceToken);
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage).toList();
    }
}
