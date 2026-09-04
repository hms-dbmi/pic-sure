package edu.harvard.hms.dbmi.avillach.auth.config;

import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomLogoutHandlerTest {

    private UserService userService;
    private CacheEvictionService cacheEvictionService;
    private JWTUtil jwtUtil;
    private SessionService sessionService;
    private CustomLogoutHandler handler;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        cacheEvictionService = mock(CacheEvictionService.class);
        jwtUtil = mock(JWTUtil.class);
        sessionService = mock(SessionService.class);
        handler = new CustomLogoutHandler(userService, cacheEvictionService, jwtUtil, sessionService);
    }

    @Test
    void endsTheSessionForTheTokenSubject() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("admin-subject");
        when(jwtUtil.parseTokenAllowingExpiration("admin-token")).thenReturn(Optional.of(claims));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer admin-token");

        handler.logout(request, new MockHttpServletResponse(), null);

        verify(cacheEvictionService).evictCache("admin-subject");
        verify(userService).removeUserPassport("admin-subject");
    }

    /**
     * The token TTL (application.token.expiration.time, 15 minutes by default) is far shorter than the session
     * (application.max.session.length, 8 hours), so a user returning to an idle tab logs out with an already-expired
     * token. That token is still signed, so it still identifies the session that has to end.
     */
    @Test
    void endsTheSessionEvenWhenTheTokenHasAlreadyExpired() {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("admin-subject");
        when(jwtUtil.parseTokenAllowingExpiration("expired-token")).thenReturn(Optional.of(claims));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer expired-token");

        handler.logout(request, new MockHttpServletResponse(), null);

        verify(cacheEvictionService).evictCache("admin-subject");
        verify(userService).removeUserPassport("admin-subject");
    }

    @Test
    void toleratesAMissingAuthorizationHeader() {
        handler.logout(new MockHttpServletRequest("POST", "/auth/logout"), new MockHttpServletResponse(), null);

        verify(cacheEvictionService, never()).evictCache(anyString());
    }

    @Test
    void toleratesAnUnverifiableToken() {
        when(jwtUtil.parseTokenAllowingExpiration("garbage")).thenReturn(Optional.empty());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer garbage");

        handler.logout(request, new MockHttpServletResponse(), null);

        verify(cacheEvictionService, never()).evictCache(anyString());
    }
}
