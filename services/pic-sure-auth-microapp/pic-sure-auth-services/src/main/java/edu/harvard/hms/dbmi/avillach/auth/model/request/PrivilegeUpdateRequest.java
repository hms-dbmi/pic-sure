package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/** Body for {@code PUT /privilege}. Absent members leave the stored value unchanged. */
public record PrivilegeUpdateRequest(
    @NotNull UUID uuid, String name, String description, @Valid EntityIdRef application, @Valid Set<EntityIdRef> accessRules
) {
}
