package edu.harvard.hms.dbmi.avillach.auth.utils;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;

import javax.crypto.spec.SecretKeySpec;
import java.security.Key;
import java.util.Date;
import java.util.Map;

/**
 * <p>This class is for generating a JWT token and contains common methods for operations on JWT tokens.</p>
 * <p>For more information on JWT tokens, see <url>https://github.com/hms-dbmi/jwt-creator/blob/master/src/main/java/edu/harvard/hms/dbmi/avillach/jwt/App.java<url/></p>
 */
public class JWTUtil {

    private static final long defaultTTLMillis = 1000L * 60 * 60 * 24 * 7;

    /**
     *
     * @param clientSecret
     * @param id
     * @param issuer
     * @param claims
     * @param subject
     * @param ttlMillis
     * @return
     */
    public static String createJwtToken(String clientSecret, String id, String issuer, Map<String, Object> claims , String subject, long ttlMillis) {

        if (ttlMillis < 0)
            ttlMillis = defaultTTLMillis;

        if (ttlMillis == 0)
            ttlMillis = 999L * 1000 * 60 * 60 * 24;

        SignatureAlgorithm signatureAlgorithm = SignatureAlgorithm.HS256;

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        //We will sign our JWT with our ApiKey secret
        byte[] apiKeySecretBytes = clientSecret.getBytes();
        Key signingKey = new SecretKeySpec(apiKeySecretBytes, signatureAlgorithm.getJcaName());


        Jwts.builder().setClaims(claims);



        //Let's set the JWT Claims
        JwtBuilder builder = Jwts.builder()
                .setClaims(claims)
                .setId(id)
                .setIssuedAt(now)
                .setSubject(subject)
                .setIssuer(issuer)
                .signWith(signatureAlgorithm, signingKey);

        //if it has been specified, let's add the expiration
        if (ttlMillis >= 0) {
            long expMillis = nowMillis + ttlMillis;
            Date exp = new Date(expMillis);
            builder.setExpiration(exp);
        }

        //Builds the JWT and serializes it to a compact, URL-safe string
        return builder.compact();
    }
}
