package edu.harvard.dbmi.avillach.logging;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

public class TestJwtBuilder {

    private static final Algorithm ALGORITHM = Algorithm.HMAC256("test-secret");

    public static String buildToken(Map<String, Object> claims) {
        var builder = JWT.create();
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                builder.withClaim(key, s);
            } else if (value instanceof Boolean b) {
                builder.withClaim(key, b);
            } else if (value instanceof Integer i) {
                builder.withClaim(key, i);
            } else if (value instanceof Long l) {
                builder.withClaim(key, l);
            } else if (value instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<String> stringList = (List<String>) list;
                builder.withClaim(key, stringList);
            }
        }
        return builder.sign(ALGORITHM);
    }

    public static String buildExpiredToken(Map<String, Object> claims) {
        var builder = JWT.create()
            .withExpiresAt(Date.from(Instant.now().minusSeconds(3600)));
        for (Map.Entry<String, Object> entry : claims.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                builder.withClaim(key, s);
            } else if (value instanceof Boolean b) {
                builder.withClaim(key, b);
            } else if (value instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<String> stringList = (List<String>) list;
                builder.withClaim(key, stringList);
            }
        }
        return builder.sign(ALGORITHM);
    }
}
