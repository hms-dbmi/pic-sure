package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Set;
import java.util.UUID;

/**
 * One application in the body of {@code PATCH /application}. {@code token} is absent, so an update cannot overwrite the application's bearer
 * token. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the application to update
 * @param name the new name
 * @param description the new description
 * @param url the new URL
 * @param enable the new enabled flag
 * @param privileges the existing privileges the application should hold, by UUID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationUpdateRequest(
    @NotNull UUID uuid, String name, String description, String url, Boolean enable, @Valid Set<EntityIdRef> privileges
) {
}
