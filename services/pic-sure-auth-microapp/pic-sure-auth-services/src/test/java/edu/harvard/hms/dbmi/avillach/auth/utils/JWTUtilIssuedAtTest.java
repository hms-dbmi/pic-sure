package edu.harvard.hms.dbmi.avillach.auth.utils;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTUtilIssuedAtTest {

    private static final String SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";
    private final JWTUtil jwtUtil = new JWTUtil(SECRET, false);

    @Test
    void extractsTheVerifiedIssuedAtWithJwtSecondPrecision() {
        Date issuedAt = new Date(1_700_000_000_789L);

        assertEquals(Optional.of(new Date(1_700_000_000_000L)), jwtUtil.extractIssuedAt(signedToken(SECRET, issuedAt)));
    }

    @Test
    void returnsEmptyWhenTheTokenHasNoIssuedAt() {
        assertTrue(jwtUtil.extractIssuedAt(signedToken(SECRET, null)).isEmpty());
    }

    @Test
    void returnsEmptyWhenTheTokenIsMissing() {
        assertTrue(jwtUtil.extractIssuedAt(null).isEmpty());
    }

    @Test
    void returnsEmptyWhenTheTokenIsMalformed() {
        assertTrue(jwtUtil.extractIssuedAt("not-a-jwt").isEmpty());
    }

    @Test
    void returnsEmptyWhenTheSignatureIsInvalid() {
        String token = signedToken("an-entirely-different-secret-of-sufficient-length", new Date());

        assertTrue(jwtUtil.extractIssuedAt(token).isEmpty());
    }

    @Test
    void returnsEmptyWhenTheIssuedAtCannotBeConvertedToADate() {
        String token = Jwts.builder().content("{\"sub\":\"researcher-subject\",\"iat\":\"not-a-date\"}")
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();

        assertTrue(jwtUtil.extractIssuedAt(token).isEmpty());
    }

    private String signedToken(String secret, Date issuedAt) {
        return Jwts.builder().subject("researcher-subject").issuedAt(issuedAt)
            .expiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }
}
