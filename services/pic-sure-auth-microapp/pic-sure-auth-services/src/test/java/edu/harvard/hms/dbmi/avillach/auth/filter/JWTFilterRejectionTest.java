package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every path where JWTFilter decides a request is unauthorized must actually stop it. The filter used to call
 * response.sendError(...) and then carry on into filterChain.doFilter(...) — in the application-token branches it
 * even installed an authenticated security context first, so a request it had just rejected reached the handler.
 */
class JWTFilterRejectionTest {

    private static final String PRESENTED_APP_TOKEN = "app-token-value";

    private TOSService tosService;
    private JWTUtil jwtUtil;
    private CustomUserDetailService userDetailsService;
    private JWTFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        tosService = mock(TOSService.class);
        jwtUtil = mock(JWTUtil.class);
        userDetailsService = mock(CustomUserDetailService.class);
        filter = new JWTFilter(tosService, "sub", jwtUtil, userDetailsService);
        filterChain = mock(FilterChain.class);
    }

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * An application token is only ever meant to reach the introspection endpoints. Anywhere else, the filter treats
     * it as possibly compromised — and must not then hand the request to the endpoint it was aimed at.
     */
    @Test
    void applicationTokenOnTheWrongEndpointIsStopped() throws Exception {
        String subject = AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|app-uuid";
        stubToken(PRESENTED_APP_TOKEN, subject, subject);
        MockHttpServletResponse response = callWithAppToken("/auth/user");

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
        assertFalse(
            response.getErrorMessage().contains("token/inspect") || response.getErrorMessage().contains("open/validate"),
            "the response must not tell a caller probing with an application token which endpoints it does work on"
        );
    }

    /**
     * Application tokens are rotated by refreshing them; the old one must stop working the moment the record changes.
     */
    @Test
    void rotatedApplicationTokenIsStopped() throws Exception {
        stubApplicationToken("the-rotated-in-replacement");
        MockHttpServletResponse response = callWithAppToken("/auth/token/inspect");

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void longTermTokenOutsideTheProfileEndpointsIsStopped() throws Exception {
        String subject = AuthNaming.LONG_TERM_TOKEN_PREFIX + "|researcher-subject";
        stubToken("long-term-token", subject, subject);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user");
        request.addHeader("Authorization", "Bearer long-term-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void userWhoHasNotAcceptedTermsOfServiceIsStopped() throws Exception {
        stubToken("user-token", "researcher-subject", "researcher-subject");
        stubUser("researcher-subject", "QUERY");
        when(tosService.hasUserAcceptedLatest("researcher-subject")).thenReturn(false);

        MockHttpServletResponse response = callWithUserToken("/auth/user/me");

        assertEquals(403, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void userWithoutRolesOrPrivilegesIsStopped() throws Exception {
        stubToken("user-token", "researcher-subject", "researcher-subject");
        User user = new User();
        user.setSubject("researcher-subject");
        user.setEmail("researcher@example.org");
        user.setRoles(Set.of());
        when(userDetailsService.loadUserByUsername("researcher-subject")).thenReturn(new CustomUserDetails(user));
        when(tosService.hasUserAcceptedLatest("researcher-subject")).thenReturn(true);

        MockHttpServletResponse response = callWithUserToken("/auth/user/me");

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    private MockHttpServletResponse callWithAppToken(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.addHeader("Authorization", "Bearer " + PRESENTED_APP_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private MockHttpServletResponse callWithUserToken(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    /**
     * @param tokenOnRecord the application's token in the database; equal to {@link #PRESENTED_APP_TOKEN} for a
     * current token, different for one that has since been rotated.
     */
    private void stubApplicationToken(String tokenOnRecord) {
        String subject = AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|app-uuid";
        stubToken(PRESENTED_APP_TOKEN, subject, subject);
        Application application = new Application();
        application.setName("PIC-SURE");
        application.setToken(tokenOnRecord);
        when(userDetailsService.loadUserByUsername("application:app-uuid")).thenReturn(new CustomApplicationDetails(application));
    }

    private void stubToken(String token, String userIdClaim, String subject) {
        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken(token)).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.get("sub", String.class)).thenReturn(userIdClaim);
        when(claims.getSubject()).thenReturn(subject);
    }

    private void stubUser(String subject, String privilegeName) {
        Privilege privilege = new Privilege();
        privilege.setName(privilegeName);
        Role role = new Role();
        role.setPrivileges(Set.of(privilege));
        User user = new User();
        user.setSubject(subject);
        user.setEmail(subject + "@example.org");
        user.setRoles(Set.of(role));
        when(userDetailsService.loadUserByUsername(subject)).thenReturn(new CustomUserDetails(user));
    }
}
