package edu.harvard.hms.dbmi.avillach.auth.filter;

import edu.harvard.hms.dbmi.avillach.auth.config.CustomLogoutHandler;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CustomUserDetailService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises signed user tokens through JWTFilter and the logout handler with the real session cache.
 * Invoking the handler directly does not cover SecurityFilterChain ordering.
 */
class LogoutRevokesTokenTest {

    private static final String SUBJECT = "admin-subject";
    private static final String SESSION_ID = "current-session";
    private static final String SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";

    private ConcurrentMapCacheManager cacheManager;
    private SessionService sessionService;
    private JWTUtil jwtUtil;
    private long issuedAt;
    private String token;
    private UserService userService;
    private AccessRuleService accessRuleService;
    private CustomLogoutHandler logoutHandler;
    private JWTFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cacheManager = new ConcurrentMapCacheManager("sessions");
        sessionService = new SessionService(8 * 60 * 60 * 1000L, cacheManager, null);
        jwtUtil = new JWTUtil(SECRET, false);
        issuedAt = System.currentTimeMillis() / 1000 * 1000;
        token = token(SESSION_ID, issuedAt + 3_600_000);

        Privilege privilege = new Privilege();
        privilege.setName("ADMIN");
        Role role = new Role();
        role.setPrivileges(Set.of(privilege));
        User user = new User();
        user.setSubject(SUBJECT);
        user.setEmail("admin@example.org");
        user.setRoles(Set.of(role));

