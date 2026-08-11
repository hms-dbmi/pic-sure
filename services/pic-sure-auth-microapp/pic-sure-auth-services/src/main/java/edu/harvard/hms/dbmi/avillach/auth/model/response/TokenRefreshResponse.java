package edu.harvard.hms.dbmi.avillach.auth.model.response;


/**
 * The body of {@code GET /token/refresh}. Replaces {@code Map.of("token", ..., "expirationDate", ...)} with the same two keys.
 */
public record TokenRefreshResponse(String token, String expirationDate) {
}
