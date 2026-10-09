package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * A reference to an existing connection by its business {@code id}, such as {@code fence}. Any other property of the referenced object,
 * such as the label or uuid the admin UI sends along, is ignored.
 *
 * @param id the business identifier of the referenced connection
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionRef(@NotBlank String id) {
}
