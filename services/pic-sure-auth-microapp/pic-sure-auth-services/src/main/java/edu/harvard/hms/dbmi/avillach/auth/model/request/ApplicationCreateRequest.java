package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(
    description = "One application to create. The server generates its identifier and its bearer token, and the response does not carry the token."
)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApplicationCreateRequest(
    @Schema(
        description = "Unique name of the application.", example = "PICSURE", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String name,
    @Schema(description = "Free-text description of the application.", example = "The PIC-SURE API application") String description,
    @Schema(description = "URL the application is served from.", example = "/picsureui") String url,
    @Schema(description = "Whether the application may authenticate. Absent means true.") Boolean enable,
    @Schema(description = "Existing privileges to attach to the application, each named by UUID.") @Valid Set<EntityIdRef> privileges
) {
}
