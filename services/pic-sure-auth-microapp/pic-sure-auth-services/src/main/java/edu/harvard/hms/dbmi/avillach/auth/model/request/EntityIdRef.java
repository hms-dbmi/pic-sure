package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A reference to an existing row by its UUID. Association members of the request records use this so a request can only point at a
 * persisted row; the service resolves the row and attaches the managed entity, never the caller's copy of it. Any other property of the
 * referenced object, such as the name or nested privileges the admin UI sends along, is ignored.
 *
 * @param uuid the UUID of the referenced row
 */
@Schema(description = "A reference to an existing row by its UUID. Any other member sent along with it is ignored.")
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityIdRef(
    @Schema(
        description = "UUID of the referenced row.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) @NotNull UUID uuid
) {
}
