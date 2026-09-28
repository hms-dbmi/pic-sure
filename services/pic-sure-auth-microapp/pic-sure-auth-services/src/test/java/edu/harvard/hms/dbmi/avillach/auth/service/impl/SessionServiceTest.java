package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {

    private static final String SUBJECT = "researcher";
    private static final long MAX_DURATION = 3_600_000;
    private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager("sessions");
    private final SessionService sessions = new SessionService(MAX_DURATION, cacheManager, null);

    @Test
    void replacingASessionRevokesItsTokensEvenWithIdenticalStartTimes() {
        long startedAt = System.currentTimeMillis();
        cacheManager.getCache("sessions").put(SUBJECT, new SessionService.Session("first", startedAt));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "first"));

        cacheManager.getCache("sessions").put(SUBJECT, new SessionService.Session("second", startedAt));

        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "first"));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "second"));
        assertFalse(sessions.isTokenValidForCurrentSession("another-subject", "second"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "another-session"})
    void invalidSessionIdsCannotAuthenticateOrCleanUp(String id) {
        sessions.startSession(SUBJECT, "current");
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, id));
        assertFalse(sessions.endSessionIfCurrent(SUBJECT, id, () -> fail("Unexpected cleanup")));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
    }

    @Test
    void malformedSessionIdCannotAuthenticateOrCleanUp() {
        sessions.startSession(SUBJECT, "current");
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, 123));
        assertFalse(sessions.endSessionIfCurrent(SUBJECT, 123, () -> fail("Unexpected cleanup")));
    }

    @Test
    void aMissingOrLegacySessionFailsClosed() {
        assertTrue(sessions.isSessionExpired(SUBJECT));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
        cacheManager.getCache("sessions").put(SUBJECT, System.currentTimeMillis());
        assertTrue(sessions.isSessionExpired(SUBJECT));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
    }

    @Test
    void anExpiredSessionCannotAuthenticateButCanStillBeCleanedUp() {
        cacheManager.getCache("sessions").put(SUBJECT,
            new SessionService.Session("current", System.currentTimeMillis() - MAX_DURATION - 60_000));
        assertTrue(sessions.isSessionExpired(SUBJECT));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
        AtomicReference<String> passport = new AtomicReference<>("expired passport");

        assertTrue(sessions.endSessionIfCurrent(SUBJECT, "current", () -> passport.set(null)));

        assertNull(passport.get());
        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
        assertFalse(sessions.endSessionIfCurrent(SUBJECT, "current", () -> fail("Duplicate cleanup")));
    }

    @Test
    void failedCleanupCanBeRetriedWithoutRestoringAuthentication() {
        sessions.startSession(SUBJECT, "current");
        AtomicReference<String> passport = new AtomicReference<>("old passport");
        assertThrows(IllegalStateException.class, () -> sessions.endSessionIfCurrent(SUBJECT, "current", () -> {
            throw new IllegalStateException("Passport storage unavailable");
        }));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
        assertTrue(sessions.isSessionExpired(SUBJECT));

        assertTrue(sessions.endSessionIfCurrent(SUBJECT, "current", () -> passport.set(null)));

        assertNull(passport.get());
        assertNull(cacheManager.getCache("sessions").get(SUBJECT));
        assertFalse(sessions.isTokenValidForCurrentSession(SUBJECT, "current"));
    }

    @Test
    void aCleanupRetryCannotClearAReplacementSession() {
        sessions.startSession(SUBJECT, "old");
        assertThrows(IllegalStateException.class, () -> sessions.endSessionIfCurrent(SUBJECT, "old", () -> {
            throw new IllegalStateException("Passport storage unavailable");
        }));
        sessions.startSession(SUBJECT, "new");

        assertFalse(sessions.endSessionIfCurrent(SUBJECT, "old", () -> fail("Unexpected cleanup")));
        assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "new"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void aBlankSubjectCannotStartOrEndASession(String subject) {
        assertThrows(IllegalArgumentException.class, () -> sessions.startSession(subject, "current"));
        assertFalse(sessions.endSessionIfCurrent(subject, "current", () -> fail("Unexpected cleanup")));
        assertFalse(sessions.isTokenValidForCurrentSession(subject, "current"));
    }

    @Test
    void aReplacementLoginWaitsForAllAcceptedLogoutCleanup() throws Exception {
        sessions.startSession(SUBJECT, "old");
        AtomicReference<String> passport = new AtomicReference<>("old passport");
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch loginAttempted = new CountDownLatch(1);
        AtomicReference<Thread> loginThread = new AtomicReference<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var logout = executor.submit(() -> sessions.endSessionIfCurrent(SUBJECT, "old", () -> {
                cleaning.countDown();
                await(releaseCleanup);
                passport.set(null);
            }));
            try {
                assertTrue(cleaning.await(5, TimeUnit.SECONDS));
                var login = executor.submit(() -> {
                    loginThread.set(Thread.currentThread());
                    loginAttempted.countDown();
                    synchronized (sessions.sessionLock(SUBJECT)) {
                        passport.set("new passport");
                        sessions.startSession(SUBJECT, "new");
                    }
                });
                assertTrue(loginAttempted.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (loginThread.get().getState() != Thread.State.BLOCKED && !login.isDone() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                assertEquals(Thread.State.BLOCKED, loginThread.get().getState());
                releaseCleanup.countDown();
                assertTrue(logout.get(5, TimeUnit.SECONDS));
                login.get(5, TimeUnit.SECONDS);
                assertEquals("new passport", passport.get());
                assertTrue(sessions.isTokenValidForCurrentSession(SUBJECT, "new"));
            } finally {
                releaseCleanup.countDown();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test coordination");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
