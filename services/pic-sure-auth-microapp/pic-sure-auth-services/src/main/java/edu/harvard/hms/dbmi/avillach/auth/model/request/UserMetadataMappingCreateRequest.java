package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One mapping in the body of {@code POST /mapping}. The row identifier is generated on persist.
 *
 * @param connection the existing connection the mapping belongs to
 * @param generalMetadataJsonPath the JSON path into a user's general metadata
 * @param auth0MetadataJsonPath the matching JSON path into the identity provider's metadata
 */
@Schema(description = "One user metadata mapping to create. The server generates its identifier.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserMetadataMappingCreateRequest(
    @Schema(
        description = "The existing connection whose users this mapping matches, named by its business identifier.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull @Valid ConnectionRef connection,
    @Schema(
        description = "JSON path evaluated against the general metadata of the admin-created user.", example = "$.email",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String generalMetadataJsonPath,
    @Schema(
        description = "JSON path evaluated against the profile the identity provider returns at login.", example = "$.email",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String auth0MetadataJsonPath
) {
}
