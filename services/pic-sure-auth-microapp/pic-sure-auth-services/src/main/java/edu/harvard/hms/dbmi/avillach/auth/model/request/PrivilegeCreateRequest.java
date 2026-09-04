package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/** Body for {@code POST /privilege}. The owning application and any access rules are referenced by UUID and resolved server-side. */
public record PrivilegeCreateRequest(
    @NotBlank String name, String description, @Valid EntityIdRef application, @Valid Set<EntityIdRef> accessRules
) {
}
