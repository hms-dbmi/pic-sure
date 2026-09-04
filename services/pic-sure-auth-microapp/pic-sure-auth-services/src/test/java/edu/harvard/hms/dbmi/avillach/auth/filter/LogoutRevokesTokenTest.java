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
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import java.util.Date;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ALS-12756, over the real session cache: a token that authenticated fine a moment ago must be rejected by
 * JWTFilter once its owner logs out. CustomLogoutHandler, CacheEvictionService, SessionService and its
 * {@code sessions} cache are the real collaborators; JWT parsing, user lookup and the TOS check are stubbed.
 * The handler is invoked directly rather than through the configured SecurityFilterChain, so this does not
 * cover the chain ordering that lets /logout reach the handler.
 */
class LogoutRevokesTokenTest {

    private static final String SUBJECT = "admin-subject";
    private static final String TOKEN = "admin-token";

    private static final long ONE_MINUTE_MS = 60_000L;

    private AnnotationConfigApplicationContext context;
    private CacheManager cacheManager;
    private SessionService sessionService;
    private CustomLogoutHandler logoutHandler;
    private JWTFilter filter;
    private FilterChain filterChain;

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
    void setUp() {
        SecurityContextHolder.clearContext();
        context = new AnnotationConfigApplicationContext(CachingTestConfig.class);
        cacheManager = context.getBean(CacheManager.class);
        sessionService = context.getBean(SessionService.class);

        JWTUtil jwtUtil = mock(JWTUtil.class);
        Claims claims = mock(Claims.class);
        @SuppressWarnings("unchecked")
        Jws<Claims> jws = mock(Jws.class);
        when(jwtUtil.parseToken(TOKEN)).thenReturn(jws);
        when(jws.getPayload()).thenReturn(claims);
        when(claims.get("sub", String.class)).thenReturn(SUBJECT);
        when(claims.getSubject()).thenReturn(SUBJECT);
        when(claims.getIssuedAt()).thenReturn(new Date(System.currentTimeMillis() - ONE_MINUTE_MS));
        when(jwtUtil.parseTokenAllowingExpiration(TOKEN)).thenReturn(java.util.Optional.of(claims));

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

        CacheEvictionService cacheEvictionService = new CacheEvictionService(sessionService, mock(AccessRuleService.class));
        logoutHandler = new CustomLogoutHandler(mock(UserService.class), cacheEvictionService, jwtUtil);
        filter = new JWTFilter(tosService, "sub", jwtUtil, userDetailsService, sessionService);
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void theSameTokenStopsWorkingOnceTheUserLogsOut() throws Exception {
        loginAMinuteAgo();

        MockHttpServletResponse beforeLogout = callAdminEndpoint();
        assertEquals(200, beforeLogout.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication(), "Token should work while logged in");

        logout();
        SecurityContextHolder.clearContext();

        MockHttpServletResponse afterLogout = callAdminEndpoint();
        assertEquals(401, afterLogout.getStatus(), "The token must be revoked the moment the user logs out");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Sessions are keyed by subject alone, so logging back in re-creates the entry that logout removed. A token
     * issued for the session the user abandoned must not come back to life along with it.
     */
    @Test
    void theLoggedOutTokenStaysDeadAfterTheUserLogsBackIn() throws Exception {
        loginAMinuteAgo();
        logout();
        SecurityContextHolder.clearContext();

        sessionService.startSession(SUBJECT);

        MockHttpServletResponse afterSecondLogin = callAdminEndpoint();
        assertEquals(401, afterSecondLogin.getStatus(), "A token from the abandoned session must not be revived by a new login");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * Back-dates the first login so the second one is distinguishable from it. {@code startSession} stamps
     * {@code System.currentTimeMillis()}, and the cache is the same store its {@code @CachePut} writes to.
     */
    private void loginAMinuteAgo() {
        Cache sessions = cacheManager.getCache("sessions");
        assertNotNull(sessions);
        sessions.put(SUBJECT, System.currentTimeMillis() - ONE_MINUTE_MS);
    }

    private MockHttpServletResponse callAdminEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/auth/user");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        return response;
    }

    private void logout() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/logout");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        logoutHandler.logout(request, new MockHttpServletResponse(), null);
    }
}
