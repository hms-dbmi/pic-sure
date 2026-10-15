package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One mapping in the body of {@code PUT /mapping}. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the mapping to update
 * @param connection the existing connection the mapping should belong to
 * @param generalMetadataJsonPath the new JSON path into a user's general metadata
 * @param auth0MetadataJsonPath the new JSON path into the identity provider's metadata
 */
@Schema(description = "One user metadata mapping to update, named by UUID. A member left out keeps its stored value.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserMetadataMappingUpdateRequest(
    @Schema(
        description = "UUID of the mapping to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid,
    @Schema(
        description = "The existing connection whose users this mapping should match, named by its business identifier."
    ) @Valid ConnectionRef connection,
    @Schema(
        description = "New JSON path evaluated against the general metadata of the admin-created user.", example = "$.email"
    ) String generalMetadataJsonPath,
    @Schema(
        description = "New JSON path evaluated against the profile the identity provider returns at login.", example = "$.email"
    ) String auth0MetadataJsonPath
) {
}
