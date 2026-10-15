package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of a successful {@code GET /user/me/refresh_long_term_token}: the caller's new long-term token, which replaces the previous one.
 *
 * @param userLongTermToken the new long-term token
 */
@Schema(description = "A new long-term token for the caller. The previous long-term token stops working.")
public record LongTermTokenResponse(
    @Schema(
        description = "The new long-term token, for use from scripts and the adapters.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJMT05HX1RFUk1fVE9LRU58ZmVuY2V8MTIzNDUifQ.sflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String userLongTermToken
) {
}
