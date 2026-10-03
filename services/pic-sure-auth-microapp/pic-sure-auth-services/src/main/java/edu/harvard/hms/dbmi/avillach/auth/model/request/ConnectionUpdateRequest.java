package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "One identity provider connection to update, named by UUID. A member left out keeps its stored value.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConnectionUpdateRequest(
    @Schema(
        description = "UUID of the connection to update.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid, @Schema(description = "New business identifier of the connection.", example = "fence") String id,
    @Schema(description = "New label shown for the connection on the login and admin screens.", example = "FENCE") String label,
    @Schema(
        description = "New prefix of the subject of every user who signs in through this connection.", example = "fence|"
    ) String subPrefix,
    @Schema(
        description = "New set of fields a user record on this connection must carry. The value is one string holding a JSON array.",
        example = "[{\"label\":\"Email\",\"id\":\"email\"}]"
    ) String requiredFields
) {
}
