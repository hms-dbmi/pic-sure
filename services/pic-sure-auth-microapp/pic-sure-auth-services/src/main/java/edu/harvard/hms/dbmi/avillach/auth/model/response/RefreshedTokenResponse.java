package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.model.ValidRefreshToken;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of a successful {@code GET /token/refresh}: a new session token and when it expires.
 *
 * @param token the new session token
 * @param expirationDate when it expires, as an ISO instant
 */
@Schema(description = "A refreshed session token and its expiry. Both members are always present.")
public record RefreshedTokenResponse(
    @Schema(
        description = "The new session token to send as the bearer token.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmZW5jZXwxMjM0NSJ9.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String token,
    @Schema(
        description = "When the new token expires, as an ISO instant.", example = "2026-09-30T14:05:00Z",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String expirationDate
) {

    /**
     * Copies a refresh the token service accepted into the response shape.
     *
     * @param refresh the accepted refresh
     * @return the response record
     */
    public static RefreshedTokenResponse from(ValidRefreshToken refresh) {
        return new RefreshedTokenResponse(refresh.token(), refresh.expirationDate());
    }
}
