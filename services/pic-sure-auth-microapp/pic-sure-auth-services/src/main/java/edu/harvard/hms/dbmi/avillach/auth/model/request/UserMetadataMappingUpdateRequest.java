package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body for {@code PUT /mapping}. Absent members leave the stored value unchanged. */
public record UserMetadataMappingUpdateRequest(
    @NotNull UUID uuid, @Valid ConnectionRef connection, String generalMetadataJsonPath, String auth0MetadataJsonPath
) {
}
