package edu.harvard.hms.dbmi.avillach.operations.configuration;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create and update request body for {@code /configuration/admin/**}.
 *
 * <p>There is no {@code @NotNull} because {@link ConfigurationController} performs create-time null checks while PATCH treats every field
 * as optional. {@code uuid} is accepted only to enforce the "UUID cannot be changed" guard on PATCH.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(
    description = "The fields of a configuration entry to create or change. A create needs name, kind and value. An update changes only the fields that are present."
)
public record ConfigurationRequestDto(
    @Schema(
        description = "Read only on an update, where a value that differs from the id in the path is answered with 400. Ignored on a create.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) UUID uuid,
    @Schema(description = "The name of the entry, unique within its kind, at most 255 characters.", example = "enableGENEQuery") @Pattern(
        regexp = "^[\\w\\d\\-?\\[\\].():]+$"
    ) @Size(max = 255) String name,
    @Schema(
        description = "The group the entry belongs to, at most 255 characters.", example = "features"
    ) @Pattern(regexp = "^[\\w\\d\\-?\\[\\].():]+$") @Size(max = 255) String kind,
    @Schema(description = "The value of the entry as text.", example = "true") String value,
    @Schema(
        description = "What the entry controls, for administrators, at most 255 characters.",
        example = "Turns on the gene filter in the query builder."
    ) @Size(max = 255) String description, @Schema(description = "Whether to flag the entry for deletion.") Boolean markForDelete
) {
}
