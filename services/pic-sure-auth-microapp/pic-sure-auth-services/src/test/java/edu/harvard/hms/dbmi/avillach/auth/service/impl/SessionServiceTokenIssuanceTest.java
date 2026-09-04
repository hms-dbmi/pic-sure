package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boundaries of the ALS-12756 check that keeps a token from a logged-out session from being revived by the next
 * login. The cache is the real one {@code @CachePut}/{@code @CacheEvict} write to, so entries are set directly.
 */
class SessionServiceTokenIssuanceTest {

    private static final String SUBJECT = "admin-subject";
    private static final long EIGHT_HOURS_MS = 8 * 60 * 60 * 1000L;

    private ConcurrentMapCacheManager cacheManager;
    private SessionService sessionService;

    @BeforeEach
    void setUp() {
        cacheManager = new ConcurrentMapCacheManager("sessions");
        sessionService = new SessionService(EIGHT_HOURS_MS, cacheManager, null);
    }

    @Test
    void aTokenOlderThanTheCurrentSessionBelongsToAPreviousOne() {
        sessionStartedAt(System.currentTimeMillis());

        assertTrue(sessionService.isTokenIssuedBeforeCurrentSession(SUBJECT, new Date(System.currentTimeMillis() - 60_000)));
    }

    @Test
    void aTokenFromTheCurrentSessionIsAccepted() {
        sessionStartedAt(System.currentTimeMillis() - 60_000);

        assertFalse(sessionService.isTokenIssuedBeforeCurrentSession(SUBJECT, new Date(System.currentTimeMillis() - 30_000)));
    }

    /**
     * The token is minted just before the session is recorded and its {@code iat} is truncated to whole seconds, so a
     * token stamped a fraction before its own session's start still belongs to it.
     */
    @Test
    void aTokenStampedJustBeforeItsOwnSessionStartIsAccepted() {
        long now = System.currentTimeMillis();
        sessionStartedAt(now);

        assertFalse(sessionService.isTokenIssuedBeforeCurrentSession(SUBJECT, new Date(now - 1_500)));
    }

    @Test
    void withoutASessionThereIsNothingToCompareAgainst() {
        assertFalse(sessionService.isTokenIssuedBeforeCurrentSession(SUBJECT, new Date(0)));
    }

    @Test
    void aTokenWithoutAnIssuedAtClaimIsLeftToTheSessionExpiryCheck() {
        sessionStartedAt(System.currentTimeMillis());

        assertFalse(sessionService.isTokenIssuedBeforeCurrentSession(SUBJECT, null));
    }

    private void sessionStartedAt(long epochMillis) {
        cacheManager.getCache("sessions").put(SUBJECT, epochMillis);
    }
}
