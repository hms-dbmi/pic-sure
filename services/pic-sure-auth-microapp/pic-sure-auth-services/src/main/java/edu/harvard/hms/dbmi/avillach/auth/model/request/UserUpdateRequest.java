package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One user in the body of {@code PATCH /user}. A member left out leaves the stored value unchanged. As on create, {@code subject},
 * {@code passport}, {@code token}, {@code acceptedTOS}, {@code matched} and {@code auth0metadata} are not members.
 *
 * @param uuid the UUID of the user to update
 * @param email the new email
 * @param active the new active flag
 * @param generalMetadata the new JSON object of profile metadata
 * @param connection the existing connection the user should sign in through
 * @param roles the existing roles the user should hold, by UUID; when present, at least one
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserUpdateRequest(
    @NotNull UUID uuid, String email, Boolean active, String generalMetadata, @Valid ConnectionRef connection, @Valid Set<EntityIdRef> roles
) {
}
