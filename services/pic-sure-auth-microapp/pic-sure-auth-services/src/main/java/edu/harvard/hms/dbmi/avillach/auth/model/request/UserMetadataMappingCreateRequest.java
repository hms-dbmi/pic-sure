package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Body for {@code POST /mapping}. The connection is referenced by its business id and must already exist. */
public record UserMetadataMappingCreateRequest(
    @NotNull @Valid ConnectionRef connection, @NotBlank String generalMetadataJsonPath, @NotBlank String auth0MetadataJsonPath
) {
}
