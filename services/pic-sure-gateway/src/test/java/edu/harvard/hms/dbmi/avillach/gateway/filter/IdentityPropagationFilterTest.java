package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class IdentityPropagationFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void copiesAttributesToHeadersIncludingPrivilegesAndRequestId() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_ID)).thenReturn("u-1");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_EMAIL)).thenReturn("a@b");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_PRIVILEGES)).thenReturn("SUPER_ADMIN");
        when(req.getHeader("X-Request-Id")).thenReturn("req-42");
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        HttpServletRequest wrapped = (HttpServletRequest) cap.getValue();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ID)).isEqualTo("u-1");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isEqualTo("SUPER_ADMIN");
        assertThat(wrapped.getHeader("X-Request-Id")).isEqualTo("req-42");
    }

    @Test
    void generatesRequestIdWhenAbsent() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        assertThat(((HttpServletRequest) cap.getValue()).getHeader("X-Request-Id")).isNotBlank();
    }

    // ---- FIX 1: gateway-owned X-User-* headers must never fall through to a client-supplied value ----

    @Test
    void clientSpoofedPrivilegesAreNotVisibleWhenGatewayResolvedNone() throws Exception {
        // Gateway resolved a user id but no privileges (e.g. a user with an empty privilege set); the client tries to
        // inject its own elevated privileges via the raw request header.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_ID)).thenReturn("u-1");
        when(req.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).thenReturn("SYSTEM,ADMIN");
        when(req.getHeaders(GatewayUserResolver.HEADER_USER_PRIVILEGES)).thenReturn(Collections.enumeration(List.of("SYSTEM,ADMIN")));
        when(req.getHeaderNames()).thenReturn(Collections.enumeration(List.of(GatewayUserResolver.HEADER_USER_PRIVILEGES)));

        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        HttpServletRequest wrapped = (HttpServletRequest) cap.getValue();

        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isNull();
        assertThat(wrapped.getHeaders(GatewayUserResolver.HEADER_USER_PRIVILEGES).hasMoreElements()).isFalse();
        assertThat(Collections.list(wrapped.getHeaderNames())).doesNotContain(GatewayUserResolver.HEADER_USER_PRIVILEGES);
    }

    @Test
    void openAccessRequestStripsClientSuppliedRolesSubjectAndEmail() throws Exception {
        // Open-access path: only X-User-Id is resolved by the gateway. The client sends its own Roles/Subject/Email
        // trying to impersonate an authenticated, privileged user.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_ID)).thenReturn("open-access");
        when(req.getHeader(GatewayUserResolver.HEADER_USER_ROLES)).thenReturn("ADMIN");
        when(req.getHeader(GatewayUserResolver.HEADER_USER_SUBJECT)).thenReturn("spoofed-subject");
        when(req.getHeader(GatewayUserResolver.HEADER_USER_EMAIL)).thenReturn("spoofed@example.com");
        when(req.getHeaderNames()).thenReturn(
            Collections.enumeration(
                List.of(
                    GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL
                )
            )
        );

        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        HttpServletRequest wrapped = (HttpServletRequest) cap.getValue();

        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ID)).isEqualTo("open-access");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ROLES)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_SUBJECT)).isNull();
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_EMAIL)).isNull();
        List<String> names = Collections.list(wrapped.getHeaderNames());
        assertThat(names).doesNotContain(
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL
        );
    }

    @Test
    void gatewayResolvedIdentityHeadersStillPropagateNormally() throws Exception {
        // Sanity check: when the gateway *does* resolve all five headers, they still flow through -- FIX 1 must not
        // break the normal authenticated path.
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_ID)).thenReturn("u-1");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_SUBJECT)).thenReturn("sub-1");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_EMAIL)).thenReturn("a@b");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_ROLES)).thenReturn("USER");
        when(req.getAttribute(GatewayUserResolver.HEADER_USER_PRIVILEGES)).thenReturn("VIEW_DATA");
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        HttpServletRequest wrapped = (HttpServletRequest) cap.getValue();

        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ID)).isEqualTo("u-1");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_SUBJECT)).isEqualTo("sub-1");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_EMAIL)).isEqualTo("a@b");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_ROLES)).isEqualTo("USER");
        assertThat(wrapped.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).isEqualTo("VIEW_DATA");
        List<String> names = Collections.list(wrapped.getHeaderNames());
        assertThat(names).contains(
            GatewayUserResolver.HEADER_USER_ID, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL,
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_PRIVILEGES
        );
    }

    // ---- FIX 3: request-id correlation must unify with the commons RequestIdFilter's MDC value ----

    @Test
    void reusesMdcRequestIdWhenNoInboundHeaderPresent() throws Exception {
        // Simulates the commons RequestIdFilter (highest precedence, runs earlier) having already generated an id
        // and bound it to MDC, without an inbound X-Request-Id header on the raw client request.
        MDC.put(RequestIdFilter.MDC_KEY, "rid-1");
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeaderNames()).thenReturn(Collections.emptyEnumeration());
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new IdentityPropagationFilter(GatewayModeResolver.enforcing()).doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        assertThat(((HttpServletRequest) cap.getValue()).getHeader("X-Request-Id")).isEqualTo("rid-1");
    }
}
