package edu.harvard.hms.dbmi.avillach.gateway.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.http.HttpServletRequest;

/**
 * FIX 1 (critical, defense-in-depth): {@link InboundIdentityHeaderSanitizingFilter} is registered UNCONDITIONALLY in
 * {@code ObservabilityConfig} as an independent trust boundary, separate from {@code IdentityPropagationFilter} in the always-on DB-free
 * auth chain. These tests exercise the filter standalone: even if the auth chain were bypassed or misconfigured, this filter must still
 * strip client-supplied identity headers.
 */
class InboundIdentityHeaderSanitizingFilterTest {

    private final InboundIdentityHeaderSanitizingFilter filter = new InboundIdentityHeaderSanitizingFilter();

    @Test
    void stripsAllFiveClientSuppliedIdentityHeadersUnconditionally() throws Exception {
        // The dangerous case: a client tries to spoof an elevated identity directly, with no other filter in front of
        // this one to stop it.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/sync");
        request.addHeader(GatewayUserResolver.HEADER_USER_ID, "spoofed-id");
        request.addHeader(GatewayUserResolver.HEADER_USER_SUBJECT, "spoofed-subject");
        request.addHeader(GatewayUserResolver.HEADER_USER_EMAIL, "spoofed@example.com");
        request.addHeader(GatewayUserResolver.HEADER_USER_ROLES, "ADMIN");
        request.addHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES, "SYSTEM,ADMIN");
        request.addHeader("X-Other-Header", "kept");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ID)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_SUBJECT)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_EMAIL)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ROLES)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isNull();
        assertThat(wrapped.getHeader("X-Other-Header")).isEqualTo("kept");

        assertThat(wrapped.getHeaders(GatewayUserResolver.HEADER_USER_PRIVILEGES).hasMoreElements()).isFalse();
        assertThat(wrapped.getHeaders(GatewayUserResolver.HEADER_USER_ID).hasMoreElements()).isFalse();

        List<String> names = Collections.list(wrapped.getHeaderNames());
        assertThat(names).doesNotContain(
            GatewayUserResolver.HEADER_USER_ID, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL,
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_PRIVILEGES
        );
        assertThat(names).contains("X-Other-Header");
    }

    @Test
    void stripsSpoofableForwardingHeadersAndInternalTokenButKeepsXForwardedForAndTelemetry() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/sync");
        request.addHeader("X-Real-IP", "6.6.6.6");
        request.addHeader("Forwarded", "for=6.6.6.6");
        request.addHeader("X-PIC-SURE-INTERNAL-TOKEN", "stolen-internal-token");
        // X-Forwarded-For stays: the trusted front proxy appends to it, and consumers take the
        // rightmost (trusted) entry. Session/client-type/request-source are intentional client telemetry.
        request.addHeader("X-Forwarded-For", "6.6.6.6, 10.0.0.1");
        request.addHeader("X-Session-Id", "session-123");
        request.addHeader("X-Client-Type", "PYTHON_ADAPTER");
        request.addHeader("request-source", "portal");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader("X-Real-IP")).isNull();
        assertThat(wrapped.getHeader("Forwarded")).isNull();
        assertThat(wrapped.getHeader("X-PIC-SURE-INTERNAL-TOKEN")).isNull();
        assertThat(wrapped.getHeader("X-Forwarded-For")).isEqualTo("6.6.6.6, 10.0.0.1");
        assertThat(wrapped.getHeader("X-Session-Id")).isEqualTo("session-123");
        assertThat(wrapped.getHeader("X-Client-Type")).isEqualTo("PYTHON_ADAPTER");
        assertThat(wrapped.getHeader("request-source")).isEqualTo("portal");

        List<String> names = Collections.list(wrapped.getHeaderNames());
        assertThat(names).doesNotContain("X-Real-IP", "Forwarded", "X-PIC-SURE-INTERNAL-TOKEN");
        assertThat(names).contains("X-Forwarded-For", "X-Session-Id", "X-Client-Type", "request-source");
    }

    @Test
    void stripsReservedServiceClientTypeFromExternalRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user/me/consents");
        request.addHeader("x-client-type", "SERVICE");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader("X-Client-Type")).isNull();
        assertThat(wrapped.getHeaders("x-client-type").hasMoreElements()).isFalse();
        assertThat(Collections.list(wrapped.getHeaderNames())).doesNotContain("x-client-type");
    }

    @Test
    void headerStrippingIsCaseInsensitive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/sync");
        request.addHeader("x-user-privileges", "SYSTEM,ADMIN");
        request.addHeader("X-USER-ID", "spoofed-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ID)).isNull();
        assertThat(Collections.list(wrapped.getHeaderNames())).doesNotContain("x-user-privileges", "X-USER-ID");
    }

    @Test
    void stripsClientSuppliedAccessTypeHeaderRegardlessOfCase() throws Exception {
        // The gateway owns X-Picsure-Access-Type, so it gets the same unconditional strip as the X-User-* set.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/visualization/auth/distributions");
        request.addHeader("x-picsure-access-type", "authorized");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        HttpServletRequest wrapped = captured.get();
        assertThat(wrapped.getHeader("x-picsure-access-type")).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_ACCESS_TYPE)).isNull();
        assertThat(wrapped.getHeaders(GatewayUserResolver.HEADER_ACCESS_TYPE).hasMoreElements()).isFalse();
        assertThat(Collections.list(wrapped.getHeaderNames()))
            .doesNotContain("x-picsure-access-type", GatewayUserResolver.HEADER_ACCESS_TYPE);
    }

    @Test
    void nonIdentityHeadersPassThroughUnchangedWhenNoSpoofAttempted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/sync");
        request.addHeader("Authorization", "Bearer abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        assertThat(captured.get().getHeader("Authorization")).isEqualTo("Bearer abc");
    }
}
