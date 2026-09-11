package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);

    private final long sessionMaxDuration;
    private final CacheManager cacheManager;
    private final LoggingClient loggingClient;
    private final Object[] sessionLocks = new Object[256];

    public record Session(String id, long startedAt, boolean revoked) {
        public Session(String id, long startedAt) {
            this(id, startedAt, false);
        }
    }

    public SessionService(@Value("${application.max.session.length}") long sessionMaxDuration, CacheManager cacheManager,
                          LoggingClient loggingClient) {
        this.sessionMaxDuration = sessionMaxDuration > 0 ? sessionMaxDuration : 8 * 60 * 60 * 1000; // 8 hours in milliseconds
        this.cacheManager = cacheManager;
        this.loggingClient = loggingClient;
        Arrays.setAll(sessionLocks, index -> new Object());
    }

    /**
     * Use the same monitor for login finalization and logout cleanup so an old logout
     * cannot remove a new login's passport.
     * Stripes bound lock storage without removing a lock while another request is waiting for it.
     * Coordination is local to this process, like the session cache.
     */
    public Object sessionLock(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            throw new IllegalArgumentException("User subject must not be blank");
        }
        return sessionLocks[Math.floorMod(userSubject.hashCode(), sessionLocks.length)];
    }

    public void startSession(String userSubject, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Session ID must not be blank");
        }
        synchronized (sessionLock(userSubject)) {
            Objects.requireNonNull(cacheManager.getCache("sessions"), "Session cache is unavailable")
                .put(userSubject, new Session(sessionId, System.currentTimeMillis()));
        }
        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(LoggingEvent.builder("AUTH").action("session.start")
                    .metadata(Map.of("user_subject", userSubject))
                    .build());
            } catch (Exception e) {
                logger.warn("Failed to send SESSION_START audit log event", e);
            }
        }
    }

    public void endSession(String userSubject) {
        synchronized (sessionLock(userSubject)) {
            Cache cache = cacheManager.getCache("sessions");
            // A failed login can leave the old passport stored, so retain ownership for logout cleanup.
            if (cache != null) {
                getSession(userSubject).ifPresent(session ->
                    cache.put(userSubject, new Session(session.id(), session.startedAt(), true)));
            }
        }
    }

    private Optional<Session> getSession(String userSubject) {
        if (userSubject == null || userSubject.isBlank()) {
            return Optional.empty();
        }
        Cache cache = cacheManager.getCache("sessions");
        if (cache != null) {
            Cache.ValueWrapper value = cache.get(userSubject);
            if (value != null && value.get() instanceof Session session) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    /** Missing, revoked, and timed-out sessions all reject session-bound authorization. */
    public boolean isSessionExpired(String userSubject) {
        return getSession(userSubject)
            .map(session -> session.revoked() || System.currentTimeMillis() - session.startedAt() > sessionMaxDuration)
            .orElse(true);
    }

    public boolean isTokenValidForCurrentSession(String userSubject, Object sessionId) {
        if (!(sessionId instanceof String id) || id.isBlank()) {
            return false;
        }
        return getSession(userSubject)
            .map(session -> !session.revoked() && id.equals(session.id())
                && System.currentTimeMillis() - session.startedAt() <= sessionMaxDuration)
            .orElse(false);
    }

    /**
     * Expiration does not prevent cleanup, but only the matching session can authorize it.
     * Failed cleanup requires an external logout retry; a successful new login replaces the revoked session.
     */
    public boolean endSessionIfCurrent(String userSubject, Object sessionId, Runnable cleanup) {
        if (userSubject == null || userSubject.isBlank() || !(sessionId instanceof String id) || id.isBlank()) {
            return false;
        }
        synchronized (sessionLock(userSubject)) {
            Optional<Session> current = getSession(userSubject).filter(session -> id.equals(session.id()));
            if (current.isEmpty()) {
                return false;
            }
            Cache cache = Objects.requireNonNull(cacheManager.getCache("sessions"), "Session cache is unavailable");
            Session session = current.get();
            // Retain ownership until cleanup succeeds; authentication rejects this marker throughout.
            cache.put(userSubject, new Session(session.id(), session.startedAt(), true));
            cleanup.run();
            cache.evict(userSubject);
            return true;
        }
    }
}
