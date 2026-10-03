package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * One privilege in the body of {@code POST /privilege}. The row identifier is generated on persist, and the owning application and any
 * access rules are resolved from storage by UUID.
 *
 * @param name the privilege name
 * @param description a free-text description
 * @param application the existing application the privilege belongs to
 * @param accessRules existing access rules to attach, by UUID
 */
@Schema(description = "One privilege to create. The server generates its identifier.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrivilegeCreateRequest(
    @Schema(
        description = "Unique name of the privilege.", example = "PRIV_FENCE_phs000007_c1", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String name,
    @Schema(
        description = "Free-text description of what the privilege grants.", example = "Access to phs000007 consent group c1"
    ) String description,
    @Schema(description = "The existing application the privilege belongs to, named by UUID.") @Valid EntityIdRef application,
    @Schema(description = "Existing access rules to attach to the privilege, each named by UUID.") @Valid Set<EntityIdRef> accessRules
) {
}
