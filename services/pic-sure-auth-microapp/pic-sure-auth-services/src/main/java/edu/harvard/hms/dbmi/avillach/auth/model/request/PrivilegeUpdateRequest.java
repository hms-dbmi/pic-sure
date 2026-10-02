package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrivilegeUpdateRequest(
    @NotNull UUID uuid, String name, String description, @Valid EntityIdRef application, @Valid Set<EntityIdRef> accessRules
) {
}
