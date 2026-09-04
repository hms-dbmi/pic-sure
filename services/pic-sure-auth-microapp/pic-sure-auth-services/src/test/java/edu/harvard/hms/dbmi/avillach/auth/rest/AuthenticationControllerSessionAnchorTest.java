package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.service.AuthenticationService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A login must leave the token it just issued usable. The session is anchored to that token's {@code iat}, so if the
 * anchor is ever taken from the wall clock instead, the new token is stamped fractionally before its own session and
 * SessionService reads it as belonging to a previous one — the user gets a token that 401s on its very next request.
 */
class AuthenticationControllerSessionAnchorTest {

    private static final String USER_ID = "researcher-subject";
    private static final String TOKEN = "freshly-minted-token";

    private AnnotationConfigApplicationContext context;
    private SessionService sessionService;
    private JWTUtil jwtUtil;
    private AuthenticationController controller;

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        public CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("sessions");
        }

        @Bean
        public SessionService sessionService(CacheManager cacheManager) {
            return new SessionService(8 * 60 * 60 * 1000L, cacheManager, null);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        context = new AnnotationConfigApplicationContext(CachingTestConfig.class);
        sessionService = context.getBean(SessionService.class);
        jwtUtil = mock(JWTUtil.class);

        AuthenticationService authenticationService = mock(AuthenticationService.class);
        HashMap<String, String> authenticated = new HashMap<>(Map.of("userId", USER_ID, "token", TOKEN));
        when(authenticationService.authenticate(any(), anyString())).thenReturn(authenticated);
        AuthenticationServiceRegistry registry = mock(AuthenticationServiceRegistry.class);
        when(registry.getAuthenticationService("okta")).thenReturn(authenticationService);

        controller = new AuthenticationController(registry, sessionService, jwtUtil);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void theSessionStartsAtTheIssuedAtOfTheTokenTheLoginReturned() throws Exception {
        Date issuedAt = new Date(System.currentTimeMillis() / 1000 * 1000);
        stubTokenIssuedAt(issuedAt);

        login();

        assertEquals(issuedAt.getTime(), context.getBean(CacheManager.class).getCache("sessions").get(USER_ID).get());
        assertFalse(sessionService.isTokenIssuedBeforeCurrentSession(USER_ID, issuedAt));
    }

    /**
     * If the issued-at cannot be read the session has to be anchored no later than the token that opened it, or the
     * login silently hands back a token that is already invalid.
     */
    @Test
    void aTokenWhoseIssuedAtCannotBeReadStillOpensAUsableSession() throws Exception {
        when(jwtUtil.parseToken(TOKEN)).thenThrow(new RuntimeException("unreadable"));
        Date issuedAtOfTheReturnedToken = new Date(System.currentTimeMillis() / 1000 * 1000);

        login();

        assertFalse(
            sessionService.isTokenIssuedBeforeCurrentSession(USER_ID, issuedAtOfTheReturnedToken),
            "the token the login just returned must not be read as belonging to a previous session"
        );
    }

    private void login() throws Exception {
        controller.authentication("okta", Map.of("code", "abc"), new MockHttpServletRequest("POST", "/auth/authentication/okta"));
    }

    private void stubTokenIssuedAt(Date issuedAt) {
        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken(TOKEN)).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.getIssuedAt()).thenReturn(issuedAt);
    }
}
