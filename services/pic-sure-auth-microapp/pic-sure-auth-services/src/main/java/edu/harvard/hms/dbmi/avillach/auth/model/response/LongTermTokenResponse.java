package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code GET /user/me/refresh_long_term_token}. Replaces {@code Map.of("userLongTermToken", ...)}; the key is unchanged.
 */
@Schema(description = "The current user's refreshed long-term token")
public record LongTermTokenResponse(@Schema(description = "The long-term token, prefixed LONG_TERM_TOKEN|") String userLongTermToken) {
}
