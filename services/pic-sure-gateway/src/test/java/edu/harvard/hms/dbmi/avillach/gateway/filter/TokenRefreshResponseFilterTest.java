package edu.harvard.hms.dbmi.avillach.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class TokenRefreshResponseFilterTest {

    @Test
    void copiesRefreshedTokenToAuthorizationHeaderAsBearer() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getAttribute(PsamaIntrospectionFilter.ATTR_REFRESHED_TOKEN)).thenReturn("new-token");

        new TokenRefreshResponseFilter().doFilter(req, resp, chain);

        verify(chain).doFilter(req, resp);
        verify(resp).setHeader("Authorization", "Bearer new-token");
    }

    @Test
    void noHeaderWhenNoRefresh() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        new TokenRefreshResponseFilter().doFilter(req, resp, chain);
        verify(resp, never()).setHeader(eq("Authorization"), anyString());
    }
}
