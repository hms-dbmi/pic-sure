package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditLoggingFilter;
import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIp;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;

class RateLimitFilterTest {

    private final RateLimitStore store = mock(RateLimitStore.class);
    private final AuditContext audit = new AuditContext();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query/sync");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(properties(enabled), new ClientIpResolver(List.of()), store, audit) {
            @Override
            protected Target target(HttpServletRequest req) {
                ClientIp client = clientIps.resolve(req);
                return new Target(RateLimitSubject.ip(client.address()), client);
            }
        };
    }

    private static RateLimitProperties properties(boolean enabled) {
        return new RateLimitProperties(
            enabled, new RateLimitProperties.KeyLimits(60, 3000), new RateLimitProperties.KeyLimits(60, 3000),
            new RateLimitProperties.IpLimits(60, 3000, 300), new RateLimitProperties.Store(1000), List.of(CidrRange.parse("192.0.2.10")),
            List.of()
        );
    }

    @Test
    void anUnparseablePeerRefusesTheRequestWithoutChargingTheStore() throws Exception {
        request.setRemoteAddr("proxy.example");
        filter(true).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(new ObjectMapper().readTree(response.getContentAsString())).isEqualTo(new ObjectMapper().readTree("""
            {"errorType":"client_ip_unresolvable",
             "message":"The gateway could not determine the connection peer address.","requestId":null}
            """));
        assertThat(chain.getRequest()).isNull();
        verifyNoInteractions(store);
        assertThat(request.getAttribute(AuditLoggingFilter.AUDIT_REQUIRED_ATTRIBUTE)).isEqualTo(Boolean.TRUE);
        assertThat(audit.getMetadata()).containsEntry("auth_result", "failure")
            .containsEntry("auth_failure_reason", "client_ip_unresolvable");
    }

    @Test
    void aDisabledLimiterDoesNotResolveThePeer() throws Exception {
        request.setRemoteAddr("proxy.example");
        filter(false).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(AuditLoggingFilter.AUDIT_REQUIRED_ATTRIBUTE)).isNull();
        assertThat(audit.getMetadata()).isEmpty();
        verifyNoInteractions(store);
    }

    @Test
    void unrelatedTargetFailuresAreNotConvertedIntoPeerFailures() {
        IllegalArgumentException failure = new IllegalArgumentException("unrelated target failure");
        RateLimitFilter filter = new RateLimitFilter(properties(true), new ClientIpResolver(List.of()), store, audit) {
            @Override
            protected Target target(HttpServletRequest req) {
                throw failure;
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
        verifyNoInteractions(store);
        assertThat(audit.getMetadata()).isEmpty();
        assertThat(chain.getRequest()).isNull();
    }
}
