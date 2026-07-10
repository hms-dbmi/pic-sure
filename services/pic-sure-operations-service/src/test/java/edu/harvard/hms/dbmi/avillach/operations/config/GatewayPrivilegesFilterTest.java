package edu.harvard.hms.dbmi.avillach.operations.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class GatewayPrivilegesFilterTest {

    private final GatewayPrivilegesFilter filter = new GatewayPrivilegesFilter();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsPrivilegesHeaderToAuthoritiesWhenUserIdPresent() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(GatewayUserResolver.HEADER_USER_ID)).thenReturn("auth0|abc123");
        when(req.getHeader(GatewayUserResolver.HEADER_USER_PRIVILEGES)).thenReturn("SUPER_ADMIN,USER");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getName()).isEqualTo("auth0|abc123");
        assertThat(auth.getAuthorities()).extracting("authority").containsExactlyInAnyOrder("SUPER_ADMIN", "USER");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noPrivilegesHeaderStillAuthenticatesWithNoAuthoritiesWhenUserIdPresent() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader(GatewayUserResolver.HEADER_USER_ID)).thenReturn("auth0|abc123");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities()).isEmpty();
        verify(chain).doFilter(any(), any());
    }

    @Test
    void noUserIdHeaderLeavesContextAnonymous() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, mock(HttpServletResponse.class), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(any(), any());
    }
}
