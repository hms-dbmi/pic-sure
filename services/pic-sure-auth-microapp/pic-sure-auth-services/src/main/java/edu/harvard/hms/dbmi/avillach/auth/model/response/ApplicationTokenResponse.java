package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The body of {@code GET /application/refreshToken/{applicationId}}.
 *
 * @param token the application's new bearer token
 */
@Schema(description = "A newly issued application token. Issuing it invalidates the token the application held before.")
public record ApplicationTokenResponse(
    @Schema(
        description = "The bearer token the application authenticates with from now on.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJQU0FNQV9BUFBMSUNBVElPTnw4Njk0ZTNkNCJ9.c2lnbmF0dXJl",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String token
) {
}
