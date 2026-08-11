package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code GET /token/refresh}. Replaces {@code Map.of("token", ..., "expirationDate", ...)} with the same two keys.
 */
@Schema(description = "A refreshed PIC-SURE token and its expiry")
public record TokenRefreshResponse(
    @Schema(description = "The refreshed PIC-SURE JWT") String token,
    @Schema(description = "ISO-8601 UTC instant at which the token expires") String expirationDate
) {
}
