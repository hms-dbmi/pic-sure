package edu.harvard.hms.dbmi.avillach.auth.model.response;

import edu.harvard.hms.dbmi.avillach.auth.entity.UserMetadataMapping;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * A user metadata mapping as the mapping endpoints return it. Every member is always written.
 *
 * @param uuid the row identifier
 * @param connection the connection the mapping belongs to
 * @param generalMetadataJsonPath the JSON path into a user's general metadata
 * @param auth0MetadataJsonPath the matching JSON path into the identity provider's metadata
 */
@Schema(
    description = "A rule that matches an admin-created user to an identity provider profile by comparing one value from each. Every "
        + "member is always present."
)
public record UserMetadataMappingResponse(
    @Schema(
        description = "Row identifier of the mapping.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The connection whose users this mapping matches.", requiredMode = Schema.RequiredMode.REQUIRED
    ) ConnectionResponse connection,
    @Schema(
        description = "JSON path evaluated against the general metadata of the admin-created user.", example = "$.email",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String generalMetadataJsonPath,
    @Schema(
        description = "JSON path evaluated against the profile the identity provider returns at login.", example = "$.email",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) String auth0MetadataJsonPath
) {

    /**
     * Copies a persisted mapping into its response shape.
     *
     * <p>A {@code null} mapping yields {@code null}.</p>
     *
     * @param mapping the persisted mapping, or {@code null}
     * @return the response record, or {@code null} when {@code mapping} is {@code null}
     */
    public static UserMetadataMappingResponse from(UserMetadataMapping mapping) {
        if (mapping == null) {
            return null;
        }
        return new UserMetadataMappingResponse(
            mapping.getUuid(), ConnectionResponse.from(mapping.getConnection()), mapping.getGeneralMetadataJsonPath(),
            mapping.getAuth0MetadataJsonPath()
        );
    }

    /**
     * Copies a collection of persisted mappings in its iteration order.
     *
     * <p>A {@code null} collection yields {@code null}.</p>
     *
     * @param mappings the persisted mappings, or {@code null}
     * @return the response records in the same order, or {@code null} when {@code mappings} is {@code null}
     */
    public static List<UserMetadataMappingResponse> fromAll(Collection<UserMetadataMapping> mappings) {
        if (mappings == null) {
            return null;
        }
        return mappings.stream().map(UserMetadataMappingResponse::from).toList();
    }
}
