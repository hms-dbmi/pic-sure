package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One application in the body of {@code PUT /application}. {@code token} is absent, so an update cannot overwrite the application's bearer
 * token. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the application to update
 * @param name the new name
 * @param description the new description
 * @param url the new URL
 * @param enable the new enabled flag
 * @param privileges the existing privileges the application should hold, by UUID
 */
@Schema(
    description = "One application to update, named by UUID. A member left out keeps its stored value, and the bearer token cannot be set here."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationUpdateRequest(
    @Schema(
        description = "UUID of the application to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New unique name of the application.", example = "PICSURE") String name,
    @Schema(description = "New free-text description of the application.", example = "The PIC-SURE API application") String description,
    @Schema(description = "New URL the application is served from.", example = "/picsureui") String url,
    @Schema(
        description = "Whether the application is enabled. Stored and returned; nothing in the auth service enforces it."
    ) Boolean enable,
    @Schema(
        description = "Existing privileges the application should own, each named by UUID. When present, it replaces the stored set."
    ) @Valid Set<EntityIdRef> privileges
) {
}
