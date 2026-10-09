package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One mapping in the body of {@code PATCH /mapping}. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the mapping to update
 * @param connection the existing connection the mapping should belong to
 * @param generalMetadataJsonPath the new JSON path into a user's general metadata
 * @param auth0MetadataJsonPath the new JSON path into the identity provider's metadata
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserMetadataMappingUpdateRequest(
    @NotNull UUID uuid, @Valid ConnectionRef connection, String generalMetadataJsonPath, String auth0MetadataJsonPath
) {
}
