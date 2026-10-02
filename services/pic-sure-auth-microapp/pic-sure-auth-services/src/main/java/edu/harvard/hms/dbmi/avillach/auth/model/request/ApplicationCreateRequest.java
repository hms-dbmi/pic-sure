package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.Set;

/**
 * One application in the body of {@code POST /application}. The bearer {@code token} is absent: it is minted after the row is persisted and
 * can only be replaced through {@code GET /application/refreshToken/{applicationId}}.
 *
 * @param name the application name
 * @param description a free-text description
 * @param url the application's URL
 * @param enable whether the application is enabled; absent means enabled
 * @param privileges existing privileges to attach to the application, by UUID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationCreateRequest(
    @NotBlank String name, String description, String url, Boolean enable, @Valid Set<EntityIdRef> privileges
) {
}
