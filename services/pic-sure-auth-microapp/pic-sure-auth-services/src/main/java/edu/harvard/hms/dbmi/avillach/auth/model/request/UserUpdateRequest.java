package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * Body for {@code PUT /user}. Absent members leave the stored value unchanged. As with {@link UserCreateRequest}, {@code subject},
 * {@code passport}, {@code token}, {@code acceptedTOS}, {@code matched}, and {@code auth0metadata} cannot be reached from a request body.
 */
public record UserUpdateRequest(
    @NotNull UUID uuid, String email, Boolean active, String generalMetadata, @Valid ConnectionRef connection, @Valid Set<EntityIdRef> roles
) {
}
