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
 * {@code ObservabilityConfig} -- unlike {@code IdentityPropagationFilter}, which only runs as part of the DB-free auth chain when
 * {@code picsure.gateway.security.auth-enabled=true}. These tests exercise the filter standalone, which is exactly the dangerous scenario
 * this fix closes: auth-enabled=false (so none of the gated auth-chain filters run at all) but WildFly may still be configured with
 * {@code GATEWAY_OWNS_AUTH=true} and trust these headers. Even then, this filter must strip them.
 */
class InboundIdentityHeaderSanitizingFilterTest {

    private final InboundIdentityHeaderSanitizingFilter filter = new InboundIdentityHeaderSanitizingFilter();

    @Test
    void stripsAllFiveClientSuppliedIdentityHeadersRegardlessOfAuthEnabled() throws Exception {
        // The dangerous case: a client tries to spoof an elevated identity directly, with no auth-chain filter in front of
        // this one to stop it (auth-enabled=false -> BufferingFilter/OpenAccessFilter/.../IdentityPropagationFilter never run).
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
    void nonIdentityHeadersPassThroughUnchangedWhenNoSpoofAttempted() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/query/sync");
        request.addHeader("Authorization", "Bearer abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<HttpServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, resp) -> captured.set((HttpServletRequest) req));

        assertThat(captured.get().getHeader("Authorization")).isEqualTo("Bearer abc");
    }
}
