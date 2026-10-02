package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One privilege in the body of {@code PUT /privilege}. A member left out leaves the stored value unchanged, so an update that does not name
 * access rules keeps the ones the privilege holds.
 *
 * @param uuid the UUID of the privilege to update
 * @param name the new name
 * @param description the new description
 * @param application the existing application the privilege should belong to
 * @param accessRules the existing access rules the privilege should hold, by UUID
 */
@Schema(description = "One privilege to update, named by UUID. A member left out keeps its stored value.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrivilegeUpdateRequest(
    @Schema(
        description = "UUID of the privilege to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New unique name of the privilege.", example = "PRIV_FENCE_phs000007_c1") String name,
    @Schema(
        description = "New free-text description of what the privilege grants.", example = "Access to phs000007 consent group c1"
    ) String description,
    @Schema(description = "The existing application the privilege should belong to, named by UUID.") @Valid EntityIdRef application,
    @Schema(
        description = "Existing access rules the privilege should hold, each named by UUID. When present, it replaces the stored set; when left out, the privilege keeps the rules it holds."
    ) @Valid Set<EntityIdRef> accessRules
) {
}
