package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

@Service
public class SessionService {

    private static final Logger logger = LoggerFactory.getLogger(SessionService.class);

    private final long sessionMaxDuration;
    private final CacheManager cacheManager;
    private final LoggingClient loggingClient;

    public SessionService(@Value("${application.max.session.length}") long sessionMaxDuration, CacheManager cacheManager,
                          LoggingClient loggingClient) {
        this.sessionMaxDuration = sessionMaxDuration > 0 ? sessionMaxDuration : 8 * 60 * 60 * 1000; // 8 hours in milliseconds
        this.cacheManager = cacheManager;
        this.loggingClient = loggingClient;
    }

    /**
     * @param tokenIssuedAt the {@code iat} of the token minted for this login. The session is anchored to it rather
     * than to the wall clock so that a token and its own session share an exact start, which lets
     * {@link #isTokenIssuedBeforeCurrentSession} compare them without a tolerance window. Null falls back to now.
     */
    @CachePut(value = "sessions", key = "#userSubject")
    public long startSession(String userSubject, Date tokenIssuedAt) {
        if (loggingClient != null && loggingClient.isEnabled()) {
            try {
                loggingClient.send(LoggingEvent.builder("AUTH").action("session.start")
                    .metadata(Map.of("user_subject", userSubject))
                    .build());
            } catch (Exception e) {
                logger.warn("Failed to send SESSION_START audit log event", e);
            }
        }
        return tokenIssuedAt != null ? tokenIssuedAt.getTime() : System.currentTimeMillis();
    }

    @CacheEvict(value = "sessions", key = "#userSubject")
    public void endSession(String userSubject) {
        // No audit logging here — endSession is called from evictCache() which fires on
        // logout, passport invalidation, and login flows. The callers log their own
        // domain-specific events (LOGOUT, PASSPORT_INVALIDATED, LOGIN_SUCCESS).
    }

    private Optional<Long> getCachedSessionStartTime(String userSubject) {
        Cache cache = cacheManager.getCache("sessions");
        if (cache != null) {
            Cache.ValueWrapper valueWrapper = cache.get(userSubject);
            if (valueWrapper != null) {
                return Optional.ofNullable((Long) valueWrapper.get());
            }
        }
        return Optional.empty();
    }

    /**
     * If the user has been logged in longer than the max session duration.
     * @param userSubject User::getSubject()
     * @return boolean if the session exists or the length of the current session
    */
    public boolean isSessionExpired(String userSubject) {
        Optional<Long> sessionStartTime = getCachedSessionStartTime(userSubject);
        return sessionStartTime.map(aLong -> System.currentTimeMillis() - aLong > sessionMaxDuration).orElse(true);
    }

    /**
     * Whether the token was minted before the subject's current session began, which means it belongs to a session
     * that has already ended. Ending a session only clears the subject's entry, so logging back in would otherwise
     * make every still-unexpired token from the previous session valid again.
     *
     * @param userSubject User::getSubject()
     * @param issuedAt the token's {@code iat} claim, or null if it carries none
     */
    public boolean isTokenIssuedBeforeCurrentSession(String userSubject, Date issuedAt) {
        if (issuedAt == null) {
            return false;
        }

        return getCachedSessionStartTime(userSubject).map(sessionStartTime -> issuedAt.getTime() < sessionStartTime).orElse(false);
    }

}
