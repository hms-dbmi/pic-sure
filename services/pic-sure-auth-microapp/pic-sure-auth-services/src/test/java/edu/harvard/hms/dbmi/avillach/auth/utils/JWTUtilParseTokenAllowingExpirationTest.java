package edu.harvard.hms.dbmi.avillach.auth.utils;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logout has to work from an expired token (ALS-12756): the token TTL is much shorter than the session it belongs
 * to, so the token a user logs out with has often idled past its expiration while their session is still live.
 */
class JWTUtilParseTokenAllowingExpirationTest {

    private static final String CLIENT_SECRET = "a-test-client-secret-long-enough-for-hmac-sha-256";

    private final JWTUtil jwtUtil = new JWTUtil(CLIENT_SECRET, false);

    @Test
    void returnsTheClaimsOfAnExpiredButCorrectlySignedToken() {
        String token = signedToken(CLIENT_SECRET, new Date(System.currentTimeMillis() - 60_000));

        Optional<Claims> claims = jwtUtil.parseTokenAllowingExpiration(token);

        assertTrue(claims.isPresent());
        assertEquals("admin-subject", claims.get().getSubject());
    }

    @Test
    void stillRejectsTheSameExpiredTokenForAuthentication() {
        String token = signedToken(CLIENT_SECRET, new Date(System.currentTimeMillis() - 60_000));

        assertThrows(NotAuthorizedException.class, () -> jwtUtil.parseToken(token));
    }

    @Test
    void returnsEmptyForATokenSignedWithTheWrongSecret() {
        String token = signedToken("an-entirely-different-secret-of-sufficient-length", new Date(System.currentTimeMillis() + 60_000));

        assertTrue(jwtUtil.parseTokenAllowingExpiration(token).isEmpty());
    }

    @Test
    void returnsEmptyForAMalformedToken() {
        assertTrue(jwtUtil.parseTokenAllowingExpiration("not-a-jwt").isEmpty());
    }

    private String signedToken(String secret, Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder().subject("admin-subject").issuedAt(new Date(System.currentTimeMillis() - 120_000)).expiration(expiration)
            .signWith(key).compact();
    }
}
