package edu.harvard.hms.dbmi.avillach.auth.model.response;


/**
 * The body of {@code GET /user/me/refresh_long_term_token}. Replaces {@code Map.of("userLongTermToken", ...)}; the key is unchanged.
 */
public record LongTermTokenResponse(String userLongTermToken) {
}
