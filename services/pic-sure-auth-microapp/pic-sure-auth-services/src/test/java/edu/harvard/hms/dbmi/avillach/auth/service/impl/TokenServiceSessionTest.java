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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenServiceSessionTest {

    private static final String SUBJECT = "researcher-subject";
    private static final String SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";
    private static final long SESSION_DURATION = 8 * 60 * 60 * 1000L;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "missing session, false, -60000, -60000, false",
        "expired session, true, -32400000, -60000, false",
        "missing issued-at, true, -60000, , false",
        "previous-session token, true, -60000, -120000, false",
        "login token, true, -60000, -60000, true",
        "refreshed token, true, -60000, -30000, true"
    })
    void introspectionAndRefreshRequireACurrentSession(
        String scenario, boolean hasSession, long sessionOffset, Long issuedAtOffset, boolean valid
    ) {
        long now = System.currentTimeMillis() / 1000 * 1000;
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("sessions");
        if (hasSession) {
            cacheManager.getCache("sessions").put(SUBJECT, now + sessionOffset);
        }
        SessionService sessionService = new SessionService(SESSION_DURATION, cacheManager, null);
        User user = new User();
        user.setSubject(SUBJECT);
        user.setActive(true);
        user.setRoles(Set.of());
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findBySubject(SUBJECT)).thenReturn(user);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.isAuthorized(any(), any(), any(), anyBoolean()))
            .thenReturn(new EvaluateAccessRuleResult(true, Set.of(), "test-rule"));
        TokenService tokenService = new TokenService(
            authorizationService, userRepository, 3_600_000, new JWTUtil(SECRET, false), sessionService, mock(UserService.class)
        );
        Application application = new Application();
        application.setPrivileges(Set.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new CustomApplicationDetails(application), null
        ));
        String token = Jwts.builder().subject(SUBJECT)
            .issuedAt(issuedAtOffset == null ? null : new Date(now + issuedAtOffset))
            .expiration(new Date(now + 3_600_000))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        Map<String, Object> inspection = tokenService.inspectToken(new HashMap<>(Map.of("token", token)));

        assertEquals(valid, inspection.get("active"));
        if (valid) {
            assertInstanceOf(ValidRefreshToken.class, tokenService.refreshToken(token));
        } else {
            assertEquals("Your session has expired. Please log in again.", inspection.get("message"));
            InvalidRefreshToken refresh = assertInstanceOf(InvalidRefreshToken.class, tokenService.refreshToken(token));
            assertEquals("Your session has expired. Please log in again.", refresh.error());
        }
    }
}
