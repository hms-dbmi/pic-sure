package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One role in the body of {@code PATCH /role}. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the role to update
 * @param name the new name
 * @param description the new description
 * @param privileges the existing privileges the role should grant, by UUID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleUpdateRequest(@NotNull UUID uuid, String name, String description, @Valid Set<EntityIdRef> privileges) {
}
