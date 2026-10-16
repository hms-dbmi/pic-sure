package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * One role in the body of {@code POST /role}. The row identifier is generated on persist, and privileges are resolved from storage by UUID.
 *
 * @param name the role name
 * @param description a free-text description
 * @param privileges existing privileges the role grants, by UUID
 */
@Schema(description = "One role to create. The server generates its identifier.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleCreateRequest(
    @Schema(
        description = "Name of the role.", example = "PIC-SURE Top Admin", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String name,
    @Schema(
        description = "Free-text description of who holds the role.", example = "Manages users, roles and privileges"
    ) String description,
    @Schema(description = "Existing privileges the role grants, each named by UUID.") @Valid Set<EntityIdRef> privileges
) {
}
