package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One connection in the body of {@code PUT /connection}. A member left out leaves the stored value unchanged.
 *
 * @param uuid the UUID of the connection to update
 * @param id the new business identifier
 * @param label the new display label
 * @param subPrefix the new subject prefix
 * @param requiredFields the new JSON array of required fields
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionUpdateRequest(@NotNull UUID uuid, String id, String label, String subPrefix, String requiredFields) {
}
