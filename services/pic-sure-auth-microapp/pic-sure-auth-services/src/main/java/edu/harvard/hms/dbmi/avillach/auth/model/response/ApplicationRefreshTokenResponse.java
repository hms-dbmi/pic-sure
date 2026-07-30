package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code GET /application/refreshToken/{applicationId}}.
 *
 * <p>The component is named {@code token}, not {@code refreshToken}: {@code Map.of("token", newApplicationToken)} is what the endpoint has
 * always emitted, and renaming the key here would break the admin UI for no gain. The endpoint name is the thing that is misleading -- it
 * mints a new long-lived application token rather than an OAuth refresh token.
 */
@Schema(description = "A newly minted application token")
public record ApplicationRefreshTokenResponse(@Schema(description = "The new application token") String token) {
}
