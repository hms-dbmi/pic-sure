package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/** Body for {@code PUT /role}. Absent members leave the stored value unchanged. */
public record RoleUpdateRequest(@NotNull UUID uuid, String name, String description, @Valid Set<EntityIdRef> privileges) {
}
