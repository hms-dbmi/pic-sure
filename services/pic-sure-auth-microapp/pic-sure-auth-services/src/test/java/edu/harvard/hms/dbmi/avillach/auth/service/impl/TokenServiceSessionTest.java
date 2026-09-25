package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.Application;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.EvaluateAccessRuleResult;
import edu.harvard.hms.dbmi.avillach.auth.model.InvalidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceSessionTest {

    private static final String SUBJECT = "researcher-subject";
    private static final String SESSION_ID = "current-session";
    private static final String SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";
    private static final long SESSION_DURATION = 8 * 60 * 60 * 1000L;

    private ConcurrentMapCacheManager cacheManager;
    private SessionService sessionService;
    private JWTUtil jwtUtil;
    private UserService userService;
    private User user;
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager("sessions");
        sessionService = new SessionService(SESSION_DURATION, cacheManager, null);
        jwtUtil = new JWTUtil(SECRET, false);
        userService = mock(UserService.class);
        user = new User();
        user.setSubject(SUBJECT);
        user.setActive(true);
        user.setRoles(Set.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findBySubject(SUBJECT)).thenReturn(user);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAuthorized(any(), any(), any(), anyBoolean()))
            .thenReturn(new EvaluateAccessRuleResult(true, Set.of(), "test-rule"));
        tokenService = new TokenService(authorizationService, userRepository, 3_600_000, jwtUtil, sessionService, userService);
        Application application = new Application();
        application.setPrivileges(Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CustomApplicationDetails(application), null
        ));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    static Stream<Arguments> sessionCases() {
        return Stream.of(
            Arguments.of("missing session", false, -60_000L, SESSION_ID, false),
            Arguments.of("expired session", true, -32_400_000L, SESSION_ID, false),
            Arguments.of("missing session ID", true, -60_000L, null, false),
            Arguments.of("blank session ID", true, -60_000L, " ", false),
            Arguments.of("empty session ID", true, -60_000L, "", false),
            Arguments.of("numeric session ID", true, -60_000L, 123, false),
            Arguments.of("object session ID", true, -60_000L, Map.of("id", SESSION_ID), false),
            Arguments.of("previous-session token", true, -60_000L, "previous-session", false),
            Arguments.of("current-session token", true, -60_000L, SESSION_ID, true)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sessionCases")
    void introspectionAndRefreshRequireAnExactLiveSession(
        String scenario, boolean hasSession, long sessionOffset, Object tokenSessionId, boolean valid
    ) {
        long now = System.currentTimeMillis() / 1000 * 1000;
        if (hasSession) {
            cacheManager.getCache("sessions").put(SUBJECT, new SessionService.Session(SESSION_ID, now + sessionOffset));
        }
        String token = token(tokenSessionId, now);

        Map<String, Object> inspection = tokenService.inspectToken(new HashMap<>(Map.of("token", token)));

        assertEquals(valid, inspection.get("active"));
        if (valid) {
            ValidRefreshToken refresh = assertInstanceOf(ValidRefreshToken.class, tokenService.refreshToken(token));
            assertEquals(SESSION_ID, jwtUtil.parseToken(refresh.token()).getPayload().get("sid"));
        } else {
            assertEquals("Your session has expired. Please log in again.", inspection.get("message"));
            InvalidRefreshToken refresh = assertInstanceOf(InvalidRefreshToken.class, tokenService.refreshToken(token));
            assertEquals("Your session has expired. Please log in again.", refresh.error());
        }
    }

    @Test
    void matchingSessionIdDoesNotRequireAnIssuedAtClaim() {
        sessionService.startSession(SUBJECT, SESSION_ID);
        String token = token(SESSION_ID, null);

        assertEquals(true, tokenService.inspectToken(new HashMap<>(Map.of("token", token))).get("active"));
        ValidRefreshToken refresh = assertInstanceOf(ValidRefreshToken.class, tokenService.refreshToken(token));
        assertEquals(SESSION_ID, jwtUtil.parseToken(refresh.token()).getPayload().get("sid"));
    }

    @Test
    void aSessionRetainedForCleanupCannotAuthenticateOrRefresh() {
        cacheManager.getCache("sessions").put(SUBJECT,
            new SessionService.Session(SESSION_ID, System.currentTimeMillis(), true));
        String token = token(SESSION_ID, System.currentTimeMillis());

        assertEquals(Boolean.FALSE, tokenService.inspectToken(new HashMap<>(Map.of("token", token))).get("active"));
        assertInstanceOf(InvalidRefreshToken.class, tokenService.refreshToken(token));
    }

    @Test
    void tokensMintedInTheSameSecondBelongToDifferentSessions() {
        long issuedAt = System.currentTimeMillis() / 1000 * 1000;
        String oldToken = token("previous-session", issuedAt);
        String currentToken = token(SESSION_ID, issuedAt);
        sessionService.startSession(SUBJECT, SESSION_ID);
        assertEquals(jwtUtil.parseToken(oldToken).getPayload().getIssuedAt(), jwtUtil.parseToken(currentToken).getPayload().getIssuedAt());

        assertEquals(false, tokenService.inspectToken(new HashMap<>(Map.of("token", oldToken))).get("active"));
        assertInstanceOf(InvalidRefreshToken.class, tokenService.refreshToken(oldToken));
        assertEquals(true, tokenService.inspectToken(new HashMap<>(Map.of("token", currentToken))).get("active"));
        assertInstanceOf(ValidRefreshToken.class, tokenService.refreshToken(currentToken));
    }

    @Test
    void refreshCannotAdoptASessionStartedAfterItsValidation() {
        sessionService.startSession(SUBJECT, SESSION_ID);
        String token = token(SESSION_ID, System.currentTimeMillis());
        when(userService.addRoleClaims(user)).thenAnswer(invocation -> {
            sessionService.startSession(SUBJECT, "new-session");
            return List.of();
        });

        ValidRefreshToken refresh = assertInstanceOf(ValidRefreshToken.class, tokenService.refreshToken(token));

        Object refreshedSessionId = jwtUtil.parseToken(refresh.token()).getPayload().get("sid");
        assertEquals(SESSION_ID, refreshedSessionId);
        assertFalse(sessionService.isTokenValidForCurrentSession(SUBJECT, refreshedSessionId));
        assertEquals(false, tokenService.inspectToken(new HashMap<>(Map.of("token", refresh.token()))).get("active"));
        assertInstanceOf(InvalidRefreshToken.class, tokenService.refreshToken(refresh.token()));
    }

    private String token(Object sessionId, Long issuedAt) {
        return Jwts.builder().subject(SUBJECT).claim("sid", sessionId)
            .issuedAt(issuedAt == null ? null : new Date(issuedAt)).expiration(new Date(System.currentTimeMillis() + 3_600_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