        CustomUserDetailService userDetailsService = mock(CustomUserDetailService.class);
        when(userDetailsService.loadUserByUsername(SUBJECT)).thenReturn(new CustomUserDetails(user));
        TOSService tosService = mock(TOSService.class);
        when(tosService.hasUserAcceptedLatest(SUBJECT)).thenReturn(true);
        accessRuleService = mock(AccessRuleService.class);
        CacheEvictionService cacheEvictionService = new CacheEvictionService(sessionService, accessRuleService);
        userService = mock(UserService.class);
        logoutHandler = new CustomLogoutHandler(userService, cacheEvictionService, jwtUtil, sessionService);
        filter = new JWTFilter(tosService, "sub", jwtUtil, userDetailsService, sessionService);
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theSameTokenStopsWorkingOnceTheUserLogsOut() throws Exception {
        sessionService.startSession(SUBJECT, SESSION_ID);

        assertEquals(200, callAdminEndpoint(token).getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());

        logout(token);
        SecurityContextHolder.clearContext();
        clearInvocations(filterChain);

        assertEquals(401, callAdminEndpoint(token).getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void matchingSessionIdAuthenticatesAndLogsOutWithoutAnIssuedAtClaim() throws Exception {
        sessionService.startSession(SUBJECT, SESSION_ID);
        String tokenWithoutIssuedAt = Jwts.builder().subject(SUBJECT).claim("sid", SESSION_ID)
            .expiration(new Date(issuedAt + 3_600_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertEquals(200, callAdminEndpoint(tokenWithoutIssuedAt).getStatus());
        logout(tokenWithoutIssuedAt);

        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
        verify(userService).removeUserPassport(SUBJECT);
    }

    @Test
    void theLoggedOutTokenStaysDeadAfterLoginWithTheSameIssuedAt() throws Exception {
        sessionService.startSession(SUBJECT, SESSION_ID);
        logout(token);
        String newToken = token("new-session", issuedAt + 3_600_000);
        sessionService.startSession(SUBJECT, "new-session");
        assertEquals(jwtUtil.parseToken(token).getPayload().getIssuedAt(), jwtUtil.parseToken(newToken).getPayload().getIssuedAt());

        assertEquals(401, callAdminEndpoint(token).getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, callAdminEndpoint(newToken).getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * LogoutFilter runs before JWTFilter, so logout must enforce session ownership itself before clearing
     * the current session's passport and authorization caches.
     */
    @Test
    void anOldTokenWithTheSameIssuedAtCannotClearTheNewSessionOrPassport() {
        sessionService.startSession(SUBJECT, SESSION_ID);
        logout(token);
        clearInvocations(userService, accessRuleService);
        sessionService.startSession(SUBJECT, "new-session");

        logout(token);

        assertFalse(sessionService.isSessionExpired(SUBJECT));
        assertEquals("new-session", cacheManager.getCache("sessions").get(SUBJECT, SessionService.Session.class).id());
        verify(userService, never()).removeUserPassport(SUBJECT);
        verify(accessRuleService, never()).evictFromMergedAccessRuleCache(SUBJECT);
        verify(accessRuleService, never()).evictFromPreProcessedAccessRules(SUBJECT);
    }

    static Stream<Arguments> invalidSessionIds() {
        return Stream.of(
            Arguments.of("missing", null),
            Arguments.of("empty", ""),
            Arguments.of("blank", " "),
            Arguments.of("numeric", 123),
            Arguments.of("object", Map.of("id", SESSION_ID))
        );
    }

    @ParameterizedTest(name = "{0} session ID")
    @MethodSource("invalidSessionIds")
    void malformedSessionIdsCannotAuthenticateOrLogOut(String scenario, Object sessionId) throws Exception {
        sessionService.startSession(SUBJECT, SESSION_ID);
        String invalidToken = token(sessionId, issuedAt + 3_600_000);

        assertEquals(401, callAdminEndpoint(invalidToken).getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain, never()).doFilter(any(), any());
        logout(invalidToken);

        assertFalse(sessionService.isSessionExpired(SUBJECT));
        verify(userService, never()).removeUserPassport(SUBJECT);
        verify(accessRuleService, never()).evictFromMergedAccessRuleCache(SUBJECT);
        verify(accessRuleService, never()).evictFromPreProcessedAccessRules(SUBJECT);
    }

    @Test
    void passportCleanupCanBeRetriedAfterFailureButTheTokenStaysRevoked() throws Exception {
        sessionService.startSession(SUBJECT, SESSION_ID);
        doThrow(new IllegalStateException("Passport storage unavailable")).doNothing()
            .when(userService).removeUserPassport(SUBJECT);

        assertThrows(IllegalStateException.class, () -> logout(token));

        assertEquals(401, callAdminEndpoint(token).getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        logout(token);

        verify(userService, times(2)).removeUserPassport(SUBJECT);
        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
        assertEquals(401, callAdminEndpoint(token).getStatus());
    }

    @Test
    void expiredTokenCanClearItsExpiredSessionAndPassport() {
        long sessionStart = System.currentTimeMillis() - 9 * 60 * 60 * 1000L;
        cacheManager.getCache("sessions").put(SUBJECT, new SessionService.Session(SESSION_ID, sessionStart));
        String expiredToken = token(SESSION_ID, issuedAt - 60_000);

        logout(expiredToken);

        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
        verify(userService).removeUserPassport(SUBJECT);
        verify(accessRuleService).evictFromMergedAccessRuleCache(SUBJECT);
        verify(accessRuleService).evictFromPreProcessedAccessRules(SUBJECT);
    }

    @Test
    void expiredTokenCannotClearADifferentExpiredSession() {
        long sessionStart = System.currentTimeMillis() - 9 * 60 * 60 * 1000L;
        cacheManager.getCache("sessions").put(SUBJECT, new SessionService.Session("new-session", sessionStart));

        logout(token(SESSION_ID, issuedAt - 60_000));

        assertNotNull(cacheManager.getCache("sessions").get(SUBJECT));
        verify(userService, never()).removeUserPassport(SUBJECT);
        verify(accessRuleService, never()).evictFromMergedAccessRuleCache(SUBJECT);
        verify(accessRuleService, never()).evictFromPreProcessedAccessRules(SUBJECT);
    }

    private String token(Object sessionId, long expiresAt) {
        return Jwts.builder().subject(SUBJECT).claim("sid", sessionId)
            .issuedAt(new Date(issuedAt)).expiration(new Date(expiresAt))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private MockHttpServletResponse callAdminEndpoint(String bearerToken) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user");
        request.addHeader("Authorization", "Bearer " + bearerToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private void logout(String bearerToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer " + bearerToken);
        logoutHandler.logout(request, new MockHttpServletResponse(), null);
    }
}
