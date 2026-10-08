package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService.SESSION_KEY_PREFIX;

/**
 * Issues, verifies, and refreshes open-access session tokens: stateless HS256 JWTs, sent as {@code picsure_s_<jwt>} in the
 * {@code X-PICSURE-API-Key} header. Nothing is stored. {@code sub} is the open-access session id, a random UUID kept across refreshes, and
 * {@code ses} is the session start, so no refresh reaches past {@code api.key.session.max.lifetime.hours}. A single session can't be
 * revoked; rotating {@code api.key.session.signing.secret} ends every session at once, and browsers silently start new ones.
 */
@Service
public class OpenSessionService {

    private static final Logger logger = LoggerFactory.getLogger(OpenSessionService.class);

    static final String ISSUER = "psama";
    static final String AUDIENCE = "picsure-open-session";
    static final String SESSION_START_CLAIM = "ses";
    private static final int MIN_SECRET_BYTES = 32;

    /** A freshly signed token. {@code token} is the full {@code picsure_s_} credential. */
    public record IssuedSession(String sessionId, String token, Instant expiresAt) {

        // the default record toString would embed a live credential, one accidental log statement away from a leak
        @Override
        public String toString() {
            return "IssuedSession[sessionId=%s, token=REDACTED, expiresAt=%s]".formatted(sessionId, expiresAt);
        }
    }

    /** The claims of a token that passed verification. */
    public record VerifiedSession(String sessionId, Instant sessionStart, Instant issuedAt, Instant expiresAt) {
    }

    private final boolean enabled;
    private final SecretKey signingKey;
    private final JwtParser parser;
    private final Duration ttl;
    private final Duration maxLifetime;
    private final Clock clock;

    @Autowired
    public OpenSessionService(
        @Value("${api.key.session.enabled}") boolean enabled, @Value("${api.key.session.signing.secret}") String signingSecret,
        @Value("${api.key.session.ttl.minutes}") long ttlMinutes, @Value("${api.key.session.max.lifetime.hours}") long maxLifetimeHours,
        @Value("${api.key.enforcement.enabled}") boolean enforcementEnabled, JWTUtil jwtUtil
    ) {
        this(enabled, signingSecret, ttlMinutes, maxLifetimeHours, enforcementEnabled, jwtUtil, Clock.systemUTC());
    }

