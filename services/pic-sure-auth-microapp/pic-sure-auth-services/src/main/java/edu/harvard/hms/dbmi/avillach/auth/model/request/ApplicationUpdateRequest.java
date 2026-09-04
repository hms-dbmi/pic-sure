package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Body for {@code PUT /application}. {@code token} is absent, so an update can neither read nor overwrite the application's bearer token.
 */
public record ApplicationUpdateRequest(
    @NotNull UUID uuid, String name, String description, String url, Boolean enable, @Valid Set<EntityIdRef> privileges
) {
}
