package edu.harvard.dbmi.avillach.logging.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class JwtDecodeService {

    private static final Logger log = LoggerFactory.getLogger(JwtDecodeService.class);
    private static final int MAX_TOKEN_BYTES = 16_384; // 16KB

    private final Map<String, String> claimMapping;

    public JwtDecodeService(Map<String, String> claimMapping) {
        this.claimMapping = claimMapping;
    }

    public Map<String, Object> extractClaims(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Map.of("logged_in", false);
        }

        String token = authorizationHeader.strip();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).strip();
        }

        if (token.isBlank()) {
            return Map.of("logged_in", false);
        }

        if (token.length() > MAX_TOKEN_BYTES) {
            log.warn("JWT token exceeds maximum size of {} bytes (got {})", MAX_TOKEN_BYTES, token.length());
            return Map.of("logged_in", false);
        }

        try {
            DecodedJWT jwt = JWT.decode(token);
            Map<String, Object> result = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : claimMapping.entrySet()) {
                String claimName = entry.getKey();
                String outputField = entry.getValue();

                Claim claim = jwt.getClaim(claimName);
                if (claim.isMissing() || claim.isNull()) {
                    continue;
                }

                Object value = extractClaimValue(claimName, claim);
                if (value != null) {
                    result.put(outputField, value);
                }
            }

            // Always emit logged_in
            String loggedInOutputField = claimMapping.get("logged_in");
            if (loggedInOutputField != null && !result.containsKey(loggedInOutputField)) {
                result.put(loggedInOutputField, true);
            } else if (loggedInOutputField == null) {
                result.put("logged_in", true);
            }

            return result;
        } catch (Exception e) {
            log.warn("Failed to decode JWT: {}", e.getMessage());
            return Map.of("logged_in", false);
        }
    }

    private Object extractClaimValue(String claimName, Claim claim) {
        // roles → keep as List<String>
        if ("roles".equals(claimName)) {
            try {
                List<String> list = claim.asList(String.class);
                if (list != null) {
                    return list;
                }
            } catch (Exception ignored) {
                // fall through to string
            }
            return claim.asString();
        }

        // logged_in → preserve boolean
        if ("logged_in".equals(claimName)) {
            Boolean boolVal = claim.asBoolean();
            if (boolVal != null) {
                return boolVal;
            }
            return claim.asString();
        }

        // All others → string
        return claim.asString();
    }
}
