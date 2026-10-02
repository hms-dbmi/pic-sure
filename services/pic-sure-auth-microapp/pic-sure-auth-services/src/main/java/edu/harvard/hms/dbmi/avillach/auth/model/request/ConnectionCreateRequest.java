package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * One connection in the body of {@code POST /connection}. The row identifier is generated on persist, so a create cannot target an existing
 * connection.
 *
 * @param id the connection's business identifier, such as {@code fence}; it must not already exist
 * @param label the display label
 * @param subPrefix the prefix of the subjects of users who sign in through this connection
 * @param requiredFields a JSON array of the fields a user record on this connection must carry
 */
@Schema(description = "One identity provider connection to create. The server generates its row identifier.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionCreateRequest(
    @Schema(
        description = "Business identifier of the connection. It must not already exist.", example = "fence",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String id,
    @Schema(
        description = "Label shown for the connection on the login and admin screens.", example = "FENCE",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String label,
    @Schema(
        description = "Prefix of the subject of every user who signs in through this connection.", example = "fence|",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String subPrefix,
    @Schema(
        description = "The fields a user record on this connection must carry. The value is one string holding a JSON array.",
        example = "[{\"label\":\"Email\",\"id\":\"email\"}]", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String requiredFields
) {
}
