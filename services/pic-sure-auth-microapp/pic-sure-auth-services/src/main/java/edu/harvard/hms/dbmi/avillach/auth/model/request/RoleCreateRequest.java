package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** Body for {@code POST /role}. Privileges are referenced by UUID; an unknown UUID is rejected. */
public record RoleCreateRequest(@NotBlank String name, String description, @Valid Set<EntityIdRef> privileges) {
}
