package edu.harvard.hms.dbmi.avillach.auth.model.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * Body for {@code POST /application}. The application's bearer {@code token} is absent: it is minted by
 * {@code ApplicationService#generateApplicationToken} after the row is persisted and can only be replaced through {@code GET
 * /application/refreshToken/{applicationId}}.
 */
public record ApplicationCreateRequest(
    @NotBlank String name, String description, String url, Boolean enable, @Valid Set<EntityIdRef> privileges
) {
}
