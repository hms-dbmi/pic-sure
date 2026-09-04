package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ALS-12756: a token must stop working the moment its owner logs out. Logout ends the user's session
 * (CustomLogoutHandler -> CacheEvictionService -> SessionService.endSession), so JWTFilter — the gate in
 * front of every PSAMA endpoint, including the admin API — has to reject a token whose session is gone.
 */
class JWTFilterSessionTest {

    private TOSService tosService;
    private JWTUtil jwtUtil;
    private CustomUserDetailService userDetailsService;
    private SessionService sessionService;
    private JWTFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        tosService = mock(TOSService.class);
        jwtUtil = mock(JWTUtil.class);
        userDetailsService = mock(CustomUserDetailService.class);
        sessionService = mock(SessionService.class);
        filter = new JWTFilter(tosService, "sub", jwtUtil, userDetailsService, sessionService);
        filterChain = mock(FilterChain.class);
    }

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminTokenIsRejectedAfterLogoutEndedTheSession() throws Exception {
        stubUserToken("admin-subject", "admin@example.org", "ADMIN");
        when(sessionService.isSessionExpired("admin-subject")).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "A logged-out token must not be authenticated");
        verify(filterChain, never()).doFilter(any(), any());
    }

    /**
     * Logging back in re-creates the session entry that logout removed, so "a session is live" is not on its own
     * enough to trust a token — the token also has to belong to that session.
     */
    @Test
    void tokenIssuedBeforeTheCurrentSessionIsRejected() throws Exception {
        stubUserToken("admin-subject", "admin@example.org", "ADMIN");
        when(sessionService.isSessionExpired("admin-subject")).thenReturn(false);
        when(sessionService.isTokenIssuedBeforeCurrentSession(eq("admin-subject"), any())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    /**
     * The gateway introspects every resource request with a PSAMA application token. That branch must stay clear of
     * the user-session check, or a single misplaced guard takes down all data access.
     */
    @Test
    void applicationTokenIsNotSubjectToTheUserSessionCheck() throws Exception {
        when(sessionService.isSessionExpired(any())).thenReturn(true);
        when(sessionService.isTokenIssuedBeforeCurrentSession(any(), any())).thenReturn(true);

        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken("app-token")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.get("sub", String.class)).thenReturn(AuthNaming.PSAMA_APPLICATION_TOKEN_PREFIX + "|app-uuid");

        Application application = new Application();
        application.setName("PIC-SURE");
        application.setToken("app-token");
        when(userDetailsService.loadUserByUsername("application:app-uuid")).thenReturn(new CustomApplicationDetails(application));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/token/inspect");
        request.addHeader("Authorization", "Bearer app-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void userTokenIsAcceptedWhileTheSessionIsStillLive() throws Exception {
        stubUserToken("researcher-subject", "researcher@example.org", "QUERY");
        when(sessionService.isSessionExpired("researcher-subject")).thenReturn(false);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user/me");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /**
     * Long-term tokens are user-issued API keys with their own lifecycle (one per user, matched against the
     * database on introspection). They are deliberately exempt from the interactive session, exactly as they are
     * in TokenService.refreshToken and AuthorizationService.isAuthorized.
     */
    @Test
    void longTermTokenSurvivesLogout() throws Exception {
        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken("long-term-token")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        String longTermSubject = AuthNaming.LONG_TERM_TOKEN_PREFIX + "|researcher-subject";
        when(claims.get("sub", String.class)).thenReturn(longTermSubject);
        when(claims.getSubject()).thenReturn(longTermSubject);
        stubUser("researcher-subject", "researcher@example.org", "QUERY");
        when(sessionService.isSessionExpired(any())).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user/me");
        request.addHeader("Authorization", "Bearer long-term-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    private void stubUserToken(String subject, String email, String privilegeName) {
        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken("user-token")).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.get("sub", String.class)).thenReturn(subject);
        when(claims.getSubject()).thenReturn(subject);
        when(claims.getIssuedAt()).thenReturn(new Date());
        stubUser(subject, email, privilegeName);
    }

    private void stubUser(String subject, String email, String privilegeName) {
        Privilege privilege = new Privilege();
        privilege.setName(privilegeName);
        Role role = new Role();
        role.setPrivileges(Set.of(privilege));
        User user = new User();
        user.setSubject(subject);
        user.setEmail(email);
        user.setRoles(Set.of(role));
        when(userDetailsService.loadUserByUsername(subject)).thenReturn(new CustomUserDetails(user));
        when(tosService.hasUserAcceptedLatest(subject)).thenReturn(true);
    }
}