    OpenSessionService(
        boolean enabled, String signingSecret, long ttlMinutes, long maxLifetimeHours, boolean enforcementEnabled, JWTUtil jwtUtil,
        Clock clock
    ) {
        this.enabled = enabled;
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.maxLifetime = Duration.ofHours(maxLifetimeHours);
        if (!enabled) {
            this.signingKey = null;
            this.parser = null;
            if (enforcementEnabled) {
                logger.warn(
                    "api.key.enforcement.enabled is true but api.key.session.enabled is false, so anonymous browsers cannot get a key and "
                        + "their open-access requests will be denied. That is right only if open access here is for partners holding "
                        + "USER or PLATFORM keys."
                );
            }
            return;
        }
        if (ttlMinutes <= 0) {
            throw new IllegalStateException("api.key.session.ttl.minutes must be positive, was " + ttlMinutes);
        }
        if (maxLifetimeHours * 60 < ttlMinutes) {
            throw new IllegalStateException(
                "api.key.session.max.lifetime.hours (" + maxLifetimeHours + ") must be at least api.key.session.ttl.minutes (" + ttlMinutes
                    + " minutes)"
            );
        }
        if (maxLifetime.compareTo(Duration.between(clock.instant(), Instant.MAX)) > 0) {
            throw new IllegalStateException(
                "api.key.session.max.lifetime.hours (" + maxLifetimeHours + ") reaches past the end of time, so no session could be issued"
            );
        }
        byte[] keyBytes = decodeSecret(signingSecret);
        if (jwtUtil.signsWith(keyBytes)) {
            throw new IllegalStateException(
                "api.key.session.signing.secret gives the same signing key as application.client.secret. Sessions need a key of their own, "
                    + "or a session token and a user token could each be presented as the other."
            );
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.parser = Jwts.parser().verifyWith(signingKey).requireIssuer(ISSUER).requireAudience(AUDIENCE)
            .clock(() -> Date.from(clock.instant())).build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Starts a new session with a new open-access session id. */
    public IssuedSession issue() {
        if (!enabled) {
            throw new IllegalStateException("Open-access sessions are not enabled");
        }
        Instant now = now();
        return mint(UUID.randomUUID().toString(), now, now);
    }

    /**
     * @param presented the full {@code picsure_s_} credential
     * @return the session, or empty if sessions are disabled or the token fails any check. Never logs the token.
     */
    public Optional<VerifiedSession> verify(String presented) {
        if (!enabled || presented == null || !presented.startsWith(SESSION_KEY_PREFIX)) {
            return Optional.empty();
        }
        Jws<Claims> jws;
        try {
            jws = parser.parseSignedClaims(presented.substring(SESSION_KEY_PREFIX.length()));
        } catch (JwtException | IllegalArgumentException e) {
            // the exception message can quote token content, so only its type is logged
            logger.debug("Rejected open-access session token: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
        // verifyWith would also accept HS384 or HS512 made with this key; only HS256 is ever issued
        if (!Jwts.SIG.HS256.getId().equals(jws.getHeader().getAlgorithm())) {
            return rejected("unexpected algorithm");
        }

        Claims claims = jws.getPayload();
        String sessionId = claims.getSubject();
        if (!isCanonicalUuid(sessionId)) {
            return rejected("sub is not a session id");
        }
        // JSON integers parse as Integer until 2038, then as Long
        Object sessionStartClaim = claims.get(SESSION_START_CLAIM);
        if (!(sessionStartClaim instanceof Integer || sessionStartClaim instanceof Long)) {
            return rejected("ses is missing or not an integer");
        }
        Instant sessionStart = Instant.ofEpochSecond(((Number) sessionStartClaim).longValue());
        if (claims.getIssuedAt() == null || claims.getExpiration() == null) {
            return rejected("iat or exp is missing");
        }
        Instant issuedAt = claims.getIssuedAt().toInstant();
        Instant expiresAt = claims.getExpiration().toInstant();
        // only a bad clock or a bug could mint these, but the cap is the one limit a refresh must never extend
        if (issuedAt.isBefore(sessionStart) || expiresAt.isAfter(sessionStart.plus(maxLifetime))) {
            return rejected("outside the session's lifetime");
        }
        return Optional.of(new VerifiedSession(sessionId, sessionStart, issuedAt, expiresAt));
    }

    /**
     * A replacement for a session token that has used at least half its lifetime, with the same session id and start. Empty before the
     * half-life, and at the cap, where a replacement would expire no later than the token it replaces.
     */
    public Optional<IssuedSession> refreshIfDue(VerifiedSession session) {
        Instant now = now();
        Instant halfLife = session.issuedAt().plus(Duration.between(session.issuedAt(), session.expiresAt()).dividedBy(2));
        if (now.isBefore(halfLife) || !expiryFor(session.sessionStart(), now).isAfter(session.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(mint(session.sessionId(), session.sessionStart(), now));
    }

    private IssuedSession mint(String sessionId, Instant sessionStart, Instant now) {
        Instant expiresAt = expiryFor(sessionStart, now);
        String jwt = Jwts.builder().issuer(ISSUER).audience().add(AUDIENCE).and().subject(sessionId).issuedAt(Date.from(now))
            .expiration(Date.from(expiresAt)).claim(SESSION_START_CLAIM, sessionStart.getEpochSecond()).signWith(signingKey, Jwts.SIG.HS256)
            .compact();
        return new IssuedSession(sessionId, SESSION_KEY_PREFIX + jwt, expiresAt);
    }

    private Instant expiryFor(Instant sessionStart, Instant now) {
        Instant byTtl = now.plus(ttl);
        Instant cap = sessionStart.plus(maxLifetime);
        return byTtl.isBefore(cap) ? byTtl : cap;
    }

    // JWT times are whole seconds; truncating here keeps the half-life and cap comparisons exact
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private static Optional<VerifiedSession> rejected(String reason) {
        logger.debug("Rejected open-access session token: {}", reason);
        return Optional.empty();
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] decodeSecret(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalStateException(
                "api.key.session.enabled is true but api.key.session.signing.secret is not set. Generate one with: openssl rand -base64 32"
            );
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(signingSecret.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "api.key.session.signing.secret is not valid Base64. Generate one with: openssl rand -base64 32"
            );
        }
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                "api.key.session.signing.secret decodes to " + keyBytes.length + " bytes; HS256 needs at least " + MIN_SECRET_BYTES
                    + ". Generate one with: openssl rand -base64 32"
            );
        }
        return keyBytes;
    }
}
