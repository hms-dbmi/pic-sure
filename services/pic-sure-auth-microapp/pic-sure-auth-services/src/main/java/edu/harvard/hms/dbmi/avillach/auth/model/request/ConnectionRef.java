package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * A reference to an existing connection by its business {@code id}, such as {@code fence}. Any other property of the referenced object,
 * such as the label or uuid the admin UI sends along, is ignored.
 *
 * @param id the business identifier of the referenced connection
 */
@Schema(description = "A reference to an existing connection by its business identifier. Any other member sent along with it is ignored.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionRef(
    @Schema(
        description = "Business identifier of the referenced connection.", example = "fence", requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotBlank String id
) {
}
