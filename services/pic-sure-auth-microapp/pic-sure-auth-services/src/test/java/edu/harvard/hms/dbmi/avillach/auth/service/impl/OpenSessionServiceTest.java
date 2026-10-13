package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.MutableClock;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.IssuedSession;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.VerifiedSession;
import edu.harvard.hms.dbmi.avillach.auth.utils.JWTUtil;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService.OPEN_SESSION_KEY_PREFIX;
import static edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.APPLICATION_SECRET;
import static edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.SESSION_KEY;
import static edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.SESSION_SECRET;
import static edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.applicationJwtUtil;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OpenSessionServiceTest {

    private static final Instant START = Instant.parse("2026-09-28T12:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(OpenSessionFixtures.TTL_MINUTES);
    private static final Duration MAX_LIFETIME = Duration.ofHours(OpenSessionFixtures.MAX_LIFETIME_HOURS);
    private static final SecretKey SESSION_SIGNING_KEY = Keys.hmacShaKeyFor(SESSION_KEY);

    private MutableClock clock;
    private OpenSessionService service;
    private ListAppender<ILoggingEvent> logs;
    private Logger serviceLogger;
    private Level originalLevel;

    @BeforeEach
    public void setUp() {
        clock = new MutableClock(START);
        service = OpenSessionFixtures.enabledService(clock);
        serviceLogger = (Logger) LoggerFactory.getLogger(OpenSessionService.class);
        originalLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.DEBUG);
        logs = new ListAppender<>();
        logs.start();
        serviceLogger.addAppender(logs);
    }

    @AfterEach
    public void tearDown() {
        serviceLogger.detachAppender(logs);
        serviceLogger.setLevel(originalLevel);
    }

    /** A token with every claim valid for {@link #START}; tests break one thing at a time. */
    private static final class Forged {

        String issuer = OpenSessionService.ISSUER;
        String audience = OpenSessionService.AUDIENCE;
        String subject = UUID.randomUUID().toString();
        Object sessionStart = START.getEpochSecond();
        Instant issuedAt = START;
        Instant expiresAt = START.plus(TTL);
        SecretKey key = SESSION_SIGNING_KEY;
        MacAlgorithm algorithm = Jwts.SIG.HS256;

        String sign() {
            return OPEN_SESSION_KEY_PREFIX + claims(Jwts.builder()).signWith(key, algorithm).compact();
        }

        String unsigned() {
            return OPEN_SESSION_KEY_PREFIX + claims(Jwts.builder()).compact();
        }

        private JwtBuilder claims(JwtBuilder builder) {
            if (issuer != null) {
                builder.issuer(issuer);
            }
            if (audience != null) {
                builder.audience().add(audience).and();
            }
            if (subject != null) {
                builder.subject(subject);
            }
            if (sessionStart != null) {
                builder.claim(OpenSessionService.SESSION_START_CLAIM, sessionStart);
            }
            if (issuedAt != null) {
                builder.issuedAt(Date.from(issuedAt));
            }
            if (expiresAt != null) {
                builder.expiration(Date.from(expiresAt));
            }
            return builder;
        }
    }

    private void assertRejected(String token) {
        assertTrue(service.verify(token).isEmpty(), "expected the token to be rejected");
    }

    private VerifiedSession verified(String token) {
        return service.verify(token).orElseThrow(() -> new AssertionError("expected the token to verify"));
    }

    private static OpenSessionService enabled(String signingSecret, long ttlMinutes, long maxLifetimeHours, JWTUtil jwtUtil) {
        return new OpenSessionService(true, signingSecret, ttlMinutes, maxLifetimeHours, false, jwtUtil, new MutableClock(START));
    }

    @Test
    public void testIssuedTokenVerifiesWithItsSessionIdAndLifetime() {
        IssuedSession issued = service.issue();

        assertTrue(issued.token().startsWith(OPEN_SESSION_KEY_PREFIX));
        assertEquals(UUID.fromString(issued.sessionId()).toString(), issued.sessionId());
        assertEquals(START.plus(TTL), issued.expiresAt());
        VerifiedSession session = verified(issued.token());
        assertEquals(issued.sessionId(), session.sessionId());
        assertEquals(START, session.sessionStart());
        assertEquals(START, session.issuedAt());
        assertEquals(START.plus(TTL), session.expiresAt());
    }

    @Test
    public void testEachIssueStartsANewSession() {
        assertNotEquals(service.issue().sessionId(), service.issue().sessionId());
    }

    @Test
    public void testNoRefreshBeforeHalfLife() {
        VerifiedSession session = verified(service.issue().token());
        clock.advance(TTL.dividedBy(2).minusSeconds(1));

        assertTrue(service.refreshIfDue(session).isEmpty());
    }

    @Test
    public void testRefreshFromASubSecondClockExpiresWhenItsTokenSays() {
        VerifiedSession session = verified(service.issue().token());
        clock.advance(TTL.dividedBy(2).plusMillis(500));

        IssuedSession refreshed = service.refreshIfDue(session).orElseThrow();

        VerifiedSession claims = verified(refreshed.token());
        assertEquals(claims.expiresAt(), refreshed.expiresAt());
        assertEquals(START.plus(TTL.dividedBy(2)), claims.issuedAt());
    }

    @Test
    public void testRefreshAtHalfLifeKeepsSessionIdAndStart() {
        IssuedSession issued = service.issue();
        clock.advance(TTL.dividedBy(2));

        IssuedSession refreshed = service.refreshIfDue(verified(issued.token())).orElseThrow();

        assertEquals(issued.sessionId(), refreshed.sessionId());
        assertNotEquals(issued.token(), refreshed.token());
        VerifiedSession session = verified(refreshed.token());
        assertEquals(issued.sessionId(), session.sessionId());
        assertEquals(START, session.sessionStart());
        assertEquals(clock.instant(), session.issuedAt());
        assertEquals(clock.instant().plus(TTL), session.expiresAt());
    }

    @Test
    public void testRefreshesStopAtTheCapAndTheLastTokenExpiresThere() {
        IssuedSession current = service.issue();
        Instant cap = START.plus(MAX_LIFETIME);
        int refreshes = 0;
        // a browser active all day: one request every 8 minutes, each past the current token's half-life
        while (!current.expiresAt().equals(cap)) {
            clock.advance(Duration.ofMinutes(8));
            IssuedSession refreshed =
                service.refreshIfDue(verified(current.token())).orElseThrow(() -> new AssertionError("expected a refresh before the cap"));
            assertFalse(refreshed.expiresAt().isAfter(cap), "a refresh reached past the cap");
            assertEquals(current.sessionId(), refreshed.sessionId());
            current = refreshed;
            refreshes++;
        }

        assertTrue(refreshes > 100, "expected a day of refreshes, got " + refreshes);
        assertEquals(START, verified(current.token()).sessionStart());
        // past the capped token's half-life, still valid, but a replacement could not expire any later
        clock.advance(Duration.between(clock.instant(), cap).minusSeconds(1));
        assertTrue(service.refreshIfDue(verified(current.token())).isEmpty());
        clock.advance(Duration.ofSeconds(2));
        assertRejected(current.token());
    }

    @Test
    public void testExpiredTokenIsRejected() {
        String token = service.issue().token();
        clock.advance(TTL.plusSeconds(1));

        assertRejected(token);
    }

    @Test
    public void testWrongAudienceIsRejected() {
        Forged forged = new Forged();
        forged.audience = "picsure-something-else";
        assertRejected(forged.sign());
    }

    @Test
    public void testMissingAudienceIsRejected() {
        Forged forged = new Forged();
        forged.audience = null;
        assertRejected(forged.sign());
    }

    @Test
    public void testWrongIssuerIsRejected() {
        Forged forged = new Forged();
        forged.issuer = "not-psama";
        assertRejected(forged.sign());
    }

    @Test
    public void testMissingSubjectIsRejected() {
        Forged forged = new Forged();
        forged.subject = null;
        assertRejected(forged.sign());
    }

    @Test
    public void testSubjectThatIsNotACanonicalUuidIsRejected() {
        Forged notUuid = new Forged();
        notUuid.subject = "not-a-session-id";
        assertRejected(notUuid.sign());

        Forged upperCase = new Forged();
        upperCase.subject = UUID.randomUUID().toString().toUpperCase();
        assertRejected(upperCase.sign());
    }

    @Test
    public void testMissingSessionStartIsRejected() {
        Forged forged = new Forged();
        forged.sessionStart = null;
        assertRejected(forged.sign());
    }

    @Test
    public void testSessionStartThatIsNotAnIntegerIsRejected() {
        Forged text = new Forged();
        text.sessionStart = String.valueOf(START.getEpochSecond());
        assertRejected(text.sign());

        Forged fraction = new Forged();
        fraction.sessionStart = START.getEpochSecond() + 0.5;
        assertRejected(fraction.sign());
    }

    @Test
    public void testMissingIssuedAtOrExpirationIsRejected() {
        Forged noIssuedAt = new Forged();
        noIssuedAt.issuedAt = null;
        assertRejected(noIssuedAt.sign());

        Forged noExpiration = new Forged();
        noExpiration.expiresAt = null;
        assertRejected(noExpiration.sign());
    }

    @Test
    public void testExpirationBeyondTheCapIsRejected() {
        Forged forged = new Forged();
        forged.sessionStart = START.minus(MAX_LIFETIME).getEpochSecond();
        forged.expiresAt = START.plus(TTL);
        assertRejected(forged.sign());
    }

    @Test
    public void testIssuedBeforeTheSessionStartedIsRejected() {
        Forged forged = new Forged();
        forged.sessionStart = START.plusSeconds(60).getEpochSecond();
        assertRejected(forged.sign());
    }

    @Test
    public void testTokenSignedWithTheApplicationKeyIsRejected() {
        Forged forged = new Forged();
        forged.key = Keys.hmacShaKeyFor(APPLICATION_SECRET.getBytes(StandardCharsets.UTF_8));
        assertRejected(forged.sign());
    }

    @Test
    public void testUnsignedTokenIsRejected() {
        assertRejected(new Forged().unsigned());
    }

    // verifyWith alone would accept HS512 made with the session key
    @Test
    public void testHs512WithTheSessionKeyIsRejected() {
        byte[] longKey = new byte[64];
        Arrays.fill(longKey, (byte) 7);
        OpenSessionService longKeyService = new OpenSessionService(
            true, Base64.getEncoder().encodeToString(longKey), 15, 24, false, applicationJwtUtil(), new MutableClock(START)
        );
        Forged forged = new Forged();
        forged.key = Keys.hmacShaKeyFor(longKey);
        forged.algorithm = Jwts.SIG.HS512;

        assertTrue(longKeyService.verify(forged.sign()).isEmpty());
        forged.algorithm = Jwts.SIG.HS256;
        assertTrue(longKeyService.verify(forged.sign()).isPresent(), "the same claims under HS256 should verify");
    }

    @Test
    public void testNonJwtWithTheSessionPrefixIsRejected() {
        assertRejected(OPEN_SESSION_KEY_PREFIX + "not-a-jwt");
        assertRejected(OPEN_SESSION_KEY_PREFIX);
    }

    @Test
    public void testJwtWithoutTheSessionPrefixIsRejected() {
        String token = service.issue().token();

        assertRejected(token.substring(OPEN_SESSION_KEY_PREFIX.length()));
        assertRejected(null);
    }

    @Test
    public void testUserTokenPresentedAsASessionIsRejected() {
        String userToken = applicationJwtUtil().createJwtToken(null, "edu.harvard.hms.dbmi.psama", Map.of(), "user-subject", 60_000);

        assertRejected(OPEN_SESSION_KEY_PREFIX + userToken);
    }

    @Test
    public void testSessionTokenFailsApplicationTokenParsing() {
        String jwt = service.issue().token().substring(OPEN_SESSION_KEY_PREFIX.length());

        assertThrows(NotAuthorizedException.class, () -> applicationJwtUtil().parseToken(jwt));
    }

    @Test
    public void testRotatingTheSecretEndsExistingSessions() {
        String token = service.issue().token();
        OpenSessionService rotated = enabled(Base64.getEncoder().encodeToString(new byte[32]), 15, 24, applicationJwtUtil());

        assertTrue(rotated.verify(token).isEmpty());
    }

    @Test
    public void testDisabledServiceVerifiesNothingAndIssuesNothing() {
        String token = service.issue().token();
        OpenSessionService disabled = new OpenSessionService(false, "", 0, 0, false, applicationJwtUtil(), clock);

        assertFalse(disabled.isEnabled());
        assertTrue(disabled.verify(token).isEmpty());
        assertThrows(IllegalStateException.class, disabled::issue);
    }

    @Test
    public void testStartupFailsWithoutASecret() {
        IllegalStateException blank = assertThrows(IllegalStateException.class, () -> enabled(" ", 15, 24, applicationJwtUtil()));
        assertTrue(blank.getMessage().contains("api.key.session.signing.secret"), blank.getMessage());
        assertThrows(IllegalStateException.class, () -> enabled(null, 15, 24, applicationJwtUtil()));
    }

    @Test
    public void testStartupFailsOnASecretThatIsNotBase64() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> enabled("not base64!", 15, 24, applicationJwtUtil()));
        assertTrue(e.getMessage().contains("Base64"), e.getMessage());
        assertFalse(e.getMessage().contains("not base64!"), "the message must not echo the secret: " + e.getMessage());
    }

    @Test
    public void testStartupFailsOnASecretShorterThan32Bytes() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[31]);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> enabled(shortSecret, 15, 24, applicationJwtUtil()));
        assertTrue(e.getMessage().contains("31 bytes"), e.getMessage());
        assertDoesNotThrow(() -> enabled(Base64.getEncoder().encodeToString(new byte[32]), 15, 24, applicationJwtUtil()));
    }

    @Test
    public void testStartupFailsWhenTheSecretGivesTheApplicationKey() {
        String sameKey = Base64.getEncoder().encodeToString(APPLICATION_SECRET.getBytes(StandardCharsets.UTF_8));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> enabled(sameKey, 15, 24, applicationJwtUtil()));
        assertTrue(e.getMessage().contains("application.client.secret"), e.getMessage());
    }

    // application.client.secret holds the Base64 form of the key: the strings differ from the key, the key bytes are the same
    @Test
    public void testStartupFailsWhenTheSecretGivesTheBase64DecodedApplicationKey() {
        byte[] applicationKey = "an-application-key-configured-in-base64-form".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(applicationKey);
        JWTUtil base64JwtUtil = new JWTUtil(encoded, true);

        assertThrows(IllegalStateException.class, () -> enabled(encoded, 15, 24, base64JwtUtil));
        assertDoesNotThrow(() -> enabled(encoded, 15, 24, new JWTUtil(encoded, false)));
    }

    @Test
    public void testStartupFailsOnANonPositiveTtl() {
        assertThrows(IllegalStateException.class, () -> enabled(SESSION_SECRET, 0, 24, applicationJwtUtil()));
        assertThrows(IllegalStateException.class, () -> enabled(SESSION_SECRET, -5, 24, applicationJwtUtil()));
    }

    @Test
    public void testStartupFailsWhenTheMaxLifetimeIsShorterThanTheTtl() {
        assertThrows(IllegalStateException.class, () -> enabled(SESSION_SECRET, 61, 1, applicationJwtUtil()));
        assertThrows(IllegalStateException.class, () -> enabled(SESSION_SECRET, 15, 0, applicationJwtUtil()));
        assertDoesNotThrow(() -> enabled(SESSION_SECRET, 60, 1, applicationJwtUtil()));
    }

    // a lifetime Duration can hold but Instant cannot would otherwise fail on the first issue(), not at startup
    @Test
    public void testStartupFailsWhenTheMaxLifetimeOutlivesInstant() {
        long hoursBeyondInstantMax = Long.MAX_VALUE / 7200;

        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> enabled(SESSION_SECRET, 15, hoursBeyondInstantMax, applicationJwtUtil()));
        assertTrue(e.getMessage().contains("max.lifetime.hours"), e.getMessage());
    }

    @Test
    public void testDisabledSessionsSkipTheStartupChecks() {
        assertDoesNotThrow(() -> new OpenSessionService(false, "", 0, 0, false, applicationJwtUtil(), clock));
    }

    @Test
    public void testWarnsWhenEnforcementIsOnAndSessionsAreOff() {
        new OpenSessionService(false, "", 15, 24, true, applicationJwtUtil(), clock);

        assertTrue(
            logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("api.key.session.enabled"))
        );
    }

    @Test
    public void testDoesNotWarnOtherwise() {
        new OpenSessionService(false, "", 15, 24, false, applicationJwtUtil(), clock);
        new OpenSessionService(true, SESSION_SECRET, 15, 24, true, applicationJwtUtil(), clock);

        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.WARN));
    }

    @Test
    public void testRejectionsNeverLogTheToken() {
        List<String> tokens = new ArrayList<>();
        String valid = service.issue().token();
        tokens.add(valid);
        Forged wrongAudience = new Forged();
        wrongAudience.audience = "elsewhere";
        tokens.add(wrongAudience.sign());
        Forged unsignedToken = new Forged();
        tokens.add(unsignedToken.unsigned());
        tokens.add(OPEN_SESSION_KEY_PREFIX + "not-a-jwt");
        tokens.add(valid.substring(0, valid.length() - 4) + "AAAA");

        clock.advance(TTL.plusSeconds(1));
        tokens.forEach(service::verify);

        assertFalse(logs.list.isEmpty(), "rejections should be logged at debug");
        for (ILoggingEvent event : logs.list) {
            for (String token : tokens) {
                // the whole token, or its signature segment on its own
                String jwt = token.substring(OPEN_SESSION_KEY_PREFIX.length());
                String signature = jwt.substring(jwt.lastIndexOf('.') + 1);
                assertFalse(event.getFormattedMessage().contains(jwt), "a log line contains a token: " + event.getFormattedMessage());
                if (!signature.isEmpty()) {
                    assertFalse(
                        event.getFormattedMessage().contains(signature), "a log line contains a signature: " + event.getFormattedMessage()
                    );
                }
            }
        }
    }

    @Test
    public void testIssuedSessionToStringRedactsTheToken() {
        IssuedSession issued = service.issue();

        assertFalse(issued.toString().contains(issued.token().substring(OPEN_SESSION_KEY_PREFIX.length())));
        assertTrue(issued.toString().contains(issued.sessionId()));
    }
}
