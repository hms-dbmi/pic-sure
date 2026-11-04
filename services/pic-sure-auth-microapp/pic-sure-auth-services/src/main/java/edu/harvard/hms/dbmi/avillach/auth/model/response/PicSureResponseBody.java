package edu.harvard.hms.dbmi.avillach.auth.model.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The envelope this service wraps some success bodies and every error body in.
 *
 * @param message a sentence describing the outcome
 * @param content the payload on success, or the error detail on failure
 * @param <T> the payload type
 */
@Schema(description = "An envelope carrying a message beside the payload. Both members are always present.")
public record PicSureResponseBody<T>(
    @Schema(
        description = "A sentence describing the outcome.", example = "All roles are added.", requiredMode = Schema.RequiredMode.REQUIRED
    ) String message,
    @Schema(
        description = "The payload on success, or the error detail on a failure. Null when there is nothing to add to the message.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) T content
) {
}
