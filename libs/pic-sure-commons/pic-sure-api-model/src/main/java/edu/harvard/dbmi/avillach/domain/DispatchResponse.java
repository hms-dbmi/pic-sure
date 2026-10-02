package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body of {@code GET /internal/queries/{picsureId}/dispatch} on the operations service: the stored query body and nothing else.
 * The value is a JSON document carried as a string, never a nested object, and the key name is fixed as the endpoint's contract.
 */
@Schema(description = "The stored query body, served for a caller that authorizes a request against an existing query.")
public record DispatchResponse(
    @Schema(
        description = "The stored query request as a JSON string, with any resourceCredentials member removed. Null when the row holds no query or holds text that is not JSON.",
        example = "{\"query\":{\"expectedResultType\":\"COUNT\"}}"
    ) String queryJson
) {
}
