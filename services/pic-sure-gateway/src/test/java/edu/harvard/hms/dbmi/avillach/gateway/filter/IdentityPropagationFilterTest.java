package edu.harvard.hms.dbmi.avillach.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class IdentityPropagationFilterTest {

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
        new IdentityPropagationFilter().doFilter(req, resp, chain);

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
        new IdentityPropagationFilter().doFilter(req, resp, chain);

        ArgumentCaptor<ServletRequest> cap = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(cap.capture(), eq(resp));
        assertThat(((HttpServletRequest) cap.getValue()).getHeader("X-Request-Id")).isNotBlank();
    }
}
