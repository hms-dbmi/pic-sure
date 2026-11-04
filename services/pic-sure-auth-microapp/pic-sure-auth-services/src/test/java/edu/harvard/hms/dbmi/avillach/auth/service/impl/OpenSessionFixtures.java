package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * Builds an enabled {@link OpenSessionService} on a clock the test moves, for tests outside this package.
 */
public final class OpenSessionFixtures {

    public static final byte[] SESSION_KEY = "open-session-test-signing-key-32b".getBytes(StandardCharsets.UTF_8);
    public static final String SESSION_SECRET = Base64.getEncoder().encodeToString(SESSION_KEY);
    public static final String APPLICATION_SECRET = "application-test-client-secret-of-32-bytes-or-more";
    public static final long TTL_MINUTES = 15;
    public static final long MAX_LIFETIME_HOURS = 24;

    private OpenSessionFixtures() {}

    public static JWTUtil applicationJwtUtil() {
        return new JWTUtil(APPLICATION_SECRET, false);
    }

    public static OpenSessionService enabledService(Clock clock) {
        return new OpenSessionService(true, SESSION_SECRET, TTL_MINUTES, MAX_LIFETIME_HOURS, false, applicationJwtUtil(), clock);
    }

    public static OpenSessionService disabledService() {
        return new OpenSessionService(false, "", TTL_MINUTES, MAX_LIFETIME_HOURS, false, applicationJwtUtil(), Clock.systemUTC());
    }

    /** A clock that stands still until the test advances it. */
    public static final class MutableClock extends Clock {

        private Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
