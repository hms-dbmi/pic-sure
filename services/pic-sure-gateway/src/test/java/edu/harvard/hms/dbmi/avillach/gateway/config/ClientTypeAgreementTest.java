package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.ClientTypeAllowlist;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import edu.harvard.hms.dbmi.avillach.gateway.filter.IdentityPropagationFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs the audit filter, the header sanitizer and the identity propagation filter in their configured order on one request, and checks that
 * the caller the gateway audits is the caller the next service receives.
 */
class ClientTypeAgreementTest {

    private static final String PATH = "/hpds/auth/query/sync";

    private final LoggingClient logging = mock(LoggingClient.class);
    private final AtomicReference<String> forwardedClientType = new AtomicReference<>();
    private final AtomicReference<Boolean> reachedDownstream = new AtomicReference<>(false);

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        when(logging.isEnabled()).thenReturn(true);
        AuditContext audit = new AuditContext();
        AuditFilterConfig auditConfig = new AuditFilterConfig();
        Filter auditFilter =
            auditConfig.auditLoggingFilter(logging, auditConfig.auditRouteTable(), audit, ClientTypeAllowlist.DEFAULT).getFilter();
        Filter sanitizer = new ObservabilityConfig().inboundIdentityHeaderSanitizingFilter(ClientTypeAllowlist.DEFAULT).getFilter();
        HttpServlet downstream = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                reachedDownstream.set(true);
                forwardedClientType.set(req.getHeader("X-Client-Type"));
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(downstream, auditFilter, sanitizer, new IdentityPropagationFilter()).doFilter(request, response);
        return response;
    }

    private LoggingEvent auditedEvent() {
        ArgumentCaptor<LoggingEvent> emitted = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(logging).send(emitted.capture());
        return emitted.getValue();
    }

    @Test
    void everyListedClientTypeIsAuditedAndForwardedAsTheSameCallerInEverySpelling() throws Exception {
        for (String listed : List.of("PYTHON_ADAPTER", "R_ADAPTER", "FRONTEND")) {
            for (String sent : List.of(listed, listed.toLowerCase(Locale.ROOT), " " + listed + " ")) {
                clearInvocations(logging);
                MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
                request.addHeader("X-Client-Type", sent);

                MockHttpServletResponse response = run(request);

                assertThat(response.getStatus()).as(sent).isEqualTo(200);
                assertThat(auditedEvent().getCaller()).as(sent).isEqualTo(listed);
                assertThat(forwardedClientType.get()).as(sent).isEqualTo(listed);
            }
        }
    }

    @Test
    void anAbsentClientTypeIsAuditedAndForwardedAsUnknown() throws Exception {
        MockHttpServletResponse response = run(new MockHttpServletRequest("POST", PATH));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(auditedEvent().getCaller()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
        assertThat(forwardedClientType.get()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
    }

    @Test
    void aBlankClientTypeIsAuditedAndForwardedAsUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("X-Client-Type", "  ");

        run(request);

        assertThat(auditedEvent().getCaller()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
        assertThat(forwardedClientType.get()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
    }

    @Test
    void aListOfAllowedClientTypesIsRejectedAndAuditedAsUnknownWithTheRawValueClaimed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER, FRONTEND");

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(reachedDownstream.get()).isFalse();
        LoggingEvent event = auditedEvent();
        assertThat(event.getCaller()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
        assertThat(event.getMetadata()).containsEntry("client_type_claimed", "PYTHON_ADAPTER, FRONTEND");
    }

    @Test
    void aRepeatedClientTypeHeaderIsRejectedBeforeAnyServiceSeesIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");
        request.addHeader("X-Client-Type", "FRONTEND");

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(reachedDownstream.get()).isFalse();
        assertThat(forwardedClientType.get()).isNull();
    }

    @Test
    void anUnlistedClientTypeIsRejectedAndAuditedAsUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.addHeader("X-Client-Type", "curl-script");

        MockHttpServletResponse response = run(request);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(reachedDownstream.get()).isFalse();
        assertThat(auditedEvent().getCaller()).isEqualTo(ClientTypeAllowlist.UNKNOWN);
    }
}
