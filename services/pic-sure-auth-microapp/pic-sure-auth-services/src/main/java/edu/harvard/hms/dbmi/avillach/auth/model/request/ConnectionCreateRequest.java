package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * One connection in the body of {@code POST /connection}. The row identifier is generated on persist, so a create cannot target an existing
 * connection.
 *
 * @param id the connection's business identifier, such as {@code fence}; it must not already exist
 * @param label the display label
 * @param subPrefix the prefix of the subjects of users who sign in through this connection
 * @param requiredFields a JSON array of the fields a user record on this connection must carry
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionCreateRequest(
    @NotBlank String id, @NotBlank String label, @NotBlank String subPrefix, @NotBlank String requiredFields
) {
}
