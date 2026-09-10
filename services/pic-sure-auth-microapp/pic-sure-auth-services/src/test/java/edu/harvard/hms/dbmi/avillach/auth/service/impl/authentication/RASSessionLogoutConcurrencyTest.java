package edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.config.CustomLogoutHandler;
import edu.harvard.hms.dbmi.avillach.auth.entity.Connection;
import edu.harvard.hms.dbmi.avillach.auth.entity.User;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.Passport;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.CacheEvictionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ConnectionWebService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RASPassPortService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TOSService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import edu.harvard.hms.dbmi.avillach.auth.utils.RestClientUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RASSessionLogoutConcurrencyTest {

    private static final String SUBJECT = "ras|researcher";
    private static final String ISSUER = "https://ras.example.test";
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    @Timeout(20)
    void concurrentLoginsKeepPassportAndSessionTogetherAndOldLogoutCannotClearTheReplacement() throws Exception {
        SessionService sessions = new SessionService(8 * 60 * 60 * 1000, new ConcurrentMapCacheManager("sessions"), null);
        JWTUtil jwt = new JWTUtil("a-test-client-secret-long-enough-for-hmac-sha-256", false);
        UserService users = spy(new UserService(null, mock(TOSService.class), null, null, null, null, null,
            60_000, 60_000, jwt, "", null, sessions));
        CacheEvictionService eviction = new CacheEvictionService(sessions, mock(AccessRuleService.class));
        Connection connection = new Connection();
        connection.setLabel("RAS");
        ConnectionWebService connections = mock(ConnectionWebService.class);
        when(connections.getConnectionByLabel("RAS")).thenReturn(connection);
        RASPassPortService passports = mock(RASPassPortService.class);
        Passport passport = new Passport();
        passport.setIss(ISSUER);
        passport.setGa4ghPassportV1(List.of());
        when(passports.extractPassport(any())).thenReturn(Optional.of(passport));

        RASAuthenticationService ras = spy(new RASAuthenticationService(users, mock(RestClientUtil.class), true,
            "ras.example.test", "ras", "client", "secret", ISSUER, passports, connections, eviction, sessions));
        ObjectMapper mapper = new ObjectMapper();
        doAnswer(invocation -> mapper.createObjectNode().put("code", invocation.getArgument(1, String.class)))
            .when(ras).handleCodeTokenExchange(anyString(), anyString());
        doAnswer(invocation -> {
            String code = invocation.getArgument(0, JsonNode.class).get("code").asText();
            return mapper.createObjectNode().put("active", true).put("userid", "researcher")
                .put("preferred_username", "researcher").put("passport_jwt_v11", "passport-" + code);
        }).when(ras).introspectToken(any(JsonNode.class));
        doAnswer(invocation -> Optional.of(user())).when(users).createRasUser(any(), any());
        doAnswer(invocation -> invocation.getArgument(1)).when(ras).updateRasUserRoles(anyString(), any(), any());

        CountDownLatch firstPassportSaved = new CountDownLatch(1);
        CountDownLatch releaseFirstLogin = new CountDownLatch(1);
        AtomicReference<String> storedPassport = new AtomicReference<>();
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            storedPassport.set(user.getPassport());
            if ("\"passport-first\"".equals(user.getPassport())) {
                firstPassportSaved.countDown();
                assertTrue(releaseFirstLogin.await(5, TimeUnit.SECONDS));
            }
            return user;
        }).when(users).save(any(User.class));

        FutureTask<HashMap<String, String>> first = new FutureTask<>(() -> ras.authenticate(Map.of("code", "first"), "localhost"));
        FutureTask<HashMap<String, String>> second = new FutureTask<>(() -> ras.authenticate(Map.of("code", "second"), "localhost"));
        Thread firstThread = new Thread(first, "first-ras-login");
        Thread secondThread = new Thread(second, "second-ras-login");
        firstThread.start();
        try {
            assertTrue(firstPassportSaved.await(5, TimeUnit.SECONDS));
            secondThread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            ThreadInfo waiting = ManagementFactory.getThreadMXBean().getThreadInfo(secondThread.threadId());
            while (secondThread.isAlive() && (waiting == null || waiting.getLockOwnerId() != firstThread.threadId())
                && System.nanoTime() < deadline) {
                Thread.sleep(1);
                waiting = ManagementFactory.getThreadMXBean().getThreadInfo(secondThread.threadId());
            }
            assertNotNull(waiting);
            assertEquals(firstThread.threadId(), waiting.getLockOwnerId(),
                "The second login must wait until the first publishes the session belonging to its passport");
            assertEquals("\"passport-first\"", storedPassport.get());
        } finally {
            releaseFirstLogin.countDown();
            firstThread.join(5_000);
            secondThread.join(5_000);
        }

        String firstToken = first.get(5, TimeUnit.SECONDS).get("token");
        String secondToken = second.get(5, TimeUnit.SECONDS).get("token");
        Object firstSid = jwt.parseToken(firstToken).getPayload().get("sid");
        Object secondSid = jwt.parseToken(secondToken).getPayload().get("sid");
        assertNotEquals(firstSid, secondSid);
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, firstSid));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, secondSid));
        assertEquals("\"passport-second\"", storedPassport.get());

        MockHttpServletRequest logout = new MockHttpServletRequest();
        logout.addHeader("Authorization", "Bearer " + firstToken);
        new CustomLogoutHandler(users, eviction, jwt, sessions).logout(logout, new MockHttpServletResponse(), null);

        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, secondSid));
        assertEquals("\"passport-second\"", storedPassport.get());
        verify(users, never()).removeUserPassport(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedLoginPreservesLogoutCleanupForLiveAndRevokedSessions(boolean earlierCleanupFailed) {
        ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("sessions");
        SessionService sessions = new SessionService(3_600_000, cacheManager, null);
        UserService users = mock(UserService.class);
        User existingUser = user();
        existingUser.setPassport("original passport");
        when(users.createRasUser(any(), any())).thenReturn(Optional.of(existingUser));
        CacheEvictionService eviction = new CacheEvictionService(sessions, mock(AccessRuleService.class));
        Connection connection = new Connection();
        connection.setLabel("RAS");
        ConnectionWebService connections = mock(ConnectionWebService.class);
        when(connections.getConnectionByLabel("RAS")).thenReturn(connection);
        RASPassPortService passports = mock(RASPassPortService.class);
        when(passports.extractPassport(any())).thenReturn(Optional.empty());
        RASAuthenticationService ras = spy(new RASAuthenticationService(users, mock(RestClientUtil.class), true,
            "ras.example.test", "ras", "client", "secret", ISSUER, passports, connections, eviction, sessions));
        JsonNode providerResponse = new ObjectMapper().createObjectNode().put("active", true);
        doReturn(providerResponse).when(ras).handleCodeTokenExchange(anyString(), anyString());
        doReturn(providerResponse).when(ras).introspectToken(any(JsonNode.class));

        JWTUtil jwt = new JWTUtil("a-test-client-secret-long-enough-for-hmac-sha-256", false);
        String token = jwt.createJwtToken("test", "test", Map.of("sid", "original"), SUBJECT, 60_000);
        sessions.startSession(SUBJECT, "original");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        CustomLogoutHandler logout = new CustomLogoutHandler(users, eviction, jwt, sessions);
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "original"));
        if (earlierCleanupFailed) {
            doThrow(new IllegalStateException("Passport storage unavailable")).when(users).removeUserPassport(SUBJECT);
            assertThrows(IllegalStateException.class, () -> logout.logout(request, new MockHttpServletResponse(), null));
        }
        doAnswer(invocation -> {
            existingUser.setPassport(null);
            return null;
        }).when(users).removeUserPassport(SUBJECT);

        assertNull(ras.authenticate(Map.of("code", "failed-login"), "localhost"));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "original"));
        assertTrue(sessions.isSessionExpired(SUBJECT));
        assertEquals("original passport", existingUser.getPassport());
        logout.logout(request, new MockHttpServletResponse(), null);

        assertNull(existingUser.getPassport());
        verify(users, times(earlierCleanupFailed ? 2 : 1)).removeUserPassport(SUBJECT);
        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
    }

    private User user() {
        User user = new User();
        user.setUuid(USER_ID);
        user.setSubject(SUBJECT);
        user.setEmail("researcher@example.test");
        user.setRoles(new HashSet<>());
        return user;
    }
}
