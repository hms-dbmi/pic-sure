package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserMetadataMappingCreateRequest(
    @NotNull @Valid ConnectionRef connection, @NotBlank String generalMetadataJsonPath, @NotBlank String auth0MetadataJsonPath
) {
}
