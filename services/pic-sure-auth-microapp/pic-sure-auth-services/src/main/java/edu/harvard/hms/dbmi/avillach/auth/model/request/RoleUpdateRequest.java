package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One role in the body of {@code PUT /role}. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the role to update
 * @param name the new name
 * @param description the new description
 * @param privileges the existing privileges the role should grant, by UUID
 */
@Schema(description = "One role to update, named by UUID. A member left out keeps its stored value.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleUpdateRequest(
    @Schema(
        description = "UUID of the role to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New name of the role.", example = "PIC-SURE Top Admin") String name,
    @Schema(
        description = "New free-text description of who holds the role.", example = "Manages users, roles and privileges"
    ) String description,
    @Schema(
        description = "Existing privileges the role should grant, each named by UUID. When present, it replaces the stored set."
    ) @Valid Set<EntityIdRef> privileges
) {
}
