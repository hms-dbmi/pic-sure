package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SessionService {

    private final long sessionMaxDuration;
    private final CacheManager cacheManager;

    public SessionService(@Value("${application.max.session.length}") long sessionMaxDuration, CacheManager cacheManager) {
        this.sessionMaxDuration = sessionMaxDuration > 0 ? sessionMaxDuration : 8 * 60 * 60 * 1000; // 8 hours in milliseconds
        this.cacheManager = cacheManager;
    }

    @CachePut(value = "sessions")
    public long startSession(String userSubject) {
        return System.currentTimeMillis();
    }

    @CacheEvict(value = "sessions")
    public void endSession(String userSubject) {
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

}
