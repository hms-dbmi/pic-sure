package edu.harvard.hms.dbmi.avillach.auth.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.NotAuthorizedException;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.Ga4ghPassportV1;
import edu.harvard.hms.dbmi.avillach.auth.model.ras.Passport;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.apache.tomcat.util.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.io.IOException;
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

    private final static ObjectMapper objectMapper = new ObjectMapper();

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
     * @param id      - id
     * @param issuer  - issuer
     * @param claims  - claims
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
            return Optional.ofNullable(authorizationHeader);
        }

        return Optional.of(authorizationHeader.substring("Bearer".length()).trim());
    }

    public static boolean isLongTermToken(String sub) {
        return sub.startsWith(AuthNaming.LONG_TERM_TOKEN_PREFIX);
    }

    public String setClientSecret(String clientSecret) {
        return clientSecret;
    }

    public boolean setClientSecretIsBase64(boolean clientSecretIsBase64) {
        return clientSecretIsBase64;
    }

    public boolean shouldRefreshToken(Date expiration, long tokenExpirationTime) {
        long currentTime = System.currentTimeMillis();
        if (currentTime >= expiration.getTime()) {
            return false;
        }

        long halfExpirationTime = tokenExpirationTime / 2;
        long refreshTime = expiration.getTime() - halfExpirationTime;
        return currentTime >= refreshTime;
    }

    /**
     * Extract the payload from the ga4gh token and convert it to a Ga4ghPassportV1
     * @param ga4ghToken The ga4gh_passport_v1 extracted from the Passport
     * @return Optional of a Ga4ghPassportV1. If empty we could not successfully extract the claims and map them to
     * the class.
     */
    public static Optional<Ga4ghPassportV1> parseGa4ghPassportV1(String ga4ghToken) {
        try {
            String[] passports = ga4ghToken.split("\\.");
            String base64EncodedPayload = passports[1];
            java.util.Base64.Decoder decoder = java.util.Base64.getDecoder();
            String payload = new String(decoder.decode(base64EncodedPayload));
            Ga4ghPassportV1 ga4ghPassportV1 = objectMapper.readValue(payload, Ga4ghPassportV1.class);
            return Optional.ofNullable(ga4ghPassportV1);
        } catch (IOException e) {
            logger.error("parsePassport() throws: {}, {}", e.getClass().getSimpleName(), e.getMessage());
        }

        return Optional.empty();
    }

    public static Optional<Passport> parsePassportJWTV11(String passportJWTV11) {
        try {
            String[] passports = passportJWTV11.split("\\.");
            String base64EncodedPayload = passports[1];
            java.util.Base64.Decoder decoder = java.util.Base64.getDecoder();
            String payload = new String(decoder.decode(base64EncodedPayload));
            Passport passport = objectMapper.readValue(payload, Passport.class);
            return Optional.ofNullable(passport);
        } catch (IOException e) {
            logger.error("parsePassport() throws: {}, {}", e.getClass().getSimpleName(), e.getMessage());
        }

        return Optional.empty();
    }

    public static Optional<String> decodePassport(String passportJWTV11) {
        try {
            String[] passports = passportJWTV11.split("\\.");
            String base64EncodedPayload = passports[1];
            java.util.Base64.Decoder decoder = java.util.Base64.getDecoder();
            String payload = new String(decoder.decode(base64EncodedPayload));
            return Optional.of(payload);
        } catch (Exception e) {
            logger.error("decodePassport() throws: {}, {}", e.getClass().getSimpleName(), e.getMessage());
        }

        return Optional.empty();
    }

}
