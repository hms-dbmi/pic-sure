package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * One role in the body of {@code POST /role}. The row identifier is generated on persist, and privileges are resolved from storage by UUID.
 *
 * @param name the role name
 * @param description a free-text description
 * @param privileges existing privileges the role grants, by UUID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoleCreateRequest(@NotBlank String name, String description, @Valid Set<EntityIdRef> privileges) {
}
