package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * One privilege in the body of {@code POST /privilege}. The row identifier is generated on persist, and the owning application and any
 * access rules are resolved from storage by UUID.
 *
 * @param name the privilege name
 * @param description a free-text description
 * @param application the existing application the privilege belongs to
 * @param accessRules existing access rules to attach, by UUID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrivilegeCreateRequest(
    @NotBlank String name, String description, @Valid EntityIdRef application, @Valid Set<EntityIdRef> accessRules
) {
}
