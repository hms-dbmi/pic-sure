package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserClaims;
import edu.harvard.hms.dbmi.avillach.auth.repository.ConnectionRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserRepository;
import edu.harvard.hms.dbmi.avillach.auth.utils.FenceMappingUtility;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserLoginSessionTest {

    private static final String SUBJECT = "researcher";
    private final JWTUtil jwtUtil = new JWTUtil("a-test-secret-long-enough-for-hmac-sha-256", false);
    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("sessions");
    private final SessionService sessions = new SessionService(3_600_000, cacheManager, null);
    private final TOSService tos = mock(TOSService.class);

    @Test
    void aLoginReturnsASignedTokenWhoseSessionIsAlreadyActive() {
        var response = users(jwtUtil).getUserProfileResponse(claims());
        var tokenClaims = jwtUtil.parseToken(response.get("token")).getPayload();

        assertEquals(SUBJECT, response.get("userId"));
        assertEquals(SUBJECT, tokenClaims.getSubject());
        assertNotNull(tokenClaims.getIssuedAt());
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, tokenClaims.get("sid")));
    }

    @Test
    void everySuccessfulLoginGetsANewSessionId() {
        UserService users = users(jwtUtil);
        String firstToken = users.getUserProfileResponse(claims()).get("token");
        String secondToken = users.getUserProfileResponse(claims()).get("token");
        Object firstId = jwtUtil.parseToken(firstToken).getPayload().get("sid");
        Object secondId = jwtUtil.parseToken(secondToken).getPayload().get("sid");

        assertNotEquals(firstId, secondId);
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, firstId));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, secondId));
    }

    @Test
    void aSigningFailureDoesNotPublishASession() {
        UserService users = users(new JWTUtil("short", false));
        assertThrows(RuntimeException.class, () -> users.getUserProfileResponse(claims()));
        assertTrue(sessions.isSessionExpired(SUBJECT));
    }

    @Test
    void aFailedProfileResponseDoesNotPublishASession() {
        when(tos.hasUserAcceptedLatest(SUBJECT)).thenThrow(new IllegalStateException("TOS storage unavailable"));
        assertThrows(IllegalStateException.class, () -> users(jwtUtil).getUserProfileResponse(claims()));
        assertTrue(sessions.isSessionExpired(SUBJECT));
    }

    @Test
    void aBlankSubjectDoesNotStartASession() {
        UserClaims claims = claims();
        claims.setSub(" ");
        assertNull(users(jwtUtil).getUserProfileResponse(claims));
        assertNull(cacheManager.getCache("sessions").get(" "));
    }

    private UserService users(JWTUtil signer) {
        return new UserService(mock(BasicMailService.class), tos, mock(UserRepository.class),
            mock(ConnectionRepository.class), mock(RoleService.class), mock(UserConsentsRepository.class),
            mock(FenceMappingUtility.class), 3_600_000, 86_400_000, signer, "ADMIN", null, sessions);
    }

    private UserClaims claims() {
        UserClaims claims = new UserClaims();
        claims.setSub(SUBJECT);
        claims.setEmail("researcher@example.org");
        claims.setUuid("test-user-uuid");
        claims.setRoles(List.of());
        return claims;
    }
}
