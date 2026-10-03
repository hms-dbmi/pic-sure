package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One user in the body of {@code PUT /user}. A member left out leaves the stored value unchanged. As on create, {@code subject},
 * {@code passport}, {@code token}, {@code acceptedTOS}, {@code matched} and {@code auth0metadata} are not members.
 *
 * @param uuid the UUID of the user to update
 * @param email the new email
 * @param active the new active flag
 * @param generalMetadata the new JSON object of profile metadata
 * @param connection the existing connection the user should sign in through
 * @param roles the existing roles the user should hold, by UUID; when present, at least one
 */
@Schema(
    description = "One user to update, named by UUID. A member left out keeps its stored value, and the subject, long-term token and passport cannot be set here."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserUpdateRequest(
    @Schema(
        description = "UUID of the user to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New email address of the user.", example = "researcher@example.org") String email,
    @Schema(description = "Whether the user may sign in.") Boolean active,
    @Schema(
        description = "New profile metadata of the user. The value is one string holding a JSON object.",
        example = "{\"email\":\"researcher@example.org\"}"
    ) String generalMetadata,
    @Schema(
        description = "The existing connection the user should sign in through, named by its business identifier."
    ) @Valid ConnectionRef connection,
    @Schema(
        description = "Existing roles the user should hold, each named by UUID. When present, it replaces the stored set and must name at least one role."
    ) @Valid Set<EntityIdRef> roles
) {
}
