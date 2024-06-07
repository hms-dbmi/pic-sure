package edu.harvard.hms.dbmi.avillach.auth.utils;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.apache.tomcat.util.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/**
 * <p>This class is for generating a JWT token and contains common methods for operations on JWT tokens.</p>
 * <p>For more information on JWT tokens, see <url><a href="https://github.com/hms-dbmi/jwt-creator/blob/master/src/main/java/edu/harvard/hms/dbmi/avillach/jwt/App.java">...</a><url/></p>
 */
@Component
public class JWTUtil {

    private final static Logger logger = LoggerFactory.getLogger(JWTUtil.class);

    private static final long defaultTTLMillis = 1000L * 60 * 60 * 24 * 7;

    private final String clientSecret;

    private final boolean clientSecretIsBase64;

    public JWTUtil(@Value("${application.client.secret}") String clientSecret,
                    @Value("${application.client.secret.base64}") boolean clientSecretIsBase64) {
        this.clientSecret = clientSecret;
        this.clientSecretIsBase64 = clientSecretIsBase64;
    }

    private String getDecodedClientSecret() {
        if (clientSecretIsBase64) {
            return new String(Base64.decodeBase64(clientSecret));
        }

        return clientSecret;
    }

    /**
     * @param id - id
     * @param issuer - issuer
     * @param claims - claims
     * @param subject - subject
     * @return JWT token
     */
    public String createJwtToken(String id, String issuer, Map<String, Object> claims, String subject, long ttlMillis) {
        logger.debug("createJwtToken() starting...");
        String jwt_token = null;

        if (ttlMillis < 0) {
            ttlMillis = defaultTTLMillis;
        }

        if (ttlMillis == 0) {
            ttlMillis = 999L * 1000 * 60 * 60 * 24;
        }

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        String clientSecret = getDecodedClientSecret();
        SecretKey signingKey = Keys.hmacShaKeyFor(clientSecret.getBytes(StandardCharsets.UTF_8));

        //Builds the JWT and serializes it to a compact, URL-safe string
        JwtBuilder builder = Jwts.builder()
                .claims(claims)
                .id(id)
                .issuedAt(now)
                .subject(subject)
                .issuer(issuer)
                .signWith(signingKey);

        //if it has been specified, let's add the expiration
        long expMillis = nowMillis + ttlMillis;
        Date exp = new Date(expMillis);
        builder.expiration(exp);
        jwt_token = builder.compact();

        return jwt_token;
    }

    public Jws<Claims> parseToken(String token) {
        String clientSecret = getDecodedClientSecret();
        SecretKey signingKey = Keys.hmacShaKeyFor(clientSecret.getBytes(StandardCharsets.UTF_8));

        Jws<Claims> jws;
        try {
            jws = Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            logger.error("parseToken() throws: {}, {}", e.getClass().getSimpleName(), e.getMessage());
            throw new NotAuthorizedException(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (jws == null) {
            logger.error("parseToken() get null for jws body by parsing Token - {}", token);
            throw new NotAuthorizedException("Please contact admin to see the log");
        }

        return jws;
    }

    public static Optional<String> getTokenFromAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }

        return Optional.of(authorizationHeader.substring("Bearer".length()).trim());
    }

    public String setClientSecret(String clientSecret) {
        return clientSecret;
    }

    public boolean setClientSecretIsBase64(boolean clientSecretIsBase64) {
        return clientSecretIsBase64;
    }


}
