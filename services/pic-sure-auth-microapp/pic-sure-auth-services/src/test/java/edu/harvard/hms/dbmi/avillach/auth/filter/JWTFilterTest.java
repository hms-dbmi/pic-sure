package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JWTFilterTest {

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void longTermTokenCanReachConsentsEndpoint() throws Exception {
        TOSService tosService = mock(TOSService.class);
        JWTUtil jwtUtil = mock(JWTUtil.class);
        CustomUserDetailService userDetailsService = mock(CustomUserDetailService.class);
        SessionService sessionService = mock(SessionService.class);
        JWTFilter filter = new JWTFilter(tosService, "sub", jwtUtil, userDetailsService, sessionService);
        FilterChain filterChain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user/me/consents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer long-term-token");

        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken("long-term-token")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.get("sub", String.class)).thenReturn(AuthNaming.LONG_TERM_TOKEN_PREFIX + "|researcher-subject");
        when(claims.getSubject()).thenReturn(AuthNaming.LONG_TERM_TOKEN_PREFIX + "|researcher-subject");

        Privilege privilege = new Privilege();
        privilege.setName("QUERY");
        Role role = new Role();
        role.setPrivileges(Set.of(privilege));
        User user = new User();
        user.setSubject("researcher-subject");
        user.setRoles(Set.of(role));
        user.setEmail("researcher@example.org");
        when(userDetailsService.loadUserByUsername("researcher-subject")).thenReturn(new CustomUserDetails(user));
        when(tosService.hasUserAcceptedLatest("researcher-subject")).thenReturn(true);

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
